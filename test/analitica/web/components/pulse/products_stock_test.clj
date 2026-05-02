(ns analitica.web.components.pulse.products-stock-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.components.pulse.products-stock :as ps]))

(defn- text [v]
  (clojure.string/join " "
    (filter string? (tree-seq coll? seq v))))

(def ^:private data
  {:oos-skus 12
   :turnover-days 28.5
   :return-pct 4.2})

(deftest renders-three-metrics
  (let [t (text (ps/render data))]
    (is (re-find #"OOS|без остатков|нет в наличии" t))
    (is (re-find #"оборачиваемост" t))
    (is (re-find #"возврат" t))))

(deftest renders-localization-hint-card
  (let [t (text (ps/render data))]
    (is (re-find #"(?iu)локализаци" t))
    (is (re-find #"70" t) "70% threshold mentioned")))

(deftest empty-state
  (let [t (text (ps/render {}))]
    (is (re-find #"(?iu)нет данных|—" t))
    (is (re-find #"(?iu)локализаци" t))))

(deftest survives-nil
  (is (vector? (ps/render nil))))
