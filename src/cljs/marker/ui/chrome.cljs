(ns marker.ui.chrome
  "App-shell chrome components — ported from chrome.jsx.
   Provides: NAV, Sidebar, Topbar, MpFilter, PeriodSelector,
             SyncBanner, Sparkline, Delta, MpBadge,
             Sheet, Modal, CmdK.
   Phase 4: :on-nav uses router/nav! for URL-driven navigation;
            topbar gains :on-tweaks prop for the tweaks panel toggle.
   Phase 6: adds Sheet, Modal, CmdK global overlays."
  (:require [clojure.string :as str]
            [uix.core :refer [$ defui use-state use-effect use-ref use-memo]]
            [marker.ui.icons :refer [icon]]
            [marker.mock     :as mock]
            [marker.api      :as api]
            [marker.state.db :as db]))

;; ============= NAV constant =============

(def NAV
  [{:id "pulse"    :label "Главная (Pulse)" :icon :pulse}
   {:id "finance"  :label "Финансы"         :icon :finance
    :children [{:id "pnl"            :label "P&L"}
               {:id "unit"           :label "Юнит-экономика"}
               {:id "report:finance" :label "Финансовый отчёт"}
               {:id "report:returns" :label "Возвраты"}]}
   {:id "products" :label "Товары"          :icon :products}
   {:id "cost-prices" :label "Себестоимость" :icon :archive}
   {:id "report:stock"  :label "Склады"     :icon :warehouse}
   {:id "reports"  :label "Отчёты"          :icon :layers
    :children [{:id "report:sales"   :label "Продажи"}
               {:id "report:abc"     :label "ABC-анализ"}
               {:id "report:buyout"  :label "Выкуп"}
               {:id "report:geo"     :label "География"}
               {:id "report:trends"  :label "Тренды"}
               {:id "report:losses"  :label "Потери"}
               {:id "report:ue"      :label "Юнит-экономика"}]}
   {:id "sync"     :label "Синхронизация" :icon :refresh}
   {:id "plan"     :label "План"    :icon :target}
   {:id "kit"      :label "UI Kit"  :icon :sparkles}])

;; ============= Sidebar =============

(defui sidebar
  "App sidebar with collapsible nav groups.
   Props: :active (string id), :on-nav (fn [id]).
   Collapse state is applied externally via data-sidebar on the wrapper."
  [{:keys [active on-nav]}]
  (let [[open-groups set-open-groups!] (use-state #{"finance"})
        toggle-group! (fn [id]
                        (set-open-groups!
                         (fn [s]
                           (if (contains? s id)
                             (disj s id)
                             (conj s id)))))]
    ($ :aside {:class "sidebar"}
       ;; Brand
       ($ :div {:class "sidebar-brand"}
          ($ :div {:class "brand-mark"})
          ($ :div {:class "brand-name"}
             "Marker"
             ($ :span {:class "dot"} ".")))
       ;; Nav
       ($ :nav {:class "sidebar-nav"}
          (for [item NAV]
            (let [item-id  (:id item)
                  children (:children item)
                  is-active (or (= active item-id)
                                (and children
                                     (some #(= active (:id %)) children)))]
              (if children
                ;; Group item
                (let [open? (contains? open-groups item-id)]
                  ($ :div {:key item-id}
                     ($ :button
                        {:class    (str "nav-item" (when is-active " active"))
                         :on-click #(toggle-group! item-id)}
                        ($ icon {:name (:icon item) :class "nav-icon"})
                        ($ :span {:class "nav-label"} (:label item))
                        ($ icon {:name  :chev-down
                                 :size  12
                                 :style {:margin-left "auto"
                                         :transform   (if open? "none" "rotate(-90deg)")
                                         :transition  "transform 150ms"}}))
                     (when open?
                       ($ :div {:class "nav-children"}
                          (for [child children]
                            ($ :button
                               {:key      (:id child)
                                :class    (str "nav-item"
                                               (when (= active (:id child)) " active"))
                                :on-click #(on-nav (:id child))}
                               ($ :span {:class "nav-label"} (:label child))))))))
                ;; Leaf item
                ($ :button
                   {:key      item-id
                    :class    (str "nav-item" (when (= active item-id) " active"))
                    :on-click #(on-nav item-id)}
                   ($ icon {:name (:icon item) :class "nav-icon"})
                   ($ :span {:class "nav-label"} (:label item))
                   (when-let [counter (:counter item)]
                     ($ :span {:class "nav-counter"} counter)))))))
       )))

;; ============= Topbar =============

(defui topbar
  "Top application bar.
   Props: :crumbs (vec of strings), :on-search (fn), :on-theme (fn),
          :theme (\"light\"|\"dark\"), :on-sidebar-toggle (fn), :on-sync (fn),
          :on-tweaks (fn) — opens the tweaks panel."
  [{:keys [crumbs on-search on-theme theme on-sidebar-toggle on-sync on-tweaks]}]
  ($ :div {:class "topbar"}
     ($ :button {:class    "icon-btn"
                 :title    "Свернуть"
                 :on-click on-sidebar-toggle}
        ($ icon {:name :panel}))
     ($ :div {:class "crumbs"}
        (map-indexed
         (fn [i c]
           ($ :span {:key i :style {:display "contents"}}
              (when (pos? i)
                ($ :span {:class "sep"} "/"))
              (if (= i (dec (count crumbs)))
                ($ :span {:class "current"} c)
                ($ :a {:href "#"} c))))
         crumbs))
     ($ :div {:class "spacer"})
     ($ :button {:class    "search-trigger"
                 :on-click on-search}
        ($ icon {:name :search :size 14})
        ($ :span "Поиск артикула или раздела…")
        ($ :kbd "⌘K"))
     ($ :button {:class    "icon-btn"
                 :title    "Запустить sync"
                 :on-click on-sync}
        ($ icon {:name :refresh}))
     ($ :button {:class "icon-btn"
                 :title "Уведомления"}
        ($ icon {:name :bell}))
     ($ :button {:class    "icon-btn"
                 :title    "Тема"
                 :on-click on-theme}
        ($ icon {:name (if (= theme "dark") :sun :moon)}))
     ($ :button {:class    "icon-btn"
                 :title    "Настройки интерфейса"
                 :on-click on-tweaks}
        ($ icon {:name :sliders}))
     ($ :div {:class "avatar"} "КМ")))

;; ============= MP Filter =============

(def ^:private mp-labels {:wb "WB" :ozon "Ozon" :ym "YM"})

(defui mp-filter
  "Marketplace filter chips — single-select.
   Either all 3 marketplaces are active (\"Все\") or exactly one is.
   Subsets are not selectable from the UI to prevent mixed-aggregate views.
   Props: :value (vec of mp keywords; either all-mps or one-element vec),
          :on-change (fn [new-vec])."
  [{:keys [value on-change]}]
  (let [all-selected? (or (zero? (count value)) (= (count value) 3))
        active-mp     (when (= (count value) 1) (first value))]
    ($ :div {:style {:display "flex" :gap "6px"}}
       ($ :button
          {:class    (str "chip" (when all-selected? " is-active"))
           :on-click #(on-change db/all-mps)}
          "Все")
       (for [mp db/all-mps]
         ($ :button
            {:key      (name mp)
             :class    (str "chip chip-mp-" (name mp)
                            (when-not (= active-mp mp) " off"))
             :on-click #(on-change [mp])}
            ($ :span {:class (str "mp-dot " (name mp))
                      :style {:width "14px" :height "14px" :font-size "8px"}}
               (-> mp name first str .toUpperCase))
            (get mp-labels mp))))))

;; ============= Period Selector =============

(def ^:private period-presets
  ["Сегодня" "Вчера" "Последние 7 дней" "Последние 30 дней"
   "Этот месяц" "Прошлый месяц" "Этот квартал" "Этот год"])

(def ^:private custom-range-re #"^\d{4}-\d{2}-\d{2},\d{4}-\d{2}-\d{2}$")

(defui period-selector
  "Period picker with popover and compare toggle.
   Props: :value (string), :on-change (fn [string]),
          :compare (bool), :on-compare (fn [bool])."
  [{:keys [value on-change compare on-compare]}]
  (let [[open? set-open!]          (use-state false)
        [custom-open? set-custom!] (use-state false)
        resolved                   (api/resolve-period value)
        [custom-from set-from!]    (use-state (or (:from resolved) ""))
        [custom-to   set-to!]      (use-state (or (:to   resolved) ""))
        ref                        (use-ref nil)
        custom-range?              (boolean (and (seq value)
                                                 (re-matches custom-range-re value)))
        display-label              (if custom-range?
                                     (or (api/format-period-range value) value)
                                     value)
        range-hint                 (when-not custom-range?
                                     (api/format-period-range value))
        apply-enabled?             (and (seq custom-from)
                                        (seq custom-to)
                                        (<= custom-from custom-to))]
    (use-effect
      (fn []
        (let [handler (fn [e]
                        (when (and @ref
                                   (not (.contains @ref (.-target e))))
                          (set-open! false)
                          (set-custom! false)))]
          (.addEventListener js/document "mousedown" handler)
          #(.removeEventListener js/document "mousedown" handler)))
      [])
    ($ :div
      {:ref   ref
       :style {:position    "relative"
               :display     "flex"
               :gap         "6px"
               :align-items "center"}}
      ;; ---- trigger button ----
      ($ :button
        {:class    "btn btn-secondary"
         :on-click #(set-open! (not open?))}
        ($ icon {:name :calendar :size 14})
        ($ :span
          display-label
          (when range-hint
            ($ :span
              {:style {:color       "var(--color-fg-muted)"
                       :margin-left "6px"
                       :font-weight 400}}
              "· " range-hint)))
        ($ icon {:name :chev-down :size 12}))
      ;; ---- compare checkbox ----
      ($ :label
        {:style {:display     "flex"
                 :align-items "center"
                 :gap         "6px"
                 :font-size   "12px"
                 :color       "var(--color-fg-secondary)"
                 :cursor      "pointer"}}
        ($ :input
          {:type      "checkbox"
           :checked   compare
           :style     {:accent-color "var(--color-accent-interactive)"}
           :on-change #(on-compare (.. % -target -checked))})
        "Сравнить с пред.")
      ;; ---- popover ----
      (when open?
        ($ :div
          {:class "popover"
           :style {:top        "100%"
                   :margin-top "4px"
                   :left       0
                   :min-width  "260px"}}
          ;; preset list
          (for [p period-presets]
            ($ :button
              {:key      p
               :class    (str "popover-item" (when (= p value) " is-active"))
               :on-click (fn []
                           (on-change p)
                           (set-open! false)
                           (set-custom! false))}
              (when (= p value)
                ($ icon {:name :check :size 12}))
              ($ :span {:style {:margin-left (if (= p value) "0" "18px")}} p)))
          ($ :div {:class "popover-divider"})
          ;; custom range section
          (if custom-open?
            ;; inline form
            ($ :div
              {:style {:padding        "8px 12px"
                       :display        "flex"
                       :flex-direction "column"
                       :gap            "8px"}}
              ($ :div
                {:style {:display               "grid"
                         :grid-template-columns "32px 1fr"
                         :align-items           "center"
                         :gap                   "6px"}}
                ($ :span
                  {:style {:font-size "12px"
                            :color     "var(--color-fg-secondary)"}}
                  "С")
                ($ :input
                  {:type      "date"
                   :class     "input"
                   :value     custom-from
                   :on-change #(set-from! (.. % -target -value))})
                ($ :span
                  {:style {:font-size "12px"
                            :color     "var(--color-fg-secondary)"}}
                  "По")
                ($ :input
                  {:type      "date"
                   :class     "input"
                   :value     custom-to
                   :on-change #(set-to! (.. % -target -value))}))
              ($ :div
                {:style {:display "flex" :gap "6px"}}
                ($ :button
                  {:class    (str "btn btn-primary"
                                  (when-not apply-enabled? " btn-disabled"))
                   :disabled (not apply-enabled?)
                   :on-click (fn []
                               (when apply-enabled?
                                 (on-change (str custom-from "," custom-to))
                                 (set-open! false)
                                 (set-custom! false)))}
                  "Применить")
                ($ :button
                  {:class    "btn btn-secondary"
                   :on-click #(set-custom! false)}
                  "Отмена")))
            ;; toggle button
            ($ :button
              {:class    "popover-item"
               :on-click (fn []
                           (let [r (api/resolve-period value)]
                             (set-from! (or (:from r) ""))
                             (set-to!   (or (:to   r) "")))
                           (set-custom! true))}
              ($ :span {:style {:margin-left "18px"}} "Свой диапазон…"))))))))

;; ============= Sync Banner =============

(defui sync-banner
  "Banner showing sync progress or success.
   Props: :state (nil | {:kind :running :section str :elapsed str :progress num}
                       | {:kind :success :time str}),
          :on-close (fn)."
  [{:keys [state on-close]}]
  (when state
    (if (= (:kind state) :success)
      ($ :div {:class "sync-banner success"}
         ($ icon {:name :check :size 16})
         ($ :span
            ($ :strong "Готово.")
            " Данные обновлены " (:time state))
         ($ :div {:class "spacer"})
         ($ :button {:class    "icon-btn"
                     :style    {:color "inherit"}
                     :on-click on-close}
            ($ icon {:name :x :size 14})))
      ($ :div {:class "sync-banner"}
         ($ :div {:class "sync-spin"})
         ($ :span
            ($ :strong (str "Обновление " (:section state)))
            " — " (:elapsed state))
         ($ :div {:class "sync-progress"}
            ($ :div {:style {:width (str (:progress state) "%")}}))
         ($ :span {:class "mono"
                   :style {:min-width  "36px"
                           :text-align "right"}}
            (str (:progress state) "%"))))))

;; ============= Sparkline =============

(defui sparkline
  "Inline SVG sparkline with last-point dot.
   Props: :data (vec of numbers), :width (default 80), :height (default 28),
          :color (optional CSS color string)."
  [{:keys [data width height color]
    :or   {width 80 height 28}}]
  (when (seq data)
    (let [mx    (apply max data)
          mn    (apply min data)
          range (if (= mx mn) 1 (- mx mn))
          trend (>= (last data) (first data))
          c     (or color
                    (if trend
                      "var(--color-delta-positive)"
                      "var(--color-delta-negative)"))
          n     (count data)
          pts   (map-indexed
                 (fn [i v]
                   (let [x (+ 2 (* (/ i (dec n)) (- width 4)))
                         y (- height 2 (* (/ (- v mn) range) (- height 4)))]
                     (str x "," y)))
                 data)
          [lx ly] (-> pts last (str/split #","))]
      ($ :svg {:width  width
               :height height
               :style  {:display "block"}}
         ($ :polyline {:points         (str/join " " pts)
                       :fill           "none"
                       :stroke         c
                       :stroke-width   "1.5"
                       :stroke-linecap "round"
                       :stroke-linejoin "round"})
         ($ :circle {:cx   lx
                     :cy   ly
                     :r    "2.5"
                     :fill c})))))

;; ============= Delta =============

(defui delta
  "Colored arrow + percentage delta.
   Props: :pct (number, e.g. 5.2 for +5.2%), :inverted (bool),
          :suffix (string appended after %)."
  [{:keys [pct inverted suffix]
    :or   {inverted false suffix ""}}]
  (if (or (nil? pct) (js/isNaN pct))
    ($ :span {:class "delta flat"} "—")
    (let [up?   (> pct 0.05)
          down? (< pct -0.05)
          dir   (cond up? "up" down? "down" :else "flat")
          cls   (if inverted
                  (cond up? "down" down? "up" :else "flat")
                  dir)
          arrow (cond up? "↑" down? "↓" :else "→")]
      ($ :span {:class (str "delta " cls)}
         arrow " "
         (-> (js/Math.abs pct) (.toFixed 1) (.replace "." ","))
         "%" suffix))))

;; ============= KPI Card =============

(defui kpi-card
  "KPI metric card with optional sparkline and delta indicator.
   Props: :label (string), :value (string), :delta-pct (number, optional),
          :sub (string, optional secondary label), :spark (vec of numbers, optional),
          :compare? (bool, default false), :inverted? (bool, default false),
          :badge (string, optional — small inline tag next to label, e.g. '≈')."
  [{:keys [label value delta-pct sub spark compare? inverted? badge]}]
  ($ :div {:class "kpi" :style {:position "relative"}}
     ($ :div {:class "kpi-label"}
        label
        (when badge
          ($ :span {:class "tag tag-sm tag-info"
                    :title "Предварительное значение — будет уточнено"
                    :style {:margin-left "6px"
                            :font-size "10px"
                            :font-family "var(--font-mono)"}}
             badge)))
     ($ :div {:class "kpi-value"} value)
     ($ :div {:class "kpi-foot"}
        (when compare?
          ($ delta {:pct delta-pct :inverted inverted?}))
        (when compare?
          ($ :span {:style {:color "var(--color-fg-muted)"}} sub))
        (when-not compare?
          ($ :span {:style {:color "var(--color-fg-muted)"}}
             (when (= label "Выручка") "за 30 дней"))))
     (when spark
       ($ :div {:style {:position "absolute" :right 14 :top 14}}
          ($ sparkline {:data spark :width 72 :height 26})))))

;; ============= MP Badge =============

(defui mp-badge
  "Inline marketplace badge dot.
   Props: :mp (keyword :wb | :ozon | :ym)."
  [{:keys [mp]}]
  ($ :span {:class (str "mp-dot " (name mp))}
     (case mp :wb "W" :ozon "O" :ym "Y" "?")))

;; ============= Sheet =============

(defui sheet
  "Fixed-position right slide-in panel.
   Props: :open? (bool), :on-close (fn), :children (ReactNode).
   Escape key calls on-close."
  [{:keys [open? on-close children]}]
  (use-effect
   (fn []
     (when open?
       (let [on-key (fn [e]
                      (when (= (.-key e) "Escape")
                        (on-close)))]
         (.addEventListener js/document "keydown" on-key)
         #(.removeEventListener js/document "keydown" on-key))))
   [open? on-close])
  ($ :<>
     ($ :div {:class    (str "sheet-backdrop" (when open? " open"))
              :on-click on-close})
     ($ :div {:class (str "sheet" (when open? " open"))}
        (when open? children))))

;; ============= Modal =============

(defui modal
  "Centered dialog overlay.
   Props: :open? (bool), :on-close (fn), :children (ReactNode).
   Escape key calls on-close; click on backdrop calls on-close."
  [{:keys [open? on-close children]}]
  (use-effect
   (fn []
     (when open?
       (let [on-key (fn [e]
                      (when (= (.-key e) "Escape")
                        (on-close)))]
         (.addEventListener js/document "keydown" on-key)
         #(.removeEventListener js/document "keydown" on-key))))
   [open? on-close])
  ($ :div {:class    (str "modal-backdrop" (when open? " open"))
           :on-click on-close}
     ($ :div {:class    "modal"
              :on-click #(.stopPropagation %)}
        (when open? children))))

;; ============= CmdK =============

(def ^:private nav-items-flat
  "Flattened NAV for search — parent items + children."
  (into []
        (mapcat (fn [n]
                  (if (:children n)
                    (cons n (:children n))
                    [n])))
        NAV))

(defui cmdk
  "Cmd+K command palette.
   Props: :open? (bool), :on-close (fn), :on-nav (fn [page-id]).
   Searches SKUs by id/name and nav items by label.
   Arrow keys navigate, Enter selects, Escape closes."
  [{:keys [open? on-close on-nav]}]
  (let [input-ref            (use-ref nil)
        [q set-q!]           (use-state "")
        [sel-idx set-sel-idx!] (use-state 0)

        results (use-memo
                 (fn []
                   (let [ql (.toLowerCase q)]
                     {:sku-matches  (if (seq ql)
                                      (->> mock/skus
                                           (filter #(or (.includes (.toLowerCase (:id %)) ql)
                                                        (.includes (.toLowerCase (:name %)) ql)))
                                           (take 6)
                                           vec)
                                      (vec (take 6 mock/skus)))
                      :nav-matches  (if (seq ql)
                                      (->> nav-items-flat
                                           (filter #(.includes (.toLowerCase (:label %)) ql))
                                           (take 4)
                                           vec)
                                      (vec nav-items-flat))}))
                 [q])

        all-items (into (:nav-matches results) (:sku-matches results))

        ;; Focus input when opened; reset query on close
        _ (use-effect
           (fn []
             (if open?
               (js/setTimeout #(when @input-ref (.focus @input-ref)) 50)
               (do (set-q! "") (set-sel-idx! 0)))
             js/undefined)
           [open?])

        on-key-down (fn [e]
                      (let [k (.-key e)]
                        (cond
                          (= k "ArrowDown")
                          (do (.preventDefault e)
                              (set-sel-idx! #(mod (inc %) (max 1 (count all-items)))))

                          (= k "ArrowUp")
                          (do (.preventDefault e)
                              (set-sel-idx! #(mod (dec %) (max 1 (count all-items)))))

                          (= k "Enter")
                          (when-let [item (nth all-items sel-idx nil)]
                            (.preventDefault e)
                            ;; nav items have :id, sku items have :id too — disambiguate by :mp
                            (if (:mp item)
                              (do (on-nav "products") (on-close))
                              (do (on-nav (:id item)) (on-close))))

                          :else nil)))]

    ($ modal {:open? open? :on-close on-close}
       ($ :div {:class "cmdk"}
          ;; Search row
          ($ :div {:style {:display      "flex"
                           :align-items  "center"
                           :gap          "10px"
                           :padding      "0 12px"
                           :border-bottom "1px solid var(--color-border-subtle)"}}
             ($ icon {:name :search :size 16
                      :style {:color "var(--color-fg-muted)"}})
             ($ :input {:ref          input-ref
                        :class        "cmdk-input"
                        :placeholder  "Поиск артикула, страницы, действия…"
                        :value        q
                        :on-change    #(do (set-q! (.. % -target -value))
                                           (set-sel-idx! 0))
                        :on-key-down  on-key-down})
             ($ :kbd {:class "kbd"} "esc"))

          ;; Results
          ($ :div {:class "cmdk-results"}
             ;; Nav section
             (when (seq (:nav-matches results))
               ($ :<>
                  ($ :div {:class "cmdk-section-title"} "Навигация")
                  (map-indexed
                   (fn [i n]
                     ($ :div {:key      (:id n)
                              :class    (str "cmdk-item" (when (= i sel-idx) " is-selected"))
                              :on-click (fn [] (on-nav (:id n)) (on-close))}
                        ($ icon {:name  (or (:icon n) :arrow-right)
                                 :size  14
                                 :style {:color "var(--color-fg-muted)"}})
                        (:label n)))
                   (:nav-matches results))))

             ;; SKUs section
             ($ :div {:class "cmdk-section-title"} "Артикулы")
             (map-indexed
              (fn [i s]
                (let [abs-i (+ (count (:nav-matches results)) i)]
                  ($ :div {:key      (:id s)
                           :class    (str "cmdk-item" (when (= abs-i sel-idx) " is-selected"))
                           :on-click (fn [] (on-nav "products") (on-close))}
                     ($ :span {:class "mono"
                               :style {:color     "var(--color-fg-muted)"
                                       :min-width "70px"}}
                        (:id s))
                     ($ :span {:style {:flex 1}} (:name s))
                     ($ :div {:style {:display "flex" :gap "4px"}}
                        (for [m (:mp s)]
                          ($ :span {:key   (name m)
                                    :class (str "mp-dot " (name m))}
                             (-> m name first str .toUpperCase)))))))
              (:sku-matches results)))

          ;; Footer hints
          ($ :div {:class "cmdk-foot"}
             ($ :span ($ :kbd {:class "kbd"} "↑↓") " навигация")
             ($ :span ($ :kbd {:class "kbd"} "↵") " открыть")
             ($ :span ($ :kbd {:class "kbd"} "esc") " закрыть"))))))

