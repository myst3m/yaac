(ns yaac.proto.mcp
  "MCP (Model Context Protocol) client implementation.
   Supports Streamable HTTP transport (2025-03-26 spec)."
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [jsonista.core :as json]
            [zeph.client :as http])
  (:import [java.nio.file Path Paths Files LinkOption]
           [java.nio.file.attribute FileAttribute]))

;; Inlined NIO helpers (from silvur.nio)
(defn- nio-path ^Path [s] (Paths/get (str s) (into-array String [])))
(defn- nio-exists? [p] (Files/exists (nio-path p) (into-array LinkOption [])))
(defn- nio-mkdir [p] (Files/createDirectories (nio-path p) (into-array FileAttribute [])))
(defn- nio-rm [p] (Files/deleteIfExists (nio-path p)))

(def session-file (str (System/getProperty "user.home") "/.silvur/mcp-session.edn"))
(def tools-cache-file (str (System/getProperty "user.home") "/.silvur/mcp-tools-cache"))

(def ^:dynamic *trace* false)

;; ANSI colors
(def ^:private colors
  {:reset      "\u001b[0m"
   :bold       "\u001b[1m"
   :dim        "\u001b[2m"
   :cyan       "\u001b[36m"
   :green      "\u001b[32m"
   :green-bold "\u001b[1;32m"
   :yellow     "\u001b[33m"
   :blue       "\u001b[34m"
   :magenta    "\u001b[35m"
   :gray       "\u001b[90m"
   :white      "\u001b[37m"})

(defn- colorize [color & strs]
  (str (colors color) (apply str strs) (:reset colors)))

(defn- ensure-session-dir! []
  (nio-mkdir (str (.getParent ^Path (nio-path session-file)))))

(defn- load-session []
  (when (nio-exists? session-file)
    (read-string (slurp session-file))))

(defn- save-session! [session-id url server-info]
  (ensure-session-dir!)
  (spit session-file (pr-str {:session-id session-id
                              :url url
                              :server-info server-info})))

(defn- clear-session! []
  (ensure-session-dir!)
  (when (nio-exists? session-file)
    (nio-rm session-file)))

(defn- cache-tools! [tools]
  (ensure-session-dir!)
  (spit tools-cache-file (str/join "\n" (map :name tools))))

(defn- current-session
  "Get the current session or nil."
  []
  (load-session))

;; JSON-RPC helpers
(defn- make-request [method params id]
  {:jsonrpc "2.0"
   :id id
   :method method
   :params (or params {})})

(defn- make-notification [method params]
  {:jsonrpc "2.0"
   :method method
   :params (or params {})})

(defn- parse-sse
  "Parse SSE response body. Returns first message data."
  [body]
  (let [lines (str/split-lines body)]
    (some (fn [line]
            (when (str/starts-with? line "data: ")
              (subs line 6)))
          lines)))

;; Streamable HTTP client
(defn request!
  "Send JSON-RPC request via Streamable HTTP.
   Returns {:result ... :session-id ...}"
  [url method params & {:keys [session-id]}]
  (let [req-id (rand-int 100000)
        body (json/write-value-as-string (make-request method params req-id))
        headers (cond-> {"Content-Type" "application/json"
                         "Accept" "application/json, text/event-stream"}
                  session-id (assoc "Mcp-Session-Id" session-id))
        resp @(http/request (cond-> {:method :post
                                     :url url
                                     :headers headers
                                     :body body}
                              *trace* (assoc :trace-detail true)))]
    (if (>= (:status resp) 400)
      (throw (ex-info (str "HTTP Error: " (:status resp))
                      {:status (:status resp) :body (:body resp)}))
      (let [;; Headers may be lowercase or mixed case
            hdrs (:headers resp)
            new-session-id (or (get hdrs "mcp-session-id")
                               (get hdrs "Mcp-Session-Id")
                               (some (fn [[k v]] (when (= (str/lower-case k) "mcp-session-id") v)) hdrs))
            content-type (or (get hdrs "content-type")
                             (get hdrs "Content-Type")
                             (some (fn [[k v]] (when (= (str/lower-case k) "content-type") v)) hdrs))
            ;; Parse body based on content-type
            body-str (:body resp)
            json-str (if (and content-type (str/includes? content-type "text/event-stream"))
                       (parse-sse body-str)
                       body-str)
            result (when json-str
                     (json/read-value json-str json/keyword-keys-object-mapper))]
        {:result (:result result)
         :error (:error result)
         :session-id (or new-session-id session-id)}))))

(defn notify!
  "Send JSON-RPC notification via Streamable HTTP."
  [url method params session-id]
  (let [body (json/write-value-as-string (make-notification method params))
        headers {"Content-Type" "application/json"
                 "Accept" "application/json, text/event-stream"
                 "Mcp-Session-Id" session-id}
        resp @(http/request (cond-> {:method :post
                                     :url url
                                     :headers headers
                                     :body body}
                              *trace* (assoc :trace-detail true)))]
    (when (>= (:status resp) 400)
      (throw (ex-info (str "HTTP Error: " (:status resp))
                      {:status (:status resp) :body (:body resp)})))))

(defn initialize!
  "Initialize MCP session. Returns session-id."
  [url & {:keys [client-name client-version]
          :or {client-name "yaac"
               client-version "1.0.0"}}]
  (let [resp (request! url "initialize"
                       {:protocolVersion "2024-11-05"
                        :capabilities {:roots {:listChanged true}}
                        :clientInfo {:name client-name
                                     :version client-version}})]
    (when (:error resp)
      (throw (ex-info (str "MCP Error: " (get-in resp [:error :message]))
                      {:error (:error resp)})))
    ;; Send initialized notification
    (notify! url "notifications/initialized" {} (:session-id resp))
    ;; Save session
    (save-session! (:session-id resp) url (:result resp))
    {:session-id (:session-id resp)
     :server-info (:result resp)}))

(defn tools-list!
  "List tools from MCP server."
  []
  (let [session (current-session)]
    (when-not session
      (throw (ex-info "No session. Run 'yaac mcp init <url>' first." {})))
    (let [{:keys [session-id url]} session
          resp (request! url "tools/list" {} :session-id session-id)]
      (when (:error resp)
        (throw (ex-info (str "MCP Error: " (get-in resp [:error :message]))
                        {:error (:error resp)})))
      (:result resp))))

(defn tools-call!
  "Call a tool on MCP server."
  [tool-name arguments]
  (let [session (current-session)]
    (when-not session
      (throw (ex-info "No session. Run 'yaac mcp init <url>' first." {})))
    (let [{:keys [session-id url]} session
          resp (request! url "tools/call"
                         {:name tool-name :arguments arguments}
                         :session-id session-id)]
      (when (:error resp)
        (throw (ex-info (str "MCP Error: " (get-in resp [:error :message]))
                        {:error (:error resp)})))
      (:result resp))))

;; CLI
(def cli-specs
  [["-h" "--help" "Show help"]
   ["-X" "--trace" "Show HTTP request/response trace"]
   ["-L" "--list" "List names only (for piping)"]])

(defn cmd-init
  "Initialize MCP session."
  [url]
  (when-not url
    (throw (ex-info "URL required. Usage: yaac mcp init <url>" {})))
  (let [{:keys [session-id server-info]} (initialize! url)]
    (println "Session ID:" session-id)
    (println)
    (println "Server Info:")
    (println "  Name:" (get-in server-info [:serverInfo :name] "Unknown"))
    (println "  Version:" (get-in server-info [:serverInfo :version] "Unknown"))
    (println "  Protocol:" (:protocolVersion server-info))))

(defn- wrap-text
  "Wrap text to given width with indent."
  [text width indent]
  (let [indent-str (apply str (repeat indent " "))
        words (str/split text #"\s+")]
    (loop [lines []
           current-line indent-str
           [word & rest-words] words]
      (if-not word
        (str/join "\n" (conj lines current-line))
        (let [new-line (if (= current-line indent-str)
                         (str current-line word)
                         (str current-line " " word))]
          (if (> (count new-line) width)
            (recur (conj lines current-line) (str indent-str word) rest-words)
            (recur lines new-line rest-words)))))))

(defn- format-schema-type [{:keys [type format items enum]}]
  (cond
    enum (str "enum[" (str/join "|" enum) "]")
    (= type "array") (str "array<" (format-schema-type items) ">")
    format (str type "(" format ")")
    :else (or type "any")))

(defn- format-params [schema]
  (let [props (:properties schema)
        required (set (:required schema))]
    (for [[k v] props]
      (let [param-name (name k)
            req? (contains? required param-name)]
        {:name param-name
         :type (format-schema-type v)
         :required req?
         :description (:description v)}))))

(defn cmd-list-tools
  "List tools from MCP session."
  [list-only?]
  (let [result (tools-list!)
        tools (:tools result)]
    (if (empty? tools)
      (when-not list-only? (println "No tools available."))
      (do
        (cache-tools! tools)
        (if list-only?
          (doseq [{:keys [name]} tools]
            (println name))
          (do
            (println)
            (println (colorize :bold "Tools") (colorize :gray (str "(" (count tools) ")")))
            (println)
            (doseq [{:keys [name description inputSchema]} tools]
              (println (str "  " (colorize :cyan (str "> " name))))
              (when description
                (println (colorize :white (wrap-text description 72 4))))
              (when inputSchema
                (let [params (format-params inputSchema)]
                  (when (seq params)
                    (println)
                    (println (str "    " (colorize :yellow "Parameters:")))
                    (doseq [{:keys [name type required description]} params]
                      (println (str "      " (colorize :green name)
                                    (colorize :blue (str " : " type))
                                    (when required (colorize :magenta " *"))))
                      (when description
                        (println (colorize :white (str "        " description))))))))
              (println))))))))

(defn cmd-session
  "Show current session."
  []
  (if-let [{:keys [session-id url server-info]} (current-session)]
    (do
      (println "Session:" session-id)
      (println "URL:" url)
      (println "Server:" (get-in server-info [:serverInfo :name])))
    (println "No session.")))

(defn cmd-clear
  "Clear current session."
  []
  (clear-session!)
  (println "Session cleared."))

(defn- parse-key-path
  "Parse a key path like 'a.b.0.c' into path segments.
   Supports escape with backslash: 'a\\.b' keeps dot as part of key.
   Numeric segments become integers (for array indexing)."
  [^String key]
  (loop [chars (seq key)
         current (StringBuilder.)
         result []]
    (if (empty? chars)
      (let [s (.toString current)]
        (if (empty? s)
          result
          (conj result (if (re-matches #"\d+" s)
                         (Integer/parseInt s)
                         s))))
      (let [[c & more] chars]
        (cond
          ;; Escaped character
          (and (= c \\) (seq more))
          (recur (rest more) (.append current (first more)) result)

          ;; Dot separator
          (= c \.)
          (let [s (.toString current)]
            (recur more
                   (StringBuilder.)
                   (conj result (if (re-matches #"\d+" s)
                                  (Integer/parseInt s)
                                  s))))

          ;; Regular character
          :else
          (recur more (.append current c) result))))))

(defn- ensure-array-size
  "Ensure vector has at least n+1 elements, padding with nil."
  [v n]
  (if (> (inc n) (count v))
    (into v (repeat (- (inc n) (count v)) nil))
    v))

(defn- assoc-in-path
  "Associate a value at a nested path, creating intermediate structures.
   Integer path segments create vectors, string segments create maps."
  [data path value]
  (if (empty? path)
    value
    (let [[k & ks] path
          current (if (integer? k)
                    (or data [])
                    (or data {}))]
      (if (integer? k)
        ;; Array index
        (let [arr (ensure-array-size (if (vector? current) current []) k)
              existing (get arr k)]
          (assoc arr k (assoc-in-path existing ks value)))
        ;; Object key
        (let [existing (get current k)]
          (assoc current k (assoc-in-path existing ks value)))))))

(defn- parse-json-value
  "Parse a raw JSON value string."
  [^String v]
  (cond
    (= v "true") true
    (= v "false") false
    (= v "null") nil
    (re-matches #"-?\d+" v) (Long/parseLong v)
    (re-matches #"-?\d+\.\d+" v) (Double/parseDouble v)
    :else (json/read-value v)))

(defn- parse-kv-args
  "Parse HTTPie-style key=value arguments into a map.
   key=value     -> string value
   key:=value    -> raw JSON (number, boolean, object, array)
   a.b=value     -> nested object: {\"a\": {\"b\": \"value\"}}
   a.0=value     -> array element: {\"a\": [\"value\"]}
   a\\.b=value   -> escaped dot: {\"a.b\": \"value\"}"
  [args]
  (reduce (fn [m arg]
            (cond
              ;; key:=json (raw JSON value)
              (str/includes? arg ":=")
              (let [idx (.indexOf ^String arg ":=")
                    k (subs arg 0 idx)
                    v (subs arg (+ idx 2))
                    path (parse-key-path k)]
                (assoc-in-path m path (parse-json-value v)))
              ;; key=value (string)
              (str/includes? arg "=")
              (let [idx (.indexOf ^String arg "=")
                    k (subs arg 0 idx)
                    v (subs arg (inc idx))
                    path (parse-key-path k)]
                (assoc-in-path m path v))
              :else m))
          nil args))

(defn- format-content [{:keys [type text]}]
  (case type
    "text" text
    (str "[" type "]")))

(defn cmd-call
  "Call a tool."
  [tool-name args]
  (when-not tool-name
    (throw (ex-info "Tool name required. Usage: yaac mcp call <tool> [key=value ...]" {})))
  (let [arguments (or (parse-kv-args args) {})
        result (tools-call! tool-name arguments)
        contents (:content result)]
    (println)
    (doseq [content contents]
      (println (format-content content)))
    (println)))

(defn usage [summary]
  (->> ["Usage: yaac mcp <command> [args]"
        ""
        "Commands:"
        "  init <url>                      Initialize session"
        "  list-tools                      List available tools"
        "  call <tool> [key=value ...]     Call a tool"
        "  session                         Show current session"
        "  clear                           Clear session"
        ""
        "Options:"
        summary
        ""
        "Parameter syntax:"
        "  key=value     String value"
        "  key:=value    Raw JSON (number, boolean, object, array)"
        "  a.b=value     Nested object: {\"a\": {\"b\": \"value\"}}"
        "  a.0=value     Array element: {\"a\": [\"value\"]}"
        "  a\\.b=value   Escaped dot: {\"a.b\": \"value\"}"
        ""
        "Examples:"
        "  yaac mcp init https://example.com/mcp"
        "  yaac mcp list-tools"
        "  yaac mcp call list-orders status=ORDERED"
        "  yaac mcp call get-order id:=123"
        "  yaac mcp call search active:=true limit:=10"
        "  yaac mcp call create user.name=John user.age:=30"
        "  yaac mcp call batch items.0=apple items.1=banana"
        ""]
       (str/join \newline)))

(defn main [& args]
  (let [{:keys [options arguments summary errors]} (parse-opts args cli-specs)
        [cmd arg & rest-args] arguments]
    (binding [*trace* (:trace options)]
      (cond
        errors
        (throw (ex-info (str/join "; " errors) {:errors errors}))

        (:help options)
        (println (usage summary))

        (= cmd "init")
        (cmd-init arg)

        (= cmd "list-tools")
        (cmd-list-tools (:list options))

        (= cmd "call")
        (cmd-call arg rest-args)

        (= cmd "session")
        (cmd-session)

        (= cmd "clear")
        (cmd-clear)

        :else
        (println (usage summary))))))
