(ns analitica.web.settings-routes-test
  (:require [clojure.test :refer [deftest is]]
            [analitica.web.server :as server]
            [analitica.web.api.settings :as settings-api]))

(deftest get-settings-route-wired
  (with-redefs [settings-api/get-settings (fn [_] {:status 200 :body {:settings {}}})]
    (let [resp ((server/app) {:request-method :get :uri "/api/v1/settings"})]
      (is (= 200 (:status resp))))))

(deftest put-marketplace-route-passes-path-mp
  (let [seen (atom nil)]
    (with-redefs [settings-api/put-marketplace
                  (fn [req] (reset! seen (get-in req [:body-params :marketplace]))
                            {:status 200 :body {:ok true}})]
      (let [resp ((server/app) {:request-method :put
                                :uri "/api/v1/settings/marketplace/wb"
                                :body-params {:api-token "x"}})]
        (is (= 200 (:status resp)))
        (is (= "wb" @seen))))))
