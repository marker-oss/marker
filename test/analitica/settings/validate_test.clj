(ns analitica.settings.validate-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.settings.validate :as validate]
            [analitica.marketplace.protocol :as proto]))

(deftest valid-when-probe-succeeds
  (with-redefs [proto/fetch-stocks (fn [_] [])]   ; authed call returns, no throw
    (let [r (validate/validate-credentials :wb {:api-token "good-token"})]
      (is (true? (:valid? r))))))

(deftest invalid-on-unauthorized
  (with-redefs [proto/fetch-stocks (fn [_] (throw (ex-info "Unauthorized" {:type :unauthorized})))]
    (let [r (validate/validate-credentials :wb {:api-token "bad-token"})]
      (is (false? (:valid? r)))
      (is (re-find #"(?i)токен|401|unauth" (:detail r))))))

(deftest invalid-on-missing-required-field
  ;; wb/make-client throws when :api-token is nil — must be caught → invalid, not crash
  (let [r (validate/validate-credentials :wb {})]
    (is (false? (:valid? r)))))

(deftest other-error-is-not-valid-but-does-not-throw
  (with-redefs [proto/fetch-stocks (fn [_] (throw (ex-info "HTTP 500" {:status 500})))]
    (let [r (validate/validate-credentials :ozon {:client-id "x" :api-key "y"})]
      (is (false? (:valid? r)))
      (is (string? (:detail r))))))
