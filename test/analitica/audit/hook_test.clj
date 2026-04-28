(ns analitica.audit.hook-test
  "Behaviour of the post-materialize audit hook (E-6)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [analitica.audit.hook :as hook]
            [analitica.audit.core :as audit]))

(def ^:private clean-report
  {:report/summary       {:counts {:expected 3 :suspicious 0 :unclassified 0}}
   :report/discrepancies []})

(def ^:private flagged-report
  {:report/summary
   {:counts {:expected 2 :suspicious 1 :unclassified 0}}
   :report/discrepancies
   [{:disc/rule-id           :wb-finance-vs-sales-events
     :disc/classification    :suspicious
     :disc/classification-reason "skew 132k₽"}
    {:disc/rule-id           :sales-qty-triangle
     :disc/classification    :expected}]})

(defn- with-stub-reconcile [report f]
  (let [calls (atom [])]
    (with-redefs [audit/run-reconcile!
                  (fn [args]
                    (swap! calls conj args)
                    {:report report :exit-code 0})]
      (f calls))))

;; ---------------------------------------------------------------------------
;; Skip cases
;; ---------------------------------------------------------------------------

(deftest skips-non-finance-entity-types
  (testing "Hook is no-op for entity types that don't feed finance audit"
    (let [run? (atom false)]
      (with-redefs [audit/run-reconcile! (fn [_] (reset! run? true) {})]
        (doseq [et [:sales :orders :stocks :prices :regions :cashflow nil]]
          (is (nil? (hook/audit-after-materialize!
                      {:entity-type et
                       :period      {:from "2026-04-01" :to "2026-04-30"}
                       :marketplace :wb}))))
        (is (false? @run?) "run-reconcile! must not be called")))))

(deftest skips-when-period-missing-or-keyword
  (testing "Hook is no-op when period isn't a concrete range"
    (let [run? (atom false)]
      (with-redefs [audit/run-reconcile! (fn [_] (reset! run? true) {})]
        (is (nil? (hook/audit-after-materialize!
                    {:entity-type :finance :period nil :marketplace :wb})))
        (is (nil? (hook/audit-after-materialize!
                    {:entity-type :finance :period :last-30-days :marketplace :wb})))
        (is (nil? (hook/audit-after-materialize!
                    {:entity-type :finance :period {:from "2026-04-01"} :marketplace :wb})))
        (is (nil? (hook/audit-after-materialize!
                    {:entity-type :finance :period ["2026-04-01"] :marketplace :wb})))
        (is (false? @run?))))))

(deftest accepts-vector-period-from-web-sync
  (testing "[from to] vector is normalized to {:from :to} map for the audit run"
    (with-stub-reconcile clean-report
      (fn [calls]
        (with-out-str
          (hook/audit-after-materialize!
            {:entity-type :finance
             :period      ["2026-04-01" "2026-04-30"]
             :marketplace :wb}))
        (is (= 1 (count @calls)))
        (is (= {:from "2026-04-01" :to "2026-04-30"}
               (-> @calls first :period))
            "vector period normalized to map before reconcile")))))

;; ---------------------------------------------------------------------------
;; Happy path
;; ---------------------------------------------------------------------------

(deftest fires-for-finance-with-valid-period
  (testing "Finance materialize with concrete period triggers reconcile"
    (with-stub-reconcile clean-report
      (fn [calls]
        (let [out (with-out-str
                    (hook/audit-after-materialize!
                      {:entity-type :finance
                       :period      {:from "2026-04-01" :to "2026-04-30"}
                       :marketplace :ozon}))]
          (is (= 1 (count @calls)))
          (is (= :ozon (-> @calls first :marketplace)))
          (is (= {:from "2026-04-01" :to "2026-04-30"}
                 (-> @calls first :period)))
          (is (str/includes? out "Audit (post-materialize)"))
          (is (str/includes? out "ozon"))
          (is (str/includes? out "expected:")))))))

(deftest fires-for-all-entity-type
  (testing "Entity type :all also triggers reconcile (sweep mode)"
    (with-stub-reconcile clean-report
      (fn [calls]
        (with-out-str
          (hook/audit-after-materialize!
            {:entity-type :all
             :period      {:from "2026-04-01" :to "2026-04-30"}
             :marketplace :all}))
        (is (= 1 (count @calls)))
        (is (= :all (-> @calls first :marketplace)))))))

(deftest digest-prints-suspicious-findings
  (testing "Suspicious discrepancies appear in the digest"
    (with-stub-reconcile flagged-report
      (fn [_]
        (let [out (with-out-str
                    (hook/audit-after-materialize!
                      {:entity-type :finance
                       :period      {:from "2026-04-01" :to "2026-04-30"}
                       :marketplace :wb}))]
          (is (str/includes? out "wb-finance-vs-sales-events"))
          (is (str/includes? out "suspicious"))
          (is (str/includes? out "skew 132k₽"))
          (is (str/includes? out "Findings (non-:expected)")))))))

(deftest digest-omits-findings-block-on-clean-run
  (testing "No findings → no Findings header"
    (with-stub-reconcile clean-report
      (fn [_]
        (let [out (with-out-str
                    (hook/audit-after-materialize!
                      {:entity-type :finance
                       :period      {:from "2026-04-01" :to "2026-04-30"}
                       :marketplace :wb}))]
          (is (not (str/includes? out "Findings"))))))))

;; ---------------------------------------------------------------------------
;; Failure mode
;; ---------------------------------------------------------------------------

(deftest never-throws-when-reconcile-fails
  (testing "Reconcile exception is caught, warning printed to *err*, returns nil"
    (with-redefs [audit/run-reconcile!
                  (fn [_] (throw (ex-info "boom" {:reason :db-down})))]
      (let [err (java.io.StringWriter.)]
        (binding [*err* err]
          (is (nil? (hook/audit-after-materialize!
                      {:entity-type :finance
                       :period      {:from "2026-04-01" :to "2026-04-30"}
                       :marketplace :wb}))
              "hook must return nil even when reconcile throws"))
        (is (str/includes? (str err) "post-materialize audit failed"))
        (is (str/includes? (str err) "boom"))))))

(deftest defaults-marketplace-to-all
  (testing "Missing :marketplace defaults to :all"
    (with-stub-reconcile clean-report
      (fn [calls]
        (with-out-str
          (hook/audit-after-materialize!
            {:entity-type :finance
             :period      {:from "2026-04-01" :to "2026-04-30"}}))
        (is (= :all (-> @calls first :marketplace)))))))
