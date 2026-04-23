(ns analitica.schema.util
  "Shared helpers for Malli schema validation across normalized tables.

   `validate-rows` is the common splitter used by every per-table
   namespace under analitica.schema.normalized.*.  Each table wraps
   its own pre-built validator and explainer (not the raw schema) so
   schema compilation happens once at namespace load, not per call.")

(defn validate-rows
  "Split `rows` into `{:ok [...] :bad [{:row <r> :error <humanized>} ...]}`
   using the pre-built `validator-fn` (row → boolean) and `explain-fn`
   (row → humanized error map or nil)."
  [validator-fn explain-fn rows]
  (reduce (fn [{:keys [ok bad] :as acc} row]
            (if (validator-fn row)
              (update acc :ok conj row)
              (update acc :bad conj {:row row :error (explain-fn row)})))
          {:ok [] :bad []}
          rows))
