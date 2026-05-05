(ns analitica.integration.ozon-orphan-services-test
  "Integration test for T047 `materialize-ozon-orphan-services!` — the
   INSERT path that complements the UPDATE-only `materialize-ozon-services!`
   to close the SC-003 reconciliation gap (B-009 fix).

   Scenario:
     - Article A has a sale row in finance (realization-path done).
     - Article B does NOT have a finance row (e.g. it was sold in a
       prior month, but this month's transactions contain a return-logistics
       service entry — orphan from the UPDATE perspective).
     - Running the full merge must:
         • UPDATE A's existing row with its service cost (delivery_cost=50)
         • INSERT a new service-only row for B (operation=\"service\",
           delivery_cost=30, for_pay=0 — B-005 invariant)
     - B-005 must be preserved — `for_pay` on A is unchanged, and the
       inserted orphan rows contribute 0 to `for_pay`.
     - Idempotency: running the full pipeline twice yields the same row
       count and the same field values (no double-adding)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.db :as db]
            [analitica.materialize :as mat]
            [analitica.sync :as sync])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp-file SQLite DB fixture (mirrors materialize_test.clj)
;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-orphan-svc-test-"
                                   ".db"
                                   (make-array FileAttribute 0))
        f    (.toFile path)]
    (.delete f)
    (.getAbsolutePath f)))

(defn- delete-test-db! [path]
  (doseq [suffix ["" "-shm" "-wal"]]
    (let [f (File. (str path suffix))]
      (when (.exists f) (.delete f)))))

(defn with-temp-db [f]
  (let [path      (fresh-temp-db-path)
        orig-spec (deref #'db/db-spec)]
    (try
      (alter-var-root #'db/db-spec (constantly {:dbtype "sqlite" :dbname path}))
      (binding [*test-db-path* path]
        (db/init!)
        (f))
      (finally
        (alter-var-root #'db/db-spec (constantly orig-spec))
        (reset! @#'db/datasource nil)
        (delete-test-db! path)))))

(use-fixtures :each with-temp-db)

;; ---------------------------------------------------------------------------
;; Fixture helpers
;; ---------------------------------------------------------------------------

(defn- seed-finance-row!
  [{:keys [rrd-id article date-from for-pay operation]
    :or   {operation "sale"
           for-pay   500.0}}]
  (let [row (sync/finance->row {:rrd-id      rrd-id
                                :date-from   date-from
                                :date-to     date-from
                                :article     article
                                :operation   operation
                                :for-pay     for-pay
                                :marketplace :ozon})]
    (db/insert-batch! :finance sync/finance-columns [row])))

(defn- seed-sku-map! [pairs]
  (db/save-ozon-sku-map! pairs))

(defn- seed-realization-raw!
  "Seed two articles in one realization payload so build-article-lookup
   resolves both SKU 1 (A) and SKU 2 (B) via the merged lookup."
  [date-from date-to articles]
  (db/insert-raw! :ozon :realization date-from date-to
                  {:header {:start_date date-from :stop_date date-to}
                   :rows   (mapv (fn [{:keys [sku article]}]
                                   {:rowNumber                 1
                                    :seller_price_per_instance 500
                                    :item                      {:sku sku :offer_id article}
                                    :delivery_commission       {:quantity 1 :amount 500 :standard_fee 0}})
                                 articles)}))

(defn- seed-transactions-raw! [operations date-from date-to]
  (db/insert-raw! :ozon :transactions date-from date-to
                  {:operations operations}))

(defn- finance-rows-for-article [article]
  (db/query
    ["SELECT rrd_id, operation, article, for_pay, retail_amount,
             delivery_cost, return_logistics, dropoff_cost,
             acquiring_fee, acceptance, storage_fee,
             additional_payment, ad_cost, date_from, date_to
      FROM finance
      WHERE marketplace='ozon' AND article = ?
      ORDER BY rrd_id"
     article]))

(defn- count-ozon-rows []
  (-> (db/query ["SELECT COUNT(*) AS cnt FROM finance WHERE marketplace='ozon'"])
      first :cnt))

;; ---------------------------------------------------------------------------
;; Fixture: article A sold this month, article B has orphan service
;; ---------------------------------------------------------------------------

(defn- setup-fixtures! []
  ;; Pre-seeded sale finance-row for article A. No row for article B.
  (seed-finance-row! {:rrd-id 1 :article "A"
                      :date-from "2026-03-01"
                      :for-pay 500.0})
  ;; SKU-map so build-article-lookup resolves BOTH skus; realization raw
  ;; only needs to exist so the Ozon :transactions branch runs (the lookup
  ;; merges catalog + realization entries).
  (seed-sku-map! [[1 "A"] [2 "B"]])
  (seed-realization-raw! "2026-03-01" "2026-03-31"
                         [{:sku 1 :article "A"}
                          {:sku 2 :article "B"}])
  (seed-transactions-raw!
    [{:operation_id   1001
      :operation_type "OperationAgentDeliveredToCustomer"
      :operation_date "2026-03-15 10:00:00"
      :type           "services"
      :amount         -50
      :items          [{:sku 1 :name "A" :price 500 :quantity 1}]
      :services       [{:name "MarketplaceServiceItemDirectFlowLogistic" :price -50}]
      :posting        {:posting_number "P-A1" :delivery_schema "FBO"}}
     {:operation_id   2001
      :operation_type "OperationReturnGoodsFBSofRMS"
      :operation_date "2026-03-20 10:00:00"
      :type           "services"
      :amount         -30
      :items          [{:sku 2 :name "B" :price 500 :quantity 1}]
      :services       [{:name "MarketplaceServiceItemReturnFlowLogistic" :price -30}]
      :posting        {:posting_number "P-B1" :delivery_schema "FBO"}}]
    "2026-03-01" "2026-03-31"))

;; ---------------------------------------------------------------------------
;; T048: orphan INSERT is applied + B-005 preserved + idempotency
;; ---------------------------------------------------------------------------

(deftest orphan-service-insert-complements-update-path
  (testing "T047: article B (no sale row) receives a service-only INSERT;
            article A's delivery_cost gets the UPDATE; B-005 unchanged"
    (setup-fixtures!)

    (mat/materialize-ozon-services! ["2026-03-01" "2026-03-31"])
    (mat/materialize-ozon-orphan-services! ["2026-03-01" "2026-03-31"])

    ;; Article A — UPDATE path
    (let [rows-a (finance-rows-for-article "A")]
      (is (= 1 (count rows-a)) "A still has exactly one row (UPDATE, not INSERT)")
      (let [row (first rows-a)]
        (is (= 50.0 (:delivery-cost row))
            "A: delivery_cost merged from service into sale-row")
        (is (= 500.0 (:for-pay row))
            "B-005 invariant: A.for_pay preserved")
        (is (= "sale" (:operation row))
            "A's existing row keeps operation='sale'")))

    ;; Article B — INSERT path (new service-only row)
    (let [rows-b (finance-rows-for-article "B")]
      (is (= 1 (count rows-b))
          "B has exactly one NEW service-only row")
      (let [row (first rows-b)]
        (is (= "service" (:operation row))
            "new orphan row: operation='service'")
        (is (= 30.0 (:return-logistics row))
            "B: return_logistics populated from orphan service
             (Phase 4: ReturnFlowLogistic moved from :delivery-cost)")
        (is (or (nil? (:for-pay row))
                (zero? (:for-pay row)))
            "B-005 invariant: inserted service-row has for_pay=0")
        (is (or (nil? (:retail-amount row))
                (zero? (:retail-amount row)))
            "retail_amount=0 on service-only row")
        (is (= "2026-03-01" (:date-from row))
            "date_from = first of operation month")
        ;; Other cost fields should NOT be populated on this orphan row
        (is (or (nil? (:acquiring-fee row)) (zero? (:acquiring-fee row))))
        (is (or (nil? (:acceptance row))    (zero? (:acceptance row))))
        (is (or (nil? (:storage-fee row))   (zero? (:storage-fee row))))))

    ;; Total row count = 1 (A) + 1 (B) = 2
    (is (= 2 (count-ozon-rows))
        "total ozon finance rows = 2 (A updated, B inserted)")))

(deftest orphan-service-insert-is-idempotent
  (testing "running materialize-ozon-orphan-services! twice does NOT create
            duplicate rows or accumulate costs"
    (setup-fixtures!)

    (mat/materialize-ozon-services! ["2026-03-01" "2026-03-31"])
    (mat/materialize-ozon-orphan-services! ["2026-03-01" "2026-03-31"])

    (let [first-count (count-ozon-rows)
          rows-b-1    (finance-rows-for-article "B")]
      (is (= 2 first-count))
      (is (= 30.0 (:return-logistics (first rows-b-1))))

      ;; Second run — both merges rerun; DB state must be unchanged
      (mat/materialize-ozon-services! ["2026-03-01" "2026-03-31"])
      (mat/materialize-ozon-orphan-services! ["2026-03-01" "2026-03-31"])

      (is (= first-count (count-ozon-rows))
          "row count unchanged after second run")
      (let [rows-b-2 (finance-rows-for-article "B")]
        (is (= 1 (count rows-b-2))
            "only one B row (no duplicate from deterministic rrd_id)")
        (is (= 30.0 (:return-logistics (first rows-b-2)))
            "return_logistics STILL 30 (not double-added to 60)"))
      (let [rows-a (finance-rows-for-article "A")]
        (is (= 500.0 (:for-pay (first rows-a)))
            "B-005 invariant still holds after second run")
        (is (= 50.0 (:delivery-cost (first rows-a)))
            "A's delivery_cost also unchanged from UPDATE path")))))

(deftest orphan-service-insert-handles-multiple-fields
  (testing "an orphan article with several distinct service fields gets
            ONE row per (article, month, field) — distinct rrd_ids."
    (seed-sku-map! [[3 "C"]])
    (seed-realization-raw! "2026-03-01" "2026-03-31"
                           [{:sku 3 :article "C"}])
    (seed-transactions-raw!
      [{:operation_id   3001
        :operation_type "OperationAgentDeliveredToCustomer"
        :operation_date "2026-03-10 10:00:00"
        :type           "services"
        :amount         -60
        :items          [{:sku 3 :name "C" :price 500 :quantity 1}]
        :services       [{:name "MarketplaceServiceItemDirectFlowLogistic" :price -30}
                         {:name "MarketplaceRedistributionOfAcquiringOperation" :price -10}
                         {:name "MarketplaceServiceItemPackageMaterialsProvision" :price -20}]
        :posting        {:posting_number "P-C1" :delivery_schema "FBO"}}]
      "2026-03-01" "2026-03-31")

    (mat/materialize-ozon-services! ["2026-03-01" "2026-03-31"])
    (mat/materialize-ozon-orphan-services! ["2026-03-01" "2026-03-31"])

    ;; C should have 3 service-only rows, one per distinct cost field
    (let [rows-c (finance-rows-for-article "C")]
      (is (= 3 (count rows-c))
          "three service-only rows: one per distinct populated cost field")
      (is (every? #(= "service" (:operation %)) rows-c)
          "all rows have operation='service'")
      (is (every? #(or (nil? (:for-pay %)) (zero? (:for-pay %))) rows-c)
          "B-005: for_pay=0 on every service-only row")
      (let [total-delivery  (reduce + 0.0 (keep :delivery-cost rows-c))
            total-acquiring (reduce + 0.0 (keep :acquiring-fee rows-c))
            total-accept    (reduce + 0.0 (keep :acceptance rows-c))]
        (is (= 30.0 total-delivery))
        (is (= 10.0 total-acquiring))
        (is (= 20.0 total-accept))))))
