(ns analitica.domain.treasury.operations-test
  "US2 — treasury registry (operations / accounts / counterparties).
   Contracts: specs/019-treasury-ledger/contracts/cashflow-api.edn §2-§4,
   ledger-entities.edn §1/§2/§4. Money is decimal-as-string (\"0.00\");
   comparison is kopeck-exact (dcmp / d->str), NEVER Math/abs < ε."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.set]
            [analitica.db :as db]
            [analitica.domain.treasury.operations :as ops]
            [analitica.domain.treasury.ledger :as ledger])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp-file SQLite fixture (mirrors db_test.clj) — file-based because init!
;; opens fresh connections per statement.
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-treasury-ops-" ".db"
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

;; ---------------------------------------------------------------------------
;; Round-trip: account / counterparty / operation (US2 AC1/AC5, FR-006/FR-007)
;; ---------------------------------------------------------------------------

(deftest account-round-trip
  (testing "create-account! persists and list-accounts returns it with derived balance"
    (let [{:keys [id]} (ops/create-account! {:name "Расчётный" :kind :bank :currency "RUB"})]
      (is (int? id))
      (let [{:keys [accounts]} (ops/list-accounts)
            acc (first (filter #(= id (:id %)) accounts))]
        (is (= "Расчётный" (:name acc)))
        (is (= :bank (:kind acc)))
        (is (= "RUB" (:currency acc)))
        ;; no operations yet → derived balance is exactly 0.00
        (is (= "0.00" (:balance acc)))))))

(deftest counterparty-round-trip
  (testing "create-counterparty! persists; list-counterparties carries operation-count linkage"
    (let [{:keys [id]} (ops/create-counterparty! {:name "Ozon" :kind :marketplace})
          acc          (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))]
      (ops/create! {:op-date "2026-06-15" :amount "1000.00" :currency "RUB"
                    :direction :income :account-id acc :counterparty-id id
                    :confirmed true :regular false})
      (let [{:keys [counterparties]} (ops/list-counterparties)
            cp (first (filter #(= id (:id %)) counterparties))]
        (is (= "Ozon" (:name cp)))
        (is (= :marketplace (:kind cp)))
        (is (= 1 (:operation-count cp)))))))

(deftest operation-round-trip
  (testing "create! persists an operation; get-op returns decimal-string amount verbatim"
    (let [acc          (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))
          {:keys [id]} (ops/create! {:op-date "2026-06-25" :amount "9900.00" :currency "RUB"
                                     :direction :expense :account-id acc :category "services"
                                     :confirmed true :regular false :description "ПО"})
          op (ops/get-op id)]
      (is (= "9900.00" (:amount op)))
      (is (= :expense (:direction op)))
      (is (= "services" (:category op)))
      (is (= "RUB" (:currency op))))))

;; ---------------------------------------------------------------------------
;; T026 — transfer net-zero (SC-003): per-account moves, business net = 0
;; ---------------------------------------------------------------------------

(deftest transfer-net-zero
  (testing "transfer A→B moves per-account balances but contributes 0 to business net"
    (let [a    (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))
          b    (:id (ops/create-account! {:name "B" :kind :bank :currency "RUB"}))
          bal0 (ledger/balances)]
      (ops/create! {:op-date "2026-06-20" :amount "50000.00" :currency "RUB"
                    :direction :transfer :account-id a :transfer-account-id b
                    :confirmed true :regular false})
      (let [bal1 (ledger/balances)]
        (is (= "-50000.00" (ledger/delta bal0 bal1 a)) "transfer did not debit A")
        (is (= "50000.00"  (ledger/delta bal0 bal1 b)) "transfer did not credit B")
        (let [s (:summary (ops/list-ops {:direction :transfer}))]
          (is (= "0.00" (:total-income s))  "transfer leaked into income")
          (is (= "0.00" (:total-expense s)) "transfer leaked into expense"))))))

;; ---------------------------------------------------------------------------
;; T027 — summary + filters + pagination (FR-008), shape per cashflow-api.edn §2
;; ---------------------------------------------------------------------------

(defn- seed-june! []
  (let [a  (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))
        b  (:id (ops/create-account! {:name "B" :kind :bank :currency "RUB"}))
        cp (:id (ops/create-counterparty! {:name "Ozon" :kind :marketplace}))]
    (ops/create! {:op-date "2026-06-15" :amount "100000.00" :currency "RUB"
                  :direction :income :account-id a :counterparty-id cp
                  :category "mp-payout" :confirmed true :regular true})
    (ops/create! {:op-date "2026-06-16" :amount "30000.00" :currency "RUB"
                  :direction :expense :account-id a :counterparty-id cp
                  :category "marketing" :confirmed true :regular true})
    (ops/create! {:op-date "2026-06-17" :amount "15000.00" :currency "RUB"
                  :direction :expense :account-id a :category "logistics"
                  :confirmed true :regular true})
    ;; a planned (unconfirmed) expense — counts in planned-count, excluded from actuals
    (ops/create! {:op-date "2026-06-18" :amount "5000.00" :currency "RUB"
                  :direction :expense :account-id a :category "services"
                  :confirmed false :regular false})
    ;; transfer must not affect income/expense summary
    (ops/create! {:op-date "2026-06-19" :amount "20000.00" :currency "RUB"
                  :direction :transfer :account-id a :transfer-account-id b
                  :confirmed true :regular false})
    {:a a :b b :cp cp}))

(deftest summary-and-filters
  (testing "list-ops summary: income/expense/balance/planned-count, transfer excluded"
    (seed-june!)
    (let [{:keys [operations summary total page page-size]}
          (ops/list-ops {:from "2026-06-01" :to "2026-06-30"
                         :direction :expense :confirmed true :page 1 :page-size 50})]
      ;; only confirmed expenses: 30000 + 15000
      (is (= 2 (count operations)))
      (is (= 2 total))
      (is (= 1 page))
      (is (= 50 page-size))
      (is (= "-45000.00" (:total-expense summary))))
    (testing "unfiltered summary excludes transfer from income/expense; planned-count present"
      (let [{:keys [summary]} (ops/list-ops {:from "2026-06-01" :to "2026-06-30"})]
        (is (= "100000.00" (:total-income summary)))
        ;; 30000 + 15000 + 5000 planned expense
        (is (= "-50000.00" (:total-expense summary)))
        ;; income − |expense|, transfer NOT counted
        (is (= "50000.00" (:balance summary)))
        (is (= 1 (:planned-count summary)))))))

(deftest pagination
  (testing "page/page-size slices operations; total is the unpaged count"
    (seed-june!)
    (let [p1 (ops/list-ops {:from "2026-06-01" :to "2026-06-30" :page 1 :page-size 2})
          p2 (ops/list-ops {:from "2026-06-01" :to "2026-06-30" :page 2 :page-size 2})]
      (is (= 5 (:total p1)))
      (is (= 2 (count (:operations p1))))
      (is (= 2 (count (:operations p2))))
      ;; no id overlap between pages
      (is (empty? (clojure.set/intersection
                    (set (map :id (:operations p1)))
                    (set (map :id (:operations p2)))))))))

;; ---------------------------------------------------------------------------
;; T028 — cross-field validation + soft-archive (R3/R9/R11, FR-022/FR-026)
;; ---------------------------------------------------------------------------

(deftest transfer-validation
  (testing "direction=:transfer requires transfer-account-id ≠ nil and ≠ account-id"
    (let [a (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))]
      (is (thrown? Exception
                   (ops/create! {:op-date "2026-06-20" :amount "1.00" :currency "RUB"
                                 :direction :transfer :account-id a
                                 :confirmed true :regular false}))
          "transfer without transfer-account-id must be rejected")
      (is (thrown? Exception
                   (ops/create! {:op-date "2026-06-20" :amount "1.00" :currency "RUB"
                                 :direction :transfer :account-id a :transfer-account-id a
                                 :confirmed true :regular false}))
          "transfer to same account must be rejected"))))

(deftest non-transfer-rejects-transfer-account
  (testing "direction≠:transfer with a transfer-account-id is rejected (R3)"
    (let [a (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))
          b (:id (ops/create-account! {:name "B" :kind :bank :currency "RUB"}))]
      (is (thrown? Exception
                   (ops/create! {:op-date "2026-06-20" :amount "1.00" :currency "RUB"
                                 :direction :expense :account-id a :transfer-account-id b
                                 :confirmed true :regular false}))))))

(deftest non-rub-rejected
  (testing "non-RUB currency is rejected at validation (DEC-4 / FR-022)"
    (let [a (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))]
      (is (thrown? Exception
                   (ops/create! {:op-date "2026-06-20" :amount "1.00" :currency "USD"
                                 :direction :expense :account-id a
                                 :confirmed true :regular false}))))))

(deftest invalid-amount-rejected
  (testing "amount not in \"0.00\"-form is rejected (no silent truncation)"
    (let [a (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))]
      (is (thrown? Exception
                   (ops/create! {:op-date "2026-06-20" :amount "1.5" :currency "RUB"
                                 :direction :expense :account-id a
                                 :confirmed true :regular false}))))))

(deftest soft-archive-with-history
  (testing "deleting an account/counterparty with referencing operations soft-archives, not hard-deletes"
    (let [a  (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))
          cp (:id (ops/create-counterparty! {:name "Ozon" :kind :marketplace}))]
      (ops/create! {:op-date "2026-06-15" :amount "1000.00" :currency "RUB"
                    :direction :income :account-id a :counterparty-id cp
                    :confirmed true :regular false})
      (let [res (ops/delete-account! a)]
        (is (:archived res) "account with history should be archived, not deleted"))
      ;; archived account still present but flagged
      (let [acc (first (filter #(= a (:id %)) (:accounts (ops/list-accounts {:include-archived true}))))]
        (is (some? (:archived-at acc))))
      (let [res (ops/delete-counterparty! cp)]
        (is (:archived res))))))

(deftest hard-delete-without-history
  (testing "deleting an unreferenced account hard-deletes"
    (let [a (:id (ops/create-account! {:name "Empty" :kind :bank :currency "RUB"}))
          res (ops/delete-account! a)]
      (is (false? (:archived res)))
      (is (empty? (filter #(= a (:id %)) (:accounts (ops/list-accounts))))))))
