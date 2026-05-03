(ns analitica.domain.finance
  (:require [analitica.marketplace.protocol :as proto]
            [analitica.marketplace.registry :as registry]
            [analitica.db :as db]
            [analitica.domain.cost-price :as cost-price]
            [analitica.domain.ozon-distribute :as ozon-distribute]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.time :as t]
            [analitica.util.math :as math]))

(defn- get-mp [marketplace]
  (registry/get-marketplace (or marketplace :wb)))

(defn- resolve-dates [period]
  (cond
    (keyword? period) (t/period period)
    (vector? period)  period
    :else             [(:from period) (:to period)]))

(defn- db-finance-event-date [from to mp-clause params]
  ;; Default event_date-precise query (with legacy overlap fallback).
  (db/query
    (into [(str "SELECT * FROM finance
                 WHERE 1=1" mp-clause "
                   AND ((event_date IS NOT NULL AND event_date BETWEEN ? AND ?)
                        OR (event_date IS NULL AND date_from <= ? AND date_to >= ?))
                 ORDER BY rrd_id")]
          (concat params [from to to from]))))

(defn- db-finance-ozon-overlap [from to]
  ;; Ozon's realization & service rows all carry event_date = start of
  ;; the report month, so an event_date BETWEEN filter would drop every
  ;; non-first-of-month week. Fetch by date_from/date_to overlap instead;
  ;; the post-fetch redistribute step replaces realization rows with
  ;; daily children whose event_date is real, after which we can safely
  ;; filter by [from..to].
  (db/query ["SELECT * FROM finance
              WHERE marketplace = 'ozon'
                AND date_from <= ? AND date_to >= ?
              ORDER BY rrd_id"
             to from]))

(defn- db-finance [from to & [marketplace]]
  ;; Event-precise query when event_date is populated; falls back to
  ;; overlap semantics on the weekly report period for legacy rows that
  ;; pre-date the 2026-04-23 event_date migration (event_date IS NULL).
  ;; See docs/canonical-formulas.md §Unit Economics UE.11 for context.
  (cond
    (= :ozon marketplace)
    (db-finance-ozon-overlap from to)

    marketplace
    (db-finance-event-date from to " AND marketplace = ?" [(name marketplace)])

    :else
    ;; All-MP: WB + YM via event-date (their event_date is per-day),
    ;; PLUS Ozon via overlap (Ozon stamps event_date = start-of-month
    ;; for realization, so an event-date BETWEEN filter would drop the
    ;; whole month). Concat then let redistribute-realization split the
    ;; Ozon rows daily and the in-window filter trim them.
    (concat
      (db-finance-event-date from to " AND marketplace != 'ozon'" [])
      (db-finance-ozon-overlap from to))))

(defn- in-window? [from to row]
  (when-let [d (or (:event-date row) (:event_date row))]
    (and (not (neg? (compare d from)))
         (not (pos? (compare d to))))))

(defn fetch-finance
  [period & {:keys [marketplace source] :or {source :db}}]
  (let [[from to] (resolve-dates period)
        rows      (case source
                    :db  (db-finance from to marketplace)
                    :api (proto/fetch-finance-report (get-mp marketplace) from to))]
    (cond->> rows
      ;; Ozon's /v2/finance/realization is a month-level aggregate — every
      ;; row carries event_date = start-of-month. Spread realization rows
      ;; into daily children weighted by `sales` coverage so weekly slicing
      ;; reflects the actual day-level revenue distribution. Sums are
      ;; preserved exactly. See domain.ozon-distribute.
      (or (nil? marketplace) (= :ozon marketplace))
      (ozon-distribute/redistribute-realization)

      ;; After spread, drop any Ozon row whose (post-spread) event_date
      ;; falls outside the requested window. Single-MP=:ozon: filter
      ;; everything. All-MP (nil): filter ONLY Ozon rows — WB/YM came
      ;; via event-date query so they're already in-window.
      (= :ozon marketplace)
      (filterv #(in-window? from to %))

      (nil? marketplace)
      (filterv #(or (not= "ozon" (:marketplace %))
                    (in-window? from to %))))))

;; ---------------------------------------------------------------------------
;; Aggregation
;; ---------------------------------------------------------------------------

(defn- line-cost
  "Get cost price for a finance line via article + barcode lookup."
  [line]
  (or (cost-price/get-price (:article line) (:barcode line))
      0.0))

;; RFC-3 (concept-crosswalk §2.1): canonical :operation-kind keyword.
;; E-2 backfill (2026-04-28) populated this column for every legacy row.
;; Predicates accept the kind as either keyword or string (DB read path
;; gives string), and fall back to the canonical English `:operation`
;; string for tests that construct rows inline without setting the
;; kind. The dirty Russian fallback ("Продажа"/"Возврат") is gone.
(defn- kind-string [v]
  (cond (keyword? v) (name v)
        (string?  v) v
        :else        nil))

(defn- sale-row? [row]
  (or (= "sale" (kind-string (:operation-kind row)))
      (= "sale" (:operation row))))

(defn- return-row? [row]
  (or (= "return" (kind-string (:operation-kind row)))
      (= "return" (:operation row))))

(defn- article-row
  [article lines storage-by-article]
  (let [sales-lines  (filter sale-row? lines)
        return-lines (filter return-row? lines)
        total-cost   (reduce + 0.0
                       (map #(* (line-cost %) (max 1 (or (:quantity %) 1)))
                            sales-lines))
        ;; First non-nil :marketplace wins; cross-MP article collisions
        ;; (rare but possible per Sales.7.3) collapse to whichever MP
        ;; appears first in the row order. Caller should disambiguate
        ;; by (marketplace, article) when this matters.
        marketplace  (some :marketplace lines)]
    {:article       article
     :marketplace   marketplace
     :brand         (or (:brand (first lines)) (:brand-name (first lines)))
     :subject       (or (:subject (first lines)) (:subject-name (first lines)))
     :sales-qty     (reduce + 0 (map #(or (:quantity %) 0) sales-lines))
     :returns-qty   (reduce + 0 (map #(or (:quantity %) 0) return-lines))
     :revenue       (math/round2 (reduce + 0.0 (map #(or (:retail-amount %) 0) sales-lines)))
     :wb-reward     (math/round2 (reduce + 0.0 (map #(or (:wb-reward %) 0) lines)))
     :mp-commission (math/round2 (reduce + 0.0 (map #(or (:mp-commission %) 0) sales-lines)))
     :acquiring     (math/round2 (reduce + 0.0 (map #(or (:acquiring-fee %) 0) lines)))
     :sales-pay     (math/round2 (reduce + 0.0 (map #(or (:for-pay %) 0) sales-lines)))
     :returns-pay   (math/round2 (reduce + 0.0 (map #(or (:for-pay %) 0) return-lines)))
     :spp-amount    (math/round2 (- (reduce + 0.0 (map #(or (:for-pay %) 0) sales-lines))
                                    (reduce + 0.0 (map #(or (:retail-amount %) 0) sales-lines))))
     :logistics     (math/round2 (reduce + 0.0 (map #(or (:delivery-cost %) 0) lines)))
     :penalties     (math/round2 (reduce + 0.0 (map #(or (:penalty %) 0) lines)))
     :additional    (math/round2 (reduce + 0.0 (map #(or (:additional-payment %) 0) lines)))
     :storage       (math/round2
                     (if storage-by-article
                       (get storage-by-article article 0.0)
                       (reduce + 0.0 (map #(or (:storage-fee %) 0) lines))))
     :acceptance    (math/round2 (reduce + 0.0 (map #(or (:acceptance %) 0) lines)))
     :deduction     (math/round2 (reduce + 0.0 (map #(or (:deduction %) 0) lines)))
     ;; RFC-15 / E-2: `for-pay` is non-negative for both sale and return rows;
     ;; direction comes from operation-kind. Net payout = sales − returns.
     :for-pay       (math/round2 (- (reduce + 0.0 (map #(or (:for-pay %) 0) sales-lines))
                                    (reduce + 0.0 (map #(or (:for-pay %) 0) return-lines))))
     :cost-price    (math/round2 (line-cost (first sales-lines)))
     :total-cost    (math/round2 total-cost)}))

(defn- empty-article-row
  [article storage-by-article]
  {:article       article
   :brand         nil
   :subject       nil
   :sales-qty     0
   :returns-qty   0
   :revenue       0.0
   :wb-reward     0.0
   :mp-commission 0.0
   :acquiring     0.0
   :sales-pay     0.0
   :returns-pay   0.0
   :spp-amount    0.0
   :logistics     0.0
   :penalties     0.0
   :additional    0.0
   :storage       (math/round2 (get storage-by-article article 0.0))
   :acceptance    0.0
   :deduction     0.0
   :for-pay       0.0
   :cost-price    0.0
   :total-cost    0.0})

(defn by-article
  "Per-article aggregation of finance rows.
   See docs/canonical-formulas.md §Finance.1-Finance.8 for the canonical formulas."
  [finance-data & {:keys [storage-by-article articles sort-key]
                   :or   {sort-key :for-pay}}]
  (let [grouped       (group-by :article finance-data)
        computed-rows (->> grouped
                           (map (fn [[article lines]]
                                  (article-row article lines storage-by-article))))
        rows          (if (seq articles)
                        (let [by-article-map (into {} (map (juxt :article identity) computed-rows))
                              article-list   (distinct articles)]
                          (mapv (fn [article]
                                  (or (get by-article-map article)
                                      (empty-article-row article storage-by-article)))
                                article-list))
                        (vec computed-rows))]
    (case sort-key
      :article (sort-by :article rows)
      (sort-by :for-pay > rows))))

(defn by-sku
  "Aggregate finance data by SKU (article + barcode). Optionally enrich with :tech-size."
  [finance-data & {:keys [size-map]}]
  (->> finance-data
       (group-by (fn [r] [(:article r) (:barcode r)]))
       (map (fn [[[article barcode] lines]]
              (let [sales-lines  (filter sale-row? lines)
                    return-lines (filter return-row? lines)
                    total-cost   (reduce + 0.0
                                   (map #(* (line-cost %) (max 1 (or (:quantity %) 1)))
                                        sales-lines))]
                {:article     article
                 :barcode     barcode
                 :tech-size   (when (and size-map barcode) (get size-map barcode))
                 :brand       (or (:brand (first lines)) (:brand-name (first lines)))
                 :subject     (or (:subject (first lines)) (:subject-name (first lines)))
                 :sales-qty   (reduce + 0 (map #(or (:quantity %) 0) sales-lines))
                 :returns-qty (reduce + 0 (map #(or (:quantity %) 0) return-lines))
                 :revenue     (math/round2 (reduce + 0.0 (map #(or (:retail-amount %) 0) sales-lines)))
                 :wb-reward   (math/round2 (reduce + 0.0 (map #(or (:wb-reward %) 0) lines)))
                 :mp-commission (math/round2 (reduce + 0.0 (map #(or (:mp-commission %) 0) sales-lines)))
                 :acquiring   (math/round2 (reduce + 0.0 (map #(or (:acquiring-fee %) 0) lines)))
                 :sales-pay   (math/round2 (reduce + 0.0 (map #(or (:for-pay %) 0) sales-lines)))
                 :returns-pay (math/round2 (reduce + 0.0 (map #(or (:for-pay %) 0) return-lines)))
                 :spp-amount  (math/round2 (- (reduce + 0.0 (map #(or (:for-pay %) 0) sales-lines))
                                              (reduce + 0.0 (map #(or (:retail-amount %) 0) sales-lines))))
                 :logistics   (math/round2 (reduce + 0.0 (map #(or (:delivery-cost %) 0) lines)))
                 :penalties   (math/round2 (reduce + 0.0 (map #(or (:penalty %) 0) lines)))
                 :additional  (math/round2 (reduce + 0.0 (map #(or (:additional-payment %) 0) lines)))
                 :storage     (math/round2 (reduce + 0.0 (map #(or (:storage-fee %) 0) lines)))
                 :acceptance  (math/round2 (reduce + 0.0 (map #(or (:acceptance %) 0) lines)))
                 :deduction   (math/round2 (reduce + 0.0 (map #(or (:deduction %) 0) lines)))
                 :for-pay     (math/round2 (- (reduce + 0.0 (map #(or (:for-pay %) 0) sales-lines))
                                              (Math/abs (reduce + 0.0 (map #(or (:for-pay %) 0) return-lines)))))
                 :cost-price  (math/round2 (line-cost (first sales-lines)))
                 :total-cost  (math/round2 total-cost)})))
       (sort-by :for-pay >)))

(defn totals [finance-data]
  (let [articles (by-article finance-data)]
    {:total-revenue     (math/round2 (reduce + 0.0 (map :revenue articles)))
     :total-wb-reward   (math/round2 (reduce + 0.0 (map :wb-reward articles)))
     :total-acquiring   (math/round2 (reduce + 0.0 (map :acquiring articles)))
     :total-spp         (math/round2 (reduce + 0.0 (map :spp-amount articles)))
     :total-logistics   (math/round2 (reduce + 0.0 (map :logistics articles)))
     :total-penalties   (math/round2 (reduce + 0.0 (map :penalties articles)))
     :total-storage     (math/round2 (reduce + 0.0 (map :storage articles)))
     :total-acceptance  (math/round2 (reduce + 0.0 (map :acceptance articles)))
     :total-additional  (math/round2 (reduce + 0.0 (map :additional articles)))
     :total-deduction  (math/round2 (reduce + 0.0 (map :deduction articles)))
     :total-for-pay    (math/round2 (reduce + 0.0 (map :for-pay articles)))
     :total-sales-qty   (reduce + 0 (map :sales-qty articles))
     :total-returns-qty (reduce + 0 (map :returns-qty articles))
     :articles-count    (count articles)}))

(defn by-report-id [finance-data]
  (->> finance-data
       (group-by (fn [r] (or (:report-id r) (:report_id r))))
       (map (fn [[id lines]]
              {:report-id id
               :date-from (or (:date-from (first lines)) (:date_from (first lines)))
               :date-to   (or (:date-to (first lines)) (:date_to (first lines)))
               :lines     (count lines)
               :for-pay   (math/round2 (reduce + 0.0 (map #(or (:for-pay %) 0) lines)))}))
       (sort-by :date-from)))

;; ---------------------------------------------------------------------------
;; Report
;; ---------------------------------------------------------------------------

(defn report
  [period & {:keys [marketplace source] :or {marketplace :wb source :db}}]
  (println "
Загрузка финансового отчёта...")
  (let [data    (fetch-finance period :marketplace marketplace :source source)
        summary (totals data)]

    (table/print-summary
     "ФИНАНСОВЫЙ ОТЧЁТ"
     [["Продажи (шт)"     (:total-sales-qty summary)]
      ["Возвраты (шт)"    (:total-returns-qty summary)]
      ["Выручка"          (:total-revenue summary)]
      ;; F-1 (2026-04-28): :wb-reward = ppvz_reward (PVZ pickup income).
      ["Возмещение ПВЗ"   (:total-wb-reward summary)]
      ["Компенс. СПП"     (:total-spp summary)]
      ["Логистика"        (:total-logistics summary)]
      ["Хранение"         (:total-storage summary)]
      ["Приёмка"          (:total-acceptance summary)]
      ["Штрафы"           (:total-penalties summary)]
      ["Доплаты"          (:total-additional summary)]
      ["К выплате"        (:total-for-pay summary)]
      ["Артикулов"        (:articles-count summary)]])

    (println "
── Детализация по артикулам ──")
    (table/print-table
     [[:article "Артикул"] [:sales-qty "Прод."] [:returns-qty "Возвр."]
      [:revenue "Выручка"] [:wb-reward "Возмещение ПВЗ"] [:logistics "Логистика"]
      [:storage "Хранение"] [:for-pay "К выплате"]]
     (by-article data))

    (let [reports (by-report-id data)]
      (when (> (count reports) 1)
        (println "
── По еженедельным отчётам ──")
        (table/print-table
         [[:report-id "ID отчёта"] [:date-from "С"] [:date-to "По"]
          [:lines "Строк"] [:for-pay "К выплате"]]
         reports)))

    summary))

;; ---------------------------------------------------------------------------
;; Export
;; ---------------------------------------------------------------------------

(def ^:private finance-export-cols
  [[:article "Артикул"] [:brand "Бренд"] [:subject "Предмет"]
   [:sales-qty "Продажи шт"] [:returns-qty "Возвраты шт"]
   [:revenue "Выручка"] [:wb-reward "Возмещение ПВЗ"] [:mp-commission "Комиссия МП"]
   [:acquiring "Эквайринг"] [:spp-amount "Компенс СПП"]
   [:logistics "Логистика"] [:storage "Хранение"] [:acceptance "Приёмка"]
   [:penalties "Штрафы"] [:additional "Доплаты"]
   [:sales-pay "Выплата прод"] [:returns-pay "Выплата возвр"]
   [:for-pay "К выплате"]
   [:cost-price "Себест/шт"] [:total-cost "Себест итого"]])

(def ^:private finance-summary-cols
  [[:metric "Показатель"] [:value "Значение"]])

(defn- finance-summary-rows [summary]
  [{:metric "Продажи (шт)"     :value (:total-sales-qty summary)}
   {:metric "Возвраты (шт)"    :value (:total-returns-qty summary)}
   {:metric "Выручка"          :value (:total-revenue summary)}
   {:metric "Возмещение ПВЗ"   :value (:total-wb-reward summary)}
   {:metric "Компенс. СПП"     :value (:total-spp summary)}
   {:metric "Логистика"        :value (:total-logistics summary)}
   {:metric "Хранение"         :value (:total-storage summary)}
   {:metric "Приёмка"          :value (:total-acceptance summary)}
   {:metric "Штрафы"           :value (:total-penalties summary)}
   {:metric "Доплаты"          :value (:total-additional summary)}
   {:metric "К выплате"        :value (:total-for-pay summary)}
   {:metric "Артикулов"        :value (:articles-count summary)}])

(defn export-csv [period path & opts]
  (let [data (apply fetch-finance period opts)]
    (export/to-csv path finance-export-cols (by-article data))))

(defn export-excel [period path & opts]
  (let [data    (apply fetch-finance period opts)
        summary (totals data)]
    (export/to-excel path
                     [{:name "By Article" :cols finance-export-cols :rows (by-article data)}
                      {:name "By Report"
                       :cols [[:report-id "ID"] [:date-from "From"] [:date-to "To"]
                              [:lines "Lines"] [:for-pay "Payout"]]
                       :rows (by-report-id data)}
                      {:name "Summary" :cols finance-summary-cols
                       :rows (finance-summary-rows summary)}])))
