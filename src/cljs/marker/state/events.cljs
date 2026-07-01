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
        ;; hit is the full response map (LT3 cache change). Re-derive the
        ;; vector + envelope so a cache hit matches a fresh load.
        {:db (assoc db :marker/sku-list-data     (:skus hit)
                       :marker/sku-list-envelope (select-keys hit [:completeness :date-basis :preliminary?])
                       :marker/sku-list-loading? false)}
        {:db         (assoc db :marker/sku-list-loading? true)
         :http-xhrio (api/get-xhrio
                       (api/build-url sku-list-url (api/build-params fs))
                       [::sku-list-data-loaded ckey]
                       [::sku-list-load-failed])}))))

(rf/reg-event-fx ::sku-list-data-loaded
  (fn [{:keys [db]} [_ ckey data]]
    ;; sku-list-data holds just the :skus vector (every consumer expects a
    ;; vector). LT3: stash the honesty envelope separately so the coverage
    ;; banner/chip can read it without changing the vector contract. Cache the
    ;; whole response so a cache hit can re-derive both.
    (let [env (select-keys data [:completeness :date-basis :preliminary?])]
      {:db (-> db
               (assoc :marker/sku-list-data     (:skus data)
                      :marker/sku-list-envelope env
                      :marker/sku-list-loading? false)
               (assoc-in [:marker/cache ckey] data)
               (update :marker/api-errors dissoc sku-list-url))
       :fx [[:dispatch [::refresh-finished :success]]]})))

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

;; ===========================================================================
;; 013 Frontend — new feature events.
;;
;; Load events: set *-loading? true, fire api/get-xhrio, success handler
;; stores data + clears loading? + dissocs any stale api-error for the url,
;; failure → [::api-error url resp] + clears loading?.
;;
;; Mutation events (add/save/delete/settle/classify/import): POST/PUT/DELETE
;; via the matching api/*-xhrio, and on success re-dispatch the matching
;; ::load-* so the view refreshes. Failures → [::api-error url resp].
;;
;; A small generic pair keeps this section DRY: `simple-load` builds a load
;; effect map for a single top-level data-key, and `simple-loaded` / handlers
;; below store the result. Endpoints that need cache or per-key nesting keep
;; bespoke handlers (none here need the Phase-8 cache — these are settings /
;; mutable data that must always reflect the server).
;; ===========================================================================

(defn- load-fx
  "Build the {:db ... :http-xhrio ...} effect map for a plain GET load into a
   single top-level data-key. `data-key`/`loading-key` are namespaced kw db
   keys; success/failure are the event vectors to dispatch."
  [db url data-key loading-key on-success on-failure]
  {:db         (assoc db loading-key true)
   :http-xhrio (api/get-xhrio url on-success on-failure)})

(defn- loaded-db
  "Store `data` under `data-key`, clear `loading-key`, dissoc stale error for url."
  [db url data-key loading-key data]
  (-> db
      (assoc data-key data
             loading-key false)
      (update :marker/api-errors dissoc url)))

(defn- load-failed-fx
  "Clear `loading-key`, route the failure to ::api-error for `url`."
  [db url loading-key failure]
  {:db       (assoc db loading-key false)
   :dispatch [::api-error url failure]})

;; ---------------------------------------------------------------------------
;; 015: Tax config
;; ---------------------------------------------------------------------------

(def ^:private tax-url "/api/v1/settings/tax")

(rf/reg-event-fx ::load-tax-config
  (fn [{:keys [db]} [_ year]]
    (let [url (api/build-url tax-url (cond-> {} year (assoc :year year)))]
      (load-fx db url :marker/tax-config :marker/tax-config-loading?
               [::tax-config-loaded url] [::tax-config-load-failed url]))))

(rf/reg-event-db ::tax-config-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/tax-config :marker/tax-config-loading? data)))

(rf/reg-event-fx ::tax-config-load-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/tax-config-loading? failure)))

(rf/reg-event-fx ::save-tax-config
  (fn [_ [_ payload]]
    {:http-xhrio (api/put-xhrio tax-url payload
                                [::tax-config-saved]
                                [::api-error tax-url])}))

(rf/reg-event-fx ::tax-config-saved
  (fn [{:keys [db]} [_ resp]]
    ;; refresh the year we just saved (falls back to server-echoed :year)
    {:fx [[:dispatch [::load-tax-config (:year resp)]]]}))

;; ---------------------------------------------------------------------------
;; 015: OPEX
;; ---------------------------------------------------------------------------

(def ^:private opex-url "/api/v1/opex")

(rf/reg-event-fx ::load-opex
  (fn [{:keys [db]} [_ period]]
    (let [url (api/build-url opex-url (cond-> {} period (assoc :period period)))]
      {:db         (assoc db :marker/opex-loading? true
                             ;; remember the active period so mutation refreshes hit it
                             :marker/opex-period period)
       :http-xhrio (api/get-xhrio url [::opex-loaded url] [::opex-load-failed url])})))

(rf/reg-event-db ::opex-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/opex :marker/opex-loading? data)))

(rf/reg-event-fx ::opex-load-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/opex-loading? failure)))

(rf/reg-event-fx ::add-opex
  (fn [_ [_ row]]
    {:http-xhrio (api/post-xhrio opex-url row
                                 [::opex-mutated] [::api-error opex-url])}))

(rf/reg-event-fx ::delete-opex
  (fn [_ [_ id]]
    (let [url (str opex-url "/" id)]
      {:http-xhrio (api/delete-xhrio url [::opex-mutated] [::api-error url])})))

(rf/reg-event-fx ::opex-mutated
  ;; Re-load the currently displayed period after any add/delete.
  (fn [{:keys [db]} _]
    {:fx [[:dispatch [::load-opex (:marker/opex-period db)]]]}))

;; ---------------------------------------------------------------------------
;; 015: OPEX auto-rules
;; ---------------------------------------------------------------------------

(def ^:private opex-auto-rules-url "/api/v1/opex/auto-rules")

(rf/reg-event-fx ::load-opex-auto-rules
  (fn [{:keys [db]} _]
    (load-fx db opex-auto-rules-url
             :marker/opex-auto-rules :marker/opex-auto-rules-loading?
             [::opex-auto-rules-loaded opex-auto-rules-url]
             [::opex-auto-rules-load-failed opex-auto-rules-url])))

(rf/reg-event-db ::opex-auto-rules-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/opex-auto-rules :marker/opex-auto-rules-loading? data)))

(rf/reg-event-fx ::opex-auto-rules-load-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/opex-auto-rules-loading? failure)))

(rf/reg-event-fx ::add-opex-auto-rule
  (fn [_ [_ rule]]
    {:http-xhrio (api/post-xhrio opex-auto-rules-url rule
                                 [::opex-auto-rule-mutated]
                                 [::api-error opex-auto-rules-url])}))

(rf/reg-event-fx ::delete-opex-auto-rule
  (fn [_ [_ id]]
    (let [url (str opex-auto-rules-url "/" id)]
      {:http-xhrio (api/delete-xhrio url [::opex-auto-rule-mutated]
                                     [::api-error url])})))

(rf/reg-event-fx ::opex-auto-rule-mutated
  (fn [_ _]
    {:fx [[:dispatch [::load-opex-auto-rules]]]}))

;; ---------------------------------------------------------------------------
;; 016: User metrics
;; ---------------------------------------------------------------------------

(def ^:private user-metrics-url "/api/v1/metrics")

(rf/reg-event-fx ::load-user-metrics
  (fn [{:keys [db]} _]
    (load-fx db user-metrics-url
             :marker/user-metrics :marker/user-metrics-loading?
             [::user-metrics-loaded user-metrics-url]
             [::user-metrics-load-failed user-metrics-url])))

(rf/reg-event-db ::user-metrics-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/user-metrics :marker/user-metrics-loading? data)))

(rf/reg-event-fx ::user-metrics-load-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/user-metrics-loading? failure)))

(rf/reg-event-fx ::save-user-metric
  (fn [_ [_ m]]
    {:http-xhrio (api/post-xhrio user-metrics-url m
                                 [::user-metric-mutated]
                                 [::api-error user-metrics-url])}))

(rf/reg-event-fx ::delete-user-metric
  (fn [_ [_ id]]
    (let [url (str user-metrics-url "/" id)]
      {:http-xhrio (api/delete-xhrio url [::user-metric-mutated]
                                     [::api-error url])})))

(rf/reg-event-fx ::user-metric-mutated
  (fn [_ _]
    {:fx [[:dispatch [::load-user-metrics]]]}))

;; ---------------------------------------------------------------------------
;; 017: Bot settings
;; ---------------------------------------------------------------------------

(def ^:private bot-subs-url "/api/v1/bot/subscriptions")
(def ^:private bot-test-url "/api/v1/bot/test")

(rf/reg-event-fx ::load-bot-settings
  (fn [{:keys [db]} _]
    (load-fx db bot-subs-url
             :marker/bot-settings :marker/bot-settings-loading?
             [::bot-settings-loaded bot-subs-url]
             [::bot-settings-load-failed bot-subs-url])))

(rf/reg-event-db ::bot-settings-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/bot-settings :marker/bot-settings-loading? data)))

(rf/reg-event-fx ::bot-settings-load-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/bot-settings-loading? failure)))

(rf/reg-event-fx ::save-bot-subscription
  ;; New subscription (no :chat-id yet) → POST; existing → PUT to /:chat-id.
  (fn [_ [_ sub]]
    (let [chat-id (:chat-id sub)
          new?    (or (nil? chat-id) (= "" (str chat-id)))
          url     (if new? bot-subs-url (str bot-subs-url "/" chat-id))
          effect  (if new?
                    (api/post-xhrio url sub [::bot-subscription-mutated] [::api-error url])
                    (api/put-xhrio  url sub [::bot-subscription-mutated] [::api-error url]))]
      {:http-xhrio effect})))

(rf/reg-event-fx ::delete-bot-subscription
  (fn [_ [_ chat-id]]
    (let [url (str bot-subs-url "/" chat-id)]
      {:http-xhrio (api/delete-xhrio url [::bot-subscription-mutated]
                                     [::api-error url])})))

(rf/reg-event-fx ::bot-subscription-mutated
  (fn [_ _]
    {:fx [[:dispatch [::load-bot-settings]]]}))

(rf/reg-event-fx ::send-bot-test
  ;; Fire the test and stash the outcome so the UI can show delivery feedback.
  (fn [_ [_ chat-id]]
    {:http-xhrio (api/post-xhrio bot-test-url {:chat-id chat-id}
                                 [::bot-test-result]
                                 [::api-error bot-test-url])}))

(rf/reg-event-db ::bot-test-result
  (fn [db [_ resp]]
    (assoc db :marker/bot-test-result resp)))

;; ---------------------------------------------------------------------------
;; 017: Plan / fact + multipart import
;; ---------------------------------------------------------------------------

(def ^:private plan-sku-url "/api/v1/plan/sku")
(def ^:private plan-preview-url "/api/v1/plan/sku/preview")
(def ^:private plan-import-url "/api/v1/plan/sku/import")

(rf/reg-event-fx ::load-plan-fact
  (fn [{:keys [db]} [_ period mp]]
    (let [params (cond-> {}
                   period (assoc :period_month period)
                   mp     (assoc :marketplace (name (if (keyword? mp) mp (keyword mp)))))
          url    (api/build-url plan-sku-url params)]
      {:db         (assoc db :marker/plan-fact-loading? true
                             :marker/plan-fact-period period
                             :marker/plan-fact-mp     mp)
       :http-xhrio (api/get-xhrio url [::plan-fact-loaded url]
                                      [::plan-fact-load-failed url])})))

(rf/reg-event-db ::plan-fact-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/plan-fact :marker/plan-fact-loading? data)))

(rf/reg-event-fx ::plan-fact-load-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/plan-fact-loading? failure)))

(rf/reg-event-fx ::preview-plan-import
  (fn [{:keys [db]} [_ js-file period mp]]
    {:db         (assoc db :marker/plan-import-preview-loading? true)
     :http-xhrio (api/multipart-xhrio
                   plan-preview-url
                   (api/build-plan-import-form js-file period mp)
                   [::plan-import-preview-loaded plan-preview-url]
                   [::plan-import-preview-failed plan-preview-url])}))

(rf/reg-event-db ::plan-import-preview-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/plan-import-preview :marker/plan-import-preview-loading? data)))

(rf/reg-event-fx ::plan-import-preview-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/plan-import-preview-loading? failure)))

(rf/reg-event-fx ::commit-plan-import
  (fn [{:keys [db]} [_ js-file period mp]]
    {:db         (assoc db :marker/plan-import-preview-loading? true)
     :http-xhrio (api/multipart-xhrio
                   plan-import-url
                   (api/build-plan-import-form js-file period mp)
                   [::plan-import-committed period mp]
                   [::plan-import-preview-failed plan-import-url])}))

(rf/reg-event-fx ::plan-import-committed
  (fn [{:keys [db]} [_ period mp data]]
    {:db (-> db
             (assoc :marker/plan-import-preview data
                    :marker/plan-import-preview-loading? false)
             (update :marker/api-errors dissoc plan-import-url))
     ;; refresh the plan/fact table for the imported period after a commit
     :fx [[:dispatch [::load-plan-fact period mp]]]}))

;; ---------------------------------------------------------------------------
;; 019: Treasury — cashflow
;; ---------------------------------------------------------------------------

(def ^:private treasury-cashflow-url "/api/v1/treasury/cashflow")

(defn- treasury-params
  "Convert a filters map to query params. Values pass through as strings;
   keyword values (:group-by, :mode) render via name. Nils are dropped.
   account-ids (a seq) is comma-joined."
  [filters]
  (reduce-kv
    (fn [acc k v]
      (cond
        (nil? v)                                   acc
        (and (coll? v) (not (map? v)))             (assoc acc k (str/join "," (map str v)))
        (keyword? v)                               (assoc acc k (name v))
        :else                                      (assoc acc k v)))
    {}
    filters))

(rf/reg-event-fx ::load-treasury-cashflow
  (fn [{:keys [db]} [_ filters]]
    (let [url (api/build-url treasury-cashflow-url (treasury-params filters))]
      {:db         (assoc db :marker/treasury-cashflow-loading? true)
       :http-xhrio (api/get-xhrio url [::treasury-cashflow-loaded url]
                                      [::treasury-cashflow-load-failed url])})))

(rf/reg-event-db ::treasury-cashflow-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/treasury-cashflow :marker/treasury-cashflow-loading? data)))

(rf/reg-event-fx ::treasury-cashflow-load-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/treasury-cashflow-loading? failure)))

;; ---------------------------------------------------------------------------
;; 019: Treasury — operations (paged + filtered)
;; ---------------------------------------------------------------------------

(def ^:private treasury-ops-url "/api/v1/treasury/operations")

(rf/reg-event-fx ::load-treasury-operations
  (fn [{:keys [db]} [_ filters]]
    (let [url (api/build-url treasury-ops-url (treasury-params filters))]
      {:db         (assoc db :marker/treasury-operations-loading? true
                             ;; remember filters so a save can re-load the same view
                             :marker/treasury-operations-filters filters)
       :http-xhrio (api/get-xhrio url [::treasury-operations-loaded url]
                                      [::treasury-operations-load-failed url])})))

(rf/reg-event-db ::treasury-operations-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/treasury-operations :marker/treasury-operations-loading? data)))

(rf/reg-event-fx ::treasury-operations-load-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/treasury-operations-loading? failure)))

(rf/reg-event-fx ::save-treasury-operation
  (fn [_ [_ op]]
    {:http-xhrio (api/post-xhrio treasury-ops-url op
                                 [::treasury-operation-mutated]
                                 [::api-error treasury-ops-url])}))

(rf/reg-event-fx ::update-treasury-operation
  (fn [_ [_ id op]]
    (let [url (str treasury-ops-url "/" id)]
      {:http-xhrio (api/put-xhrio url op [::treasury-operation-mutated]
                                  [::api-error url])})))

(rf/reg-event-fx ::treasury-operation-mutated
  ;; Refresh both the operations list (using its stored filters) and the
  ;; cashflow view, since an op changes both.
  (fn [{:keys [db]} _]
    {:fx [[:dispatch [::load-treasury-operations
                      (:marker/treasury-operations-filters db)]]]}))

;; ---------------------------------------------------------------------------
;; 019: Treasury — accounts
;; ---------------------------------------------------------------------------

(def ^:private treasury-accounts-url "/api/v1/treasury/accounts")

(rf/reg-event-fx ::load-treasury-accounts
  (fn [{:keys [db]} _]
    (load-fx db treasury-accounts-url
             :marker/treasury-accounts :marker/treasury-accounts-loading?
             [::treasury-accounts-loaded treasury-accounts-url]
             [::treasury-accounts-load-failed treasury-accounts-url])))

(rf/reg-event-db ::treasury-accounts-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/treasury-accounts :marker/treasury-accounts-loading? data)))

(rf/reg-event-fx ::treasury-accounts-load-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/treasury-accounts-loading? failure)))

(rf/reg-event-fx ::add-treasury-account
  (fn [_ [_ a]]
    {:http-xhrio (api/post-xhrio treasury-accounts-url a
                                 [::treasury-account-mutated]
                                 [::api-error treasury-accounts-url])}))

(rf/reg-event-fx ::delete-treasury-account
  (fn [_ [_ id]]
    (let [url (str treasury-accounts-url "/" id)]
      {:http-xhrio (api/delete-xhrio url [::treasury-account-mutated]
                                     [::api-error url])})))

(rf/reg-event-fx ::treasury-account-mutated
  (fn [_ _]
    {:fx [[:dispatch [::load-treasury-accounts]]]}))

;; ---------------------------------------------------------------------------
;; 019: Treasury — counterparties
;; ---------------------------------------------------------------------------

(def ^:private treasury-counterparties-url "/api/v1/treasury/counterparties")

(rf/reg-event-fx ::load-treasury-counterparties
  (fn [{:keys [db]} _]
    (load-fx db treasury-counterparties-url
             :marker/treasury-counterparties :marker/treasury-counterparties-loading?
             [::treasury-counterparties-loaded treasury-counterparties-url]
             [::treasury-counterparties-load-failed treasury-counterparties-url])))

(rf/reg-event-db ::treasury-counterparties-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/treasury-counterparties
               :marker/treasury-counterparties-loading? data)))

(rf/reg-event-fx ::treasury-counterparties-load-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/treasury-counterparties-loading? failure)))

(rf/reg-event-fx ::add-treasury-counterparty
  (fn [_ [_ c]]
    {:http-xhrio (api/post-xhrio treasury-counterparties-url c
                                 [::treasury-counterparty-mutated]
                                 [::api-error treasury-counterparties-url])}))

(rf/reg-event-fx ::treasury-counterparty-mutated
  (fn [_ _]
    {:fx [[:dispatch [::load-treasury-counterparties]]]}))

;; ---------------------------------------------------------------------------
;; 019: Treasury — obligations (+ summary + dynamics)
;; ---------------------------------------------------------------------------

(def ^:private treasury-obligations-url "/api/v1/treasury/obligations")
(def ^:private treasury-obligations-summary-url "/api/v1/treasury/obligations/summary")
(def ^:private treasury-obligations-dynamics-url "/api/v1/treasury/obligations/dynamics")

(rf/reg-event-fx ::load-treasury-obligations
  (fn [{:keys [db]} [_ filters]]
    (let [url (api/build-url treasury-obligations-url (treasury-params filters))]
      {:db         (assoc db :marker/treasury-obligations-loading? true
                             :marker/treasury-obligations-filters filters)
       :http-xhrio (api/get-xhrio url [::treasury-obligations-loaded url]
                                      [::treasury-obligations-load-failed url])})))

(rf/reg-event-db ::treasury-obligations-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/treasury-obligations :marker/treasury-obligations-loading? data)))

(rf/reg-event-fx ::treasury-obligations-load-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/treasury-obligations-loading? failure)))

(rf/reg-event-fx ::add-treasury-obligation
  (fn [_ [_ o]]
    {:http-xhrio (api/post-xhrio treasury-obligations-url o
                                 [::treasury-obligation-mutated]
                                 [::api-error treasury-obligations-url])}))

(rf/reg-event-fx ::settle-treasury-obligation
  (fn [_ [_ id payload]]
    (let [url (str treasury-obligations-url "/" id "/settle")]
      {:http-xhrio (api/post-xhrio url payload
                                   [::treasury-obligation-mutated]
                                   [::api-error url])})))

(rf/reg-event-fx ::treasury-obligation-mutated
  ;; A settle/add changes the list, the summary AND the dynamics chart.
  (fn [{:keys [db]} _]
    {:fx [[:dispatch [::load-treasury-obligations
                      (:marker/treasury-obligations-filters db)]]
          [:dispatch [::load-treasury-obligations-summary]]
          [:dispatch [::load-treasury-obligations-dynamics]]]}))

(rf/reg-event-fx ::load-treasury-obligations-summary
  (fn [{:keys [db]} [_ mode]]
    (let [url (api/build-url treasury-obligations-summary-url
                             (cond-> {} mode (assoc :mode (name mode))))]
      {:db         (assoc db :marker/treasury-obligations-summary-loading? true)
       :http-xhrio (api/get-xhrio url [::treasury-obligations-summary-loaded url]
                                      [::treasury-obligations-summary-failed url])})))

(rf/reg-event-db ::treasury-obligations-summary-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/treasury-obligations-summary
               :marker/treasury-obligations-summary-loading? data)))

(rf/reg-event-fx ::treasury-obligations-summary-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/treasury-obligations-summary-loading? failure)))

(rf/reg-event-fx ::load-treasury-obligations-dynamics
  (fn [{:keys [db]} [_ mode]]
    (let [url (api/build-url treasury-obligations-dynamics-url
                             (cond-> {} mode (assoc :mode (name mode))))]
      {:db         (assoc db :marker/treasury-obligations-dynamics-loading? true)
       :http-xhrio (api/get-xhrio url [::treasury-obligations-dynamics-loaded url]
                                      [::treasury-obligations-dynamics-failed url])})))

(rf/reg-event-db ::treasury-obligations-dynamics-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/treasury-obligations-dynamics
               :marker/treasury-obligations-dynamics-loading? data)))

(rf/reg-event-fx ::treasury-obligations-dynamics-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/treasury-obligations-dynamics-loading? failure)))

;; ---------------------------------------------------------------------------
;; 019: Treasury — auto-rules + classify
;; ---------------------------------------------------------------------------

(def ^:private treasury-auto-rules-url "/api/v1/treasury/auto-rules")
(def ^:private treasury-classify-url "/api/v1/treasury/auto-rules/classify")

(rf/reg-event-fx ::load-treasury-auto-rules
  (fn [{:keys [db]} _]
    (load-fx db treasury-auto-rules-url
             :marker/treasury-auto-rules :marker/treasury-auto-rules-loading?
             [::treasury-auto-rules-loaded treasury-auto-rules-url]
             [::treasury-auto-rules-load-failed treasury-auto-rules-url])))

(rf/reg-event-db ::treasury-auto-rules-loaded
  (fn [db [_ url data]]
    (loaded-db db url :marker/treasury-auto-rules :marker/treasury-auto-rules-loading? data)))

(rf/reg-event-fx ::treasury-auto-rules-load-failed
  (fn [{:keys [db]} [_ url failure]]
    (load-failed-fx db url :marker/treasury-auto-rules-loading? failure)))

(rf/reg-event-fx ::add-treasury-auto-rule
  (fn [_ [_ r]]
    {:http-xhrio (api/post-xhrio treasury-auto-rules-url r
                                 [::treasury-auto-rule-mutated]
                                 [::api-error treasury-auto-rules-url])}))

(rf/reg-event-fx ::treasury-auto-rule-mutated
  (fn [_ _]
    {:fx [[:dispatch [::load-treasury-auto-rules]]]}))

(rf/reg-event-fx ::classify-treasury
  ;; Run auto-classification, stash the result for UI feedback, then refresh
  ;; both the operations list and cashflow (classification changes categories).
  (fn [{:keys [db]} _]
    {:db         (assoc db :marker/treasury-classify-result-loading? true)
     :http-xhrio (api/post-xhrio treasury-classify-url {}
                                 [::treasury-classified]
                                 [::treasury-classify-failed])}))

(rf/reg-event-fx ::treasury-classified
  (fn [{:keys [db]} [_ result]]
    {:db (-> db
             (assoc :marker/treasury-classify-result result
                    :marker/treasury-classify-result-loading? false)
             (update :marker/api-errors dissoc treasury-classify-url))
     :fx [[:dispatch [::load-treasury-operations
                      (:marker/treasury-operations-filters db)]]]}))

(rf/reg-event-fx ::treasury-classify-failed
  (fn [{:keys [db]} [_ failure]]
    {:db       (assoc db :marker/treasury-classify-result-loading? false)
     :dispatch [::api-error treasury-classify-url failure]}))
