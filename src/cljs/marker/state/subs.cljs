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

;; LT3: honesty envelope for sku-list (stashed separately because
;; ::sku-list-data is contractually just the :skus vector).
(rf/reg-sub ::sku-list-envelope
  (fn [db _] (:marker/sku-list-envelope db)))

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
;; LT3: active-coverage — honesty envelope for the CURRENTLY active page.
;; Drives the topbar coverage chip. Picks {:completeness :date-basis
;; :preliminary?} from whichever page-data slice matches the active page:
;;   :pulse            → pulse-data (top-level envelope)
;;   [:finance :pnl]   → pnl-data
;;   [:products :skus] → sku-list-envelope
;; Pages without a finance envelope (sync/settings/other tabs) → nil → the
;; chip hides itself.
;; ---------------------------------------------------------------------------
(rf/reg-sub ::active-coverage
  :<- [::page]
  :<- [::pulse-data]
  :<- [::pnl-data]
  :<- [::sku-list-envelope]
  (fn [[page pulse-data pnl-data sku-env] _]
    (let [pick (fn [m]
                 (when (and m (contains? m :completeness))
                   (select-keys m [:completeness :date-basis :preliminary?])))]
      (cond
        (= page :pulse)              (pick pulse-data)
        (= page [:finance :pnl])     (pick pnl-data)
        (= page [:products :skus])   (pick sku-env)
        :else                         nil))))

;; P4 (FR-P4.6): reconciliation — P&L vs payout per-article
(rf/reg-sub ::reconciliation-data
  (fn [db _] (:marker/reconciliation-data db)))

(rf/reg-sub ::reconciliation-loading?
  (fn [db _] (:marker/reconciliation-loading? db)))

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

;; ---------------------------------------------------------------------------
;; Unit-econ: real-article baseline
;; ---------------------------------------------------------------------------

(rf/reg-sub ::unit-baseline-data
  (fn [db _] (:marker/unit-baseline-data db)))

(rf/reg-sub ::unit-baseline-loading?
  (fn [db _] (:marker/unit-baseline-loading? db)))

(rf/reg-sub ::unit-baseline-article
  (fn [db _] (:marker/unit-baseline-article db)))

;; ---------------------------------------------------------------------------
;; Settings — operator marketplace credentials
;; ---------------------------------------------------------------------------

(rf/reg-sub ::settings-data
  (fn [db _] (get-in db [:marker/settings :data])))

(rf/reg-sub ::settings-status
  (fn [db _] (get-in db [:marker/settings :status])))

;; ---------------------------------------------------------------------------
;; 013 Frontend — new feature slices (one reg-sub per db key, sans :marker/)
;; ---------------------------------------------------------------------------

;; 015: tax config + OPEX
(rf/reg-sub ::tax-config
  (fn [db _] (:marker/tax-config db)))
(rf/reg-sub ::tax-config-loading?
  (fn [db _] (:marker/tax-config-loading? db)))
(rf/reg-sub ::opex
  (fn [db _] (:marker/opex db)))
(rf/reg-sub ::opex-loading?
  (fn [db _] (:marker/opex-loading? db)))
(rf/reg-sub ::opex-auto-rules
  (fn [db _] (:marker/opex-auto-rules db)))
(rf/reg-sub ::opex-auto-rules-loading?
  (fn [db _] (:marker/opex-auto-rules-loading? db)))

;; 016: user metrics
(rf/reg-sub ::user-metrics
  (fn [db _] (:marker/user-metrics db)))
(rf/reg-sub ::user-metrics-loading?
  (fn [db _] (:marker/user-metrics-loading? db)))

;; 017: bot settings
(rf/reg-sub ::bot-settings
  (fn [db _] (:marker/bot-settings db)))
(rf/reg-sub ::bot-settings-loading?
  (fn [db _] (:marker/bot-settings-loading? db)))

;; 017: plan/fact + import preview
(rf/reg-sub ::plan-fact
  (fn [db _] (:marker/plan-fact db)))
(rf/reg-sub ::plan-fact-loading?
  (fn [db _] (:marker/plan-fact-loading? db)))
(rf/reg-sub ::plan-import-preview
  (fn [db _] (:marker/plan-import-preview db)))
(rf/reg-sub ::plan-import-preview-loading?
  (fn [db _] (:marker/plan-import-preview-loading? db)))

;; 019: treasury
(rf/reg-sub ::treasury-cashflow
  (fn [db _] (:marker/treasury-cashflow db)))
(rf/reg-sub ::treasury-cashflow-loading?
  (fn [db _] (:marker/treasury-cashflow-loading? db)))
(rf/reg-sub ::treasury-operations
  (fn [db _] (:marker/treasury-operations db)))
(rf/reg-sub ::treasury-operations-loading?
  (fn [db _] (:marker/treasury-operations-loading? db)))
(rf/reg-sub ::treasury-accounts
  (fn [db _] (:marker/treasury-accounts db)))
(rf/reg-sub ::treasury-accounts-loading?
  (fn [db _] (:marker/treasury-accounts-loading? db)))
(rf/reg-sub ::treasury-counterparties
  (fn [db _] (:marker/treasury-counterparties db)))
(rf/reg-sub ::treasury-counterparties-loading?
  (fn [db _] (:marker/treasury-counterparties-loading? db)))
(rf/reg-sub ::treasury-obligations-summary
  (fn [db _] (:marker/treasury-obligations-summary db)))
(rf/reg-sub ::treasury-obligations-summary-loading?
  (fn [db _] (:marker/treasury-obligations-summary-loading? db)))
(rf/reg-sub ::treasury-obligations-dynamics
  (fn [db _] (:marker/treasury-obligations-dynamics db)))
(rf/reg-sub ::treasury-obligations-dynamics-loading?
  (fn [db _] (:marker/treasury-obligations-dynamics-loading? db)))
(rf/reg-sub ::treasury-obligations
  (fn [db _] (:marker/treasury-obligations db)))
(rf/reg-sub ::treasury-obligations-loading?
  (fn [db _] (:marker/treasury-obligations-loading? db)))
(rf/reg-sub ::treasury-auto-rules
  (fn [db _] (:marker/treasury-auto-rules db)))
(rf/reg-sub ::treasury-auto-rules-loading?
  (fn [db _] (:marker/treasury-auto-rules-loading? db)))
(rf/reg-sub ::treasury-classify-result
  (fn [db _] (:marker/treasury-classify-result db)))
(rf/reg-sub ::treasury-classify-result-loading?
  (fn [db _] (:marker/treasury-classify-result-loading? db)))
