(ns analitica.bot.subscription-test
  "US1: subscription registry CRUD round-trip + metric-slug validation (US2).
   Tests are DB-backed (test-analitica.db via test-helpers fixture).
   No network calls — Telegram sender stubbed throughout."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.test-helpers :as th]
            [analitica.db :as db]
            [analitica.bot.registry :as registry]
            [analitica.bot.subscription :as sub]))

;; ---------------------------------------------------------------------------
;; Fixture — fresh DB per namespace run
;; ---------------------------------------------------------------------------

(use-fixtures :once th/with-test-db)

(defn- clear-bot-tables! []
  (try (db/execute! ["DELETE FROM bot_deliveries"]) (catch Throwable _ nil))
  (try (db/execute! ["DELETE FROM bot_subscriptions"]) (catch Throwable _ nil)))

(use-fixtures :each (fn [f] (clear-bot-tables!) (f)))

;; ---------------------------------------------------------------------------
;; US1 — subscription round-trip
;; ---------------------------------------------------------------------------

(deftest subscribe-then-get-round-trip
  (testing "save a subscription then retrieve it by chat-id"
    (let [params {:chat-id "chat-111"
                  :cadences #{:daily}
                  :metrics [:revenue :net-profit]
                  :show-movers? true
                  :marketplace :all
                  :gate-when-empty :skip}
          saved  (registry/save-subscription! params)
          found  (registry/get-subscription "chat-111")]
      (is (= "chat-111" (:chat-id saved)))
      (is (= #{:daily} (:cadences saved)))
      (is (= [:revenue :net-profit] (:metrics saved)))
      (is (true? (:show-movers? saved)))
      (is (= :all (:marketplace saved)))
      (is (= :skip (:gate-when-empty saved)))
      (is (= :active (:status saved)))
      (is (some? (:id saved)))
      ;; retrieved row must equal saved row on key fields
      (is (= (:chat-id saved) (:chat-id found)))
      (is (= (:metrics saved) (:metrics found)))
      (is (= (:cadences saved) (:cadences found))))))

(deftest repeat-subscribe-is-upsert-not-duplicate
  (testing "subscribing the same chat-id twice updates, does not create a second row"
    (registry/save-subscription! {:chat-id "chat-upsert"
                                   :cadences #{:daily}
                                   :metrics [:revenue]
                                   :show-movers? false
                                   :marketplace :wb
                                   :gate-when-empty :skip})
    (registry/save-subscription! {:chat-id "chat-upsert"
                                   :cadences #{:daily :weekly}
                                   :metrics [:revenue :net-profit]
                                   :show-movers? true
                                   :marketplace :all
                                   :gate-when-empty :notice})
    (let [all (registry/list-subscriptions)]
      (is (= 1 (count (filter #(= "chat-upsert" (:chat-id %)) all))))
      (let [sub (registry/get-subscription "chat-upsert")]
        (is (= #{:daily :weekly} (:cadences sub)))
        (is (= [:revenue :net-profit] (:metrics sub)))
        (is (= :notice (:gate-when-empty sub)))))))

(deftest delete-subscription
  (testing "unsubscribe removes the row"
    (registry/save-subscription! {:chat-id "chat-del"
                                   :cadences #{:daily}
                                   :metrics []
                                   :show-movers? false
                                   :marketplace :all
                                   :gate-when-empty :skip})
    (is (some? (registry/get-subscription "chat-del")))
    (registry/delete-subscription! "chat-del")
    (is (nil? (registry/get-subscription "chat-del")))))

(deftest list-returns-only-active
  (testing "list-subscriptions returns :active subscriptions"
    (registry/save-subscription! {:chat-id "chat-active"
                                   :cadences #{:daily}
                                   :metrics [:revenue]
                                   :show-movers? false
                                   :marketplace :all
                                   :gate-when-empty :skip})
    ;; Insert a dormant row directly
    (db/execute!
      ["INSERT INTO bot_subscriptions
          (chat_id, cadences, metrics, show_movers, marketplace, gate_when_empty, status, created_at, updated_at)
         VALUES (?, 'daily', '', 1, 'all', 'skip', 'dormant', datetime('now'), datetime('now'))"
       "chat-dormant"])
    (let [active (registry/list-subscriptions)]
      (is (every? #(= :active (:status %)) active))
      (is (some #(= "chat-active" (:chat-id %)) active))
      (is (not-any? #(= "chat-dormant" (:chat-id %)) active)))))

;; ---------------------------------------------------------------------------
;; US2 — metric slug validation (consume-only, subset of 016 canonical-metric-slugs)
;; ---------------------------------------------------------------------------

(deftest valid-metrics-accepted
  (testing "slugs from 016 canonical-metric-slugs are accepted"
    (let [result (sub/validate-metrics [:revenue :net-profit :margin-pct :drr-pct])]
      (is (true? (:valid? result)))
      (is (empty? (:rejected result))))))

(deftest unknown-slug-is-rejected
  (testing "a slug absent from 016 canonical-metric-slugs is rejected"
    (let [result (sub/validate-metrics [:revenue :this-is-not-a-known-slug])]
      (is (false? (:valid? result)))
      (is (= [:this-is-not-a-known-slug] (:rejected result))))))

(deftest max-metrics-exceeded-is-rejected
  (testing "more than max-metrics (10) slugs → validation error"
    (let [many-slugs (vec (take 11 (cycle [:revenue :net-profit :margin-pct :drr-pct
                                           :orders :advertising :cogs :logistics])))
          result     (sub/validate-metrics many-slugs)]
      (is (false? (:valid? result)))
      (is (re-find #"max" (str (:error result)))))))

(deftest empty-cadences-rejected
  (testing "subscription with no cadences is rejected"
    (let [result (sub/validate-subscription {:chat-id "x"
                                              :cadences #{}
                                              :metrics [:revenue]
                                              :show-movers? false
                                              :marketplace :all
                                              :gate-when-empty :skip})]
      (is (false? (:valid? result))))))

(deftest default-metric-set-is-valid
  (testing "default-metric-set contains only known 016 slugs"
    (let [result (sub/validate-metrics sub/default-metric-set)]
      (is (true? (:valid? result))))))
