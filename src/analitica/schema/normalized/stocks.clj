(ns analitica.schema.normalized.stocks
  "Canonical StocksRow — warehouse stock snapshots.
   See docs/data-dictionary.md#stocks."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def StocksRow
  [:map {:closed false}
   [:article     :string]
   [:marketplace [:enum :wb :ozon :ym]]

   [:id            {:optional true} [:maybe [:or :int :double]]]
   [:nm-id         {:optional true} [:maybe [:or :int :double]]]
   [:barcode       {:optional true} [:maybe :string]]
   [:tech-size     {:optional true} [:maybe :string]]
   [:subject       {:optional true} [:maybe :string]]
   [:category      {:optional true} [:maybe :string]]
   [:brand         {:optional true} [:maybe :string]]
   [:warehouse     {:optional true} [:maybe :string]]
   [:quantity      {:optional true} [:maybe [:or :int :double]]]
   [:quantity-full {:optional true} [:maybe [:or :int :double]]]
   [:in-way-to     {:optional true} [:maybe [:or :int :double]]]
   [:in-way-from   {:optional true} [:maybe [:or :int :double]]]
   [:synced-at     {:optional true} [:maybe :string]]])

(def ^:private validator (m/validator StocksRow))
(def ^:private explainer (m/explainer StocksRow))

(defn valid? [row] (validator row))
(defn explain [row] (some-> (explainer row) me/humanize))
(defn validate-rows [rows]
  (reduce (fn [{:keys [ok bad] :as acc} row]
            (if (validator row)
              (update acc :ok conj row)
              (update acc :bad conj {:row row :error (explain row)})))
          {:ok [] :bad []}
          rows))
