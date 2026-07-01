(ns analitica.marketplace.wb.transform-test
  "FR-006 — WB saleID prefix classification.
   Contract: 'D...' => :refusal, 'S...' => :sale, 'R...' => :return, else => :other."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.marketplace.wb.transform :as wb]))

;; ---------------------------------------------------------------------------
;; §FR-006 saleid-fixture — saleID prefix classification
;; ---------------------------------------------------------------------------

(deftest saleid-d-classified-as-refusal
  (testing "saleID prefix 'D' is classified as :refusal"
    (let [row (wb/->sale {:saleID "D12345" :supplierArticle "X" :subject "test"
                          :forPay 0.0 :totalPrice 0 :discountPercent 0
                          :spp 0 :finishedPrice 0 :priceWithDisc 0
                          :warehouseName "" :regionName "" :oblastOkrugName ""
                          :date "" :lastChangeDate "" :nmId 0 :barcode "" :techSize ""
                          :IsStorno 0})]
      (is (= :refusal (:type row))
          "D-prefix saleID must be :refusal, not :other")))

  (testing "saleID prefix 'S' still classified as :sale (no regression)"
    (let [row (wb/->sale {:saleID "S99999" :supplierArticle "Y" :subject "test"
                          :forPay 100.0 :totalPrice 100 :discountPercent 0
                          :spp 0 :finishedPrice 100 :priceWithDisc 100
                          :warehouseName "" :regionName "" :oblastOkrugName ""
                          :date "" :lastChangeDate "" :nmId 0 :barcode "" :techSize ""
                          :IsStorno 0})]
      (is (= :sale (:type row))
          "S-prefix saleID must remain :sale")))

  (testing "saleID prefix 'R' still classified as :return (no regression)"
    (let [row (wb/->sale {:saleID "R77777" :supplierArticle "Z" :subject "test"
                          :forPay 100.0 :totalPrice 100 :discountPercent 0
                          :spp 0 :finishedPrice 100 :priceWithDisc 100
                          :warehouseName "" :regionName "" :oblastOkrugName ""
                          :date "" :lastChangeDate "" :nmId 0 :barcode "" :techSize ""
                          :IsStorno 0})]
      (is (= :return (:type row))
          "R-prefix saleID must remain :return")))

  (testing "saleID prefix 'X' falls to :other (no regression)"
    (let [row (wb/->sale {:saleID "X00001" :supplierArticle "W" :subject "test"
                          :forPay 0.0 :totalPrice 0 :discountPercent 0
                          :spp 0 :finishedPrice 0 :priceWithDisc 0
                          :warehouseName "" :regionName "" :oblastOkrugName ""
                          :date "" :lastChangeDate "" :nmId 0 :barcode "" :techSize ""
                          :IsStorno 0})]
      (is (= :other (:type row))
          "Unknown prefix must remain :other"))))
