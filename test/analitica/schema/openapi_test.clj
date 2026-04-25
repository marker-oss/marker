(ns analitica.schema.openapi-test
  "Tests for OpenAPI → Malli converter."
  (:require [clojure.test :refer [deftest testing is]]
            [analitica.schema.openapi :as oa]
            [malli.core :as m]))

(defn- convert [openapi-node]
  (:schema (oa/->malli openapi-node)))

(defn- warnings-of [openapi-node]
  (:warnings (oa/->malli openapi-node)))

;; ---------------------------------------------------------------------------
;; Primitives
;; ---------------------------------------------------------------------------

(deftest primitive-integer
  (is (= :int (convert {:type "integer"}))))

(deftest primitive-string
  (is (= :string (convert {:type "string"}))))

(deftest primitive-boolean
  (is (= :boolean (convert {:type "boolean"}))))

(deftest primitive-number-default
  (is (= 'number? (convert {:type "number"}))))

(deftest primitive-number-double-format
  (is (= :double (convert {:type "number" :format "double"}))))

(deftest primitive-nullable-wraps-maybe
  (is (= [:maybe :int] (convert {:type "integer" :nullable true}))))

;; ---------------------------------------------------------------------------
;; Object
;; ---------------------------------------------------------------------------

(deftest object-with-required-and-optional
  (let [schema (convert
                 {:type "object"
                  :properties {:x {:type "integer"}
                               :y {:type "string"}}
                  :required ["x"]})]
    (is (m/validate schema {:x 42}))
    (is (m/validate schema {:x 42 :y "abc"}))
    (is (not (m/validate schema {:y "abc"})))
    (is (not (m/validate schema {:x "not-int"})))))

(deftest object-nullable-wraps-maybe
  (let [schema (convert
                 {:type "object"
                  :properties {:x {:type "integer"}}
                  :nullable true})]
    (is (= :maybe (first schema)))))

(deftest object-empty-properties-falls-back-to-map
  (is (= :map (convert {:type "object"}))))

;; ---------------------------------------------------------------------------
;; Array
;; ---------------------------------------------------------------------------

(deftest array-of-objects
  (let [schema (convert
                 {:type "array"
                  :items {:type "object"
                          :properties {:x {:type "integer"}}
                          :required ["x"]}})]
    (is (m/validate schema [{:x 1} {:x 2}]))
    (is (not (m/validate schema [{:x "bad"}])))))

(deftest array-without-items-is-any
  (let [schema (convert {:type "array"})]
    (is (m/validate schema [1 "two" :three]))))

;; ---------------------------------------------------------------------------
;; Enum
;; ---------------------------------------------------------------------------

(deftest enum-basic
  (let [schema (convert {:enum ["red" "green" "blue"]})]
    (is (m/validate schema "red"))
    (is (not (m/validate schema "purple")))))

;; ---------------------------------------------------------------------------
;; $ref resolution
;; ---------------------------------------------------------------------------

(deftest ref-resolves-components-schemas
  (let [root {:components
              {:schemas
               {:MyInt {:type "integer"}}}}
        schema (:schema
                 (oa/->malli {:$ref "#/components/schemas/MyInt"} root))]
    (is (= :int schema))))

(deftest ref-resolves-nested-within-properties
  (let [root {:components
              {:schemas
               {:Item {:type "object"
                       :properties {:id {:type "integer"}}
                       :required ["id"]}}}}
        schema (:schema
                 (oa/->malli
                  {:type "object"
                   :properties {:item {:$ref "#/components/schemas/Item"}}
                   :required ["item"]}
                  root))]
    (is (m/validate schema {:item {:id 5}}))
    (is (not (m/validate schema {:item {:id "not-int"}})))))

;; ---------------------------------------------------------------------------
;; Unsupported constructs — warnings, no throw
;; ---------------------------------------------------------------------------

(deftest oneof-produces-warning-and-any-fallback
  (let [{:keys [schema warnings]}
        (oa/->malli {:oneOf [{:type "integer"} {:type "string"}]})]
    (is (= :any schema))
    (is (= 1 (count warnings)))
    (is (= :unsupported-composition (:context (first warnings))))
    (is (= :oneOf (get-in (first warnings) [:detail :kind])))))

(deftest unresolved-ref-produces-warning
  (let [{:keys [schema warnings]}
        (oa/->malli {:$ref "#/components/schemas/Missing"} {})]
    (is (= :any schema))
    (is (= :unresolved-ref (:context (first warnings))))))

;; ---------------------------------------------------------------------------
;; Spec loading & path resolution
;; ---------------------------------------------------------------------------

(deftest parse-spec-handles-json
  (let [text "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"demo\"}}"
        parsed (oa/parse-spec text)]
    (is (= "3.0.0" (:openapi parsed)))))

(deftest parse-spec-handles-yaml
  (let [text "openapi: 3.0.0\ninfo:\n  title: demo"
        parsed (oa/parse-spec text)]
    (is (= "3.0.0" (:openapi parsed)))))

(deftest spec-at-path-navigates
  (let [doc {:paths {(keyword "/x") {:get {:responses {:200 "result"}}}}}]
    (is (= "result" (oa/spec-at-path doc "$.paths./x.get.responses.200")))))
