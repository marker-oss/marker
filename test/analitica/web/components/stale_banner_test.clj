(ns analitica.web.components.stale-banner-test
  "Tests for the stale-data banner component."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.components.stale-banner :as sb]
            [hiccup.core :refer [html]]))

;; ---------------------------------------------------------------------------
;; 1. Returns nil when status = :ok
;; ---------------------------------------------------------------------------

(deftest stale-banner-nil-when-ok
  (testing "Returns nil when stale-info status is :ok"
    (let [ok-info {:status :ok}
          result  (sb/stale-banner ok-info {:report :pnl :period "2026-04-01_2026-04-26"})]
      (is (nil? result))))

  (testing "Returns nil when stale-info is nil"
    (is (nil? (sb/stale-banner nil {:report :pnl :period "2026-04-01_2026-04-26"})))))

;; ---------------------------------------------------------------------------
;; 2. Renders :div.bg-amber-50 when stale
;; ---------------------------------------------------------------------------

(deftest stale-banner-renders-when-stale
  (let [stale-info {:status       :stale
                    :reason       "WB finance отстаёт на 9 дней"
                    :last-sync    "2026-04-17T03:40:00"
                    :age-days     9
                    :worst-pair   [:wb :finance]
                    :max-lag-days 7}
        result (sb/stale-banner stale-info {:report :pnl :period "2026-04-01_2026-04-26"})]

    (testing "Top-level element is :div with amber-50 background"
      (is (vector? result))
      (is (= :div.bg-amber-50.border-l-4.border-amber-400.p-3.mb-4.flex.items-center.justify-between
             (first result))))

    (testing "HTML contains the reason text"
      (let [h (html result)]
        (is (re-find #"WB finance" h))))

    (testing "HTML contains last-sync date in human-readable form"
      (let [h (html result)]
        (is (re-find #"17\.04" h))))

    (testing "HTML contains sync link"
      (let [h (html result)]
        (is (re-find #"/sync" h))))

    (testing "HTML contains dismiss button"
      (let [h (html result)]
        (is (re-find #"localStorage" h))
        (is (re-find #"×" h))))))

;; ---------------------------------------------------------------------------
;; 3. data-stale-banner attribute for JS dismissal
;; ---------------------------------------------------------------------------

(deftest stale-banner-has-data-attribute
  (testing "Banner root has data-stale-banner attribute"
    (let [stale-info {:status :stale :reason "test" :last-sync "2026-04-17T03:40:00"
                      :age-days 9 :worst-pair [:wb :finance] :max-lag-days 7}
          result (sb/stale-banner stale-info {:report :pnl :period "2026-04-01_2026-04-26"})
          attrs  (second result)]
      (is (contains? attrs :data-stale-banner))
      (is (string? (:data-stale-banner attrs)))))

  (testing "data-stale-banner key includes report type and period"
    (let [stale-info {:status :stale :reason "test" :last-sync "2026-04-17T03:40:00"
                      :age-days 9 :worst-pair [:wb :finance] :max-lag-days 7}
          result (sb/stale-banner stale-info {:report :sales :period "2026-04-01_2026-04-26"})
          attrs  (second result)
          key    (:data-stale-banner attrs)]
      (is (re-find #"sales" key))
      (is (re-find #"2026" key)))))

;; ---------------------------------------------------------------------------
;; 4. Warning icon present
;; ---------------------------------------------------------------------------

(deftest stale-banner-has-warning-icon
  (testing "Banner contains warning icon"
    (let [stale-info {:status :stale :reason "WB finance отстаёт на 9 дней"
                      :last-sync "2026-04-17T03:40:00" :age-days 9
                      :worst-pair [:wb :finance] :max-lag-days 7}
          result (sb/stale-banner stale-info {:report :pnl :period "x"})
          h      (html result)]
      (is (re-find #"⚠" h)))))
