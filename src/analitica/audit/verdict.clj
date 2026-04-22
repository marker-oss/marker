(ns analitica.audit.verdict
  "Read-only parser for specs/002-calculation-audit/verdicts.md.

   Data model per [data-model.md §BugHypothesisVerdict](../../specs/002-calculation-audit/data-model.md).

   Parsing strategy:
     1. Read whole file, split on leading `## B-\\d+` level-2 headers.
     2. For each section, extract id/title from the header line and
        parse structured bullet fields (`- **Hypothesis**: ...`, etc.).
     3. Conclusion accepts both `:keyword` and bare-`keyword` styles and
        strips surrounding emphasis markers / back-ticks / bold.
     4. `:verdict/raw-markdown` carries the whole section text including the
        header so `audit verdict show` can echo it verbatim.

   Editing verdicts.md is **operator-manual** — the CLI never writes back."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private default-verdicts-path
  "specs/002-calculation-audit/verdicts.md")

(def ^:private valid-conclusions
  #{:refuted :confirmed :fixed :not-yet-verdicted :confirmed-deferred})

;; ---------------------------------------------------------------------------
;; Private helpers
;; ---------------------------------------------------------------------------

(defn- placeholder?
  "Return true if the field value is one of the operator-placeholder tokens
   meaning \"no value\" / \"to be filled later\".

   Accepts bare em-dash `—`, ASCII `-`, optional trailing parenthetical
   explanation (e.g. `— (исправление не требуется)`), and markdown-styled
   `*(to be filled)*` / `*tbd*`."
  [s]
  (boolean
    (when s
      (let [t (str/trim s)]
        (or (str/blank? t)
            (= "—" t)
            (= "-" t)
            ;; `— (исправление не требуется)` and similar — em-dash followed
            ;; by a parenthetical note that is itself not a real ticket ref.
            (re-matches #"^[—\-]\s*\(.*\)\s*$" t)
            (re-matches #"(?i)\*+\s*\(?to be filled\)?\s*\*+" t)
            (re-matches #"(?i)\*+\s*tbd\s*\*+" t))))))

(defn- strip-markdown-emphasis
  "Strip leading/trailing markdown emphasis (`**...**`, `*...*`, backticks)."
  [s]
  (when s
    (-> s
        str/trim
        (str/replace #"^[`*_]+" "")
        (str/replace #"[`*_]+$" "")
        str/trim)))

(defn- parse-conclusion
  "Extract conclusion keyword from a raw field value.

   Accepts inputs like:
     `:refuted`                              → :refuted
     `:confirmed-deferred`                   → :confirmed-deferred
     `**:fixed** 2026-04-22 — …`             → :fixed
     `**\\`:refuted\\` для WB** (…)`         → :refuted
     `**\\`:confirmed\\` → \\`:fixed\\` …`  → :fixed  (arrow → last wins)

   Returns nil for a blank/placeholder value. When the value contains
   multiple keywords separated by `→` (common \"was-confirmed-now-fixed\"
   pattern in the summary table), the LAST matching keyword wins."
  [raw]
  (when (and raw (not (placeholder? raw)))
    (let [normalized (str/replace raw #"[`*]" "")
          ;; Collect every occurrence of a colon-keyword, preserving order.
          matches    (re-seq #":([a-z][a-z0-9-]*)" normalized)
          keywords   (->> matches
                          (map (comp keyword second))
                          (filter valid-conclusions)
                          vec)]
      (or (peek keywords)  ;; arrow/update: last wins
          ;; Fallback: bare keyword without leading colon (only when the
          ;; entire enumerated label appears as its own word).
          (let [bare (str/lower-case (str/trim normalized))]
            (some (fn [kw]
                    (when (re-find (re-pattern (str "\\b"
                                                    (java.util.regex.Pattern/quote (name kw))
                                                    "\\b"))
                                   bare)
                      kw))
                  valid-conclusions))))))

(defn- parse-header
  "Extract id/title from a section header line like
   `## B-001 — Двойное вычитание логистики в \\`unit_economics.calculate\\``.

   Returns {:id \"B-001\" :title \"Двойное вычитание …\"} or nil if the line
   does not match the expected pattern."
  [line]
  (when-let [m (re-matches #"^##\s+(B-\d{3})\s*[—\-]\s*(.+?)\s*$" line)]
    {:id    (nth m 1)
     :title (nth m 2)}))

(defn- parse-field
  "Find the first bullet line in `section-text` matching
   `- **FieldName**: VALUE` (or **FieldName**:) and return VALUE.

   Tolerates variants: `- **Linked ticket**: —`, `**Linked tickets**: ...`,
   `**Conclusion**: **\\`:fixed\\` 2026-04-22 — …**`.

   Returns nil when the field is absent."
  [section-text field-name]
  ;; Match both with and without the leading `- ` list marker. The value
  ;; continues until the next newline (we do not attempt to capture
  ;; multi-line nested content — bullet-style fields fit on one line in
  ;; the live file, and callers who need the full body use raw-markdown).
  (let [pattern (re-pattern (str "(?im)^\\s*(?:-\\s+)?\\*\\*"
                                 (java.util.regex.Pattern/quote field-name)
                                 "s?\\*\\*\\s*:\\s*(.+?)\\s*$"))]
    (when-let [m (re-find pattern section-text)]
      (second m))))

(defn- split-sections
  "Split the full markdown text on leading-of-line `## B-\\d+` headers.

   Returns a vector of strings, where each string starts with the `## B-NNN`
   header line and includes every line up to (but not including) the next
   such header. Content before the first `## B-` header (summary table,
   front-matter) is discarded."
  [md]
  (when (seq md)
    (let [;; Use a look-ahead regex so the delimiter stays attached to the
          ;; section that follows it. `(?m)` enables multiline ^.
          parts (str/split md #"(?m)(?=^## B-\d{3}\b)")]
      (into [] (filter #(re-find #"^## B-\d{3}\b" %) parts)))))

(defn- parse-summary-table
  "Scan the prelude (text before the first `## B-` header) for the summary
   table and extract `{B-NNN → {:conclusion kw :linked-ticket s}}`.

   The table format (per live verdicts.md):
     | ID | Подозрение | Conclusion | Linked ticket |
     |---|---|---|---|
     | B-001 | ... | **`:refuted`** ... | — |

   The table is the *operator-authoritative* status roll-up; individual
   section headers often carry an earlier status (e.g. `:confirmed` with a
   trailing `### Update` block announcing `:fixed`). Treating the summary
   row as the source-of-truth avoids re-parsing update postscripts."
  [md]
  (when (seq md)
    (let [;; Find the prelude ending at the first `## B-` header (inclusive
          ;; of the table; the table lives between front-matter and the first
          ;; section).
          prelude (first (str/split md #"(?m)^## B-\d{3}\b" 2))
          lines   (str/split-lines (or prelude ""))
          row-pattern #"^\|\s*\**\s*(B-\d{3})\s*\**\s*\|\s*.+?\s*\|\s*(.+?)\s*\|\s*(.+?)\s*\|\s*$"]
      (into {}
            (keep (fn [line]
                    (when-let [m (re-matches row-pattern line)]
                      (let [id       (nth m 1)
                            concl    (parse-conclusion (nth m 2))
                            linked   (let [v (strip-markdown-emphasis (nth m 3))]
                                       (when-not (placeholder? v) v))]
                        [id (cond-> {}
                              concl  (assoc :conclusion concl)
                              linked (assoc :linked-ticket linked))]))))
            lines))))

(defn- parse-section
  "Parse a single section string into a BugHypothesisVerdict map."
  [section]
  (let [header-line (first (str/split-lines section))]
    (when-let [{:keys [id title]} (parse-header header-line)]
      (let [hypothesis    (parse-field section "Hypothesis")
            method        (parse-field section "Method")
            conclusion    (parse-conclusion (parse-field section "Conclusion"))
            rationale     (parse-field section "Rationale")
            linked-raw    (parse-field section "Linked ticket")
            closed-raw    (parse-field section "Closed-at")
            opened-raw    (parse-field section "Opened-at")
            linked-val    (when-not (placeholder? linked-raw)
                            (strip-markdown-emphasis linked-raw))
            closed-val    (when-not (placeholder? closed-raw)
                            (strip-markdown-emphasis closed-raw))
            opened-val    (when-not (placeholder? opened-raw)
                            (strip-markdown-emphasis opened-raw))]
        (cond-> {:verdict/id            id
                 :verdict/title         title
                 :verdict/conclusion    (or conclusion :not-yet-verdicted)
                 :verdict/raw-markdown  (str/trimr section)}
          (seq hypothesis) (assoc :verdict/hypothesis hypothesis)
          (seq method)     (assoc :verdict/method method)
          (seq rationale)  (assoc :verdict/rationale rationale)
          linked-val       (assoc :verdict/linked-ticket linked-val)
          closed-val       (assoc :verdict/closed-at closed-val)
          opened-val       (assoc :verdict/opened-at opened-val))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn parse-verdicts-file
  "Read `verdicts.md` and return a vector of BugHypothesisVerdict maps.

   Returns an empty vector if the file does not exist (graceful).
   Accepts an optional `path` (default: the canonical live location).

   For each verdict the summary-table row (if present) overrides the
   in-section `**Conclusion**` field — operators keep the summary up to date
   when a verdict is re-classified (e.g. `:confirmed` → `:fixed` after a
   later `### Update ...` postscript)."
  ([] (parse-verdicts-file default-verdicts-path))
  ([path]
   (let [f (io/file path)]
     (if-not (.exists f)
       []
       (let [md       (slurp f)
             summary  (parse-summary-table md)
             sections (split-sections md)
             raw      (into [] (keep parse-section) sections)]
         (mapv (fn [v]
                 (let [id      (:verdict/id v)
                       over    (get summary id)
                       concl   (:conclusion over)
                       linked  (:linked-ticket over)]
                   (cond-> v
                     concl  (assoc :verdict/conclusion concl)
                     (and linked (nil? (:verdict/linked-ticket v)))
                     (assoc :verdict/linked-ticket linked))))
               raw))))))

(defn verdict-show
  "Return the full markdown section for the given id (e.g. \"B-001\" or
   \"b-001\"). nil if the id is not found.

   The returned string includes the `## B-NNN — title` header and every
   line up to (but not including) the next `## B-` header — which means
   intervening `### Update ...` postscripts are part of the result."
  ([id] (verdict-show id default-verdicts-path))
  ([id path]
   (when (and id (seq id))
     (let [canonical (str/upper-case (str/trim id))
           verdicts  (parse-verdicts-file path)]
       (some (fn [v]
               (when (= (str/upper-case (:verdict/id v)) canonical)
                 (:verdict/raw-markdown v)))
             verdicts)))))

(defn verdicts-list
  "Return a summary vector of
   `[{:verdict/id :verdict/title :verdict/conclusion :verdict/linked-ticket}]`.

   Options:
     :conclusion-filter — keyword; if supplied, only rows whose
                          conclusion matches are returned."
  [& {:keys [conclusion-filter path]
      :or   {path default-verdicts-path}}]
  (let [rows (parse-verdicts-file path)
        pick (juxt :verdict/id :verdict/title :verdict/conclusion :verdict/linked-ticket)]
    (into []
          (comp
            (map (fn [v]
                   (zipmap [:verdict/id :verdict/title
                            :verdict/conclusion :verdict/linked-ticket]
                           (pick v))))
            (if conclusion-filter
              (filter #(= (:verdict/conclusion %) conclusion-filter))
              identity))
          rows)))
