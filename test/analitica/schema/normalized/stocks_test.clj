(ns analitica.schema.normalized.stocks-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.schema.normalized.stocks :as sut]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [analitica.test-helpers :as th]))

(defn- strip-ns [m]
  (into {} (map (fn [[k v]] [(keyword (name k)) v]) m)))

(def minimal-row
  {:article "ABC-1" :marketplace :wb})

(deftest minimal-row-validates
  (is (sut/valid? minimal-row)))

(deftest missing-article-rejected
  (is (not (sut/valid? (dissoc minimal-row :article)))))

(deftest production-rows-conform
  (testing "Every stocks row passes StocksRow"
    (if-let [ds (th/db-or-skip)]
      (let [rows (jdbc/execute! ds ["SELECT * FROM stocks LIMIT 1000"]
                                {:builder-fn rs/as-kebab-maps})
            rows (->> rows
                      (map strip-ns)
                      (map #(update % :marketplace (fn [v] (some-> v keyword)))))
            {:keys [ok bad]} (sut/validate-rows rows)]
        (is (empty? bad)
            (str "Non-conforming: " (count bad) " / "
                 (+ (count ok) (count bad))
                 "\nFirst 3 errors: " (vec (take 3 bad)))))
      (is true "skipped — no DB configured"))))
