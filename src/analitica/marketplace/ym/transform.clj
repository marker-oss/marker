(ns analitica.marketplace.ym.transform
  "Transform raw Yandex Market API responses into the common domain model.
   All functions return nil for missing/null fields and never throw exceptions."
  (:require [com.brunobonacci.mulog :as mu]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- ym-sale-type
  "Legacy order-level classification. Kept for `->sale` / `->sales`
   which operate on raw orders without item-level details. FinanceRow
   paths use `classify-item-operation` instead (see data-model.md §4)."
  [status]
  (if (#{"RETURNED" "PARTIALLY_RETURNED"} status)
    :return
    :sale))

(defn- classify-item-operation
  "Per-item :operation classification: item-level status from
   `item.details[0].itemStatus` takes priority over order-level
   `order.status`. Returns \"sale\", \"return\", or \"cancelled\".

   Priority matrix (see specs/003-finance-row-completeness/data-model.md §4):

     itemStatus RETURNED               → \"return\"   (incl. post-delivery)
     itemStatus REJECTED               → \"cancelled\"
     order.status DELIVERED | PARTIALLY_DELIVERED → \"sale\"
     order.status CANCELLED_*          → \"cancelled\"
     order.status RETURNED             → \"return\"   (fallback when items lack :details)
     anything else                     → \"sale\"     (safe default + mu/log)"
  [order item]
  (let [item-status  (get-in item [:details 0 :itemStatus])
        order-status (:status order)]
    (cond
      (= "RETURNED" item-status) "return"
      (= "REJECTED" item-status) "cancelled"
      (#{"DELIVERED" "PARTIALLY_DELIVERED"} order-status) "sale"
      (#{"CANCELLED_BEFORE_PROCESSING"
         "CANCELLED_IN_PROCESSING"
         "CANCELLED_IN_DELIVERY"} order-status) "cancelled"
      (= "RETURNED" order-status) "return"
      :else (do
              (mu/log ::ym-unknown-status
                      :order-status order-status
                      :item-status item-status)
              "sale"))))

(defn- commission-value
  "Extract commission amount by type from commissions list."
  [commissions type-name]
  (some (fn [c] (when (= type-name (:type c)) (:actual c)))
        commissions))

(defn- price-by-type
  "Extract price total by type from prices list."
  [prices type-name]
  (some (fn [p] (when (= type-name (:type p)) (:total p)))
        prices))

;; ---------------------------------------------------------------------------
;; Orders
;; ---------------------------------------------------------------------------

(defn- ->order [raw]
  (let [items    (get raw :items [])
        item     (first items)
        qty      (reduce + 0 (map #(get % :count 0) items))]
    {:marketplace :ym
     :order-id    (or (get raw :id) (get raw :orderId))
     :date        (or (get raw :creationDate) (get raw :date))
     :article     (get item :offerId)
     :quantity    (when (seq items) qty)
     :price       (or (get raw :buyerTotal) (get raw :total))
     :status      (when-let [s (get raw :status)] (keyword s))
     :warehouse   (get-in raw [:delivery :shipments 0 :warehouseId])
     :region      (get-in raw [:delivery :region :name])}))

(defn ->orders [raw-list]
  (mapv ->order raw-list))

;; ---------------------------------------------------------------------------
;; Sales
;; ---------------------------------------------------------------------------

(defn- ->sale [raw]
  (let [items  (get raw :items [])
        item   (first items)
        qty    (reduce + 0 (map #(get % :count 0) items))
        status (get raw :status)]
    {:marketplace :ym
     :sale-id     (or (get raw :id) (get raw :orderId))
     :date        (or (get raw :creationDate) (get raw :date))
     :article     (get item :offerId)
     :quantity    (when (seq items) qty)
     :total-price (or (get raw :buyerTotal) (get raw :total))
     :for-pay     (get raw :forPay)
     :type        (ym-sale-type status)}))

(defn ->sales [raw-list]
  (mapv ->sale raw-list))

;; ---------------------------------------------------------------------------
;; Stocks
;; ---------------------------------------------------------------------------

(defn- available-count
  "Sum AVAILABLE stock count from a stocks list."
  [stocks]
  (reduce + 0 (->> stocks
                   (filter #(= "AVAILABLE" (name (get % :type ""))))
                   (map #(or (get % :count) 0)))))

(defn- fit-count
  "Sum FIT (total physical) stock count from a stocks list."
  [stocks]
  (reduce + 0 (map #(or (get % :count) 0) stocks)))

(defn ->stocks
  "Transform YM warehouses response into stock rows.
   Input: list of warehouse maps {:warehouseId N :offers [{:offerId ... :stocks [...]}]}"
  [warehouses]
  (vec
   (for [wh    warehouses
         offer (get wh :offers [])
         :let  [article (get offer :offerId)
                stocks  (get offer :stocks [])
                qty     (available-count stocks)]
         :when (seq article)]
     {:marketplace        :ym
      :article            article
      :nm-id              nil
      :barcode            nil
      :tech-size          nil
      :subject            nil
      :category           nil
      :brand              nil
      :warehouse          (str (get wh :warehouseId))
      :quantity           qty
      :quantity-full      (fit-count stocks)
      :in-way-to-client   nil
      :in-way-from-client nil})))

;; ---------------------------------------------------------------------------
;; Finance from order-stats (primary source for YM commissions)
;; ---------------------------------------------------------------------------

(defn- ->finance-from-order-stat
  "Convert a single order-stats order into finance lines (one per item).

  YM financial model (stats/orders) — validated against 132 live orders
  (Mar-Apr 2026, all DELIVERED): payments[].total matches BUYER price for 97.7%
  of orders (avg residual ~42₽ rounding). Commissions are NOT deducted from
  payments — YM invoices them separately in the next settlement period.

  - prices[BUYER]        = what the buyer actually paid; equals payments[].total
                           and is therefore the correct base for net income
  - prices[MARKETPLACE]  = accounting price (post-promo), used in ledger only —
                           NOT a money flow; using it as for-pay base undercounts
                           seller income by ~58% (that's the buyer-visible
                           discount Yandex absorbs from its own margin)
  - subsidies[].amount   = ledger entry, mirrors MARKETPLACE; not additive income
  - commissions[].actual = what YM will debit later

  Net seller income (aligns with WB's ppvz_for_pay and Ozon realization
  delivery_commission.amount — all three mean \"netto to seller after fees\"):

      for-pay = BUYER − FEE − AGENCY − DELIVERY_TO_CUSTOMER
                      − PAYMENT_TRANSFER − AUCTION_PROMOTION"
  [order]
  (let [order-id    (get order :id)
        date        (get order :creationDate)
        commissions (get order :commissions [])
        status      (get order :status)
        items       (get order :items [])
        ;; Distribute order-level commissions across items proportionally
        ;; For simplicity, divide evenly when multiple items
        n-items     (max 1 (count items))
        fee         (/ (or (commission-value commissions "FEE") 0) n-items)
        delivery    (/ (or (commission-value commissions "DELIVERY_TO_CUSTOMER") 0) n-items)
        acquiring   (/ (or (commission-value commissions "PAYMENT_TRANSFER") 0) n-items)
        agency      (/ (or (commission-value commissions "AGENCY") 0) n-items)
        ;; Sum of ALL commission types (known + future) for for-pay netting.
        ;; Named types above are kept for attribution to specific FinanceRow
        ;; fields; the generic sum guarantees for-pay stays correct when YM
        ;; introduces new commission types (e.g. SORTING, seen once in live data).
        all-comm    (/ (reduce + 0.0 (map #(or (:actual %) 0) commissions)) n-items)]
    (mapv (fn [item]
            (let [shop-sku    (get item :shopSku)
                  buyer-price (price-by-type (get item :prices []) "BUYER")
                  qty         (or (get item :count) 1)
                  net-pay     (- (or buyer-price 0) all-comm)]
              {:marketplace        :ym
               :rrd-id             (Math/abs (.hashCode (str order-id "-" shop-sku)))
               :report-id          nil
               :date-from          date
               :date-to            date
               :article            shop-sku
               :nm-id              (get item :marketSku)
               :barcode            nil
               :subject            nil
               :brand              nil
               :operation          (classify-item-operation order item)
               :doc-type           status
               :quantity           qty
               :retail-price       (or buyer-price 0)
               :retail-amount      (* (or buyer-price 0) qty)
               :sale-percent       nil
               :commission-pct     nil
               :wb-commission      (+ fee agency)
               :wb-reward          nil
               :wb-kvw-prc         nil
               :spp-prc            nil
               :price-with-disc    nil
               :delivery-amount    nil
               :return-amount      nil
               :delivery-cost      delivery
               :for-pay            net-pay
               :penalty            nil
               :storage-fee        nil
               :acceptance         nil
               :additional-payment nil
               :deduction          nil
               :acquiring-fee      acquiring}))
          items)))

(defn ->finance-from-order-stats
  "Convert order-stats response into finance lines."
  [orders]
  (vec (mapcat ->finance-from-order-stat orders)))

;; ---------------------------------------------------------------------------
;; Finance report (legacy — goods-realization)
;; ---------------------------------------------------------------------------

(defn- ->finance-line [raw]
  {:marketplace    :ym
   :date-from      (or (get raw :dateFrom) (get raw :date))
   :date-to        (or (get raw :dateTo) (get raw :date))
   :article        (or (get raw :offerId) (get raw :shopSku))
   :operation      (or (get raw :type) (get raw :operationType))
   :quantity       (or (get raw :count) (get raw :quantity))
   :retail-price   (or (get raw :price) (get raw :retailPrice))
   :commission-pct (get raw :commissionPercent)
   :for-pay        (or (get raw :payment) (get raw :forPay))
   :delivery-cost  (get raw :deliveryCost)
   :penalty        (get raw :penalty)})

(defn ->finance-report [raw-list]
  (mapv ->finance-line raw-list))

;; ---------------------------------------------------------------------------
;; Product stats
;; ---------------------------------------------------------------------------

(defn- ->product-stat [item]
  {:article      (or (get item :offerId) (get item :shopSku))
   :views        (get-in item [:statistics :shows])
   :add-to-cart  (get-in item [:statistics :cartItems])
   :orders       (get-in item [:statistics :orderedItems])
   :orders-sum   (get-in item [:statistics :orderedItemsRevenue])
   :buyouts      (get-in item [:statistics :deliveredItems])
   :buyouts-sum  (get-in item [:statistics :deliveredItemsRevenue])})

(defn ->product-stats [response]
  (let [items (get response :result [])]
    (mapv ->product-stat items)))

;; ---------------------------------------------------------------------------
;; Prices
;; ---------------------------------------------------------------------------

(defn- ->price [raw]
  {:marketplace :ym
   :article     (or (get raw :offerId) (get raw :id))
   :price       (or (get-in raw [:price :value]) (get raw :price))
   :discount    (get-in raw [:price :discountBase])})

(defn ->prices [raw-list]
  (mapv ->price raw-list))
