(ns analitica.schema.normalized.ad-stats
  "Canonical AdStatsRow — WB advertising campaign per-day statistics.
   See docs/data-dictionary.md#ad_stats."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def AdStatsRow
  [:map {:closed false}
   [:campaign-id [:or :int :double]]
   [:date        :string]
   [:nm-id       [:or :int :double]]

   [:views     {:optional true} [:maybe [:or :int :double]]]
   [:clicks    {:optional true} [:maybe [:or :int :double]]]
   [:ctr       {:optional true} [:maybe [:or :int :double]]]
   [:cpc       {:optional true} [:maybe [:or :int :double]]]
   [:spend     {:optional true} [:maybe [:or :int :double]]]
   [:atbs      {:optional true} [:maybe [:or :int :double]]]
   [:orders    {:optional true} [:maybe [:or :int :double]]]
   [:cr        {:optional true} [:maybe [:or :int :double]]]
   [:shks      {:optional true} [:maybe [:or :int :double]]]
   [:sum-price {:optional true} [:maybe [:or :int :double]]]
   [:synced-at {:optional true} [:maybe :string]]])

(def ^:private validator (m/validator AdStatsRow))
(def ^:private explainer (m/explainer AdStatsRow))

(defn valid? [row] (validator row))
(defn explain [row] (some-> (explainer row) me/humanize))
(defn validate-rows [rows]
  (reduce (fn [{:keys [ok bad] :as acc} row]
            (if (validator row)
              (update acc :ok conj row)
              (update acc :bad conj {:row row :error (explain row)})))
          {:ok [] :bad []}
          rows))
