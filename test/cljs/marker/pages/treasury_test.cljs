(ns marker.pages.treasury-test
  "Node-tests for the Treasury page (spec 019 — T024/T034/T052).
   No DOM / UIx rendering — :node-test has no js/document; these tests
   exercise the PURE view-model layer the components render:
     - ДДС matrix (columns/rows/net/uncategorised badge) from a mock
     - decimal-as-string formatting (\"-186000.00\" → «−186 000 ₽»,
       precision-exact, NO parseFloat)
     - operations registry rows + summary (transfer shows both accounts)
     - obligations summary/status badges + the 12-point dynamics chart data."
  (:require [cljs.test :refer [deftest is testing]]
            [marker.pages.treasury :as t]
            [marker.util.format :as fmt]))

(def NBSP "\u00a0")
(def MINUS "\u2212")

;; ---------------------------------------------------------------------------
;; Mock ДДС response (::subs/treasury-cashflow shape)
;; ---------------------------------------------------------------------------

(def mock-cashflow
  {:mode                :actuals
   :group-by            :category
   :columns             ["total" "2026-06" "2026-05"]
   :rows
   [{:key   "marketing" :label "Реклама" :activity-type :operational
     :cells {"total" "-186000.00" "2026-06" "-100000.00" "2026-05" "-86000.00"}}
    {:key   "mp-payout" :label "Маркетплейс / выплаты" :activity-type :operational
     :cells {"total" "500000.00" "2026-06" "250000.00" "2026-05" "250000.00"}}]
   :net
   {:label "Чистый денежный поток"
    :cells {"total" "314000.00" "2026-06" "150000.00" "2026-05" "164000.00"}}
   :uncategorised-count 5})

;; ---------------------------------------------------------------------------
;; ДДС matrix — columns / rows / net / uncategorised badge (T024)
;; ---------------------------------------------------------------------------

(deftest cashflow-columns-render-newest-first
  (testing "column ids map to RU labels, total first"
    (is (= ["Итого" "Июн 2026" "Май 2026"]
           (mapv t/column-label (:columns mock-cashflow)))))
  (testing "unknown / passthrough column ids survive"
    (is (= "Итого" (t/column-label "total")))
    (is (= "Янв 2026" (t/column-label "2026-01")))
    (is (= "Дек 2025" (t/column-label "2025-12")))
    (is (= "garbage" (t/column-label "garbage")))))

(deftest cashflow-rows-have-a-cell-per-column
  (doseq [row (:rows mock-cashflow)
          col (:columns mock-cashflow)]
    (is (some? (t/cell-str row col))
        (str "row " (:key row) " missing cell for column " col))))

(deftest cashflow-net-line-cells
  (let [net (:net mock-cashflow)]
    (testing "net line carries a cell per column"
      (doseq [col (:columns mock-cashflow)]
        (is (some? (t/cell-str net col)))))
    (testing "net total renders as exact grouped rubles"
      (is (= (str "314" NBSP "000" NBSP "₽")
             (fmt/format-decimal-rub (t/cell-str net "total")))))))

(deftest cashflow-negative-cells-detected-on-the-string
  (is (true?  (t/negative-cell? "-186000.00")))
  (is (false? (t/negative-cell? "500000.00")))
  (is (false? (t/negative-cell? nil))))

(deftest cashflow-uncategorised-badge
  (is (= "Без категории: 5"
         (t/uncategorised-label (:uncategorised-count mock-cashflow))))
  (is (= "Без категории: 0" (t/uncategorised-label nil))))

;; ---------------------------------------------------------------------------
;; Decimal-as-string formatting — «-186000.00» → «−186 000 ₽», no parseFloat
;; ---------------------------------------------------------------------------

(deftest decimal-string-formats-without-precision-loss
  (testing "the T024 acceptance string: unicode minus + NBSP grouping"
    (is (= (str MINUS "186" NBSP "000" NBSP "₽")
           (fmt/format-decimal-rub "-186000.00"))))
  (testing "magnitude beyond 2^53 keeps every digit (parseFloat would not)"
    ;; js/parseFloat rounds 90071992547409929 → 90071992547409930
    (is (= (str "90" NBSP "071" NBSP "992" NBSP "547" NBSP "409" NBSP "929" NBSP "₽")
           (fmt/format-decimal-rub "90071992547409929.00"))))
  (testing "nil / blank → «—»"
    (is (= "—" (fmt/format-decimal-rub nil)))
    (is (= "—" (fmt/format-decimal-str "")))))

;; ---------------------------------------------------------------------------
;; Реестр — operations rows + summary (T034)
;; ---------------------------------------------------------------------------

(def mock-accounts
  [{:id 1 :name "Расчётный счёт" :kind :bank   :balance "150000.00"}
   {:id 2 :name "Кошелёк"        :kind :wallet :balance "20000.00"}])

(def mock-counterparties
  [{:id 7 :name "Ozon" :kind :marketplace :operation-count 12}])

(def mock-operations
  {:operations
   [{:id 100 :op-date "2026-06-20" :amount "50000.00" :direction :transfer
     :account-id 1 :transfer-account-id 2 :counterparty-id nil
     :category nil :confirmed true :regular false}
    {:id 101 :op-date "2026-06-21" :amount "120000.00" :direction :income
     :account-id 1 :counterparty-id 7 :category "mp-payout"
     :category-source :rule :confirmed true :regular true}
    {:id 102 :op-date "2026-06-22" :amount "80000.50" :direction :expense
     :account-id 2 :counterparty-id 7 :category "marketing"
     :category-source :manual :confirmed false :regular false}]
   :summary {:total-income  "120000.00"
             :total-expense "80000.50"
             :balance       "39999.50"
             :planned-count 1}
   :page 1 :page-size 20 :total 3})

(deftest operations-direction-labels
  (is (= "Приход"  (t/direction-label :income)))
  (is (= "Расход"  (t/direction-label :expense)))
  (is (= "Перевод" (t/direction-label :transfer)))
  (is (= "—"       (t/direction-label nil))))

(deftest transfer-operation-shows-both-accounts
  (let [transfer (first (:operations mock-operations))]
    (is (= "Расчётный счёт → Кошелёк"
           (t/operation-account-label transfer mock-accounts))))
  (testing "non-transfer shows the single account"
    (let [income (second (:operations mock-operations))]
      (is (= "Расчётный счёт"
             (t/operation-account-label income mock-accounts))))))

(deftest operations-summary-formats-from-decimal-strings
  (let [s (:summary mock-operations)]
    (is (= (str "120" NBSP "000" NBSP "₽") (fmt/format-decimal-rub (:total-income s))))
    ;; mock values end in .50 — display rounds HALF_UP (no truncation)
    (is (= (str "80"  NBSP "001" NBSP "₽") (fmt/format-decimal-rub (:total-expense s))))
    (is (= (str "40"  NBSP "000" NBSP "₽") (fmt/format-decimal-rub (:balance s))))
    (is (= "1" (fmt/format-int (:planned-count s))))))

(deftest operations-lookups
  (is (= "Ozon" (t/counterparty-name mock-counterparties 7)))
  (is (= "—"    (t/counterparty-name mock-counterparties nil)))
  (is (= "Контрагент #9" (t/counterparty-name mock-counterparties 9)))
  (is (= "Счёт #3" (t/account-name mock-accounts 3)))
  (is (= "Маркетплейс / выплаты" (t/category-title "mp-payout")))
  (is (= "—" (t/category-title nil))))

(deftest ops-filter-map-builds-from-ui-state
  (testing "blank selections are dropped; ids parsed; tri-states expanded"
    (is (= {:page 2 :page-size 20 :direction :expense :account-id 3
            :planned true :regular false}
           (t/build-ops-filters {:page 2 :page-size 20 :from "" :to ""
                                 :direction "expense" :account-id "3"
                                 :counterparty-id "" :category ""
                                 :status "planned" :regular "oneoff"}))))
  (testing "confirmed status and date range"
    (is (= {:page 1 :page-size 20 :from "2026-06-01" :to "2026-06-30"
            :confirmed true :regular true}
           (t/build-ops-filters {:from "2026-06-01" :to "2026-06-30"
                                 :status "confirmed" :regular "regular"}))))
  (testing "empty UI state → just pagination"
    (is (= {:page 1 :page-size 20} (t/build-ops-filters {})))))

(deftest amount-validation
  (is (true?  (t/valid-amount? "50000.00")))
  (is (true?  (t/valid-amount? "50000")))
  (is (true?  (t/valid-amount? "0.5")))
  (is (false? (t/valid-amount? "0.505")))   ; 3 fraction digits
  (is (false? (t/valid-amount? "-100.00"))) ; sign lives in :direction
  (is (false? (t/valid-amount? "12,50")))   ; RU comma is not the ledger format
  (is (false? (t/valid-amount? "")))
  (is (false? (t/valid-amount? nil))))

;; ---------------------------------------------------------------------------
;; Обязательства — status badges + summary + 12-point dynamics (T052)
;; ---------------------------------------------------------------------------

(deftest obligation-status-badges
  (is (= "badge badge-danger"  (:class (t/obligation-badge :overdue))))
  (is (= "badge badge-warning" (:class (t/obligation-badge :due-soon))))
  (is (= "badge badge-neutral" (:class (t/obligation-badge :settled))))
  (is (= "badge badge-info"    (:class (t/obligation-badge :open))))
  (testing "unknown status falls back to open"
    (is (= "badge badge-info" (:class (t/obligation-badge nil)))))
  (testing "RU labels"
    (is (= "Просрочено" (:label (t/obligation-badge :overdue))))
    (is (= "Скоро срок" (:label (t/obligation-badge :due-soon))))
    (is (= "Погашено"   (:label (t/obligation-badge :settled))))
    (is (= "Открыто"    (:label (t/obligation-badge :open))))))

(deftest obligations-summary-formats-buckets
  (let [summary {:receivable "742000.00" :payable "428000.00" :balance "314000.00"
                 :next-30-receivable {:amount "300000.00" :count 2}
                 :overdue-payable    {:amount "-128000.00" :count 1}}]
    (is (= (str "742" NBSP "000" NBSP "₽")
           (fmt/format-decimal-rub (:receivable summary))))
    (is (= (str "300" NBSP "000" NBSP "₽")
           (fmt/format-decimal-rub (:amount (:next-30-receivable summary)))))
    (is (= (str MINUS "128" NBSP "000" NBSP "₽")
           (fmt/format-decimal-rub (:amount (:overdue-payable summary)))))
    (is (false? (t/negative-cell? (:balance summary))))))

(def mock-dynamics-points
  (vec
   (for [[y m] [[2025 7] [2025 8] [2025 9] [2025 10] [2025 11] [2025 12]
                [2026 1] [2026 2] [2026 3] [2026 4] [2026 5] [2026 6]]]
     {:month      (str y "-" (if (< m 10) (str "0" m) m))
      :receivable "100000.00"
      :payable    "40000.00"
      :balance    "60000.00"})))

(deftest dynamics-has-exactly-12-points
  (is (true? (t/dynamics-12? mock-dynamics-points)))
  (is (false? (t/dynamics-12? (butlast mock-dynamics-points))))
  (is (false? (t/dynamics-12? []))))

(deftest dynamics-chart-config-shape
  (let [cfg      (t/dynamics-chart-config mock-dynamics-points)
        datasets (:datasets cfg)]
    (testing "12 RU month labels in chronological order"
      (is (= 12 (count (:labels cfg))))
      (is (= "Июл 2025" (first (:labels cfg))))
      (is (= "Июн 2026" (last  (:labels cfg)))))
    (testing "3 datasets — receivable / payable / balance — 12 points each"
      (is (= 3 (count datasets)))
      (doseq [ds datasets]
        (is (= 12 (count (:data ds))))
        (is (every? number? (:data ds)) (str (:label ds) " has non-numeric data"))))
    (testing "dataset values follow the mock decimals"
      (is (= 100000 (first (:data (nth datasets 0)))))
      (is (= 40000  (first (:data (nth datasets 1)))))
      (is (= 60000  (first (:data (nth datasets 2))))))))

;; ---------------------------------------------------------------------------
;; Page plumbing helpers
;; ---------------------------------------------------------------------------

(deftest cashflow-window-is-last-12-months
  (let [w (t/cashflow-window (js/Date. 2026 5 15))] ; 15 June 2026
    (is (= "2025-07-01" (:from w)))
    (is (= "2026-06-15" (:to w)))))

(deftest pagination-total-pages
  (is (= 3 (t/total-pages 45 20)))
  (is (= 1 (t/total-pages 0 20)))
  (is (= 1 (t/total-pages nil 20)))
  (is (= 2 (t/total-pages 21 20))))

(deftest api-error-prefix-match
  (let [errors {"/api/v1/treasury/cashflow?from=2025-07-01&to=2026-06-15"
                {:message "boom" :status 500}}]
    (is (= "boom" (t/api-error-for errors "/api/v1/treasury/cashflow")))
    (is (nil? (t/api-error-for errors "/api/v1/treasury/operations")))
    (is (nil? (t/api-error-for nil "/api/v1/treasury/cashflow")))))
