(ns analitica.schema.normalized.sales-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.schema.normalized.sales :as sut]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [analitica.test-helpers :as th]))

(defn- strip-ns [m]
  (into {} (map (fn [[k v]] [(keyword (name k)) v]) m)))

(def minimal-sale
  {:sale-id "S-0001"
   :date    "2026-03-15"
   :article "ABC-123"
   :type    :sale
   :marketplace :wb})

(deftest minimal-row-validates
  (is (sut/valid? minimal-sale))
  (is (nil? (sut/explain minimal-sale))))

(deftest unknown-type-rejected
  (let [row (assoc minimal-sale :type :foobar)]
    (is (not (sut/valid? row)))))

(deftest missing-required-rejected
  (doseq [k [:sale-id :date :article :type :marketplace]]
    (let [row (dissoc minimal-sale k)]
      (is (not (sut/valid? row)) (str "should reject missing " k)))))

(deftest production-rows-conform
  (testing "Every sales row passes SalesRow"
    (if-let [ds (th/db-or-skip)]
      (let [rows (jdbc/execute! ds ["SELECT * FROM sales LIMIT 1000"]
                                {:builder-fn rs/as-kebab-maps})
            rows (->> rows
                      (map strip-ns)
                      (map #(-> %
                                (update :marketplace (fn [v] (some-> v keyword)))
                                (update :type (fn [v] (some-> v keyword))))))]
        (let [{:keys [ok bad]} (sut/validate-rows rows)]
          (is (empty? bad)
              (str "Non-conforming: " (count bad) " / "
                   (+ (count ok) (count bad))
                   "\nFirst 3 errors: " (vec (take 3 bad))))))
      (do (println "  (skipped — no ANALITICA_TEST_DB / ANALITICA_DB set)")
          (is true "skipped — no DB configured")))))
