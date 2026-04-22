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
            [analitica.domain.finance :as finance]))

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

(defn- qty-by-article-from-finance [rows]
  (->> rows
       (filter #(contains? sale-operations (:operation %)))
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
    "additional_payment" "deduction"})

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
     :rule/severity    :critical
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
     :rule/fn          tax-absence}))
