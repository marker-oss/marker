(ns analitica.domain.treasury.seed
  "Seed the treasury ledger from `cash_flow_periods` (spec 019 T032, FR-025/R5).

   Ozon-only weekly cash-flow buckets become operations in the UNIFIED
   registry (no per-MP silo): each money column → income/expense by sign,
   payout legs (payment, invoice_transfer) → :transfer MP-settlement→bank so
   business net stays 0 (CF-5). Amounts cross the double→decimal no-bridge
   boundary ONCE via (m/d (str x)). Buckets straddling the window are sliced
   by DEC-3 telescoping pro-rate so consecutive windows sum to the bucket
   amount to the kopeck. `cash_flow_periods` itself is NEVER mutated (P&L.6
   source, SC-008)."
  (:require [analitica.db :as db]
            [analitica.util.math :as m]
            [analitica.domain.treasury.operations :as ops])
  (:import [java.math BigDecimal RoundingMode]
           [java.time LocalDate]
           [java.time.temporal ChronoUnit]))

(defn- d-of-double
  "The ONE sanctioned double→decimal crossing (T032, no-bridge §5): REAL
   `cash_flow_periods` values enter the ledger here and nowhere else.
   BigDecimal/valueOf takes the double's canonical decimal form (locale-free);
   setScale 2 HALF_UP normalises to ledger scale."
  ^BigDecimal [x]
  (.setScale (BigDecimal/valueOf (double x)) m/decimal-scale RoundingMode/HALF_UP))

;; ---------------------------------------------------------------------------
;; Column → taxonomy mapping (§3.A slugs, cashflow/treasury-categories)
;; ---------------------------------------------------------------------------

(def flow-columns
  "Money-flow columns → category slug. Source signs are preserved in the DB
   (costs negative, db/cash-flow-adjustments): sign picks the direction,
   this map picks the category."
  {:orders_amount      "mp-payout"
   :returns_amount     "mp-payout"
   :return_amount      "mp-payout"
   :commission_amount  "services"
   :acquiring          "services"
   :subscription       "services"
   :other_services     "services"
   :delivery_amount    "logistics"
   :delivery_logistics "logistics"
   :return_logistics   "logistics"
   :returns_cargo      "logistics"
   :storage            "logistics"
   :packaging          "logistics"
   :warehouse_movement "logistics"
   :fines              "other"
   :corrections        "other"
   :compensation       "other"})

(def transfer-columns
  "Payout legs — money leaving the MP settlement account to the seller's
   bank. Modeled as :transfer (excluded from ДДС income/expense, CF-5)."
  [:payment :invoice_transfer])

;; ---------------------------------------------------------------------------
;; DEC-3 telescoping pro-rate
;; ---------------------------------------------------------------------------

(defn- parse-day ^LocalDate [s] (LocalDate/parse (subs s 0 10)))

(defn- days-incl ^long [^LocalDate a ^LocalDate b]
  (inc (.between ChronoUnit/DAYS a b)))

(defn slice-amount
  "Window share of a signed bucket amount on exact decimal. Telescoping:
   slice = P(days up to window end) − P(days before window start), with
   P(k) = d-prorate(amount, k, total-days) — so consecutive windows sum to
   the full amount to the kopeck (DEC-3)."
  [amount bucket-begin bucket-end win-from win-to]
  (let [b0    (parse-day bucket-begin)
        b1    (parse-day bucket-end)
        total (days-incl b0 b1)
        cum   (fn [^LocalDate d]
                (cond (.isBefore d b0) 0
                      (.isAfter d b1)  total
                      :else            (days-incl b0 d)))]
    (m/d- (m/d-prorate amount (cum (parse-day win-to)) total)
          (m/d-prorate amount (cum (.minusDays (parse-day win-from) 1)) total))))

;; ---------------------------------------------------------------------------
;; Bucket → operations (pure)
;; ---------------------------------------------------------------------------

(defn bucket->ops
  "One cash_flow_periods row → vector of ops/create!-ready maps for the
   window overlap. Zero slices are skipped."
  [row {:keys [from to mp-account-id bank-account-id counterparty-id]}]
  (let [begin    (subs (str (:period_begin row)) 0 10)
        end      (subs (str (:period_end row)) 0 10)
        op-date  (if (pos? (compare end to)) to end)
        slice-of (fn [col]
                   (let [raw (get row col)]
                     (when (and raw (not (zero? (double raw))))
                       (let [s (slice-amount (d-of-double raw) begin end from to)]
                         (when-not (m/d-zero? s) s)))))
        descr    (fn [col] (str "cash_flow_periods #" (:id row) " "
                                (name col) " " begin ".." end))
        base     {:op-date    op-date
                  :currency   "RUB"
                  :account-id mp-account-id
                  :confirmed  true
                  :regular    false
                  :source     "seed:cash_flow_periods"}
        flows    (keep (fn [[col category]]
                         (when-let [s (slice-of col)]
                           (assoc base
                                  :amount          (m/d->str (.abs ^BigDecimal s))
                                  :direction       (if (neg? (.signum ^BigDecimal s))
                                                     :expense :income)
                                  :category        category
                                  :category-source :seed
                                  :counterparty-id counterparty-id
                                  :description     (descr col))))
                       flow-columns)
        payouts  (keep (fn [col]
                         (when-let [s (slice-of col)]
                           (assoc base
                                  :amount              (m/d->str (.abs ^BigDecimal s))
                                  :direction           :transfer
                                  :transfer-account-id bank-account-id
                                  :description         (descr col))))
                       transfer-columns)]
    (vec (concat flows payouts))))
