(ns analitica.web.api.charts-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.web.api.charts :as charts]
            [analitica.domain.sales :as sales]
            [analitica.domain.finance :as finance]
            [analitica.domain.pnl :as pnl]
            [analitica.db :as db]))

(use-fixtures :once
  (fn [f]
    ;; Initialize database before running tests
    (db/init!)
    (f)))

(deftest sales-chart-data-test
  (testing "sales-chart-data returns correct structure for single marketplace"
    (with-redefs [sales/fetch-sales (fn [_ & _] 
                                       [{:date "2026-04-01T10:00:00" :type :sale :for-pay 1000.0}
                                        {:date "2026-04-02T10:00:00" :type :sale :for-pay 1500.0}])
                  sales/by-day (fn [_] 
                                 [{:group "2026-04-01" :revenue 1000.0}
                                  {:group "2026-04-02" :revenue 1500.0}])]
      (let [result (charts/sales-chart-data :last-7-days :marketplace :wb)]
        (is (map? result))
        (is (contains? result :labels))
        (is (contains? result :datasets))
        (is (vector? (:labels result)))
        (is (vector? (:datasets result)))
        (is (= 2 (count (:labels result))))
        (is (= 1 (count (:datasets result))))
        (is (= "wb" (:label (first (:datasets result)))))
        (is (= [1000.0 1500.0] (:data (first (:datasets result))))))))

  (testing "sales-chart-data returns correct structure for all marketplaces"
    (with-redefs [sales/fetch-sales (fn [_ & {:keys [marketplace]}]
                                       (case marketplace
                                         :wb [{:date "2026-04-01T10:00:00" :type :sale :for-pay 1000.0}]
                                         :ozon [{:date "2026-04-01T10:00:00" :type :sale :for-pay 800.0}]
                                         :ym [{:date "2026-04-01T10:00:00" :type :sale :for-pay 600.0}]
                                         []))
                  sales/by-day (fn [data]
                                 (when (seq data)
                                   [{:group "2026-04-01" :revenue (:for-pay (first data))}]))]
      (let [result (charts/sales-chart-data :last-7-days)]
        (is (map? result))
        (is (contains? result :labels))
        (is (contains? result :datasets))
        (is (vector? (:labels result)))
        (is (vector? (:datasets result)))
        (is (= 1 (count (:labels result))))
        (is (= 3 (count (:datasets result))))
        (is (= #{"wb" "ozon" "ym"} (set (map :label (:datasets result)))))))))

(deftest ^:integration share-chart-data-test
  (testing "share-chart-data returns correct structure"
    (with-redefs [finance/fetch-finance (fn [_ & {:keys [marketplace]}]
                                           (case marketplace
                                             :wb [{:for-pay 10000.0}]
                                             :ozon [{:for-pay 8000.0}]
                                             :ym [{:for-pay 6000.0}]
                                             []))
                  pnl/calculate (fn [data & _]
                                  (if (seq data)
                                    {:revenue (:for-pay (first data))}
                                    {:revenue 0.0}))]
      (let [result (charts/share-chart-data :last-7-days)
            data   (get-in result [:datasets 0 :data])]
        (is (map? result))
        (is (contains? result :labels))
        (is (contains? result :datasets))
        (is (vector? (:labels result)))
        (is (vector? data))
        (is (= 3 (count (:labels result))))
        (is (= 3 (count data)))
        (is (= ["wb" "ozon" "ym"] (:labels result)))
        (is (= [10000.0 8000.0 6000.0] data))))))


(deftest report-chart-data-test
  (testing "report-chart-data for sales returns line chart structure"
    (with-redefs [sales/fetch-sales (fn [_ & _] 
                                       [{:date "2026-04-01T10:00:00" :type :sale :for-pay 1000.0}])
                  sales/by-day (fn [_] 
                                 [{:group "2026-04-01" :revenue 1000.0}])]
      (let [result (charts/report-chart-data :sales :last-7-days :marketplace :wb)]
        (is (map? result))
        (is (contains? result :labels))
        (is (contains? result :datasets))
        (is (= ["2026-04-01"] (:labels result)))
        (is (= 1 (count (:datasets result))))
        (is (= "Выручка" (:label (first (:datasets result))))))))

  (testing "report-chart-data for finance returns stacked bar chart structure"
    (with-redefs [finance/fetch-finance (fn [_ & _] [])
                  finance/by-article (fn [_] 
                                       [{:article "ART-001" :revenue 5000.0 :wb-reward 500.0 
                                         :logistics 300.0 :storage 100.0}])]
      (let [result (charts/report-chart-data :finance :last-7-days :marketplace :wb)]
        (is (map? result))
        (is (contains? result :labels))
        (is (contains? result :datasets))
        (is (= ["ART-001"] (:labels result)))
        (is (= 4 (count (:datasets result))))
        (is (= #{"Выручка" "Комиссия" "Логистика" "Хранение"} 
               (set (map :label (:datasets result))))))))

  (testing "report-chart-data for pnl returns waterfall chart structure"
    (with-redefs [finance/fetch-finance (fn [_ & _] [])
                  pnl/calculate (fn [_ & _]
                                  {:revenue 10000.0 :wb-reward 1000.0 :logistics 500.0
                                   :storage 200.0 :cogs 3000.0 :ad-spend 500.0
                                   :mp-commission -1500.0
                                   :net-profit 4800.0})]
      (let [result (charts/report-chart-data :pnl :last-7-days :marketplace :wb)]
        (is (map? result))
        (is (contains? result :labels))
        (is (contains? result :datasets))
        (is (= 7 (count (:labels result))))
        (is (= 1 (count (:datasets result))))
        (is (= "P&L" (:label (first (:datasets result)))))
        (is (= 7 (count (:data (first (:datasets result))))))
        ;; canon F-1: «Комиссия МП» bar = :mp-commission (already negative),
        ;; NOT −:wb-reward (PVZ reimbursement) — they differ in the stub.
        (is (= -1500.0 (second (:data (first (:datasets result)))))))))

  (testing "report-chart-data for stock returns bar chart structure"
    ;; Stock chart requires actual domain functions, so we test structure only
    (let [result (charts/report-chart-data :stock :last-7-days :marketplace :wb)]
      (is (map? result))
      (is (contains? result :labels))
      (is (contains? result :datasets))
      (is (vector? (:labels result)))
      (is (vector? (:datasets result)))
      (when (seq (:datasets result))
        (is (= "Остатки" (:label (first (:datasets result))))))))

  (testing "report-chart-data for returns returns line chart structure"
    (with-redefs [sales/fetch-sales (fn [_ & _] [])
                  sales/by-day (fn [_] 
                                 [{:group "2026-04-01" :sales-count 10 :returns-count 2}
                                  {:group "2026-04-02" :sales-count 15 :returns-count 3}])]
      (let [result (charts/report-chart-data :returns :last-7-days :marketplace :wb)]
        (is (map? result))
        (is (contains? result :labels))
        (is (contains? result :datasets))
        (is (= ["2026-04-01" "2026-04-02"] (:labels result)))
        (is (= 1 (count (:datasets result))))
        (is (= "% возвратов" (:label (first (:datasets result)))))
        ;; Check return rate calculation: 2/(10+2) = 16.67%, 3/(15+3) = 16.67%
        (is (every? number? (:data (first (:datasets result))))))))

  (testing "report-chart-data for unknown type returns empty chart"
    (let [result (charts/report-chart-data :unknown :last-7-days)]
      (is (map? result))
      (is (contains? result :labels))
      (is (contains? result :datasets))
      (is (empty? (:labels result)))
      (is (empty? (:datasets result))))))

;; ---------------------------------------------------------------------------
;; LT4 — trends chart threads period + marketplace (BUG A fix)
;; ---------------------------------------------------------------------------

(deftest trends-chart-threads-period-and-mp
  "The :trends chart case must pass period + :marketplace into trends/wow.
   Before LT4, charts.clj:254-255 called (trends/wow) with NO args — period
   and marketplace were silently ignored. This test verifies the args reach
   the domain function."
  (let [calls   (atom [])
        period  {:from "2026-06-01" :to "2026-06-07"}
        ws-var  (resolve 'analitica.domain.trends/weekly-sales)]
    (with-redefs-fn {ws-var (fn [from to & {:keys [marketplace]}]
                               (swap! calls conj {:from from :to to :mp marketplace})
                               [])}
      (fn []
        (charts/report-chart-data :trends period :marketplace :ozon)
        (testing "weekly-sales was called (trends path executed)"
          (is (pos? (count @calls))))
        (testing "period from=2026-06-01 was forwarded to weekly-sales"
          (is (some #(= "2026-06-01" (:from %)) @calls)))
        (testing "marketplace :ozon was forwarded to weekly-sales"
          (is (some #(= :ozon (:mp %)) @calls)))))))

(deftest trends-chart-differs-by-mp
  "Calling :trends chart for :wb vs :ozon must produce different marketplace
   args to weekly-sales — the chart must NOT be byte-identical across MPs."
  (let [wb-calls   (atom [])
        ozon-calls (atom [])
        ws-var     (resolve 'analitica.domain.trends/weekly-sales)]
    (with-redefs-fn {ws-var (fn [_from _to & {:keys [marketplace]}]
                               ;; route to the right atom by mp
                               (when (= :wb   marketplace) (swap! wb-calls   conj marketplace))
                               (when (= :ozon marketplace) (swap! ozon-calls conj marketplace))
                               [])}
      (fn []
        (charts/report-chart-data :trends {:from "2026-06-01" :to "2026-06-07"} :marketplace :wb)
        (charts/report-chart-data :trends {:from "2026-06-01" :to "2026-06-07"} :marketplace :ozon)
        (testing "WB call routed :wb marketplace to weekly-sales"
          (is (pos? (count @wb-calls))))
        (testing "Ozon call routed :ozon marketplace to weekly-sales"
          (is (pos? (count @ozon-calls))))))))
