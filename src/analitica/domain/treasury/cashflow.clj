(ns analitica.domain.treasury.cashflow
  "US1 — ДДС (cash-flow statement) aggregation (spec 019, T021).

   Contracts: cashflow-api.edn §1. The report is a DERIVED matrix (never
   stored): rows = categories | accounts, columns = total + months
   newest-first, each cell a decimal-string. The net line is Σinflow − Σoutflow
   and — critically — EVERY net cell equals the dsum of that column across all
   rows (CF-2), and EVERY row total equals the dsum of its month cells (CF-3),
   to the kopeck (FR-004/SC-002). Transfers are excluded from net and from
   every row cell (R3, CF-5). Uncategorised operations are surfaced via a count,
   never folded into a catch-all row (FR-005, CF-6).

   Sign convention: income cells are positive, expense cells negative. A
   category/account row cell = Σincome − Σoutflow of that group in that month.

   Modes (R10, FR-021): :actuals (confirmed only) | :with-planned (all).

   `treasury-categories` is the SHARED taxonomy with 015 (FR-023, §3.A):
   a superset of 015 opex-categories (salary/rent/services/marketing/other
   present verbatim) plus the full ДДС article set."
  (:require [clojure.string :as str]
            [analitica.db :as db]
            [analitica.util.math :as m]
            [analitica.domain.treasury.operations :as ops]))

;; ---------------------------------------------------------------------------
;; Shared category taxonomy (§3.A / FR-023) — superset of 015 opex-categories.
;; Slugs salary/rent/services/marketing/other are 015 verbatim. Source of truth
;; for the read-only /categories reference endpoint.
;; ---------------------------------------------------------------------------

(def treasury-categories
  [{:slug "purchase"  :title "Закупка товара"        :activity-type :operational}
   {:slug "logistics" :title "Логистика"             :activity-type :operational}
   {:slug "marketing" :title "Реклама"               :activity-type :operational}
   {:slug "services"  :title "Услуги"                :activity-type :operational}
   {:slug "salary"    :title "Зарплата"              :activity-type :operational}
   {:slug "rent"      :title "Аренда"                :activity-type :operational}
   {:slug "mp-payout" :title "Маркетплейс / выплаты" :activity-type :operational}
   {:slug "taxes"     :title "Налоги"                :activity-type :tax}
   {:slug "loans"     :title "Кредиты / займы"       :activity-type :financial}
   {:slug "capex"     :title "Оборудование / CapEx"  :activity-type :investment}
   {:slug "other"     :title "Прочее"                :activity-type :operational}])

(def ^:private category-by-slug
  (into {} (map (juxt :slug identity)) treasury-categories))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- month-of
  "YYYY-MM prefix of an ISO date string."
  [op-date]
  (subs op-date 0 7))

(defn- signed
  "Signed BigDecimal contribution of an operation to a cash cell:
   income → +amount, expense → −amount. (Transfers are pre-excluded.)"
  [{:keys [direction amount]}]
  (let [a (m/d amount)]
    (case direction
      :income  a
      :expense (m/d- (m/d "0.00") a)
      (m/d "0.00"))))

(defn- months-in-range
  "All YYYY-MM strings from `from`..`to` inclusive, newest-first.
   Throws ex-info when from > to: the loop below only terminates on
   equality, so a reversed range would otherwise walk to year 9999,
   wrap, and cycle forever while growing acc (audit 2026-07-02 H1)."
  [from to]
  (let [ym (fn [d] (subs d 0 7))
        start (ym from)
        end   (ym to)]
    (when (pos? (compare start end))
      (throw (ex-info "from is after to" {:from from :to to})))
    (loop [cur start acc []]
      (let [acc' (conj acc cur)]
        (cond
          (= cur end)
          (vec (reverse acc'))

          ;; belt-and-suspenders: 1200 months = 100 years; runaway input.
          (> (count acc') 1200)
          (throw (ex-info "month range too large" {:from from :to to}))

          :else
          (let [y (Integer/parseInt (subs cur 0 4))
                mo (Integer/parseInt (subs cur 5 7))
                [y' mo'] (if (= mo 12) [(inc y) 1] [y (inc mo)])]
            (recur (format "%04d-%02d" y' mo') acc')))))))

(defn- fetch-operations
  "Confirmed (and, in :with-planned mode, planned) NON-transfer operations in
   [from..to], optionally restricted to :account-ids. Returned in domain shape."
  [{:keys [from to account-ids mode]}]
  (let [confirmed-clause (if (= mode :with-planned) nil "confirmed = 1")
        acc-clause (when (seq account-ids)
                     (str "account_id IN ("
                          (clojure.string/join "," (repeat (count account-ids) "?")) ")"))
        clauses (->> ["direction != 'transfer'"
                      "op_date >= ?" "op_date <= ?"
                      confirmed-clause acc-clause]
                     (remove nil?))
        where   (clojure.string/join " AND " clauses)
        params  (into [from to] (when (seq account-ids) account-ids))]
    (map #(-> {:direction (keyword (:direction %))
               :amount    (:amount %)
               :op-date   (:op-date %)
               :category  (:category %)
               :account-id (:account-id %)})
         (db/treasury-query-operations where params))))

;; ---------------------------------------------------------------------------
;; Matrix assembly
;; ---------------------------------------------------------------------------

(defn- account-meta
  "Map account-id → {:label :marketplace} for group-by :account rows."
  []
  (into {}
        (map (fn [a] [(:id a) {:label (:name a) :marketplace (:marketplace a)}]))
        (:accounts (ops/list-accounts {:include-archived true}))))

(defn- group-key+meta
  "Grouping key and row metadata for an operation, per grouping dimension."
  [dim acc-meta op]
  (case dim
    :category (let [slug (:category op)
                    m    (category-by-slug slug)]
                {:key slug :label (or (:title m) slug)
                 :activity-type (:activity-type m)})
    :account  (let [aid (:account-id op)
                    m   (acc-meta aid)]
                {:key aid :label (:label m) :marketplace (:marketplace m)})))

(defn- group-ops
  "Group `ops` by the dimension, returning a seq of [group-meta ops]."
  [dim acc-meta ops]
  (->> ops
       (clojure.core/group-by (fn [op] (group-key+meta dim acc-meta op)))
       (map (fn [[gmeta gops]] [gmeta gops]))))

(defn- build-cells
  "Given ops for one row and the month columns, return the cells map
   {\"total\" \"…\" \"YYYY-MM\" \"…\"} with total = dsum(month cells)."
  [ops months]
  (let [by-month (reduce (fn [acc op]
                           (update acc (month-of (:op-date op))
                                   (fnil conj []) op))
                         {} ops)
        month-cells (into {}
                          (map (fn [mo]
                                 [mo (m/dsum (map signed (get by-month mo [])))]))
                          months)
        total (m/dsum (vals month-cells))]
    (-> (into {} (map (fn [[mo v]] [mo (m/d->str v)])) month-cells)
        (assoc "total" (m/d->str total)))))

(defn report
  "Build a ДДС report. Args map: :from :to :group-by (:category|:account,
   default :category) :account-ids (optional) :mode (:actuals|:with-planned,
   default :actuals). Returns the derived matrix per cashflow-api.edn §1."
  [{gb :group-by :keys [from to mode] :or {gb :category mode :actuals} :as req}]
  (let [gb        (or gb :category)
        ops       (fetch-operations (assoc req :mode mode))
        ;; Uncategorised operations (category nil) are SURFACED via a count and
        ;; excluded from BOTH the rows and the net line, in either grouping.
        ;; This keeps net == Σ rows (CF-2) AND by_category.net == by_account.net
        ;; (CF-4): both dimensions aggregate exactly the categorised operations.
        uncat     (filter #(nil? (:category %)) ops)
        rows-ops  (remove #(nil? (:category %)) ops)
        months    (months-in-range from to)
        columns   (into ["total"] months)
        acc-meta  (when (= gb :account) (account-meta))
        grouped   (group-ops gb acc-meta rows-ops)
        rows      (->> grouped
                       (mapv (fn [[gmeta row-ops]]
                               (merge gmeta {:cells (build-cells row-ops months)})))
                       (sort-by (comp str :key))
                       vec)
        ;; net cell per column = dsum of that column across rows (CF-2)
        net-cells (into {}
                        (map (fn [col]
                               [col (m/d->str
                                      (m/dsum (map #(m/d (get-in % [:cells col] "0.00"))
                                                   rows)))]))
                        columns)]
    {:mode                mode
     :group-by            gb
     :columns             columns
     :rows                rows
     :net                 {:label "Чистый поток" :cells net-cells}
     :uncategorised-count (count uncat)}))
