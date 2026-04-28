(ns analitica.schema.normalized.stocks-history
  "Canonical StocksHistoryRow — daily stock snapshot per article-warehouse.
   See docs/data-dictionary.md#stocks_history for canonical semantics.

   RFC-13 (closed 2026-04-28). Where `stocks` is a snapshot that gets
   overwritten on every sync, `stocks_history` accumulates one row per
   (snapshot_date, marketplace, article, warehouse) so we can compute
   trends, velocity, days-of-supply, and out-of-stock predictions.

   Population strategy (v1): daily capture from the current `stocks`
   table — calling `analitica.materialize/snapshot-stocks-history!`
   inserts today's rows. Forward-looking only; pre-existing snapshots
   are not reconstructable."
  (:require [malli.core :as m]
            [malli.error :as me]
            [analitica.schema.util :as schema-util]))

(def StocksHistoryRow
  [:map {:closed false}
   [:snapshot-date  :string]                       ;; ISO date YYYY-MM-DD
   [:marketplace    [:enum :wb :ozon :ym]]
   [:article        :string]
   [:warehouse      [:maybe :string]]
   [:quantity       [:maybe [:or :int :double]]]
   [:quantity-full  {:optional true} [:maybe [:or :int :double]]]
   [:in-way-to      {:optional true} [:maybe [:or :int :double]]]
   [:in-way-from    {:optional true} [:maybe [:or :int :double]]]
   [:nm-id          {:optional true} [:maybe [:or :int :double]]]
   [:barcode        {:optional true} [:maybe :string]]
   [:tech-size      {:optional true} [:maybe :string]]
   [:subject        {:optional true} [:maybe :string]]
   [:brand          {:optional true} [:maybe :string]]
   [:synced-at      {:optional true} [:maybe :string]]])

(def ^:private validator (m/validator StocksHistoryRow))
(def ^:private explainer (m/explainer StocksHistoryRow))

(defn valid? [row] (validator row))
(defn explain [row] (some-> (explainer row) me/humanize))
(defn validate-rows [rows] (schema-util/validate-rows validator explain rows))
