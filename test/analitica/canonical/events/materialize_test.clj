(ns analitica.canonical.events.materialize-test
  "End-to-end test for canonical item_events materializer.
   Seeds raw postings, calls materialize-ozon-events!, then asserts
   item_events rows match expectation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.canonical.events.materialize :as mat])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-events-test-"
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
  (let [path (fresh-temp-db-path)]
    (try
      (alter-var-root #'db/db-spec (constantly {:dbtype "sqlite" :dbname path}))
      (binding [*test-db-path* path]
        (db/init!)
        (f))
      (finally
        (reset! @#'db/datasource nil)
        (delete-test-db! path)))))

(use-fixtures :each with-temp-db)

(defn- count-events [event-type]
  (-> (db/query
        ["SELECT COUNT(*) AS n FROM item_events WHERE event_type = ?" event-type])
      first :n))

(defn- seed-postings! [date-from date-to postings]
  (db/insert-raw! :ozon :postings date-from date-to postings))

(deftest materialize-counts-units-not-postings
  (testing "raw posting with quantity=2 → 2 ordered events; multi-product
            posting with q=1+q=1 → 2 events. Each Ozon ordered unit = one row."
    (seed-postings! "2026-04-01" "2026-04-30"
                    [{:posting_number "P-A"
                      :status         "delivered"
                      :in_process_at  "2026-04-15T10:00:00Z"
                      :products       [{:offer_id "ART-A" :sku 1 :quantity 2 :price "100"}]}
                     {:posting_number "P-B"
                      :status         "delivered"
                      :in_process_at  "2026-04-16T10:00:00Z"
                      :products       [{:offer_id "ART-B" :sku 2 :quantity 1 :price "200"}
                                       {:offer_id "ART-C" :sku 3 :quantity 1 :price "300"}]}])
    (mat/materialize-ozon-events! "2026-04-01" "2026-04-30")
    (is (= 4 (count-events "ordered"))
        "P-A contributes 2 (units of A) + P-B contributes 2 (one each A/B/C) = 4")))

(deftest materialize-is-idempotent
  (testing "running the materializer twice does not duplicate events
            (composite PK + INSERT OR REPLACE keeps state stable)"
    (seed-postings! "2026-04-01" "2026-04-30"
                    [{:posting_number "P-X"
                      :status         "delivered"
                      :in_process_at  "2026-04-10T10:00:00Z"
                      :products       [{:offer_id "X" :sku 5 :quantity 3 :price "500"}]}])
    (mat/materialize-ozon-events! "2026-04-01" "2026-04-30")
    (let [first-cnt (count-events "ordered")]
      (is (= 3 first-cnt))
      (mat/materialize-ozon-events! "2026-04-01" "2026-04-30")
      (is (= first-cnt (count-events "ordered"))
          "second run leaves the count unchanged"))))

(deftest materialize-preserves-event-date-from-in-process-at
  (testing "ordered event_date is the in_process_at day, regardless of
            the raw_data window in which the posting was ingested"
    (seed-postings! "2026-04-01" "2026-04-30"
                    [{:posting_number "P-Q"
                      :status         "delivered"
                      :in_process_at  "2026-03-29T23:55:00Z"
                      :products       [{:offer_id "Q" :sku 9 :quantity 1 :price "100"}]}])
    (mat/materialize-ozon-events! "2026-04-01" "2026-04-30")
    (let [row (-> (db/query
                    ["SELECT event_date FROM item_events WHERE posting_id = 'P-Q'"])
                  first)]
      (is (= "2026-03-29" (:event-date row))
          "ordered event_date reflects when buyer placed the order, even
           though the posting was ingested in an Apr raw_data row"))))
