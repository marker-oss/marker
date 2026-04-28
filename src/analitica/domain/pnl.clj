(ns analitica.domain.pnl
  (:require [analitica.domain.finance :as finance]
            [analitica.domain.cost-price :as cost-price]
            [analitica.db :as db]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.math :as math]))

(defn- derive-date-range
  "Extract min date-from and max date-to from finance data."
  [finance-data]
  (let [pick (fn [k1 k2 m] (or (get m k1) (get m k2)))
        dates-from (->> finance-data (map #(pick :date-from :date_from %)) (remove nil?))
        dates-to   (->> finance-data (map #(pick :date-to :date_to %)) (remove nil?))]
    [(when (seq dates-from) (reduce #(if (neg? (compare %1 %2)) %1 %2) dates-from))
     (when (seq dates-to)   (reduce #(if (pos? (compare %1 %2)) %1 %2) dates-to))]))

(defn- ad-cost-sum
  "SUM(finance.ad_cost) across the period, optionally filtered by marketplace.
   Returns a double (0.0 when no rows match). Non-throwing: a DB-schema drift
   (no ad_cost column) returns nil.

   Prefers per-event `event_date` filter (populated by the 2026-04-23
   migration); falls back to weekly-report overlap on date_from/date_to
   for legacy rows that pre-date event_date."
  [from to marketplace]
  (try
    (let [base "SELECT COALESCE(SUM(ad_cost), 0) AS sum FROM finance
                WHERE ((event_date IS NOT NULL AND event_date BETWEEN ? AND ?)
                       OR (event_date IS NULL AND date_from <= ? AND date_to >= ?))"
          [sql params] (if marketplace
                         [(str base " AND marketplace = ?")
                          [from to to from (name marketplace)]]
                         [base [from to to from]])
          row (first (db/query (into [sql] params)))]
      (double (or (:sum row) 0.0)))
    (catch Exception _ nil)))

(defn- legacy-ad-spend-sum
  "Legacy ad-spend SUM via ad_stats JOIN. Used as fallback when the canonical
   finance.ad_cost path returns 0 for WB (pre-migration state) or when an
   all-marketplace total is requested and no ad_cost has been materialized."
  [from to marketplace]
  (try
    (:spend
      (first
        (if marketplace
          (db/query
            ["SELECT SUM(a.spend) AS spend
              FROM ad_stats a
              JOIN (SELECT DISTINCT nm_id, marketplace FROM finance
                    WHERE nm_id IS NOT NULL) f
                ON a.nm_id = f.nm_id
              WHERE a.date >= ? AND a.date <= ?
                AND f.marketplace = ?"
             from to (name marketplace)])
          (db/query
            ["SELECT SUM(spend) AS spend FROM ad_stats
              WHERE date >= ? AND date <= ?"
             from to]))))
    (catch Exception _ nil)))

(defn- ad-spend-total
  "Total ad spend for period, optionally filtered by marketplace.

   Preference order (spec 003 US5 T040):
     1. Canonical path — SUM(finance.ad_cost). YM and Ozon always use this
        (their ad_cost is populated by US2/US3 ingest). For :wb this is the
        primary path once the US5 migration has populated ad_cost.
     2. Legacy fallback — ad_stats JOIN. Used ONLY when:
          * marketplace is :wb OR nil, AND
          * the canonical ad_cost SUM is 0 (i.e. migration has not yet been
            run for this period).

   This lets the migration roll out per-period: periods with materialized
   ad_cost use the canonical path; older periods without it continue to
   work via the legacy JOIN. See specs/003-finance-row-completeness/spec.md
   §FR-016 + §SC-009."
  [from to marketplace]
  (when (and from to)
    (let [canonical (ad-cost-sum from to marketplace)]
      (cond
        ;; YM / Ozon — always canonical. No legacy path.
        (contains? #{:ym :ozon} marketplace)
        (or canonical 0.0)

        ;; WB or all-marketplaces: canonical if populated, else fall back.
        ;; Threshold ≥ 0.01₽ on the canonical value so exactly-zero totals
        ;; (no ad_cost yet materialized) trigger the fallback.
        (and canonical (> canonical 0.0))
        canonical

        :else
        (or (legacy-ad-spend-sum from to marketplace) 0.0)))))

(defn calculate
  "Period-level P&L rollup.

   Canonical definition: see docs/canonical-formulas.md §P&L
   (P&L.1–P&L.9). Every field produced has a 5-point block in the canon
   with formula, economic justification, inputs, edge cases, and a test
   pointer.

   Arguments:
     finance-data          seq of finance rows (usually from
                           `finance/fetch-finance`).
     :cf-adjustments       map from `db/cash-flow-adjustments` (Ozon
                           only). When nil, adjusted-* fields are
                           omitted from the output.
     :marketplace          keyword :wb | :ozon | :ym | nil. Scopes
                           ad-spend lookup; does NOT filter finance-data
                           (caller must pre-filter).

   Returns a map with the P&L.1–P&L.5 fields unconditionally; P&L.6
   cf-* / adjusted-* fields appear only when cf-adjustments is non-nil."
  [finance-data & {:keys [cf-adjustments marketplace]}]
  (let [by-art        (finance/by-article finance-data)
        [from to]     (derive-date-range finance-data)
        revenue       (reduce + 0.0 (map :revenue by-art))
        wb-reward     (reduce + 0.0 (map :wb-reward by-art))
        logistics     (reduce + 0.0 (map :logistics by-art))
        storage       (reduce + 0.0 (map :storage by-art))
        acceptance    (reduce + 0.0 (map :acceptance by-art))
        penalties     (reduce + 0.0 (map :penalties by-art))
        deduction     (reduce + 0.0 (map :deduction by-art))
        additional    (reduce + 0.0 (map :additional by-art))
        for-pay       (reduce + 0.0 (map :for-pay by-art))
        cogs          (reduce + 0.0 (map :total-cost by-art))
        ad-spend      (or (ad-spend-total from to marketplace) 0.0)
        gross-profit  (- for-pay cogs logistics storage penalties acceptance deduction (- additional))
        net-profit    (- gross-profit ad-spend)
        sales-qty     (reduce + 0 (map :sales-qty by-art))
        returns-qty   (reduce + 0 (map :returns-qty by-art))
        net-qty       (- sales-qty returns-qty)
        ;; Cash flow adjustments: account-level costs (values are negative = costs)
        cf            (or cf-adjustments {})
        cf-subscr     (or (:subscription cf) 0)
        cf-warehouse  (or (:warehouse-movement cf) 0)
        cf-ret-cargo  (or (:returns-cargo cf) 0)
        cf-fines      (or (:fines cf) 0)
        cf-packaging  (or (:packaging cf) 0)
        cf-other-svc  (or (:other-services cf) 0)
        cf-correct    (or (:corrections cf) 0)
        cf-compens    (or (:compensation cf) 0)
        ;; Total extra costs (all negative in the source, so sum is negative)
        cf-costs      (+ cf-subscr cf-warehouse cf-ret-cargo cf-fines cf-packaging cf-other-svc)
        ;; Corrections/compensation are typically positive (money back)
        cf-income     (+ cf-correct cf-compens)
        cf-total      (+ cf-costs cf-income)
        has-cf?       (some? cf-adjustments)
        adj-gross     (+ gross-profit cf-total)
        adj-net       (- adj-gross ad-spend)]
    (cond->
      {:revenue       (math/round2 revenue)
       :wb-reward     (math/round2 wb-reward)
       :logistics     (math/round2 logistics)
       :storage       (math/round2 storage)
       :acceptance    (math/round2 acceptance)
       :penalties     (math/round2 penalties)
       :deduction     (math/round2 deduction)
       :additional    (math/round2 additional)
       :for-pay       (math/round2 for-pay)
       :cogs          (math/round2 cogs)
       :ad-spend      (math/round2 (double ad-spend))
       :gross-profit  (math/round2 gross-profit)
       :net-profit    (math/round2 net-profit)
       :margin-gross  (math/percentage gross-profit revenue)
       :margin-net    (math/percentage net-profit revenue)
       :sales-qty     sales-qty
       :returns-qty   returns-qty
       :buyout-rate   (math/percentage sales-qty (+ sales-qty returns-qty))
       :avg-check     (math/round2 (math/safe-div revenue sales-qty))
       :profit-per-sale (math/round2 (math/safe-div net-profit net-qty))
       :articles      (count by-art)}
      has-cf?
      (assoc :cf-subscription     (math/round2 cf-subscr)
             :cf-warehouse        (math/round2 cf-warehouse)
             :cf-returns-cargo    (math/round2 cf-ret-cargo)
             :cf-fines            (math/round2 cf-fines)
             :cf-packaging        (math/round2 cf-packaging)
             :cf-other-services   (math/round2 cf-other-svc)
             :cf-corrections      (math/round2 cf-correct)
             :cf-compensation     (math/round2 cf-compens)
             :cf-costs            (math/round2 cf-costs)
             :cf-income           (math/round2 cf-income)
             :cf-total            (math/round2 cf-total)
             :adjusted-gross      (math/round2 adj-gross)
             :adjusted-net        (math/round2 adj-net)
             :adjusted-margin     (math/percentage adj-net revenue)))))

(defn load-cf-adjustments [from to marketplace]
  (when (and from to (= marketplace :ozon))
    (let [adj (db/cash-flow-adjustments "ozon" from to)]
      (when (some pos? (map #(Math/abs (or % 0)) (vals adj)))
        adj))))

(defn report
  "Print P&L report."
  [period & {:keys [marketplace source] :or {marketplace :wb source :db}}]
  (println "\nЗагрузка P&L...")
  (let [fin-data  (finance/fetch-finance period :marketplace marketplace :source source)
        [from to] (derive-date-range fin-data)
        cf-adj    (load-cf-adjustments from to marketplace)
        pnl       (calculate fin-data
                             :cf-adjustments cf-adj
                             :marketplace marketplace)]

    (table/print-summary
     "P&L (ОТЧЁТ О ПРИБЫЛЯХ И УБЫТКАХ)"
     (cond->
       [["ДОХОДЫ" nil]
        ["  Выручка (розница)"    (:revenue pnl)]
        ;; :wb-reward = WB ppvz_reward = "Возмещение за выдачу и возврат
        ;; товаров на ПВЗ" — income to seller, NOT a commission. The
        ;; previous label "Комиссия МП" was misleading (Phase D finding,
        ;; 2026-04-28). Other MPs leave this nil.
        ["  Возмещение ПВЗ"       (:wb-reward pnl)]
        ["  К выплате от МП"      (:for-pay pnl)]
        ["" nil]
        ["РАСХОДЫ (из транзакций)" nil]
        ["  Себестоимость"        (:cogs pnl)]
        ["  Логистика"            (:logistics pnl)]
        ["  Хранение"             (:storage pnl)]
        ["  Приёмка"              (:acceptance pnl)]
        ["  Штрафы"               (:penalties pnl)]
        ["  Удержания"            (:deduction pnl)]
        ["  Доплаты"              (:additional pnl)]
        ["  Реклама"              (:ad-spend pnl)]
        ["" nil]
        ["РЕЗУЛЬТАТ (транзакции)" nil]
        ["  Валовая прибыль"      (:gross-profit pnl)]
        ["  Чистая прибыль"       (:net-profit pnl)]
        ["  Маржа валовая"        (str (:margin-gross pnl) "%")]
        ["  Маржа чистая"         (str (:margin-net pnl) "%")]]

       (:cf-total pnl)
       (into [["" nil]
              ["ДОПРАСХОДЫ (выписка МП)" nil]
              ["  Подписка Stars"        (:cf-subscription pnl)]
              ["  Перемещение со склада" (:cf-warehouse pnl)]
              ["  Грузовой возврат"      (:cf-returns-cargo pnl)]
              ["  Упаковка"             (:cf-packaging pnl)]
              ["  Штрафы (из выписки)"  (:cf-fines pnl)]
              ["  Прочие услуги"        (:cf-other-services pnl)]
              ["  = Допрасходы итого"   (:cf-costs pnl)]
              ["" nil]
              ["  Корректировки"        (:cf-corrections pnl)]
              ["  Компенсации"          (:cf-compensation pnl)]
              ["  = Доп. доходы итого"  (:cf-income pnl)]
              ["" nil]
              ["СКОРРЕКТИРОВАННЫЙ РЕЗУЛЬТАТ" nil]
              ["  Вал. прибыль (скорр.)"  (:adjusted-gross pnl)]
              ["  Чист. прибыль (скорр.)" (:adjusted-net pnl)]
              ["  Маржа (скорр.)"         (str (:adjusted-margin pnl) "%")]])

       true
       (into [["" nil]
              ["ПОКАЗАТЕЛИ" nil]
              ["  Продажи"              (:sales-qty pnl)]
              ["  Возвраты"             (:returns-qty pnl)]
              ["  % выкупа"             (str (:buyout-rate pnl) "%")]
              ["  Средний чек"          (:avg-check pnl)]
              ["  Прибыль на продажу"   (:profit-per-sale pnl)]
              ["  Артикулов"            (:articles pnl)]])))

    pnl))

(defn export-excel
  [period path & opts]
  (let [opts-map    (apply hash-map opts)
        marketplace (:marketplace opts-map)
        fin-data    (apply finance/fetch-finance period opts)
        [from to]   (derive-date-range fin-data)
        cf-adj      (load-cf-adjustments from to marketplace)
        pnl         (calculate fin-data
                               :cf-adjustments cf-adj
                               :marketplace marketplace)
        pnl-rows    (cond->
                      [{:line "REVENUE"            :amount (:revenue pnl)}
                       ;; See Russian section above — :wb-reward is PVZ
                       ;; reimbursement income, not commission.
                       {:line "  PVZ Reimbursement" :amount (:wb-reward pnl)}
                       {:line "  MP Payout"       :amount (:for-pay pnl)}
                       {:line "" :amount nil}
                       {:line "EXPENSES (transactions)" :amount nil}
                       {:line "  COGS"            :amount (:cogs pnl)}
                       {:line "  Logistics"       :amount (:logistics pnl)}
                       {:line "  Storage"         :amount (:storage pnl)}
                       {:line "  Acceptance"      :amount (:acceptance pnl)}
                       {:line "  Penalties"       :amount (:penalties pnl)}
                       {:line "  Deduction"       :amount (:deduction pnl)}
                       {:line "  Additional"      :amount (:additional pnl)}
                       {:line "  Advertising"     :amount (:ad-spend pnl)}
                       {:line "" :amount nil}
                       {:line "GROSS PROFIT"      :amount (:gross-profit pnl)}
                       {:line "NET PROFIT"        :amount (:net-profit pnl)}
                       {:line "Gross Margin %"    :amount (:margin-gross pnl)}
                       {:line "Net Margin %"      :amount (:margin-net pnl)}]

                      (:cf-total pnl)
                      (into [{:line "" :amount nil}
                             {:line "EXTRA COSTS (cash flow)" :amount nil}
                             {:line "  Stars subscription"   :amount (:cf-subscription pnl)}
                             {:line "  Warehouse movement"   :amount (:cf-warehouse pnl)}
                             {:line "  Returns cargo"        :amount (:cf-returns-cargo pnl)}
                             {:line "  Packaging"            :amount (:cf-packaging pnl)}
                             {:line "  Fines (cash flow)"    :amount (:cf-fines pnl)}
                             {:line "  Other services"       :amount (:cf-other-services pnl)}
                             {:line "  Corrections"          :amount (:cf-corrections pnl)}
                             {:line "  Compensation"         :amount (:cf-compensation pnl)}
                             {:line "" :amount nil}
                             {:line "ADJUSTED GROSS"         :amount (:adjusted-gross pnl)}
                             {:line "ADJUSTED NET"           :amount (:adjusted-net pnl)}
                             {:line "Adjusted Margin %"      :amount (:adjusted-margin pnl)}])

                      true
                      (into [{:line "" :amount nil}
                             {:line "Sales qty"         :amount (:sales-qty pnl)}
                             {:line "Returns qty"       :amount (:returns-qty pnl)}
                             {:line "Buyout rate %"     :amount (:buyout-rate pnl)}
                             {:line "Avg check"         :amount (:avg-check pnl)}
                             {:line "Profit per sale"   :amount (:profit-per-sale pnl)}]))]
    (export/to-excel path "PnL" [[:line "Line"] [:amount "Amount RUB"]] pnl-rows)))
