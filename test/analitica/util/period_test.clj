(ns analitica.util.period-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.util.period :as p]))

(deftest resolve-preset-test
  (testing "30-days returns [today-29, today]"
    (let [today (java.time.LocalDate/parse "2026-04-30")
          [from to] (p/resolve-preset :last-30-days today)]
      (is (= "2026-04-01" (str from)))
      (is (= "2026-04-30" (str to)))))

  (testing "last-7-days returns [today-6, today]"
    (let [today (java.time.LocalDate/parse "2026-04-30")
          [from to] (p/resolve-preset :last-7-days today)]
      (is (= "2026-04-24" (str from)))
      (is (= "2026-04-30" (str to)))))

  (testing "this-month returns [first-of-month, today]"
    (let [[from to] (p/resolve-preset :this-month (java.time.LocalDate/parse "2026-04-15"))]
      (is (= "2026-04-01" (str from)))
      (is (= "2026-04-15" (str to)))))

  (testing "prev-month returns full previous month"
    (let [[from to] (p/resolve-preset :prev-month (java.time.LocalDate/parse "2026-04-15"))]
      (is (= "2026-03-01" (str from)))
      (is (= "2026-03-31" (str to)))))

  (testing "custom returns nil (requires explicit dates)"
    (is (nil? (p/resolve-preset :custom (java.time.LocalDate/parse "2026-04-15"))))))

(deftest compare-period-test
  (testing "same-length prior — 30 days"
    (let [[from to] (p/compare-period {:from "2026-04-01" :to "2026-04-30"})]
      (is (= "2026-03-02" from))
      (is (= "2026-03-31" to))))

  (testing "compare for 1 day"
    (let [[from to] (p/compare-period {:from "2026-04-30" :to "2026-04-30"})]
      (is (= "2026-04-29" from))
      (is (= "2026-04-29" to))))

  (testing "compare for 7 days — spans month boundary"
    (let [[from to] (p/compare-period {:from "2026-05-01" :to "2026-05-07"})]
      (is (= "2026-04-24" from))
      (is (= "2026-04-30" to)))))

(deftest parse-url-state-test
  (testing "extracts from/to/compare from query params"
    (let [s (p/parse-url-state {"from" "2026-04-01" "to" "2026-04-30" "compare" "prev"})]
      (is (= "2026-04-01" (:from s)))
      (is (= "2026-04-30" (:to s)))
      (is (= :prev (:compare s)))))

  (testing "compare=none (default)"
    (let [s (p/parse-url-state {"from" "2026-04-01" "to" "2026-04-30"})]
      (is (= :none (:compare s)))))

  (testing "keyword params also supported"
    (let [s (p/parse-url-state {:from "2026-04-01" :to "2026-04-30" :compare "prev"})]
      (is (= :prev (:compare s))))))

(deftest days-between-test
  (testing "inclusive count"
    (is (= 1 (p/days-between "2026-04-30" "2026-04-30")))
    (is (= 30 (p/days-between "2026-04-01" "2026-04-30")))
    (is (= 31 (p/days-between "2026-03-01" "2026-03-31")))))

(deftest default-state-test
  (testing "default state is last-30-days, no compare"
    (let [today (java.time.LocalDate/parse "2026-04-30")
          s (p/default-state today)]
      (is (= "2026-04-01" (:from s)))
      (is (= "2026-04-30" (:to s)))
      (is (= :last-30-days (:preset s)))
      (is (= :none (:compare s)))
      (is (= "all" (:marketplace s))))))

(deftest resolve-preset-includes-last-week
  (testing "last-week = previous complete Mon..Sun (matches legacy time/parse-period)"
    (let [today (p/parse-date "2026-06-25")            ; Thursday
          [f t] (p/resolve-preset :last-week today)]
      (is (= "2026-06-15" (p/format-date f)))           ; prev Monday
      (is (= "2026-06-21" (p/format-date t))))))
