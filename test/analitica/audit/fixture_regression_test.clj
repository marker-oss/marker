(ns analitica.audit.fixture-regression-test
  "Regression test for captured ground-truth fixtures (T045, US4).

   Semantics:
     - Reads every `*.edn` from `specs/002-calculation-audit/fixtures/`.
     - For each: if `:fixture/sha256-of-rows` matches current DB state, run
       `pnl/calculate` + `unit_economics/calculate`, compare with
       `:fixture/expected` — assert match (within the tolerance baked into
       `fixture/verify-fixture!`).
     - If the SHA doesn't match → SKIP with explicit note (db drifted, expected
       values may be stale; NOT a failure).
     - If the fixtures directory is missing or empty → SKIP the test entirely
       (non-failing — important for CI environments without captured fixtures).

   Rationale: fixtures are operator-captured artefacts; their presence should
   never block a normal test run. When present, they guard against formula
   regressions; when absent, the test is a no-op."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [analitica.audit.fixture :as fix])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- fixtures-in-dir
  "Return the vector of fixture EDN files in `fix/default-fixtures-dir`,
   or nil if the directory is absent."
  []
  (let [dir (io/file fix/default-fixtures-dir)]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter (fn [^File f]
                     (and (.isFile f)
                          (.endsWith (.getName f) ".edn"))))
           vec))))

;; ---------------------------------------------------------------------------
;; Regression
;; ---------------------------------------------------------------------------

(deftest captured-fixtures-replay-cleanly
  (testing "All captured fixtures under specs/002-.../fixtures/ replay without regression"
    (let [files (fixtures-in-dir)]
      (cond
        (nil? files)
        (do (println "[fixture-regression] fixtures directory absent — SKIP (no captured fixtures to verify)")
            (is true "no fixtures directory → test skipped (non-failing)"))

        (empty? files)
        (do (println "[fixture-regression] fixtures directory empty — SKIP (no captured fixtures to verify)")
            (is true "empty fixtures directory → test skipped (non-failing)"))

        :else
        (doseq [^File f files]
          (let [fixture-id (clojure.string/replace (.getName f) #"\.edn$" "")]
            (testing (str "fixture " fixture-id)
              (let [result (try
                             (fix/verify-fixture! fixture-id)
                             (catch Throwable t
                               {:verdict :error :error t}))]
                (case (:verdict result)
                  :match
                  (is true (str fixture-id " matches current DB — no regression"))

                  :sha-mismatch
                  (do (println (format "[fixture-regression] %s SHA drift — SKIP (fixture=%s current=%s)"
                                       fixture-id
                                       (subs (or (:fixture-sha result) "") 0 (min 8 (count (or (:fixture-sha result) ""))))
                                       (subs (or (:current-sha result) "") 0 (min 8 (count (or (:current-sha result) ""))))))
                      (is true (str fixture-id " SHA drift — regression skipped (DB changed since capture)")))

                  :diff
                  (is false
                      (str fixture-id " regression DETECTED:\n"
                           "  pnl-diff: "   (pr-str (:pnl-diff result))   "\n"
                           "  ue-diff:  "   (pr-str (:ue-diff result))    "\n"
                           "  sales-diff: " (pr-str (:sales-qty-diff result))))

                  :not-found
                  (is false (str fixture-id " not found at " (:path result)))

                  :error
                  (is false (str fixture-id " verify threw: " (.getMessage ^Throwable (:error result))))

                  (is false (str fixture-id " unexpected verdict: " (pr-str result))))))))))))
