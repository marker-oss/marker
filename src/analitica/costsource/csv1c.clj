(ns analitica.costsource.csv1c
  "CostSource provider for 1C's `units.csv` export.

   Format (columns): Склад,,,Номенклатура,,Характеристика,Штрихкод,Цена,Остаток.
   Values may be quoted to carry commas; prices use Russian format like
   `1,590` or `22.000`. `Характеристика` carries one or more CSV-escaped
   attributes (color, size, composition); we extract color as the first
   comma-separated token, keep the full string as `:characteristic`.

   WB-style article is reconstructed as `<art-num>/<color>`, falling back
   to just `<art-num>` when color is absent. Downstream dedup / per-
   barcode lookup happens in the common ingest pipeline."
  (:require [analitica.costsource.protocol :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Low-level parsing (moved from analitica.domain.cost-price so it can be
;; reused by any CostSource built on top of a 1C CSV export).
;; ---------------------------------------------------------------------------

(defn parse-russian-number
  "Parse Russian number format: '1,590' -> 1590.0, '22.000' -> 22.0"
  [s]
  (when (and s (not= (str/trim s) ""))
    (-> s
        str/trim
        (str/replace "\"" "")
        (str/replace " " "")
        (str/replace "," "")
        Double/parseDouble)))

(defn- extract-article-number
  "Extract article number from 1C nomenclature: 'Платье арт. 3452' -> '3452'."
  [nom]
  (when nom
    (let [m (re-find #"арт\.\s*(\S+)" nom)]
      (when m (second m)))))

(defn- extract-color
  "Extract color from 1C characteristic: 'Бежевый, 48, 100% Полиэстер' -> 'Бежевый'."
  [char-str]
  (when (and char-str (not= (str/trim char-str) ""))
    (-> char-str
        str/trim
        (str/split #",")
        first
        str/trim)))

(defn- make-wb-article
  "Construct WB-style article from 1C data: '3452' + 'Бежевый' -> '3452/Бежевый'."
  [art-num color]
  (if (and art-num color (not= color ""))
    (str art-num "/" color)
    art-num))

(defn- split-csv-respecting-quotes [line]
  (loop [s       line
         fields  []
         current ""]
    (if (empty? s)
      (conj fields current)
      (let [c (first s)]
        (cond
          (= c \")
          (let [end-idx (str/index-of s "\"" 1)
                quoted  (if end-idx (subs s 1 end-idx) (subs s 1))
                rest-s  (if end-idx (subs s (inc end-idx)) "")]
            (recur (if (and (seq rest-s) (= (first rest-s) \,))
                     (subs rest-s 1) rest-s)
                   (conj fields quoted) ""))

          (= c \,)
          (recur (subs s 1) (conj fields current) "")

          :else
          (recur (subs s 1) fields (str current c)))))))

(defn parse-line
  "Parse one CSV line. Returns a raw cost-price record (see
   costsource.protocol ns doc for shape), or nil for skippable lines
   (headers, blanks, totals)."
  [line]
  (when (and line
             (not (str/starts-with? (str/trim line) "Параметры"))
             (not (str/starts-with? (str/trim line) "Отбор"))
             (not (str/starts-with? (str/trim line) "Склад,,,Номенклатура"))
             (not (str/starts-with? (str/trim line) "Итого"))
             (not (str/blank? (str/replace line "," ""))))
    (let [parts (split-csv-respecting-quotes line)]
      (when (>= (count parts) 9)
        (let [nomenclature   (nth parts 3 "")
              characteristic (nth parts 5 "")
              barcode        (str/trim (nth parts 6 ""))
              price-str      (nth parts 7 "")
              qty-str        (nth parts 8 "")
              art-num        (extract-article-number nomenclature)
              color          (extract-color characteristic)
              wb-article     (make-wb-article art-num color)
              price          (parse-russian-number price-str)]
          (when (and art-num price)
            {:article        wb-article
             :article-num    art-num
             :color          color
             :barcode        (or barcode "")
             :cost-price     price
             :quantity       (parse-russian-number qty-str)
             :nomenclature   (str/trim nomenclature)
             :characteristic (str/trim characteristic)}))))))

(defn parse-file
  "Read a 1C CSV file and return a vector of parsed records.
   Bad lines are silently dropped (preserves current behaviour).
   Throws FileNotFoundException if the path is absent."
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (->> (str/split-lines (slurp f :encoding "UTF-8"))
           (map parse-line)
           (remove nil?)
           vec)
      (throw (ex-info "1C CSV not found"
                      {:path (str f) :cause :file-not-found})))))

(defn- header-or-meta?
  "Lines that parse-line intentionally skips (headers, totals, meta, BOM).
   Used by parse-file-with-diagnostics so they show up as :skipped, not
   :error — failure to parse a header row is not a problem.

   Strips a leading BOM (U+FEFF) before checking. Also strips leading
   commas before label matching, since 1C indents metadata under empty
   leading columns: ',,Вид цены: Себестоимость,,,…' and
   ',,Склад: Основной склад,,,…' should be :skipped, not :missing-article."
  [line]
  (let [t (-> (or line "")
              str/trim
              (str/replace #"^﻿" ""))
        ;; non-empty content with leading-comma indent stripped
        body (str/triml (str/replace t #"^[, ]+" ""))]
    (or (str/blank? (str/replace t "," ""))
        (str/starts-with? t "Параметры")
        (str/starts-with? t "Отбор")
        (str/starts-with? t "Склад,,,Номенклатура")
        (str/starts-with? t "Итого")
        ;; Indented metadata labels (any leading-comma prefix tolerated):
        (str/starts-with? body "Вид цены:")
        (str/starts-with? body "Склад:")
        (str/starts-with? body "Дата отчёта:")
        (str/starts-with? body "Период:")
        (str/starts-with? body "Параметры:"))))

(defn parse-file-with-diagnostics
  "Like parse-file but reports per-line outcome. Returns
       {:rows [...]            ; successfully parsed records
        :errors [{:line N :raw S :reason kw} ...]
        :skipped N             ; headers / blanks / totals
        :total-lines N}

   Reasons:
     :too-few-columns         row had < 9 comma-separated fields
     :missing-article         article column was empty / unparseable
     :missing-cost-price      cost-price column blank or non-numeric

   File-level errors throw (caller is expected to catch and 4xx)."
  [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info "1C CSV not found" {:path (str f) :cause :file-not-found})))
    (let [lines (str/split-lines (slurp f :encoding "UTF-8"))]
      (loop [idx 0 remaining lines rows [] errors [] skipped 0]
        (if-let [line (first remaining)]
          (cond
            (header-or-meta? line)
            (recur (inc idx) (rest remaining) rows errors (inc skipped))

            :else
            (let [parts (split-csv-respecting-quotes line)]
              (cond
                (< (count parts) 9)
                (recur (inc idx) (rest remaining) rows
                       (conj errors {:line (inc idx) :raw line :reason :too-few-columns})
                       skipped)

                :else
                (let [nomenclature (nth parts 3 "")
                      art-num      (extract-article-number nomenclature)
                      price        (parse-russian-number (nth parts 7 ""))
                      parsed       (parse-line line)]
                  (cond
                    (nil? art-num)
                    (recur (inc idx) (rest remaining) rows
                           (conj errors {:line (inc idx) :raw line :reason :missing-article})
                           skipped)
                    (nil? price)
                    (recur (inc idx) (rest remaining) rows
                           (conj errors {:line (inc idx) :raw line :reason :missing-cost-price})
                           skipped)
                    parsed
                    (recur (inc idx) (rest remaining) (conj rows parsed) errors skipped)
                    :else
                    (recur (inc idx) (rest remaining) rows
                           (conj errors {:line (inc idx) :raw line :reason :unparseable})
                           skipped))))))
          {:rows         rows
           :errors       errors
           :skipped      skipped
           :total-lines  (count lines)})))))

;; ---------------------------------------------------------------------------
;; CostSource implementation
;; ---------------------------------------------------------------------------

(defrecord Csv1cSource [path]
  p/CostSource
  (source-id [_] :cost-1c-csv)
  (fetch-cost-prices [_] (parse-file path)))

(defn make-source
  "Construct a CostSource backed by a 1C CSV file at `path`.
   Pass no arg to default to `1c/units.csv` (repo-local bootstrap file)."
  ([] (make-source "1c/units.csv"))
  ([path] (->Csv1cSource path)))
