(ns analitica.marketplace.wb.transform
  "Transform raw WB API responses into the common domain model.")

;; ---------------------------------------------------------------------------
;; Orders
;; ---------------------------------------------------------------------------

(defn ->order [raw]
  {:marketplace    :wb
   :order-id       (str (:srid raw))
   :date           (:date raw)
   :last-change    (:lastChangeDate raw)
   :article        (:supplierArticle raw)
   :nm-id          (:nmId raw)
   :barcode        (:barcode raw)
   :tech-size      (:techSize raw)
   :subject        (:subject raw)
   :category       (:category raw)
   :brand          (:brand raw)
   :quantity       1
   :price          (:totalPrice raw)
   :discount       (:discountPercent raw)
   :price-with-disc (:priceWithDisc raw)
   :warehouse      (:warehouseName raw)
   :region         (:regionName raw)
   :province       (:oblastOkrugName raw)
   :is-cancel      (:isCancel raw)
   :cancel-date    (:cancelDate raw)
   :status         (if (:isCancel raw) :cancelled :active)})

(defn ->orders [raw-list]
  (mapv ->order raw-list))

;; ---------------------------------------------------------------------------
;; Sales
;; ---------------------------------------------------------------------------

(defn ->sale [raw]
  {:marketplace     :wb
   :sale-id         (str (:saleID raw))
   :date            (:date raw)
   :last-change     (:lastChangeDate raw)
   :article         (:supplierArticle raw)
   :nm-id           (:nmId raw)
   :barcode         (:barcode raw)
   :tech-size       (:techSize raw)
   :subject         (:subject raw)
   :category        (:category raw)
   :brand           (:brand raw)
   :quantity        (if (and (:saleID raw) (.startsWith (str (:saleID raw)) "R")) -1 1)
   :total-price     (:totalPrice raw)
   :discount        (:discountPercent raw)
   :spp             (:spp raw)
   :for-pay         (:forPay raw)
   :finished-price  (:finishedPrice raw)
   :price-with-disc (:priceWithDisc raw)
   :warehouse       (:warehouseName raw)
   :region          (:regionName raw)
   :province        (:oblastOkrugName raw)
   :is-return       (and (:saleID raw) (.startsWith (str (:saleID raw)) "R"))
   :is-storno       (= 1 (:IsStorno raw))
   :type            (cond
                      (and (:saleID raw) (.startsWith (str (:saleID raw)) "R")) :return
                      (and (:saleID raw) (.startsWith (str (:saleID raw)) "S")) :sale
                      :else :other)})

(defn ->sales [raw-list]
  (mapv ->sale raw-list))

;; ---------------------------------------------------------------------------
;; Stocks
;; ---------------------------------------------------------------------------

(defn ->stock [raw]
  {:marketplace  :wb
   :article      (:supplierArticle raw)
   :nm-id        (:nmId raw)
   :barcode      (:barcode raw)
   :tech-size    (:techSize raw)
   :subject      (:subject raw)
   :category     (:category raw)
   :brand        (:brand raw)
   :warehouse    (:warehouseName raw)
   :quantity     (:quantity raw)
   :in-way-to-client (:inWayToClient raw)
   :in-way-from-client (:inWayFromClient raw)
   :quantity-full (:quantityFull raw)
   :days-on-site (:daysOnSite raw)
   :last-change  (:lastChangeDate raw)})

(defn ->stocks [raw-list]
  (mapv ->stock raw-list))

;; ---------------------------------------------------------------------------
;; Finance report
;; ---------------------------------------------------------------------------

(defn ->finance-line [raw]
  {:marketplace       :wb
   :rrd-id            (:rrd_id raw)
   :report-id         (:realizationreport_id raw)
   :date-from         (:date_from raw)
   :date-to           (:date_to raw)
   :article           (:sa_name raw)
   :nm-id             (:nm_id raw)
   :barcode           (:barcode raw)
   :doc-type          (:doc_type_name raw)
   :quantity          (:quantity raw)
   :retail-price      (:retail_price raw)
   :retail-amount     (:retail_amount raw)
   :sale-percent      (:sale_percent raw)
   :commission-pct    (:commission_percent raw)
   :wb-commission     (:ppvz_sales_commission raw)  ;; комиссия WB (руб)
   :wb-reward         (:ppvz_reward raw)            ;; вознаграждение WB
   :wb-kvw-prc        (:ppvz_kvw_prc raw)           ;; % комиссии WB
   :spp-prc           (:ppvz_spp_prc raw)            ;; % СПП
   :operation         (case (:supplier_oper_name raw)
                        "Продажа" "sale"
                        "Возврат" "return"
                        (:supplier_oper_name raw))
   :price-with-disc   (:retail_price_withdisc_rub raw)
   :delivery-amount   (:delivery_amount raw)
   :return-amount     (:return_amount raw)
   :delivery-cost     (:delivery_rub raw)
   :for-pay           (:ppvz_for_pay raw)
   :penalty           (:penalty raw)
   :additional-payment (:additional_payment raw)
   :deduction         (:deduction raw)
   :storage-fee       (:storage_fee raw)
   :acceptance        (:acceptance raw)
   :acquiring-fee     (:acquiring_fee raw)           ;; эквайринг
   :subject           (:subject_name raw)
   :brand             (:brand_name raw)})

(defn ->finance-report [raw-list]
  (mapv ->finance-line raw-list))

;; ---------------------------------------------------------------------------
;; Paid storage
;; ---------------------------------------------------------------------------

(defn ->storage-cost [raw]
  ;; NB: WB `warehousePrice` is already the total cost for this row's
  ;; `barcodesCount` items (= per_unit_rate × barcodesCount).  Earlier code
  ;; multiplied by barcodesCount again, triple-inflating storage in March
  ;; 2026 (362k stored vs 120k real). See Phase-2 verification 2026-04-23.
  {:date           (:date raw)
   :article        (:vendorCode raw)
   :nm-id          (:nmId raw)
   :barcode        (or (:barcode raw) "")
   :warehouse      (or (:warehouse raw) "")
   :cost           (or (:warehousePrice raw) 0.0)
   :volume         (:volume raw)
   :barcodes-count (:barcodesCount raw)
   :marketplace    :wb})

(defn ->storage-costs
  "Transform WB paid-storage raw items and coalesce duplicates.

   WB returns multiple rows per (date, barcode, warehouse) when inventory
   is split across calcType categories (e.g. «короба базы» +
   «короба свыше базы»).  Each row's `warehousePrice` is the cost for
   its portion; we sum them per unique (date, barcode, warehouse) key.
   Otherwise `db/insert-batch!` INSERT-OR-REPLACE would drop all-but-last."
  [raw-list]
  (->> raw-list
       (mapv ->storage-cost)
       (group-by (juxt :date :barcode :warehouse))
       vals
       (mapv (fn [rows]
               (-> (first rows)
                   (assoc :cost (reduce + 0.0 (keep :cost rows)))
                   (assoc :barcodes-count (reduce + 0 (keep :barcodes-count rows))))))))

;; ---------------------------------------------------------------------------
;; Product stats (nm-report)
;; ---------------------------------------------------------------------------

(defn ->product-stat [raw]
  {:nm-id        (:nmID raw)
   :article      (:vendorCode raw)
   :views        (get-in raw [:statistics :selectedPeriod :openCardCount])
   :add-to-cart  (get-in raw [:statistics :selectedPeriod :addToCartCount])
   :orders       (get-in raw [:statistics :selectedPeriod :ordersCount])
   :orders-sum   (get-in raw [:statistics :selectedPeriod :ordersSumRub])
   :buyouts      (get-in raw [:statistics :selectedPeriod :buyoutsCount])
   :buyouts-sum  (get-in raw [:statistics :selectedPeriod :buyoutsSumRub])
   :cancel-count (get-in raw [:statistics :selectedPeriod :cancelCount])
   :cancel-sum   (get-in raw [:statistics :selectedPeriod :cancelSumRub])})

(defn ->product-stats [response]
  (mapv ->product-stat (get-in response [:data :cards])))

;; ---------------------------------------------------------------------------
;; Prices
;; ---------------------------------------------------------------------------

(defn ->price [raw]
  {:nm-id     (:nmID raw)
   :article   (:vendorCode raw)
   :price     (get-in raw [:sizes 0 :price])
   :discount  (:discount raw)
   :club-disc (:clubDiscount raw)})

(defn ->prices [response]
  (mapv ->price (get-in response [:data :listGoods] [])))
