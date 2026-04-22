(ns analitica.audit.fixture-test
  "Tests for analitica.audit.fixture — ground-truth fixtures (US4).

   Covers:
     T043 — capture-fixture!: writes EDN with deterministic sha256-of-rows,
            shape matches data-model §GroundTruthFixture, :expected.pnl keys
            match keys from (pnl/calculate ...).
     T044 — verify-fixture!: captured fixture + unchanged DB → :match;
            captured fixture + stubbed (different) formula → :diff with list
            of mismatching keys."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [analitica.audit.test-helpers :as th]
            [analitica.audit.fixture :as fix]
            [analitica.domain.pnl :as pnl])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Fixtures: isolated DB + per-test temp fixture directory
;; ---------------------------------------------------------------------------

(def ^:dynamic *tmp-dir* nil)

(defn- with-tmp-fixtures-dir [f]
  (let [dir (File. (str "test-fixtures-" (System/currentTimeMillis) "-" (rand-int 99999)))]
    (.mkdirs dir)
    (try
      (binding [*tmp-dir* (.getAbsolutePath dir)]
        (f))
      (finally
        (doseq [^File file (reverse (file-seq dir))]
          (when (.exists file) (.delete file)))))))

(use-fixtures :each th/with-isolated-db with-tmp-fixtures-dir)

(def ^:private period {:from "2026-03-01" :to "2026-03-31"})

;; ---------------------------------------------------------------------------
;; T043 — capture-fixture!
;; ---------------------------------------------------------------------------

(deftest capture-fixture-writes-edn-to-disk
  (testing "capture-fixture! writes an EDN file at <dir>/<id>.edn"
    (th/insert-finance!
      [(th/finance-row :article "A" :retail-amount 1000.0 :for-pay 850.0)
       (th/finance-row :article "B" :retail-amount 2000.0 :for-pay 1700.0)])
    (fix/capture-fixture! {:id "wb-test-001"
                           :marketplace :wb
                           :period      period
                           :fixtures-dir *tmp-dir*})
    (let [expected-path (str *tmp-dir* File/separator "wb-test-001.edn")]
      (is (.exists (File. expected-path))
          (str "fixture EDN must be written to " expected-path)))))

(deftest capture-fixture-shape-matches-data-model
  (testing "captured fixture has the keys required by data-model §GroundTruthFixture"
    (th/insert-finance!
      [(th/finance-row :article "A" :retail-amount 1000.0 :for-pay 850.0)])
    (let [fixture (fix/capture-fixture! {:id "wb-shape"
                                         :marketplace :wb
                                         :period      period
                                         :fixtures-dir *tmp-dir*})]
      ;; Required top-level keys per data-model.md
      (is (= "wb-shape" (:fixture/id fixture)))
      (is (= :wb (:fixture/marketplace fixture)))
      (is (= period (:fixture/period fixture)))
      (is (string? (:fixture/captured-at fixture)))
      (is (map? (:fixture/source fixture)))
      (is (string? (:fixture/sha256-of-rows fixture)))
      (is (integer? (:fixture/row-count fixture)))
      (is (pos? (:fixture/row-count fixture)))
      (is (map? (:fixture/expected fixture)))
      (is (map? (:pnl (:fixture/expected fixture))))
      (is (map? (:unit-economics (:fixture/expected fixture))))
      (is (map? (:sales-qty (:fixture/expected fixture))))
      (is (number? (get-in fixture [:fixture/expected :sales-qty :total]))))))

(deftest capture-fixture-sha256-is-deterministic
  (testing "Two captures over the same DB rows produce the same sha256-of-rows"
    (th/insert-finance!
      [(th/finance-row :article "A" :retail-amount 1000.0)
       (th/finance-row :article "B" :retail-amount 2000.0)
       (th/finance-row :article "C" :retail-amount 3000.0)])
    (let [f1 (fix/capture-fixture! {:id "det-1"
                                    :marketplace :wb
                                    :period      period
                                    :fixtures-dir *tmp-dir*})
          f2 (fix/capture-fixture! {:id "det-2"
                                    :marketplace :wb
                                    :period      period
                                    :fixtures-dir *tmp-dir*})]
      (is (= (:fixture/sha256-of-rows f1) (:fixture/sha256-of-rows f2))
          "sha256-of-rows must be identical across two captures of the same DB state"))))

(deftest capture-fixture-sha256-changes-when-rows-change
  (testing "Inserting a new row changes sha256-of-rows"
    (th/insert-finance! [(th/finance-row :article "A" :retail-amount 1000.0)])
    (let [f1 (fix/capture-fixture! {:id "before"
                                    :marketplace :wb
                                    :period      period
                                    :fixtures-dir *tmp-dir*})]
      (th/insert-finance! [(th/finance-row :article "B" :retail-amount 2000.0)])
      (let [f2 (fix/capture-fixture! {:id "after"
                                      :marketplace :wb
                                      :period      period
                                      :fixtures-dir *tmp-dir*})]
        (is (not= (:fixture/sha256-of-rows f1) (:fixture/sha256-of-rows f2))
            "sha256 must change when the finance rows change")))))

(deftest capture-fixture-expected-pnl-keys-match-pnl-calculate
  (testing ":fixture/expected.pnl keys equal (keys (pnl/calculate ...))"
    (th/insert-finance!
      [(th/finance-row :article "A" :retail-amount 1000.0 :for-pay 850.0)
       (th/finance-row :article "B" :retail-amount 2000.0 :for-pay 1700.0)])
    (let [fixture       (fix/capture-fixture! {:id "keys-match"
                                               :marketplace :wb
                                               :period      period
                                               :fixtures-dir *tmp-dir*})
          finance-rows  (analitica.domain.finance/fetch-finance period
                                                                :marketplace :wb
                                                                :source :db)
          pnl-live      (pnl/calculate finance-rows :marketplace :wb)
          fixture-keys  (set (keys (get-in fixture [:fixture/expected :pnl])))
          live-keys     (set (keys pnl-live))]
      (is (= fixture-keys live-keys)
          (str "fixture expected.pnl keys must equal pnl/calculate keys. "
               "missing-in-fixture=" (vec (sort (clojure.set/difference live-keys fixture-keys)))
               " extra-in-fixture=" (vec (sort (clojure.set/difference fixture-keys live-keys))))))))

(deftest capture-fixture-row-count-matches-finance-query
  (testing "row-count equals the number of finance rows queried"
    (th/insert-finance!
      [(th/finance-row :article "A")
       (th/finance-row :article "B")
       (th/finance-row :article "C")])
    (let [fixture (fix/capture-fixture! {:id "count"
                                         :marketplace :wb
                                         :period      period
                                         :fixtures-dir *tmp-dir*})]
      (is (= 3 (:fixture/row-count fixture))))))

(deftest capture-fixture-attaches-from-report-when-provided
  (testing ":from-report option populates :fixture/source.report-id"
    (th/insert-finance! [(th/finance-row :article "A")])
    (let [report-id "test-report-abc123"
          fixture   (fix/capture-fixture! {:id "with-report"
                                           :marketplace :wb
                                           :period      period
                                           :from-report report-id
                                           :fixtures-dir *tmp-dir*})]
      (is (= report-id (get-in fixture [:fixture/source :report-id]))))))

(deftest capture-fixture-without-from-report-marks-direct
  (testing "without :from-report, source.report-id is :direct"
    (th/insert-finance! [(th/finance-row :article "A")])
    (let [fixture (fix/capture-fixture! {:id "direct"
                                         :marketplace :wb
                                         :period      period
                                         :fixtures-dir *tmp-dir*})]
      (is (= :direct (get-in fixture [:fixture/source :report-id]))))))

(deftest capture-fixture-edn-round-trips
  (testing "The written EDN file deserialises back into the same map"
    (th/insert-finance! [(th/finance-row :article "A" :retail-amount 1000.0)])
    (let [captured (fix/capture-fixture! {:id "edn-roundtrip"
                                          :marketplace :wb
                                          :period      period
                                          :fixtures-dir *tmp-dir*})
          path     (str *tmp-dir* File/separator "edn-roundtrip.edn")
          read-back (with-open [r (java.io.PushbackReader. (io/reader path))]
                      (edn/read r))]
      (is (= (:fixture/id captured) (:fixture/id read-back)))
      (is (= (:fixture/marketplace captured) (:fixture/marketplace read-back)))
      (is (= (:fixture/sha256-of-rows captured) (:fixture/sha256-of-rows read-back)))
      (is (= (:fixture/row-count captured) (:fixture/row-count read-back)))
      (is (= (keys (get-in captured [:fixture/expected :pnl]))
             (keys (get-in read-back [:fixture/expected :pnl])))))))

(deftest capture-fixture-validates-inputs
  (testing "Missing required keys throw ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (fix/capture-fixture! {:marketplace :wb
                                        :period      period
                                        :fixtures-dir *tmp-dir*}))
        "missing :id must throw")
    (is (thrown? clojure.lang.ExceptionInfo
                 (fix/capture-fixture! {:id "x"
                                        :period period
                                        :fixtures-dir *tmp-dir*}))
        "missing :marketplace must throw")
    (is (thrown? clojure.lang.ExceptionInfo
                 (fix/capture-fixture! {:id "x"
                                        :marketplace :wb
                                        :fixtures-dir *tmp-dir*}))
        "missing :period must throw")))

;; ---------------------------------------------------------------------------
;; T044 — verify-fixture!
;; ---------------------------------------------------------------------------

(deftest verify-fixture-matches-when-db-unchanged
  (testing "Capturing then verifying against the same DB returns :match"
    (th/insert-finance!
      [(th/finance-row :article "A" :retail-amount 1000.0 :for-pay 850.0)
       (th/finance-row :article "B" :retail-amount 2000.0 :for-pay 1700.0)])
    (fix/capture-fixture! {:id "match-test"
                           :marketplace :wb
                           :period      period
                           :fixtures-dir *tmp-dir*})
    (let [result (fix/verify-fixture! "match-test" :fixtures-dir *tmp-dir*)]
      (is (= :match (:verdict result))
          (str "expected :match, got " (pr-str result))))))

(deftest verify-fixture-reports-diff-when-pnl-formula-changes
  (testing "Changed pnl/calculate output (simulated via with-redefs) → :diff"
    (th/insert-finance!
      [(th/finance-row :article "A" :retail-amount 1000.0 :for-pay 850.0)])
    (fix/capture-fixture! {:id "diff-test"
                           :marketplace :wb
                           :period      period
                           :fixtures-dir *tmp-dir*})
    ;; Stub pnl/calculate to return inflated revenue — simulates a formula bug.
    (let [original pnl/calculate
          stubbed  (fn [finance-data & opts]
                     (let [orig-res (apply original finance-data opts)]
                       (update orig-res :revenue + 10000.0)))
          result   (with-redefs [pnl/calculate stubbed]
                     (fix/verify-fixture! "diff-test" :fixtures-dir *tmp-dir*))]
      (is (= :diff (:verdict result))
          (str "expected :diff when pnl formula differs, got " (pr-str result)))
      (is (contains? (:pnl-diff result) :revenue)
          "pnl-diff must list :revenue as mismatched")
      (let [revenue-diff (get-in result [:pnl-diff :revenue])]
        (is (some? revenue-diff)
            "revenue diff map must be present")
        (is (contains? revenue-diff :expected))
        (is (contains? revenue-diff :actual))
        (is (not= (:expected revenue-diff) (:actual revenue-diff))
            "expected and actual must differ — that's the whole point of the diff")))))

(deftest verify-fixture-returns-sha-mismatch-when-db-drifts
  (testing "Insert a new finance row after capture → :sha-mismatch (NOT :diff)"
    (th/insert-finance! [(th/finance-row :article "A" :retail-amount 1000.0)])
    (fix/capture-fixture! {:id "drift-test"
                           :marketplace :wb
                           :period      period
                           :fixtures-dir *tmp-dir*})
    ;; Mutate DB — the hash no longer matches.
    (th/insert-finance! [(th/finance-row :article "B" :retail-amount 9999.0)])
    (let [result (fix/verify-fixture! "drift-test" :fixtures-dir *tmp-dir*)]
      (is (= :sha-mismatch (:verdict result))
          (str "expected :sha-mismatch, got " (pr-str result)))
      (is (string? (:fixture-sha result)))
      (is (string? (:current-sha result)))
      (is (not= (:fixture-sha result) (:current-sha result))))))

(deftest verify-fixture-returns-not-found-for-missing-id
  (testing "Unknown fixture id → :not-found"
    (let [result (fix/verify-fixture! "does-not-exist" :fixtures-dir *tmp-dir*)]
      (is (= :not-found (:verdict result))))))

;; ---------------------------------------------------------------------------
;; list-fixtures
;; ---------------------------------------------------------------------------

(deftest list-fixtures-returns-empty-for-empty-dir
  (testing "Empty fixtures directory → empty vector"
    (is (= [] (fix/list-fixtures :fixtures-dir *tmp-dir*)))))

(deftest list-fixtures-omits-expected-to-keep-listing-compact
  (testing "Listing drops :fixture/expected (operator listing should stay fast)"
    (th/insert-finance! [(th/finance-row :article "A" :retail-amount 1000.0)])
    (fix/capture-fixture! {:id "listing-test"
                           :marketplace :wb
                           :period      period
                           :fixtures-dir *tmp-dir*})
    (let [rows (fix/list-fixtures :fixtures-dir *tmp-dir*)]
      (is (= 1 (count rows)))
      (let [row (first rows)]
        (is (= "listing-test" (:fixture/id row)))
        (is (= :wb (:fixture/marketplace row)))
        (is (nil? (:fixture/expected row))
            "expected payload must be stripped from list output")
        (is (string? (:fixture/path row)))))))

(deftest list-fixtures-returns-sorted-by-id
  (testing "Results ordered by :fixture/id alphabetically"
    (th/insert-finance! [(th/finance-row :article "A")])
    (doseq [id ["zeta" "alpha" "beta"]]
      (fix/capture-fixture! {:id id
                             :marketplace :wb
                             :period      period
                             :fixtures-dir *tmp-dir*}))
    (let [ids (mapv :fixture/id (fix/list-fixtures :fixtures-dir *tmp-dir*))]
      (is (= ["alpha" "beta" "zeta"] ids)))))

(deftest list-fixtures-handles-missing-directory
  (testing "Non-existent fixtures-dir returns empty vector, no crash"
    (is (= [] (fix/list-fixtures :fixtures-dir "/tmp/does-not-exist-abc-xyz-42")))))
