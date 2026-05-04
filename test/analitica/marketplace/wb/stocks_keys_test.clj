(ns analitica.marketplace.wb.stocks-keys-test
  "B5: WB ->stock used :in-way-to-client / :in-way-from-client which do
   not match the L1 schema names :in-way-to / :in-way-from. Sync wrote
   the data to DB correctly via a parallel reader, but no domain code
   could rely on the canonical key names — and the Malli schema would
   silently drop the values (closed=false) if validation were enabled.

   These tests pin the canonical-name contract across all 3 transforms."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.marketplace.wb.transform :as wb]
            [analitica.marketplace.ozon.transform :as ozon]
            [analitica.marketplace.ym.transform :as ym]
            [analitica.schema.normalized.stocks :as stocks-schema]))

(deftest wb-stock-uses-canonical-in-way-keys
  (let [raw   {:supplierArticle "ART"
               :nmId            12345
               :barcode         "BC1"
               :techSize        "M"
               :subject         "shirt"
               :category        "cat"
               :brand           "brand"
               :warehouseName   "WH-1"
               :quantity        10
               :quantityFull    18
               :inWayToClient   5
               :inWayFromClient 3}
        stock (wb/->stock raw)]
    (testing ":in-way-to populated from raw :inWayToClient"
      (is (= 5 (:in-way-to stock))))
    (testing ":in-way-from populated from raw :inWayFromClient"
      (is (= 3 (:in-way-from stock))))
    (testing "legacy non-canonical keys must not be present"
      (is (not (contains? stock :in-way-to-client))
          "Found :in-way-to-client — schema dropped this key, must be removed.")
      (is (not (contains? stock :in-way-from-client))
          "Found :in-way-from-client — schema dropped this key, must be removed."))
    (testing "row passes L1 schema validation"
      (let [{:keys [bad]} (stocks-schema/validate-rows [stock])]
        (is (empty? bad)
            (str "Schema validation should pass. Bad rows: " bad))))))

(deftest ozon-stock-uses-canonical-in-way-keys
  ;; Ozon transform sets in-way fields to nil (Ozon API does not
  ;; surface in-transit per analytics_data path). Still must use
  ;; canonical key names so cross-MP consumers don't break.
  (let [raw   {:item_code            "OZ-ART"
               :sku                  98765
               :warehouse_name       "OZ-WH"
               :free_to_sell_amount  10
               :reserved_amount      2
               :promised_amount      1}
        stock (first (ozon/->stocks [raw]))]
    (testing "Ozon stock has canonical :in-way-to / :in-way-from keys (nil ok)"
      (is (contains? stock :in-way-to))
      (is (contains? stock :in-way-from)))
    (testing "Ozon stock must not carry legacy keys"
      (is (not (contains? stock :in-way-to-client)))
      (is (not (contains? stock :in-way-from-client))))
    (testing "Ozon row passes schema validation"
      (let [{:keys [bad]} (stocks-schema/validate-rows [stock])]
        (is (empty? bad) (str "Bad rows: " bad))))))

(deftest ym-stock-uses-canonical-in-way-keys
  (let [stocks (ym/->stocks
                 [{:warehouseId 42
                   :offers      [{:offerId "YM-ART"
                                  :stocks  [{:type "AVAILABLE" :count 7}
                                            {:type "DEFECT"    :count 1}]}]}])
        stock  (first stocks)]
    (testing "YM stock has canonical :in-way-to / :in-way-from keys (nil ok)"
      (is (contains? stock :in-way-to))
      (is (contains? stock :in-way-from)))
    (testing "YM stock must not carry legacy keys"
      (is (not (contains? stock :in-way-to-client)))
      (is (not (contains? stock :in-way-from-client))))
    (testing "YM row passes schema validation"
      (let [{:keys [bad]} (stocks-schema/validate-rows [stock])]
        (is (empty? bad) (str "Bad rows: " bad))))))
