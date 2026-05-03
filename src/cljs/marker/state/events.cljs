(ns marker.state.events
  "re-frame event handlers for the Marker SPA.
   Includes initialize-db (with localStorage merge), all setters,
   tweaks persistence via localStorage[\"marker/tweaks\"],
   and Phase 8 API load events."
  (:require [re-frame.core :as rf]
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

(rf/reg-event-db ::set-mp-filter
  (fn [db [_ mps]]
    (assoc db :marker/mp-filter mps)))

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

(rf/reg-event-db ::pulse-data-loaded
  (fn [db [_ ckey data]]
    (-> db
        (assoc :marker/pulse-data    data
               :marker/pulse-loading? false)
        (assoc-in [:marker/cache ckey] data)
        (update :marker/api-errors dissoc pulse-url))))

(rf/reg-event-fx ::pulse-load-failed
  (fn [{:keys [db]} [_ failure]]
    {:db       (assoc db :marker/pulse-loading? false)
     :dispatch [::api-error pulse-url failure]}))

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

(rf/reg-event-db ::pnl-data-loaded
  (fn [db [_ ckey data]]
    (-> db
        (assoc :marker/pnl-data    data
               :marker/pnl-loading? false)
        (assoc-in [:marker/cache ckey] data)
        (update :marker/api-errors dissoc pnl-url))))

(rf/reg-event-fx ::pnl-load-failed
  (fn [{:keys [db]} [_ failure]]
    {:db       (assoc db :marker/pnl-loading? false)
     :dispatch [::api-error pnl-url failure]}))

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

(rf/reg-event-db ::sku-list-data-loaded
  (fn [db [_ ckey data]]
    (-> db
        (assoc :marker/sku-list-data    (:skus data)
               :marker/sku-list-loading? false)
        (assoc-in [:marker/cache ckey] (:skus data))
        (update :marker/api-errors dissoc sku-list-url))))

(rf/reg-event-fx ::sku-list-load-failed
  (fn [{:keys [db]} [_ failure]]
    {:db       (assoc db :marker/sku-list-loading? false)
     :dispatch [::api-error sku-list-url failure]}))

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
(rf/reg-event-fx ::open-sheet-and-load
  (fn [{:keys [db]} [_ sku-id]]
    {:db       (assoc db :marker/sheet-sku sku-id)
     :dispatch [::load-sku-detail sku-id]}))

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
;; Phase 8: Sync / refresh — clear cache + reload current page
;; ---------------------------------------------------------------------------

(rf/reg-event-fx ::sync-and-refresh
  ;; Clear cache, show sync running state, reload data for the current page.
  (fn [{:keys [db]} _]
    (let [page (:marker/page db)
          fs   {:mp-filter (:marker/mp-filter db)
                :period    (:marker/period    db)
                :compare   (:marker/compare   db)}
          load-evt (case page
                     :pulse    [::load-pulse    fs]
                     :pnl      [::load-pnl      fs]
                     :products [::load-sku-list fs]
                     nil)]
      (cond->
       {:db (-> db
                (assoc :marker/cache {})
                (assoc :marker/sync-state
                       {:kind     :running
                        :section  (case page
                                    :pulse    "Pulse"
                                    :pnl      "P&L"
                                    :products "Товары"
                                    "данных")
                        :elapsed  "0s"
                        :progress 30}))}
        load-evt (assoc :dispatch load-evt)))))
