(ns analitica.schema.normalized.region-sales
  "Canonical RegionSalesRow — per-region sales aggregates.
   See docs/data-dictionary.md#region_sales."
  (:require [malli.core :as m]
            [malli.error :as me]
            [analitica.schema.util :as schema-util]))

(def RegionSalesRow
  [:map {:closed false}
   [:nm-id     [:or :int :double]]
   [:article   :string]
   [:region    :string]
   [:city      :string]
   [:date-from :string]
   [:date-to   :string]

   [:country       {:optional true} [:maybe :string]]
   [:fo            {:optional true} [:maybe :string]]
   [:qty           {:optional true} [:maybe [:or :int :double]]]
   [:sum-price     {:optional true} [:maybe [:or :int :double]]]
   [:sum-price-prc {:optional true} [:maybe [:or :int :double]]]
   [:synced-at     {:optional true} [:maybe :string]]])

(def ^:private validator (m/validator RegionSalesRow))
(def ^:private explainer (m/explainer RegionSalesRow))

(defn valid? [row] (validator row))
(defn explain [row] (some-> (explainer row) me/humanize))
(defn validate-rows [rows]
  (schema-util/validate-rows validator explain rows))
