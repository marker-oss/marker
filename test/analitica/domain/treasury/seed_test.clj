(ns analitica.domain.treasury.seed-test
  "T029/T032 — seed from cash_flow_periods (FR-025, R5, DEC-3).
   Money comparisons are kopeck-exact on decimal-strings, never ε."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.db :as db]
            [analitica.util.math :as m]
            [analitica.domain.treasury.ledger :as ledger]
            [analitica.domain.treasury.operations :as tops]
            [analitica.domain.treasury.seed :as seed])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp-file SQLite fixture (mirrors operations_test.clj).
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-treasury-seed-" ".db"
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
      (db/init!)
      (f)
      (finally
        (alter-var-root #'db/db-spec (constantly orig-spec))
        (reset! @#'db/datasource nil)
        (delete-test-db! path)))))

(use-fixtures :each with-temp-db)

(def ^:private ctx
  {:from "2026-04-01" :to "2026-04-30"
   :mp-account-id 1 :bank-account-id 2 :counterparty-id 3})

(deftest slice-amount-full-containment
  (testing "bucket fully inside window → full amount"
    (is (= "100.01" (m/d->str (seed/slice-amount (m/d "100.01")
                                                 "2026-04-06" "2026-04-12"
                                                 "2026-04-01" "2026-04-30"))))))

(deftest slice-amount-telescopes-to-kopeck   ;; DEC-3
  (testing "Apr 28..May 4 bucket (3d apr + 4d may): slices sum EXACTLY"
    (let [amt (m/d "100.01")
          apr (seed/slice-amount amt "2026-04-28" "2026-05-04" "2026-04-01" "2026-04-30")
          may (seed/slice-amount amt "2026-04-28" "2026-05-04" "2026-05-01" "2026-05-31")]
      (is (= "42.86" (m/d->str apr)))          ;; d-prorate(100.01, 3, 7)
      (is (= "57.15" (m/d->str may)))          ;; 100.01 − 42.86 (remainder absorbed)
      (is (= "100.01" (m/d->str (m/d+ apr may)))))))

(deftest bucket->ops-shapes
  (let [row {:id 7 :source "ozon"
             :period-begin "2026-04-06" :period-end "2026-04-12"
             :orders-amount 1000.50 :commission-amount -250.10
             :payment -700.00 :storage 0.0}
        ops (seed/bucket->ops row ctx)
        by-dir (group-by :direction ops)]
    (testing "sign → direction, abs amount, category from column map"
      (let [inc-op (first (:income by-dir))]
        (is (= "1000.50" (:amount inc-op)))
        (is (= "mp-payout" (:category inc-op)))
        (is (= :seed (:category-source inc-op)))
        (is (= 3 (:counterparty-id inc-op))))
      (let [exp-op (first (:expense by-dir))]
        (is (= "250.10" (:amount exp-op)))
        (is (= "services" (:category exp-op)))))
    (testing "payment → transfer MP→bank, no category"
      (let [tr (first (:transfer by-dir))]
        (is (= "700.00" (:amount tr)))
        (is (= 1 (:account-id tr)))
        (is (= 2 (:transfer-account-id tr)))
        (is (nil? (:category tr)))))
    (testing "zero columns skipped; common fields"
      (is (= 3 (count ops)))                                   ;; storage 0.0 skipped
      (is (every? #(= "seed:cash_flow_periods" (:source %)) ops))
      (is (every? #(= "2026-04-12" (:op-date %)) ops))         ;; bucket end inside window
      (is (every? :confirmed ops)))))

(deftest bucket->ops-clamps-op-date
  (testing "bucket end past window → op-date clamped to :to"
    (let [row {:id 8 :source "ozon" :period-begin "2026-04-28"
               :period-end "2026-05-04" :orders-amount 100.0}
          ops (seed/bucket->ops row ctx)]
      (is (= "2026-04-30" (:op-date (first ops)))))))

;; ---------------------------------------------------------------------------
;; seed! — end-to-end against a temp DB (T029/T032)
;; ---------------------------------------------------------------------------

(defn- insert-bucket! [begin end m]
  (db/execute!
    [(str "INSERT INTO cash_flow_periods (source, period_begin, period_end, "
          "orders_amount, commission_amount, payment, synced_at) "
          "VALUES ('ozon', ?, ?, ?, ?, ?, '2026-07-02T00:00:00')")
     begin end (:orders m 0.0) (:commission m 0.0) (:payment m 0.0)]))

(deftest seed!-end-to-end-and-idempotent
  (insert-bucket! "2026-04-06" "2026-04-12"
                  {:orders 1000.50 :commission -250.10 :payment -700.00})
  (let [r1 (seed/seed! "ozon" "2026-04-01" "2026-04-30")]
    (testing "ops created through the canonical registry"
      (is (= 1 (:buckets r1)))
      (is (= 3 (:ops r1)))
      (is (= "1000.50" (:income r1)))
      (is (= "250.10"  (:expense r1)))
      (is (= "700.00"  (:transfer r1))))
    (testing "derived balances: MP = in − out − payout; bank = payout"
      (let [bal   (ledger/balances)
            accts (:accounts (tops/list-accounts))
            id-of (fn [n] (:id (first (filter #(= n (:name %)) accts))))]
        (is (= "50.40"  (m/d->str (ledger/account-balance bal (id-of "Ozon — маркетплейс")))))
        (is (= "700.00" (m/d->str (ledger/account-balance bal (id-of "Расчётный счёт")))))))
    (testing "cash_flow_periods untouched (P&L.6 source intact)"
      (is (= 1 (-> (db/query ["SELECT COUNT(*) AS n FROM cash_flow_periods"]) first :n)))
      (is (= 1000.50 (-> (db/query ["SELECT orders_amount FROM cash_flow_periods"]) first :orders-amount))))
    (testing "re-run is idempotent: same counts, no dupes, no new accounts"
      (let [r2 (seed/seed! "ozon" "2026-04-01" "2026-04-30")
            n  (-> (db/query [(str "SELECT COUNT(*) AS n FROM treasury_operations "
                                   "WHERE source='seed:cash_flow_periods'")])
                   first :n)]
        (is (= 3 (:deleted r2)))
        (is (= 3 n))
        (is (= (:income r1) (:income r2)))
        (is (= 2 (count (:accounts (tops/list-accounts)))))))))

(deftest seed!-straddling-bucket-reconciles-across-windows   ;; T029 / DEC-3
  (insert-bucket! "2026-04-28" "2026-05-04" {:orders 100.01})
  (seed/seed! "ozon" "2026-04-01" "2026-04-30")
  (seed/seed! "ozon" "2026-05-01" "2026-05-31")
  (let [amts (->> (db/query [(str "SELECT amount FROM treasury_operations "
                                  "WHERE source='seed:cash_flow_periods' ORDER BY op_date")])
                  (map :amount))]
    (is (= ["42.86" "57.15"] amts))
    (is (= "100.01" (m/d->str (m/dsum (map m/d amts)))))))
