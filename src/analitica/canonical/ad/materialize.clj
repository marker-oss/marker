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
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn- sum-spend-by-article-day
  "Compute {[article event_date] → Σ :spend} from ad_spend for the period.
   Day-level (not article-total) so ad_cost can be allocated onto ONE finance
   row per (article, day) instead of the article total on every row (audit N3).
   nil-article rows (account-level residue) are excluded."
  [marketplace from to]
  (->> (db/query
         ["SELECT article, event_date, COALESCE(SUM(spend), 0.0) AS total_spend
           FROM ad_spend
           WHERE marketplace = ?
             AND event_date BETWEEN ? AND ?
             AND article IS NOT NULL
           GROUP BY article, event_date"
          (name marketplace) from to])
       (reduce (fn [m row]
                 (let [article (:article row)
                       day     (or (:event-date row) (:event_date row))
                       spend   (or (:total-spend row) (:total_spend row) 0.0)]
                   (if article
                     (assoc m [article day] (double spend))
                     m)))
               {})))

(defn- ad-cost-by-rrd
  "Allocate {[article day] → spend} onto finance rrd_ids: one target row per
   (article, day) — min rrd_id with event_date = day, else the article's min
   rrd_id in the window. Keeps SUM(ad_cost) == Σ spend without per-row
   multiplication (audit N3). Returns {rrd_id → ad_cost}."
  [tx marketplace by-art-day from to]
  (let [articles (into #{} (keep first (keys by-art-day)))
        fin-rows (when (seq articles)
                   (jdbc/execute! tx
                     (into [(str "SELECT rrd_id, article, event_date FROM finance
                                  WHERE marketplace = ? AND article IN ("
                                 (str/join "," (repeat (count articles) "?"))
                                 ") AND ((event_date IS NOT NULL AND event_date BETWEEN ? AND ?)
                                        OR (event_date IS NULL AND date_from <= ? AND date_to >= ?))
                                  ORDER BY rrd_id")]
                           (concat [(name marketplace)] articles [from to to from]))
                     {:builder-fn rs/as-unqualified-lower-maps}))
        by-day-rrd (reduce (fn [m {:keys [rrd_id article event_date]}]
                             (update m [article event_date] (fnil conj []) rrd_id))
                           {} fin-rows)
        by-art-rrd (reduce (fn [m {:keys [rrd_id article]}]
                             (update m article (fnil conj []) rrd_id))
                           {} fin-rows)]
    (reduce (fn [acc [[article day] spend]]
              (if-let [rrd (or (first (get by-day-rrd [article day]))
                               (first (get by-art-rrd article)))]
                (update acc rrd (fnil + 0.0) spend)
                acc))
            {} by-art-day)))

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
  (let [by-art-day (sum-spend-by-article-day marketplace from to)]
    (jdbc/with-transaction [tx (db/ds)]
      ;; Step 1: reset ad_cost to 0 for the entire (marketplace, period)
      (jdbc/execute! tx
        ["UPDATE finance
          SET ad_cost = 0
          WHERE marketplace = ?
            AND ((event_date IS NOT NULL AND event_date BETWEEN ? AND ?)
                 OR (event_date IS NULL AND date_from <= ? AND date_to >= ?))"
         (name marketplace) from to to from])
      ;; Step 2: SET one target finance row per (article, day) — NOT the article
      ;; total on every row (audit N3: that multiplied ad_cost by rows-per-article).
      (doseq [[rrd cost] (ad-cost-by-rrd tx marketplace by-art-day from to)]
        (jdbc/execute! tx
          ["UPDATE finance SET ad_cost = ? WHERE marketplace = ? AND rrd_id = ?"
           (double cost) (name marketplace) rrd])))
    {:marketplace marketplace
     :articles    (count (into #{} (keep first (keys by-art-day))))
     :total-spend (reduce + 0.0 (vals by-art-day))}))
