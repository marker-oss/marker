(ns marker.state.events
  "re-frame event handlers for the Marker SPA.
   Includes initialize-db (with localStorage merge), all setters,
   tweaks persistence via localStorage[\"marker/tweaks\"],
   and Phase 8 API load events."
  (:require [re-frame.core :as rf]
            [clojure.string  :as str]
            [marker.state.db :as db]
            [marker.api      :as api]))

;; ---------------------------------------------------------------------------
;; localStorage helpers
;; ---------------------------------------------------------------------------

(def ^:private LS-KEY "marker/tweaks")

(defn- read-ls-tweaks []
  (try
    (when-let [raw (.getItem js/localStorage LS-KEY)]
      (js->clj (js/JSON.parse raw) :keywordize-keys false))
    (catch :default _
      nil)))

(defn- persist-tweaks!
  "Write theme/density/sidebar-collapsed to localStorage."
  [db]
  (try
    (let [payload (clj->js {"theme"             (:marker/theme db)
                             "density"           (:marker/density db)
                             "sidebar-collapsed" (:marker/sidebar-collapsed db)})]
      (.setItem js/localStorage LS-KEY (js/JSON.stringify payload)))
    (catch :default _
      nil)))

;; ---------------------------------------------------------------------------
;; Effect: persist tweaks to localStorage.
;; Used as an :fx entry by reg-event-fx handlers that mutate persisted keys,
;; so the reg-event-db handlers themselves stay pure (data-in / data-out).
;; ---------------------------------------------------------------------------

(rf/reg-fx ::persist-tweaks persist-tweaks!)

;; ---------------------------------------------------------------------------
;; Time helper
;; ---------------------------------------------------------------------------

(defn- current-time-hhmm
  "Returns current local time as \"HH:MM\" string."
  []
  (let [d  (js/Date.)
        hh (-> (.getHours d) str (.padStart 2 "0"))
        mm (-> (.getMinutes d) str (.padStart 2 "0"))]
    (str hh ":" mm)))

;; ---------------------------------------------------------------------------
;; initialize-db
;; ---------------------------------------------------------------------------

(rf/reg-event-db ::initialize-db
  (fn [_ _]
    (let [persisted (read-ls-tweaks)
          base      db/default-db]
      (cond-> base
        (string? (get persisted "theme"))
        (assoc :marker/theme (get persisted "theme"))

        (string? (get persisted "density"))
        (assoc :marker/density (get persisted "density"))

        (contains? persisted "sidebar-collapsed")
        (assoc :marker/sidebar-collapsed (boolean (get persisted "sidebar-collapsed")))))))

;; ---------------------------------------------------------------------------
;; Setters
;; ---------------------------------------------------------------------------

(rf/reg-event-db ::set-page
  (fn [db [_ page]]
    (assoc db :marker/page page)))

;; invariant: all 3 or exactly 1 — matches the single-select UI rule in chrome.cljs
(def ^:private known-mps #{:wb :ozon :ym})

(defn- normalize-mp-filter
  "Enforce all-or-one invariant.
   - Filter to known keywords, deduplicate.
   - Count 1 → store as-is; any other count → snap to all 3."
  [mps]
  (let [clean (vec (distinct (filter known-mps (or mps []))))]
    (if (= 1 (count clean))
      clean
      db/all-mps)))

(rf/reg-event-db ::set-mp-filter
  (fn [db [_ mps]]
    (assoc db :marker/mp-filter (normalize-mp-filter mps))))

(rf/reg-event-db ::set-period
  (fn [db [_ period]]
    (assoc db :marker/period period)))

(rf/reg-event-db ::set-compare
  (fn [db [_ v]]
    (assoc db :marker/compare v)))

;; Persisted setters use reg-event-fx so the db update stays pure and the
;; localStorage side-effect goes through a registered effect handler.
;; This keeps re-frame's interceptor / replay / 10x-devtools model sound.

(rf/reg-event-fx ::set-theme
  (fn [{:keys [db]} [_ theme]]
    (let [db' (assoc db :marker/theme theme)]
      {:db db' ::persist-tweaks db'})))

(rf/reg-event-fx ::set-density
  (fn [{:keys [db]} [_ density]]
    (let [db' (assoc db :marker/density density)]
      {:db db' ::persist-tweaks db'})))

(rf/reg-event-fx ::toggle-sidebar
  (fn [{:keys [db]} _]
    (let [db' (update db :marker/sidebar-collapsed not)]
      {:db db' ::persist-tweaks db'})))

(rf/reg-event-db ::open-cmdk
  (fn [db _]
    (assoc db :marker/cmdk-open true)))

(rf/reg-event-db ::close-cmdk
  (fn [db _]
    (assoc db :marker/cmdk-open false)))

(rf/reg-event-db ::open-sheet
  (fn [db [_ sku-id]]
    (assoc db :marker/sheet-sku sku-id)))

(rf/reg-event-db ::close-sheet
  (fn [db _]
    (assoc db :marker/sheet-sku nil)))

(rf/reg-event-db ::set-sync-state
  (fn [db [_ state]]
    (assoc db :marker/sync-state state)))

(rf/reg-event-fx ::refresh-finished
  ;; Transitions sync-state to :success (auto-clears after 4s) or nil on failure.
  ;; No-op when sync-state is not :running (protects against spurious calls
  ;; from per-row detail loads that run outside of sync-and-refresh).
  (fn [{:keys [db]} [_ outcome]]
    (let [running? (= (:kind (:marker/sync-state db)) :running)]
      (cond
        (not running?)       {:db db}
        (= outcome :failure) {:db (assoc db :marker/sync-state nil)}
        (= outcome :success) {:db (assoc db :marker/sync-state
                                          {:kind :success
                                           :time (current-time-hhmm)})
                              :fx [[:dispatch-later
                                    {:ms       4000
                                     :dispatch [::set-sync-state nil]}]]}))))

(rf/reg-event-db ::toggle-tweaks
  (fn [db _]
    (update db :marker/tweaks-open not)))

;; ---------------------------------------------------------------------------
;; Phase 8: Cache helpers
;; ---------------------------------------------------------------------------

(defn- cache-key
  "Build a cache-key tuple from filter state.
   mp-filter is sorted so toggling MP chips off then back on (which appends in
   click order) produces the same key as the original filter — without this,
   [:wb :ozon :ym] and [:wb :ym :ozon] would be different cache buckets."
  [page {:keys [mp-filter period compare]}]
  [page (vec (sort mp-filter)) period compare])

;; ---------------------------------------------------------------------------
;; Phase 8: Error handling
;; ---------------------------------------------------------------------------

;; Write an error entry to :marker/api-errors. Payload is the clj-ajax failure response map.
(rf/reg-event-db ::api-error
  (fn [db [_ url failure]]
    (let [msg (or (get-in failure [:response :error])
                  (:status-text failure)
                  (str "HTTP " (:status failure))
                  "Неизвестная ошибка")]
      (assoc-in db [:marker/api-errors url]
                {:message msg
                 :status  (:status failure)}))))

(rf/reg-event-db ::clear-api-error
  (fn [db [_ url]]
    (update db :marker/api-errors dissoc url)))

;; ---------------------------------------------------------------------------
;; Phase 8: Cache management
;; ---------------------------------------------------------------------------

;; Clear the entire API cache (used by Sync button).
(rf/reg-event-db ::clear-cache
  (fn [db _]
    (assoc db :marker/cache {})))

;; ---------------------------------------------------------------------------
;; Phase 8: Pulse
;; ---------------------------------------------------------------------------

(def ^:private pulse-url "/api/v1/marker/pulse-summary")

;; Load pulse-summary data. Checks cache first; fires http-xhrio on miss.
(rf/reg-event-fx ::load-pulse
  (fn [{:keys [db]} [_ filter-state]]
    (let [fs   (or filter-state
                   {:mp-filter (:marker/mp-filter db)
                    :period    (:marker/period    db)
                    :compare   (:marker/compare   db)})
          ckey (cache-key :pulse fs)
          hit  (get-in db [:marker/cache ckey])]
      (if hit
        {:db (assoc db :marker/pulse-data hit :marker/pulse-loading? false)}
        {:db         (assoc db :marker/pulse-loading? true)
         :http-xhrio (api/get-xhrio
                       (api/build-url pulse-url (api/build-params fs))
                       [::pulse-data-loaded ckey]
                       [::pulse-load-failed])}))))

(rf/reg-event-fx ::pulse-data-loaded
  (fn [{:keys [db]} [_ ckey data]]
    {:db (-> db
             (assoc :marker/pulse-data    data
                    :marker/pulse-loading? false)
             (assoc-in [:marker/cache ckey] data)
             (update :marker/api-errors dissoc pulse-url))
     :fx [[:dispatch [::refresh-finished :success]]]}))

(rf/reg-event-fx ::pulse-load-failed
  (fn [{:keys [db]} [_ failure]]
    {:db (assoc db :marker/pulse-loading? false)
     :fx [[:dispatch [::api-error pulse-url failure]]
          [:dispatch [::refresh-finished :failure]]]}))

;; ---------------------------------------------------------------------------
;; Phase 8: P&L
;; ---------------------------------------------------------------------------

(def ^:private pnl-url "/api/v1/marker/pnl")

(rf/reg-event-fx ::load-pnl
  (fn [{:keys [db]} [_ filter-state]]
    (let [fs   (or filter-state
                   {:mp-filter (:marker/mp-filter db)
                    :period    (:marker/period    db)
                    :compare   (:marker/compare   db)})
          ckey (cache-key :pnl fs)
          hit  (get-in db [:marker/cache ckey])]
      (if hit
        {:db (assoc db :marker/pnl-data hit :marker/pnl-loading? false)}
        {:db         (assoc db :marker/pnl-loading? true)
         :http-xhrio (api/get-xhrio
                       (api/build-url pnl-url (api/build-params fs))
                       [::pnl-data-loaded ckey]
                       [::pnl-load-failed])}))))

(rf/reg-event-fx ::pnl-data-loaded
  (fn [{:keys [db]} [_ ckey data]]
    {:db (-> db
             (assoc :marker/pnl-data    data
                    :marker/pnl-loading? false)
             (assoc-in [:marker/cache ckey] data)
             (update :marker/api-errors dissoc pnl-url))
     :fx [[:dispatch [::refresh-finished :success]]]}))

(rf/reg-event-fx ::pnl-load-failed
  (fn [{:keys [db]} [_ failure]]
    {:db (assoc db :marker/pnl-loading? false)
     :fx [[:dispatch [::api-error pnl-url failure]]
          [:dispatch [::refresh-finished :failure]]]}))

;; ---------------------------------------------------------------------------
;; Phase 8: SKU list
;; ---------------------------------------------------------------------------

(def ^:private sku-list-url "/api/v1/marker/sku-list")

(rf/reg-event-fx ::load-sku-list
  (fn [{:keys [db]} [_ filter-state]]
    (let [fs   (or filter-state
                   {:mp-filter (:marker/mp-filter db)
                    :period    (:marker/period    db)
                    :compare   (:marker/compare   db)})
          ckey (cache-key :sku-list fs)
          hit  (get-in db [:marker/cache ckey])]
      (if hit
        {:db (assoc db :marker/sku-list-data hit :marker/sku-list-loading? false)}
        {:db         (assoc db :marker/sku-list-loading? true)
         :http-xhrio (api/get-xhrio
                       (api/build-url sku-list-url (api/build-params fs))
                       [::sku-list-data-loaded ckey]
                       [::sku-list-load-failed])}))))

(rf/reg-event-fx ::sku-list-data-loaded
  (fn [{:keys [db]} [_ ckey data]]
    {:db (-> db
             (assoc :marker/sku-list-data    (:skus data)
                    :marker/sku-list-loading? false)
             (assoc-in [:marker/cache ckey] (:skus data))
             (update :marker/api-errors dissoc sku-list-url))
     :fx [[:dispatch [::refresh-finished :success]]]}))

(rf/reg-event-fx ::sku-list-load-failed
  (fn [{:keys [db]} [_ failure]]
    {:db (assoc db :marker/sku-list-loading? false)
     :fx [[:dispatch [::api-error sku-list-url failure]]
          [:dispatch [::refresh-finished :failure]]]}))

;; ---------------------------------------------------------------------------
;; P4 (FR-P4.6): Reconciliation — P&L vs payout per-article
;; ---------------------------------------------------------------------------

(def ^:private reconciliation-url "/api/v1/marker/reconciliation")

(rf/reg-event-fx ::load-reconciliation
  (fn [{:keys [db]} [_ filter-state]]
    (let [fs   (or filter-state
                   {:mp-filter (:marker/mp-filter db)
                    :period    (:marker/period    db)
                    :compare   (:marker/compare   db)})
          ckey (cache-key :reconciliation fs)
          hit  (get-in db [:marker/cache ckey])]
      (if hit
        {:db (assoc db :marker/reconciliation-data hit
                       :marker/reconciliation-loading? false)}
        {:db         (assoc db :marker/reconciliation-loading? true)
         :http-xhrio (api/get-xhrio
                       (api/build-url reconciliation-url (api/build-params fs))
                       [::reconciliation-loaded ckey]
                       [::reconciliation-load-failed])}))))

(rf/reg-event-fx ::reconciliation-loaded
  (fn [{:keys [db]} [_ ckey data]]
    {:db (-> db
             (assoc :marker/reconciliation-data    data
                    :marker/reconciliation-loading? false)
             (assoc-in [:marker/cache ckey] data)
             (update :marker/api-errors dissoc reconciliation-url))
     :fx [[:dispatch [::refresh-finished :success]]]}))

(rf/reg-event-fx ::reconciliation-load-failed
  (fn [{:keys [db]} [_ failure]]
    {:db (assoc db :marker/reconciliation-loading? false)
     :fx [[:dispatch [::api-error reconciliation-url failure]]
          [:dispatch [::refresh-finished :failure]]]}))

;; ---------------------------------------------------------------------------
;; Phase 8: SKU detail (keyed by sku-id inside :marker/sku-detail-data map)
;; ---------------------------------------------------------------------------

(defn- sku-detail-url [sku-id]
  (str "/api/v1/marker/sku-detail/" (js/encodeURIComponent sku-id)))

;; Load detail for a single SKU. Cache key includes sku-id.
;; Loading state is stored per-SKU inside :marker/sku-detail-data to avoid the
;; race condition where SKU A's response clears a global flag while SKU B's
;; request is still in-flight.
(rf/reg-event-fx ::load-sku-detail
  (fn [{:keys [db]} [_ sku-id filter-state]]
    (let [fs   (or filter-state
                   {:mp-filter (:marker/mp-filter db)
                    :period    (:marker/period    db)
                    :compare   (:marker/compare   db)})
          ckey [:sku-detail sku-id (:mp-filter fs) (:period fs)]
          hit  (get-in db [:marker/cache ckey])
          url  (sku-detail-url sku-id)]
      (if hit
        {:db (assoc-in db [:marker/sku-detail-data sku-id] {:data hit :loading? false})}
        {:db         (assoc-in db [:marker/sku-detail-data sku-id :loading?] true)
         :http-xhrio (api/get-xhrio
                       (api/build-url url (api/build-params fs))
                       [::sku-detail-loaded ckey sku-id]
                       [::sku-detail-load-failed sku-id])}))))

(rf/reg-event-db ::sku-detail-loaded
  (fn [db [_ ckey sku-id data]]
    (-> db
        (assoc-in [:marker/sku-detail-data sku-id] {:data data :loading? false})
        (assoc-in [:marker/cache ckey] data)
        ;; Q4: dissoc stale error entry so a successful retry clears the banner
        (update :marker/api-errors dissoc (sku-detail-url sku-id)))))

(rf/reg-event-fx ::sku-detail-load-failed
  (fn [{:keys [db]} [_ sku-id failure]]
    {:db       (assoc-in db [:marker/sku-detail-data sku-id :loading?] false)
     :dispatch [::api-error (sku-detail-url sku-id) failure]}))

;; ---------------------------------------------------------------------------
;; Phase 8: open-sheet — also triggers SKU detail fetch
;; ---------------------------------------------------------------------------

;; Open the SKU sheet and load its data from the API.
;; Phase 2 (UI restructure): also kicks off the per-warehouse stock
;; drilldown so the sheet's «Остаток по складам» section can render
;; without a second user action.
(rf/reg-event-fx ::open-sheet-and-load
  (fn [{:keys [db]} [_ sku-id]]
    {:db (assoc db :marker/sheet-sku sku-id)
     :fx [[:dispatch [::load-sku-detail sku-id]]
          [:dispatch [::load-stock-article sku-id]]]}))

;; ---------------------------------------------------------------------------
;; Phase 8: what-if-recalc (POST) — used by unit page for server validation
;; ---------------------------------------------------------------------------

(def ^:private what-if-url "/api/v1/marker/what-if-recalc")

;; Send unit-econ params to server for validation/persistence.
;; Does NOT replace client-side compute — client stays snappy.
(rf/reg-event-fx ::what-if-recalc
  (fn [_ [_ params on-success]]
    {:http-xhrio (api/post-xhrio
                   what-if-url
                   {:price          (:price params)
                    :cogs           (:cogs params)
                    :commission-pct (:commission params)
                    :logistics      (:logistics params)
                    :ads            (:ads params)
                    :returns-pct    (:returns params)}
                   (or on-success [::what-if-recalc-ok])
                   [::what-if-recalc-failed])}))

(rf/reg-event-db ::what-if-recalc-ok
  (fn [db [_ _result]]
    ;; Server confirmed calculation; no UI update needed (client already shows it)
    db))

(rf/reg-event-fx ::what-if-recalc-failed
  (fn [{:keys [db]} [_ failure]]
    {:db       db   ; silently log; UI is driven by client-side compute
     :dispatch [::api-error what-if-url failure]}))

;; ---------------------------------------------------------------------------
;; Unit-econ: load real article baseline (for "what-if for THIS SKU" mode)
;; ---------------------------------------------------------------------------

(def ^:private unit-baseline-url "/api/v1/marker/unit-baseline")

(rf/reg-event-fx ::load-unit-baseline
  (fn [{:keys [db]} [_ article]]
    (let [art (some-> article str str/trim)]
      (if (or (nil? art) (= "" art))
        {:db db}
        (let [fs   {:mp-filter (:marker/mp-filter db)
                    :period    (:marker/period    db)
                    :compare   false}
              params (assoc (api/build-params fs) :article art)
              url    (api/build-url unit-baseline-url params)]
          {:db         (assoc db
                              :marker/unit-baseline-loading? true
                              :marker/unit-baseline-article  art)
           :http-xhrio (api/get-xhrio url
                                      [::unit-baseline-loaded]
                                      [::unit-baseline-load-failed])})))))

(rf/reg-event-db ::unit-baseline-loaded
  (fn [db [_ data]]
    (-> db
        (assoc :marker/unit-baseline-data     data
               :marker/unit-baseline-loading? false)
        (update :marker/api-errors dissoc unit-baseline-url))))

(rf/reg-event-fx ::unit-baseline-load-failed
  (fn [{:keys [db]} [_ failure]]
    {:db       (assoc db :marker/unit-baseline-loading? false)
     :dispatch [::api-error unit-baseline-url failure]}))

(rf/reg-event-db ::clear-unit-baseline
  (fn [db _]
    (dissoc db
            :marker/unit-baseline-data
            :marker/unit-baseline-loading?
            :marker/unit-baseline-article)))

;; ---------------------------------------------------------------------------
;; Phase 2 (UI restructure): Stocks — by-warehouse + per-article drilldown
;; Stocks are snapshots, not period-windowed; only :mp-filter applies.
;; ---------------------------------------------------------------------------

(def ^:private stocks-overview-url "/api/v1/marker/stocks/overview")

(defn- stock-article-url [article]
  (str "/api/v1/marker/stocks/article/" (js/encodeURIComponent article)))

(defn- stocks-params
  "Stocks endpoints accept :mp only. mp-filter is a vec of keywords from
   app-db; collapse to «wb»/«ozon»/«ym»/«all»."
  [mp-filter]
  (let [mp (api/mp-param mp-filter)]
    (cond-> {}
      mp (assoc :mp mp))))

(rf/reg-event-fx ::load-stocks-overview
  (fn [{:keys [db]} _]
    (let [mp-filter (:marker/mp-filter db)
          ckey      [:stocks/overview (api/mp-param mp-filter)]
          hit       (get-in db [:marker/cache ckey])]
      (if hit
        {:db (assoc db
                    :marker/stocks-overview         hit
                    :marker/stocks-overview-loading? false)}
        {:db         (assoc db :marker/stocks-overview-loading? true)
         :http-xhrio (api/get-xhrio
                       (api/build-url stocks-overview-url
                                       (stocks-params mp-filter))
                       [::stocks-overview-loaded ckey]
                       [::stocks-overview-failed])}))))

(rf/reg-event-fx ::stocks-overview-loaded
  (fn [{:keys [db]} [_ ckey data]]
    {:db (-> db
             (assoc :marker/stocks-overview         data
                    :marker/stocks-overview-loading? false)
             (assoc-in [:marker/cache ckey] data)
             (update :marker/api-errors dissoc stocks-overview-url))}))

(rf/reg-event-fx ::stocks-overview-failed
  (fn [{:keys [db]} [_ failure]]
    {:db       (assoc db :marker/stocks-overview-loading? false)
     :dispatch [::api-error stocks-overview-url failure]}))

;; Per-article drilldown — keyed by article id so multiple sheet opens
;; don't race on a global :loading? flag (mirrors sku-detail pattern).
(rf/reg-event-fx ::load-stock-article
  (fn [{:keys [db]} [_ article]]
    (let [mp-filter (:marker/mp-filter db)
          url       (stock-article-url article)]
      {:db         (assoc-in db [:marker/stocks-article-data article :loading?] true)
       :http-xhrio (api/get-xhrio
                     (api/build-url url (stocks-params mp-filter))
                     [::stock-article-loaded article]
                     [::stock-article-failed article url])})))

(rf/reg-event-db ::stock-article-loaded
  (fn [db [_ article data]]
    (-> db
        (assoc-in [:marker/stocks-article-data article :loading?] false)
        (assoc-in [:marker/stocks-article-data article :data] data))))

(rf/reg-event-fx ::stock-article-failed
  (fn [{:keys [db]} [_ article url failure]]
    {:db       (assoc-in db [:marker/stocks-article-data article :loading?] false)
     :dispatch [::api-error url failure]}))

;; ---------------------------------------------------------------------------
;; Phase 9: Generic reports loader — keyed by report-type
;; ---------------------------------------------------------------------------

(defn- reports-url [report-type]
  (str "/api/v1/marker/reports/" (name report-type)))

(rf/reg-event-fx ::load-report
  (fn [{:keys [db]} [_ report-type filter-state]]
    (let [fs   (or filter-state
                   {:mp-filter (:marker/mp-filter db)
                    :period    (:marker/period    db)
                    :compare   (:marker/compare   db)})
          ckey [:report report-type (vec (sort (:mp-filter fs))) (:period fs) (:compare fs)]
          hit  (get-in db [:marker/cache ckey])
          url  (reports-url report-type)]
      (if hit
        {:db (-> db
                 (assoc-in [:marker/reports-data    report-type] hit)
                 (assoc-in [:marker/reports-loading? report-type] false))}
        {:db         (assoc-in db [:marker/reports-loading? report-type] true)
         :http-xhrio (api/get-xhrio
                       (api/build-url url (api/build-params fs))
                       [::report-loaded ckey report-type]
                       [::report-load-failed report-type])}))))

(rf/reg-event-fx ::report-loaded
  (fn [{:keys [db]} [_ ckey report-type data]]
    {:db (-> db
             (assoc-in [:marker/reports-data     report-type] data)
             (assoc-in [:marker/reports-loading? report-type] false)
             (assoc-in [:marker/cache ckey] data)
             (update :marker/api-errors dissoc (reports-url report-type)))
     :fx [[:dispatch [::refresh-finished :success]]]}))

(rf/reg-event-fx ::report-load-failed
  (fn [{:keys [db]} [_ report-type failure]]
    {:db (assoc-in db [:marker/reports-loading? report-type] false)
     :fx [[:dispatch [::api-error (reports-url report-type) failure]]
          [:dispatch [::refresh-finished :failure]]]}))

;; ---------------------------------------------------------------------------
;; Phase 3 (UI restructure): Chart.js dataset loader, keyed by report-type.
;; Mirrors ::load-report shape so cache-keys, error-routing, and
;; refresh-finished signalling stay consistent.
;; ---------------------------------------------------------------------------

(defn- chart-url [report-type]
  (str "/api/v1/marker/chart/" (name report-type)))

(rf/reg-event-fx ::load-report-chart
  (fn [{:keys [db]} [_ report-type filter-state]]
    (let [fs   (or filter-state
                   {:mp-filter (:marker/mp-filter db)
                    :period    (:marker/period    db)
                    :compare   (:marker/compare   db)})
          ckey [:report-chart report-type (vec (sort (:mp-filter fs)))
                (:period fs) (:compare fs)]
          hit  (get-in db [:marker/cache ckey])
          url  (chart-url report-type)]
      (if hit
        {:db (-> db
                 (assoc-in [:marker/report-chart-data    report-type] hit)
                 (assoc-in [:marker/report-chart-loading? report-type] false))}
        {:db         (assoc-in db [:marker/report-chart-loading? report-type] true)
         :http-xhrio (api/get-xhrio
                       (api/build-url url (api/build-params fs))
                       [::report-chart-loaded ckey report-type]
                       [::report-chart-failed report-type])}))))

(rf/reg-event-db ::report-chart-loaded
  (fn [db [_ ckey report-type data]]
    (-> db
        (assoc-in [:marker/report-chart-data     report-type] data)
        (assoc-in [:marker/report-chart-loading? report-type] false)
        (assoc-in [:marker/cache ckey] data)
        (update :marker/api-errors dissoc (chart-url report-type)))))

(rf/reg-event-fx ::report-chart-failed
  (fn [{:keys [db]} [_ report-type failure]]
    {:db       (assoc-in db [:marker/report-chart-loading? report-type] false)
     :dispatch [::api-error (chart-url report-type) failure]}))

;; ---------------------------------------------------------------------------
;; Phase 8: Sync / refresh — clear cache + reload current page
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Settings: operator marketplace credentials
;; ---------------------------------------------------------------------------

(def ^:private settings-url "/api/v1/settings")

(defn settings-form->payload
  "Drop blank/nil values; keep only fields the operator actually typed."
  [form]
  (into {} (remove (fn [[_ v]] (or (nil? v) (and (string? v) (= "" (str/trim v))))) form)))

(rf/reg-event-fx ::load-settings
  (fn [_ _]
    {:http-xhrio (api/get-xhrio settings-url
                                [::settings-loaded] [::settings-load-failed])}))

(rf/reg-event-db ::settings-loaded
  (fn [db [_ resp]]
    (assoc-in db [:marker/settings :data] (:settings resp))))

(rf/reg-event-db ::settings-load-failed
  (fn [db [_ _err]]
    (assoc-in db [:marker/settings :data] {})))

(rf/reg-event-fx ::test-marketplace
  (fn [{:keys [db]} [_ mp form]]
    {:db (assoc-in db [:marker/settings :status mp] {:testing? true})
     :http-xhrio (api/post-xhrio (str settings-url "/marketplace/" (name mp) "/test")
                                 (settings-form->payload form)
                                 [::marketplace-tested mp] [::marketplace-tested mp])}))

(rf/reg-event-db ::marketplace-tested
  (fn [db [_ mp resp]]
    (-> db
        (assoc-in [:marker/settings :status mp :testing?] false)
        (assoc-in [:marker/settings :status mp :verdict]
                  {:valid? (boolean (:valid? resp)) :detail (:detail resp)}))))

(rf/reg-event-fx ::save-marketplace
  (fn [{:keys [db]} [_ mp form]]
    {:db (assoc-in db [:marker/settings :status mp] {:saving? true})
     :http-xhrio (api/put-xhrio (str settings-url "/marketplace/" (name mp))
                                (settings-form->payload form)
                                [::marketplace-saved mp] [::marketplace-save-failed mp])}))

(rf/reg-event-fx ::marketplace-saved
  (fn [{:keys [db]} [_ mp resp]]
    {:db (-> db
             (assoc-in [:marker/settings :status mp :saving?] false)
             (assoc-in [:marker/settings :status mp :saved?] (boolean (:ok resp)))
             (assoc-in [:marker/settings :status mp :error] nil))
     :fx [[:dispatch [::load-settings]]]}))   ; refresh masked values

(rf/reg-event-db ::marketplace-save-failed
  (fn [db [_ mp resp]]
    (-> db
        (assoc-in [:marker/settings :status mp :saving?] false)
        (assoc-in [:marker/settings :status mp :saved?] false)
        (assoc-in [:marker/settings :status mp :error]
                  (or (get-in resp [:response :detail])
                      (get-in resp [:response :error])
                      "Не удалось сохранить")))))

(rf/reg-event-fx ::sync-and-refresh
  ;; Clear cache, show sync running state, reload data for the current page.
  (fn [{:keys [db]} _]
    (let [page (:marker/page db)
          fs   {:mp-filter (:marker/mp-filter db)
                :period    (:marker/period    db)
                :compare   (:marker/compare   db)}
          ;; If on a report page, reload that report
          report-type (when (and (vector? page) (= :report (first page)))
                        (second page))
          load-evt (cond
                     report-type     [::load-report  report-type fs]
                     (= page :pulse) [::load-pulse    fs]
                     (= page :pnl)   [::load-pnl      fs]
                     (= page :products) [::load-sku-list fs]
                     :else           nil)]
      (cond->
       {:db (-> db
                (assoc :marker/cache {})
                (assoc :marker/sync-state
                       {:kind     :running
                        :section  (cond
                                    report-type      (str "Отчёт " (name report-type))
                                    (= page :pulse)  "Pulse"
                                    (= page :pnl)    "P&L"
                                    (= page :products) "Товары"
                                    :else            "данных")
                        :elapsed  "0s"
                        :progress 30}))}
        load-evt (assoc :dispatch load-evt)))))
