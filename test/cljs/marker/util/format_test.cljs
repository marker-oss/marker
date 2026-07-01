(ns marker.util.format-test
  "Unit tests for marker.util.format — pure function coverage.
   Run via: shadow-cljs compile test  (target :node-test, autorun true)"
  (:require [cljs.test :refer [deftest is testing run-tests]]
            [marker.util.format :as fmt]
            [marker.ui.metric-hint :as mh]))

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

(deftest format-int-negatives
  (testing "regression: -500 must not insert NBSP between sign and digits"
    (is (= "-500" (fmt/format-int -500))))
  (testing "negative with thousands separator"
    (is (= (str "-1 234") (fmt/format-int -1234))))
  (testing "NaN returns em-dash"
    (is (= "—" (fmt/format-int js/NaN)))))

;; ---- format-date ----

(deftest format-date-basic
  (testing "formats date as DD.MM.YYYY"
    (is (= "03.05.2026" (fmt/format-date (js/Date. 2026 4 3))))))

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

;; ---- format-days ----

(deftest format-days-basic
  (testing "day count with Дн. suffix"
    (is (= (str "12 Дн.") (fmt/format-days 12))))
  (testing "thousands separated"
    (is (= (str "1 000 Дн.") (fmt/format-days 1000))))
  (testing "nil returns em-dash"
    (is (= "—" (fmt/format-days nil))))
  (testing "NaN returns em-dash"
    (is (= "—" (fmt/format-days js/NaN)))))

;; ---- format-suffixed ----

(deftest format-suffixed-suffixes
  (testing ":rub"
    (is (= (str "1 234 ₽") (fmt/format-suffixed 1234 :rub))))
  (testing ":pct"
    (is (= "12,3%" (fmt/format-suffixed 12.3 :pct))))
  (testing ":qty"
    (is (= (str "42 шт") (fmt/format-suffixed 42 :qty))))
  (testing ":days"
    (is (= (str "7 Дн.") (fmt/format-suffixed 7 :days))))
  (testing ":mul"
    (is (= "1,5×" (fmt/format-suffixed 1.5 :mul))))
  (testing ":none → plain int"
    (is (= (str "1 000") (fmt/format-suffixed 1000 :none))))
  (testing "nil suffix → plain int"
    (is (= (str "1 000") (fmt/format-suffixed 1000 nil))))
  (testing "unknown suffix → plain int"
    (is (= (str "1 000") (fmt/format-suffixed 1000 :bananas)))))

(deftest format-suffixed-nil-value
  (testing "nil value ALWAYS → em-dash regardless of suffix"
    (is (= "—" (fmt/format-suffixed nil :rub)))
    (is (= "—" (fmt/format-suffixed nil :pct)))
    (is (= "—" (fmt/format-suffixed nil :qty)))
    (is (= "—" (fmt/format-suffixed nil :days)))
    (is (= "—" (fmt/format-suffixed nil :mul)))
    (is (= "—" (fmt/format-suffixed nil :none)))
    (is (= "—" (fmt/format-suffixed nil nil)))))

;; ---- format-decimal-str / format-decimal-rub ----

(deftest format-decimal-str-precision
  (testing "negative decimal string → grouped unicode-minus, fraction dropped"
    (is (= (str "−742 000") (fmt/format-decimal-str "-742000.00"))))
  (testing "positive decimal string grouped"
    (is (= (str "1 234 567") (fmt/format-decimal-str "1234567.99"))))
  (testing "no fractional part"
    (is (= (str "500") (fmt/format-decimal-str "500"))))
  (testing "explicit plus sign stripped"
    (is (= (str "1 000") (fmt/format-decimal-str "+1000.00"))))
  (testing "precision beyond js double is preserved in integer grouping"
    (is (= (str "9 007 199 254 740 993")
           (fmt/format-decimal-str "9007199254740993.00"))))
  (testing "nil / blank → em-dash"
    (is (= "—" (fmt/format-decimal-str nil)))
    (is (= "—" (fmt/format-decimal-str "")))))

(deftest format-decimal-rub-basic
  (testing "appends ruble unit"
    (is (= (str "−742 000 ₽") (fmt/format-decimal-rub "-742000.00"))))
  (testing "positive with unit"
    (is (= (str "1 000 ₽") (fmt/format-decimal-rub "1000.00"))))
  (testing "nil / blank → em-dash"
    (is (= "—" (fmt/format-decimal-rub nil)))
    (is (= "—" (fmt/format-decimal-rub "")))))

;; ---- delta-class (marker.ui.metric-hint) truth table ----

(deftest delta-class-positive-if-grow
  (testing "revenue-like: growth good"
    (is (= "up"   (mh/delta-class 10 true)))
    (is (= "down" (mh/delta-class -10 true)))))

(deftest delta-class-cost-like
  (testing "cost-like: growth bad → inverted colours"
    (is (= "down" (mh/delta-class 10 false)))
    (is (= "up"   (mh/delta-class -10 false)))))

(deftest delta-class-flat
  (testing "nil delta → flat"
    (is (= "flat" (mh/delta-class nil true)))
    (is (= "flat" (mh/delta-class nil false))))
  (testing "zero delta → flat"
    (is (= "flat" (mh/delta-class 0 true)))
    (is (= "flat" (mh/delta-class 0 false))))
  (testing "within neutral band → flat"
    (is (= "flat" (mh/delta-class 0.01 true)))
    (is (= "flat" (mh/delta-class -0.01 false))))
  (testing "NaN → flat"
    (is (= "flat" (mh/delta-class js/NaN true)))))
