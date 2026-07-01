(ns analitica.web.user-metrics-test
  "016 US5 (T059/T060) — safe EDN-AST evaluator for user-defined metrics.

   A user metric is a formula composed ONLY of arithmetic operators
   {:+ :- :* :/} over canonical-slug leaves or numeric literals. It is
   evaluated by a PURE INTERPRETER (`eval-user-metric`) that walks the AST over
   a row map — NEVER clojure.core/eval, NEVER read-eval. Divide-by-zero yields
   nil (rendered '—'), never a throw. Unknown slugs are rejected as validation
   errors, not executed.

   Slug→row-key aliases (per contracts/descriptor-schema.edn :slug-row-key-aliases):
     :gross-margin → :gross-profit   (pnl emits :gross-profit)
     :advertising  → :ad-spend       (pnl emits :ad-spend)"
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.report-schemas :as rs]))

;; A representative per-article/period row map (subset of pnl/calculate + aggregator output).
(def ^:private row
  {:revenue      10000.0
   :net-profit   2500.0
   :gross-profit 4000.0   ; :gross-margin slug aliases to this
   :ad-spend     500.0    ; :advertising slug aliases to this
   :mp-commission 1500.0
   :cogs         3000.0})

;; ---------------------------------------------------------------------------
;; T059 — AST evaluation over slug leaves + operators
;; ---------------------------------------------------------------------------

(deftest eval-arithmetic-over-slug-leaves
  (testing "the four operators evaluate correctly over slug leaves and numeric literals"
    (is (= 12500.0 (rs/eval-user-metric [:+ :revenue :net-profit] row)) "+")
    (is (= 7500.0  (rs/eval-user-metric [:- :revenue :net-profit] row)) "-")
    (is (= 5000.0  (rs/eval-user-metric [:* :net-profit 2] row))        "* with numeric literal")
    (is (= 0.25    (rs/eval-user-metric [:/ :net-profit :revenue] row)) "/ (Маржа X = net-profit / revenue)")))

(deftest eval-nested-ast
  (testing "nested formulas evaluate recursively"
    ;; (revenue - cogs - mp-commission) / revenue
    (is (= 0.55
           (rs/eval-user-metric [:/ [:- [:- :revenue :cogs] :mp-commission] :revenue] row)))))

(deftest eval-bare-leaf
  (testing "a bare slug or number is a valid formula"
    (is (= 10000.0 (rs/eval-user-metric :revenue row)))
    (is (= 42.0    (rs/eval-user-metric 42 row)))))

;; ---------------------------------------------------------------------------
;; T059 — slug aliases resolve (else a slug silently yields nil)
;; ---------------------------------------------------------------------------

(deftest eval-slug-aliases-resolve
  (testing "aliased display-slugs resolve to their emitted row-key
            (:gross-margin→:gross-profit, :advertising→:ad-spend)"
    (is (= 4000.0 (rs/eval-user-metric :gross-margin row))
        ":gross-margin resolves to the :gross-profit row value")
    (is (= 500.0 (rs/eval-user-metric :advertising row))
        ":advertising resolves to the :ad-spend row value")
    ;; and they compose in a formula
    (is (= 3500.0 (rs/eval-user-metric [:- :gross-margin :advertising] row))
        "aliased slugs compose in arithmetic")))

;; ---------------------------------------------------------------------------
;; T059 — divide-by-zero → nil, never throw
;; ---------------------------------------------------------------------------

(deftest eval-divide-by-zero-is-nil
  (testing "division by zero yields nil (rendered '—'), never an exception"
    (is (nil? (rs/eval-user-metric [:/ :net-profit :revenue] (assoc row :revenue 0.0))))
    (is (nil? (rs/eval-user-metric [:/ :net-profit 0] row)))
    ;; nested div-by-zero also propagates as nil, not a crash
    (is (nil? (rs/eval-user-metric [:+ 1 [:/ :net-profit 0]] row)))))

(deftest eval-missing-row-value-is-nil
  (testing "a valid slug absent from the row (e.g. metric not computed for this row)
            yields nil rather than throwing"
    (is (nil? (rs/eval-user-metric :gmroi row))
        ":gmroi is a canonical slug but absent from this row → nil, no crash")
    ;; and a nil operand propagates to nil, not an NPE
    (is (nil? (rs/eval-user-metric [:+ :gmroi :revenue] row)))))

;; ---------------------------------------------------------------------------
;; T059 — unknown slug ⇒ validation error, NOT a crash / NOT executed
;; ---------------------------------------------------------------------------

(deftest unknown-slug-is-validation-error
  (testing "a slug NOT in :canonical-metric-slugs is rejected by validation
            (valid-formula?), never silently evaluated"
    (is (false? (rs/valid-formula? [:/ :net-profit :not-a-real-slug]))
        "unknown slug fails formula validation")
    (is (false? (rs/valid-formula? :totally-made-up))
        "bare unknown slug fails validation")
    (is (true? (rs/valid-formula? [:/ :net-profit :revenue]))
        "a formula over canonical slugs passes validation")
    (is (true? (rs/valid-formula? [:* [:+ :cogs :logistics] 2]))
        "nested valid formula passes")))

(deftest malformed-ast-is-validation-error
  (testing "malformed ASTs (bad operator, wrong arity, non-numeric literal) fail validation"
    (is (false? (rs/valid-formula? [:pow :revenue 2])) "operator not in #{+ - * /}")
    (is (false? (rs/valid-formula? [:+ :revenue]))     "wrong arity (needs 2 args)")
    (is (false? (rs/valid-formula? [:+ :revenue :net-profit :cogs])) "wrong arity (too many)")
    (is (false? (rs/valid-formula? "revenue / net-profit")) "string is not an AST")
    (is (false? (rs/valid-formula? [])) "empty vector is not an AST")))

;; ---------------------------------------------------------------------------
;; VR-u2 — evaluator is a PURE INTERPRETER (no eval / no read-eval)
;; ---------------------------------------------------------------------------

(deftest evaluator-source-is-pure-interpreter
  (testing "VR-u2: report_schemas.clj eval-user-metric contains no (eval …),
            no read-eval binding, no clojure.core/eval reference"
    (let [src (slurp "src/analitica/web/report_schemas.clj")]
      (is (not (re-find #"\bclojure\.core/eval\b" src))
          "must not reference clojure.core/eval")
      (is (not (re-find #"\(eval\s" src))
          "must not call (eval …)")
      (is (not (re-find #"read-eval" src))
          "must not use read-eval")
      (is (not (re-find #"load-string" src))
          "must not use load-string"))))

;; ---------------------------------------------------------------------------
;; T060 — a saved user metric emits as an ordinary ColumnDescriptor (US1 path)
;; ---------------------------------------------------------------------------

(deftest user-metric-emits-as-column-descriptor
  (testing "T060/VR-u1: user-metric->descriptor yields a valid ColumnDescriptor with
            :hint (human formula + basis) / :suffix / :positiveIfGrow / :filterType,
            so it renders through the SAME US1 path as built-in columns"
    (let [m {:slug :margin-x
             :name "Маржа X"
             :formula [:/ :net-profit :revenue]
             :suffix :pct
             :filterType :number-range
             :positiveIfGrow true
             :basis "net profit / gross realisation"}
          d (rs/user-metric->descriptor m)]
      (is (= :margin-x (:key d)) ":key is the slug")
      (is (= "Маржа X" (:title d)))
      (is (= :pct (:suffix d)))
      (is (= :number-range (:filterType d)))
      (is (= true (:positiveIfGrow d)))
      (is (true? (:user-defined? d)) "flagged user-defined? so SPA can offer edit/delete")
      (is (string? (:hint d)) ":hint present")
      (is (re-find #"net profit / gross realisation" (:hint d))
          ":hint folds in the free-text basis (P6/FR-004)")
      (is (some? (rs/validate-descriptor d))
          "emitted descriptor validates against ColumnDescriptor (US1 render-path)"))))

(deftest ratio-metric-renders-as-ratio-not-rub
  (testing "T059/GMROI-style: a ratio metric declares :suffix :ratio (dimensionless,
            e.g. 4.2×), NEVER ₽"
    (let [m {:slug :gmroi-like
             :name "GMROI-подобная"
             :formula [:/ :net-profit :cogs]
             :suffix :ratio
             :filterType :number-range
             :positiveIfGrow true
             :basis "net profit / cost"}
          d (rs/user-metric->descriptor m)]
      (is (= :ratio (:suffix d)) ":suffix :ratio for a dimensionless ratio")
      (is (= :ratio (:format d)) ":format :ratio (NOT :rub) so the SPA does not append ₽")
      ;; and it computes as a plain ratio
      (is (= (double (/ 2500.0 3000.0))
             (rs/eval-user-metric (:formula m) row))))))
