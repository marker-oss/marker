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
  (:require [analitica.domain.finance         :as finance]
            [analitica.domain.pnl             :as pnl]
            [analitica.domain.preliminary     :as prelim]
            [analitica.domain.reconciliation  :as reconciliation]
            [analitica.domain.sales       :as sales]
            [analitica.domain.stock       :as stock]
            [analitica.domain.buyout      :as buyout]
            [analitica.domain.cost-price  :as cost-price]
            [analitica.alerts             :as alerts]
            [analitica.canonical.events.query :as canon]
            [analitica.util.period        :as period]
            [analitica.util.math          :as math]
            [analitica.db                 :as db]
            [analitica.web.api.charts     :as charts]
            [analitica.web.api.report     :as report]
            [analitica.web.report-schemas :as rs]
            [analitica.platform.capability :as cap]
            [clojure.string               :as str]
            [com.brunobonacci.mulog       :as mu]))

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
   when supplied, pnl/calculate adds the P&L.6 cf-* / adjusted-* fields.

   015 T039: the 5-arity also threads an optional `management` block (from
   pnl/load-management-adjustments) as :management to pnl/calculate, so the
   result carries the management-layer keys (:tax :vat :opex :profit …). When
   `management` is nil the output is byte-for-byte identical to the pre-015
   result (SC-006 / INV-4); the management keys simply aren't emitted."
  ([fin-data period-map marketplace]
   (compute-pnl fin-data period-map marketplace nil nil))
  ([fin-data period-map marketplace cf-adjustments]
   (compute-pnl fin-data period-map marketplace cf-adjustments nil))
  ([fin-data period-map marketplace cf-adjustments management]
   (try
     (apply pnl/calculate
            fin-data
            (cond-> [:marketplace marketplace
                     :from        (:from period-map)
                     :to          (:to  period-map)]
              cf-adjustments (conj :cf-adjustments cf-adjustments)
              management     (conj :management management)))
     (catch Exception _
       {:revenue 0.0 :net-profit 0.0 :gross-profit 0.0 :margin-net 0.0
        :ad-spend 0.0 :cogs 0.0 :logistics 0.0 :for-pay 0.0
        :sales-qty 0 :returns-qty 0
        :non-return-rate 0.0 :buyout-rate 0.0  ; FR-008: :non-return-rate canonical; :buyout-rate alias
        :avg-check 0.0}))))

;; ---------------------------------------------------------------------------
;; 015 T039 — management-layer keys forwarded verbatim into the pnl-handler
;; response envelope. These are exactly the keys pnl/calculate emits when a
;; :management block is supplied (contracts/tax-opex-api.md §3). Absent from
;; the response when management is inert-nil (they simply aren't in pnl-cur).
;; ---------------------------------------------------------------------------

(def ^:private management-response-keys
  [:management-configured? :tax-base :tax :vat :opex :opex-by-category
   :profit :profit-without-expense :management-margin :margin-without-expense
   :management-zero-reason])

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
                     :preliminary-as-of (:as-of p)
                     :revenue-source    :preliminary))
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
  "Daily revenue list for the period (one element per day).
   Sourced from sales rows — legacy fallback when finance rows are unavailable."
  [sales-data from to]
  (let [by-day (sales-by-day-map sales-data)
        dates  (date-range-seq from to)]
    (mapv #(double (get by-day % 0.0)) dates)))

(defn- realization-revenue-spark
  "Daily for_pay spark derived from already-fetched finance rows.

   LT4 (BUG B): aligns the Pulse chart basis with :month-fact. The
   headline rev-cur comes from pnl/calculate over fin-cur (realization
   rows); the chart must use the same rows so chart-total ≈ month-fact.

   Groups fin-cur by event_date (first 10 chars), sums for_pay. Falls
   back to 0.0 for days with no finance rows. When fin-cur is empty,
   returns a zero-filled vector of (period-length) elements."
  [fin-cur from to]
  (let [by-day (->> fin-cur
                    (group-by (fn [r]
                                (let [d (or (:event_date r) (:event-date r) "")]
                                  (if (>= (count d) 10) (subs d 0 10) d))))
                    (into {} (map (fn [[d rows]]
                                    [d (reduce + 0.0
                                               (map (fn [r]
                                                      (or (:for-pay r) (:for_pay r) 0.0))
                                                    rows))]))))
        dates  (date-range-seq from to)]
    (mapv #(double (get by-day % 0.0)) dates)))

(defn- orders-spark
  "30-element daily count of `sale`-type rows from the sales-data list.
   Despite the historical name, this counts settled purchases (delivered
   sales), not orders-placed. Use `orders-count-spark` for the latter."
  [sales-data from to]
  (let [by-day (->> sales-data
                    (filter #(= :sale (:type %)))
                    (group-by (fn [r]
                                (let [d (or (:date r) "")]
                                  (if (>= (count d) 10) (subs d 0 10) d))))
                    (into {} (map (fn [[d rows]] [d (count rows)]))))
        dates  (date-range-seq from to)]
    (mapv #(long (get by-day % 0)) dates)))

(defn- orders-count-spark
  "30-element daily count of orders (from the `orders` table) for the
   period. Distinct from `orders-spark` which counts settled sales rows.

   `mp1` is the resolved single MP keyword (:wb / :ozon / :ym) when the
   user filter narrowed to one, nil for all-MPs."
  [from to mp1]
  (let [mp-clause (cond
                    (= :ozon mp1) " AND marketplace = 'ozon'"
                    (= :wb   mp1) " AND marketplace = 'wb'"
                    (= :ym   mp1) " AND marketplace = 'ym'"
                    :else         "")
        sql       (str "SELECT substr(date,1,10) AS day, COUNT(*) AS n
                        FROM orders
                        WHERE substr(date,1,10) BETWEEN ? AND ?"
                       mp-clause
                       " GROUP BY substr(date,1,10)")
        rows      (try (db/query [sql from to])
                       (catch Exception _ []))
        by-day    (into {} (map (juxt :day :n) rows))
        dates     (date-range-seq from to)]
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

(defn- cost-line
  "Build a cost-breakdown line with consistent shape.
   :source is :none when cur is zero (nothing to attribute).

   5-arg arity (missing? = true): LT5 honesty path for costs that are
   absent in a preliminary Ozon window (commission/COGS not yet published).
   Returns {:value nil :source :preliminary-missing :delta-pct nil :as-of as-of}.
   The nil value signals «нет данных» to the UI — never to be summed into totals."
  ([cur prev source as-of]
   {:value     (math/round2 (or cur 0))
    :delta-pct (math/pct-delta (or cur 0) (or prev 0))
    :source    (if (and source (pos? (or cur 0))) source :none)
    :as-of     as-of})
  ([_cur _prev _source as-of missing?]
   (if missing?
     {:value     nil
      :delta-pct nil
      :source    :preliminary-missing
      :as-of     as-of}
     (cost-line _cur _prev _source as-of))))

(defn- sum-other-costs
  "Storage + acceptance + penalties + deduction + additional."
  [pnl]
  (+ (or (:storage    pnl) 0)
     (or (:acceptance pnl) 0)
     (or (:penalties  pnl) 0)
     (or (:deduction  pnl) 0)
     (or (:additional pnl) 0)))

(defn- sum-total-costs
  "Sum of all tracked cost components."
  [pnl]
  (+ (or (:cogs      pnl) 0)
     (or (:wb-reward pnl) 0)
     (or (:logistics pnl) 0)
     (or (:ad-spend  pnl) 0)
     (sum-other-costs pnl)))

;; ---------------------------------------------------------------------------

(defn basis-note
  "Note for a finance KPI given its date-basis split and the window length
   in days. Returns :flat-heavy-subperiod when a sub-month window leans on
   flat (guess-distributed) rows that carry no per-day meaning. nil otherwise."
  [fin-basis window-days]
  (when (and (< window-days 28) (>= (double (or (:flat fin-basis) 0.0)) 0.2))
    :flat-heavy-subperiod))

(defn- empty-finance?
  "True when the monetary sum of |for-pay| across `rows` is zero.
   Mirrors the `amt` fn inside finance/date-basis-split (tolerant of both
   :for-pay and :for_pay key spellings). Used as the :empty guard in
   basis-envelope and the sku-list/pnl/sku-detail call sites."
  [rows]
  (let [amt (fn [r] (Math/abs (double (or (:for-pay r) (:for_pay r) 0.0))))]
    (zero? (reduce + 0.0 (map amt rows)))))

(defn basis-envelope
  "Return the basis-contract fragment {:date-basis ... :completeness ...}
   for any finance handler response envelope.

   Completeness decision tree (LT3, specs/010 P0-A):
     1. zero monetary sum        → :empty      (no data; window not yet published
                                                or genuinely no sales)
     2. preliminary? is true     → :estimated  (at least one MP says «preliminary»)
     3. flat fraction >= 0.2     → :estimated  (day-level meaning is a guess)
     4. else                     → :full

   Pass preliminary? as false when the handler has no per-MP preliminary
   signal (sku-list) — completeness falls back to flat-threshold only."
  [rows preliminary?]
  (let [fin-basis    (finance/date-basis-split rows)
        completeness (cond
                       (empty-finance? rows)      :empty
                       preliminary?               :estimated
                       (>= (:flat fin-basis) 0.2) :estimated
                       :else                      :full)]
    {:date-basis   fin-basis
     :completeness completeness}))

;; ---------------------------------------------------------------------------

(defn- build-kpi
  ([cur-val prev-val spark]
   {:value     (or cur-val 0.0)
    :delta-pct (math/pct-delta (or cur-val 0.0) (or prev-val 0.0))
    :spark     (or spark [])})
  ([cur-val prev-val spark source as-of]
   {:value     (or cur-val 0.0)
    :delta-pct (math/pct-delta (or cur-val 0.0) (or prev-val 0.0))
    :spark     (or spark [])
    :source    source
    :as-of     as-of}))

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
                           prev-rev  (or (get prev-map art) 0.0)
                           mp        (or (some-> r :marketplace keyword) :wb)]
                       {:id        (str art)
                        :name      (or (:subject r) art)
                        :mp        [mp]
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

;; ---------------------------------------------------------------------------
;; Pure helpers for pulse-summary charts (directly testable; see marker_test.clj)
;; ROAS/ДРР/ROMI helpers live in analitica.util.math so they're shared with
;; sku-list, what-if, and digest (they all need the same noise-floor).
;; ---------------------------------------------------------------------------

(defn- compute-mp-share
  "Build {:wb % :ozon % :ym %} percentages from a list of all-MP sales
   rows (regardless of any user-side MP filter). Always returns the
   three keys; missing MPs read as 0.

   Weighting is by Σ unit-qty (analitica.util.math/unit-qty), not row
   count. On quantity-less rows (e.g. WB sales table) unit-qty coalesces
   to 1, so the result is byte-identical to the old row-count behaviour.

   This widget is structural — the donut shows where the seller's
   revenue comes from across MPs. Independent of MP filter so the user
   can see the full picture even when they've narrowed the rest of the
   page to a single MP."
  [all-sales-mp]
  (let [by-mp (->> all-sales-mp
                   (filter #(= :sale (:type %)))
                   (group-by :marketplace)
                   (into {} (map (fn [[mp rows]]
                                   [(keyword (or mp "wb"))
                                    (reduce + 0 (map math/unit-qty rows))]))))
        total (max 1 (reduce + 0 (vals by-mp)))]
    {:wb   (math/round2 (* 100.0 (/ (double (get by-mp :wb 0)) total)))
     :ozon (math/round2 (* 100.0 (/ (double (get by-mp :ozon 0)) total)))
     :ym   (math/round2 (* 100.0 (/ (double (get by-mp :ym 0)) total)))}))

(defn- compute-orders-by-mp
  "Build {:wb [...] :ozon [...] :ym [...]} sparklines of daily order
   counts per MP, computed on all-MP sales. Like compute-mp-share, this
   chart is structural and independent of the user's MP filter."
  [all-sales-mp from to]
  {:wb   (orders-spark (filter #(= :wb   (:marketplace %)) all-sales-mp) from to)
   :ozon (orders-spark (filter #(= :ozon (:marketplace %)) all-sales-mp) from to)
   :ym   (orders-spark (filter #(= :ym   (:marketplace %)) all-sales-mp) from to)})

(defn- compute-projection
  "Forward-projection of revenue at current pace.

     velocity   = revenue / days-so-far
     projection = velocity * days-total

   For closed periods (days-so-far == days-total) projection equals
   revenue. Caller is responsible for choosing a revenue source that
   matches the displayed Pulse KPI (otherwise projection drifts away
   from the headline figure)."
  [revenue days-so-far days-total]
  (if (and (pos? days-so-far) (pos? days-total))
    (math/round2 (* (/ (double revenue) days-so-far) days-total))
    0.0))

(defn- pulse-capability-envelope
  "Add :capabilities alongside the existing honesty-envelope keys.
   Caller context is nil (single-API-key open edition — FR-028 forward seam).
   The slot is placed NEXT TO :completeness/:date-basis/:preliminary? (FR-027/P6);
   it never replaces them and never truncates payload (FR-017/SC-006)."
  [response-map]
  (merge response-map (cap/capabilities-for nil)))

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

          ;; Current period data — same cf-adjustments treatment as
          ;; pnl-handler so subscription, warehouse-movement, fines and
          ;; the rest of the Ozon cash-flow service costs land on Pulse
          ;; net-profit too. Without this Pulse silently overstated
          ;; Ozon profit vs the canonical P&L tile.
          ;; US2 T025: data-load inner trace — wraps finance + sales DB fetches.
          ;; mu/trace emits a span nested under the outer request span context
          ;; (set by wrap-request-trace via mu/with-context). Only allow-list
          ;; attributes — no per-SKU/article labels (FR-014).
          fin-cur    (mu/trace :marker/data-load {} (load-finance period mp1))
          sales-cur  (mu/trace :marker/data-load {} (load-sales   period mp1))
          cf-adj-cur (try (pnl/load-cf-adjustments (:from period) (:to period) mp1)
                          (catch Exception _ nil))
          pnl-cur    (-> (compute-pnl fin-cur period mp1 cf-adj-cur)
                         (with-prelim period mp1))

          ;; Previous period
          fin-prev   (load-finance prev mp1)
          sales-prev (load-sales  prev mp1)
          cf-adj-prev (try (pnl/load-cf-adjustments (:from prev) (:to prev) mp1)
                           (catch Exception _ nil))
          pnl-prev   (-> (compute-pnl fin-prev prev mp1 cf-adj-prev)
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
          ;;   rev-spark    — daily revenue spark for :revenue-30d chart (LT4 BUG B:
          ;;                  sourced from finance rows so chart-total ≈ :month-fact).
          ;;                  When finance is empty (no data yet) falls back to the
          ;;                  sales-table spark so the chart is never blank on a live
          ;;                  window that has sales but not yet realization.
          ;;   purch-spark  — daily settled-purchase count (sales rows of type :sale)
          ;;   ord-spark    — daily orders count from the orders table
          ;; The :orders and :purchases KPI cards each get their own spark
          ;; — they are different counters and previously both received
          ;; purch-spark, hiding the order-vs-purchase delta visually.
          real-spark  (realization-revenue-spark fin-cur from to)
          rev-spark   (if (every? zero? real-spark)
                        ;; Finance rows absent or all-zero: fall back to sales spark
                        ;; so the chart still shows something on a fresh window.
                        (revenue-spark sales-cur from to)
                        real-spark)
          purch-spark (orders-spark  sales-cur from to)
          ord-spark   (orders-count-spark from to mp1)

          ;; Movers/fallers
          [movers fallers] (top-movers-fallers cur-by-art prev-by-art from to sales-cur)

          ;; Alerts
          alert-list  (build-alerts stocks-wt cur-by-art prev-by-art pnl-cur pnl-prev
                                    buyout-cur sales-3d top-10)

          ;; Forecast — use plan domain if available, else simple run-rate.
          ;; TODO: wire to domain.plan/run-rate once monthly plans exist in DB.
          ;; Velocity must use the SAME revenue source as the displayed
          ;; Pulse KPI (rev-cur, defined below) so projection doesn't
          ;; silently diverge from the headline figure. Computed after
          ;; rev-cur is bound — see :forecast key in the response map.
          days-total  (period/days-between from to)
          days-so-far (let [today-str (period/format-date today-d)]
                        (if (<= (compare today-str to) 0)
                          (period/days-between from today-str)
                          days-total))

          ;; Fetch all-MP sales (no filter) once. Reused by mp-share and
          ;; orders-by-mp — these widgets are structural ("where does my
          ;; revenue come from across MPs") so they intentionally ignore
          ;; the user-side MP filter and always show the full mix.
          all-sales-mp (load-sales period nil)
          mp-share     (compute-mp-share all-sales-mp)
          orders-by-mp (compute-orders-by-mp all-sales-mp from to)

          ;; Revenue previous (compare mode)
          rev-prev-spark (when do-compare (revenue-spark sales-prev (:from prev) (:to prev)))

          ;; Data freshness
          fresh       (alerts/freshness-data)

          ;; Order vs purchase counts. Phase 5e (2026-05-05): when canonical
          ;; item_events covers the period, prefer them — they count by
          ;; ITEM-UNIT (LK / MPStats convention) and have one definition
          ;; across MPs. When canon is empty (period not yet materialized
          ;; or MP without a normalizer — WB/YM until Phase 5f/g), fall
          ;; back to the legacy orders/sales tables.
          ;;   :orders     — units ordered (was: posting count)
          ;;   :purchases  — units delivered (was: sales-table row count)
          ;; Conversion = purchases / orders.
          mp-name-clause (cond
                           (= :ozon mp1) " AND marketplace = 'ozon'"
                           (= :wb   mp1) " AND marketplace = 'wb'"
                           (= :ym   mp1) " AND marketplace = 'ym'"
                           :else         "")
          orders-total-q (str "SELECT COUNT(*) AS n FROM orders
                               WHERE substr(date,1,10) BETWEEN ? AND ?"
                              mp-name-clause)
          legacy-orders  (fn [from* to*]
                           (long (or (:n (first (try (db/query [orders-total-q from* to*])
                                                     (catch Exception _ [{:n 0}]))))
                                     0)))
          canon-orders   (fn [from* to*] (canon/units-ordered   from* to* mp1))
          canon-deliv    (fn [from* to*] (canon/units-delivered from* to* mp1))
          ;; Use canon when it has data for this period+mp; fallback otherwise.
          ;; Phase 5e is Ozon-only — WB/YM canon is empty until 5f/g land.
          [orders-cur orders-src]
                      (let [c (canon-orders from to)]
                        (cond
                          (pos? c)                       [c :canon]
                          (pos? (legacy-orders from to)) [(legacy-orders from to) :legacy-orders]
                          :else                          [0 :none]))
          orders-prev (let [c (canon-orders (:from prev) (:to prev))]
                        (if (pos? c) c (legacy-orders (:from prev) (:to prev))))
          [purchases-cur purchases-src]
                      (let [c (canon-deliv from to)]
                        (cond
                          (pos? c)                                    [c :canon]
                          (pos? (long (or (:total-sales sales-tots) 0)))
                            [(long (or (:total-sales sales-tots) 0)) :legacy-sales]
                          :else                                       [0 :none]))
          purchases-prev (let [c (canon-deliv (:from prev) (:to prev))]
                           (if (pos? c) c (long (or (:total-sales prev-tots) 0))))

          ;; Realized: units that appear in canonical realization-based finance rows.
          ;; Read directly from pnl-cur/prev :sales-qty — pnl/calculate already
          ;; aggregated this; no need to re-run finance/totals over the same data.
          ;; Never :preliminary by definition: this reflects finance.realization rows;
          ;; if those don't exist the value is 0.
          realized-cur  (long (or (:sales-qty pnl-cur)  0))
          realized-prev (long (or (:sales-qty pnl-prev) 0))
          realized-src  (if (pos? realized-cur) :realization :none)

          ;; Returned: units that came back to seller in this period.
          ;; Sourced from pnl-cur :returns-qty — same realization-based
          ;; accounting as :realized. Source :none when 0 (nothing to show).
          returned-cur  (long (or (:returns-qty pnl-cur)  0))
          returned-prev (long (or (:returns-qty pnl-prev) 0))
          returned-src  (if (pos? returned-cur) :realization :none)

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
                                    (:preliminary? pnl-prev)))

          ;; ---------------------------------------------------------------------------
          ;; Date-basis composition (P0-A Part A, specs/010). Fraction of finance
          ;; for_pay by event_date_source. A high :flat share means the value is
          ;; largely a guess-distribution (Ozon monthly realization split evenly), so
          ;; a sub-period slice has no real day-level meaning → flag :estimated.
          ;; WB/YM are 100% :api; this fires mostly on Ozon / all-MP views.
          ;; ---------------------------------------------------------------------------
          fin-basis        (finance/date-basis-split fin-cur)
          window-days      (period/days-between from to)
          ;; NOTE: this cond MUST mirror basis-envelope's completeness tree
          ;; (see basis-envelope above) so the two honesty paths don't diverge.
          ;; In particular the `:empty` guard (empty-finance? first) is what
          ;; stops the coverage chip from reading «полные данные» on a window
          ;; with no monetary data at all. LT3 / specs/010 P0-A.
          fin-completeness (cond
                             (empty-finance? fin-cur)   :empty
                             preliminary?               :estimated
                             (>= (:flat fin-basis) 0.2) :estimated
                             :else                      :full)

          ;; ---------------------------------------------------------------------------
          ;; Per-KPI source metadata (for seller-view rendering of "preliminary" stars)
          ;; ---------------------------------------------------------------------------
          ;; revenue-src / revenue-as-of: derived from pnl-cur revenue-source.
          ;;   :preliminary  → Ozon cash-flow overlay (real but unconfirmed).
          ;;   :realization  → canonical finance table (settled).
          ;;   :legacy-sales → last-ditch sales-tots fallback when PnL=0.
          ;;   :none         → no source had data (value is 0).
          revenue-src   (cond
                          (= :preliminary (:revenue-source pnl-cur)) :preliminary
                          (pos? (or (:revenue pnl-cur) 0.0))         :realization
                          (pos? (or (:revenue sales-tots) 0.0))       :legacy-sales
                          :else                                        :none)
          revenue-as-of (when (= :preliminary revenue-src)
                          (:preliminary-as-of pnl-cur))

          ;; ---------------------------------------------------------------------------
          ;; Buyout — P0-B (specs/010). The headline «Выкуп» must reconcile with the
          ;; MP seller cabinet, so it is ORDER-BASED (sold/placed) — NOT the legacy
          ;; non-return rate (sold/(sold+returns)), which structurally cannot see
          ;; cancellations and read 86%/0%/97% on the same data. Cancellations get
          ;; their own «% отмен» (:cancel); the legacy rate survives under its honest
          ;; name «Доля невозвратов» (:non-return).
          ;; ---------------------------------------------------------------------------
          buyout-of     (fn [pmap from* to*]
                          (let [orows (db/orders-by-article from* to* :marketplace mp1)
                                omap  (into {} (map (juxt :article identity) orows))]
                            (buyout/aggregate
                              (buyout/analyze pmap :marketplace mp1 :orders-by-article omap))))
          buyout-agg    (buyout-of period from to)
          buyout-prev   (buyout-of prev (:from prev) (:to prev))
          buyout-rate   (:buyout-orders-rate buyout-agg)   ; sold/placed; nil if no orders
          cancel-rate   (:cancel-rate buyout-agg)          ; cancelled/placed
          non-return    (:non-return-rate buyout-agg)      ; legacy sold/(sold+ret)
          ;; :source = :orders when orders data exists (a real 0% is still :orders,
          ;; not :none). :none only when there are no placed orders to divide by.
          buyout-src    (if (pos? (:placed buyout-agg)) :orders :none)

          ;; Projection uses the SAME revenue source as the KPI tile —
          ;; otherwise the «Прогноз» line under the chart drifts away
          ;; from the headline outage. Fixes Bug #3.
          projection  (compute-projection rev-cur days-so-far days-total)

          ;; Pulse ad-spend (canonical PnL). Below the noise threshold,
          ;; ROAS/ДРР return nil → UI renders «—» (avoids 7-digit ROAS
          ;; from 0.25 ₽ ad-stats artefacts; fixes Bugs #4+#5).
          ad-cur      (or (:ad-spend pnl-cur) 0.0)
          ad-cost-src (:ad-cost-source pnl-cur)

          ;; LT5 honesty: Ozon preliminary window may have logistics/storage
          ;; published but commission/COGS absent (realization not yet out).
          ;; When stamped by maybe-overlay-preliminary, do NOT fabricate
          ;; a partial-cost profit/margin or surface a false zero.
          ;; Domain pnl-result keeps numeric 0 (safe for sum-total-costs/
          ;; what-if/exports); nil is applied ONLY here in presentation.
          cost-missing? (= :preliminary-missing (:cost-source pnl-cur))
          ;; US2 T025 / US3 T032: response-map bound so we can wrap it in
          ;; compute+encode traces and merge the capability-slot (FR-027/P6).
          response-map
          (mu/trace :marker/compute {}
            {:alerts          alert-list
       :kpis            {:revenue   (-> (build-kpi rev-cur rev-prev rev-spark revenue-src revenue-as-of)
                                        (assoc :date-basis fin-basis :completeness fin-completeness
                                               :basis-note (basis-note fin-basis window-days)
                                               :spark-source :sales))
                         :profit    (-> (build-kpi (when-not cost-missing? (:net-profit pnl-cur))
                                                   (:net-profit pnl-prev) [])
                                        ;; LT5 / I1: build-kpi resolves a nil :value to 0.0; under
                                        ;; :preliminary-missing force it back to nil so profit reads
                                        ;; «нет данных», never a fabricated 0 ₽ (matches :margin below).
                                        (assoc :value (when-not cost-missing? (or (:net-profit pnl-cur) 0.0))
                                               :source (cond
                                                          cost-missing?                              :preliminary-missing
                                                          (pos? (or (:net-profit pnl-cur) 0.0))     revenue-src
                                                          :else                                      :none)
                                               :as-of revenue-as-of
                                               :date-basis fin-basis
                                               :completeness fin-completeness
                                               :basis-note (basis-note fin-basis window-days)
                                               :ad-cost-source ad-cost-src))
                         :orders    {:value     orders-cur
                                     :delta-pct (math/pct-delta orders-cur orders-prev)
                                     :spark     ord-spark
                                     :source    orders-src
                                     :as-of     nil}
                         :purchases {:value     purchases-cur
                                     :delta-pct (math/pct-delta purchases-cur purchases-prev)
                                     :spark     purch-spark
                                     :source    purchases-src
                                     :as-of     nil}
                         ;; TODO: daily realized-qty spark — needs a finance-by-day aggregation,
                         ;; analogous to orders-count-spark. Empty vec until then.
                         :realized  {:value     realized-cur
                                     :delta-pct (math/pct-delta realized-cur realized-prev)
                                     :spark     []
                                     :source    realized-src
                                     :as-of     nil}
                         ;; TODO: daily returns spark — needs finance-by-day aggregation.
                         :returned  {:value     returned-cur
                                     :delta-pct (math/pct-delta returned-cur returned-prev)
                                     :spark     []
                                     :source    returned-src
                                     :as-of     nil}
                         :margin    {:value     (when-not cost-missing?
                                                  (or (:margin-net pnl-cur) 0.0))
                                     :delta-pct (when-not cost-missing?
                                                  (math/pct-delta
                                                    (or (:margin-net pnl-cur) 0.0)
                                                    (or (:margin-net pnl-prev) 0.0)))
                                     :spark     []
                                     :source    (cond
                                                  cost-missing?                              :preliminary-missing
                                                  (pos? (or (:margin-net pnl-cur) 0.0))     revenue-src
                                                  :else                                      :none)
                                     :as-of     revenue-as-of
                                     :date-basis fin-basis
                                     :completeness fin-completeness
                                     :basis-note (basis-note fin-basis window-days)
                                     :ad-cost-source ad-cost-src}
                         :avg-check {:value     ac-cur
                                     :delta-pct (math/pct-delta ac-cur ac-prev)
                                     :spark     []
                                     :source    revenue-src
                                     :as-of     revenue-as-of}
                         ;; «Выкуп (от заказов)» — sold/placed, reconciles with MP cabinet.
                         :buyout    {:value     (or buyout-rate 0.0)
                                     :delta-pct (math/pct-delta
                                                  (or buyout-rate 0.0)
                                                  (or (:buyout-orders-rate buyout-prev) 0.0))
                                     :spark     []
                                     :basis     :orders
                                     :source    buyout-src
                                     :as-of     nil}
                         ;; «% отмен» — cancelled/placed; first-class, since the legacy
                         ;; non-return rate structurally cannot see cancellations.
                         :cancel    {:value     (or cancel-rate 0.0)
                                     :delta-pct (math/pct-delta
                                                  (or cancel-rate 0.0)
                                                  (or (:cancel-rate buyout-prev) 0.0))
                                     :spark     []
                                     :basis     :orders
                                     :source    buyout-src
                                     :as-of     nil}
                         ;; «Доля невозвратов» — the former «Выкуп» (sold/(sold+returns)),
                         ;; renamed to its honest meaning. Realization-based.
                         :non-return {:value     (or non-return 0.0)
                                      :delta-pct (math/pct-delta
                                                   (or non-return 0.0)
                                                   (or (:non-return-rate buyout-prev) 0.0))
                                      :spark     []
                                      :basis     :delivered-units
                                      :source    (if (pos? (+ (:sold buyout-agg)
                                                              (:returned buyout-agg)))
                                                   :realization :none)
                                      :as-of     nil}
                         :roas      {:value          (math/roas rev-cur ad-cur)
                                     :delta-pct      nil
                                     :spark          []
                                     :source         revenue-src
                                     :as-of          revenue-as-of
                                     :ad-cost-source ad-cost-src}
                         :drr       {:value          (math/drr rev-cur ad-cur)
                                     :delta-pct      nil
                                     :spark          []
                                     :source         revenue-src
                                     :as-of          revenue-as-of
                                     :ad-cost-source ad-cost-src}
                         ;; max-ДРР ceiling — the break-even ad-spend rate.
                         ;; Formula (mirrors UE.7): (net-profit + ad-spend) / revenue × 100.
                         ;; When ads are at this ceiling the article hits exactly 0 profit.
                         ;; Above it → over-ceiling? true. Source follows revenue.
                         :drr-ceiling {:value  (math/percentage
                                                 (+ (or (:net-profit pnl-cur) 0.0) ad-cur)
                                                 rev-cur)
                                       :delta-pct nil
                                       :spark     []
                                       :source    revenue-src
                                       :as-of     revenue-as-of}}
       ;; LT5: when cost-missing? (Ozon preliminary, commission/COGS unpublished),
       ;; render :cogs/:commission with nil value + :preliminary-missing source.
       ;; :logistics/:ads/:other ARE published in the realization window → stay real.
       ;; :total is also :preliminary-missing (incomplete; lists :known-components).
       ;; Domain sum-total-costs/what-if/exports continue to use numeric 0 from pnl-cur.
       :costs           {:cogs       (cost-line (:cogs         pnl-cur) (:cogs         pnl-prev) revenue-src revenue-as-of cost-missing?)
                        :commission (cost-line (:mp-commission pnl-cur) (:mp-commission pnl-prev) revenue-src revenue-as-of cost-missing?)
                        :logistics  (cost-line (:logistics pnl-cur) (:logistics pnl-prev) revenue-src revenue-as-of)
                        :ads        (cost-line (:ad-spend  pnl-cur) (:ad-spend  pnl-prev) revenue-src revenue-as-of)
                        :other      (cost-line (sum-other-costs pnl-cur) (sum-other-costs pnl-prev) revenue-src revenue-as-of)
                        :total      (if cost-missing?
                                      {:value            nil
                                       :delta-pct        nil
                                       :source           :preliminary-missing
                                       :as-of            revenue-as-of
                                       :known-components [:logistics :ads :other]}
                                      (cost-line (sum-total-costs pnl-cur) (sum-total-costs pnl-prev) revenue-src revenue-as-of))}
       :forecast        {:month-plan nil           ; TODO: wire to domain.plan DB once plans exist
                         :month-fact (math/round2 rev-cur)
                         :projection projection}
       :charts          (cond-> {:revenue-30d  rev-spark
                                 :orders-by-mp orders-by-mp
                                 :mp-share     mp-share}
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
       :preliminary-as-of (or (:preliminary-as-of pnl-cur) nil)
       ;; LT3: top-level honesty envelope (mirrors pnl/sku-list/reports). The
       ;; SPA's ::subs/active-coverage reads this to drive the topbar coverage
       ;; chip; :empty here suppresses the «полные данные» lie on a no-data Pulse.
       :completeness    fin-completeness
       :date-basis      fin-basis
       ;; Cost-price coverage warning. cost_prices table is sparse
       ;; (~9% of articles have a registered cost). For any sale row
       ;; without cost-price, line-cost defaults to 0 → cogs = 0 → P&L
       ;; profit is an upper bound, not actual. UI shows a warning when
       ;; coverage is below the threshold.
       :cost-coverage   (let [fin-arts     (try (finance/by-article fin-cur)
                                                 (catch Exception _ []))
                              ;; Only count articles that actually had sales
                              ;; in this period — orphan service-only rows
                              ;; without sales naturally have no cost.
                              with-sales   (filter #(pos? (or (:sales-qty %) 0))
                                                   fin-arts)
                              arts-total   (count with-sales)
                              arts-with    (count (filter #(pos? (or (:total-cost %) 0))
                                                          with-sales))
                              pct          (if (pos? arts-total)
                                             (math/round2 (* 100.0 (/ arts-with arts-total)))
                                             100.0)]
                          {:articles-with-cost arts-with
                           :articles-total     arts-total
                           :coverage-pct       pct
                           :complete?          (>= pct 90.0)})})]
      ;; US2 T025: encode inner trace wraps final encoding step.
      ;; US3 T032: merge :capabilities NEXT TO :completeness/:date-basis/:preliminary?
      ;; (FR-027/P6) — pulse-capability-envelope is additive, never truncates payload.
      (mu/trace :marker/encode {}
        (pulse-capability-envelope response-map)))
    (catch Exception e
      {:error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; B1c. stocks-overview — by-warehouse + by-article + drilldown
;; ---------------------------------------------------------------------------

(defn- days->status
  "Map days-of-cover to a status badge keyword consistent with the
   critical-stocks alert thresholds."
  [days]
  (cond
    (nil? days)  "ok"
    (< days 7)   "danger"
    (< days 14)  "warning"
    :else        "success"))

(defn- by-warehouse-rich
  "Like stock/by-warehouse but also carries :in-way-to and :in-way-from
   which the «Склады» page needs to surface transit pipeline."
  [stocks]
  (->> stocks
       (group-by :warehouse)
       (map (fn [[wh items]]
              {:warehouse     (or wh "—")
               :articles      (count (distinct (map :article items)))
               :quantity      (reduce + 0 (map #(or (:quantity %) 0) items))
               :quantity-full (reduce + 0 (map #(or (:quantity-full %) 0) items))
               :in-way-to     (reduce + 0
                                      (map #(or (:in-way-to %)
                                                (:in-way-to-client %) 0)
                                           items))
               :in-way-from   (reduce + 0
                                      (map #(or (:in-way-from %)
                                                (:in-way-from-client %) 0)
                                           items))}))
       (sort-by (fn [r] (- (or (:quantity-full r) 0))))))

(defn stocks-overview-handler
  "Handler for GET /api/v1/marker/stocks/overview
   Query params: ?mp=wb|ozon|ym|all, ?category=, ?brand=
   Returns {:totals       {:quantity :quantity-full :in-way-to :in-way-from
                            :warehouses :articles}
            :by-warehouse [{:warehouse :articles :quantity :quantity-full
                            :in-way-to :in-way-from}]
            :by-article   [{:article :subject :quantity :quantity-full
                            :in-way-to :in-way-from :warehouses
                            :daily-rate :days :status}]}"
  [{:keys [params]}]
  (try
    (let [mp-param (:mp params)
          mps      (cond (or (nil? mp-param) (= mp-param "all")) nil
                         :else (keyword mp-param))
          category (:category params)
          brand    (:brand params)
          stocks-raw (stock/fetch-stocks :marketplace mps)
          stocks    (cond->> stocks-raw
                      category (filter #(= category (:category %)))
                      brand    (filter #(= brand (:brand %))))

          by-art  (stock/by-article stocks)
          by-wh   (by-warehouse-rich stocks)

          today    (java.time.LocalDate/now)
          from-d   (.minusDays today 30)
          sales    (try (sales/fetch-sales
                          {:from (str from-d) :to (str today)}
                          :marketplace mps)
                        (catch Exception _ []))
          enriched (try (stock/with-turnover by-art sales 30)
                        (catch Exception _ by-art))
          by-art*  (mapv (fn [r]
                           (let [d (:days-left r)]
                             {:article       (:article r)
                              :subject       (:subject r)
                              :quantity      (or (:quantity r) 0)
                              :quantity-full (or (:quantity-full r) 0)
                              :in-way-to     (or (:in-way-to r) 0)
                              :in-way-from   (or (:in-way-from r) 0)
                              :warehouses    (or (:warehouses r) 0)
                              :daily-rate    (or (:daily-rate r) 0.0)
                              :days          d
                              :status        (days->status d)}))
                         enriched)

          totals  {:quantity      (reduce + 0 (map :quantity by-wh))
                   :quantity-full (reduce + 0 (map :quantity-full by-wh))
                   :in-way-to     (reduce + 0 (map :in-way-to by-wh))
                   :in-way-from   (reduce + 0 (map :in-way-from by-wh))
                   :warehouses    (count by-wh)
                   :articles      (count by-art)}]
      {:status 200
       :body   {:totals       totals
                :by-warehouse (vec by-wh)
                :by-article   by-art*}})
    (catch Exception e
      {:status 500
       :body   {:error (.getMessage e)}})))

(defn stock-article-handler
  "Handler for GET /api/v1/marker/stocks/article/:article
   Query params: ?mp=wb|ozon|ym|all, ?from=, ?to=
   Returns {:per-warehouse [{:warehouse :marketplace :quantity :quantity-full
                              :in-way-to :in-way-from}]
            :history       [{:date :quantity :in-way-to}]}"
  [{:keys [params path-params]}]
  (try
    (let [article  (or (:article path-params) (:article params))
          mp-param (:mp params)
          mps      (cond (or (nil? mp-param) (= mp-param "all")) nil
                         :else (keyword mp-param))
          today    (java.time.LocalDate/now)
          from-d   (.minusDays today 30)
          from     (or (:from params) (str from-d))
          to       (or (:to   params) (str today))

          stocks   (->> (stock/fetch-stocks :marketplace mps)
                        (filter #(= article (:article %))))
          per-wh   (mapv (fn [s]
                           {:warehouse     (:warehouse s)
                            :marketplace   (:marketplace s)
                            :quantity      (or (:quantity s) 0)
                            :quantity-full (or (:quantity-full s) 0)
                            :in-way-to     (or (:in-way-to s)
                                              (:in-way-to-client s) 0)
                            :in-way-from   (or (:in-way-from s)
                                              (:in-way-from-client s) 0)})
                         stocks)

          history-raw (try (stock/fetch-history from to
                                                :marketplace mps
                                                :article article)
                           (catch Exception _ []))
          history (->> history-raw
                       (group-by :snapshot-date)
                       (mapv (fn [[d rows]]
                               {:date      d
                                :quantity  (reduce + 0 (map #(or (:quantity %) 0) rows))
                                :in-way-to (reduce + 0 (map #(or (:in-way-to %)
                                                                 (:in-way-to-client %) 0)
                                                            rows))}))
                       (sort-by :date)
                       vec)]
      {:status 200
       :body   {:per-warehouse per-wh
                :history       history}})
    (catch Exception e
      {:status 500
       :body   {:error (.getMessage e)}})))

;; ---------------------------------------------------------------------------
;; B2. pnl
;; ---------------------------------------------------------------------------

(def pnl-row-defs
  "Definition of P&L rows for the SPA table.
   Each entry: [key label group]"
  [[:revenue       "Выручка (розница)"        "income"]
   [:wb-reward     "Возмещение ПВЗ"            "income"]
   [:for-pay       "К выплате от МП"           "subtotal"]
   [:mp-commission "Комиссия МП"               "cost"]
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
          pnl-prev (compute-pnl fin-prev prev   mp1)

          ;; 015 T039: assemble the generalised management block (cf + OPEX +
          ;; tax-config) for the current window. :cf carries the Ozon cash-flow
          ;; adjustments (nil for WB/YM) — this SUPERSEDES the standalone
          ;; load-cf-adjustments call; the :cf key is the same map. When no
          ;; tax/opex is configured, :configured? is false and the management
          ;; keys are inert (profit == net-profit, FR-016).
          mgmt-blk (try (pnl/load-management-adjustments (:from period) (:to period) mp1)
                        (catch Exception _ nil))
          cf-adj   (:cf mgmt-blk)
          ;; Current-period P&L WITH cf-adjustments AND the management layer.
          ;; pnl/calculate accepts :cf-adjustments + :management; compute-pnl
          ;; 5-arity passes both through.
          pnl-cur  (compute-pnl fin-cur period mp1 cf-adj mgmt-blk)

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

          ;; ── 016-US3 layered P&L waterfall (§0.1 LOCKED GROSS top-line) ──
          ;; Pure re-composition of pnl-cur; netProfit == pnl :net-profit by
          ;; construction. ?compare=true attaches per-line deltas vs prev period.
          compare? (contains? #{"true" "1" "yes"}
                              (some-> (or (:compare params) (get params "compare"))
                                      clojure.core/str str/lower-case))
          wf       (if compare?
                     (pnl/waterfall pnl-cur :comparison pnl-prev)
                     (pnl/waterfall pnl-cur))

          ;; Per-SKU breakdown via finance/by-article
          by-art   (try (finance/by-article fin-cur) (catch Exception _ []))
          ads-by-art (try (pnl/ad-spend-by-article (:from period) (:to period) mp1)
                          (catch Exception _ {}))
          sku-det  (mapv (fn [a]
                           (let [art (str (:article a))]
                             {:id         art
                              :name       (or (:subject a) (:article a))
                              :mp         [(keyword (or (:marketplace a) :wb))]
                              :revenue    (or (:revenue a) 0.0)
                              :cogs       (or (:total-cost a) 0.0)
                              :commission (- (Math/abs (double (or (:mp-commission a) 0.0))))
                              :ads        (or (get ads-by-art art) 0.0)
                              :net        (or (:for-pay a) 0.0)}))
                         by-art)]
      (merge {:rows              rows
              :waterfall         (:waterfall wf)
              :sku-detail        sku-det
              :preliminary?      (boolean (:preliminary? pnl-cur))
              :preliminary-as-of (:preliminary-as-of pnl-cur)}
             (basis-envelope fin-cur (boolean (:preliminary? pnl-cur)))
             ;; 015 T039: surface the management-layer keys (tax/opex/profit …)
             ;; at the response top level (contract §3). Only the keys actually
             ;; present on pnl-cur are copied — when management is inert-nil
             ;; none exist and the response is byte-identical to pre-015.
             (select-keys pnl-cur management-response-keys)))
    (catch Exception e
      {:rows [] :sku-detail [] :error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; B3. sku-list
;; ---------------------------------------------------------------------------

(defn filter-orphan-skus
  "Drop SKUs that had no activity in the window — revenue=0 AND orders=0.

   These are typically Ozon 'orphan service' rows: articles with
   logistics/storage costs from a previous period whose sale rows were
   redistributed away by the in-window filter. They clutter the catalog
   without representing current activity. Set ?include-orphans=true on
   the sku-list endpoint to opt back in."
  [skus]
  (filterv (fn [s]
             (or (pos? (or (:revenue s) 0))
                 (pos? (or (:orders s) 0))))
           skus))

(defn- max-drr-numerator
  "Break-even ad-spend numerator for max-ДРР ceiling (FR-005 / UE.7).
   Returns for-pay − cogs − logistics − storage − penalties − acceptance.
   All variable-cost fields coalesce to 0 when absent.
   When all variable costs are zero the result equals the old gross-margin
   proxy (for-pay − cogs), preserving WB/quantity-less behaviour."
  [for-pay cogs logistics storage penalties acceptance]
  (- for-pay cogs
     (or logistics   0.0)
     (or storage     0.0)
     (or penalties   0.0)
     (or acceptance  0.0)))

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
          ads-by-art (try (pnl/ad-spend-by-article from to mp1) (catch Exception _ {}))

          ;; ── 016-US2 capitalization / GMROI / turnover (T022) ──
          ;; days-in-period for annualization + turnover daily-rate.
          days-in-period (try (inc (.until (period/parse-date from) (period/parse-date to)
                                           java.time.temporal.ChronoUnit/DAYS))
                              (catch Exception _ 30))
          ;; cost basis lookup (nil ⇒ N/A, FR-013).
          cost-fn        (fn [art] (cost-price/get-price art))
          ;; period weighted-avg retail per article (FR-008, NOT last-full-week).
          wavg-by-art    (->> (group-by :article sales-cur)
                              (map (fn [[art rows]] [art (stock/wavg-retail rows)]))
                              (into {}))
          ;; per-day stocks_history for the window, grouped by article (coverage-aware GMROI).
          history-rows   (try (stock/fetch-history from to :marketplace mp1) (catch Exception _ []))
          history-by-art (group-by :article history-rows)
          ;; capitalization aggregate + totals (VR-c1/FR-014).
          cap-result     (stock/capitalize stocks :cost-fn cost-fn
                                            :price-fn #(get wavg-by-art %))
          cap-by-art     (->> (:per-sku cap-result)
                              (map (fn [c] [(:article c) c]))
                              (into {}))

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
                                  buyout    (math/percentage orders (+ orders returns))
                                  ads       (or (get ads-by-art art) 0.0)
                                  roas      (math/roas rev ads)
                                  ;; max-ДРР ceiling (FR-005 / UE.7).
                                  ;; Numerator = for-pay − cogs − logistics − storage − penalties − acceptance.
                                  ;; These fields ARE carried by finance/by-article rows (article-row, finance.clj).
                                  ;; max-drr-pct = numerator / revenue × 100 (break-even ad-spend rate).
                                  logistics-a   (or (:logistics   a) 0.0)
                                  storage-a     (or (:storage     a) 0.0)
                                  penalties-a   (or (:penalties   a) 0.0)
                                  acceptance-a  (or (:acceptance  a) 0.0)
                                  max-drr-numer (max-drr-numerator for-pay cogs logistics-a storage-a penalties-a acceptance-a)
                                  max-drr-pct   (math/percentage max-drr-numer rev)
                                  drr-pct         (math/percentage ads rev)
                                  drr-headroom    (math/round2 (- (or max-drr-pct 0.0)
                                                                   (or drr-pct 0.0)))
                                  over-ceiling?   (boolean (and max-drr-pct drr-pct
                                                                (> drr-pct max-drr-pct)))
                                  ;; ── 016-US2 capitalization / GMROI / turnover (T022) ──
                                  cap           (get cap-by-art art)
                                  ;; per-SKU MP net profit = gross-profit − ad (TS-def GMROI numerator).
                                  ;; nil when this SKU has no cost basis (FR-013 propagation).
                                  net-profit    (when (:unit-cost-basis cap)
                                                  (math/round2 (- for-pay cogs ads)))
                                  gmroi-map     (stock/gmroi-inputs
                                                  {:article         art
                                                   :unit-cost-basis (:unit-cost-basis cap)
                                                   :net-profit      net-profit
                                                   :history         (get history-by-art art [])
                                                   :days-in-period  days-in-period})
                                  ;; turnover Σqty from finance sales-qty (orders) over the period.
                                  daily-rate    (math/safe-div orders days-in-period)
                                  days-of-cover (when (pos? daily-rate)
                                                  (math/round2 (/ (double qty-full) daily-rate)))]
                              {:id               art
                               :name             (or (:subject a) art)
                               :mp               [(keyword (or (:marketplace a) :wb))]
                               :revenue          rev
                               :orders           orders
                               :margin           (or margin 0.0)
                               :buyout           (or buyout 0.0)
                               :stock            qty-full
                               :delta-pct        (math/pct-delta rev prev-r)
                               :ads-cost         ads
                               :roas             roas
                               :max-drr-pct      max-drr-pct
                               :drr-headroom-pct drr-headroom
                               :over-ceiling?    over-ceiling?
                               ;; capitalization + coverage-aware GMROI + turnover
                               :cap-by-cost      (:cap-by-cost cap)
                               :cap-by-price     (:cap-by-price cap)
                               :gmroi            (:gmroi gmroi-map)
                               :gmroi-annualized (:gmroi-annualized gmroi-map)
                               :covered-days     (:covered-days gmroi-map)
                               :days-of-cover    days-of-cover
                               :spark            []}))  ; TODO: per-article daily spark expensive — defer to Phase 8
                          by-art)
          ;; Default: drop orphan service-only SKUs (revenue=0 AND orders=0).
          ;; Pass ?include-orphans=true to see them.
          include-orphans? (= "true" (or (:include-orphans params)
                                         (get params "include-orphans")))
          skus-active (if include-orphans? skus (filter-orphan-skus skus))
          limit     (some-> (get params :limit) parse-long)
          offset    (or (some-> (get params :offset) parse-long) 0)
          skus-paged (cond->> skus-active
                       true   (drop offset)
                       limit  (take limit)
                       true   vec)]
      (merge {:skus   skus-paged
              ;; 016-US2 totals (T022) per contracts/stock-capitalization.edn:
              ;; :cap-by-cost-total / :cap-by-price-total / :stock-qty-total / :na-cost-count.
              :totals (:totals cap-result)}
             (basis-envelope fin-cur false)))
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

          ;; P&L for this article. Preliminary overlay can't be applied
          ;; per-article (cash_flow_periods is per-MP, not per-SKU), so
          ;; we only fall back to sales-derived revenue when canonical
          ;; is empty for an Ozon SKU in the current month — keeps the
          ;; tile from going to 0 right after a month rolls over.
          sales-art-rev (reduce + 0.0
                                (map (fn [s]
                                       (or (:for-pay s)
                                           (:price-with-disc s)
                                           (:finished-price s)
                                           (:total-price s) 0))
                                     (filter #(= :sale (:type %)) art-sales)))
          pnl-cur  (let [base (if (seq fin-art)
                                (compute-pnl fin-art period mp1)
                                {:revenue 0.0 :net-profit 0.0 :margin-net 0.0 :ad-spend 0.0})]
                     (if (and (zero? (or (:revenue base) 0.0))
                              (pos? sales-art-rev))
                       (assoc base
                              :revenue           sales-art-rev
                              :preliminary?      true
                              :revenue-source    :sales-table)
                       base))
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

      (let [rev-val       (or (:revenue pnl-cur) 0.0)
            ads-val       (or (:ad-spend pnl-cur) 0.0)
            np-val        (or (:net-profit pnl-cur) 0.0)
            max-drr-pct   (math/percentage (+ np-val ads-val) (if (pos? rev-val) rev-val 1.0))
            drr-pct       (math/percentage ads-val (if (pos? rev-val) rev-val 1.0))
            over-ceiling? (boolean (and (pos? rev-val) max-drr-pct drr-pct (> drr-pct max-drr-pct)))
            basis-env     (basis-envelope fin-art (boolean (:preliminary? pnl-cur)))]
      {:id       sku-id
       :name     subject
       :nm-id    nm-id
       :subject  subject
       :mp       (vec mps-list)
       :kpis     {:revenue     {:value     rev-val
                                :delta-pct (math/pct-delta
                                             rev-val
                                             (or (:revenue pnl-prev) 0.0))}
                  :orders      {:value (or (:sales-qty agg) 0)}
                  :margin      {:value (or (:margin-net pnl-cur) 0.0)}
                  :ads         {:value ads-val}
                  :max-drr-pct {:value max-drr-pct}}
       :over-ceiling?   over-ceiling?
       :revenue-30d  rev-spark
       :plan-fact    {:plan       nil
                      :fact       (math/round2 (or (:revenue pnl-cur) 0.0))
                      :projection proj}
       :stocks-by-mp (if (seq stk-by-mp) stk-by-mp [])
       :preliminary? (boolean (:preliminary? pnl-cur))
       :date-basis   (:date-basis   basis-env)
       :completeness (:completeness basis-env)}))
    (catch Exception e
      {:id    (get-in request [:params :sku-id] "")
       :error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; B4b. unit-baseline — derive per-unit calculator inputs from real article data
;; ---------------------------------------------------------------------------
;;
;; What it does:
;;   Takes an article + period and aggregates finance/sales/ad data into the
;;   per-unit shape consumed by the unit-econ what-if calculator (price, cogs,
;;   commission %, logistics ₽/шт, returns %, ads ₽/шт).
;;
;; Sales-qty in by-article counts only sale rows; returns-qty counts return
;; rows. Per-unit denominators:
;;   - price          = revenue / sales-qty                (gross per sold unit)
;;   - cogs           = :cost-price                        (already per-unit)
;;   - commission %   = mp-commission / revenue × 100      (% of gross)
;;   - logistics ₽/шт = logistics / (sales-qty + returns-qty) (per shipped unit)
;;   - returns %      = returns-qty / (sales-qty + returns-qty) × 100
;;   - ads ₽/шт       = ad-cost / sales-qty
;;
;; When no data exists for the article in the period, returns :found? false
;; and zeros so the UI can show a graceful empty state.

(defn- safe-div [n d]
  (if (and d (pos? d)) (double (/ n d)) 0.0))

(defn unit-baseline-handler
  "Handler for GET /api/v1/marker/unit-baseline?article=...
   Optional: ?from&to (period), ?mp=wb|ozon|ym (single MP).

   Returns:
     {:article  string
      :name     string
      :marketplace kw|nil
      :period   {:from iso :to iso}
      :found?   bool
      :qty      int      ; sales-qty
      :revenue  double
      :params   {:price :cogs :commission :logistics :returns :ads}}"
  [request]
  (try
    (let [params  (:params request)
          article (or (:article params) (get params "article") "")
          period  (parse-period-params params)
          mp1     (let [mps (parse-mp-param params)]
                    (when (and mps (= 1 (count mps))) (first mps)))
          fin-all (load-finance period mp1)
          fin-art (filter #(= article (or (:article %) "")) fin-all)
          by-art  (try (finance/by-article fin-art) (catch Exception _ []))
          agg     (first by-art)
          qty     (long (or (:sales-qty agg) 0))
          ret-qty (long (or (:returns-qty agg) 0))
          shipped (+ qty ret-qty)
          revenue (double (or (:revenue agg) 0.0))
          ads-by-art (try (pnl/ad-spend-by-article (:from period) (:to period) mp1)
                          (catch Exception _ {}))
          ad-cost (double (or (get ads-by-art article) 0.0))
          mp-out  (or mp1
                      (some-> (:marketplace agg) keyword))
          price       (math/round2 (safe-div revenue qty))
          cogs        (math/round2 (or (:cost-price agg) 0.0))
          commission  (math/round2 (* (safe-div (or (:mp-commission agg) 0.0) revenue) 100.0))
          logistics   (math/round2 (safe-div (or (:logistics agg) 0.0) shipped))
          returns-pct (math/round2 (* (safe-div ret-qty shipped) 100.0))
          ads-per     (math/round2 (safe-div ad-cost qty))]
      {:article     article
       :name        (or (:subject agg) article)
       :marketplace mp-out
       :period      period
       :found?      (boolean (and agg (pos? qty)))
       :qty         qty
       :returns-qty ret-qty
       :revenue     (math/round2 revenue)
       :params      {:price      price
                     :cogs       cogs
                     :commission commission
                     :logistics  logistics
                     :returns    returns-pct
                     :ads        ads-per}})
    (catch Exception e
      {:article (get-in request [:params :article] "")
       :found?  false
       :error   (.getMessage e)})))

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

        ;; ROAS: revenue / ads spend (returns nil below noise threshold so
        ;; calculator stays consistent with Pulse / sku-list display)
        roas           (math/roas effective-rev ads)

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
              compare-blk (:compare data)
              ;; ── 016 US5 — user-metric constructor ──
              ;; Merge saved user-metric descriptors into :columns and compute
              ;; each row's value via eval-user-metric BEFORE returning (i.e.
              ;; before any client-side sort/filter/pagination), so a user
              ;; metric renders / sorts / filters exactly like a built-in column.
              ;; Additive: when no user metrics exist, columns/rows are unchanged.
              ;; Fetch the metrics ONCE: descriptors (for :columns) come from
              ;; user-metric->descriptor; row values come from the metric's
              ;; :slug + :formula via eval-user-metric.
              user-metrics (try (rs/fetch-user-metrics) (catch Exception _ []))
              user-descs   (mapv rs/user-metric->descriptor user-metrics)
              enrich-user  (fn [rows]
                             (if (seq user-metrics)
                               (mapv (fn [row]
                                       (reduce (fn [r {:keys [slug formula]}]
                                                 (assoc r slug (rs/eval-user-metric formula row)))
                                               row user-metrics))
                                     rows)
                               rows))
              ;; LT3: compute honesty envelope (single source of truth).
              ;; One extra load-finance call; reports don't expose raw finance
              ;; rows from report-data, so we fetch separately.
              fin-env     (load-finance period mp1)
              pnl-env     (compute-pnl  fin-env period mp1)
              env         (basis-envelope fin-env (boolean (:preliminary? pnl-env)))]
          {:status 200
           :body   (cond-> {:report-type  rtype
                            :columns      (into (vec (:columns schema)) user-descs)
                            :rows         (enrich-user (vec (:rows data)))
                            :totals       (or (:totals data) {})
                            :schema       (select-keys schema
                                                       [:id :title :rows-mode
                                                        :supports-compare?
                                                        :supports-period?
                                                        :supports-marketplace?
                                                        :tabs :presets :kpi
                                                        :drill-down :chart])
                            :completeness (:completeness env)
                            :date-basis   (:date-basis   env)
                            :preliminary? (boolean (:preliminary? pnl-env))}
                     compare-blk (assoc :compare {:rows   (enrich-user (vec (:rows compare-blk)))
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

(defn report-chart-handler
  "Handler for GET /api/v1/marker/chart/:type
   Query params: ?from=&to= (or omitted → last-30-days), ?mp=, ?compare=true.

   Wraps `analitica.web.api.charts/report-chart-data` for the SPA so the
   Динамика / Товары tabs can render Chart.js charts instead of (or
   alongside) the schema-driven tables. The backend already produces
   Chart.js-shaped {:labels [...] :datasets [...]} payloads."
  [request]
  (try
    (let [params      (:params request)
          type-str    (or (get-in request [:path-params :type])
                          (:type params))
          report-type (->report-type type-str)
          period      (parse-period-params params)
          mps         (parse-mp-param params)
          mp1         (when (and mps (= 1 (count mps))) (first mps))
          compare-kw  (if (compare? params) :prev :none)]
      (cond
        (nil? report-type)
        {:status 400
         :body   {:error (str "Unknown report type: "
                              (or type-str "(missing)"))
                  :known (mapv name (rs/all-report-types))}}

        :else
        {:status 200
         :body   (charts/report-chart-data report-type period
                                           :marketplace mp1
                                           :compare     compare-kw)}))
    (catch Exception e
      {:status 500
       :body   {:error (.getMessage e)}})))

;; ---------------------------------------------------------------------------
;; B6. reconciliation (FR-P4.6)
;; ---------------------------------------------------------------------------

(defn reconciliation-handler
  "Handler for GET /api/v1/marker/reconciliation

   Query params:
     from        YYYY-MM-DD (required; falls back to last-30-days default)
     to          YYYY-MM-DD (required; falls back to last-30-days default)
     mp          single marketplace keyword: wb | ozon | ym (optional; nil = all MPs)

   Returns:
     {:pnl-total    double
      :payout-total double
      :delta        double   ; payout − pnl
      :per-article  [{:article :pnl :payout :delta} ...]}"
  [request]
  (try
    (let [params  (:params request)
          period  (parse-period-params params)
          mp1     (let [mps (parse-mp-param params)]
                    (when (and mps (= 1 (count mps))) (first mps)))]
      {:status 200
       :body   (reconciliation/pnl-vs-payout (:from period) (:to period) mp1)})
    (catch Exception e
      (mu/log ::reconciliation-error
              :error-message (.getMessage e)
              :error-type    (type e))
      {:status 500
       :body   {:error (.getMessage e)}})))
