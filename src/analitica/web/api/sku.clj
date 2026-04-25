(ns analitica.web.api.sku
  "GET /api/sku/:identifier — returns an HTML fragment for the SKU drill-down panel.

  :identifier is interpreted as:
    - integer nm-id if it parses as Long, then looked up in sales.nm_id
    - otherwise treated as article string

  The fragment is consumed by sku-sheet.js via fetch() → dialog.innerHTML."
  (:require [analitica.domain.sku :as sku]
            [analitica.web.components.sku-sheet :as sheet]
            [analitica.util.period :as period]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- today-str []
  (subs (str (java.time.LocalDate/now)) 0 10))

(defn- thirty-days-ago []
  (subs (str (.minusDays (java.time.LocalDate/now) 29)) 0 10))

(defn- resolve-from-to [params]
  [(or (when (seq (get params :from "")) (get params :from))
       (thirty-days-ago))
   (or (when (seq (get params :to "")) (get params :to))
       (today-str))])

(defn- parse-marketplace [mp-str]
  (when (contains? #{"wb" "ozon" "ym"} mp-str)
    (keyword mp-str)))

;; ---------------------------------------------------------------------------
;; Handler
;; ---------------------------------------------------------------------------

(defn handler
  "Ring handler for GET /api/sku/:identifier"
  [{:keys [params]}]
  (let [raw-id   (str (get params :identifier ""))
        mp-str   (get params :marketplace "")
        mp       (parse-marketplace mp-str)
        [from to] (resolve-from-to params)]
    (if (empty? raw-id)
      {:status  404
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body    (sheet/render-not-found "(пусто)")}
      (let [summary (try
                      (sku/sku-summary raw-id from to :marketplace mp)
                      (catch Exception _
                        nil))]
        (if (or (nil? summary)
                (and (zero? (:sales-count summary 0))
                     (zero? (:returns-count summary 0))
                     (empty? (:daily-revenue summary))))
          {:status  200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body    (sheet/render-not-found raw-id)}
          {:status  200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body    (sheet/render summary
                                  :from        from
                                  :to          to
                                  :marketplace mp-str)})))))
