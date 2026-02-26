;   Copyright (c) Tsutomu Miyashita. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns yaac.auth
  (:gen-class)
  (:require [clojure.string :as str]
            [zeph.client :as http]
            [yaac.util :refer [json->edn edn->json]]
            [taoensso.timbre :as log]
            [zeph.server :refer [run-server]]
            [yaac.util :as util]
            [yaac.error :as e]
            [clojure.core.async :as async :refer [<!! chan >!!]]
            [yaac.core :as yc]
            [reitit.http :as rh]
            [reitit.http.interceptors.parameters]
            ))

(defn- url-encode [s]
  (java.net.URLEncoder/encode (str s) "UTF-8"))

;; --- OIDC Discovery ---

(defn- discover-oidc
  "Fetch .well-known/openid-configuration from issuer URL"
  [issuer]
  (let [url (str (str/replace issuer #"/$" "") "/.well-known/openid-configuration")]
    (log/debug "OIDC discovery:" url)
    (-> @(http/get url {})
        :body
        (json->edn))))

;; --- Presets ---

(def presets
  {"azure" {:authorize-url-fn (fn [{:keys [tenant]}]
                                (str "https://login.microsoftonline.com/" tenant "/oauth2/v2.0/authorize"))
            :token-url-fn     (fn [{:keys [tenant]}]
                                (str "https://login.microsoftonline.com/" tenant "/oauth2/v2.0/token"))
            :default-scope    "https://graph.microsoft.com/.default"
            :extra-params     [:tenant]}})

(defn- resolve-endpoints
  "Resolve authorize-url and token-url from preset, issuer, or explicit params"
  [{:keys [preset issuer authorize-url token-url] :as params}]
  (cond
    ;; Explicit URLs
    (and authorize-url token-url)
    {:authorize-url (first authorize-url)
     :token-url (first token-url)}

    ;; Preset
    preset
    (let [p (get presets (first preset))]
      (when-not p
        (throw (e/invalid-arguments "Unknown preset" {:preset (first preset)
                                                      :available (keys presets)})))
      {:authorize-url ((:authorize-url-fn p) (update-vals params #(first %)))
       :token-url ((:token-url-fn p) (update-vals params #(first %)))
       :default-scope (:default-scope p)})

    ;; OIDC Discovery
    issuer
    (let [oidc (discover-oidc (first issuer))]
      {:authorize-url (:authorization-endpoint oidc)
       :token-url (:token-endpoint oidc)})

    :else nil))

;; --- JWT decode helper ---

(defn- base64url-decode [s]
  (let [padded (case (mod (count s) 4)
                 2 (str s "==")
                 3 (str s "=")
                 s)
        standard (-> padded (str/replace "-" "+") (str/replace "_" "/"))]
    (String. (.decode (java.util.Base64/getDecoder) standard) "UTF-8")))

(defn- decode-jwt [token]
  (try
    (let [parts (str/split token #"\.")
          header (json->edn (base64url-decode (nth parts 0)))
          payload (json->edn (base64url-decode (nth parts 1)))]
      {:header header :payload payload})
    (catch Exception _ nil)))

;; --- Callback HTML ---

(defn- html-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- jwt-field-row [k v]
  (let [ks (html-escape (name k))
        display (cond
                  (and (#{:exp :iat :nbf} k) (number? v))
                  (str "<span class=\"val-str\">" v "</span>"
                       "<span class=\"val-date\"> &rarr; "
                       (html-escape (str (java.time.Instant/ofEpochSecond (long v))))
                       "</span>")
                  (string? v)
                  (str "<span class=\"val-str\">\"" (html-escape v) "\"</span>")
                  :else
                  (str "<span class=\"val-num\">" (html-escape (str v)) "</span>"))]
    (str "<tr><td class=\"fk\">" ks "</td><td>" display "</td></tr>")))

(defn- callback-html [parsed]
  (let [token (:access-token parsed)
        decoded (when token (decode-jwt token))
        header (:header decoded)
        payload (:payload decoded)
        token-parts (when token (str/split token #"\."))
        ;; split token display into 3 colored segments
        token-display (when (and token-parts (= 3 (count token-parts)))
                        (str "<span class=\"tp-h\">" (nth token-parts 0)
                             "</span>.<span class=\"tp-p\">" (nth token-parts 1)
                             "</span>.<span class=\"tp-s\">" (nth token-parts 2) "</span>"))]
    (str "<!DOCTYPE html>
<html lang=\"en\">
<head>
<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
<title>yaac &mdash; Token</title>
<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">
<link href=\"https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;600&family=DM+Sans:wght@400;500;700&display=swap\" rel=\"stylesheet\">
<style>
*{margin:0;padding:0;box-sizing:border-box}
:root{
  --bg:#0c0e14;--surface:#13161f;--surface2:#1a1e2b;--border:#252a3a;
  --fg:#c5cde0;--fg2:#8891a5;--accent:#64d2ff;--accent2:#bf5af2;
  --green:#30d158;--orange:#ff9f0a;--red:#ff453a;--yellow:#ffd60a;
}
body{background:var(--bg);color:var(--fg);font-family:'DM Sans',sans-serif;min-height:100vh;overflow-x:hidden}
.grain{position:fixed;inset:0;opacity:.03;pointer-events:none;background-image:url(\"data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.85' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E\")}
.wrap{max-width:820px;margin:0 auto;padding:48px 24px 80px}

/* header */
.hdr{display:flex;align-items:center;gap:12px;margin-bottom:40px;padding-bottom:20px;border-bottom:1px solid var(--border)}
.hdr-icon{width:36px;height:36px;border-radius:10px;background:linear-gradient(135deg,var(--accent),var(--accent2));display:flex;align-items:center;justify-content:center;font-weight:700;font-size:14px;color:#fff;font-family:'JetBrains Mono',monospace;letter-spacing:-0.5px}
.hdr h1{font-size:18px;font-weight:700;color:#fff;letter-spacing:-.3px}
.hdr h1 span{color:var(--fg2);font-weight:400}
.badge{display:inline-block;padding:3px 10px;border-radius:100px;font-size:11px;font-weight:600;letter-spacing:.5px;text-transform:uppercase;margin-left:12px}
.badge-ok{background:rgba(48,209,88,.12);color:var(--green)}
.badge-err{background:rgba(255,69,58,.12);color:var(--red)}

/* cards */
.card{background:var(--surface);border:1px solid var(--border);border-radius:14px;margin-bottom:20px;overflow:hidden;animation:cardIn .4s ease both}
.card:nth-child(2){animation-delay:.08s}.card:nth-child(3){animation-delay:.16s}
@keyframes cardIn{from{opacity:0;transform:translateY(12px)}to{opacity:1;transform:none}}
.card-hdr{padding:16px 20px;border-bottom:1px solid var(--border);display:flex;align-items:center;gap:8px}
.card-hdr h2{font-size:13px;font-weight:600;text-transform:uppercase;letter-spacing:.8px;color:var(--fg2)}
.dot{width:7px;height:7px;border-radius:50%;flex-shrink:0}
.dot-blue{background:var(--accent)}.dot-purple{background:var(--accent2)}.dot-green{background:var(--green)}
.card-body{padding:20px}

/* token display */
.token-raw{font-family:'JetBrains Mono',monospace;font-size:12px;line-height:1.7;word-break:break-all;color:var(--fg2);padding:16px;background:var(--bg);border-radius:8px}
.tp-h{color:var(--accent)}.tp-p{color:var(--accent2)}.tp-s{color:var(--green)}

/* fields table */
.ftbl{width:100%;border-collapse:collapse;font-size:13.5px}
.ftbl tr{border-bottom:1px solid var(--border)}
.ftbl tr:last-child{border-bottom:none}
.ftbl td{padding:10px 0;vertical-align:top}
.ftbl .fk{font-family:'JetBrains Mono',monospace;font-size:12px;color:var(--accent);width:120px;white-space:nowrap;padding-right:20px;font-weight:600}
.val-str{color:var(--green)}.val-num{color:var(--orange)}.val-date{color:var(--fg2);font-size:12px;font-style:italic}

/* response fields */
.resp-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px}
.resp-item{background:var(--bg);border-radius:8px;padding:14px 16px}
.resp-label{font-size:11px;text-transform:uppercase;letter-spacing:.8px;color:var(--fg2);margin-bottom:6px;font-weight:600}
.resp-val{font-family:'JetBrains Mono',monospace;font-size:13px;color:#fff;word-break:break-all}

/* copy button */
.copy-wrap{position:relative}
.copy-btn{position:absolute;top:8px;right:8px;background:var(--surface2);border:1px solid var(--border);color:var(--fg2);font-size:11px;padding:4px 10px;border-radius:6px;cursor:pointer;font-family:'DM Sans',sans-serif;transition:all .15s}
.copy-btn:hover{background:var(--accent);color:var(--bg);border-color:var(--accent)}
.copy-btn.copied{background:var(--green);color:#000;border-color:var(--green)}
</style>
</head>
<body>
<div class=\"grain\"></div>
<div class=\"wrap\">

<div class=\"hdr\">
  <div class=\"hdr-icon\">ya</div>
  <h1>yaac <span>auth code</span></h1>
  <span class=\"badge badge-ok\">success</span>
</div>
"
         ;; -- Token Response card --
         "<div class=\"card\">
  <div class=\"card-hdr\"><div class=\"dot dot-blue\"></div><h2>Token Response</h2></div>
  <div class=\"card-body\">
    <div class=\"resp-grid\">"
         (str "<div class=\"resp-item\"><div class=\"resp-label\">Token Type</div><div class=\"resp-val\">" (html-escape (str (:token-type parsed))) "</div></div>")
         (str "<div class=\"resp-item\"><div class=\"resp-label\">Expires In</div><div class=\"resp-val\">" (:expires-in parsed) "s</div></div>")
         (str "<div class=\"resp-item\"><div class=\"resp-label\">Scope</div><div class=\"resp-val\">" (html-escape (str (:scope parsed))) "</div></div>")
         (when (:refresh-token parsed)
           (str "<div class=\"resp-item\"><div class=\"resp-label\">Refresh Token</div><div class=\"resp-val\">" (html-escape (str (:refresh-token parsed))) "</div></div>"))
         "    </div>
  </div>
</div>"

         ;; -- Access Token card --
         (when token
           (str "<div class=\"card\">
  <div class=\"card-hdr\"><div class=\"dot dot-green\"></div><h2>Access Token</h2></div>
  <div class=\"card-body\">
    <div class=\"copy-wrap\">
      <button class=\"copy-btn\" onclick=\"navigator.clipboard.writeText('" (html-escape token) "');this.textContent='Copied';this.classList.add('copied');setTimeout(()=>{this.textContent='Copy';this.classList.remove('copied')},1500)\">Copy</button>
      <div class=\"token-raw\">" (or token-display (html-escape token)) "</div>
    </div>
  </div>
</div>"))

         ;; -- JWT Header card --
         (when header
           (str "<div class=\"card\">
  <div class=\"card-hdr\"><div class=\"dot dot-purple\"></div><h2>JWT Header</h2></div>
  <div class=\"card-body\">
    <table class=\"ftbl\">"
                (apply str (map (fn [[k v]] (jwt-field-row k v)) (sort-by (comp str key) header)))
                "</table>
  </div>
</div>"))

         ;; -- JWT Payload card --
         (when payload
           (str "<div class=\"card\">
  <div class=\"card-hdr\"><div class=\"dot dot-purple\"></div><h2>JWT Payload</h2></div>
  <div class=\"card-body\">
    <table class=\"ftbl\">"
                (apply str (map (fn [[k v]] (jwt-field-row k v)) (sort-by (comp str key) payload)))
                "</table>
  </div>
</div>"))

         "</div>
</body></html>")))

;; --- Callback server for Authorization Code Flow ---

(defn- callback-app [token-url client-id client-secret redirect-uri scope pipe]
  (rh/ring-handler
   (rh/router [["/oauth2/callback" {:get (fn [{:keys [params]}]
                                           (let [{:strs [code state]} params
                                                 resp @(http/post token-url
                                                                  {:form-params {:client_id client-id
                                                                                 :scope scope
                                                                                 :code code
                                                                                 :redirect_uri redirect-uri
                                                                                 :grant_type "authorization_code"
                                                                                 :client_secret client-secret}})
                                                 body (:body resp)
                                                 parsed (json->edn body)]
                                             (>!! pipe (if (map? parsed) parsed {:error body}))
                                             {:status 200
                                              :headers {"Content-Type" "text/html; charset=utf-8"}
                                              :body (if (map? parsed)
                                                      (callback-html parsed)
                                                      (str "<pre>" body "</pre>"))}))}]])
   {:executor reitit.interceptor.sieppari/executor
    :interceptors [(reitit.http.interceptors.parameters/parameters-interceptor)]}))

;; --- Authorization Code Flow ---

(defn auth-code [{:keys [client-id client-secret redirect-uri scope port preset] :as opts}]
  (let [con (System/console)
        {:keys [authorize-url token-url default-scope] :as endpoints} (resolve-endpoints opts)
        _ (when-not (and authorize-url token-url)
            (throw (e/invalid-arguments
                    "Specify issuer=<url>, -p <preset>, or authorize-url=<url> token-url=<url>"
                    {:opts (dissoc opts :args :summary)})))
        port (let [p (or (first port) 9180)] (if (string? p) (parse-long p) p))
        client-id (or (first client-id) (and con (.readLine con "%s" (into-array ["client-id: "]))))
        client-secret (or (first client-secret) (and con (.readLine con "%s" (into-array ["client-secret: "]))))
        redirect-uri (or (first redirect-uri) (format "http://localhost:%d/oauth2/callback" port))
        scope (or (first scope) default-scope "openid")
        pipe (chan)
        preset-name (first preset)]

    (println
     (->> [""
           "------------------------------------------------------------"
           (str "Authorization Code Flow" (when preset-name (str " (" preset-name ")")))
           (str "Listening on " redirect-uri)
           ""
           "Open the following URL in your browser:"
           "------------------------------------------------------------"
           ""
           (str authorize-url "?"
                (->> {:client_id client-id
                      :response_type "code"
                      :redirect_uri (url-encode redirect-uri)
                      :scope (url-encode scope)
                      :state "12345"}
                     (map (fn [[k v]] (str (name k) "=" v)))
                     (str/join "&")))
           ""]
          (str/join \newline)))
    (let [server (run-server (callback-app token-url client-id client-secret redirect-uri scope pipe)
                             {:host "0.0.0.0" :port port})
          result (<!! pipe)]
      (Thread/sleep 1000)
      (server)
      (if (:error result)
        (throw (e/error "Token exchange failed" {:body (:error result)}))
        (yc/add-extra-fields [result]
                             :issuer (or (first (:issuer opts)) preset-name))))))

;; --- Client Credentials Flow ---

(defn auth-client [{:keys [client-id client-secret scope preset] :as opts}]
  (let [con (System/console)
        {:keys [token-url]} (resolve-endpoints opts)
        _ (when-not token-url
            (throw (e/invalid-arguments
                    "Specify issuer=<url>, -p <preset>, or token-url=<url>"
                    {:opts (dissoc opts :args :summary)})))
        client-id (or (first client-id) (and con (.readLine con "%s" (into-array ["client-id: "]))))
        client-secret (or (first client-secret) (and con (.readLine con "%s" (into-array ["client-secret: "]))))
        scope (or (first scope) (:default-scope (get presets (first preset))) "openid")]

    (log/debug "Client Credentials:" token-url)
    (let [result (-> @(http/post token-url
                                {:form-params {:client_id client-id
                                               :client_secret client-secret
                                               :grant_type "client_credentials"
                                               :scope scope}})
                     :body
                     (json->edn))]
      (yc/add-extra-fields [result]
                           :issuer (or (first (:issuer opts)) (first preset))))))

;; --- Legacy Azure handler (delegate to auth-code) ---

(defn auth-azure [{:keys [tenant] :as opts}]
  (let [tenant (or (seq tenant)
                   (let [con (System/console)]
                     (when con
                       [(.readLine con "%s" (into-array ["tenant: "]))])))]
    (when-not (seq tenant)
      (throw (e/invalid-arguments "tenant is required for azure preset" {})))
    (auth-code (assoc opts :preset ["azure"] :tenant tenant))))

;; --- Usage / Options / Routes ---

(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: auth <flow> [options] [key=value ...]"
           ""
           "Run OAuth2 flows to obtain tokens"
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Flows:"
             ""
             "  code      Authorization Code Flow (browser-based)"
             "  client    Client Credentials Flow"
             "  azure     Azure Entra preset (= code -p azure)"
             ""
             "Keys:"
             "  Common:"
             "    - issuer         OIDC issuer URL (auto-discovers endpoints)"
             "    - authorize-url  Authorization endpoint (explicit)"
             "    - token-url      Token endpoint (explicit)"
             "    - client-id"
             "    - client-secret"
             "    - redirect-uri   (default: http://localhost:9180/oauth2/callback)"
             "    - scope          (default: openid)"
             ""
             "  Azure preset (-p azure):"
             "    - tenant         Azure AD tenant ID"
             ""])
          ["Examples:"
           ""
           "# Authorization code flow with OIDC discovery"
           "  > yaac auth code issuer=https://my-idp client-id=xxx client-secret=xxx"
           ""
           "# Authorization code flow with Azure preset"
           "  > yaac auth code -p azure tenant=my-tenant client-id=xxx client-secret=xxx"
           ""
           "# Client credentials flow"
           "  > yaac auth client issuer=https://my-idp client-id=xxx client-secret=xxx"
           ""
           "# Azure (legacy shorthand)"
           "  > yaac auth azure tenant=my-tenant client-id=xxx client-secret=xxx"
           ""])
         (str/join \newline))))

(def options [["-p" "--preset NAME" "Preset (azure)"]
              ["--port" "--port PORT" "Listen port for callback (default: 9180)"
               :parse-fn parse-long]])

(def result-fields [[:extra :issuer]
                    :access-token
                    :token-type
                    :expires-in
                    :scope])

(def route
  ["auth" {:options options
           :usage usage}
   ["" {:help true}]
   ;; Authorization Code Flow
   ["|code" {:help true}]
   ["|code|{*args}" {:fields result-fields
                     :handler auth-code}]
   ;; Client Credentials Flow
   ["|client" {:help true}]
   ["|client|{*args}" {:fields result-fields
                       :handler auth-client}]
   ;; Legacy Azure shorthand
   ["|azure" {:help true}]
   ["|azure|{*args}" {:fields result-fields
                      :handler auth-azure}]])
