(ns analitica.domain.pnl-ad-canon-test
  "P&L.2 byte-for-byte invariant tests (T016) and SC-003 DRR/ROAS
   equal-basis fixture invariant (T016b).

   T016:  pnl/calculate :ad-spend reads from finance.ad_cost (canon path).
          revenue/gross byte-for-byte unchanged; ad-spend from canon materialize.
   T016b: equal spend/revenue inputs → identical drr_pct across all MPs
          on the same documented basis (drr_pct := ad_spend / revenue × 100)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.domain.pnl :as pnl]
            [analitica.canonical.ad.materialize :as mat]
            [analitica.util.math :as math]
            [next.jdbc :as jdbc])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; DB isolation
;; ---------------------------------------------------------------------------

(def ^:dynamic *db-path* nil)

(defn- fresh-db []
  (let [p (Files/createTempFile "pnl-ad-canon-test-" ".db" (make-array FileAttribute 0))
        f (.toFile p)]
    (.delete f)
    (.getAbsolutePath f)))

(defn- delete-db [path]
  (doseq [s ["" "-shm" "-wal"]]
    (let [f (File. (str path s))]
      (when (.exists f) (.delete f)))))

(defn with-db [f]
  (let [p (fresh-db)]
    (try
      (alter-var-root #'db/db-spec (constantly {:dbtype "sqlite" :dbname p}))
      (binding [*db-path* p]
        (db/init!)
        (f))
      (finally
        (reset! @#'db/datasource nil)
        (delete-db p)))))

(use-fixtures :each with-db)

;; ---------------------------------------------------------------------------
;; Finance insertion helper — ONE row per article per period
;; (Materialize sets ad_cost on ALL matching rows; so 1 row/article avoids
;; the N×ad_cost multiplication when SUM(ad_cost) is computed by pnl.)
;; ---------------------------------------------------------------------------

(def ^:private rrd-seq (atom 0))
(defn- next-rrd [] (swap! rrd-seq inc))

(defn- insert-finance-row!
  "Insert ONE finance row for a given article. Returns the rrd_id used."
  [{:keys [marketplace article event-date date-from date-to
           retail-amount for-pay mp-commission wb-reward
           delivery-cost storage-fee acceptance penalty deduction
           additional-payment ad-cost]
    :or   {date-from "2026-04-01" date-to "2026-04-30"
           retail-amount 0.0 for-pay 0.0 mp-commission 0.0 wb-reward 0.0
           delivery-cost 0.0 storage-fee 0.0 acceptance 0.0 penalty 0.0
           deduction 0.0 additional-payment 0.0 ad-cost 0.0}}]
  (let [rrd (next-rrd)]
    (jdbc/execute-one!
      (db/ds)
      ["INSERT INTO finance
          (rrd_id, marketplace, article, date_from, date_to, event_date,
           retail_amount, for_pay, mp_commission, wb_reward,
           delivery_cost, storage_fee, acceptance, penalty, deduction,
           additional_payment, ad_cost, synced_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'2026-04-15 00:00:00')"
       rrd (name marketplace) article date-from date-to event-date
       retail-amount for-pay mp-commission wb-reward
       delivery-cost storage-fee acceptance penalty deduction
       additional-payment ad-cost])
    rrd))

(defn- db-rows->finance-maps
  "Convert DB rows (kebab-case from db/query) to pnl-domain maps.
   db/query uses rs/as-unqualified-kebab-maps → keys are already kebab-case."
  [rows]
  (mapv (fn [row]
          {:marketplace        (keyword (:marketplace row))
           :rrd-id             (:rrd-id row)
           :article            (:article row)
           :date-from          (:date-from row)
           :date-to            (:date-to row)
           :event-date         (:event-date row)
           :retail-amount      (double (or (:retail-amount row) 0))
           :retail-price       (double (or (:retail-amount row) 0))
           :for-pay            (double (or (:for-pay row) 0))
           :mp-commission      (double (or (:mp-commission row) 0))
           :wb-reward          (double (or (:wb-reward row) 0))
           :delivery-cost      (double (or (:delivery-cost row) 0))
           :storage-fee        (double (or (:storage-fee row) 0))
           :acceptance         (double (or (:acceptance row) 0))
           :penalty            (double (or (:penalty row) 0))
           :deduction          (double (or (:deduction row) 0))
           :additional-payment (double (or (:additional-payment row) 0))
           :ad-cost            (double (or (:ad-cost row) 0))
           :operation          "sale"
           :quantity           1})
        rows))

;; ---------------------------------------------------------------------------
;; T016: P&L.2 — revenue/gross byte-for-byte; ad-spend from canon path
;;
;; Design: ONE finance row per article to match materialize semantics
;; (materialize sets ad_cost on ALL matching rows; SUM = 1×cost = correct).
;; ---------------------------------------------------------------------------

(deftest p7-pnl2-revenue-gross-net-byte-for-byte
  (testing "T016: P&L.2 — revenue/gross byte-for-byte; ad-spend from canon path"
    (let [from "2026-04-01" to "2026-04-30"
          ;; ONE row per article (matches Ozon realization semantics)
          in-mem-rows
          [{:marketplace :wb :rrd-id 1
            :date-from from :date-to to :event-date "2026-04-10"
            :article "A" :operation "sale" :quantity 1
            :retail-amount 500.0 :retail-price 500.0
            :for-pay 400.0 :mp-commission 75.0 :wb-reward 75.0
            :delivery-cost 5.0 :storage-fee 2.5 :acceptance 1.0
            :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
            :additional-payment 0.0 :ad-cost 0.0}
           {:marketplace :wb :rrd-id 2
            :date-from from :date-to to :event-date "2026-04-11"
            :article "B" :operation "sale" :quantity 1
            :retail-amount 150.0 :retail-price 150.0
            :for-pay 126.0 :mp-commission 24.0 :wb-reward 24.0
            :delivery-cost 1.5 :storage-fee 0.6 :acceptance 0.3
            :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
            :additional-payment 0.0 :ad-cost 0.0}]

          ;; Baseline: pure in-memory, no DB, ad_cost=0
          baseline (pnl/calculate in-mem-rows :from from :to to :marketplace :wb)]

      ;; Insert ONE finance row per article into DB
      (doseq [row in-mem-rows]
        (insert-finance-row! row))

      ;; Seed ad_spend for article A = 50.0
      (db/insert-ad-spend!
        [(assoc {:marketplace        :wb
                 :event-date         "2026-04-10"
                 :campaign-id        "W001"
                 :article            "A"
                :spend              50.0
                 :bonus-spend        0.0
                 :attribution-source :api}
                :synced-at "2026-04-15 00:00:00")])

      ;; Materialize: sets finance.ad_cost=50.0 on the ONE matching row for article A
      (mat/materialize-ad-cost! :wb from to)

      ;; Read DB rows back → build finance-data with updated ad_cost
      (let [db-rows       (db/query ["SELECT * FROM finance WHERE marketplace='wb'"])
            finance-canon (db-rows->finance-maps db-rows)
            with-canon    (pnl/calculate finance-canon :from from :to to :marketplace :wb)]

        (testing "revenue is byte-for-byte identical (P6 frozen)"
          (is (= (:revenue baseline) (:revenue with-canon))
              (str "revenue: baseline=" (:revenue baseline) " canon=" (:revenue with-canon))))

        (testing "gross-profit is byte-for-byte identical (P6 frozen)"
          (is (= (:gross-profit baseline) (:gross-profit with-canon))
              (str "gross: baseline=" (:gross-profit baseline) " canon=" (:gross-profit with-canon))))

        (testing "ad-spend = 50.0 from canon path (finance.ad_cost)"
          (is (= 50.0 (:ad-spend with-canon))
              (str "ad-spend from canon: " (:ad-spend with-canon))))

        (testing "net-profit = gross-profit - ad-spend"
          (is (= (math/round2 (- (:gross-profit with-canon) (:ad-spend with-canon)))
                 (:net-profit with-canon))
              "net-profit = gross - ad-spend (P&L.2)"))

        (testing ":ad-cost-source is :canonical"
          (is (= :canonical (:ad-cost-source with-canon))
              "source flag: should be :canonical"))))))

;; ---------------------------------------------------------------------------
;; T016b: SC-003 — equal spend/revenue → identical drr_pct across all MPs
;;
;; Design: ONE finance row per MP (matches materialize expectations).
;; After materialize, SUM(finance.ad_cost) per MP = 100.0 exactly.
;; ---------------------------------------------------------------------------

(deftest sc-003-uniform-drr-basis-across-mps
  (testing "T016b SC-003: equal spend/revenue for all MPs → identical drr_pct (§3.12)"
    (let [from "2026-04-01" to "2026-04-30"
          mps  [:ozon :wb :ym]]

      ;; Insert ONE finance row + ONE ad_spend row + materialize for each MP
      (doseq [mp mps]
        ;; ONE finance row per MP (so materialize sets ad_cost = 100.0 on exactly 1 row)
        (insert-finance-row! {:marketplace        mp
                              :article            "SC003-ART"
                              :event-date         "2026-04-15"
                              :date-from          from
                              :date-to            to
                              :retail-amount      1000.0
                              :for-pay            800.0
                              :mp-commission      100.0
                              :delivery-cost      10.0
                              :storage-fee        5.0
                              :acceptance         2.0
                              :ad-cost            0.0})
        (db/insert-ad-spend!
          [(assoc {:marketplace        mp
                   :event-date         "2026-04-15"
                   :campaign-id        (str "SC003-" (name mp))
                   :article            "SC003-ART"
                   :spend              100.0
                   :bonus-spend        0.0
                   :attribution-source :api}
                  :synced-at "2026-04-15 00:00:00")])
        (mat/materialize-ad-cost! mp from to))

      ;; Compute P&L per MP using DB rows
      (let [results
            (into {}
                  (for [mp mps]
                    (let [db-rows       (db/query ["SELECT * FROM finance WHERE marketplace=?"
                                                   (name mp)])
                          finance-rows  (db-rows->finance-maps db-rows)
                          pnl-result    (pnl/calculate finance-rows
                                                       :from from :to to
                                                       :marketplace mp)]
                      [mp pnl-result])))]

        (testing "SC-003: revenue = 1000.0 for all MPs"
          (doseq [mp mps]
            (is (= 1000.0 (get-in results [mp :revenue]))
                (str (name mp) " revenue = 1000.0"))))

        (testing "SC-003: ad-spend = 100.0 from canon path for all MPs"
          (doseq [mp mps]
            (is (= 100.0 (get-in results [mp :ad-spend]))
                (str (name mp) " ad-spend = 100.0 (canon path)"))))

        (testing "SC-003: drr_pct = 10.0% for all MPs (ad_spend/revenue×100 = 100/1000×100, §3.12)"
          (doseq [mp mps]
            (let [ad-spend (get-in results [mp :ad-spend])
                  revenue  (get-in results [mp :revenue])
                  drr      (when (pos? revenue)
                             (math/round2 (* 100.0 (/ ad-spend revenue))))]
              (is (= 10.0 drr)
                  (str (name mp) " drr_pct = 10.0% (§3.12 basis)")))))

        (testing "SC-003: drr_pct is IDENTICAL across all MPs (single documented basis)"
          (let [drr-values
                (mapv (fn [mp]
                        (let [ad-spend (get-in results [mp :ad-spend])
                              revenue  (get-in results [mp :revenue])]
                          (when (pos? revenue)
                            (math/round2 (* 100.0 (/ ad-spend revenue))))))
                      mps)]
            (is (apply = drr-values)
                (str "SC-003 VIOLATED: drr_pct not identical across MPs: " drr-values))))))))
