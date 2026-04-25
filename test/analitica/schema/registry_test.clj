(ns analitica.schema.registry-test
  "Tests for analitica.schema.registry — register/lookup/clear contract."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.schema.registry :as r]))

(defn- with-clean-registry [f]
  (r/clear!)
  (try (f) (finally (r/clear!))))

(use-fixtures :each with-clean-registry)

(def ^:private wb-contract
  {:endpoint/id          :wb/sample
   :endpoint/marketplace :wb
   :endpoint/api-path    "/sample"
   :endpoint/method      :get
   :contract/source      {:kind :manual :generated-at "2026-04-22"}
   :contract/response-schema [:map [:x :int]]
   :contract/version     1})

(def ^:private ozon-contract
  (assoc wb-contract
         :endpoint/id :ozon/sample
         :endpoint/marketplace :ozon))

(deftest register-adds-contract
  (r/register! wb-contract)
  (is (= wb-contract (r/lookup :wb/sample))))

(deftest lookup-missing-returns-nil
  (is (nil? (r/lookup :does-not/exist))))

(deftest register-replaces-existing
  (r/register! wb-contract)
  (r/register! (assoc wb-contract :contract/version 2))
  (is (= 2 (:contract/version (r/lookup :wb/sample)))))

(deftest register-requires-id
  (is (thrown? clojure.lang.ExceptionInfo
               (r/register! (dissoc wb-contract :endpoint/id)))))

(deftest all-endpoints-is-sorted
  (r/register! (assoc wb-contract :endpoint/id :zzz/last))
  (r/register! (assoc wb-contract :endpoint/id :aaa/first))
  (r/register! (assoc wb-contract :endpoint/id :mmm/middle))
  (is (= [:aaa/first :mmm/middle :zzz/last]
         (mapv :endpoint/id (r/all-endpoints)))))

(deftest by-marketplace-filters
  (r/register! wb-contract)
  (r/register! ozon-contract)
  (is (= [:wb/sample]   (mapv :endpoint/id (r/by-marketplace :wb))))
  (is (= [:ozon/sample] (mapv :endpoint/id (r/by-marketplace :ozon))))
  (is (= []             (mapv :endpoint/id (r/by-marketplace :ym)))))

(deftest clear-empties-registry
  (r/register! wb-contract)
  (r/register! ozon-contract)
  (r/clear!)
  (is (= [] (r/all-endpoints))))
