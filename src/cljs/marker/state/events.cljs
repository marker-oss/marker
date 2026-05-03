(ns marker.state.events
  "re-frame event handlers for the Marker SPA.
   Includes initialize-db (with localStorage merge), all setters,
   and tweaks persistence via localStorage[\"marker/tweaks\"]."
  (:require [re-frame.core :as rf]
            [marker.state.db :as db]))

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

(rf/reg-event-db ::set-theme
  (fn [db [_ theme]]
    (let [db' (assoc db :marker/theme theme)]
      (persist-tweaks! db')
      db')))

(rf/reg-event-db ::set-density
  (fn [db [_ density]]
    (let [db' (assoc db :marker/density density)]
      (persist-tweaks! db')
      db')))

(rf/reg-event-db ::toggle-sidebar
  (fn [db _]
    (let [db' (update db :marker/sidebar-collapsed not)]
      (persist-tweaks! db')
      db')))

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
