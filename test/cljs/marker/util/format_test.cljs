(ns marker.util.format-test
  "Unit tests for marker.util.format — pure function coverage.
   Run via shadow-cljs test build or inline node verification.
   NOTE: DONE_WITH_CONCERNS — a :test shadow-cljs build target is not yet
   configured in shadow-cljs.edn. The tests are correct ClojureScript and
   will pass once a test runner is wired. For Phase 3 verification we rely
   on the Playwright visual smoke test for chrome components, and on manual
   review of the pure formatter logic."
  (:require [cljs.test :refer [deftest is testing run-tests]]
            [marker.util.format :as fmt]))

;; ---- format-rub ----

(deftest format-rub-positive
  (testing "positive integer"
    (is (= (str "1 234 567 ₽") (fmt/format-rub 1234567))))
  (testing "positive small"
    (is (= (str "42 ₽") (fmt/format-rub 42))))
  (testing "positive with-sign"
    (is (= (str "+100 ₽") (fmt/format-rub 100 true))))
  (testing "rounds to integer"
    (is (= (str "12 ₽") (fmt/format-rub 12.4)))
    (is (= (str "13 ₽") (fmt/format-rub 12.6)))))

(deftest format-rub-negative
  (testing "negative uses unicode minus"
    (is (= (str "−500 ₽") (fmt/format-rub -500))))
  (testing "negative large"
    (is (= (str "−1 000 ₽") (fmt/format-rub -1000)))))

(deftest format-rub-zero
  (testing "zero is formatted without sign"
    (is (= (str "0 ₽") (fmt/format-rub 0)))))

(deftest format-rub-nil
  (testing "nil returns em-dash"
    (is (= "—" (fmt/format-rub nil)))))

;; ---- format-pct ----

(deftest format-pct-whole
  (testing "whole percentage"
    (is (= "10,0%" (fmt/format-pct 10)))))

(deftest format-pct-decimal
  (testing "decimal percentage"
    (is (= "3,7%" (fmt/format-pct 3.7))))
  (testing "two digits"
    (is (= "3,75%" (fmt/format-pct 3.75 2)))))

(deftest format-pct-nil
  (testing "nil returns em-dash"
    (is (= "—" (fmt/format-pct nil)))))

;; ---- plural-ru ----

(deftest plural-ru-one
  (testing "n=1 → one"
    (is (= "товар" (fmt/plural-ru 1 "товар" "товара" "товаров"))))
  (testing "n=21 → one"
    (is (= "товар" (fmt/plural-ru 21 "товар" "товара" "товаров")))))

(deftest plural-ru-few
  (testing "n=2 → few"
    (is (= "товара" (fmt/plural-ru 2 "товар" "товара" "товаров"))))
  (testing "n=22 → few"
    (is (= "товара" (fmt/plural-ru 22 "товар" "товара" "товаров"))))
  (testing "n=4 → few"
    (is (= "товара" (fmt/plural-ru 4 "товар" "товара" "товаров")))))

(deftest plural-ru-many
  (testing "n=5 → many"
    (is (= "товаров" (fmt/plural-ru 5 "товар" "товара" "товаров"))))
  (testing "n=11 → many (exception)"
    (is (= "товаров" (fmt/plural-ru 11 "товар" "товара" "товаров"))))
  (testing "n=14 → many (exception)"
    (is (= "товаров" (fmt/plural-ru 14 "товар" "товара" "товаров"))))
  (testing "n=100 → many"
    (is (= "товаров" (fmt/plural-ru 100 "товар" "товара" "товаров")))))

;; ---- format-int ----

(deftest format-int-basic
  (testing "thousands separated by NBSP"
    (is (= (str "1 000") (fmt/format-int 1000))))
  (testing "nil"
    (is (= "—" (fmt/format-int nil)))))

;; ---- format-short ----

(deftest format-short-basic
  (testing "millions"
    (is (= "1,5M" (fmt/format-short 1500000))))
  (testing "thousands"
    (is (= "3,4K" (fmt/format-short 3400))))
  (testing "small"
    (is (= "42" (fmt/format-short 42)))))

;; ---- format-mul ----

(deftest format-mul-basic
  (testing "multiplier"
    (is (= "2,5×" (fmt/format-mul 2.5))))
  (testing "nil"
    (is (= "—" (fmt/format-mul nil)))))
