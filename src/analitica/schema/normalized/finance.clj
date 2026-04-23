(ns analitica.schema.normalized.finance
  "Canonical FinanceRow — the contract between transform (L3) and
   materialize (L4). See docs/data-dictionary.md#finance for canonical
   semantics.

   Rows carrying extra keys are accepted ({:closed false}); missing
   required keys are rejected. Validation is non-fatal by default:
   `validate-rows` returns bad rows alongside good ones so a schema
   drift never silently drops data."
  (:require [malli.core :as m]
            [malli.error :as me]
            [clojure.string]
            [analitica.schema.util :as schema-util]))

(def FinanceRow
  "Minimal contract every transform must satisfy. Identity + the three
   numeric pillars (for-pay, retail-amount, quantity) are required.
   Every other field is optional-nilable because not every marketplace
   has every field (see data-dictionary.md §finance.source-mapping
   for the coverage matrix)."
  [:map {:closed false}
   [:marketplace    [:enum :wb :ozon :ym]]
   [:rrd-id         [:or :int :double]]
   [:date-from      :string]
   [:date-to        :string]
   ;; :event-date is required going forward (2026-04-23 migration).
   ;; Legacy rows that pre-date it may have nil until re-materialized;
   ;; optional-nilable during the rollout window.
   [:event-date     {:optional true} [:maybe :string]]
   [:article        [:maybe :string]]
   [:operation      :string]
   [:quantity       [:maybe [:or :int :double]]]
   [:for-pay        [:or :int :double]]

   [:report-id          {:optional true} [:maybe [:or :int :double]]]
   [:nm-id              {:optional true} [:maybe [:or :int :double]]]
   [:barcode            {:optional true} [:maybe :string]]
   [:subject            {:optional true} [:maybe :string]]
   [:brand              {:optional true} [:maybe :string]]
   [:doc-type           {:optional true} [:maybe :string]]
   [:retail-price       {:optional true} [:maybe [:or :int :double]]]
   [:retail-amount      {:optional true} [:maybe [:or :int :double]]]
   [:sale-percent       {:optional true} [:maybe [:or :int :double]]]
   [:commission-pct     {:optional true} [:maybe [:or :int :double]]]
   [:wb-commission      {:optional true} [:maybe [:or :int :double]]]
   [:wb-reward          {:optional true} [:maybe [:or :int :double]]]
   [:wb-kvw-prc         {:optional true} [:maybe [:or :int :double]]]
   [:spp-prc            {:optional true} [:maybe [:or :int :double]]]
   [:price-with-disc    {:optional true} [:maybe [:or :int :double]]]
   [:delivery-amount    {:optional true} [:maybe [:or :int :double]]]
   [:return-amount      {:optional true} [:maybe [:or :int :double]]]
   [:delivery-cost      {:optional true} [:maybe [:or :int :double]]]
   [:penalty            {:optional true} [:maybe [:or :int :double]]]
   [:storage-fee        {:optional true} [:maybe [:or :int :double]]]
   [:acceptance         {:optional true} [:maybe [:or :int :double]]]
   [:additional-payment {:optional true} [:maybe [:or :int :double]]]
   [:deduction          {:optional true} [:maybe [:or :int :double]]]
   [:acquiring-fee      {:optional true} [:maybe [:or :int :double]]]
   [:ad-cost            {:optional true} [:maybe [:or :int :double]]]
   [:synced-at          {:optional true} [:maybe :string]]])

(def ^:private validator (m/validator FinanceRow))
(def ^:private explainer (m/explainer FinanceRow))

(defn valid? [row] (validator row))

(defn explain [row] (some-> (explainer row) me/humanize))

(defn validate-rows [rows]
  (schema-util/validate-rows validator explain rows))

(defn summarize-bad
  [bad]
  (->> bad
       (group-by (fn [{:keys [row error]}]
                   [(or (:marketplace row) :unknown)
                    (first (keys error))]))
       (map (fn [[[mp k] xs]]
              (str "  " (name mp) " → " (name (or k :unknown))
                   ": " (count xs) " rows")))
       (clojure.string/join "\n")))
