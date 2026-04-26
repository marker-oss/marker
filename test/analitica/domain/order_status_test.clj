(ns analitica.domain.order-status-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.order-status :as os]))

(deftest cancelled-mapping
  (testing "all per-MP cancelled variants land in :cancelled"
    (is (= :cancelled (os/canonicalize "cancelled")))
    (is (= :cancelled (os/canonicalize "CANCELLED_BEFORE_PROCESSING")))
    (is (= :cancelled (os/canonicalize "CANCELLED_IN_PROCESSING")))
    (is (= :cancelled (os/canonicalize "CANCELLED_IN_DELIVERY")))))

(deftest delivered-mapping
  (testing "Ozon and YM delivery success states map to :delivered"
    (is (= :delivered (os/canonicalize "delivered")))
    (is (= :delivered (os/canonicalize "DELIVERED")))
    (is (= :delivered (os/canonicalize "PARTIALLY_DELIVERED")))
    (is (= :delivered (os/canonicalize "PICKUP")))))

(deftest returned-mapping
  (testing "YM RETURNED is its own bucket — distinct from :cancelled"
    (is (= :returned (os/canonicalize "RETURNED")))))

(deftest in-flight-mapping
  (testing "everything not classified is :in-flight"
    (is (= :in-flight (os/canonicalize "active"))                ":wb-active")
    (is (= :in-flight (os/canonicalize "delivering"))            ":ozon-delivering")
    (is (= :in-flight (os/canonicalize "awaiting_packaging"))    ":ozon-awaiting")
    (is (= :in-flight (os/canonicalize "PROCESSING"))            ":ym-processing")
    (is (= :in-flight (os/canonicalize "DELIVERY"))              ":ym-delivery")
    (is (= :in-flight (os/canonicalize "unknown_future_status"))
        "unknown statuses default to :in-flight rather than throwing")
    (is (= :in-flight (os/canonicalize nil))
        "nil status (data quality issue) falls through to :in-flight")))

(deftest case-sensitive
  (testing "canonicalizer is case-sensitive — WB lower-case differs from YM upper-case"
    (is (= :cancelled (os/canonicalize "cancelled")))
    (is (= :in-flight (os/canonicalize "CANCELLED"))
        "Bare 'CANCELLED' is not a real per-MP value — must be an exact match")))
