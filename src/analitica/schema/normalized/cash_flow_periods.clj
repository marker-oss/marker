(ns analitica.schema.normalized.cash-flow-periods
  "Canonical CashFlowPeriodRow — Ozon period-level cash flow statement.
   See docs/data-dictionary.md#cash_flow_periods."
  (:require [malli.core :as m]
            [malli.error :as me]
            [analitica.schema.util :as schema-util]))

(def ^:private num-default-zero [:or :int :double])

(def CashFlowPeriodRow
  [:map {:closed false}
   [:source       [:enum :ozon]]
   [:period-begin :string]
   [:period-end   :string]
   [:synced-at    :string]

   [:id                  {:optional true} [:maybe [:or :int :double]]]
   [:orders-amount       {:optional true} [:maybe num-default-zero]]
   [:returns-amount      {:optional true} [:maybe num-default-zero]]
   [:commission-amount   {:optional true} [:maybe num-default-zero]]
   [:delivery-amount     {:optional true} [:maybe num-default-zero]]
   [:delivery-logistics  {:optional true} [:maybe num-default-zero]]
   [:return-amount       {:optional true} [:maybe num-default-zero]]
   [:return-logistics    {:optional true} [:maybe num-default-zero]]
   [:storage             {:optional true} [:maybe num-default-zero]]
   [:packaging           {:optional true} [:maybe num-default-zero]]
   [:warehouse-movement  {:optional true} [:maybe num-default-zero]]
   [:returns-cargo       {:optional true} [:maybe num-default-zero]]
   [:subscription        {:optional true} [:maybe num-default-zero]]
   [:fines               {:optional true} [:maybe num-default-zero]]
   [:other-services      {:optional true} [:maybe num-default-zero]]
   [:acquiring           {:optional true} [:maybe num-default-zero]]
   [:corrections         {:optional true} [:maybe num-default-zero]]
   [:compensation        {:optional true} [:maybe num-default-zero]]
   [:payment             {:optional true} [:maybe num-default-zero]]
   [:begin-balance       {:optional true} [:maybe num-default-zero]]
   [:end-balance         {:optional true} [:maybe num-default-zero]]
   [:invoice-transfer    {:optional true} [:maybe num-default-zero]]])

(def ^:private validator (m/validator CashFlowPeriodRow))
(def ^:private explainer (m/explainer CashFlowPeriodRow))

(defn valid? [row] (validator row))
(defn explain [row] (some-> (explainer row) me/humanize))
(defn validate-rows [rows]
  (schema-util/validate-rows validator explain rows))
