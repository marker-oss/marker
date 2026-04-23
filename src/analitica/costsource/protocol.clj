(ns analitica.costsource.protocol
  "Interface for cost-price data sources. Each provider (1C CSV, 1C API,
   Мойсклад API, …) implements this protocol so the ingest pipeline in
   `analitica.costsource/ingest!` stays source-agnostic.

   A raw record produced by `fetch-cost-prices` carries at minimum:
     :article        string
     :barcode        string or \"\"
     :cost-price     number ≥ 0
   Optional but canonical:
     :nomenclature   string       — human-readable product name
     :color          string       — variant color
     :characteristic string       — size or other per-variant attribute
     :quantity       number       — stock-on-hand at source (informational)
   Providers may carry extra keys; the downstream transform/validate
   pipeline normalises to the canonical CostPriceRow shape.")

(defprotocol CostSource
  (source-id [this]
    "Stable keyword identifying this provider, e.g. :cost/1c-csv, :cost/moysklad-api.
     Used as `raw_data.source` for audit/replay.")
  (fetch-cost-prices [this]
    "Return a lazy/eager seq of raw cost-price records (see ns doc for shape).
     Throws ex-info on unrecoverable fetch failures (network down, file
     missing, malformed root); per-row issues should surface via empty /
     skipped rows and be detected in the validate step."))
