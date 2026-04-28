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
  ;; RFC-14: :quantity is always ≥ 0. Direction is encoded by :type
  ;; (:sale / :return / :other). Same convention as canonical SalesRow.
  ;; RFC-15: :for-pay is always ≥ 0; sign through :type.
  (let [is-return? (and (:saleID raw) (.startsWith (str (:saleID raw)) "R"))
        is-sale?   (and (:saleID raw) (.startsWith (str (:saleID raw)) "S"))
        raw-pay    (:forPay raw)]
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
     :quantity        1
     :total-price     (:totalPrice raw)
     :discount        (:discountPercent raw)
     :spp             (:spp raw)
     :for-pay         (some-> raw-pay double Math/abs)
     :finished-price  (:finishedPrice raw)
     :price-with-disc (:priceWithDisc raw)
     :warehouse       (:warehouseName raw)
     :region          (:regionName raw)
     :province        (:oblastOkrugName raw)
     :is-return       is-return?
     :is-storno       (= 1 (:IsStorno raw))
     :type            (cond
                        is-return? :return
                        is-sale?   :sale
                        :else      :other)}))

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

(defn- iso-day
  "Normalize a date/timestamp string to YYYY-MM-DD (WB uses mixed forms)."
  [s]
  (when (and s (>= (count s) 10)) (subs s 0 10)))

(def ^:private wb-operation-kind
  "Map raw WB `supplier_oper_name` to canonical :operation-kind. See
   RFC-3 in docs/concept-crosswalk.md §2.1 and L2 §4.3.

   :sale        — buyer purchase event
   :return      — buyer return event
   :service     — non-event MP service line (logistics / storage / acceptance)
   :adjustment  — corrections, compensations, fines, deductions
   nil          — fallback when raw value is unrecognized; transform leaves
                  :operation-kind unset and downstream code falls back to
                  the legacy :operation string + a warning.

   Map extended 2026-04-28 from production data audit (Phase D): the
   original 11-entry version covered only ~40% of real WB rows. Added
   compensations / corrections / loyalty discounts / processing fees that
   surfaced in the 17k-row finance sample."
  {"Продажа"                                                    :sale
   "Возврат"                                                    :return
   ;; Service operations — money lives in delivery-cost / storage-fee / acceptance.
   "Логистика"                                                  :service
   "Коррекция логистики"                                        :service
   "Хранение"                                                   :service
   "Платная приёмка"                                            :service
   "Сторно платной приёмки"                                     :service
   "Обработка товара"                                           :service
   ;; Adjustments — money lives in additional-payment / penalty / deduction.
   "Возмещение издержек по перевозке/по складским операциям с товаром" :adjustment
   "Возмещение за выдачу и возврат товаров на ПВЗ"              :adjustment
   "Корректировка вознаграждения"                               :adjustment
   "Коррекция продаж"                                           :adjustment
   "Компенсация ущерба"                                         :adjustment
   "Компенсация скидки по программе лояльности"                 :adjustment
   "Штраф"                                                      :adjustment
   "Удержание"                                                  :adjustment
   "Доплата"                                                    :adjustment})

(defn- wb-canonical-op
  "Return [kind subtype] for a raw WB supplier_oper_name. Subtype
   preserves the original Russian classifier verbatim for audit/UI
   drill-down (concept-crosswalk §2.1)."
  [raw-op]
  (let [kind (get wb-operation-kind raw-op)]
    [kind raw-op]))

(defn ->finance-line [raw]
  ;; RFC-3: classify operation into canonical :operation-kind +
  ;; preserve raw classifier in :operation-subtype.
  ;; RFC-14/15: :quantity and :for-pay are normalized to non-negative;
  ;; direction is encoded in :operation-kind (sale = +, return = − under
  ;; L2 mp_payout). Service / adjustment rows carry for-pay = 0; their
  ;; money lives in dedicated fields (delivery-cost / storage-fee / …).
  (let [raw-op             (:supplier_oper_name raw)
        [kind subtype]     (wb-canonical-op raw-op)
        op-string          (case raw-op
                             "Продажа" "sale"
                             "Возврат" "return"
                             raw-op)
        raw-qty            (:quantity raw)
        raw-pay            (:ppvz_for_pay raw)
        for-pay-normalized (cond
                             (#{:service :adjustment} kind) 0
                             :else                          (some-> raw-pay double Math/abs))]
    {:marketplace       :wb
     :rrd-id            (:rrd_id raw)
     :report-id         (:realizationreport_id raw)
     :date-from         (:date_from raw)
     :date-to           (:date_to raw)
     ;; Per-event date: `rr_dt` = realization-report event date. Used by
     ;; domain queries to filter precisely without overlap inflation.
     :event-date        (iso-day (or (:rr_dt raw)
                                     (:sale_dt raw)
                                     (:order_dt raw)
                                     (:date_from raw)))
     :article           (:sa_name raw)
     :nm-id             (:nm_id raw)
     :barcode           (:barcode raw)
     :doc-type          (:doc_type_name raw)
     :quantity          (some-> raw-qty Math/abs)
     :retail-price      (:retail_price raw)
     :retail-amount     (:retail_amount raw)
     :sale-percent      (:sale_percent raw)
     :commission-pct    (:commission_percent raw)
     :mp-commission     (:ppvz_sales_commission raw)
     :wb-reward         (:ppvz_reward raw)
     :wb-kvw-prc        (:ppvz_kvw_prc raw)
     :spp-prc           (:ppvz_spp_prc raw)
     :operation         op-string
     :operation-kind    kind
     :operation-subtype subtype
     :price-with-disc   (:retail_price_withdisc_rub raw)
     :delivery-amount   (:delivery_amount raw)
     :return-amount     (:return_amount raw)
     :delivery-cost     (:delivery_rub raw)
     :for-pay           for-pay-normalized
     :penalty           (:penalty raw)
     :additional-payment (:additional_payment raw)
     :deduction         (:deduction raw)
     :storage-fee       (:storage_fee raw)
     :acceptance        (:acceptance raw)
     :acquiring-fee     (:acquiring_fee raw)
     :subject           (:subject_name raw)
     :brand             (:brand_name raw)}))

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
