(ns marker.pages.cost-prices-test
  "Unit tests for the pure helpers in marker.pages.cost-prices.
   No DOM / UIx rendering needed — only tests the data-transformation layer.
   Run via: shadow-cljs compile test"
  (:require [cljs.test :refer [deftest is testing]]
            [marker.pages.cost-prices :refer [parse-imports-payload]]))

;; ---------------------------------------------------------------------------
;; parse-imports-payload — JS body object → Clojure vector of maps
;; ---------------------------------------------------------------------------

(deftest parse-imports-payload-normal
  (testing "converts a typical API response to a vector of keyword-keyed maps"
    (let [row1 #js {:id 7 :imported-at "2026-04-01T10:00:00Z"
                    :source "csv1c" :loaded 120 :rejected 3
                    :filename "units.csv" :notes nil}
          row2 #js {:id 8 :imported-at "2026-04-02T11:00:00Z"
                    :source "csv1c" :loaded 80 :rejected 0
                    :filename "units2.csv" :notes "re-upload"}
          body #js {:imports #js [row1 row2]}
          result (parse-imports-payload body)]
      (is (vector? result)                        "result is a vector")
      (is (= 2 (count result))                    "two rows returned")
      (is (= 7 (:id (first result)))              "id field parsed")
      (is (= "csv1c" (:source (first result)))    "source field parsed")
      (is (= 120 (:loaded (first result)))        "loaded field parsed")
      (is (= 3 (:rejected (first result)))        "rejected field parsed")
      (is (= 8 (:id (second result)))             "second row id correct")
      (is (= "re-upload" (:notes (second result))) "notes field parsed"))))

(deftest parse-imports-payload-empty
  (testing "returns empty vector when imports array is empty"
    (let [result (parse-imports-payload #js {:imports #js []})]
      (is (vector? result)   "result is a vector")
      (is (= 0 (count result)) "no rows")))

  (testing "returns empty vector when imports key is missing entirely"
    (let [result (parse-imports-payload #js {})]
      (is (vector? result)     "result is a vector")
      (is (= 0 (count result)) "no rows when key absent"))))

(deftest parse-imports-payload-keyword-keys
  (testing "all keys in row maps are keywords, not strings"
    (let [row  #js {:id 1 :source "wb" :loaded 5 :rejected 0
                    :imported-at "2026-05-01T00:00:00Z"
                    :filename "f.csv" :notes ""}
          body #js {:imports #js [row]}
          m    (first (parse-imports-payload body))]
      (is (every? keyword? (keys m)) "all keys are keywords")
      (is (contains? m :id)           ":id key present")
      (is (contains? m :source)       ":source key present")
      (is (contains? m :loaded)       ":loaded key present"))))
