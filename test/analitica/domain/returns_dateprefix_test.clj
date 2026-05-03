(ns analitica.domain.returns-dateprefix-test
  "Bug #9: returns/parse-date-str is a carbon-copy of the sales bug we
   already fixed — `(subs s 0 10)` crashes on date strings shorter than
   10 chars. by-day groups by this; one short date in the input bombs
   the whole returns report."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.returns :as returns]))

(deftest parse-date-str-handles-full-iso
  (is (= "2026-05-02" (#'returns/parse-date-str "2026-05-02"))))

(deftest parse-date-str-trims-iso-with-time
  (is (= "2026-05-02" (#'returns/parse-date-str "2026-05-02T13:45:00"))))

(deftest parse-date-str-returns-nil-for-short-strings
  (is (nil? (#'returns/parse-date-str "2026")))
  (is (nil? (#'returns/parse-date-str "2026-05")))
  (is (nil? (#'returns/parse-date-str ""))))

(deftest parse-date-str-returns-nil-for-nil
  (is (nil? (#'returns/parse-date-str nil))))

(deftest by-day-survives-short-dates
  (testing "by-day must not throw when an input row has a malformed date"
    (let [rows [{:type :return :date "2026-05-02"}
                {:type :return :date "2026"}
                {:type :return :date nil}
                {:type :return :date "2026-05-02T11:00:00"}]
          out (returns/by-day rows)]
      (is (sequential? out))
      (is (some #(= "2026-05-02" (:date %)) out)
          "valid dates still aggregate"))))
