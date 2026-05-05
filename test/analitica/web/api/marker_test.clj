(ns analitica.web.api.marker-test
  "Integration tests for the Marker SPA Transit API endpoints.

   Constructs minimal Ring request maps directly (no ring.mock dep).
   Tests boot the full Ring app, make requests, decode Transit responses,
   and assert on *shape* (key presence + types) rather than specific values —
   the backend data is real and may shift."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.util.math]
            [analitica.web.server :as server]
            [analitica.web.middleware.transit :as transit-mw]
            [analitica.web.api.marker :as marker-api])
  (:import (java.io ByteArrayInputStream)))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn init-test-db [f]
  (db/init!)
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
;; B1. pulse-summary — shape tests
;; ---------------------------------------------------------------------------

(deftest ^:integration pulse-summary-shape-test
  (testing "returns 200"
    (let [{:keys [status body]} (do-get "/api/v1/marker/pulse-summary")]
      (is (= 200 status))
      (is (map? body))))

  (testing "has all required top-level keys"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")]
      (doseq [k [:alerts :kpis :forecast :charts :top-movers :top-fallers
                 :critical-stocks :data-fresh]]
        (is (contains? body k) (str "missing key: " k)))))

  (testing ":alerts is a vector"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")]
      (is (vector? (:alerts body)))))

  (testing ":kpis contains all 10 KPI keys"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          kpis (:kpis body)]
      (is (map? kpis))
      (doseq [k [:revenue :profit :orders :purchases :realized :margin
                 :avg-check :buyout :roas :drr]]
        (is (contains? kpis k) (str "kpis missing: " k)))))

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
  #{:realization :preliminary :canon :legacy-orders :legacy-sales :none})

(deftest ^:integration pulse-summary-source-metadata-test
  (testing "every KPI has :source and :as-of keys"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          kpis (:kpis body)]
      (doseq [k [:revenue :profit :orders :purchases :realized :margin
                 :avg-check :buyout :roas :drr]]
        (let [kpi (get kpis k)]
          (is (contains? kpi :source)
              (str k " missing :source"))
          (is (contains? kpi :as-of)
              (str k " missing :as-of"))))))

  (testing ":source values are from the closed valid set"
    (let [{:keys [body]} (do-get "/api/v1/marker/pulse-summary")
          kpis (:kpis body)]
      (doseq [k [:revenue :profit :orders :purchases :realized :margin
                 :avg-check :buyout :roas :drr]]
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
  (testing ":realized matches sum of finance.sale-qty across articles"
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
      ;; In a healthy late-month state, realized = purchases (all delivered are settled).
      ;; Mid-month or with Ozon realization lag, purchases > realized.
      ;; realized > purchases is impossible (you can't be paid for what wasn't delivered).
      (is (>= purchases realized)
          (str "Realized must not exceed delivered. Got purchases=" purchases
               " realized=" realized)))))

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
      (is (contains? pf :projection)))))

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
