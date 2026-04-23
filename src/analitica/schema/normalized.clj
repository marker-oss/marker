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
            [analitica.schema.normalized.cost-prices :as cost-prices]))

(def tables #{:finance :sales :stocks :cost-prices})

(def schemas
  {:finance     finance/FinanceRow
   :sales       sales/SalesRow
   :stocks      stocks/StocksRow
   :cost-prices cost-prices/CostPriceRow})

(defn valid?
  "Dispatch on table keyword. Returns true iff row satisfies the table's schema."
  [table row]
  (case table
    :finance     (finance/valid? row)
    :sales       (sales/valid? row)
    :stocks      (stocks/valid? row)
    :cost-prices (cost-prices/valid? row)))

(defn validate-rows
  "Dispatch on table keyword."
  [table rows]
  (case table
    :finance     (finance/validate-rows rows)
    :sales       (sales/validate-rows rows)
    :stocks      (stocks/validate-rows rows)
    :cost-prices (cost-prices/validate-rows rows)))
