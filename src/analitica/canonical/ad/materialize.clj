(ns analitica.canonical.ad.materialize
  "Generic write-side of the unified MP-tagged advertising canon (018 §3.B).

   This namespace is the SHARED materialize layer: any MP producer (Ozon via
   spec 011, WB/YM later) calls `materialize-ad-cost!` after inserting rows
   into `ad_spend` to propagate per-article :spend totals into the existing
   `finance.ad_cost` column.

   IMPORTANT — 011 coordinate contract (BRIEF §0.4 LOCKED):
   - 011 (Ozon Performance) calls its own `materialize-ozon-ad-cost!` in
     analitica.materialize. THAT function is the authoritative Ozon path
     and handles raw Performance ingest + spread attribution + reconciliation.
   - THIS namespace provides a GENERIC write that other producers (WB/YM)
     reuse, and that tests for 018 use to verify the canon write semantics
     without coupling to Ozon-specific raw logic.
   - Both paths converge on the SAME contract:
       DELETE(marketplace,period) → INSERT ad_spend → SET finance.ad_cost per article.
   - finance.ad_cost stays the single read-point for P&L (FR-006).

   Idempotency (P5): DELETE(marketplace, from..to) → INSERT is the canonical
   pattern (respread-ozon-finance!). Running twice yields identical state.

   Only :spend (cash) flows into finance.ad_cost.
   :bonus-spend is stored in ad_spend separately and never summed into ad_cost
   (prevents double-counting, contracts/ad-canon.edn :invariants)."
  (:require [analitica.db :as db]
            [next.jdbc :as jdbc]))

(defn- sum-spend-by-article
  "Compute {article → Σ :spend} from ad_spend for the given marketplace+period.
   nil-article rows (account-level residue) are excluded — they have no finance
   row to UPDATE."
  [marketplace from to]
  (->> (db/query
         ["SELECT article, COALESCE(SUM(spend), 0.0) AS total_spend
           FROM ad_spend
           WHERE marketplace = ?
             AND event_date BETWEEN ? AND ?
             AND article IS NOT NULL
           GROUP BY article"
          (name marketplace) from to])
       (reduce (fn [m row]
                 (let [article     (:article row)
                       total-spend (or (:total-spend row) (:total_spend row) 0.0)]
                   (if article
                     (assoc m article (double total-spend))
                     m)))
               {})))

(defn materialize-ad-cost!
  "Propagate per-article :spend totals from ad_spend into finance.ad_cost
   for the given marketplace + [from..to] window.

   Contract:
     1. Reset finance.ad_cost = 0 for (marketplace, period) — idempotent reset.
     2. For each article with spend > 0: SET finance.ad_cost = Σ ad_spend.spend.
     3. Only :spend (cash) is summed; :bonus-spend is NOT included (FR-006).
     4. nil-article rows are skipped (no finance row to target).

   Idempotent (P5): reset+SET means double-run yields the same result.
   finance.ad_cost is the SINGLE read-point for P&L — do NOT break it.

   Returns {:marketplace mp :articles N :total-spend double}."
  [marketplace from to]
  (let [by-article (sum-spend-by-article marketplace from to)]
    (jdbc/with-transaction [tx (db/ds)]
      ;; Step 1: reset ad_cost to 0 for the entire (marketplace, period)
      (jdbc/execute! tx
        ["UPDATE finance
          SET ad_cost = 0
          WHERE marketplace = ?
            AND ((event_date IS NOT NULL AND event_date BETWEEN ? AND ?)
                 OR (event_date IS NULL AND date_from <= ? AND date_to >= ?))"
         (name marketplace) from to to from])
      ;; Step 2: SET per article (only where we have spend)
      (doseq [[article spend] by-article]
        (jdbc/execute! tx
          ["UPDATE finance
            SET ad_cost = ?
            WHERE marketplace = ?
              AND article = ?
              AND ((event_date IS NOT NULL AND event_date BETWEEN ? AND ?)
                   OR (event_date IS NULL AND date_from <= ? AND date_to >= ?))"
           (double spend) (name marketplace) article
           from to to from])))
    {:marketplace  marketplace
     :articles     (count by-article)
     :total-spend  (reduce + 0.0 (vals by-article))}))
