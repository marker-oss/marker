(ns analitica.web.healthz-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [analitica.web.server :as server]
            [analitica.config :as config]
            [analitica.db :as db]))

;; (server/app) reads config (wrap-api-key → config/api-key; CORS →
;; config/cors-origins), which throw "Config not loaded" in this unloaded test
;; process. Pin both getters so the handler builds (same fixture as server_test).
(use-fixtures :each
  (fn [f]
    (with-redefs [config/api-key      (constantly nil)
                  config/cors-origins (constantly ["http://localhost:3000"])]
      (f))))

(deftest healthz-ok-when-db-up
  ;; db/query uses rs/as-unqualified-kebab-maps, so "SELECT 1 AS one" → :one (NOT :1)
  (with-redefs [db/query (fn [_] [{:one 1}])]
    (let [r (#'server/healthz-response)]
      (is (= 200 (:status r)))
      (is (true? (get-in r [:body :db-ok?]))))))

(deftest healthz-reports-db-down-without-throwing
  (with-redefs [db/query (fn [_] (throw (ex-info "db down" {})))]
    (let [r (#'server/healthz-response)]
      (is (= 200 (:status r)))
      (is (false? (get-in r [:body :db-ok?]))))))

(deftest healthz-wired-through-app
  (let [resp ((server/app) {:request-method :get :uri "/healthz" :headers {}})]
    (is (= 200 (:status resp)))
    ;; body is JSON-serialized by wrap-json-response → a string containing the keys
    (is (re-find #"\"status\"" (str (:body resp))))))
