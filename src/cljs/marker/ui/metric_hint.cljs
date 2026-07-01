(ns marker.ui.metric-hint
  "Descriptor-render foundation for the 016 dashboard constructor.

   These components turn a metric DESCRIPTOR (the {:suffix :hint
   :positive-if-grow …} map the backend attaches to every metric) into UI:
     - metric-hint : the ⓘ info icon + tooltip carrying the metric's
                     formula / definition (descriptor :hint).
     - combo-cell  : a ₽-absolute + muted share-of-revenue % + coloured
                     delta cell, the standard P&L / reports value cell.
     - delta-class : the PURE up/down/flat colour selector, driven by
                     :positive-if-grow so cost-like metrics colour inverted.

   Reuses tokens.css only: [data-tip] tooltip, .delta/.delta.up/.down/.flat,
   .muted, and marker.ui.icons :info glyph."
  (:require [uix.core :refer [$ defui]]
            [clojure.string :as str]
            [marker.ui.icons :refer [icon]]
            [marker.util.format :as fmt]))

;; ---------------------------------------------------------------------------
;; delta-class — pure colour selector (exported: pnl waterfall + reports reuse).
;; ---------------------------------------------------------------------------

(defn delta-class
  "Pure helper → \"up\" / \"down\" / \"flat\" for a delta value.

   positive-if-grow=true  (revenue-like: growth is good)
     delta>0 → \"up\"   (favourable, green)
     delta<0 → \"down\" (adverse, red)
   positive-if-grow=false (cost-like: growth is bad)
     delta>0 → \"down\" (adverse, red)
     delta<0 → \"up\"   (favourable, green)
   nil / zero delta (|delta| ≤ 0.05) → \"flat\" in every case.

   The neutral band mirrors marker.ui.chrome/delta so the two agree."
  [delta positive-if-grow]
  (if (or (nil? delta) (js/isNaN delta))
    "flat"
    (let [up?   (> delta 0.05)
          down? (< delta -0.05)]
      (cond
        (not (or up? down?)) "flat"
        positive-if-grow     (if up? "up" "down")
        :else                (if up? "down" "up")))))

;; ---------------------------------------------------------------------------
;; metric-hint — ⓘ icon with a formula/definition tooltip.
;; ---------------------------------------------------------------------------

(defui metric-hint
  "An ⓘ info icon whose hover tooltip is the descriptor :hint
   (formula / definition text). Renders NOTHING when :hint is nil/blank —
   no icon, no wrapper. Uses the tokens.css [data-tip] tooltip pattern."
  [{:keys [hint]}]
  (when-not (str/blank? hint)
    ($ :span {:class    "metric-hint muted"
              :data-tip hint
              :style    {:display        "inline-flex"
                         :align-items    "center"
                         :margin-left    "4px"
                         :cursor         "help"
                         :vertical-align "middle"}}
       ($ icon {:name :info :size 13}))))

;; ---------------------------------------------------------------------------
;; combo-cell — ₽ absolute + muted share-of-revenue % + coloured delta.
;; ---------------------------------------------------------------------------

(defui combo-cell
  "Standard metric value cell.
   Props:
     :abs              number  — the absolute ruble value (₽).
     :share-pct        number  — share of revenue, rendered muted «(12,3%)».
     :delta            number  — period-over-period delta magnitude (optional).
     :delta-pct        number  — delta as a percent, shown inside the badge (optional).
     :positive-if-grow bool    — colour semantics for the delta (see delta-class).

   Renders nothing coloured when :delta is absent. nil :abs → «—»."
  [{:keys [abs share-pct delta delta-pct positive-if-grow]}]
  ($ :span {:class "combo-cell"
            :style {:display "inline-flex" :align-items "baseline" :gap "6px"}}
     ($ :span {:class "combo-abs"} (fmt/format-rub abs))
     (when-not (nil? share-pct)
       ($ :span {:class "muted combo-share" :style {:font-size "12px"}}
          "(" (fmt/format-pct share-pct) ")"))
     (when-not (or (nil? delta) (js/isNaN delta))
       (let [cls   (delta-class delta positive-if-grow)
             arrow (case cls "up" "↑" "down" "↓" "→")
             txt   (if (or (nil? delta-pct) (js/isNaN delta-pct))
                     (fmt/format-rub (js/Math.abs delta))
                     (fmt/format-pct (js/Math.abs delta-pct)))]
         ($ :span {:class (str "delta " cls)}
            arrow " " txt)))))
