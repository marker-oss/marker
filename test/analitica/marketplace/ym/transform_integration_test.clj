(ns analitica.marketplace.ym.transform-integration-test
  "Integration test: exercise `->finance-from-order-stats` on the captured
   fixture `fixtures/ym-orders-2026-03.edn` (100 real YM orders, all statuses),
   loaded from the classpath so the test is runnable from any cwd.

   Validates US1 FR-003/FR-004/FR-007/FR-015 — status distribution, post-delivery
   return edge, and FinanceRow schema compliance."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [analitica.marketplace.ym.transform :as transform]
            [analitica.domain.finance-row :as frow]))

(def ^:private fixture-resource
  "fixtures/ym-orders-2026-03.edn")

(defn- load-fixture []
  (-> (io/resource fixture-resource) slurp edn/read-string :orders))

(deftest fixture-transforms-to-at-least-100-rows
  (let [orders (load-fixture)
        rows   (transform/->finance-from-order-stats orders)]
    (is (>= (count orders) 100)
        "fixture advertises 100 orders")
    (is (>= (count rows) 100)
        "each order contributes >= 1 finance-row (items)")))

(deftest fixture-covers-all-three-operation-values
  (let [rows         (transform/->finance-from-order-stats (load-fixture))
        distribution (frequencies (map :operation rows))]
    (testing "operations distribution is consistent with data-model §4 live data"
      (is (>= (get distribution "return" 0) 1)
          "at least one return row (RETURNED orders + post-delivery edge)")
      (is (>= (get distribution "sale" 0) 5)
          "at least 5 sale rows from DELIVERED / PARTIALLY_DELIVERED")
      (is (>= (get distribution "cancelled" 0) 50)
          "at least 50 cancelled rows from CANCELLED_* orders"))))

(deftest fixture-contains-post-delivery-return-edge
  (testing "at least one DELIVERED order has an item → return (itemStatus RETURNED)"
    (let [orders     (load-fixture)
          delivered  (filter #(= "DELIVERED" (:status %)) orders)
          edge-cases (for [order delivered
                           :let [rows (transform/->finance-from-order-stats [order])]
                           :when (some #(= "return" (:operation %)) rows)]
                       (:id order))]
      (is (>= (count edge-cases) 1)
          (str "fixture should include at least 1 post-delivery return case; "
               "edge-case order IDs found: " (vec edge-cases))))))

(deftest every-fixture-row-passes-finance-row-validation
  (let [rows (transform/->finance-from-order-stats (load-fixture))
        {:keys [ok bad]} (frow/validate-rows rows)]
    (is (empty? bad)
        (str "all fixture-derived rows must satisfy FinanceRow; bad rows:\n"
             (frow/summarize-bad bad)))
    (is (= (count rows) (count ok)))))

;; ---------------------------------------------------------------------------
;; Ad-cost reconciliation — SUM(:ad-cost) ≈ SUM(AUCTION_PROMOTION commission).
;;
;; Historical note: this test used to compare against SUM(bidFee). That was
;; wrong — bidFee is the seller's bid CAP (max they'd agree to pay), while
;; the real ad-auction charge is `commissions[AUCTION_PROMOTION].actual`
;; (Vickrey second-price clearing). On live data bidFee outstrips
;; AUCTION_PROMOTION ~350×. Corrected 2026-04-24.
;; ---------------------------------------------------------------------------

(deftest ad-cost-sum-matches-fixture-auction-promotion
  (let [orders          (load-fixture)
        rows            (transform/->finance-from-order-stats orders)
        actual-ad-cost  (double (reduce + 0 (keep :ad-cost rows)))
        expected-ap     (double
                          (reduce + 0
                            (for [o orders
                                  c (:commissions o)
                                  :when (= "AUCTION_PROMOTION" (:type c))]
                              (or (:actual c) 0))))]
    (is (< (Math/abs (- actual-ad-cost expected-ap)) 1.0)
        (format "SUM :ad-cost (%.2f) != SUM AUCTION_PROMOTION (%.2f), delta %.2f"
                actual-ad-cost expected-ap (- actual-ad-cost expected-ap)))))
