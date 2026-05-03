(ns analitica.web.pages.digest-recent-sale-test
  "Bug #2: digest collected `last-3-sales` via an inline anonymous filter
   that called `(subs d 0 10)` after only `(seq d)` — short dates would
   crash the entire homepage. Tests pin a named, length-guarded helper."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.pages.digest :as digest]))

(defn- recent? [row threshold]
  (#'digest/recent-sale? row threshold))

(deftest accepts-rows-on-or-after-threshold
  (is (true?  (recent? {:date "2026-05-02"} "2026-05-01")))
  (is (true?  (recent? {:date "2026-05-01"} "2026-05-01")))
  (is (false? (recent? {:date "2026-04-30"} "2026-05-01"))))

(deftest accepts-iso-with-time-suffix
  (is (true? (recent? {:date "2026-05-02T13:45:00"} "2026-05-01"))))

(deftest falls-back-to-event-date
  (is (true? (recent? {:event-date "2026-05-02"} "2026-05-01"))))

(deftest rejects-rows-with-short-date-without-throwing
  (testing "Date strings shorter than YYYY-MM-DD are excluded, not crash"
    (is (false? (recent? {:date "2026"}    "2026-05-01")))
    (is (false? (recent? {:date "2026-05"} "2026-05-01")))
    (is (false? (recent? {:date ""}        "2026-05-01")))
    (is (false? (recent? {:date nil}       "2026-05-01")))
    (is (false? (recent? {}                "2026-05-01")))))
