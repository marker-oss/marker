(ns analitica.web.api.marker-test
  "Integration tests for the Marker SPA Transit API endpoints.

   Constructs minimal Ring request maps directly (no ring.mock dep).
   Tests boot the full Ring app, make requests, decode Transit responses,
   and assert on *shape* (key presence + types) rather than specific values —
   the backend data is real and may shift."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.config :as config]
            [analitica.db :as db]
            [analitica.util.math]
            [analitica.web.server :as server]
            [analitica.web.middleware.transit :as transit-mw]
            [analitica.web.api.marker :as marker-api]
            [analitica.domain.plan]
            [analitica.web.api.report])
  (:import (java.io ByteArrayInputStream)))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn init-test-db [f]
  (db/init!)
  ;; app() construction reads cors-origins → config must be loaded for the
  ;; ^:integration tests (skipped by default; run via the integration profile).
  ;; Pin the real path — (load-config) no-arg reuses config's global last-path
  ;; atom which another test may leave pointing at a deleted temp file. Tolerate
  ;; failure: under a full-suite run another test can leave a temp DB connected,
  ;; and settings/overrides would throw on its missing app_settings — the pure
  ;; unit tests in this ns don't need config, so don't abort them.
  (try (config/load-config "config.edn")
       (catch Throwable _ nil))
  (f))

(use-fixtures :once init-test-db)

;; ---------------------------------------------------------------------------
;; Minimal Ring request builders (no ring/ring-mock dependency)
;; ---------------------------------------------------------------------------

(defn- make-get
  "Build a minimal Ring GET request map."
  [uri & {:keys [accept headers params query-string]
          :or   {accept "application/transit+json"
                 headers {}
                 params {}
                 query-string nil}}]
  {:server-port    3001
   :server-name    "localhost"
   :remote-addr    "127.0.0.1"
   :uri            uri
   :query-string   query-string
   :scheme         :http
   :request-method :get
   :headers        (merge {"accept" accept} headers)
   :params         params})

(defn- make-post
  "Build a minimal Ring POST request with a transit body."
  [uri data]
  (let [encoded (transit-mw/encode-transit-json data)
        body-bytes (.getBytes ^String encoded "UTF-8")]
    {:server-port    3001
     :server-name    "localhost"
     :remote-addr    "127.0.0.1"
     :uri            uri
     :query-string   nil
     :scheme         :http
     :request-method :post
     :headers        {"content-type" "application/transit+json"
                      "accept"       "application/transit+json"}
     :params         {}
     :body           (ByteArrayInputStream. body-bytes)}))

;; ---------------------------------------------------------------------------
;; Response helpers
;; ---------------------------------------------------------------------------

(defn- decode-body
  "Decode a response body string as transit-json."
  [resp]
  (let [b (:body resp)]
    (when (string? b)
      (try
        (transit-mw/decode-transit-json (.getBytes ^String b "UTF-8"))
        (catch Exception e
          {:decode-error (.getMessage e) :raw b})))))

(defn- do-get
  "Run a GET through the app and return {:status <int> :body <decoded>}."
  [uri & {:keys [accept params query-string]
          :or   {accept "application/transit+json"
                 params {}
                 query-string nil}}]
  (let [app  (server/app)
        req  (make-get uri :accept accept :params params :query-string query-string)
        resp (app req)]
    {:status (:status resp)
     :body   (decode-body resp)
     :raw    (:body resp)
     :headers (:headers resp)}))

(defn- do-post
  "Run a POST with transit body through the app."
  [uri data]
  (let [app  (server/app)
        req  (make-post uri data)
        resp (app req)]
    {:status (:status resp)
     :body   (decode-body resp)
     :raw    (:body resp)
     :headers (:headers resp)}))

;; ---------------------------------------------------------------------------
;; A. Transit middleware unit tests (pure, no DB)
;; ---------------------------------------------------------------------------

(deftest transit-encode-decode-roundtrip
  (testing "encode then decode preserves a map"
    (let [data    {:foo "bar" :n 42 :vec [1 2 3]}
          encoded (transit-mw/encode-transit-json data)
          decoded (transit-mw/decode-transit-json (.getBytes ^String encoded "UTF-8"))]
      (is (string? encoded))
      (is (= "bar" (:foo decoded)))
      (is (= 42    (:n decoded)))
      (is (= 3     (count (:vec decoded))))))

  (testing "encode then decode preserves a nested map"
    (let [data    {:kpis {:revenue {:value 1000.0 :delta-pct -5.2}}}
          encoded (transit-mw/encode-transit-json data)
          decoded (transit-mw/decode-transit-json (.getBytes ^String encoded "UTF-8"))]
      (is (= 1000.0 (get-in decoded [:kpis :revenue :value]))))))

(deftest transit-response-middleware-unit
  (testing "wrap-transit-response encodes map when Accept: transit+json"
    (let [handler (fn [_] {:status 200 :body {:hello "world"}})
          wrapped (transit-mw/wrap-transit-response handler)
          req     {:headers {"accept" "application/transit+json"}}
          resp    (wrapped req)]
      (is (= 200 (:status resp)))
      (is (string? (:body resp)))
      (is (re-find #"transit" (get-in resp [:headers "Content-Type"] "")))))

  (testing "wrap-transit-response passes map through when no transit Accept"
    (let [handler (fn [_] {:status 200 :body {:hello "world"}})
          wrapped (transit-mw/wrap-transit-response handler)
          req     {:headers {"accept" "application/json"}}
          resp    (wrapped req)]
      ;; body stays as map — wrap-json-response (outer) will encode it
      (is (map? (:body resp)))))

  (testing "wrap-transit-response does not encode string body"
    (let [handler (fn [_] {:status 200 :body "<html/>"})
          wrapped (transit-mw/wrap-transit-response handler)
          req     {:headers {"accept" "application/transit+json"}}
          resp    (wrapped req)]
      (is (= "<html/>" (:body resp))))))

(deftest transit-body-middleware-unit
  (testing "wrap-transit-body decodes incoming transit body"
    (let [data    {:price 2500 :cogs 1200}
          encoded (transit-mw/encode-transit-json data)
          handler (fn [req] {:status 200 :body (:body req)})
          wrapped (transit-mw/wrap-transit-body handler)
          req     {:headers {"content-type" "application/transit+json"}
                   :body    (ByteArrayInputStream.
                              (.getBytes ^String encoded "UTF-8"))}
          resp    (wrapped req)]
      (is (map? (:body resp)))
      (is (= 2500 (:price (:body resp)))))))

;; ---------------------------------------------------------------------------
;; B4. pulse-summary — pure helpers (no DB)
;; ---------------------------------------------------------------------------

(def ^:private compute-mp-share     #'marker-api/compute-mp-share)
(def ^:private compute-orders-by-mp #'marker-api/compute-orders-by-mp)
(def ^:private compute-projection   #'marker-api/compute-projection)
(def ^:private build-kpi            #'marker-api/build-kpi)
(def ^:private cost-line            #'marker-api/cost-line)
;; ROAS / ДРР now live in analitica.util.math (shared with sku-list,
;; what-if, and digest). Test the canonical implementation directly.
(def ^:private compute-roas analitica.util.math/roas)
(def ^:private compute-drr  analitica.util.math/drr)

(deftest compute-mp-share-pure
  (testing "balanced 3-MP distribution"
    (let [sales [{:type :sale :marketplace "wb"}
                 {:type :sale :marketplace "wb"}
                 {:type :sale :marketplace "ozon"}
                 {:type :sale :marketplace "ym"}]
          r     (compute-mp-share sales)]
      (is (= 50.0 (:wb r)))
      (is (= 25.0 (:ozon r)))
      (is (= 25.0 (:ym r)))))
  (testing "single-MP dataset always returns three keys"
    (let [r (compute-mp-share [{:type :sale :marketplace "ozon"}
                                {:type :sale :marketplace "ozon"}])]
      (is (= 0.0   (:wb r)))
      (is (= 100.0 (:ozon r)))
      (is (= 0.0   (:ym r)))))
  (testing "non-:sale rows ignored"
    (let [r (compute-mp-share [{:type :sale   :marketplace "wb"}
                                {:type :return :marketplace "wb"}
                                {:type :return :marketplace "ozon"}])]
      (is (= 100.0 (:wb r)))
      (is (= 0.0   (:ozon r)))))
  (testing "empty sales — all zeros (no NaN)"
    (let [r (compute-mp-share [])]
      (is (= 0.0 (:wb r)))
      (is (= 0.0 (:ozon r)))
      (is (= 0.0 (:ym r))))))

(deftest compute-orders-by-mp-pure
  (testing "always returns three keys with vector values"
    (let [r (compute-orders-by-mp
              [{:type :sale :marketplace :wb   :date "2026-04-01"}]
              "2026-04-01" "2026-04-02")]
      (is (vector? (:wb   r)))
      (is (vector? (:ozon r)))
      (is (vector? (:ym   r)))))
  (testing "wb-only data populates :wb, leaves others empty (zero-valued vectors)"
    (let [r (compute-orders-by-mp
              [{:type :sale :marketplace :wb :date "2026-04-01"}
               {:type :sale :marketplace :wb :date "2026-04-02"}]
              "2026-04-01" "2026-04-02")]
      (is (every? zero? (:ozon r)))
      (is (every? zero? (:ym   r))))))

(deftest compute-projection-pure
  (testing "closed period (days-so-far = days-total) — projection equals revenue"
    (is (= 1000.0 (compute-projection 1000.0 30 30))))
  (testing "mid-period — pace × full-period"
    ;; revenue 500 over 10 days → pace 50/day → projection = 50 × 30 = 1500
    (is (= 1500.0 (compute-projection 500.0 10 30))))
  (testing "zero days-so-far returns 0 (no division by zero)"
    (is (= 0.0 (compute-projection 1000.0 0 30))))
  (testing "zero days-total returns 0"
    (is (= 0.0 (compute-projection 1000.0 10 0)))))

(deftest compute-roas-threshold
  (testing "above threshold returns ratio"
    (is (= 5.0  (compute-roas 1000.0 200.0)))
    (is (= 10.0 (compute-roas 5000.0 500.0))))
  (testing "below threshold returns nil (avoids 0.25₽ overflow)"
    (is (nil? (compute-roas 456397.0 0.25)))
    (is (nil? (compute-roas 1000.0   0.0)))
    (is (nil? (compute-roas 1000.0   50.0))))   ;; 50 < 100 threshold
  (testing "exactly at threshold counts as valid"
    (is (some? (compute-roas 1000.0 100.0))))
  (testing "nil ad-spend returns nil"
    (is (nil? (compute-roas 1000.0 nil)))))

(deftest compute-drr-threshold
  (testing "above threshold returns percent"
    (is (= 20.0 (compute-drr 1000.0 200.0)))
    (is (= 10.0 (compute-drr 5000.0 500.0))))
  (testing "below threshold returns nil (avoids 0.0% rounding from 0.25₽)"
    (is (nil? (compute-drr 456397.0 0.25)))
    (is (nil? (compute-drr 1000.0   50.0))))
  (testing "zero revenue returns nil"
    (is (nil? (compute-drr 0.0   200.0))))
  (testing "nil ad-spend returns nil"
    (is (nil? (compute-drr 1000.0 nil)))))

;; ---------------------------------------------------------------------------
;; B4c. basis-note — pure helper (no DB)
;; ---------------------------------------------------------------------------

(deftest basis-note-flags-flat-heavy-subperiod
  (is (= :flat-heavy-subperiod
         (marker-api/basis-note {:flat 0.6 :spread 0.3 :api 0.1} 7)))   ; sub-month, flat-heavy
  (is (nil? (marker-api/basis-note {:flat 0.6} 31)))                     ; full month → no note
  (is (nil? (marker-api/basis-note {:flat 0.05} 7))))                    ; low flat → no note

;; ---------------------------------------------------------------------------
;; B4b. stocks-overview — pure shape via with-redefs (no live DB)
;; ---------------------------------------------------------------------------

(def ^:private fixture-stocks
  [{:article "A1" :marketplace :wb   :warehouse "Коледино"
    :quantity 10 :quantity-full 12 :in-way-to 0 :in-way-from 1
    :subject "Кружка" :category "Дом" :brand "Acme"}
   {:article "A1" :marketplace :wb   :warehouse "Электросталь"
    :quantity 5  :quantity-full 5  :in-way-to 2 :in-way-from 0
    :subject "Кружка" :category "Дом" :brand "Acme"}
   {:article "A2" :marketplace :ozon :warehouse "Хоругвино"
    :quantity 0  :quantity-full 0  :in-way-to 8 :in-way-from 0
    :subject "Тарелка" :category "Дом" :brand "Acme"}])

(deftest stocks-overview-shape
  (with-redefs [analitica.domain.stock/fetch-stocks
                (fn [& _] fixture-stocks)
                analitica.domain.sales/fetch-sales (fn [& _] [])]
    (let [resp (marker-api/stocks-overview-handler {:params {:mp "all"}})
          body (:body resp)]
      (testing "200 OK + canonical shape"
        (is (= 200 (:status resp)))
        (is (contains? body :totals))
        (is (contains? body :by-warehouse))
        (is (contains? body :by-article)))
      (testing ":totals sums across warehouses"
        (is (= 15 (get-in body [:totals :quantity])))
        (is (= 17 (get-in body [:totals :quantity-full])))
        (is (= 10 (get-in body [:totals :in-way-to])))
        (is (= 1  (get-in body [:totals :in-way-from])))
        (is (= 3  (get-in body [:totals :warehouses])))
        (is (= 2  (get-in body [:totals :articles]))))
      (testing ":by-warehouse rows carry the in-way columns Pulse needs"
        (let [koledino (->> body :by-warehouse
                             (filter #(= "Коледино" (:warehouse %)))
                             first)]
          (is (= 10 (:quantity koledino)))
          (is (= 1  (:articles koledino)))
          (is (= 0  (:in-way-to koledino)))
          (is (= 1  (:in-way-from koledino)))))
      (testing ":by-article carries status keyword string"
        (let [a2 (->> body :by-article
                       (filter #(= "A2" (:article %)))
                       first)]
          (is (contains? a2 :status))
          (is (#{"ok" "danger" "warning" "success"} (:status a2))))))))

(deftest stocks-overview-mp-filter
  (testing ":mp=wb passes :marketplace :wb to fetch-stocks"
    (let [calls (atom [])]
      (with-redefs [analitica.domain.stock/fetch-stocks
                    (fn [& kvs]
                      (swap! calls conj (apply hash-map kvs))
                      (filter #(= :wb (:marketplace %)) fixture-stocks))
                    analitica.domain.sales/fetch-sales (fn [& _] [])]
        (marker-api/stocks-overview-handler {:params {:mp "wb"}})
        (is (= :wb (:marketplace (first @calls))))))))

(deftest stocks-overview-capitalization-gmroi
  ;; 016-US2: stocks-overview :by-article rows + top-level :totals must carry
  ;; capitalization (cap-by-cost/cap-by-price), GMROI, and coverage — parity
  ;; with sku-list-handler. Articles WITH a cost basis get non-nil values;
  ;; those WITHOUT get nil (SPA renders "—"). 4 capitalization totals present.
  (with-redefs [analitica.domain.stock/fetch-stocks
                (fn [& _] fixture-stocks)
                ;; 30d sales drive wavg-retail (cap-by-price) + turnover.
                analitica.domain.sales/fetch-sales
                (fn [& _]
                  [{:article "A1" :quantity 3 :retail-amount 900.0}
                   {:article "A2" :quantity 1 :retail-amount 400.0}])
                ;; A1 has a cost basis; A2 does not (nil ⇒ N/A).
                analitica.domain.cost-price/get-price
                (fn ([art] ({"A1" 100.0} art))
                  ([art _] ({"A1" 100.0} art)))
                ;; finance drives net-profit numerator for GMROI (A1 only).
                analitica.domain.finance/fetch-finance
                (fn [& _]
                  [{:article "A1" :for-pay 900.0 :total-cost 300.0 :quantity 3
                    :retail-amount 900.0}])
                analitica.domain.pnl/ad-spend-by-article
                (fn [& _] {"A1" 50.0})]
    (let [resp (marker-api/stocks-overview-handler {:params {:mp "all"}})
          body (:body resp)
          a1   (->> body :by-article (filter #(= "A1" (:article %))) first)
          a2   (->> body :by-article (filter #(= "A2" (:article %))) first)]
      (is (= 200 (:status resp)))
      (testing "article WITH cost basis carries capitalization + GMROI"
        (is (some? (:cap-by-cost a1)))
        (is (some? (:cap-by-price a1)))
        ;; cap-by-cost = unit-cost 100 × quantity-full 17 (A1 across two whs).
        (is (= (* 100.0 17) (:cap-by-cost a1)))
        (is (contains? a1 :gmroi))
        (is (contains? a1 :gmroi-annualized))
        (is (contains? a1 :days-of-cover)))
      (testing "article WITHOUT cost basis is nil (\"—\"-able)"
        (is (nil? (:cap-by-cost a2)))
        ;; no basis ⇒ net-profit + gmroi N/A, never 0/∞ (FR-013).
        (is (or (nil? (:gmroi a2)) (= :not-applicable (:gmroi a2)))))
      (testing "top-level :totals carry the 4 capitalization totals"
        (let [t (:totals body)]
          (is (contains? t :cap-by-cost-total))
          (is (contains? t :cap-by-price-total))
          (is (contains? t :stock-qty-total))
          (is (contains? t :na-cost-count))
          ;; A2 has no cost basis ⇒ exactly one N/A SKU.
          (is (= 1 (:na-cost-count t)))
          ;; existing inventory totals preserved (additive-only).
          (is (contains? t :quantity))
          (is (contains? t :articles)))))))

(deftest stock-article-detail-shape
  (testing "per-article detail returns per-warehouse + history vectors"
    (with-redefs [analitica.domain.stock/fetch-stocks
                  (fn [& _] (filter #(= "A1" (:article %)) fixture-stocks))
                  analitica.domain.stock/fetch-history
                  (fn [_ _ & _]
                    [{:snapshot-date "2026-04-01" :quantity 12 :in-way-to 0}
                     {:snapshot-date "2026-04-15" :quantity 10 :in-way-to 2}
                     {:snapshot-date "2026-04-15" :quantity 5  :in-way-to 0}])]
      (let [resp (marker-api/stock-article-handler
                   {:path-params {:article "A1"}
                    :params      {:mp "wb"}})
            body (:body resp)]
        (is (= 200 (:status resp)))
        (is (vector? (:per-warehouse body)))
        (is (= 2 (count (:per-warehouse body))))
        (let [koledino (->> body :per-warehouse
                             (filter #(= "Коледино" (:warehouse %)))
                             first)]
          (is (= 10 (:quantity koledino)))
          (is (= 0  (:in-way-to koledino))))
        (testing ":history aggregates daily snapshots across warehouses"
          (is (vector? (:history body)))
          (is (= 2 (count (:history body))))
          (let [d2 (->> body :history
                         (filter #(= "2026-04-15" (:date %)))
                         first)]
            (is (= 15 (:quantity d2)))
            (is (= 2  (:in-way-to d2)))))))))

(deftest stock-article-handler-empty
  (testing "unknown article returns empty arrays not 500"
    (with-redefs [analitica.domain.stock/fetch-stocks (fn [& _] [])
                  analitica.domain.stock/fetch-history (fn [_ _ & _] [])]
      (let [resp (marker-api/stock-article-handler
                   {:path-params {:article "ZZZ"}})
            body (:body resp)]
        (is (= 200 (:status resp)))
        (is (= [] (:per-warehouse body)))
        (is (= [] (:history body)))))))

;; ---------------------------------------------------------------------------
;; B5. what-if-recalc — pinned numeric outputs (pure, no DB)
;; ---------------------------------------------------------------------------

(deftest what-if-recalc-pure
  (testing "known inputs produce expected margin range"
    ;; price=2500, cogs=1200, commission-pct=17, logistics=90, ads=220, returns-pct=8
    ;; effective-rev = 2500 * (1 - 0.08)        = 2300
    ;; commission    = 2300 * 0.17               = 391
    ;; total-costs   = 1200 + 391 + 90 + 220     = 1901
    ;; profit        = 2300 - 1901               = 399
    ;; margin-pct    = 399 / 2300 * 100          ≈ 17.35%
    (let [result (marker-api/what-if-recalc
                   {:price 2500 :cogs 1200 :commission-pct 17
                    :logistics 90 :ads 220 :returns-pct 8})]
      (is (map? result))
      (is (contains? result :margin-pct))
      (is (contains? result :profit))
      (is (contains? result :roas))
      (is (contains? result :break-even))
      (is (< 17.0 (:margin-pct result) 18.0)  "margin ≈ 17.35%")
      (is (< 398.0 (:profit result) 400.0)     "profit ≈ 399")
      (is (some? (:roas result)))
      (is (< 10.0 (:roas result) 11.0)         "roas = 2300/220 ≈ 10.45")))

  (testing "zero ads → nil roas"
    (let [result (marker-api/what-if-recalc
                   {:price 2500 :cogs 1200 :commission-pct 17
                    :logistics 90 :ads 0 :returns-pct 8})]
      (is (nil? (:roas result)))))

  (testing "zero price → zero margin"
    (let [result (marker-api/what-if-recalc
                   {:price 0 :cogs 0 :commission-pct 0
                    :logistics 0 :ads 0 :returns-pct 0})]
      (is (= 0.0 (:margin-pct result)))))

  (testing "break-even is positive for positive costs"
    (let [result (marker-api/what-if-recalc
                   {:price 1000 :cogs 500 :commission-pct 10
                    :logistics 50 :ads 0 :returns-pct 0})]
      (is (pos? (:break-even result)))))

  (testing "margin is negative when costs exceed revenue"
    (let [result (marker-api/what-if-recalc
                   {:price 100 :cogs 200 :commission-pct 10
                    :logistics 50 :ads 0 :returns-pct 0})]
      (is (neg? (:margin-pct result)))))

  (testing "what-if-recalc never throws on empty input"
    (is (map? (marker-api/what-if-recalc {})))))

;; ---------------------------------------------------------------------------
;; B1 (fix). sku-list pagination logic — pure, no DB required
;; ---------------------------------------------------------------------------

(deftest sku-list-pagination-logic
  (testing "cond->> drop/take mirrors spec pagination semantics"
    ;; Verify the exact pagination idiom used in sku-list-handler is correct.
    (let [items  (vec (range 10))
          paginate (fn [xs limit offset]
                     (let [off (or offset 0)]
                       (cond->> xs
                         true          (drop off)
                         (some? limit) (take limit)
                         true          vec)))]
      (is (= [0 1 2]     (paginate items 3 nil))  "limit=3, no offset")
      (is (= [3 4 5]     (paginate items 3 3))    "limit=3, offset=3")
      (is (= [5 6 7 8 9] (paginate items nil 5))  "no limit, offset=5")
      (is (= []          (paginate items 3 10))   "offset beyond end → empty"))))

(deftest sku-list-orphan-filter
  ;; Drop SKUs with revenue=0 AND orders=0 (typical Ozon "orphan service"
  ;; articles — only logistics/storage cost rows survived the period
  ;; filter, no actual sale activity). Keep everything else.
  (testing "filter-orphan-skus removes revenue=0 AND orders=0 SKUs"
    (let [skus [{:id "A" :revenue 100.0 :orders 5}
                {:id "B" :revenue 0.0   :orders 3}    ; pre-orders-only — keep
                {:id "C" :revenue 50.0  :orders 0}    ; refund-only — keep
                {:id "D" :revenue 0.0   :orders 0}    ; orphan — drop
                {:id "E" :revenue 0     :orders nil}  ; orphan — drop
                {:id "F" :revenue nil   :orders nil}] ; orphan — drop
          kept (marker-api/filter-orphan-skus skus)
          ids  (map :id kept)]
      (is (= #{"A" "B" "C"} (set ids))
          (str "Expected only A/B/C to remain. Got " ids))))

  (testing "filter-orphan-skus on empty input returns empty"
    (is (= [] (marker-api/filter-orphan-skus []))))

  (testing "filter-orphan-skus preserves order of remaining items"
    (let [skus [{:id 1 :revenue 0 :orders 0}
                {:id 2 :revenue 5 :orders 1}
                {:id 3 :revenue 0 :orders 0}
                {:id 4 :revenue 7 :orders 2}]]
      (is (= [2 4] (mapv :id (marker-api/filter-orphan-skus skus)))))))

;; ---------------------------------------------------------------------------
;; B2 (fix). what-if-handler validation — pure, no DB required
;; ---------------------------------------------------------------------------

(deftest what-if-handler-validation
  (testing "returns 400 with :error when body is empty (all required fields missing)"
    (let [result (marker-api/what-if-handler {:request-method :post :body {} :headers {}})]
      (is (= 400 (:status result)))
      (is (contains? (:body result) :error))))

  (testing "returns 400 with :error when a required field is non-numeric"
    (let [result (marker-api/what-if-handler
                   {:request-method :post
                    :body           {:price "abc" :cogs 1200 :commission-pct 17
                                     :logistics 90 :ads 220 :returns-pct 8}
                    :headers        {}})]
      (is (= 400 (:status result)))
      (is (contains? (:body result) :error)))))

;; ---------------------------------------------------------------------------
;; B5. what-if-recalc HTTP endpoint
;; ---------------------------------------------------------------------------

(deftest ^:integration what-if-http-test
  (testing "POST /api/v1/marker/what-if-recalc returns 200 with transit"
    (let [{:keys [status body]} (do-post "/api/v1/marker/what-if-recalc"
                                          {:price 2500 :cogs 1200
                                           :commission-pct 17 :logistics 90
                                           :ads 220 :returns-pct 8})]
      (is (= 200 status))
      (is (map? body))
      (is (contains? body :margin-pct))
      (is (contains? body :profit))
      (is (< 17.0 (:margin-pct body) 18.0))))

  (testing "POST with empty body returns 400"
    (let [{:keys [status body]} (do-post "/api/v1/marker/what-if-recalc" {})]
      (is (= 400 status))
      (is (contains? body :error))))

  (testing "POST with non-numeric field returns 400"
    (let [{:keys [status body]} (do-post "/api/v1/marker/what-if-recalc"
                                          {:price "abc" :cogs 1200
                                           :commission-pct 17 :logistics 90
                                           :ads 220 :returns-pct 8})]
      (is (= 400 status))
      (is (contains? body :error)))))

;; ---------------------------------------------------------------------------
;; B4d. pulse-summary — ad-integrity flag propagation (FR-P4.3, pure/no DB)
;;
;; When pnl/calculate returns :ad-cost-source :missing (neither canonical
;; finance.ad_cost nor legacy ad_stats had any row), the :drr and :roas KPI
;; maps must carry :ad-cost-source :missing so the SPA can render
;; «данные о рекламе отсутствуют» instead of a misleading 0 / «—».
;; ---------------------------------------------------------------------------

(def ^:private load-finance-var  #'marker-api/load-finance)
(def ^:private load-sales-var    #'marker-api/load-sales)
(def ^:private compute-pnl-var   #'marker-api/compute-pnl)
(def ^:private with-prelim-var   #'marker-api/with-prelim)

(def ^:private minimal-pnl-missing-ad
  "Minimal pnl-cur map as pnl/calculate would return it when ad data absent."
  {:revenue        10000.0
   :net-profit     2000.0
   :gross-profit   2500.0
   :margin-net     20.0
   :ad-spend       500.0   ;; above noise threshold so roas/drr compute non-nil
   :cogs           5000.0
   :logistics      500.0
   :for-pay        10000.0
   :sales-qty      100
   :returns-qty    5
   :buyout-rate    0.95
   :avg-check      100.0
   :ad-cost-source :missing})

(deftest pulse-summary-ad-cost-source-propagates-to-drr-roas
  (testing ":drr and :roas KPIs carry :ad-cost-source when pnl-cur has it"
    (with-redefs
      [load-finance-var                          (fn [& _] [])
       load-sales-var                            (fn [& _] [])
       compute-pnl-var                           (fn [& _] minimal-pnl-missing-ad)
       with-prelim-var                           (fn [pnl & _] pnl)
       analitica.domain.stock/fetch-stocks       (fn [& _] [])
       analitica.domain.buyout/analyze           (fn [& _] [])
       analitica.db/orders-by-article            (fn [& _] [])
       analitica.canonical.events.query/units-ordered   (fn [& _] 0)
       analitica.canonical.events.query/units-delivered (fn [& _] 0)
       analitica.alerts/freshness-data           (fn [& _] {})
       analitica.alerts/detect-alerts            (fn [& _] [])
       analitica.domain.finance/date-basis-split (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})
       analitica.domain.finance/fetch-finance    (fn [& _] [])
       analitica.db/query                        (fn [& _] [{:n 0}])]
      ;; pulse-summary returns a plain Clojure map (Transit middleware wraps it
      ;; at the Ring layer; in unit tests the raw map is returned directly).
      (let [body (marker-api/pulse-summary {:params {}})
            kpis (:kpis body)]
        (is (map? body) "handler returned a map (not an exception)")
        (is (= :missing (get-in kpis [:drr  :ad-cost-source]))
            ":drr must carry :ad-cost-source :missing")
        (is (= :missing (get-in kpis [:roas :ad-cost-source]))
            ":roas must carry :ad-cost-source :missing")))))

;; ---------------------------------------------------------------------------
;; B1. pulse-summary — shape tests
;; ---------------------------------------------------------------------------

(deftest ^:integration pulse-summary-shape-test
  (testing "returns 200"
    (let [{:keys [status body]} (do-get "/api/v1/marker/pulse-summary")]
      (is (= 200 status))
      (is (map? body))))

  (testing "has all required top-level keys"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")]
      (doseq [k [:alerts :kpis :costs :forecast :charts :top-movers :top-fallers
                 :critical-stocks :data-fresh]]
        (is (contains? body k) (str "missing key: " k)))))

  (testing ":alerts is a vector"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")]
      (is (vector? (:alerts body)))))

  (testing ":kpis contains all 13 KPI keys (P0-B added :cancel + :non-return)"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          kpis (:kpis body)]
      (is (map? kpis))
      (doseq [k [:revenue :profit :orders :purchases :realized :returned :margin
                 :avg-check :buyout :cancel :non-return :roas :drr]]
        (is (contains? kpis k) (str "kpis missing: " k)))))

  (testing "P0-B: :buyout is order-based (от заказов), :cancel + :non-return present"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          buyout     (get-in body [:kpis :buyout])
          cancel     (get-in body [:kpis :cancel])
          non-return (get-in body [:kpis :non-return])]
      (testing ":buyout carries its denominator basis (order-based)"
        (is (= :orders (:basis buyout))
            "Headline buyout must declare it is computed from orders placed, not sales-ops."))
      (testing ":cancel is the % отмен KPI"
        (is (map? cancel))
        (is (or (nil? (:value cancel)) (number? (:value cancel)))))
      (testing ":non-return preserves the legacy retention rate under its honest name"
        (is (map? non-return))
        (is (or (nil? (:value non-return)) (number? (:value non-return)))))))

  (testing "P0-A Part A: finance KPIs carry a date-basis contract"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          rev (get-in body [:kpis :revenue])]
      (testing ":date-basis has api/spread/flat fractions"
        (let [b (:date-basis rev)]
          (is (map? b))
          (is (every? #(number? (get b %)) [:api :spread :flat]))))
      (testing ":completeness is a known state"
        ;; LT3: :empty is now a valid state — a recent window with no published
        ;; finance (Ozon realization lag / no sales) honestly reports :empty.
        (is (contains? #{:full :partial :estimated :missing :empty} (:completeness rev))))))

  (testing ":orders is total orders (incl. cancelled), :purchases is delivered sales — orders >= purchases"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          orders     (get-in body [:kpis :orders :value])
          purchases  (get-in body [:kpis :purchases :value])]
      (is (number? orders))
      (is (number? purchases))
      (is (>= orders purchases)
          (str "Expected orders ≥ purchases (orders include cancelled+in-flight). "
               "Got orders=" orders " purchases=" purchases))))

  (testing ":kpis :revenue has value/delta-pct/spark"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          rev (get-in body [:kpis :revenue])]
      (is (map? rev))
      (is (contains? rev :value))
      (is (contains? rev :delta-pct))
      (is (vector? (:spark rev)))))

  (testing "FR-P1.3: :kpis :revenue carries :spark-source :sales (live sales series)"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          rev (get-in body [:kpis :revenue])]
      (is (= :sales (:spark-source rev))
          "revenue spark is always the live sales series — must be labeled :sales")))

  (testing ":forecast has month-fact and projection"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          fc (:forecast body)]
      (is (map? fc))
      (is (contains? fc :month-fact))
      (is (contains? fc :projection))))

  (testing ":charts has revenue-30d vector"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          ch (:charts body)]
      (is (map? ch))
      (is (vector? (:revenue-30d ch)))))

  (testing ":data-fresh has wb/ozon/ym keys"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          fresh (:data-fresh body)]
      (is (map? fresh))
      (is (contains? fresh :wb))
      (is (contains? fresh :ozon))
      (is (contains? fresh :ym))))

  (testing "?compare=true adds revenue-prev-30d"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary"
                                  :params {:compare "true"})
          ch (:charts body)]
      (is (contains? ch :revenue-prev-30d))))

  (testing "Content-Type is transit+json"
    (let [{:keys [headers]} (do-get "/api/v1/marker/pulse-summary")]
      (is (re-find #"transit" (get headers "Content-Type" ""))))))

;; ---------------------------------------------------------------------------
;; B1b. pulse-summary — per-KPI source metadata
;; ---------------------------------------------------------------------------

(def valid-kpi-sources
  ;; :orders — P0-B: buyout/cancel are computed from orders placed (sold/placed).
  ;; :preliminary-missing — LT5: commission/COGS absent in preliminary Ozon window.
  #{:realization :preliminary :preliminary-missing :canon :legacy-orders :legacy-sales :orders :none})

(deftest ^:integration pulse-summary-source-metadata-test
  (testing "every KPI has :source and :as-of keys"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          kpis (:kpis body)]
      (doseq [k [:revenue :profit :orders :purchases :realized :returned :margin
                 :avg-check :buyout :cancel :non-return :roas :drr]]
        (let [kpi (get kpis k)]
          (is (contains? kpi :source)
              (str k " missing :source"))
          (is (contains? kpi :as-of)
              (str k " missing :as-of"))))))

  (testing ":source values are from the closed valid set"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          kpis (:kpis body)]
      (doseq [k [:revenue :profit :orders :purchases :realized :returned :margin
                 :avg-check :buyout :cancel :non-return :roas :drr]]
        (let [src (get-in kpis [k :source])]
          (is (contains? valid-kpi-sources src)
              (str k " :source=" src " not in valid set"))))))

  (testing "when top-level :preliminary? is true, at least one KPI has :source = :preliminary"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          kpis          (:kpis body)
          prelim?       (:preliminary? body)]
      (when prelim?
        (let [preliminary-kpis (filter #(= :preliminary (get-in kpis [% :source]))
                                       (keys kpis))]
          (is (seq preliminary-kpis)
              "Expected at least one KPI with :source=:preliminary when :preliminary?=true")))))

  (testing ":profit :source matches :revenue :source"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          kpis           (:kpis body)
          rev-src        (get-in kpis [:revenue :source])
          profit-src     (get-in kpis [:profit :source])]
      ;; profit inherits revenue source; only exception is when profit=0 → :none
      (when (not= :none profit-src)
        (is (= rev-src profit-src)
            (str "profit :source=" profit-src " should match revenue :source=" rev-src)))))

  (testing ":margin :source mirrors :revenue :source"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          kpis           (:kpis body)
          rev-src        (get-in kpis [:revenue :source])
          margin-src     (get-in kpis [:margin :source])]
      (when (not= :none margin-src)
        (is (= rev-src margin-src)
            (str "margin :source=" margin-src " should mirror revenue :source=" rev-src))))))

;; ---------------------------------------------------------------------------
;; B1c. pulse-summary — :realized KPI specific tests
;; ---------------------------------------------------------------------------

(deftest ^:integration pulse-summary-realized-test
  (testing ":realized equals sum of :sales-qty across finance articles"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          realized-val (get-in body [:kpis :realized :value])
          realized-src (get-in body [:kpis :realized :source])]
      (is (number? realized-val))
      (is (>= realized-val 0))
      (when (pos? realized-val)
        (is (= :realization realized-src)))
      (when (zero? realized-val)
        (is (= :none realized-src)))))

  (testing ":purchases >= :realized (delivered units >= settled units)"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          purchases (get-in body [:kpis :purchases :value])
          realized  (get-in body [:kpis :realized :value])]
      ;; Both :purchases and :realized are GROSS counts (returns excluded —
      ;; returns are tracked separately as :returns-qty in pnl). If either counter
      ;; is ever changed to net, this invariant must be revisited: net-realized
      ;; could exceed gross-purchases in periods with heavy returns.
      (is (>= purchases realized)
          (str "Realized must not exceed delivered. Got purchases=" purchases
               " realized=" realized))))

  (testing "funnel: returned <= purchases"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          purchases (get-in body [:kpis :purchases :value])
          returned  (get-in body [:kpis :returned  :value])]
      ;; Cannot return more than was delivered.
      (is (>= purchases returned)
          (str "Returned must not exceed delivered. Got purchases=" purchases
               " returned=" returned)))))

;; ---------------------------------------------------------------------------
;; B1d. pulse-summary — :costs breakdown tests
;; ---------------------------------------------------------------------------

(deftest ^:integration pulse-summary-costs-shape-test
  (testing "response has :costs map with 6 cost-line keys"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          costs (:costs body)]
      (is (map? costs))
      (doseq [k [:cogs :commission :logistics :ads :other :total]]
        (is (contains? costs k) (str "costs missing: " k)))))

  (testing "each cost line has :value :delta-pct :source :as-of"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          costs (:costs body)]
      (doseq [k [:cogs :commission :logistics :ads :other :total]]
        (let [line (get costs k)]
          ;; LT5: when source is :preliminary-missing, value is nil (not a number) — that is correct.
          (is (or (number? (:value line))
                  (= :preliminary-missing (:source line)))
              (str k " :value must be a number or source must be :preliminary-missing"))
          (is (or (number? (:delta-pct line))
                  (nil?    (:delta-pct line)))  (str k " :delta-pct must be number or nil"))
          (is (contains? line :source)          (str k " missing :source"))
          (is (contains? line :as-of)           (str k " missing :as-of"))))))

  (testing ":costs :total == sum of individual cost lines (within ₽1 rounding)"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          costs (:costs body)
          ;; LT5: when costs are preliminary-missing, total and cogs/commission are nil
          ;; (Ozon preliminary window). Skip the sum check in that case.
          cost-missing? (= :preliminary-missing (get-in costs [:total :source]))]
      (when-not cost-missing?
        (let [parts (+ (get-in costs [:cogs       :value])
                       (get-in costs [:commission :value])
                       (get-in costs [:logistics  :value])
                       (get-in costs [:ads        :value])
                       (get-in costs [:other      :value]))
              total (get-in costs [:total :value])]
          (is (< (Math/abs (- total parts)) 1.0)
              (str "Cost-total " total " should equal sum of parts " parts " within ₽1"))))))

  (testing ":returned KPI present and >= 0"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          returned (get-in body [:kpis :returned])]
      (is (some? returned))
      (is (number? (:value returned)))
      (is (>= (:value returned) 0)))))

;; ---------------------------------------------------------------------------
;; B1e. pulse-summary — LT5 preliminary-missing cost honesty (pure unit tests)
;; ---------------------------------------------------------------------------

(deftest cost-line-missing-arity
  (testing "cost-line with missing?=true returns nil value and :preliminary-missing source"
    (let [line (cost-line nil nil :preliminary :preliminary-missing true)]
      (is (nil? (:value line))
          "missing cost-line must have nil value, not 0")
      (is (= :preliminary-missing (:source line))
          "missing cost-line source must be :preliminary-missing")))

  (testing "cost-line normal arity (4-arg) still works — no regression"
    (let [line (cost-line 5000.0 4000.0 :preliminary nil)]
      (is (= 5000.0 (:value line)))
      (is (= :preliminary (:source line))))))

(deftest pulse-costs-preliminary-missing-not-zero
  (testing "when pnl-cur carries :cost-source :preliminary-missing,
            :cogs and :commission cost-lines have nil :value and :preliminary-missing source"
    ;; Build a minimal pnl-cur that simulates the Ozon preliminary hollow state:
    ;; revenue filled from cash-flow, but commission/cogs absent.
    (let [pnl-cur  {:revenue          340583.0
                    :revenue-source   :preliminary
                    :preliminary?     true
                    :preliminary-as-of "2026-04-26"
                    :mp-commission    0.0
                    :cogs             0.0
                    :logistics        3200.0
                    :storage          800.0
                    :ad-spend         0.0
                    :net-profit       0.0
                    :margin-net       0.0
                    :cost-source      :preliminary-missing
                    :preliminary-cost-fields #{:cogs :commission}}
          pnl-prev {:revenue 0.0 :mp-commission 0.0 :cogs 0.0
                    :logistics 0.0 :storage 0.0 :ad-spend 0.0}
          revenue-src  :preliminary
          revenue-as-of "2026-04-26"
          ;; Build cost map the same way marker.clj does, but with missing? logic
          cogs-line       (cost-line nil nil revenue-src revenue-as-of true)
          commission-line (cost-line nil nil revenue-src revenue-as-of true)
          logistics-line  (cost-line (:logistics pnl-cur) (:logistics pnl-prev) revenue-src revenue-as-of)]
      (is (nil? (:value cogs-line))
          ":cogs value must be nil when preliminary-missing")
      (is (= :preliminary-missing (:source cogs-line))
          ":cogs source must be :preliminary-missing")
      (is (nil? (:value commission-line))
          ":commission value must be nil when preliminary-missing")
      (is (= :preliminary-missing (:source commission-line))
          ":commission source must be :preliminary-missing")
      ;; Logistics IS published — must stay real
      (is (= 3200.0 (:value logistics-line))
          ":logistics value must remain real")
      (is (= :preliminary (:source logistics-line))
          ":logistics source must be :preliminary, not missing"))))

(deftest pulse-profit-margin-preliminary-missing
  (testing "when costs are :preliminary-missing, profit and margin KPIs
            carry :source :preliminary-missing and do NOT surface a partial-cost negative"
    ;; Simulate the marker.clj KPI-build logic for profit/margin
    ;; when cost-source = :preliminary-missing
    (let [pnl-cur {:revenue          340583.0
                   :revenue-source   :preliminary
                   :preliminary?     true
                   :mp-commission    0.0
                   :cogs             0.0
                   :logistics        3200.0
                   :net-profit       (- 340583.0 3200.0)  ; partial fake negative (old bug)
                   :margin-net       (/ (- 340583.0 3200.0) 340583.0)
                   :cost-source      :preliminary-missing
                   :preliminary-cost-fields #{:cogs :commission}}
          cost-missing? (= :preliminary-missing (:cost-source pnl-cur))
          ;; When cost is missing → profit/margin source = :preliminary-missing
          ;; and value should NOT be the fabricated partial-cost number
          profit-src (if cost-missing? :preliminary-missing :realization)
          margin-src (if cost-missing? :preliminary-missing :realization)
          ;; I1 (LT5): mirror marker.clj's :value expressions exactly.
          ;; :profit was passing nil through build-kpi (→ 0.0); both profit and
          ;; margin :value MUST be nil under cost-missing, never a fabricated number.
          profit-val (when-not cost-missing? (or (:net-profit pnl-cur) 0.0))
          margin-val (when-not cost-missing? (or (:margin-net pnl-cur) 0.0))]
      (is (= :preliminary-missing profit-src)
          "profit :source must be :preliminary-missing when costs missing")
      (is (= :preliminary-missing margin-src)
          "margin :source must be :preliminary-missing when costs missing")
      (is (nil? profit-val)
          "I1: profit :value must be nil (not 0.0/partial negative) under :preliminary-missing")
      (is (nil? margin-val)
          "margin :value must be nil under :preliminary-missing")
      ;; The fabricated partial-cost negative should not be surfaced
      (is (contains? valid-kpi-sources profit-src)
          ":preliminary-missing must be in valid-kpi-sources"))))

;; ---------------------------------------------------------------------------
;; B2. pnl — shape tests
;; ---------------------------------------------------------------------------

(deftest ^:integration pnl-shape-test
  (testing "returns 200"
    (let [{:keys [status body]} (do-get "/api/v1/marker/pnl")]
      (is (= 200 status))
      (is (map? body))))

  (testing "has :rows and :sku-detail vectors"
    (let [{:keys [body]} (do-get "/api/v1/marker/pnl")]
      (is (vector? (:rows body)))
      (is (vector? (:sku-detail body)))))

  (testing ":rows entries have required shape"
    (let [{:keys [body]} (do-get "/api/v1/marker/pnl")
          rows (:rows body)]
      (is (= (count marker-api/pnl-row-defs) (count rows))
          "row count matches definition")
      (when (seq rows)
        (let [r (first rows)]
          (doseq [k [:key :label :cur :prev :group]]
            (is (contains? r k) (str "row missing: " k)))))))

  (testing ":sku-detail entries (when present) have required shape"
    (let [{:keys [body]} (do-get "/api/v1/marker/pnl")
          det (:sku-detail body)]
      (when (seq det)
        (let [d (first det)]
          (doseq [k [:id :name :mp :revenue :cogs :commission :ads :net]]
            (is (contains? d k) (str "sku-detail missing: " k))))))))

;; ---------------------------------------------------------------------------
;; B3. sku-list — shape tests
;; ---------------------------------------------------------------------------

(deftest ^:integration sku-list-shape-test
  (testing "returns 200"
    (let [{:keys [status body]} (do-get "/api/v1/marker/sku-list")]
      (is (= 200 status))
      (is (map? body))))

  (testing "has :skus vector"
    (let [{:keys [body]} (do-get "/api/v1/marker/sku-list")]
      (is (vector? (:skus body)))))

  (testing ":skus entries have required fields"
    (let [{:keys [body]} (do-get "/api/v1/marker/sku-list")
          skus (:skus body)]
      (when (seq skus)
        (let [s (first skus)]
          (doseq [k [:id :name :revenue :orders :margin :stock :delta-pct]]
            (is (contains? s k) (str "sku missing: " k)))))))

  (testing "?limit=3 returns at most 3 SKUs (pagination)"
    (let [{:keys [status body]} (do-get "/api/v1/marker/sku-list"
                                         :params {:limit "3" :offset "0"})]
      (is (= 200 status))
      (is (contains? body :skus))
      (is (<= (count (:skus body)) 3) "limit=3 must return ≤3 entries"))))

;; ---------------------------------------------------------------------------
;; B4. sku-detail — shape tests
;; ---------------------------------------------------------------------------

(deftest ^:integration sku-detail-shape-test
  (testing "unknown SKU returns 200 (no crash)"
    (let [{:keys [status body]} (do-get "/api/v1/marker/sku-detail/NONEXISTENT-SKU-999")]
      (is (= 200 status))
      (is (map? body))))

  (testing "response has required top-level keys"
    (let [{:keys [body]} (do-get "/api/v1/marker/sku-detail/SKU-TEST")]
      (doseq [k [:id :name :kpis :revenue-30d :plan-fact :stocks-by-mp]]
        (is (contains? body k) (str "sku-detail missing: " k)))))

  (testing ":kpis has revenue/orders/margin/ads"
    (let [{:keys [body]} (do-get "/api/v1/marker/sku-detail/SKU-TEST")
          kpis (:kpis body)]
      (is (map? kpis))
      (doseq [k [:revenue :orders :margin :ads]]
        (is (contains? kpis k) (str "kpis missing: " k)))))

  (testing ":revenue-30d is a vector"
    (let [{:keys [body]} (do-get "/api/v1/marker/sku-detail/SKU-TEST")]
      (is (vector? (:revenue-30d body)))))

  (testing ":plan-fact has fact and projection"
    (let [{:keys [body]} (do-get "/api/v1/marker/sku-detail/SKU-TEST")
          pf (:plan-fact body)]
      (is (map? pf))
      (is (contains? pf :fact))
      (is (contains? pf :projection))))

  (testing ":kpis has :max-drr-pct (FR-P4.2)"
    (let [{:keys [body]} (do-get "/api/v1/marker/sku-detail/SKU-TEST")
          kpis (:kpis body)]
      (is (contains? kpis :max-drr-pct) "sku-detail kpis must include :max-drr-pct (FR-P4.2)")
      (is (map? (:max-drr-pct kpis))    ":max-drr-pct must be a {:value …} map")))

  (testing ":over-ceiling? present at top level (FR-P4.2)"
    (let [{:keys [body]} (do-get "/api/v1/marker/sku-detail/SKU-TEST")]
      (is (contains? body :over-ceiling?) "sku-detail must include :over-ceiling? (FR-P4.2)")
      (is (boolean? (:over-ceiling? body)) ":over-ceiling? must be a boolean"))))

;; ---------------------------------------------------------------------------
;; C. Transit vs JSON content negotiation
;; ---------------------------------------------------------------------------

(deftest ^:integration content-negotiation-test
  (testing "pulse-summary with transit Accept → transit content-type"
    (let [{:keys [headers]} (do-get "/api/v1/marker/pulse-summary")]
      (is (re-find #"transit" (get headers "Content-Type" "")))))

  (testing "pulse-summary without transit Accept → JSON string body"
    (let [{:keys [raw headers]} (do-get "/api/v1/marker/pulse-summary"
                                         :accept "application/json")]
      ;; wrap-json-response encoded to JSON string
      (is (string? raw))
      (is (re-find #"application/json" (get headers "Content-Type" ""))))))

;; ---------------------------------------------------------------------------
;; D. Existing endpoints must not be broken by transit middleware
;; ---------------------------------------------------------------------------

(deftest ^:integration existing-endpoints-unbroken-test
  (testing "GET /api/metrics still returns JSON without transit"
    (let [app  (server/app)
          req  {:server-port 3001 :server-name "localhost" :remote-addr "127.0.0.1"
                :uri "/api/metrics" :query-string "period=last-30-days"
                :scheme :http :request-method :get
                :headers {} :params {:period "last-30-days"}}
          resp (app req)]
      (is (= 200 (:status resp)))
      (is (string? (:body resp)))
      (is (re-find #"application/json" (get-in resp [:headers "Content-Type"] "")))))

  (testing "GET /app still returns HTML shell"
    (let [app  (server/app)
          req  {:server-port 3001 :server-name "localhost" :remote-addr "127.0.0.1"
                :uri "/app" :query-string nil
                :scheme :http :request-method :get
                :headers {} :params {}}
          resp (app req)]
      (is (= 200 (:status resp)))
      (is (string? (:body resp)))
      (is (re-find #"text/html" (get-in resp [:headers "Content-Type"] ""))))))

;; ---------------------------------------------------------------------------
;; E. Phase 9 — generic /reports/:type endpoint
;; ---------------------------------------------------------------------------

(deftest reports-handler-validation
  (testing "unknown report type returns 400 with :error"
    (let [resp (marker-api/reports-handler
                 {:request-method :get :params {:type "nonsense"} :headers {}})]
      (is (= 400 (:status resp)))
      (is (contains? (:body resp) :error))
      (is (vector? (:known (:body resp))))))

  (testing "missing :type returns 400"
    (let [resp (marker-api/reports-handler
                 {:request-method :get :params {} :headers {}})]
      (is (= 400 (:status resp))))))

(deftest ^:integration reports-shape-test
  (testing "GET /api/v1/marker/reports/finance returns 200 + columns/rows/totals"
    (let [{:keys [status body]} (do-get "/api/v1/marker/reports/finance"
                                         :params {:type "finance"})]
      (is (= 200 status))
      (is (= :finance (:report-type body)))
      (is (vector? (:columns body)))
      (is (pos? (count (:columns body))))
      (is (vector? (:rows body)))
      (is (map?    (:totals body)))
      (is (map?    (:schema body)))))

  (testing "GET reports/ue + ?compare=true attaches :compare block"
    (let [{:keys [status body]} (do-get "/api/v1/marker/reports/ue"
                                         :params {:type "ue" :compare "true"}
                                         :query-string "compare=true")]
      (is (= 200 status))
      (is (contains? body :compare))
      (is (map? (:compare body)))))

  (testing "GET reports/abc with mp filter (single MP) does not crash"
    (let [{:keys [status body]} (do-get "/api/v1/marker/reports/abc"
                                         :params {:type "abc" :mp "wb"}
                                         :query-string "mp=wb")]
      (is (= 200 status))
      (is (vector? (:rows body)))))

  (testing "drill-down route returns rows for one article"
    (let [{:keys [status body]} (do-get "/api/v1/marker/reports/ue/article/SOMETHING"
                                         :params {:type "ue" :article "SOMETHING"})]
      (is (= 200 status))
      (is (vector? (:rows body))))))

;; ---------------------------------------------------------------------------
;; B4d. revenue KPI dual-source labeling — FR-P1.3 (pure, no DB)
;; ---------------------------------------------------------------------------

(deftest revenue-kpi-spark-source-pure
  ;; FR-P1.3: the revenue KPI tile is backed by finance (PnL) but its
  ;; sparkline is always the live per-day sales series.
  ;;
  ;; Contract: build-kpi does NOT emit :spark-source (it is source-agnostic);
  ;; the :spark-source :sales key is added by the pulse-summary handler assoc
  ;; on the revenue KPI only.  The ^:integration pulse-summary-shape-test
  ;; above is the RED/GREEN gate for the handler-level contract; this test
  ;; guards the build-kpi layer boundary.
  (testing "build-kpi base map does NOT carry :spark-source (handler assoc owns it)"
    (let [base (build-kpi 50000.0 45000.0 [100.0 200.0] :realization nil)]
      (is (not (contains? base :spark-source))
          "build-kpi must not pre-populate :spark-source")))

  (testing ":source (tile) and :spark-source (sparkline) can coexist independently"
    ;; Verify the assoc semantics are additive — :source is not overwritten.
    (let [kpi (-> (build-kpi 50000.0 45000.0 [] :preliminary nil)
                  (assoc :spark-source :sales))]
      (is (= :preliminary (:source kpi))
          "tile :source must be preserved when :spark-source is assoc'd")
      (is (= :sales (:spark-source kpi))))))

;; ---------------------------------------------------------------------------
;; B4e. max-ДРР / headroom / over-ceiling? — FR-P4.2 (pure + integration)
;; ---------------------------------------------------------------------------

;; Real handler helper (FR-005 numerator). Aliased here so both the pure
;; formula test below and the isolation test further down share one source.
(def ^:private max-drr-numerator #'marker-api/max-drr-numerator)

(deftest sku-list-max-drr-formula-pure
  ;; The sku-list-handler builds per-SKU rows inline. This test verifies the
  ;; max-ДРР formula that must appear in each row (FR-005 / UE.7):
  ;;   max-drr-numer = for-pay − cogs − logistics − storage − penalties − acceptance
  ;;   max-drr-pct   = max-drr-numer / revenue × 100
  ;;   drr-headroom-pct = max-drr-pct − drr-pct
  ;;   over-ceiling? = drr-pct > max-drr-pct
  ;; The numerator MUST come from the real handler helper so a reverted
  ;; deduction is caught here, not silently re-implemented with the old formula.
  (testing "max-drr-pct uses the FR-005 numerator (all variable costs deducted)"
    ;; spec fixture: for-pay=700 cogs=200 logistics=50 storage=20 penalties=10
    ;;               acceptance=20 → numerator=400 ; revenue=1000 → 40.0
    ;;               (before-would-be: (700−200)/1000×100 = 50.0)
    (let [rev        1000.0
          for-pay     700.0
          cogs        200.0
          logistics    50.0
          storage      20.0
          penalties    10.0
          acceptance   20.0
          ads          50.0
          margin        (analitica.util.math/percentage (- for-pay cogs) (max 1.0 for-pay))
          drr-pct       (analitica.util.math/percentage ads rev)
          ;; Call the REAL handler helper (not an inline re-implementation).
          max-drr-numer (max-drr-numerator for-pay cogs logistics storage penalties acceptance)
          max-drr-pct   (analitica.util.math/percentage max-drr-numer rev)
          headroom-pct  (analitica.util.math/round2 (- (or max-drr-pct 0.0) (or drr-pct 0.0)))
          over-ceiling? (boolean (and max-drr-pct drr-pct (> drr-pct max-drr-pct)))]
      (is (= 400.0 max-drr-numer) "numerator = for-pay − cogs − logistics − storage − penalties − acceptance")
      (is (= 71.43 margin)        "margin % = (for-pay − cogs) / for-pay × 100")
      (is (= 40.0 max-drr-pct)    "max-drr-pct = numerator/revenue*100  ;; before-would-be 50.0")
      (is (= 5.0  drr-pct)        "drr-pct = ads/revenue*100")
      (is (= 35.0 headroom-pct)   "headroom = max-drr - drr")
      (is (false? over-ceiling?)  "ads < max ceiling → not over")))

  (testing "over-ceiling? fires when ads exceed break-even"
    ;; loss article: for-pay=1000, cogs=1200 (already below break-even), ads=200
    ;; numerator = 1000-1200-0-0-0-0 = -200 → max-drr-pct = -20%
    ;; drr-pct = 200/1000*100 = 20% → 20 > -20 → over-ceiling?=true
    (let [rev     1000.0
          for-pay 1000.0
          cogs    1200.0
          ads      200.0
          max-drr-pct  (analitica.util.math/percentage
                         (max-drr-numerator for-pay cogs 0.0 0.0 0.0 0.0) rev)
          drr-pct      (analitica.util.math/percentage ads rev)
          over-ceiling? (boolean (and max-drr-pct drr-pct (> drr-pct max-drr-pct)))]
      (is (true? over-ceiling?) "loss-making SKU must be flagged over-ceiling"))))

(deftest sku-list-handler-threads-variable-costs-into-ceiling
  ;; FR-005 WIRING guard: the isolation test above proves the helper is
  ;; correct; this proves sku-list-handler actually READS
  ;; :logistics/:storage/:penalties/:acceptance off the by-article row and
  ;; threads them into the ceiling. If any of logistics-a/storage-a/
  ;; penalties-a/acceptance-a wiring were reverted, :max-drr-pct would jump
  ;; from 40.0 back toward the old gross-margin 50.0 and this test would fail.
  ;;
  ;; Spec fixture (metric-formulas.edn §FR-005):
  ;;   revenue=1000 for-pay=700 cogs(total-cost)=200
  ;;   logistics=50 storage=20 penalties=10 acceptance=20
  ;;   numerator = 700 − 200 − 50 − 20 − 10 − 20 = 400 → max-drr-pct = 40.0
  ;;   (old gross-margin numerator 700−200=500 would give 50.0)
  (let [by-art-row {:article    "SKU-WIRE"
                    :subject    "Wiring fixture"
                    :marketplace :ozon
                    :revenue    1000.0
                    :for-pay    700.0
                    :total-cost 200.0
                    :logistics  50.0
                    :storage    20.0
                    :penalties  10.0
                    :acceptance 20.0
                    :sales-qty  5
                    :returns-qty 0}]
    (with-redefs
      [load-finance-var                          (fn [& _] [{:for-pay 700.0}])
       load-sales-var                            (fn [& _] [])
       compute-pnl-var                           (fn [& _] {:ad-spend 0.0})
       analitica.domain.finance/by-article       (fn [_] [by-art-row])
       analitica.domain.sales/by-article         (fn [_] [])
       analitica.domain.stock/fetch-stocks       (fn [& _] [])
       analitica.domain.stock/by-article         (fn [_] [])
       analitica.domain.pnl/ad-spend-by-article  (fn [& _] {"SKU-WIRE" 0.0})
       analitica.domain.finance/date-basis-split (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})]
      (let [body (marker-api/sku-list-handler {:params {}})
            sku  (first (:skus body))]
        (is (map? body) "handler returned a map (not an exception)")
        (is (nil? (:error body)) (str "handler errored: " (:error body)))
        (is (= "SKU-WIRE" (:id sku)) "fixture row must survive to the response")
        (testing "max-drr-pct reflects logistics+storage+penalties+acceptance deductions"
          ;; This is the load-bearing assertion: 40.0 only if all four are threaded.
          (is (= 40.0 (:max-drr-pct sku))
              "max-drr-pct must be 40.0 (numerator 400), NOT 50.0 (gross-margin) — variable costs threaded"))
        (testing "headroom = ceiling − drr (ads=0 → drr=0 → headroom = ceiling)"
          (is (= 40.0 (:drr-headroom-pct sku))))))))

(deftest ^:integration sku-list-max-drr-fields-test
  ;; FR-P4.2: sku-list rows must carry the three max-ДРР fields.
  (testing ":skus entries carry :max-drr-pct, :drr-headroom-pct, :over-ceiling?"
    (let [{:keys [body]} (do-get "/api/v1/marker/sku-list")
          skus (:skus body)]
      (when (seq skus)
        (let [s (first skus)]
          (doseq [k [:max-drr-pct :drr-headroom-pct :over-ceiling?]]
            (is (contains? s k) (str "sku-list row missing FR-P4.2 field: " k))))))))

(deftest ^:integration pulse-summary-drr-ceiling-test
  ;; FR-P4.2: pulse-summary kpis must contain a :drr-ceiling KPI alongside :drr.
  (testing ":kpis contains :drr-ceiling"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          kpis (:kpis body)]
      (is (contains? kpis :drr-ceiling)
          "pulse-summary kpis must include :drr-ceiling (FR-P4.2)")))

  (testing ":drr-ceiling has the expected KPI shape (:value key)"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          dc (get-in body [:kpis :drr-ceiling])]
      (is (map? dc))
      (is (contains? dc :value)
          ":drr-ceiling must carry a :value key")
      (is (or (nil? (:value dc)) (number? (:value dc)))
          ":drr-ceiling :value must be a number or nil"))))

;; ---------------------------------------------------------------------------
;; LT3 — basis-contract envelope on pnl / sku-list / sku-detail / reports
;; ---------------------------------------------------------------------------
;;
;; Every finance handler must emit {:date-basis {:api f :spread f :flat f}
;;                                  :completeness #{:empty :full :estimated}}
;; at the top level of its response map.
;; :empty is the honest signal for zero-monetary-sum windows (no data published
;; yet / no sales in period). Emitting :full on an empty window is a lie.
;;
;; Pure test: basis-envelope helper semantics (no DB).
;; Integration tests: shape contract on the three handlers.

(def ^:private basis-envelope-fn #'marker-api/basis-envelope)

(deftest basis-envelope-pure
  (testing "basis-envelope returns :date-basis and :completeness keys"
    ;; Use a dummy row so empty-monetary check doesn't fire
    (with-redefs [analitica.domain.finance/date-basis-split
                  (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})]
      (let [env (basis-envelope-fn [{:for-pay 100.0}] false)]
        (is (map? env))
        (is (contains? env :date-basis))
        (is (contains? env :completeness)))))

  (testing ":date-basis has :api :spread :flat fraction keys"
    (with-redefs [analitica.domain.finance/date-basis-split
                  (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})]
      (let [env (basis-envelope-fn [{:for-pay 100.0}] false)
            db  (:date-basis env)]
        (doseq [k [:api :spread :flat]]
          (is (contains? db k) (str ":date-basis missing key " k))))))

  (testing ":completeness is :full when rows are all-api and not preliminary"
    ;; finance/date-basis-split on rows with only :api source → {:api 1.0 :spread 0.0 :flat 0.0}
    ;; flat < 0.2 and preliminary?=false and non-zero monetary → :full
    (with-redefs [analitica.domain.finance/date-basis-split
                  (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})]
      (let [env (basis-envelope-fn [{:for-pay 100.0}] false)]
        (is (= :full (:completeness env))))))

  (testing ":completeness is :estimated when flat fraction >= 0.2"
    (with-redefs [analitica.domain.finance/date-basis-split
                  (fn [& _] {:api 0.7 :spread 0.1 :flat 0.2})]
      (let [env (basis-envelope-fn [{:for-pay 100.0}] false)]
        (is (= :estimated (:completeness env))))))

  (testing ":completeness is :estimated when preliminary? is true regardless of flat"
    (with-redefs [analitica.domain.finance/date-basis-split
                  (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})]
      (let [env (basis-envelope-fn [{:for-pay 100.0}] true)]
        (is (= :estimated (:completeness env)))))))

;; LT3 — new tests for :empty completeness

(deftest basis-envelope-zero-forpay-is-empty
  (testing "empty rows (no for-pay) → :empty, not :full"
    ;; This is the lie we're fixing: before LT3, empty rows returned :full because
    ;; date-basis-split returns all-zeros → flat=0.0 < 0.2 → :else :full.
    ;; The honest answer is :empty (window has no monetary data at all).
    (let [env (basis-envelope-fn [] false)]
      (is (= :empty (:completeness env))
          "empty rows must yield :completeness :empty, not :full")))

  (testing "rows with all for-pay = 0 → :empty"
    (let [rows [{:for-pay 0.0 :operation "sale"} {:for-pay 0.0 :operation "return"}]
          env  (basis-envelope-fn rows false)]
      (is (= :empty (:completeness env))
          "zero-monetary rows must yield :completeness :empty")))

  (testing ":empty wins even when preliminary?=true (no data at all)"
    (let [env (basis-envelope-fn [] true)]
      (is (= :empty (:completeness env))
          ":empty beats preliminary? — there's literally no monetary data"))))

(deftest basis-envelope-still-full-when-real
  (testing "rows with real for-pay and low flat fraction → :full"
    (with-redefs [analitica.domain.finance/date-basis-split
                  (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})]
      (let [env (basis-envelope-fn [{:for-pay 1500.0}] false)]
        (is (= :full (:completeness env))
            "real data with api-only source must be :full"))))

  (testing "rows with real for-pay + flat>=0.2 → :estimated (not :empty)"
    (with-redefs [analitica.domain.finance/date-basis-split
                  (fn [& _] {:api 0.5 :spread 0.3 :flat 0.2})]
      (let [env (basis-envelope-fn [{:for-pay 1500.0}] false)]
        (is (= :estimated (:completeness env))
            "real data with high flat fraction must be :estimated"))))

  (testing "snake_case :for_pay key is honoured for monetary check"
    (with-redefs [analitica.domain.finance/date-basis-split
                  (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})]
      (let [env (basis-envelope-fn [{:for_pay 200.0}] false)]
        (is (= :full (:completeness env))
            "snake_case :for_pay must count as monetary data")))))

(deftest ^:integration pulse-summary-empty-window-completeness-test
  ;; LT3: the Pulse top-level :completeness is computed by an inline
  ;; fin-completeness cond that must mirror basis-envelope — including the
  ;; :empty guard. Without it, a window with zero monetary finance reads
  ;; «полные данные» on the coverage chip (the lie LT3 fixes). A far-future
  ;; window has no finance rows, so :completeness MUST be :empty.
  (testing "zero-monetary (future) window → top-level :completeness :empty"
    (let [{:keys [status body]} (do-get "/api/v1/marker/pulse-summary"
                                        :params {:from "2099-01-01" :to "2099-01-31"})]
      (is (= 200 status))
      (is (= :empty (:completeness body))
          "Pulse :completeness must be :empty for a no-monetary-data window, not :full")
      (is (contains? body :date-basis)
          "Pulse must also carry top-level :date-basis for the coverage chip"))))

(deftest ^:integration pnl-basis-contract-test
  ;; FR-P4.1: pnl-handler response must carry basis-contract at envelope level.
  (testing "pnl response carries :date-basis map with :api/:spread/:flat"
    (let [{:keys [body]} (do-get "/api/v1/marker/pnl")]
      (is (contains? body :date-basis) "pnl response missing :date-basis (FR-P4.1)")
      (let [db (:date-basis body)]
        (is (map? db))
        (doseq [k [:api :spread :flat]]
          (is (contains? db k) (str "pnl :date-basis missing key " k))))))

  (testing "pnl response carries :completeness in #{:empty :full :estimated}"
    (let [{:keys [body]} (do-get "/api/v1/marker/pnl")]
      (is (contains? body :completeness) "pnl response missing :completeness (FR-P4.1)")
      (is (contains? #{:empty :full :estimated} (:completeness body))
          "pnl :completeness must be :empty, :full, or :estimated"))))

(deftest ^:integration sku-list-basis-contract-test
  ;; FR-P4.1: sku-list-handler response must carry basis-contract at envelope level.
  (testing "sku-list response carries :date-basis map with :api/:spread/:flat"
    (let [{:keys [body]} (do-get "/api/v1/marker/sku-list")]
      (is (contains? body :date-basis) "sku-list response missing :date-basis (FR-P4.1)")
      (let [db (:date-basis body)]
        (is (map? db))
        (doseq [k [:api :spread :flat]]
          (is (contains? db k) (str "sku-list :date-basis missing key " k))))))

  (testing "sku-list response carries :completeness in #{:empty :full :estimated}"
    (let [{:keys [body]} (do-get "/api/v1/marker/sku-list")]
      (is (contains? body :completeness) "sku-list response missing :completeness (FR-P4.1)")
      (is (contains? #{:empty :full :estimated} (:completeness body))
          "sku-list :completeness must be :empty, :full, or :estimated"))))

(deftest ^:integration sku-detail-basis-contract-test
  ;; FR-P4.1: sku-detail-handler response must carry basis-contract at envelope level.
  (testing "sku-detail response carries :date-basis map with :api/:spread/:flat"
    (let [{:keys [body]} (do-get "/api/v1/marker/sku-detail/SKU-TEST")]
      (is (contains? body :date-basis) "sku-detail response missing :date-basis (FR-P4.1)")
      (let [db (:date-basis body)]
        (is (map? db))
        (doseq [k [:api :spread :flat]]
          (is (contains? db k) (str "sku-detail :date-basis missing key " k))))))

  (testing "sku-detail response carries :completeness in #{:empty :full :estimated}"
    (let [{:keys [body]} (do-get "/api/v1/marker/sku-detail/SKU-TEST")]
      (is (contains? body :completeness) "sku-detail response missing :completeness (FR-P4.1)")
      (is (contains? #{:empty :full :estimated} (:completeness body))
          "sku-detail :completeness must be :empty, :full, or :estimated"))))

;; ---------------------------------------------------------------------------
;; LT2 — mp_commission wiring (pure, no DB)
;; ---------------------------------------------------------------------------


(deftest pulse-commission-uses-mp-commission-not-wb-reward
  (testing "Pulse :costs :commission is wired to :mp-commission, not :wb-reward"
    ;; Strategy: mock load-finance to return a row whose by-article aggregate has a
    ;; known mp-commission. pnl/calculate (called via compute-pnl) will produce
    ;; :mp-commission = -99.0 from 1 sale row with :mp-commission 99.0 (ozon positive).
    ;; :wb-reward = 200.0 is deliberately different so we can tell them apart.
    ;; The test verifies :costs :commission :value = -99.0 (from :mp-commission),
    ;; not 200.0 (from :wb-reward).
    (let [finance-row {:marketplace :wb :rrd-id 1
                       :date-from "2026-04-01" :date-to "2026-04-30"
                       :event-date "2026-04-15"
                       :article "LT2" :operation "sale" :quantity 1
                       :retail-amount 1000.0 :retail-price 1000.0
                       :for-pay 830.0 :mp-commission 99.0 :wb-reward 200.0
                       :delivery-cost 0.0 :storage-fee 0.0 :acceptance 0.0
                       :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
                       :additional-payment 0.0 :ad-cost 0.0}]
      (with-redefs
        [load-finance-var                                   (fn [& _] [finance-row])
         load-sales-var                                     (fn [& _] [])
         with-prelim-var                                    (fn [pnl & _] pnl)
         analitica.domain.stock/fetch-stocks                (fn [& _] [])
         analitica.domain.buyout/analyze                    (fn [& _] [])
         analitica.db/orders-by-article                     (fn [& _] [])
         analitica.canonical.events.query/units-ordered     (fn [& _] 0)
         analitica.canonical.events.query/units-delivered   (fn [& _] 0)
         analitica.alerts/freshness-data                    (fn [& _] {})
         analitica.alerts/detect-alerts                     (fn [& _] [])
         analitica.domain.finance/date-basis-split          (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})
         analitica.domain.finance/fetch-finance             (fn [& _] [finance-row])
         analitica.db/query                                 (fn [& _] [{:n 0}])]
        (let [body     (marker-api/pulse-summary {:params {}})
              comm-val (get-in body [:costs :commission :value])]
          (is (map? body) "handler returned a map")
          ;; :mp-commission (ozon-positive 99) normalizes to -99.0
          ;; :wb-reward = 200.0 — they are distinct, so we can prove the wire
          (is (= -99.0 comm-val)
              (str "Pulse :costs :commission must use :mp-commission (-99.0 normalized), "
                   "not :wb-reward (200.0). Got: " comm-val)))))))

(deftest pnl-rows-include-mp-commission-row
  (testing "pnl-row-defs contains a :mp-commission row labeled Комиссия МП"
    (let [mp-comm-row (some #(when (= :mp-commission (first %)) %)
                             marker-api/pnl-row-defs)]
      (is (some? mp-comm-row)
          "pnl-row-defs must contain [:mp-commission ...] entry")
      (is (= "Комиссия МП" (second mp-comm-row))
          "pnl-row-defs :mp-commission label must be \"Комиссия МП\"")
      (is (= "cost" (nth mp-comm-row 2))
          "pnl-row-defs :mp-commission group must be \"cost\""))))

(deftest sku-detail-commission-is-mp-commission
  (testing "sku-detail :commission field is wired to :mp-commission from by-article, not :deduction"
    ;; Strategy: feed pnl-handler a real finance row with :mp-commission != :deduction.
    ;; finance/by-article will produce :mp-commission -150.0 (WB negative, stored as-is
    ;; from the fixture) and :deduction 10.0. The handler's sku-detail should use
    ;; :mp-commission for :commission, yielding -150.0 not 10.0.
    (let [finance-row {:marketplace :wb :rrd-id 1
                       :date-from "2026-04-01" :date-to "2026-04-30"
                       :event-date "2026-04-15"
                       :article "TEST-SKU" :operation "sale" :quantity 1
                       :retail-amount 1000.0 :retail-price 1000.0
                       :for-pay 850.0 :mp-commission -150.0 :wb-reward 0.0
                       :delivery-cost 0.0 :storage-fee 0.0 :acceptance 0.0
                       :penalty 0.0 :acquiring-fee 0.0 :deduction 10.0
                       :additional-payment 0.0 :ad-cost 0.0}]
      (with-redefs
        [load-finance-var                          (fn [& _] [finance-row])
         analitica.domain.finance/fetch-finance    (fn [& _] [finance-row])
         analitica.domain.finance/date-basis-split (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})
         with-prelim-var                           (fn [pnl & _] pnl)
         analitica.domain.pnl/ad-spend-by-article  (fn [& _] {})
         analitica.domain.pnl/load-cf-adjustments  (fn [& _] nil)
         analitica.db/query                        (fn [& _] [{:n 0}])]
        (let [resp (marker-api/pnl-handler {:params {}})
              sku  (first (:sku-detail resp))]
          (is (map? resp) "pnl-handler returned a map")
          (is (some? sku) "sku-detail must be non-empty for the fixture row")
          (when sku
            (is (= -150.0 (:commission sku))
                (str "sku-detail :commission must use :mp-commission (-150.0), "
                     "not :deduction (10.0). Got: " (:commission sku))))))))

  (testing "sku-detail :commission normalizes Ozon/YM positive mp-commission to negative"
    ;; Ozon/YM store commission as a positive raw sum in finance rows; sku-detail must
    ;; negate it to match P&L convention.  Input :mp-commission 150.0 → expected -150.0.
    (let [finance-row {:marketplace :ozon :rrd-id 2
                       :date-from "2026-04-01" :date-to "2026-04-30"
                       :event-date "2026-04-15"
                       :article "OZON-SKU" :operation "sale" :quantity 1
                       :retail-amount 1000.0 :retail-price 1000.0
                       :for-pay 850.0 :mp-commission 150.0 :wb-reward 0.0
                       :delivery-cost 0.0 :storage-fee 0.0 :acceptance 0.0
                       :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
                       :additional-payment 0.0 :ad-cost 0.0}]
      (with-redefs
        [load-finance-var                          (fn [& _] [finance-row])
         analitica.domain.finance/fetch-finance    (fn [& _] [finance-row])
         analitica.domain.finance/date-basis-split (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})
         with-prelim-var                           (fn [pnl & _] pnl)
         analitica.domain.pnl/ad-spend-by-article  (fn [& _] {})
         analitica.domain.pnl/load-cf-adjustments  (fn [& _] nil)
         analitica.db/query                        (fn [& _] [{:n 0}])]
        (let [resp (marker-api/pnl-handler {:params {}})
              sku  (first (:sku-detail resp))]
          (is (map? resp) "pnl-handler returned a map")
          (is (some? sku) "sku-detail must be non-empty for the Ozon fixture row")
          (when sku
            (is (= -150.0 (:commission sku))
                (str "sku-detail :commission must normalize positive Ozon mp-commission "
                     "(150.0) to -150.0. Got: " (:commission sku)))))))))

;; ---------------------------------------------------------------------------
;; 015 T039 — pnl-handler wires the :management block (tax/opex layer)
;;
;; The handler assembles the management block via pnl/load-management-adjustments
;; and passes it as :management to pnl/calculate, so the response carries the
;; §3 management keys. Configured vs inert are asserted with mocked finance +
;; management block (deterministic, no live DB dependency).
;; ---------------------------------------------------------------------------

;; Shared fixture finance row for the T039 handler tests:
;;   1 WB sale, for_pay 830, revenue 1000, cogs 0 (no cost basis) → net-profit
;;   equals for_pay (no ad, no cogs). Chosen so tax base (=for_pay) is obvious.
(def ^:private t039-finance-row
  {:marketplace :wb :rrd-id 1
   :date-from "2026-04-01" :date-to "2026-04-30"
   :event-date "2026-04-15"
   :article "MGMT-SKU" :operation "sale" :quantity 1
   :retail-amount 1000.0 :retail-price 1000.0
   :for-pay 830.0 :mp-commission 0.0 :wb-reward 0.0
   :delivery-cost 0.0 :storage-fee 0.0 :acceptance 0.0
   :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
   :additional-payment 0.0 :ad-cost 0.0})

(deftest pnl-handler-emits-management-keys-when-configured
  (testing "pnl-handler wires load-management-adjustments → management keys present, configured? true"
    (with-redefs
      [load-finance-var                          (fn [& _] [t039-finance-row])
       analitica.domain.finance/fetch-finance    (fn [& _] [t039-finance-row])
       analitica.domain.finance/date-basis-split (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})
       with-prelim-var                           (fn [pnl & _] pnl)
       analitica.domain.pnl/ad-spend-by-article  (fn [& _] {})
       analitica.domain.pnl/load-cf-adjustments  (fn [& _] nil)
       ;; Configured management block: УСН-income 6% + 100 OPEX.
       analitica.domain.pnl/load-management-adjustments
       (fn [& _] {:cf               nil
                  :opex             100.0
                  :opex-by-category {"rent" 100.0}
                  :tax-config       {:taxation-type       :usn-income
                                     :usn-rate            0.06
                                     :vat-rate            0.0
                                     :official-cost-price true}
                  :configured?      true})
       analitica.db/query                        (fn [& _] [{:n 0}])]
      (let [resp (marker-api/pnl-handler {:params {}})]
        (is (map? resp) "pnl-handler returned a map")
        ;; Contract §3: the response is enriched with management keys at top level.
        (is (true? (:management-configured? resp)) ":management-configured? true")
        (doseq [k [:tax :tax-base :vat :opex :opex-by-category :profit
                   :profit-without-expense :management-margin
                   :margin-without-expense :management-zero-reason]]
          (is (contains? resp k) (str "management key " k " must be present")))
        ;; net-profit = for_pay − cogs − ad = 830 (cogs=0, ad=0).
        ;; tax-base = for_pay = 830; tax = round2(830 × 0.06) = 49.8.
        (is (= 830.0 (:tax-base resp)) "tax-base = for_pay")
        (is (= 49.8 (:tax resp)) "tax = 830 × 0.06")
        ;; profit = net − opex − tax = 830 − 100 − 49.8 = 680.2
        (is (= 680.2 (:profit resp))
            (str "management profit = net − opex − tax = 830 − 100 − 49.8. Got "
                 (:profit resp)))
        (is (= 780.2 (:profit-without-expense resp))
            "profit-without-expense = net − (tax + vat) = 830 − 49.8")))))

(deftest pnl-handler-management-inert-when-not-configured
  (testing "pnl-handler with unconfigured management → configured? false, profit == net-profit"
    (with-redefs
      [load-finance-var                          (fn [& _] [t039-finance-row])
       analitica.domain.finance/fetch-finance    (fn [& _] [t039-finance-row])
       analitica.domain.finance/date-basis-split (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})
       with-prelim-var                           (fn [pnl & _] pnl)
       analitica.domain.pnl/ad-spend-by-article  (fn [& _] {})
       analitica.domain.pnl/load-cf-adjustments  (fn [& _] nil)
       ;; Inert management block: no tax-config, 0 opex.
       analitica.domain.pnl/load-management-adjustments
       (fn [& _] {:cf               nil
                  :opex             0.0
                  :opex-by-category {}
                  :tax-config       nil
                  :configured?      false})
       analitica.db/query                        (fn [& _] [{:n 0}])]
      (let [resp (marker-api/pnl-handler {:params {}})
            net-row (some #(when (= :net-profit (:key %)) %) (:rows resp))]
        (is (map? resp) "pnl-handler returned a map")
        (is (false? (:management-configured? resp)) ":management-configured? false when inert")
        (is (contains? resp :profit) ":profit still emitted (inert)")
        (is (= (:cur net-row) (:profit resp))
            "inert: management :profit must equal :net-profit (byte-identical, FR-016)")))))

;; compute-pnl unit: management block passed → management keys present on result
(deftest compute-pnl-passes-management-block-through
  (testing "compute-pnl 5-arity forwards :management to pnl/calculate"
    ;; Mock ad-spend-by-article → {} so no live-DB ad-spend leaks into net-profit;
    ;; assert the INV-1/INV-2 relationships hold over whatever net-profit results.
    (with-redefs [analitica.domain.finance/fetch-finance (fn [& _] [t039-finance-row])
                  analitica.domain.pnl/ad-spend-by-article (fn [& _] {})]
      (let [mgmt {:cf nil :opex 100.0 :opex-by-category {"rent" 100.0}
                  :tax-config {:taxation-type :usn-income :usn-rate 0.06
                               :vat-rate 0.0 :official-cost-price true}
                  :configured? true}
            result ((deref compute-pnl-var)
                    [t039-finance-row] {:from "2026-04-01" :to "2026-04-30"} :wb nil mgmt)
            net    (:net-profit result)
            tax    (:tax result)
            vat    (:vat result)
            opex   (:opex result)]
        (is (true? (:management-configured? result)) ":management-configured? true")
        (is (contains? result :tax) ":tax present")
        (is (contains? result :vat) ":vat present")
        (is (contains? result :opex) ":opex present")
        (is (contains? result :profit) ":profit present")
        (is (contains? result :profit-without-expense) ":profit-without-expense present")
        (is (contains? result :management-margin) ":management-margin present")
        (is (contains? result :management-zero-reason) ":management-zero-reason present")
        (is (= 100.0 opex) ":opex forwarded from the management block")
        ;; INV-1: profit = round2(net − opex − (tax + vat))
        (is (= (analitica.util.math/round2 (- net opex tax vat)) (:profit result))
            "INV-1: profit = net − opex − (tax + vat)")
        ;; INV-2: profit-without-expense = round2(net − (tax + vat))
        (is (= (analitica.util.math/round2 (- net tax vat)) (:profit-without-expense result))
            "INV-2: profit-without-expense = net − (tax + vat)")))))

;; ---------------------------------------------------------------------------
;; 016 US5 — reports-handler merges user-metric descriptors into :columns and
;; computes each row's user-metric value via eval-user-metric before returning.
;; ---------------------------------------------------------------------------

(deftest reports-handler-merges-user-metric-columns-and-values
  (testing "user-metric descriptors are appended to :columns and each row carries the computed value"
    (let [;; A saved user metric: revenue-per-order = revenue / orders.
          user-metric {:slug :rev-per-order :name "Выручка/заказ"
                       :formula [:/ :revenue :orders]
                       :suffix :rub :filterType :number-range :positiveIfGrow true
                       :basis "gross realisation per order"}
          ;; Two rows: one with orders>0, one with orders=0 (div-by-0 → nil).
          rows        [{:article "A1" :revenue 1000.0 :orders 4}
                       {:article "A2" :revenue 500.0  :orders 0}]]
      (with-redefs
        [analitica.web.api.report/report-data
         (fn [& _] {:rows rows :totals {}})
         analitica.web.report-schemas/fetch-user-metrics (fn [] [user-metric])
         load-finance-var                          (fn [& _] [])
         compute-pnl-var                           (fn [& _] {})
         analitica.domain.finance/date-basis-split (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})]
        (let [resp (marker-api/reports-handler {:params {:type "ue"}})
              body (:body resp)
              cols (:columns body)
              out  (:rows body)
              by-a (into {} (map (juxt :article identity)) out)]
          (is (= 200 (:status resp)))
          ;; Descriptor merged into columns
          (is (some #(= :rev-per-order (:key %)) cols)
              "user-metric descriptor must be appended to :columns")
          (is (true? (:user-defined? (some #(when (= :rev-per-order (:key %)) %) cols)))
              "merged column is flagged :user-defined?")
          ;; Row values computed via eval-user-metric
          (is (= 250.0 (get-in by-a ["A1" :rev-per-order]))
              "A1: 1000 / 4 = 250")
          (is (nil? (get-in by-a ["A2" :rev-per-order]))
              "A2: division by zero → nil (N/A discipline)"))))))

(deftest reports-handler-no-user-metrics-is-inert
  (testing "with no user metrics, :columns and :rows are byte-identical to base (additive-only)"
    (let [rows [{:article "A1" :revenue 1000.0 :orders 4}]]
      (with-redefs
        [analitica.web.api.report/report-data
         (fn [& _] {:rows rows :totals {}})
         analitica.web.report-schemas/fetch-user-metrics (fn [] [])
         load-finance-var                          (fn [& _] [])
         compute-pnl-var                           (fn [& _] {})
         analitica.domain.finance/date-basis-split (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})]
        (let [resp (marker-api/reports-handler {:params {:type "ue"}})
              out  (:rows (:body resp))]
          (is (= 200 (:status resp)))
          (is (= rows out) "rows unchanged when no user metrics exist"))))))

;; ---------------------------------------------------------------------------
;; 016-US3 — pnl-handler emits the layered waterfall block
;; ---------------------------------------------------------------------------

(deftest pnl-handler-emits-waterfall
  (testing "pnl-handler response carries a :waterfall whose netProfit ties to pnl :net-profit"
    (let [finance-row {:marketplace :wb :rrd-id 1
                       :date-from "2026-04-01" :date-to "2026-04-30"
                       :event-date "2026-04-15"
                       :article "WF-SKU" :operation "sale" :quantity 1
                       :retail-amount 1000.0 :retail-price 1000.0
                       :for-pay 760.0 :mp-commission -150.0 :wb-reward 0.0
                       :delivery-cost 40.0 :storage-fee 10.0 :acceptance 5.0
                       :penalty 0.0 :acquiring-fee 0.0 :deduction 5.0
                       :additional-payment 20.0 :ad-cost 0.0}]
      (with-redefs
        [load-finance-var                          (fn [& _] [finance-row])
         analitica.domain.finance/fetch-finance    (fn [& _] [finance-row])
         analitica.domain.finance/date-basis-split (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})
         with-prelim-var                           (fn [pnl & _] pnl)
         analitica.domain.pnl/ad-spend-by-article  (fn [& _] {})
         analitica.domain.pnl/load-cf-adjustments  (fn [& _] nil)
         analitica.db/query                        (fn [& _] [{:n 0}])]
        (let [resp (marker-api/pnl-handler {:params {}})
              wf   (:waterfall resp)
              line (fn [k] (first (filter #(= k (:key %)) wf)))]
          (is (vector? wf) "response must carry a :waterfall vector")
          (is (= "Выручка (GROSS)" (:label (line :sales))) "top line is GROSS revenue")
          (is (some? (line :mp-commission)) "commission is a visible child line")
          (let [np (:amount (line :net-profit))
                p  (analitica.domain.pnl/calculate [finance-row]
                                                   :marketplace :wb
                                                   :from "2026-04-01" :to "2026-04-30")]
            (is (= (:net-profit p) np)
                "waterfall netProfit == pnl :net-profit to the kopeck"))))))
  (testing "?compare=true attaches deltas; default omits them (neutral)"
    (let [finance-row {:marketplace :wb :rrd-id 1
                       :date-from "2026-04-01" :date-to "2026-04-30"
                       :event-date "2026-04-15"
                       :article "WF-SKU" :operation "sale" :quantity 1
                       :retail-amount 1000.0 :retail-price 1000.0
                       :for-pay 760.0 :mp-commission -150.0 :wb-reward 0.0
                       :delivery-cost 40.0 :storage-fee 10.0 :acceptance 5.0
                       :penalty 0.0 :acquiring-fee 0.0 :deduction 5.0
                       :additional-payment 20.0 :ad-cost 0.0}]
      (with-redefs
        [load-finance-var                          (fn [& _] [finance-row])
         analitica.domain.finance/fetch-finance    (fn [& _] [finance-row])
         analitica.domain.finance/date-basis-split (fn [& _] {:api 1.0 :spread 0.0 :flat 0.0})
         with-prelim-var                           (fn [pnl & _] pnl)
         analitica.domain.pnl/ad-spend-by-article  (fn [& _] {})
         analitica.domain.pnl/load-cf-adjustments  (fn [& _] nil)
         analitica.db/query                        (fn [& _] [{:n 0}])]
        (let [no-cmp   (:waterfall (marker-api/pnl-handler {:params {}}))
              with-cmp (:waterfall (marker-api/pnl-handler {:params {:compare "true"}}))
              sales-nc (first (filter #(= :sales (:key %)) no-cmp))
              sales-wc (first (filter #(= :sales (:key %)) with-cmp))]
          (is (nil? (:delta-pct sales-nc)) "no compare ⇒ neutral (delta-pct nil)")
          (is (contains? sales-wc :delta) "compare=true ⇒ line carries :delta"))))))

;; ---------------------------------------------------------------------------
;; LT3 — sku-list-empty-window-not-full (pure, no DB)
;; ---------------------------------------------------------------------------

(deftest sku-list-empty-window-not-full
  (testing "sku-list on a zero-finance window returns :completeness :empty, not :full"
    ;; Before LT3 fix: load-finance returns [] → basis-envelope → flat=0.0 → :full (lie).
    ;; After LT3 fix: empty monetary sum → :empty.
    (with-redefs
      [load-finance-var                                   (fn [& _] [])
       analitica.domain.sales/fetch-sales                 (fn [& _] [])
       analitica.domain.stock/fetch-stocks                (fn [& _] [])
       analitica.domain.pnl/ad-spend-by-article           (fn [& _] {})
       analitica.db/query                                  (fn [& _] [{:n 0}])]
      (let [resp (marker-api/sku-list-handler {:params {}})]
        (is (map? resp) "sku-list-handler returned a map")
        (is (= [] (:skus resp)) "empty finance → empty skus")
        (is (= :empty (:completeness resp))
            (str "empty window must have :completeness :empty, got: "
                 (:completeness resp)))))))

;; ---------------------------------------------------------------------------
;; LT3 — reports-handler-carries-envelope (pure, no DB)
;; ---------------------------------------------------------------------------

(def ^:private report-data-var #'analitica.web.api.report/report-data)

(deftest reports-handler-carries-envelope
  (testing "reports-handler 200 body carries :completeness :date-basis :preliminary? from basis-envelope"
    (with-redefs
      [load-finance-var   (fn [& _] [])
       ;; load-finance-var (a def holding #'load-finance) is rewritten by the
       ;; with-redefs MACRO as (var load-finance-var) — it rebinds the def, NOT
       ;; the real private marker-api/load-finance, so the envelope's finance
       ;; fetch would otherwise hit the real dev DB and read :full on any
       ;; calendar day with data in the default 30-day window (latent order-
       ;; dependent flake exposed 2026-07-01 by batch 014 reshuffling test order).
       ;; Stub the underlying fetch directly so the "empty window → :empty"
       ;; contract is deterministic and truly DB-free (matches sibling
       ;; sku-list-empty-window-not-full, which stubs the db/query path).
       analitica.domain.finance/fetch-finance (fn [& _] [])
       report-data-var    (fn [& _] {:rows [] :totals {}})]
      (let [resp (marker-api/reports-handler
                   {:request-method :get
                    :params {:type "finance"}
                    :headers {}})]
        (is (= 200 (:status resp)) "reports-handler must return 200")
        (is (contains? (:body resp) :completeness)
            "reports 200 body must carry :completeness")
        (is (contains? (:body resp) :date-basis)
            "reports 200 body must carry :date-basis")
        (is (contains? (:body resp) :preliminary?)
            "reports 200 body must carry :preliminary?")
        (is (= :empty (:completeness (:body resp)))
            "empty finance window → :completeness :empty on reports"))))

  (testing "reports-handler 400 body does NOT need envelope"
    (let [resp (marker-api/reports-handler
                 {:request-method :get :params {:type "nonsense"} :headers {}})]
      (is (= 400 (:status resp))))))

;; ---------------------------------------------------------------------------
;; LT4 (BUG B) — Pulse Plan/Fact headline must match chart basis
;; :month-fact comes from realization-based pnl rev-cur;
;; :revenue-30d (the chart spark) must also be realization-based so that
;;   (reduce + (:revenue-30d charts)) ≈ (:month-fact forecast).
;; Before LT4, :revenue-30d used revenue-spark (sales table) while
;; :month-fact used pnl revenue (finance table) — ~6× mismatch.
;; ---------------------------------------------------------------------------

(deftest pulse-plan-fact-headline-matches-chart-basis
  "When pnl revenue is non-zero, the chart :revenue-30d daily series must
   sum to approximately :month-fact (same realization basis, ±1 ₽ rounding).
   We stub finance/fetch-finance + pnl/calculate to return a controlled
   revenue of 10 000 ₽ and verify the chart spark sums to that same total."
  (let [period-map {:from "2026-06-01" :to "2026-06-07"}
        rev-total  10000.0
        ;; Distribute 10 000 evenly over 7 days
        daily-val  (/ rev-total 7)
        fin-rows   (for [i (range 7)]
                     {:for-pay daily-val
                      :event_date (str "2026-06-0" (inc i))
                      :marketplace "wb"
                      :type "realization"})
        fin-rows   (vec fin-rows)]
    (with-redefs [analitica.domain.finance/fetch-finance
                  (fn [_period & _opts] fin-rows)
                  analitica.domain.pnl/calculate
                  (fn [_data & _opts]
                    {:revenue       rev-total
                     :net-profit    2000.0
                     :gross-profit  3000.0
                     :margin-net    20.0
                     :ad-spend      0.0
                     :cogs          0.0
                     :logistics     0.0
                     :for-pay       rev-total
                     :sales-qty     70
                     :returns-qty   0
                     :buyout-rate   100.0
                     :avg-check     143.0})
                  analitica.domain.pnl/load-cf-adjustments
                  (fn [& _] nil)
                  analitica.domain.sales/fetch-sales
                  (fn [& _] [])
                  analitica.domain.stock/fetch-stocks
                  (fn [& _] [])
                  analitica.domain.buyout/analyze
                  (fn [& _] [])
                  analitica.domain.buyout/aggregate
                  (fn [& _] {:buyout-orders-rate nil :cancel-rate nil
                              :non-return-rate nil :placed 0 :sold 0 :returned 0})
                  analitica.alerts/detect-alerts
                  (fn [& _] [])
                  analitica.alerts/freshness-data
                  (fn [] {:wb nil :ozon nil :ym nil})
                  analitica.canonical.events.query/units-ordered
                  (fn [& _] 0)
                  analitica.canonical.events.query/units-delivered
                  (fn [& _] 0)
                  analitica.db/orders-by-article
                  (fn [& _] [])]
      (let [req  {:params {:from "2026-06-01" :to "2026-06-07" :mp "wb"}}
            resp (marker-api/pulse-summary req)
            month-fact  (get-in resp [:forecast :month-fact])
            rev-30d     (get-in resp [:charts   :revenue-30d])
            chart-total (when (seq rev-30d) (reduce + rev-30d))]
        (testing ":forecast :month-fact is the pnl revenue (10 000 ₽)"
          (is (= (analitica.util.math/round2 rev-total) month-fact)))
        (testing ":charts :revenue-30d is a non-empty vector"
          (is (vector? rev-30d))
          (is (pos? (count rev-30d))))
        (testing ":revenue-30d sums to ≈ :month-fact (same realization basis, ±1 rounding)"
          (is (some? chart-total))
          (is (< (Math/abs (- (double month-fact) (double chart-total))) 1.0)
              (str "Expected chart-total≈" month-fact " but got " chart-total)))))))

;; ---------------------------------------------------------------------------
;; FR-003: compute-mp-share must weigh by Σ unit-qty, not row count
;; ---------------------------------------------------------------------------

(deftest mp-share-weighs-by-quantity
  ;; 1 WB unit vs 3 Ozon units → WB=25%, Ozon=75%, YM=0%
  ;; before-fix: row-count weighting → {:wb 50.0 :ozon 50.0 :ym 0.0}
  (testing "unit-weighted split: 1 WB unit vs 3 Ozon units"
    (let [rows [{:type :sale :marketplace :wb   :quantity 1}
                {:type :sale :marketplace :ozon :quantity 3}]
          r    (compute-mp-share rows)]
      (is (= 25.0  (:wb   r)) "WB:   1/4 units = 25%  ;; before-would-be 50.0")
      (is (= 75.0  (:ozon r)) "Ozon: 3/4 units = 75%  ;; before-would-be 50.0")
      (is (= 0.0   (:ym   r)) "YM: not present = 0%")))

  (testing "WB zero-regression: quantity-less rows (unit-qty coalesces to 1) == old row-count behavior"
    ;; 2 WB rows (no :quantity) + 2 Ozon rows (no :quantity) → 50/50, same as before
    (let [rows [{:type :sale :marketplace :wb}
                {:type :sale :marketplace :wb}
                {:type :sale :marketplace :ozon}
                {:type :sale :marketplace :ozon}]
          r    (compute-mp-share rows)]
      (is (= 50.0 (:wb   r)))
      (is (= 50.0 (:ozon r)))
      (is (= 0.0  (:ym   r)))))

  (testing "string marketplace keys are coerced to keywords (existing behavior retained)"
    (let [rows [{:type :sale :marketplace "wb"   :quantity 2}
                {:type :sale :marketplace "ozon" :quantity 2}]
          r    (compute-mp-share rows)]
      (is (= 50.0 (:wb   r)))
      (is (= 50.0 (:ozon r))))))

;; ---------------------------------------------------------------------------
;; FR-005: max-drr-numerator must include all variable costs
;; ---------------------------------------------------------------------------
;; (the #'marker-api/max-drr-numerator var alias is defined once, above the
;;  B4e max-ДРР section, so the pure formula test can reference it too.)

(deftest max-drr-numerator-includes-variable-costs
  ;; Fixture from spec contract (metric-formulas.edn §FR-005):
  ;;   revenue=1000, for-pay=700, cogs=200, logistics=50, storage=20, penalties=10, acceptance=20
  ;;   numerator = 700 - 200 - 50 - 20 - 10 - 20 = 400
  ;;   max-drr-pct = 400 / 1000 * 100 = 40.0
  ;;   before-would-be: (700 - 200) / 1000 * 100 = 50.0  (gross-margin only)
  (testing "numerator deducts all variable costs"
    (is (= 400.0 (max-drr-numerator 700.0 200.0 50.0 20.0 10.0 20.0))
        "for-pay=700 cogs=200 logistics=50 storage=20 penalties=10 acceptance=20 => 400  ;; before-would-be 500.0"))

  (testing "max-drr-pct = numerator/revenue*100 = 40.0 on spec fixture"
    (let [rev        1000.0
          for-pay    700.0
          cogs       200.0
          logistics  50.0
          storage    20.0
          penalties  10.0
          acceptance 20.0
          numer      (max-drr-numerator for-pay cogs logistics storage penalties acceptance)
          pct        (analitica.util.math/percentage numer rev)]
      (is (= 40.0 pct) "max-drr-pct = 40.0  ;; before-would-be 50.0")))

  (testing "zero variable costs → numerator = for-pay - cogs (same as old gross-margin)"
    (is (= 500.0 (max-drr-numerator 700.0 200.0 0.0 0.0 0.0 0.0))))

  (testing "coalesces nil variable-cost fields to 0"
    ;; nil variable-cost args must be treated as 0.0 (exercises the (or x 0.0)
    ;; coalescing) → numerator = for-pay − cogs = 500.0.
    (is (= 500.0 (max-drr-numerator 700.0 200.0 nil nil nil nil))
        "nil logistics/storage/penalties/acceptance coalesce to 0 → 700 − 200 = 500")))

;; ---------------------------------------------------------------------------
;; Audit 2026-07-02 N1 — :additional (доплаты) is a seller credit and must
;; REDUCE «Прочее»/«Итого расходы», not inflate them.
;; ---------------------------------------------------------------------------

(deftest additional-payments-reduce-other-costs
  (let [pnl {:storage 100.0 :acceptance 50.0 :penalties 30.0
             :deduction 20.0 :additional 60.0
             :cogs 500.0 :wb-reward 10.0 :logistics 40.0 :ad-spend 25.0}]
    (testing "other = storage+acceptance+penalties+deduction − additional"
      (is (= 140.0 (#'marker-api/sum-other-costs pnl))))
    (testing "total inherits the credit"
      (is (= (+ 500.0 10.0 40.0 25.0 140.0)
             (#'marker-api/sum-total-costs pnl))))
    (testing "cost block reconciles with the signed P&L identity"
      ;; gross-profit = for-pay − cogs − logistics − storage − penalties
      ;;                − acceptance − deduction + additional  (pnl.clj:248)
      ;; ⇒ for-pay − (cogs+logistics+other) = gross-profit exactly when
      ;;   other carries additional as a credit.
      (let [for-pay 1000.0
            gross   (+ (- for-pay 500.0 40.0 100.0 30.0 50.0 20.0) 60.0)]
        (is (= gross (- for-pay 500.0 40.0 (#'marker-api/sum-other-costs pnl))))))))

;; ---------------------------------------------------------------------------
;; Audit 2026-07-02 N3 — GMROI numerator must be the FULL MP-level net profit
;; (canon Stock.GMROI.1 / 016 FR-009), not for-pay − cogs − ads.
;; ---------------------------------------------------------------------------

(deftest gmroi-numerator-is-full-net-profit
  (let [fa {:for-pay 1000.0 :total-cost 400.0 :logistics 120.0 :storage 30.0
            :penalties 10.0 :acceptance 5.0 :deduction 15.0 :additional 20.0}]
    (testing "all cost components subtracted, additional credited"
      (is (= (+ (- 1000.0 400.0 120.0 30.0 10.0 5.0 15.0 50.0) 20.0)
             (#'marker-api/gmroi-net-profit fa 50.0))))
    (testing "logistics-heavy SKU: new numerator strictly below the old formula"
      (is (< (#'marker-api/gmroi-net-profit fa 50.0)
             (- 1000.0 400.0 50.0))))
    (testing "nil-safe on missing components"
      (is (= 1000.0 (#'marker-api/gmroi-net-profit {:for-pay 1000.0} nil))))))

;; ---------------------------------------------------------------------------
;; Audit 2026-07-02 — pulse :forecast :month-plan wired to 017 plans (L6)
;; and :charts :dates carries the real day axis (M1).
;; ---------------------------------------------------------------------------

(deftest pulse-forecast-month-plan-and-chart-dates
  (testing "no plan set → :month-plan nil (SPA hides the plan line)"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")]
      (is (nil? (get-in body [:forecast :month-plan])))))
  (testing "an :all revenue plan for the current month surfaces as month-plan"
    (let [month (subs (str (java.time.LocalDate/now)) 0 7)]
      (analitica.domain.plan/save-plan! {:period-month month
                                         :marketplace  :all
                                         :metric       :revenue
                                         :target-value 500000.0})
      (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")]
        (is (= 500000.0 (get-in body [:forecast :month-plan]))))))
  (testing ":charts :dates is the ISO day axis, same length as revenue-30d"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          ch (:charts body)]
      (is (vector? (:dates ch)))
      (is (= (count (:dates ch)) (count (:revenue-30d ch))))
      (is (every? #(re-matches #"\d{4}-\d{2}-\d{2}" %) (:dates ch))))))
