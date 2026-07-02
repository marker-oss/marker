(ns marker.pages.settings
  "Settings page — MP credentials + 015/017 management cards.

   Sections (each an independent .card.section-card):
     1. MP credentials (WB / Ozon / YM)         — view masked, validate, save
     2. «Налоговые ставки» (015)                — 12-month tax config per year
     3. «Операционные расходы» (015)            — monthly OPEX rows CRUD
     4. «Регулярные расходы (автоправила)» (015)— monthly auto-rules CRUD
     5. «Телеграм-бот» (017)                    — per-chat digest subscriptions

   All state flows through the Phase-B re-frame contract (marker.state.events /
   marker.state.subs); this namespace adds NO events/subs of its own.
   Money renders via marker.util.format (nil → «—»)."
  (:require [clojure.string :as str]
            [uix.core :refer [$ defui use-state use-effect]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.util.format :as fmt]
            [marker.ui.icons :refer [icon]]
            [marker.state.subs   :as subs]
            [marker.state.events :as events]))

;; ---------------------------------------------------------------------------
;; Shared pure helpers (unit-tested in marker.pages.settings-test)
;; ---------------------------------------------------------------------------

(defn parse-num
  "Parse a user-entered numeric string (comma or dot decimal) → number | nil.
   Numbers pass through; blank/garbage → nil."
  [s]
  (cond
    (number? s) s
    (string? s) (let [v (-> s
                            (str/replace #"[\s ]" "") ; thousands spaces
                            (str/replace "," ".")
                            js/parseFloat)]
                  (when-not (js/isNaN v) v))
    :else       nil))

(defn api-error-for
  "First error :message among api-errors whose URL starts with url-prefix
   (load URLs carry query params, so exact-key lookup misses)."
  [api-errors url-prefix]
  (some (fn [[url err]]
          (when (str/starts-with? (str url) url-prefix)
            (or (:message err) "Ошибка запроса")))
        api-errors))

(defn- dispatch-clearing!
  "Dispatch ::events/clear-api-error for every stored error whose URL starts
   with url-prefix. Called before re-attempting a mutation so a stale banner
   from a failed POST/DELETE (whose exact URL a later GET success never
   dissoc's) does not stick around after the retry succeeds."
  [api-errors url-prefix]
  (doseq [[url _] api-errors]
    (when (str/starts-with? (str url) url-prefix)
      (rf/dispatch [::events/clear-api-error url]))))

(def month-names
  ["Январь" "Февраль" "Март" "Апрель" "Май" "Июнь"
   "Июль" "Август" "Сентябрь" "Октябрь" "Ноябрь" "Декабрь"])

(defn- current-month
  "Today as YYYY-MM."
  []
  (let [d (js/Date.)]
    (str (.getFullYear d) "-" (.padStart (str (inc (.getMonth d))) 2 "0"))))

(defn mp-name-label
  "Short RU display label for a marketplace keyword/string; nil → «—»."
  [mp]
  (case (some-> mp name)
    "wb"   "WB"
    "ozon" "Ozon"
    "ym"   "ЯМ"
    nil    "—"
    (str mp)))

;; ---------------------------------------------------------------------------
;; Shared small UI pieces
;; ---------------------------------------------------------------------------

(defui ^:private card-skeleton [{:keys [title rows]}]
  ($ :section {:class "card section-card"}
     ($ :div {:class "section-head"}
        ($ :h3 {:class "section-title"} title))
     ($ :div {:style {:display "flex" :flex-direction "column" :gap "8px"}}
        (for [i (range (or rows 4))]
          ($ :div {:key i :class "skel" :style {:height "20px"}})))))

(defui ^:private card-error [{:keys [message]}]
  ($ :div {:class "alert alert-danger" :style {:margin-bottom "12px"}}
     ($ icon {:name :danger :class "alert-icon"})
     ($ :div {:class "alert-body"}
        ($ :div {:class "alert-title"} "Ошибка запроса")
        ($ :div message))))

(def ^:private checkbox-style
  {:width "14px" :height "14px"
   :accent-color "var(--color-accent-interactive)"
   :cursor "pointer"})

;; ---------------------------------------------------------------------------
;; 1. MP credentials (pre-013, unchanged)
;; ---------------------------------------------------------------------------

(def mp-specs
  [{:mp :wb :label "Wildberries"
    :fields [{:k :api-token :label "API-токен" :secret? true :setting-key "mp.wb.api-token"}]}
   {:mp :ozon :label "Ozon"
    :fields [{:k :client-id :label "Client-Id" :setting-key "mp.ozon.client-id"}
             {:k :api-key   :label "API-Key" :secret? true :setting-key "mp.ozon.api-key"}]}
   {:mp :ym :label "Яндекс.Маркет"
    :fields [{:k :oauth-token  :label "OAuth-токен"  :secret? true :setting-key "mp.ym.oauth-token"}
             {:k :campaign-id  :label "Campaign-Id"  :setting-key "mp.ym.campaign-id"}
             {:k :business-id  :label "Business-Id"  :setting-key "mp.ym.business-id"}]}])

(defn current-masked
  "Masked/plain current value for a dotted setting-key, or nil if unset."
  [data setting-key]
  (get-in data [setting-key :value]))

(defui ^:private mp-card [{:keys [spec data status]}]
  (let [[form set-form!] (use-state {})
        mp (:mp spec)
        st (get status mp)]
    ($ :section {:class "card section-card"}
       ($ :div {:class "section-head"}
          ($ :h3 {:class "section-title"} (:label spec)))
       ($ :div {:style {:display "flex" :flex-direction "column" :gap "10px"}}
          (for [{:keys [k label secret? setting-key]} (:fields spec)]
            ($ :div {:key (name k) :style {:display "flex" :flex-direction "column" :gap "4px"}}
               ($ :label {:style {:font-size "12px" :color "var(--color-fg-muted)"}}
                  label
                  (when-let [cur (current-masked data setting-key)]
                    ($ :span {:class "mono" :style {:margin-left "8px"}} "текущее: " cur)))
               ($ :input {:type        (if secret? "password" "text")
                          :placeholder (if (current-masked data setting-key)
                                         "(оставьте пустым — без изменений)"
                                         "")
                          :value       (get form k "")
                          :on-change   (fn [e] (set-form! (assoc form k (.. e -target -value))))}))))
       ($ :div {:style {:display "flex" :gap "8px" :margin-top "12px" :align-items "center"}}
          ($ :button {:class    "btn btn-secondary"
                      :disabled (boolean (:testing? st))
                      :on-click #(rf/dispatch [::events/test-marketplace mp form])}
             (if (:testing? st) "Проверка..." "Проверить"))
          ($ :button {:class    "btn btn-primary"
                      :disabled (boolean (:saving? st))
                      :on-click #(rf/dispatch [::events/save-marketplace mp form])}
             (if (:saving? st) "Сохранение..." "Сохранить"))
          (when-let [v (:verdict st)]
            ($ :span {:class (str "badge " (if (:valid? v) "badge-success" "badge-danger"))}
               (if (:valid? v) "OK" (str "✗ " (:detail v)))))
          (when (:saved? st)
            ($ :span {:class "badge badge-success"} "Сохранено и применено"))
          (when-let [err (:error st)]
            ($ :span {:class "badge badge-danger"} err))))))

;; ---------------------------------------------------------------------------
;; 2. «Налоговые ставки» (015)
;; ---------------------------------------------------------------------------

(def taxation-type-options
  [{:value :none               :label "Нет"}
   {:value :usn-income         :label "УСН Доходы"}
   {:value :usn-income-expense :label "УСН Доходы−Расходы"}])

(defn fraction->pct
  "Stored fraction → UI percent, rounded to 2 dp (0.06 → 6, not 6.000000001)."
  [x]
  (if (number? x)
    (/ (js/Math.round (* x 10000)) 100)
    0))

(defn tax-month->form
  "Server month row (fractions) → editable form row (percent strings)."
  [{:keys [month taxation-type usn-rate vat-rate official-cost-price]}]
  {:month               month
   :taxation-type       (or taxation-type :none)
   :usn-rate-pct        (str (fraction->pct usn-rate))
   :vat-rate-pct        (str (fraction->pct vat-rate))
   :official-cost-price (boolean official-cost-price)})

(defn tax-form->payload
  "Editable rows → PUT /settings/tax body. Percents are sent as
   :usn-rate-pct / :vat-rate-pct — the server normalizes pct → fraction.
   Never emits :usn-rate/:vat-rate (fraction keys would win over pct)."
  [year months]
  {:year   year
   :months (mapv (fn [{:keys [month taxation-type usn-rate-pct vat-rate-pct
                              official-cost-price]}]
                   {:month               month
                    :taxation-type       (or taxation-type :none)
                    :usn-rate-pct        (or (parse-num usn-rate-pct) 0)
                    :vat-rate-pct        (or (parse-num vat-rate-pct) 0)
                    :official-cost-price (boolean official-cost-price)})
                 months)})

(def ^:private tax-url-prefix "/api/v1/settings/tax")

(defui ^:private tax-rate-input [{:keys [value on-change]}]
  ($ :input {:class     "input mono"
             :style     {:width "72px" :height "28px" :text-align "right"}
             :value     (or value "")
             :on-change (fn [e] (on-change (.. e -target -value)))}))

(defui ^:private tax-card []
  (let [data       (use-subscribe [::subs/tax-config])
        loading?   (use-subscribe [::subs/tax-config-loading?])
        api-errors (use-subscribe [::subs/api-errors])
        cy         (.getFullYear (js/Date.))
        [year   set-year!]   (use-state cy)
        [months set-months!] (use-state nil)
        [saved? set-saved!]  (use-state false)
        error-msg  (api-error-for api-errors tax-url-prefix)
        edit!      (fn [idx k v]
                     (set-saved! false)
                     (set-months! #(assoc-in % [idx k] v)))]
    (use-effect
     (fn [] (rf/dispatch [::events/load-tax-config year]) js/undefined)
     [year])
    ;; Sync server → local edit state only when the loaded year matches the
    ;; selected one (an in-flight load for another year must not clobber edits).
    (use-effect
     (fn []
       (when (and data (= (:year data) year))
         (set-months! (mapv tax-month->form (:months data))))
       js/undefined)
     [data year])
    (if (and (nil? months) loading?)
      ($ card-skeleton {:title "Налоговые ставки" :rows 6})
      ($ :section {:class "card section-card"}
         ($ :div {:class "section-head"}
            ($ :h3 {:class "section-title"} "Налоговые ставки")
            ($ :div {:style {:display "flex" :gap "8px" :align-items "center"}}
               ($ :label {:class "field-label" :style {:margin 0}} "Год")
               ($ :select {:class     "input select"
                           :style     {:width "96px" :height "28px"}
                           :value     (str year)
                           :on-change (fn [e]
                                        (set-months! nil)
                                        (set-saved! false)
                                        (set-year! (js/parseInt (.. e -target -value) 10)))}
                  (for [y (range (- cy 2) (+ cy 3))]
                    ($ :option {:key y :value (str y)} (str y))))))
         (when error-msg ($ card-error {:message error-msg}))
         ($ :p {:style {:color "var(--color-fg-muted)" :font-size "12px"
                        :margin "0 0 10px"}}
            "Ставки указываются в процентах и применяются помесячно. "
            "«Офиц. себестоимость» — учитывать официальную себестоимость в базе УСН Доходы−Расходы.")
         (if (nil? months)
           ($ :div {:style {:display "flex" :flex-direction "column" :gap "8px"}}
              (for [i (range 6)]
                ($ :div {:key i :class "skel" :style {:height "20px"}})))
           ($ :div {:class "tbl-wrap"}
              ($ :table {:class "tbl"}
                 ($ :thead
                    ($ :tr
                       ($ :th "Месяц")
                       ($ :th "Налогообложение")
                       ($ :th {:class "num"} "УСН, %")
                       ($ :th {:class "num"} "НДС, %")
                       ($ :th "Офиц. себестоимость")))
                 ($ :tbody
                    (for [[idx m] (map-indexed vector months)]
                      ($ :tr {:key (:month m)}
                         ($ :td (get month-names (dec (:month m)) (str (:month m))))
                         ($ :td
                            ($ :select {:class     "input select"
                                        :style     {:height "28px" :min-width "190px"}
                                        :value     (name (:taxation-type m))
                                        :on-change (fn [e]
                                                     (edit! idx :taxation-type
                                                            (keyword (.. e -target -value))))}
                               (for [{:keys [value label]} taxation-type-options]
                                 ($ :option {:key (name value) :value (name value)} label))))
                         ($ :td {:class "num"}
                            ($ tax-rate-input {:value     (:usn-rate-pct m)
                                               :on-change #(edit! idx :usn-rate-pct %)}))
                         ($ :td {:class "num"}
                            ($ tax-rate-input {:value     (:vat-rate-pct m)
                                               :on-change #(edit! idx :vat-rate-pct %)}))
                         ($ :td
                            ($ :input {:type      "checkbox"
                                       :style     checkbox-style
                                       :checked   (boolean (:official-cost-price m))
                                       :on-change #(edit! idx :official-cost-price
                                                          (not (:official-cost-price m)))}))))))))
         ($ :div {:style {:display "flex" :gap "8px" :margin-top "12px" :align-items "center"}}
            ($ :button {:class    "btn btn-primary"
                        :disabled (nil? months)
                        :on-click (fn []
                                    (dispatch-clearing! api-errors tax-url-prefix)
                                    (rf/dispatch [::events/save-tax-config
                                                  (tax-form->payload year months)])
                                    (set-saved! true))}
               "Сохранить")
            (when saved?
              ($ :span {:class "badge badge-success"} "Сохранено")))))))

;; ---------------------------------------------------------------------------
;; 3. «Операционные расходы» (015)
;; ---------------------------------------------------------------------------

(def opex-category-options
  [{:value "salary"    :label "Зарплата"}
   {:value "rent"      :label "Аренда"}
   {:value "services"  :label "Услуги"}
   {:value "marketing" :label "Маркетинг"}
   {:value "other"     :label "Прочее"}])

(defn opex-category-label
  "RU label for a category slug (keyword or string); unknown → as-is."
  [cat]
  (let [c (if (keyword? cat) (name cat) (str cat))]
    (or (some (fn [{:keys [value label]}] (when (= value c) label))
              opex-category-options)
        c)))

(defn opex-form->payload
  "Add-form → POST /opex body, or nil while invalid (no category, amount ≤ 0)."
  [period {:keys [category amount marketplace note]}]
  (let [amt (parse-num amount)]
    (when (and (seq (str category)) amt (pos? amt) (seq (str period)))
      (cond-> {:period-month period
               :category     category
               :amount       amt}
        (seq (str marketplace)) (assoc :marketplace (keyword marketplace))
        (seq (str note))        (assoc :note note)))))

(def ^:private opex-url-prefix "/api/v1/opex")

(def ^:private opex-mp-options
  (cons {:value "" :label "Общие (без МП)"}
        [{:value "wb" :label "Wildberries"}
         {:value "ozon" :label "Ozon"}
         {:value "ym" :label "Яндекс.Маркет"}]))

(defui ^:private opex-add-form [{:keys [form set-form! period api-errors]}]
  (let [payload (opex-form->payload period form)]
    ($ :div {:style {:display "flex" :gap "8px" :align-items "flex-end"
                     :flex-wrap "wrap" :margin-bottom "12px"}}
       ($ :div
          ($ :label {:class "field-label"} "Категория")
          ($ :select {:class     "input select"
                      :style     {:width "150px"}
                      :value     (:category form)
                      :on-change (fn [e] (set-form! (assoc form :category (.. e -target -value))))}
             (for [{:keys [value label]} opex-category-options]
               ($ :option {:key value :value value} label))))
       ($ :div
          ($ :label {:class "field-label"} "Сумма, ₽")
          ($ :input {:class     "input mono"
                     :style     {:width "120px" :text-align "right"}
                     :value     (:amount form)
                     :on-change (fn [e] (set-form! (assoc form :amount (.. e -target -value))))}))
       ($ :div
          ($ :label {:class "field-label"} "Маркетплейс")
          ($ :select {:class     "input select"
                      :style     {:width "160px"}
                      :value     (:marketplace form)
                      :on-change (fn [e] (set-form! (assoc form :marketplace (.. e -target -value))))}
             (for [{:keys [value label]} opex-mp-options]
               ($ :option {:key value :value value} label))))
       ($ :div {:style {:flex "1 1 160px"}}
          ($ :label {:class "field-label"} "Примечание")
          ($ :input {:class     "input"
                     :style     {:width "100%"}
                     :value     (:note form)
                     :on-change (fn [e] (set-form! (assoc form :note (.. e -target -value))))}))
       ($ :button {:class    "btn btn-secondary"
                   :disabled (nil? payload)
                   :on-click (fn []
                               (dispatch-clearing! api-errors opex-url-prefix)
                               (rf/dispatch [::events/add-opex payload])
                               (set-form! (assoc form :amount "" :note "")))}
          ($ icon {:name :plus :size 14})
          "Добавить"))))

(defui ^:private opex-card []
  (let [data       (use-subscribe [::subs/opex])
        api-errors (use-subscribe [::subs/api-errors])
        [period set-period!] (use-state (current-month))
        [form   set-form!]   (use-state {:category "other" :amount ""
                                         :marketplace "" :note ""})
        error-msg  (api-error-for api-errors opex-url-prefix)
        ;; Rows are only valid for the period they were loaded for — while a
        ;; period switch is in flight `data` still holds the previous month.
        stale?     (and (some? data) (not= (:period data) period))
        rows       (when-not stale? (:rows data))]
    (use-effect
     (fn [] (rf/dispatch [::events/load-opex period]) js/undefined)
     [period])
    ($ :section {:class "card section-card"}
       ($ :div {:class "section-head"}
          ($ :h3 {:class "section-title"} "Операционные расходы")
          ($ :div {:style {:display "flex" :gap "8px" :align-items "center"}}
             ($ :label {:class "field-label" :style {:margin 0}} "Период")
             ($ :input {:type      "month"
                        :class     "input mono"
                        :style     {:height "28px"}
                        :value     period
                        :on-change (fn [e]
                                     (let [v (.. e -target -value)]
                                       (when (seq v) (set-period! v))))})))
       (when error-msg ($ card-error {:message error-msg}))
       ($ opex-add-form {:form form :set-form! set-form! :period period
                         :api-errors api-errors})
       (cond
         ;; No usable data yet (first load or period switch in flight) —
         ;; skeleton, unless the load already failed (banner above suffices).
         (and (nil? rows) error-msg)
         nil

         (or (nil? data) stale?)
         ($ :div {:style {:display "flex" :flex-direction "column" :gap "8px"}}
            (for [i (range 4)]
              ($ :div {:key i :class "skel" :style {:height "20px"}})))

         (empty? rows)
         ($ :div {:style {:padding "16px" :text-align "center"
                          :color "var(--color-fg-muted)"}}
            "За этот месяц расходов пока нет.")

         :else
         ($ :<>
            ($ :div {:class "tbl-wrap"}
               ($ :table {:class "tbl"}
                  ($ :thead
                     ($ :tr
                        ($ :th "Категория")
                        ($ :th {:class "num"} "Сумма")
                        ($ :th "МП")
                        ($ :th "Источник")
                        ($ :th "Примечание")
                        ($ :th "")))
                  ($ :tbody
                     (for [r rows]
                       (let [auto? (= :auto (some-> (:source r) keyword))]
                         ($ :tr {:key (:id r)}
                            ($ :td (opex-category-label (:category r)))
                            ($ :td {:class "num mono"} (fmt/format-rub (:amount r)))
                            ($ :td (mp-name-label (:marketplace r)))
                            ($ :td (if auto?
                                     ($ :span {:class "badge badge-info"} "авто")
                                     ($ :span {:class "badge badge-neutral"} "вручную")))
                            ($ :td (or (:note r) "—"))
                            ($ :td {:class "num"}
                               ($ :button {:class    "btn btn-ghost btn-sm"
                                           :title    "Удалить"
                                           :on-click (fn []
                                                       (dispatch-clearing! api-errors opex-url-prefix)
                                                       (rf/dispatch [::events/delete-opex (:id r)]))}
                                  ($ icon {:name :trash :size 14})))))))
                  ($ :tfoot
                     ($ :tr
                        ($ :td "Итого")
                        ($ :td {:class "num mono"} (fmt/format-rub (:total data)))
                        ($ :td) ($ :td) ($ :td) ($ :td)))))
            (when (seq (:by-category data))
              ($ :div {:style {:display "flex" :gap "6px" :flex-wrap "wrap"
                               :margin-top "10px"}}
                 (for [[cat amt] (sort-by (comp - second) (:by-category data))]
                   ($ :span {:key (str cat) :class "chip"}
                      ($ :span {:class "chip-label"} (opex-category-label cat))
                      ($ :span {:class "chip-value"} (fmt/format-rub amt)))))))))))

;; ---------------------------------------------------------------------------
;; 4. «Регулярные расходы (автоправила)» (015)
;; ---------------------------------------------------------------------------

(defn auto-rule-form->payload
  "Add-rule form → POST /opex/auto-rules body, or nil while invalid.
   Cadence is fixed :monthly (the only backend-supported cadence)."
  [{:keys [category amount marketplace effective-from effective-to]}]
  (let [amt (parse-num amount)]
    (when (and (seq (str category)) amt (pos? amt) (seq (str effective-from)))
      (cond-> {:category       category
               :amount         amt
               :cadence        :monthly
               :effective-from effective-from}
        (seq (str marketplace))  (assoc :marketplace (keyword marketplace))
        (seq (str effective-to)) (assoc :effective-to effective-to)))))

(def ^:private auto-rules-url-prefix "/api/v1/opex/auto-rules")

(defui ^:private auto-rules-card []
  (let [data       (use-subscribe [::subs/opex-auto-rules])
        api-errors (use-subscribe [::subs/api-errors])
        [form set-form!] (use-state {:category "rent" :amount "" :marketplace ""
                                     :effective-from (current-month) :effective-to ""})
        error-msg  (api-error-for api-errors auto-rules-url-prefix)
        rules      (:rules data)
        payload    (auto-rule-form->payload form)]
    (use-effect
     (fn [] (rf/dispatch [::events/load-opex-auto-rules]) js/undefined)
     [])
    (if (and (nil? data) (not error-msg))
      ($ card-skeleton {:title "Регулярные расходы (автоправила)" :rows 3})
      ($ :section {:class "card section-card"}
         ($ :div {:class "section-head"}
            ($ :h3 {:class "section-title"} "Регулярные расходы (автоправила)"))
         (when error-msg ($ card-error {:message error-msg}))
         ($ :p {:style {:color "var(--color-fg-muted)" :font-size "12px"
                        :margin "0 0 10px"}}
            "Правило ежемесячно добавляет строку расходов в каждый месяц периода действия. "
            "Пустой «конец» — правило бессрочное.")
         ($ :div {:style {:display "flex" :gap "8px" :align-items "flex-end"
                          :flex-wrap "wrap" :margin-bottom "12px"}}
            ($ :div
               ($ :label {:class "field-label"} "Категория")
               ($ :select {:class     "input select"
                           :style     {:width "150px"}
                           :value     (:category form)
                           :on-change (fn [e] (set-form! (assoc form :category (.. e -target -value))))}
                  (for [{:keys [value label]} opex-category-options]
                    ($ :option {:key value :value value} label))))
            ($ :div
               ($ :label {:class "field-label"} "Сумма, ₽/мес")
               ($ :input {:class     "input mono"
                          :style     {:width "120px" :text-align "right"}
                          :value     (:amount form)
                          :on-change (fn [e] (set-form! (assoc form :amount (.. e -target -value))))}))
            ($ :div
               ($ :label {:class "field-label"} "Маркетплейс")
               ($ :select {:class     "input select"
                           :style     {:width "160px"}
                           :value     (:marketplace form)
                           :on-change (fn [e] (set-form! (assoc form :marketplace (.. e -target -value))))}
                  (for [{:keys [value label]} opex-mp-options]
                    ($ :option {:key value :value value} label))))
            ($ :div
               ($ :label {:class "field-label"} "Начало")
               ($ :input {:type      "month"
                          :class     "input mono"
                          :value     (:effective-from form)
                          :on-change (fn [e] (set-form! (assoc form :effective-from (.. e -target -value))))}))
            ($ :div
               ($ :label {:class "field-label"} "Конец (опц.)")
               ($ :input {:type      "month"
                          :class     "input mono"
                          :value     (:effective-to form)
                          :on-change (fn [e] (set-form! (assoc form :effective-to (.. e -target -value))))}))
            ($ :button {:class    "btn btn-secondary"
                        :disabled (nil? payload)
                        :on-click (fn []
                                    (dispatch-clearing! api-errors auto-rules-url-prefix)
                                    (rf/dispatch [::events/add-opex-auto-rule payload])
                                    (set-form! (assoc form :amount "")))}
               ($ icon {:name :plus :size 14})
               "Добавить правило"))
         (cond
           (nil? data)
           nil

           (empty? rules)
           ($ :div {:style {:padding "16px" :text-align "center"
                            :color "var(--color-fg-muted)"}}
              "Автоправил пока нет.")

           :else
           ($ :div {:class "tbl-wrap"}
              ($ :table {:class "tbl"}
                 ($ :thead
                    ($ :tr
                       ($ :th "Категория")
                       ($ :th {:class "num"} "Сумма")
                       ($ :th "МП")
                       ($ :th "Каденс")
                       ($ :th "Начало")
                       ($ :th "Конец")
                       ($ :th "")))
                 ($ :tbody
                    (for [r rules]
                      ($ :tr {:key (:id r)}
                         ($ :td (opex-category-label (:category r)))
                         ($ :td {:class "num mono"} (fmt/format-rub (:amount r)))
                         ($ :td (mp-name-label (:marketplace r)))
                         ($ :td "ежемесячно")
                         ($ :td {:class "mono"} (or (:effective-from r) "—"))
                         ($ :td {:class "mono"} (or (:effective-to r) "—"))
                         ($ :td {:class "num"}
                            ($ :button {:class    "btn btn-ghost btn-sm"
                                        :title    "Удалить"
                                        :on-click (fn []
                                                    (dispatch-clearing! api-errors auto-rules-url-prefix)
                                                    (rf/dispatch [::events/delete-opex-auto-rule (:id r)]))}
                               ($ icon {:name :trash :size 14})))))))))))))

;; ---------------------------------------------------------------------------
;; 5. «Телеграм-бот» (017)
;; ---------------------------------------------------------------------------

(def bot-metric-options
  "RU picker labels for the 016 canonical metric slugs (report-schemas
   canonical-metric-slugs — the 017 bot consumes this vocabulary)."
  [{:slug :revenue            :label "Выручка"}
   {:slug :orders             :label "Заказы"}
   {:slug :net-profit         :label "Чистая прибыль"}
   {:slug :gross-margin       :label "Валовая прибыль"}
   {:slug :margin-pct         :label "Маржа, %"}
   {:slug :cogs               :label "Себестоимость"}
   {:slug :mp-commission      :label "Комиссия МП"}
   {:slug :logistics          :label "Логистика"}
   {:slug :storage            :label "Хранение"}
   {:slug :acceptance         :label "Приёмка"}
   {:slug :penalties          :label "Штрафы"}
   {:slug :deduction          :label "Удержания"}
   {:slug :additional         :label "Прочие начисления"}
   {:slug :advertising        :label "Реклама"}
   {:slug :drr-pct            :label "ДРР, %"}
   {:slug :operating-expenses :label "Опер. расходы"}
   {:slug :ebitda             :label "EBITDA"}
   {:slug :tax                :label "Налог"}
   {:slug :vat                :label "НДС"}
   {:slug :cap-by-cost        :label "Сток по себестоимости"}
   {:slug :cap-by-price       :label "Сток по ценам"}
   {:slug :gmroi              :label "GMROI"}
   {:slug :days-of-cover      :label "Дней запаса"}
   {:slug :revenue-abc        :label "ABC по выручке"}
   {:slug :profit-abc         :label "ABC по прибыли"}])

(defn bot-metric-label
  "RU label for a metric slug; unknown slug → its name."
  [slug]
  (or (some (fn [{s :slug l :label}] (when (= s slug) l)) bot-metric-options)
      (name slug)))

(defn toggle-metric
  "Toggle slug in the ordered metrics vector, enforcing ≤ max-n selections.
   Adding beyond max-n is a no-op (the UI also disables those chips)."
  [metrics slug max-n]
  (let [ms (vec (or metrics []))]
    (cond
      (some #{slug} ms)     (vec (remove #{slug} ms))
      (< (count ms) max-n)  (conj ms slug)
      :else                 ms)))

(defn bot-sub->form
  "Server subscription → editable form map."
  [{:keys [chat-id label cadences metrics show-movers? marketplace gate-when-empty]}]
  {:chat-id         (str (or chat-id ""))
   :new?            false
   :label           (or label "")
   :daily?          (contains? (set cadences) :daily)
   :weekly?         (contains? (set cadences) :weekly)
   :metrics         (vec (or metrics []))
   :show-movers?    (boolean show-movers?)
   :marketplace     (or marketplace :all)
   :gate-when-empty (or gate-when-empty :skip)})

(defn bot-form->payload
  "Editable form → save-bot-subscription payload (upsert keyed by :chat-id)."
  [{:keys [chat-id label daily? weekly? metrics show-movers?
           marketplace gate-when-empty]}]
  {:chat-id         (str/trim (str (or chat-id "")))
   :label           (str (or label ""))
   :cadences        (cond-> #{}
                      daily?  (conj :daily)
                      weekly? (conj :weekly))
   :metrics         (vec (or metrics []))
   :show-movers?    (boolean show-movers?)
   :marketplace     (keyword (or marketplace :all))
   :gate-when-empty (keyword (or gate-when-empty :skip))})

(defn bot-form-valid?
  "Saveable? chat-id non-blank + ≥ 1 cadence (mirrors server FR-012 rules)."
  [{:keys [chat-id daily? weekly?]}]
  (boolean (and (seq (str/trim (str (or chat-id ""))))
                (or daily? weekly?))))

(def ^:private empty-bot-form
  {:chat-id "" :new? true :label ""
   :daily? true :weekly? false
   :metrics [] :show-movers? true
   :marketplace :all :gate-when-empty :skip})

(def ^:private bot-url-prefix "/api/v1/bot")

(def ^:private bot-mp-options
  [{:value :all  :label "Все МП"}
   {:value :wb   :label "Wildberries"}
   {:value :ozon :label "Ozon"}
   {:value :ym   :label "Яндекс.Маркет"}])

(def ^:private bot-gate-options
  [{:value :skip   :label "Пропустить (не отправлять)"}
   {:value :notice :label "Прислать уведомление"}])

(defui ^:private bot-sub-editor [{:keys [form set-form! max-metrics on-close api-errors]}]
  (let [selected (set (:metrics form))
        at-max?  (>= (count (:metrics form)) max-metrics)]
    ($ :div {:style {:border "1px solid var(--color-border-subtle)"
                     :border-radius "var(--radius-lg)"
                     :padding "12px" :margin-top "12px"
                     :display "flex" :flex-direction "column" :gap "10px"}}
       ($ :div {:style {:display "flex" :gap "8px" :flex-wrap "wrap" :align-items "flex-end"}}
          ($ :div
             ($ :label {:class "field-label"} "Chat ID")
             ($ :input {:class     "input mono"
                        :style     {:width "140px"}
                        :value     (:chat-id form)
                        :disabled  (not (:new? form))
                        :on-change (fn [e] (set-form! (assoc form :chat-id (.. e -target -value))))}))
          ($ :div {:style {:flex "1 1 160px"}}
             ($ :label {:class "field-label"} "Название")
             ($ :input {:class     "input"
                        :style     {:width "100%"}
                        :value     (:label form)
                        :on-change (fn [e] (set-form! (assoc form :label (.. e -target -value))))}))
          ($ :div
             ($ :label {:class "field-label"} "Маркетплейс")
             ($ :select {:class     "input select"
                         :style     {:width "150px"}
                         :value     (name (:marketplace form))
                         :on-change (fn [e] (set-form! (assoc form :marketplace
                                                              (keyword (.. e -target -value)))))}
                (for [{:keys [value label]} bot-mp-options]
                  ($ :option {:key (name value) :value (name value)} label))))
          ($ :div
             ($ :label {:class "field-label"} "Если данных нет")
             ($ :select {:class     "input select"
                         :style     {:width "210px"}
                         :value     (name (:gate-when-empty form))
                         :on-change (fn [e] (set-form! (assoc form :gate-when-empty
                                                              (keyword (.. e -target -value)))))}
                (for [{:keys [value label]} bot-gate-options]
                  ($ :option {:key (name value) :value (name value)} label)))))
       ($ :div {:style {:display "flex" :gap "16px" :align-items "center" :flex-wrap "wrap"}}
          ($ :label {:style {:display "flex" :gap "6px" :align-items "center"
                             :font-size "13px" :cursor "pointer"}}
             ($ :input {:type      "checkbox"
                        :style     checkbox-style
                        :checked   (boolean (:daily? form))
                        :on-change #(set-form! (update form :daily? not))})
             "Ежедневно")
          ($ :label {:style {:display "flex" :gap "6px" :align-items "center"
                             :font-size "13px" :cursor "pointer"}}
             ($ :input {:type      "checkbox"
                        :style     checkbox-style
                        :checked   (boolean (:weekly? form))
                        :on-change #(set-form! (update form :weekly? not))})
             "Еженедельно")
          ($ :label {:style {:display "flex" :gap "6px" :align-items "center"
                             :font-size "13px" :cursor "pointer"}}
             ($ :input {:type      "checkbox"
                        :style     checkbox-style
                        :checked   (boolean (:show-movers? form))
                        :on-change #(set-form! (update form :show-movers? not))})
             "Топ-изменения (movers)"))
       ($ :div
          ($ :label {:class "field-label"}
             (str "Метрики — выбрано " (count (:metrics form)) " / " max-metrics))
          ($ :div {:style {:display "flex" :flex-wrap "wrap" :gap "6px"}}
             (for [{:keys [slug label]} bot-metric-options]
               (let [sel? (contains? selected slug)]
                 ($ :button {:key      (name slug)
                             :type     "button"
                             :class    (str "chip" (when sel? " is-active"))
                             :disabled (and (not sel?) at-max?)
                             :style    (when (and (not sel?) at-max?) {:opacity 0.45})
                             :on-click #(set-form! (update form :metrics
                                                           toggle-metric slug max-metrics))}
                    label)))))
       ($ :div {:style {:display "flex" :gap "8px" :align-items "center"}}
          ($ :button {:class    "btn btn-primary"
                      :disabled (not (bot-form-valid? form))
                      :on-click (fn []
                                  (dispatch-clearing! api-errors bot-url-prefix)
                                  (rf/dispatch [::events/save-bot-subscription
                                                (bot-form->payload form)])
                                  (on-close))}
             "Сохранить подписку")
          ($ :button {:class "btn btn-ghost" :on-click on-close} "Отмена")
          (when-not (bot-form-valid? form)
            ($ :span {:style {:font-size "12px" :color "var(--color-fg-muted)"}}
               "Нужен chat-id и хотя бы одна периодичность."))))))

(defui ^:private bot-card []
  (let [data       (use-subscribe [::subs/bot-settings])
        api-errors (use-subscribe [::subs/api-errors])
        [form set-form!] (use-state nil)              ; nil ⇒ editor closed
        [test-sent set-test-sent!] (use-state nil)    ; chat-id of last test
        error-msg   (api-error-for api-errors bot-url-prefix)
        subs'       (:subscriptions data)
        max-metrics (or (:max-metrics data) 10)]
    (use-effect
     (fn [] (rf/dispatch [::events/load-bot-settings]) js/undefined)
     [])
    (if (and (nil? data) (not error-msg))
      ($ card-skeleton {:title "Телеграм-бот" :rows 3})
      ($ :section {:class "card section-card"}
         ($ :div {:class "section-head"}
            ($ :h3 {:class "section-title"} "Телеграм-бот")
            (when (some? data)
              (if (:bot-configured? data)
                ($ :span {:class "badge badge-success"} "Бот подключён")
                ($ :span {:class "badge badge-warning"} "Токен бота не задан"))))
         (when error-msg ($ card-error {:message error-msg}))
         ($ :p {:style {:color "var(--color-fg-muted)" :font-size "12px"
                        :margin "0 0 10px"}}
            (str "Дайджест метрик в Telegram по расписанию. До " max-metrics
                 " метрик на подписку; один чат — одна подписка."))
         (cond
           (nil? data) nil

           (empty? subs')
           ($ :div {:style {:padding "12px 0" :color "var(--color-fg-muted)"}}
              "Подписок пока нет.")

           :else
           ($ :div {:class "tbl-wrap"}
              ($ :table {:class "tbl"}
                 ($ :thead
                    ($ :tr
                       ($ :th "Chat ID")
                       ($ :th "Название")
                       ($ :th "Периодичность")
                       ($ :th {:class "num"} "Метрик")
                       ($ :th "МП")
                       ($ :th "")))
                 ($ :tbody
                    (for [s subs']
                      (let [chat-id (:chat-id s)]
                        ($ :tr {:key (str chat-id)}
                           ($ :td {:class "mono"} (str chat-id))
                           ($ :td (or (not-empty (:label s)) "—"))
                           ($ :td (let [cs (set (:cadences s))]
                                    (str/join ", "
                                              (cond-> []
                                                (cs :daily)  (conj "ежедневно")
                                                (cs :weekly) (conj "еженедельно")))))
                           ($ :td {:class "num mono"
                                   :title (str/join ", " (map bot-metric-label (:metrics s)))}
                              (count (:metrics s)))
                           ($ :td (if (= :all (:marketplace s))
                                    "Все"
                                    (mp-name-label (:marketplace s))))
                           ($ :td {:class "num"}
                              ($ :div {:style {:display "flex" :gap "4px"
                                               :justify-content "flex-end"}}
                                 ($ :button {:class    "btn btn-ghost btn-sm"
                                             :on-click #(set-form! (bot-sub->form s))}
                                    "Изменить")
                                 ($ :button {:class    "btn btn-ghost btn-sm"
                                             :disabled (not (:bot-configured? data))
                                             :on-click (fn []
                                                         (dispatch-clearing! api-errors bot-url-prefix)
                                                         (rf/dispatch [::events/send-bot-test chat-id])
                                                         (set-test-sent! chat-id))}
                                    (if (= test-sent chat-id) "Тест отправлен" "Отправить тест"))
                                 ($ :button {:class    "btn btn-ghost btn-sm"
                                             :title    "Удалить"
                                             :on-click (fn []
                                                         (dispatch-clearing! api-errors bot-url-prefix)
                                                         (rf/dispatch [::events/delete-bot-subscription chat-id]))}
                                    ($ icon {:name :trash :size 14})))))))))))
         (if (nil? form)
           ($ :div {:style {:margin-top "12px"}}
              ($ :button {:class    "btn btn-secondary"
                          :on-click #(set-form! empty-bot-form)}
                 ($ icon {:name :plus :size 14})
                 "Добавить подписку"))
           ($ bot-sub-editor {:form form :set-form! set-form!
                              :max-metrics max-metrics
                              :api-errors api-errors
                              :on-close #(set-form! nil)}))))))

;; ---------------------------------------------------------------------------
;; Page root
;; ---------------------------------------------------------------------------

(defui settings []
  (let [data   (use-subscribe [::subs/settings-data])
        status (use-subscribe [::subs/settings-status])]
    (use-effect (fn [] (rf/dispatch [::events/load-settings]) js/undefined) [])
    ($ :div {:class "page-content"}
       ($ :p {:style {:color "var(--color-fg-muted)" :margin-bottom "12px"}}
          "Секреты хранятся на сервере и применяются сразу — перезапуск не нужен. "
          "Пустое поле при сохранении оставляет текущее значение без изменений.")
       (for [spec mp-specs]
         ($ mp-card {:key (name (:mp spec)) :spec spec
                     :data (or data {}) :status (or status {})}))
       ($ tax-card)
       ($ opex-card)
       ($ auto-rules-card)
       ($ bot-card))))
