(ns marker.pages.settings-test
  (:require [cljs.test :refer [deftest is]]
            [marker.pages.settings :as settings]))

(deftest current-masked-reads-value
  (let [data {"mp.wb.api-token" {:value "••••1234" :secret? true}}]
    (is (= "••••1234" (settings/current-masked data "mp.wb.api-token")))
    (is (nil? (settings/current-masked data "mp.ozon.api-key")))))

(deftest mp-specs-cover-three-marketplaces
  (is (= #{:wb :ozon :ym} (set (map :mp settings/mp-specs)))))

(deftest wb-first-field-setting-key
  (let [wb-spec  (first (filter #(= :wb (:mp %)) settings/mp-specs))
        first-fk (:setting-key (first (:fields wb-spec)))]
    (is (= "mp.wb.api-token" first-fk))))
