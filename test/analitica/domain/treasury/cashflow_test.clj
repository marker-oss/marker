(ns analitica.domain.treasury.cashflow-test
  "US1 — ДДС (cash-flow statement) by_category / by_account.
   Contracts: specs/019-treasury-ledger/contracts/cashflow-api.edn §1 invariants
   CF-1..CF-7. Money is decimal-as-string; comparison is kopeck-exact
   (m/d->str / m/dcmp), NEVER Math/abs < ε."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.set]
            [analitica.db :as db]
            [analitica.util.math :as m]
            [analitica.domain.treasury.operations :as ops]
            [analitica.domain.treasury.cashflow :as cf])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp-file SQLite fixture
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-treasury-cf-" ".db"
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
;; Fixture: operations across wb + ozon + ym accounts, all directions, H1-2026.
;; Two months (2026-05, 2026-06) keep expectations easy to hand-verify while
;; still exercising the multi-month matrix, the newest-first column order, the
;; net line, transfer exclusion and uncategorised surfacing.
;; ---------------------------------------------------------------------------

(defn- seed! []
  (let [wb   (:id (ops/create-account! {:name "WB выплаты"   :marketplace :wb   :kind :mp-settlement :currency "RUB"}))
        ozon (:id (ops/create-account! {:name "Ozon выплаты" :marketplace :ozon :kind :mp-settlement :currency "RUB"}))
        ym   (:id (ops/create-account! {:name "YM выплаты"   :marketplace :ym   :kind :mp-settlement :currency "RUB"}))
        own  (:id (ops/create-account! {:name "Расчётный"    :kind :bank :currency "RUB"}))]
    ;; --- income (mp-payout) ---
    (ops/create! {:op-date "2026-05-10" :amount "200000.00" :currency "RUB" :direction :income
                  :account-id wb   :category "mp-payout" :confirmed true :regular true})
    (ops/create! {:op-date "2026-06-10" :amount "260000.00" :currency "RUB" :direction :income
                  :account-id ozon :category "mp-payout" :confirmed true :regular true})
    (ops/create! {:op-date "2026-06-11" :amount "40000.00"  :currency "RUB" :direction :income
                  :account-id ym   :category "mp-payout" :confirmed true :regular true})
    ;; --- expense (marketing) ---
    (ops/create! {:op-date "2026-05-15" :amount "29000.00" :currency "RUB" :direction :expense
                  :account-id own :category "marketing" :confirmed true :regular true})
    (ops/create! {:op-date "2026-06-15" :amount "31000.00" :currency "RUB" :direction :expense
                  :account-id own :category "marketing" :confirmed true :regular true})
    ;; --- expense (logistics) ---
    (ops/create! {:op-date "2026-06-16" :amount "15000.00" :currency "RUB" :direction :expense
                  :account-id own :category "logistics" :confirmed true :regular true})
    ;; --- uncategorised expense (category nil) — surfaced, not in a catch-all row ---
    (ops/create! {:op-date "2026-06-17" :amount "3300.00" :currency "RUB" :direction :expense
                  :account-id own :confirmed true :regular false})
    ;; --- transfer own↔wb-ish (must NOT touch net or any category row) ---
    (ops/create! {:op-date "2026-06-20" :amount "50000.00" :currency "RUB" :direction :transfer
                  :account-id own :transfer-account-id wb :confirmed true :regular false})
    ;; --- a PLANNED income (confirmed false) — only in :with-planned mode ---
    (ops/create! {:op-date "2026-06-25" :amount "10000.00" :currency "RUB" :direction :income
                  :account-id ym :category "mp-payout" :confirmed false :regular false})
    {:wb wb :ozon ozon :ym ym :own own}))

(def base-req {:from "2026-01-01" :to "2026-06-30" :mode :actuals})

;; ---------------------------------------------------------------------------
;; T017 — CF-1..CF-3
;; ---------------------------------------------------------------------------

(deftest cf-1-net-total
  (testing "CF-1: net.total = Σinflow − Σoutflow (actuals), kopeck-exact"
    (seed!)
    (let [r (cf/report (assoc base-req :group-by :category))]
      ;; income: 200000 + 260000 + 40000 = 500000
      ;; categorised expense: 29000+31000+15000 = 75000 (uncategorised 3300 excluded)
      ;; net = 500000 − 75000 = 425000
      (is (= "425000.00" (get-in r [:net :cells "total"]))))))

(deftest cf-2-net-column-equals-sum-of-rows
  (testing "CF-2: ∀ col, net.cells[col] = Σ of that column across all rows (exact)"
    (seed!)
    (doseq [gb [:category :account]]
      (let [r (cf/report (assoc base-req :group-by gb))]
        (doseq [col (:columns r)]
          (let [row-sum (m/d->str (m/dsum (map #(m/d (get-in % [:cells col] "0.00"))
                                               (:rows r))))
                net-cell (get-in r [:net :cells col])]
            (is (= row-sum net-cell)
                (str "CF-2 violated group-by " gb " col " col
                     ": Σrows=" row-sum " net=" net-cell))))))))

(deftest cf-3-row-total-equals-sum-of-months
  (testing "CF-3: ∀ row, cells[\"total\"] = Σ month cells (exact)"
    (seed!)
    (doseq [gb [:category :account]]
      (let [r (cf/report (assoc base-req :group-by gb))
            months (remove #{"total"} (:columns r))]
        (doseq [row (:rows r)]
          (let [msum (m/d->str (m/dsum (map #(m/d (get-in row [:cells %] "0.00")) months)))]
            (is (= msum (get-in row [:cells "total"]))
                (str "CF-3 violated group-by " gb " row " (:key row)))))
        ;; and the net line itself obeys total = Σ months
        (let [msum (m/d->str (m/dsum (map #(m/d (get-in r [:net :cells %] "0.00")) months)))]
          (is (= msum (get-in r [:net :cells "total"]))
              (str "CF-3 violated for net line, group-by " gb)))))))

;; ---------------------------------------------------------------------------
;; T018 — CF-4..CF-6
;; ---------------------------------------------------------------------------

(deftest cf-4-by-category-equals-by-account
  (testing "CF-4: by-category net.total == by-account net.total, kopeck-exact"
    (seed!)
    (let [by-cat (cf/report (assoc base-req :group-by :category))
          by-acc (cf/report (assoc base-req :group-by :account))]
      (is (= (get-in by-cat [:net :cells "total"])
             (get-in by-acc [:net :cells "total"]))))))

(deftest cf-5-transfer-excluded
  (testing "CF-5: transfer operations appear neither in net nor any row cell"
    (seed!)
    (let [r (cf/report (assoc base-req :group-by :category))
          all-cells (mapcat (fn [row] (vals (:cells row))) (:rows r))]
      ;; 50000 transfer must not surface as a cell value anywhere
      (is (not-any? #(= "50000.00" %) all-cells))
      (is (not-any? #(= "-50000.00" %) all-cells))
      ;; net total already verified as 425000 in cf-1 (transfer not included)
      (is (= "425000.00" (get-in r [:net :cells "total"]))))))

(deftest cf-6-uncategorised
  (testing "CF-6: uncategorised-count = |ops with category nil|; not folded into a row"
    (seed!)
    (let [r (cf/report (assoc base-req :group-by :category))
          row-keys (set (map :key (:rows r)))]
      (is (= 1 (:uncategorised-count r)))
      (is (not (contains? row-keys nil)))
      (is (not (contains? row-keys "uncategorised")))
      ;; net still includes the uncategorised expense (it moved cash), but no
      ;; category row carries the 3300
      (let [all-cells (mapcat (fn [row] (vals (:cells row))) (:rows r))]
        (is (not-any? #(= "-3300.00" %) all-cells))))))

;; ---------------------------------------------------------------------------
;; T019 — CF-7 multi-MP through the single registry (SC-009)
;; ---------------------------------------------------------------------------

(deftest cf-7-multi-mp
  (testing "CF-7: group-by :account surfaces all 3 MPs via the single registry"
    (seed!)
    (let [r (cf/report (assoc base-req :group-by :account))
          mps (->> (:rows r) (keep :marketplace) set)]
      (is (= #{:wb :ozon :ym}
             (clojure.set/intersection mps #{:wb :ozon :ym}))
          (str "CF-7: not all 3 MPs present, got " mps)))))

;; ---------------------------------------------------------------------------
;; Modes + account filter (FR-021 / US1 AC5)
;; ---------------------------------------------------------------------------

(deftest with-planned-mode
  (testing "with-planned includes confirmed=false ops; actuals excludes them; :mode echoed"
    (seed!)
    (let [actuals (cf/report (assoc base-req :group-by :category :mode :actuals))
          planned (cf/report (assoc base-req :group-by :category :mode :with-planned))]
      (is (= :actuals (:mode actuals)))
      (is (= :with-planned (:mode planned)))
      ;; planned adds a 10000 income → net 425000 + 10000 = 435000
      (is (= "425000.00" (get-in actuals [:net :cells "total"])))
      (is (= "435000.00" (get-in planned [:net :cells "total"]))))))

(deftest account-filter
  (testing "account-ids filters the matrix; net reconciles for the subset"
    (let [{:keys [own]} (seed!)
          r (cf/report (assoc base-req :group-by :category :account-ids [own]))]
      ;; own account carries categorised expenses 29000+31000+15000 = 75000
      ;; (the 3300 uncategorised expense is surfaced as a count, not in net)
      (is (= "-75000.00" (get-in r [:net :cells "total"]))))))

(deftest columns-newest-first
  (testing "columns = total then months newest-first"
    (seed!)
    (let [r (cf/report (assoc base-req :group-by :category))]
      (is (= "total" (first (:columns r))))
      (let [months (rest (:columns r))]
        (is (= months (reverse (sort months))))))))
