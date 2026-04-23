(ns analitica.schema.normalized
  "Aggregator for all L1 normalized-table Malli schemas.

   Each per-table namespace under analitica.schema.normalized.* exposes:
     - <Table>Row   — the Malli schema
     - valid?       — predicate
     - explain      — humanized error map
     - validate-rows — {:ok [...] :bad [...]}

   See docs/data-dictionary.md for canonical semantics."
  (:require [analitica.schema.normalized.finance :as finance]
            [analitica.schema.normalized.sales :as sales]
            [analitica.schema.normalized.stocks :as stocks]
            [analitica.schema.normalized.cost-prices :as cost-prices]
            [analitica.schema.normalized.ad-stats :as ad-stats]
            [analitica.schema.normalized.paid-storage :as paid-storage]
            [analitica.schema.normalized.region-sales :as region-sales]
            [analitica.schema.normalized.cash-flow-periods :as cash-flow-periods]))

(def tables
  "All registered normalized tables."
  #{:finance :sales :stocks :cost-prices :ad-stats :paid-storage
    :region-sales :cash-flow-periods})

(def schemas
  "Schema per table keyword."
  {:finance           finance/FinanceRow
   :sales             sales/SalesRow
   :stocks            stocks/StocksRow
   :cost-prices       cost-prices/CostPriceRow
   :ad-stats          ad-stats/AdStatsRow
   :paid-storage      paid-storage/PaidStorageRow
   :region-sales      region-sales/RegionSalesRow
   :cash-flow-periods cash-flow-periods/CashFlowPeriodRow})

(defn valid?
  "Dispatch on table keyword. Returns true iff row satisfies the table's schema.
   Throws ex-info on unknown table keyword."
  [table row]
  (case table
    :finance           (finance/valid? row)
    :sales             (sales/valid? row)
    :stocks            (stocks/valid? row)
    :cost-prices       (cost-prices/valid? row)
    :ad-stats          (ad-stats/valid? row)
    :paid-storage      (paid-storage/valid? row)
    :region-sales      (region-sales/valid? row)
    :cash-flow-periods (cash-flow-periods/valid? row)
    (throw (ex-info "Unknown normalized table"
                    {:table table :known tables}))))

(defn validate-rows
  "Dispatch on table keyword. Throws ex-info on unknown table keyword."
  [table rows]
  (case table
    :finance           (finance/validate-rows rows)
    :sales             (sales/validate-rows rows)
    :stocks            (stocks/validate-rows rows)
    :cost-prices       (cost-prices/validate-rows rows)
    :ad-stats          (ad-stats/validate-rows rows)
    :paid-storage      (paid-storage/validate-rows rows)
    :region-sales      (region-sales/validate-rows rows)
    :cash-flow-periods (cash-flow-periods/validate-rows rows)
    (throw (ex-info "Unknown normalized table"
                    {:table table :known tables}))))
