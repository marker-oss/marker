(ns analitica.web.api.detail-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.api.detail :as detail]))

(deftest article-detail-ue-test
  (testing "returns map with :article :kpi :breakdown keys for :ue"
    (let [result (detail/article-detail :ue "TEST-ART"
                   {:from "2026-04-01" :to "2026-04-30"})]
      (is (map? result))
      (is (contains? result :article))
      (is (contains? result :kpi))
      (is (contains? result :breakdown)))))

(deftest article-detail-unsupported-type-test
  (testing "unsupported report type returns empty kpi/breakdown"
    (let [result (detail/article-detail :sales "TEST-ART"
                   {:from "2026-04-01" :to "2026-04-30"})]
      (is (map? result))
      (is (= "TEST-ART" (:article result)))
      (is (= {} (:kpi result)))
      (is (= {} (:breakdown result))))))

(deftest article-detail-article-passthrough-test
  (testing "article is returned unchanged in result"
    (let [article "3467/белый"
          result (detail/article-detail :sales article
                   {:from "2026-04-01" :to "2026-04-30"})]
      (is (= article (:article result))))))
