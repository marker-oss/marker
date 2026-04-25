(ns analitica.marketplace.ym.transform
  "Transform raw Yandex Market API responses into the common domain model.
   All functions return nil for missing/null fields and never throw exceptions."
  (:require [com.brunobonacci.mulog :as mu]
            [clojure.string :as str]))

(defn- extract-barcode
  "YM items carry a GS1 DataMatrix string in `cisList`, e.g.
   `(01)04640392784759(21)<serial>`. The `(01)` segment is the 14-digit
   GTIN; stripping one leading zero yields the 13-digit EAN used in 1C.
   Returns nil when cisList is missing or malformed."
  [cis-list]
  (when-let [cis (first cis-list)]
    (when-let [[_ gtin14] (re-find #"\(01\)(\d{14})" cis)]
      (if (str/starts-with? gtin14 "0")
        (subs gtin14 1)
        gtin14))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- ->iso-datetime
  "YM /stats/orders returns `creationDate` and friends as 'DD-MM-YYYY HH:MM:SS'
   while WB and Ozon emit ISO 8601. Storing the raw YM string broke SQL
   ORDER BY/MIN/MAX (lexicographic '01-04-…' sorts before '31-03-…') and
   the dashboard last-sync-time. Normalize at the transform boundary so
   nothing downstream has to know about the dialect.

   Accepts:
     'DD-MM-YYYY HH:MM:SS' → 'YYYY-MM-DDTHH:MM:SS'
     'DD-MM-YYYY'          → 'YYYY-MM-DD'
     ISO already           → returned as-is
     nil / unrecognized    → returned as-is (no throw)"
  [s]
  (when s
    (cond
      (re-matches #"\d{4}-\d{2}-\d{2}.*" s) s
      (re-matches #"(\d{2})-(\d{2})-(\d{4})(?:[ T](\d{2}:\d{2}:\d{2}))?" s)
      (let [[_ d m y t] (re-matches #"(\d{2})-(\d{2})-(\d{4})(?:[ T](\d{2}:\d{2}:\d{2}))?" s)]
        (if t (str y "-" m "-" d "T" t) (str y "-" m "-" d)))
      :else s)))

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

     itemStatus RETURNED | PARTIALLY_RETURNED → \"return\"   (incl. post-delivery)
     itemStatus REJECTED               → \"cancelled\"
     order.status DELIVERED | PARTIALLY_DELIVERED → \"sale\"
     order.status CANCELLED_*          → \"cancelled\"
     order.status RETURNED | PARTIALLY_RETURNED → \"return\"   (fallback when items lack :details)
     anything else                     → \"sale\"     (safe default + mu/log)

   PARTIALLY_RETURNED is included for parity with legacy `ym-sale-type`;
   data-model.md §4 does not enumerate it but YM may emit it and we
   want to classify as return, not unknown."
  [order item]
  (let [item-status  (get-in item [:details 0 :itemStatus])
        order-status (:status order)]
    (cond
      (#{"RETURNED" "PARTIALLY_RETURNED"} item-status) "return"
      (= "REJECTED" item-status) "cancelled"
      (#{"DELIVERED" "PARTIALLY_DELIVERED"} order-status) "sale"
      (#{"CANCELLED_BEFORE_PROCESSING"
         "CANCELLED_IN_PROCESSING"
         "CANCELLED_IN_DELIVERY"} order-status) "cancelled"
      (#{"RETURNED" "PARTIALLY_RETURNED"} order-status) "return"
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
     :date        (->iso-datetime (or (get raw :creationDate) (get raw :date)))
     ;; YM /stats/orders returns the article as :shopSku, not :offerId.
     ;; Keep :offerId as a fallback for the (theoretical) /stats endpoint
     ;; shape; ->finance-line uses the same dual lookup.
     :article     (or (get item :offerId) (get item :shopSku))
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
     :date        (->iso-datetime (or (get raw :creationDate) (get raw :date)))
     ;; Same shopSku/offerId fallback as ->order — see comment there.
     :article     (or (get item :offerId) (get item :shopSku))
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
                      − PAYMENT_TRANSFER − AUCTION_PROMOTION − bidFee

  Per-item bidFee is extracted to :ad-cost and subtracted from :for-pay
  without redistribution (FR-005/006/019). CANCELLED_BEFORE_PROCESSING
  orders still carry bidFee — seller paid for the ad even if the order
  never shipped, so :for-pay turns negative (matching real cash impact)."
  [order]
  (let [order-id    (get order :id)
        date        (get order :creationDate)
        ;; Per-event date: prefer statusUpdateDate (when the status became
        ;; final, i.e. when the financial event crystallised); fall back to
        ;; creationDate if missing. Normalized to YYYY-MM-DD.
        event-date  (let [s (or (get order :statusUpdateDate) date)]
                      (when (and s (>= (count s) 10)) (subs s 0 10)))
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
        ;; AUCTION_PROMOTION is the REAL ad-auction commission (Vickrey
        ;; second-price clear), usually 2–4 ₽ per order. Keep it out of
        ;; `all-comm` so that ad-cost isn't subtracted twice (once inside
        ;; for-pay via commissions, once again as :ad-cost in the profit
        ;; formula). Attribution: → :ad-cost below.
        ;;
        ;; Do NOT use `:bidFee` at the item level for :ad-cost — it's the
        ;; seller's bid-cap (max they'd pay), not the clearing price. We
        ;; observed bidFee 196k vs AUCTION_PROMOTION 559 on the same data
        ;; (351× inflation). Spec-003 mistook the cap for actual charge;
        ;; corrected here 2026-04-24 after P&L verification.
        ad-commission (/ (double (or (commission-value commissions "AUCTION_PROMOTION") 0)) n-items)
        ;; Sum of all commission types EXCEPT AUCTION_PROMOTION for for-pay
        ;; netting. Other named types above (FEE, DELIVERY, etc) stay in
        ;; all-comm. Generic sum keeps for-pay correct when YM adds new
        ;; commission types (e.g. SORTING, seen once in live data).
        all-comm    (/ (reduce + 0.0
                         (map #(or (:actual %) 0)
                              (filter #(not= "AUCTION_PROMOTION" (:type %))
                                      commissions)))
                       n-items)
        ;; YM subsidies: Yandex pays seller to cover Yandex-side discounts
        ;; (SUBSIDY type) and cashbacks (YANDEX_CASHBACK). ACCRUAL adds to
        ;; seller payout; DEDUCTION reverses on returns / partial delivery.
        ;; Net effect per order, split across items like commissions.
        ;; Discovered 2026-04-24 Phase-2 verification — YM UE was losing
        ;; ~40% of real seller income on promo-heavy periods.
        subsidies   (get order :subsidies [])
        subsidy-net (/ (reduce + 0.0
                          (map (fn [s]
                                 (let [a (or (:amount s) 0)]
                                   (case (:operationType s)
                                     "ACCRUAL"   a
                                     "DEDUCTION" (- a)
                                     0)))
                               subsidies))
                       n-items)]
    (mapv (fn [item]
            (let [shop-sku    (get item :shopSku)
                  buyer-price (price-by-type (get item :prices []) "BUYER")
                  qty         (or (get item :count) 1)
                  ;; :for-pay = BUYER − Σ(non-ad commissions) + net_subsidies.
                  ;; AUCTION_PROMOTION (ad) is held separately in :ad-cost
                  ;; so the UE.4 profit formula subtracts it once; the
                  ;; other commissions stay inside for-pay.
                  net-pay     (+ (- (or buyer-price 0) all-comm)
                                 subsidy-net)]
              {:marketplace        :ym
               :rrd-id             (Math/abs (.hashCode (str order-id "-" shop-sku)))
               :report-id          nil
               :date-from          date
               :date-to            date
               :event-date         event-date
               :article            shop-sku
               :nm-id              (get item :marketSku)
               :barcode            (extract-barcode (get item :cisList))
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
               :acquiring-fee      acquiring
               :ad-cost            ad-commission}))
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
   :date-from      (->iso-datetime (or (get raw :dateFrom) (get raw :date)))
   :date-to        (->iso-datetime (or (get raw :dateTo) (get raw :date)))
   :event-date     (let [d (->iso-datetime (or (get raw :date) (get raw :dateFrom)))]
                     (when (and d (>= (count d) 10)) (subs d 0 10)))
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
