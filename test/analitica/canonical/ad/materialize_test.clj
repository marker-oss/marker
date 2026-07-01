(ns analitica.canonical.ad.materialize-test
  "Tests for canonical/ad/materialize.clj (US1 T015).

   Invariants:
   - Only :spend (not :bonus-spend) flows into finance.ad_cost (FR-006).
   - Σ per-article spend in ad_spend == finance.ad_cost per article.
   - INSERT OR REPLACE is idempotent — double-run does not double counts (P5)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.canonical.ad.materialize :as mat]
            [next.jdbc :as jdbc])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; DB isolation — fresh SQLite per test
;; ---------------------------------------------------------------------------

(def ^:dynamic *db-path* nil)

(defn- fresh-db []
  (let [p (Files/createTempFile "ad-materialize-test-" ".db" (make-array FileAttribute 0))
        f (.toFile p)]
    (.delete f)
    (.getAbsolutePath f)))

(defn- delete-db [path]
  (doseq [s ["" "-shm" "-wal"]]
    (let [f (File. (str path s))]
      (when (.exists f) (.delete f)))))

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

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- seed-ad-spend! [rows]
  (let [synced-at "2026-04-15 00:00:00"]
    (db/insert-ad-spend! (mapv #(assoc % :synced-at synced-at) rows))))

(def ^:private rrd-counter (atom 0))

(defn- next-rrd-id [] (swap! rrd-counter inc))

(defn- insert-finance-row!
  "Insert a minimal finance row with a known article + marketplace so
   materialize-ad-cost! can UPDATE it.
   rrd_id is NOT NULL in the DDL, so we generate a unique integer per call."
  [{:keys [marketplace article event-date]
    :or   {event-date "2026-04-10"}}]
  (jdbc/execute-one!
    (db/ds)
    ["INSERT INTO finance
        (rrd_id, marketplace, article, date_from, date_to, event_date,
         retail_amount, for_pay, ad_cost, synced_at)
      VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, '2026-04-15 00:00:00')"
     (next-rrd-id) (name marketplace) article "2026-04-01" "2026-04-30" event-date]))

(defn- get-finance-ad-cost
  "Return {article → ad_cost} from the finance table for the period."
  [marketplace from to]
  (->> (db/query
         ["SELECT article, COALESCE(SUM(ad_cost), 0) AS cost FROM finance
           WHERE marketplace = ?
             AND event_date BETWEEN ? AND ?
             AND article IS NOT NULL
           GROUP BY article"
          (name marketplace) from to])
       (reduce (fn [m {:keys [article cost]}]
                 (assoc m article (double cost)))
               {})))

;; ---------------------------------------------------------------------------
;; T015a: Σ ad_spend.spend per-article == finance.ad_cost (only :spend, not :bonus-spend)
;; ---------------------------------------------------------------------------

(deftest materialize-spend-to-finance-ad-cost
  (testing "T015a: materialize-ad-cost! writes Σ :spend per article to finance.ad_cost"
    (let [from "2026-04-01" to "2026-04-30"]
      ;; Seed ad_spend rows: two articles, Ozon
      (seed-ad-spend!
        [{:marketplace        :ozon
          :event-date         "2026-04-10"
          :campaign-id        "C001"
          :article            "ART-A"
          :spend              120.0
          :bonus-spend        30.0   ; must NOT flow into finance.ad_cost
          :attribution-source :api}
         {:marketplace        :ozon
          :event-date         "2026-04-11"
          :campaign-id        "C001"
          :article            "ART-A"
          :spend              80.0
          :bonus-spend        10.0
          :attribution-source :api}
         {:marketplace        :ozon
          :event-date         "2026-04-12"
          :campaign-id        "C002"
          :article            "ART-B"
          :spend              200.0
          :bonus-spend        5.0
          :attribution-source :spread}])

      ;; Seed matching finance rows so UPDATE has targets
      (insert-finance-row! {:marketplace :ozon :article "ART-A" :event-date "2026-04-10"})
      (insert-finance-row! {:marketplace :ozon :article "ART-B" :event-date "2026-04-12"})

      ;; Run materialize
      (mat/materialize-ad-cost! :ozon from to)

      (let [costs (get-finance-ad-cost :ozon from to)]
        (testing "ART-A: Σ spend = 120.0+80.0 = 200.0 (bonus-spend excluded)"
          (is (= 200.0 (costs "ART-A"))
              "only :spend flows into ad_cost; :bonus-spend is stored separately"))
        (testing "ART-B: Σ spend = 200.0"
          (is (= 200.0 (costs "ART-B"))))))))

;; ---------------------------------------------------------------------------
;; T015b: bonus-spend does NOT flow into finance.ad_cost
;; ---------------------------------------------------------------------------

(deftest bonus-spend-not-in-ad-cost
  (testing "T015b: :bonus-spend is stored in ad_spend but never summed into finance.ad_cost"
    (let [from "2026-04-01" to "2026-04-30"]
      (seed-ad-spend!
        [{:marketplace        :wb
          :event-date         "2026-04-10"
          :campaign-id        "W001"
          :article            "ART-W"
          :spend              100.0
          :bonus-spend        999.0  ; large bonus — must not appear in ad_cost
          :attribution-source :api}])

      (insert-finance-row! {:marketplace :wb :article "ART-W" :event-date "2026-04-10"})

      (mat/materialize-ad-cost! :wb from to)

      (let [costs (get-finance-ad-cost :wb from to)]
        (is (= 100.0 (costs "ART-W"))
            "ad_cost = :spend only, :bonus-spend (999.0) must NOT be included")))))

;; ---------------------------------------------------------------------------
;; T015c: INSERT OR REPLACE idempotency — double-run does not double counts (P5)
;; ---------------------------------------------------------------------------

(deftest materialize-idempotent
  (testing "T015c: running materialize-ad-cost! twice yields same finance.ad_cost (P5)"
    (let [from "2026-04-01" to "2026-04-30"]
      (seed-ad-spend!
        [{:marketplace        :ym
          :event-date         "2026-04-10"
          :campaign-id        "Y001"
          :article            "ART-Y"
          :spend              300.0
          :bonus-spend        20.0
          :attribution-source :spread}])

      (insert-finance-row! {:marketplace :ym :article "ART-Y" :event-date "2026-04-10"})

      ;; First run
      (mat/materialize-ad-cost! :ym from to)
      (let [costs-first (get-finance-ad-cost :ym from to)]

        ;; Second run — must not double the amount
        (mat/materialize-ad-cost! :ym from to)
        (let [costs-second (get-finance-ad-cost :ym from to)]
          (is (= costs-first costs-second)
              "double-run: finance.ad_cost unchanged after second materialize (idempotent)"))))))

;; ---------------------------------------------------------------------------
;; T015d: nil-article rows are stored in ad_spend but NOT propagated to finance.ad_cost
;; ---------------------------------------------------------------------------

(deftest nil-article-not-materialized-to-finance
  (testing "T015d: account-level (nil article) ad_spend rows are stored but not written to finance.ad_cost"
    (let [from "2026-04-01" to "2026-04-30"]
      (seed-ad-spend!
        [{:marketplace        :ozon
          :event-date         "2026-04-11"
          :campaign-id        "C-acct"
          :article            nil   ; account-level — no finance row to UPDATE
          :spend              50.0
          :bonus-spend        5.0
          :attribution-source :api}])

      ;; No finance row seeded for nil article (there is none).
      (mat/materialize-ad-cost! :ozon from to)

      ;; finance table should have no ad_cost set for this period
      (let [total (-> (db/query
                        ["SELECT COALESCE(SUM(ad_cost),0) AS s FROM finance
                          WHERE marketplace='ozon' AND event_date BETWEEN ? AND ?"
                         from to])
                      first :s double)]
        (is (zero? total)
            "nil-article spend (50.0) must NOT leak into finance.ad_cost (no article = no finance row)")))))
