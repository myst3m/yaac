(ns yaac.analyze
  (:refer-clojure :exclude [macroexpand-1 compile])
  (:require [clojure.tools.analyzer :as ana]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.tools.analyzer.env :as env]
            [clojure.tools.analyzer.utils :as ana.utils]
            [clojure.tools.analyzer.passes :as ana.passes]
            [clojure.tools.analyzer.passes
             [source-info :refer [source-info]]]
            [edamame.core :as eda]))

;;; utils
(defn read-all-string [s]
  (eda/parse-string-all s {:deref true
                           :row-key :line
                           :col-key :column
                           :end-row-key :end-line
                           :end-col-key :end-column
                           }))


;;; emitter
(defmulti -emit :op)
(defmethod -emit :invoke [{f :fn :keys [args top-level in-statement env] :as expr}]
  
  )

(defmethod -emit :do [{f :fn :keys [args top-level in-statement env] :as expr}]
  (clojure.pprint/pprint expr)
  )

(defmacro incz [x]
  (list '+ x 10))

(defn build-ns-map []
  (let [mappings {}]
    {'clojure.core {:mappings mappings
                    :alias {}
                    :ns 'clojure.core}
     'user {:mappings mappings
            :alias {}
            :ns 'user}}))

(defn global-env []
  (atom {:namespaces (build-ns-map)
         :update-ns-map! (fn update-ns-map! []
                           (swap! env/*env* assoc-in [:namespaces] (build-ns-map)))}))


;; (defn macroexpand-1 [form env]
;;   (if (and (list? form) (= (first form) 'var))
;;     (if (not= (count form) 3)
;;       (throw (ex-info (str "(var) was given " (dec (count form)) " arguments but expects 2")
;;                       (ana.utils/source-info (meta form))))
;;       (with-meta (cons 'vari (rest form) (meta form))))
;;     (ana.jvm/macroexpand-1 form env)))

(defn ^{:pass-info {:walk :post :depends #{} :after #{#'source-info}}} classify-invoke
  [{:keys [op target form tag env class] :as ast}]
  (if-not (= op :invoke)
    ast
    (let [the-fn (:fn ast)]
      (if (#{'async 'await 'resume 'return 'defer 'errdefer 'continue 'break 'unreachable 'usingnamespace} (:form the-fn))
        (assoc ast :op :statement)
        ast))))


(def default-passes
  #{#'source-info
    #'classify-invoke})

(def scheduled-default-passes
  (ana.passes/schedule default-passes))

(defn run-passes [ast]
  (scheduled-default-passes ast))

(defn analyze
  ([form] (analyze form (ana/empty-env)))
  ([form env]
   (binding [ana/macroexpand-1  ana.jvm/macroexpand-1
             ana/create-var ana.jvm/create-var
             ana/parse ana/-parse
             ana/var? var?]
     (-> (ana/analyze form env)
         (doto clojure.pprint/pprint)
         (run-passes)))))

(defn compile-form [form]
  (let [ast (analyze form)]
    (-emit ast)))

(defn compile [{[forms] :args}]
  (env/ensure (global-env) (doseq [form (read-all-string forms)]
                             (compile-form form))))



