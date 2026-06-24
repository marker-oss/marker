(ns analitica.web.middleware.error-test
  (:require [clojure.test :refer [deftest is]]
            [analitica.web.middleware.error :as error]
            [clojure.string :as str]
            [jsonista.core :as json]))

(deftest passes-through-when-no-throw
  (let [h (error/wrap-exception (fn [_] {:status 200 :body "ok"}))]
    (is (= 200 (:status (h {:request-method :get :uri "/x"}))))))

(deftest throw-becomes-500-with-trace-id-no-stacktrace
  (let [h (error/wrap-exception (fn [_] (throw (RuntimeException. "kaboom-secret-detail"))))
        r (h {:request-method :post :uri "/api/x"})]
    (is (= 500 (:status r)))
    ;; body is an already-serialized JSON STRING (this mw is outside wrap-json-response)
    (is (string? (:body r)))
    (let [parsed (json/read-value (:body r) json/keyword-keys-object-mapper)]
      (is (= "internal" (:error parsed)))
      (is (string? (:trace_id parsed)))
      (is (seq (:trace_id parsed)))
      ;; exact key-set: nothing else (no :detail / :stacktrace) can ever leak
      (is (= #{:error :trace_id} (set (keys parsed)))))
    ;; the exception message / stacktrace must NOT leak into the body
    (is (not (str/includes? (:body r) "kaboom-secret-detail")))))
