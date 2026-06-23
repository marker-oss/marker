(ns analitica.web.middleware.body-limit-test
  (:require [clojure.test :refer [deftest is]]
            [analitica.web.middleware.body-limit :as bl]
            [jsonista.core :as json]))

(defn- ok [_] {:status 200 :body "ok"})

(deftest oversize-json-413
  (let [h (bl/wrap-content-length-limit ok)
        resp (h {:request-method :post :uri "/api/x"
                 :headers {"content-length" (str (inc bl/json-max-bytes))}})]
    (is (= 413 (:status resp)))
    ;; F2: body MUST be a pre-serialized JSON string (not a raw map) because this
    ;; middleware sits OUTSIDE wrap-json-response — a map body would throw at the
    ;; Jetty adapter (no StreamableResponseBody for IPersistentMap).
    (is (string? (:body resp)))                                 ; F2: regression guard
    (is (= "application/json" (get-in resp [:headers "Content-Type"])))
    (is (= {:error "payload too large"}
           (json/read-value (:body resp) json/keyword-keys-object-mapper)))))

(deftest within-json-limit-passes
  (let [h (bl/wrap-content-length-limit ok)]
    (is (= 200 (:status (h {:request-method :post :uri "/api/x"
                            :headers {"content-length" "1000"}}))))))

(deftest multipart-allows-up-to-50mb
  (let [h (bl/wrap-content-length-limit ok)]
    (is (= 200 (:status (h {:request-method :post :uri "/api/x"
                            :headers {"content-type" "multipart/form-data; boundary=x"
                                      "content-length" (str (* 40 1024 1024))}}))))
    (is (= 413 (:status (h {:request-method :post :uri "/api/x"
                            :headers {"content-type" "multipart/form-data; boundary=x"
                                      "content-length" (str (inc bl/multipart-max-bytes))}}))))))

(deftest no-content-length-passes
  (let [h (bl/wrap-content-length-limit ok)]
    (is (= 200 (:status (h {:request-method :post :uri "/api/x" :headers {}}))))))

(deftest malformed-content-length-fails-open
  ;; A garbage Content-Length must not throw; it passes through (the Jetty hard
  ;; cap is the real backstop — see C2). This pins the intended fail-open.
  (let [h (bl/wrap-content-length-limit ok)]
    (is (= 200 (:status (h {:request-method :post :uri "/api/x"
                            :headers {"content-length" "not-a-number"}}))))))
