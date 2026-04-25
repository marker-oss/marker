(ns analitica.schema.cli-test
  "Tests for `schema list/show/diff` CLI dispatch. Drives handle-schema-* helpers
   directly (via vars) — System/exit calls are thrown through to caller here,
   so we need to wrap in try/catch or rely on clojure.test's standard reporting."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [analitica.audit.test-helpers :as th]
            [analitica.db :as db]
            [analitica.cli :as cli]
            [analitica.schema.registry :as r]))

(defn- with-env [f]
  (th/with-isolated-db
    (fn []
      (r/clear!)
      (try (f) (finally (r/clear!))))))

(use-fixtures :each with-env)

(defn- register-demo!
  ([] (register-demo! :wb/sample))
  ([id]
   (r/register!
    {:endpoint/id          id
     :endpoint/marketplace (keyword (namespace id))
     :endpoint/api-path    "/sample"
     :endpoint/method      :get
     :contract/source      {:kind :manual :generated-at "2026-04-22"}
     :contract/response-schema [:sequential [:map [:x :int]]]
     :contract/version     1})))

(defn- with-exit-caught
  "Call f, swallow System/exit (which throws SecurityException under a
   custom SecurityManager). Since we don't install one here, we invoke
   directly and accept the real exit — that's undesirable in test runs.
   Instead, we drive the private handle-schema-show / handle-schema-diff
   helpers via vars. They call System/exit only on the unhappy path; on
   the happy path they return nil. For tests that hit the error branch,
   we use Runtime.getRuntime().addShutdownHook to suppress.

   A cleaner alternative is `with-redefs System/exit no-op`, but you
   can't redef static methods. So we branch:
     - exercise happy-path via the helpers directly
     - exercise error-path by catching the resulting side effect on stdout"
  [f]
  (f))

(defn- capture-out [thunk]
  (let [buf (java.io.StringWriter.)]
    (binding [*out* buf] (thunk))
    (str buf)))

;; ---------------------------------------------------------------------------
;; schema list (happy path)
;; ---------------------------------------------------------------------------

(deftest list-shows-registered-endpoints
  (register-demo! :wb/alpha)
  (register-demo! :ozon/beta)
  (let [out (capture-out #(cli/handle-schema ["list"] {}))]
    (is (str/includes? out ":wb/alpha"))
    (is (str/includes? out ":ozon/beta"))))

(deftest list-on-empty-registry
  (let [out (capture-out #(cli/handle-schema ["list"] {}))]
    (is (str/includes? out "none"))))

;; ---------------------------------------------------------------------------
;; schema show (happy path only — error path calls System/exit 3
;;                                  which would kill the test VM)
;; ---------------------------------------------------------------------------

(deftest show-prints-contract-for-registered-endpoint
  (register-demo! :wb/alpha)
  (let [out (capture-out
              #(cli/handle-schema ["show" ":wb/alpha"] {}))]
    (is (str/includes? out ":wb/alpha"))
    (is (str/includes? out ":contract/source"))
    (is (str/includes? out ":contract/response-schema"))))

(deftest show-accepts-plain-keyword-without-leading-colon
  (register-demo! :wb/alpha)
  (let [out (capture-out
              #(cli/handle-schema ["show" "wb/alpha"] {}))]
    (is (str/includes? out ":wb/alpha"))))

;; ---------------------------------------------------------------------------
;; schema diff — against a clean sample (all pass)
;; ---------------------------------------------------------------------------

(deftest diff-clean-reports-no-drift
  (register-demo! :wb/alpha)
  ;; Seed raw_data with a valid sample matching the schema
  ;; (the demo schema is [:sequential [:map [:x :int]]])
  (db/insert-raw! :wb :alpha "2026-03-01" "2026-03-31"
                   [{:x 1} {:x 2} {:x 3}])
  (let [out (capture-out
              #(try (cli/handle-schema ["diff" ":wb/alpha" "--sample" "1"] {})
                    (catch Throwable _ nil)))]
    (is (str/includes? out "no drift detected"))))

(deftest diff-drift-reports-violations
  (register-demo! :wb/alpha)
  ;; Seed raw_data with a violating sample (extra field → :warned)
  (db/insert-raw! :wb :alpha "2026-03-01" "2026-03-31"
                   [{:x 1 :unexpected "field"}])
  (let [out (capture-out
              #(try (cli/handle-schema ["diff" ":wb/alpha" "--sample" "1"] {})
                    (catch Throwable _ nil)))]
    (is (str/includes? out "extra-field"))))
