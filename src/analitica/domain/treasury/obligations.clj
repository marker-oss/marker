(ns analitica.domain.treasury.obligations
  "US4 — obligations (ДЗ/КЗ): dashboard summary, 12-month dynamics, settle.

   Contracts: obligations-api.edn §1-§3, ledger-entities.edn §6.
   Money is decimal-as-string (\"0.00\", FR-019); all arithmetic via
   util/math decimal helpers (m/d, m/dsum, m/d-, m/d->str, m/d-prorate).
   Status is DERIVED on read (R6) from due-date + remaining vs today.

   Status derivation (R6, priority matters: overdue before due-soon):
     :settled  remaining-amount = \"0.00\"
     :overdue  due-date < today AND remaining > 0
     :due-soon today <= due-date <= today+30d AND remaining > 0
     :open     otherwise (remaining > 0, due-date > today+30d)

   Dynamics (FR-016, R7, SC-006):
     EXACTLY 12 monthly buckets (today−11m .. today), chronological order.
     Each bucket = Σ remaining of OPEN obligations whose issue_date falls in
     that month (or due_date month if no issue_date). Proratable via
     m/d-prorate (DEC-3). Empty months = zero, not missing.
     Balance = receivable − payable at every point (OB-6, exact decimal)."
  (:require [clojure.string :as str]
            [analitica.db :as db]
            [analitica.util.math :as m]
            [analitica.domain.treasury.schema :as schema])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- now-ts [] (str (java.time.Instant/now)))

(defn- today [] (LocalDate/now))

(defn- parse-date [s] (LocalDate/parse s))

(defn- ->bool [x] (= 1 (long x)))
(defn- ->kw   [s] (when (and s (not (str/blank? (str s)))) (keyword s)))

(defn- row->obligation [r]
  {:id                   (:id r)
   :direction            (->kw (:direction r))
   :amount               (:amount r)
   :remaining-amount     (:remaining-amount r)
   :currency             (:currency r)
   :counterparty-id      (:counterparty-id r)
   :issue-date           (:issue-date r)
   :due-date             (:due-date r)
   :settled-operation-id (:settled-operation-id r)
   :confirmed            (->bool (:confirmed r))
   :created-at           (:created-at r)})

;; ---------------------------------------------------------------------------
;; Status derivation (R6) — DERIVED on read, not stored
;; ---------------------------------------------------------------------------

(defn- derive-status
  "Compute the status of an obligation from its remaining-amount and due-date
   relative to today. Priority: settled > overdue > due-soon > open (R6)."
  [{:keys [remaining-amount due-date]}]
  (let [remaining (m/d remaining-amount)
        td        (today)
        dd        (parse-date due-date)]
    (cond
      (m/d-zero? remaining)              :settled
      (.isBefore dd td)                   :overdue
      (.isAfter dd (.plusDays td 30))     :open
      :else                               :due-soon)))

;; ---------------------------------------------------------------------------
;; CRUD
;; ---------------------------------------------------------------------------

(defn create!
  "Validate + insert an obligation. Returns {:id n}.
   Keys per ledger-entities.edn §6."
  [{:keys [direction amount remaining-amount currency
           counterparty-id issue-date due-date settled-operation-id confirmed]
    :or   {currency "RUB" confirmed true}}]
  (let [ob {:direction             direction
             :amount                amount
             :remaining-amount      remaining-amount
             :currency              currency
             :counterparty-id       counterparty-id
             :issue-date            issue-date
             :due-date              due-date
             :settled-operation-id  settled-operation-id
             :confirmed             confirmed}]
    (schema/validate! schema/Obligation ob "Obligation")
    {:id (db/treasury-insert-obligation!
           {:direction             (name direction)
            :amount                amount
            :remaining-amount      remaining-amount
            :currency              currency
            :counterparty-id       counterparty-id
            :issue-date            issue-date
            :due-date              due-date
            :settled-operation-id  settled-operation-id
            :confirmed             confirmed
            :created-at            (now-ts)})}))

;; ---------------------------------------------------------------------------
;; settle! — partial or full settlement
;; ---------------------------------------------------------------------------

(defn settle!
  "Apply a settlement to obligation `id`. Reduces remaining-amount by
   `settle-amount`; when remaining reaches 0 the obligation is effectively
   :settled (status derived on next read). Records `settled-operation-id`.
   Returns {:ok true :remaining-amount \"0.00\" :status :settled|:open|…}.
   Throws if settle-amount > remaining."
  [id {:keys [settled-operation-id settle-amount]}]
  (let [raw (db/treasury-get-obligation id)]
    (when-not raw
      (throw (ex-info (str "Obligation not found: " id) {:id id})))
    (let [ob      (row->obligation raw)
          curr    (m/d (:remaining-amount ob))
          settle  (m/d settle-amount)
          new-rem (m/d- curr settle)]
      (when (m/dneg? new-rem)
        (throw (ex-info "settle-amount exceeds remaining-amount"
                        {:remaining (:remaining-amount ob)
                         :settle-amount settle-amount})))
      (let [new-rem-str (m/d->str new-rem)
            set-map     (cond-> {:remaining_amount new-rem-str}
                          settled-operation-id
                          (assoc :settled_operation_id settled-operation-id))]
        (db/treasury-update-obligation! id set-map)
        (let [updated (assoc ob :remaining-amount new-rem-str)]
          {:ok               true
           :remaining-amount new-rem-str
           :status           (derive-status updated)})))))

;; ---------------------------------------------------------------------------
;; summary — dashboard (FR-014, FR-015, SC-005)
;; ---------------------------------------------------------------------------

(defn summary
  "Build the obligations summary (receivable/payable totals + buckets).
   :mode :actuals (default) | :with-planned (R10, FR-021).
   Returns the shape from obligations-api.edn §1."
  ([] (summary {}))
  ([{:keys [mode] :or {mode :actuals}}]
   (let [where     (if (= mode :actuals) "confirmed = 1" nil)
         raw-rows  (db/treasury-query-obligations (or where "") [])
         obs       (mapv row->obligation raw-rows)
         td        (today)
         ;; Only open obligations count (remaining > 0)
         open-obs  (filter (fn [o]
                             (not (m/d-zero? (m/d (:remaining-amount o)))))
                           obs)
         ;; Totals by direction
         recv-obs  (filter #(= :receivable (:direction %)) open-obs)
         pay-obs   (filter #(= :payable    (:direction %)) open-obs)
         receivable (m/d->str (m/dsum (map #(m/d (:remaining-amount %)) recv-obs)))
         payable    (m/d->str (m/dsum (map #(m/d (:remaining-amount %)) pay-obs)))
         balance    (m/d->str (m/d- (m/d receivable) (m/d payable)))
         ;; Bucket helpers (OB-2, OB-3: overdue priority over due-soon)
         overdue?  (fn [o] (let [dd (parse-date (:due-date o))]
                             (.isBefore dd td)))
         due-soon? (fn [o] (let [dd (parse-date (:due-date o))]
                             (and (not (.isBefore dd td))
                                  (not (.isAfter dd (.plusDays td 30))))))
         ;; Overdue beats due-soon (FR-015, OB-3)
         overdue-recv  (filter #(and (= :receivable (:direction %)) (overdue?  %)) open-obs)
         due-soon-recv (filter #(and (= :receivable (:direction %)) (due-soon? %)) open-obs)
         overdue-pay   (filter #(and (= :payable    (:direction %)) (overdue?  %)) open-obs)
         due-soon-pay  (filter #(and (= :payable    (:direction %)) (due-soon? %)) open-obs)]
     {:mode       mode
      :receivable receivable
      :payable    payable
      :balance    balance
      :next-30-receivable {:amount (m/d->str (m/dsum (map #(m/d (:remaining-amount %)) due-soon-recv)))
                           :count  (count due-soon-recv)}
      :next-30-payable    {:amount (m/d->str (m/dsum (map #(m/d (:remaining-amount %)) due-soon-pay)))
                           :count  (count due-soon-pay)}
      :overdue-receivable {:amount (m/d->str (m/dsum (map #(m/d (:remaining-amount %)) overdue-recv)))
                           :count  (count overdue-recv)}
      :overdue-payable    {:amount (m/d->str (m/dsum (map #(m/d (:remaining-amount %)) overdue-pay)))
                           :count  (count overdue-pay)}})))

;; ---------------------------------------------------------------------------
;; dynamics — 12-month time series (FR-016, SC-006)
;; ---------------------------------------------------------------------------

(defn- month-start
  "First day of the YYYY-MM month containing LocalDate `d`."
  [^LocalDate d]
  (.withDayOfMonth d 1))

(defn- month-end
  "Last day of the YYYY-MM month containing LocalDate `d`."
  [^LocalDate d]
  (.withDayOfMonth d (.lengthOfMonth d)))

(defn- month-label
  "YYYY-MM string from a LocalDate."
  [^LocalDate d]
  (.format d (DateTimeFormatter/ofPattern "yyyy-MM")))

(defn- build-12-months
  "Return a vector of 12 {:month \"YYYY-MM\" :start LocalDate :end LocalDate}
   maps covering today-11months .. today, chronological (oldest first)."
  []
  (let [td (today)]
    (mapv (fn [months-back]
            (let [base  (.minusMonths td months-back)
                  start (month-start base)
                  end   (month-end base)]
              {:month (month-label base)
               :start start
               :end   end}))
          ;; 11 months ago to 0 months ago (today's month), then reverse
          (reverse (range 12)))))

(defn- obligation-month
  "YYYY-MM for placing an obligation into a dynamics bucket.
   Uses :issue-date when present, otherwise :due-date."
  [ob]
  (let [d (or (:issue-date ob) (:due-date ob))]
    (subs d 0 7)))

(defn dynamics
  "Build the 12-month obligations dynamics series.
   Returns {:mode :actuals :points [{:month :receivable :payable :balance}×12]}.
   Every point has balance = receivable − payable, exact decimal (OB-6, SC-006).
   Exactly 12 points; empty months have zero values (OB-5, FR-016)."
  ([] (dynamics {}))
  ([{:keys [mode] :or {mode :actuals}}]
   (let [where    (if (= mode :actuals) "confirmed = 1" nil)
         raw-rows (db/treasury-query-obligations (or where "") [])
         obs      (mapv row->obligation raw-rows)
         ;; Only open (remaining > 0) obligations contribute
         open-obs (filter (fn [o] (not (m/d-zero? (m/d (:remaining-amount o))))) obs)
         buckets  (build-12-months)
         ;; Build a set of month labels in our 12-month window for fast lookup
         months-set (into #{} (map :month buckets))
         ;; Group open obligations by their month label
         by-month (group-by obligation-month open-obs)]
     {:mode   mode
      :points (mapv (fn [{:keys [month]}]
                      (let [obs-in-month (get by-month month [])
                            recv-obs     (filter #(= :receivable (:direction %)) obs-in-month)
                            pay-obs      (filter #(= :payable    (:direction %)) obs-in-month)
                            receivable   (m/d->str (m/dsum (map #(m/d (:remaining-amount %)) recv-obs)))
                            payable      (m/d->str (m/dsum (map #(m/d (:remaining-amount %)) pay-obs)))
                            balance      (m/d->str (m/d- (m/d receivable) (m/d payable)))]
                        {:month      month
                         :receivable receivable
                         :payable    payable
                         :balance    balance}))
                    buckets)})))

;; ---------------------------------------------------------------------------
;; list-obligations — paginated listing with derived status (FR-017, R6)
;; ---------------------------------------------------------------------------

(defn list-obligations
  "List obligations with derived :status (computed from due-date + remaining).
   Filter opts: :direction :status :mode :page :page-size.
   Returns {:obligations [...] :page :page-size :total}."
  ([] (list-obligations {}))
  ([{:keys [direction status mode page page-size]
     :or   {mode :actuals page 1 page-size 50}}]
   (let [clauses (cond-> []
                   (= mode :actuals) (conj ["confirmed = 1"])
                   direction         (conj [(str "direction = ?") (name direction)]))
         where   (if (seq clauses)
                   (str/join " AND " (map first clauses))
                   "")
         params  (mapcat (fn [c] (rest c)) clauses)
         all     (->> (db/treasury-query-obligations where (vec params))
                      (mapv row->obligation)
                      (mapv (fn [o] (assoc o :status (derive-status o)))))
         ;; Apply status filter (derived, so filter in Clojure after fetching)
         filtered (if status
                    (filter #(= status (:status %)) all)
                    all)
         total    (count filtered)
         page-vec (->> filtered
                       (drop (* (dec page) page-size))
                       (take page-size)
                       vec)]
     {:obligations page-vec
      :page        page
      :page-size   page-size
      :total       total})))
