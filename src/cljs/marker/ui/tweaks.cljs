(ns marker.ui.tweaks
  "Tweaks panel — floating bottom-right settings panel.
   Allows toggling theme / density / sidebar / compare without
   navigating away.  Values are persisted to localStorage via
   re-frame events.  Only rendered when :marker/tweaks-open is true."
  (:require [uix.core              :refer [$ defui]]
            [re-frame.core         :as rf]
            [uix.re-frame          :refer [use-subscribe]]
            [marker.state.subs     :as subs]
            [marker.state.events   :as events]
            [marker.ui.icons       :refer [icon]]))

;; ---------------------------------------------------------------------------
;; Small button-group helper
;; ---------------------------------------------------------------------------

(defui ^:private btn-group
  "Renders a row of toggle buttons.
   :options — seq of [value label] pairs
   :current — currently selected value
   :on-pick — (fn [value])"
  [{:keys [options current on-pick]}]
  ($ :div {:style {:display "flex" :gap "4px"}}
     (for [[v label] options]
       ($ :button
          {:key      v
           :class    (str "btn btn-sm " (if (= current v) "btn-secondary" "btn-ghost"))
           :style    {:flex "1" :padding "0 6px"}
           :on-click #(on-pick v)}
          label))))

;; ---------------------------------------------------------------------------
;; Tweaks panel
;; ---------------------------------------------------------------------------

(defui tweaks-panel
  "Floating tweaks panel, bottom-right.
   Subscribe to all relevant subs internally — no props needed."
  [_]
  (let [open?     (use-subscribe [::subs/tweaks-open])
        theme     (use-subscribe [::subs/theme])
        density   (use-subscribe [::subs/density])
        collapsed (use-subscribe [::subs/sidebar-collapsed])
        compare?  (use-subscribe [::subs/compare])]
    (when open?
      ($ :div {:style {:position      "fixed"
                       :bottom        "16px"
                       :right         "16px"
                       :width         "280px"
                       :background    "var(--color-bg-surface)"
                       :border        "1px solid var(--color-border-subtle)"
                       :border-radius "10px"
                       :box-shadow    "var(--shadow-lg)"
                       ;; Above tokens.css z-stack (modal=50, popover=60,
                       ;; tooltip=70, toast=80) so the panel is reachable
                       ;; even when a Phase 6 sheet/modal is open.
                       :z-index       9000
                       :padding       "14px"
                       :display       "flex"
                       :flex-direction "column"
                       :gap           "12px"}}
         ;; Header row
         ($ :div {:style {:display "flex" :align-items "center"}}
            ($ :strong "Tweaks")
            ($ :div {:class "spacer"})
            ($ :button {:class    "icon-btn"
                        :title    "Закрыть"
                        :on-click #(rf/dispatch [::events/toggle-tweaks])}
               ($ icon {:name :x :size 14})))

         ;; Theme
         ($ :div
            ($ :div {:class "field-label"} "Тема")
            ($ btn-group {:options  [["light" "Светлая"] ["dark" "Тёмная"]]
                          :current  theme
                          :on-pick  #(rf/dispatch [::events/set-theme %])}))

         ;; Density
         ($ :div
            ($ :div {:class "field-label"} "Плотность")
            ($ btn-group {:options  [["compact"     "Компакт"]
                                      ["standard"    "Станд."]
                                      ["comfortable" "Просто."]]
                          :current  density
                          :on-pick  #(rf/dispatch [::events/set-density %])}))

         ;; Sidebar
         ($ :div
            ($ :div {:class "field-label"} "Sidebar")
            ($ btn-group {:options  [["expanded"  "Развёрн."] ["collapsed" "Свёрн."]]
                          :current  (if collapsed "collapsed" "expanded")
                          :on-pick  #(when (not= (if collapsed "collapsed" "expanded") %)
                                       (rf/dispatch [::events/toggle-sidebar]))}))

         ;; Compare mode
         ($ :div
            ($ :div {:class "field-label"} "Compare mode")
            ($ :label {:style {:display     "flex"
                               :align-items "center"
                               :gap         "6px"
                               :cursor      "pointer"
                               :font-size   "13px"}}
               ($ :input {:type      "checkbox"
                          :checked   compare?
                          :style     {:accent-color "var(--color-accent-interactive)"}
                          :on-change #(rf/dispatch [::events/set-compare (.. % -target -checked)])})
               "Сравнивать с предыдущим"))))))
