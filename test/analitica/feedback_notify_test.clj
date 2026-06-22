(ns analitica.feedback-notify-test
  (:require [clojure.test :refer [deftest is]]
            [analitica.feedback.notify :as notify]
            [analitica.settings :as settings]
            [hato.client :as hc]))

(deftest not-configured-is-noop
  (with-redefs [settings/overrides (constantly {})]
    (let [r (notify/notify! {:id 1 :kind "bug" :message "x" :attachments []})]
      (is (false? (:sent? r))))))

(deftest configured-sends-and-never-throws
  (let [calls (atom [])]
    (with-redefs [settings/overrides (constantly {"notify.telegram.bot-token" "T"
                                                  "notify.telegram.chat-id" "42"})
                  hc/post (fn [url opts] (swap! calls conj url) {:status 200 :body "{}"})]
      (let [r (notify/notify! {:id 1 :kind "bug" :message "broken" :page-url "/app/pulse" :attachments []})]
        (is (true? (:sent? r)))
        (is (some #(re-find #"sendMessage" %) @calls))))))

(deftest http-error-is-swallowed
  (with-redefs [settings/overrides (constantly {"notify.telegram.bot-token" "T"
                                                "notify.telegram.chat-id" "42"})
                hc/post (fn [_ _] (throw (ex-info "network down" {})))]
    (let [r (notify/notify! {:id 1 :kind "bug" :message "x" :attachments []})]
      (is (false? (:sent? r))))))
