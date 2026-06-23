(ns analitica.web.spa-shell-test
  (:require [clojure.test :refer [deftest is]]
            [analitica.web.server :as server]
            [analitica.config :as config]))

;; NOTE: hiccup renders attributes in alphabetical order, so a meta declared as
;; [:meta {:name "api-key" :content ...}] serializes as
;; <meta content="..." name="api-key" />. The regexes below assert presence of
;; the api-key meta with the correct content, order-independent (the cljs client
;; reads it via DOM querySelector, which is attribute-order agnostic).
(deftest shell-injects-api-key-meta
  (with-redefs [config/api-key (constantly "shell-key-9")]
    (let [body (:body (#'server/marker-spa-shell))]
      (is (re-find #"<meta content=\"shell-key-9\" name=\"api-key\"" body)))))

(deftest shell-meta-empty-when-unconfigured
  (with-redefs [config/api-key (constantly nil)]
    (let [body (:body (#'server/marker-spa-shell))]
      (is (re-find #"<meta content=\"\" name=\"api-key\"" body)))))
