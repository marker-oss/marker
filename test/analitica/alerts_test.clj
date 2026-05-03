(ns analitica.alerts-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.alerts :as alerts]))

;; ---------------------------------------------------------------------------
;; Test data helpers
;; ---------------------------------------------------------------------------

(defn- stock-row [article name days-of-cover avg-daily-sales]
  {:article         article
   :name            name
   :days-of-cover   days-of-cover
   :avg-daily-sales avg-daily-sales})

(defn- sales-row [article revenue day]
  {:article article
   :revenue revenue
   :day     day})

(defn- pnl-map [revenue net-profit]
  {:revenue revenue :net-profit net-profit})

(defn- buyout-row [article buyout-rate ordered]
  {:article     article
   :buyout-rate buyout-rate
   :ordered     ordered})

;; ---------------------------------------------------------------------------
;; 2.1 Rule: TOP_MOVER
;; ---------------------------------------------------------------------------

(deftest test-top-mover-fires-on-growth
  (testing "TOP_MOVER fires when revenue grows > 30% vs prev period"
    (let [curr [{:article "art-1" :group "art-1" :revenue 1300.0}
                {:article "art-2" :group "art-2" :revenue 500.0}]
          prev [{:article "art-1" :group "art-1" :revenue 900.0}
                {:article "art-2" :group "art-2" :revenue 500.0}]
          data {:current-sales-by-article curr
                :prev-sales-by-article    prev
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           []
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (some #(= :TOP_MOVER (:rule %)) alerts)
          "Should fire TOP_MOVER for article with >30% revenue growth"))))

(deftest test-top-mover-no-fire-below-threshold
  (testing "TOP_MOVER does NOT fire when growth is <= 30%"
    (let [curr [{:article "art-1" :group "art-1" :revenue 1200.0}]
          prev [{:article "art-1" :group "art-1" :revenue 1000.0}]
          data {:current-sales-by-article curr
                :prev-sales-by-article    prev
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           []
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (not (some #(= :TOP_MOVER (:rule %)) alerts))
          "Should NOT fire TOP_MOVER when growth is exactly 20%"))))

(deftest test-top-mover-no-fire-when-prev-zero
  (testing "TOP_MOVER does NOT fire when prev revenue is 0 (avoid division by zero)"
    (let [curr [{:article "art-1" :group "art-1" :revenue 1000.0}]
          prev []
          data {:current-sales-by-article curr
                :prev-sales-by-article    prev
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           []
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (not (some #(= :TOP_MOVER (:rule %)) alerts))
          "Should NOT fire TOP_MOVER when prev revenue is 0"))))

;; ---------------------------------------------------------------------------
;; Rule: RETURNS_SPIKE
;; ---------------------------------------------------------------------------

(deftest test-returns-spike-fires-on-low-buyout
  (testing "RETURNS_SPIKE fires when buyout rate < 70%"
    (let [buyout-data [{:article "art-1" :buyout-rate 62.0 :ordered 100}
                       {:article "art-2" :buyout-rate 85.0 :ordered 50}]
          data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           buyout-data
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (some #(= :RETURNS_SPIKE (:rule %)) alerts)
          "Should fire RETURNS_SPIKE for articles with buyout-rate < 70%"))))

(deftest test-returns-spike-no-fire-on-normal-buyout
  (testing "RETURNS_SPIKE does NOT fire when buyout rate >= 80%"
    (let [buyout-data [{:article "art-1" :buyout-rate 82.0 :ordered 100}
                       {:article "art-2" :buyout-rate 95.0 :ordered 50}]
          data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           buyout-data
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (not (some #(= :RETURNS_SPIKE (:rule %)) alerts))
          "Should NOT fire RETURNS_SPIKE when all buyout rates >= 80%"))))

(deftest test-returns-spike-ignores-nil-buyout
  (testing "RETURNS_SPIKE does NOT throw for nil buyout-rate rows"
    (let [buyout-data [{:article "art-1" :buyout-rate nil :ordered 0}]
          data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           buyout-data
                :sales-last-3-days        []
                :top-10-by-revenue        []}]
      (is (coll? (alerts/detect-alerts data))
          "Should return a collection even with nil buyout rates"))))

;; ---------------------------------------------------------------------------
;; Rule: OUT_OF_STOCK
;; ---------------------------------------------------------------------------

(deftest test-out-of-stock-fires-on-low-cover
  (testing "OUT_OF_STOCK fires when days-of-cover < 7 AND avg-daily-sales >= 1"
    (let [stocks [{:article "art-1" :name "Product A" :size "M"
                   :days-of-cover 4.0 :avg-daily-sales 3.0}]
          data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     stocks
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           []
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (some #(= :OUT_OF_STOCK (:rule %)) alerts)
          "Should fire OUT_OF_STOCK for article with days-of-cover=4 and avg-daily-sales=3"))))

(deftest test-out-of-stock-no-fire-on-sufficient-cover
  (testing "OUT_OF_STOCK does NOT fire when days-of-cover >= 7"
    (let [stocks [{:article "art-1" :name "Product A" :size "M"
                   :days-of-cover 10.0 :avg-daily-sales 3.0}]
          data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     stocks
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           []
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (not (some #(= :OUT_OF_STOCK (:rule %)) alerts))
          "Should NOT fire OUT_OF_STOCK when days-of-cover >= 7"))))

(deftest test-out-of-stock-no-fire-when-no-sales
  (testing "OUT_OF_STOCK does NOT fire when avg-daily-sales < 1 (slow movers)"
    (let [stocks [{:article "art-1" :name "Product A" :size "M"
                   :days-of-cover 2.0 :avg-daily-sales 0.1}]
          data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     stocks
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           []
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (not (some #(= :OUT_OF_STOCK (:rule %)) alerts))
          "Should NOT fire OUT_OF_STOCK for slow movers (avg-daily-sales < 1)"))))

;; Bug #8 regression — production data path uses :days-left / :daily-rate
;; (the keys emitted by stock/with-turnover) not the :days-of-cover /
;; :avg-daily-sales pair the existing tests use. Without an explicit
;; assertion on the production shape, dead destructuring in the rule
;; could silently regress.

(deftest test-out-of-stock-fires-on-with-turnover-shape
  (testing "Rule must accept :days-left / :daily-rate (stock/with-turnover output)"
    (let [stocks [{:article "art-1" :name "Product A" :size "M"
                   :days-left 3.0 :daily-rate 4.0}]
          data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     stocks
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           []
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (some #(= :OUT_OF_STOCK (:rule %)) alerts)
          "Production stock-with-turnover row must trigger OUT_OF_STOCK"))))

;; ---------------------------------------------------------------------------
;; Rule: ZERO_SALES_TOP_SKU
;; ---------------------------------------------------------------------------

(deftest test-zero-sales-top-sku-fires
  (testing "ZERO_SALES_TOP_SKU fires when top-10 article has 0 sales in last 3 days"
    (let [top-10 [{:article "art-top" :rank 1 :name "Top Product" :revenue 100000}
                  {:article "art-ok"  :rank 2 :name "OK Product"  :revenue 80000}]
          ;; art-top has no sales; art-ok has sales
          last3  [{:article "art-ok" :revenue 1000 :day "2026-04-24"}]
          data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           []
                :sales-last-3-days        last3
                :top-10-by-revenue        top-10}
          alerts (alerts/detect-alerts data)]
      (is (some #(= :ZERO_SALES_TOP_SKU (:rule %)) alerts)
          "Should fire ZERO_SALES_TOP_SKU for top article with 0 sales last 3 days"))))

(deftest test-zero-sales-top-sku-no-fire-when-active
  (testing "ZERO_SALES_TOP_SKU does NOT fire when top articles have recent sales"
    (let [top-10 [{:article "art-top" :rank 1 :name "Top Product" :revenue 100000}]
          last3  [{:article "art-top" :revenue 1200 :day "2026-04-24"}]
          data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           []
                :sales-last-3-days        last3
                :top-10-by-revenue        top-10}
          alerts (alerts/detect-alerts data)]
      (is (not (some #(= :ZERO_SALES_TOP_SKU (:rule %)) alerts))
          "Should NOT fire ZERO_SALES_TOP_SKU when article has recent sales"))))

;; ---------------------------------------------------------------------------
;; Rule: MARGIN_DROP
;; ---------------------------------------------------------------------------

(deftest test-margin-drop-fires-on-large-drop
  (testing "MARGIN_DROP fires when margin drops > 15% absolute WoW"
    ;; prev: 30% margin, curr: 12% margin — drop of 18 ppts
    (let [data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 100000 12000)
                :prev-pnl                 (pnl-map 100000 30000)
                :current-buyout           []
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (some #(= :MARGIN_DROP (:rule %)) alerts)
          "Should fire MARGIN_DROP when margin dropped 18 ppts (from 30% to 12%)"))))

(deftest test-margin-drop-no-fire-on-small-drop
  (testing "MARGIN_DROP does NOT fire when margin drop <= 15%"
    ;; prev: 30% margin, curr: 20% margin — drop of 10 ppts (below 15% threshold)
    (let [data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 100000 20000)
                :prev-pnl                 (pnl-map 100000 30000)
                :current-buyout           []
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (not (some #(= :MARGIN_DROP (:rule %)) alerts))
          "Should NOT fire MARGIN_DROP when drop is only 10 ppts"))))

(defn- margin-drop-data [curr-pnl prev-pnl]
  {:current-sales-by-article []
   :prev-sales-by-article    []
   :stocks-with-turnover     []
   :current-pnl              curr-pnl
   :prev-pnl                 prev-pnl
   :current-buyout           []
   :sales-last-3-days        []
   :top-10-by-revenue        []})

(deftest test-margin-drop-no-throw-on-zero-revenue
  (testing "MARGIN_DROP does NOT throw when both periods have zero revenue"
    (is (coll? (alerts/detect-alerts
                 (margin-drop-data (pnl-map 0 0) (pnl-map 0 0)))))))

;; Bug #6: revenue collapse to 0 used to silence MARGIN_DROP entirely
;; (safe-margin returned nil → rule guard skipped). Pin the new behaviour:
;; treat curr-margin as 0 when revenue collapsed from positive to zero.

(deftest test-margin-drop-fires-on-revenue-collapse
  (testing "Revenue 100k @ 30% margin → 0 revenue must fire MARGIN_DROP"
    (let [alerts (alerts/detect-alerts
                   (margin-drop-data (pnl-map 0 -2000)
                                     (pnl-map 100000 30000)))]
      (is (some #(= :MARGIN_DROP (:rule %)) alerts)
          "Catastrophic revenue loss is the WORST possible margin drop and must alert"))))

(deftest test-margin-drop-no-fire-when-prev-margin-was-tiny
  (testing "Revenue collapse from low-margin period → drop = prev-margin (e.g. 5%) below threshold"
    (let [alerts (alerts/detect-alerts
                   (margin-drop-data (pnl-map 0 -1000)
                                     (pnl-map 100000 5000)))]
      (is (not (some #(= :MARGIN_DROP (:rule %)) alerts))
          "5% prev margin → 5 ppt 'drop' is below 15% threshold"))))

(deftest test-margin-drop-no-fire-when-prev-also-zero
  (testing "If revenue was 0 in prev period too there is no signal — skip"
    (let [alerts (alerts/detect-alerts
                   (margin-drop-data (pnl-map 0 0) (pnl-map 0 0)))]
      (is (not (some #(= :MARGIN_DROP (:rule %)) alerts))))))

;; ---------------------------------------------------------------------------
;; Aggregator: detect-alerts cap + sort
;; ---------------------------------------------------------------------------

(deftest test-detect-alerts-caps-at-5
  (testing "detect-alerts returns at most 5 alerts even when many fire"
    ;; Build data that triggers many alerts
    (let [many-stocks (into [] (for [i (range 20)]
                                 {:article (str "art-" i) :name (str "Product " i)
                                  :size "M" :days-of-cover 2.0 :avg-daily-sales 5.0}))
          many-buyout (into [] (for [i (range 20)]
                                 {:article (str "art-" i) :buyout-rate 50.0 :ordered 100}))
          many-top-10 (into [] (for [i (range 10)]
                                 {:article (str "art-top-" i) :rank (inc i)
                                  :name (str "Top " i) :revenue (* (- 10 i) 10000)}))
          data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     many-stocks
                :current-pnl              (pnl-map 100000 5000)
                :prev-pnl                 (pnl-map 100000 40000)
                :current-buyout           many-buyout
                :sales-last-3-days        []
                :top-10-by-revenue        many-top-10}
          alerts (alerts/detect-alerts data)]
      (is (<= (count alerts) 5)
          "detect-alerts should cap output at 5 alerts"))))

(deftest test-detect-alerts-sorts-by-severity
  (testing "detect-alerts puts RED alerts before YELLOW before GREEN"
    (let [stocks [{:article "art-1" :name "Product A" :size "M"
                   :days-of-cover 3.0 :avg-daily-sales 5.0}]
          buyout [{:article "art-2" :buyout-rate 62.0 :ordered 100}]
          curr   [{:article "art-3" :group "art-3" :revenue 2000.0}]
          prev   [{:article "art-3" :group "art-3" :revenue 1000.0}]
          data {:current-sales-by-article curr
                :prev-sales-by-article    prev
                :stocks-with-turnover     stocks
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           buyout
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)
          severities (map :severity alerts)]
      ;; All red come before yellow, yellow before green
      (is (every? some? alerts))
      (is (= severities (sort-by #(case % :red 1 :yellow 2 :green 3) severities))
          "Alerts should be sorted: red → yellow → green"))))

(deftest test-detect-alerts-returns-empty-for-no-issues
  (testing "detect-alerts returns empty list when nothing is wrong"
    (let [data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 100000 25000)
                :prev-pnl                 (pnl-map 100000 24000)
                :current-buyout           []
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (empty? alerts)
          "detect-alerts should return empty when no rules fire"))))

;; ---------------------------------------------------------------------------
;; Threshold-boundary tests: strict > means exact threshold value must NOT fire
;; ---------------------------------------------------------------------------

(deftest top-mover-not-fires-on-exactly-30%-test
  (testing "TOP_MOVER does NOT fire when revenue growth is exactly 30%"
    ;; 1000 → 1300 = exactly 30.0% growth; threshold is > 30, so must NOT fire
    (let [curr [{:article "art-1" :group "art-1" :revenue 1300.0}]
          prev [{:article "art-1" :group "art-1" :revenue 1000.0}]
          data {:current-sales-by-article curr
                :prev-sales-by-article    prev
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           []
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (not (some #(= :TOP_MOVER (:rule %)) alerts))
          "TOP_MOVER should NOT fire at exactly 30% growth (threshold is strict >)"))))

(deftest margin-drop-not-fires-on-exactly-15-ppt-test
  (testing "MARGIN_DROP does NOT fire when margin drops exactly 15 ppt"
    ;; prev: 30% margin (30000/100000), curr: 15% margin (15000/100000) — drop = exactly 15 ppt
    (let [data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 100000 15000)
                :prev-pnl                 (pnl-map 100000 30000)
                :current-buyout           []
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (not (some #(= :MARGIN_DROP (:rule %)) alerts))
          "MARGIN_DROP should NOT fire at exactly 15 ppt drop (threshold is strict >)"))))

(deftest returns-spike-not-fires-on-exactly-0.7-test
  (testing "RETURNS_SPIKE does NOT fire when buyout-rate is exactly 70.0 (= threshold)"
    ;; Threshold is :returns-spike-buyout-max 70.0; rule uses < (strict), so 70.0 must NOT fire
    (let [buyout-data [{:article "art-1" :buyout-rate 70.0 :ordered 100}]
          data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           buyout-data
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (not (some #(= :RETURNS_SPIKE (:rule %)) alerts))
          "RETURNS_SPIKE should NOT fire at buyout-rate exactly 70.0 (threshold is strict <)"))))

(deftest out-of-stock-not-fires-on-exactly-7-days-test
  (testing "OUT_OF_STOCK does NOT fire when days-of-cover is exactly 7"
    ;; Threshold is :out-of-stock-days 7; rule uses < (strict), so 7.0 must NOT fire
    (let [stocks [{:article "art-1" :name "Product A" :size "M"
                   :days-of-cover 7.0 :avg-daily-sales 3.0}]
          data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     stocks
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           []
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (is (not (some #(= :OUT_OF_STOCK (:rule %)) alerts))
          "OUT_OF_STOCK should NOT fire when days-of-cover is exactly 7 (threshold is strict <)"))))

(deftest zero-sales-not-fires-on-exactly-3-day-window-test
  (testing "ZERO_SALES_TOP_SKU does NOT fire when article has a sale on day -3 (within window)"
    ;; A sale on three-days-ago (= curr-to - 2 days) is still within the last-3-days window
    ;; The filter is >= three-days-ago, so a sale exactly on that boundary date should count
    (let [top-10 [{:article "art-top" :rank 1 :name "Top Product" :revenue 100000}]
          ;; Sale date is exactly on the 3-day boundary (curr-to minus 2 days = three-days-ago)
          boundary-date (-> (java.time.LocalDate/now) (.minusDays 2) str)
          last3  [{:article "art-top" :revenue 500 :date boundary-date}]
          data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     []
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           []
                :sales-last-3-days        last3
                :top-10-by-revenue        top-10}
          alerts (alerts/detect-alerts data)]
      (is (not (some #(= :ZERO_SALES_TOP_SKU (:rule %)) alerts))
          "ZERO_SALES_TOP_SKU should NOT fire when article sold exactly on the 3-day boundary"))))

;; ---------------------------------------------------------------------------

(deftest test-alert-shape
  (testing "Each alert has required keys: :rule :severity :title :body :action-route :action-label"
    (let [stocks [{:article "art-1" :name "Product A" :size "M"
                   :days-of-cover 3.0 :avg-daily-sales 5.0}]
          data {:current-sales-by-article []
                :prev-sales-by-article    []
                :stocks-with-turnover     stocks
                :current-pnl              (pnl-map 10000 2000)
                :prev-pnl                 (pnl-map 10000 2000)
                :current-buyout           []
                :sales-last-3-days        []
                :top-10-by-revenue        []}
          alerts (alerts/detect-alerts data)]
      (doseq [a alerts]
        (is (contains? a :rule))
        (is (contains? a :severity))
        (is (contains? a :title))
        (is (contains? a :body))
        (is (contains? a :action-route))
        (is (contains? a :action-label))))))
