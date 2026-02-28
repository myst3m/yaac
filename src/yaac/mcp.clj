(ns yaac.mcp
  (:require [yaac.core :refer [*org* *env*]]
            [yaac.describe :as desc]
            [yaac.error :as e]
            [silvur.mcp :as mcp]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [jansi-clj.core :as jansi]
            [reitit.core :as r]))

(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: mcp <command> [options]"
           ""
           "MCP (Model Context Protocol) client"
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Commands:"
             ""
             "  init [org] [env] <app>           Initialize session (resolve public URL)"
             "  init <url>                        Initialize session (direct URL)"
             "  tool                              List available tools"
             "  call <tool> [key=val ...]         Call a tool (HTTPie-style args)"
             "  session                           Show current session"
             "  clear                             Clear session"
             ""
             "Parameter syntax:"
             "  key=value     String value"
             "  key:=value    Raw JSON (number, boolean, object, array)"
             "  a.b=value     Nested: {\"a\": {\"b\": \"value\"}}"
             ""
             "Examples:"
             ""
             "  yaac mcp init T1 Sandbox my-mcp-app"
             "  yaac mcp init https://my-app.cloudhub.io/mcp"
             "  yaac mcp tool"
             "  yaac mcp tool -L"
             "  yaac mcp call list-orders status=ORDERED"
             ""]))
         (str/join \newline))))

(def options [["-L" "--list" "List names only (for piping)"]])

(defn- resolve-mcp-url
  "Resolve app name to MCP endpoint URL via describe-application."
  [args]
  (let [[org env app] (case (count args)
                        1 [*org* *env* (first args)]
                        2 [(first args) *env* (second args)]
                        3 args
                        (throw (e/invalid-arguments
                                "Usage: yaac mcp init [org] [env] <app>"
                                {:args args})))
        _ (log/debug "Resolving MCP URL for:" org env app)
        [app-context] (desc/describe-application {:args [org env app]})
        public-url (get-in app-context [:target :deployment-settings :http :inbound :public-url])]
    (when (str/blank? public-url)
      (throw (e/app-not-found (str "Public URL is not configured for '" app "'. Enable it in Runtime Manager > Settings > Ingress.")
                              {:app app :org org :env env})))
    (str public-url "/mcp")))

(defn mcp-init [{:keys [args list]
                 :as opts}]
  (let [raw-args (if (string? args) (str/split args #"\|") args)
        url (if (and (= 1 (count raw-args))
                     (str/starts-with? (first raw-args) "http"))
              (first raw-args)
              (resolve-mcp-url raw-args))]
    (log/debug "MCP URL:" url)
    (let [{:keys [session-id server-info]} (mcp/initialize! url
                                             :client-name "yaac"
                                             :client-version "1.0.0")]
      [{:session-id session-id
        :url url
        :server (get-in server-info [:serverInfo :name] "Unknown")
        :version (get-in server-info [:serverInfo :version] "Unknown")
        :protocol (:protocolVersion server-info)}])))

;; --- Tool list formatting ---

(defn- wrap-text
  "Wrap text to given width with indent prefix."
  [text width indent]
  (let [indent-str (apply str (repeat indent \space))
        words (str/split (str text) #"\s+")]
    (loop [lines []
           current indent-str
           [w & ws] words]
      (if-not w
        (str/join "\n" (conj lines current))
        (let [candidate (if (= current indent-str)
                          (str current w)
                          (str current " " w))]
          (if (> (count candidate) width)
            (recur (conj lines current) (str indent-str w) ws)
            (recur lines candidate ws)))))))

(defn- format-schema-type [{:keys [type format items enum]}]
  (cond
    enum       (str "enum[" (str/join "|" enum) "]")
    (= type "array") (str "array<" (format-schema-type items) ">")
    format     (str type "(" format ")")
    :else      (or type "any")))

(defn- format-tool [{:keys [name description inputSchema]}]
  (let [sb (StringBuilder.)]
    ;; tool name
    (.append sb (str "  " (jansi/fg :cyan (str "> " name)) "\n"))
    ;; description
    (when (seq description)
      (.append sb (str (jansi/a :faint (wrap-text description 76 4)) "\n")))
    ;; parameters
    (when-let [props (seq (:properties inputSchema))]
      (let [required (set (:required inputSchema))]
        (.append sb (str "    " (jansi/fg :yellow "Parameters:") "\n"))
        (doseq [[k v] props]
          (let [pname (clojure.core/name k)
                req?  (contains? required pname)]
            (.append sb (str "      " (jansi/fg :green pname)
                             (jansi/fg :blue (str " : " (format-schema-type v)))
                             (when req? (jansi/fg :magenta " *"))
                             "\n"))
            (when (:description v)
              (.append sb (str "        " (jansi/a :faint (:description v)) "\n")))))))
    (.toString sb)))

(defn- format-tools-rich
  "Custom formatter for tool list — rich colored output."
  [_fmt results _opts]
  (str "\n"
       (jansi/a :bold "Tools") " " (jansi/a :faint (str "(" (count results) ")"))
       "\n\n"
       (str/join "\n" (map format-tool results))))

(defn- fetch-tools
  "Fetch tools from MCP server and update cache."
  []
  (let [result (mcp/tools-list!)
        tools (:tools result)]
    (#'mcp/cache-tools! tools)
    tools))

(defn mcp-list-tools
  "List tool names."
  [opts]
  (let [tools (fetch-tools)]
    (mapv (fn [{:keys [name]}] {:name name}) tools)))

(defn mcp-describe-tool
  "Show detail for a specific tool (or all if no name given)."
  [{:keys [args] :as opts}]
  (let [raw-args (if (string? args) (str/split args #"\|") args)
        ;; filter out option flags (already parsed by ext-parse-opts)
        tool-name (first (remove #(str/starts-with? % "-") raw-args))
        tools (fetch-tools)
        matched (if tool-name
                  (filter #(= (:name %) tool-name) tools)
                  tools)]
    (when (and tool-name (empty? matched))
      (throw (e/app-not-found (str "Tool not found: " tool-name))))
    (vec matched)))

(defn mcp-call-tool [{:keys [args] :as opts}]
  (let [raw-args (if (string? args) (str/split args #"\|") args)
        tool-name (first raw-args)
        kv-args (rest raw-args)]
    (when-not tool-name
      (throw (e/invalid-arguments "Tool name required" {:args args})))
    (let [arguments (or (#'mcp/parse-kv-args kv-args) {})
          result (mcp/tools-call! tool-name arguments)
          contents (:content result)]
      (->> contents
           (map (fn [{:keys [type text]}]
                  (case type
                    "text" text
                    (str "[" type "]"))))
           (str/join "\n")))))

(defn mcp-session [opts]
  (if-let [{:keys [session-id url server-info]} (#'mcp/current-session)]
    [{:session-id session-id
      :url url
      :server (get-in server-info [:serverInfo :name] "Unknown")}]
    [{:message "No active session."}]))

(defn mcp-clear [opts]
  (#'mcp/clear-session!)
  [{:message "Session cleared."}])

(def route
  ["mcp" {:options options :usage usage}
   ["" {:help true}]
   ["|init" {:help true}]
   ["|init|{*args}" {:handler mcp-init
                     :fields [:session-id :url :server :version :protocol]}]
   ["|tool" {:handler mcp-list-tools
             :fields [:name]}]
   ["|tool|{*args}" {:handler mcp-describe-tool
                     :formatter format-tools-rich}]
   ["|tools" {:handler mcp-list-tools
              :fields [:name]}]
   ["|tools|{*args}" {:handler mcp-describe-tool
                      :formatter format-tools-rich}]
   ["|call|{*args}" {:handler mcp-call-tool
                     :output-format :raw}]
   ["|session" {:handler mcp-session
                :fields [:session-id :url :server]}]
   ["|clear" {:handler mcp-clear
              :fields [:message]}]])
