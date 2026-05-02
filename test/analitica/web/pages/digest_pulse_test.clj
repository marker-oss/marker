(ns analitica.web.pages.digest-pulse-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.pages.digest :as digest]))

(defn- text [v]
  (clojure.string/join " " (filter string? (tree-seq coll? seq v))))

(def ^:private fake-data
  {:from "2026-05-01" :to "2026-05-22"
   :period-month  "2026-05"
   :days-elapsed  22
   :days-in-month 30
   :kpi      {:revenue 0.0 :net-profit 0.0 :margin 0.0 :drr 0.0
              :revenue-delta nil :net-profit-delta nil
              :margin-delta nil :drr-delta nil
              :revenue-sparkline [] :profit-sparkline []
              :margin-sparkline [] :drr-sparkline []}
   :alerts   []
   :movers   [] :fallers []
   :freshness {} :daily-revenue [] :by-marketplace []
   :pulse {:plan-fact         {:targets [] :period-month "2026-05"}
           :sales-conversion  {:orders-qty 100 :buyout-pct 80.0}
           :profit-forecast   {}
           :margin-roi        {:gross-profit 1000.0 :margin-pct 25.0}
           :products-stock    {:oos-skus 0 :turnover-days 30.0 :return-pct 2.0}
           :ads-traffic       {:impressions 0 :clicks 0 :ctr-pct 0 :cpc-rub 0
                               :romi 0 :drr-pct 0}}})

(deftest render-page-includes-all-eight-section-titles
  (let [t (text (digest/render-page fake-data))]
    (is (re-find #"Гипотезы" t))
    (is (re-find #"План-Факт" t))
    (is (re-find #"Продажи и конверсия" t))
    (is (re-find #"Прогноз прибыли" t))
    (is (re-find #"Маржинальность и ROI" t))
    (is (re-find #"Товары и остатки" t))
    (is (re-find #"Реклама и трафик" t))
    (is (re-find #"Кастомная метрика" t))))

(deftest render-page-handles-missing-pulse-key
  (testing "Tolerates absence of :pulse — render-page must not throw"
    (is (some? (digest/render-page (dissoc fake-data :pulse))))))
