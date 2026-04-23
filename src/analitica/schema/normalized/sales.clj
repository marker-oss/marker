(ns analitica.schema.normalized.sales
  "Canonical SalesRow — per-order / per-return events.
   See docs/data-dictionary.md#sales for canonical semantics."
  (:require [malli.core :as m]
            [malli.error :as me]
            [analitica.schema.util :as schema-util]))

(def SalesRow
  [:map {:closed false}
   [:sale-id     :string]
   [:date        :string]
   [:article     :string]
   [:type        [:enum :sale :return :cancel]]
   [:marketplace [:enum :wb :ozon :ym]]

   [:nm-id           {:optional true} [:maybe [:or :int :double]]]
   [:barcode         {:optional true} [:maybe :string]]
   [:tech-size       {:optional true} [:maybe :string]]
   [:subject         {:optional true} [:maybe :string]]
   [:category        {:optional true} [:maybe :string]]
   [:brand           {:optional true} [:maybe :string]]
   [:warehouse       {:optional true} [:maybe :string]]
   [:region          {:optional true} [:maybe :string]]
   [:total-price     {:optional true} [:maybe [:or :int :double]]]
   [:for-pay         {:optional true} [:maybe [:or :int :double]]]
   [:finished-price  {:optional true} [:maybe [:or :int :double]]]
   [:price-with-disc {:optional true} [:maybe [:or :int :double]]]
   [:synced-at       {:optional true} [:maybe :string]]])

(def ^:private validator (m/validator SalesRow))
(def ^:private explainer (m/explainer SalesRow))

(defn valid? [row] (validator row))
(defn explain [row] (some-> (explainer row) me/humanize))

(defn validate-rows [rows]
  (schema-util/validate-rows validator explain rows))
