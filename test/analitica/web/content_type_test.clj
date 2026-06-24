(ns analitica.web.content-type-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [analitica.web.server :as server]
            [analitica.config :as config]))

;; (server/app) reads config (wrap-api-key → config/api-key; CORS →
;; config/cors-origins), which throw "Config not loaded" in this unloaded test
;; process. Pin both getters so the handler builds (same fixture as healthz_test).
(use-fixtures :each
  (fn [f]
    (with-redefs [config/api-key      (constantly nil)
                  config/cors-origins (constantly ["http://localhost:3000"])]
      (f))))

;; tokens.css is served from resources/public/css/tokens.css (a real asset).
(deftest css-asset-has-content-type
  (let [resp ((server/app) {:request-method :get :uri "/css/tokens.css" :headers {}})]
    (is (= 200 (:status resp)))
    (is (re-find #"text/css" (get-in resp [:headers "Content-Type"])))))
