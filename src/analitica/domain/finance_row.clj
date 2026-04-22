(ns analitica.domain.finance-row
  "Canonical finance-row schema — the contract between transform (L3) and
   materialize (L4). See docs/canonical-finance.md §4 for semantics.

   Validation is non-fatal by default: `validate-rows` returns bad rows
   alongside good ones. Materialize-finance! logs warnings but still
   writes every row it received, so a schema drift never loses data
   that is already in raw_data."
  (:require [malli.core :as m]
            [malli.error :as me]))

;; ---------------------------------------------------------------------------
;; Schema
;; ---------------------------------------------------------------------------

(def FinanceRow
  "Minimal contract every transform must satisfy.
   Required fields — identity + the three numeric pillars (for_pay,
   retail_amount, quantity). Everything else is optional-nilable,
   because not every marketplace has every field (see §6.4 of
   canonical-finance.md for the coverage matrix)."
  [:map {:closed false}
   [:marketplace    [:enum :wb :ozon :ym]]
   [:rrd-id         [:or :int :double]]
   [:date-from      :string]
   [:date-to        :string]
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
   [:ad-cost            {:optional true} [:maybe [:or :int :double]]]])

(def ^:private validator  (m/validator FinanceRow))
(def ^:private explainer  (m/explainer FinanceRow))

;; ---------------------------------------------------------------------------
;; Pure API
;; ---------------------------------------------------------------------------

(defn valid?
  "Predicate: does `row` satisfy FinanceRow?"
  [row]
  (validator row))

(defn explain
  "Return humanized explanation of why `row` fails, or nil if valid."
  [row]
  (some-> (explainer row) me/humanize))

(defn validate-rows
  "Partition `rows` into {:ok [...] :bad [...]} where each bad entry is
   {:row ... :error humanized-map}. Pure — no side effects."
  [rows]
  (reduce (fn [{:keys [ok bad] :as acc} row]
            (if (validator row)
              (update acc :ok conj row)
              (update acc :bad conj {:row row :error (explain row)})))
          {:ok [] :bad []}
          rows))

(defn summarize-bad
  "Turn the :bad vector into a short string suitable for console output.
   Groups errors by marketplace + first bad key and counts occurrences."
  [bad]
  (->> bad
       (group-by (fn [{:keys [row error]}]
                   [(or (:marketplace row) :unknown)
                    (first (keys error))]))
       (map (fn [[[mp k] xs]]
              (str "  " (name mp) " → " (name (or k :unknown))
                   ": " (count xs) " rows")))
       (clojure.string/join "\n")))
