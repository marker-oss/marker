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
            [marker.ui.icons :refer [icon]]
            [marker.ui.chrome :refer [mp-badge]]))

;; ---------------------------------------------------------------------------
;; HTTP helpers (plain fetch + JSON; sync API is JSON-only)
;; ---------------------------------------------------------------------------

(defn- post-json! [url body on-ok on-err]
  (-> (js/fetch url
                #js {:method  "POST"
                     :headers #js {"Content-Type" "application/json"
                                   "Accept"       "application/json"}
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
      (.then (fn [r] (.json r)))
      (.then (fn [body] (on-ok (js->clj body :keywordize-keys true))))
      (.catch (fn [e] (when on-err (on-err (.-message e)))))))

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
  "Normalise a raw coverage cell from the JSON response.
   Returns {:from string :to string :days int} or nil.
   Accepts a map (good), nil (no data), or a sentinel string like \"—\"."
  [cell]
  (cond
    (nil? cell)    nil
    (string? cell) nil                  ; sentinel "—" or any string → absent
    (map? cell)    (let [f (:from cell)
                         t (:to   cell)
                         d (:days cell)]
                     (when (and f t)
                       {:from f :to t :days (or d 0)}))
    :else          nil))

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
    ($ :<>
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
             ($ :span {:class (str "tag tag-sm " (status-tag-class status))}
                (or status "—"))))
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
(def ^:private dtype-keys   [:sales :orders :finance :storage :stocks])
(def ^:private dtype-labels {:sales   "Продажи"
                              :orders  "Заказы"
                              :finance "Финансы"
                              :storage "Хранение"
                              :stocks  "Остатки"})
(def ^:private cross-mp-keys   [:stats :regions :1c :prices])
(def ^:private cross-mp-labels {:stats   "Статистика"
                                 :regions "Регионы"
                                 :1c      "1С цены"
                                 :prices  "Цены"})

(defui ^:private coverage-cell [{:keys [cell now]}]
  (let [parsed (parse-coverage-cell cell)
        cls    (freshness-class (:to parsed) now)]
    ($ :td {:class (str "coverage-cell coverage-" cls)}
       (if parsed
         ($ :span
            ($ :span {:class "coverage-range"}
               (:from parsed) "–" (:to parsed))
            ($ :span {:class "coverage-days"}
               " · " (:days parsed) " дн"))
         "—"))))

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
         ($ :<>
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
                           cls    (freshness-class (:to parsed) now)]]
                 ($ :div {:key k :class (str "chip coverage-chip-" cls)}
                    ($ :span {:class "chip-label"} (get cross-mp-labels k))
                    (if parsed
                      ($ :span {:class "chip-value"}
                         (:from parsed) "–" (:to parsed)
                         " · " (:days parsed) " дн")
                      ($ :span {:class "chip-value chip-absent"} "—"))))))))))

;; ---------------------------------------------------------------------------
;; Schedule helpers (pure, testable)
;; ---------------------------------------------------------------------------

(def ^:private valid-what-values
  #{"sales" "orders" "finance" "storage" "stocks" "stats" "prices" "regions" "cashflow" "all"})

(def ^:private valid-mp-values    #{"all" "wb" "ozon" "ym"})
(def ^:private valid-period-values #{"last-week" "last-7-days" "last-30-days" "this-month"})

(defn parse-schedule-payload
  "Normalise a GET /api/sync/schedule response body.
   Converts snake_case :next_run_at → :next-run-at and coerces types."
  [body]
  (when body
    {:enabled     (boolean (:enabled body))
     :hour        (or (:hour body) 6)
     :minute      (or (:minute body) 0)
     :what        (or (:what body) "all")
     :marketplace (or (:marketplace body) "all")
     :period      (or (:period body) "last-7-days")
     :next-run-at (or (:next_run_at body) (:next-run-at body))}))

(defn schedule-form->body
  "Extract only the keys the POST /api/sync/schedule endpoint expects."
  [form]
  (select-keys form [:enabled :hour :minute :what :marketplace :period]))

(defn validate-schedule-form
  "Returns nil if valid; returns an error string describing the first problem."
  [form]
  (let [h (:hour form)
        m (:minute form)]
    (cond
      (not (and (int? h) (<= 0 h 23)))  (str "Час должен быть от 0 до 23 (получено: " h ")")
      (not (and (int? m) (<= 0 m 59)))  (str "Минута должна быть от 0 до 59 (получено: " m ")")
      (not (valid-what-values    (:what form)))        "Недопустимое значение «что синкать»"
      (not (valid-mp-values      (:marketplace form))) "Недопустимый маркетплейс"
      (not (valid-period-values  (:period form)))      "Недопустимый период"
      :else nil)))

;; ---------------------------------------------------------------------------
;; Schedule editor component
;; ---------------------------------------------------------------------------

(defui ^:private schedule-editor
  [{:keys [schedule on-save loading? saving? save-error save-status]}]
  (let [defaults {:enabled false :hour 6 :minute 0
                  :what "all" :marketplace "all" :period "last-7-days"}
        [form set-form!] (use-state (merge defaults (dissoc schedule :next-run-at)))
        next-run        (:next-run-at schedule)
        on-field        (fn [k v] (set-form! #(assoc % k v)))]
    ($ :section {:class "card section-card"}
       ($ :div {:class "section-head"}
          ($ :h3 {:class "section-title"} "Расписание")
          ($ :div {:class "section-subtitle"}
             "Ежедневный автоматический sync."))

       (when loading?
         ($ :div {:style {:padding "16px"}}
            (for [i (range 3)]
              ($ :div {:key i :class "skel"
                       :style {:height "20px" :margin-bottom "8px"
                               :border-radius "var(--radius-sm)"}}))))

       (when-not loading?
         ($ :div {:style {:padding "0 16px 16px" :display "flex" :flex-direction "column" :gap "12px"}}

            ;; Enabled toggle
            ($ :label {:style {:display "flex" :align-items "center" :gap "8px"
                                :font-size "14px" :cursor "pointer"}}
               ($ :input {:type      "checkbox"
                           :checked   (boolean (:enabled form))
                           :on-change #(on-field :enabled (.. % -target -checked))})
               "Включено")

            ;; Time
            ($ :div {:style {:display "flex" :align-items "center" :gap "6px"
                              :font-size "14px"}}
               ($ :span {:style {:color "var(--color-fg-muted)"}} "Время")
               ($ :input {:type      "number" :min 0 :max 23 :value (:hour form)
                           :style     {:width "56px" :text-align "center"}
                           :on-change #(on-field :hour (js/parseInt (.. % -target -value) 10))})
               ($ :span ":")
               ($ :input {:type      "number" :min 0 :max 59 :value (:minute form)
                           :style     {:width "56px" :text-align "center"}
                           :on-change #(on-field :minute (js/parseInt (.. % -target -value) 10))}))

            ;; What
            ($ :div {:style {:display "flex" :align-items "center" :gap "8px" :font-size "14px"}}
               ($ :span {:style {:color "var(--color-fg-muted)" :min-width "110px"}} "Что синкать")
               ($ :select {:value (:what form) :on-change #(on-field :what (.. % -target -value))}
                  (for [v ["all" "sales" "orders" "finance" "storage" "stocks"
                            "stats" "prices" "regions" "cashflow"]]
                    ($ :option {:key v :value v} v))))

            ;; Marketplace
            ($ :div {:style {:display "flex" :align-items "center" :gap "8px" :font-size "14px"}}
               ($ :span {:style {:color "var(--color-fg-muted)" :min-width "110px"}} "Маркетплейс")
               ($ :select {:value (:marketplace form) :on-change #(on-field :marketplace (.. % -target -value))}
                  (for [v ["all" "wb" "ozon" "ym"]]
                    ($ :option {:key v :value v} v))))

            ;; Period
            ($ :div {:style {:display "flex" :align-items "center" :gap "8px" :font-size "14px"}}
               ($ :span {:style {:color "var(--color-fg-muted)" :min-width "110px"}} "Период")
               ($ :select {:value (:period form) :on-change #(on-field :period (.. % -target -value))}
                  (for [v ["last-week" "last-7-days" "last-30-days" "this-month"]]
                    ($ :option {:key v :value v} v))))

            ;; Next-run hint
            (when (and (:enabled form) next-run)
              ($ :div {:style {:font-size "13px" :color "var(--color-fg-muted)"}}
                 "Следующий запуск: " next-run))

            ;; Status / error feedback
            (when (= save-status :saved)
              ($ :div {:class "alert alert-success"}
                 ($ :div {:class "alert-body"} "Расписание сохранено")))
            (when save-error
              ($ :div {:class "alert alert-danger"}
                 ($ icon {:name :danger :class "alert-icon"})
                 ($ :div {:class "alert-body"} save-error)))

            ;; Save button
            ($ :button {:class    (str "btn btn-primary" (when saving? " btn-disabled"))
                        :disabled saving?
                        :on-click #(on-save form)}
               (if saving? "Сохраняем…" "Сохранить")))))))

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
        es-ref                    (use-ref nil)
        runs-timer-ref            (use-ref nil)

        load-schedule!
        (fn []
          (set-schedule-loading! true)
          (set-schedule-error! nil)
          (fetch-json! "/api/sync/schedule"
                       (fn [body]
                         (set-schedule! (parse-schedule-payload body))
                         (set-schedule-loading! false))
                       (fn [msg]
                         ;; 404 "schedule not initialized" → show form with defaults
                         (set-schedule! (parse-schedule-payload nil))
                         (set-schedule-loading! false)
                         (when-not (str/includes? (or msg "") "not initialized")
                           (set-schedule-error! msg)))))

        save-schedule!
        (fn [form]
          (let [err (validate-schedule-form form)]
            (if err
              (set-schedule-error! err)
              (do
                (set-schedule-saving! true)
                (set-schedule-error! nil)
                (set-schedule-status! nil)
                (post-json! "/api/sync/schedule"
                            (schedule-form->body form)
                            (fn [body]
                              (set-schedule! (parse-schedule-payload body))
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
                       (fn [msg]
                         (set-coverage-error! msg)
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
                      {:what "all" :period "last-7-days" :marketplace "all"}
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

       ($ schedule-editor {:schedule      schedule
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
          ($ :div {:style {:display "flex" :gap "8px" :flex-wrap "wrap"}}
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
                "Обновить список")))

       ($ live-log  {:events events})
       ($ coverage-matrix {:coverage  coverage
                           :error     coverage-error
                           :loading?  coverage-loading?})
       ($ runs-table {:runs       runs
                      :load-runs! load-runs!}))))
