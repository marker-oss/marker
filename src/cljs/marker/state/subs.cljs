(ns marker.state.subs
  "re-frame subscriptions for the Marker SPA.
   One reg-sub per top-level :marker/* app-db key."
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::page
  (fn [db _] (:marker/page db)))

;; Derived from ::page: section keyword (e.g. :finance) for any page
;; shape; active-tab is non-nil only for [:section :tab] sectioned pages.
(rf/reg-sub ::active-section
  :<- [::page]
  (fn [page _]
    (cond
      (vector? page) (first page)
      :else          page)))

(rf/reg-sub ::active-tab
  :<- [::page]
  (fn [page _]
    (when (and (vector? page) (= 2 (count page)))
      (second page))))

(rf/reg-sub ::mp-filter
  (fn [db _] (:marker/mp-filter db)))

(rf/reg-sub ::period
  (fn [db _] (:marker/period db)))

(rf/reg-sub ::compare
  (fn [db _] (:marker/compare db)))

(rf/reg-sub ::theme
  (fn [db _] (:marker/theme db)))

(rf/reg-sub ::density
  (fn [db _] (:marker/density db)))

(rf/reg-sub ::sidebar-collapsed
  (fn [db _] (:marker/sidebar-collapsed db)))

(rf/reg-sub ::cmdk-open
  (fn [db _] (:marker/cmdk-open db)))

(rf/reg-sub ::sheet-sku
  (fn [db _] (:marker/sheet-sku db)))

(rf/reg-sub ::sync-state
  (fn [db _] (:marker/sync-state db)))

(rf/reg-sub ::tweaks-open
  (fn [db _] (:marker/tweaks-open db)))

;; ---------------------------------------------------------------------------
;; Phase 8: API data slices
;; ---------------------------------------------------------------------------

(rf/reg-sub ::pulse-data
  (fn [db _] (:marker/pulse-data db)))

(rf/reg-sub ::pulse-loading?
  (fn [db _] (:marker/pulse-loading? db)))

(rf/reg-sub ::pnl-data
  (fn [db _] (:marker/pnl-data db)))

(rf/reg-sub ::pnl-loading?
  (fn [db _] (:marker/pnl-loading? db)))

(rf/reg-sub ::sku-list-data
  (fn [db _] (:marker/sku-list-data db)))

(rf/reg-sub ::sku-list-loading?
  (fn [db _] (:marker/sku-list-loading? db)))

;; Parameterized per-SKU subs — subscribe with [::sku-detail-loading? sku-id]
;; and [::sku-detail-data sku-id].  This avoids the race condition where
;; SKU A's response could clear a global loading flag while SKU B is still
;; in-flight.
(rf/reg-sub ::sku-detail-loading?
  (fn [db [_ sku-id]]
    (get-in db [:marker/sku-detail-data sku-id :loading?] false)))

(rf/reg-sub ::sku-detail-data
  (fn [db [_ sku-id]]
    (get-in db [:marker/sku-detail-data sku-id :data])))

(rf/reg-sub ::api-errors
  (fn [db _] (:marker/api-errors db)))

;; ---------------------------------------------------------------------------
;; Phase 9: Generic reports — keyed by report-type keyword
;; ---------------------------------------------------------------------------

(rf/reg-sub ::report-data
  (fn [db [_ report-type]]
    (get-in db [:marker/reports-data report-type])))

(rf/reg-sub ::report-loading?
  (fn [db [_ report-type]]
    (get-in db [:marker/reports-loading? report-type] false)))

;; Phase 3: chart datasets per report-type
(rf/reg-sub ::report-chart-data
  (fn [db [_ report-type]]
    (get-in db [:marker/report-chart-data report-type])))

(rf/reg-sub ::report-chart-loading?
  (fn [db [_ report-type]]
    (get-in db [:marker/report-chart-loading? report-type] false)))

;; ---------------------------------------------------------------------------
;; Phase 2: Stocks
;; ---------------------------------------------------------------------------

(rf/reg-sub ::stocks-overview
  (fn [db _] (:marker/stocks-overview db)))

(rf/reg-sub ::stocks-overview-loading?
  (fn [db _] (:marker/stocks-overview-loading? db)))

(rf/reg-sub ::stock-article-data
  (fn [db [_ article]]
    (get-in db [:marker/stocks-article-data article :data])))

(rf/reg-sub ::stock-article-loading?
  (fn [db [_ article]]
    (get-in db [:marker/stocks-article-data article :loading?] false)))
