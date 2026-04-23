(ns analitica.schema.normalized.cost-prices
  "Canonical CostPriceRow — per-(article, barcode) self-cost from 1C.
   See docs/data-dictionary.md#cost_prices."
  (:require [malli.core :as m]
            [malli.error :as me]
            [analitica.schema.util :as schema-util]))

(def CostPriceRow
  [:map {:closed false}
   [:article    :string]
   [:barcode    :string]
   [:cost-price [:and [:or :int :double] [:>= 0]]]

   [:nomenclature {:optional true} [:maybe :string]]
   [:color        {:optional true} [:maybe :string]]
   [:quantity-1c  {:optional true} [:maybe [:or :int :double]]]
   [:updated-at   {:optional true} [:maybe :string]]])

(def ^:private validator (m/validator CostPriceRow))
(def ^:private explainer (m/explainer CostPriceRow))

(defn valid? [row] (validator row))
(defn explain [row] (some-> (explainer row) me/humanize))
(defn validate-rows [rows]
  (schema-util/validate-rows validator explain rows))
