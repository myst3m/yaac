(ns yaac.a2a.oauth
  "OAuth 2.0 Authorization Code + PKCE flow for the Anypoint A2A In-Task
   Authorization Code policy. Given an authChallenge extracted from an
   auth-required Task, launches the browser, listens on the redirect URI,
   exchanges the code for a token, and returns the parsed token response.

   The challenge shape mirrors the policy doc:
     {:authorizationEndpoint, :tokenEndpoint, :scopes, :redirectUri,
      :responseType, :codeChallengeMethod, :audience, :secondaryAuthProvider,
      :bodyEncoding}
   `clientId` is intentionally NOT carried by the policy and must be supplied
   by the caller (CLI flag or env var)."
  (:require [clojure.string :as str]
            [clojure.core.async :as async :refer [<!! >!! chan timeout alts!!]]
            [zeph.client :as http]
            [zeph.server :refer [run-server]]
            [reitit.http :as rh]
            [reitit.http.interceptors.parameters :as ip]
            [reitit.interceptor.sieppari :as sieppari]
            [yaac.util :refer [json->edn]])
  (:import [java.net URI URLEncoder]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]))

;; --- Helpers ---------------------------------------------------------------

(defn- url-encode [s] (URLEncoder/encode (str s) "UTF-8"))

(defn- random-bytes [^long n]
  (let [b (byte-array n)]
    (.nextBytes (SecureRandom.) b)
    b))

(defn- b64url [^bytes b]
  (-> (Base64/getUrlEncoder) .withoutPadding (.encodeToString b)))

(defn- sha256 [^String s]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))

(defn generate-pkce
  "RFC 7636 PKCE pair (S256). Returns {:verifier :challenge :method}."
  []
  (let [verifier (b64url (random-bytes 32))]
    {:verifier verifier
     :challenge (b64url (sha256 verifier))
     :method "S256"}))

(defn- random-state [] (b64url (random-bytes 16)))

(defn- parse-localhost-callback
  "Decompose a localhost redirectUri into :port + :path. Errors if non-local
   (the policy must be configured with a localhost redirect for automation
   to work)."
  [redirect-uri]
  (let [u (URI. redirect-uri)
        host (.getHost u)
        scheme (.getScheme u)
        port (let [p (.getPort u)]
               (cond
                 (pos? p) p
                 (= "https" scheme) 443
                 :else 80))
        path (let [p (.getPath u)] (if (str/blank? p) "/" p))]
    (when-not (#{"localhost" "127.0.0.1" "::1"} host)
      (throw (ex-info (format "redirectUri host '%s' is not local. Automated flow needs a localhost callback — complete the OAuth in your browser yourself and resubmit with /auth <token>." host)
                      {:redirect-uri redirect-uri :host host})))
    {:port port :path path}))

(defn- spawn [cmd]
  (try (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;" (into-array String cmd))
       true
       (catch Throwable _ false)))

(defn open-browser
  "Best-effort browser launch using xdg-open / open / cmd. Returns true on
   spawn success, false otherwise — caller should still print the URL."
  [^String url]
  (let [os (str/lower-case (System/getProperty "os.name" ""))]
    (cond
      (str/includes? os "mac") (spawn ["open" url])
      (str/includes? os "win") (spawn ["cmd" "/c" "start" "" url])
      :else (or (spawn ["xdg-open" url])
                (spawn ["wslview" url])))))

(defn build-authorize-url
  [{:keys [authorize-url client-id redirect-uri scope state pkce response-type audience]
    :or {response-type "code"}}]
  (let [scope-str (if (sequential? scope) (str/join " " scope) (str scope))
        params (cond-> [(str "response_type=" (url-encode response-type))
                        (str "client_id=" (url-encode client-id))
                        (str "redirect_uri=" (url-encode redirect-uri))
                        (str "state=" (url-encode state))]
                 (seq scope-str) (conj (str "scope=" (url-encode scope-str)))
                 audience        (conj (str "audience=" (url-encode audience)))
                 pkce            (->
                                  (conj (str "code_challenge=" (url-encode (:challenge pkce))))
                                  (conj (str "code_challenge_method=" (url-encode (:method pkce))))))]
    (str authorize-url "?" (str/join "&" params))))

(defn- success-html [provider]
  (str "<!doctype html><meta charset=utf-8>
<style>body{font-family:system-ui;max-width:560px;margin:80px auto;padding:32px;text-align:center;color:#222}
.ok{color:#0a7d3a;font-size:64px;margin:0}
h1{margin:8px 0 16px;font-weight:500}
.hint{color:#666;font-size:14px}</style>
<p class=ok>&check;</p><h1>Authentication successful</h1>
<p>You can close this window and return to <code>yaac</code>"
       (when provider (str " (" provider ")")) ".</p>
<p class=hint>The terminal is processing your token.</p>"))

(defn- error-html [error description]
  (str "<!doctype html><meta charset=utf-8>
<style>body{font-family:system-ui;max-width:560px;margin:80px auto;padding:32px;color:#222}
.bad{color:#b00020;font-size:48px;margin:0}</style>
<p class=bad>&times;</p><h1>Authentication failed</h1>
<p><b>" (or error "error") "</b>"
       (when description (str ": " description)) "</p>"))

(defn- callback-router [{:keys [expected-state pipe path provider]}]
  (rh/ring-handler
   (rh/router
    [[path {:get (fn [{:keys [params]}]
                   (let [{:strs [code state error error_description]} params]
                     (cond
                       error
                       (do (>!! pipe {:error error :description error_description})
                           {:status 400
                            :headers {"Content-Type" "text/html; charset=utf-8"}
                            :body (error-html error error_description)})

                       (not= state expected-state)
                       (do (>!! pipe {:error "state_mismatch"
                                      :description "OAuth state parameter did not match"})
                           {:status 400
                            :headers {"Content-Type" "text/html; charset=utf-8"}
                            :body (error-html "state_mismatch" "The state value did not match — possible CSRF.")})

                       :else
                       (do (>!! pipe {:code code})
                           {:status 200
                            :headers {"Content-Type" "text/html; charset=utf-8"}
                            :body (success-html provider)}))))}]])
   {:executor sieppari/executor
    :interceptors [(ip/parameters-interceptor)]}))

(defn- exchange-token!
  [{:keys [token-url client-id client-secret redirect-uri code verifier audience]}]
  (let [form (cond-> {"grant_type" "authorization_code"
                      "code" code
                      "redirect_uri" redirect-uri
                      "client_id" client-id}
               client-secret (assoc "client_secret" client-secret)
               verifier      (assoc "code_verifier" verifier)
               audience      (assoc "audience" audience))
        resp @(http/post token-url
                         {:headers {"Content-Type" "application/x-www-form-urlencoded"
                                    "Accept" "application/json"}
                          :form-params form})
        body (:body resp)
        ;; Pass nil mode to preserve snake_case keys (OAuth uses access_token,
        ;; token_type, expires_in — yaac.util/json->edn default :kebab would
        ;; mangle them to :access-token etc.).
        parsed (try (json->edn nil body) (catch Throwable _ nil))]
    (cond
      (and (map? parsed) (:access_token parsed)) parsed
      (>= (:status resp 0) 400)
      (throw (ex-info (format "Token exchange failed (HTTP %s)" (:status resp))
                      {:status (:status resp) :body body}))
      :else
      (throw (ex-info "Token endpoint returned no access_token"
                      {:body body :parsed parsed})))))

(defn run-auth-code-flow!
  "Drive the full Authorization Code (+ PKCE) flow from an A2A authChallenge.

   `creds`  — {:client-id, :client-secret (optional), :pkce? (default true)}
   `on-step` — (fn [k m]) called with progress signals:
     :listening {:redirect-uri}  :browser-opened {:authorize-url}
     :awaiting-code {:timeout-secs}  :exchanging {}  :done {:expires-in}

   Returns the parsed token map (must contain :access_token), or throws.
   Default callback wait is 5 minutes."
  [challenge {:keys [client-id client-secret pkce? timeout-secs]
              :or {pkce? true timeout-secs 300}}
   on-step]
  (let [{:keys [authorizationEndpoint tokenEndpoint scopes redirectUri
                responseType codeChallengeMethod audience secondaryAuthProvider]} challenge]
    (when-not authorizationEndpoint
      (throw (ex-info "authChallenge.authorizationEndpoint is missing" {:challenge challenge})))
    (when-not tokenEndpoint
      (throw (ex-info "authChallenge.tokenEndpoint is missing" {:challenge challenge})))
    (when-not redirectUri
      (throw (ex-info "authChallenge.redirectUri is missing" {:challenge challenge})))
    (when (str/blank? client-id)
      (throw (ex-info "client-id is required. Set YAAC_A2A_CLIENT_ID or use /auth --client-id <id>."
                      {:provider secondaryAuthProvider})))
    (let [pkce-allowed? (or (nil? codeChallengeMethod) (= "S256" codeChallengeMethod))
          pkce (when (and pkce? pkce-allowed?) (generate-pkce))
          state (random-state)
          {:keys [port path]} (parse-localhost-callback redirectUri)
          authorize-url (build-authorize-url
                         {:authorize-url authorizationEndpoint
                          :client-id client-id
                          :redirect-uri redirectUri
                          :scope scopes
                          :state state
                          :pkce pkce
                          :response-type (or responseType "code")
                          :audience audience})
          pipe (chan 1)
          server (run-server (callback-router {:expected-state state
                                               :pipe pipe
                                               :path path
                                               :provider secondaryAuthProvider})
                             {:host "0.0.0.0" :port port})]
      (try
        (on-step :listening {:redirect-uri redirectUri})
        (let [opened? (open-browser authorize-url)]
          (on-step :browser-opened {:authorize-url authorize-url :opened? opened?}))
        (on-step :awaiting-code {:timeout-secs timeout-secs})
        (let [[result _] (alts!! [pipe (timeout (* timeout-secs 1000))])
              {:keys [code error description]} result]
          (cond
            (nil? result)
            (throw (ex-info (format "Timed out after %d seconds waiting for OAuth callback." timeout-secs) {}))

            error
            (throw (ex-info (str "OAuth error: " error
                                 (when description (str " — " description)))
                            {:error error :description description}))

            :else
            (do
              (on-step :exchanging {})
              (let [token (exchange-token!
                           {:token-url tokenEndpoint
                            :client-id client-id
                            :client-secret client-secret
                            :redirect-uri redirectUri
                            :code code
                            :verifier (:verifier pkce)
                            :audience audience})]
                (on-step :done {:expires-in (:expires_in token)})
                token))))
        (finally
          (try (server) (catch Throwable _ nil)))))))
