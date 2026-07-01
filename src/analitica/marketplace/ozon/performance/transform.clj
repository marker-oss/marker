(ns analitica.marketplace.ozon.performance.transform
  "Ozon Performance (advertising) API — contract Malli schemas + registration.

   Spec 011-ozon-performance-ads (T009). Host api-performance.ozon.ru is
   DISTINCT from the Seller api-seller.ozon.ru; auth is OAuth
   client_credentials → Bearer. READ-ONLY: only GET/stat + POST /token +
   POST /statistics (create-report) are used — no campaign-management/write
   calls (FR-010, P2).

   The upstream field names — `moneySpent` / `bonusSpent` / `ordersMoney`,
   `advObjectType`, `access_token`, etc. — are PINNED HERE and nowhere else.
   The contract schemas below are the single point where they are declared,
   so a rename in the Performance API changes exactly one file. Unknown /
   changed fields are logged by `with-validation` (as with realization),
   never fatal (FR-011).

   The four contracts are registered into `analitica.schema.registry` at
   namespace load, keyed like `:ozon/finance-realization`, so
   `schema-validator/with-validation :ozon/performance-daily …` works
   identically. See:
   - specs/011-ozon-performance-ads/data-model.md §3.1
   - specs/011-ozon-performance-ads/contracts/performance-api.edn"
  (:require [analitica.schema.registry :as reg]
            [analitica.util.math :as math]))

;; ---------------------------------------------------------------------------
;; Contract schemas — response shapes (data-model.md §3.1). Money as :double,
;; counts as :int; all non-key fields optional so a sparse row (e.g. a day
;; with clicks but no orders) validates. Unknown fields → warning, not fatal.
;; ---------------------------------------------------------------------------

(def TokenResponse
  "POST /api/client/token — OAuth client_credentials → Bearer."
  [:map
   [:access_token :string]
   [:expires_in {:optional true} :int]
   [:token_type {:optional true} :string]])

(def Campaign
  "One campaign entry in GET /api/client/campaign :list. `:id` is the
   campaignId (STRING at Ozon) — the join key to daily/stat rows."
  [:map
   [:id :string]
   [:title {:optional true} :string]
   [:state {:optional true} :string]          ;; CAMPAIGN_STATE_RUNNING / ...
   [:advObjectType {:optional true} :string]  ;; SKU | BANNER | BRAND_SHELF | ...
   [:paymentType {:optional true} :string]])  ;; CPC | CPO

(def CampaignListResponse
  "GET /api/client/campaign → {:list [Campaign …]}."
  [:map
   [:list [:sequential Campaign]]])

(def DailyStatRow
  "One per-campaign per-day row in GET /api/client/statistics/daily.

   `:id` (campaignId) is REQUIRED: it is the join key to Campaign.:id. A
   stat row without it cannot be attributed → would silently lose spend. If
   the source ever omits it, `with-validation` logs+drops the row rather
   than materialising unattributed spend.

   `moneySpent` = CASH spend (→ :spend); `bonusSpent` = bonus-funded spend
   (→ :bonus-spend, FR-009); `ordersMoney` = orders revenue (ДРРз/ROAS denom).

   `:sku` is OPTIONAL: the daily endpoint is per-campaign, so most rows carry
   no SKU (→ campaign-level `:spread`). Per-product campaign types (search
   promotion / трафареты) DO report the SKU on the daily row — when present it
   routes the row to per-SKU `:api` attribution (transform/daily-rows->ad-spend)."
  [:map
   [:date :string]
   [:id :string]
   [:sku {:optional true} :string]
   [:views {:optional true} :int]
   [:clicks {:optional true} :int]
   [:moneySpent {:optional true} :double]
   [:bonusSpent {:optional true} :double]
   [:orders {:optional true} :int]
   [:ordersMoney {:optional true} :double]])

(def DailyStatsResponse
  "GET /api/client/statistics/daily → {:rows [DailyStatRow …]}."
  [:map
   [:rows [:sequential DailyStatRow]]])

(def StatisticsReportRow
  "One per-SKU row in the async statistics report download (P1). Per-product
   rows (`:sku` present) resolve to :api attribution; else campaign-level
   spend falls back to :spread."
  [:map
   [:date :string]
   [:sku {:optional true} :string]
   [:views {:optional true} :int]
   [:clicks {:optional true} :int]
   [:moneySpent {:optional true} :double]
   [:bonusSpent {:optional true} :double]
   [:orders {:optional true} :int]
   [:ordersMoney {:optional true} :double]])

(def StatisticsReportResponse
  "Async statistics report download → {:rows [StatisticsReportRow …]}."
  [:map
   [:rows [:sequential StatisticsReportRow]]])

;; ---------------------------------------------------------------------------
;; Registry — register the contracts under :ozon/performance-* keys, exactly
;; like :ozon/finance-realization, so `with-validation` resolves them via
;; analitica.schema.registry. `reg/register!` is idempotent (swap! assoc),
;; so re-requiring this namespace is safe.
;; ---------------------------------------------------------------------------

(def contracts
  "The four Performance API contracts as registry-shaped maps
   (:endpoint/id + :contract/response-schema, like the EDN contracts under
   resources/schemas/)."
  [{:endpoint/id          :ozon/performance-token
    :endpoint/marketplace :ozon
    :endpoint/api-path    "/api/client/token"
    :endpoint/method      :post
    :endpoint/description "Ozon Performance OAuth client_credentials → Bearer token."
    :contract/response-schema TokenResponse
    :contract/version     1}
   {:endpoint/id          :ozon/performance-campaign
    :endpoint/marketplace :ozon
    :endpoint/api-path    "/api/client/campaign"
    :endpoint/method      :get
    :endpoint/description "Ozon Performance campaign list."
    :contract/response-schema CampaignListResponse
    :contract/version     1}
   {:endpoint/id          :ozon/performance-daily
    :endpoint/marketplace :ozon
    :endpoint/api-path    "/api/client/statistics/daily"
    :endpoint/method      :get
    :endpoint/description "Ozon Performance per-campaign per-day statistics (P0 fast path)."
    :contract/response-schema DailyStatsResponse
    :contract/version     1}
   {:endpoint/id          :ozon/performance-statistics-report
    :endpoint/marketplace :ozon
    :endpoint/api-path    "/api/client/statistics/report"
    :endpoint/method      :get
    :endpoint/description "Ozon Performance async statistics report (per-SKU, P1)."
    :contract/response-schema StatisticsReportResponse
    :contract/version     1}])

(defn register-contracts!
  "Register all four Performance API contracts into the schema registry.
   Idempotent. Returns the vector of registered :endpoint/id keys."
  []
  (mapv reg/register! contracts))

;; Register at namespace load, so requiring this ns wires the contracts for
;; `with-validation` the same way load-all! wires the EDN contracts.
(register-contracts!)

;; ===========================================================================
;; T019 — attribution transform: Performance daily rows → §3.B ad_spend canon.
;;
;; Two attribution paths, both emit the SAME ad_spend shape:
;;   :api    — a daily row carries a :sku (per-product campaign) → mapped to an
;;             article via ozon_sku_map, day-grain event-date straight from the
;;             source. One ad_spend row per (campaign, day, article).
;;   :spread — a campaign reports only campaign-level spend (banners, brand
;;             shelf; no :sku) → distributed across the campaign's articles
;;             proportional to their period revenue. Σ per-article == campaign
;;             total EXACTLY (P7): the rounding residue is routed to the
;;             largest-weight article/day. Zero-revenue period → flat fallback
;;             (account-level rows, article nil, one per day) so the total is
;;             never lost.
;;
;; Money base (P6, :basis): Ozon Performance daily `moneySpent` (CASH). Only
;; :spend flows to finance.ad_cost; :bonus-spend is stored separately so the
;; profit total is not double-counted (FR-009).
;; ===========================================================================

(def spend-basis
  "Canonical :basis doc-string stamped on every ad_spend row (P6 honesty)."
  "Ozon Performance daily moneySpent (cash); per-SKU where reported, else revenue-weighted spread")

(defn ad-cost-contribution
  "The finance.ad_cost contribution of an ad_spend row: CASH `:spend` ONLY.
   `:bonus-spend` is deliberately excluded so bonus-funded advertising does not
   inflate costs / double-count (FR-009)."
  [ad-spend-row]
  (double (or (:spend ad-spend-row) 0.0)))

(defn- money [x] (double (or x 0.0)))

(defn attribute-daily-rows
  "Split Performance daily rows into per-SKU `:api` ad_spend rows and the
   campaign-level remainder to be spread.

   `sku->article` resolves a SKU to its article (ozon_sku_map). `opts`:
     :synced-at       — ISO timestamp stamped on each row.
     :campaign-types  — {campaign-id advObjectType} (optional, for :campaign-type).

   Returns {:api-rows       [<ad_spend :api rows>]
            :campaign-rows  {campaign-id [<daily rows with no resolvable sku>]}}.
   A row is :api iff it has a :sku that resolves to an article; every other row
   (no :sku, or an unmapped :sku) falls to the campaign bucket for spreading."
  [rows sku->article {:keys [synced-at campaign-types]}]
  (let [ctype (fn [cid] (get campaign-types cid))
        ;; ozon_sku_map.sku is an INTEGER column → jdbc returns Long keys, while
        ;; the Performance daily :sku arrives as a string. Normalise both sides
        ;; to string so the join resolves regardless of stored type.
        sku-idx (into {} (map (fn [[k v]] [(str k) v]) sku->article))]
    (reduce
      (fn [acc {:keys [date id sku moneySpent bonusSpent] :as _row}]
        (let [article (when sku (get sku-idx (str sku)))]
          (if article
            (update acc :api-rows conj
                    {:marketplace        :ozon
                     :event-date         date
                     :campaign-id        id
                     :campaign-type      (ctype id)
                     :article            article
                     :sku                sku
                     :spend              (math/round2 (money moneySpent))
                     :bonus-spend        (math/round2 (money bonusSpent))
                     :attribution-source :api
                     :basis              spend-basis})
            (update-in acc [:campaign-rows id] (fnil conj []) _row))))
      {:api-rows [] :campaign-rows {}}
      rows)))

(defn- allocate-exactly
  "Split `total` (kopecks-safe) across `weights` ({key → weight}) proportional
   to weight, returning {key → round2 amount} whose Σ == total EXACTLY. The
   rounding residue lands on the largest-weight key. Empty/zero-weight → {}."
  [total weights]
  (let [total-w (reduce + 0.0 (vals weights))]
    (if (or (empty? weights) (not (pos? total-w)) (zero? (double total)))
      {}
      (let [ks       (vec (keys weights))
            raw      (into {} (map (fn [k] [k (math/round2 (* (double total)
                                                              (/ (double (get weights k)) total-w)))])
                                   ks))
            assigned (reduce + 0.0 (vals raw))
            residue  (math/round2 (- (double total) assigned))
            ;; largest-weight key absorbs the residue (deterministic tie-break
            ;; on key string so re-runs are identical).
            biggest  (first (sort-by (fn [k] [(- (double (get weights k))) (str k)]) ks))]
        (update raw biggest (fnil + 0.0) residue)))))

(defn spread-campaign-spend
  "Revenue-weighted `:spread` attribution for one campaign's campaign-level
   daily rows.

   `daily-rows`       — the campaign's daily rows (no per-SKU breakdown).
   `article->revenue` — {article → period revenue} for the campaign's articles.
   `opts` :synced-at.

   Algorithm (Σ per-article == campaign total EXACTLY, P7):
     1. campaign total spend = Σ moneySpent; bonus total = Σ bonusSpent.
     2. If Σ revenue > 0: allocate the campaign total across articles by
        revenue weight (residue → largest-weight article). Then split each
        article's amount across the campaign's active days by that day's
        moneySpent share (residue → the day with the largest spend). One
        ad_spend row per (article, day).
     3. Else (zero revenue): flat fallback — one account-level row per day
        (article nil) carrying that day's exact moneySpent, so the total is
        preserved with day grain.
   Bonus is spread by the same day weights (revenue path) or per-day (flat)."
  [campaign-id campaign-type daily-rows article->revenue {:keys [synced-at]}]
  (let [days           (mapv (fn [r] {:date (:date r)
                                      :spend (money (:moneySpent r))
                                      :bonus (money (:bonusSpent r))})
                             daily-rows)
        total-spend    (reduce + 0.0 (map :spend days))
        total-bonus    (reduce + 0.0 (map :bonus days))
        day-weights    (into {} (map (fn [{:keys [date spend]}] [date spend]) days))
        mk-row (fn [article event-date spend bonus]
                 {:marketplace        :ozon
                  :event-date         event-date
                  :campaign-id        campaign-id
                  :campaign-type      campaign-type
                  :article            article
                  :sku                nil
                  :spend              (math/round2 spend)
                  :bonus-spend        (math/round2 bonus)
                  :attribution-source :spread
                  :basis              spend-basis})]
    (if (pos? (reduce + 0.0 (vals article->revenue)))
      ;; Revenue-weighted spread: article split, then per-article day split.
      (let [per-article (allocate-exactly total-spend article->revenue)
            bonus-per-article (allocate-exactly total-bonus article->revenue)]
        (vec
          (mapcat
            (fn [[article art-spend]]
              (let [art-bonus (get bonus-per-article article 0.0)
                    ;; split this article's spend across days by day-spend weight
                    spend-by-day (allocate-exactly art-spend day-weights)
                    bonus-by-day (allocate-exactly art-bonus day-weights)]
                (mapv (fn [{:keys [date]}]
                        (mk-row article date
                                (get spend-by-day date 0.0)
                                (get bonus-by-day date 0.0)))
                      days)))
            per-article)))
      ;; Flat fallback: one account-level row per day, exact per-day amounts.
      (mapv (fn [{:keys [date spend bonus]}]
              (mk-row nil date spend bonus))
            days))))
