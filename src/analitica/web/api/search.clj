(ns analitica.web.api.search
  "GET /api/search?q=... — command-palette search backend.

  Returns JSON {:results [{:type :sku|:report|:page :title :hint :route}]}.
  Short queries (< 2 chars) return {:results []} immediately.
  SKUs are queried from the `sales` table; reports and pages are static."
  (:require [analitica.db :as db]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Static catalogs
;; ---------------------------------------------------------------------------

(def ^:private static-pages
  [{:type :page :title "Главная"       :route "/"     :hint "страница"}
   {:type :page :title "Синхронизация" :route "/sync" :hint "страница"}])

(def ^:private static-reports
  [{:type :report :title "Юнит-экономика"      :route "/reports/ue"      :hint "отчёт"}
   {:type :report :title "P&L"                  :route "/reports/pnl"     :hint "отчёт"}
   {:type :report :title "Финансы"              :route "/reports/finance"  :hint "отчёт"}
   {:type :report :title "Возвраты"             :route "/reports/returns"  :hint "отчёт"}
   {:type :report :title "Продажи"              :route "/reports/sales"    :hint "отчёт"}
   {:type :report :title "ABC-анализ"           :route "/reports/abc"      :hint "отчёт"}
   {:type :report :title "Тренды"               :route "/reports/trends"   :hint "отчёт"}
   {:type :report :title "Выкуп"                :route "/reports/buyout"   :hint "отчёт"}
   {:type :report :title "География"            :route "/reports/geo"      :hint "отчёт"}
   {:type :report :title "Остатки"              :route "/reports/stock"    :hint "отчёт"}
   {:type :report :title "Потери"               :route "/reports/losses"   :hint "отчёт"}
   ;; Dashboards
   {:type :report :title "Сводка (WB)"          :route "/wb"              :hint "дашборд"}
   {:type :report :title "Сводка (Ozon)"        :route "/ozon"            :hint "дашборд"}
   {:type :report :title "Сводка (ЯМ)"          :route "/ym"              :hint "дашборд"}])

;; ---------------------------------------------------------------------------
;; DB search
;; ---------------------------------------------------------------------------

(defn- search-skus
  "Return up to 10 distinct articles where article/subject/brand contains q
   (case-insensitive). SQLite's LIKE is case-INSENSITIVE only for ASCII, so
   Cyrillic queries (e.g. \"плать\" → \"Платье\") need an in-memory filter.
   The catalog is small (~600-1000 rows), the cost is negligible."
  [q]
  (let [q-lower (str/lower-case q)
        rows    (db/query
                 ["SELECT DISTINCT article, subject, brand FROM sales"])
        match?  (fn [{:keys [article subject brand]}]
                  (or (and article (str/includes? (str/lower-case article) q-lower))
                      (and subject (str/includes? (str/lower-case subject) q-lower))
                      (and brand   (str/includes? (str/lower-case brand)   q-lower))))]
    (->> rows
         (filter match?)
         (take 10))))

(defn- sku->result
  [{:keys [article subject brand]}]
  (let [safe-article (or article "")
        hint-parts   (filter some? [brand subject])
        hint         (if (seq hint-parts) (str/join " · " hint-parts) "артикул")]
    {:type  :sku
     :title (if (str/blank? safe-article) (or subject "—") safe-article)
     :hint  hint
     :route (str "/reports/sales?article="
                 (java.net.URLEncoder/encode safe-article "UTF-8"))}))

;; ---------------------------------------------------------------------------
;; Static filter
;; ---------------------------------------------------------------------------

(defn- matches-query?
  "Case-insensitive substring match on :title."
  [q {:keys [title]}]
  (str/includes? (str/lower-case title) (str/lower-case q)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn search
  "Return {:results [...]} for query string q.
   Returns {:results []} when q is nil or fewer than 2 chars."
  [q]
  (if (or (nil? q) (< (count q) 2))
    {:results []}
    (let [skus    (try
                    (->> (search-skus q)
                         (mapv sku->result))
                    (catch Exception _
                      []))
          reports (->> static-reports
                       (filter (partial matches-query? q))
                       (take 5)
                       vec)
          pages   (->> static-pages
                       (filter (partial matches-query? q))
                       (take 3)
                       vec)]
      {:results (vec (concat skus reports pages))})))
