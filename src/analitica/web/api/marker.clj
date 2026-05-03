(ns analitica.web.api.marker
  "Marker SPA — Transit-JSON backend endpoints.

   Endpoints under /api/v1/marker/*:
     GET  pulse-summary       — dashboard KPIs, alerts, top-movers, critical-stocks
     GET  pnl                 — P&L rows + per-SKU breakdown
     GET  sku-list            — paginated SKU list with metrics
     GET  sku-detail/:id      — per-SKU detail
     POST what-if-recalc      — pure unit-econ what-if calculation
     GET  reports/:type       — generic schema-driven report data (Phase 9)
     GET  reports/:type/article/:article — drill-down for one article

   Every handler returns a plain Clojure map; the wrap-transit-response
   middleware (registered in server.clj) encodes it as transit-json when the
   client sends Accept: application/transit+json."
  (:require [analitica.domain.finance     :as finance]
            [analitica.domain.pnl         :as pnl]
            [analitica.domain.preliminary :as prelim]
            [analitica.domain.sales       :as sales]
            [analitica.domain.stock       :as stock]
            [analitica.domain.buyout      :as buyout]
            [analitica.alerts             :as alerts]
            [analitica.util.period        :as period]
            [analitica.util.math          :as math]
            [analitica.db                 :as db]
            [analitica.web.api.report     :as report]
            [analitica.web.report-schemas :as rs]
            [clojure.string               :as str]))

;; ---------------------------------------------------------------------------
;; Parameter parsing helpers
;; ---------------------------------------------------------------------------

(defn- default-period
  "Return {:from iso :to iso} for the last 30 days."
  []
  (let [[from to] (period/resolve-preset :last-30-days)]
    {:from (period/format-date from)
     :to   (period/format-date to)}))

(defn- parse-period-params
  "Extract {:from iso :to iso} from query params. Falls back to last 30 days."
  [params]
  (let [from (get params :from)
        to   (get params :to)]
    (if (and (seq from) (seq to))
      {:from from :to to}
      (default-period))))

(defn- parse-mp-param
  "Parse ?mp=wb,ozon,ym into a vector of keywords. nil/empty → nil (all MPs)."
  [params]
  (let [mp-str (get params :mp)]
    (when (and mp-str (seq mp-str))
      (let [kws (->> (str/split mp-str #",")
                     (map str/trim)
                     (filter #(#{"wb" "ozon" "ym"} %))
                     (mapv keyword))]
        (when (seq kws) kws)))))

(defn- compare? [params]
  (= "true" (get params :compare)))

;; ---------------------------------------------------------------------------
;; Shared data-loading helpers
;; ---------------------------------------------------------------------------

(defn- load-finance [period-map marketplace]
  (try
    (finance/fetch-finance period-map :marketplace marketplace)
    (catch Exception _ [])))

(defn- load-sales [period-map marketplace]
  (try
    (sales/fetch-sales period-map :marketplace marketplace)
    (catch Exception _ [])))

(defn- compute-pnl
  "Compute P&L for fin-data over period-map.
   Accepts an optional cf-adjustments map (from pnl/load-cf-adjustments);
   when supplied, pnl/calculate adds the P&L.6 cf-* / adjusted-* fields."
  ([fin-data period-map marketplace]
   (compute-pnl fin-data period-map marketplace nil))
  ([fin-data period-map marketplace cf-adjustments]
   (try
     (apply pnl/calculate
            fin-data
            (cond-> [:marketplace marketplace
                     :from        (:from period-map)
                     :to          (:to  period-map)]
              cf-adjustments (conj :cf-adjustments cf-adjustments)))
     (catch Exception _
       {:revenue 0.0 :net-profit 0.0 :gross-profit 0.0 :margin-net 0.0
        :ad-spend 0.0 :cogs 0.0 :logistics 0.0 :for-pay 0.0
        :sales-qty 0 :returns-qty 0 :buyout-rate 0.0 :avg-check 0.0}))))

(defn- with-prelim
  "Apply Ozon preliminary overlay to a pnl-result.
   - mp=:ozon  → maybe-overlay-preliminary (swaps revenue if canonical=0)
   - mp=nil    → all-MP: when Ozon's canonical contribution is zero, ADD
                 the Ozon preliminary revenue on top of WB+YM. Marks
                 result with :preliminary? so the UI can show a badge.
   - mp=:wb/:ym→ no overlay; canonical only.
   period-map is the {:from :to} map already used for compute-pnl."
  [pnl-result period-map mp]
  (cond
    (= :ozon mp)
    (prelim/maybe-overlay-preliminary
      pnl-result {:period period-map :marketplace :ozon})

    (nil? mp)
    (let [ozon-fin    (try (finance/fetch-finance period-map :marketplace :ozon)
                           (catch Exception _ []))
          ozon-pnl    (compute-pnl ozon-fin period-map :ozon)
          ozon-canon  (or (:revenue ozon-pnl) 0.0)]
      (if (zero? ozon-canon)
        (if-let [p (prelim/ozon-preliminary-totals period-map)]
          (-> pnl-result
              (update :revenue + (:revenue p))
              (assoc :preliminary?      true
                     :preliminary-as-of (:as-of p)))
          pnl-result)
        pnl-result))

    :else
    pnl-result))

;; ---------------------------------------------------------------------------
;; Spark-line helpers
;; ---------------------------------------------------------------------------

(defn- sales-by-day-map
  "Return {date-str -> revenue} for sales data."
  [sales-data]
  (->> sales-data
       (filter #(= :sale (:type %)))
       (group-by (fn [r]
                   (let [d (or (:date r) "")]
                     (if (>= (count d) 10) (subs d 0 10) d))))
       (into {} (map (fn [[d rows]]
                       [d (reduce + 0.0
                                  (map (fn [r]
                                         (or (:for-pay r) (:price-with-disc r)
                                             (:finished-price r) (:total-price r) 0))
                                       rows))])))))

(defn- date-range-seq
  "Return a seq of YYYY-MM-DD strings from `from` to `to` (inclusive)."
  [from to]
  (let [from-d (period/parse-date from)
        to-d   (period/parse-date to)
        days   (inc (.until from-d to-d java.time.temporal.ChronoUnit/DAYS))]
    (mapv (fn [i] (period/format-date (.plusDays from-d i)))
          (range days))))

(defn- revenue-spark
  "30-element daily revenue list for the period."
  [sales-data from to]
  (let [by-day (sales-by-day-map sales-data)
        dates  (date-range-seq from to)]
    (mapv #(double (get by-day % 0.0)) dates)))

(defn- orders-spark
  "30-element daily order count list for the period."
  [sales-data from to]
  (let [by-day (->> sales-data
                    (filter #(= :sale (:type %)))
                    (group-by (fn [r]
                                (let [d (or (:date r) "")]
                                  (if (>= (count d) 10) (subs d 0 10) d))))
                    (into {} (map (fn [[d rows]] [d (count rows)]))))
        dates  (date-range-seq from to)]
    (mapv #(long (get by-day % 0)) dates)))

;; ---------------------------------------------------------------------------
;; previous-period helper
;; ---------------------------------------------------------------------------

(defn- prev-period
  "Return {:from :to} for the same-length window immediately before `period`."
  [{:keys [from to]}]
  (let [from-d (period/parse-date from)
        to-d   (period/parse-date to)
        days   (period/days-between from to)
        p-to   (.minusDays from-d 1)
        p-from (.minusDays p-to (dec days))]
    {:from (period/format-date p-from)
     :to   (period/format-date p-to)}))

;; ---------------------------------------------------------------------------
;; B1. pulse-summary
;; ---------------------------------------------------------------------------

(defn- build-kpi
  [cur-val prev-val spark]
  {:value     (or cur-val 0.0)
   :delta-pct (math/pct-delta (or cur-val 0.0) (or prev-val 0.0))
   :spark     (or spark [])})

(defn- build-alerts
  "Adapt analitica.alerts/detect-alerts output to SPA shape."
  [stocks-wt cur-sales-by-art prev-sales-by-art cur-pnl prev-pnl cur-buyout
   sales-last-3d top-10]
  (try
    (let [raw (alerts/detect-alerts
                {:stocks-with-turnover     stocks-wt
                 :current-sales-by-article cur-sales-by-art
                 :prev-sales-by-article    prev-sales-by-art
                 :current-pnl              cur-pnl
                 :prev-pnl                 prev-pnl
                 :current-buyout           cur-buyout
                 :sales-last-3-days        sales-last-3d
                 :top-10-by-revenue        top-10})]
      ;; TODO upstream: analitica.alerts/detect-alerts emits "null" literals
      ;; when an article's :name and :article are both nil. Filter here until
      ;; the source is fixed.
      (->> raw
           (remove (fn [a]
                     (or (re-find #"\bnull\b" (str (:title a)))
                         (re-find #"\bnull\b" (str (:body a))))))
           (mapv (fn [a]
                   {:kind  (case (:severity a)
                              :red    "danger"
                              :yellow "warning"
                              "info")
                    :title (:title a)
                    :body  (:body a)
                    :cta   (:action-label a)}))))
    (catch Exception _ [])))

(defn- critical-stocks
  "Build critical-stocks list (up to 7 items) from stock-with-turnover rows."
  [stocks-wt]
  (->> (or stocks-wt [])
       (filter (fn [s]
                 (let [dl (or (:days-left s) (:days-of-cover s))]
                   (and (some? dl) (< dl 30)))))
       (sort-by (fn [s] (or (:days-left s) (:days-of-cover s))))
       (take 7)
       (mapv (fn [s]
               (let [dl    (or (:days-left s) (:days-of-cover s) 0)
                     speed (math/round2 (or (:daily-rate s) (:avg-daily-sales s) 0))]
                 {:id     (str (:article s))
                  :name   (or (:subject s) (:article s))
                  :mp     [(keyword (or (:marketplace s) :wb))]
                  :stock  (or (:quantity-full s) (:quantity s) 0)
                  :speed  (int (Math/round (double (or speed 0))))
                  :days   (int (Math/round (double dl)))
                  :status (cond (< dl 7)  "danger"
                                (< dl 14) "warning"
                                :else     "success")})))))

(defn- top-movers-fallers
  "Top 5 movers and fallers from current vs prev sales by article."
  [cur-by-art prev-by-art period-from period-to sales-data]
  (let [prev-map  (->> prev-by-art
                       (map (fn [r] [(or (:group r) (:article r)) (:revenue r)]))
                       (into {}))
        dates      (date-range-seq period-from period-to)
        spark-fn   (fn [art]
                     ;; Per-article daily revenue spark
                     (let [art-sales (->> sales-data
                                          (filter #(and (= :sale (:type %))
                                                        (= art (or (:article %) "")))))]
                       (let [art-by-day (->> art-sales
                                             (group-by (fn [r]
                                                         (let [d (or (:date r) "")]
                                                           (if (>= (count d) 10) (subs d 0 10) d))))
                                             (into {} (map (fn [[d rows]]
                                                             [d (reduce + 0.0
                                                                        (map (fn [r]
                                                                               (or (:for-pay r) (:price-with-disc r)
                                                                                   (:finished-price r) (:total-price r) 0))
                                                                             rows))]))))]
                         (mapv #(double (get art-by-day % 0.0)) dates))))
        enrich     (fn [r]
                     (let [art       (or (:group r) (:article r))
                           cur-rev   (or (:revenue r) 0.0)
                           prev-rev  (or (get prev-map art) 0.0)]
                       {:id        (str art)
                        :name      (or (:subject r) art)
                        :mp        [:wb]        ; TODO: derive from finance rows when available
                        :revenue   cur-rev
                        :delta-pct (math/pct-delta cur-rev prev-rev)
                        :spark     (spark-fn art)}))
        enriched   (->> cur-by-art (map enrich))
        movers     (->> enriched
                        (filter #(pos? (:delta-pct %)))
                        (sort-by :delta-pct >)
                        (take 5)
                        vec)
        fallers    (->> enriched
                        (filter #(neg? (:delta-pct %)))
                        (sort-by :delta-pct)
                        (take 5)
                        vec)]
    [movers fallers]))

(defn pulse-summary
  "Handler for GET /api/v1/marker/pulse-summary"
  [request]
  (try
    (let [params     (:params request)
          period     (parse-period-params params)
          mps        (parse-mp-param params)
          do-compare (compare? params)
          from       (:from period)
          to         (:to   period)
          prev       (prev-period period)

          ;; single MP or nil (all)
          mp1        (when (and mps (= 1 (count mps))) (first mps))

          ;; Current period data
          fin-cur    (load-finance period mp1)
          sales-cur  (load-sales  period mp1)
          pnl-cur    (-> (compute-pnl fin-cur period mp1)
                         (with-prelim period mp1))

          ;; Previous period
          fin-prev   (load-finance prev mp1)
          sales-prev (load-sales  prev mp1)
          pnl-prev   (-> (compute-pnl fin-prev prev mp1)
                         (with-prelim prev mp1))

          ;; Sales aggregates
          cur-by-art  (sales/by-article sales-cur)
          prev-by-art (sales/by-article sales-prev)
          sales-tots  (sales/totals sales-cur)
          prev-tots   (sales/totals sales-prev)

          ;; Stock data (all MPs — stocks are snapshot, not period-filtered)
          stocks-raw  (try (stock/fetch-stocks :marketplace mp1) (catch Exception _ []))
          stocks-wt   (try (stock/with-turnover
                             (stock/by-article stocks-raw)
                             sales-cur
                             (period/days-between from to))
                           (catch Exception _ []))

          ;; Buyout
          buyout-cur  (try (buyout/analyze period :marketplace mp1) (catch Exception _ []))

          ;; Last-3-days sales for ZERO_SALES alert
          today-d     (java.time.LocalDate/now)
          d3-from     (period/format-date (.minusDays today-d 2))
          d3-to       (period/format-date today-d)
          sales-3d    (load-sales {:from d3-from :to d3-to} mp1)

          ;; Top-10 by revenue for ZERO_SALES alert — sorted descending so the
          ;; highest-revenue SKUs are checked first (sales/by-article order is unspecified).
          top-10      (->> cur-by-art
                           (sort-by :revenue >)
                           (take 10)
                           (map-indexed (fn [i r] (assoc r :rank (inc i))))
                           vec)

          ;; Sparks
          rev-spark   (revenue-spark sales-cur from to)
          ord-spark   (orders-spark  sales-cur from to)

          ;; Movers/fallers
          [movers fallers] (top-movers-fallers cur-by-art prev-by-art from to sales-cur)

          ;; Alerts
          alert-list  (build-alerts stocks-wt cur-by-art prev-by-art pnl-cur pnl-prev
                                    buyout-cur sales-3d top-10)

          ;; Forecast — use plan domain if available, else simple run-rate
          ;; TODO: wire to domain.plan/run-rate once monthly plans exist in DB
          days-total  (period/days-between from to)
          days-so-far (let [today-str (period/format-date today-d)]
                        (if (<= (compare today-str to) 0)
                          (period/days-between from (if (<= (compare today-str to) 0) today-str to))
                          days-total))
          ;; Velocity uses sales-table revenue (same source as the Pulse
          ;; revenue KPI) so Ozon's pre-realization period still produces
          ;; a meaningful projection.
          velocity    (if (pos? days-so-far)
                        (/ (double (or (:revenue sales-tots) 0.0)) days-so-far)
                        0.0)
          projection  (math/round2 (* velocity days-total))

          ;; Fetch all-MP sales once; reused by both mp-share and orders-by-mp below.
          all-sales-mp (when-not mp1 (load-sales period nil))

          ;; MP share (orders by marketplace when no MP filter)
          mp-share    (when-not mp1
                        (let [by-mp (->> all-sales-mp
                                         (filter #(= :sale (:type %)))
                                         (group-by :marketplace)
                                         (into {} (map (fn [[mp rows]]
                                                         [(keyword (or mp "wb"))
                                                          (count rows)]))))]
                          (let [total (max 1 (reduce + 0 (vals by-mp)))]
                            {:wb   (math/round2 (* 100.0 (/ (double (get by-mp :wb 0)) total)))
                             :ozon (math/round2 (* 100.0 (/ (double (get by-mp :ozon 0)) total)))
                             :ym   (math/round2 (* 100.0 (/ (double (get by-mp :ym 0)) total)))})))

          ;; Orders by MP spark — reuse all-sales-mp, no second DB call
          orders-by-mp (when-not mp1
                         {:wb   (orders-spark (filter #(= :wb (:marketplace %)) all-sales-mp) from to)
                          :ozon (orders-spark (filter #(= :ozon (:marketplace %)) all-sales-mp) from to)
                          :ym   (orders-spark (filter #(= :ym (:marketplace %)) all-sales-mp) from to)})

          ;; Revenue previous (compare mode)
          rev-prev-spark (when do-compare (revenue-spark sales-prev (:from prev) (:to prev)))

          ;; Data freshness
          fresh       (alerts/freshness-data)

          ;; Build response
          orders-cur  (long (or (:total-sales sales-tots) 0))
          orders-prev (long (or (:total-sales prev-tots) 0))

          ;; Revenue / avg-check: come from PnL with preliminary overlay
          ;; applied via with-prelim. Canonical realization-based when
          ;; available; cash-flow-derived (preliminary) for Ozon when
          ;; realization is delayed. PnL :revenue is for-pay-net
          ;; (sale − return) which matches what the seller actually
          ;; receives; it's the right number to feature on Pulse.
          ;; Sales-tots is kept as a last-ditch fallback when PnL is
          ;; still 0 (e.g. brand-new MP with no finance materialised).
          rev-cur     (let [p (or (:revenue pnl-cur) 0.0)]
                        (if (pos? p) p (or (:revenue sales-tots) 0.0)))
          rev-prev    (let [p (or (:revenue pnl-prev) 0.0)]
                        (if (pos? p) p (or (:revenue prev-tots) 0.0)))
          ac-cur      (let [p (or (:avg-check pnl-cur) 0.0)]
                        (if (pos? p) p (or (:avg-price sales-tots) 0.0)))
          ac-prev     (let [p (or (:avg-check pnl-prev) 0.0)]
                        (if (pos? p) p (or (:avg-price prev-tots) 0.0)))
          preliminary? (boolean (or (:preliminary? pnl-cur)
                                    (:preliminary? pnl-prev)))]

      {:alerts          alert-list
       :kpis            {:revenue   (build-kpi rev-cur rev-prev rev-spark)
                         :profit    (build-kpi (:net-profit pnl-cur) (:net-profit pnl-prev) [])
                         :orders    {:value     orders-cur
                                     :delta-pct (math/pct-delta orders-cur orders-prev)
                                     :spark     ord-spark}
                         :margin    {:value     (or (:margin-net pnl-cur) 0.0)
                                     :delta-pct (math/pct-delta
                                                  (or (:margin-net pnl-cur) 0.0)
                                                  (or (:margin-net pnl-prev) 0.0))
                                     :spark     []}
                         :avg-check {:value     ac-cur
                                     :delta-pct (math/pct-delta ac-cur ac-prev)
                                     :spark     []}
                         :buyout    {:value     (or (:buyout-rate pnl-cur) 0.0)
                                     :delta-pct (math/pct-delta
                                                  (or (:buyout-rate pnl-cur) 0.0)
                                                  (or (:buyout-rate pnl-prev) 0.0))
                                     :spark     []}
                         ;; ROAS: revenue / ad-spend — TODO: not computed at aggregate level; stubbed nil
                         :roas      {:value     (let [ad (or (:ad-spend pnl-cur) 0.0)]
                                                  (if (pos? ad)
                                                    (math/round2 (/ (double (or (:revenue pnl-cur) 0.0)) ad))
                                                    nil))
                                     :delta-pct nil
                                     :spark     []}
                         ;; DRR: ad-spend / revenue * 100
                         :drr       {:value     (let [rev (or (:revenue pnl-cur) 0.0)
                                                       ad  (or (:ad-spend pnl-cur) 0.0)]
                                                   (if (pos? rev)
                                                     (math/round2 (* 100.0 (/ ad rev)))
                                                     nil))
                                     :delta-pct nil
                                     :spark     []}}
       :forecast        {:month-plan nil           ; TODO: wire to domain.plan DB once plans exist
                         :month-fact (math/round2 rev-cur)
                         :projection projection}
       :charts          (cond-> {:revenue-30d    rev-spark
                                 :orders-by-mp   (or orders-by-mp {:wb ord-spark :ozon [] :ym []})
                                 :mp-share       (or mp-share {:wb 100.0 :ozon 0.0 :ym 0.0})}
                          do-compare (assoc :revenue-prev-30d (or rev-prev-spark [])))
       :top-movers      movers
       :top-fallers     fallers
       :critical-stocks (critical-stocks stocks-wt)
       :data-fresh      {:wb   (:wb fresh)
                         :ozon (:ozon fresh)
                         :ym   (:ym fresh)}
       ;; Preliminary flag — true when revenue includes Ozon cash-flow
       ;; estimate (realization not yet published or not yet
       ;; materialized). UI can render a "preliminary" badge.
       :preliminary?    preliminary?
       :preliminary-as-of (or (:preliminary-as-of pnl-cur) nil)})
    (catch Exception e
      {:error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; B2. pnl
;; ---------------------------------------------------------------------------

(def pnl-row-defs
  "Definition of P&L rows for the SPA table.
   Each entry: [key label group]"
  [[:revenue       "Выручка (розница)"        "income"]
   [:wb-reward     "Возмещение ПВЗ"            "income"]
   [:for-pay       "К выплате от МП"           "subtotal"]
   [:cogs          "Себестоимость"             "cost"]
   [:logistics     "Логистика"                 "cost"]
   [:storage       "Хранение"                  "cost"]
   [:acceptance    "Приёмка"                   "cost"]
   [:penalties     "Штрафы"                    "cost"]
   [:deduction     "Удержания"                 "cost"]
   [:additional    "Доплаты"                   "cost"]
   [:ad-spend      "Реклама"                   "cost"]
   [:gross-profit  "Валовая прибыль"           "subtotal"]
   [:net-profit    "Чистая прибыль"            "total"]])

(defn pnl-handler
  "Handler for GET /api/v1/marker/pnl"
  [request]
  (try
    (let [params   (:params request)
          period   (parse-period-params params)
          prev     (prev-period period)
          mp1      (let [mps (parse-mp-param params)]
                     (when (and mps (= 1 (count mps))) (first mps)))

          fin-cur  (load-finance period mp1)
          fin-prev (load-finance prev   mp1)
          pnl-cur  (compute-pnl fin-cur  period mp1)
          pnl-prev (compute-pnl fin-prev prev   mp1)

          cf-adj   (try (pnl/load-cf-adjustments (:from period) (:to period) mp1)
                        (catch Exception _ nil))
          ;; Recompute with CF adjustments when available (adds P&L.6 cf-*/adjusted-* fields).
          ;; pnl/calculate accepts :cf-adjustments; compute-pnl 4-arity passes it through.
          pnl-cur  (if cf-adj
                     (compute-pnl fin-cur period mp1 cf-adj)
                     pnl-cur)

          ;; Apply preliminary overlay so Ozon shows a meaningful number
          ;; while realization is delayed. Per-MP=:ozon swaps revenue
          ;; with cash-flow-derived for-pay-net; all-MP adds Ozon
          ;; preliminary on top of WB+YM canonical.
          pnl-cur  (with-prelim pnl-cur period mp1)
          pnl-prev (with-prelim pnl-prev prev   mp1)

          rows     (mapv (fn [[k label group]]
                           {:key   k
                            :label label
                            :cur   (or (get pnl-cur k) 0.0)
                            :prev  (or (get pnl-prev k) 0.0)
                            :group group})
                         pnl-row-defs)

          ;; Per-SKU breakdown via finance/by-article
          by-art   (try (finance/by-article fin-cur) (catch Exception _ []))
          sku-det  (mapv (fn [a]
                           {:id         (str (:article a))
                            :name       (or (:subject a) (:article a))
                            :mp         [(keyword (or (:marketplace a) :wb))]
                            :revenue    (or (:revenue a) 0.0)
                            :cogs       (or (:total-cost a) 0.0)
                            :commission (or (:deduction a) 0.0)
                            :ads        0.0       ; TODO: per-article ad spend not aggregated here
                            :net        (or (:for-pay a) 0.0)})
                         by-art)]
      {:rows              rows
       :sku-detail        sku-det
       :preliminary?      (boolean (:preliminary? pnl-cur))
       :preliminary-as-of (:preliminary-as-of pnl-cur)})
    (catch Exception e
      {:rows [] :sku-detail [] :error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; B3. sku-list
;; ---------------------------------------------------------------------------

(defn sku-list-handler
  "Handler for GET /api/v1/marker/sku-list"
  [request]
  (try
    (let [params    (:params request)
          period    (parse-period-params params)
          prev      (prev-period period)
          mp1       (let [mps (parse-mp-param params)]
                      (when (and mps (= 1 (count mps))) (first mps)))
          from      (:from period)
          to        (:to   period)

          fin-cur   (load-finance period mp1)
          fin-prev  (load-finance prev   mp1)
          by-art    (try (finance/by-article fin-cur)  (catch Exception _ []))
          by-art-p  (try (finance/by-article fin-prev) (catch Exception _ []))
          prev-rev  (->> by-art-p
                         (map (fn [a] [(or (:article a) "") (:revenue a)]))
                         (into {}))

          sales-cur (load-sales period mp1)
          by-art-s  (sales/by-article sales-cur)
          sales-rev (->> by-art-s
                         (map (fn [a] [(or (:group a) (:article a)) (:revenue a)]))
                         (into {}))

          stocks    (try (stock/fetch-stocks :marketplace mp1) (catch Exception _ []))
          stock-map (->> (stock/by-article stocks)
                         (map (fn [s] [(:article s) s]))
                         (into {}))

          pnl-cur   (compute-pnl fin-cur period mp1)
          ad-total  (or (:ad-spend pnl-cur) 0.0)

          skus      (mapv (fn [a]
                            (let [art       (or (:article a) "")
                                  rev       (or (:revenue a) 0.0)
                                  prev-r    (or (get prev-rev art) 0.0)
                                  stk       (get stock-map art)
                                  qty-full  (or (:quantity-full stk) 0)
                                  orders    (or (:sales-qty a) 0)
                                  returns   (or (:returns-qty a) 0)
                                  for-pay   (or (:for-pay a) 0.0)
                                  cogs      (or (:total-cost a) 0.0)
                                  margin    (math/percentage
                                              (- for-pay cogs)
                                              (max 1.0 for-pay))
                                  buyout    (math/percentage orders (+ orders returns))]
                              {:id        art
                               :name      (or (:subject a) art)
                               :mp        [(keyword (or (:marketplace a) :wb))]
                               :revenue   rev
                               :orders    orders
                               :margin    (or margin 0.0)
                               :buyout    (or buyout 0.0)
                               :stock     qty-full
                               :delta-pct (math/pct-delta rev prev-r)
                               :ads-cost  0.0    ; TODO: per-article ad cost not available without ad_stats join
                               :roas      nil    ; TODO: depends on ads-cost
                               :spark     []}))  ; TODO: per-article daily spark expensive — defer to Phase 8
                          by-art)
          limit     (some-> (get params :limit) parse-long)
          offset    (or (some-> (get params :offset) parse-long) 0)
          skus-paged (cond->> skus
                       true   (drop offset)
                       limit  (take limit)
                       true   vec)]
      {:skus skus-paged})
    (catch Exception e
      {:skus [] :error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; B4. sku-detail
;; ---------------------------------------------------------------------------

(defn sku-detail-handler
  "Handler for GET /api/v1/marker/sku-detail/:sku-id"
  [request]
  (try
    (let [params   (:params request)
          sku-id   (or (:sku-id params) (get params "sku-id") "")
          period   (parse-period-params params)
          prev     (prev-period period)
          from     (:from period)
          to       (:to   period)
          mp1      (let [mps (parse-mp-param params)]
                     (when (and mps (= 1 (count mps))) (first mps)))

          ;; Finance scoped to this article across all MPs
          fin-all  (try (finance/fetch-finance period mp1) (catch Exception _ []))
          fin-art  (filter #(= sku-id (or (:article %) "")) fin-all)

          fin-prev-all (try (finance/fetch-finance prev mp1) (catch Exception _ []))
          fin-art-p    (filter #(= sku-id (or (:article %) "")) fin-prev-all)

          by-art   (try (finance/by-article fin-art)   (catch Exception _ []))
          by-art-p (try (finance/by-article fin-art-p) (catch Exception _ []))
          agg      (first by-art)
          agg-p    (first by-art-p)

          ;; Sales for spark
          sales-cur  (load-sales period mp1)
          art-sales  (filter #(= sku-id (or (:article %) "")) sales-cur)
          rev-spark  (revenue-spark art-sales from to)

          ;; Stocks
          stocks    (try (stock/fetch-stocks :marketplace mp1) (catch Exception _ []))
          stk-all   (stock/by-article stocks)
          stk-art   (filter #(= sku-id (:article %)) stk-all)
          stk-by-mp (->> stk-art
                         (mapv (fn [s]
                                 (let [qty  (or (:quantity-full s) (:quantity s) 0)
                                       sp   (or (:daily-rate s) 0)
                                       days (if (pos? sp)
                                              (int (Math/round (/ (double qty) sp)))
                                              nil)]
                                   {:mp    (keyword (or (:marketplace s) :wb))
                                    :stock qty
                                    :days  days}))))

          ;; P&L for this article
          pnl-cur  (if (seq fin-art)
                     (compute-pnl fin-art period mp1)
                     {:revenue 0.0 :net-profit 0.0 :margin-net 0.0 :ad-spend 0.0})
          pnl-prev (if (seq fin-art-p)
                     (compute-pnl fin-art-p prev mp1)
                     {:revenue 0.0 :net-profit 0.0 :margin-net 0.0 :ad-spend 0.0})

          ;; Forecast: simple run-rate
          today-d   (java.time.LocalDate/now)
          today-str (period/format-date today-d)
          days-tot  (period/days-between from to)
          days-done (if (<= (compare today-str to) 0)
                      (period/days-between from today-str)
                      days-tot)
          vel       (if (pos? days-done)
                      (/ (double (or (:revenue pnl-cur) 0.0)) days-done)
                      0.0)
          proj      (math/round2 (* vel days-tot))

          nm-id    (some :nm-id by-art)
          subject  (or (:subject agg) sku-id)
          mps-list (or (seq (distinct (map (comp keyword :marketplace) fin-art)))
                       (filterv some? [(some-> mp1 keyword) :wb])
                       [:wb])]

      {:id       sku-id
       :name     subject
       :nm-id    nm-id
       :subject  subject
       :mp       (vec mps-list)
       :kpis     {:revenue {:value     (or (:revenue pnl-cur) 0.0)
                             :delta-pct (math/pct-delta
                                          (or (:revenue pnl-cur) 0.0)
                                          (or (:revenue pnl-prev) 0.0))}
                  :orders  {:value (or (:sales-qty agg) 0)}
                  :margin  {:value (or (:margin-net pnl-cur) 0.0)}
                  :ads     {:value (or (:ad-spend pnl-cur) 0.0)}}
       :revenue-30d  rev-spark
       :plan-fact    {:plan       nil
                      :fact       (math/round2 (or (:revenue pnl-cur) 0.0))
                      :projection proj}
       :stocks-by-mp (if (seq stk-by-mp) stk-by-mp [])})
    (catch Exception e
      {:id    (get-in request [:params :sku-id] "")
       :error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; B5. what-if-recalc
;; ---------------------------------------------------------------------------

(defn what-if-recalc
  "Pure unit-econ what-if computation.
   Called on every slider drag — must be fast and side-effect-free.

   Input (Transit-decoded from request body):
     {:price <num> :cogs <num> :commission-pct <num>
      :logistics <num> :ads <num> :returns-pct <num>}

   Returns:
     {:margin-pct <num> :profit <num> :roas <num> :break-even <num>}"
  [inputs]
  (let [price          (double (or (:price inputs) 0))
        cogs           (double (or (:cogs inputs) 0))
        commission-pct (double (or (:commission-pct inputs) 0))
        logistics      (double (or (:logistics inputs) 0))
        ads            (double (or (:ads inputs) 0))
        returns-pct    (double (or (:returns-pct inputs) 0))

        ;; Effective revenue accounting for returns
        effective-rev  (* price (- 1.0 (/ returns-pct 100.0)))

        ;; Commission as absolute amount
        commission     (* effective-rev (/ commission-pct 100.0))

        ;; Total costs
        total-costs    (+ cogs commission logistics ads)

        ;; Profit and margin
        profit         (- effective-rev total-costs)
        margin-pct     (if (pos? effective-rev)
                         (math/round2 (* 100.0 (/ profit effective-rev)))
                         0.0)

        ;; ROAS: revenue / ads spend
        roas           (if (pos? ads)
                         (math/round2 (/ effective-rev ads))
                         nil)

        ;; Break-even price: solve effective-rev = total-costs when price = break-even
        ;; break-even = (cogs + logistics + ads) / (1 - commission_pct/100 - returns_pct/100)
        net-factor     (- 1.0
                          (/ commission-pct 100.0)
                          (/ returns-pct 100.0))
        break-even     (if (pos? net-factor)
                         (math/round2 (/ (+ cogs logistics ads) net-factor))
                         nil)]
    {:margin-pct (or margin-pct 0.0)
     :profit     (math/round2 profit)
     :roas       roas
     :break-even break-even}))

(defn what-if-handler
  "Handler for POST /api/v1/marker/what-if-recalc"
  [request]
  (try
    (let [body     (if (map? (:body request)) (:body request) {})
          required #{:price :cogs :commission-pct :logistics :ads :returns-pct}
          missing  (remove #(number? (get body %)) required)]
      (if (seq missing)
        {:status 400
         :body   {:error (str "Required numeric fields missing or invalid: "
                              (str/join ", " (sort (map name missing))))}}
        {:status 200
         :body   (what-if-recalc body)}))
    (catch Exception e
      {:status 500
       :body   {:error (.getMessage e)}})))

;; ---------------------------------------------------------------------------
;; B6. reports — generic schema-driven report endpoint (Phase 9)
;; ---------------------------------------------------------------------------

(def ^:private known-report-types
  "Pre-computed set of valid report-type keywords from the schema registry."
  (set (rs/all-report-types)))

(defn- ->report-type
  "Coerce a string path-param to a known report-type keyword, or nil."
  [s]
  (when (string? s)
    (let [k (keyword (str/lower-case s))]
      (when (contains? known-report-types k) k))))

(defn- ->trend-type [s]
  (when (string? s)
    (let [k (keyword (str/lower-case s))]
      (when (#{:wow :mom :daily} k) k))))

(defn- compare-flag [params]
  (if (compare? params) :prev :none))

(defn reports-handler
  "Handler for GET /api/v1/marker/reports/:type
   Query params: ?from&to (or omitted → last-30-days), ?mp=wb,ozon,ym,
                 ?compare=true, ?article=<sku>, ?trend-type=wow|mom|daily.

   Returns: {:report-type kw
             :columns     [col-meta...]   ; from schema (UI rendering)
             :rows        [row-map...]
             :totals      {kw -> num}
             :compare     {:rows [...] :totals {...}} (only if compare=true)
             :schema      {:rows-mode kw :tabs ... :presets ... :kpi ...}}

   On unknown :type returns 400; on internal failure returns 500."
  [request]
  (try
    (let [params      (:params request)
          rtype-raw   (or (:type params) (get-in request [:path-params :type]))
          rtype       (->report-type rtype-raw)]
      (if-not rtype
        {:status 400
         :body   {:error (str "Unknown report type: " (pr-str rtype-raw))
                  :known (mapv name (rs/all-report-types))}}
        (let [period      (parse-period-params params)
              mps         (parse-mp-param params)
              ;; compute-report supports a single :marketplace keyword (or nil = all)
              mp1         (cond
                            (nil? mps)              nil
                            (= 1 (count mps))       (first mps)
                            :else                   nil)
              article     (let [a (:article params)] (when (seq a) a))
              trend-type  (->trend-type (:trend-type params))
              data        (report/report-data rtype period
                                              :marketplace mp1
                                              :trend-type  trend-type
                                              :article     article
                                              :compare     (compare-flag params))
              schema      (rs/get-schema rtype)
              compare-blk (:compare data)]
          {:status 200
           :body   (cond-> {:report-type rtype
                            :columns     (vec (:columns schema))
                            :rows        (vec (:rows data))
                            :totals      (or (:totals data) {})
                            :schema      (select-keys schema
                                                      [:id :title :rows-mode
                                                       :supports-compare?
                                                       :supports-period?
                                                       :supports-marketplace?
                                                       :tabs :presets :kpi
                                                       :drill-down :chart])}
                     compare-blk (assoc :compare {:rows   (vec (:rows compare-blk))
                                                  :totals (or (:totals compare-blk) {})}))})))
    (catch Exception e
      {:status 500
       :body   {:error (.getMessage e)}})))

(defn report-article-handler
  "Handler for GET /api/v1/marker/reports/:type/article/:article
   Returns the same shape as reports-handler but filtered to one article."
  [request]
  (let [params  (:params request)
        article (or (:article params) (get-in request [:path-params :article]))
        request' (assoc-in request [:params :article] article)]
    (reports-handler request')))
