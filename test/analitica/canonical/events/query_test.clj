(ns analitica.canonical.events.query-test
  "Tests for the canonical-counts read API. Uses a temp DB and seeds
   item_events directly to keep tests independent of the per-MP
   normalizers."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.canonical.events.query :as q]
            [next.jdbc :as jdbc])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *db-path* nil)

(defn- fresh-db []
  (let [p (Files/createTempFile "events-query-test-" ".db" (make-array FileAttribute 0))
        f (.toFile p)]
    (.delete f) (.getAbsolutePath f)))

(defn- delete-db [path]
  (doseq [s ["" "-shm" "-wal"]]
    (let [f (File. (str path s))] (when (.exists f) (.delete f)))))

(defn with-db [f]
  (let [p (fresh-db)]
    (try
      (alter-var-root #'db/db-spec (constantly {:dbtype "sqlite" :dbname p}))
      (binding [*db-path* p]
        (db/init!)
        (f))
      (finally
        (reset! @#'db/datasource nil)
        (delete-db p)))))

(use-fixtures :each with-db)

(defn- seed-event!
  "Insert one item_event row into the canonical table."
  [{:keys [marketplace posting-id item-seq article event-type event-date]
    :or   {item-seq 0}}]
  (jdbc/execute-one!
    (db/ds)
    ["INSERT INTO item_events
       (marketplace, posting_id, item_seq, article, event_type, event_date,
        quantity, ingested_at)
      VALUES (?, ?, ?, ?, ?, ?, 1, '2026-05-05 00:00:00')"
     marketplace posting-id item-seq article event-type event-date]))

(defn- seed-many! [marketplace event-type articles dates]
  (doseq [[i [art date]] (map-indexed vector (map vector articles dates))]
    (seed-event! {:marketplace marketplace
                  :posting-id  (str "P-" i)
                  :item-seq    0
                  :article     art
                  :event-type  event-type
                  :event-date  date})))

(deftest units-ordered-counts-events-in-window
  (testing "ordered count includes events in [from..to] inclusive"
    (seed-many! "ozon" "ordered"
                ["A" "B" "C" "D"]
                ["2026-04-01" "2026-04-15" "2026-04-30" "2026-05-01"])
    (is (= 3 (q/units-ordered "2026-04-01" "2026-04-30" :ozon))
        "April events count; May 1 excluded")))

(deftest units-delivered-counts-only-delivered-event-type
  (testing "delivered counter doesn't include ordered events for same posting"
    (seed-event! {:marketplace "ozon" :posting-id "P-A" :event-type "ordered"
                  :event-date "2026-04-10" :article "A"})
    (seed-event! {:marketplace "ozon" :posting-id "P-A" :item-seq 1
                  :event-type "delivered" :event-date "2026-04-12" :article "A"})
    (is (= 1 (q/units-ordered   "2026-04-01" "2026-04-30" :ozon)))
    (is (= 1 (q/units-delivered "2026-04-01" "2026-04-30" :ozon)))))

(deftest marketplace-filter-narrows-cross-mp-totals
  (testing "all-MP query returns sum of per-MP counts"
    (seed-many! "wb"   "ordered" ["A" "B"]     ["2026-04-05" "2026-04-10"])
    (seed-many! "ozon" "ordered" ["X" "Y" "Z"] ["2026-04-05" "2026-04-10" "2026-04-15"])
    (seed-many! "ym"   "ordered" ["Q"]         ["2026-04-08"])
    (is (= 2 (q/units-ordered "2026-04-01" "2026-04-30" :wb)))
    (is (= 3 (q/units-ordered "2026-04-01" "2026-04-30" :ozon)))
    (is (= 1 (q/units-ordered "2026-04-01" "2026-04-30" :ym)))
    (is (= 6 (q/units-ordered "2026-04-01" "2026-04-30" nil))
        "nil mp = total across all marketplaces")
    (is (= 6 (q/units-ordered "2026-04-01" "2026-04-30" :all))
        ":all keyword treated same as nil")))

(deftest buyout-rate-true-formula
  (testing "(delivered − returned) / ordered, expressed as percent.
            Distinct from pnl/:buyout-rate which is delivered/(delivered+returned)."
    ;; ordered 10, delivered 8, returned 2 → buyout = (8-2)/10 = 60%
    (dotimes [i 10]
      (seed-event! {:marketplace "ozon" :posting-id (str "O-" i)
                    :event-type "ordered" :event-date "2026-04-01" :article (str "A" i)}))
    (dotimes [i 8]
      (seed-event! {:marketplace "ozon" :posting-id (str "D-" i)
                    :event-type "delivered" :event-date "2026-04-15" :article (str "A" i)}))
    (dotimes [i 2]
      (seed-event! {:marketplace "ozon" :posting-id (str "R-" i)
                    :event-type "returned" :event-date "2026-04-25" :article (str "A" i)}))
    (is (= 60.0 (q/buyout-rate-true "2026-04-01" "2026-04-30" :ozon)))))

(deftest buyout-rate-true-handles-zero-ordered
  (testing "no orders → nil (callers display em-dash)"
    (is (nil? (q/buyout-rate-true "2026-04-01" "2026-04-30" :ozon)))))

(deftest counts-summary-bundles-everything
  (testing "summary returns map with all counters in one call"
    (seed-event! {:marketplace "ozon" :posting-id "X" :event-type "ordered"
                  :event-date "2026-04-10" :article "A"})
    (seed-event! {:marketplace "ozon" :posting-id "X" :item-seq 1 :event-type "delivered"
                  :event-date "2026-04-12" :article "A"})
    (let [s (q/counts-summary "2026-04-01" "2026-04-30" :ozon)]
      (is (= 1 (:ordered s)))
      (is (= 1 (:delivered s)))
      (is (= 0 (:cancelled s)))
      (is (= 0 (:returned s)))
      (is (= 100.0 (:buyout-rate-true s))))))
