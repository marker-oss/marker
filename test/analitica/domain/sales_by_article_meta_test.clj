(ns analitica.domain.sales-by-article-meta-test
  "Bug #5: sales/by-article must expose :nm-id and :subject so the
   homepage Top-movers/Top-fallers tables can wire SKU-sheet links."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.sales :as sales]))

(def ^:private rows
  [{:article "A1" :nm-id 12345 :subject "Widget"
    :type :sale  :for-pay 1000.0 :date "2026-05-01"}
   {:article "A1" :nm-id 12345 :subject "Widget"
    :type :sale  :for-pay 1500.0 :date "2026-05-02"}
   {:article "B2" :nm-id 67890 :subject "Gadget"
    :type :sale  :for-pay 500.0  :date "2026-05-01"}])

(deftest by-article-includes-nm-id-and-subject
  (let [out (sales/by-article rows)
        a1  (first (filter #(= "A1" (:group %)) out))
        b2  (first (filter #(= "B2" (:group %)) out))]
    (is (= 12345 (:nm-id a1)))
    (is (= "Widget" (:subject a1)))
    (is (= 67890 (:nm-id b2)))
    (is (= "Gadget" (:subject b2)))))

(deftest by-article-tolerates-missing-nm-id
  (testing "Rows without nm-id (e.g. Ozon) leave :nm-id nil — no crash"
    (let [out (sales/by-article [{:article "X" :type :sale :for-pay 100 :date "2026-05-01"}])]
      (is (nil? (:nm-id (first out)))))))
