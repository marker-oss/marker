(ns marker.mock
  "Deterministic mock data — ports data.js 1:1.
   Uses a pure LCG (same constants as JS: s = (s * 9301 + 49297) % 233280)
   wrapped in a stateful atom so callers can obtain successive values.
   All top-level vars are computed once at load time and are frozen.")

;; ---------------------------------------------------------------------------
;; Seeded random
;; ---------------------------------------------------------------------------

(defn- make-rand
  "Returns a zero-argument fn that each call advances the LCG state and
   returns a float in [0, 1).  Seed must be an integer."
  [seed]
  (let [s (atom seed)]
    (fn []
      (swap! s #(mod (+ (* % 9301) 49297) 233280))
      (/ @s 233280.0))))

;; ---------------------------------------------------------------------------
;; Series generator (mirrors genSeries in data.js)
;; ---------------------------------------------------------------------------

(defn- gen-series
  "Generate a 30-element daily series seeded at `seed`, starting near `base`,
   with volatility `vol`.  Floors each value at base * 0.4."
  [seed base vol]
  (let [r   (make-rand seed)
        floor (* base 0.4)]
    (loop [i   0
           v   (double base)
           out []]
      (if (= i 30)
        out
        (let [v' (max floor (+ v (* (- (r) 0.45) vol)))]
          (recur (inc i) v' (conj out v')))))))

;; ---------------------------------------------------------------------------
;; Top-level series
;; ---------------------------------------------------------------------------

(def revenue-series (gen-series 1 280000 60000))
(def profit-series  (gen-series 2 78000  22000))
(def orders-series  (gen-series 3 320    80))
(def ads-series     (gen-series 4 32000  9000))

;; ---------------------------------------------------------------------------
;; Marketplaces
;; ---------------------------------------------------------------------------

(def marketplaces [:wb :ozon :ym])

(def mp-label {:wb "Wildberries" :ozon "Ozon" :ym "YM"})

;; ---------------------------------------------------------------------------
;; SKU generator (mirrors genSku in data.js)
;; ---------------------------------------------------------------------------

(defn- gen-sku
  "Generate one SKU map for index i.  Uses seed (i*7+11) — identical to JS."
  [i]
  (let [r         (make-rand (+ (* i 7) 11))
        thresholds [0.05 0.4 0.65]
        ;; Consume the three r() calls for MP selection upfront
        mp-rolls  [(r) (r) (r)]
        mps       (filterv identity
                           (map-indexed
                            (fn [idx mp]
                              (when (> (nth mp-rolls idx) (nth thresholds idx))
                                mp))
                            [:wb :ozon :ym]))
        mps       (if (empty? mps) [:wb] mps)
        revenue   (js/Math.round (+ 40000 (* (r) 400000)))
        orders    (js/Math.round (+ 20 (* (r) 240)))
        margin    (+ 0.12 (* (r) 0.32))
        buyout    (+ 0.55 (* (r) 0.4))
        stock     (js/Math.round (* (r) 800))
        delta-pct (* (- (r) 0.45) 80)
        ads-cost  (js/Math.round (* revenue (+ 0.06 (* (r) 0.18))))
        roas      (/ revenue (max 1 ads-cost))
        spark     (gen-series (* i 13) (/ revenue 30) (/ revenue 80))
        plan      (js/Math.round (* revenue (+ 0.85 (* (r) 0.4))))]
    {:id        (str "SKU-" (+ 1200 i))
     :name      (str "Артикул " (+ 1200 i))
     :mp        mps
     :revenue   revenue
     :orders    orders
     :margin    margin
     :buyout    buyout
     :stock     stock
     :delta-pct delta-pct
     :ads-cost  ads-cost
     :roas      roas
     :spark     spark
     :plan      plan}))

(def skus
  "Vector of 32 SKU maps — deterministic, seeded."
  (mapv gen-sku (range 32)))

;; ---------------------------------------------------------------------------
;; P&L rows
;; ---------------------------------------------------------------------------

(def pnl-rows
  [{:key "revenue"    :label "Выручка"          :cur  8420000 :prev  7510000 :group "income"}
   {:key "cogs"       :label "Себестоимость"     :cur -3380000 :prev -3010000 :group "cost"     :muted true}
   {:key "gross"      :label "Валовая прибыль"   :cur  5040000 :prev  4500000 :group "subtotal"}
   {:key "commission" :label "Комиссия МП"       :cur -1430000 :prev -1280000 :group "cost"}
   {:key "logistics"  :label "Логистика и FBO"   :cur  -680000 :prev  -610000 :group "cost"}
   {:key "returns"    :label "Возвраты"           :cur  -310000 :prev  -290000 :group "cost"}
   {:key "ads"        :label "Реклама"            :cur  -940000 :prev  -780000 :group "cost"}
   {:key "opex"       :label "Прочие расходы"     :cur  -210000 :prev  -180000 :group "cost"}
   {:key "ebitda"     :label "EBITDA"             :cur  1470000 :prev  1360000 :group "subtotal"}
   {:key "taxes"      :label "Налоги"             :cur  -310000 :prev  -290000 :group "cost"}
   {:key "net"        :label "Чистая прибыль"     :cur  1160000 :prev  1070000 :group "total"}])

;; ---------------------------------------------------------------------------
;; Alerts
;; ---------------------------------------------------------------------------

(def alerts
  [{:kind "danger"
    :title "Маржа упала ниже 18%"
    :body  "SKU-1208 «Артикул 1208» — маржа 12,4% (было 24,1%) из-за роста CPC на WB."
    :cta   "Открыть SKU"}
   {:kind "warning"
    :title "Остатки на исходе"
    :body  "6 артикулов на FBO Ozon кончатся за ≤ 7 дней по текущей оборачиваемости."
    :cta   "Перейти к остаткам"}
   {:kind "info"
    :title "Sync YM не запускался 26 часов"
    :body  "Данные YM могут отставать. Последняя успешная — вчера в 22:00."
    :cta   "Запустить sync"}])

;; ---------------------------------------------------------------------------
;; Forecast
;; ---------------------------------------------------------------------------

(def forecast
  {:month-plan   12000000
   :month-fact    8420000
   ;; Projected month-end revenue at current pace (matches data.js dummy:
   ;; fact / day-of-month-fraction). Kept for shape parity with the JS
   ;; prototype's monthPace field even though no UI reads it yet.
   :month-pace   (* (/ 8420000 (+ (/ 3 30) (/ 22 30))) (/ 30 22))
   :projection  11480000})

;; ---------------------------------------------------------------------------
;; Top movers / fallers — derived from skus, sorted by delta-pct
;; ---------------------------------------------------------------------------

(def top-movers
  "5 SKUs with highest delta-pct (best performers)."
  (->> skus
       (sort-by :delta-pct >)
       (take 5)
       vec))

(def top-fallers
  "5 SKUs with lowest delta-pct (worst performers)."
  (->> skus
       (sort-by :delta-pct <)
       (take 5)
       vec))
