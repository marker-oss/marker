(ns analitica.web.pages.plan
  "GET /plan + POST /plan — monthly target editor.

   Form layout: rows are metrics (revenue / orders / gross_profit /
   margin_pct / ad_spend / drr_pct / profit_margin_pct), columns are
   marketplaces (wb / ozon / ym / all). Each cell is one
   <input type=number name='<mp>__<metric>' …>. Empty cell on POST
   means 'delete'. Period selected via period_month <select>."
  (:require [clojure.string :as str]
            [hiccup.core :refer [html]]
            [analitica.domain.plan :as plan]
            [analitica.web.layout :as layout])
  (:import [java.time YearMonth]
           [java.time.format DateTimeFormatter]))

(def ^:private metrics
  [["revenue"            "Выручка"            "₽"]
   ["orders"             "Заказы"             "шт"]
   ["gross_profit"       "Валовая прибыль"    "₽"]
   ["margin_pct"         "Маржа"              "%"]
   ["ad_spend"           "Расходы на рекламу" "₽"]
   ["drr_pct"            "ДРР"                "%"]
   ["profit_margin_pct"  "% чистой прибыли"   "%"]])

(def ^:private marketplaces
  [["wb"   "WB"]
   ["ozon" "Ozon"]
   ["ym"   "YM"]
   ["all"  "Все MP"]])

(defn- current-month-iso []
  (.format (YearMonth/now) (DateTimeFormatter/ofPattern "yyyy-MM")))

(defn- month-options
  "Ascending vector of yyyy-MM strings covering a 12-month window
   centered on the current month (6 past + current + 6 future).
   `selected` is unioned in if it falls outside that window so the
   dropdown can always highlight the rendered period."
  [selected]
  (let [base   (YearMonth/now)
        window (for [i (range -6 7)]
                 (str (.plusMonths base i)))]
    (->> (cond-> (set window) selected (conj selected))
         sort
         vec)))

(defn- format-target
  "Render a saved plan value into a string usable as an
   <input type=\"number\"> value attribute. The browser rejects values
   containing whitespace or thousand separators and silently blanks
   the field, so emit a plain integer (no formatting). The visual
   thousand separator can be added by a future client-side formatter."
  [v]
  (when (number? v)
    (format "%.0f" (double v))))

(defn- index-rows
  "Build {[mp metric] target-value}."
  [rows]
  (into {}
        (map (fn [r] [[(:marketplace r) (:metric r)] (:target-value r)])
             rows)))

(defn- render-form [period-month rows error-msgs]
  (let [idx (index-rows rows)]
    [:div.max-w-5xl.mx-auto.px-4.py-6
     [:h1.text-2xl.font-bold.mb-4 "План на месяц"]
     (when (seq error-msgs)
       [:div.bg-red-50.border.border-red-300.text-red-800.px-4.py-2.rounded.mb-4
        (for [m error-msgs] [:div m])])
     [:form {:method "post" :action "/plan"}
      [:div.flex.items-center.gap-3.mb-4
       [:label.text-sm.text-gray-700 "Период:"]
       [:select.border.rounded.px-2.py-1
        {:name "period_month"
         :onchange "window.location='/plan?period_month='+this.value"}
        (for [m (month-options period-month)]
          [:option {:value m :selected (= m period-month)} m])]]
      [:table.min-w-full.border-collapse
       [:thead
        [:tr.bg-gray-50.text-sm
         [:th.text-left.p-2 ""]
         (for [[_ label] marketplaces]
           [:th.text-right.p-2 label])]]
       [:tbody
        (for [[metric label unit] metrics]
          [:tr.border-t
           [:td.p-2.text-sm (str label " (" unit ")")]
           (for [[mp _] marketplaces]
             [:td.p-2.text-right
              [:input.w-32.text-right.border.rounded.px-2.py-1
               {:type        "number"
                :step        "any"
                :min         "0"
                :name        (str mp "__" metric)
                :value       (format-target (get idx [mp metric]))
                :placeholder "—"}]])])]]
      [:div.mt-4
       [:button.bg-blue-600.text-white.px-4.py-2.rounded.hover:bg-blue-700
        {:type "submit"} "Сохранить"]]]]))

(defn- collect-submitted-cells [form-params]
  (for [[mp _]     marketplaces
        [metric _ _] metrics
        :let [k   (str mp "__" metric)
              raw (or (get form-params k)
                      (get form-params (keyword k)))]
        :when (some? raw)]
    {:mp mp :metric metric :raw (str/trim (str raw))}))

(defn- parse-target [raw]
  (when (and raw (not (str/blank? raw)))
    (try (Double/parseDouble (str/replace raw "," "."))
         (catch NumberFormatException _ ::invalid))))

(defn get-handler [{:keys [params]}]
  (let [period-month (or (:period_month params)
                         (get params "period_month")
                         (current-month-iso))
        rows         (plan/fetch-plans period-month)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (layout/page "План"
                        (render-form period-month rows [])
                        :active-route "/plan")}))

(defn post-handler [{:keys [params form-params]}]
  (let [period-month (or (get form-params "period_month")
                         (:period_month params)
                         (current-month-iso))
        cells        (collect-submitted-cells form-params)
        parsed       (map (fn [{:keys [mp metric raw]}]
                            {:mp mp :metric metric
                             :value (parse-target raw)})
                          cells)
        invalid      (filter #(= ::invalid (:value %)) parsed)
        errors       (map #(str "target_value для " (:mp %) "/" (:metric %)
                                " не число")
                          invalid)]
    (if (seq errors)
      {:status 400
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (layout/page "План"
                          (render-form period-month
                                       (plan/fetch-plans period-month)
                                       errors)
                          :active-route "/plan")}
      (do
        (doseq [{:keys [mp metric value]} parsed]
          (cond
            (nil? value)
              (plan/delete-plan! {:period-month period-month
                                  :marketplace mp :metric metric})
            (and (number? value) (pos? value))
              (plan/save-plan! {:period-month period-month
                                :marketplace mp :metric metric
                                :target-value value})
            :else
              (plan/delete-plan! {:period-month period-month
                                  :marketplace mp :metric metric})))
        {:status 303
         :headers {"Location" (str "/plan?period_month=" period-month)}
         :body ""}))))
