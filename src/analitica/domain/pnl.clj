(ns analitica.domain.pnl
  (:require [analitica.domain.finance :as finance]
            [analitica.domain.cost-price :as cost-price]
            [analitica.db :as db]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.math :as math]
            [analitica.util.safe :as safe]
            [analitica.util.time :as t]))

(defn- derive-date-range
  "Fallback used only when the caller did not pass `:from`/`:to`: take
   min(date_from)/max(date_to) across the rows. This expands the
   window for pre-aggregated reports (rows whose date_from/date_to
   span the whole report range), which causes side-query
   double-counting when the caller is slicing into sub-periods.
   Prefer passing `:from`/`:to` explicitly; this fallback exists for
   call sites that build finance rows inline without a period."
  [finance-data]
  (let [pick (fn [k1 k2 m] (or (get m k1) (get m k2)))
        dates-from (->> finance-data (map #(pick :date-from :date_from %)) (remove nil?))
        dates-to   (->> finance-data (map #(pick :date-to :date_to %)) (remove nil?))]
    [(when (seq dates-from) (reduce #(if (neg? (compare %1 %2)) %1 %2) dates-from))
     (when (seq dates-to)   (reduce #(if (pos? (compare %1 %2)) %1 %2) dates-to))]))

(defn- ad-cost-sum
  "SUM(finance.ad_cost) across the period, optionally filtered by marketplace.
   Returns a double when one or more rows match (even if the sum is 0.0), or
   nil when NO rows matched — so callers can distinguish a genuine zero-spend
   period from a missing-data period.  Non-throwing: DB-schema drift (no
   ad_cost column) also returns nil.

   Prefers per-event `event_date` filter (populated by the 2026-04-23
   migration); falls back to weekly-report overlap on date_from/date_to
   for legacy rows that pre-date event_date."
  [from to marketplace]
  (safe/safely
    (let [base "SELECT COUNT(*) AS cnt, COALESCE(SUM(ad_cost), 0) AS sum FROM finance
                WHERE ((event_date IS NOT NULL AND event_date BETWEEN ? AND ?)
                       OR (event_date IS NULL AND date_from <= ? AND date_to >= ?))"
          [sql params] (if marketplace
                         [(str base " AND marketplace = ?")
                          [from to to from (name marketplace)]]
                         [base [from to to from]])
          row (first (db/query (into [sql] params)))]
      (when (and row (pos? (or (:cnt row) 0)))
        (double (or (:sum row) 0.0))))
    nil
    ::ad-cost-sum-failed))

(defn- legacy-ad-spend-sum
  "Legacy ad-spend SUM via ad_stats JOIN. Used as fallback when the canonical
   finance.ad_cost path returns 0 for WB (pre-migration state) or when an
   all-marketplace total is requested and no ad_cost has been materialized."
  [from to marketplace]
  (safe/safely
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
    nil
    ::legacy-ad-spend-sum-failed))

(defn- ad-spend-total
  "Total ad spend for period, optionally filtered by marketplace.

   Returns a map {:value <double> :source <keyword>} where :source is one of:
     :canonical — finance.ad_cost rows matched the period
     :legacy    — no canonical rows; fell back to ad_stats JOIN (WB path)
     :missing   — neither path returned any row (distinct from a genuine 0.0)

   The :value is always a double (callers that only need the number use
   (:value …) or continue using `(or … 0.0)` on the result).

   Preference order (spec 003 US5 T040):
     1. Canonical path — SUM(finance.ad_cost). YM and Ozon always use this
        (their ad_cost is populated by US2/US3 ingest). For :wb this is the
        primary path once the US5 migration has populated ad_cost.
     2. Legacy fallback — ad_stats JOIN. Used ONLY when:
          * marketplace is :wb OR nil, AND
          * the canonical ad_cost SUM is nil (no rows — migration not yet run).

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
        {:value  (or canonical 0.0)
         :source (if (some? canonical) :canonical :missing)}

        ;; WB or all-marketplaces: canonical if it returned a positive sum
        ;; (i.e. ad_cost has been materialised for this period).
        ;; Threshold ≥ 0.01₽: a nil (no rows) or 0.0 (rows exist but ad_cost
        ;; not yet populated) both trigger the legacy fallback, matching the
        ;; pre-migration roll-out semantics (spec 003 §FR-016 §SC-009).
        (and (some? canonical) (> canonical 0.0))
        {:value canonical :source :canonical}

        :else
        (let [legacy (legacy-ad-spend-sum from to marketplace)]
          {:value  (or legacy 0.0)
           :source (if (some? legacy) :legacy :missing)})))))

;; ---------------------------------------------------------------------------
;; Per-article ad-spend lookup
;; ---------------------------------------------------------------------------

(defn- ad-cost-by-article-canonical
  "Per-article SUM(finance.ad_cost) over the period. Same date predicate as
   `ad-cost-sum`; returns {article → spend} for articles with positive spend."
  [from to marketplace]
  (safe/safely
    (let [base "SELECT article, COALESCE(SUM(ad_cost), 0) AS spend FROM finance
                WHERE ((event_date IS NOT NULL AND event_date BETWEEN ? AND ?)
                       OR (event_date IS NULL AND date_from <= ? AND date_to >= ?))
                  AND article IS NOT NULL"
          [sql params] (if marketplace
                         [(str base " AND marketplace = ? GROUP BY article")
                          [from to to from (name marketplace)]]
                         [(str base " GROUP BY article")
                          [from to to from]])]
      (->> (db/query (into [sql] params))
           (reduce (fn [m {:keys [article spend]}]
                     (if (and article (pos? (or spend 0)))
                       (assoc m article (double spend))
                       m))
                   {})))
    {}
    ::ad-cost-by-article-failed))

(defn- legacy-ad-spend-by-article
  "Legacy per-article ad-spend via ad_stats JOIN. Mirrors `legacy-ad-spend-sum`
   but groups by article. ad_stats is WB-only, so this only returns data when
   `marketplace` is :wb or nil; the JOIN is always restricted to WB finance
   rows so cross-MP nm_id collisions can't double-count spend onto Ozon/YM
   articles. Returns {} on schema drift or query error."
  [from to marketplace]
  (when (contains? #{nil :wb} marketplace)
    ;; TODO(obs/7e): deferred — convert this silent (catch Exception _ {}) to
    ;; analitica.util.safe/safely post-pilot. See
    ;; docs/superpowers/plans/2026-06-23-pilot-hardening-observability.md Task 2.
    (try
      (->> (db/query
             ["SELECT f.article AS article, SUM(a.spend) AS spend
               FROM ad_stats a
               JOIN (SELECT DISTINCT nm_id, article FROM finance
                     WHERE nm_id IS NOT NULL AND article IS NOT NULL
                       AND marketplace = 'wb') f
                 ON a.nm_id = f.nm_id
               WHERE a.date >= ? AND a.date <= ?
               GROUP BY f.article"
              from to])
           (reduce (fn [m {:keys [article spend]}]
                     (if (and article (pos? (or spend 0)))
                       (assoc m article (double spend))
                       m))
                   {}))
      (catch Exception _ {}))))

(defn ad-spend-by-article
  "{article → ad-spend (₽)} for the period, optionally marketplace-scoped.

   Mirrors `ad-spend-total` semantics so per-article and aggregate paths agree:
     1. Canonical — SUM(finance.ad_cost) GROUP BY article. Always used for
        :ym and :ozon (their ad_cost is canonical).
     2. Legacy fallback — ad_stats JOIN. Used ONLY when:
          * marketplace is :wb or nil, AND
          * the canonical per-article totals sum to 0 (US5 migration not run
            for this period yet).

   Returns {} when both paths are empty or `from`/`to` are missing."
  [from to marketplace]
  (if (and from to)
    (let [canonical (ad-cost-by-article-canonical from to marketplace)
          total     (reduce + 0.0 (vals canonical))]
      (cond
        (contains? #{:ym :ozon} marketplace) canonical
        (pos? total)                          canonical
        :else (or (legacy-ad-spend-by-article from to marketplace) {})))
    {}))

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
     :from / :to           ISO date strings bounding the period. Used
                           for ad-spend side-query. When omitted, falls
                           back to min/max of date_from/date_to across
                           rows — which over-extends the window for
                           pre-aggregated reports and silently
                           double-counts ad-spend when the caller
                           slices a month into weeks.

   Returns a map with the P&L.1–P&L.5 fields unconditionally; P&L.6
   cf-* / adjusted-* fields appear only when cf-adjustments is non-nil."
  [finance-data & {:keys [cf-adjustments marketplace from to]}]
  (let [by-art        (finance/by-article finance-data)
        [from to]     (if (and from to)
                        [from to]
                        (derive-date-range finance-data))
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
        mp-commission (reduce + 0.0 (map :mp-commission by-art))
        ad-total      (ad-spend-total from to marketplace)
        ad-spend      (or (:value ad-total) 0.0)
        ad-cost-src   (or (:source ad-total) :missing)
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
       :mp-commission (math/round2 (- (Math/abs mp-commission)))
       :cogs          (math/round2 cogs)
       :ad-spend      (math/round2 (double ad-spend))
       :ad-cost-source ad-cost-src
       :gross-profit  (math/round2 gross-profit)
       :net-profit    (math/round2 net-profit)
       :margin-gross  (math/percentage gross-profit revenue)
       :margin-net    (math/percentage net-profit revenue)
       :sales-qty     sales-qty
       :returns-qty   returns-qty
       ;; FR-008 rename: canonical key is :non-return-rate.
       ;; :buyout-rate kept as deprecated alias for one release cycle.
       :non-return-rate (math/percentage sales-qty (+ sales-qty returns-qty))
       :buyout-rate     (math/percentage sales-qty (+ sales-qty returns-qty))
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
  (let [[from to] (t/resolve-period period)
        fin-data  (finance/fetch-finance period :marketplace marketplace :source source)
        cf-adj    (load-cf-adjustments from to marketplace)
        pnl       (calculate fin-data
                             :cf-adjustments cf-adj
                             :marketplace marketplace
                             :from from :to to)]

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
              ["  Доля невозвратов"      (str (:non-return-rate pnl) "%")]
              ["  Средний чек"          (:avg-check pnl)]
              ["  Прибыль на продажу"   (:profit-per-sale pnl)]
              ["  Артикулов"            (:articles pnl)]])))

    pnl))

(defn export-excel
  [period path & opts]
  (let [opts-map    (apply hash-map opts)
        marketplace (:marketplace opts-map)
        [from to]   (t/resolve-period period)
        fin-data    (apply finance/fetch-finance period opts)
        cf-adj      (load-cf-adjustments from to marketplace)
        pnl         (calculate fin-data
                               :cf-adjustments cf-adj
                               :marketplace marketplace
                               :from from :to to)
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
                             {:line "Доля невозвратов"  :amount (:non-return-rate pnl)}
                             {:line "Avg check"         :amount (:avg-check pnl)}
                             {:line "Profit per sale"   :amount (:profit-per-sale pnl)}]))]
    (export/to-excel path "PnL" [[:line "Line"] [:amount "Amount RUB"]] pnl-rows)))
