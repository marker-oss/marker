(ns marker.state.db
  "Default app-db map for the Marker SPA.
   All keys are namespaced under :marker/ to avoid collisions with
   re-frame internals and future library keys.")

(def all-mps [:wb :ozon :ym])

(def default-db
  {:marker/page              :pulse
   :marker/mp-filter         all-mps
   :marker/period            "Последние 30 дней"
   :marker/compare           false
   :marker/theme             "light"
   :marker/density           "standard"
   :marker/sidebar-collapsed false
   :marker/cmdk-open         false
   :marker/sheet-sku         nil
   :marker/sync-state        nil
   :marker/tweaks-open       false

   ;; Phase 8: API data slices — nil means "not yet loaded"
   :marker/pulse-data        nil
   :marker/pulse-loading?    false
   :marker/pnl-data          nil
   :marker/pnl-loading?      false
   :marker/sku-list-data     nil
   :marker/sku-list-envelope nil   ; LT3: honesty envelope, separate from the :skus vector
   :marker/sku-list-loading? false
   :marker/sku-detail-data   {}    ; map of {sku-id {:data <map> :loading? <bool>}}

   ;; Phase 8: cache — {[page mp-filter period compare] data-snapshot}
   :marker/cache             {}

   ;; Phase 8: API errors — {url {:message str :status num}}
   :marker/api-errors        {}

   ;; Phase 9: Generic reports — keyed by report-type keyword
   ;; {report-type {:report-type kw :columns [...] :rows [...] :totals {} :schema {} :compare {}}}
   :marker/reports-data      {}
   ;; {report-type bool}
   :marker/reports-loading?  {}

   ;; Phase 3 (UI restructure): Chart.js datasets per report-type.
   ;; {report-type {:labels [...] :datasets [...]}}
   :marker/report-chart-data     {}
   :marker/report-chart-loading? {}

   ;; Phase 2 (UI restructure): Stocks
   :marker/stocks-overview          nil
   :marker/stocks-overview-loading? false
   ;; {article-id {:loading? bool :data <map>}}
   :marker/stocks-article-data      {}

   ;; Settings page — operator marketplace credentials
   ;; {:data <server settings map> :status {<mp> {:testing? bool :saving? bool :verdict {:valid? bool :detail str} :saved? bool :error str}}}
   :marker/settings {:data nil :status {}}})
