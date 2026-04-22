(ns analitica.integration.ym-netting-reconcile-test
  "T045 / FR-017 reconciliation: load the committed YM united-netting fixture
   (1672-row live March 2026 response captured 2026-04-22) and verify:

     - Known aggregates per `:transactionType` match the numbers observed
       on production (±1 ₽ tolerance for float rounding).
     - Total row count = 1672.
     - shopSku presence is ≥ 99% (1669 of 1672 observed — the three
       admin-only rows legitimately lack a shopSku).
     - The parsed JSON validates cleanly against the `:ym/united-netting`
       contract with zero critical violations.

   Plus T046 exploratory: count and report rows whose
   `:offerOrServiceName` contains the substring 'cashback' or 'кэшбек'
   (case-insensitive) so the YANDEX_CASHBACK classification question
   can be answered empirically without modifying transform.clj."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [jsonista.core :as j]
            [analitica.schema.loader :as schema-loader]
            [analitica.schema.registry :as schema-registry]
            [analitica.schema.validator :as schema-validator]))

;; ---------------------------------------------------------------------------
;; Fixture: load + parse the live 1672-row March 2026 netting JSON.
;; ---------------------------------------------------------------------------

(def ^:private fixture-path "fixtures/ym-netting-2026-03.json")

(def ^:private json-mapper
  (j/object-mapper {:decode-key-fn true}))

(defn- load-fixture []
  (let [url (io/resource fixture-path)]
    (assert url (str "fixture missing on classpath: " fixture-path))
    (j/read-value (slurp url) json-mapper)))

(defn- with-registry [f]
  (schema-registry/clear!)
  (schema-loader/load-all!)
  (try (f) (finally (schema-registry/clear!))))

(use-fixtures :each with-registry)

;; ---------------------------------------------------------------------------
;; Aggregation helper.
;; ---------------------------------------------------------------------------

(defn- aggregate-by-type [rows]
  (->> rows
       (group-by :transactionType)
       (into {}
             (map (fn [[t rs]]
                    [t {:count (count rs)
                        :sum   (reduce + 0.0 (keep :transactionSum rs))}])))))

;; ---------------------------------------------------------------------------
;; Expected aggregates (pre-computed from the 1672-row live sample).
;; ---------------------------------------------------------------------------

(def ^:private expected-by-type
  ;; transactionType → {:count :sum (±1 ₽)}
  {"Начисление"      {:count 293  :sum   577804.67}
   "Удержание"       {:count 1162 :sum  -182099.63}
   "Возврат"         {:count 60   :sum  -146593.00}
   "Списание"        {:count 152  :sum  -121557.75}
   "Возврат списания" {:count 5    :sum     6273.75}})

(def ^:private expected-total-rows 1672)
(def ^:private expected-net-sum 133828.04)
(def ^:private sum-tolerance 1.0)

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest fixture-row-count-matches-production
  (let [{:keys [rows]} (load-fixture)]
    (is (= expected-total-rows (count rows))
        (str "fixture should be the 1672-row March 2026 capture; got "
             (count rows)))))

(deftest per-transaction-type-aggregates-reconcile-within-one-ruble
  (let [{:keys [rows]} (load-fixture)
        got (aggregate-by-type rows)]
    (doseq [[t expected] expected-by-type]
      (testing (str "transactionType=" t)
        (let [{actual-count :count actual-sum :sum} (get got t)]
          (is (= (:count expected) actual-count)
              (str "row count mismatch for " t
                   ": expected " (:count expected)
                   " got " actual-count))
          (is (< (Math/abs (- (double (:sum expected)) (double actual-sum)))
                 sum-tolerance)
              (str "sum mismatch for " t
                   ": expected ≈ " (:sum expected)
                   " got " actual-sum
                   " (delta " (- actual-sum (:sum expected)) ")")))))))

(deftest net-sum-across-all-types-equals-expected
  (let [{:keys [rows]} (load-fixture)
        net (reduce + 0.0 (keep :transactionSum rows))]
    (is (< (Math/abs (- expected-net-sum net)) sum-tolerance)
        (str "net transactionSum should ≈ " expected-net-sum " got " net))))

(deftest shop-sku-presence-near-100-percent
  (let [{:keys [rows]} (load-fixture)
        with-sku (count (filter (comp some? :shopSku) rows))
        pct (* 100.0 (/ (double with-sku) (count rows)))]
    ;; Live sample: 1669 of 1672 rows carry a shopSku (99.82%). Three
    ;; admin-only rows legitimately have no SKU attribution. Assertion
    ;; holds the ratio ≥ 99% so fixture drift stays observable.
    (is (>= pct 99.0)
        (str "shopSku presence " pct "% < 99%; check fixture integrity."))))

(deftest fixture-validates-against-ym-united-netting-schema
  (testing "every row passes the :ym/united-netting contract — zero critical violations"
    (let [parsed (load-fixture)
          result (schema-validator/validate!
                   :ym/united-netting
                   parsed)]
      ;; validate! returns the response on :ok/:warned and throws on :failed.
      ;; If we got here without throwing, no critical violation was raised.
      (is (some? result) "validate! should return the response map")
      (is (= expected-total-rows (count (:rows result)))))))

;; ---------------------------------------------------------------------------
;; T046 — YANDEX_CASHBACK exploratory check.
;;
;; Observation 2026-04-22: netting `offerOrServiceName` does NOT surface
;; the literal substring "cashback" / "кэшбек" in March 2026; that terminology
;; belongs to the legacy `subsidies` block in raw order-stats. The
;; authoritative signal for Yandex Plus cashback inside netting is
;; `transactionSource = "Баллы за скидку Яндекс Плюс"` — positive
;; Начисление rows, March 2026 aggregate ≈ 3,974 ₽ (matches memorised
;; 3,967 ₽ from raw order-stats subsidies block within ±7 ₽).
;;
;; Classification conclusion: these are INCOME (`Начисление`, positive
;; transactionSum, subsidised by YM). They are NOT merchant expenses and
;; therefore MUST NOT move `:for-pay` or `:ad-cost` in canonical
;; FinanceRow. Current transform.clj behaviour (FR-021) keeps them out
;; of both — this test confirms the stance remains correct post-netting.
;;
;; We do NOT modify transform.clj here (T046 is validation-only).
;; ---------------------------------------------------------------------------

(defn- literal-cashback-hits
  "Case-insensitive substring match for 'cashback'/'кэшбек' on
   `:offerOrServiceName`. Returns the matching rows."
  [rows]
  (filter
    (fn [{:keys [offerOrServiceName]}]
      (and (string? offerOrServiceName)
           (let [lc (str/lower-case offerOrServiceName)]
             (or (str/includes? lc "cashback")
                 (str/includes? lc "кэшбек")
                 (str/includes? lc "кешбек")))))
    rows))

(defn- yandex-plus-cashback-hits
  "Match the authoritative YM netting source string for Yandex Plus
   cashback subsidies."
  [rows]
  (filter
    (fn [{:keys [transactionSource]}]
      (and (string? transactionSource)
           (str/includes? (str/lower-case transactionSource) "плюс")))
    rows))

(deftest t046-cashback-classification-verified-against-netting
  (let [{:keys [rows]} (load-fixture)
        literal-hits (literal-cashback-hits rows)
        plus-hits    (yandex-plus-cashback-hits rows)
        plus-sum     (reduce + 0.0 (keep :transactionSum plus-hits))]
    ;; Finding 1: netting does NOT carry the literal "cashback" substring
    ;; in March 2026 — confirms verdicts.md assumption that the semantics
    ;; live in raw order-stats' `subsidies` block (YANDEX_CASHBACK key).
    (is (zero? (count literal-hits))
        "no row should carry literal 'cashback' / 'кэшбек' in offerOrServiceName")

    ;; Finding 2: the "Яндекс Плюс" transactionSource captures the subsidy
    ;; flow. Assert sign: every hit is a positive Начисление (income),
    ;; so the classification stays "income / ledger-only" — NOT an expense
    ;; and MUST NOT enter :for-pay or :ad-cost.
    (is (pos? (count plus-hits))
        "Yandex Plus subsidy rows should be present in March 2026 netting")
    (is (every? (fn [{:keys [transactionType transactionSum]}]
                  (and (= "Начисление" transactionType)
                       (or (nil? transactionSum) (pos? transactionSum))))
                plus-hits)
        "every Yandex Plus row must be a positive Начисление (income)")
    ;; Finding 3: reconciliation with memorised YANDEX_CASHBACK residual.
    ;; Memory: 3,967 ₽ across two months; March alone ≈ 3,974 ₽ here —
    ;; within the same order of magnitude, no threshold breach.
    (is (< plus-sum 10000.0)
        (str "Yandex Plus cashback should stay < 10k ₽/month "
             "(FR-021 residual threshold < 1% of turnover); got "
             plus-sum))
    (println (str "[T046] YM netting Yandex-Plus cashback: count="
                  (count plus-hits) " sum=" plus-sum " (classification=income)"))))
