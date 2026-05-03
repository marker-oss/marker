(ns analitica.web.pages.plan-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.web.pages.plan :as plan-page]
            [analitica.domain.plan :as plan]
            analitica.test-helpers))

(use-fixtures :once analitica.test-helpers/with-test-db)

(defn- ring-mock-get [params]
  {:request-method :get :uri "/plan" :params params})

(defn- ring-mock-post [params form-params]
  {:request-method :post :uri "/plan"
   :params params :form-params form-params})

(deftest get-plan-renders-current-month-when-no-param
  (let [resp (plan-page/get-handler (ring-mock-get {}))]
    (is (= 200 (:status resp)))
    (is (re-find #"<form" (:body resp)))
    (is (re-find #"name=\"period_month\"" (:body resp)))))

(deftest get-plan-renders-specified-month
  (plan/clear-month! "2026-06")
  (plan/save-plan! {:period-month "2026-06" :marketplace "wb"
                    :metric "revenue" :target-value 123456.0})
  (let [resp (plan-page/get-handler (ring-mock-get {:period_month "2026-06"}))]
    (is (= 200 (:status resp)))
    (is (re-find #"123 ?456" (:body resp))
        "rendered target value visible (with thousand separator)")))

(deftest post-plan-saves-non-empty-cells
  (plan/clear-month! "2026-07")
  (let [resp (plan-page/post-handler
               (ring-mock-post {}
                 {"period_month"   "2026-07"
                  "wb__revenue"    "450000"
                  "ozon__revenue"  ""
                  "wb__orders"     "2500"}))]
    (is (= 303 (:status resp)) "redirect after POST")
    (let [rows (plan/fetch-plans "2026-07")]
      (is (= 2 (count rows)))
      (is (= 450000.0 (->> rows
                           (filter #(and (= (:marketplace %) "wb")
                                         (= (:metric %) "revenue")))
                           first :target-value))))))

(deftest post-plan-empty-cell-deletes-existing
  (plan/clear-month! "2026-07")
  (plan/save-plan! {:period-month "2026-07" :marketplace "wb"
                    :metric "revenue" :target-value 100.0})
  (plan-page/post-handler
    (ring-mock-post {} {"period_month" "2026-07" "wb__revenue" ""}))
  (is (zero? (count (plan/fetch-plans "2026-07")))))

(deftest post-plan-rejects-invalid
  (let [resp (plan-page/post-handler
               (ring-mock-post {}
                 {"period_month" "2026-07" "wb__revenue" "not-a-number"}))]
    (is (= 400 (:status resp)))
    (is (re-find #"target_value" (:body resp)))))

;; ---------------------------------------------------------------------------
;; Bug #4: period-month <select> must include past months and the
;; currently-rendered period-month even if it falls outside the default
;; ±N-month window. Otherwise the dropdown silently drops the URL param.
;; ---------------------------------------------------------------------------

(deftest month-options-window-includes-past-and-future
  (testing "Default window is sorted yyyy-MM strings spanning past and future months"
    (let [opts (#'plan-page/month-options nil)]
      (is (vector? opts))
      (is (every? #(re-matches #"\d{4}-(0[1-9]|1[0-2])" %) opts))
      (is (= opts (sort opts)) "options are ascending")
      (is (some #(re-matches #"\d{4}-\d{2}" %) opts)))))

(deftest month-options-includes-explicit-selected-when-outside-window
  (testing "Even an old period like 2020-01 must appear so the <select> can highlight it"
    (let [opts (#'plan-page/month-options "2020-01")]
      (is (some #{"2020-01"} opts)))))

(deftest month-options-no-duplicate-when-selected-already-in-window
  (testing "Selected period inside the default window must not be duplicated"
    (let [opts (#'plan-page/month-options
                 (-> (java.time.YearMonth/now) str))]
      (is (= (count opts) (count (distinct opts)))))))

(deftest get-plan-renders-past-period-as-selected-option
  (testing "Bug #4: GET /plan?period_month=<past> shows that month as <option selected>"
    (let [resp (plan-page/get-handler
                 (ring-mock-get {:period_month "2020-01"}))]
      (is (= 200 (:status resp)))
      (is (re-find #"<option[^>]*selected[^>]*value=\"2020-01\""
                   (:body resp))
          "past period appears in dropdown AND is the selected option"))))
