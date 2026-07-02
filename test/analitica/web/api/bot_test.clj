(ns analitica.web.api.bot-test
  "Contract tests for spec 017 Bot-settings HTTP API.

   Tests call handlers directly (no ring.mock), using a fresh temp-file SQLite
   DB per test (same pattern as tax-opex-test / settings-test).

   Contract: specs/017-engagement-bot-planfact/contracts/bot-subscription.edn §4.

   Run focused:
     clojure -M:test --focus analitica.web.api.bot-test"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.settings :as settings]
            [analitica.bot.registry :as registry]
            [analitica.feedback.notify :as notify]
            [analitica.web.api.bot :as api])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Fixture: fresh temp-file SQLite DB per test
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "bot-api-test-" ".db"
                                   (make-array FileAttribute 0))
        f    (.toFile path)]
    (.delete f)
    (.getAbsolutePath f)))

(defn- delete-temp-db! [path]
  (doseq [suffix ["" "-shm" "-wal"]]
    (let [f (File. (str path suffix))]
      (when (.exists f) (.delete f)))))

(defn with-temp-db [f]
  (let [path      (fresh-temp-db-path)
        orig-spec (deref #'db/db-spec)]
    (try
      (alter-var-root #'db/db-spec (constantly {:dbtype "sqlite" :dbname path}))
      (db/init!)
      (f)
      (finally
        (alter-var-root #'db/db-spec (constantly orig-spec))
        (reset! @#'db/datasource nil)
        (delete-temp-db! path)))))

(use-fixtures :each with-temp-db)

;; ---------------------------------------------------------------------------
;; GET /api/v1/bot/subscriptions
;; ---------------------------------------------------------------------------

(deftest get-subscriptions-empty
  (testing "GET subscriptions with none returns shape with empty list"
    (let [resp (api/get-subscriptions {})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (map? body))
      (is (vector? (:subscriptions body)))
      (is (empty? (:subscriptions body)))
      (is (contains? body :bot-configured?))
      (is (boolean? (:bot-configured? body)))
      (is (= 10 (:max-metrics body))))))

(deftest get-subscriptions-bot-configured-reflects-token
  (testing ":bot-configured? is false without token, true with token"
    (is (false? (:bot-configured? (:body (api/get-subscriptions {})))))
    (settings/set! "notify.telegram.bot-token" "123:abc" :secret? true)
    (is (true? (:bot-configured? (:body (api/get-subscriptions {})))))))

(deftest get-subscriptions-lists-saved
  (testing "GET returns saved subscriptions"
    (registry/save-subscription! {:chat-id "42" :label "Я"
                                  :cadences #{:daily} :metrics [:revenue]
                                  :show-movers? true :marketplace :all
                                  :gate-when-empty :skip})
    (let [subs (:subscriptions (:body (api/get-subscriptions {})))]
      (is (= 1 (count subs)))
      (is (= "42" (:chat-id (first subs))))
      (is (= [:revenue] (:metrics (first subs)))))))

;; ---------------------------------------------------------------------------
;; POST /api/v1/bot/subscriptions  (create)
;; ---------------------------------------------------------------------------

(deftest post-subscription-creates
  (testing "POST valid subscription saves and returns {:saved? true :subscription ...}"
    (let [resp (api/post-subscription
                 {:body {:chat-id "77" :label "Команда"
                         :cadences ["daily" "weekly"]
                         :metrics ["revenue" "net-profit"]
                         :show-movers? true :marketplace "wb"
                         :gate-when-empty "skip"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (true? (:saved? body)))
      (let [sub (:subscription body)]
        (is (= "77" (:chat-id sub)))
        (is (= #{:daily :weekly} (:cadences sub)))
        (is (= [:revenue :net-profit] (:metrics sub)))
        (is (= :wb (:marketplace sub))))
      ;; persisted
      (is (some? (registry/get-subscription "77"))))))

(deftest post-subscription-empty-metrics-ok
  (testing "POST with empty metrics is valid (defaults applied at render, FR-011)"
    (let [resp (api/post-subscription
                 {:body {:chat-id "88" :cadences ["daily"] :metrics []}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :saved?]))))))

(deftest post-subscription-rejects-over-max-metrics
  (testing "POST with > max-metrics metrics is rejected (FR-012)"
    (let [too-many (mapv name (repeat 11 :revenue))
          resp (api/post-subscription
                 {:body {:chat-id "99" :cadences ["daily"] :metrics too-many}})]
      (is (= 422 (:status resp)))
      (is (false? (get-in resp [:body :saved?])))
      (is (string? (get-in resp [:body :error])))
      ;; not persisted
      (is (nil? (registry/get-subscription "99"))))))

(deftest post-subscription-rejects-no-cadence
  (testing "POST with no cadence is rejected"
    (let [resp (api/post-subscription
                 {:body {:chat-id "100" :cadences [] :metrics ["revenue"]}})]
      (is (= 422 (:status resp)))
      (is (false? (get-in resp [:body :saved?]))))))

;; ---------------------------------------------------------------------------
;; PUT /api/v1/bot/subscriptions/:chat-id  (update)
;; ---------------------------------------------------------------------------

(deftest put-subscription-updates
  (testing "PUT updates an existing subscription (upsert by chat-id)"
    (registry/save-subscription! {:chat-id "55" :cadences #{:daily}
                                  :metrics [:revenue] :marketplace :all})
    (let [resp (api/put-subscription
                 {:params {:chat-id "55"}
                  :body {:cadences ["weekly"] :metrics ["net-profit"]
                         :marketplace "ozon"}})
          sub  (get-in resp [:body :subscription])]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :saved?])))
      (is (= #{:weekly} (:cadences sub)))
      (is (= [:net-profit] (:metrics sub)))
      (is (= :ozon (:marketplace sub))))))

(deftest put-subscription-rejects-over-max
  (testing "PUT over max-metrics is rejected"
    (registry/save-subscription! {:chat-id "56" :cadences #{:daily}
                                  :metrics [:revenue]})
    (let [resp (api/put-subscription
                 {:params {:chat-id "56"}
                  :body {:cadences ["daily"]
                         :metrics (mapv name (repeat 11 :revenue))}})]
      (is (= 422 (:status resp)))
      (is (false? (get-in resp [:body :saved?]))))))

;; ---------------------------------------------------------------------------
;; DELETE /api/v1/bot/subscriptions/:chat-id
;; ---------------------------------------------------------------------------

(deftest delete-subscription-unsubscribes
  (testing "DELETE removes the subscription"
    (registry/save-subscription! {:chat-id "66" :cadences #{:daily}
                                  :metrics [:revenue]})
    (is (some? (registry/get-subscription "66")))
    (let [resp (api/delete-subscription {:params {:chat-id "66"}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :deleted?]))))
    (is (nil? (registry/get-subscription "66")))))

(deftest delete-subscription-missing-chat-id
  (testing "DELETE without chat-id returns 400"
    (let [resp (api/delete-subscription {:params {}})]
      (is (= 400 (:status resp)))
      (is (false? (get-in resp [:body :deleted?]))))))

;; ---------------------------------------------------------------------------
;; POST /api/v1/bot/test  (manual digest test)
;; ---------------------------------------------------------------------------

(deftest post-test-sends-digest
  (testing "POST /test assembles a digest and reports the outcome"
    (registry/save-subscription! {:chat-id "31" :cadences #{:daily}
                                  :metrics [:revenue] :marketplace :all})
    (let [sent (atom nil)]
      (with-redefs [api/collect-payload!
                    (fn [_sub _from _to]
                      {:from "2026-06-01" :to "2026-06-30"
                       :kpi {:revenue 100.0}
                       :by-marketplace [{:marketplace :wb :revenue 100.0
                                         :profit 30.0 :preliminary? false}]
                       :freshness {:wb (str (java.time.LocalDateTime/now))}})
                    notify/send-message!
                    (fn [chat-id text]
                      (reset! sent {:chat-id chat-id :text text})
                      {:sent? true :detail "ok"})]
        (let [resp (api/post-test {:body {:chat-id "31"}})]
          (is (= 200 (:status resp)))
          (is (true? (get-in resp [:body :sent?])))
          (is (= "31" (:chat-id @sent))))))))

(deftest post-test-unknown-chat
  (testing "POST /test for an unknown chat returns sent? false"
    (let [resp (api/post-test {:body {:chat-id "does-not-exist"}})]
      (is (= 200 (:status resp)))
      (is (false? (get-in resp [:body :sent?])))
      (is (string? (get-in resp [:body :detail]))))))

;; ---------------------------------------------------------------------------
;; POST /api/v1/bot/max-request  (FR-027)
;; ---------------------------------------------------------------------------

(deftest post-max-request-records
  (testing "POST /max-request records the request (FR-027)"
    (let [resp (api/post-max-request {:body {:contact "@seller"}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :recorded?]))))
    ;; durable trace exists
    (is (some? (get (settings/overrides) "bot.max-request")))))
