(ns analitica.web.what-if-sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]))

(defn- read-js []
  (slurp (io/resource "public/js/what-if.js")))

(deftest js-file-loadable
  (testing "what-if.js exists on the classpath"
    (is (string? (read-js)))))

(deftest js-references-canonical-formula-comment
  (testing "JS contains a reference to canonical Clojure formula path"
    (is (re-find #"(?iu)unit_economics|unit-economics"
                 (read-js)))))

(deftest js-exports-net-profit-function
  (testing "Tripwire — JS must export a function named netProfit"
    (let [js (read-js)]
      (is (re-find #"netProfit\s*=" js)
          "JS must export a function named netProfit"))))
