(ns analitica.domain.cost-price
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [analitica.report.table :as table]))

;; Two maps: by article (e.g. "3452/Бежевый" -> 1590.0) and by barcode
(defonce ^:private cost-prices (atom {}))
(defonce ^:private cost-by-barcode (atom {}))

;; ---------------------------------------------------------------------------
;; 1C CSV parser
;; ---------------------------------------------------------------------------

(defn- parse-russian-number
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
  "Extract article number from 1C nomenclature: 'Платье арт. 3452' -> '3452'"
  [nom]
  (when nom
    (let [m (re-find #"арт\.\s*(\S+)" nom)]
      (when m (second m)))))

(defn- extract-color
  "Extract color from 1C characteristic: 'Бежевый, 48, 100% Полиэстер' -> 'Бежевый'"
  [char-str]
  (when (and char-str (not= (str/trim char-str) ""))
    (-> char-str
        str/trim
        (str/split #",")
        first
        str/trim)))

(defn- make-wb-article
  "Construct WB-style article from 1C data: '3452' + 'Бежевый' -> '3452/Бежевый'"
  [art-num color]
  (if (and art-num color (not= color ""))
    (str art-num "/" color)
    art-num))

(defn- parse-1c-csv-line
  "Parse a data line from 1C units.csv.
   Format: Склад,,,Номенклатура,,Характеристика,Штрихкод,Цена,Остаток
   Characteristic field may contain commas inside quotes."
  [line]
  (when (and line
             (not (str/starts-with? (str/trim line) "Параметры"))
             (not (str/starts-with? (str/trim line) "Отбор"))
             (not (str/starts-with? (str/trim line) "Склад,,,Номенклатура"))
             (not (str/starts-with? (str/trim line) "Итого"))
             (not (str/blank? (str/replace line "," ""))))
    ;; Parse CSV respecting quoted fields
    (let [parts (loop [s       line
                       fields  []
                       current ""]
                  (if (empty? s)
                    (conj fields current)
                    (let [c (first s)]
                      (cond
                        (= c \") ; start quoted field
                        (let [end-idx (str/index-of s "\"" 1)
                              quoted  (if end-idx (subs s 1 end-idx) (subs s 1))
                              rest-s  (if end-idx (subs s (inc end-idx)) "")]
                          ;; skip comma after closing quote
                          (recur (if (and (seq rest-s) (= (first rest-s) \,))
                                   (subs rest-s 1) rest-s)
                                 (conj fields quoted) ""))

                        (= c \,)
                        (recur (subs s 1) (conj fields current) "")

                        :else
                        (recur (subs s 1) fields (str current c))))))]
      (when (>= (count parts) 9)
        (let [nomenclature (nth parts 3 "")
              characteristic (nth parts 5 "")
              barcode      (str/trim (nth parts 6 ""))
              price-str    (nth parts 7 "")
              qty-str      (nth parts 8 "")
              art-num      (extract-article-number nomenclature)
              color        (extract-color characteristic)
              wb-article   (make-wb-article art-num color)
              price        (parse-russian-number price-str)]
          (when (and art-num price)
            {:article      wb-article
             :article-num  art-num
             :color        color
             :barcode      barcode
             :cost-price   price
             :quantity     (parse-russian-number qty-str)
             :nomenclature (str/trim nomenclature)
             :characteristic (str/trim characteristic)}))))))

(defn load-from-1c
  "Load cost prices from 1C units.csv export.
   Builds maps by article (WB-style) and by barcode."
  ([] (load-from-1c "1c/units.csv"))
  ([path]
   (let [f (io/file path)]
     (if (.exists f)
       (let [lines  (str/split-lines (slurp f :encoding "UTF-8"))
             parsed (->> lines
                         (map parse-1c-csv-line)
                         (remove nil?))
             by-art (->> parsed
                         (group-by :article)
                         (map (fn [[art items]]
                                [art (:cost-price (first items))]))
                         (into {}))
             by-bc  (->> parsed
                         (filter #(seq (:barcode %)))
                         (map (fn [r] [(:barcode r) (:cost-price r)]))
                         (into {}))]
         (reset! cost-prices by-art)
         (reset! cost-by-barcode by-bc)
         (println "Загружено из 1С:" (count by-art) "артикулов," (count by-bc) "баркодов")
         {:articles (count by-art) :barcodes (count by-bc)})
       (println "Файл не найден:" path)))))

;; ---------------------------------------------------------------------------
;; Simple loaders
;; ---------------------------------------------------------------------------

(defn load-from-edn
  "Load cost prices from EDN file.
   Expected format: {\"article\" 500.0}"
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (do
        (reset! cost-prices (edn/read-string (slurp f)))
        (println "Loaded" (count @cost-prices) "cost prices from" path))
      (println "Cost price file not found:" path))))

(defn load-from-csv
  "Load cost prices from simple CSV: article,cost_price"
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (let [lines (rest (str/split-lines (slurp f)))
            data  (->> lines
                       (map #(str/split % #"[,;\t]"))
                       (filter #(>= (count %) 2))
                       (map (fn [[art price]] [(str/trim art)
                                               (Double/parseDouble (str/trim price))]))
                       (into {}))]
        (reset! cost-prices data)
        (println "Loaded" (count data) "cost prices from" path))
      (println "Cost price file not found:" path))))

;; ---------------------------------------------------------------------------
;; Accessors
;; ---------------------------------------------------------------------------

(defn set-price! [article price]
  (swap! cost-prices assoc article price))

(defn average-price
  "Average cost price across all loaded articles."
  []
  (let [prices (vals @cost-prices)]
    (when (seq prices)
      (/ (reduce + prices) (count prices)))))

(defn- normalize-article
  "Canonicalise an article name for fuzzy matching across marketplaces:
   lowercase + strip trailing digit runs (size suffix). E.g.
   `6405/Голубой48` → `6405/голубой`, `1523-1/Серый` → `1523-1/серый`.
   1C CSVs omit the size; WB lowercases; YM/Ozon often include size."
  [s]
  (when s
    (-> s
        clojure.string/lower-case
        (clojure.string/replace #"\s+$" "")
        (clojure.string/replace #"\d+$" ""))))

(defonce ^:private cost-by-normalized (atom {}))

(defn- rebuild-normalized-index! []
  (reset! cost-by-normalized
          (reduce-kv (fn [acc art price]
                       (let [k (normalize-article art)]
                         (if (and k (not (contains? acc k)))
                           (assoc acc k price)
                           acc)))
                     {}
                     @cost-prices)))

;; Rebuild the normalized index whenever cost-prices changes via load-*.
;; Simple add-watch keeps the two atoms in sync without touching load sites.
(add-watch cost-prices ::normalized-index
           (fn [_ _ _ _] (rebuild-normalized-index!)))

(defn get-price
  "Get cost price by article with fallback strategy:
     1. strict match on article
     2. strict match on barcode
     3. fuzzy match: lowercase + strip size suffix (e.g. /Голубой48 → /голубой).
   Returns nil when nothing matches."
  ([article] (get-price article nil))
  ([article barcode]
   (or (get @cost-prices article)
       (when barcode (get @cost-by-barcode barcode))
       (when article (get @cost-by-normalized (normalize-article article))))))

(defn all-prices [] @cost-prices)
(defn all-barcodes [] @cost-by-barcode)

(defn report
  "Print loaded cost prices summary."
  []
  (let [prices @cost-prices]
    (table/print-summary
     "СЕБЕСТОИМОСТЬ ИЗ 1С"
     [["Артикулов" (count prices)]
      ["Баркодов"  (count @cost-by-barcode)]
      ["Мин. цена" (when (seq prices) (apply min (vals prices)))]
      ["Макс. цена" (when (seq prices) (apply max (vals prices)))]
      ["Средняя"   (when (seq prices) (/ (reduce + (vals prices)) (count prices)))]])
    (println "\n── Артикулы (первые 30) ──")
    (table/print-table
     [[:article "Артикул"] [:cost "Себестоимость"]]
     (->> prices
          (map (fn [[art cost]] {:article art :cost cost}))
          (sort-by :article)
          (take 30)))))
