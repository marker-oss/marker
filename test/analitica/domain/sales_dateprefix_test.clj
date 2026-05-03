(ns analitica.domain.sales-dateprefix-test
  "Bug #2: sales/parse-date-str crashed on date strings shorter than
   10 chars. Tests pin the guarded behaviour."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.sales :as sales]))

(deftest parse-date-str-handles-full-iso
  (is (= "2026-05-02" (#'sales/parse-date-str "2026-05-02"))))

(deftest parse-date-str-trims-iso-with-time
  (is (= "2026-05-02" (#'sales/parse-date-str "2026-05-02T13:45:00"))))

(deftest parse-date-str-returns-nil-for-short-strings
  (testing "Strings shorter than 10 chars must not throw"
    (is (nil? (#'sales/parse-date-str "2026")))
    (is (nil? (#'sales/parse-date-str "2026-05")))
    (is (nil? (#'sales/parse-date-str "")))))

(deftest parse-date-str-returns-nil-for-nil
  (is (nil? (#'sales/parse-date-str nil))))
