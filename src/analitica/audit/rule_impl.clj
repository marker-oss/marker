(ns analitica.audit.rule-impl
  "Concrete reconciliation rule implementations.

   Each rule is registered in `analitica.audit.rules/registry` via the
   `register-all!` function at the bottom of this file. Rule functions take a
   ReconciliationContext and return a seq of discrepancy maps (see
   data-model.md §Discrepancy)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [analitica.audit.rules :as r]
            [analitica.audit.report :as rep]
            [analitica.db :as db]
            [analitica.domain.finance :as finance]
            [analitica.domain.pnl :as pnl]
            [analitica.domain.unit-economics :as ue]))

;; ---------------------------------------------------------------------------
;; Shared helpers
;; ---------------------------------------------------------------------------

(defn- safe-div [a b] (if (zero? (double b)) 0.0 (/ (double a) (double b))))

(defn- fetch-finance-rows
  "Read all finance rows whose [date_from, date_to] range overlaps the requested
   period, filtered by marketplace. Mirrors `analitica.domain.finance/db-finance`
   but scoped to this namespace to avoid pulling in private fns."
  [{:keys [from to]} marketplace]
  (if (= :all marketplace)
    (db/query ["SELECT * FROM finance WHERE date_from <= ? AND date_to >= ? ORDER BY rrd_id"
               to from])
    (db/query ["SELECT * FROM finance WHERE date_from <= ? AND date_to >= ? AND marketplace = ? ORDER BY rrd_id"
               to from (name marketplace)])))

(defn- fetch-sales-rows
  [{:keys [from to]} marketplace]
  (if (= :all marketplace)
    (db/query ["SELECT * FROM sales WHERE date >= ? AND date <= ? ORDER BY sale_id"
               from to])
    (db/query ["SELECT * FROM sales WHERE date >= ? AND date <= ? AND marketplace = ? ORDER BY sale_id"
               from to (name marketplace)])))

(defn- fetch-orders-rows
  [{:keys [from to]} marketplace]
  (if (= :all marketplace)
    (db/query ["SELECT * FROM orders WHERE date >= ? AND date <= ? ORDER BY order_id"
               from to])
    (db/query ["SELECT * FROM orders WHERE date >= ? AND date <= ? AND marketplace = ? ORDER BY order_id"
               from to (name marketplace)])))

;; ---------------------------------------------------------------------------
;; Rule: :aggregate-vs-raw
;; ---------------------------------------------------------------------------
;;
;; Semantics (data-model.md + research R1):
;;   Compare SUM(finance.for_pay) over every row in scope (the "raw" baseline
;;   after materialisation) against SUM(:for-pay) of rows produced by
;;   analitica.domain.finance/by-article (what P&L / UE actually see).
;;
;;   A non-zero delta indicates that some raw rows were excluded from the
;;   article-level aggregation — typically account-level operations whose
;;   :article is nil (B-002 in verdicts.md) or operation types other than
;;   sale/return (logistics-only rows, storage rows).
;;
;; Output:
;;   - Zero delta → empty seq (no discrepancy).
;;   - Non-zero delta → one discrepancy with :field "for_pay", source pair
;;     :raw-finance ↔ :agg-finance, classification per ctx/tolerance.
;;   - Evidence: sample of up to 5 "dropped" rows (operation/article) for
;;     drill-down by the operator.

(defn aggregate-vs-raw
  "Rule function for :aggregate-vs-raw. See namespace doc for semantics."
  [ctx]
  (let [period   (:ctx/period ctx)
        mp       (:ctx/marketplace ctx)
        rows     (fetch-finance-rows period mp)
        raw-sum  (reduce + 0.0 (map #(or (:for-pay %) 0) rows))
        agg-rows (finance/by-article rows)
        agg-sum  (reduce + 0.0 (map #(or (:for-pay %) 0) agg-rows))
        delta    (- (double raw-sum) (double agg-sum))
        d-abs    (Math/abs delta)
        d-rel    (safe-div d-abs (max (Math/abs (double raw-sum))
                                      (Math/abs (double agg-sum))
                                      1.0))
        class    (r/classify d-abs d-rel (:ctx/tolerance ctx))
        ;; Sample rows not captured by by-article's sale/return filter, for drill-down.
        dropped  (->> rows
                      (remove #(#{"sale" "Продажа" "return" "Возврат"} (:operation %)))
                      (take 5)
                      (mapv #(select-keys % [:rrd-id :operation :article :for-pay])))]
    (if (zero? d-abs)
      []
      [(rep/make-discrepancy
         {:rule-id              :aggregate-vs-raw
          :marketplace          mp
          :period               period
          :location             {:source-a :raw-finance
                                 :source-b :agg-finance
                                 :field    "for_pay"}
          :delta                {:a    (double raw-sum)
                                 :b    (double agg-sum)
                                 :abs  d-abs
                                 :rel  d-rel
                                 :unit :rub}
          :classification       class
          :classification-reason
          (case class
            :expected   (format "within tolerance (abs=%.2f₽ rel=%.4f)" d-abs d-rel)
            :suspicious (format "aggregation drops non-sale/return rows (%d sample rows in :evidence)"
                                (count dropped))
            "classified")
          :evidence             {:raw-row-count    (count rows)
                                 :agg-row-count    (count agg-rows)
                                 :sample-dropped   dropped
                                 :unique-operations (->> rows
                                                         (map :operation)
                                                         frequencies)}})])))

;; ---------------------------------------------------------------------------
;; Rule: :sales-qty-triangle
;; ---------------------------------------------------------------------------
;;
;; Semantics (FR-002, FR-008):
;;   Triangulate sales qty per article across three sources:
;;     - finance: SUM(quantity) WHERE operation IN {sale, Продажа}
;;     - sales:   COUNT(sale_id) WHERE type='S' (non-return rows)
;;     - orders:  COUNT(order_id) — excluding explicit 'cancelled'/'declined'
;;
;;   For each article present in ANY source, compare pair-wise and emit one
;;   discrepancy per mismatch that exceeds tolerance (abs threshold).
;;   Classification uses the same abs/rel rule from research R5; for :qty
;;   unit the rel threshold is not meaningful (no natural base), so classify
;;   via abs only.

(def ^:const sale-operations #{"sale" "Продажа"})
(def ^:const sold-order-statuses
  ;; Not explicitly cancelled/declined → count as potentially-sold.
  #{"waiting" "delivered" "sold"})

;; RFC-3 / E-2: canonical :operation-kind. DB persists as string, in-memory
;; constructors may use keyword. We also accept the canonical English
;; :operation string for inline test fixtures that don't set the kind.
;; The dirty Russian fallback was removed after the 2026-04-28 backfill.
(defn- sale-row? [row]
  (let [k (:operation-kind row)]
    (or (= :sale k) (= "sale" k) (= "sale" (:operation row)))))

(defn- qty-by-article-from-finance [rows]
  (->> rows
       (filter sale-row?)
       (group-by :article)
       (reduce-kv
         (fn [acc article lines]
           (assoc acc article (reduce + 0 (map #(or (:quantity %) 0) lines))))
         {})))

(defn- qty-by-article-from-sales [rows]
  (->> rows
       (filter #(= "S" (:type %)))
       (group-by :article)
       (reduce-kv (fn [acc article lines] (assoc acc article (count lines))) {})))

(defn- qty-by-article-from-orders [rows]
  (->> rows
       (filter #(contains? sold-order-statuses (:status %)))
       (group-by :article)
       (reduce-kv (fn [acc article lines] (assoc acc article (count lines))) {})))

(defn sales-qty-triangle
  "Rule function for :sales-qty-triangle."
  [ctx]
  (let [period    (:ctx/period ctx)
        mp        (:ctx/marketplace ctx)
        tol       (:ctx/tolerance ctx)
        fin-qty   (qty-by-article-from-finance (fetch-finance-rows period mp))
        sal-qty   (qty-by-article-from-sales   (fetch-sales-rows   period mp))
        ord-qty   (qty-by-article-from-orders  (fetch-orders-rows  period mp))
        ;; If ≥2 of 3 sources are absent, triangulation is impossible.
        sources-present (cond-> []
                          (seq fin-qty) (conj :finance)
                          (seq sal-qty) (conj :sales)
                          (seq ord-qty) (conj :orders))
        all-arts  (->> (concat (keys fin-qty) (keys sal-qty) (keys ord-qty))
                       (remove nil?)
                       distinct
                       sort
                       vec)]
    (if (< (count sources-present) 2)
      ;; No triangulation possible — silently return []. The orchestrator
      ;; will reflect source availability in :report/sources-available.
      []
      (vec
        (for [article all-arts
              [src-a src-b] [[:finance :sales] [:finance :orders] [:sales :orders]]
              :let [a (double (or (case src-a
                                    :finance (get fin-qty article)
                                    :sales   (get sal-qty article)
                                    :orders  (get ord-qty article))
                                  0))
                    b (double (or (case src-b
                                    :finance (get fin-qty article)
                                    :sales   (get sal-qty article)
                                    :orders  (get ord-qty article))
                                  0))
                    ;; Only compare pairs where both sources are "alive".
                    both-present? (and (some #{src-a} sources-present)
                                       (some #{src-b} sources-present))
                    d-abs (Math/abs (- a b))
                    d-rel (safe-div d-abs (max (Math/abs a) (Math/abs b) 1.0))]
              :when (and both-present? (pos? d-abs))]
          (let [class (r/classify d-abs d-rel tol)]
            (rep/make-discrepancy
              {:rule-id     :sales-qty-triangle
               :marketplace mp
               :period      period
               :location    {:source-a src-a
                             :source-b src-b
                             :field    "quantity"
                             :article  article}
               :delta       {:a a :b b :abs d-abs :rel d-rel :unit :qty}
               :classification class
               :classification-reason
               (case class
                 :expected (format "within tolerance (abs=%.1f rel=%.4f)" d-abs d-rel)
                 :suspicious (format "%s=%.1f vs %s=%.1f for article %s"
                                     (name src-a) a (name src-b) b (pr-str article))
                 "classified")
               :evidence    {:sources-present sources-present}})))))))

;; ---------------------------------------------------------------------------
;; Rule: :unclassified-operations
;; ---------------------------------------------------------------------------
;;
;; Semantics (edge-case §spec:81):
;;   Scan finance rows for operation values outside a known whitelist.
;;   Each distinct unknown operation → one :unclassified discrepancy
;;   (the invariant from report.clj requires :location.operation non-blank).

(def ^:const known-operations
  ;; Enumerated from real observed raw data. Any other operation surfaces as
  ;; :unclassified and requires developer attention (add here + write vedict).
  #{"sale" "return"
    ;; WB Russian-language operations (from production raw_data)
    "Продажа" "Возврат"
    "Логистика" "Хранение"
    "Компенсация ущерба" "Компенсация подмены"
    "Коррекция продаж" "Коррекция логистики"
    "Обработка товара"
    "Удержание" "Штраф"
    "Возмещение издержек по перевозке/по складским операциям с товаром"
    "Возмещение за выдачу и возврат товаров на ПВЗ"
    "Компенсация скидки по программе лояльности"
    ;; Ozon / YM / English synonyms
    "service"
    "logistics" "storage"
    "acquiring"
    "penalty" "штраф"
    "additional_payment" "deduction"
    ;; YM RFC-3 (2026-04-28): cancelled orders are classified as
    ;; :adjustment in canonical taxonomy; the legacy `:operation` field
    ;; carries the per-item classification "cancelled".
    "cancelled"
    ;; Reserve for future RFC-3 rollout: once the DB is re-materialised,
    ;; canonical kinds may also surface here.
    "adjustment"})

(defn unclassified-operations
  "Rule function for :unclassified-operations."
  [ctx]
  (let [period (:ctx/period ctx)
        mp     (:ctx/marketplace ctx)
        rows   (fetch-finance-rows period mp)
        ;; Group by operation to dedupe, then pick out unknown ops.
        by-op  (group-by :operation rows)
        unknown (->> by-op
                     (filter (fn [[op _]]
                               (and (string? op)
                                    (seq (str/trim op))
                                    (not (contains? known-operations op)))))
                     vec)]
    (mapv
      (fn [[op op-rows]]
        (rep/make-discrepancy
          {:rule-id     :unclassified-operations
           :marketplace mp
           :period      period
           :location    {:source-a  :raw-finance
                         :operation op
                         :article   (some :article op-rows)}
           :delta       {:a 0 :b 0 :abs (count op-rows) :rel 0 :unit :count}
           :classification :unclassified
           :classification-reason
           (format "operation '%s' not in known-operations (%d rows)"
                   op (count op-rows))
           :evidence    {:sample-row-ids (->> op-rows
                                               (map :rrd-id)
                                               (take 5)
                                               vec)
                         :row-count (count op-rows)}}))
      unknown)))

;; ---------------------------------------------------------------------------
;; Rule: :bank-delta
;; ---------------------------------------------------------------------------
;;
;; Semantics (FR-004):
;;   Compare external bank reference sum against SUM(finance.for_pay) for the
;;   period & marketplace. When :ctx/bank-data is nil the rule returns an
;;   empty seq (nothing to compare); the orchestrator decides how to reflect
;;   this in :sources-available.

(defn bank-delta
  [ctx]
  (let [bank (:ctx/bank-data ctx)]
    (if-not (and (map? bank) (number? (:sum bank)))
      []
      (let [period   (:ctx/period ctx)
            mp       (:ctx/marketplace ctx)
            rows     (fetch-finance-rows period mp)
            raw-sum  (reduce + 0.0 (map #(or (:for-pay %) 0) rows))
            bank-sum (double (:sum bank))
            d-abs    (Math/abs (- raw-sum bank-sum))
            d-rel    (safe-div d-abs (max (Math/abs raw-sum)
                                          (Math/abs bank-sum)
                                          1.0))
            class    (r/classify d-abs d-rel (:ctx/tolerance ctx))]
        [(rep/make-discrepancy
           {:rule-id     :bank-delta
            :marketplace mp
            :period      period
            :location    {:source-a :raw-finance
                          :source-b :bank-input
                          :field    "for_pay"}
            :delta       {:a raw-sum :b bank-sum :abs d-abs :rel d-rel :unit :rub}
            :classification class
            :classification-reason
            (case class
              :expected (format "within tolerance (abs=%.2f₽ rel=%.4f)" d-abs d-rel)
              :suspicious (format "raw=%.2f₽ vs bank=%.2f₽ (Δ=%.2f₽, %.2f%%)"
                                  raw-sum bank-sum d-abs (* 100.0 d-rel))
              "classified")
            :evidence    {:raw-row-count   (count rows)
                          :raw-sum         raw-sum
                          :bank-sum        bank-sum
                          :missing-dates   (:missing-dates bank)}})]))))

;; ---------------------------------------------------------------------------
;; Rule: :tax-absence
;; ---------------------------------------------------------------------------
;;
;; Semantics (US3 AS-4, vision §13):
;;   Permanent documentation rule: grep `src/analitica/domain/pnl.clj` for
;;   tax-related tokens. If none found → emit an :expected discrepancy
;;   explaining that tax is out-of-scope per vision. This is by design and
;;   should never flip to :suspicious until tax accounting is added.

(def ^:private tax-token-pattern
  #"(?i)\b(tax|налог|усн|ндс)\b")

(defn- read-pnl-source
  "Return the content of src/analitica/domain/pnl.clj as a string, or nil if
   the file is not accessible (e.g. running from a fat jar where classpath
   lookup is easier than file-system paths)."
  []
  (or
    ;; Classpath lookup — works when the source jar is on the path.
    (try (slurp (io/resource "analitica/domain/pnl.clj"))
         (catch Throwable _ nil))
    ;; Filesystem fallback.
    (try (slurp (io/file "src/analitica/domain/pnl.clj"))
         (catch Throwable _ nil))))

(defn tax-absence
  [ctx]
  (let [period  (:ctx/period ctx)
        mp      (:ctx/marketplace ctx)
        content (read-pnl-source)
        found?  (and content (re-find tax-token-pattern content))]
    (if found?
      []
      [(rep/make-discrepancy
         {:rule-id     :tax-absence
          :marketplace mp
          :period      period
          :location    {:source-a :pnl-code
                        :field    "tax"}
          :delta       {:a 0 :b 0 :abs 0 :rel 0 :unit :rub}
          :classification :expected
          :classification-reason
          "tax out-of-scope per vision §13 — P&L does not include tax accounting (documented gap)"
          :evidence    {:scanned-file "src/analitica/domain/pnl.clj"
                        :source-readable (boolean content)}})])))

;; ---------------------------------------------------------------------------
;; Phase C — cross-source reconciliation rules (added 2026-04-28)
;;
;; Built on top of the rule framework above. These compare *paired endpoints*
;; or *paired tables* that — per concept-crosswalk.md §6 / §11 — should agree
;; numerically within tolerance. A discrepancy outside tolerance signals
;; either a sync gap, a transform bug, or a documented divergence.
;;
;; Source-pair catalogue (see docs/reconciliation.md):
;;   :ozon-finance-vs-cashflow    Ozon finance.for_pay  ⟷ cash_flow_periods.payment
;;   :finance-row-internal        within-row sanity     for_pay vs retail_amount
;;   :wb-finance-vs-sales-events  WB finance.for_pay    ⟷ sales.for_pay (per-event)
;; ---------------------------------------------------------------------------

(defn ozon-finance-vs-cashflow
  "Reconcile Ozon settlement: SUM(finance.for_pay sale) − SUM(finance.for_pay return)
   over the period ⟷ SUM(cash_flow_periods.orders_amount + returns_amount)
   for overlapping rows.

   Why this matters: Ozon publishes the same settlement information through
   two endpoints — `/v2/finance/realization` (per-row, feeds `finance`) and
   `/v1/finance/cash-flow-statement` (period-level, feeds `cash_flow_periods`).
   They should agree on gross sales activity.

   Why `orders_amount + returns_amount` and not `invoice_transfer`?
   E-4 (2026-04-28) field-by-field investigation found that
   `cash_flow_periods.orders_amount` matches `finance.sales_pay` exactly
   per month (Feb 424,814₽ on both sides; Mar 572,975₽ on both sides).
   Likewise `returns_amount` (negative) mirrors `finance.returns_pay`
   (positive). `invoice_transfer` instead represents the *banking*
   side of the flow with weeks of lag, which is not what the rule is
   trying to verify.

   Period coverage caveat: `/v2/finance/realization` is a *month-level*
   report — it lands when the month closes, ~5 days after period end.
   `cash-flow-statement` is *weekly* and lands sooner. So at the leading
   edge of the query window, cash-flow has data that finance doesn't
   have yet. To avoid false positives, we restrict cash-flow to periods
   that fall within the finance table's actual coverage."
  [ctx]
  (let [period   (:ctx/period ctx)
        mp       (:ctx/marketplace ctx)
        from     (:from period)
        to       (:to period)
        ;; Skip when scope isn't Ozon — rule is a no-op for other MPs.
        skip?    (and (not= :all mp) (not= :ozon mp))]
    (if skip?
      []
      (let [;; E-1 (2026-04-28): operation_kind is now a real DB column,
            ;; populated for every row by the E-2 backfill. Filter on it
            ;; directly — no string fallback.
            ;; E-4 (2026-04-28): also pull date_to so we know the actual
            ;; coverage of the finance table; cash-flow is then restricted
            ;; to periods inside that coverage.
            fin-rows (db/query
                       ["SELECT operation_kind, for_pay, date_to
                         FROM finance
                         WHERE marketplace = 'ozon'
                           AND ((event_date IS NOT NULL AND event_date BETWEEN ? AND ?)
                                OR (event_date IS NULL AND date_from <= ? AND date_to >= ?))"
                        from to to from])
            sale?    (fn [r] (= "sale" (:operation-kind r)))
            return?  (fn [r] (= "return" (:operation-kind r)))
            sales-pay   (reduce + 0.0
                          (map #(or (:for-pay %) 0)
                               (filter sale? fin-rows)))
            returns-pay (reduce + 0.0
                          (map #(or (:for-pay %) 0)
                               (filter return? fin-rows)))
            net-pay     (- sales-pay returns-pay)
            ;; Cash-flow is restricted to the finance table's actual
            ;; *sale/return* coverage. Without this, leading-edge weeks
            ;; (Apr 2026 in the sample) show 0 finance sales / non-zero
            ;; cash-flow, blowing up the gap by hundreds of thousands of
            ;; rubles purely because realization (`/v2/finance/realization`)
            ;; is a month-level report that lands later than the weekly
            ;; cash-flow statement. Service rows (from transaction/list)
            ;; can populate later periods even when realization hasn't —
            ;; we filter them out when picking the coverage end.
            fin-cover-end (when (seq fin-rows)
                            (->> fin-rows
                                 (filter #(#{"sale" "return"} (:operation-kind %)))
                                 (keep :date-to)
                                 sort
                                 last))
            cf-end      (or fin-cover-end to)
            cf-rows     (db/query
                          ["SELECT orders_amount, returns_amount FROM cash_flow_periods
                            WHERE source = 'ozon'
                              AND period_begin <= ? AND period_end >= ?
                              AND period_end <= ?"
                           to from cf-end])
            cf-orders   (reduce + 0.0 (map #(or (:orders-amount %) 0) cf-rows))
            cf-returns  (reduce + 0.0 (map #(or (:returns-amount %) 0) cf-rows))
            cf-net      (+ cf-orders cf-returns) ;; returns is already negative
            delta       (- net-pay cf-net)
            d-abs       (Math/abs delta)
            d-rel       (safe-div d-abs (max (Math/abs net-pay)
                                             (Math/abs cf-net)
                                             1.0))
            class       (r/classify d-abs d-rel (:ctx/tolerance ctx))]
        (cond
          (and (zero? (count fin-rows)) (zero? (count cf-rows)))
          []

          (zero? d-abs)
          []

          :else
          [(rep/make-discrepancy
             {:rule-id              :ozon-finance-vs-cashflow
              :marketplace          :ozon
              :period               period
              :location             {:source-a :finance
                                     :source-b :cash-flow-periods
                                     :field    "net_payout"}
              :delta                {:a    net-pay
                                     :b    cf-net
                                     :abs  d-abs
                                     :rel  d-rel
                                     :unit :rub}
              :classification       class
              :classification-reason
              (case class
                :expected   (format "within tolerance (abs=%.2f₽ rel=%.4f) — settlement endpoints agree"
                                    d-abs d-rel)
                :suspicious (format "Ozon settlement gap: realization net %.2f₽ vs cash-flow orders+returns %.2f₽ (Δ=%.2f₽); check finance/cash-flow sync alignment"
                                    net-pay cf-net delta)
                "classified")
              :evidence             {:finance-rows     (count fin-rows)
                                     :cash-flow-rows   (count cf-rows)
                                     :sales-pay        sales-pay
                                     :returns-pay      returns-pay
                                     :net-pay          net-pay
                                     :cf-orders        cf-orders
                                     :cf-returns       cf-returns
                                     :cf-net           cf-net
                                     :coverage-end     fin-cover-end}})])))))

(defn finance-row-internal-consistency
  "Within-row sanity: for sale rows, for_pay should not exceed retail_amount
   by an unreasonable factor. Catches transform bugs that scale the payout
   incorrectly (e.g. accidentally multiplying by quantity twice).

   Tolerance reasoning: Ozon co-investments (`bank_coinvestment`,
   `pick_up_point_coinvestment`, `stars`) can legitimately push for_pay
   ABOVE retail_amount when the seller's bonus partners pay extra to the
   seller for the sale. A factor of 2.0× is a generous cap that rejects
   only catastrophic mismatches (rrd-ids where for_pay > 2 × retail_amount
   AND retail_amount > 0). Production sample 2026-04-28: Ozon max ratio
   1.89×, WB max < 1.5×; the cap chosen so that all legitimate co-
   investment patterns pass while transform bugs (≥ 5×) and YM-source
   data anomalies (retail_amount = 1₽ on a 1700₽ payout — corrupt BUYER
   price in raw_data) still surface.

   Reverse direction (retail_amount >> for_pay) is *expected* — for_pay is
   net of commissions, so it's normally smaller than retail_amount. We do
   NOT flag that direction."
  [ctx]
  (let [period (:ctx/period ctx)
        mp     (:ctx/marketplace ctx)
        rows   (fetch-finance-rows period mp)
        sale?  (fn [r] (= "sale" (:operation-kind r)))
        cap    2.0
        outliers (->> rows
                      (filter sale?)
                      (filter (fn [r]
                                (let [pay (or (:for-pay r) 0)
                                      ret (or (:retail-amount r) 0)]
                                  (and (pos? ret)
                                       (> pay (* cap ret))))))
                      (mapv #(select-keys % [:rrd-id :marketplace :article
                                             :quantity :retail-amount :for-pay])))]
    (if (empty? outliers)
      []
      [(rep/make-discrepancy
         {:rule-id              :finance-row-internal-consistency
          :marketplace          mp
          :period               period
          :location             {:source-a :raw-finance
                                 :field    "for_pay_vs_retail_amount"}
          :delta                {:a   (count outliers)
                                 :b   0
                                 :abs (count outliers)
                                 :rel 0.0
                                 :unit :count}
          :classification       :suspicious
          :classification-reason
          (format "%d sale row(s) have for_pay > %.1f × retail_amount (cap)"
                  (count outliers) cap)
          :evidence             {:cap-factor cap
                                 :outliers   (take 10 outliers)
                                 :total      (count outliers)}})])))

(defn wb-finance-vs-sales-events
  "WB only: SUM(finance.for_pay) sale-rows over period ⟷
   SUM(sales.for_pay) sale-events over the same period.

   Why this matters: WB's `finance.for_pay` (from `/api/v5/supplier/reportDetailByPeriod`)
   and `sales.for_pay` (from `/api/v1/supplier/sales`) come from independent
   endpoints with different sync schedules. Persistent gap means one of:
     - sales is fresher than finance (expected within 24h sync window) → :expected
     - finance has rows the sales feed missed → bug or settlement-only operation
     - sales has rows finance hasn't received yet → typical sync skew

   Skips for non-WB marketplaces. Skips with no-op when either source is empty
   (e.g. brand-new install)."
  [ctx]
  (let [period  (:ctx/period ctx)
        mp      (:ctx/marketplace ctx)
        skip?   (and (not= :all mp) (not= :wb mp))]
    (if skip?
      []
      (let [fin-rows  (filter #(= "wb" (or (:marketplace %) "wb"))
                              (fetch-finance-rows period :wb))
            ;; E-2: operation_kind is populated for every legacy row; use it.
            sale-fin? (fn [r] (= "sale" (:operation-kind r)))
            fin-sum   (reduce + 0.0
                        (map #(or (:for-pay %) 0)
                             (filter sale-fin? fin-rows)))
            sales-rows (fetch-sales-rows period :wb)
            ;; sales.type is "sale" / "return" / "cancel" in current production
            ;; data, but a legacy single-letter encoding ("S" / "R") survives in
            ;; older fixtures and possibly some unmaterialised rows. Accept both.
            sale-evt? (fn [s] (#{"sale" "S"} (:type s)))
            sales-sum (reduce + 0.0
                        (map #(or (:for-pay %) 0)
                             (filter sale-evt? sales-rows)))
            delta     (- fin-sum sales-sum)
            d-abs     (Math/abs delta)
            d-rel     (safe-div d-abs (max (Math/abs fin-sum)
                                           (Math/abs sales-sum)
                                           1.0))
            class     (r/classify d-abs d-rel (:ctx/tolerance ctx))]
        (cond
          ;; Both empty → nothing to reconcile.
          (and (zero? (count fin-rows)) (zero? (count sales-rows)))
          []

          ;; One side empty: cannot meaningfully compare — likely an
          ;; un-synced source rather than a transform bug. Emit nothing;
          ;; sync-coverage tooling owns this signal.
          (or (zero? (count fin-rows)) (zero? (count sales-rows)))
          []

          (zero? d-abs)
          []

          :else
          [(rep/make-discrepancy
             {:rule-id              :wb-finance-vs-sales-events
              :marketplace          :wb
              :period               period
              :location             {:source-a :finance
                                     :source-b :sales-events
                                     :field    "for_pay"}
              :delta                {:a    fin-sum
                                     :b    sales-sum
                                     :abs  d-abs
                                     :rel  d-rel
                                     :unit :rub}
              :classification       class
              :classification-reason
              (case class
                :expected   (format "within tolerance (abs=%.2f₽ rel=%.4f) — endpoints agree" d-abs d-rel)
                :suspicious (format "WB endpoint gap: finance %.2f₽ vs sales %.2f₽ (Δ=%.2f₽); check sync skew or transform" fin-sum sales-sum delta)
                "classified")
              :evidence             {:finance-rows  (count fin-rows)
                                     :sales-rows    (count sales-rows)
                                     :finance-sum   fin-sum
                                     :sales-sum     sales-sum}})])))))

(defn ym-buyer-price-anomaly
  "YM data-quality rule (E-5, 2026-04-28). YM `stats/orders` occasionally
   returns a `prices[BUYER].total` of 1₽ on orders where the actual
   commissions and seller payout are in the thousands. Production sample
   2026-03 → 2026-04: 5 such rows out of 197 sales (2.5%).

   Likely cause is upstream YM API quirk for specific order types
   (e.g. cash-on-delivery edge cases, partial delivery, manual price
   corrections). It's not a transform bug — our YM transform faithfully
   stores BUYER total — but it produces wildly inflated unit-economics
   if not surfaced.

   Rule: flag YM sale rows where retail_amount × 5 < for_pay (5× chosen
   so it captures the 1₽ → 1700₽ class without flagging legitimate
   subsidy-heavy orders where for_pay ≈ 2 × retail). Reports outlier
   rows as evidence for the seller to verify against YM ЛК.

   No-op for non-YM scope. No-op when retail_amount = 0 (handled
   separately by other rules)."
  [ctx]
  (let [period (:ctx/period ctx)
        mp     (:ctx/marketplace ctx)
        skip?  (and (not= :all mp) (not= :ym mp))]
    (if skip?
      []
      (let [rows     (filter #(= "ym" (:marketplace %))
                             (fetch-finance-rows period (if (= :all mp) :ym mp)))
            sale?    (fn [r] (= "sale" (:operation-kind r)))
            outlier? (fn [r]
                       (let [pay (or (:for-pay r) 0)
                             ret (or (:retail-amount r) 0)]
                         (and (sale? r)
                              (> ret 0)
                              (> pay (* 5 ret)))))
            outliers (->> rows
                          (filter outlier?)
                          (mapv #(select-keys % [:rrd-id :article :event-date
                                                 :retail-amount :for-pay
                                                 :mp-commission :delivery-cost])))]
        (if (empty? outliers)
          []
          [(rep/make-discrepancy
             {:rule-id              :ym-buyer-price-anomaly
              :marketplace          (if (= :all mp) :ym mp)
              :period               period
              :location             {:source-a :raw-finance
                                     :field    "buyer_price_vs_for_pay"}
              :delta                {:a   (count outliers)
                                     :b   0
                                     :abs (count outliers)
                                     :rel 0.0
                                     :unit :count}
              :classification       :suspicious
              :classification-reason
              (format "%d YM sale row(s) with for_pay > 5 × retail_amount — likely YM source data quality issue (BUYER price too low)"
                      (count outliers))
              :evidence             {:cap-factor 5
                                     :outliers   (take 10 outliers)
                                     :total      (count outliers)
                                     :note       "Cross-check against YM ЛК; not a transform bug"}})])))))

;; ---------------------------------------------------------------------------
;; L2-C Phase rules — cross-report metric agreement (added 2026-04-28)
;;
;; Phase L2-A revealed 16 metrics that appear in 2+ reports (UE, P&L,
;; Finance). For the same finance-data and period, those metrics MUST
;; agree numerically — they're computing the same business quantity from
;; the same input rows. Any drift means one of the report's domain code
;; subtly diverged (different filter, different rounding, different
;; default).
;;
;; Implementation note: Finance/UE expose `:total-*` keys in their
;; `totals` map; P&L exposes plain keys in its `calculate` map. We
;; canonicalise both into a flat {metric → value} map, then compare
;; pairwise.
;; ---------------------------------------------------------------------------

(def ^:private cross-report-pairs
  "[{:metric :report-a :key-a :report-b :key-b}] — same business value,
   different access path. Add new pairs as L2 evolves.

   Key-name conventions (discovered during L2-D 2026-04-28):
     - finance/totals:  prefix `:total-*` for sums, plain key for non-sums
     - ue/totals:       MIXED — `:total-*` for monetary sums, plain
                        `:sales-qty`/`:returns-qty` for quantities
     - pnl/calculate:   plain key for everything"
  [;; UE totals ↔ Finance totals
   {:metric :revenue        :a :ue :ka :total-revenue        :b :finance :kb :total-revenue}
   {:metric :wb-reward      :a :ue :ka :total-wb-reward      :b :finance :kb :total-wb-reward}
   {:metric :logistics      :a :ue :ka :total-logistics      :b :finance :kb :total-logistics}
   {:metric :acceptance     :a :ue :ka :total-acceptance     :b :finance :kb :total-acceptance}
   {:metric :penalties      :a :ue :ka :total-penalties      :b :finance :kb :total-penalties}
   {:metric :deduction      :a :ue :ka :total-deduction      :b :finance :kb :total-deduction}
   {:metric :additional     :a :ue :ka :total-additional     :b :finance :kb :total-additional}
   {:metric :acquiring      :a :ue :ka :total-acquiring      :b :finance :kb :total-acquiring}
   ;; UE totals ↔ P&L (P&L uses unprefixed keys)
   {:metric :revenue        :a :ue :ka :total-revenue        :b :pnl :kb :revenue}
   {:metric :wb-reward      :a :ue :ka :total-wb-reward      :b :pnl :kb :wb-reward}
   {:metric :logistics      :a :ue :ka :total-logistics      :b :pnl :kb :logistics}
   {:metric :storage        :a :ue :ka :total-storage        :b :pnl :kb :storage}
   {:metric :acceptance     :a :ue :ka :total-acceptance     :b :pnl :kb :acceptance}
   {:metric :penalties      :a :ue :ka :total-penalties      :b :pnl :kb :penalties}
   {:metric :deduction      :a :ue :ka :total-deduction      :b :pnl :kb :deduction}
   {:metric :additional     :a :ue :ka :total-additional     :b :pnl :kb :additional}
   ;; Quantities — UE totals exposes them under plain `:sales-qty`/`:returns-qty`
   ;; (a nominal asymmetry vs the `:total-*` prefix used for sums).
   {:metric :sales-qty      :a :ue :ka :sales-qty            :b :pnl :kb :sales-qty}
   {:metric :returns-qty    :a :ue :ka :returns-qty          :b :pnl :kb :returns-qty}])

(defn- safe-num [v] (or v 0))

(defn- mp-keyword
  "Audit context :ctx/marketplace is :all/:wb/:ozon/:ym; pnl/calculate
   wants nil (= no filter) when scope is :all."
  [mp]
  (when (and mp (not= :all mp)) mp))

(def ^:private l2-strict-metrics
  "Metrics whose three-way reports MUST agree to the kopek. These are
   driven entirely by sale/return rows and don't include account-level
   bleed."
  #{:revenue :sales-qty :returns-qty :for-pay :acquiring :acceptance})

(def ^:private l2-account-level-metrics
  "Metrics where UE intentionally drops account-level rows
   (`article=\"\"`, e.g. WB-side storage, deduction, wb-reward charges
   that don't belong to a specific seller article) while Finance/P&L
   include them. Drift here is **expected by design**, not a bug — we
   flag it as :informational so the seller knows the per-article view
   under-reports these costs by exactly the account-level slice.

   Discovered L2-D 2026-04-28 against production data: 110k₽ storage,
   846₽ wb-reward, 2000₽ deduction all on one WB account-level row
   with `article=\"\"`."
  #{:storage :wb-reward :deduction :additional :penalties :logistics})

(defn l2-cross-report-agreement
  "Compute the same metric set via three independent paths
   (Finance.totals, P&L.calculate, UE.totals) and emit a discrepancy
   per metric pair that disagrees beyond tolerance.

   Same `finance-data` is fed to all three so any drift is purely in
   the formulas/aggregations. A clean run is the strongest possible
   evidence that the L2 layer is internally consistent.

   Two-tier classification (L2-D 2026-04-28):
   - `l2-strict-metrics` — flagged :suspicious on any drift (rounding aside).
   - `l2-account-level-metrics` — drift is *expected* (UE drops blank-article
     rows that Finance/P&L include); we still emit but classify :expected
     when the drift is below 5% rel.

   Caveats:
   - P&L cf-adjustments are NOT compared here — they're a separate path.
   - Tolerance for strict metrics: ±1₽ abs OR 0.1% rel."
  [ctx]
  (let [period   (:ctx/period ctx)
        mp       (:ctx/marketplace ctx)
        rows     (fetch-finance-rows period mp)
        fin-totals (finance/totals rows)
        ue-totals  (ue/totals (ue/calculate rows))
        pnl-data   (pnl/calculate rows :marketplace (mp-keyword mp))
        report-map {:finance fin-totals :ue ue-totals :pnl pnl-data}
        tol      (:ctx/tolerance ctx)]
    (->> cross-report-pairs
         (keep (fn [{:keys [metric a ka b kb]}]
                 (let [va (safe-num (get-in report-map [a ka]))
                       vb (safe-num (get-in report-map [b kb]))
                       d  (Math/abs (- (double va) (double vb)))
                       rel (safe-div d (max (Math/abs (double va))
                                            (Math/abs (double vb))
                                            1.0))
                       account-level? (l2-account-level-metrics metric)
                       cls (cond
                             (zero? d) :expected
                             ;; Account-level metrics: UE drops `article=""` row;
                             ;; Finance/P&L include it. Drift is design-by-construction,
                             ;; classify :expected regardless of magnitude.
                             account-level? :expected
                             :else (r/classify d rel tol))]
                   (when (not (zero? d))
                     (rep/make-discrepancy
                       {:rule-id              :l2-cross-report-agreement
                        :marketplace          mp
                        :period               period
                        :location             {:source-a (keyword (str (name a) "." (name ka)))
                                               :source-b (keyword (str (name b) "." (name kb)))
                                               :field    (name metric)}
                        :delta                {:a    (double va)
                                               :b    (double vb)
                                               :abs  d
                                               :rel  rel
                                               :unit (if (#{:sales-qty :returns-qty} metric) :qty :rub)}
                        :classification       cls
                        :classification-reason
                        (cond
                          (= cls :expected)
                          (if account-level?
                            (format "%s drift expected (account-level row dropped by UE): Δ=%.2f"
                                    (name metric) d)
                            (format "%s agrees within tolerance (Δ=%.2f)" (name metric) d))
                          (= cls :suspicious)
                          (format "%s drift between %s and %s: %.2f vs %.2f (Δ=%.2f)"
                                  (name metric) (name a) (name b)
                                  (double va) (double vb) d)
                          :else "classified")
                        :evidence             {:reports-checked [a b]
                                               :both-keys      {a ka b kb}
                                               :account-level? account-level?}})))))
         (filter some?)
         vec)))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn register-all!
  "Register all known rules. Idempotent — safe to call multiple times."
  []
  (r/register-rule!
    {:rule/id          :aggregate-vs-raw
     :rule/description "SUM(raw.for_pay) vs SUM(by-article :for-pay) — catches account-level drops"
     :rule/marketplace :all
     :rule/sources     [:raw-finance :agg-finance]
     :rule/severity    :informational
     ;; E-3 (2026-04-28): design-gap rule — by-article subtracts returns
     ;; while raw sums them, so the gap = 2 × returns_for_pay even on
     ;; clean data. Loosen rel ≤ 0.5 (50%) so the rule surfaces only
     ;; truly outsized drops (B-002 account-level bleed > 50% relative).
     :rule/tolerance   {:abs 1000.0 :rel 0.5}
     :rule/fn          aggregate-vs-raw})
  (r/register-rule!
    {:rule/id          :sales-qty-triangle
     :rule/description "Compare sales qty per article across finance, sales, orders"
     :rule/marketplace :all
     :rule/sources     [:finance :sales :orders]
     :rule/severity    :critical
     :rule/fn          sales-qty-triangle})
  (r/register-rule!
    {:rule/id          :unclassified-operations
     :rule/description "Surface finance operation values not in the known whitelist"
     :rule/marketplace :all
     :rule/sources     [:raw-finance]
     :rule/severity    :informational
     :rule/fn          unclassified-operations})
  (r/register-rule!
    {:rule/id          :bank-delta
     :rule/description "SUM(raw.for_pay) vs bank reference (optional — no-op if bank-data absent)"
     :rule/marketplace :all
     :rule/sources     [:raw-finance :bank-input]
     :rule/severity    :critical
     :rule/fn          bank-delta})
  (r/register-rule!
    {:rule/id          :tax-absence
     :rule/description "Permanent doc rule: P&L formula has no tax terms (expected, out-of-scope)"
     :rule/marketplace :all
     :rule/sources     [:pnl-code]
     :rule/severity    :informational
     :rule/fn          tax-absence})
  ;; Phase C reconciliation rules (2026-04-28):
  (r/register-rule!
    {:rule/id          :ozon-finance-vs-cashflow
     :rule/description "Ozon settlement: finance net payout ⟷ cash_flow_periods.payment"
     :rule/marketplace #{:ozon}
     :rule/sources     [:finance :cash-flow-periods]
     :rule/severity    :critical
     :rule/fn          ozon-finance-vs-cashflow})
  (r/register-rule!
    {:rule/id          :finance-row-internal-consistency
     :rule/description "Within-row sanity: for_pay should not exceed retail_amount × 1.5"
     :rule/marketplace :all
     :rule/sources     [:raw-finance]
     :rule/severity    :critical
     :rule/fn          finance-row-internal-consistency})
  (r/register-rule!
    {:rule/id          :wb-finance-vs-sales-events
     :rule/description "WB cross-endpoint: finance.for_pay ⟷ sales.for_pay (sale events)"
     :rule/marketplace #{:wb}
     :rule/sources     [:finance :sales]
     :rule/severity    :critical
     ;; E-3: WB settlement (`reportDetailByPeriod`) syncs weekly while
     ;; the sales feed (`supplier/sales`) is near-realtime. Tail-window
     ;; sync skew of 5-10% is normal — only flag larger gaps.
     :rule/tolerance   {:abs 100.0 :rel 0.1}
     :rule/fn          wb-finance-vs-sales-events})
  ;; E-5 (2026-04-28): YM data-quality detector.
  (r/register-rule!
    {:rule/id          :ym-buyer-price-anomaly
     :rule/description "YM source-data quality: BUYER price implausibly low vs commissions"
     :rule/marketplace #{:ym}
     :rule/sources     [:raw-finance]
     :rule/severity    :informational
     :rule/fn          ym-buyer-price-anomaly})
  ;; L2-C (2026-04-28): cross-report metric agreement.
  (r/register-rule!
    {:rule/id          :l2-cross-report-agreement
     :rule/description "Same metric via different reports (Finance/P&L/UE) must agree numerically"
     :rule/marketplace :all
     :rule/sources     [:finance :pnl :unit-economics]
     :rule/severity    :critical
     ;; L2 metrics rounded at math/round2 — allow 1₽ abs / 0.1% rel.
     :rule/tolerance   {:abs 1.0 :rel 0.001}
     :rule/fn          l2-cross-report-agreement}))
