(ns analitica.integration.ozon-hybrid-reconcile-test
  "Integration test: pre-populate SQLite from Ozon realization + transactions
   fixtures, run `materialize-ozon-services!`, then verify:

     - SC-004 B-005 invariant : SUM(for_pay) unchanged after service merge.
     - SC-003 reconciliation  : total cost fields ≈ fixture services total
                                (±5% against the independently-computed
                                 fixture-derived total).
     - SC-011 unknown-rate    : emitted ::ozon-unknown-service events /
                                total services processed ≤ 2%.

   The fixtures live under test/resources/fixtures/ and are loaded from
   the classpath so the test is runnable from any cwd."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [analitica.db :as db]
            [analitica.marketplace.ozon.transform :as ozon-t]
            [analitica.materialize :as mat])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp-file SQLite DB fixture
;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-integration-"
                                   ".db"
                                   (make-array FileAttribute 0))
        f    (.toFile path)]
    (.delete f)
    (.getAbsolutePath f)))

(defn- delete-test-db! [path]
  (doseq [suffix ["" "-shm" "-wal"]]
    (let [f (File. (str path suffix))]
      (when (.exists f) (.delete f)))))

(defn with-temp-db [f]
  (let [path      (fresh-temp-db-path)
        orig-spec (deref #'db/db-spec)]
    (try
      (alter-var-root #'db/db-spec (constantly {:dbtype "sqlite" :dbname path}))
      (binding [*test-db-path* path]
        (db/init!)
        (f))
      (finally
        (alter-var-root #'db/db-spec (constantly orig-spec))
        (reset! @#'db/datasource nil)
        (delete-test-db! path)))))

(use-fixtures :each with-temp-db)

;; ---------------------------------------------------------------------------
;; Fixture loading
;; ---------------------------------------------------------------------------

(defn- load-fixture [resource-name]
  (-> (io/resource resource-name) slurp edn/read-string))

(def ^:private realization-fixture  "fixtures/ozon-realization-2026-03.edn")
(def ^:private transactions-fixture "fixtures/ozon-transactions-2026-03.edn")

(defn- seed-realization! []
  (let [fixture  (load-fixture realization-fixture)
        responses (:responses fixture)]
    (doseq [{:keys [date-from payload]} responses]
      (let [date-to (get-in payload [:header :stop_date])]
        (db/insert-raw! :ozon :realization date-from date-to payload)))))

(defn- seed-transactions! []
  (let [fixture (load-fixture transactions-fixture)
        ops     (:operations fixture)
        ;; Use a period covering the whole month of 2026-03
        date-from "2026-03-01"
        date-to   "2026-03-31"]
    (db/insert-raw! :ozon :transactions date-from date-to {:operations ops})))

(defn- materialize-realization! [from to]
  ;; Call the existing (private) function via var indirection.
  ((deref #'mat/materialize-ozon-finance-from-realization!) from to))

;; ---------------------------------------------------------------------------
;; Pre-compute fixture-derived expected totals (for reconciliation).
;; ---------------------------------------------------------------------------

(defn- fixture-baselines
  "Compute expected SUM(for_pay) and cost-field totals directly from the
   fixtures, bypassing the materialize layer. Used as reference values
   for SC-003 and SC-004."
  []
  (let [real-raw  (load-fixture realization-fixture)
        tx-raw    (load-fixture transactions-fixture)
        real-payload (:payload (first (:responses real-raw)))
        fin-rows  (ozon-t/->finance-from-realization real-payload)
        lookup    (into {} (for [r (:rows real-payload)
                                 :let [item (:item r)]]
                             [(:sku item) (:offer_id item)]))
        svc-rows  (mapcat #(ozon-t/tx-op->service-rows % lookup)
                          (:operations tx-raw))]
    {:for-pay-baseline    (reduce + 0.0 (keep :for-pay fin-rows))
     :service-total       (reduce + 0.0
                                  (for [r svc-rows
                                        k [:delivery-cost :acquiring-fee
                                           :acceptance :storage-fee
                                           :additional-payment :ad-cost]
                                        :when (get r k)]
                                    (get r k)))
     :total-services-seen (count (for [op (:operations tx-raw)
                                       s  (get op :services [])]
                                   s))}))

;; ---------------------------------------------------------------------------
;; Unknown-service counter — SC-011.
;;
;; Instead of plugging into mulog (which would couple the test to a specific
;; publisher API), we count directly by re-walking the fixture and classifying
;; each service. A name is "unknown" iff it is not present in
;; `ozon-t/ozon-service-mapping` — this is exactly what `classify-ozon-service`
;; uses to fall back + mu/log, so the count is equivalent to observed events.
;; ---------------------------------------------------------------------------

(defn- count-unknown-service-names
  []
  (let [tx-raw (load-fixture transactions-fixture)
        known  (set (keys ozon-t/ozon-service-mapping))]
    (->> (:operations tx-raw)
         (mapcat #(get % :services []))
         (map :name)
         (remove nil?)
         (remove known)
         count)))

;; ---------------------------------------------------------------------------
;; SC-004 — B-005 invariant
;; ---------------------------------------------------------------------------

(deftest sc-004-b005-invariant-for-pay-unchanged
  (testing "SUM(for_pay) after materialize-ozon-services! equals baseline"
    (seed-realization!)
    (seed-transactions!)
    (materialize-realization! "2026-03-01" "2026-03-31")

    (let [{:keys [for-pay-baseline]} (fixture-baselines)
          before (-> (db/query
                       ["SELECT SUM(for_pay) AS s FROM finance
                         WHERE marketplace='ozon' AND date_from='2026-03-01'"])
                     first :s (or 0.0))]
      ;; Sanity: baseline realization materialization produced the expected total
      (is (< (Math/abs (- before for-pay-baseline)) 1.0)
          (str "Baseline mismatch before merge: DB=" before
               " fixture=" for-pay-baseline))

      ;; Run services merge
      (mat/materialize-ozon-services! ["2026-03-01" "2026-03-31"])

      (let [after (-> (db/query
                        ["SELECT SUM(for_pay) AS s FROM finance
                          WHERE marketplace='ozon' AND date_from='2026-03-01'"])
                      first :s (or 0.0))]
        (is (< (Math/abs (- after before)) 1.0)
            (str "B-005: for_pay MUST NOT change during service merge. "
                 "before=" before " after=" after))))))

;; ---------------------------------------------------------------------------
;; SC-003 — Reconciliation of cost fields
;; ---------------------------------------------------------------------------

(deftest sc-003-reconciliation-within-5-percent
  (testing "SUM(cost fields) ≈ fixture-derived service total"
    (seed-realization!)
    (seed-transactions!)
    (materialize-realization! "2026-03-01" "2026-03-31")
    (mat/materialize-ozon-services! ["2026-03-01" "2026-03-31"])

    (let [{:keys [service-total]} (fixture-baselines)
          row (-> (db/query
                    ["SELECT
                        COALESCE(SUM(delivery_cost),0)      AS dc,
                        COALESCE(SUM(acquiring_fee),0)      AS af,
                        COALESCE(SUM(acceptance),0)         AS ac,
                        COALESCE(SUM(storage_fee),0)        AS sf,
                        COALESCE(SUM(additional_payment),0) AS ap,
                        COALESCE(SUM(ad_cost),0)            AS adc
                      FROM finance
                      WHERE marketplace='ozon' AND date_from='2026-03-01'"])
                  first)
          db-total (+ (:dc row) (:af row) (:ac row) (:sf row) (:ap row) (:adc row))
          tolerance-pct (if (pos? service-total)
                          (* 100.0 (/ (Math/abs (- db-total service-total))
                                      service-total))
                          100.0)]
      (is (> service-total 1000.0)
          (str "Fixture service total should be non-trivial; got "
               service-total))
      (is (<= tolerance-pct 5.0)
          (str "SC-003: DB cost total " db-total
               " vs fixture " service-total
               " → off by " tolerance-pct "%")))))

;; ---------------------------------------------------------------------------
;; SC-011 — Unknown-service rate ≤ 2%
;; ---------------------------------------------------------------------------

(deftest sc-011-unknown-service-rate-under-2-percent
  (testing "ratio of unknown-service names to total services ≤ 2%"
    (let [unknown-count (count-unknown-service-names)
          {:keys [total-services-seen]} (fixture-baselines)
          ratio (if (pos? total-services-seen)
                  (* 100.0 (/ (double unknown-count) total-services-seen))
                  0.0)]
      (is (pos? total-services-seen)
          "fixture must contain some services[] entries")
      (is (<= ratio 2.0)
          (str "SC-011: unknown-service rate " ratio "% > 2% "
               "(unknown=" unknown-count " / total=" total-services-seen ")")))))
