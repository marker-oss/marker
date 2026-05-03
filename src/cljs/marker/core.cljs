(ns marker.core
  "Marker SPA — entry point. Phase 1: bootstrap-only smoke test.
   Subsequent phases mount the real shell (sidebar + topbar + routed pages)."
  (:require [uix.core :refer [$ defui]]
            [uix.dom]))

(defui hello-marker []
  ($ :div {:style {:padding "48px"
                   :max-width "640px"
                   :margin "0 auto"
                   :font-family "Inter, system-ui, sans-serif"}}
     ($ :div {:style {:display "flex"
                      :align-items "center"
                      :gap "12px"
                      :margin-bottom "16px"}}
        ($ :div {:style {:width "32px"
                         :height "32px"
                         :border-radius "8px"
                         :background "#0f172a"
                         :display "grid"
                         :place-items "center"}}
           ($ :div {:style {:width "12px"
                            :height "12px"
                            :border-radius "50%"
                            :background "#4f46e5"
                            :box-shadow "0 0 0 2px #0f172a, 0 0 0 4px #4f46e5"}}))
        ($ :h1 {:style {:font-size "20px"
                        :font-weight 700
                        :margin 0
                        :letter-spacing "-0.01em"}}
           "Marker SPA — bootstrapped"))
     ($ :p {:style {:color "#64748b"
                    :font-size "14px"
                    :line-height "20px"}}
        "ClojureScript + UIx + shadow-cljs toolchain работает. "
        "Следующая фаза: tokens.css + порт chrome (sidebar/topbar/filterbar).")
     ($ :div {:style {:margin-top "24px"
                      :padding "12px 14px"
                      :background "#dbeafe"
                      :border-left "3px solid #1e40af"
                      :color "#1e40af"
                      :border-radius "6px"
                      :font-size "13px"}}
        ($ :strong "Live reference: ")
        ($ :a {:href "/marker-preview/Marker.html"
               :target "_blank"
               :style {:color "#1e40af"}}
           "/marker-preview/Marker.html"))))

(defonce root
  (uix.dom/create-root (js/document.getElementById "root")))

(defn ^:export init []
  (uix.dom/render-root ($ hello-marker) root))
