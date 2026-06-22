(ns analitica.web.settings-routes-test
  (:require [clojure.test :refer [deftest is]]
            [analitica.web.server :as server]
            [analitica.web.api.settings :as settings-api]
            [analitica.web.middleware.transit :as transit-mw])
  (:import (java.io ByteArrayInputStream)))

(deftest get-settings-route-wired
  (with-redefs [settings-api/get-settings (fn [_] {:status 200 :body {:settings {}}})]
    (let [resp ((server/app) {:request-method :get :uri "/api/v1/settings"})]
      (is (= 200 (:status resp))))))

(deftest put-marketplace-route-passes-path-mp
  ;; C1 fix: route now merges :mp into :body (not :body-params)
  (let [seen (atom nil)]
    (with-redefs [settings-api/put-marketplace
                  (fn [req] (reset! seen (get-in req [:body :marketplace]))
                            {:status 200 :body {:ok true}})]
      (let [resp ((server/app) {:request-method :put
                                :uri "/api/v1/settings/marketplace/wb"
                                :body {:api-token "x"}})]
        (is (= 200 (:status resp)))
        (is (= "wb" @seen))))))

(deftest put-marketplace-transit-body-survives-middleware
  ;; C1 integration test: sends a real transit-encoded body through the full
  ;; middleware stack (wrap-transit-body decodes → route merges :mp → handler
  ;; sees both :marketplace and :api-token in (:body req)).
  (let [captured (atom nil)
        payload  {:api-token "test-token-123"}
        encoded  (transit-mw/encode-transit-json payload)
        body-is  (ByteArrayInputStream. (.getBytes encoded "UTF-8"))]
    (with-redefs [settings-api/put-marketplace
                  (fn [req] (reset! captured (:body req))
                            {:status 200 :body {:ok true}})]
      ((server/app) {:request-method :put
                     :uri            "/api/v1/settings/marketplace/wb"
                     :headers        {"content-type" "application/transit+json"}
                     :body           body-is})
      ;; After middleware: :body is the decoded map with :mp merged in
      (is (map? @captured)
          "handler must receive a map body (not a raw InputStream)")
      (is (= "wb" (:marketplace @captured))
          "path :mp must be merged into :body by the route")
      (is (= "test-token-123" (:api-token @captured))
          "transit-encoded field must survive decode → route → handler"))))
