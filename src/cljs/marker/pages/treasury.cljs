(ns marker.pages.treasury
  "«Казначейство» — spec 019 treasury ledger (ДДС / реестр / ДЗ-КЗ).

   Tabs:
     :cashflow    → ДДС-матрица (rows × [total + месяцы newest-first], net,
                    uncategorised-бейдж, by_category↔by_account, фильтр
                    счетов, режим actuals↔with-planned)
     :registry    → реестр операций (пагинация, summary, фильтры, формы
                    add-operation / add-account / add-counterparty,
                    автоправила + «Классифицировать»)
     :obligations → ДЗ/КЗ dashboard (summary-карточки, 12-мес Chart.js
                    line-график, drill-down список со status-бейджами и
                    «Погасить», режим actuals↔with-planned)

   ВСЕ деньги ледгера — decimal-as-string \"0.00\" (FR-019). Рендер ТОЛЬКО
   через fmt/format-decimal-rub / fmt/format-decimal-str — НИКОГДА
   js/parseFloat (точность до копейки). Единственное исключение — y-значения
   Chart.js-графика (пиксельная визуализация, не текст): там js/Number.

   Active tab приходит как :tab prop из marker.core (sectioned route);
   ::subs/active-tab — app-db источник того же значения."
  (:require ["chart.js/auto" :refer [Chart]]
            [clojure.string :as str]
            [uix.core :refer [$ defui use-state use-effect use-memo use-ref]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.state.subs   :as subs]
            [marker.state.events :as events]
            [marker.ui.chrome    :refer [tabs]]
            [marker.ui.icons     :refer [icon]]
            [marker.util.nav     :as nav]
            [marker.router       :as router]
            [marker.util.format  :as fmt]))

;; ---------------------------------------------------------------------------
;; Pure helpers (PURE, node-tested in test/cljs/marker/pages/treasury_test.cljs)
;; ---------------------------------------------------------------------------

(def month-names-ru
  ["Янв" "Фев" "Мар" "Апр" "Май" "Июн" "Июл" "Авг" "Сен" "Окт" "Ноя" "Дек"])

(defn column-label
  "ДДС column id → RU label: \"total\" → «Итого», \"2026-06\" → «Июн 2026».
   Unknown format passes through unchanged."
  [col]
  (if (= col "total")
    "Итого"
    (let [[y m] (str/split (str col) #"-")
          idx   (dec (js/parseInt (or m "") 10))]
      (if (and y (>= idx 0) (< idx 12))
        (str (nth month-names-ru idx) " " y)
        (str col)))))

(defn- pad2 [n] (-> n str (.padStart 2 "0")))

(defn- iso-date [^js d]
  (str (.getFullYear d) "-" (pad2 (inc (.getMonth d))) "-" (pad2 (.getDate d))))

(defn cashflow-window
  "Default ДДС window — last 12 months: from = first day of the month
   11 months back, to = today. Both ISO yyyy-MM-dd."
  ([] (cashflow-window (js/Date.)))
  ([^js now]
   {:from (iso-date (js/Date. (.getFullYear now) (- (.getMonth now) 11) 1))
    :to   (iso-date now)}))

(defn cell-str
  "Exact decimal cell of a ДДС row/net line for a column, or nil when absent."
  [row col]
  (get (:cells row) col))

(defn negative-cell?
  "True when a decimal-as-string amount is negative (string check, no parse)."
  [s]
  (and (some? s) (str/starts-with? (str/trim (str s)) "-")))

(defn uncategorised-label
  "Badge text for the ДДС uncategorised-operation count (FR-005)."
  [n]
  (str "Без категории: " (fmt/format-int (or n 0))))

(def direction-labels
  {:income "Приход" :expense "Расход" :transfer "Перевод"})

(defn direction-label [dir]
  (get direction-labels dir "—"))

(def category-options
  "Shared 019/015 category taxonomy — mirrors
   analitica.domain.treasury.cashflow/treasury-categories verbatim (FR-023)."
  [["purchase"  "Закупка товара"]
   ["logistics" "Логистика"]
   ["marketing" "Реклама"]
   ["services"  "Услуги"]
   ["salary"    "Зарплата"]
   ["rent"      "Аренда"]
   ["mp-payout" "Маркетплейс / выплаты"]
   ["taxes"     "Налоги"]
   ["loans"     "Кредиты / займы"]
   ["capex"     "Оборудование / CapEx"]
   ["other"     "Прочее"]])

(defn category-title
  "RU title for a category slug; nil → «—», unknown slug passes through."
  [slug]
  (if (nil? slug)
    "—"
    (or (some (fn [[v t]] (when (= v slug) t)) category-options)
        (str slug))))

(defn account-name
  "Account name by id from the ::treasury-accounts :accounts vector."
  [accounts id]
  (or (some #(when (= (:id %) id) (:name %)) accounts)
      (when id (str "Счёт #" id))
      "—"))

(defn counterparty-name
  "Counterparty name by id from ::treasury-counterparties :counterparties."
  [counterparties id]
  (or (some #(when (= (:id %) id) (:name %)) counterparties)
      (when id (str "Контрагент #" id))
      "—"))

(defn operation-account-label
  "Account cell of a registry row. Transfers show BOTH accounts «A → B»."
  [op accounts]
  (if (= :transfer (:direction op))
    (str (account-name accounts (:account-id op))
         " → "
         (account-name accounts (:transfer-account-id op)))
    (account-name accounts (:account-id op))))

(defn valid-amount?
  "Client-side check for a ledger amount string: positive decimal with at most
   2 fraction digits (sign is carried by :direction, not the amount)."
  [s]
  (boolean (and (string? s) (re-matches #"\d+(\.\d{1,2})?" (str/trim s)))))

(defn build-ops-filters
  "PURE: UI selections → operations query filter map. Blank selections are
   dropped; ids parsed to ints; status/regular tri-states expand to the
   backend's planned/confirmed/regular booleans."
  [{:keys [from to direction account-id counterparty-id category status
           regular page page-size]}]
  (cond-> {:page (or page 1) :page-size (or page-size 20)}
    (not (str/blank? from))            (assoc :from from)
    (not (str/blank? to))              (assoc :to to)
    (not (str/blank? direction))       (assoc :direction (keyword direction))
    (not (str/blank? account-id))      (assoc :account-id (js/parseInt account-id 10))
    (not (str/blank? counterparty-id)) (assoc :counterparty-id (js/parseInt counterparty-id 10))
    (not (str/blank? category))        (assoc :category category)
    (= status "confirmed")             (assoc :confirmed true)
    (= status "planned")               (assoc :planned true)
    (= regular "regular")              (assoc :regular true)
    (= regular "oneoff")               (assoc :regular false)))

(def status-badges
  "Obligation lifecycle status → badge class + RU label.
   overdue=danger, due-soon=warning, settled=neutral, open=info."
  {:overdue  {:class "badge badge-danger"  :label "Просрочено"}
   :due-soon {:class "badge badge-warning" :label "Скоро срок"}
   :settled  {:class "badge badge-neutral" :label "Погашено"}
   :open     {:class "badge badge-info"    :label "Открыто"}})

(defn obligation-badge
  "Badge descriptor for an obligation status keyword (unknown → open)."
  [status]
  (get status-badges status (:open status-badges)))

(defn dynamics-12?
  "FR-016 guard: dynamics must carry EXACTLY 12 monthly points."
  [points]
  (= 12 (count points)))

(def ^:private receivable-color "#16a34a")
(def ^:private payable-color   "#dc2626")
(def ^:private balance-color   "#4f46e5")

(defn dynamics-chart-config
  "PURE: Chart.js :data map (CLJ) for the 12-month ДЗ/КЗ dynamics.
   y-values go through js/Number ONLY for pixel plotting — every textual
   money render stays on the exact decimal string."
  [points]
  {:labels   (mapv #(column-label (:month %)) points)
   :datasets [{:label       "ДЗ (нам должны)"
               :data        (mapv #(js/Number (:receivable %)) points)
               :borderColor receivable-color
               :backgroundColor "rgba(22,163,74,0.10)"
               :borderWidth 2 :pointRadius 0 :pointHoverRadius 4 :tension 0.3}
              {:label       "КЗ (мы должны)"
               :data        (mapv #(js/Number (:payable %)) points)
               :borderColor payable-color
               :backgroundColor "rgba(220,38,38,0.10)"
               :borderWidth 2 :pointRadius 0 :pointHoverRadius 4 :tension 0.3}
              {:label       "Баланс (ДЗ − КЗ)"
               :data        (mapv #(js/Number (:balance %)) points)
               :borderColor balance-color
               :borderDash  [4 4]
               :borderWidth 1.5 :pointRadius 0 :pointHoverRadius 4 :tension 0.3}]})

(defn api-error-for
  "First error :message under any :marker/api-errors key that starts with
   url-prefix (load URLs carry query strings, so exact match won't hit)."
  [api-errors url-prefix]
  (some (fn [[url err]]
          (when (str/starts-with? (str url) url-prefix)
            (:message err)))
        api-errors))

(defn total-pages
  "Page count for a paginated list; never below 1."
  [total page-size]
  (max 1 (js/Math.ceil (/ (or total 0) (max 1 (or page-size 1))))))

;; ---------------------------------------------------------------------------
;; Shared chrome: skeleton / error banner / chip toggle / pager / field
;; ---------------------------------------------------------------------------

(defui ^:private skel-card [{:keys [rows]}]
  ($ :section {:class "card section-card"}
     ($ :table {:class "tbl"}
        ($ :tbody
           (for [i (range (or rows 8))]
             ($ :tr {:key i}
                (for [j (range 5)]
                  ($ :td {:key j}
                     ($ :div {:class "skel"
                              :style {:height "14px" :border-radius "4px"}})))))))))

(defui ^:private error-banner [{:keys [title message on-retry]}]
  ($ :div {:class "alert alert-danger" :style {:margin-bottom "12px"}}
     ($ icon {:name :danger :class "alert-icon"})
     ($ :div {:class "alert-body"}
        ($ :div {:class "alert-title"} title)
        ($ :div (or message "Проверьте соединение с сервером.")))
     (when on-retry
       ($ :button {:class    "btn btn-ghost btn-sm"
                   :style    {:color "inherit" :border "1px solid currentColor"}
                   :on-click on-retry}
          "Повторить"))))

(defui ^:private chip-toggle
  "Small chips single-select: options = [[value label] …]."
  [{:keys [options value on-change]}]
  ($ :div {:style {:display "flex" :gap "6px"}}
     (for [[v label] options]
       ($ :button {:key      (str v)
                   :class    (str "chip" (when (= v value) " is-active"))
                   :on-click #(on-change v)}
          label))))

(defui ^:private pager [{:keys [page total page-size on-page]}]
  (let [pages (total-pages total page-size)]
    ($ :div {:class "row"
             :style {:justify-content "flex-end" :align-items "center"
                     :gap "8px" :padding "10px 12px"}}
       ($ :span {:class "muted" :style {:font-size "12px"}}
          (str "Страница " page " из " pages
               " · " (fmt/format-int (or total 0)) " "
               (fmt/plural-ru (or total 0) "запись" "записи" "записей")))
       ($ :button {:class    "btn btn-secondary btn-sm"
                   :disabled (<= page 1)
                   :on-click #(on-page (dec page))}
          "Назад")
       ($ :button {:class    "btn btn-secondary btn-sm"
                   :disabled (>= page pages)
                   :on-click #(on-page (inc page))}
          "Вперёд"))))

(defui ^:private field
  "Labelled form field wrapper (.field-label + control)."
  [{:keys [label children]}]
  ($ :div {:style {:display "flex" :flex-direction "column" :gap "4px"}}
     ($ :div {:class "field-label"} label)
     children))

;; ---------------------------------------------------------------------------
;; Вкладка 1 — ДДС (cash-flow matrix)
;; ---------------------------------------------------------------------------

(def ^:private mode-options
  [[:actuals "Факт"] [:with-planned "С плановыми"]])

(def ^:private group-options
  [[:category "По категориям"] [:account "По счетам"]])

(defui ^:private cashflow-matrix [{:keys [cf group-by*]}]
  (let [columns (or (:columns cf) [])
        rows    (or (:rows cf) [])
        net     (:net cf)]
    ($ :div {:class "tbl-wrap"}
       ($ :table {:class "tbl"}
          ($ :thead
             ($ :tr
                ($ :th (if (= group-by* :account) "Счёт" "Статья"))
                (for [col columns]
                  ($ :th {:key col :class "num"} (column-label col)))))
          ($ :tbody
             (if (empty? rows)
               ($ :tr
                  ($ :td {:col-span (inc (count columns))
                          :style {:text-align "center" :padding "32px"
                                  :color "var(--color-fg-muted)"}}
                     "Нет категоризированных операций за период."))
               (for [row rows]
                 ($ :tr {:key (str (:key row))}
                    ($ :td (:label row))
                    (for [col columns]
                      (let [v (cell-str row col)]
                        ($ :td {:key   col
                                :class "num mono"
                                :style {:color (when (negative-cell? v)
                                                 "var(--color-delta-negative)")}}
                           (fmt/format-decimal-rub v))))))))
          (when net
            ($ :tfoot
               ($ :tr {:style {:background "var(--color-bg-subtle)" :font-weight 600}}
                  ($ :td (or (:label net) "Чистый денежный поток"))
                  (for [col columns]
                    (let [v (cell-str net col)]
                      ($ :td {:key   col
                              :class "num mono"
                              :style {:color (when (negative-cell? v)
                                               "var(--color-delta-negative)")}}
                         (fmt/format-decimal-rub v)))))))))))

(defui ^:private cashflow-tab []
  (let [cf         (use-subscribe [::subs/treasury-cashflow])
        loading?   (use-subscribe [::subs/treasury-cashflow-loading?])
        accounts   (:accounts (use-subscribe [::subs/treasury-accounts]))
        api-errors (use-subscribe [::subs/api-errors])
        error-msg  (api-error-for api-errors "/api/v1/treasury/cashflow")
        [group-by*  set-group-by!] (use-state :category)
        [mode       set-mode!]     (use-state :actuals)
        [account-id set-account!]  (use-state "")
        window     (use-memo #(cashflow-window) [])
        filters    (cond-> (assoc window :group-by group-by* :mode mode)
                     (not (str/blank? account-id))
                     (assoc :account-ids [(js/parseInt account-id 10)]))
        load!      (fn [] (rf/dispatch [::events/load-treasury-cashflow filters]))]

    ;; accounts feed the account filter (mount once)
    (use-effect
     (fn [] (rf/dispatch [::events/load-treasury-accounts]) js/undefined)
     [])

    ;; (re)load on every control change
    (use-effect
     (fn []
       (rf/dispatch [::events/load-treasury-cashflow
                     (cond-> (assoc window :group-by group-by* :mode mode)
                       (not (str/blank? account-id))
                       (assoc :account-ids [(js/parseInt account-id 10)]))])
       js/undefined)
     [window group-by* mode account-id])

    (cond
      (and loading? (nil? cf))
      ($ :div {:class "page-content"} ($ skel-card {:rows 10}))

      (and error-msg (nil? cf))
      ($ :div {:class "page-content"}
         ($ error-banner {:title    "Не удалось загрузить ДДС"
                          :message  error-msg
                          :on-retry load!}))

      :else
      ($ :div {:class "page-content"}
         (when error-msg
           ($ error-banner {:title    "Не удалось обновить ДДС"
                            :message  error-msg
                            :on-retry load!}))
         ($ :section {:class "card section-card"}
            ($ :div {:class "section-head"}
               ($ :div
                  ($ :h3 {:class "section-title"}
                     "ДДС — движение денежных средств"
                     (when (pos? (or (:uncategorised-count cf) 0))
                       ($ :span {:class "badge badge-warning"
                                 :style {:margin-left "8px"}
                                 :title "Операции без категории не входят в матрицу — настройте автоправила во вкладке «Реестр»"}
                          (uncategorised-label (:uncategorised-count cf)))))
                  ($ :div {:class "section-subtitle"}
                     (str "Последние 12 месяцев · " (:from window) " — " (:to window))))
               ($ :div {:class "row" :style {:gap "10px" :flex-wrap "wrap"}}
                  ($ chip-toggle {:options   group-options
                                  :value     group-by*
                                  :on-change set-group-by!})
                  ($ chip-toggle {:options   mode-options
                                  :value     mode
                                  :on-change set-mode!})
                  ($ :select {:class     "select"
                              :value     account-id
                              :on-change #(set-account! (.. % -target -value))}
                     ($ :option {:value ""} "Все счета")
                     (for [a accounts]
                       ($ :option {:key (:id a) :value (str (:id a))}
                          (:name a))))))
            ($ cashflow-matrix {:cf cf :group-by* group-by*}))))))

;; ---------------------------------------------------------------------------
;; Вкладка 2 — Реестр (operations + accounts + counterparties + auto-rules)
;; ---------------------------------------------------------------------------

(defui ^:private op-form [{:keys [accounts counterparties on-done]}]
  (let [[op-date   set-date!]   (use-state (iso-date (js/Date.)))
        [amount    set-amount!] (use-state "")
        [direction set-dir!]    (use-state "expense")
        [acc       set-acc!]    (use-state "")
        [acc2      set-acc2!]   (use-state "")
        [cp        set-cp!]     (use-state "")
        [category  set-cat!]    (use-state "")
        [confirmed set-conf!]   (use-state true)
        [regular   set-reg!]    (use-state false)
        [descr     set-descr!]  (use-state "")
        [err       set-err!]    (use-state nil)
        transfer?  (= direction "transfer")
        submit!
        (fn []
          (cond
            (str/blank? op-date)       (set-err! "Укажите дату")
            (not (valid-amount? amount)) (set-err! "Сумма — положительное число, максимум 2 знака после точки")
            (str/blank? acc)           (set-err! "Выберите счёт")
            (and transfer? (str/blank? acc2)) (set-err! "Выберите счёт-получатель")
            (and transfer? (= acc acc2))      (set-err! "Счета перевода должны различаться")
            :else
            (do (set-err! nil)
                (rf/dispatch
                 [::events/save-treasury-operation
                  (cond-> {:op-date   op-date
                           :amount    (str/trim amount)
                           :currency  "RUB"
                           :direction (keyword direction)
                           :account-id (js/parseInt acc 10)
                           :confirmed confirmed
                           :regular   regular}
                    transfer?
                    (assoc :transfer-account-id (js/parseInt acc2 10))
                    (and (not transfer?) (not (str/blank? cp)))
                    (assoc :counterparty-id (js/parseInt cp 10))
                    (and (not transfer?) (not (str/blank? category)))
                    (assoc :category category :category-source :manual)
                    (not (str/blank? descr))
                    (assoc :description descr))])
                (on-done))))]
    ($ :div {:class "card section-card" :style {:margin-bottom "12px"}}
       ($ :div {:class "section-head"}
          ($ :h3 {:class "section-title"} "Новая операция"))
       ($ :div {:class "row" :style {:gap "12px" :flex-wrap "wrap" :align-items "flex-end"}}
          ($ field {:label "Дата"}
             ($ :input {:class "input" :type "date" :value op-date
                        :on-change #(set-date! (.. % -target -value))}))
          ($ field {:label "Сумма, ₽"}
             ($ :input {:class "input" :placeholder "0.00" :value amount
                        :style {:width "120px"}
                        :on-change #(set-amount! (.. % -target -value))}))
          ($ field {:label "Направление"}
             ($ :select {:class "select" :value direction
                         :on-change #(set-dir! (.. % -target -value))}
                ($ :option {:value "income"}   "Приход")
                ($ :option {:value "expense"}  "Расход")
                ($ :option {:value "transfer"} "Перевод")))
          ($ field {:label (if transfer? "Со счёта" "Счёт")}
             ($ :select {:class "select" :value acc
                         :on-change #(set-acc! (.. % -target -value))}
                ($ :option {:value ""} "—")
                (for [a accounts]
                  ($ :option {:key (:id a) :value (str (:id a))} (:name a)))))
          (when transfer?
            ($ field {:label "На счёт"}
               ($ :select {:class "select" :value acc2
                           :on-change #(set-acc2! (.. % -target -value))}
                  ($ :option {:value ""} "—")
                  (for [a accounts]
                    ($ :option {:key (:id a) :value (str (:id a))} (:name a))))))
          (when-not transfer?
            ($ field {:label "Контрагент"}
               ($ :select {:class "select" :value cp
                           :on-change #(set-cp! (.. % -target -value))}
                  ($ :option {:value ""} "—")
                  (for [c counterparties]
                    ($ :option {:key (:id c) :value (str (:id c))} (:name c))))))
          (when-not transfer?
            ($ field {:label "Категория"}
               ($ :select {:class "select" :value category
                           :on-change #(set-cat! (.. % -target -value))}
                  ($ :option {:value ""} "Без категории")
                  (for [[v t] category-options]
                    ($ :option {:key v :value v} t)))))
          ($ field {:label "Описание"}
             ($ :input {:class "input" :value descr :placeholder "Комментарий…"
                        :style {:width "180px"}
                        :on-change #(set-descr! (.. % -target -value))}))
          ($ :label {:class "field-label"
                     :style {:display "flex" :gap "6px" :align-items "center"}}
             ($ :input {:type "checkbox" :checked confirmed
                        :on-change #(set-conf! (.. % -target -checked))})
             "Проведена (факт)")
          ($ :label {:class "field-label"
                     :style {:display "flex" :gap "6px" :align-items "center"}}
             ($ :input {:type "checkbox" :checked regular
                        :on-change #(set-reg! (.. % -target -checked))})
             "Регулярная")
          ($ :button {:class "btn btn-primary btn-sm" :on-click submit!}
             "Сохранить")
          ($ :button {:class "btn btn-ghost btn-sm" :on-click on-done}
             "Отмена"))
       (when err
         ($ :div {:class "badge badge-danger" :style {:margin-top "8px"}} err)))))

(defui ^:private account-form [{:keys [accounts total-balance on-done]}]
  (let [[nm   set-nm!]   (use-state "")
        [kind set-kind!] (use-state "bank")
        [mp   set-mp!]   (use-state "")
        [err  set-err!]  (use-state nil)
        submit!
        (fn []
          (if (str/blank? nm)
            (set-err! "Укажите название счёта")
            (do (set-err! nil)
                (rf/dispatch
                 [::events/add-treasury-account
                  (cond-> {:name (str/trim nm) :kind (keyword kind) :currency "RUB"}
                    (not (str/blank? mp)) (assoc :marketplace (keyword mp)))])
                (on-done))))]
    ($ :div {:class "card section-card" :style {:margin-bottom "12px"}}
       ($ :div {:class "section-head"}
          ($ :div
             ($ :h3 {:class "section-title"} "Счета")
             ($ :div {:class "section-subtitle"}
                (str "Общий баланс: " (fmt/format-decimal-rub total-balance)))))
       (when (seq accounts)
         ($ :div {:class "tbl-wrap"}
            ($ :table {:class "tbl"}
               ($ :thead
                  ($ :tr ($ :th "Название") ($ :th "Тип") ($ :th "МП")
                     ($ :th {:class "num"} "Баланс") ($ :th)))
               ($ :tbody
                  (for [a accounts]
                    ($ :tr {:key (:id a)}
                       ($ :td (:name a))
                       ($ :td (case (:kind a)
                                :bank "Банк" :wallet "Кошелёк"
                                :mp-settlement "МП-расчёты" "—"))
                       ($ :td (if (:marketplace a)
                                (.toUpperCase (name (:marketplace a))) "—"))
                       ($ :td {:class "num mono"}
                          (fmt/format-decimal-rub (:balance a)))
                       ($ :td
                          ($ :button {:class "icon-btn"
                                      :title "Удалить / архивировать"
                                      :on-click #(rf/dispatch
                                                  [::events/delete-treasury-account (:id a)])}
                             ($ icon {:name :trash :size 14})))))))))
       ($ :div {:class "row" :style {:gap "12px" :flex-wrap "wrap"
                                     :align-items "flex-end" :margin-top "10px"}}
          ($ field {:label "Название"}
             ($ :input {:class "input" :value nm :placeholder "Расчётный счёт…"
                        :on-change #(set-nm! (.. % -target -value))}))
          ($ field {:label "Тип"}
             ($ :select {:class "select" :value kind
                         :on-change #(set-kind! (.. % -target -value))}
                ($ :option {:value "bank"}          "Банк")
                ($ :option {:value "wallet"}        "Кошелёк")
                ($ :option {:value "mp-settlement"} "МП-расчёты")))
          ($ field {:label "Маркетплейс"}
             ($ :select {:class "select" :value mp
                         :on-change #(set-mp! (.. % -target -value))}
                ($ :option {:value ""} "—")
                ($ :option {:value "wb"}   "WB")
                ($ :option {:value "ozon"} "Ozon")
                ($ :option {:value "ym"}   "YM")))
          ($ :button {:class "btn btn-primary btn-sm" :on-click submit!} "Добавить")
          ($ :button {:class "btn btn-ghost btn-sm" :on-click on-done} "Закрыть"))
       (when err
         ($ :div {:class "badge badge-danger" :style {:margin-top "8px"}} err)))))

(defui ^:private counterparty-form [{:keys [counterparties on-done]}]
  (let [[nm   set-nm!]   (use-state "")
        [kind set-kind!] (use-state "supplier")
        [err  set-err!]  (use-state nil)
        submit!
        (fn []
          (if (str/blank? nm)
            (set-err! "Укажите название контрагента")
            (do (set-err! nil)
                (rf/dispatch [::events/add-treasury-counterparty
                              {:name (str/trim nm) :kind (keyword kind)}])
                (on-done))))]
    ($ :div {:class "card section-card" :style {:margin-bottom "12px"}}
       ($ :div {:class "section-head"}
          ($ :h3 {:class "section-title"} "Контрагенты"))
       (when (seq counterparties)
         ($ :div {:class "row" :style {:gap "6px" :flex-wrap "wrap" :margin-bottom "10px"}}
            (for [c counterparties]
              ($ :span {:key (:id c) :class "chip"}
                 (:name c)
                 ($ :span {:class "muted" :style {:margin-left "4px"}}
                    (str "· " (fmt/format-int (or (:operation-count c) 0))))))))
       ($ :div {:class "row" :style {:gap "12px" :flex-wrap "wrap" :align-items "flex-end"}}
          ($ field {:label "Название"}
             ($ :input {:class "input" :value nm :placeholder "ООО «Поставщик»…"
                        :on-change #(set-nm! (.. % -target -value))}))
          ($ field {:label "Тип"}
             ($ :select {:class "select" :value kind
                         :on-change #(set-kind! (.. % -target -value))}
                ($ :option {:value "supplier"}      "Поставщик")
                ($ :option {:value "contractor"}    "Подрядчик")
                ($ :option {:value "marketplace"}   "Маркетплейс")
                ($ :option {:value "tax-authority"} "Налоговая")
                ($ :option {:value "own"}           "Свой счёт")))
          ($ :button {:class "btn btn-primary btn-sm" :on-click submit!} "Добавить")
          ($ :button {:class "btn btn-ghost btn-sm" :on-click on-done} "Закрыть"))
       (when err
         ($ :div {:class "badge badge-danger" :style {:margin-top "8px"}} err)))))

(def ^:private match-field-labels
  {:counterparty "Контрагент" :account "Счёт" :description "Описание"})

(def ^:private match-op-labels
  {:equals "равно" :contains "содержит"})

(defui ^:private auto-rules-section []
  (let [rules-data  (use-subscribe [::subs/treasury-auto-rules])
        result      (use-subscribe [::subs/treasury-classify-result])
        classifying? (use-subscribe [::subs/treasury-classify-result-loading?])
        rules       (or (:rules rules-data) (:auto-rules rules-data) [])
        [mf   set-mf!]   (use-state "counterparty")
        [mop  set-mop!]  (use-state "equals")
        [mval set-mval!] (use-state "")
        [cat  set-cat!]  (use-state "other")
        [prio set-prio!] (use-state "100")
        [err  set-err!]  (use-state nil)
        submit!
        (fn []
          (cond
            (str/blank? mval) (set-err! "Укажите значение для сопоставления")
            (str/blank? cat)  (set-err! "Выберите категорию")
            :else
            (do (set-err! nil)
                (rf/dispatch [::events/add-treasury-auto-rule
                              {:match-field (keyword mf)
                               :match-op    (keyword mop)
                               :match-value (str/trim mval)
                               :category    cat
                               :priority    (js/parseInt (if (str/blank? prio) "100" prio) 10)
                               :enabled     true}])
                (set-mval! ""))))]
    ($ :section {:class "card section-card"}
       ($ :div {:class "section-head"}
          ($ :div
             ($ :h3 {:class "section-title"} "Автоправила классификации")
             ($ :div {:class "section-subtitle"}
                "Правила присваивают категории операциям без категории; ручные категории не перезаписываются (приоритет: меньше — раньше)"))
          ($ :button {:class    "btn btn-primary btn-sm"
                      :disabled (boolean classifying?)
                      :on-click #(rf/dispatch [::events/classify-treasury])}
             (if classifying? "Классификация…" "Классифицировать")))
       (when result
         ($ :div {:class "row" :style {:gap "8px" :flex-wrap "wrap" :margin-bottom "10px"}}
            ($ :span {:class "badge badge-success"}
               (str "Классифицировано: " (fmt/format-int (or (:classified result) 0))))
            ($ :span {:class "badge badge-warning"}
               (str "Осталось без категории: "
                    (fmt/format-int (or (:left-uncategorised result) 0))))
            ($ :span {:class "badge badge-neutral"}
               (str "Ручных сохранено: "
                    (fmt/format-int (or (:manual-preserved result) 0))))))
       ($ :div {:class "tbl-wrap"}
          ($ :table {:class "tbl"}
             ($ :thead
                ($ :tr
                   ($ :th "Поле") ($ :th "Условие") ($ :th "Значение")
                   ($ :th "Категория") ($ :th {:class "num"} "Приоритет")
                   ($ :th "Статус")))
             ($ :tbody
                (if (empty? rules)
                  ($ :tr
                     ($ :td {:col-span 6
                             :style {:text-align "center" :padding "24px"
                                     :color "var(--color-fg-muted)"}}
                        "Правил пока нет — создайте первое ниже."))
                  (for [r rules]
                    ($ :tr {:key (:id r)}
                       ($ :td (get match-field-labels (:match-field r)
                                   (str (:match-field r))))
                       ($ :td (get match-op-labels (:match-op r)
                                   (str (:match-op r))))
                       ($ :td {:class "mono"} (:match-value r))
                       ($ :td (category-title (:category r)))
                       ($ :td {:class "num mono"} (fmt/format-int (:priority r)))
                       ($ :td (if (:enabled r)
                                ($ :span {:class "badge badge-success"} "Активно")
                                ($ :span {:class "badge badge-neutral"} "Выключено")))))))))
       ($ :div {:class "row" :style {:gap "12px" :flex-wrap "wrap"
                                     :align-items "flex-end" :margin-top "10px"}}
          ($ field {:label "Поле"}
             ($ :select {:class "select" :value mf
                         :on-change #(set-mf! (.. % -target -value))}
                ($ :option {:value "counterparty"} "Контрагент")
                ($ :option {:value "account"}      "Счёт")
                ($ :option {:value "description"}  "Описание")))
          ($ field {:label "Условие"}
             ($ :select {:class "select" :value mop
                         :on-change #(set-mop! (.. % -target -value))}
                ($ :option {:value "equals"}   "равно")
                ($ :option {:value "contains"} "содержит")))
          ($ field {:label "Значение"}
             ($ :input {:class "input" :value mval :placeholder "Ozon…"
                        :on-change #(set-mval! (.. % -target -value))}))
          ($ field {:label "Категория"}
             ($ :select {:class "select" :value cat
                         :on-change #(set-cat! (.. % -target -value))}
                (for [[v t] category-options]
                  ($ :option {:key v :value v} t))))
          ($ field {:label "Приоритет"}
             ($ :input {:class "input" :value prio :style {:width "80px"}
                        :on-change #(set-prio! (.. % -target -value))}))
          ($ :button {:class "btn btn-secondary btn-sm" :on-click submit!}
             ($ icon {:name :plus :size 14})
             "Правило"))
       (when err
         ($ :div {:class "badge badge-danger" :style {:margin-top "8px"}} err)))))

(defui ^:private ops-summary [{:keys [summary]}]
  ($ :div {:class "kpi-grid" :style {:margin-bottom "12px"}}
     ($ :div {:class "kpi"}
        ($ :div {:class "kpi-label"} "Приход")
        ($ :div {:class "kpi-value mono"
                 :style {:color "var(--color-delta-positive)"}}
           (fmt/format-decimal-rub (:total-income summary))))
     ($ :div {:class "kpi"}
        ($ :div {:class "kpi-label"} "Расход")
        ($ :div {:class "kpi-value mono"
                 :style {:color "var(--color-delta-negative)"}}
           (fmt/format-decimal-rub (:total-expense summary))))
     ($ :div {:class "kpi"}
        ($ :div {:class "kpi-label"} "Баланс")
        ($ :div {:class "kpi-value mono"}
           (fmt/format-decimal-rub (:balance summary))))
     ($ :div {:class "kpi"}
        ($ :div {:class "kpi-label"} "Плановых операций")
        ($ :div {:class "kpi-value mono"}
           (fmt/format-int (or (:planned-count summary) 0))))))

(defui ^:private ops-table [{:keys [operations accounts counterparties]}]
  ($ :div {:class "tbl-wrap"}
     ($ :table {:class "tbl"}
        ($ :thead
           ($ :tr
              ($ :th "Дата")
              ($ :th {:class "num"} "Сумма")
              ($ :th "Направление")
              ($ :th "Счёт")
              ($ :th "Контрагент")
              ($ :th "Категория")
              ($ :th "Статус")
              ($ :th "Регулярная")))
        ($ :tbody
           (if (empty? operations)
             ($ :tr
                ($ :td {:col-span 8
                        :style {:text-align "center" :padding "32px"
                                :color "var(--color-fg-muted)"}}
                   "Операций по текущему фильтру нет."))
             (for [op operations]
               ($ :tr {:key (:id op)}
                  ($ :td {:class "mono"} (:op-date op))
                  ($ :td {:class "num mono"
                          :style {:font-weight 600
                                  :color (case (:direction op)
                                           :income  "var(--color-delta-positive)"
                                           :expense "var(--color-delta-negative)"
                                           "var(--color-fg-muted)")}}
                     (fmt/format-decimal-rub (:amount op)))
                  ($ :td (direction-label (:direction op)))
                  ($ :td (operation-account-label op accounts))
                  ($ :td (counterparty-name counterparties (:counterparty-id op)))
                  ($ :td
                     (category-title (:category op))
                     (when (= :manual (:category-source op))
                       ($ :span {:class "badge badge-neutral"
                                 :style {:margin-left "6px"}
                                 :title "Категория присвоена вручную"}
                          "вручную")))
                  ($ :td (if (:confirmed op)
                           ($ :span {:class "badge badge-success"} "Проведена")
                           ($ :span {:class "badge badge-warning"} "План")))
                  ($ :td (if (:regular op) "Да" "—")))))))))

(def ^:private empty-ops-ui
  {:page 1 :page-size 20 :from "" :to "" :direction "" :account-id ""
   :counterparty-id "" :category "" :status "" :regular ""})

(defui ^:private registry-tab []
  (let [ops-data   (use-subscribe [::subs/treasury-operations])
        loading?   (use-subscribe [::subs/treasury-operations-loading?])
        accs-data  (use-subscribe [::subs/treasury-accounts])
        cps-data   (use-subscribe [::subs/treasury-counterparties])
        api-errors (use-subscribe [::subs/api-errors])
        error-msg  (api-error-for api-errors "/api/v1/treasury/operations")
        accounts       (or (:accounts accs-data) [])
        counterparties (or (:counterparties cps-data) [])
        [ui set-ui!]   (use-state empty-ops-ui)
        ;; any non-page change resets to page 1
        upd! (fn [k v] (set-ui! #(assoc % k v :page (if (= k :page) v 1))))
        [open-form set-open-form!] (use-state nil) ; nil | :op | :account | :cp
        load! (fn [] (rf/dispatch [::events/load-treasury-operations
                                   (build-ops-filters ui)]))]

    ;; reference data (mount once)
    (use-effect
     (fn []
       (rf/dispatch [::events/load-treasury-accounts])
       (rf/dispatch [::events/load-treasury-counterparties])
       (rf/dispatch [::events/load-treasury-auto-rules])
       js/undefined)
     [])

    ;; operations follow the filter state
    (use-effect
     (fn []
       (rf/dispatch [::events/load-treasury-operations (build-ops-filters ui)])
       js/undefined)
     [ui])

    (cond
      (and loading? (nil? ops-data))
      ($ :div {:class "page-content"} ($ skel-card {:rows 10}))

      (and error-msg (nil? ops-data))
      ($ :div {:class "page-content"}
         ($ error-banner {:title    "Не удалось загрузить реестр операций"
                          :message  error-msg
                          :on-retry load!}))

      :else
      ($ :div {:class "page-content"}
         (when error-msg
           ($ error-banner {:title    "Не удалось обновить реестр"
                            :message  error-msg
                            :on-retry load!}))

         ($ ops-summary {:summary (:summary ops-data)})

         ;; inline add-forms
         (when (= open-form :op)
           ($ op-form {:accounts       accounts
                       :counterparties counterparties
                       :on-done        #(set-open-form! nil)}))
         (when (= open-form :account)
           ($ account-form {:accounts      accounts
                            :total-balance (:total-balance accs-data)
                            :on-done       #(set-open-form! nil)}))
         (when (= open-form :cp)
           ($ counterparty-form {:counterparties counterparties
                                 :on-done        #(set-open-form! nil)}))

         ($ :section {:class "card section-card"}
            ($ :div {:class "section-head"}
               ($ :div
                  ($ :h3 {:class "section-title"} "Реестр операций")
                  ($ :div {:class "section-subtitle"}
                     "Все денежные движения: приходы, расходы и переводы между счетами"))
               ($ :div {:class "row" :style {:gap "8px"}}
                  ($ :button {:class    "btn btn-primary btn-sm"
                              :on-click #(set-open-form!
                                          (if (= open-form :op) nil :op))}
                     ($ icon {:name :plus :size 14})
                     "Добавить операцию")
                  ($ :button {:class    "btn btn-secondary btn-sm"
                              :on-click #(set-open-form!
                                          (if (= open-form :account) nil :account))}
                     "Счёт")
                  ($ :button {:class    "btn btn-secondary btn-sm"
                              :on-click #(set-open-form!
                                          (if (= open-form :cp) nil :cp))}
                     "Контрагент")))

            ;; filters
            ($ :div {:class "row"
                     :style {:gap "10px" :flex-wrap "wrap"
                             :align-items "flex-end" :margin-bottom "10px"}}
               ($ field {:label "С даты"}
                  ($ :input {:class "input" :type "date" :value (:from ui)
                             :on-change #(upd! :from (.. % -target -value))}))
               ($ field {:label "По дату"}
                  ($ :input {:class "input" :type "date" :value (:to ui)
                             :on-change #(upd! :to (.. % -target -value))}))
               ($ field {:label "Направление"}
                  ($ :select {:class "select" :value (:direction ui)
                              :on-change #(upd! :direction (.. % -target -value))}
                     ($ :option {:value ""}         "Все")
                     ($ :option {:value "income"}   "Приход")
                     ($ :option {:value "expense"}  "Расход")
                     ($ :option {:value "transfer"} "Перевод")))
               ($ field {:label "Счёт"}
                  ($ :select {:class "select" :value (:account-id ui)
                              :on-change #(upd! :account-id (.. % -target -value))}
                     ($ :option {:value ""} "Все")
                     (for [a accounts]
                       ($ :option {:key (:id a) :value (str (:id a))} (:name a)))))
               ($ field {:label "Контрагент"}
                  ($ :select {:class "select" :value (:counterparty-id ui)
                              :on-change #(upd! :counterparty-id (.. % -target -value))}
                     ($ :option {:value ""} "Все")
                     (for [c counterparties]
                       ($ :option {:key (:id c) :value (str (:id c))} (:name c)))))
               ($ field {:label "Категория"}
                  ($ :select {:class "select" :value (:category ui)
                              :on-change #(upd! :category (.. % -target -value))}
                     ($ :option {:value ""} "Все")
                     (for [[v t] category-options]
                       ($ :option {:key v :value v} t))))
               ($ field {:label "Статус"}
                  ($ :select {:class "select" :value (:status ui)
                              :on-change #(upd! :status (.. % -target -value))}
                     ($ :option {:value ""}          "Все")
                     ($ :option {:value "confirmed"} "Проведённые")
                     ($ :option {:value "planned"}   "Плановые")))
               ($ field {:label "Регулярность"}
                  ($ :select {:class "select" :value (:regular ui)
                              :on-change #(upd! :regular (.. % -target -value))}
                     ($ :option {:value ""}        "Все")
                     ($ :option {:value "regular"} "Регулярные")
                     ($ :option {:value "oneoff"}  "Разовые"))))

            ($ ops-table {:operations     (or (:operations ops-data) [])
                          :accounts       accounts
                          :counterparties counterparties})
            ($ pager {:page      (or (:page ops-data) (:page ui))
                      :total     (or (:total ops-data) 0)
                      :page-size (or (:page-size ops-data) (:page-size ui))
                      :on-page   #(upd! :page %)}))

         ($ auto-rules-section)))))

;; ---------------------------------------------------------------------------
;; Вкладка 3 — Обязательства (ДЗ/КЗ dashboard)
;; ---------------------------------------------------------------------------

(defui ^:private dynamics-chart [{:keys [points]}]
  (let [canvas-ref (use-ref nil)]
    (use-effect
     (fn []
       (when-let [canvas @canvas-ref]
         (let [base-font #js{:family "Inter" :size 11}
               cfg       (dynamics-chart-config points)
               chart     (Chart. canvas
                                 #js{:type "line"
                                     :data (clj->js cfg)
                                     :options
                                     #js{:responsive true
                                         :maintainAspectRatio false
                                         :plugins #js{:legend #js{:position "bottom"
                                                                  :align    "start"
                                                                  :labels   #js{:boxWidth 10 :boxHeight 10
                                                                                :padding 14
                                                                                :font base-font
                                                                                :color "#475569"}}
                                                      :tooltip #js{:backgroundColor "#0f172a"
                                                                   :titleColor      "#fff"
                                                                   :bodyColor       "#cbd5e1"
                                                                   :borderColor     "#334155"
                                                                   :borderWidth     1
                                                                   :cornerRadius    6
                                                                   :padding         10
                                                                   :titleFont       #js{:family "Inter" :size 11 :weight 600}
                                                                   :bodyFont        base-font}}
                                         :scales #js{:x #js{:grid  #js{:display false}
                                                            :ticks #js{:font base-font
                                                                       :color "#94a3b8"
                                                                       :maxTicksLimit 12}}
                                                     :y #js{:grid  #js{:color "#f1f5f9"
                                                                       :drawBorder false}
                                                            :ticks #js{:font base-font
                                                                       :color "#94a3b8"
                                                                       :callback (fn [v] (fmt/format-short v))}
                                                            :border #js{:display false}}}}})]
           (fn [] (.destroy chart)))))
     [points])
    ($ :canvas (assoc {} :ref canvas-ref))))

(defui ^:private obligations-summary-cards [{:keys [summary]}]
  (let [bucket (fn [b] (str (fmt/format-decimal-rub (:amount b))
                            " · " (fmt/format-int (or (:count b) 0)) " шт"))]
    ($ :div {:class "kpi-grid" :style {:margin-bottom "12px"}}
       ($ :div {:class "kpi"}
          ($ :div {:class "kpi-label"} "ДЗ — нам должны")
          ($ :div {:class "kpi-value mono"
                   :style {:color "var(--color-delta-positive)"}}
             (fmt/format-decimal-rub (:receivable summary))))
       ($ :div {:class "kpi"}
          ($ :div {:class "kpi-label"} "КЗ — мы должны")
          ($ :div {:class "kpi-value mono"
                   :style {:color "var(--color-delta-negative)"}}
             (fmt/format-decimal-rub (:payable summary))))
       ($ :div {:class "kpi"}
          ($ :div {:class "kpi-label"} "Баланс (ДЗ − КЗ)")
          ($ :div {:class "kpi-value mono"
                   :style {:color (when (negative-cell? (:balance summary))
                                    "var(--color-delta-negative)")}}
             (fmt/format-decimal-rub (:balance summary))))
       ($ :div {:class "kpi"}
          ($ :div {:class "kpi-label"} "Ближайшие 30 дней · ДЗ")
          ($ :div {:class "kpi-value mono" :style {:font-size "18px"}}
             (bucket (:next-30-receivable summary))))
       ($ :div {:class "kpi"}
          ($ :div {:class "kpi-label"} "Ближайшие 30 дней · КЗ")
          ($ :div {:class "kpi-value mono" :style {:font-size "18px"}}
             (bucket (:next-30-payable summary))))
       ($ :div {:class "kpi"}
          ($ :div {:class "kpi-label"} "Просрочено · ДЗ")
          ($ :div {:class "kpi-value mono"
                   :style {:font-size "18px"
                           :color (when (pos? (or (:count (:overdue-receivable summary)) 0))
                                    "var(--color-delta-negative)")}}
             (bucket (:overdue-receivable summary))))
       ($ :div {:class "kpi"}
          ($ :div {:class "kpi-label"} "Просрочено · КЗ")
          ($ :div {:class "kpi-value mono"
                   :style {:font-size "18px"
                           :color (when (pos? (or (:count (:overdue-payable summary)) 0))
                                    "var(--color-delta-negative)")}}
             (bucket (:overdue-payable summary)))))))

(defui ^:private obligation-form [{:keys [counterparties on-done]}]
  (let [[direction set-dir!]  (use-state "receivable")
        [amount    set-amt!]  (use-state "")
        [cp        set-cp!]   (use-state "")
        [issue     set-issue!] (use-state (iso-date (js/Date.)))
        [due       set-due!]  (use-state "")
        [err       set-err!]  (use-state nil)
        submit!
        (fn []
          (cond
            (not (valid-amount? amount)) (set-err! "Сумма — положительное число, максимум 2 знака после точки")
            (str/blank? cp)  (set-err! "Выберите контрагента")
            (str/blank? due) (set-err! "Укажите срок")
            :else
            (do (set-err! nil)
                (rf/dispatch [::events/add-treasury-obligation
                              {:direction       (keyword direction)
                               :amount          (str/trim amount)
                               :currency        "RUB"
                               :counterparty-id (js/parseInt cp 10)
                               :issue-date      issue
                               :due-date        due}])
                (on-done))))]
    ($ :div {:class "card section-card" :style {:margin-bottom "12px"}}
       ($ :div {:class "section-head"}
          ($ :h3 {:class "section-title"} "Новое обязательство"))
       ($ :div {:class "row" :style {:gap "12px" :flex-wrap "wrap" :align-items "flex-end"}}
          ($ field {:label "Направление"}
             ($ :select {:class "select" :value direction
                         :on-change #(set-dir! (.. % -target -value))}
                ($ :option {:value "receivable"} "ДЗ — нам должны")
                ($ :option {:value "payable"}    "КЗ — мы должны")))
          ($ field {:label "Сумма, ₽"}
             ($ :input {:class "input" :placeholder "0.00" :value amount
                        :style {:width "120px"}
                        :on-change #(set-amt! (.. % -target -value))}))
          ($ field {:label "Контрагент"}
             ($ :select {:class "select" :value cp
                         :on-change #(set-cp! (.. % -target -value))}
                ($ :option {:value ""} "—")
                (for [c counterparties]
                  ($ :option {:key (:id c) :value (str (:id c))} (:name c)))))
          ($ field {:label "Выдано"}
             ($ :input {:class "input" :type "date" :value issue
                        :on-change #(set-issue! (.. % -target -value))}))
          ($ field {:label "Срок"}
             ($ :input {:class "input" :type "date" :value due
                        :on-change #(set-due! (.. % -target -value))}))
          ($ :button {:class "btn btn-primary btn-sm" :on-click submit!} "Сохранить")
          ($ :button {:class "btn btn-ghost btn-sm" :on-click on-done} "Отмена"))
       (when err
         ($ :div {:class "badge badge-danger" :style {:margin-top "8px"}} err)))))

(defui ^:private obligations-list [{:keys [obligations]}]
  ($ :div {:class "tbl-wrap"}
     ($ :table {:class "tbl"}
        ($ :thead
           ($ :tr
              ($ :th "Контрагент")
              ($ :th "Направление")
              ($ :th {:class "num"} "Сумма")
              ($ :th {:class "num"} "Остаток")
              ($ :th "Выдано")
              ($ :th "Срок")
              ($ :th "Статус")
              ($ :th)))
        ($ :tbody
           (if (empty? obligations)
             ($ :tr
                ($ :td {:col-span 8
                        :style {:text-align "center" :padding "32px"
                                :color "var(--color-fg-muted)"}}
                   "Обязательств нет."))
             (for [o obligations]
               (let [{:keys [class label]} (obligation-badge (:status o))]
                 ($ :tr {:key (:id o)}
                    ($ :td (or (:counterparty-name o)
                               (str "Контрагент #" (:counterparty-id o))))
                    ($ :td (case (:direction o)
                             :receivable "ДЗ" :payable "КЗ" "—"))
                    ($ :td {:class "num mono"}
                       (fmt/format-decimal-rub (:amount o)))
                    ($ :td {:class "num mono" :style {:font-weight 600}}
                       (fmt/format-decimal-rub (:remaining-amount o)))
                    ($ :td {:class "mono"} (or (:issue-date o) "—"))
                    ($ :td {:class "mono"} (or (:due-date o) "—"))
                    ($ :td ($ :span {:class class} label))
                    ($ :td
                       (when-not (= :settled (:status o))
                         ($ :button {:class    "btn btn-secondary btn-sm"
                                     :title    "Погасить остаток целиком"
                                     :on-click #(rf/dispatch
                                                 [::events/settle-treasury-obligation
                                                  (:id o)
                                                  {:settle-amount (:remaining-amount o)}])}
                            "Погасить")))))))))))

(defui ^:private obligations-tab []
  (let [summary    (use-subscribe [::subs/treasury-obligations-summary])
        sum-load?  (use-subscribe [::subs/treasury-obligations-summary-loading?])
        dynamics   (use-subscribe [::subs/treasury-obligations-dynamics])
        obls-data  (use-subscribe [::subs/treasury-obligations])
        cps-data   (use-subscribe [::subs/treasury-counterparties])
        api-errors (use-subscribe [::subs/api-errors])
        error-msg  (api-error-for api-errors "/api/v1/treasury/obligations")
        [mode set-mode!] (use-state :actuals)
        [page set-page!] (use-state 1)
        [show-form? set-show-form!] (use-state false)
        points     (or (:points dynamics) [])
        load-all!  (fn []
                     (rf/dispatch [::events/load-treasury-obligations-summary mode])
                     (rf/dispatch [::events/load-treasury-obligations-dynamics mode])
                     (rf/dispatch [::events/load-treasury-obligations
                                   {:mode mode :page page :page-size 20}]))]

    ;; counterparties for the create-form select (mount once)
    (use-effect
     (fn [] (rf/dispatch [::events/load-treasury-counterparties]) js/undefined)
     [])

    ;; summary + dynamics follow the mode; list follows mode + page
    (use-effect
     (fn []
       (rf/dispatch [::events/load-treasury-obligations-summary mode])
       (rf/dispatch [::events/load-treasury-obligations-dynamics mode])
       js/undefined)
     [mode])
    (use-effect
     (fn []
       (rf/dispatch [::events/load-treasury-obligations
                     {:mode mode :page page :page-size 20}])
       js/undefined)
     [mode page])

    (cond
      (and sum-load? (nil? summary))
      ($ :div {:class "page-content"} ($ skel-card {:rows 8}))

      (and error-msg (nil? summary) (nil? obls-data))
      ($ :div {:class "page-content"}
         ($ error-banner {:title    "Не удалось загрузить обязательства"
                          :message  error-msg
                          :on-retry load-all!}))

      :else
      ($ :div {:class "page-content"}
         (when error-msg
           ($ error-banner {:title    "Не удалось обновить обязательства"
                            :message  error-msg
                            :on-retry load-all!}))

         ($ :div {:class "row"
                  :style {:justify-content "flex-end" :margin-bottom "12px"}}
            ($ chip-toggle {:options   mode-options
                            :value     mode
                            :on-change set-mode!}))

         (when summary
           ($ obligations-summary-cards {:summary summary}))

         ($ :section {:class "card section-card"}
            ($ :div {:class "section-head"}
               ($ :div
                  ($ :h3 {:class "section-title"} "Динамика ДЗ/КЗ — 12 месяцев")
                  ($ :div {:class "section-subtitle"}
                     "Дебиторская и кредиторская задолженность и их баланс по месяцам")))
            (when (and (seq points) (not (dynamics-12? points)))
              ($ :div {:class "alert alert-warning" :style {:margin-bottom "10px"}}
                 ($ icon {:name :warning :class "alert-icon"})
                 ($ :div {:class "alert-body"}
                    (str "Ожидалось ровно 12 точек динамики, получено "
                         (count points) " (FR-016)."))))
            (if (seq points)
              ($ :div {:style {:height "260px" :position "relative"}}
                 ($ dynamics-chart {:points points}))
              ($ :div {:style {:text-align "center" :padding "32px"
                               :color "var(--color-fg-muted)"}}
                 "Нет данных динамики.")))

         (when show-form?
           ($ obligation-form {:counterparties (or (:counterparties cps-data) [])
                               :on-done        #(set-show-form! false)}))

         ($ :section {:class "card section-card"}
            ($ :div {:class "section-head"}
               ($ :div
                  ($ :h3 {:class "section-title"} "Обязательства")
                  ($ :div {:class "section-subtitle"}
                     "Каждое обязательство: контрагент, сумма, срок и статус"))
               ($ :button {:class    "btn btn-primary btn-sm"
                           :on-click #(set-show-form! (not show-form?))}
                  ($ icon {:name :plus :size 14})
                  "Добавить"))
            ($ obligations-list {:obligations (or (:obligations obls-data) [])})
            ($ pager {:page      (or (:page obls-data) page)
                      :total     (or (:total obls-data) 0)
                      :page-size (or (:page-size obls-data) 20)
                      :on-page   set-page!}))))))

;; ---------------------------------------------------------------------------
;; Page root — tab strip + per-tab content
;; ---------------------------------------------------------------------------

(defui treasury
  [{:keys [tab]}]
  (let [active-sub (use-subscribe [::subs/active-tab])
        active     (or tab active-sub :cashflow)]
    ($ :<> {}
       ($ tabs {:items     (nav/section-tabs :treasury)
                :active    active
                :on-change (fn [t] (router/nav! [:treasury t]))})
       (case active
         :cashflow    ($ cashflow-tab)
         :registry    ($ registry-tab)
         :obligations ($ obligations-tab)
         ($ cashflow-tab)))))
