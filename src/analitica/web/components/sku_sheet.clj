(ns analitica.web.components.sku-sheet
  "Hiccup fragment renderer for the SKU drill-down side panel.

  Entry point: (render summary)
  summary is the map returned by analitica.domain.sku/sku-summary."
  (:require [clojure.string :as str]
            [hiccup.core :refer [html]]
            [analitica.web.pages.digest :as digest]))

;; ---------------------------------------------------------------------------
;; Formatting helpers
;; ---------------------------------------------------------------------------

(defn- fmt-num [n]
  (if (and n (number? n))
    (str/replace (format "%,.0f" (double n)) "," " ")
    "0"))

(defn- fmt-rub [n]
  (str (fmt-num n) " ₽"))

(defn- fmt-pct [n]
  (if (and n (number? n))
    (format "%.1f%%" (double n))
    "0.0%"))

(defn- fmt-roi [n]
  (if (and n (number? n) (pos? (double n)))
    (format "%.2fx" (double n))
    "0.00x"))

;; ---------------------------------------------------------------------------
;; Sub-sections
;; ---------------------------------------------------------------------------

(defn- kpi-tiles [{:keys [sales-count returns-count margin-pct roi]}]
  [:div.grid.grid-cols-2.gap-3.mb-4
   [:div.bg-blue-50.rounded-lg.p-3.text-center
    [:div.text-xs.text-blue-600.font-medium.mb-1 "Продажи"]
    [:div.text-2xl.font-bold.text-blue-800 (fmt-num sales-count)]]
   [:div.bg-orange-50.rounded-lg.p-3.text-center
    [:div.text-xs.text-orange-600.font-medium.mb-1 "Возвраты"]
    [:div.text-2xl.font-bold.text-orange-800 (fmt-num returns-count)]]
   [:div.bg-green-50.rounded-lg.p-3.text-center
    [:div.text-xs.text-green-600.font-medium.mb-1 "Маржа"]
    [:div.text-2xl.font-bold.text-green-800 (fmt-pct margin-pct)]]
   [:div.bg-purple-50.rounded-lg.p-3.text-center
    [:div.text-xs.text-purple-600.font-medium.mb-1 "ROI"]
    [:div.text-2xl.font-bold.text-purple-800 (fmt-roi roi)]]])

(defn- sparkline-section [daily-revenue]
  (let [values (mapv #(or (:revenue %) 0.0) daily-revenue)]
    [:div.mb-4
     [:div.text-sm.font-semibold.text-gray-700.mb-2 "Динамика продаж"]
     [:div.bg-gray-50.rounded-lg.p-3
      (if (seq values)
        [:div.text-gray-600
         (digest/sparkline values)]
        [:div.text-xs.text-gray-400.italic "Нет данных за период"])]]))

(defn- cross-links [article from to marketplace]
  (let [mp-param (when (seq marketplace) (str "&marketplace=" marketplace))
        base     (fn [report] (str "/reports/" report
                                   "?from=" from "&to=" to mp-param
                                   "&article=" (java.net.URLEncoder/encode (str article) "UTF-8")))]
    [:div.mb-4
     [:div.text-sm.font-semibold.text-gray-700.mb-2 "Перейти в отчёт"]
     [:div.flex.flex-col.gap-1
      (for [[label route] [["Продажи"      "sales"]
                           ["Юнит-экономика" "ue"]
                           ["Возвраты"     "returns"]
                           ["Остатки"      "stock"]
                           ["Финансы"      "finance"]
                           ["ABC-анализ"   "abc"]]]
        [:a.flex.items-center.gap-2.text-sm.text-blue-600.hover:underline.py-0.5
         {:href (base route)}
         [:span "▸"] label])]]))

(defn- ops-table [recent-ops]
  (let [type-label {"sale"   "Продажа"
                    "return" "Возврат"}
        mp-label   {"wb"   "WB"
                    "ozon" "Ozon"
                    "ym"   "YM"}]
    [:div.mb-2
     [:div.text-sm.font-semibold.text-gray-700.mb-2 "Последние операции"]
     (if (seq recent-ops)
       [:table.w-full.text-xs
        [:thead
         [:tr.text-gray-500
          [:th.text-left.pb-1 "Дата"]
          [:th.text-left.pb-1 "Тип"]
          [:th.text-left.pb-1 "МП"]
          [:th.text-right.pb-1 "Сумма, ₽"]]]
        [:tbody
         (for [op recent-ops]
           (let [amt    (or (:amount op) 0.0)
                 pos?   (>= amt 0)
                 color  (if pos? "text-green-700" "text-red-700")]
             [:tr.border-t.border-gray-100
              [:td.py-0.5 (:date op)]
              [:td.py-0.5 (get type-label (:type op) (:type op))]
              [:td.py-0.5 (get mp-label (:marketplace op) (:marketplace op))]
              [:td.py-0.5.text-right {:class color}
               (str (if pos? "+" "") (fmt-num amt))]]))]]
       [:div.text-xs.text-gray-400.italic "Операций не найдено"])]))

;; ---------------------------------------------------------------------------
;; Main render
;; ---------------------------------------------------------------------------

(defn render
  "Render the SKU drill-down fragment from a sku-summary map.
   Extra params from, to, marketplace are needed for cross-report links."
  [{:keys [article nm-id] :as summary}
   & {:keys [from to marketplace]
      :or   {from "" to "" marketplace ""}}]
  (html
   [:div.sku-sheet-body
    ;; Header: article name + nm-id
    [:div.mb-4
     [:div.text-lg.font-bold.text-gray-900 (str article)]
     (when nm-id
       [:div.text-xs.text-gray-500 (str "nm-id: " nm-id)])]

    ;; Period label
    (when (and (seq from) (seq to))
      [:div.text-xs.text-gray-500.mb-4
       [:span "Период: "] [:span.font-medium from] " — " [:span.font-medium to]])

    ;; 4 KPI tiles
    (kpi-tiles summary)

    ;; Sparkline
    (sparkline-section (:daily-revenue summary))

    ;; Cross-report links
    (cross-links article from to marketplace)

    ;; Ops table
    (ops-table (:recent-ops summary))]))

(defn render-not-found
  "Return Hiccup data (a vector) for the 'SKU not found' fragment.
   Callers are responsible for calling (html ...) to produce the final string."
  [identifier]
  [:div.sku-sheet-body.text-center.py-8
   [:div.text-gray-400.text-4xl.mb-3 "🔍"]
   [:div.text-lg.font-semibold.text-gray-700.mb-2 "SKU не найден"]
   [:div.text-sm.text-gray-500 (str "Идентификатор: " identifier)]
   [:div.text-xs.text-gray-400.mt-2 "Проверьте период или попробуйте другой артикул."]])
