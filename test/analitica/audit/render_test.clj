(ns analitica.audit.render-test
  "Tests for analitica.audit.report rendering (T015 / T024).

   Renderer requirements:
     - render-stdout : Report map + top-n → string with header + counts + top-causes
     - write-edn!    : Report map + path → file; round-trips through edn/read-string
     - Deterministic: two renders of same Report (modulo :captured-at) produce
       identical strings."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [analitica.audit.report :as rep])
  (:import [java.io File]))

(def ^:private period {:from "2026-03-01" :to "2026-03-31"})
(def ^:private default-t {:rel 0.01 :abs 10.0})

(defn- disc [rule-id class abs unit & {:keys [operation article]}]
  (rep/make-discrepancy
    {:rule-id rule-id
     :marketplace :wb
     :period period
     :location (cond-> {}
                 article   (assoc :article article)
                 operation (assoc :operation operation))
     :delta {:a 0 :b 0 :abs abs :rel 0.01 :unit unit}
     :classification class
     :classification-reason (cond (= class :expected) "rounding"
                                  (= class :unclassified) "unknown op"
                                  :else nil)}))

(defn- build-report [discs]
  (rep/make-report
    {:marketplace :wb
     :period period
     :rules-applied [:aggregate-vs-raw :bank-delta :sales-qty-triangle]
     :sources-available [:raw-finance :agg-finance :sales :orders]
     :discrepancies discs
     :tolerance-snapshot default-t
     :captured-at "2026-04-21T12:00:00Z"
     :top-n 10}))

;; ---------------------------------------------------------------------------
;; render-stdout — content expectations
;; ---------------------------------------------------------------------------

(deftest render-stdout-contains-header-fields
  (let [r (build-report [])
        s (rep/render-stdout r 10)]
    (is (string? s))
    (is (re-find #"Reconciliation report" s))
    (is (re-find #"Period:\s*2026-03-01\s*\.\.\s*2026-03-31" s))
    (is (re-find #"Marketplace:\s*wb" s))
    (is (re-find #"Counts" s))))

(deftest render-stdout-includes-counts
  (let [r (build-report [(disc :r :suspicious 100.0 :rub)
                         (disc :r :expected 1.0 :rub)])
        s (rep/render-stdout r 10)]
    (is (re-find #"suspicious=1" s))
    (is (re-find #"expected=1" s))))

(deftest render-stdout-top-n-truncation
  (let [discs (mapv (fn [i] (disc (keyword (str "rule-" i))
                                  :suspicious (* 1.0 i) :rub))
                    (range 1 16))
        r (build-report discs)
        s (rep/render-stdout r 5)
        ;; count how many distinct rule-N lines are in the Top-causes section
        matches (re-seq #"\d+\. rule-\d+" s)]
    ;; Only top-5 rules should appear in the top-causes section
    (is (= 5 (count matches))
        (str "expected exactly 5 top-causes entries, got: " (vec matches)))
    (is (re-find #"rule-15" s) "biggest rule must be present")))

;; ---------------------------------------------------------------------------
;; Determinism — same report → identical render string
;; ---------------------------------------------------------------------------

(deftest render-stdout-is-deterministic
  (let [r (build-report [(disc :r :suspicious 100.0 :rub :article "A")])
        s1 (rep/render-stdout r 10)
        s2 (rep/render-stdout r 10)]
    (is (= s1 s2))))

(deftest render-stdout-ignores-captured-at
  (let [r1 (rep/make-report {:marketplace :wb :period period
                             :rules-applied [:a] :sources-available [:raw-finance]
                             :discrepancies [] :tolerance-snapshot default-t
                             :captured-at "2026-04-21T12:00:00Z"})
        r2 (rep/make-report {:marketplace :wb :period period
                             :rules-applied [:a] :sources-available [:raw-finance]
                             :discrepancies [] :tolerance-snapshot default-t
                             :captured-at "2099-01-01T00:00:00Z"})
        ;; Strip the Captured-at line completely (case-insensitive line match)
        strip (fn [s] (clojure.string/replace s #"(?m)^Captured-at:.*\n" ""))
        s1 (strip (rep/render-stdout r1 10))
        s2 (strip (rep/render-stdout r2 10))]
    (is (= s1 s2)
        "captured-at is informational; modulo that line, render is byte-identical")))

;; ---------------------------------------------------------------------------
;; write-edn! and round-trip
;; ---------------------------------------------------------------------------

(deftest write-edn-round-trips
  (let [r (build-report [(disc :r :suspicious 100.0 :rub :article "A")
                         (disc :r2 :unclassified 1.0 :count :operation "новое")])
        f (File/createTempFile "audit-report-" ".edn")
        _ (.deleteOnExit f)
        path (.getCanonicalPath f)]
    (rep/write-edn! r path)
    (let [s (slurp path)
          read-back (edn/read-string s)]
      (is (= (:report/id r) (:report/id read-back)))
      (is (= (count (:report/discrepancies r))
             (count (:report/discrepancies read-back))))
      (is (= (:report/marketplace r) (:report/marketplace read-back)))
      (is (= (:report/period r) (:report/period read-back))))))

;; ---------------------------------------------------------------------------
;; Empty period edge case
;; ---------------------------------------------------------------------------

(deftest render-empty-report-does-not-crash
  (let [r (rep/make-report {:marketplace :wb :period period
                            :rules-applied []
                            :sources-available []
                            :discrepancies []
                            :tolerance-snapshot default-t
                            :captured-at "2026-04-21T12:00:00Z"})
        s (rep/render-stdout r 10)]
    (is (string? s))
    (is (re-find #"No data" s))))
