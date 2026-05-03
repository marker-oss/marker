(ns marker.pages.sync.schedule-test
  "Tests for pure helpers in marker.pages.sync.schedule."
  (:require [cljs.test :refer [deftest is testing]]
            [marker.pages.sync.schedule :refer [parse-schedule-payload
                                                schedule-form->body
                                                validate-schedule-form]]))

;; ---------------------------------------------------------------------------
;; parse-schedule-payload
;; ---------------------------------------------------------------------------

(deftest parse-schedule-payload-nil
  (testing "nil body returns nil (form merges defaults itself)"
    (is (nil? (parse-schedule-payload nil)))))

(deftest parse-schedule-payload-full-body
  (testing "full snake_case body is normalised"
    (let [r (parse-schedule-payload {:enabled true :hour 3 :minute 30
                                     :what "finance" :marketplace "wb"
                                     :period "last-30-days"
                                     :next_run_at "2026-05-04T03:30:00Z"})]
      (is (true? (:enabled r)))
      (is (= 3 (:hour r)))
      (is (= 30 (:minute r)))
      (is (= "finance" (:what r)))
      (is (= "wb" (:marketplace r)))
      (is (= "last-30-days" (:period r)))
      (is (= "2026-05-04T03:30:00Z" (:next-run-at r)))))
  (testing "missing next_run_at produces nil :next-run-at"
    (let [r (parse-schedule-payload {:enabled false :hour 6 :minute 0
                                     :what "all" :marketplace "all"
                                     :period "last-7-days"})]
      (is (nil? (:next-run-at r)))))
  (testing "empty map {} returns all keys set to defaults"
    (let [r (parse-schedule-payload {})]
      (is (false? (:enabled r)))
      (is (= 6 (:hour r)))
      (is (= 0 (:minute r)))
      (is (= "all" (:what r)))
      (is (= "all" (:marketplace r)))
      (is (= "last-7-days" (:period r))))))

;; ---------------------------------------------------------------------------
;; schedule-form->body
;; ---------------------------------------------------------------------------

(deftest schedule-form->body-strips-extras
  (testing "only expected keys are forwarded"
    (let [body (schedule-form->body {:enabled true :hour 6 :minute 0
                                     :what "all" :marketplace "all"
                                     :period "last-7-days"
                                     :next-run-at "2026-05-04T06:00:00Z"
                                     :extra-junk 42})]
      (is (= #{:enabled :hour :minute :what :marketplace :period}
             (set (keys body))))
      (is (true? (:enabled body)))
      (is (= 6 (:hour body)))
      (is (nil? (:next-run-at body)))
      (is (nil? (:extra-junk body))))))

;; ---------------------------------------------------------------------------
;; validate-schedule-form
;; ---------------------------------------------------------------------------

(def ^:private valid-form
  {:enabled true :hour 6 :minute 0 :what "all" :marketplace "all" :period "last-7-days"})

(deftest validate-schedule-form-valid
  (testing "valid form returns nil"
    (is (nil? (validate-schedule-form valid-form))))
  (testing "hour 0 is valid boundary"
    (is (nil? (validate-schedule-form (assoc valid-form :hour 0)))))
  (testing "hour 23 is valid boundary"
    (is (nil? (validate-schedule-form (assoc valid-form :hour 23)))))
  (testing "minute 0 is valid boundary"
    (is (nil? (validate-schedule-form (assoc valid-form :minute 0)))))
  (testing "minute 59 is valid boundary"
    (is (nil? (validate-schedule-form (assoc valid-form :minute 59))))))

(deftest validate-schedule-form-invalid-hour
  (testing "hour 24 is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :hour 24)))))
  (testing "hour -1 is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :hour -1)))))
  (testing "nil hour is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :hour nil)))))
  (testing "string hour is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :hour "6"))))))

(deftest validate-schedule-form-invalid-minute
  (testing "minute 60 is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :minute 60)))))
  (testing "minute -1 is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :minute -1)))))
  (testing "nil minute is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :minute nil))))))

(deftest validate-schedule-form-invalid-what
  (testing "unknown what value is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :what "unknown")))))
  (testing "nil what is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :what nil)))))
  (testing "all known what values are valid"
    (doseq [v ["all" "sales" "orders" "finance" "storage" "stocks"
               "stats" "prices" "regions" "cashflow"]]
      (is (nil? (validate-schedule-form (assoc valid-form :what v)))
          (str "expected nil for what=" v)))))

(deftest validate-schedule-form-invalid-marketplace
  (testing "unknown marketplace is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :marketplace "amazon")))))
  (testing "nil marketplace is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :marketplace nil)))))
  (testing "all valid marketplaces pass"
    (doseq [v ["all" "wb" "ozon" "ym"]]
      (is (nil? (validate-schedule-form (assoc valid-form :marketplace v)))
          (str "expected nil for marketplace=" v)))))

(deftest validate-schedule-form-invalid-period
  (testing "unknown period is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :period "last-14-days")))))
  (testing "nil period is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :period nil)))))
  (testing "all valid periods pass"
    (doseq [v ["last-week" "last-7-days" "last-30-days" "this-month"]]
      (is (nil? (validate-schedule-form (assoc valid-form :period v)))
          (str "expected nil for period=" v)))))
