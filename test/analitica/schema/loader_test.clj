(ns analitica.schema.loader-test
  "Tests for analitica.schema.loader — EDN parsing + meta-validation."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [analitica.schema.loader :as l]
            [analitica.schema.registry :as r])
  (:import [java.io File]))

(defn- temp-edn!
  "Write `content` (a string) to a unique temp .edn file, return the File."
  [content]
  (let [f (File/createTempFile "schema-loader-test-" ".edn")]
    (spit f content)
    (.deleteOnExit f)
    f))

(defn- valid-contract-edn
  ([] (valid-contract-edn {}))
  ([overrides]
   (pr-str
    (merge {:endpoint/id          :wb/sample
            :endpoint/marketplace :wb
            :endpoint/api-path    "/sample"
            :endpoint/method      :get
            :contract/source      {:kind :manual :generated-at "2026-04-22"}
            :contract/response-schema [:map [:x :int]]
            :contract/version     1}
           overrides))))

(defn- with-clean-registry [f]
  (r/clear!)
  (try (f) (finally (r/clear!))))

(use-fixtures :each with-clean-registry)

(deftest load-edn-file-happy-path
  (let [f (temp-edn! (valid-contract-edn))
        c (l/load-edn-file f)]
    (is (= :wb/sample (:endpoint/id c)))
    (is (= 1 (:contract/version c)))))

(deftest load-edn-file-rejects-non-map
  (let [f (temp-edn! "[:not :a :map]")]
    (is (thrown? clojure.lang.ExceptionInfo (l/load-edn-file f)))))

(deftest load-edn-file-rejects-missing-required-keys
  (let [f (temp-edn! (pr-str {:endpoint/marketplace :wb
                              :contract/source {:kind :manual :generated-at "x"}
                              :contract/response-schema [:map]
                              :contract/version 1}))]
    (is (thrown? clojure.lang.ExceptionInfo (l/load-edn-file f)))))

(deftest load-edn-file-rejects-unknown-marketplace
  (let [f (temp-edn! (valid-contract-edn {:endpoint/marketplace :wildcard}))]
    (is (thrown? clojure.lang.ExceptionInfo (l/load-edn-file f)))))

(deftest load-edn-file-rejects-invalid-malli-schema
  ;; [:map "not a valid child form"] — malli will reject this at compile
  (let [f (temp-edn! (valid-contract-edn {:contract/response-schema [:map "garbage"]}))]
    (is (thrown? clojure.lang.ExceptionInfo (l/load-edn-file f)))))

(deftest load-edn-file-upstream-source-requires-url-and-path
  (let [bad-source {:contract/source {:kind :upstream-openapi
                                       :generated-at "2026-04-22"}}
        f (temp-edn! (valid-contract-edn bad-source))]
    (is (thrown? clojure.lang.ExceptionInfo (l/load-edn-file f)))))

(deftest load-edn-file-accepts-upstream-source-with-required-fields
  (let [good-source {:contract/source {:kind :upstream-openapi
                                        :url "https://example.com/spec.yaml"
                                        :path "$.paths./x.get"
                                        :generated-at "2026-04-22"}}
        f (temp-edn! (valid-contract-edn good-source))]
    (is (= :wb/sample (:endpoint/id (l/load-edn-file f))))))

(deftest load-edn-file-inferred-source-requires-positive-sample-count
  (let [bad-source {:contract/source {:kind :inferred
                                       :generated-at "2026-04-22"
                                       :sample-count 0}}
        f (temp-edn! (valid-contract-edn bad-source))]
    (is (thrown? clojure.lang.ExceptionInfo (l/load-edn-file f)))))

(deftest load-all-registers-every-valid-file-in-resources
  ;; Integration: reads the live resources/schemas/ directory.
  ;; This test tolerates any number >= 0 of valid files; it mainly
  ;; asserts that load-all! doesn't throw on a clean directory.
  (r/clear!)
  (let [{:keys [loaded errors]} (l/load-all!)]
    (is (int? loaded))
    (is (coll? errors))
    ;; After load, registry size should equal loaded count.
    (is (= loaded (count (r/all-endpoints))))))
