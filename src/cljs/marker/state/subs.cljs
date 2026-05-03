(ns marker.state.subs
  "re-frame subscriptions for the Marker SPA.
   One reg-sub per top-level :marker/* app-db key."
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::page
  (fn [db _] (:marker/page db)))

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

;; Returns a map of {sku-id detail-map}.
(rf/reg-sub ::sku-detail-data
  (fn [db _] (:marker/sku-detail-data db)))

(rf/reg-sub ::sku-detail-loading?
  (fn [db _] (:marker/sku-detail-loading? db)))

(rf/reg-sub ::api-errors
  (fn [db _] (:marker/api-errors db)))
