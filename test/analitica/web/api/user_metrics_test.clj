(ns analitica.web.api.user-metrics-test
  "Contract + store tests for spec 016 US5 user-metric persistence & CRUD.

   - Store round-trip / validation / descriptor emission live in
     analitica.web.report-schemas (fetch-user-metrics, save-user-metric!,
     delete-user-metric!, user-metrics->descriptors).
   - HTTP CRUD handlers live in analitica.web.api.user-metrics.

   Uses a fresh temp-file SQLite DB per test (same pattern as tax-opex-test /
   feedback-api-test). Handlers are called directly with plain req maps.

   Run focused:
     clojure -M:test --focus analitica.web.api.user-metrics-test"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.web.report-schemas :as rs]
            [analitica.web.api.user-metrics :as api])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Fixture: fresh temp-file SQLite DB per test
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "user-metrics-test-" ".db"
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
;; Store — save-user-metric! / fetch-user-metrics round-trip
;; ---------------------------------------------------------------------------

(def ^:private valid-metric
  {:slug           :net-per-order
   :name           "Прибыль на заказ"
   :formula        [:/ :net-profit :orders]
   :suffix         :rub
   :filterType     :number-range
   :positiveIfGrow true
   :basis          "net sales per order"})

(deftest save-and-fetch-round-trip
  (testing "save-user-metric! persists a valid metric; fetch-user-metrics returns it"
    (is (empty? (rs/fetch-user-metrics)) "empty at start")
    (let [saved (rs/save-user-metric! valid-metric)]
      (is (pos-int? (:id saved)) "save returns {:id n}")
      (let [all (rs/fetch-user-metrics)]
        (is (= 1 (count all)))
        (let [m (first all)]
          (is (pos-int? (:id m)))
          (is (= :net-per-order (:slug m)))
          (is (= "Прибыль на заказ" (:name m)))
          (is (= [:/ :net-profit :orders] (:formula m))
              "formula round-trips as an EDN AST, not a string")
          (is (= :rub (:suffix m)))
          (is (= :number-range (:filterType m)))
          (is (= true (:positiveIfGrow m)))
          (is (= "net sales per order" (:basis m))))))))

(deftest save-rejects-invalid-formula
  (testing "save-user-metric! rejects a formula that fails valid-formula?"
    ;; :bogus-slug is not in canonical-metric-slugs → invalid AST.
    (is (thrown? Exception
          (rs/save-user-metric! (assoc valid-metric :formula [:+ :bogus-slug 1]))))
    (is (empty? (rs/fetch-user-metrics)) "nothing persisted on rejection")))

(deftest save-rejects-malformed-ast
  (testing "save-user-metric! rejects a structurally malformed AST"
    (is (thrown? Exception
          (rs/save-user-metric! (assoc valid-metric :formula [:pow :revenue :orders])))
        "unknown operator :pow is rejected")))

(deftest delete-user-metric-removes-it
  (testing "delete-user-metric! removes the saved metric"
    (let [{:keys [id]} (rs/save-user-metric! valid-metric)]
      (is (= 1 (count (rs/fetch-user-metrics))))
      (rs/delete-user-metric! id)
      (is (empty? (rs/fetch-user-metrics)) "metric gone after delete"))))

;; ---------------------------------------------------------------------------
;; Store — user-metrics->descriptors emits US1-valid ColumnDescriptors
;; ---------------------------------------------------------------------------

(deftest user-metrics->descriptors-emits-valid-descriptors
  (testing "user-metrics->descriptors maps each saved metric via user-metric->descriptor"
    (rs/save-user-metric! valid-metric)
    (let [descs (rs/user-metrics->descriptors)]
      (is (= 1 (count descs)))
      (let [d (first descs)]
        (is (= :net-per-order (:key d)) "descriptor :key = metric slug")
        (is (= "Прибыль на заказ" (:title d)))
        (is (= :user (:group d)) "user metrics land in the :user group")
        (is (true? (:user-defined? d)) "flagged for edit/delete in the UI")
        (is (some? (rs/validate-descriptor d))
            "emitted descriptor satisfies the ColumnDescriptor schema (US1-valid)")))))

;; ---------------------------------------------------------------------------
;; HTTP CRUD — GET/POST/DELETE /api/v1/metrics
;; ---------------------------------------------------------------------------

(deftest get-metrics-empty
  (testing "GET /api/v1/metrics with none returns {:metrics []}"
    (let [resp (api/get-metrics {})]
      (is (= 200 (:status resp)))
      (is (vector? (get-in resp [:body :metrics])))
      (is (empty? (get-in resp [:body :metrics]))))))

(deftest post-metric-valid-returns-ok
  (testing "POST /api/v1/metrics with a valid metric returns {:ok true :id n}"
    (let [resp (api/post-metric {:body valid-metric})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :ok])))
      (is (pos-int? (get-in resp [:body :id])))
      ;; And it now shows up in GET
      (let [g (api/get-metrics {})]
        (is (= 1 (count (get-in g [:body :metrics]))))))))

(deftest post-metric-coerces-string-slug-and-formula
  (testing "POST tolerates string slug/suffix and an EDN-string formula"
    (let [resp (api/post-metric {:body {:slug           "roas-lite"
                                        :name           "ROAS-lite"
                                        :formula        "[:/ :revenue :advertising]"
                                        :suffix         "ratio"
                                        :positiveIfGrow true}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :ok])))
      (let [m (first (rs/fetch-user-metrics))]
        (is (= :roas-lite (:slug m)))
        (is (= [:/ :revenue :advertising] (:formula m))
            "string formula parsed to an EDN AST")))))

(deftest post-metric-invalid-formula-returns-error
  (testing "POST /api/v1/metrics with an invalid formula returns {:ok false :error}"
    (let [resp (api/post-metric {:body (assoc valid-metric :formula [:+ :not-a-slug 1])})]
      (is (= 422 (:status resp)))
      (is (false? (get-in resp [:body :ok])))
      (is (string? (get-in resp [:body :error])))
      (is (empty? (rs/fetch-user-metrics)) "nothing persisted"))))

(deftest delete-metric-returns-ok
  (testing "DELETE /api/v1/metrics/:id returns {:ok true} and removes it"
    (let [id (get-in (api/post-metric {:body valid-metric}) [:body :id])]
      (is (pos-int? id))
      (let [resp (api/delete-metric {:params {:id (str id)}})]
        (is (= 200 (:status resp)))
        (is (true? (get-in resp [:body :ok]))))
      (is (empty? (rs/fetch-user-metrics)) "gone after delete"))))

(deftest delete-metric-missing-id-returns-error
  (testing "DELETE without :id returns 400 {:ok false}"
    (let [resp (api/delete-metric {:params {}})]
      (is (= 400 (:status resp)))
      (is (false? (get-in resp [:body :ok]))))))
