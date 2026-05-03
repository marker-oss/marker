(ns marker.pages.cost-prices
  "Cost-prices upload page — Phase 9.
   Calls existing JSON endpoints under /api/cost-prices/*:
     POST /api/cost-prices/upload  — multipart `file` part, returns
                                     {:loaded :rejected :source ...}
     GET  /api/cost-prices/imports — recent import audit rows
   Uses fetch + FormData so no Transit/JSON wiring is needed."
  (:require [uix.core :refer [$ defui use-state use-effect use-ref]]
            [marker.ui.icons :refer [icon]]))

(defn parse-imports-payload
  "Pure helper: takes a parsed JS response object from /api/cost-prices/imports
   and returns a Clojure vector of maps (one per import row).
   Returns [] when the payload has no :imports array."
  [body]
  (let [arr (or (.-imports body) #js [])]
    (mapv #(js->clj % :keywordize-keys true) arr)))

(defn- fetch-imports!
  "Fetch recent imports list.
   on-result is called with the parsed JSON on success.
   on-error  is called with a string message on HTTP error or network failure."
  [on-result on-error]
  (-> (js/fetch "/api/cost-prices/imports?limit=10"
                #js {:method "GET" :headers #js {"Accept" "application/json"}})
      (.then (fn [r]
               (if (.-ok r)
                 (.json r)
                 (throw (js/Error. (str "HTTP " (.-status r) " " (.-statusText r)))))))
      (.then on-result)
      (.catch (fn [e] (on-error (.-message e))))))

(defn- upload-file!
  "POST a single file as multipart/form-data. Resolves with parsed JSON."
  [file on-progress on-result on-error]
  (let [fd (js/FormData.)]
    (.append fd "file" file)
    (on-progress :sending)
    (-> (js/fetch "/api/cost-prices/upload"
                  #js {:method "POST" :body fd
                       :headers #js {"Accept" "application/json"}})
        (.then (fn [r]
                 (-> (.json r)
                     (.then (fn [body]
                              (if (.-ok r)
                                (on-result body)
                                (on-error (or (.-error body) (str "HTTP " (.-status r))))))))))
        (.catch (fn [e] (on-error (.-message e)))))))

;; ---------------------------------------------------------------------------
;; Imports history table
;; ---------------------------------------------------------------------------

(defui ^:private imports-table [{:keys [imports error]}]
  ($ :section {:class "card section-card"}
     ($ :div {:class "section-head"}
        ($ :h3 {:class "section-title"} "История загрузок")
        ($ :div {:class "section-subtitle"}
           "Последние 10 импортов"))
     (cond
       error
       ($ :div {:class "alert alert-danger" :style {:margin "12px"}}
          ($ icon {:name :danger :class "alert-icon"})
          ($ :div {:class "alert-body"}
             ($ :div {:class "alert-title"} "Не удалось загрузить историю")
             ($ :div error)))

       (zero? (count imports))
       ($ :div {:style {:padding "24px" :text-align "center"
                        :color "var(--color-fg-muted)"}}
          "Импортов пока не было.")

       :else
       ($ :div {:style {:overflow-x "auto"}}
          ($ :table {:class "tbl"}
             ($ :thead
                ($ :tr
                   ($ :th "#")
                   ($ :th "Когда")
                   ($ :th "Источник")
                   ($ :th {:class "num"} "Загружено")
                   ($ :th {:class "num"} "Отброшено")
                   ($ :th "Файл")
                   ($ :th "Примечание")))
             ($ :tbody
                (for [r imports]
                  ($ :tr {:key (:id r)}
                     ($ :td {:class "mono"} (:id r))
                     ($ :td {:class "mono" :style {:font-size "12px"}}
                        (:imported-at r))
                     ($ :td (:source r))
                     ($ :td {:class "num mono"} (:loaded r))
                     ($ :td {:class "num mono"} (:rejected r))
                     ($ :td (or (:filename r) "—"))
                     ($ :td (or (:notes r) ""))))))))))

;; ---------------------------------------------------------------------------
;; Page root
;; ---------------------------------------------------------------------------

(defui cost-prices []
  (let [[file          set-file!]          (use-state nil)
        [status        set-status!]        (use-state :idle)   ; :idle :sending :ok :err
        [last-result   set-result!]        (use-state nil)
        [error         set-error!]         (use-state nil)
        [imports       set-imports!]       (use-state [])
        [imports-error set-imports-error!] (use-state nil)
        input-ref                          (use-ref nil)
        reload-imports!                    (fn []
                                             (fetch-imports!
                                              (fn [body]
                                                (set-imports-error! nil)
                                                (set-imports! (parse-imports-payload body)))
                                              (fn [msg]
                                                (set-imports-error! msg))))]

    (use-effect
     (fn []
       (fetch-imports!
        (fn [body]
          (set-imports-error! nil)
          (set-imports! (parse-imports-payload body)))
        (fn [msg]
          (set-imports-error! msg)))
       js/undefined)
     [])

    ($ :div {:class "page-content"}
       ;; Upload card
       ($ :section {:class "card section-card"}
          ($ :div {:class "section-head"}
             ($ :h3 {:class "section-title"} "Загрузка себестоимости из 1С")
             ($ :div {:class "section-subtitle"}
                "CSV-файл из выгрузки units.csv"))

          ($ :div {:style {:display "flex" :gap "12px" :align-items "center"
                           :flex-wrap "wrap"}}
             ($ :input {:type      "file"
                        :ref       input-ref
                        :accept    ".csv,text/csv"
                        :style     {:flex "1"
                                    :font-size "13px"
                                    :padding "6px"}
                        :on-change (fn [e]
                                     (let [^js f (some-> e .-target .-files (aget 0))]
                                       (set-file! f)
                                       (set-status! :idle)
                                       (set-error! nil)))})
             ($ :button
                {:class    (str "btn btn-primary"
                                (when (or (nil? file) (= status :sending))
                                  " btn-disabled"))
                 :disabled (or (nil? file) (= status :sending))
                 :on-click (fn [_]
                             (when file
                               (upload-file!
                                file
                                set-status!
                                (fn [body]
                                  (set-status! :ok)
                                  (set-result! (js->clj body :keywordize-keys true))
                                  (set-file! nil)
                                  (when @input-ref
                                    (set! (.-value @input-ref) ""))
                                  (reload-imports!))
                                (fn [msg]
                                  (set-status! :err)
                                  (set-error! msg)))))}
                ($ icon {:name :download :size 14 :style {:transform "rotate(180deg)"}})
                (case status
                  :sending "Загрузка..."
                  "Загрузить"))
             (when (and file (not= status :sending))
               ($ :div {:style {:font-size "12px"
                                :color "var(--color-fg-muted)"
                                :font-family "var(--font-mono)"}}
                  (.-name ^js file) " · "
                  (-> (.-size ^js file) (/ 1024) (.toFixed 1)) " KB")))

          ;; Result / error feedback
          (cond
            (= status :ok)
            ($ :div {:class "alert alert-success" :style {:margin-top "12px"}}
               ($ icon {:name :check :class "alert-icon"})
               ($ :div {:class "alert-body"}
                  ($ :div {:class "alert-title"} "Импорт успешен")
                  ($ :div
                     "Загружено: " ($ :strong (or (:loaded last-result) 0))
                     " · Отброшено: " ($ :strong (or (:rejected last-result) 0))
                     " · Источник: " (or (:source last-result) "csv1c"))))

            (= status :err)
            ($ :div {:class "alert alert-danger" :style {:margin-top "12px"}}
               ($ icon {:name :danger :class "alert-icon"})
               ($ :div {:class "alert-body"}
                  ($ :div {:class "alert-title"} "Ошибка загрузки")
                  ($ :div (or error "Неизвестная ошибка")))))

          ($ :p {:style {:font-size "12px" :color "var(--color-fg-muted)"
                         :margin-top "12px"}}
             "После загрузки себестоимости обновятся немедленно — перезапуск не требуется."))

       ;; Imports history
       ($ imports-table {:imports imports :error imports-error}))))
