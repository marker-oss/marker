(ns analitica.web.api.detail
  "Per-article drill-down data for the drill-panel side panel.

   `article-detail` returns a map:
     {:article    <article>
      :kpi        {<metric> <val>}  — 6 key per-article metrics for the top card row
      :breakdown  {<metric> <val>}  — MP-side cost breakdown (wb-reward, logistics, ad-spend, etc.)
      :all-metrics <row>}           — full per-article row from domain (34 keys)

   For report-types other than :ue, returns {:article ... :kpi {} :breakdown {}}.

   Article names may contain forward slashes and Cyrillic. Callers that pass the
   article via URL path must percent-encode it (e.g. JS encodeURIComponent), which
   encodes '/' as '%2F'. The route handler decodes before calling this function."
  (:require [analitica.domain.finance :as finance]
            [analitica.domain.unit-economics :as ue]
            [analitica.db :as db]
            [analitica.util.time :as t]))

(defn- resolve-from-to
  "Return [from to] strings from a period argument.
   Accepts: map {:from ... :to ...}, 2-vector, or keyword."
  [period]
  (t/resolve-period period))

(defn article-detail
  "Per-article drill-down data for side-panel.

   Arguments:
     report-type  — keyword (:ue or other)
     article      — article string (already decoded, may contain '/' or Cyrillic)
     period       — map {:from <iso> :to <iso>}, 2-vector, or keyword
   Keyword args:
     :marketplace — optional keyword (:wb :ozon :ym)

   Returns:
     {:article    <article>
      :kpi        {<metric> <val>}
      :breakdown  {<metric> <val>}
      :all-metrics <row>}

   When the article is not found in the period the row will be nil, so :kpi
   and :breakdown will be nil-sourced empty selects — clients must handle nil
   values in the kpi/breakdown maps gracefully.

   For unsupported report types returns {:article ... :kpi {} :breakdown {}}."
  [report-type article period & {:keys [marketplace]}]
  (case report-type
    :ue
    (try
      (let [[from to] (resolve-from-to period)
            all-fin   (finance/fetch-finance period
                                            :marketplace marketplace
                                            :source :db)
            art-fin   (filter #(= article (:article %)) all-fin)
            storage-map (let [rows (db/storage-by-article from to :marketplace marketplace)]
                          (when (seq rows)
                            (into {} (map (juxt :article :storage-cost) rows))))
            ad-map      (let [rows (db/ad-spend-by-article from to :marketplace marketplace)]
                          (when (seq rows)
                            (into {} (map (juxt :article :ad-spend) rows))))
            rows        (ue/calculate art-fin
                                      :storage-by-article storage-map
                                      :ad-spend-by-article ad-map)
            row         (first rows)]
        {:article     article
         :kpi         (select-keys row [:revenue :profit :margin-pct :drr-pct
                                        :sales-qty :non-return-rate])
         :breakdown   (select-keys row [:wb-reward :logistics :storage :acceptance
                                        :penalties :acquiring :deduction
                                        :ad-spend :total-cost])
         :all-metrics row})
      (catch Exception _
        {:article article :kpi {} :breakdown {} :all-metrics nil}))

    ;; default: empty payload for unsupported report types
    {:article article :kpi {} :breakdown {}}))
