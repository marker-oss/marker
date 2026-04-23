(ns analitica.web.report-schemas-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.report-schemas :as rs]))

(deftest schema-registry-test
  (testing "schema exists for :ue"
    (is (some? (rs/get-schema :ue))))

  (testing ":ue schema has required top-level keys"
    (let [s (rs/get-schema :ue)]
      (is (= :ue (:report-type s)))
      (is (string? (:title s)))
      (is (boolean? (:uses-period? s)))
      (is (boolean? (:supports-compare? s)))
      (is (#{:per-article :none} (:rows-mode s)))
      (is (vector? (:tabs s)))
      (is (vector? (:columns s)))
      (is (vector? (:kpi s)))))

  (testing ":ue columns have canonical metadata"
    (let [cols (:columns (rs/get-schema :ue))
          article-col (first (filter #(= :article (:key %)) cols))
          margin-col (first (filter #(= :margin-pct (:key %)) cols))]
      (is (= "Артикул" (:title article-col)))
      (is (= :identity (:group article-col)))
      (is (= :pct (:format margin-col)))
      (is (= "UE.7" (:canon-anchor margin-col))))))

(deftest ue-presets-reference-valid-columns-test
  (testing "каждый ключ в column-presets :per-unit/:percentages существует в :columns
            (не даёт silent-fail при рендере презета)"
    (let [schema (rs/get-schema :ue)
          col-keys (set (map :key (:columns schema)))
          check-preset (fn [preset-key]
                         (let [preset (get-in schema [:column-presets preset-key])]
                           (when (vector? preset)
                             (doseq [k preset]
                               (is (contains? col-keys k)
                                   (str "preset " preset-key " references missing column " k))))))]
      (check-preset :basic)
      (check-preset :per-unit)
      (check-preset :percentages))))
