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
   :marker/settings {:data nil :status {}}

   ;; ---------------------------------------------------------------------
   ;; 013 Frontend — new feature data slices.
   ;; Each data key nil = "not yet loaded"; paired *-loading? starts false.
   ;; ---------------------------------------------------------------------

   ;; 015: tax config + OPEX
   :marker/tax-config                 nil
   :marker/tax-config-loading?        false
   :marker/opex                       nil
   :marker/opex-loading?              false
   :marker/opex-auto-rules            nil
   :marker/opex-auto-rules-loading?   false

   ;; 016: user-defined metrics
   :marker/user-metrics               nil
   :marker/user-metrics-loading?      false

   ;; 017: Telegram/MAX bot settings
   :marker/bot-settings               nil
   :marker/bot-settings-loading?      false

   ;; 017: plan/fact + import preview
   :marker/plan-fact                  nil
   :marker/plan-fact-loading?         false
   :marker/plan-import-preview        nil
   :marker/plan-import-preview-loading? false

   ;; 019: treasury (DECIMAL cells arrive as "0.00" strings — never parseFloat
   ;; for storage/aggregation, only for display formatting)
   :marker/treasury-cashflow                    nil
   :marker/treasury-cashflow-loading?           false
   :marker/treasury-operations                  nil
   :marker/treasury-operations-loading?         false
   :marker/treasury-accounts                    nil
   :marker/treasury-accounts-loading?           false
   :marker/treasury-counterparties              nil
   :marker/treasury-counterparties-loading?     false
   :marker/treasury-obligations-summary         nil
   :marker/treasury-obligations-summary-loading? false
   :marker/treasury-obligations-dynamics        nil
   :marker/treasury-obligations-dynamics-loading? false
   :marker/treasury-obligations                 nil
   :marker/treasury-obligations-loading?        false
   :marker/treasury-auto-rules                  nil
   :marker/treasury-auto-rules-loading?         false
   ;; last classify {:classified ...} for UI feedback
   :marker/treasury-classify-result             nil
   :marker/treasury-classify-result-loading?    false})
