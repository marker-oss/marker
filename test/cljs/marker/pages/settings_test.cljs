(ns marker.pages.settings-test
  (:require [cljs.test :refer [deftest is]]
            [marker.pages.settings :as settings]))

(deftest current-masked-reads-value
  (let [data {"mp.wb.api-token" {:value "••••1234" :secret? true}}]
    (is (= "••••1234" (settings/current-masked data "mp.wb.api-token")))
    (is (nil? (settings/current-masked data "mp.ozon.api-key")))))

(deftest mp-specs-cover-three-marketplaces
  (is (= #{:wb :ozon :ym} (set (map :mp settings/mp-specs)))))
