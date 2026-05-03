(ns marker.state.db
  "Default app-db map for the Marker SPA.
   All keys are namespaced under :marker/ to avoid collisions with
   re-frame internals and future library keys.")

(def default-db
  {:marker/page              :pulse
   :marker/mp-filter         [:wb :ozon :ym]
   :marker/period            "Последние 30 дней"
   :marker/compare           false
   :marker/theme             "light"
   :marker/density           "standard"
   :marker/sidebar-collapsed false
   :marker/cmdk-open         false
   :marker/sheet-sku         nil
   :marker/sync-state        nil
   :marker/tweaks-open       false})
