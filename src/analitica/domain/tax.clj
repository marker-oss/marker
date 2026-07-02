(ns analitica.domain.tax
  "Management-basis tax layer — УСН (income / income-expense) and НДС (output VAT).

   This namespace owns the tax_config table (spec 015, data-model.md §1.1) and the
   pure computation function tax/compute-period.

   ## Contracts (LOCKED spec 015 §0 / contracts/tax-opex-api.md)

   tax-base (usn-income):          for_pay
   tax-base (usn-income-expense):  for_pay − (if official-cost-price (+ cogs opex) opex)
   tax:  round2(max(0, tax-base) × usn_rate)     — always ≥ 0, no 1%-floor in v1
   vat:  round2(revenue × vat_rate)              — output VAT on gross, independent of УСН
   none/osno/patent → tax=0, vat=0  (forward-compatible enum, unsupported regime)

   ## Invariants
   INV-3: tax ≥ 0 (убыток Д−Р ⇒ max(0, base) ⇒ tax 0)
   INV-8: :management-configured? false when no config and no OPEX

   ## Public API (US2)
   compute-period   — pure {:tax-base :tax :vat}
   fetch-config     — year's rows (0..12)
   config-for-month — (year,month) row | nil (nil ⇒ tax 0, FR-004)
   save-config!     — UPSERT on (year,month); normalizes UI percents → fractions"
  (:require [analitica.db :as db]
            [analitica.util.math :as math]
            [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Malli schemas (data-model.md §4)
;; ---------------------------------------------------------------------------

(def TaxationType
  [:enum :none :usn-income :usn-income-expense :osno :patent])

(def TaxConfigRow
  [:map
   [:year int?]
   [:month [:int {:min 1 :max 12}]]
   [:taxation-type TaxationType]
   [:usn-rate [:double {:min 0.0 :max 1.0}]]
   [:vat-rate [:double {:min 0.0 :max 1.0}]]
   [:official-cost-price boolean?]])

(def TaxComputeInput
  [:map
   [:taxation-type TaxationType]
   [:usn-rate :double]
   [:vat-rate :double]
   [:official-cost-price boolean?]
   [:for-pay :double]
   [:revenue :double]
   [:cogs :double]
   [:opex :double]])

(def TaxComputeResult
  [:map
   [:tax-base :double]
   [:tax [:double {:min 0.0}]]
   [:vat [:double {:min 0.0}]]])

;; ---------------------------------------------------------------------------
;; Pure computation (data-model.md §3, LOCKED money model)
;; ---------------------------------------------------------------------------

(defn compute-period
  "Pure function: ставки + агрегаты периода → {:tax-base :tax :vat}.

   Money model (LOCKED, guardrails / data-model §3):
     usn-income          tax-base = for_pay
     usn-income-expense  tax-base = for_pay − (if official-cost-price (+ cogs opex) opex)
     tax                 = round2(max(0, tax-base) × usn_rate)   ; ≥ 0, no 1%-floor
     vat                 = round2(revenue × vat_rate)            ; output VAT on gross
     none/osno/patent    ⇒ tax 0, vat 0   (forward-compatible enum)

   tax and vat are ALWAYS ≥ 0. All money via math/round2."
  [{:keys [taxation-type usn-rate vat-rate official-cost-price
           for-pay revenue cogs opex]}]
  (let [for-pay  (double (or for-pay 0.0))
        revenue  (double (or revenue 0.0))
        cogs     (double (or cogs 0.0))
        opex     (double (or opex 0.0))
        usn-rate (double (or usn-rate 0.0))
        vat-rate (double (or vat-rate 0.0))
        tax-base (case taxation-type
                   :usn-income         for-pay
                   :usn-income-expense (- for-pay
                                          (if official-cost-price
                                            (+ cogs opex)
                                            opex))
                   ;; none / osno / patent — no УСН base
                   0.0)
        usn?     (contains? #{:usn-income :usn-income-expense} taxation-type)
        tax      (if usn?
                   (math/round2 (* (max 0.0 tax-base) usn-rate))
                   0.0)
        vat      (if (contains? #{:none :osno :patent} taxation-type)
                   0.0
                   (math/round2 (* revenue vat-rate)))]
    {:tax-base (math/round2 tax-base)
     :tax      tax
     :vat      vat}))

;; ---------------------------------------------------------------------------
;; Store (SQLite via analitica.db) — pattern of plan.clj save-plan!
;; ---------------------------------------------------------------------------

(defn- row->config
  "Coerce a raw kebab-cased DB row into a TaxConfigRow map:
   taxation_type TEXT → keyword; official_cost_price INTEGER → boolean;
   rates as doubles."
  [{:keys [year month taxation-type usn-rate vat-rate official-cost-price
           updated-at]}]
  {:year                (long year)
   :month               (long month)
   :taxation-type       (keyword taxation-type)
   :usn-rate            (double (or usn-rate 0.0))
   :vat-rate            (double (or vat-rate 0.0))
   :official-cost-price (not (zero? (long (or official-cost-price 0))))
   :updated-at          updated-at})

(defn- normalize-rate
  "Resolve a rate from either a fraction key (`:usn-rate`) or a UI-percent key
   (`:usn-rate-pct`, e.g. 6 → 0.06). Fraction wins when both present."
  [row frac-key pct-key]
  (cond
    (contains? row frac-key) (double (or (get row frac-key) 0.0))
    (contains? row pct-key)  (/ (double (or (get row pct-key) 0.0)) 100.0)
    :else                    0.0))

(defn fetch-config
  "Return the seq of TaxConfigRow maps for `year` (0..12 rows), month-ordered."
  [year]
  (->> (db/query
         ["SELECT year, month, taxation_type, usn_rate, vat_rate,
                  official_cost_price, updated_at
             FROM tax_config WHERE year = ? ORDER BY month" year])
       (map row->config)))

(defn config-for-month
  "Return the TaxConfigRow for (year, month), or nil if absent.
   nil ⇒ tax=0, not an error (FR-004)."
  [year month]
  (some-> (db/query
            ["SELECT year, month, taxation_type, usn_rate, vat_rate,
                     official_cost_price, updated_at
                FROM tax_config WHERE year = ? AND month = ?" year month])
          first
          row->config))

(defn save-config!
  "Upsert a seq of TaxConfigRow maps into tax_config (ON CONFLICT (year,month)
   DO UPDATE). UI enters percents; store normalizes to fractions
   (`:usn-rate-pct 6` ⇒ 0.06). Returns the count of rows written."
  [rows]
  (doseq [{:keys [year month taxation-type official-cost-price] :as row} rows]
    (when-not (and (int? month) (<= 1 month 12))
      (throw (ex-info "month must be 1..12" {:row row})))
    (db/execute!
      ["INSERT INTO tax_config
          (year, month, taxation_type, usn_rate, vat_rate,
           official_cost_price, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, datetime('now'))
        ON CONFLICT (year, month)
        DO UPDATE SET taxation_type       = excluded.taxation_type,
                      usn_rate            = excluded.usn_rate,
                      vat_rate            = excluded.vat_rate,
                      official_cost_price = excluded.official_cost_price,
                      updated_at          = excluded.updated_at"
       (long year) (long month) (name (or taxation-type :none))
       (normalize-rate row :usn-rate :usn-rate-pct)
       (normalize-rate row :vat-rate :vat-rate-pct)
       (if official-cost-price 1 0)]))
  {:saved (count rows)})
