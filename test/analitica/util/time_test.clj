(ns analitica.util.time-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.util.time :as t])
  (:import [java.time LocalDate]))

(deftest test-minus-days
  (testing "minus-days shifts a date string backward by N days"
    (is (= "2026-02-01" (t/minus-days "2026-04-02" 60)))
    (is (= "2026-04-01" (t/minus-days "2026-04-02" 1)))
    (is (= "2026-03-31" (t/minus-days "2026-04-01" 1))
        "month boundary handled by LocalDate arithmetic"))
  (testing "non-positive N is allowed"
    (is (= "2026-04-02" (t/minus-days "2026-04-02" 0)))
    (is (= "2026-04-03" (t/minus-days "2026-04-02" -1))
        "negative shift moves the date forward")))

(deftest test-parse-period-predefined
  (testing "Predefined period strings"
    (testing "last-week"
      (let [[from to] (t/parse-period "last-week")]
        (is (string? from) "from should be a string")
        (is (string? to) "to should be a string")
        (is (re-matches #"\d{4}-\d{2}-\d{2}" from) "from should match date format")
        (is (re-matches #"\d{4}-\d{2}-\d{2}" to) "to should match date format")
        ;; Last week should be exactly 7 days (Monday to Sunday)
        (is (= 7 (inc (.between java.time.temporal.ChronoUnit/DAYS
                                (LocalDate/parse from)
                                (LocalDate/parse to))))
            "Should span exactly 7 days (Monday to Sunday)")
        ;; Verify it's Monday
        (is (= 1 (.getValue (.getDayOfWeek (LocalDate/parse from))))
            "Should start on Monday")
        ;; Verify it's Sunday
        (is (= 7 (.getValue (.getDayOfWeek (LocalDate/parse to))))
            "Should end on Sunday")))
    
    (testing "last-7-days"
      (let [[from to] (t/parse-period "last-7-days")]
        (is (string? from))
        (is (string? to))
        ;; last-7-days means "7 days ago to today" which is 8 days inclusive
        (is (= 8 (inc (.between java.time.temporal.ChronoUnit/DAYS
                                (LocalDate/parse from)
                                (LocalDate/parse to))))
            "Should span 8 days inclusive (7 days ago to today)")))
    
    (testing "last-30-days"
      (let [[from to] (t/parse-period "last-30-days")]
        (is (string? from))
        (is (string? to))
        ;; last-30-days means "30 days ago to today" which is 31 days inclusive
        (is (= 31 (inc (.between java.time.temporal.ChronoUnit/DAYS
                                 (LocalDate/parse from)
                                 (LocalDate/parse to))))
            "Should span 31 days inclusive (30 days ago to today)")))
    
    (testing "this-month"
      (let [[from to] (t/parse-period "this-month")]
        (is (string? from))
        (is (string? to))
        (is (re-matches #"\d{4}-\d{2}-01" from) "from should be first day of month")))))

(deftest test-parse-period-custom-range
  (testing "Custom date range format"
    (testing "Valid date range"
      (let [[from to] (t/parse-period "2026-04-01,2026-04-30")]
        (is (= "2026-04-01" from))
        (is (= "2026-04-30" to))))
    
    (testing "Single day range"
      (let [[from to] (t/parse-period "2026-04-15,2026-04-15")]
        (is (= "2026-04-15" from))
        (is (= "2026-04-15" to))))))

(deftest test-parse-period-invalid-input
  (testing "Invalid input handling"
    (testing "nil input"
      (is (thrown? Exception (t/parse-period nil))
          "Should throw on nil"))
    
    (testing "Unknown period string"
      (is (thrown? Exception (t/parse-period "unknown-period"))
          "Should throw on unknown period"))
    
    (testing "Invalid date format"
      (is (thrown? Exception (t/parse-period "2026-13-01,2026-13-31"))
          "Should throw on invalid month"))
    
    (testing "Malformed custom range"
      (is (thrown? Exception (t/parse-period "2026-04-01"))
          "Should throw on missing comma"))
    
    (testing "Wrong date format"
      (is (thrown? Exception (t/parse-period "04/01/2026,04/30/2026"))
          "Should throw on wrong date format"))))

(deftest test-parse-period-edge-cases
  (testing "Edge cases"
    (testing "Leap year date"
      (let [[from to] (t/parse-period "2024-02-29,2024-02-29")]
        (is (= "2024-02-29" from))
        (is (= "2024-02-29" to))))
    
    (testing "Year boundary"
      (let [[from to] (t/parse-period "2025-12-31,2026-01-01")]
        (is (= "2025-12-31" from))
        (is (= "2026-01-01" to))))))
