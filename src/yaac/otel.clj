(ns yaac.otel
  "OTLP Collector - receives OpenTelemetry logs and traces over HTTP(S).
   Uses zeph NIO server for both HTTP and HTTPS.
   Supports both JSON and protobuf content types."
  (:require [zeph.server :as zeph]
            [clojure.string :as str]
            [jsonista.core :as json]
            [clojure.tools.cli :refer [parse-opts]])
  (:import [java.io InputStream ByteArrayOutputStream FileWriter BufferedWriter]
           [java.time Instant ZoneId]
           [java.time.format DateTimeFormatter]
           [io.opentelemetry.proto.collector.logs.v1 ExportLogsServiceRequest]
           [io.opentelemetry.proto.collector.trace.v1 ExportTraceServiceRequest]
           [io.opentelemetry.proto.common.v1 AnyValue KeyValue]
           [io.opentelemetry.proto.logs.v1 LogRecord SeverityNumber]
           [io.opentelemetry.proto.trace.v1 Span Status$StatusCode Span$SpanKind]))

(def version "0.4.0")

;; --- ANSI colors ---
(def ^:private RESET  "\u001b[0m")
(def ^:private BOLD   "\u001b[1m")
(def ^:private FAINT  "\u001b[2m")
(def ^:private RED    "\u001b[31m")
(def ^:private GREEN  "\u001b[32m")
(def ^:private YELLOW "\u001b[33m")
(def ^:private BLUE   "\u001b[34m")
(def ^:private CYAN   "\u001b[36m")
(def ^:private GRAY   "\u001b[90m")

;; --- Output directory state ---
(def ^:private output-dir (atom nil))
(def ^:private writers (atom {}))  ;; {"app/type" -> BufferedWriter}
(def ^:private strip-ansi? (atom false))  ;; strip ANSI for file output

;; --- App name resolution ---
;; CH2 Mule Runtime sends service.name="mule-container" for all apps.
;; We learn the real app name from log messages like "+ New app 'xxx'"
;; and map service.instance.id -> app-name for subsequent logs.
;; When -O is used, pre-learning logs go under instance-id dir,
;; then renamed to app-name dir on learning.
(def ^:private instance-app-map (atom {}))  ;; {service.instance.id -> app-name}

(def ^:private console-formatter
  (-> (DateTimeFormatter/ofPattern "HH:mm:ss.SSS")
      (.withZone (ZoneId/systemDefault))))

(def ^:private file-formatter
  (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.SSS")
      (.withZone (ZoneId/systemDefault))))

(defn- now-str []
  (.format ^DateTimeFormatter console-formatter ^java.time.temporal.TemporalAccessor (Instant/now)))

(defn- format-ts
  "Format nanosecond timestamp to string."
  [^long nanos ^DateTimeFormatter fmt]
  (if (pos? nanos)
    (try (.format fmt ^java.time.temporal.TemporalAccessor
           (Instant/ofEpochSecond (quot nanos 1000000000)
                                   (rem nanos 1000000000)))
         (catch Exception _ "??:??:??.???"))
    (now-str)))

(defn- strip-ansi
  "Remove ANSI escape sequences from string."
  [^String s]
  (str/replace s #"\u001b\[[0-9;]*m" ""))

(defn- get-writer
  "Get or create a BufferedWriter for app/type combination."
  ^BufferedWriter [^String app ^String type]
  (let [k (str app "/" type)]
    (or (get @writers k)
        (let [dir (java.io.File. ^String @output-dir app)]
          (.mkdirs dir)
          (let [f (java.io.File. dir (str type ".log"))
                w (BufferedWriter. (FileWriter. f true))]
            (swap! writers assoc k w)
            w)))))

(defn- write-line
  "Write a line to console and optionally to file."
  [^String app ^String type ^String line]
  (println line)
  (when @output-dir
    (let [^BufferedWriter w (get-writer (or app "unknown") type)
          ^String file-line (strip-ansi line)]
      (.write w file-line)
      (.newLine w)
      (.flush w))))

(defn- rename-app-dir!
  "Rename output directory from old-name to new-name and migrate writers."
  [^String old-name ^String new-name]
  (when @output-dir
    ;; Close writers for old name
    (doseq [type ["logs" "traces"]]
      (let [k (str old-name "/" type)]
        (when-let [^BufferedWriter w (get @writers k)]
          (try (.close w) (catch Exception _))
          (swap! writers dissoc k))))
    ;; Rename directory
    (let [old-dir (java.io.File. ^String @output-dir old-name)
          new-dir (java.io.File. ^String @output-dir new-name)]
      (when (.exists old-dir)
        (if (.exists new-dir)
          ;; Target exists: append old files to new files
          (doseq [^java.io.File f (.listFiles old-dir)]
            (let [target (java.io.File. new-dir (.getName f))]
              (with-open [in (java.io.FileReader. f)
                          out (FileWriter. target true)]
                (let [buf (char-array 4096)]
                  (loop []
                    (let [n (.read in buf)]
                      (when (pos? n)
                        (.write out buf 0 n)
                        (recur))))))
              (.delete f))
            (.delete old-dir))
          (.renameTo old-dir new-dir))))))

(defn- close-writers []
  (doseq [[_ ^BufferedWriter w] @writers]
    (try (.close w) (catch Exception _)))
  (reset! writers {}))

(defn- read-body
  "Read request body from Ring :body (InputStream)."
  [req]
  (when-let [^InputStream body (:body req)]
    (let [buf (ByteArrayOutputStream. 4096)
          tmp (byte-array 4096)]
      (loop []
        (let [n (.read body tmp)]
          (when (pos? n)
            (.write buf tmp 0 n)
            (recur))))
      (.toByteArray buf))))

;; --- Common formatting ---

(defn- severity-name [n]
  (case (int (or n 0))
    (1 2 3 4) "TRACE"
    (5 6 7 8) "DEBUG"
    (9 10 11 12) "INFO"
    (13 14 15 16) "WARN"
    (17 18 19 20) "ERROR"
    (21 22 23 24) "FATAL"
    "?"))

(defn- severity-color [n]
  (case (int (or n 0))
    (1 2 3 4) GRAY
    (5 6 7 8) GRAY
    (9 10 11 12) GREEN
    (13 14 15 16) YELLOW
    (17 18 19 20) RED
    (21 22 23 24) (str RED BOLD)
    FAINT))

(defn- format-duration [duration-ns]
  (when duration-ns
    (cond
      (< duration-ns 1000000) (format "%.1fus" (/ duration-ns 1000.0))
      (< duration-ns 1000000000) (format "%.1fms" (/ duration-ns 1000000.0))
      :else (format "%.2fs" (/ duration-ns 1000000000.0)))))

;; --- JSON log/trace formatting ---

(defn- json-kv-str
  "Format key-value pairs from OTLP JSON attributes."
  [attrs]
  (when (seq attrs)
    (str/join " "
      (map (fn [{:keys [key value]}]
             (let [v (or (:stringValue value)
                         (:intValue value)
                         (:boolValue value)
                         (str value))]
               (str GRAY key "=" RESET v)))
           attrs))))

(defn- format-json-log [record resource-attrs]
  (let [sev-num (:severityNumber record 9)
        sev-text (or (:severityText record) (severity-name sev-num))
        color (severity-color sev-num)
        body-val (get-in record [:body :stringValue] "")
        ts (:timeUnixNano record)
        time-str (if ts
                   (format-ts (if (string? ts) (parse-long ts) ts) console-formatter)
                   (now-str))
        attrs (:attributes record)
        service-name (some (fn [{:keys [key value]}]
                             (when (= key "service.name") (:stringValue value)))
                           resource-attrs)
        flow-name (some (fn [{:keys [key value]}]
                          (when (= key "mule.flow.name") (:stringValue value)))
                        attrs)]
    {:app service-name
     :line (str GRAY time-str " "
                color (format "%-5s" sev-text) RESET " "
                (when service-name (str CYAN service-name RESET " "))
                (when flow-name (str BLUE flow-name RESET " "))
                body-val
                (when-let [extra (json-kv-str (remove #(#{"mule.flow.name"} (:key %)) attrs))]
                  (str " " extra)))}))

(defn- handle-json-logs [data]
  (doseq [rl (:resourceLogs data)]
    (let [resource-attrs (get-in rl [:resource :attributes] [])]
      (doseq [sl (:scopeLogs rl)]
        (doseq [record (:logRecords sl)]
          (let [{:keys [app line]} (format-json-log record resource-attrs)]
            (write-line app "logs" line)))))))

(defn- format-json-span [span resource-attrs]
  (let [name (:name span "?")
        status-code (get-in span [:status :code] 0)
        status-str (case (int status-code)
                     0 "" 1 (str GREEN "OK" RESET) 2 (str RED "ERROR" RESET) (str status-code))
        start-ns (some-> (:startTimeUnixNano span) str parse-long)
        end-ns (some-> (:endTimeUnixNano span) str parse-long)
        duration-ns (when (and start-ns end-ns) (- end-ns start-ns))
        duration-str (format-duration duration-ns)
        service-name (some (fn [{:keys [key value]}]
                             (when (= key "service.name") (:stringValue value)))
                           resource-attrs)
        kind-num (int (:kind span 0))
        kind (case kind-num
               0 "" 1 (str GRAY "INTERNAL" RESET) 2 (str BLUE "SERVER" RESET)
               3 (str YELLOW "CLIENT" RESET) 4 (str CYAN "PRODUCER" RESET)
               5 (str CYAN "CONSUMER" RESET) "")]
    {:app service-name
     :line (str GRAY (now-str) " "
                BOLD "SPAN" RESET " "
                (when service-name (str CYAN service-name RESET " "))
                name " "
                (when (seq kind) (str kind " "))
                (when duration-str (str FAINT duration-str RESET " "))
                (when (seq status-str) (str status-str " "))
                (when-let [extra (json-kv-str (:attributes span))]
                  extra))}))

(defn- handle-json-traces [data]
  (doseq [rt (:resourceSpans data)]
    (let [resource-attrs (get-in rt [:resource :attributes] [])]
      (doseq [ss (:scopeSpans rt)]
        (doseq [span (:spans ss)]
          (let [{:keys [app line]} (format-json-span span resource-attrs)]
            (write-line app "traces" line)))))))

;; --- Protobuf log/trace formatting ---

(defn- anyvalue->str
  "Extract string representation from protobuf AnyValue."
  [^AnyValue av]
  (case (.name (.getValueCase av))
    "STRING_VALUE" (.getStringValue av)
    "INT_VALUE" (str (.getIntValue av))
    "DOUBLE_VALUE" (str (.getDoubleValue av))
    "BOOL_VALUE" (str (.getBoolValue av))
    (str av)))

(defn- pb-kv-str
  "Format protobuf KeyValue list for display."
  [attrs]
  (when (seq attrs)
    (str/join " "
      (map (fn [^KeyValue kv]
             (str GRAY (.getKey kv) "=" RESET (anyvalue->str (.getValue kv))))
           attrs))))

(defn- pb-find-attr
  "Find attribute value by key in protobuf KeyValue list."
  [attrs ^String key]
  (some (fn [^KeyValue kv]
          (when (= key (.getKey kv))
            (anyvalue->str (.getValue kv))))
        attrs))

(defn- format-pb-log-record
  "Format a protobuf LogRecord for terminal display."
  [^LogRecord record resource-attrs]
  (let [sev-num (.getNumber (.getSeverityNumber record))
        sev-text (let [t (.getSeverityText record)]
                   (if (seq t) t (severity-name sev-num)))
        color (severity-color sev-num)
        body-val (if (.hasBody record) (anyvalue->str (.getBody record)) "")
        ts (.getTimeUnixNano record)
        time-str (format-ts ts console-formatter)
        attrs (.getAttributesList record)
        instance-id (pb-find-attr resource-attrs "service.instance.id")
        raw-service (pb-find-attr resource-attrs "service.name")
        ;; Learn app name from "+ New app 'xxx'" log messages
        _ (when (and instance-id (re-find #"\+ New app '([^']+)'" body-val))
            (let [app-name (second (re-find #"\+ New app '([^']+)'" body-val))
                  old-name (get @instance-app-map instance-id)]
              ;; If first time learning (was using instance-id as dir name), rename
              (when (and (nil? old-name) @output-dir)
                (rename-app-dir! instance-id app-name))
              (swap! instance-app-map assoc instance-id app-name)))
        ;; Resolve: learned app name > instance-id (for dir separation) > service.name
        service-name (or (when instance-id (get @instance-app-map instance-id))
                         instance-id
                         raw-service)
        flow-name (pb-find-attr attrs "mule.flow.name")]
    {:app service-name
     :line (str GRAY time-str " "
                color (format "%-5s" sev-text) RESET " "
                (when service-name (str CYAN service-name RESET " "))
                (when flow-name (str BLUE flow-name RESET " "))
                body-val
                (when-let [extra (pb-kv-str (remove #(= "mule.flow.name" (.getKey ^KeyValue %)) attrs))]
                  (str " " extra)))}))

(defn- handle-pb-logs
  "Parse and pretty-print protobuf OTLP log export."
  [^bytes body]
  (let [req (ExportLogsServiceRequest/parseFrom body)]
    (doseq [rl (.getResourceLogsList req)]
      (let [resource-attrs (when (.hasResource rl)
                             (.getAttributesList (.getResource rl)))]
        (doseq [sl (.getScopeLogsList rl)]
          (doseq [record (.getLogRecordsList sl)]
            (let [{:keys [app line]} (format-pb-log-record record resource-attrs)]
              (write-line app "logs" line))))))))

(defn- format-pb-span
  "Format a protobuf Span for terminal display."
  [^Span span resource-attrs]
  (let [name (.getName span)
        status-code (if (.hasStatus span)
                      (.getNumber (.getCode (.getStatus span)))
                      0)
        status-str (case (int status-code)
                     0 "" 1 (str GREEN "OK" RESET) 2 (str RED "ERROR" RESET) (str status-code))
        start-ns (.getStartTimeUnixNano span)
        end-ns (.getEndTimeUnixNano span)
        duration-ns (when (and (pos? start-ns) (pos? end-ns)) (- end-ns start-ns))
        duration-str (format-duration duration-ns)
        instance-id (pb-find-attr resource-attrs "service.instance.id")
        raw-service (pb-find-attr resource-attrs "service.name")
        service-name (or (when instance-id (get @instance-app-map instance-id))
                         instance-id
                         raw-service)
        kind-num (.getNumber (.getKind span))
        kind (case (int kind-num)
               0 "" 1 (str GRAY "INTERNAL" RESET) 2 (str BLUE "SERVER" RESET)
               3 (str YELLOW "CLIENT" RESET) 4 (str CYAN "PRODUCER" RESET)
               5 (str CYAN "CONSUMER" RESET) "")]
    {:app service-name
     :line (str GRAY (now-str) " "
                BOLD "SPAN" RESET " "
                (when service-name (str CYAN service-name RESET " "))
                name " "
                (when (seq kind) (str kind " "))
                (when duration-str (str FAINT duration-str RESET " "))
                (when (seq status-str) (str status-str " "))
                (when-let [extra (pb-kv-str (.getAttributesList span))]
                  extra))}))

(defn- handle-pb-traces
  "Parse and pretty-print protobuf OTLP trace export."
  [^bytes body]
  (let [req (ExportTraceServiceRequest/parseFrom body)]
    (doseq [rt (.getResourceSpansList req)]
      (let [resource-attrs (when (.hasResource rt)
                             (.getAttributesList (.getResource rt)))]
        (doseq [ss (.getScopeSpansList rt)]
          (doseq [span (.getSpansList ss)]
            (let [{:keys [app line]} (format-pb-span span resource-attrs)]
              (write-line app "traces" line))))))))

;; --- Ring handler ---

(defn- handle-otlp [req]
  (let [body-bytes (read-body req)
        uri (:uri req)
        content-type (get-in req [:headers "content-type"] "")]
    (when body-bytes
      (try
        (if (str/includes? content-type "json")
          ;; JSON content
          (let [data (json/read-value body-bytes json/keyword-keys-object-mapper)]
            (cond
              (str/includes? uri "/logs") (handle-json-logs data)
              (str/includes? uri "/traces") (handle-json-traces data)))
          ;; Protobuf content (default for CH2 Mule Runtime)
          (cond
            (str/includes? uri "/logs") (handle-pb-logs body-bytes)
            (str/includes? uri "/traces") (handle-pb-traces body-bytes)))
        (catch Exception e
          (println (str RED (now-str) " Error processing OTLP: " (.getMessage e) RESET)))))
    (flush)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body "{\"partialSuccess\":{}}"}))

(defn- otel-handler [req]
  (let [method (:request-method req)
        uri (:uri req)]
    (cond
      ;; Health check
      (and (= method :get)
           (or (= uri "/") (= uri "/health")))
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body "{\"status\":\"ok\"}"}

      ;; OTLP endpoints
      (and (= method :post)
           (or (str/includes? uri "/v1/logs")
               (str/includes? uri "/v1/traces")))
      (handle-otlp req)

      :else
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "Not Found"})))

;; --- CLI ---

(def cli-options
  [["-O" "--output-dir DIR" "Write logs/traces to per-app files in DIR"]
   [nil "--cert CERT" "Path to certificate PEM file (enables SSL)"]
   [nil "--key KEY" "Path to private key PEM file"]
   ["-h" "--help" "Show help"]])

(defn usage [summary]
  (->> ["Usage: yaac otel <port> [options]"
        ""
        "Start an OTLP collector that receives OpenTelemetry logs and traces."
        "Supports both JSON and protobuf (default for CH2 Mule Runtime)."
        ""
        "Options:"
        ""
        summary
        ""
        "Output directory (-O):"
        "  When specified, creates per-app files under the directory:"
        "    <dir>/<service.name>/logs.log"
        "    <dir>/<service.name>/traces.log"
        "  ANSI colors are stripped from file output."
        ""
        "Example:"
        ""
        "  # Console only"
        "  yaac otel 9180"
        ""
        "  # Console + per-app files"
        "  yaac otel 9180 -O /tmp/otel"
        ""
        "  # HTTPS mode (auto-enabled when --cert is provided)"
        "  sudo yaac otel 9180 --cert /etc/letsencrypt/live/example.com/fullchain.pem --key /etc/letsencrypt/live/example.com/privkey.pem"
        ""]
       (str/join \newline)))

(defn start-collector [{:keys [port ssl? cert key out-dir]}]
  (let [opts (cond-> {:port port
                      :ip "0.0.0.0"
                      :thread 2
                      :idle-timeout 0}
               ssl? (assoc :ssl? true :cert cert :key key))]
    (println (str BOLD "OTLP Collector" RESET " v" version))
    (println (str "  Listen: " (if ssl? "https" "http") "://0.0.0.0:" port))
    (println (str "  POST /v1/logs   - receive logs"))
    (println (str "  POST /v1/traces - receive traces"))
    (println (str "  GET  /health    - health check"))
    (when out-dir
      (println (str "  Output dir: " out-dir))
      (reset! output-dir out-dir))
    (println)
    (let [stop-fn (zeph/run-server otel-handler opts)]
      (println (str GREEN "Ready." RESET " Waiting for OTLP data..."))
      (println)
      ;; Block until interrupted
      (let [latch (java.util.concurrent.CountDownLatch. 1)]
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. (fn []
                                     (println (str "\n" FAINT "Shutting down..." RESET))
                                     (close-writers)
                                     (stop-fn)
                                     (.countDown latch))))
        (.await latch)))))

(defn cli [& args]
  (let [{:keys [options arguments summary errors]}
        (parse-opts (map name args) cli-options)]
    (when (:help options)
      (println (usage summary))
      (System/exit 0))
    (when errors
      (doseq [e errors] (println e))
      (System/exit 1))
    (let [port (or (some-> (first arguments) parse-long)
                   9180)
          cert (:cert options)]
      (start-collector {:port (int port)
                        :ssl? (boolean cert)
                        :cert cert
                        :key (:key options)
                        :out-dir (:output-dir options)}))))
