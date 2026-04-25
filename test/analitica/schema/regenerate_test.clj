(ns analitica.schema.regenerate-test
  "Tests for the regenerate pipeline. HTTP is mocked via with-redefs on
   `analitica.schema.regenerate/fetch-spec`, so no network access is
   required."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.schema.registry :as r]
            [analitica.schema.regenerate :as rg]))

(defn- with-clean-registry [f]
  (r/clear!)
  (try (f) (finally (r/clear!))))

(use-fixtures :each with-clean-registry)

(def ^:private upstream-contract
  {:endpoint/id          :wb/upstream-sample
   :endpoint/marketplace :wb
   :endpoint/api-path    "/api/v5/sample"
   :endpoint/method      :get
   :contract/source      {:kind :upstream-openapi
                           :url  "https://example.invalid/openapi.yaml"
                           :path "$.paths./api/v5/sample.get.responses.200.content.application/json.schema"
                           :generated-at "2026-04-22"}
   :contract/response-schema
   [:sequential [:map [:x :int]]]
   :contract/version 1})

(def ^:private manual-contract
  {:endpoint/id          :ozon/manual-sample
   :endpoint/marketplace :ozon
   :endpoint/api-path    "/v1/sample"
   :endpoint/method      :post
   :contract/source      {:kind :manual :generated-at "2026-04-22"}
   :contract/response-schema [:map [:y :string]]
   :contract/version 1})

(defn- openapi-body-with-fields
  "Build a minimal valid OpenAPI YAML body whose response.200 matches the
   upstream-contract :path and contains the given field keywords as
   required integer properties."
  [fields]
  (let [req-list (str "[" (str/join ", "
                                     (map #(str "\"" (name %) "\"") fields)) "]")]
    (str
      "openapi: 3.0.0\n"
      "paths:\n"
      "  /api/v5/sample:\n"
      "    get:\n"
      "      responses:\n"
      "        '200':\n"
      "          content:\n"
      "            application/json:\n"
      "              schema:\n"
      "                type: array\n"
      "                items:\n"
      "                  type: object\n"
      "                  required: " req-list "\n"
      "                  properties:\n"
      (apply str
        (for [f fields]
          (str "                    " (name f) ":\n"
               "                      type: integer\n"))))))

;; ---------------------------------------------------------------------------
;; Skipping manual contracts
;; ---------------------------------------------------------------------------

(deftest skips-manual-contracts
  (r/register! manual-contract)
  (let [results (rg/regenerate-all!
                 {:fetch-fn (fn [_] (throw (ex-info "should not be called" {})))})]
    (is (= [:skipped] (mapv :status results)))))

;; ---------------------------------------------------------------------------
;; No-change diff
;; ---------------------------------------------------------------------------

(deftest no-change-when-fields-match
  (r/register! upstream-contract)
  (let [body    (openapi-body-with-fields [:x])
        results (rg/regenerate-all!
                 {:fetch-fn (fn [_] body)})]
    (is (= 1 (count results)))
    (let [r (first results)]
      (is (= :no-changes (:status r))
          (str "expected :no-changes, got: " (pr-str r))))))

;; ---------------------------------------------------------------------------
;; Additions detected
;; ---------------------------------------------------------------------------

(deftest added-field-reported-as-change
  (r/register! upstream-contract)
  (let [body    (openapi-body-with-fields [:x :y])
        results (rg/regenerate-all!
                 {:fetch-fn (fn [_] body)})
        r       (first results)]
    (is (= :changed (:status r)))
    (is (= [:y] (:added (:diff r))))
    (is (= [] (:removed (:diff r))))))

;; ---------------------------------------------------------------------------
;; Removals detected
;; ---------------------------------------------------------------------------

(deftest removed-field-reported-as-change
  (r/register!
    (assoc upstream-contract
           :contract/response-schema
           [:sequential [:map [:x :int] [:dropped :int]]]))
  (let [body    (openapi-body-with-fields [:x])
        results (rg/regenerate-all!
                 {:fetch-fn (fn [_] body)})
        r       (first results)]
    (is (= :changed (:status r)))
    (is (= [] (:added (:diff r))))
    (is (= [:dropped] (:removed (:diff r))))))

;; ---------------------------------------------------------------------------
;; Marketplace filter
;; ---------------------------------------------------------------------------

(deftest marketplace-filter-restricts-targets
  (r/register! upstream-contract)                 ;; :wb
  (r/register! (assoc upstream-contract
                      :endpoint/id :ym/other
                      :endpoint/marketplace :ym))
  (let [body (openapi-body-with-fields [:x])
        wb-only (rg/regenerate-all!
                 {:marketplace :wb
                  :fetch-fn (fn [_] body)})]
    (is (= 1 (count wb-only)))
    (is (= :wb/upstream-sample (:endpoint-id (first wb-only))))))

;; ---------------------------------------------------------------------------
;; Error handling
;; ---------------------------------------------------------------------------

(deftest fetch-failure-yields-error-status
  (r/register! upstream-contract)
  (let [results (rg/regenerate-all!
                 {:fetch-fn (fn [_] (throw (ex-info "network down" {})))})]
    (is (= :error (:status (first results))))
    (is (.contains ^String (:reason (first results)) "network down"))))
