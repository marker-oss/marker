(ns analitica.web.components.pulse.stub-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.components.pulse.hypotheses :as hyp]
            [analitica.web.components.pulse.custom     :as cust]))

(defn- hiccup-text [v]
  (clojure.string/join " "
    (filter string? (tree-seq coll? seq v))))

(deftest hypotheses-stub-renders
  (let [v (hyp/render {})]
    (is (vector? v))
    (is (re-find #"(?iu)гипотез" (hiccup-text v)))
    (is (re-find #"(?iu)скоро|coming soon|готовим" (hiccup-text v)))))

(deftest custom-stub-renders
  (let [v (cust/render {})]
    (is (vector? v))
    (is (re-find #"(?iu)кастом|custom" (hiccup-text v)))))

(deftest stubs-survive-nil-input
  (is (vector? (hyp/render nil)))
  (is (vector? (cust/render nil))))
