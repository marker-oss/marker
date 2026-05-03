# Marker SPA Backend API

## Overview

The Marker SPA is a ClojureScript single-page application (built with UIx + re-frame,
served at `/app`) that provides a real-time analytics dashboard for marketplace sellers.

This document describes the **Transit-JSON API** under `/api/v1/marker/*` added in
Phase 7. These endpoints expose real data from the Analitica backend to the SPA in a
CLJS-friendly format.

Phase 8 wires these endpoints from the frontend (replacing `marker.mock` mock data).

---

## Transit Format

All `/api/v1/marker/*` endpoints return [Transit-JSON](https://github.com/cognitect/transit-format)
when the client sends:

```
Accept: application/transit+json
```

Without this header the existing `wrap-json-response` middleware encodes the response
as plain JSON (still useful for curl debugging — just omit the Accept header).

**Content negotiation summary:**
- `Accept: application/transit+json` → `Content-Type: application/transit+json; charset=utf-8`
- Any other `Accept` → `Content-Type: application/json; charset=utf-8`

The CLJS frontend uses `day8.re-frame/http-fx` with transit decoding configured via
`cljs-transit`.

POST bodies should also be sent as Transit-JSON with:
```
Content-Type: application/transit+json
```

---

## Common Query Parameters

| Param     | Type                  | Default       | Description                        |
|-----------|-----------------------|---------------|------------------------------------|
| `from`    | `YYYY-MM-DD`          | today - 29    | Period start (inclusive)           |
| `to`      | `YYYY-MM-DD`          | today         | Period end (inclusive)             |
| `mp`      | `wb,ozon,ym` (CSV)    | all           | Marketplace filter                 |
| `compare` | `true` / `false`      | `false`       | Include previous-period comparison |

If `from`/`to` are omitted, defaults to the last 30 days.

---

## Endpoints

### B1. GET /api/v1/marker/pulse-summary

Dashboard summary: alerts, KPI tiles, top-movers, critical stocks, charts.

**Backed by:** `analitica.alerts/detect-alerts`, `analitica.domain.pnl/calculate`,
`analitica.domain.sales/by-article`, `analitica.domain.stock/with-turnover`

**Response shape:**
```clojure
{:alerts          [{:kind "danger"|"warning"|"info"
                    :title "..."
                    :body  "..."
                    :cta   "..."}]
 :kpis            {:revenue   {:value <num> :delta-pct <num> :spark [<num>×N]}
                   :profit    {:value <num> :delta-pct <num> :spark []}
                   :orders    {:value <int> :delta-pct <num> :spark [<int>×N]}
                   :margin    {:value <pct> :delta-pct <num> :spark []}
                   :avg-check {:value <num> :delta-pct <num> :spark []}
                   :buyout    {:value <pct> :delta-pct <num> :spark []}
                   :roas      {:value <num>|nil :delta-pct nil :spark []}
                   :drr       {:value <pct>|nil :delta-pct nil :spark []}}
 :forecast        {:month-plan nil      ; TODO: wire once monthly plans exist
                   :month-fact <num>
                   :projection <num>}
 :charts          {:revenue-30d      [<num>×N]
                   :orders-by-mp     {:wb [<int>×N] :ozon [...] :ym [...]}
                   :mp-share         {:wb <pct> :ozon <pct> :ym <pct>}
                   :revenue-prev-30d [<num>×N]}  ; only when ?compare=true
 :top-movers      [{:id <str> :name <str> :mp [...kw] :revenue <num>
                    :delta-pct <num> :spark [...]}  ; ×5
                  ]
 :top-fallers     [...]  ; ×5
 :critical-stocks [{:id <str> :name <str> :mp [...kw]
                    :stock <int> :speed <int> :days <int>
                    :status "danger"|"warning"|"success"}  ; ≤7
                  ]
 :data-fresh      {:wb <iso-ts>|nil :ozon <iso-ts>|nil :ym <iso-ts>|nil}}
```

**Example curl:**
```bash
curl -s -H "Accept: application/transit+json" \
  "http://localhost:3001/api/v1/marker/pulse-summary?from=2026-04-01&to=2026-04-30"
```

**Stubbed fields:**
- `:forecast :month-plan` — always `nil` (monthly plans table not yet wired)
- `:kpis :roas :delta-pct` — always `nil` (prev-period ROAS not computed)
- `:kpis :drr :delta-pct` — always `nil`
- `:top-movers/:top-fallers :mp` — always `[:wb]` (multi-MP derivation deferred)

---

### B2. GET /api/v1/marker/pnl

P&L summary rows and per-SKU breakdown.

**Backed by:** `analitica.domain.pnl/calculate`, `analitica.domain.finance/by-article`

**Response shape:**
```clojure
{:rows       [{:key   :revenue|:gross-profit|:net-profit|...
               :label "Выручка (розница)"
               :cur   <num>
               :prev  <num>
               :group "income"|"cost"|"subtotal"|"total"}]
 :sku-detail [{:id         <str>
               :name       <str>
               :mp         [<kw>]
               :revenue    <num>
               :cogs       <num>
               :commission <num>
               :ads        <num>    ; TODO: per-article ad spend (always 0)
               :net        <num>}]}
```

**Row keys produced:** `:revenue`, `:wb-reward`, `:for-pay`, `:cogs`, `:logistics`,
`:storage`, `:acceptance`, `:penalties`, `:deduction`, `:additional`, `:ad-spend`,
`:gross-profit`, `:net-profit`

**Example curl:**
```bash
curl -s -H "Accept: application/transit+json" \
  "http://localhost:3001/api/v1/marker/pnl?from=2026-04-01&to=2026-04-30&mp=wb"
```

**Stubbed fields:**
- `:sku-detail :ads` — always `0.0` (per-article ad cost breakdown requires ad_stats JOIN not implemented at this layer)

---

### B3. GET /api/v1/marker/sku-list

Full SKU list with key metrics. Accepts `?limit=N&offset=N` for server-side pagination. Without limit, returns all SKUs.

**Backed by:** `analitica.domain.finance/by-article`, `analitica.domain.stock/by-article`,
`analitica.domain.sales/by-article`

**Response shape:**
```clojure
{:skus [{:id        <str>
         :name      <str>
         :mp        [<kw>]
         :revenue   <num>
         :orders    <int>
         :margin    <pct>
         :buyout    <pct>
         :stock     <int>
         :delta-pct <num>
         :ads-cost  <num>    ; TODO: per-article ad cost (always 0)
         :roas      nil      ; TODO: depends on ads-cost
         :spark     []}]}    ; TODO: per-article daily spark (deferred)
```

**Example curl:**
```bash
curl -s -H "Accept: application/transit+json" \
  "http://localhost:3001/api/v1/marker/sku-list?limit=10"
```

**Stubbed fields:**
- `:ads-cost` — always `0.0`
- `:roas` — always `nil`
- `:spark` — always `[]` (per-article daily spark is expensive; deferred to Phase 8)

---

### B4. GET /api/v1/marker/sku-detail/:sku-id

Per-SKU detail with KPIs, daily spark, plan-fact, and stocks by MP.

**Backed by:** `analitica.domain.finance/by-article` (scoped to article),
`analitica.domain.stock/by-article`, sales data

**Route:** `/api/v1/marker/sku-detail/{article}` where `{article}` is the seller's
article code (URL-encoded). Example: `SKU-1200`, `Артикул%2F42`.

**Response shape:**
```clojure
{:id          <str>
 :name        <str>
 :nm-id       <int>|nil
 :subject     <str>
 :mp          [<kw>]
 :kpis        {:revenue {:value <num> :delta-pct <num>}
               :orders  {:value <int>}
               :margin  {:value <pct>}
               :ads     {:value <num>}}
 :revenue-30d [<num>×N]
 :plan-fact   {:plan       nil   ; TODO: wire to monthly_plans
               :fact       <num>
               :projection <num>}
 :stocks-by-mp [{:mp <kw> :stock <int> :days <int>|nil}]}
```

**Example curl:**
```bash
curl -s -H "Accept: application/transit+json" \
  "http://localhost:3001/api/v1/marker/sku-detail/MY-ARTICLE-CODE"
```

**Stubbed fields:**
- `:plan-fact :plan` — always `nil`

---

### B5. POST /api/v1/marker/what-if-recalc

Pure unit-econ what-if computation. Called on every slider drag — fast and side-effect-free.

**Backed by:** `analitica.web.api.marker/what-if-recalc` (pure function in this namespace)

**Request body (Transit-JSON):**
```clojure
{:price          <num>   ; retail price (₽)
 :cogs           <num>   ; cost of goods sold (₽)
 :commission-pct <num>   ; marketplace commission %
 :logistics      <num>   ; logistics cost (₽)
 :ads            <num>   ; advertising spend (₽)
 :returns-pct    <num>   ; returns rate %}
```

**Response shape:**
```clojure
{:margin-pct <num>    ; net margin as % of effective revenue
 :profit     <num>    ; absolute profit (₽)
 :roas       <num>|nil  ; revenue / ads; nil when ads=0
 :break-even <num>|nil} ; break-even price (₽)
```

**Formula:**
```
effective-rev  = price × (1 − returns-pct/100)
commission     = effective-rev × commission-pct/100
total-costs    = cogs + commission + logistics + ads
profit         = effective-rev − total-costs
margin-pct     = profit / effective-rev × 100
roas           = effective-rev / ads          (nil when ads=0)
break-even     = (cogs + logistics + ads) / (1 − commission-pct/100 − returns-pct/100)
```

**Example curl:**
```bash
curl -s -X POST \
  -H "Content-Type: application/transit+json" \
  -H "Accept: application/transit+json" \
  --data '["^ ","~:price",2500,"~:cogs",1200,"~:commission-pct",17,"~:logistics",90,"~:ads",220,"~:returns-pct",8]' \
  http://localhost:3001/api/v1/marker/what-if-recalc
```

---

## CORS

The existing `wrap-cors` middleware allows all origins for `:get :post :options`.
The new `/api/v1/marker/*` endpoints (GET + POST) are covered by this configuration.

---

## Replacing the Legacy API

The `/api/v1/marker/*` endpoints are the forward-looking API for the SPA.
Legacy `/api/*` endpoints (used by the Hiccup SSR pages) remain unchanged and will
continue to work until the Hiccup pages are retired.

Phase 8 will wire the SPA to call these endpoints instead of the `marker.mock` data.

---

## Missing / Stubbed Data

| Field | Reason |
|-------|--------|
| `pulse-summary :forecast :month-plan` | Monthly plans DB not wired yet |
| `sku-list :ads-cost`, `pnl :sku-detail :ads` | Per-article ad cost requires ad_stats JOIN not implemented at aggregation level |
| `sku-list :roas` | Depends on ads-cost |
| `sku-list :spark` | Per-article daily spark is O(skus × days); deferred |
| `top-movers :mp` | Multi-MP SKU derivation deferred |
| `sku-detail :plan-fact :plan` | Monthly plans DB not wired |

All stubbed fields are clearly marked with `; TODO:` comments in the source.
