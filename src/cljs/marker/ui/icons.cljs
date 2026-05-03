(ns marker.ui.icons
  "Lucide-style SVG icon set — ported from icons.jsx.
   All icons share: fill=none, stroke=currentColor, stroke-width=2,
   stroke-linecap=round, stroke-linejoin=round, viewBox 0 0 24 24.
   Use ($ icon {:name :pulse :size 16}) to render."
  (:require [uix.core :refer [$ defui]]))

;; Each entry is a seq of hiccup child elements to be placed inside the <svg>.
;; Using vectors so they compose cleanly without React fragments.
(def icon-paths
  {:home       [($ :path {:key "a" :d "M3 12l9-9 9 9"})
                ($ :path {:key "b" :d "M5 10v10h14V10"})]

   :pulse      [($ :path {:key "a" :d "M3 12h4l3-9 4 18 3-9h4"})]

   :finance    [($ :circle {:key "a" :cx 12 :cy 12 :r 9})
                ($ :path   {:key "b" :d "M9 9h4.5a2 2 0 010 4H10m0 0h3.5a2 2 0 010 4H8"})]

   :products   [($ :path {:key "a" :d "M3 7l9-4 9 4-9 4-9-4z"})
                ($ :path {:key "b" :d "M3 12l9 4 9-4"})
                ($ :path {:key "c" :d "M3 17l9 4 9-4"})]

   :warehouse  [($ :path {:key "a" :d "M3 9l9-6 9 6v12H3z"})
                ($ :path {:key "b" :d "M9 22V12h6v10"})]

   :target     [($ :circle {:key "a" :cx 12 :cy 12 :r 9})
                ($ :circle {:key "b" :cx 12 :cy 12 :r 5})
                ($ :circle {:key "c" :cx 12 :cy 12 :r 1.5})]

   :settings   [($ :circle {:key "a" :cx 12 :cy 12 :r 3})
                ($ :path   {:key "b" :d "M19.4 15a1.7 1.7 0 00.4 1.9l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.9-.4 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1a1.7 1.7 0 00-1-1.5 1.7 1.7 0 00-1.9.4l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.4-1.9 1.7 1.7 0 00-1.5-1H3a2 2 0 110-4h.1a1.7 1.7 0 001.5-1 1.7 1.7 0 00-.4-1.9l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.9.4h0a1.7 1.7 0 001-1.5V3a2 2 0 114 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.9-.4l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.4 1.9v0a1.7 1.7 0 001.5 1H21a2 2 0 110 4h-.1a1.7 1.7 0 00-1.5 1z"})]

   :sparkles   [($ :path {:key "a" :d "M12 3v18M3 12h18"})]

   :search     [($ :circle {:key "a" :cx 11 :cy 11 :r 7})
                ($ :path   {:key "b" :d "M21 21l-4.3-4.3"})]

   :refresh    [($ :path {:key "a" :d "M3 12a9 9 0 0115.5-6.3L21 8"})
                ($ :path {:key "b" :d "M21 3v5h-5"})
                ($ :path {:key "c" :d "M21 12a9 9 0 01-15.5 6.3L3 16"})
                ($ :path {:key "d" :d "M3 21v-5h5"})]

   :bell       [($ :path {:key "a" :d "M6 8a6 6 0 0112 0c0 7 3 9 3 9H3s3-2 3-9z"})
                ($ :path {:key "b" :d "M10 21a2 2 0 004 0"})]

   :user       [($ :circle {:key "a" :cx 12 :cy 8 :r 4})
                ($ :path   {:key "b" :d "M4 21a8 8 0 0116 0"})]

   :chev-down  [($ :polyline {:key "a" :points "6 9 12 15 18 9"})]

   :chev-right [($ :polyline {:key "a" :points "9 6 15 12 9 18"})]

   :plus       [($ :path {:key "a" :d "M12 5v14M5 12h14"})]

   :x          [($ :path {:key "a" :d "M18 6L6 18M6 6l12 12"})]

   :check      [($ :polyline {:key "a" :points "5 12 10 17 19 7"})]

   :arrow-up   [($ :path {:key "a" :d "M12 19V5M5 12l7-7 7 7"})]

   :arrow-down [($ :path {:key "a" :d "M12 5v14M5 12l7 7 7-7"})]

   :arrow-right [($ :path {:key "a" :d "M5 12h14M12 5l7 7-7 7"})]

   :download   [($ :path {:key "a" :d "M12 3v12M5 12l7 7 7-7"})
                ($ :path {:key "b" :d "M5 21h14"})]

   :filter     [($ :path {:key "a" :d "M3 5h18l-7 9v6l-4-2v-4z"})]

   :more-h     [($ :circle {:key "a" :cx 6  :cy 12 :r 1.5})
                ($ :circle {:key "b" :cx 12 :cy 12 :r 1.5})
                ($ :circle {:key "c" :cx 18 :cy 12 :r 1.5})]

   :more-v     [($ :circle {:key "a" :cx 12 :cy 6  :r 1.5})
                ($ :circle {:key "b" :cx 12 :cy 12 :r 1.5})
                ($ :circle {:key "c" :cx 12 :cy 18 :r 1.5})]

   :expand     [($ :path {:key "a" :d "M3 9V3h6M21 9V3h-6M3 15v6h6M21 15v6h-6"})]

   :panel      [($ :rect {:key "a" :x 3 :y 3 :width 18 :height 18 :rx 2})
                ($ :path {:key "b" :d "M9 3v18"})]

   :layout     [($ :rect {:key "a" :x 3 :y 3 :width 18 :height 18 :rx 2})
                ($ :path {:key "b" :d "M3 9h18M9 21V9"})]

   :calendar   [($ :rect {:key "a" :x 3 :y 4 :width 18 :height 18 :rx 2})
                ($ :path {:key "b" :d "M16 2v4M8 2v4M3 10h18"})]

   :sliders    [($ :path {:key "a" :d "M4 21V14M4 10V3M12 21V12M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"})]

   :layers     [($ :path {:key "a" :d "M12 2L2 7l10 5 10-5-10-5z"})
                ($ :path {:key "b" :d "M2 17l10 5 10-5M2 12l10 5 10-5"})]

   :box        [($ :path {:key "a" :d "M21 16V8l-9-5-9 5v8l9 5 9-5z"})
                ($ :path {:key "b" :d "M3.3 7L12 12l8.7-5M12 22V12"})]

   :edit       [($ :path {:key "a" :d "M11 4H4v16h16v-7"})
                ($ :path {:key "b" :d "M18.5 2.5l3 3L12 15l-4 1 1-4z"})]

   :archive    [($ :rect {:key "a" :x 3 :y 3 :width 18 :height 5 :rx 1})
                ($ :path {:key "b" :d "M5 8v12h14V8M10 12h4"})]

   :eye        [($ :path   {:key "a" :d "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"})
                ($ :circle {:key "b" :cx 12 :cy 12 :r 3})]

   :trash      [($ :path {:key "a" :d "M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14"})]

   :moon       [($ :path {:key "a" :d "M21 12.8A9 9 0 1111.2 3a7 7 0 009.8 9.8z"})]

   :sun        [($ :circle {:key "a" :cx 12 :cy 12 :r 4})
                ($ :path   {:key "b" :d "M12 2v2M12 20v2M4 12H2M22 12h-2M5 5l1.5 1.5M17.5 17.5L19 19M5 19l1.5-1.5M17.5 6.5L19 5"})]

   :info       [($ :circle {:key "a" :cx 12 :cy 12 :r 9})
                ($ :path   {:key "b" :d "M12 8v.01M11 12h1v4h1"})]

   :warning    [($ :path {:key "a" :d "M12 3l10 18H2z"})
                ($ :path {:key "b" :d "M12 10v5M12 18v.01"})]

   :danger     [($ :circle {:key "a" :cx 12 :cy 12 :r 9})
                ($ :path   {:key "b" :d "M12 7v6M12 16v.01"})]

   :success    [($ :circle   {:key "a" :cx 12 :cy 12 :r 9})
                ($ :polyline {:key "b" :points "8 12 11 15 16 9"})]

   :activity   [($ :path {:key "a" :d "M3 12h4l3-9 4 18 3-9h4"})]

   :zap        [($ :path {:key "a" :d "M13 2L3 14h7l-1 8 10-12h-7z"})]

   :save       [($ :path {:key "a" :d "M19 21H5a2 2 0 01-2-2V5a2 2 0 012-2h11l5 5v11a2 2 0 01-2 2z"})
                ($ :path {:key "b" :d "M17 21v-8H7v8M7 3v5h8"})]

   :flame      [($ :path {:key "a" :d "M12 22a7 7 0 007-7c0-3-2-6-4-7 1 4-3 6-3 9 0-2-2-3-2-3 0 2-3 4-3 6a5 5 0 005 5z"})]})

;; camelCase aliases used in chrome.jsx (chevDown / chevRight etc.)
;; kept so callers can use :chev-down or :chevDown
(def ^:private aliases
  {:chevDown  :chev-down
   :chevRight :chev-right
   :arrowUp   :arrow-up
   :arrowDown :arrow-down
   :arrowRight :arrow-right
   :moreH     :more-h
   :moreV     :more-v})

(defui icon
  "SVG icon component.
   Props: :name (keyword), :size (number, default 16), plus any SVG attrs.
   Example: ($ icon {:name :pulse :size 20})"
  [{:keys [name size class]
    :or   {size 16}
    :as   props}]
  (let [kw       (get aliases name name)
        children (get icon-paths kw)]
    ($ :svg
       (merge
        {:width            size
         :height           size
         :view-box         "0 0 24 24"
         :fill             "none"
         :stroke           "currentColor"
         :stroke-width     2
         :stroke-linecap   "round"
         :stroke-linejoin  "round"
         :class            class}
        (dissoc props :name :size :class))
       children)))
