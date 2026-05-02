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
