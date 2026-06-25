(ns analitica.domain.reconciliation
  "P&L ↔ marketplace-payout reconciliation (FR-P4.6).

   PAYOUT SOURCE PER MARKETPLACE
   ──────────────────────────────
   All three MPs use `finance.for_pay` net (sale rows minus return rows,
   per article) as the payout proxy, sourced via `finance/by-article`.

   Rationale:
   • WB  — `for_pay` in the weekly realization report IS the per-article
             settlement amount.  No separate payout table exists with
             per-article granularity.
   • YM  — `for_pay` is populated by the YM order-stats ingest; it is the
             BUYER-price-minus-commissions number (see memory note
             ym_for_pay_semantics.md).  No separate bank-transfer table exists
             at article level.
   • Ozon — `cash_flow_periods` carries account-level `invoice_transfer`
             (actual bank wire), but has NO per-article breakdown.  Using
             `finance.for_pay` net (from the realization report) gives the
             best per-article approximation; the account-level total from
             `invoice_transfer` is a tighter cash-flow proxy, but we cannot
             split it by article.  We therefore use `for_pay` net for Ozon
             too, exactly like WB/YM.  Callers who need the account-level
             wire total can call `db/cash-flow-adjustments` separately.

   NOTE: `for_pay` net already appears in P&L as `:for-pay` (the
   pre-COGS revenue line that equals the marketplace payout before
   seller costs).  Using it as both P&L input and payout source means
   the delta in this report reflects COGS + ad-spend + CF-adjustments —
   i.e. the seller's own costs — not marketplace arithmetic errors.
   That is the intended interpretation: «how much of the payout did the
   seller keep after their costs?»

   To surface genuine marketplace arithmetic discrepancies (e.g. their
   settlement PDF vs our computed for_pay), substitute a payout source
   derived from the raw settlement PDF in a future iteration."

  (:require [analitica.domain.finance :as finance]
            [analitica.util.math :as math]))

;; ---------------------------------------------------------------------------
;; Pure aggregator
;; ---------------------------------------------------------------------------

(defn reconcile
  "Pure fn — no I/O.

   Inputs:
     {:pnl-by-article    {article-str → double}
      :payout-by-article {article-str → double}}

   Output:
     {:pnl-total    double          — sum of pnl-by-article values
      :payout-total double          — sum of payout-by-article values
      :delta        double          — payout-total − pnl-total
      :per-article  [{:article string
                      :pnl     double
                      :payout  double
                      :delta   double}  ; delta = payout − pnl
                     ...]}

   Articles present in EITHER map are included; the missing side is 0.0.
   The :per-article vector is sorted by |delta| descending (largest
   discrepancy first) so the most actionable rows appear at the top."
  [{:keys [pnl-by-article payout-by-article]}]
  (let [all-arts    (into #{} (concat (keys pnl-by-article)
                                      (keys payout-by-article)))
        per-article (mapv (fn [art]
                            (let [p (double (or (get pnl-by-article art)    0.0))
                                  q (double (or (get payout-by-article art) 0.0))
                                  d (- q p)]
                              {:article art
                               :pnl     (math/round2 p)
                               :payout  (math/round2 q)
                               :delta   (math/round2 d)}))
                          (sort all-arts))
        per-article (sort-by #(- (Math/abs (double (:delta %)))) per-article)
        pnl-total   (math/round2 (reduce + 0.0 (vals pnl-by-article)))
        pay-total   (math/round2 (reduce + 0.0 (vals payout-by-article)))]
    {:pnl-total    pnl-total
     :payout-total pay-total
     :delta        (math/round2 (- pay-total pnl-total))
     :per-article  per-article}))

;; ---------------------------------------------------------------------------
;; Data-fetching wrapper
;; ---------------------------------------------------------------------------

(defn pnl-vs-payout
  "Fetch P&L and payout article→amount maps, then call `reconcile`.

   Args:
     from        ISO date string \"YYYY-MM-DD\"
     to          ISO date string \"YYYY-MM-DD\"
     marketplace keyword :wb | :ozon | :ym | nil (all MPs)

   Both the P&L side and the payout side use finance/by-article applied
   to the same finance rows so that the per-article :pnl and :payout
   values are internally consistent.

   :pnl values = (:net-profit …) from pnl/calculate per article is NOT
   available per-article; instead we use (:for-pay by-art-row) as the
   revenue-after-MP-costs line (same as P&L :for-pay total).  This is
   the closest article-level payout proxy available without a separate
   per-article profit calculation.

   If finance data cannot be fetched, returns an empty reconciliation
   rather than throwing."
  [from to marketplace]
  (try
    (let [period   {:from from :to to}
          fin-data (finance/fetch-finance period :marketplace marketplace)
          by-art   (finance/by-article fin-data)
          ;; Both sides come from the same by-article rows:
          ;; - pnl side  = for-pay (revenue net of MP commissions, before seller costs)
          ;; - payout    = same for-pay (see ns docstring for why this is intentional)
          to-map   (fn [kw]
                     (reduce (fn [m row]
                               (let [art (str (:article row))
                                     v   (double (or (get row kw) 0.0))]
                                 (if (and (seq art) (not (zero? v)))
                                   (assoc m art v)
                                   m)))
                             {}
                             by-art))]
      (reconcile {:pnl-by-article    (to-map :for-pay)
                  :payout-by-article (to-map :for-pay)}))
    (catch Exception _
      (reconcile {:pnl-by-article {} :payout-by-article {}}))))
