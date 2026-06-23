(ns analitica.web.middleware.auth-test
  (:require [clojure.test :refer [deftest is]]
            [analitica.web.middleware.auth :as auth]
            [analitica.config :as config]
            [jsonista.core :as json]))

(defn- ok-handler [_] {:status 200 :body "ok"})

(deftest mutating-without-key-is-401
  (with-redefs [config/api-key (constantly "K")]
    (let [h (auth/wrap-api-key ok-handler)]
      ;; design's verbatim acceptance route + the SPA routes
      (let [resp (h {:request-method :post :uri "/api/sync/start" :headers {}})]
        (is (= 401 (:status resp)))
        ;; F2: body MUST be a pre-serialized JSON string (not a raw map) because
        ;; this middleware sits OUTSIDE wrap-json-response — a map body would
        ;; throw at the Jetty adapter (no StreamableResponseBody for IPersistentMap).
        (is (string? (:body resp)))                                 ; F2: regression guard
        (is (= "application/json" (get-in resp [:headers "Content-Type"])))
        (is (= {:error "unauthorized"}
               (json/read-value (:body resp) json/keyword-keys-object-mapper))))  ; M3: logical body shape
      (is (= 401 (:status (h {:request-method :post :uri "/api/v1/feedback" :headers {}}))))
      (is (= 401 (:status (h {:request-method :put  :uri "/api/v1/settings/marketplace/wb"
                              :headers {"x-api-key" "WRONG"}}))))
      (is (= 401 (:status (h {:request-method :delete :uri "/api/v1/feedback"  ; I4: DELETE gated
                              :headers {}})))))))

(deftest mutating-with-key-passes
  (with-redefs [config/api-key (constantly "K")]
    (let [h (auth/wrap-api-key ok-handler)]
      (is (= 200 (:status (h {:request-method :post   :uri "/api/sync/start"
                              :headers {"x-api-key" "K"}}))))
      (is (= 200 (:status (h {:request-method :post   :uri "/api/v1/feedback"
                              :headers {"x-api-key" "K"}}))))
      (is (= 200 (:status (h {:request-method :delete :uri "/api/v1/feedback"  ; I4: DELETE + key → 200
                              :headers {"x-api-key" "K"}})))))))

(deftest percent-encoded-api-path-still-gated
  ;; M1: if Jetty ever delivers an un-decoded :uri, the prefix check must not be bypassed.
  ;; If this fails, normalize/decode :uri before the starts-with? check in mutating?.
  (with-redefs [config/api-key (constantly "K")]
    (let [h (auth/wrap-api-key ok-handler)]
      (is (= 401 (:status (h {:request-method :post :uri "/%61pi/v1/feedback" :headers {}})))))))

(deftest get-and-options-and-non-api-exempt
  (with-redefs [config/api-key (constantly "K")]
    (let [h (auth/wrap-api-key ok-handler)]
      (is (= 200 (:status (h {:request-method :get     :uri "/api/v1/marker/pnl" :headers {}}))))
      (is (= 200 (:status (h {:request-method :options :uri "/api/v1/feedback"   :headers {}}))))
      (is (= 200 (:status (h {:request-method :post    :uri "/app"               :headers {}})))))))

(deftest no-key-configured-fails-open
  (with-redefs [config/api-key (constantly nil)]
    (let [h (auth/wrap-api-key ok-handler)]
      (is (= 200 (:status (h {:request-method :post :uri "/api/v1/feedback" :headers {}})))))))

(deftest constant-time-eq
  (is (auth/constant-time-eq? "abc" "abc"))
  (is (not (auth/constant-time-eq? "abc" "abd")))
  (is (not (auth/constant-time-eq? "abc" "ab"))))
