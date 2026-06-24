(ns marker.pages.sync
  "Sync UI — Phase 9.4 MVP + Phase 10 per-task retry.
   Replaces the legacy /sync Hiccup page with a re-frame-free, hooks-only
   page that uses fetch + EventSource against the existing JSON sync API.

   Capabilities:
     - Start full sync (what=all, period=last-7-days, marketplace=all)
     - Live SSE feed of progress messages
     - Stop button (POST /api/sync/stop)
     - Recent runs table (GET /api/sync/runs/recent)
     - Expandable run rows with per-task retry (Phase 10)"
  (:require [uix.core :refer [$ defui use-state use-effect use-ref use-memo]]
            [clojure.string :as str]
            [marker.api :as api]
            [marker.ui.icons :refer [icon]]
            [marker.ui.chrome :refer [mp-badge]]
            [marker.pages.sync.schedule :as schedule]))

;; ---------------------------------------------------------------------------
;; HTTP helpers (plain fetch + JSON; sync API is JSON-only)
;; ---------------------------------------------------------------------------

(defn- post-json! [url body on-ok on-err]
  (-> (js/fetch url
                #js {:method  "POST"
                     :headers #js {"Content-Type" "application/json"
                                   "Accept"       "application/json"
                                   "X-API-Key"    (api/api-key)}
                     :body    (js/JSON.stringify (clj->js (or body {})))})
      (.then (fn [r]
               (-> (.text r)
                   (.then (fn [t]
                            (let [body (try (js/JSON.parse t) (catch :default _ t))]
                              (if (.-ok r)
                                (on-ok (js->clj body :keywordize-keys true))
                                (on-err (or (some-> body .-error)
                                            (str "HTTP " (.-status r)))))))))))
      (.catch (fn [e] (on-err (.-message e))))))

(defn- fetch-json! [url on-ok on-err]
  (-> (js/fetch url #js {:headers #js {"Accept" "application/json"}})
      (.then (fn [r]
               (-> (.text r)
                   (.then (fn [t]
                            (let [body   (try (js/JSON.parse t) (catch :default _ nil))
                                  parsed (when body (js->clj body :keywordize-keys true))]
                              (if (.-ok r)
                                (on-ok parsed)
                                (when on-err (on-err {:status (.-status r) :body parsed})))))))))
      (.catch (fn [e] (when on-err (on-err {:status 0 :body nil :message (.-message e)}))))))

;; ---------------------------------------------------------------------------
;; Date formatting
;; ---------------------------------------------------------------------------

(defn- pad2 [n]
  (let [s (str n)]
    (if (< (count s) 2) (str "0" s) s)))

(defn- format-iso
  "Pretty-print an ISO datetime string as DD.MM HH:MM."
  [s]
  (try
    (let [d (js/Date. s)]
      (str (pad2 (.getDate d)) "."
           (pad2 (inc (.getMonth d))) " "
           (pad2 (.getHours d)) ":"
           (pad2 (.getMinutes d))))
    (catch :default _ (or s "—"))))

(defn- duration-s
  "Compute duration in seconds between two ISO timestamps (strings)."
  [from to]
  (try
    (when (and from to)
      (let [a (.getTime (js/Date. from))
            b (.getTime (js/Date. to))]
        (Math/round (/ (- b a) 1000))))
    (catch :default _ nil)))

(defn- status-tag-class [status]
  (case (some-> status name)
    "completed" "tag-good"
    "succeeded" "tag-good"
    "ok"        "tag-good"
    "failed"    "tag-bad"
    "error"     "tag-bad"
    "running"   "tag-info"
    "pending"   "tag-neutral"
    "tag-neutral"))

;; ---------------------------------------------------------------------------
;; Task helpers (pure — also used in tests)
;; ---------------------------------------------------------------------------

(defn task-display-id
  "Return the last two slash-separated segments of a task-id string.
   e.g. 'abc-123/wb/sales/extract' → 'sales/extract'."
  [id]
  (when id
    (let [parts (str/split id #"/")]
      (if (>= (count parts) 2)
        (str/join "/" (take-last 2 parts))
        id))))

(defn task-row-data
  "Pick the display fields from a raw task map.
   Returns {:display-id :marketplace :entity-type :phase :status :items}."
  [task]
  {:display-id  (task-display-id (:id task))
   :marketplace (some-> task :marketplace keyword)
   :entity-type (:entity-type task)
   :phase       (:phase task)
   :status      (some-> task :status name)
   :items       (:items task)})

;; ---------------------------------------------------------------------------
;; Coverage helpers (pure — also tested)
;; ---------------------------------------------------------------------------

(defn freshness-class
  "Given an ISO date string `to-iso` and a js/Date `now`, return a CSS
   modifier class reflecting how stale the data is:
     \"good\"    → to-iso is within the last 2 days
     \"stale\"   → 3-7 days old
     \"old\"     → more than 7 days old
     \"missing\" → to-iso is nil or not a valid date string"
  [to-iso now]
  (if (or (nil? to-iso) (= "" to-iso))
    "missing"
    (try
      (let [to-ms  (.getTime (js/Date. to-iso))
            now-ms (.getTime now)
            days   (/ (- now-ms to-ms) 86400000)]
        (cond
          (< days 3)  "good"
          (<= days 7) "stale"
          :else        "old"))
      (catch :default _ "missing"))))

(defn parse-coverage-cell
  "Normalise a coverage-report cell (P0-A Part B). Returns a map carrying the
   honest per-calendar-day shape, or nil when there is no data.

     {:kind \"snapshot\"|\"event-stream\"|\"monthly-batch\"
      :status \"full\"|\"partial\"
      :present int :expected int          ; event/batch
      :span {:from :to}                   ; event/batch
      :holes [[from to] …]                ; event/batch
      :as-of string}                      ; snapshot

   Tolerates keyword or string :kind/:status (JSON vs Transit). A :missing
   status (or absent cell) → nil → rendered as \"—\"."
  [cell]
  (when (map? cell)
    (let [kind   (some-> (:kind cell) name)
          status (some-> (:status cell) name)]
      (when (and kind (not= status "missing"))
        {:kind     kind
         :status   status
         :present  (:present cell)
         :expected (:expected cell)
         :as-of    (:as-of cell)
         :span     (:span cell)
         :holes    (or (:holes cell) [])}))))

;; ---------------------------------------------------------------------------
;; Status banner
;; ---------------------------------------------------------------------------

(defui ^:private status-banner [{:keys [running? last-event last-run-status]}]
  (let [kind (cond
               running?                              :running
               (= last-run-status "completed")       :ok
               (= last-run-status "succeeded")       :ok
               (or (= last-run-status "failed")
                   (= last-run-status "error"))      :err
               :else                                 :idle)
        cls  (case kind
               :running "alert alert-info"
               :ok      "alert alert-success"
               :err     "alert alert-danger"
               "alert")
        icn  (case kind
               :running :refresh
               :ok      :check
               :err     :danger
               :info)
        ttl  (case kind
               :running "Идёт синхронизация…"
               :ok      "Последний запуск завершился успешно"
               :err     "Последний запуск завершился с ошибкой"
               "Синхронизация не запущена")]
    ($ :div {:class cls :style {:margin-bottom "12px"}}
       ($ icon {:name icn :class "alert-icon"
                :style (when (= kind :running)
                         {:animation "spin 1.5s linear infinite"})})
       ($ :div {:class "alert-body"}
          ($ :div {:class "alert-title"} ttl)
          (when last-event
            ($ :div {:style {:font-family "var(--font-mono)"
                             :font-size "12px"}}
               last-event))))))

;; ---------------------------------------------------------------------------
;; Live log
;; ---------------------------------------------------------------------------

(defui ^:private live-log [{:keys [events]}]
  ($ :section {:class "card section-card"}
     ($ :div {:class "section-head"}
        ($ :h3 {:class "section-title"} "Live-лог"))
     (if (zero? (count events))
       ($ :div {:style {:padding "16px"
                        :color "var(--color-fg-muted)"
                        :font-size "13px"}}
          "Нет событий. Запустите синхронизацию, чтобы увидеть прогресс.")
       ($ :div {:style {:max-height "320px"
                        :overflow-y "auto"
                        :background "var(--color-bg-subtle)"
                        :border-radius "var(--radius-md)"
                        :padding "10px 12px"
                        :font-family "var(--font-mono)"
                        :font-size "12px"
                        :line-height "1.6"}}
          (for [[idx ev] (map-indexed vector events)]
            ($ :div {:key idx
                     :style {:color (case (:type ev)
                                      :error "var(--color-danger)"
                                      :done  "var(--color-success)"
                                      "var(--color-fg-secondary)")}}
               ($ :span {:style {:color "var(--color-fg-muted)"}}
                  (pad2 (mod idx 1000)) "  ")
               (:text ev)))))))

;; ---------------------------------------------------------------------------
;; Recent runs table — expandable rows with per-task retry
;; ---------------------------------------------------------------------------

(defui ^:private task-rows
  "Expanded task sub-table for one run. Handles per-task retry."
  [{:keys [tasks load-runs!]}]
  (let [[retry-loading set-retry-loading!] (use-state {})
        ;; Per-task error map: {task-id error-string}. Multiple concurrent
        ;; retries each maintain their own error without clobbering siblings.
        [retry-err     set-retry-err!]     (use-state {})
        ;; mounted-ref prevents setState after row collapse (stale-closure guard).
        mounted-ref (use-ref true)
        retry!
        (fn [task-id]
          (set-retry-err! (fn [m] (dissoc m task-id)))
          (set-retry-loading! (fn [m] (assoc m task-id true)))
          (post-json! (str "/api/sync/tasks/" task-id "/retry")
                      nil
                      (fn [_]
                        (when @mounted-ref
                          (set-retry-loading! (fn [m] (dissoc m task-id)))
                          (load-runs!)))
                      (fn [msg]
                        (when @mounted-ref
                          (set-retry-loading! (fn [m] (dissoc m task-id)))
                          (set-retry-err! (fn [m] (assoc m task-id msg)))))))]
    (use-effect
     (fn [] (fn [] (reset! mounted-ref false)))
     [])
    ;; :col-span is correct UIx kebab-case; uix.compiler.attributes/camel-case-dom
    ;; converts it to colSpan at compile time (same as :on-click → onClick).
    ($ :tr {:class "run-tasks-row"}
       ($ :td {:col-span 6 :style {:padding "0 0 8px 32px"}}
          ($ :table {:class "tbl" :style {:font-size "12px" :margin-top "4px"}}
             ($ :thead
                ($ :tr
                   ($ :th "Задача")
                   ($ :th "МП")
                   ($ :th "Тип")
                   ($ :th "Фаза")
                   ($ :th {:class "num"} "Записей")
                   ($ :th "Статус")
                   ($ :th "")))
             ($ :tbody
                (for [task tasks
                      :let [{:keys [display-id marketplace entity-type
                                    phase status items]} (task-row-data task)
                            task-id  (:id task)
                            loading? (get retry-loading task-id)
                            err      (get retry-err task-id)]]
                  ($ :<> {:key task-id}
                     ($ :tr
                        ($ :td {:class "mono" :style {:font-size "11px"}} (or display-id "—"))
                        ($ :td
                           (if marketplace
                             ($ mp-badge {:mp marketplace})
                             "—"))
                        ($ :td (or entity-type "—"))
                        ($ :td (or phase "—"))
                        ($ :td {:class "num mono"} (or items "—"))
                        ($ :td
                           ($ :span {:class (str "tag tag-sm " (status-tag-class status))}
                              (or status "—")))
                        ($ :td
                           (when (= status "failed")
                             ($ :button
                                {:class    (str "btn btn-ghost" (when loading? " btn-disabled"))
                                 :disabled loading?
                                 :style    {:font-size "11px" :padding "2px 8px"}
                                 :on-click #(retry! task-id)}
                                (if loading? "Запуск…" "Повтор")))))
                     (when err
                       ($ :tr
                          ($ :td {:col-span 7
                                  :style    {:padding "0 0 4px 0"}}
                             ($ :div {:class "alert alert-danger"
                                      :style {:margin "2px 0" :padding "4px 10px"}}
                                ($ icon {:name :danger :class "alert-icon"})
                                ($ :div {:class "alert-body"} err)))))))))))))

(defui ^:private run-row
  "Single row in the recent-runs table. Manages its own expanded? state."
  [{:keys [run load-runs!]}]
  (let [[expanded? set-expanded!] (use-state false)
        started  (:started-at run)
        finished (:finished-at run)
        secs     (duration-s started finished)
        status   (some-> run :status name)
        tasks    (:tasks run)]
    ($ :<> {}
       ($ :tr {:style         {:cursor "pointer"}
               :tab-index     "0"
               :role          "button"
               :aria-expanded (str expanded?)
               :on-click      #(set-expanded! not)
               :on-key-down   (fn [e]
                                (when (#{" " "Enter"} (.-key e))
                                  (.preventDefault e)
                                  (set-expanded! not)))}
          ($ :td {:style {:padding-left "8px"}}
             ($ icon {:name  (if expanded? :arrow-down :arrow-right)
                      :size  12
                      :style {:margin-right "4px" :opacity "0.6"}})
             ($ :span {:class "mono" :style {:font-size "11px"}}
                (subs (or (:run-id run) "") 0 (min 8 (count (or (:run-id run) ""))))))
          ($ :td {:class "mono"} (format-iso started))
          ($ :td {:class "mono"} (if finished (format-iso finished) "—"))
          ($ :td {:class "num mono"} (if secs (str secs " с") "—"))
          ($ :td {:class "num mono"} (or (:total run) "—"))
          ($ :td
             (if (and (:stuck? run) (= status "running"))
               ($ :span {:class "tag tag-sm tag-warn"
                         :title (str "Процесс завис — нет активности более "
                                     (Math/round (or (:age-min run) 0)) " мин")}
                  "⚠ завис " (Math/round (or (:age-min run) 0)) " мин")
               ($ :span {:class (str "tag tag-sm " (status-tag-class status))}
                  (or status "—")))))
       (when (and expanded? (seq tasks))
         ($ task-rows {:tasks tasks :load-runs! load-runs!})))))

(defui ^:private runs-table [{:keys [runs load-runs!]}]
  ($ :section {:class "card section-card"}
     ($ :div {:class "section-head"}
        ($ :h3 {:class "section-title"} "Последние запуски")
        ($ :div {:class "section-subtitle"} "Топ-10 по времени старта"))
     (if (zero? (count runs))
       ($ :div {:style {:padding "24px" :text-align "center"
                        :color "var(--color-fg-muted)"}}
          "Запусков пока не было.")
       ($ :div {:style {:overflow-x "auto"}}
          ($ :table {:class "tbl"}
             ($ :thead
                ($ :tr
                   ($ :th "Run ID")
                   ($ :th "Старт")
                   ($ :th "Финиш")
                   ($ :th {:class "num"} "Длит.")
                   ($ :th {:class "num"} "Задач")
                   ($ :th "Статус")))
             ($ :tbody
                (for [r runs]
                  ($ run-row {:key      (:run-id r)
                               :run      r
                               :load-runs! load-runs!}))))))))

;; ---------------------------------------------------------------------------
;; Coverage matrix
;; ---------------------------------------------------------------------------

(def ^:private mp-keys   [:wb :ozon :ym])
(def ^:private mp-labels {:wb "Wildberries" :ozon "Ozon" :ym "Яндекс Маркет"})
(def ^:private dtype-keys   [:sales :orders :finance :storage :stocks :prices :ad_stats])
(def ^:private dtype-labels {:sales    "Продажи"
                              :orders   "Заказы"
                              :finance  "Финансы"
                              :storage  "Хранение"
                              :stocks   "Остатки"
                              :prices   "Цены"
                              :ad_stats "Реклама"})
(def ^:private cross-mp-keys   [:stats :regions :1c])
(def ^:private cross-mp-labels {:stats   "Статистика"
                                 :regions "Регионы"
                                 :1c      "1С цены"})

(defn- coverage-ref-date
  "The date used for freshness colouring: snapshot → as-of, else span end."
  [p]
  (if (= (:kind p) "snapshot") (:as-of p) (get-in p [:span :to])))

(defn- holes-title [holes]
  (str "Пропуски: "
       (str/join ", " (map (fn [[a b]] (if (= a b) a (str a "–" b))) holes))))

(defui ^:private coverage-label
  "Inner text for a coverage cell/chip. Snapshots read 'на <date>'; event &
   batch entities read 'from–to · present/expected дн' plus a ⚠ gap marker
   (count of missing calendar days, with the ranges in the tooltip)."
  [{:keys [p]}]
  (if (= (:kind p) "snapshot")
    ($ :span {:class "coverage-range"} "на " (:as-of p))
    (let [missing (max 0 (- (or (:expected p) 0) (or (:present p) 0)))]
      ($ :<> {}
         ($ :span {:class "coverage-range"}
            (get-in p [:span :from]) "–" (get-in p [:span :to]))
         ($ :span {:class "coverage-days"}
            " · " (:present p) "/" (:expected p) " дн")
         (when (pos? missing)
           ($ :span {:class "coverage-gap"
                     :title (holes-title (:holes p))}
              " · ⚠ " missing))))))

(defui ^:private coverage-cell [{:keys [cell now]}]
  (let [p (parse-coverage-cell cell)]
    (if (nil? p)
      ($ :td {:class "coverage-cell coverage-missing"} "—")
      ($ :td {:class (str "coverage-cell coverage-" (freshness-class (coverage-ref-date p) now)
                          (when (= (:status p) "partial") " coverage-partial"))}
         ($ coverage-label {:p p})))))

(defui ^:private coverage-matrix [{:keys [coverage error loading?]}]
  (let [now (use-memo (fn [] (js/Date.)) [coverage])]
    ($ :section {:class "card section-card"}
       ($ :div {:class "section-head"}
          ($ :h3 {:class "section-title"} "Покрытие данных"))

       (cond
         loading?
         ($ :div {:style {:padding "16px"}}
            (for [i (range 3)]
              ($ :div {:key i
                       :class "skel"
                       :style {:height "20px" :margin-bottom "8px"
                               :border-radius "var(--radius-sm)"}})))

         error
         ($ :div {:class "alert alert-danger" :style {:margin "12px"}}
            ($ icon {:name :danger :class "alert-icon"})
            ($ :div {:class "alert-body"} error))

         (nil? coverage)
         ($ :div {:style {:padding "16px"
                          :color "var(--color-fg-muted)"
                          :font-size "13px"}}
            "Нет данных о покрытии.")

         :else
         ($ :<> {}
            ;; Legend — two axes explained
            ($ :div {:class "coverage-legend"
                     :style {:font-size "12px"
                             :color "var(--color-fg-muted)"
                             :padding "8px 16px 4px"
                             :display "flex"
                             :flex-wrap "wrap"
                             :gap "16px"}}
               ($ :div {}
                  ($ :strong {:style {:color "var(--color-fg-secondary)"}} "Цвет = свежесть")
                  " (возраст последнего синка): "
                  ($ :span {:class "tag tag-sm tag-good" :style {:font-size "11px"}} "зелёный")
                  " < 3 дн · "
                  ($ :span {:class "tag tag-sm tag-warn" :style {:font-size "11px"}} "жёлтый")
                  " 3–7 дн · "
                  ($ :span {:class "tag tag-sm tag-bad" :style {:font-size "11px"}} "красный")
                  " > 7 дн")
               ($ :div {}
                  ($ :strong {:style {:color "var(--color-fg-secondary)"}} "«⚠ N» = полнота")
                  " — пропущено N календарных дней; наведите для диапазонов")
               ($ :div {}
                  ($ :strong {:style {:color "var(--color-fg-secondary)"}} "Типы ячеек: ")
                  "snapshot — одна точка «на дату»; "
                  "event-stream — непрерывный ряд событий; "
                  "monthly-batch — ежемесячная выгрузка"))
            ;; Per-MP table
            ($ :div {:style {:overflow-x "auto" :margin-bottom "16px"}}
               ($ :table {:class "tbl coverage-tbl"}
                  ($ :thead
                     ($ :tr
                        ($ :th "МП")
                        (for [dk dtype-keys]
                          ($ :th {:key dk} (get dtype-labels dk)))))
                  ($ :tbody
                     (for [mp mp-keys
                           :let [mp-data (get coverage mp)]]
                       ($ :tr {:key mp}
                          ($ :td {:class "coverage-mp-label"}
                             (get mp-labels mp))
                          (for [dk dtype-keys
                                :let [raw (get mp-data dk)]]
                            ($ coverage-cell {:key dk :cell raw :now now})))))))

            ;; Cross-MP chips
            ($ :div {:class "section-head"
                     :style {:padding-bottom "8px"}}
               ($ :div {:class "section-subtitle"} "Общие данные (без привязки к МП)"))
            ($ :div {:style {:display "flex" :flex-wrap "wrap" :gap "8px"
                             :padding "0 16px 16px"}}
               (for [k cross-mp-keys
                     ;; 1c may come through as string key "1c" from JSON
                     :let [raw (or (get coverage k)
                                   (get coverage (name k)))
                           parsed (parse-coverage-cell raw)
                           cls    (freshness-class (coverage-ref-date parsed) now)]]
                 ($ :div {:key k :class (str "chip coverage-chip-" cls)}
                    ($ :span {:class "chip-label"} (get cross-mp-labels k))
                    (if parsed
                      ($ :span {:class "chip-value"}
                         ($ coverage-label {:p parsed}))
                      ($ :span {:class "chip-value chip-absent"} "—"))))))))))
;; ---------------------------------------------------------------------------
;; Page root
;; ---------------------------------------------------------------------------

(defui sync-page []
  (let [[running?  set-running!]  (use-state false)
        [events    set-events!]   (use-state [])
        [runs      set-runs!]     (use-state [])
        [last-msg  set-last-msg!] (use-state nil)
        [start-err set-start-err!] (use-state nil)
        [last-status set-last-status!] (use-state nil)
        [coverage  set-coverage!]  (use-state nil)
        [coverage-error  set-coverage-error!]  (use-state nil)
        [coverage-loading? set-coverage-loading!] (use-state false)
        [schedule        set-schedule!]          (use-state nil)
        [schedule-loading? set-schedule-loading!] (use-state false)
        [schedule-saving?  set-schedule-saving!]  (use-state false)
        [schedule-error    set-schedule-error!]   (use-state nil)
        [schedule-status   set-schedule-status!]  (use-state nil)
        [sync-period       set-sync-period!]       (use-state "last-7-days")
        es-ref                    (use-ref nil)
        runs-timer-ref            (use-ref nil)

        load-schedule!
        (fn []
          (set-schedule-loading! true)
          (set-schedule-error! nil)
          (fetch-json! "/api/sync/schedule"
                       (fn [body]
                         (set-schedule! (schedule/parse-schedule-payload body))
                         (set-schedule-loading! false))
                       (fn [err]
                         ;; 404 with :error-code "not-initialized" → show form with defaults, no error banner
                         (set-schedule! (schedule/parse-schedule-payload nil))
                         (set-schedule-loading! false)
                         (when-not (= "not-initialized" (get-in err [:body :error-code]))
                           (set-schedule-error! (or (:message err) (get-in err [:body :error]) "Не удалось загрузить расписание"))))))

        save-schedule!
        (fn [form]
          (let [err (schedule/validate-schedule-form form)]
            (if err
              (set-schedule-error! err)
              (do
                (set-schedule-saving! true)
                (set-schedule-error! nil)
                (set-schedule-status! nil)
                (post-json! "/api/sync/schedule"
                            (schedule/schedule-form->body form)
                            (fn [body]
                              (set-schedule! (schedule/parse-schedule-payload body))
                              (set-schedule-saving! false)
                              (set-schedule-status! :saved)
                              (js/setTimeout #(set-schedule-status! nil) 3000))
                            (fn [msg]
                              (set-schedule-saving! false)
                              (set-schedule-error! msg)))))))

        load-coverage!
        (fn []
          (set-coverage-loading! true)
          (fetch-json! "/api/sync/coverage"
                       (fn [body]
                         (set-coverage-error! nil)
                         (set-coverage! body)
                         (set-coverage-loading! false))
                       (fn [err]
                         (set-coverage-error! (or (:message err) (get-in err [:body :error]) "Не удалось загрузить"))
                         (set-coverage-loading! false))))

        load-runs!
        (fn []
          (fetch-json! "/api/sync/runs/recent"
                       (fn [body]
                         (let [rs (or body [])]
                           (set-runs! (vec rs))
                           (when-let [first-run (first rs)]
                             (set-last-status! (some-> first-run :status name)))))
                       nil))

        push-event!
        (fn [ev]
          (set-events! (fn [evs]
                         (let [trimmed (if (>= (count evs) 200)
                                         (subvec evs (- (count evs) 199))
                                         evs)]
                           (conj trimmed ev))))
          (set-last-msg! (:text ev)))

        open-stream!
        (fn []
          (when @es-ref (.close ^js @es-ref))
          (let [es (js/EventSource. "/api/sync/stream")]
            (set! (.-onmessage es)
                  (fn [e] (push-event! {:type :message :text (.-data e)})))
            (.addEventListener es "message"
                               (fn [e] (push-event! {:type :message :text (.-data e)})))
            (.addEventListener es "done"
                               (fn [_]
                                 (push-event! {:type :done :text "✓ Синхронизация завершена"})
                                 (set-running! false)
                                 (load-runs!)
                                 (load-coverage!)))
            (.addEventListener es "error"
                               (fn [e]
                                 (push-event! {:type :error
                                               :text (or (.-data e) "Ошибка SSE")})))
            (set! (.-onerror es)
                  (fn [_]
                    ;; Browser auto-reconnects; just log once.
                    nil))
            (reset! es-ref es)))

        start-sync!
        (fn []
          (set-start-err! nil)
          (set-events! [])
          (set-running! true)
          (open-stream!)
          (post-json! "/api/sync/start"
                      {:what "all" :period sync-period :marketplace "all"}
                      (fn [body]
                        (push-event! {:type :message
                                      :text (str "→ запущено: "
                                                 (or (:run-id body)
                                                     (:status body)
                                                     "ok"))}))
                      (fn [msg]
                        (set-running! false)
                        (set-start-err! msg))))

        stop-sync!
        (fn []
          (post-json! "/api/sync/stop" {}
                      (fn [_]
                        (push-event! {:type :message :text "⏹ Остановка запрошена"})
                        (set-running! false))
                      (fn [msg]
                        (set-start-err! msg))))]

    ;; Mount: load runs + coverage once, plus refresh runs every 8s while running.
    (use-effect
     (fn []
       (fetch-json! "/api/sync/runs/recent"
                    (fn [body]
                      (let [rs (or body [])]
                        (set-runs! (vec rs))
                        (when-let [first-run (first rs)]
                          (set-last-status! (some-> first-run :status name)))))
                    nil)
       (load-coverage!)
       (load-schedule!)
       (when running?
         (let [t (js/setInterval
                  #(fetch-json! "/api/sync/runs/recent"
                                (fn [body] (set-runs! (vec (or body []))))
                                nil)
                  8000)]
           (reset! runs-timer-ref t)))
       (fn []
         (when @runs-timer-ref
           (js/clearInterval @runs-timer-ref)
           (reset! runs-timer-ref nil))
         (when @es-ref
           (.close ^js @es-ref)
           (reset! es-ref nil))))
     [running?])

    ($ :div {:class "page-content"}
       ($ status-banner {:running?        running?
                         :last-event      last-msg
                         :last-run-status last-status})

       (when start-err
         ($ :div {:class "alert alert-danger" :style {:margin-bottom "12px"}}
            ($ icon {:name :danger :class "alert-icon"})
            ($ :div {:class "alert-body"}
               ($ :div {:class "alert-title"} "Не удалось запустить sync")
               ($ :div start-err))))

       ($ schedule/schedule-editor {:schedule      schedule
                           :on-save        save-schedule!
                           :loading?       schedule-loading?
                           :saving?        schedule-saving?
                           :save-error     schedule-error
                           :save-status    schedule-status})

       ($ :section {:class "card section-card"}
          ($ :div {:class "section-head"}
             ($ :h3 {:class "section-title"} "Управление")
             ($ :div {:class "section-subtitle"}
                "По умолчанию синхронизируем все МП за последние 7 дней."))
          ($ :div {:style {:display "flex" :gap "8px" :flex-wrap "wrap"
                           :align-items "center" :margin-bottom "8px"}}
             ($ :select {:class     "select select-sm"
                         :disabled  running?
                         :value     sync-period
                         :on-change (fn [e] (set-sync-period! (.. e -target -value)))}
                ($ :option {:value "last-7-days"}  "Последние 7 дней")
                ($ :option {:value "last-30-days"} "Последние 30 дней")
                ($ :option {:value "this-month"}   "Текущий месяц")
                ($ :option {:value "prev-month"}   "Прошлый месяц"))
             ($ :button {:class    (str "btn btn-primary" (when running? " btn-disabled"))
                         :disabled running?
                         :on-click start-sync!}
                ($ icon {:name :refresh :size 14})
                "Запустить полный sync")
             ($ :button {:class    (str "btn btn-secondary" (when (not running?) " btn-disabled"))
                         :disabled (not running?)
                         :on-click stop-sync!}
                ($ icon {:name :x :size 14})
                "Остановить")
             ($ :button {:class    "btn btn-ghost"
                         :on-click (fn [] (load-runs!) (load-coverage!))}
                ($ icon {:name :refresh :size 14})
                "Обновить список"))
          ($ :div {:class "help-text"
                   :style {:font-size "12px" :color "var(--color-fg-muted)"
                           :padding "0 0 8px" :line-height "1.6"}}
             "Окна по типу данных: "
             ($ :strong "остатки/цены") " — снапшот «сейчас» (период игнорируется); "
             ($ :strong "Ozon заказы/отправки") " — фиксировано −60 дн; "
             ($ :strong "финансы") " — целый месяц; "
             ($ :strong "WB хранение") " — чанки по 7 дн; "
             ($ :strong "WB регионы, WB/YM реклама") " — чанки по 30 дн."))

       ($ live-log  {:events events})
       ($ coverage-matrix {:coverage  coverage
                           :error     coverage-error
                           :loading?  coverage-loading?})
       ($ runs-table {:runs       runs
                      :load-runs! load-runs!}))))
