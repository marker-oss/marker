(ns analitica.domain.finance-row
  "DEPRECATED: moved to analitica.schema.normalized.finance. This
   namespace re-exports the public API for backward compatibility
   during the Phase-1 L1 audit migration. Callers should migrate to
   the new namespace; this shim will be removed after all callers
   are updated."
  (:require [analitica.schema.normalized.finance :as canon]))

(def FinanceRow canon/FinanceRow)
(def valid? canon/valid?)
(def explain canon/explain)
(def validate-rows canon/validate-rows)
(def summarize-bad canon/summarize-bad)
