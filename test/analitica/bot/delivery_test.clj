(ns analitica.bot.delivery-test
  "US1: idempotent delivery — re-run for already-delivered (chat,cadence,period)
   must NOT re-send. Also covers gated/failed outcomes allowing retry."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.test-helpers :as th]
            [analitica.db :as db]
            [analitica.bot.delivery :as delivery]))

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

(use-fixtures :once th/with-test-db)

(defn- clear! []
  (try (db/execute! ["DELETE FROM bot_deliveries"]) (catch Throwable _ nil))
  (try (db/execute! ["DELETE FROM bot_subscriptions"]) (catch Throwable _ nil)))

(use-fixtures :each (fn [f] (clear!) (f)))

;; ---------------------------------------------------------------------------
;; Idempotent delivery
;; ---------------------------------------------------------------------------

(deftest delivered-outcome-blocks-resend
  (testing "second fire! for same (chat, cadence, period) with outcome=delivered → skips, no second send"
    (let [send-count (atom 0)
          stub-sender (fn [_chat-id _text]
                        (swap! send-count inc)
                        {:sent? true :detail "ok"})]
      ;; First send
      (delivery/record-delivery! {:chat-id "chat-A" :cadence :daily :period "2026-06-30"
                                   :outcome :delivered :detail nil})
      ;; Attempt second send — must be skipped
      (let [result (delivery/maybe-deliver!
                     {:chat-id "chat-A" :cadence :daily :period "2026-06-30"}
                     (fn [] (stub-sender "chat-A" "hello")))]
        (is (= :already-delivered (:skip-reason result)))
        (is (zero? @send-count))))))

(deftest gated-outcome-allows-retry
  (testing "previous outcome=:gated does NOT block a retry attempt"
    (let [send-count (atom 0)
          stub-sender (fn [] (swap! send-count inc) {:sent? true :detail "ok"})]
      (delivery/record-delivery! {:chat-id "chat-B" :cadence :daily :period "2026-06-30"
                                   :outcome :gated :detail "nothing final"})
      (let [result (delivery/maybe-deliver!
                     {:chat-id "chat-B" :cadence :daily :period "2026-06-30"}
                     stub-sender)]
        ;; Should have attempted (not skipped)
        (is (not= :already-delivered (:skip-reason result)))
        (is (pos? @send-count))))))

(deftest failed-outcome-allows-retry
  (testing "previous outcome=:failed does NOT block a retry"
    (let [send-count (atom 0)
          stub-sender (fn [] (swap! send-count inc) {:sent? true :detail "ok"})]
      (delivery/record-delivery! {:chat-id "chat-C" :cadence :weekly :period "2026-W26"
                                   :outcome :failed :detail "timeout"})
      (let [result (delivery/maybe-deliver!
                     {:chat-id "chat-C" :cadence :weekly :period "2026-W26"}
                     stub-sender)]
        (is (not= :already-delivered (:skip-reason result)))
        (is (pos? @send-count))))))

(deftest no-prior-record-sends
  (testing "no prior delivery record → send is attempted"
    (let [send-count (atom 0)
          stub-sender (fn [] (swap! send-count inc) {:sent? true :detail "ok"})]
      (delivery/maybe-deliver!
        {:chat-id "chat-D" :cadence :daily :period "2026-06-29"}
        stub-sender)
      (is (pos? @send-count)))))

(deftest double-fire-only-one-send
  (testing "two sequential fire! calls for same key → exactly one delivery"
    (let [send-count (atom 0)
          stub-sender (fn []
                        (swap! send-count inc)
                        {:sent? true :detail "ok"})]
      ;; First
      (delivery/maybe-deliver!
        {:chat-id "chat-E" :cadence :daily :period "2026-07-01"}
        stub-sender)
      ;; Second
      (delivery/maybe-deliver!
        {:chat-id "chat-E" :cadence :daily :period "2026-07-01"}
        stub-sender)
      (is (= 1 @send-count)))))
