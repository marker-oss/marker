(ns analitica.domain.unit-economics
  (:require [analitica.db :as db]
            [analitica.domain.finance :as finance]
            [analitica.domain.cost-price :as cost-price]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [clojure.string :as str]
            [analitica.util.math :as math]
            [analitica.util.time :as t]))

(defn- ensure-finance-coverage!
  [finance-data from to]
  (let [pick-date  (fn [k1 k2 m] (or (get m k1) (get m k2)))
        min-str    (fn [xs] (reduce #(if (neg? (compare %1 %2)) %1 %2) xs))
        max-str    (fn [xs] (reduce #(if (pos? (compare %1 %2)) %1 %2) xs))
        ;; Coverage is the widest span across event_date and the legacy
        ;; report-period bounds (date_from/date_to). Ozon realization is
        ;; month-aggregated, so event_date = month-first for every row;
        ;; the report-period bounds cover the whole month. Taking the
        ;; widest span lets a mid-month query pass the coverage check
        ;; while still catching genuine data gaps (e.g. no rows for part
        ;; of the period).
        candidates-from (->> finance-data
                             (mapcat (fn [r]
                                       [(pick-date :event-date :event_date r)
                                        (pick-date :date-from :date_from r)]))
                             (remove nil?))
        candidates-to   (->> finance-data
                             (mapcat (fn [r]
                                       [(pick-date :event-date :event_date r)
                                        (pick-date :date-to :date_to r)]))
                             (remove nil?))
        min-from   (when (seq candidates-from) (min-str candidates-from))
        max-to     (when (seq candidates-to) (max-str candidates-to))]
    (cond
      (empty? finance-data)
      (do
        (println (str "\n! No finance data found for period " from " .. " to "."))
        (println "  Please sync finance first, for example:")
        (println (str "  clojure -M -m analitica.cli sync finance -f " from " -t " to))
        (throw (ex-info "Finance data is missing for requested period"
                        {:from from :to to})))

      (or (and min-from (pos? (compare min-from from)))
          (and max-to (neg? (compare max-to to))))
      (do
        (println (str "\n! Finance data does not fully cover requested period " from " .. " to "."))
        (when (or min-from max-to)
          (println (str "  Available range in DB: "
                        (or min-from "?") " .. " (or max-to "?"))))
        (println "  Consider syncing finance for the missing dates.")
        (throw (ex-info "Finance data does not fully cover requested period"
                        {:from from :to to :min-from min-from :max-to max-to}))))))

(defn- article-basis-articles
  [finance-data storage-by-article]
  (->> (concat (map :article finance-data)
               (keys (or storage-by-article {})))
       (remove nil?)
       (remove str/blank?)
       (into (sorted-set))
       vec))


(defn calculate
  "Row-level unit economics per article.

   Canonical definition: see docs/canonical-formulas.md §Unit Economics
   (UE.1–UE.7). Every metric produced by this function has a 5-point
   block in the canon with formula, economic justification, inputs,
   edge cases, and a test pointer.

   Keyword args:
     :storage-by-article   {article -> paid-storage.cost}  (per canonical §storage)
     :ad-spend-by-article  {article -> ad-spend total}     (per UE.5)
     :basis  :article (default) | :for-pay                 (sort order)

   Returns seq of per-article rows, each carrying the 34 metrics listed
   in UE.2–UE.7. Passes through the finance-row monetary fields and adds
   the UE-derived metrics."
  [finance-data & {:keys [storage-by-article ad-spend-by-article basis]
                   :or   {basis :article}}]
  (let [articles (when (= basis :article)
                   (article-basis-articles finance-data storage-by-article))
        by-art   (finance/by-article finance-data
                                     :storage-by-article storage-by-article
                                     :articles articles
                                     :sort-key (if (= basis :article) :article :for-pay))
        rows     (->> by-art
                      (map (fn [{:keys [sales-qty returns-qty revenue wb-reward wb-commission
                                        acquiring logistics storage acceptance penalties
                                        deduction additional
                                        for-pay total-cost spp-amount] :as row}]
                             (let [ops                (+ sales-qty returns-qty)
                                   net-qty            (max 1 (- sales-qty returns-qty))
                                   total-ops          (max 1 ops)
                                   wb-reward-v        (or wb-reward 0)
                                   logistics-v        (or logistics 0)
                                   storage-v          (or storage 0)
                                   acceptance-v       (or acceptance 0)
                                   penalties-v        (or penalties 0)
                                   acquiring-v        (or acquiring 0)
                                   deduction-v        (or deduction 0)
                                   additional-v       (or additional 0)
                                   ad-spend-v         (if ad-spend-by-article
                                                        (get ad-spend-by-article (:article row) 0.0)
                                                        0.0)
                                   total-wb-costs     (+ wb-reward-v logistics-v storage-v
                                                         acceptance-v penalties-v acquiring-v
                                                         deduction-v)
                                   spp-v              (or spp-amount 0)
                                   profit             (- for-pay total-cost
                                                         logistics-v storage-v penalties-v
                                                         acceptance-v deduction-v ad-spend-v
                                                         (- additional-v))
                                   revenue-per-unit   (math/round2 (math/safe-div revenue sales-qty))
                                   reward-per-unit    (math/round2 (math/safe-div wb-reward-v sales-qty))
                                   logistics-per-op   (math/round2 (math/safe-div logistics-v total-ops))
                                   logistics-per-unit (math/round2 (math/safe-div logistics-v net-qty))
                                   storage-per-unit   (math/round2 (math/safe-div storage-v net-qty))
                                   accept-per-unit    (math/round2 (math/safe-div acceptance-v net-qty))
                                   acquiring-per-unit (math/round2 (math/safe-div acquiring-v sales-qty))
                                   cost-per-unit      (math/round2 (math/safe-div total-cost sales-qty))
                                   payout-per-unit    (math/round2 (math/safe-div for-pay net-qty))
                                   profit-per-unit    (math/round2 (math/safe-div profit net-qty))
                                   margin-pct         (math/percentage profit revenue)
                                   buyout-rate        (math/percentage sales-qty ops)
                                   wb-cost-pct        (math/percentage total-wb-costs revenue)
                                   cogs-pct           (math/percentage total-cost revenue)
                                   logistics-pct      (math/percentage logistics-v revenue)
                                   drr-pct            (math/percentage ad-spend-v revenue)]
                               (assoc row
                                      :total-wb-costs     (math/round2 total-wb-costs)
                                      :spp-compensation   (math/round2 spp-v)
                                      :ad-spend           (math/round2 ad-spend-v)
                                      :profit             (math/round2 profit)
                                      :revenue-per-unit   revenue-per-unit
                                      :reward-per-unit    reward-per-unit
                                      :logistics-per-op   logistics-per-op
                                      :logistics-per-unit logistics-per-unit
                                      :storage-per-unit   storage-per-unit
                                      :accept-per-unit    accept-per-unit
                                      :acquiring-per-unit acquiring-per-unit
                                      :cost-per-unit      cost-per-unit
                                      :payout-per-unit    payout-per-unit
                                      :profit-per-unit    profit-per-unit
                                      :buyout-rate        buyout-rate
                                      :margin-pct         margin-pct
                                      :wb-cost-pct        wb-cost-pct
                                      :cogs-pct           cogs-pct
                                      :logistics-pct      logistics-pct
                                      :drr-pct            drr-pct)))))]
    (if (= basis :article)
      (sort-by :article rows)
      (sort-by :profit > rows))))

(defn totals
  "Period-level unit-economics rollup.

   Canonical definition: see docs/canonical-formulas.md §Unit Economics
   UE.8 (summary totals) and UE.9 (derived summary metrics).

   Input: seq of rows from `calculate`. Output: map with 22 summary keys.
   Note: `:total-profit` may differ from `pnl/calculate`'s `:net-profit`
   by up to 2 kopek per article due to independent rounding — see UE.8
   edge cases."
  [ue-data]
  (let [sum (fn [k] (reduce + 0.0 (map #(or (k %) 0) ue-data)))
        total-revenue    (sum :revenue)
        total-for-pay    (sum :for-pay)
        total-cost       (sum :total-cost)
        total-profit     (sum :profit)
        total-logistics  (sum :logistics)
        total-storage    (sum :storage)
        total-wb-reward  (sum :wb-reward)
        total-acceptance (sum :acceptance)
        total-penalties  (sum :penalties)
        total-acquiring  (sum :acquiring)
        total-deduction  (sum :deduction)
        total-additional (sum :additional)
        total-ad-spend   (sum :ad-spend)
        total-wb-costs   (sum :total-wb-costs)
        total-spp        (sum :spp-compensation)
        sales-qty        (reduce + 0 (map :sales-qty ue-data))
        returns-qty      (reduce + 0 (map :returns-qty ue-data))
        net-qty          (- sales-qty returns-qty)]
    {:total-revenue    (math/round2 total-revenue)
     :total-wb-reward  (math/round2 total-wb-reward)
     :total-logistics  (math/round2 total-logistics)
     :total-storage    (math/round2 total-storage)
     :total-acceptance (math/round2 total-acceptance)
     :total-penalties  (math/round2 total-penalties)
     :total-acquiring  (math/round2 total-acquiring)
     :total-deduction  (math/round2 total-deduction)
     :total-additional (math/round2 total-additional)
     :total-ad-spend   (math/round2 total-ad-spend)
     :total-wb-costs   (math/round2 total-wb-costs)
     :total-spp        (math/round2 total-spp)
     :total-for-pay    (math/round2 total-for-pay)
     :total-cost       (math/round2 total-cost)
     :total-profit     (math/round2 total-profit)
     :margin-pct       (math/percentage total-profit total-revenue)
     :wb-cost-pct      (math/percentage total-wb-costs total-revenue)
     :cogs-pct         (math/percentage total-cost total-revenue)
     :drr-pct          (math/percentage total-ad-spend total-revenue)
     :profit-per-sale  (math/round2 (math/safe-div total-profit net-qty))
     :avg-check        (math/round2 (math/safe-div total-revenue sales-qty))
     :sales-qty        sales-qty
     :returns-qty      returns-qty
     :cost-loaded      (pos? total-cost)}))

(defn report
  [period & {:keys [marketplace source basis]
             :or   {source :db basis :article}}]
  (println "\nЗагрузка данных для юнит-экономики...")
  (let [[from to] (t/resolve-period period)
        fin-data (finance/fetch-finance period :marketplace marketplace :source source)
        _        (ensure-finance-coverage! fin-data from to)
        storage-map (let [rows (db/storage-by-article from to :marketplace marketplace)]
                      (when (seq rows)
                        (into {} (map (juxt :article :storage-cost) rows))))
        ad-map   (let [rows (db/ad-spend-by-article from to :marketplace marketplace)]
                   (when (seq rows)
                     (into {} (map (juxt :article :ad-spend) rows))))
        ue-data  (calculate fin-data
                            :storage-by-article storage-map
                            :ad-spend-by-article ad-map
                            :basis basis)
        s        (totals ue-data)]

    (when-not (:cost-loaded s)
      (println "\n! Себестоимость не загружена. Загрузите:")
      (println "  (cost-price/load-from-1c)   ;; из 1С")
      (println "  (sync/sync! :1c)            ;; сохранить в БД"))

    (table/print-summary
     "ЮНИТ-ЭКОНОМИКА — ПОЛНАЯ РАЗБИВКА"
     [["Выручка (розница)"      (:total-revenue s)]
      ["" nil]
      ["ИЗДЕРЖКИ МП:" nil]
      ["  Комиссия МП"          (:total-wb-reward s)]
      ["  Логистика"            (:total-logistics s)]
      ["  Хранение"             (:total-storage s)]
      ["  Приёмка"              (:total-acceptance s)]
      ["  Штрафы"               (:total-penalties s)]
      ["  Эквайринг"            (:total-acquiring s)]
      ["  Удержания"            (:total-deduction s)]
      ["  Итого издержки МП"    (:total-wb-costs s)]
      ["  % от выручки"         (str (:wb-cost-pct s) "%")]
      ["" nil]
      ["  Доплаты"           (:total-additional s)]
      ["  Компенсация СПП"      (:total-spp s)]
      ["= К выплате от МП"      (:total-for-pay s)]
      ["" nil]
      ["СЕБЕСТОИМОСТЬ:"          (:total-cost s)]
      ["  % от выручки"         (str (:cogs-pct s) "%")]
      ["" nil]
      ["РЕКЛАМА:"                (:total-ad-spend s)]
      ["  ДРР%"                  (str (:drr-pct s) "%")]
      ["" nil]
      ["= ПРИБЫЛЬ"              (:total-profit s)]
      ["  Маржа от выручки"     (str (:margin-pct s) "%")]
      ["  Прибыль на 1 продажу" (:profit-per-sale s)]
      ["  Средний чек"          (:avg-check s)]
      ["  Продажи/Возвраты"     (str (:sales-qty s) "/" (:returns-qty s))]])

    (println "\n── Топ-20 по прибыли (на единицу) ──")
    (table/print-table
     [[:article "Артикул"] [:sales-qty "Прод"] [:buyout-rate "Выкуп%"]
      [:revenue-per-unit "Цена"] [:cost-per-unit "Себ/шт"]
      [:reward-per-unit "Ком/шт"] [:logistics-per-op "Log/op"] [:logistics-per-unit "Log/buy"]
      [:storage-per-unit "Скл/шт"] [:payout-per-unit "Выпл/шт"]
      [:profit-per-unit "Приб/шт"] [:margin-pct "Маржа%"]]
     (->> ue-data
          (sort-by :profit-per-unit >)
          (take 20)))

    (println "\n── Убыточные артикулы ──")
    (let [losers (filter #(neg? (:profit %)) ue-data)]
      (if (seq losers)
        (table/print-table
         [[:article "Артикул"] [:sales-qty "Прод"] [:buyout-rate "Выкуп%"]
          [:revenue-per-unit "Цена"] [:cost-per-unit "Себ/шт"]
          [:logistics-per-op "Log/op"] [:logistics-per-unit "Log/buy"] [:payout-per-unit "Выпл/шт"]
          [:profit-per-unit "Приб/шт"] [:margin-pct "Маржа%"]]
         losers)
        (println "  Убыточных артикулов нет.")))

    (println "\n── Где больше всего теряем (% издержек МП от выручки) ──")
    (table/print-table
     [[:article "Артикул"] [:sales-qty "Прод"] [:buyout-rate "Выкуп%"] [:revenue "Выручка"]
      [:total-wb-costs "Издержки МП"] [:wb-cost-pct "МП%"]
      [:logistics-pct "Лог%"] [:total-cost "Себест"] [:profit "Прибыль"]]
     (->> ue-data
          (filter #(pos? (:sales-qty %)))
          (sort-by :wb-cost-pct >)
          (take 15)))

    s))

;; ---------------------------------------------------------------------------
;; Export
;; ---------------------------------------------------------------------------

(def ^:private ue-export-cols
  [[:article "Article"]
   [:brand "Brand"] [:subject "Subject"]
   [:sales-qty "Sales"] [:buyout-rate "Buyout %"] [:returns-qty "Returns"]
   [:revenue "Revenue"] [:revenue-per-unit "Price/unit"]
   [:cost-per-unit "COGS/unit"] [:total-cost "COGS total"] [:cogs-pct "COGS%"]
   [:wb-reward "MP Commission"] [:reward-per-unit "Commission/unit"]
   [:logistics "Logistics"] [:logistics-per-op "Logistics/op"] [:logistics-per-unit "Logistics/buyout"] [:logistics-pct "Logistics%"]
   [:storage "Storage"] [:storage-per-unit "Storage/unit"]
   [:acceptance "Acceptance"] [:penalties "Penalties"] [:acquiring "Acquiring"]
   [:deduction "Deduction"] [:additional "Additional"]
   [:total-wb-costs "Total MP costs"] [:wb-cost-pct "MP costs%"]
   [:spp-compensation "SPP compensation"]
   [:for-pay "MP Payout"] [:payout-per-unit "Payout/unit"]
   [:ad-spend "Ad Spend"] [:drr-pct "DRR%"]
   [:profit "Profit"] [:profit-per-unit "Profit/unit"] [:margin-pct "Margin%"]])

(def ^:private ue-summary-cols
  [[:metric "Metric"] [:value "Value"]])

(defn- summary-rows [summary]
  (let [sales-qty   (:sales-qty summary)
        returns-qty (:returns-qty summary)
        ops         (+ sales-qty returns-qty)
        buyout-rate (math/percentage sales-qty ops)]
    [{:metric "Revenue"        :value (:total-revenue summary)}
     {:metric "MP Commission"  :value (:total-wb-reward summary)}
     {:metric "Logistics"      :value (:total-logistics summary)}
     {:metric "Storage"        :value (:total-storage summary)}
     {:metric "Acceptance"     :value (:total-acceptance summary)}
     {:metric "Penalties"      :value (:total-penalties summary)}
     {:metric "Acquiring"      :value (:total-acquiring summary)}
     {:metric "Deduction"      :value (:total-deduction summary)}
     {:metric "Total MP costs" :value (:total-wb-costs summary)}
     {:metric "MP costs %"     :value (:wb-cost-pct summary)}
     {:metric "Additional"     :value (:total-additional summary)}
     {:metric "SPP compensation" :value (:total-spp summary)}
     {:metric "MP Payout"      :value (:total-for-pay summary)}
     {:metric "COGS total"     :value (:total-cost summary)}
     {:metric "COGS %"         :value (:cogs-pct summary)}
     {:metric "Ad Spend"       :value (:total-ad-spend summary)}
     {:metric "DRR %"          :value (:drr-pct summary)}
     {:metric "Profit"         :value (:total-profit summary)}
     {:metric "Margin %"       :value (:margin-pct summary)}
     {:metric "Profit per sale" :value (:profit-per-sale summary)}
     {:metric "Avg check"      :value (:avg-check summary)}
     {:metric "Sales qty"      :value sales-qty}
     {:metric "Returns qty"    :value returns-qty}
     {:metric "Buyout %"       :value buyout-rate}]))

(def ^:private losers-cols
  [[:article "Article"] [:brand "Brand"] [:subject "Subject"]
   [:sales-qty "Sales"] [:returns-qty "Returns"] [:buyout-rate "Buyout %"]
   [:revenue "Revenue"] [:revenue-per-unit "Price/unit"]
   [:cost-per-unit "COGS/unit"] [:total-cost "COGS total"]
   [:logistics "Logistics"] [:logistics-per-unit "Logistics/buyout"]
   [:for-pay "MP Payout"] [:payout-per-unit "Payout/unit"]
   [:profit "Profit"] [:profit-per-unit "Profit/unit"] [:margin-pct "Margin%"]])

(def ^:private wb-costs-cols
  [[:article "Article"] [:brand "Brand"]
   [:sales-qty "Sales"] [:buyout-rate "Buyout %"] [:revenue "Revenue"]
   [:wb-reward "MP Commission"] [:logistics "Logistics"] [:storage "Storage"]
   [:acceptance "Acceptance"] [:penalties "Penalties"] [:acquiring "Acquiring"]
   [:deduction "Deduction"]
   [:total-wb-costs "Total MP costs"] [:wb-cost-pct "MP costs%"]
   [:logistics-pct "Logistics%"] [:total-cost "COGS"] [:ad-spend "Ad Spend"]
   [:profit "Profit"] [:margin-pct "Margin%"]])

(defn export-excel [period path & opts]
  (let [[from to] (t/resolve-period period)
        opts-map (apply hash-map opts)
        marketplace (:marketplace opts-map)
        basis       (or (:basis opts-map) :article)
        fin-data (apply finance/fetch-finance period opts)
        _        (ensure-finance-coverage! fin-data from to)
        storage-map (let [rows (db/storage-by-article from to :marketplace marketplace)]
                      (when (seq rows)
                        (into {} (map (juxt :article :storage-cost) rows))))
        ad-map   (let [rows (db/ad-spend-by-article from to :marketplace marketplace)]
                   (when (seq rows)
                     (into {} (map (juxt :article :ad-spend) rows))))
        ue-data  (calculate fin-data
                            :storage-by-article storage-map
                            :ad-spend-by-article ad-map
                            :basis basis)
        s        (totals ue-data)
        losers   (filter #(neg? (:profit %)) ue-data)
        high-wb  (->> ue-data
                      (filter #(pos? (:sales-qty %)))
                      (sort-by :wb-cost-pct >))]
    (export/to-excel path
                     (cond-> [{:name "Unit Economics" :cols ue-export-cols :rows ue-data}
                              {:name "Summary" :cols ue-summary-cols :rows (summary-rows s)}]
                       (seq losers)
                       (conj {:name "Losers" :cols losers-cols :rows losers})
                       true
                       (conj {:name "MP Costs Breakdown" :cols wb-costs-cols :rows high-wb})))))
