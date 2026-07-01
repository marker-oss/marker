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
  (:require [analitica.schema.registry :as reg]))

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
   (→ :bonus-spend, FR-009); `ordersMoney` = orders revenue (ДРРз/ROAS denom)."
  [:map
   [:date :string]
   [:id :string]
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
