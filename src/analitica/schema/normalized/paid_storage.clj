(ns analitica.schema.normalized.paid-storage
  "Canonical PaidStorageRow — WB paid-storage daily charge.
   See docs/data-dictionary.md#paid_storage."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def PaidStorageRow
  [:map {:closed false}
   [:date        :string]
   [:article     :string]
   [:barcode     :string]
   [:warehouse   :string]
   [:cost        [:and [:or :int :double] [:>= 0]]]
   [:marketplace [:enum :wb :ozon :ym]]

   [:nm-id          {:optional true} [:maybe [:or :int :double]]]
   [:volume         {:optional true} [:maybe [:or :int :double]]]
   [:barcodes-count {:optional true} [:maybe [:or :int :double]]]
   [:synced-at      {:optional true} [:maybe :string]]])

(def ^:private validator (m/validator PaidStorageRow))
(def ^:private explainer (m/explainer PaidStorageRow))

(defn valid? [row] (validator row))
(defn explain [row] (some-> (explainer row) me/humanize))
(defn validate-rows [rows]
  (reduce (fn [{:keys [ok bad] :as acc} row]
            (if (validator row)
              (update acc :ok conj row)
              (update acc :bad conj {:row row :error (explain row)})))
          {:ok [] :bad []}
          rows))
