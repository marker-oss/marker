(ns analitica.schema.performance-test
  "T043 — performance benchmark for validator.

   SC-007 target: validation overhead ≤ 5% of ingest time. For a typical
   finance response (≈1 000 rows, ingest ~2 s), that means `< 100 ms` for the
   validator itself. This test guards that budget."
  (:require [clojure.test :refer [deftest is]]
            [analitica.schema.registry :as r]
            [analitica.schema.validator :as v]))

(def ^:private n-rows 1000)

(def ^:private row-schema
  [:map
   [:rrd_id                :int]
   [:date_from             :string]
   [:date_to               :string]
   [:article               :string]
   [:nm_id                 :int]
   [:operation             :string]
   [:quantity              :int]
   [:retail_price          number?]
   [:retail_amount         number?]
   [:ppvz_for_pay          number?]
   [:ppvz_sales_commission number?]
   [:delivery_amount       number?]])

(def ^:private contract
  {:endpoint/id             :perf/wb-finance-like
   :endpoint/marketplace    :wb
   :endpoint/api-path       "/perf"
   :endpoint/method         :get
   :contract/source         {:kind :manual :generated-at "2026-04-22"}
   :contract/response-schema [:sequential row-schema]
   :contract/version        1})

(defn- sample-row [i]
  {:rrd_id                i
   :date_from             "2026-03-01"
   :date_to               "2026-03-31"
   :article               (str "ART-" i)
   :nm_id                 (+ 100000 i)
   :operation             "Продажа"
   :quantity              1
   :retail_price          (* 1.5 i)
   :retail_amount         (* 1.5 i)
   :ppvz_for_pay          (* 1.2 i)
   :ppvz_sales_commission (* 0.3 i)
   :delivery_amount       0.0})

(defn- sample-response [] (mapv sample-row (range n-rows)))

(deftest validate-1000-rows-under-100ms
  (r/clear!)
  (r/register! contract)
  (let [resp    (sample-response)
        ;; warm-up — triggers Malli compilation and JIT
        _       (dotimes [_ 3] (v/validate contract resp))
        runs    5
        samples (vec (for [_ (range runs)]
                       (let [t0 (System/nanoTime)
                             r  (v/validate contract resp)
                             _  (assert (= :ok (:result/status r)))]
                         (/ (- (System/nanoTime) t0) 1e6))))
        median  (nth (sort samples) (quot runs 2))
        budget-ms 100.0]
    (println (format "[perf] validate(%d rows) median=%.2fms runs=%s budget=%.0fms"
                     n-rows (double median) (pr-str (mapv #(format "%.1f" %) samples)) budget-ms))
    (is (< median budget-ms)
        (format "validator median %.2fms exceeded %.0fms budget (SC-007)" median budget-ms))))
