(ns marker.pages.feedback
  "Floating feedback widget — Phase pilot-feedback Task 5.
   Self-contained: floating FAB button + modal dialog.
   POSTs to /api/v1/feedback via FormData+fetch (no Transit)."
  (:require [clojure.string :as str]
            [uix.core :as uix :refer [defui $]]))

;; ---------------------------------------------------------------------------
;; Pure data — exported for tests
;; ---------------------------------------------------------------------------

(def kind-options
  "Feedback kind select options: [[value label] ...]"
  [["bug"      "Проблема"]
   ["idea"     "Идея"]
   ["question" "Вопрос"]])

;; ---------------------------------------------------------------------------
;; Submit helper
;; ---------------------------------------------------------------------------

(defn- submit!
  "Build FormData and POST to /api/v1/feedback.
   Calls on-ok on 201; calls on-err with a string message on 4xx or network failure."
  [{:keys [kind message files on-ok on-err]}]
  (let [fd (js/FormData.)]
    (.append fd "message"    message)
    (.append fd "kind"       kind)
    (.append fd "page_url"   (.. js/window -location -pathname))
    (.append fd "user_agent" (.. js/navigator -userAgent))
    (doseq [f files] (.append fd "attachments" f))
    (-> (js/fetch "/api/v1/feedback" #js {:method "POST" :body fd})
        (.then (fn [resp]
                 (if (.-ok resp)
                   (on-ok)
                   (-> (.json resp)
                       (.then (fn [b] (on-err (or (.-error b) "Ошибка"))))))))
        (.catch (fn [_] (on-err "Сеть недоступна"))))))

;; ---------------------------------------------------------------------------
;; Widget component
;; ---------------------------------------------------------------------------

(defui widget []
  (let [[open?       set-open!]    (uix/use-state false)
        [kind        set-kind!]    (uix/use-state "bug")
        [message     set-message!] (uix/use-state "")
        [files       set-files!]   (uix/use-state [])
        [status      set-status!]  (uix/use-state nil)] ; nil | :sending | :ok | {:error str}
    ($ :div {:class "feedback-widget"}
       ;; FAB button — always visible, bottom-right
       ($ :button {:class    "btn feedback-fab"
                   :on-click #(set-open! true)}
          "Сообщить о проблеме")
       ;; Modal dialog
       (when open?
         ($ :div {:class "modal-backdrop open"
                  :on-click #(do (set-open! false) (set-status! nil))}
            ($ :div {:class "modal"
                     :on-click #(.stopPropagation %)}
               ($ :h3 {} "Обратная связь")
               ;; Kind select
               ($ :select {:value     kind
                           :on-change #(set-kind! (.. % -target -value))}
                  (for [[v label] kind-options]
                    ($ :option {:key v :value v} label)))
               ;; Message textarea
               ($ :textarea {:value       message
                             :placeholder "Опишите проблему…"
                             :on-change   #(set-message! (.. % -target -value))})
               ;; File attachment input
               ($ :input {:type      "file"
                          :multiple  true
                          :on-change #(set-files! (array-seq (.. % -target -files)))})
               ;; Status feedback
               (when (map? status)
                 ($ :div {:class "badge badge-danger"} (:error status)))
               (when (= status :ok)
                 ($ :div {:class "badge badge-success"} "Отправлено, спасибо!"))
               ;; Actions
               ($ :div {:class "modal-actions"}
                  ($ :button {:class    "btn btn-primary"
                              :disabled (= status :sending)
                              :on-click (fn []
                                          (if (str/blank? message)
                                            (set-status! {:error "Введите описание"})
                                            (do (set-status! :sending)
                                                (submit! {:kind    kind
                                                          :message message
                                                          :files   files
                                                          :on-ok   #(do (set-status! :ok)
                                                                        (set-message! "")
                                                                        (set-files! [])
                                                                        (set-open! false))
                                                          :on-err  #(set-status! {:error %})}))))}
                     "Отправить")
                  ($ :button {:class    "btn btn-ghost"
                              :on-click #(do (set-open! false) (set-status! nil))}
                     "Закрыть"))))))))
