(ns analitica.schema.normalized
  "Aggregator for all L1 normalized-table Malli schemas.

   Each per-table namespace under analitica.schema.normalized.* exposes:
     - <Table>Row   — the Malli schema
     - valid?       — predicate
     - explain      — humanized error map
     - validate-rows — {:ok [...] :bad [...]}

   See docs/data-dictionary.md for canonical semantics.")

(def tables
  "Registered normalized tables. Populated by per-table tasks."
  #{})
