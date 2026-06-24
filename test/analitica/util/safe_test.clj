(ns analitica.util.safe-test
  (:require [clojure.test :refer [deftest is]]
            [analitica.util.safe :as safe]))

(deftest safely-returns-value-on-success
  (is (= 42 (safe/safely* (fn [] 42) :fallback ::ctx))))

(deftest safely-returns-fallback-and-reports-on-throw
  (let [calls (atom [])]
    (with-redefs [safe/report-error! (fn [ctx t] (swap! calls conj [ctx (.getMessage t)]))]
      (is (= :fb (safe/safely* (fn [] (throw (ex-info "boom" {}))) :fb ::test-ctx))))
    (is (= [::test-ctx "boom"] (first @calls)))))

(deftest safely-macro-expands-to-thunk
  (let [calls (atom 0)]
    (with-redefs [safe/report-error! (fn [_ _] (swap! calls inc))]
      (is (= :fb (safe/safely (throw (ex-info "x" {})) :fb ::m)))
      (is (= 99 (safe/safely 99 :fb ::m))))
    (is (= 1 @calls))))

(deftest report-error-runs-without-throwing
  ;; exercise the REAL report-error! (no redef) against a real Throwable to catch
  ;; key-name / stacktrace-method typos. mu/log with no started publisher is a no-op
  ;; that returns nil — the point is it must not throw.
  (is (nil? (safe/report-error! ::ctx (ex-info "boom" {})))))
