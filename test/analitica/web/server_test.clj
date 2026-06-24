(ns analitica.web.server-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.web.server :as server]
            [analitica.config :as config]
            [analitica.util.time :as time]))

;; (server/app) now reads config (wrap-api-key → config/api-key; CORS →
;; config/cors-origins), which throw "Config not loaded" in this unloaded test
;; process (M4: surface load errors, don't swallow to nil). Pin both getters so
;; the handler builds; the root-route 302 assertion in test-routes is a
;; pre-existing baseline failure unrelated to this work.
(use-fixtures :each
  (fn [f]
    (with-redefs [config/api-key      (constantly nil)
                  config/cors-origins (constantly ["http://localhost:3000"])]
      (f))))

(deftest test-app-function
  (testing "app function returns a Ring handler"
    (let [handler (server/app)]
      (is (fn? handler) "app should return a function"))))

(deftest test-port-parsing
  (testing "Port argument parsing"
    (let [parse-port (fn [args]
                       (let [port-arg (some #(when (.startsWith % "--port=")
                                               (subs % 7))
                                            args)]
                         (if port-arg
                           (Integer/parseInt port-arg)
                           3000)))]
      (is (= 3000 (parse-port [])) "Default port should be 3000")
      (is (= 4000 (parse-port ["--port=4000"])) "Should parse --port=4000")
      (is (= 8080 (parse-port ["--port=8080"])) "Should parse --port=8080"))))

(deftest test-routes
  (testing "Basic route responses"
    (let [handler (server/app)]
      (testing "Root route"
        (let [response (handler {:request-method :get :uri "/"})]
          (is (= 200 (:status response)) "Root should return 200")))
      
      (testing "API metrics route"
        (let [response (handler {:request-method :get :uri "/api/metrics"})]
          (is (= 200 (:status response)) "API metrics should return 200")))
      
      (testing "404 for unknown route"
        (let [response (handler {:request-method :get :uri "/unknown"})]
          (is (= 404 (:status response)) "Unknown route should return 404"))))))

(deftest test-parse-period-integration
  (testing "parse-period utility integration"
    (testing "Can parse predefined periods"
      (let [[from to] (time/parse-period "last-week")]
        (is (string? from))
        (is (string? to))))

    (testing "Can parse custom date ranges"
      (let [[from to] (time/parse-period "2026-04-01,2026-04-30")]
        (is (= "2026-04-01" from))
        (is (= "2026-04-30" to))))))

;; ---------------------------------------------------------------------------
;; resolve-period-from-params tests (Task 7.3 — URL state plumbing)
;; ---------------------------------------------------------------------------

(deftest resolve-period-from-params-test
  (testing "?from and ?to both present and valid → {:from :to} map"
    (is (= {:from "2026-03-01" :to "2026-04-01"}
           (#'server/resolve-period-from-params {:from "2026-03-01" :to "2026-04-01"}))))

  (testing "?from and ?to both present but malformed → nil (400)"
    (is (nil? (#'server/resolve-period-from-params {:from "not-a-date" :to "2026-04-01"})))
    (is (nil? (#'server/resolve-period-from-params {:from "2026-04-01" :to "bad"})))
    (is (nil? (#'server/resolve-period-from-params {:from "26-04-01" :to "2026-04-01"}))))

  (testing "?from only (no ?to) → nil (partial range is invalid)"
    (is (nil? (#'server/resolve-period-from-params {:from "2026-04-01"}))))

  (testing "?to only (no ?from) → nil (partial range is invalid)"
    (is (nil? (#'server/resolve-period-from-params {:to "2026-04-01"}))))

  (testing "valid ?period string → {:from :to} map (all named periods resolved via parse-period)"
    (is (map? (#'server/resolve-period-from-params {:period "last-30-days"})))
    (is (map? (#'server/resolve-period-from-params {:period "last-week"})))
    (is (map? (#'server/resolve-period-from-params {:period "last-7-days"})))
    (is (map? (#'server/resolve-period-from-params {:period "this-month"})))
    ;; all results have :from and :to string keys
    (let [r (#'server/resolve-period-from-params {:period "last-7-days"})]
      (is (string? (:from r)))
      (is (string? (:to r)))))

  (testing "invalid ?period → nil (caller should 400)"
    (is (nil? (#'server/resolve-period-from-params {:period "bogus-period"}))))

  (testing "?period as date-range string → {:from :to} map"
    (is (= {:from "2026-04-01" :to "2026-04-30"}
           (#'server/resolve-period-from-params {:period "2026-04-01,2026-04-30"}))))

  (testing "no params → {:from :to} map for last-30-days default"
    (let [r (#'server/resolve-period-from-params {})]
      (is (map? r))
      (is (string? (:from r)))
      (is (string? (:to r)))))

  (testing "?from and ?to take priority over ?period when all present"
    (is (= {:from "2026-03-01" :to "2026-04-01"}
           (#'server/resolve-period-from-params {:from "2026-03-01" :to "2026-04-01"
                                                  :period "last-week"})))))
