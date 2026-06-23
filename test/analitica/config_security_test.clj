(ns analitica.config-security-test
  (:require [clojure.test :refer [deftest is]]
            [analitica.config :as config]))

(deftest api-key-getter-reads-config
  (with-redefs [config/config (constantly {:api-key "secret-123"})]
    (is (= "secret-123" (config/api-key))))
  (with-redefs [config/config (constantly {})]
    (is (nil? (config/api-key)))))

(deftest cors-origins-defaults-when-unset
  (with-redefs [config/config (constantly {})]
    (is (= ["https://marker.shegida.ru" "http://localhost:3000"] (config/cors-origins))))
  (with-redefs [config/config (constantly {:cors-origins ["https://x.test"]})]
    (is (= ["https://x.test"] (config/cors-origins)))))
