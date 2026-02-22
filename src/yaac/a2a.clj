(ns yaac.a2a
  (:require [yaac.core :refer [*org* *env*]]
            [yaac.describe :as desc]
            [yaac.error :as e]
            [yaac.markdown :as markdown]
            [yaac.util :as util]
            [silvur.a2a :as a2a]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [jansi-clj.core :as jansi]
            [reitit.core :as r])
  (:import [org.jline.reader LineReader LineReader$Option LineReaderBuilder
            EndOfFileException UserInterruptException Candidate Reference]
           [org.jline.reader.impl.history DefaultHistory]
           [org.jline.terminal TerminalBuilder]
           [java.nio.file Paths]))

(def ^:private session-dir (str (System/getProperty "user.home") "/.yaac/a2a-sessions"))
(def ^:private history-file (str (System/getProperty "user.home") "/.yaac/a2a-history"))
(def ^:private last-session-file (str session-dir "/last-agent-key"))

;; Current agent key — set by console/init to enable per-agent sessions
(def ^:private ^:dynamic *agent-key* nil)

(defn- agent-session-file
  "Return session file path for the given agent key (app name or URL hash)."
  [agent-key]
  (str session-dir "/" agent-key ".edn"))

(defn- save-last-agent-key!
  "Persist the last-used agent key for session resume."
  [agent-key]
  (let [dir (java.io.File. session-dir)]
    (when-not (.exists dir) (.mkdirs dir))
    (spit last-session-file agent-key)))

(defn- load-last-agent-key
  "Load the last-used agent key, or nil."
  []
  (let [f (java.io.File. last-session-file)]
    (when (.exists f)
      (let [k (str/trim (slurp f))]
        (when (seq k) k)))))

(defn- url->agent-key
  "Derive a stable key from a URL (last path segment or hostname)."
  [url]
  (let [path (try (.getPath (java.net.URI. url)) (catch Exception _ ""))]
    (or (when (seq path)
          (last (remove str/blank? (str/split path #"/"))))
        (try (.getHost (java.net.URI. url)) (catch Exception _ "default")))))

(defn- resolve-agent-key
  "Determine agent key from raw args (app name or URL)."
  [raw-args]
  (if (and (= 1 (count raw-args))
           (str/starts-with? (first raw-args) "http"))
    (url->agent-key (first raw-args))
    ;; Use the app name (last arg, or the one before agent-path)
    (let [agent-path (when (str/starts-with? (last raw-args) "/") (last raw-args))
          args (if agent-path (butlast raw-args) raw-args)]
      (last args))))

(defn- with-session
  "Wrap handler with session binding.
   resolve-key? true (default): resolve agent-key from args (for init/console).
   resolve-key? false: use last-agent-key (for send/task/cancel/card/session/clear)."
  ([handler] (with-session handler true))
  ([handler resolve-key?]
   (fn [opts]
     (let [raw-args (let [a (:args opts)]
                      (if (string? a) (str/split a #"\|") a))
           ak (or *agent-key*
                  (when resolve-key?
                    (when (seq raw-args) (resolve-agent-key raw-args)))
                  (load-last-agent-key)
                  "default")]
       (binding [a2a/*session-file* (agent-session-file ak)
                 *agent-key* ak]
         (handler opts))))))

(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: a2a <command> [options]"
           ""
           "A2A (Agent-to-Agent) protocol client"
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Commands:"
             ""
             "  init [org] [env] <app> [/path]   Connect to A2A agent (resolve public URL)"
             "  init <url>                        Connect to A2A agent (direct URL)"
             "  console [org] [env] <app> [/path]  Interactive console"
             "  send <message...>                 Send message to agent"
             "  task <task-id>                    Get task status"
             "  cancel <task-id>                  Cancel a task"
             "  card                              Show agent card"
             "  session                           Show current session"
             "  clear                             Clear session"
             ""
             "Examples:"
             ""
             "  yaac a2a init my-a2a-app"
             "  yaac a2a init my-a2a-app /order-fulfillment"
             "  yaac a2a init T1 Sandbox my-a2a-app /order-fulfillment"
             "  yaac a2a init https://my-agent.cloudhub.io"
             "  yaac a2a send Hello, what can you do?"
             "  yaac a2a task abc-123"
             "  yaac a2a card"
             ""]))
         (str/join \newline))))

(def options [])

(defn- resolve-a2a-url
  "Resolve app name to A2A endpoint URL via describe-application.
   Last arg starting with / is treated as agent path."
  [args]
  (let [;; Extract agent path (last arg starting with /)
        agent-path (when (str/starts-with? (last args) "/") (last args))
        args (if agent-path (butlast args) args)
        [org env app] (case (count args)
                        1 [*org* *env* (first args)]
                        2 [(first args) *env* (second args)]
                        3 args
                        (throw (e/invalid-arguments
                                "Usage: yaac a2a init [org] [env] <app> [/agent-path]"
                                {:args args})))
        _ (log/debug "Resolving A2A URL for:" org env app "path:" agent-path)
        [app-context] (desc/describe-application {:args [org env app]})
        public-url (get-in app-context [:target :deployment-settings :http :inbound :public-url])]
    (when-not public-url
      (throw (e/app-not-found (str "No public URL found for " app))))
    (str public-url agent-path)))

(defn a2a-init [{:keys [args] :as opts}]
  (let [raw-args (if (string? args) (str/split args #"\|") args)
        url (if (and (= 1 (count raw-args))
                     (str/starts-with? (first raw-args) "http"))
              (first raw-args)
              (resolve-a2a-url raw-args))]
    (log/debug "A2A URL:" url)
    (let [card (a2a/discover! url)]
      (when *agent-key* (save-last-agent-key! *agent-key*))
      [{:url url
        :agent-name (:name card)
        :version (:version card)}])))

;; --- Response formatting ---

(defn- extract-text-parts
  "Extract text from parts array. Handles both 'type' and 'kind' fields (v0.2/v0.3).
   Markdown in text parts is rendered to ANSI escape sequences."
  [parts]
  (->> parts
       (keep (fn [{:keys [type kind text data] :as part}]
               (case (or kind type)
                 "text" text
                 "file" "[file]"
                 "data" nil
                 (str "[" (or kind type) "]"))))
       (str/join "\n")
       markdown/render))

(defn- format-send-result
  "Format a message/send result for display.
   Result can be a Task (with :status, :artifacts) or a Message (with :parts, :role).
   Options:
     :show-artifact-name - show artifact name header (default true)"
  ([result] (format-send-result result {}))
  ([result {:keys [show-artifact-name] :or {show-artifact-name true}}]
   (case (:kind result)
     ;; Direct message response (v0.3)
     "message"
     (str (jansi/fg :green (:role result "agent")) "\n"
          (extract-text-parts (:parts result)) "\n")

     ;; Task response
     "task"
     (let [status (get-in result [:status :state] "unknown")
           artifacts (:artifacts result)]
       (str (jansi/a :bold "Task") " " (jansi/a :faint (:id result "?"))
            " " (case status
                  "completed" (jansi/fg :green status)
                  "failed" (jansi/fg :red status)
                  "working" (jansi/fg :yellow status)
                  "input-required" (jansi/fg :cyan status)
                  (jansi/a :faint status))
            "\n"
            (when (seq artifacts)
              (str "\n"
                   (str/join "\n"
                             (map (fn [{:keys [name parts]}]
                                    (str (when (and show-artifact-name name)
                                           (str (jansi/fg :cyan name) "\n"))
                                         (extract-text-parts parts)))
                                  artifacts))
                   "\n"))
            (when-let [msg (get-in result [:status :message])]
              (str "\n" (jansi/a :faint msg) "\n"))))

     ;; Fallback — try to extract parts or print as-is
     (if (:parts result)
       (str (extract-text-parts (:parts result)) "\n")
       (str result "\n")))))

;; --- Streaming support ---

(defn- streaming-capable?
  "Check if current session's agent supports streaming."
  []
  (let [session (a2a/current-session)]
    (get-in session [:agent-card :capabilities :streaming])))

(defn- format-stream-event
  "Format a single SSE event for real-time display."
  [{:keys [event data]}]
  (case event
    "status-update"
    (let [state (get-in data [:status :state])]
      (case state
        "working" (str (jansi/fg :yellow "⟳ ") (jansi/a :blink (jansi/fg :yellow "working...")))
        "completed" (jansi/a :faint (str "✓ " state))
        "input-required" (jansi/a :faint (str "? " state))
        "failed" (str (jansi/a :faint (str "✗ " state))
                      (when-let [msg (get-in data [:status :message])]
                        (str "\n" (extract-text-parts (:parts msg)))))
        ;; other states
        (jansi/a :faint (str "  [" state "]"))))

    "artifact-update"
    (let [artifact (:artifact data)
          aname (:name artifact)
          text (extract-text-parts (:parts artifact))
          last-chunk (:lastChunk data)]
      (case aname
        ;; Step results: show just tool name, one line
        "ステップ結果"
        (let [;; text format: "description: {json...}"
              brief (first (str/split text #":" 2))]
          (str (jansi/fg :yellow "  ⟳ ") (jansi/a :faint (str/trim (or brief "...")))))
        ;; Plan: show as-is (short)
        "実行計画"
        (str (jansi/fg :cyan "  ") (jansi/a :faint text))
        ;; Final summary or question: show full text
        (str (when aname (str (jansi/fg :cyan aname) "\n"))
             text)))

    "task"
    (let [state (get-in data [:status :state])]
      (str (jansi/a :bold "Task") " " (jansi/a :faint (or (:id data) "?"))
           " " (jansi/a :faint state)))

    ;; Unknown event
    nil))

(defn- send-streaming!
  "Send message via streaming and display events in real-time.
   Returns the final result text for console display."
  [text]
  (let [last-summary (atom nil)]
    (a2a/send-message-stream! text
      (fn [{:keys [event data] :as evt}]
        (when-let [formatted (format-stream-event evt)]
          (println formatted)
          (flush))
        ;; Track the last artifact text as summary
        (when (= event "artifact-update")
          (reset! last-summary (extract-text-parts (get-in data [:artifact :parts]))))))
    @last-summary))

(defn a2a-send [{:keys [args] :as opts}]
  (let [raw-args (if (string? args) (str/split args #"\|") args)
        text (str/join " " raw-args)]
    (when (str/blank? text)
      (throw (e/invalid-arguments "Message required" {:args args})))
    (if (streaming-capable?)
      (do (println)
          (send-streaming! text)
          (println)
          "")
      (let [result (a2a/send-message! text)]
        (format-send-result result)))))

(defn a2a-get-task [{:keys [args] :as opts}]
  (let [raw-args (if (string? args) (str/split args #"\|") args)
        task-id (first raw-args)]
    (when-not task-id
      (throw (e/invalid-arguments "Task ID required" {:args args})))
    (let [result (a2a/get-task! task-id)]
      [{:task-id (:id result)
        :status (get-in result [:status :state])
        :context-id (:contextId result)}])))

(defn a2a-cancel-task [{:keys [args] :as opts}]
  (let [raw-args (if (string? args) (str/split args #"\|") args)
        task-id (first raw-args)]
    (when-not task-id
      (throw (e/invalid-arguments "Task ID required" {:args args})))
    (let [result (a2a/cancel-task! task-id)]
      [{:task-id (:id result)
        :status (get-in result [:status :state])}])))

;; --- Card display ---

(defn- format-card-rich
  "Rich formatted agent card display."
  [_fmt results _opts]
  (let [card (first results)]
    (str "\n"
         (jansi/fg :cyan (jansi/a :bold (:name card "Unknown Agent")))
         (when (:version card)
           (str " " (jansi/a :faint (str "v" (:version card)))))
         "\n"
         (when (:description card)
           (str (jansi/a :faint (:description card)) "\n"))
         (when-let [url (:url card)]
           (str (jansi/a :faint (str "URL: " url)) "\n"))
         "\n"
         ;; Skills
         (when-let [skills (seq (:skills card))]
           (str (jansi/a :bold "Skills") " " (jansi/a :faint (str "(" (count skills) ")")) "\n\n"
                (str/join "\n"
                          (map (fn [{:keys [id name description examples]}]
                                 (str "  " (jansi/fg :cyan (str "> " (or name id)))
                                      (when description
                                        (str "\n    " (jansi/a :faint description)))
                                      (when (seq examples)
                                        (str "\n"
                                             (str/join "\n"
                                                       (map (fn [ex] (str "    " (jansi/fg :yellow "$ ") ex))
                                                            examples))))))
                               skills))
                "\n\n"))
         ;; Capabilities
         (when-let [caps (:capabilities card)]
           (str (jansi/a :bold "Capabilities") "\n"
                (when (:streaming caps) (str "  " (jansi/fg :green "streaming") "\n"))
                (when (:pushNotifications caps) (str "  " (jansi/fg :green "push-notifications") "\n"))
                (when (:stateTransitionHistory caps) (str "  " (jansi/fg :green "state-transition-history") "\n"))
                "\n")))))

(defn a2a-card [opts]
  (let [session (a2a/current-session)]
    (when-not session
      (throw (ex-info "No session. Run 'yaac a2a init <url>' first." {})))
    ;; Override card url with session url when card has localhost
    (let [card (:agent-card session)
          card-url (:url card)]
      [(if (or (nil? card-url)
               (str/includes? card-url "localhost")
               (str/includes? card-url "127.0.0.1"))
         (assoc card :url (:url session))
         card)])))

(defn a2a-session [opts]
  (if-let [{:keys [url agent-card context-id]} (a2a/current-session)]
    [{:url url
      :agent-name (:name agent-card "Unknown")
      :context-id (or context-id "-")}]
    [{:message "No active session."}]))

(defn a2a-clear [opts]
  (#'a2a/clear-session!)
  [{:message "Session cleared."}])

;; --- Interactive Console ---

(def ^:private slash-commands
  [["/card"    "Show agent card"]
   ["/session" "Show session info"]
   ["/clear"   "Clear session and reconnect"]
   ["/help"    "Show commands"]
   ["/quit"    "Exit console"]])

(def ^:private console-help
  (str/join "\n"
            (map (fn [[cmd desc]] (str "  " cmd (apply str (repeat (- 12 (count cmd)) " ")) desc))
                 slash-commands)))

(defn- make-slash-completer []
  (reify org.jline.reader.Completer
    (complete [_ _reader line candidates]
      (let [word (or (.word line) "")]
        (doseq [[cmd desc] slash-commands]
          (when (str/starts-with? cmd word)
            (.add candidates (Candidate. cmd cmd nil desc nil nil true))))))))

(defn a2a-console [{:keys [args] :as opts}]
  (let [raw-args (if (string? args) (str/split args #"\|") args)
        ;; No args → resume last session
        ak (if (seq raw-args)
             (resolve-agent-key raw-args)
             (load-last-agent-key))]
   (binding [a2a/*session-file* (agent-session-file (or ak "default"))
             *agent-key* ak]
    ;; If args provided and no session, init first
    (when (and (seq raw-args) (nil? (a2a/current-session)))
      (let [url (if (and (= 1 (count raw-args))
                         (str/starts-with? (first raw-args) "http"))
                  (first raw-args)
                  (resolve-a2a-url raw-args))]
        (a2a/discover! url)))
    ;; Verify session exists
    (let [session (a2a/current-session)]
      (when-not session
        (throw (ex-info "No session. Run 'yaac a2a init <agent>' first, or: yaac a2a console <agent>" {})))
      ;; Save last-used agent key for resume
      (when ak (save-last-agent-key! ak))
      ;; Print banner
      (let [card (:agent-card session)]
        (println)
        (println (str (jansi/fg :cyan (jansi/a :bold (:name card "Unknown Agent")))
                      (when (:version card)
                        (str " " (jansi/a :faint (str "v" (:version card)))))))
        (println (jansi/a :faint (:url session)))
        (println)))
    ;; JLine REPL
    (with-open [terminal (-> (TerminalBuilder/builder) (.system true) (.build))]
      (let [reader (-> (LineReaderBuilder/builder)
                       (.terminal terminal)
                       (.completer (make-slash-completer))
                       (.variable LineReader/HISTORY_FILE (Paths/get history-file (into-array String [])))
                       (.option LineReader$Option/AUTO_LIST true)
                       (.option LineReader$Option/AUTO_MENU true)
                       (.option LineReader$Option/LIST_ROWS_FIRST true)
                       (.build))]
        ;; Bind '/' to show completion list, then filter as user types
        (when-not (instance? org.jline.terminal.impl.DumbTerminal terminal)
          (let [slash-cmds (mapv first slash-commands)
                ;; Find matching commands for current buffer
                matching-cmds (fn []
                                (let [input (str (.getBuffer reader))]
                                  (filterv #(str/starts-with? % input) slash-cmds)))]
            ;; '/' at line start: insert '/' and show all candidates
            (.put (.getWidgets reader) "slash-complete"
                  (reify org.jline.reader.Widget
                    (apply [_]
                      (let [buf (.getBuffer reader)]
                        (.write buf (int \/))
                        (when (and (= (.cursor buf) 1) (= (.length buf) 1))
                          (.callWidget reader LineReader/LIST_CHOICES))
                        true))))
            ;; Typing a character: self-insert + re-show filtered list (no auto-complete)
            (.put (.getWidgets reader) "self-insert-filter"
                  (reify org.jline.reader.Widget
                    (apply [_]
                      (.callWidget reader LineReader/SELF_INSERT)
                      (when (str/starts-with? (str (.getBuffer reader)) "/")
                        (.callWidget reader LineReader/LIST_CHOICES))
                      true)))
            ;; Backspace: delete char + re-show filtered list
            (.put (.getWidgets reader) "backward-delete-filter"
                  (reify org.jline.reader.Widget
                    (apply [_]
                      (.callWidget reader LineReader/BACKWARD_DELETE_CHAR)
                      (let [line (str (.getBuffer reader))]
                        (when (and (seq line) (str/starts-with? line "/"))
                          (.callWidget reader LineReader/LIST_CHOICES)))
                      true)))
            ;; Enter: if in slash mode with unique match, complete it; otherwise accept line
            (.put (.getWidgets reader) "slash-accept-line"
                  (reify org.jline.reader.Widget
                    (apply [_]
                      (let [input (str (.getBuffer reader))]
                        (if (str/starts-with? input "/")
                          (let [matches (matching-cmds)]
                            (cond
                              ;; Exact match — submit as-is
                              (some #{input} slash-cmds)
                              (.callWidget reader LineReader/ACCEPT_LINE)
                              ;; Unique prefix match — fill in and submit
                              (= 1 (count matches))
                              (let [buf (.getBuffer reader)]
                                (.clear buf)
                                (.write buf ^String (first matches))
                                (.callWidget reader LineReader/ACCEPT_LINE))
                              ;; No match or ambiguous — just submit what's there
                              :else
                              (.callWidget reader LineReader/ACCEPT_LINE)))
                          ;; Normal text — submit
                          (.callWidget reader LineReader/ACCEPT_LINE)))
                      true)))
            (let [^org.jline.keymap.KeyMap main-km (.get (.getKeyMaps reader) LineReader/MAIN)]
              (.bind main-km (Reference. "slash-complete") (into-array CharSequence ["/"]))
              ;; Bind a-z and hyphen to filtering widget
              (doseq [c (range (int \a) (inc (int \z)))]
                (.bind main-km (Reference. "self-insert-filter") (into-array CharSequence [(str (char c))])))
              (.bind main-km (Reference. "self-insert-filter") (into-array CharSequence ["-"]))
              ;; Bind backspace to filtering widget
              (.bind main-km (Reference. "backward-delete-filter") (into-array CharSequence ["\u007f"]))
              ;; Bind Enter to slash-aware accept
              (.bind main-km (Reference. "slash-accept-line") (into-array CharSequence ["\r" "\n"])))))
        (loop []
          (let [line (try
                       (.readLine reader "> ")
                       (catch EndOfFileException _ nil)
                       (catch UserInterruptException _ :interrupted))]
            (cond
              ;; EOF (Ctrl+D)
              (nil? line)
              (println (jansi/a :faint "\nBye."))

              ;; Ctrl+C — cancel current input
              (= :interrupted line)
              (do (println) (recur))

              ;; Empty line
              (str/blank? line)
              (recur)

              ;; Slash commands
              (str/starts-with? line "/")
              (let [cmd (str/lower-case (str/trim line))]
                (case cmd
                  ("/quit" "/exit" "/q")
                  (println (jansi/a :faint "Bye."))

                  "/card"
                  (do (let [s (a2a/current-session)
                            card (:agent-card s)
                            card (if (and (:url card)
                                         (or (str/includes? (:url card) "localhost")
                                             (str/includes? (:url card) "127.0.0.1")))
                                   (assoc card :url (:url s))
                                   card)]
                        (println (format-card-rich nil [card] nil)))
                      (recur))

                  "/session"
                  (let [{:keys [url agent-card context-id]} (a2a/current-session)]
                    (println (str "  Agent: " (:name agent-card "Unknown")))
                    (println (str "  URL:   " url))
                    (println (str "  Context: " (or context-id "-")))
                    (println)
                    (recur))

                  "/clear"
                  (do (#'a2a/clear-session!)
                      (println (jansi/fg :yellow "Session cleared."))
                      (when (seq args)
                        (let [raw-args (if (string? args) (str/split args #"\|") args)
                              url (if (and (= 1 (count raw-args))
                                           (str/starts-with? (first raw-args) "http"))
                                    (first raw-args)
                                    (resolve-a2a-url raw-args))
                              card (a2a/discover! url)]
                          (println (str (jansi/fg :green "Reconnected: ") (:name card)))))
                      (println)
                      (recur))

                  "/help"
                  (do (println) (println console-help) (println)
                      (recur))

                  ;; Unknown slash command
                  (do (println (str (jansi/fg :yellow "Unknown command: ") cmd))
                      (println (jansi/a :faint "Type /help for available commands."))
                      (recur))))

              ;; Normal message → send to agent
              :else
              (do
                (try
                  (println)
                  (if (streaming-capable?)
                    (send-streaming! line)
                    (let [result (util/with-spin "Processing..."
                                   (a2a/send-message! line))]
                      (print (format-send-result result {:show-artifact-name false}))
                      (flush)))
                  (catch Exception e
                    (println (str (jansi/fg :red "Error: ") (ex-message e)))))
                (println)
                (recur))))))))))


(def route
  ["a2a" {:options options :usage usage}
   ["" {:help true}]
   ["|init" {:help true}]
   ["|init|{*args}" {:handler (with-session a2a-init)
                      :fields [:url :agent-name :version]}]
   ["|send|{*args}" {:handler (with-session a2a-send false)
                      :output-format :raw}]
   ["|task|{*args}" {:handler (with-session a2a-get-task false)
                      :fields [:task-id :status :context-id]}]
   ["|cancel|{*args}" {:handler (with-session a2a-cancel-task false)
                        :fields [:task-id :status]}]
   ["|card" {:handler (with-session a2a-card false)
             :formatter format-card-rich}]
   ["|session" {:handler (with-session a2a-session false)
                 :fields [:url :agent-name :context-id]}]
   ["|clear" {:handler (with-session a2a-clear false)
               :fields [:message]}]])
