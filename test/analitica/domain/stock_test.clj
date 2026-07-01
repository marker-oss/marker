(ns analitica.domain.stock-test
  "016-US2 — capitalization / coverage-aware GMROI / turnover invariants.
   Written BEFORE implementation (TDD). Fixture:
   test/resources/fixtures/stocks-history-2026-05.edn (T002).

   Invariants under test:
     T016 — VR-c1/SC-003: Σ cap-by-cost (non-nil) == :cap-by-cost-total to the kopeck;
            FR-014: :stock-qty-total == (:total-full (stock/totals ...)).
     T017 — VR-g1/SC-004: toggling one zero-stock day changes BOTH covered-days
            AND the daily-cap-series length; all-uncovered SKU → :not-applicable
            (NOT 0/∞); VR-g2/FR-009: numerator is net-profit, NOT textbook
            gross-margin/avg-inventory.
     T018 — FR-012: turnover from Σqty (one 5-unit order → daily-rate from 5, not 1);
            FR-011: gmroi-annualized == period-gmroi × (365 / days-in-period)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [analitica.domain.stock :as stock]
            [analitica.util.math :as math]))

(def fixture
  (delay (edn/read-string (slurp (io/resource "fixtures/stocks-history-2026-05.edn")))))

(defn- fx [& ks] (get-in @fixture ks))

;; ---------------------------------------------------------------------------
;; T016 — capitalization-by-cost totals (VR-c1 / FR-014)
;; ---------------------------------------------------------------------------

(deftest t016-capitalization-by-cost-invariants
  (let [f          @fixture
        stocks     (:stocks f)
        cost-fn    #(get (:cost-prices f) %)
        result     (stock/capitalize stocks :cost-fn cost-fn)
        per-sku    (:per-sku result)
        totals     (:totals result)]

    (testing "cap-by-cost = unit-cost-basis × quantity-full for the covered SKU (AC-1)"
      (let [s (first (filter #(= "3452/Бежевый" (:article %)) per-sku))]
        (is (= 120 (:stock-qty s)) "quantity-full = 100 FBO + 20 in-transit")
        (is (= 590.00 (:unit-cost-basis s)))
        (is (= 70800.00 (:cap-by-cost s)) "590 × 120")
        (is (= (math/round2 (* (:unit-cost-basis s) (:stock-qty s)))
               (:cap-by-cost s)))))

    (testing "missing cost-price → cap-by-cost nil, never 0 (FR-013)"
      (let [s (first (filter #(= "9981/Чёрный" (:article %)) per-sku))]
        (is (nil? (:unit-cost-basis s)))
        (is (nil? (:cap-by-cost s)) "N/A rendered '—', never 0")
        (is (= 40 (:stock-qty s)))))

    (testing "VR-c1/SC-003: Σ cap-by-cost (non-nil) == :cap-by-cost-total to the kopeck"
      (is (= (:cap-by-cost-total totals)
             (math/round2 (reduce + 0.0 (keep :cap-by-cost per-sku)))))
      (is (= (fx :expected :cap-by-cost-total) (:cap-by-cost-total totals))))

    (testing "FR-014: :stock-qty-total == (:total-full (stock/totals stocks))"
      (is (= (:total-full (stock/totals stocks)) (:stock-qty-total totals)))
      (is (= (fx :expected :stock-qty-total) (:stock-qty-total totals))))

    (testing "transparency totals: :na-cost-count / :sku-count"
      (is (= (fx :expected :na-cost-count) (:na-cost-count totals)))
      (is (= (fx :expected :sku-count) (:sku-count totals))))))

;; ---------------------------------------------------------------------------
;; T017 — coverage-aware GMROI (VR-g1/VR-g2 / SC-004 / FR-009/FR-010/FR-013)
;; ---------------------------------------------------------------------------

(deftest t017-gmroi-coverage-aware
  (let [f          @fixture
        basis      (get (:cost-prices f) "3452/Бежевый")
        history    (:stocks-history f)   ; 22 rows, one qty=0 (2026-05-15)
        net-profit (get (:net-profit f) "3452/Бежевый")]

    (testing "daily-cap-series covers only days with collected (non-zero) stock"
      (let [g (stock/gmroi-inputs {:article "3452/Бежевый"
                                   :unit-cost-basis basis
                                   :net-profit net-profit
                                   :history history
                                   :days-in-period 31})]
        ;; 22 rows present, one is a zero-stock day → 21 covered days
        (is (= 21 (:covered-days g)))
        (is (= 21 (count (:daily-cap-series g))))
        (is (every? #(= 70800.00 %) (:daily-cap-series g)) "590 × 120 each covered day")
        (is (= 70800.00 (:avg-inventory g)))))

    (testing "VR-g2/FR-009: numerator is net-profit, NOT textbook gross-margin/avg-inventory"
      (let [g (stock/gmroi-inputs {:article "3452/Бежевый"
                                   :unit-cost-basis basis
                                   :net-profit net-profit
                                   :history history
                                   :days-in-period 31})
            expected (math/round2 (/ net-profit (:avg-inventory g)))]
        (is (= expected (:gmroi g)) "gmroi = net-profit ÷ avg-inventory")
        ;; a textbook substitution (gross-margin / avg-inventory) would NOT equal this,
        ;; because the numerator here is explicitly the MP net-profit, not a margin.
        (is (= net-profit 24500.00) "numerator sourced from net-profit field, not a margin")))

    (testing "VR-g1/SC-004: toggling ONE zero-stock day changes BOTH covered-days AND series length"
      (let [g-real (stock/gmroi-inputs {:article "3452/Бежевый"
                                        :unit-cost-basis basis
                                        :net-profit net-profit
                                        :history history
                                        :days-in-period 31})
            ;; toggle: force the 2026-05-15 zero-stock day to carry collected stock
            toggled (mapv (fn [r]
                            (if (= "2026-05-15" (:snapshot-date r))
                              (assoc r :quantity 120)
                              r))
                          history)
            g-toggled (stock/gmroi-inputs {:article "3452/Бежевый"
                                           :unit-cost-basis basis
                                           :net-profit net-profit
                                           :history toggled
                                           :days-in-period 31})]
        (is (= 21 (:covered-days g-real)))
        (is (= 22 (:covered-days g-toggled)) "denominator changed")
        (is (= 21 (count (:daily-cap-series g-real))))
        (is (= 22 (count (:daily-cap-series g-toggled))) "numerator basis (series) length changed")
        (is (not= (:covered-days g-real) (:covered-days g-toggled)))))

    (testing "FR-013: all-uncovered SKU → :gmroi :not-applicable (NOT 0/∞)"
      (let [g (stock/gmroi-inputs {:article "9981/Чёрный"
                                   :unit-cost-basis nil
                                   :net-profit nil
                                   :history []          ; no collected history
                                   :days-in-period 31})]
        (is (= 0 (:covered-days g)))
        (is (nil? (:avg-inventory g)))
        (is (= :not-applicable (:gmroi g)) "never 0 or infinite")
        (is (= :not-applicable (:gmroi-annualized g)))))

    (testing "FR-013: nil cost-basis → not-applicable even with collected history"
      (let [g (stock/gmroi-inputs {:article "9981/Чёрный"
                                   :unit-cost-basis nil
                                   :net-profit 1000.0
                                   :history [{:snapshot-date "2026-05-10" :article "9981/Чёрный"
                                              :warehouse "FBO-Тула" :quantity 40}]
                                   :days-in-period 31})]
        (is (= :not-applicable (:gmroi g)))))))

;; ---------------------------------------------------------------------------
;; T018 — turnover from Σqty + annualized GMROI (FR-012 / FR-011)
;; ---------------------------------------------------------------------------

(deftest t018-turnover-from-sum-qty
  (testing "FR-012/R4: turnover uses Σ(sale-quantity), NOT (count items)"
    (let [f          @fixture
          stock-ba   [{:article "3452/Бежевый" :quantity-full 120}]
          ;; one sale EVENT of 5 units
          sales-data (:sales f)
          days       31
          enriched   (stock/with-turnover stock-ba sales-data days)
          row        (first (filter #(= "3452/Бежевый" (:article %)) enriched))]
      (is (= 5 (:sold-period row)) "Σqty = 5, NOT 1 event")
      ;; daily-rate = 5/31 (round2); had it counted events it would be 1/31
      (is (= (math/round2 (/ 5.0 days)) (:daily-rate row)))
      (is (not= (math/round2 (/ 1.0 days)) (:daily-rate row))
          "count-of-events would be wrong")
      ;; days-of-cover = quantity-full / daily-rate = 120 / (5/31)
      (is (= (math/round2 (/ 120.0 (/ 5.0 days))) (:days-left row))))))

(deftest t018-gmroi-annualized
  (testing "FR-011: gmroi-annualized == period-gmroi × (365 / days-in-period)"
    (let [f          @fixture
          basis      (get (:cost-prices f) "3452/Бежевый")
          history    (:stocks-history f)
          net-profit (get (:net-profit f) "3452/Бежевый")
          g          (stock/gmroi-inputs {:article "3452/Бежевый"
                                          :unit-cost-basis basis
                                          :net-profit net-profit
                                          :history history
                                          :days-in-period 31})
          expected   (math/round2 (* (:gmroi g) (/ 365.0 31)))]
      (is (= expected (:gmroi-annualized g)))
      (is (> (:gmroi-annualized g) (:gmroi g)) "annualized > period for <365-day window"))))
