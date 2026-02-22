(ns yaac.markdown
  "Regex-based Markdown → ANSI escape renderer.
   GraalVM native-image compatible (no reflection, no ServiceLoader)."
  (:require [clojure.string :as str]
            [jansi-clj.core :as jansi]))

;; ---------------------------------------------------------------------------
;; Code block protection
;; ---------------------------------------------------------------------------
;; Fenced code blocks (```) are extracted first so that Markdown syntax inside
;; them is NOT processed. They are replaced with placeholder tokens and
;; restored at the end.

(def ^:private code-block-placeholder "\u0000CB#")

(defn- protect-code-blocks
  "Extract fenced code blocks, replace with placeholders.
   Returns [text, blocks-vec]."
  [text]
  (let [blocks (atom [])
        result (str/replace text
                 #"(?ms)^```[^\n]*\n(.*?)^```"
                 (fn [m]
                   (let [idx (count @blocks)]
                     (swap! blocks conj (first m))
                     (str code-block-placeholder idx))))]
    [result @blocks]))

(defn- restore-code-blocks
  "Replace placeholders with rendered code blocks."
  [text blocks]
  (reduce-kv
    (fn [t idx block]
      (let [;; Strip ``` fences, render content as dim
            lines (str/split-lines block)
            lang-line (first lines)
            content-lines (butlast (rest lines))
            rendered (str (jansi/a :faint (str/join "\n" content-lines)))]
        (str/replace t (str code-block-placeholder idx) rendered)))
    text
    blocks))

;; ---------------------------------------------------------------------------
;; Inline / block renderers
;; ---------------------------------------------------------------------------

(defn- render-headings [text]
  (str/replace text
    #"(?m)^(#{1,3})\s+(.+)$"
    (fn [[_ hashes content]]
      (jansi/a :bold (jansi/a :underline content)))))

(defn- render-hr [text]
  (let [rule (apply str (repeat 60 "─"))]
    (str/replace text
      #"(?m)^-{3,}\s*$"
      (fn [_] (jansi/a :faint rule)))))

(defn- render-bullets [text]
  (str/replace text
    #"(?m)^([ \t]*)[-*]\s+(.+)$"
    (fn [[_ indent content]]
      (str indent "• " content))))

(defn- render-bold [text]
  (str/replace text
    #"\*\*(.+?)\*\*"
    (fn [[_ content]]
      (jansi/a :bold content))))

(defn- render-italic [text]
  (str/replace text
    #"(?<!\*)\*([^*\n]+?)\*(?!\*)"
    (fn [[_ content]]
      (jansi/a :italic content))))

(defn- render-inline-code [text]
  (str/replace text
    #"`([^`\n]+?)`"
    (fn [[_ content]]
      (jansi/fg :yellow content))))

;; ---------------------------------------------------------------------------
;; Table renderer
;; ---------------------------------------------------------------------------

(defn- parse-table-row [line]
  (->> (str/split line #"\|")
       (remove str/blank?)
       (mapv str/trim)))

(defn- separator-row? [line]
  (boolean (re-matches #"\|?[\s\-:|]+" line)))

(defn- render-table-block
  "Render a block of table lines with aligned columns."
  [lines]
  (let [rows (mapv parse-table-row lines)
        data-rows (remove (fn [r] (every? #(re-matches #"-{2,}|:-+:?|-+:" %) r)) rows)
        col-count (apply max (map count data-rows))
        ;; Calculate max width per column
        widths (reduce (fn [ws row]
                         (mapv (fn [i]
                                 (max (get ws i 0)
                                      (count (get row i ""))))
                               (range col-count)))
                       (vec (repeat col-count 0))
                       data-rows)
        fmt-row (fn [row]
                  (let [cells (mapv (fn [i]
                                     (let [cell (get row i "")]
                                       (str cell (apply str (repeat (- (get widths i 0) (count cell)) " ")))))
                                   (range col-count))]
                    (str "│ " (str/join " │ " cells) " │")))
        separator (str "├─" (str/join "─┼─" (map #(apply str (repeat % "─")) widths)) "─┤")
        top-border (str "┌─" (str/join "─┬─" (map #(apply str (repeat % "─")) widths)) "─┐")
        bottom-border (str "└─" (str/join "─┴─" (map #(apply str (repeat % "─")) widths)) "─┘")]
    (let [header (first data-rows)
          body (rest data-rows)]
      (str top-border "\n"
           (jansi/a :bold (fmt-row header)) "\n"
           separator "\n"
           (str/join "\n" (map fmt-row body)) "\n"
           bottom-border))))

(defn- render-tables
  "Find consecutive table lines (|...|) and render them."
  [text]
  (let [lines (str/split-lines text)
        ;; Group consecutive table lines
        groups (reduce
                 (fn [{:keys [result current]} line]
                   (if (re-find #"^\|" line)
                     {:result result :current (conj current line)}
                     (if (seq current)
                       {:result (conj result {:type :table :lines current})
                        :current []}
                       {:result (conj result {:type :text :line line})
                        :current []})))
                 {:result [] :current []}
                 lines)
        groups (let [{:keys [result current]} groups]
                 (if (seq current)
                   (conj result {:type :table :lines current})
                   result))]
    (->> groups
         (map (fn [{:keys [type line lines]}]
                (if (= type :table)
                  (render-table-block lines)
                  line)))
         (str/join "\n"))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn render
  "Convert Markdown text to ANSI-escaped text for terminal display."
  [text]
  (when text
    (let [[protected blocks] (protect-code-blocks text)]
      (-> protected
          render-headings
          render-tables
          render-hr
          render-bullets
          render-bold
          render-italic
          render-inline-code
          (restore-code-blocks blocks)))))
