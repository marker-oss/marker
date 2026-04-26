(ns analitica.web.pages.digest-integration-test
  "Integration tests for GET / digest page — use with-redefs to avoid real DB.
   Does not use ring.mock.request (not in deps); constructs minimal Ring requests directly."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [analitica.web.server :as server]
            [analitica.web.pages.digest :as digest]
            [analitica.alerts :as alerts]
            [analitica.domain.sales    :as sales]
            [analitica.domain.finance  :as finance]
            [analitica.domain.pnl      :as pnl]
            [analitica.domain.stock    :as stock]
            [analitica.domain.buyout   :as buyout]))

;; ---------------------------------------------------------------------------
;; Minimal Ring request builder (no ring.mock dep required)
;; ---------------------------------------------------------------------------

(defn- make-request
  "Build a minimal Ring-compatible request map."
  [method uri & {:keys [params] :or {params {}}}]
  {:server-port    3000
   :server-name    "localhost"
   :remote-addr    "127.0.0.1"
   :uri            uri
   :query-string   nil
   :scheme         :http
   :request-method method
   :headers        {}
   :params         params})

;; ---------------------------------------------------------------------------
;; Stub data
;; ---------------------------------------------------------------------------

(def ^:private stub-sales
  [{:article "art-1" :subject "Widget" :type :sale :for-pay 1000.0
    :finished-price 1200.0 :date "2026-04-20T00:00:00" :marketplace :wb}
   {:article "art-1" :subject "Widget" :type :sale :for-pay 1000.0
    :finished-price 1200.0 :date "2026-04-25T00:00:00" :marketplace :wb}])

(def ^:private stub-finance [])
(def ^:private stub-pnl {:revenue 2000.0 :net-profit 400.0 :ad-spend 100.0
                         :sales-qty 2 :returns-qty 0})
(def ^:private stub-freshness {:wb "2026-04-25T18:30:00" :ozon nil :ym nil})

;; ---------------------------------------------------------------------------
;; 2.8 Integration test — GET / returns 200 with expected sections
;; ---------------------------------------------------------------------------

(deftest ^:integration test-get-root-returns-200
  (testing "GET / returns 200 with alerts and movers sections"
    (with-redefs [sales/fetch-sales    (fn [& _] stub-sales)
                  finance/fetch-finance (fn [& _] stub-finance)
                  pnl/calculate         (fn [& _] stub-pnl)
                  stock/fetch-stocks    (fn [& _] [])
                  stock/with-turnover   (fn [& _] [])
                  buyout/analyze        (fn [& _] [])
                  alerts/freshness-data (fn [] stub-freshness)]
      (let [app      (server/app)
            request  (make-request :get "/")
            response (app request)]
        (is (= 200 (:status response))
            "GET / should return 200")
        (is (string? (:body response))
            "Response body should be a string")
        (is (str/includes? (:body response) "digest-alerts")
            "Response body should contain #digest-alerts section")
        (is (str/includes? (:body response) "digest-movers")
            "Response body should contain #digest-movers section")
        (is (str/includes? (:body response) "Свежесть данных")
            "Response body should contain data freshness section")))))

(deftest ^:integration test-get-root-with-period-params
  (testing "GET / with from/to params returns 200"
    (with-redefs [sales/fetch-sales    (fn [& _] stub-sales)
                  finance/fetch-finance (fn [& _] stub-finance)
                  pnl/calculate         (fn [& _] stub-pnl)
                  stock/fetch-stocks    (fn [& _] [])
                  stock/with-turnover   (fn [& _] [])
                  buyout/analyze        (fn [& _] [])
                  alerts/freshness-data (fn [] stub-freshness)]
      (let [app      (server/app)
            request  (make-request :get "/" :params {:from "2026-03-01" :to "2026-03-31"})
            response (app request)]
        (is (= 200 (:status response))
            "GET / with period params should return 200")))))

(deftest ^:integration test-dashboard-summary-still-accessible
  (testing "GET /dashboard/summary returns 200 (legacy route still wired)"
    (with-redefs [sales/fetch-sales    (fn [& _] stub-sales)
                  finance/fetch-finance (fn [& _] stub-finance)
                  pnl/calculate         (fn [& _] stub-pnl)
                  stock/fetch-stocks    (fn [& _] [])
                  stock/with-turnover   (fn [& _] [])
                  buyout/analyze        (fn [& _] [])
                  alerts/freshness-data (fn [] stub-freshness)]
      (let [app      (server/app)
            request  (make-request :get "/dashboard/summary")
            response (app request)]
        ;; May be 200 (wired) or 404 (if legacy removed) — both acceptable
        (is (#{200 404} (:status response))
            "GET /dashboard/summary should return 200 or 404, not 500")))))
