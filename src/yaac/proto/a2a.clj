(ns yaac.proto.a2a
  "A2A (Agent-to-Agent) protocol client implementation.
   JSON-RPC 2.0 over HTTP, agent discovery via /.well-known/agent-card.json."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [zeph.client :as http])
  (:import [java.nio.file Path Paths Files LinkOption]
           [java.nio.file.attribute FileAttribute]))

;; Inlined NIO helpers (from silvur.nio)
(defn- nio-path ^Path [s] (Paths/get (str s) (into-array String [])))
(defn- nio-exists? [p] (Files/exists (nio-path p) (into-array LinkOption [])))
(defn- nio-mkdir [p] (Files/createDirectories (nio-path p) (into-array FileAttribute [])))
(defn- nio-rm [p] (Files/deleteIfExists (nio-path p)))

(def ^:dynamic *session-file* (str (System/getProperty "user.home") "/.silvur/a2a-session.edn"))

(defn- ensure-session-dir! []
  (nio-mkdir (str (.getParent ^Path (nio-path *session-file*)))))

(defn- load-session []
  (when (nio-exists? *session-file*)
    (read-string (slurp *session-file*))))

(defn- save-session! [data]
  (ensure-session-dir!)
  (spit *session-file* (pr-str data)))

(defn- clear-session! []
  (ensure-session-dir!)
  (when (nio-exists? *session-file*)
    (nio-rm *session-file*)))

(defn current-session []
  (load-session))

(defn clear-task-id!
  "Clear task-id from session so the next message starts a new task.
   Used after auth-required to avoid sending the stale task-id."
  []
  (when-let [session (load-session)]
    (save-session! (dissoc session :task-id))))

(def ^:private terminal-task-states
  "Task states for which the broker rejects further message/send on the same taskId
   (RequestHandler.validateTask: 'Task has reached terminal state.')."
  #{"completed" "failed" "canceled" "rejected"})

(def ^:private context-ttl-ms
  "Broker-side hardcoded TTL for context/task entries in the A2A connector
   (mule4-a2a-connector ServerAgent.TASK_OS_TTL = 1 day). After this elapses
   the broker drops the conversation and rejects sends with 'contextId not found'."
  (* 24 60 60 1000))

(defn- terminal-result? [result]
  (and (map? result)
       (contains? terminal-task-states (get-in result [:status :state]))))

(defn- now-ms [] (System/currentTimeMillis))

(defn context-age-ms
  "Milliseconds since :context-created-at, or nil if no context is established."
  [session]
  (when-let [t (:context-created-at session)]
    (- (now-ms) t)))

(defn context-expired?
  "True when the current context is older than the broker TTL and the next send
   is likely to be rejected with 'contextId not found'."
  [session]
  (when-let [age (context-age-ms session)]
    (> age context-ttl-ms)))

(defn- maybe-warn-expired-context! [session]
  (when (context-expired? session)
    (binding [*out* *err*]
      (println (format "Warning: context-id is %d hours old, exceeding the broker's 24h TTL. The next send may fail with 'contextId not found'. Run 'yaac a2a init <agent>' to start a fresh conversation."
                       (long (/ (context-age-ms session) (* 60 60 1000))))))))

(defn- persist-session-ids!
  "Persist contextId from response (always) and taskId (only if task is still live).
   Stamps :context-created-at the first time a contextId appears (or when the
   broker hands back a different one). When the task has reached a terminal
   state, drop :task-id so the next send starts a new task under the same
   context."
  [session result]
  (let [terminal? (terminal-result? result)
        new-ctx (:contextId result)
        ctx-changed? (and new-ctx (not= new-ctx (:context-id session)))]
    (save-session! (cond-> session
                     new-ctx                              (assoc :context-id new-ctx)
                     ctx-changed?                         (assoc :context-created-at (now-ms))
                     (and (:id result) (not terminal?))   (assoc :task-id (:id result))
                     terminal?                            (dissoc :task-id)))))

;; JSON-RPC helpers
(defn- make-request [method params id]
  {:jsonrpc "2.0"
   :id id
   :method method
   :params (or params {})})

(defn- jsonrpc-post!
  "Send JSON-RPC request to A2A server. Returns parsed result map.
   Optional bearer-token for Authorization header."
  [url method params & {:keys [bearer-token]}]
  (let [req-id (rand-int 100000)
        body (json/write-value-as-string (make-request method params req-id))
        headers (cond-> {"Content-Type" "application/json"
                         "Accept" "application/json"}
                  bearer-token (assoc "Authorization" (str "Bearer " bearer-token)))
        resp @(http/request {:method :post
                             :url url
                             :headers headers
                             :body body
                             :timeout 120000})]
    (when-not (:body resp)
      (throw (ex-info (str "No response from server (HTTP " (:status resp 0) ")")
                      {:status (:status resp)})))
    (if (>= (:status resp) 400)
      (throw (ex-info (str "HTTP Error: " (:status resp))
                      {:status (:status resp) :body (:body resp)}))
      (let [result (json/read-value (:body resp) json/keyword-keys-object-mapper)]
        (when (:error result)
          (throw (ex-info (str "A2A Error: " (get-in result [:error :message]))
                          {:error (:error result)
                           :a2a-data (get-in result [:error :data])})))
        (:result result)))))

;; Public API

(defn discover!
  "Fetch agent card from /.well-known/agent-card.json and save session.
   Optional bearer-token is stored in session for subsequent requests."
  [url & {:keys [bearer-token]}]
  (let [card-url (str (str/replace url #"/$" "") "/.well-known/agent-card.json")
        headers (cond-> {"Accept" "application/json"}
                  bearer-token (assoc "Authorization" (str "Bearer " bearer-token)))
        resp @(http/request {:method :get
                             :url card-url
                             :headers headers})
        _ (when (>= (:status resp) 400)
            (throw (ex-info (str "Failed to fetch agent card: HTTP " (:status resp))
                            {:status (:status resp) :body (:body resp)})))
        card (json/read-value (:body resp) json/keyword-keys-object-mapper)]
    (save-session! (cond-> {:url (or (:url card) url)
                            :agent-card card
                            :context-id nil}
                     bearer-token (assoc :bearer-token bearer-token)))
    card))

(defn send-message!
  "Send a text message via message/send. Returns task result.
   Optional :bearer-token for Authorization header.
   Optional :secondary-token for InTaskAC flow (parts[].data.auth_credentials.accessToken)."
  [text & {:keys [context-id task-id bearer-token secondary-token]}]
  (let [session (current-session)
        _ (when-not session
            (throw (ex-info "No session. Run 'yaac a2a init <url>' first." {})))
        _ (maybe-warn-expired-context! session)
        url (:url session)
        bearer (or bearer-token (:bearer-token session))
        ctx (or context-id (:context-id session))
        tid (or task-id (:task-id session))
        msg-id (str (java.util.UUID/randomUUID))
        ;; Include contextId/taskId in both params level AND inside message object
        ;; Broker connector reads from message.getContextId()/getTaskId() for conversation lookup
        msg (cond-> {:kind "message"
                     :messageId msg-id
                     :role "user"
                     :parts [{:kind "text" :text text}]}
              ctx (assoc :contextId ctx)
              tid (assoc :taskId tid)
              ;; InTaskAC: pass auth credentials as DataPart (official A2A format)
              secondary-token (update :parts conj {:kind "data"
                                                   :data {:auth_credentials
                                                          {:accessToken secondary-token}}}))
        params (cond-> {:message msg
                        :configuration {:acceptedOutputModes ["text"]}}
                 ctx (assoc :contextId ctx)
                 tid (assoc :taskId tid))
        result (jsonrpc-post! url "message/send" params :bearer-token bearer)]
    (persist-session-ids! session result)
    result))

(defn get-task!
  "Get task status by ID."
  [task-id]
  (let [session (current-session)
        bearer (:bearer-token session)]
    (when-not session
      (throw (ex-info "No session. Run 'yaac a2a init <url>' first." {})))
    (jsonrpc-post! (:url session) "tasks/get" {:id task-id} :bearer-token bearer)))

(defn cancel-task!
  "Cancel a task by ID."
  [task-id]
  (let [session (current-session)
        bearer (:bearer-token session)]
    (when-not session
      (throw (ex-info "No session. Run 'yaac a2a init <url>' first." {})))
    (jsonrpc-post! (:url session) "tasks/cancel" {:id task-id} :bearer-token bearer)))

(defn send-message-stream!
  "Send a text message via message/sendStream (SSE streaming).
   Calls event-fn for each parsed SSE event {:event <type> :data <parsed-json>}.
   Returns the final result."
  [text event-fn & {:keys [context-id task-id bearer-token secondary-token]}]
  (let [session (current-session)
        _ (when-not session
            (throw (ex-info "No session. Run 'yaac a2a init <url>' first." {})))
        _ (maybe-warn-expired-context! session)
        url (:url session)
        bearer (or bearer-token (:bearer-token session))
        ctx (or context-id (:context-id session))
        tid (or task-id (:task-id session))
        msg-id (str (java.util.UUID/randomUUID))
        msg (cond-> {:kind "message"
                     :messageId msg-id
                     :role "user"
                     :parts [{:kind "text" :text text}]}
              ctx (assoc :contextId ctx)
              tid (assoc :taskId tid)
              secondary-token (update :parts conj {:kind "data"
                                                   :data {:auth_credentials
                                                          {:accessToken secondary-token}}}))
        params (cond-> {:message msg
                        :configuration {:acceptedOutputModes ["text"]}}
                 ctx (assoc :contextId ctx)
                 tid (assoc :taskId tid))
        req-id (rand-int 100000)
        body (json/write-value-as-string (make-request "message/stream" params req-id))
        headers (cond-> {"Content-Type" "application/json"
                         "Accept" "text/event-stream"}
                  bearer (assoc "Authorization" (str "Bearer " bearer)))
        resp @(http/request {:method :post
                             :url url
                             :headers headers
                             :body body
                             :timeout 120000})
        last-result (atom nil)]
    (when-not (:body resp)
      (throw (ex-info (str "No response from server (HTTP " (:status resp 0) ")")
                      {:status (:status resp)})))
    (when (>= (:status resp) 400)
      (throw (ex-info (str "HTTP Error: " (:status resp))
                      {:status (:status resp) :body (:body resp)})))
    ;; Parse SSE events from response body
    (let [lines (str/split-lines (:body resp))
          events (loop [ls lines, event-type nil, data-buf [], result []]
                   (if (empty? ls)
                     (if (seq data-buf)
                       (conj result {:event event-type :data (str/join "\n" data-buf)})
                       result)
                     (let [line (first ls)]
                       (cond
                         (str/starts-with? line "event: ")
                         (recur (rest ls) (subs line 7) data-buf result)

                         (str/starts-with? line "data: ")
                         (recur (rest ls) event-type (conj data-buf (subs line 6)) result)

                         (str/blank? line)
                         (if (seq data-buf)
                           (recur (rest ls) nil []
                                  (conj result {:event event-type
                                                :data (str/join "\n" data-buf)}))
                           (recur (rest ls) event-type data-buf result))

                         :else
                         (recur (rest ls) event-type data-buf result)))))]
      ;; Track terminal state across all events — the last event may be an
      ;; artifact-update without a status field, but an earlier status-update
      ;; could have signaled terminal. We collapse both into the saved session.
      (let [saw-terminal? (atom false)]
        (doseq [{:keys [event data]} events]
          (let [parsed (try (json/read-value data json/keyword-keys-object-mapper)
                            (catch Exception _ data))]
            (reset! last-result parsed)
            (when (terminal-result? parsed)
              (reset! saw-terminal? true))
            (event-fn {:event event :data parsed})))
        (when-let [r @last-result]
          (when (map? r)
            ;; If any event signaled terminal, fabricate a terminal-shaped result
            ;; so persist-session-ids! drops :task-id regardless of last event shape.
            (persist-session-ids! session
                                  (if @saw-terminal?
                                    (assoc r :status {:state "completed"})
                                    r)))))
      @last-result)))
