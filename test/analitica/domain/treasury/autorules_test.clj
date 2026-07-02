(ns analitica.domain.treasury.autorules-test
  "US3 — auto-rules classification (spec 019, T036-T039).
   Contracts: specs/019-treasury-ledger/contracts/obligations-api.edn §4,
   ledger-entities.edn §5. SC-004: idempotent + manual-override-wins + deterministic precedence.
   Comparison is kopeck-exact where money is involved; NEVER Math/abs < ε."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.db :as db]
            [analitica.util.math :as m]
            [analitica.domain.treasury.operations :as ops]
            [analitica.domain.treasury.cashflow :as cf]
            [analitica.domain.treasury.autorules :as ar])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp-file SQLite fixture
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-treasury-ar-" ".db"
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
;; Fixtures helpers
;; ---------------------------------------------------------------------------

(defn- make-account! []
  (:id (ops/create-account! {:name "Расчётный" :kind :bank :currency "RUB"})))

(defn- make-cp! [name]
  (:id (ops/create-counterparty! {:name name :kind :contractor})))

;; ---------------------------------------------------------------------------
;; T036 — AR-1 idempotency (FR-011/SC-004)
;; ---------------------------------------------------------------------------

(deftest ar-1-idempotency
  (testing "AR-1: classify! twice yields identical category assignments (idempotent)"
    (let [acc (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))
          cp  (make-cp! "Яндекс Маркет")]
      ;; rule: match description contains "маркет" → marketing
      (ar/create! {:match-field :description :match-op :contains :match-value "маркет"
                   :category "marketing" :priority 10 :enabled true})
      ;; 3 ops: 2 matching, 1 non-matching
      (ops/create! {:op-date "2026-06-01" :amount "5000.00" :currency "RUB"
                    :direction :expense :account-id acc :description "Оплата Яндекс маркет"
                    :confirmed true :regular false})
      (ops/create! {:op-date "2026-06-02" :amount "7000.00" :currency "RUB"
                    :direction :expense :account-id acc :description "Реклама на маркет"
                    :confirmed true :regular false})
      (ops/create! {:op-date "2026-06-03" :amount "3000.00" :currency "RUB"
                    :direction :expense :account-id acc :description "Аренда склада"
                    :confirmed true :regular false})
      ;; first run
      (let [r1 (ar/classify!)
            ;; capture categories after first run
            cats-after-1 (into {} (map (fn [op] [(:id op) (:category op)])
                                       (:operations (ops/list-ops {}))))
            ;; second run
            r2 (ar/classify!)
            cats-after-2 (into {} (map (fn [op] [(:id op) (:category op)])
                                       (:operations (ops/list-ops {}))))]
        (is (= (:classified r1) (:classified r2))
            "AR-1: classified count should be deterministic across runs")
        (is (= cats-after-1 cats-after-2)
            "AR-1: category assignments must be identical after two runs")
        (is (= 2 (:classified r1))
            "AR-1: 2 ops match the description pattern")
        (is (= 1 (:left-uncategorised r1))
            "AR-1: 1 op remains uncategorised (no rule matches)")))))

;; ---------------------------------------------------------------------------
;; T037 — AR-2 manual-override-wins (FR-012/SC-004)
;; ---------------------------------------------------------------------------

(deftest ar-2-manual-override-wins
  (testing "AR-2: :category-source :manual NEVER overwritten by classify!"
    (let [acc (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))]
      ;; rule that would match the description
      (ar/create! {:match-field :description :match-op :contains :match-value "логист"
                   :category "logistics" :priority 10 :enabled true})
      ;; op with manual category already set (should never be touched)
      (let [{:keys [id]} (ops/create! {:op-date "2026-06-01" :amount "9000.00" :currency "RUB"
                                       :direction :expense :account-id acc
                                       :description "Логистика"
                                       :category "rent"           ; manual override
                                       :category-source :manual
                                       :confirmed true :regular false})]
        (let [r (ar/classify!)]
          ;; manual-preserved must include this op
          (is (pos? (:manual-preserved r))
              "AR-2: manual-preserved must be positive")
          ;; the op must still have :rent category and :manual source
          (let [op (ops/get-op id)]
            (is (= "rent" (:category op))
                "AR-2: manual category must NOT be overwritten by the rule")
            (is (= :manual (:category-source op))
                "AR-2: category-source must remain :manual after classify!")))
        ;; running again still leaves it alone
        (ar/classify!)
        (let [op (ops/get-op id)]
          (is (= "rent" (:category op))
              "AR-2: still :rent after second classify!")
          (is (= :manual (:category-source op))))))))

;; ---------------------------------------------------------------------------
;; T038 — AR-3 precedence: (priority ASC, id ASC) (FR-012, no double-classify)
;; ---------------------------------------------------------------------------

(deftest ar-3-precedence-deterministic
  (testing "AR-3: overlapping rules — only the (lowest priority, then lowest id) wins"
    (let [acc (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))]
      ;; Two rules both matching the same op:
      ;;   rule-1: priority 10, category "marketing"
      ;;   rule-2: priority 50, category "services"
      ;; rule-1 wins (lower priority number = higher precedence)
      (let [r1-id (:id (ar/create! {:match-field :description :match-op :contains
                                    :match-value "реклам"
                                    :category "marketing" :priority 10 :enabled true}))
            r2-id (:id (ar/create! {:match-field :description :match-op :contains
                                    :match-value "реклам"
                                    :category "services" :priority 50 :enabled true}))]
        (ops/create! {:op-date "2026-06-10" :amount "4000.00" :currency "RUB"
                      :direction :expense :account-id acc
                      :description "Платёж за рекламу"
                      :confirmed true :regular false})
        (ar/classify!)
        (let [ops-all (:operations (ops/list-ops {}))
              target  (first ops-all)]
          (is (= "marketing" (:category target))
              "AR-3: lower-priority-number rule wins (marketing, priority 10)")
          (is (= :rule (:category-source target))
              "AR-3: category-source is :rule")
          (is (= r1-id (:applied-rule-id target))
              "AR-3: applied-rule-id points to the winning rule"))))))

(deftest ar-3-tiebreak-by-id
  (testing "AR-3: same priority → lower id wins (deterministic tiebreak)"
    (let [acc (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))]
      ;; two rules with same priority
      (let [first-id  (:id (ar/create! {:match-field :description :match-op :contains
                                        :match-value "тест"
                                        :category "salary" :priority 20 :enabled true}))
            second-id (:id (ar/create! {:match-field :description :match-op :contains
                                        :match-value "тест"
                                        :category "taxes" :priority 20 :enabled true}))]
        (ops/create! {:op-date "2026-06-11" :amount "1000.00" :currency "RUB"
                      :direction :expense :account-id acc
                      :description "тест платёж"
                      :confirmed true :regular false})
        (ar/classify!)
        (let [op (first (:operations (ops/list-ops {})))]
          (is (= "salary" (:category op))
              "AR-3: lower id wins on priority tie → salary")
          (is (= first-id (:applied-rule-id op))
              "AR-3: applied-rule-id is the first (lower id) rule"))))))

;; ---------------------------------------------------------------------------
;; T039 — AR-4 uncategorised-count integration with ДДС cashflow (US3 AC4)
;; ---------------------------------------------------------------------------

(deftest ar-4-uncategorised-count-decreases
  (testing "AR-4: after classify!, cashflow uncategorised-count = |ops with no matching rule|"
    (let [acc (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))]
      ;; 3 ops: 2 will get a category from the rule, 1 will stay uncategorised
      (ops/create! {:op-date "2026-06-01" :amount "2000.00" :currency "RUB"
                    :direction :expense :account-id acc
                    :description "Реклама кампания 1"
                    :confirmed true :regular false})
      (ops/create! {:op-date "2026-06-02" :amount "3000.00" :currency "RUB"
                    :direction :expense :account-id acc
                    :description "Реклама кампания 2"
                    :confirmed true :regular false})
      (ops/create! {:op-date "2026-06-03" :amount "1000.00" :currency "RUB"
                    :direction :expense :account-id acc
                    :description "Прочий платёж"
                    :confirmed true :regular false})
      ;; before classify: all 3 ops have no category → uncategorised-count = 3
      (let [r-before (cf/report {:from "2026-01-01" :to "2026-12-31" :mode :actuals})]
        (is (= 3 (:uncategorised-count r-before))
            "AR-4: before classify all ops are uncategorised"))
      ;; add rule matching 2 of them
      (ar/create! {:match-field :description :match-op :contains :match-value "Реклама"
                   :category "marketing" :priority 10 :enabled true})
      ;; run classify
      (let [r (ar/classify!)]
        (is (= 2 (:classified r))     "AR-4: 2 ops classified")
        (is (= 1 (:left-uncategorised r)) "AR-4: 1 op left uncategorised")
        ;; ДДС uncategorised-count must now reflect only the 1 remaining
        (let [r-after (cf/report {:from "2026-01-01" :to "2026-12-31" :mode :actuals})]
          (is (= 1 (:uncategorised-count r-after))
              "AR-4: cashflow uncategorised-count decreased to 1 after classify!"))))))

;; ---------------------------------------------------------------------------
;; list + disabled rule
;; ---------------------------------------------------------------------------

(deftest disabled-rule-ignored
  (testing "disabled rules are not applied during classify!"
    (let [acc (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))]
      (ar/create! {:match-field :description :match-op :contains :match-value "тест"
                   :category "marketing" :priority 10 :enabled false})
      (ops/create! {:op-date "2026-06-01" :amount "1000.00" :currency "RUB"
                    :direction :expense :account-id acc
                    :description "тест платёж"
                    :confirmed true :regular false})
      (let [r (ar/classify!)]
        (is (= 0 (:classified r))        "disabled rule must not classify")
        (is (= 1 (:left-uncategorised r)) "op remains uncategorised"))))  )

(deftest list-rules
  (testing "list returns all created rules"
    (ar/create! {:match-field :counterparty :match-op :equals :match-value "Ozon"
                 :category "mp-payout" :priority 5 :enabled true})
    (ar/create! {:match-field :description :match-op :contains :match-value "налог"
                 :category "taxes" :priority 20 :enabled true})
    (let [{:keys [auto-rules]} (ar/list-rules)]
      (is (= 2 (count auto-rules)))
      (is (every? :id auto-rules))
      (is (every? :match-field auto-rules)))))

(deftest rule-classifies-by-counterparty
  (testing "match-field :counterparty matches by counterparty name"
    (let [acc (:id (ops/create-account! {:name "A" :kind :bank :currency "RUB"}))
          cp  (:id (ops/create-counterparty! {:name "Ozon" :kind :marketplace}))]
      (ar/create! {:match-field :counterparty :match-op :equals :match-value "Ozon"
                   :category "mp-payout" :priority 10 :enabled true})
      (let [op-id (:id (ops/create! {:op-date "2026-06-01" :amount "10000.00" :currency "RUB"
                                     :direction :income :account-id acc :counterparty-id cp
                                     :confirmed true :regular false}))]
        (ar/classify!)
        (let [op (ops/get-op op-id)]
          (is (= "mp-payout" (:category op)))
          (is (= :rule (:category-source op))))))))
