(ns analitica.canonical.ad.query
  "Read-side of the canonical ad-spend log (018 §3.B owner definition).

   SINGLE query path, SINGLE mp-clause, ZERO per-MP branches (SC-001/SC-002).
   Mirror of canonical/events/query.clj mp-clause discipline.

   Adding WB or YM as a second/third producer means a new :marketplace tag
   in the SAME ad_spend table; this read-side requires ZERO changes (SC-002).

   Consumer API (contracts/ad-canon.edn :consumer-query):
     spend-by-mp      [{:keys [from to marketplace]}]
       → [{:marketplace kw :spend double :bonus-spend double} …]
     spend-by-article [{:keys [from to marketplace]}]
       → [{:marketplace kw :article str-or-nil :spend double} …]

   Graceful zero (FR-008/SC-008): no rows → empty collection / 0.0, not error."
  (:require [analitica.db :as db]))

;; ---------------------------------------------------------------------------
;; Private: unified mp-clause (mirrors canonical/events/query.clj:10-18)
;; nil / :all → no filter; keyword → parametric AND marketplace = ?
;; ---------------------------------------------------------------------------

(defn- mp-clause
  "Build SQL fragment filtering by marketplace.
     nil / :all → \"\" (cross-MP totals)
     :ozon/:wb/:ym → \" AND marketplace = ?\" (param appended to args)"
  [mp]
  (cond
    (nil? mp)   ""
    (= :all mp) ""
    :else       " AND marketplace = ?"))

(defn- mp-args
  "Return the extra SQL args for the mp-clause, or [] for nil/:all."
  [mp]
  (cond
    (nil? mp)   []
    (= :all mp) []
    :else       [(name mp)]))

;; ---------------------------------------------------------------------------
;; Public: spend-by-mp — aggregate spend per marketplace in a date range
;; ---------------------------------------------------------------------------

(defn spend-by-mp
  "Return aggregated ad spend per marketplace for [from..to].
   :marketplace nil / :all → all MPs; keyword → single MP.
   Graceful zero: empty table → empty collection (FR-008)."
  [{:keys [from to marketplace]}]
  (let [sql  (str "SELECT marketplace, SUM(spend) AS spend, SUM(bonus_spend) AS bonus_spend"
                  " FROM ad_spend"
                  " WHERE event_date BETWEEN ? AND ?"
                  (mp-clause marketplace)
                  " GROUP BY marketplace")
        args (into [from to] (mp-args marketplace))
        rows (db/query (into [sql] args))]
    (mapv (fn [row]
            {:marketplace        (keyword (:marketplace row))
             :spend              (double (or (:spend row) 0.0))
             :bonus-spend        (double (or (:bonus_spend row) 0.0))})
          rows)))

;; ---------------------------------------------------------------------------
;; Public: spend-by-article — per-article breakdown for [from..to]
;; ---------------------------------------------------------------------------

(defn spend-by-article
  "Return aggregated ad spend per marketplace+article for [from..to].
   :marketplace nil → all MPs; keyword → single MP.
   Rows with article=nil (account-level residue) are included (FR-005).
   Graceful zero: empty table → empty collection (FR-008)."
  [{:keys [from to marketplace]}]
  (let [sql  (str "SELECT marketplace, article, SUM(spend) AS spend, SUM(bonus_spend) AS bonus_spend"
                  " FROM ad_spend"
                  " WHERE event_date BETWEEN ? AND ?"
                  (mp-clause marketplace)
                  " GROUP BY marketplace, article")
        args (into [from to] (mp-args marketplace))
        rows (db/query (into [sql] args))]
    (mapv (fn [row]
            {:marketplace  (keyword (:marketplace row))
             :article      (:article row)
             :spend        (double (or (:spend row) 0.0))
             :bonus-spend  (double (or (:bonus_spend row) 0.0))})
          rows)))
