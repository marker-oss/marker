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
  (:require [uix.core :refer [$ defui use-state use-effect use-ref]]
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
    (let [parts (clojure.string/split id #"/")]
      (if (>= (count parts) 2)
        (clojure.string/join "/" (take-last 2 parts))
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
        [retry-err     set-retry-err!]     (use-state nil)
        retry!
        (fn [task-id]
          (set-retry-err! nil)
          (set-retry-loading! (fn [m] (assoc m task-id true)))
          (post-json! (str "/api/sync/tasks/" task-id "/retry")
                      nil
                      (fn [_]
                        (set-retry-loading! (fn [m] (dissoc m task-id)))
                        (load-runs!))
                      (fn [msg]
                        (set-retry-loading! (fn [m] (dissoc m task-id)))
                        (set-retry-err! msg))))]
    ($ :tr {:class "run-tasks-row"}
       ($ :td {:col-span 6 :style {:padding "0 0 8px 32px"}}
          (when retry-err
            ($ :div {:class "alert alert-danger"
                     :style {:margin "6px 8px 6px 0" :padding "6px 10px"}}
               ($ icon {:name :danger :class "alert-icon"})
               ($ :div {:class "alert-body"} retry-err)))
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
                            loading? (get retry-loading (:id task))]]
                  ($ :tr {:key (:id task)}
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
                              :on-click #(retry! (:id task))}
                             (if loading? "Запуск…" "Повтор"))))))))))))

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
       ($ :tr {:style    {:cursor "pointer"}
               :on-click #(set-expanded! not)}
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
;; Page root
;; ---------------------------------------------------------------------------

(defui sync-page []
  (let [[running?  set-running!]  (use-state false)
        [events    set-events!]   (use-state [])
        [runs      set-runs!]     (use-state [])
        [last-msg  set-last-msg!] (use-state nil)
        [start-err set-start-err!] (use-state nil)
        [last-status set-last-status!] (use-state nil)
        es-ref                    (use-ref nil)
        runs-timer-ref            (use-ref nil)

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
                                 (load-runs!)))
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

    ;; Mount: load runs once, plus refresh every 8s while running.
    (use-effect
     (fn []
       (fetch-json! "/api/sync/runs/recent"
                    (fn [body]
                      (let [rs (or body [])]
                        (set-runs! (vec rs))
                        (when-let [first-run (first rs)]
                          (set-last-status! (some-> first-run :status name)))))
                    nil)
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
                         :on-click load-runs!}
                ($ icon {:name :refresh :size 14})
                "Обновить список")))

       ($ live-log  {:events events})
       ($ runs-table {:runs       runs
                      :load-runs! load-runs!}))))
