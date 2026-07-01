(ns analitica.domain.pnl
  (:require [analitica.domain.finance :as finance]
            [analitica.domain.cost-price :as cost-price]
            [analitica.domain.tax :as tax]
            [analitica.domain.opex :as opex]
            [analitica.db :as db]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.math :as math]
            [analitica.util.safe :as safe]
            [analitica.util.time :as t]
            [clojure.string :as str]))

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
  [finance-data & {:keys [cf-adjustments marketplace from to management]}]
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
        adj-net       (- adj-gross ad-spend)
        ;; -------------------------------------------------------------------
        ;; Management-basis layer (spec 015 §P&L.10): tax (УСН/НДС) + OPEX
        ;; ABOVE the frozen reconciled basis. Computed only when :management
        ;; is passed; otherwise the output is byte-for-byte identical to today
        ;; (SC-006, INV-4). P&L.1–P&L.6 above are NEVER touched by this.
        ;;
        ;; tax/vat are derived from the ALREADY-computed aggregates
        ;; (for_pay / revenue / cogs / opex — no second pass, R8).
        ;; -------------------------------------------------------------------
        has-mgmt?     (some? management)
        tax-config    (when has-mgmt? (:tax-config management))
        mgmt-opex     (when has-mgmt? (math/round2 (double (or (:opex management) 0.0))))
        opex-by-cat   (when has-mgmt? (or (:opex-by-category management) {}))
        mgmt-configured? (when has-mgmt?
                           (boolean (or tax-config
                                        (pos? (or mgmt-opex 0.0)))))
        tax-res       (when has-mgmt?
                        (tax/compute-period
                          {:taxation-type       (or (:taxation-type tax-config) :none)
                           :usn-rate            (double (or (:usn-rate tax-config) 0.0))
                           :vat-rate            (double (or (:vat-rate tax-config) 0.0))
                           :official-cost-price (boolean (:official-cost-price tax-config))
                           :for-pay             (double for-pay)
                           :revenue             (double revenue)
                           :cogs                (double cogs)
                           :opex                (double (or mgmt-opex 0.0))}))
        mgmt-tax      (when has-mgmt? (or (:tax tax-res) 0.0))
        mgmt-vat      (when has-mgmt? (or (:vat tax-res) 0.0))
        mgmt-tax-base (when has-mgmt? (or (:tax-base tax-res) 0.0))
        ;; base ≤ 0 while a УСН regime IS configured ⇒ tax clamped to 0 (loss),
        ;; distinguishing configured-but-zero from inert (FR-016 / SC-007).
        usn?          (contains? #{:usn-income :usn-income-expense}
                                 (:taxation-type tax-config))
        zero-reason   (when (and has-mgmt? usn? (<= (or mgmt-tax-base 0.0) 0.0))
                        :base-non-positive)
        mgmt-profit   (when has-mgmt?
                        (math/round2 (- net-profit mgmt-opex mgmt-tax mgmt-vat)))
        mgmt-profit-we (when has-mgmt?
                         (math/round2 (- net-profit mgmt-tax mgmt-vat)))]
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
             :adjusted-margin     (math/percentage adj-net revenue))
      has-mgmt?
      (assoc :management-configured? mgmt-configured?
             :tax-base               (math/round2 mgmt-tax-base)
             :tax                    (math/round2 mgmt-tax)
             :vat                    (math/round2 mgmt-vat)
             :opex                   mgmt-opex
             :opex-by-category       opex-by-cat
             :profit                 mgmt-profit
             :profit-without-expense mgmt-profit-we
             :management-margin      (math/percentage mgmt-profit revenue)
             :margin-without-expense (math/percentage mgmt-profit-we revenue)
             :management-zero-reason zero-reason))))

;; ---------------------------------------------------------------------------
;; P&L waterfall (spec 016 US3) — §0.1 LOCKED GROSS-realisation top-line.
;;
;; A PURE fn over `calculate` output. It NEVER recomputes revenue/gross/net —
;; it re-composes the already-computed frozen aggregates onto a GROSS top-line
;; so the kopeck invariants hold BY CONSTRUCTION:
;;
;;   sales          = :revenue                       (GROSS realisation, NOT for_pay)
;;   directExpenses = :gross-profit − :revenue       (= −(revenue − gross-profit))
;;   grossMargin    = sales + directExpenses          == :gross-profit (VR-w3)
;;   advertising    = −:ad-spend                      (distinct line, FR-016/FR-017)
;;   operatingExpenses = −:opex | 0                   (015 seam; 0 pre-015, FR-020/FR-021)
;;   EBITDA         = grossMargin + advertising + operatingExpenses
;;   tax            = −(:tax + :vat) | 0              (015 seam; 0 pre-015)
;;   netProfit      = EBITDA + tax                    == :net-profit (opex=tax=0) / == :profit (managed)
;;
;; directExpenses children are EXACTLY the signed components of :gross-profit
;; recomposed onto the GROSS base: the commission child absorbs (revenue − for_pay)
;; — the MP's total retention at the payout gate — so Σ children == directExpenses
;; to the kopeck (VR-w2). commission is now a VISIBLE line instead of hidden inside
;; for_pay. :additional (Доплаты) is a CREDIT (positive, reduces directExpenses); it
;; is the ONLY credit in :gross-profit. :compensation is a cf-layer field ABOVE the
;; frozen basis and MUST NOT appear here.
;; ---------------------------------------------------------------------------

(def ^:private waterfall-labels
  {:sales             "Выручка (GROSS)"
   :direct-expenses   "Прямые расходы"
   :cogs              "Себестоимость"
   :mp-commission     "Комиссия МП"
   :logistics         "Логистика"
   :storage           "Хранение"
   :acceptance        "Приёмка"
   :penalties         "Штрафы"
   :deduction         "Удержания"
   :additional        "Доплаты"
   :gross-margin      "Валовая прибыль"
   :advertising       "Реклама"
   :operating-expenses "Операционные расходы"
   :ebitda            "Операционная прибыль (EBITDA)"
   :tax               "Налог"
   :tax-usn           "Налог УСН"
   :vat               "НДС (исх.)"
   :net-profit        "Чистая прибыль"})

(def ^:private direct-expense-child-keys
  "Drilldown children of the directExpenses layer — EXACTLY the signed
   components of pnl :gross-profit recomposed on the GROSS base (VR-w3)."
  [:cogs :mp-commission :logistics :storage :penalties :deduction :acceptance :additional])

(defn- delta-for
  "Signed delta and neutral-safe pct for a line vs its comparison amount.
   Returns {:delta d :delta-pct p|nil}. pct is nil (neutral, NOT ±100%) when
   there is no comparison or the prior amount is 0 (FR-026/VR-w5)."
  [amount prev]
  (if (nil? prev)
    {:delta nil :delta-pct nil}
    (let [d (math/round2 (- amount prev))]
      {:delta d
       :delta-pct (when-not (zero? prev)
                    (math/round2 (* 100.0 (/ (- amount prev) (Math/abs (double prev))))))})))

(defn waterfall
  "Layered P&L waterfall over a `calculate` result (spec 016 US3, §0.1 LOCKED).

   Pure re-composition of the frozen aggregates onto a GROSS top-line; the
   frozen :revenue/:for-pay/:gross-profit/:net-profit are NOT recomputed.

   Options:
     :comparison  a prior-period `calculate` result. When present, each line
                  carries {:delta :delta-pct} vs the matching prior line;
                  a prior amount of 0 ⇒ :delta-pct nil (neutral, FR-026).

   Returns {:waterfall [WaterfallLine...]}. Layers, in order:
     :sales → :direct-expenses (+ children) → :gross-margin → :advertising
     → :operating-expenses → :ebitda → :tax (+ children) → :net-profit.

   Management layers (:operating-expenses / :tax) read the 015 adjusted-net
   seam via the management keys on the `calculate` result (:opex / :tax / :vat).
   When 015 has not landed (those keys absent) they render 0 and
   EBITDA == grossMargin − advertising, netProfit == :net-profit (VR-w4)."
  [pnl-result & {:keys [comparison]}]
  (let [revenue    (double (or (:revenue pnl-result) 0.0))
        for-pay    (double (or (:for-pay pnl-result) 0.0))
        gross      (double (or (:gross-profit pnl-result) 0.0))
        ad-spend   (double (or (:ad-spend pnl-result) 0.0))
        ;; Signed component amounts (expenses negative, credits positive), recomposed
        ;; onto the GROSS base. commission = −(revenue − for_pay): the MP's retention.
        child-amount (fn [k]
                       (case k
                         :mp-commission (math/round2 (- (- revenue for-pay)))
                         :additional    (math/round2 (double (or (:additional pnl-result) 0.0)))
                         ;; all other children are costs stored non-negative in pnl → negate
                         (math/round2 (- (double (or (get pnl-result k) 0.0))))))
        children     (mapv (fn [k] {:key k :amount (child-amount k)}) direct-expense-child-keys)
        direct-exp   (math/round2 (reduce + 0.0 (map :amount children)))   ; == gross − revenue
        gross-margin (math/round2 (+ revenue direct-exp))                  ; == :gross-profit
        advertising  (math/round2 (- ad-spend))
        ;; 015 management seam — 0 when absent (FR-020/FR-021).
        opex         (math/round2 (double (or (:opex pnl-result) 0.0)))
        tax-usn      (math/round2 (double (or (:tax pnl-result) 0.0)))
        vat          (math/round2 (double (or (:vat pnl-result) 0.0)))
        op-exp-amt   (math/round2 (- opex))
        tax-amt      (math/round2 (- (+ tax-usn vat)))
        ebitda       (math/round2 (+ gross-margin advertising op-exp-amt))
        net-profit   (math/round2 (+ ebitda tax-amt))
        ;; comparison line amounts, keyed the same way, for delta attach.
        cmp          (when comparison (waterfall comparison))
        cmp-amt      (fn [k] (when cmp
                               (some #(when (= k (:key %)) (:amount %))
                                     (:waterfall cmp))))
        line         (fn [m]
                       (let [base (merge {:label (get waterfall-labels (:key m))} m)]
                         (if comparison
                           (merge base (delta-for (:amount base) (cmp-amt (:key m))))
                           base)))
        child-line   (fn [{:keys [key amount]}]
                       (line {:key key :amount amount :layer :direct-expense
                              :basis :gross-realisation
                              :positive-if-grow (= key :additional)}))]
    {:waterfall
     (vec
       (concat
         [(line {:key :sales :amount (math/round2 revenue) :layer :sales
                 :basis :gross-realisation :positive-if-grow true})
          (line {:key :direct-expenses :amount direct-exp :layer :direct-expense
                 :basis :gross-realisation :positive-if-grow false
                 :children direct-expense-child-keys})]
         (map child-line children)
         [(line {:key :gross-margin :amount gross-margin :layer :gross-margin
                 :basis :gross-realisation :positive-if-grow true})
          (line {:key :advertising :amount advertising :layer :advertising
                 :basis :payout :positive-if-grow false})
          (line {:key :operating-expenses :amount op-exp-amt :layer :operating-expense
                 :basis :management :positive-if-grow false})
          (line {:key :ebitda :amount ebitda :layer :ebitda
                 :basis :management :positive-if-grow true})
          (line {:key :tax :amount tax-amt :layer :tax
                 :basis :management :positive-if-grow false
                 :children [:tax-usn :vat]})
          (line {:key :tax-usn :amount (math/round2 (- tax-usn)) :layer :tax
                 :basis :management :positive-if-grow false})
          (line {:key :vat :amount (math/round2 (- vat)) :layer :tax
                 :basis :management :positive-if-grow false})
          (line {:key :net-profit :amount net-profit :layer :net-profit
                 :basis :management :positive-if-grow true})]))}))

(defn load-cf-adjustments [from to marketplace]
  (when (and from to (= marketplace :ozon))
    (let [adj (db/cash-flow-adjustments "ozon" from to)]
      (when (some pos? (map #(Math/abs (or % 0)) (vals adj)))
        adj))))

(defn- period-months
  "Distinct (year, month) pairs spanning the [from..to] window (YYYY-MM[-DD]
   strings). Used to look up tax-config for each month of the period. Returns
   a seq of [year month] longs, month-ordered."
  [from to]
  (let [ym    (fn [s] (mapv #(Long/parseLong %) (take 2 (str/split s #"-"))))
        [fy fm] (ym from)
        [ty tm] (ym to)]
    (loop [y fy m fm acc []]
      (if (or (> y ty) (and (= y ty) (> m tm)))
        acc
        (recur (if (= m 12) (inc y) y)
               (if (= m 12) 1 (inc m))
               (conj acc [y m]))))))

(defn load-management-adjustments
  "Generalised management seam (spec 015 §3.A, US4/T032). Loads the three
   management inputs for the [from..to] period and assembles the :management
   block that `calculate` consumes.

     :cf               Ozon cash-flow adjustments map (nil for non-Ozon MPs or
                       when no non-trivial cf lines exist). `load-cf-adjustments`
                       itself gates on marketplace=:ozon, so calling it
                       unconditionally here is correct — WB/YM always get nil
                       (T032: removed the redundant outer (= marketplace :ozon)
                       guard; inner gate in load-cf-adjustments is the authority).
     :opex             Σ OPEX for the period+marketplace (double, ≥ 0).
                       Allocation rule R11 (T033): per-MP query = tagged rows of
                       that MP only; blended (marketplace nil) = all per-MP +
                       unallocated NULL rows — no double-count. Delegated to
                       opex/sum-by-category which implements R11 directly.
     :opex-by-category {category → double}.
     :tax-config       the TaxConfigRow for the FIRST month of the period, or
                       nil when no config exists (nil ⇒ tax 0, FR-004).
     :configured?      true when a tax-config exists OR OPEX > 0 (FR-016).

   Returns {:cf {..|nil} :opex d :opex-by-category {..} :tax-config {..|nil}
            :configured? bool}. Pure over the stores (no finance second-pass, R8)."
  [from to marketplace]
  (let [;; T032: call load-cf-adjustments unconditionally — it gates on :ozon internally.
        ;; WB/YM ⇒ nil; Ozon with non-trivial cf lines ⇒ the cf map.
        cf         (load-cf-adjustments from to marketplace)
        ;; T033: opex/sum-by-category implements R11 allocation directly:
        ;;   marketplace non-nil → tagged rows for that MP only (NULL excluded)
        ;;   marketplace nil     → ALL rows (per-MP tagged + unallocated NULL), no double-count
        opex-agg   (opex/sum-by-category from to marketplace)
        opex-total (double (or (:total opex-agg) 0.0))
        opex-by-cat (or (:by-category opex-agg) {})
        ;; tax-config keyed by (year, month); the period's first month drives
        ;; the regime/rate (mid-year rate change is per-month, but the P&L
        ;; period is a single month in practice — first month wins).
        [y m]      (first (period-months from to))
        tax-config (when (and y m) (tax/config-for-month y m))]
    {:cf               cf
     :opex             opex-total
     :opex-by-category opex-by-cat
     :tax-config       tax-config
     :configured?      (boolean (or tax-config (pos? opex-total)))}))

(defn report
  "Print P&L report."
  [period & {:keys [marketplace source] :or {marketplace :wb source :db}}]
  (println "\nЗагрузка P&L...")
  (let [[from to] (t/resolve-period period)
        fin-data  (finance/fetch-finance period :marketplace marketplace :source source)
        ;; T032: replace load-cf-adjustments call site with load-management-adjustments.
        ;; The :cf key carries the Ozon cf map (or nil for WB/YM) via the inner gate.
        mgmt-blk  (load-management-adjustments from to marketplace)
        pnl       (calculate fin-data
                             :cf-adjustments (:cf mgmt-blk)
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
        ;; T032: replace load-cf-adjustments call site with load-management-adjustments.
        mgmt-blk    (load-management-adjustments from to marketplace)
        pnl         (calculate fin-data
                               :cf-adjustments (:cf mgmt-blk)
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
