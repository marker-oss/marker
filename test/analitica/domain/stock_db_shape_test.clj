(ns analitica.domain.stock-db-shape-test
  "Bug #7: stock/by-article and stock/totals only knew the API-shape keys
   (:in-way-to-client / :in-way-from-client) emitted by marketplace
   transforms before insert. After insert the rows come back from db/query
   with the canonical kebab keys :in-way-to / :in-way-from (mirroring DB
   columns in_way_to / in_way_from), and the aggregate functions silently
   summed nil → 0. Stock reports surfaced 0 for `В пути` regardless of
   actual transit. These tests pin the dual-key fallback."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.stock :as stock]))

(def ^:private db-rows
  "Two warehouse rows for the same article, in DB shape."
  [{:article "A1" :warehouse "MSK"
    :quantity 10 :quantity-full 12 :in-way-to 3 :in-way-from 1}
   {:article "A1" :warehouse "SPB"
    :quantity 5  :quantity-full 5  :in-way-to 2 :in-way-from 0}])

(def ^:private api-rows
  "Same payload but in API shape (pre-insert WB transform)."
  [{:article "A1" :warehouse "MSK"
    :quantity 10 :quantity-full 12 :in-way-to-client 3 :in-way-from-client 1}
   {:article "A1" :warehouse "SPB"
    :quantity 5  :quantity-full 5  :in-way-to-client 2 :in-way-from-client 0}])

(deftest by-article-aggregates-db-shape-in-way-fields
  (testing "DB rows expose :in-way-to / :in-way-from — by-article must sum them"
    (let [out (first (stock/by-article db-rows))]
      (is (= 5 (:in-way-to   out)))
      (is (= 1 (:in-way-from out))))))

(deftest by-article-still-aggregates-api-shape
  (testing "Backwards compatibility: API-shape keys keep working"
    (let [out (first (stock/by-article api-rows))]
      (is (= 5 (:in-way-to   out)))
      (is (= 1 (:in-way-from out))))))

(deftest totals-aggregates-db-shape-in-way-fields
  (testing "totals reads the same key fallback as by-article"
    (let [t (stock/totals db-rows)]
      (is (= 5 (:total-to-client   t)))
      (is (= 1 (:total-from-client t))))))

(deftest totals-still-aggregates-api-shape
  (let [t (stock/totals api-rows)]
    (is (= 5 (:total-to-client   t)))
    (is (= 1 (:total-from-client t)))))
