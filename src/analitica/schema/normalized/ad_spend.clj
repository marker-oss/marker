(ns analitica.schema.normalized.ad-spend
  "Canonical advertising-spend schema — the SHARED §3.B ad-canon.

   This is the single source of truth for the `ad_spend` record shape,
   used by BOTH spec 011-ozon-performance-ads (FIRST PRODUCER — Ozon
   Performance) and spec 018-platform-seams (OWNER of the definition —
   consumer-query + no-silo invariant). Adding WB/YM later means a new
   producer emitting the SAME shape with a different `:marketplace` tag
   and ZERO new consumer branches (FR-016(011) / FR-004+SC-002(018)).

   `AdSpend` MUST match `contracts/ad-canon.edn` :ad-spend-malli byte for
   byte — do NOT diverge without updating both. It is a sibling to
   item_events (ad spend = cost over a window), NOT a new event_type.

   Money fields are `double` + round2 (util/math), like the rest of the
   analytics — NOT the decimal ledger (§3.D, that is spec 019).

   See:
   - specs/011-ozon-performance-ads/data-model.md §3.2/§3.3
   - specs/011-ozon-performance-ads/contracts/ad-canon.edn :ad-spend-malli
   - docs/data-dictionary.md (ad_spend / ad_campaign_stats)"
  (:require [malli.core :as m]
            [malli.error :as me]
            [analitica.schema.util :as schema-util]))

;; ---------------------------------------------------------------------------
;; §3.B canon — ad_spend record (SHARED with 018; byte-for-byte with
;; contracts/ad-canon.edn :ad-spend-malli). Attribution source is the only
;; enum: :api = per-SKU straight from Performance; :spread = revenue-weighted.
;; ---------------------------------------------------------------------------

(def AttributionSource
  "Provenance of the attribution: :api = per-SKU direct from source;
   :spread = revenue-weighted synthesis across a campaign's articles."
  [:enum :api :spread])

(def AdSpend
  "SHARED canon (011 producer + 018 owner). Must byte-for-byte match
   contracts/ad-canon.edn :ad-spend-malli.

   Invariants every producer MUST uphold (contracts/ad-canon.edn):
   - Σ spend per campaign per period == campaign-level Performance total
     EXACTLY (P7); rounding residue → largest-weight article.
   - :attribution-source :api    ⇒ article + event-date from source
                                    (event_date_source='api').
   - :attribution-source :spread ⇒ article + event-date synthesised from
                                    revenue weights (event_date_source='spread').
   - :bonus-spend stored separately; only :spend flows into finance.ad_cost
     so totals are not double-counted."
  [:map
   [:marketplace        [:enum :ozon :wb :ym]]
   [:event-date         :string]
   [:campaign-id        {:optional true} [:maybe :string]]
   [:campaign-type      {:optional true} [:maybe :string]]
   [:article            {:optional true} [:maybe :string]]
   [:sku                {:optional true} [:maybe :string]]
   [:spend              [:double {:min 0.0}]]
   [:bonus-spend        [:double {:min 0.0}]]
   [:attribution-source [:enum :api :spread]]
   [:basis              {:optional true} [:maybe :string]]])

;; ---------------------------------------------------------------------------
;; §3.3 — per-campaign/per-day stat row (P1 efficiency). Feeds
;; ad_campaign_stats; source of CTR/CPC/CPM/CPO/CPS/CR/ДРРз/ROAS.
;; ---------------------------------------------------------------------------

(def AdCampaignStat
  "Per-campaign/per-day advertising statistics (P1 efficiency report source)."
  [:map
   [:marketplace   [:enum :ozon :wb :ym]]
   [:campaign-id   :string]
   [:campaign-type {:optional true} [:maybe :string]]
   [:campaign-name {:optional true} [:maybe :string]]
   [:stat-date     :string]
   [:views          :int]
   [:clicks         :int]
   [:add-to-cart    :int]
   [:orders         :int]
   [:orders-revenue :double]
   [:spend          :double]
   [:bonus-spend    :double]])

(def ^:private ad-spend-validator          (m/validator AdSpend))
(def ^:private ad-spend-explainer          (m/explainer AdSpend))
(def ^:private ad-campaign-stat-validator  (m/validator AdCampaignStat))
(def ^:private ad-campaign-stat-explainer  (m/explainer AdCampaignStat))

(defn valid-ad-spend? [row] (ad-spend-validator row))
(defn explain-ad-spend [row] (some-> (ad-spend-explainer row) me/humanize))
(defn validate-ad-spend-rows [rows]
  (schema-util/validate-rows ad-spend-validator explain-ad-spend rows))

(defn valid-ad-campaign-stat? [row] (ad-campaign-stat-validator row))
(defn explain-ad-campaign-stat [row] (some-> (ad-campaign-stat-explainer row) me/humanize))
(defn validate-ad-campaign-stat-rows [rows]
  (schema-util/validate-rows ad-campaign-stat-validator explain-ad-campaign-stat rows))
