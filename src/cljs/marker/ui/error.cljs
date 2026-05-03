(ns marker.ui.error
  "Global React ErrorBoundary for the Marker SPA.

   Catches render-time exceptions in any descendant and shows a friendly
   fallback UI with a Reload button. Without this, a single bad row in
   one panel would unmount the whole app and leave a blank screen.

   ErrorBoundary requires a class component (componentDidCatch is not
   exposed via hooks), so we drop down to React.createClass via uix
   interop."
  (:require ["react" :as react]
            [uix.core :refer [$ defui]]
            [marker.ui.icons :refer [icon]]))

;; React class component via JS interop. Defining inside a function so
;; it's only constructed once at module load.
(def ^:private ErrorBoundaryClass
  (let [^js base react/Component]
    (let [cls (fn [props]
                (this-as this
                  (.call base this props)
                  (set! (.-state ^js this) #js {:hasError false :error nil})
                  this))]
      (set! (.-prototype ^js cls) (.create js/Object (.-prototype base)))
      (set! (.. ^js cls -prototype -constructor) cls)
      ;; getDerivedStateFromError — capture error into state for next render.
      (set! (.-getDerivedStateFromError ^js cls)
            (fn [error] #js {:hasError true :error error}))
      ;; componentDidCatch — log to console.
      (set! (.. ^js cls -prototype -componentDidCatch)
            (fn [error info]
              (this-as _this
                (js/console.error "ErrorBoundary caught:" error info))))
      ;; render — show children or fallback.
      (set! (.. ^js cls -prototype -render)
            (fn []
              (this-as this
                (let [^js state    (.-state ^js this)
                      ^js props    (.-props ^js this)
                      err?         (.-hasError state)
                      err          (.-error state)
                      fallback     (.-fallback props)
                      children     (.-children props)]
                  (if err?
                    (if fallback (fallback err) nil)
                    children)))))
      cls)))

(defui ^:private default-fallback [{:keys [error]}]
  ($ :div {:style {:min-height       "100vh"
                   :display          "grid"
                   :place-items      "center"
                   :background       "var(--color-bg-app)"
                   :padding          "32px"}}
     ($ :div {:class "card section-card"
              :style {:max-width "480px"
                      :width     "100%"
                      :text-align "center"}}
        ($ :div {:style {:font-size "32px" :margin-bottom "12px"}} "⚠️")
        ($ :h2 {:style {:font-size "18px" :margin "0 0 8px"
                        :color "var(--color-fg-primary)"}}
           "Что-то пошло не так")
        ($ :p {:style {:color     "var(--color-fg-muted)"
                       :font-size "13px"
                       :margin    "0 0 4px"}}
           "Произошла ошибка в интерфейсе. Попробуйте перезагрузить страницу.")
        (when error
          ($ :pre {:style {:font-family "var(--font-mono)"
                           :font-size   "11px"
                           :background  "var(--color-bg-subtle)"
                           :padding     "8px 12px"
                           :border-radius "6px"
                           :color       "var(--color-fg-muted)"
                           :margin      "12px 0"
                           :overflow-x  "auto"
                           :text-align  "left"
                           :white-space "pre-wrap"}}
             (or (.-message error) (str error))))
        ($ :div {:style {:display "flex" :gap "8px" :justify-content "center"}}
           ($ :button {:class    "btn btn-primary"
                       :on-click #(.reload (.-location js/window))}
              ($ icon {:name :refresh :size 14})
              "Перезагрузить")
           ($ :button {:class    "btn btn-secondary"
                       :on-click #(set! (.-href (.-location js/window)) "/app/pulse")}
              "На главную")))))

(defn boundary
  "Wrap children in an ErrorBoundary. Use as: ($ boundary {} ($ children ...))"
  [props & children]
  (apply react/createElement
         ErrorBoundaryClass
         #js {:fallback (fn [error] ($ default-fallback {:error error}))}
         children))
