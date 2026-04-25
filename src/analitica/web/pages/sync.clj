(ns analitica.web.pages.sync
  (:require [hiccup.core :refer [html]]
            [analitica.web.components :as c]
            [analitica.web.api.metrics :as metrics-api]
            [analitica.sync.scheduler :as scheduler]))

;; ---------------------------------------------------------------------------
;; Auto-refresh section (Phase 9)
;; ---------------------------------------------------------------------------

(defn- auto-refresh-section
  "Render the daily auto-refresh scheduler toggle and settings form."
  []
  (let [sched   (try (scheduler/get-schedule) (catch Exception _ nil))
        enabled (= 1 (:enabled sched 0))
        hour    (or (:hour sched) 6)
        minute  (or (:minute sched) 0)
        what    (or (:what sched) "all")
        mp      (or (:marketplace sched) "all")
        period  (or (:period sched) "last-7-days")]
    [:div.bg-white.rounded-lg.shadow.p-6.mb-6
     [:h3.text-lg.font-semibold.text-gray-900.mb-4 "Авто-обновление"]
     [:p.text-sm.text-gray-600.mb-4
      "Ежедневно запускает синхронизацию в указанное время. "
      "Пропускает запуск, если уже выполняется ручная синхронизация."]

     ;; Settings form — POSTs JSON to /api/sync/schedule
     [:form#schedule-form.space-y-4
      {:hx-post     "/api/sync/schedule"
       :hx-swap     "none"
       :hx-encoding "application/json"
       "hx-on:htmx:afterRequest"
       (str "if(event.detail.xhr.status===200){"
            "document.getElementById('schedule-saved-msg').style.display='inline';"
            "setTimeout(function(){document.getElementById('schedule-saved-msg').style.display='none';},3000);"
            "}else{alert('Ошибка: '+event.detail.xhr.responseText);}")}

      ;; Enabled toggle
      [:div.flex.items-center.gap-3
       [:input#schedule-enabled
        {:type    "checkbox"
         :name    "enabled"
         :value   "true"
         :checked (when enabled "checked")
         :class   "h-4 w-4 text-blue-600 border-gray-300 rounded"}]
       [:label.text-sm.font-medium.text-gray-700 {:for "schedule-enabled"} "Включено"]]

      ;; Time row
      [:div.flex.flex-wrap.items-center.gap-4
       [:div.flex.items-center.gap-2
        [:label.text-sm.text-gray-700 {:for "schedule-hour"} "Час (0–23):"]
        [:input#schedule-hour.border.border-gray-300.rounded-md.px-2.py-1.text-sm.w-16
         {:type "number" :name "hour" :min "0" :max "23" :value hour}]]
       [:div.flex.items-center.gap-2
        [:label.text-sm.text-gray-700 {:for "schedule-minute"} "Минута (0–59):"]
        [:input#schedule-minute.border.border-gray-300.rounded-md.px-2.py-1.text-sm.w-16
         {:type "number" :name "minute" :min "0" :max "59" :value minute}]]]

      ;; Period / marketplace / what row
      [:div.flex.flex-wrap.items-center.gap-4
       [:div.flex.items-center.gap-2
        [:label.text-sm.text-gray-700 {:for "schedule-period"} "Период:"]
        [:select#schedule-period.border.border-gray-300.rounded-md.px-2.py-1.text-sm
         {:name "period"}
         (for [[v label] [["last-7-days"  "Последние 7 дней"]
                          ["last-30-days" "Последние 30 дней"]
                          ["last-week"    "Прошлая неделя"]
                          ["this-month"   "Текущий месяц"]]]
           [:option (merge {:value v} (when (= v period) {:selected "selected"})) label])]]
       [:div.flex.items-center.gap-2
        [:label.text-sm.text-gray-700 {:for "schedule-mp"} "Маркетплейс:"]
        [:select#schedule-mp.border.border-gray-300.rounded-md.px-2.py-1.text-sm
         {:name "marketplace"}
         (for [[v label] [["all"  "Все"] ["wb" "Wildberries"] ["ozon" "Ozon"] ["ym" "Яндекс Маркет"]]]
           [:option (merge {:value v} (when (= v mp) {:selected "selected"})) label])]]
       [:div.flex.items-center.gap-2
        [:label.text-sm.text-gray-700 {:for "schedule-what"} "Тип данных:"]
        [:select#schedule-what.border.border-gray-300.rounded-md.px-2.py-1.text-sm
         {:name "what"}
         (for [[v label] [["all"     "Все"]
                          ["sales"   "Продажи"]
                          ["orders"  "Заказы"]
                          ["finance" "Финансы"]
                          ["stocks"  "Остатки"]]]
           [:option (merge {:value v} (when (= v what) {:selected "selected"})) label])]]]

      ;; Submit
      [:div.flex.items-center.gap-3
       [:button.px-4.py-2.bg-blue-600.text-white.rounded-lg.hover:bg-blue-700.text-sm.font-medium
        {:type "submit"} "Сохранить"]
       [:span#schedule-saved-msg.text-sm.text-green-600
        {:style "display:none"} "Сохранено"]]]

     ;; Status info
     [:div.mt-4.pt-4.border-t.border-gray-200.text-sm.text-gray-600.space-y-1
      [:div "Последний авто-запуск: "
       [:span.font-mono (or (:last-run-at sched) "—")]
       (when (:last-run-id sched)
         [:span.text-gray-400 (str "  (run-id: " (:last-run-id sched) ")")])]
      [:div "Следующий запуск: "
       [:span.font-mono (or (:next-run-at sched) (if enabled "вычисляется…" "—"))]]]

     ;; JS: serialize form as JSON on submit (HTMX sends form-data by default;
     ;; override with hx-vals to send JSON that the server can parse via slurp).
     [:script "
       (function() {
         var form = document.getElementById('schedule-form');
         if (!form) return;
         form.addEventListener('htmx:configRequest', function(evt) {
           var params = evt.detail.parameters;
           var enabled = document.getElementById('schedule-enabled').checked;
           var body = {
             enabled: enabled,
             hour: parseInt(document.getElementById('schedule-hour').value, 10),
             minute: parseInt(document.getElementById('schedule-minute').value, 10),
             period: document.getElementById('schedule-period').value,
             marketplace: document.getElementById('schedule-mp').value,
             what: document.getElementById('schedule-what').value
           };
           evt.detail.parameters = body;
           evt.detail.headers['Content-Type'] = 'application/json';
         });
       })();
     "]]))

;; ---------------------------------------------------------------------------
;; Sync Buttons
;; ---------------------------------------------------------------------------

(defn- sync-controls
  "Hero 'Обновить данные за <period>' button as the default flow:
   one click → ingest+materialize across all 3 MPs sequentially. Power
   users get the per-MP / per-type / re-materialize controls inside an
   <details> 'Расширенный режим' block — closed by default so the seller
   isn't faced with 16 buttons just to refresh their numbers.

   Period is the global header picker (URL ?from&to), single source of
   truth for the whole UI."
  []
  [:div.bg-white.rounded-lg.shadow.p-6.mb-6
   [:h3.text-lg.font-semibold.text-gray-900.mb-2 "Обновить данные"]
   [:p.text-sm.text-gray-600.mb-4
    "Один клик — данные за выбранный период скачиваются по всем маркетплейсам и пересчитываются. "
    "Период: " [:span#sync-hero-period.font-mono.text-gray-900 "—"]]

   ;; Hero button + Stop side-by-side. js:{what:'all', marketplace:'all'}
   ;; tells the server to fan out across [:wb :ozon :ym].
   [:div.flex.flex-wrap.items-center.gap-3.mb-2
    [:button#sync-hero-btn.px-6.py-3.bg-blue-600.text-white.rounded-lg.hover:bg-blue-700.transition-colors.text-base.font-semibold.shadow
     {:hx-post "/api/sync/run"
      :hx-vals "js:{what:'all',marketplace:'all',period:window.__resolveSyncPeriod()}"
      :hx-swap "none"
      "hx-on:htmx:responseError"
      "if(event.detail.xhr.status===409){alert('Синхронизация уже запущена. Дождитесь завершения.');}"
      "hx-on:htmx:afterRequest"
      (str "try{"
           "var d=JSON.parse(event.detail.xhr.responseText);"
           "if(d&&d['run-id']){"
           "window.__activeRunId=d['run-id'];"
           "window.dispatchEvent(new CustomEvent('analitica:run-started',{detail:{runId:d['run-id']}}));"
           "}"
           "}catch(e){console.error('run-started dispatch failed',e)}")}
     "↻ Обновить данные"]
    [:button.px-4.py-3.bg-red-600.text-white.rounded-lg.hover:bg-red-700.transition-colors.text-sm.font-medium
     {:hx-post "/api/sync/stop"
      :hx-swap "none"
      "hx-on:htmx:responseError"
      "if(event.detail.xhr.status===409){alert('Сейчас нет активной синхронизации.');}"}
     "⛔ Остановить"]
    [:span.text-xs.text-gray-500
     "Прогресс — ниже в логе, обычно 5–15 мин на полный refresh"]]

   ;; Power-user controls: per-MP, per-type ingest + rematerialize.
   ;; Closed by default — only opens if user explicitly needs granularity.
   [:details.mt-6.border-t.border-gray-200.pt-4
    [:summary.cursor-pointer.text-sm.font-medium.text-gray-700.hover:text-blue-600
     "Расширенный режим — отдельные маркетплейсы и типы данных"]
    [:div.mt-4
     [:div.flex.flex-wrap.items-center.gap-4.mb-3
      [:div.flex.items-center.gap-2
       [:label.text-sm.font-medium.text-gray-700 "Маркетплейс:"]
       [:select#sync-marketplace.border.border-gray-300.rounded-md.px-3.py-1.5.text-sm.focus:outline-none.focus:ring-2.focus:ring-blue-500
        [:option {:value "wb"}   "Wildberries"]
        [:option {:value "ozon"} "Ozon"]
        [:option {:value "ym"}   "Яндекс Маркет"]]]]

     [:p.text-xs.text-gray-500.mb-1 "Скачать с MP + пересчитать (для выбранного маркетплейса):"]
     [:div.flex.flex-wrap.mb-3
      (for [[label what] [["Sync All"  "all"]
                          ["Sales"     "sales"]
                          ["Orders"    "orders"]
                          ["Finance"   "finance"]
                          ["Storage"   "storage"]
                          ["Stocks"    "stocks"]
                          ["Stats"     "stats"]
                          ["Prices"    "prices"]
                          ["Regions"   "regions"]
                          ["1C"        "1c"]]]
        [:button.px-3.py-1.5.bg-blue-600.text-white.rounded.hover:bg-blue-700.transition-colors.text-xs.font-medium.mr-2.mb-2
         {:hx-post    "/api/sync/start"
          :hx-include "#sync-marketplace"
          :hx-vals    (str "js:{what:'" what "',period:window.__resolveSyncPeriod()}")
          :hx-swap    "none"
          "hx-on:htmx:responseError"
          "if(event.detail.xhr.status===409){alert('Синхронизация уже запущена.');}"
          "hx-on:htmx:afterRequest"
          (str "try{var d=JSON.parse(event.detail.xhr.responseText);"
               "if(d&&d['run-id']){"
               "window.__activeRunId=d['run-id'];"
               "window.dispatchEvent(new CustomEvent('analitica:run-started',{detail:{runId:d['run-id']}}));"
               "}}catch(e){console.error('run-started dispatch failed',e)}")}
         label])]

     [:p.text-xs.text-gray-500.mb-1 "Только пересчитать отчёты на уже скачанных данных (без обращения в MP):"]
     [:div.flex.flex-wrap
      (for [[label what] [["Пересчитать всё"  "all"]
                          ["Пересч. Sales"    "sales"]
                          ["Пересч. Orders"   "orders"]
                          ["Пересч. Finance"  "finance"]
                          ["Пересч. Stocks"   "stocks"]
                          ["Пересч. Prices"   "prices"]]]
        [:button.px-3.py-1.5.bg-amber-600.text-white.rounded.hover:bg-amber-700.transition-colors.text-xs.mr-2.mb-2
         {:hx-post    "/api/sync/rematerialize"
          :hx-include "#sync-marketplace"
          :hx-vals    (str "js:{what:'" what "',period:window.__resolveSyncPeriod()}")
          :hx-swap    "none"
          "hx-on:htmx:responseError"
          "if(event.detail.xhr.status===409){alert('Уже что-то выполняется. Дождитесь завершения.');}"}
         label])]]]

   ;; Single source of truth for the sync period: URL → localStorage → default.
   [:script "
     window.__resolveSyncPeriod = function() {
       var q = new URLSearchParams(location.search);
       if (q.get('from') && q.get('to')) return q.get('from') + ',' + q.get('to');
       try {
         var s = JSON.parse(localStorage.getItem('analitica/period') || 'null');
         if (s && s.from && s.to) return s.from + ',' + s.to;
       } catch (_) {}
       return 'last-30-days';
     };
     (function() {
       var el = document.getElementById('sync-hero-period');
       if (!el) return;
       var p = window.__resolveSyncPeriod();
       el.textContent = p === 'last-30-days' ? 'последние 30 дней (по умолчанию)' : p.replace(',', ' — ');
     })();
   "]])

;; ---------------------------------------------------------------------------
;; Last Sync Status Table
;; ---------------------------------------------------------------------------

(defn- last-sync-status
  "Render table showing last sync status (MAX(synced_at)) per data type per MP.

   Reads directly from analytical tables — no separate sync_history needed.
   Each row shows the freshest synced_at across all rows for that MP/type."
  []
  (let [mps    [[:wb "WB"] [:ozon "Ozon"] [:ym "YM"]]
        ;; (table, type-label) — only tables that carry a `synced_at` column.
        types  [["sales"   "Продажи"]
                ["finance" "Финансы"]
                ["orders"  "Заказы"]
                ["stocks"  "Остатки"]
                ["prices"  "Цены"]]
        rows   (for [[mp mp-label] mps
                     [tbl tbl-label] types
                     :let [last (try
                                  (-> ((requiring-resolve 'analitica.db/query)
                                       [(str "SELECT MAX(synced_at) AS ts FROM " tbl
                                             " WHERE marketplace = ?")
                                        (name mp)])
                                      first :ts)
                                  (catch Exception _ nil))]]
                 [mp-label tbl-label last])]
    [:div.bg-white.rounded-lg.shadow.p-6.mb-6
     [:h3.text-lg.font-semibold.text-gray-900.mb-4 "Статус последней синхронизации"]
     [:p.text-xs.text-gray-500.mb-3 "По метке " [:code "synced_at"] " в analytical tables"]
     [:table.min-w-full.divide-y.divide-gray-200
      [:thead.bg-gray-50
       [:tr
        [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Маркетплейс"]
        [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Тип"]
        [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Последняя синхронизация"]
        [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Статус"]]]
      [:tbody.bg-white.divide-y.divide-gray-200
       (for [[mp-label tbl-label ts] rows]
         [:tr
          [:td.px-6.py-2.whitespace-nowrap.text-sm.font-medium.text-gray-900 mp-label]
          [:td.px-6.py-2.whitespace-nowrap.text-sm.text-gray-700 tbl-label]
          [:td.px-6.py-2.whitespace-nowrap.text-sm.text-gray-500.font-mono (or ts "—")]
          [:td.px-6.py-2.whitespace-nowrap
           (if ts
             [:span.px-2.inline-flex.text-xs.leading-5.font-semibold.rounded-full.bg-green-100.text-green-800 "OK"]
             [:span.px-2.inline-flex.text-xs.leading-5.font-semibold.rounded-full.bg-gray-100.text-gray-500 "Нет данных"])]])]]]))

;; ---------------------------------------------------------------------------
;; Data Coverage Heatmap
;; ---------------------------------------------------------------------------

(defn render-coverage-bars
  "Render coverage bars for all data types and marketplaces."
  [coverage-data]
  (when coverage-data
    [:div
     ;; WB Coverage
     (when-let [wb-data (:wb coverage-data)]
       [:div.mb-6
        [:h4.text-md.font-semibold.text-gray-800.mb-3 "Wildberries"]
        (when-let [sales (:sales wb-data)]
          (c/data-coverage-bar {:label "Продажи"
                                :filled-days (:days sales)
                                :total-days (:total-days sales)
                                :date-from (:from sales)
                                :date-to (:to sales)}))
        (when-let [orders (:orders wb-data)]
          (c/data-coverage-bar {:label "Заказы"
                                :filled-days (:days orders)
                                :total-days (:total-days orders)
                                :date-from (:from orders)
                                :date-to (:to orders)}))
        (when-let [finance (:finance wb-data)]
          (c/data-coverage-bar {:label "Финансы"
                                :filled-days (:days finance)
                                :total-days (:total-days finance)
                                :date-from (:from finance)
                                :date-to (:to finance)}))
        (when-let [storage (:storage wb-data)]
          (c/data-coverage-bar {:label "Хранение"
                                :filled-days (:days storage)
                                :total-days (:total-days storage)
                                :date-from (:from storage)
                                :date-to (:to storage)}))
        (when-let [stocks (:stocks wb-data)]
          (c/data-coverage-bar {:label "Остатки"
                                :filled-days (:days stocks)
                                :total-days (:total-days stocks)
                                :date-from (:from stocks)
                                :date-to (:to stocks)}))])
     
     ;; Ozon Coverage
     (when-let [ozon-data (:ozon coverage-data)]
       [:div.mb-6
        [:h4.text-md.font-semibold.text-gray-800.mb-3 "Ozon"]
        (when-let [sales (:sales ozon-data)]
          (c/data-coverage-bar {:label "Продажи"
                                :filled-days (:days sales)
                                :total-days (:total-days sales)
                                :date-from (:from sales)
                                :date-to (:to sales)}))
        (when-let [orders (:orders ozon-data)]
          (c/data-coverage-bar {:label "Заказы"
                                :filled-days (:days orders)
                                :total-days (:total-days orders)
                                :date-from (:from orders)
                                :date-to (:to orders)}))
        (when-let [finance (:finance ozon-data)]
          (c/data-coverage-bar {:label "Финансы"
                                :filled-days (:days finance)
                                :total-days (:total-days finance)
                                :date-from (:from finance)
                                :date-to (:to finance)}))
        (when-let [storage (:storage ozon-data)]
          (c/data-coverage-bar {:label "Хранение"
                                :filled-days (:days storage)
                                :total-days (:total-days storage)
                                :date-from (:from storage)
                                :date-to (:to storage)}))
        (when-let [stocks (:stocks ozon-data)]
          (c/data-coverage-bar {:label "Остатки"
                                :filled-days (:days stocks)
                                :total-days (:total-days stocks)
                                :date-from (:from stocks)
                                :date-to (:to stocks)}))])
     
     ;; YM Coverage
     (when-let [ym-data (:ym coverage-data)]
       [:div.mb-6
        [:h4.text-md.font-semibold.text-gray-800.mb-3 "Yandex Market"]
        (when-let [sales (:sales ym-data)]
          (c/data-coverage-bar {:label "Продажи"
                                :filled-days (:days sales)
                                :total-days (:total-days sales)
                                :date-from (:from sales)
                                :date-to (:to sales)}))
        (when-let [orders (:orders ym-data)]
          (c/data-coverage-bar {:label "Заказы"
                                :filled-days (:days orders)
                                :total-days (:total-days orders)
                                :date-from (:from orders)
                                :date-to (:to orders)}))
        (when-let [finance (:finance ym-data)]
          (c/data-coverage-bar {:label "Финансы"
                                :filled-days (:days finance)
                                :total-days (:total-days finance)
                                :date-from (:from finance)
                                :date-to (:to finance)}))
        (when-let [storage (:storage ym-data)]
          (c/data-coverage-bar {:label "Хранение"
                                :filled-days (:days storage)
                                :total-days (:total-days storage)
                                :date-from (:from storage)
                                :date-to (:to storage)}))
        (when-let [stocks (:stocks ym-data)]
          (c/data-coverage-bar {:label "Остатки"
                                :filled-days (:days stocks)
                                :total-days (:total-days stocks)
                                :date-from (:from stocks)
                                :date-to (:to stocks)}))])
     
     ;; Non-marketplace data
     [:div.mb-6
      [:h4.text-md.font-semibold.text-gray-800.mb-3 "Общие данные"]
      (when-let [stats (:stats coverage-data)]
        (c/data-coverage-bar {:label "Статистика товаров"
                              :filled-days (:days stats)
                              :total-days (:total-days stats)
                              :date-from (:from stats)
                              :date-to (:to stats)}))
      (when-let [regions (:regions coverage-data)]
        (c/data-coverage-bar {:label "Продажи по регионам"
                              :filled-days (:days regions)
                              :total-days (:total-days regions)
                              :date-from (:from regions)
                              :date-to (:to regions)}))
      (when-let [cost-prices (:1c coverage-data)]
        (c/data-coverage-bar {:label "Себестоимость (1C)"
                              :filled-days (:days cost-prices)
                              :total-days (:total-days cost-prices)
                              :date-from (:from cost-prices)
                              :date-to (:to cost-prices)}))
      (when-let [prices (:prices coverage-data)]
        (c/data-coverage-bar {:label "Цены"
                              :filled-days (:days prices)
                              :total-days (:total-days prices)
                              :date-from (:from prices)
                              :date-to (:to prices)}))]]))

(defn- data-coverage-section
  "Render data coverage heatmap section."
  []
  [:div.bg-white.rounded-lg.shadow.p-6.mb-6
   [:h3.text-lg.font-semibold.text-gray-900.mb-4 "Покрытие данных"]
   [:div {:hx-get "/api/sync/coverage"
          :hx-trigger "load"
          :hx-swap "innerHTML"}
    [:div.text-sm.text-gray-500 "Загрузка данных покрытия..."]]])

;; ---------------------------------------------------------------------------
;; Main Sync Page
;; ---------------------------------------------------------------------------

(defn sync-page
  "Render the sync management page with buttons, task matrix, progress log, coverage, and status table.

  Requirements: 6.1, 6.4, 6.5"
  []
  [:div
   ;; Daily auto-refresh scheduler toggle (Phase 9)
   (auto-refresh-section)

   ;; Sync control buttons
   (sync-controls)

   ;; Task matrix — primary progress view (Phase 4)
   (c/task-matrix {})

   ;; Stream log — collapsible debug panel (demoted in Phase 4)
   [:details.mt-6
    [:summary.cursor-pointer.text-sm.font-medium.text-gray-600.hover:text-blue-600.mb-2
     "Подробный лог (потоковый вывод)"]
    (c/sync-log {:id "sync-log"
                 :stream-url "/api/sync/stream"
                 :height "300px"})]

   ;; Data coverage heatmap (per-day × per-MP × per-type)
   (c/sync-heatmap {:id "sync-coverage-heatmap"
                    :api-url "/api/sync/coverage-days"})

   ;; 1C CSV cost-prices upload
   [:div.mt-6
    (c/cost-prices-upload)]

   ;; Last sync status table
   (last-sync-status)])

