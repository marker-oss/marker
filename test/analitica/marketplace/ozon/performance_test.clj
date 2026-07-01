(ns analitica.marketplace.ozon.performance-test
  "US1 + US2 unit tests for the Ozon Performance transform + client (spec 011).

   US1:
     T012 — :api per-SKU attribution.
     T013 — :spread revenue-weighted, Σ per-article == campaign total EXACTLY.
     T014 — cash≠bonus split (only :spend flows to finance.ad_cost).
     T016 — read-only posture (client only touches GET/stat + POST token/statistics).

   US2 (release gate):
     T024 — optionality: config nil → {:status :not-configured}, no ad_spend, ad_cost=0.
     T025 — empty campaigns: creds present, list-campaigns=[] → identical to no-creds.
     T026 — failure-isolation: 503/401 → {:status :ad-unavailable}; non-ad finance rows unchanged.
     T027 — idempotency: 2× ingest+materialize → identical ad_spend / finance.ad_cost."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [analitica.config :as config]
            [analitica.db :as db]
            [analitica.ingest :as ingest]
            [analitica.materialize :as mat]
            [analitica.marketplace.ozon.performance.transform :as pt]
            [analitica.marketplace.ozon.performance.client :as pc]
            [analitica.schema.normalized.ad-spend :as ad-spend])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private synced-at "2026-05-01T00:00:00")

;; ---------------------------------------------------------------------------
;; T012 — :api per-SKU attribution
;; ---------------------------------------------------------------------------

(deftest api-attribution-per-sku
  (testing "a daily row carrying :sku maps to an :api ad_spend row via ozon_sku_map"
    (let [rows        [{:date "2026-04-10" :id "78901" :sku "12345678"
                        :moneySpent 410.00 :bonusSpent 0.0}]
          sku->article {"12345678" "ABC-123"}
          {:keys [api-rows]} (pt/attribute-daily-rows rows sku->article
                                                       {:synced-at synced-at
                                                        :campaign-types {"78901" "SKU"}})
          row (first api-rows)]
      (is (= 1 (count api-rows)))
      (is (= :ozon (:marketplace row)))
      (is (= :api (:attribution-source row)))
      (is (= "ABC-123" (:article row)) "sku resolved to article via ozon_sku_map")
      (is (= "12345678" (:sku row)))
      (is (= "2026-04-10" (:event-date row)) "day-grain event-date straight from source")
      (is (= "78901" (:campaign-id row)))
      (is (= 410.00 (:spend row)) ":spend == moneySpent")
      (is (= 0.0 (:bonus-spend row)))
      (is (string? (:basis row)) ":basis doc-string present (P6)")
      (is (ad-spend/valid-ad-spend? row) "conforms to shared §3.B canon schema"))))

;; ---------------------------------------------------------------------------
;; T013 — :spread revenue-weighted, Σ per-article == campaign total EXACTLY
;; ---------------------------------------------------------------------------

(deftest spread-revenue-weighted-exact
  (testing "campaign-level spend spreads over articles proportional to revenue,
            attribution-source :spread, Σ per-article == campaign total EXACTLY"
    (let [;; Campaign 78902 (banner): two days, total moneySpent = 1034.56.
          rows            [{:date "2026-04-10" :id "78902" :moneySpent 500.00 :bonusSpent 60.00}
                           {:date "2026-04-20" :id "78902" :moneySpent 534.56 :bonusSpent 40.00}]
          ;; Article revenue weights: 60% / 40% split — chosen so the
          ;; distribution has a rounding residue to route to largest-weight.
          article->revenue {"ART-A" 6000.0 "ART-B" 4000.0}
          spread-rows     (pt/spread-campaign-spend "78902" "BANNER" rows
                                                    article->revenue
                                                    {:synced-at synced-at})
          total-spend     (reduce + 0.0 (map :spend spread-rows))
          total-bonus     (reduce + 0.0 (map :bonus-spend spread-rows))]
      (is (seq spread-rows))
      (is (every? #(= :spread (:attribution-source %)) spread-rows))
      (is (every? #(= :ozon (:marketplace %)) spread-rows))
      (is (every? #(string? (:basis %)) spread-rows))
      (is (every? ad-spend/valid-ad-spend? spread-rows))
      ;; EXACT kopeck reconciliation: Σ spend == campaign total (1034.56).
      (is (= 103456 (Math/round (* total-spend 100.0)))
          "residue routed so Σ per-article == campaign total (1034.56) to the kopeck")
      (is (= 10000 (Math/round (* total-bonus 100.0)))
          "Σ bonus-spend == 100.00 exactly (60 + 40)")
      ;; Article ABC gets the larger revenue share.
      (let [by-article (group-by :article spread-rows)
            a-total    (reduce + 0.0 (map :spend (get by-article "ART-A")))
            b-total    (reduce + 0.0 (map :spend (get by-article "ART-B")))]
        (is (> a-total b-total) "larger-revenue article gets larger spend"))))

  (testing "zero-revenue period → flat-spread across days, total not lost"
    (let [rows        [{:date "2026-04-10" :id "78902" :moneySpent 500.00 :bonusSpent 0.0}
                       {:date "2026-04-20" :id "78902" :moneySpent 534.56 :bonusSpent 0.0}]
          ;; No article has revenue in the period.
          spread-rows (pt/spread-campaign-spend "78902" "BANNER" rows {} {:synced-at synced-at})
          total-spend (reduce + 0.0 (map :spend spread-rows))]
      (is (seq spread-rows) "flat fallback still emits rows")
      (is (= 103456 (Math/round (* total-spend 100.0)))
          "total preserved under flat fallback")
      (is (every? #(nil? (:article %)) spread-rows)
          "no revenue → account-level rows carry nil article"))))

;; ---------------------------------------------------------------------------
;; T014 — cash ≠ bonus split
;; ---------------------------------------------------------------------------

(deftest cash-not-bonus-split
  (testing "moneySpent=1234.56 bonusSpent=100.00 → spend=1234.56, bonus-spend=100.00;
            only :spend (cash) is the ad_cost contribution"
    (let [rows        [{:date "2026-04-10" :id "78901" :sku "12345678"
                        :moneySpent 1234.56 :bonusSpent 100.00}]
          sku->article {"12345678" "ABC-123"}
          {:keys [api-rows]} (pt/attribute-daily-rows rows sku->article
                                                       {:synced-at synced-at})
          row (first api-rows)]
      (is (= 1234.56 (:spend row)))
      (is (= 100.00 (:bonus-spend row)))
      ;; The finance.ad_cost contribution helper uses ONLY :spend, never bonus.
      (is (= 1234.56 (pt/ad-cost-contribution row))
          "ad_cost contribution == cash spend only (bonus excluded → no double-count)"))))

;; ---------------------------------------------------------------------------
;; T016 — read-only posture
;; ---------------------------------------------------------------------------

(deftest read-only-posture
  (testing "OzonPerfClient reaches ONLY read/stat + auth endpoints — never a
            campaign-management/write URL (FR-010, P2)"
    (let [urls (pc/reachable-urls)]
      (is (seq urls))
      ;; Every declared URL is one of: token (POST auth), campaign (GET),
      ;; statistics/daily (GET), statistics create (POST report), status/download.
      (is (every? (fn [{:keys [url method]}]
                    (and (str/starts-with? url "https://api-performance.ozon.ru")
                         ;; No write verbs beyond POST-token and POST-statistics
                         ;; (create-report is a read-side async report request).
                         (contains? #{:get :post} method)))
                  urls))
      ;; Explicitly assert no campaign-management / write path is reachable.
      (is (not-any? (fn [{:keys [url]}]
                      (or (str/includes? url "/campaign/")   ;; edit/activate/deactivate
                          (str/includes? url "update")
                          (str/includes? url "activate")
                          (str/includes? url "deactivate")
                          (str/includes? url "delete")))
                    urls)
          "no campaign-management/write endpoint is reachable"))))

;; ===========================================================================
;; US2 — release-gate tests (T024-T027).
;;
;; These exercise the full ingest → materialize path against a temp SQLite DB,
;; so they need the same isolated-DB fixture as ozon-ads-reconcile-test.
;; ===========================================================================

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-perf-us2-" ".db" (make-array FileAttribute 0))
        f    (.toFile path)]
    (.delete f)
    (.getAbsolutePath f)))

(defn- delete-test-db! [path]
  (doseq [suffix ["" "-shm" "-wal"]]
    (let [f (File. (str path suffix))]
      (when (.exists f) (.delete f)))))

(defn with-temp-db [f]
  (let [path      (fresh-temp-db-path)
        orig-spec (deref #'db/db-spec)]
    (try
      (alter-var-root #'db/db-spec (constantly {:dbtype "sqlite" :dbname path}))
      (binding [*test-db-path* path]
        (db/init!)
        (f))
      (finally
        (alter-var-root #'db/db-spec (constantly orig-spec))
        (reset! @#'db/datasource nil)
        (delete-test-db! path)))))

(use-fixtures :each with-temp-db)

(def ^:private from "2026-04-01")
(def ^:private to   "2026-04-30")
(def ^:private synced "2026-05-01T00:00:00")

(def ^:private finance-cols
  [:rrd_id :report_id :date_from :date_to :event_date :event_date_source
   :article :nm_id :operation :operation_subtype
   :retail_amount :for_pay :quantity :ad_cost :marketplace :synced_at])

(defn- seed-finance! []
  ;; Three ozon sale rows — the pre-feature baseline (ad_cost = 0 everywhere).
  (let [rows [{:rrd_id "r-abc" :report_id "rep1" :date_from from :date_to to
               :event_date "2026-04-12" :event_date_source "api"
               :article "ABC-123" :nm_id "12345678"
               :operation "sale" :operation_subtype "realization"
               :retail_amount 20000.0 :for_pay 15000.0 :quantity 10
               :ad_cost 0.0 :marketplace "ozon" :synced_at synced}
              {:rrd_id "r-a" :report_id "rep1" :date_from from :date_to to
               :event_date "2026-04-11" :event_date_source "api"
               :article "ART-A" :nm_id "22222222"
               :operation "sale" :operation_subtype "realization"
               :retail_amount 6000.0 :for_pay 4500.0 :quantity 4
               :ad_cost 0.0 :marketplace "ozon" :synced_at synced}
              {:rrd_id "r-b" :report_id "rep1" :date_from from :date_to to
               :event_date "2026-04-13" :event_date_source "api"
               :article "ART-B" :nm_id "33333333"
               :operation "sale" :operation_subtype "realization"
               :retail_amount 4000.0 :for_pay 3000.0 :quantity 3
               :ad_cost 0.0 :marketplace "ozon" :synced_at synced}]]
    (db/insert-batch! :finance finance-cols
                      (mapv (fn [r] (mapv r finance-cols)) rows))))

(defn- seed-sku-map! []
  (db/save-ozon-sku-map! [["12345678" "ABC-123"]]))

;; Non-ad finance columns: everything the ad-cost path must NEVER touch.
;; (event_date_source IS legitimately updated on ad-attributed rows, so it is
;; excluded from the "unchanged" comparison; the isolation test never attributes
;; anything so we still assert it below where relevant.)
(defn- finance-snapshot
  "Snapshot of every finance row minus the ad_cost column, ordered stably —
   the invariant the release gate protects (nothing but ad_cost may change)."
  []
  (->> (db/query ["SELECT rrd_id, report_id, date_from, date_to, event_date,
                          event_date_source, article, nm_id, operation,
                          operation_subtype, retail_amount, for_pay, quantity,
                          marketplace
                     FROM finance ORDER BY rrd_id"])
       (mapv identity)))

(defn- sum-ad-cost []
  (-> (db/query ["SELECT COALESCE(SUM(ad_cost),0) AS s FROM finance WHERE marketplace='ozon'"])
      first :s double))

(defn- kopecks [x] (Math/round (* (double x) 100.0)))

(def ^:private control-total 1644.56) ;; Σ moneySpent of the full fixture

;; Full fixture (SKU camp 610.00 + banner 1034.56 = 1644.56).
(defn- full-stub []
  (pc/stub-client
    {:token      {:access_token "t" :expires_in 1800}
     :campaigns  [{:id "78901" :advObjectType "SKU"    :title "SKU camp"}
                  {:id "78902" :advObjectType "BANNER" :title "Banner camp"}]
     :daily-rows [{:date "2026-04-10" :id "78901" :sku "12345678" :moneySpent 410.00 :bonusSpent 0.0}
                  {:date "2026-04-15" :id "78901" :sku "12345678" :moneySpent 200.00 :bonusSpent 0.0}
                  {:date "2026-04-10" :id "78902" :moneySpent 500.00 :bonusSpent 60.00}
                  {:date "2026-04-20" :id "78902" :moneySpent 534.56 :bonusSpent 40.00}]}))

;; A client whose daily-stats always throws (persistent 503/401) — even after a
;; token refresh — so the ingest wrapper's failure-isolation must fire.
(defrecord ThrowingDailyClient [ex]
  pc/PerformanceApi
  (-token [_] "stub-token")
  (-list-campaigns [_] [{:id "78901" :advObjectType "SKU" :title "SKU camp"}])
  (-daily-stats [_ _ _ _] (throw ex)))

;; ---------------------------------------------------------------------------
;; T024 — optionality (FR-005 / SC-003)
;; ---------------------------------------------------------------------------

(deftest not-configured-is-baseline
  (testing "no Performance creds → {:status :not-configured}, no ad_spend, ad_cost=0, no throw"
    (seed-finance!)
    (seed-sku-map!)
    (let [before (finance-snapshot)]
      (with-redefs [config/ozon-performance-config (constantly nil)]
        (let [res (ingest/ingest-ozon-ads! from to)]   ;; no explicit client → resolves via config
          (is (= :not-configured (:status res))
              "config nil short-circuits to :not-configured (no exception)")))
      ;; No raw ad_performance was written → materialize is a strict no-op.
      (mat/materialize-ozon-ad-cost! from to)
      (is (empty? (db/get-ad-spend :ozon from to)) "zero ad_spend rows")
      (is (= 0 (kopecks (sum-ad-cost))) "finance.ad_cost(ozon) stays 0")
      (is (= before (finance-snapshot)) "all non-ad finance columns identical to baseline"))))

;; ---------------------------------------------------------------------------
;; T025 — empty campaigns (Acceptance 2)
;; ---------------------------------------------------------------------------

(deftest empty-campaigns-is-baseline
  (testing "creds present but list-campaigns=[] → same as no-creds (ad_cost=0, no errors)"
    (seed-finance!)
    (seed-sku-map!)
    (let [before (finance-snapshot)
          empty-stub (pc/stub-client {:token {:access_token "t"} :campaigns [] :daily-rows []})
          res (ingest/ingest-ozon-ads! empty-stub from to)]
      (is (= :ok (:status res)) "ingest completes cleanly")
      (is (= 0 (:campaigns res)))
      (is (= 0 (:daily-rows res)))
      (mat/materialize-ozon-ad-cost! from to)
      (is (empty? (db/get-ad-spend :ozon from to)) "no ad_spend rows")
      (is (= 0 (kopecks (sum-ad-cost))) "ad_cost stays 0")
      (is (= before (finance-snapshot)) "non-ad finance columns unchanged"))))

;; ---------------------------------------------------------------------------
;; T026 — failure-isolation (FR-006 / SC-005)
;; ---------------------------------------------------------------------------

(deftest failure-isolation
  (testing "daily-stats 503 → {:status :ad-unavailable}; non-ad finance rows identical"
    (seed-finance!)
    (seed-sku-map!)
    (let [before (finance-snapshot)
          before-ad-cost (sum-ad-cost)
          boom (ex-info "Service Unavailable" {:type :server-error :status 503})
          res  (ingest/ingest-ozon-ads! (->ThrowingDailyClient boom) from to)]
      (is (= :ad-unavailable (:status res)) "failure isolated to advertising")
      (is (string? (:error res)) "error message surfaced, not thrown")
      ;; No raw ad_performance was persisted → materialize is a no-op.
      (mat/materialize-ozon-ad-cost! from to)
      (is (= (kopecks before-ad-cost) (kopecks (sum-ad-cost)))
          "finance.ad_cost untouched by the outage")
      (is (= before (finance-snapshot))
          "EVERY non-ad finance column is byte-for-byte identical before/after")))

  (testing "401 → client refreshes token once then re-throws → isolated, not fatal"
    (seed-finance!)
    (seed-sku-map!)
    (let [before (finance-snapshot)
          unauth (ex-info "Unauthorized" {:type :unauthorized :status 401})
          res    (ingest/ingest-ozon-ads! (->ThrowingDailyClient unauth) from to)]
      (is (= :ad-unavailable (:status res)) "persistent 401 isolated after refresh attempt")
      (is (= before (finance-snapshot)) "non-ad finance rows unchanged"))))

;; ---------------------------------------------------------------------------
;; T027 — idempotency (FR-007 / P5)
;; ---------------------------------------------------------------------------

(deftest idempotent-reingest
  (testing "2× (ingest + materialize) → identical ad_spend, finance.ad_cost (spend not doubled)"
    (seed-finance!)
    (seed-sku-map!)
    ;; First pass.
    (ingest/ingest-ozon-ads! (full-stub) from to)
    (mat/materialize-ozon-ad-cost! from to)
    (let [ad-spend-1 (db/get-ad-spend :ozon from to)
          ad-cost-1  (sum-ad-cost)]
      (is (= (kopecks control-total) (kopecks ad-cost-1))
          "first pass reconciles to control total")
      ;; Second pass — re-ingest + re-materialize on the same fixture.
      (ingest/ingest-ozon-ads! (full-stub) from to)
      (mat/materialize-ozon-ad-cost! from to)
      (let [ad-spend-2 (db/get-ad-spend :ozon from to)
            ad-cost-2  (sum-ad-cost)]
        (is (= (count ad-spend-1) (count ad-spend-2))
            "ad_spend row count unchanged (DELETE+INSERT, no accumulation)")
        (is (= (mapv #(dissoc % :synced-at :id) ad-spend-1)
               (mapv #(dissoc % :synced-at :id) ad-spend-2))
            "ad_spend content identical across re-runs")
        (is (= (kopecks ad-cost-1) (kopecks ad-cost-2))
            "finance.ad_cost NOT doubled — SET-0-then-SET is idempotent")
        (is (= (kopecks control-total) (kopecks ad-cost-2))
            "still reconciles exactly to control total after re-run")))))
