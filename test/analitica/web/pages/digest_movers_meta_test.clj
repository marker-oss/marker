(ns analitica.web.pages.digest-movers-meta-test
  "Bug #5: top-movers/top-fallers buttons need data-nm-id to drive the
   SKU-sheet click-delegation in resources/public/js/sku-sheet.js. The
   nm-id was being discarded by compute-movers-fallers."
  (:require [clojure.test :refer [deftest is testing]]
            [hiccup.core :refer [html]]
            [analitica.web.pages.digest :as digest]))

(defn- render-html [hiccup-vec]
  (html hiccup-vec))

(deftest top-movers-table-emits-nm-id-attribute
  (let [out (render-html
              (digest/top-movers-table
                [{:article "A1" :name "Widget" :revenue 1000.0
                  :delta-pct 12.5 :nm-id 12345}]))]
    (is (re-find #"data-nm-id=\"12345\"" out)
        "the SKU button must carry nm-id, not an empty string")))

(deftest top-fallers-table-emits-nm-id-attribute
  (let [out (render-html
              (digest/top-fallers-table
                [{:article "A1" :name "Widget" :revenue 800.0
                  :delta-pct -25.0 :nm-id 67890}]))]
    (is (re-find #"data-nm-id=\"67890\"" out))))

(deftest compute-movers-fallers-preserves-nm-id
  (let [curr [{:group "A1" :revenue 1500.0 :nm-id 12345 :subject "Widget"}]
        prev [{:group "A1" :revenue 1000.0 :nm-id 12345 :subject "Widget"}]
        out  (#'digest/compute-movers-fallers curr prev)]
    (is (= 12345 (:nm-id (first out))))
    (is (= "Widget" (:name (first out))))))
