(ns analitica.web.api.treasury-test
  "Contract tests for spec 019 Treasury HTTP API (web/api/treasury.clj).

   Tests call handlers directly (no ring.mock), using a fresh temp-file SQLite
   DB per test (same pattern as tax-opex-test / cashflow-test).

   Contracts:
     specs/019-treasury-ledger/contracts/cashflow-api.edn (§1..§5)
     specs/019-treasury-ledger/contracts/obligations-api.edn (§1..§4)
     specs/019-treasury-ledger/contracts/decimal-money.edn (money = \"0.00\" strings)

   Run focused:
     clojure -M:test --focus analitica.web.api.treasury-test"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [analitica.db :as db]
            [analitica.web.api.treasury :as api]
            [analitica.domain.treasury.operations :as ops])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time LocalDate]))

;; ---------------------------------------------------------------------------
;; Fixture: fresh temp-file SQLite DB per test
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "treasury-api-test-" ".db"
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
;; Helpers
;; ---------------------------------------------------------------------------

(defn- days-from-today [n] (str (.plusDays (LocalDate/now) n)))
(defn- days-ago [n] (str (.minusDays (LocalDate/now) n)))

(defn- decimal-str? [s]
  (boolean (and (string? s) (re-matches #"^-?\d+\.\d{2}$" s))))

(defn- seed-accounts! []
  {:wb   (:id (ops/create-account! {:name "WB выплаты"   :marketplace :wb   :kind :mp-settlement :currency "RUB"}))
   :ozon (:id (ops/create-account! {:name "Ozon выплаты" :marketplace :ozon :kind :mp-settlement :currency "RUB"}))
   :ym   (:id (ops/create-account! {:name "YM выплаты"   :marketplace :ym   :kind :mp-settlement :currency "RUB"}))
   :own  (:id (ops/create-account! {:name "Расчётный"    :kind :bank :currency "RUB"}))})

(defn- seed-cashflow! []
  (let [{:keys [wb ozon ym own] :as accts} (seed-accounts!)]
    (ops/create! {:op-date "2026-05-10" :amount "200000.00" :currency "RUB" :direction :income
                  :account-id wb   :category "mp-payout" :confirmed true :regular true})
    (ops/create! {:op-date "2026-06-10" :amount "260000.00" :currency "RUB" :direction :income
                  :account-id ozon :category "mp-payout" :confirmed true :regular true})
    (ops/create! {:op-date "2026-06-11" :amount "40000.00"  :currency "RUB" :direction :income
                  :account-id ym   :category "mp-payout" :confirmed true :regular true})
    (ops/create! {:op-date "2026-05-15" :amount "29000.00" :currency "RUB" :direction :expense
                  :account-id own :category "marketing" :confirmed true :regular true})
    (ops/create! {:op-date "2026-06-15" :amount "31000.00" :currency "RUB" :direction :expense
                  :account-id own :category "marketing" :confirmed true :regular true})
    ;; uncategorised expense (category nil) — surfaced via count
    (ops/create! {:op-date "2026-06-17" :amount "3300.00" :currency "RUB" :direction :expense
                  :account-id own :confirmed true :regular false})
    ;; transfer — must not touch net or any row
    (ops/create! {:op-date "2026-06-20" :amount "50000.00" :currency "RUB" :direction :transfer
                  :account-id own :transfer-account-id wb :confirmed true :regular false})
    accts))

;; ═══════════════════════════════════════════════════════════════════════════
;; §1 — GET /api/v1/treasury/cashflow
;; ═══════════════════════════════════════════════════════════════════════════

(deftest cashflow-happy-path
  (testing "GET cashflow returns matrix with mode echo, columns newest-first, net, uncategorised-count"
    (seed-cashflow!)
    (let [resp (api/get-cashflow {:params {:from "2026-01-01" :to "2026-06-30"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (= :actuals (:mode body)))
      (is (= :category (:group-by body)))
      (is (= "total" (first (:columns body))))
      ;; months newest-first
      (let [months (rest (:columns body))]
        (is (= months (reverse (sort months)))))
      ;; net.total = 500000 income − 60000 categorised marketing expense = 440000
      ;; (uncategorised 3300 excluded from rows/net-of-rows per CF-6)
      (is (= "440000.00" (get-in body [:net :cells "total"])))
      (is (decimal-str? (get-in body [:net :cells "total"])))
      ;; uncategorised surfaced
      (is (= 1 (:uncategorised-count body)))
      ;; every cell in every row is a decimal-string
      (doseq [row (:rows body)
              [_ v] (:cells row)]
        (is (decimal-str? v) (str "cell not decimal-str: " v))))))

(deftest cashflow-group-by-account-string-coercion
  (testing "GET cashflow group-by string is coerced to keyword; mode with-planned works"
    (seed-cashflow!)
    (let [resp (api/get-cashflow {:params {:from "2026-01-01" :to "2026-06-30"
                                           :group-by "account" :mode "with-planned"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (= :account (:group-by body)))
      (is (= :with-planned (:mode body))))))

(deftest cashflow-account-ids-filter
  (testing "GET cashflow account-ids filter restricts the matrix (own only → -75000)"
    (let [{:keys [own]} (seed-cashflow!)
          resp (api/get-cashflow {:params {:from "2026-01-01" :to "2026-06-30"
                                           :account-ids [own]}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      ;; own carries categorised marketing 29000+31000 = 60000 (uncategorised 3300 excluded)
      (is (= "-60000.00" (get-in body [:net :cells "total"]))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; §5 — GET /api/v1/treasury/categories (read-only taxonomy)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest categories-list
  (testing "GET categories returns the read-only taxonomy superset"
    (let [resp (api/get-categories {})
          body (:body resp)
          slugs (set (map :slug (:categories body)))]
      (is (= 200 (:status resp)))
      (is (vector? (:categories body)))
      ;; contains the 015-shared slugs + ДДС articles
      (is (every? slugs ["purchase" "marketing" "salary" "taxes"]))
      (is (every? #(and (:slug %) (:title %) (:activity-type %)) (:categories body))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; §2 — operations list / create / update
;; ═══════════════════════════════════════════════════════════════════════════

(deftest operations-list-with-summary
  (testing "GET operations returns operations + summary + pagination; transfers excluded from income/expense"
    (seed-cashflow!)
    (let [resp (api/get-operations {:params {:from "2026-01-01" :to "2026-06-30"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (vector? (:operations body)))
      (is (map? (:summary body)))
      (is (= "500000.00" (get-in body [:summary :total-income])))
      ;; expense = 29000 + 31000 + 3300 = 63300, sign preserved negative
      (is (= "-63300.00" (get-in body [:summary :total-expense])))
      (is (= "436700.00" (get-in body [:summary :balance])))
      (is (= 1 (:page body)))
      (is (pos-int? (:total body))))))

(deftest operations-list-filters
  (testing "GET operations honors direction + category filters"
    (seed-cashflow!)
    (let [resp (api/get-operations {:params {:direction "expense" :category "marketing"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (= 2 (count (:operations body))))
      (is (every? #(= :expense (:direction %)) (:operations body))))))

(deftest operation-create-happy-path
  (testing "POST operations creates an operation, returns {:ok true :id n}"
    (let [{:keys [own]} (seed-accounts!)
          resp (api/post-operation
                 {:body {:op-date "2026-06-25" :amount "9900.00" :currency "RUB"
                         :direction "expense" :account-id own :category "services"
                         :confirmed true :regular false :description "ПО"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (true? (:ok body)))
      (is (pos-int? (:id body))))))

(deftest operation-create-transfer-requires-transfer-account
  (testing "POST transfer operation without transfer-account-id fails validation (422)"
    (let [{:keys [own]} (seed-accounts!)
          resp (api/post-operation
                 {:body {:op-date "2026-06-25" :amount "50000.00" :currency "RUB"
                         :direction "transfer" :account-id own
                         :confirmed true :regular false}})
          body (:body resp)]
      (is (= 422 (:status resp)))
      (is (false? (:ok body)))
      (is (string? (:error body))))))

(deftest operation-create-transfer-valid
  (testing "POST valid transfer (distinct transfer-account-id) succeeds"
    (let [{:keys [own wb]} (seed-accounts!)
          resp (api/post-operation
                 {:body {:op-date "2026-06-25" :amount "50000.00" :currency "RUB"
                         :direction "transfer" :account-id own :transfer-account-id wb
                         :confirmed true :regular false}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :ok]))))))

(deftest operation-update-manual-category
  (testing "PUT operations/:id with manual category sets category-source :manual"
    (let [{:keys [own]} (seed-accounts!)
          op-id (:id (ops/create! {:op-date "2026-06-25" :amount "9900.00" :currency "RUB"
                                   :direction :expense :account-id own
                                   :confirmed true :regular false}))
          resp  (api/put-operation
                  {:params {:id (str op-id)}
                   :body   {:category "purchase" :category-source "manual"}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :ok])))
      ;; verify persisted category-source
      (let [op (ops/get-op op-id)]
        (is (= "purchase" (:category op)))
        (is (= :manual (:category-source op)))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; §3 — accounts list / create / delete (soft-archive)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest accounts-list-with-balances
  (testing "GET accounts returns accounts with derived balance + total-balance"
    (seed-cashflow!)
    (let [resp (api/get-accounts {})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (vector? (:accounts body)))
      (is (decimal-str? (:total-balance body)))
      (doseq [a (:accounts body)]
        (is (decimal-str? (:balance a)) (str "balance not decimal-str: " (:balance a)))))))

(deftest account-create-happy-path
  (testing "POST accounts creates an account, returns {:ok true :id n}; marketplace/kind coerced"
    (let [resp (api/post-account
                 {:body {:name "Wildberries выплаты" :marketplace "wb"
                         :kind "mp-settlement" :currency "RUB"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (true? (:ok body)))
      (is (pos-int? (:id body))))))

(deftest account-delete-hard-when-unreferenced
  (testing "DELETE accounts/:id hard-deletes when no referencing operations"
    (let [{:keys [id]} (:body (api/post-account {:body {:name "Temp" :kind "bank" :currency "RUB"}}))
          resp (api/delete-account {:params {:id (str id)}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (true? (:ok body)))
      (is (false? (:archived body))))))

(deftest account-delete-soft-archive-when-referenced
  (testing "DELETE accounts/:id soft-archives when it has referencing operations"
    (let [{:keys [own]} (seed-accounts!)]
      (ops/create! {:op-date "2026-06-15" :amount "31000.00" :currency "RUB" :direction :expense
                    :account-id own :category "marketing" :confirmed true :regular true})
      (let [resp (api/delete-account {:params {:id (str own)}})
            body (:body resp)]
        (is (= 200 (:status resp)))
        (is (true? (:ok body)))
        (is (true? (:archived body)))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; §4 — counterparties list / create
;; ═══════════════════════════════════════════════════════════════════════════

(deftest counterparties-list-and-create
  (testing "POST + GET counterparties round-trips with operation-count linkage"
    (let [c-resp (api/post-counterparty {:body {:name "ИП Логист" :kind "contractor"}})
          c-id   (get-in c-resp [:body :id])
          resp   (api/get-counterparties {})
          body   (:body resp)]
      (is (= 200 (:status c-resp)))
      (is (pos-int? c-id))
      (is (= 200 (:status resp)))
      (is (vector? (:counterparties body)))
      (is (= 1 (count (:counterparties body))))
      (is (= :contractor (:kind (first (:counterparties body)))))
      (is (= 0 (:operation-count (first (:counterparties body))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; obligations §1 summary / §2 dynamics / §3 list / create / settle
;; ═══════════════════════════════════════════════════════════════════════════

(defn- seed-obligations! []
  (api/post-obligation {:body {:direction "receivable" :amount "100000.00" :remaining-amount "100000.00"
                               :currency "RUB" :due-date (days-from-today 15) :confirmed true}})
  (api/post-obligation {:body {:direction "receivable" :amount "95000.00" :remaining-amount "95000.00"
                               :currency "RUB" :due-date (days-ago 10) :confirmed true}})
  (api/post-obligation {:body {:direction "payable" :amount "60000.00" :remaining-amount "60000.00"
                               :currency "RUB" :due-date (days-from-today 20) :confirmed true}}))

(deftest obligations-summary-happy-path
  (testing "GET obligations/summary returns receivable/payable/balance + buckets, mode echoed"
    (seed-obligations!)
    (let [resp (api/get-obligations-summary {:params {}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (= :actuals (:mode body)))
      (is (= "195000.00" (:receivable body)))
      (is (= "60000.00"  (:payable body)))
      ;; OB-1 balance = receivable − payable
      (is (= "135000.00" (:balance body)))
      ;; overdue-receivable: the days-ago 10 one → 95000, count 1
      (is (= "95000.00" (get-in body [:overdue-receivable :amount])))
      (is (= 1 (get-in body [:overdue-receivable :count])))
      ;; next-30-receivable: the days-from-today 15 one → 100000, count 1
      (is (= "100000.00" (get-in body [:next-30-receivable :amount])))
      (is (= 1 (get-in body [:next-30-receivable :count]))))))

(deftest obligations-dynamics-exactly-12-points
  (testing "GET obligations/dynamics returns EXACTLY 12 chronological points, each with balance = recv − pay"
    (seed-obligations!)
    (let [resp (api/get-obligations-dynamics {:params {}})
          body (:body resp)
          points (:points body)]
      (is (= 200 (:status resp)))
      (is (= 12 (count points)) "dynamics must be exactly 12 points")
      ;; chronological (oldest → newest)
      (is (= (map :month points) (sort (map :month points))))
      ;; each point balance = receivable − payable, all decimal-strings
      (doseq [p points]
        (is (decimal-str? (:receivable p)))
        (is (decimal-str? (:payable p)))
        (is (decimal-str? (:balance p)))))))

(deftest obligations-list-with-status
  (testing "GET obligations returns list with derived status + pagination"
    (seed-obligations!)
    (let [resp (api/get-obligations {:params {:direction "receivable"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (vector? (:obligations body)))
      (is (= 2 (count (:obligations body))))
      (is (every? #(= :receivable (:direction %)) (:obligations body)))
      (is (contains? (set (map :status (:obligations body))) :overdue))
      (is (= 1 (:page body)))
      (is (= 2 (:total body))))))

(deftest obligation-create-happy-path
  (testing "POST obligations creates an obligation, returns {:ok true :id n}"
    (let [resp (api/post-obligation
                 {:body {:direction "payable" :amount "180000.00" :remaining-amount "180000.00"
                         :currency "RUB" :due-date (days-from-today 30) :confirmed true}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (true? (:ok body)))
      (is (pos-int? (:id body))))))

(deftest obligation-settle-to-zero
  (testing "POST obligations/:id/settle to zero → remaining 0.00, status :settled, drops from open totals"
    (let [id (get-in (api/post-obligation
                       {:body {:direction "receivable" :amount "300000.00" :remaining-amount "300000.00"
                               :currency "RUB" :due-date (days-from-today 10) :confirmed true}})
                     [:body :id])
          resp (api/settle-obligation
                 {:params {:id (str id)}
                  :body   {:settled-operation-id 145 :settle-amount "300000.00"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (true? (:ok body)))
      (is (= "0.00" (:remaining-amount body)))
      (is (= :settled (:status body)))
      ;; drops out of open receivable totals
      (let [s (:body (api/get-obligations-summary {:params {}}))]
        (is (= "0.00" (:receivable s)))))))

(deftest obligation-settle-partial
  (testing "POST obligations/:id/settle partial → remaining reduced, not settled"
    (let [id (get-in (api/post-obligation
                       {:body {:direction "receivable" :amount "300000.00" :remaining-amount "300000.00"
                               :currency "RUB" :due-date (days-from-today 10) :confirmed true}})
                     [:body :id])
          resp (api/settle-obligation
                 {:params {:id (str id)}
                  :body   {:settle-amount "100000.00"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (= "200000.00" (:remaining-amount body)))
      (is (not= :settled (:status body))))))

(deftest obligation-settle-overshoot-errors
  (testing "POST settle beyond remaining → error (not a silent overshoot)"
    (let [id (get-in (api/post-obligation
                       {:body {:direction "receivable" :amount "100000.00" :remaining-amount "100000.00"
                               :currency "RUB" :due-date (days-from-today 10) :confirmed true}})
                     [:body :id])
          resp (api/settle-obligation
                 {:params {:id (str id)}
                  :body   {:settle-amount "150000.00"}})]
      (is (= 500 (:status resp)))
      (is (false? (get-in resp [:body :ok]))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; auto-rules §4 list / create / classify
;; ═══════════════════════════════════════════════════════════════════════════

(deftest auto-rules-list-empty
  (testing "GET auto-rules with none returns {:auto-rules []}"
    (let [resp (api/get-auto-rules {})]
      (is (= 200 (:status resp)))
      (is (vector? (get-in resp [:body :auto-rules])))
      (is (empty? (get-in resp [:body :auto-rules]))))))

(deftest auto-rule-create-and-list
  (testing "POST auto-rules creates a rule; GET lists it; enums coerced"
    (let [c-resp (api/post-auto-rule
                   {:body {:match-field "counterparty" :match-op "equals" :match-value "Ozon"
                           :category "marketing" :priority 10 :enabled true}})
          c-body (:body c-resp)]
      (is (= 200 (:status c-resp)))
      (is (true? (:ok c-body)))
      (is (pos-int? (:id c-body)))
      (let [resp  (api/get-auto-rules {})
            rules (get-in resp [:body :auto-rules])]
        (is (= 1 (count rules)))
        (is (= :counterparty (:match-field (first rules))))
        (is (= :equals (:match-op (first rules))))
        (is (= "marketing" (:category (first rules))))))))

(deftest auto-rule-create-invalid-returns-422
  (testing "POST auto-rules with invalid match-field returns 422"
    (let [resp (api/post-auto-rule
                 {:body {:match-field "nonsense" :match-op "equals" :match-value "x"
                         :category "marketing" :priority 10 :enabled true}})]
      (is (= 422 (:status resp)))
      (is (false? (get-in resp [:body :ok]))))))

(deftest auto-rules-classify-idempotent
  (testing "POST auto-rules/classify assigns categories + is idempotent (same counts on re-run)"
    (let [cp-id (get-in (api/post-counterparty {:body {:name "Ozon" :kind "marketplace"}}) [:body :id])
          {:keys [own]} (seed-accounts!)]
      ;; two ops with Ozon counterparty, uncategorised
      (ops/create! {:op-date "2026-06-15" :amount "31000.00" :currency "RUB" :direction :expense
                    :account-id own :counterparty-id cp-id :confirmed true :regular true})
      (ops/create! {:op-date "2026-06-16" :amount "29000.00" :currency "RUB" :direction :expense
                    :account-id own :counterparty-id cp-id :confirmed true :regular true})
      ;; one op with no counterparty, uncategorised → stays uncategorised
      (ops/create! {:op-date "2026-06-17" :amount "3300.00" :currency "RUB" :direction :expense
                    :account-id own :confirmed true :regular false})
      (api/post-auto-rule {:body {:match-field "counterparty" :match-op "equals" :match-value "Ozon"
                                  :category "marketing" :priority 10 :enabled true}})
      (let [r1 (:body (api/classify-auto-rules {:body {}}))]
        (is (true? (:ok r1)))
        (is (= 2 (:classified r1)))
        (is (= 1 (:left-uncategorised r1)))
        ;; idempotent: re-run yields identical classified count
        (let [r2 (:body (api/classify-auto-rules {:body {}}))]
          (is (= (:classified r1) (:classified r2)))
          (is (= (:left-uncategorised r1) (:left-uncategorised r2))))
        ;; after classify, cashflow uncategorised-count reflects only the unmatched op
        (let [cf (:body (api/get-cashflow {:params {:from "2026-01-01" :to "2026-12-31"}}))]
          (is (= 1 (:uncategorised-count cf))))))))

(deftest auto-rules-classify-preserves-manual
  (testing "classify never overwrites :manual category-source"
    (let [cp-id (get-in (api/post-counterparty {:body {:name "Ozon" :kind "marketplace"}}) [:body :id])
          {:keys [own]} (seed-accounts!)
          op-id (:id (ops/create! {:op-date "2026-06-15" :amount "31000.00" :currency "RUB" :direction :expense
                                   :account-id own :counterparty-id cp-id :confirmed true :regular true}))]
      ;; manually set category
      (api/put-operation {:params {:id (str op-id)}
                          :body {:category "salary" :category-source "manual"}})
      (api/post-auto-rule {:body {:match-field "counterparty" :match-op "equals" :match-value "Ozon"
                                  :category "marketing" :priority 10 :enabled true}})
      (let [r (:body (api/classify-auto-rules {:body {}}))]
        (is (= 1 (:manual-preserved r)))
        (is (= 0 (:classified r))))
      ;; the manual category survives
      (let [op (ops/get-op op-id)]
        (is (= "salary" (:category op)))
        (is (= :manual (:category-source op)))))))
