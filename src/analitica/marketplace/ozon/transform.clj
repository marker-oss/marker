(ns analitica.marketplace.ozon.transform
  "Transform raw Ozon Seller API responses into the common domain model.
   All functions return nil for missing/null fields and never throw exceptions.")

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- sale-type [status]
  (cond
    (#{"delivered" "awaiting_deliver"} status) :sale
    (#{"cancelled" "returned"}         status) :return
    :else                                       :sale))

;; ---------------------------------------------------------------------------
;; Orders
;; ---------------------------------------------------------------------------

(defn- ->order [raw]
  (let [products (get raw :products [])
        product  (first products)
        analytics (get raw :analytics_data {})]
    {:marketplace  :ozon
     :order-id     (get raw :posting_number)
     :date         (get raw :in_process_at)
     :article      (get product :offer_id)
     :nm-id        nil
     :barcode      nil
     :tech-size    nil
     :subject      nil
     :category     nil
     :brand        nil
     :quantity     (get product :quantity)
     :price        (get product :price)
     :price-with-disc nil
     :warehouse    (get analytics :warehouse_name)
     :region       (get analytics :region)
     :status       (get raw :status)}))

(defn ->orders [raw-list]
  (mapv ->order raw-list))

;; ---------------------------------------------------------------------------
;; Sales
;; ---------------------------------------------------------------------------

(defn- ->sale [raw]
  (let [products  (get raw :products [])
        product   (first products)
        status    (get raw :status)
        analytics (get raw :analytics_data {})]
    {:marketplace     :ozon
     :sale-id         (get raw :posting_number)
     :date            (get raw :in_process_at)
     :article         (get product :offer_id)
     :nm-id           nil
     :barcode         nil
     :tech-size       nil
     :subject         nil
     :category        nil
     :brand           nil
     :quantity        (get product :quantity)
     :total-price     (get product :price)
     :for-pay         nil
     :finished-price  nil
     :price-with-disc nil
     :warehouse       (get analytics :warehouse_name)
     :region          (get analytics :region)
     :type            (sale-type status)}))

(defn ->sales [raw-list]
  (mapv ->sale raw-list))

;; ---------------------------------------------------------------------------
;; Stocks
;; ---------------------------------------------------------------------------

(defn- ->stock [raw]
  {:marketplace        :ozon
   :article            (get raw :item_code)
   :nm-id              (get raw :sku)
   :barcode            nil
   :tech-size          nil
   :subject            nil
   :category           nil
   :brand              nil
   :warehouse          (get raw :warehouse_name)
   :quantity           (get raw :free_to_sell_amount)
   :quantity-full      (some-> (get raw :free_to_sell_amount)
                               (+ (or (get raw :reserved_amount) 0)
                                  (or (get raw :promised_amount) 0)))
   :in-way-to-client   nil
   :in-way-from-client nil})

(defn ->stocks [raw-list]
  (mapv ->stock raw-list))

;; ---------------------------------------------------------------------------
;; Finance report
;; ---------------------------------------------------------------------------

(defn- normalize-ozon-operation [op-type]
  (case op-type
    "OperationAgentDeliveredToCustomer"        "sale"
    "OperationAgentDeliveredToCustomerCanceled" "sale"
    "ClientReturnAgentOperation"               "return"
    "OperationReturnGoodsFBSofRMS"             "return"
    "other"))

(defn- sum-services
  "Sum absolute service costs matching any of the given service names.
   Ozon returns service prices as negative numbers (deductions from payout).
   We store costs as positive values for consistent display across all marketplaces."
  [services names]
  (reduce + 0.0
    (map #(Math/abs (or (get % :price) 0))
         (filter #(contains? names (get % :name)) services))))

(defn- ->finance-line [raw sku-map]
  (let [item     (first (get raw :items []))
        sku      (get item :sku)
        services (get raw :services [])
        ;; Service name sets verified against live Ozon API (2026-03)
        logistics-names #{"MarketplaceServiceItemDirectFlowLogistic"         ;; FBO forward delivery
                          "MarketplaceServiceItemRedistributionLastMileCourier" ;; last-mile courier
                          "MarketplaceServiceItemReturnFlowLogistic"          ;; return logistics
                          "MarketplaceServiceItemReturnNotDelivToCustomer"    ;; return, not delivered
                          "MarketplaceServiceItemRedistributionReturnsPVZ"    ;; return via PVZ
                          "MarketplaceServiceItemReturnAfterDelivToCustomer"  ;; return post-delivery
                          "MarketplaceServiceItemReturnPartGoodsCustomer"}    ;; partial return
        fulfillment-names #{"MarketplaceServiceItemDropoffPVZ"               ;; dropoff at PVZ
                            "MarketplaceServiceItemRedistributionDropOffApvz" ;; dropoff APVZ redistrib
                            "MarketplaceServiceItemDropoffSC"                 ;; dropoff at SC
                            "MarketplaceServiceItemPackageMaterialsProvision" ;; packaging materials
                            "MarketplaceServiceItemPackageRedistribution"}    ;; package redistribution
        storage-names   #{"MarketplaceServiceItemTemporaryStorageRedistribution"} ;; temp storage
        acquiring-names #{"MarketplaceRedistributionOfAcquiringOperation"}]  ;; acquiring
    {:marketplace        :ozon
     :rrd-id             (get raw :operation_id)
     :report-id          nil
     :date-from          (get raw :operation_date)
     :date-to            (get raw :operation_date)
     :article            (get sku-map sku)
     :nm-id              sku
     :barcode            nil
     :subject            nil
     :brand              nil
     :operation          (normalize-ozon-operation (get raw :operation_type))
     :doc-type           (get raw :type)
     :quantity           (or (get item :quantity) 1)
     :retail-price       (get raw :accruals_for_sale)
     :retail-amount      (or (get raw :accruals_for_sale) 0)
     :sale-percent       nil
     :commission-pct     nil
     :wb-commission      (Math/abs (or (get raw :sale_commission) 0))
     :wb-reward          nil
     :wb-kvw-prc         nil
     :spp-prc            nil
     :price-with-disc    nil
     :delivery-amount    nil
     :return-amount      nil
     :delivery-cost      (+ (or (get raw :delivery_charge) 0)
                            (sum-services services logistics-names))
     :for-pay            (get raw :amount)
     :penalty            nil
     :storage-fee        (sum-services services storage-names)
     :acceptance         (sum-services services fulfillment-names)
     :additional-payment nil
     :deduction          nil
     :acquiring-fee      (sum-services services acquiring-names)}))

(defn ->finance-report [raw-list sku-map]
  (mapv #(->finance-line % sku-map) raw-list))

;; ---------------------------------------------------------------------------
;; Finance from /v2/finance/realization
;;
;; Input: one "realization raw" map with keys :header and :rows, where
;;   :header has :start_date / :stop_date (period)
;;   :rows is a vector of per-article rows shaped as
;;     {:item {:offer_id :sku :barcode :name}
;;      :seller_price_per_instance N
;;      :delivery_commission {:amount :standard_fee :quantity :total …}
;;      :return_commission   {:amount :standard_fee :quantity :total …}}
;;
;; Output: 0–2 finance-row maps per realization row (one for sales,
;;         one for returns), matching the schema used by WB transform
;;         so the same by-article aggregation works unchanged.
;;
;; Key decisions (see specs/002-calculation-audit/verdicts.md B-005):
;;   - article = item.offer_id (direct, no sku-map needed)
;;   - for_pay = delivery_commission.amount (what Ozon paid net to seller)
;;   - retail_amount = qty * seller_price_per_instance (gross revenue)
;;   - wb_commission = standard_fee - amount (commission deducted;
;;     negative value means seller got MORE than base rate due to
;;     bonus / bank_coinvestment / stars)
;;   - Returns carry POSITIVE for_pay (matches WB convention; by-article
;;     subtracts via abs).
;; ---------------------------------------------------------------------------

(defn- realization-row->finance-rows
  "Transform one realization row into 0–2 finance-rows (sale + return)."
  [rrow date-from date-to]
  (let [item     (get rrow :item)
        article  (get item :offer_id)
        sku      (get item :sku)
        barcode  (get item :barcode)
        price    (or (get rrow :seller_price_per_instance) 0)
        dc       (get rrow :delivery_commission)
        rc       (get rrow :return_commission)
        common   {:marketplace        :ozon
                  :report-id          nil
                  :date-from          date-from
                  :date-to            date-to
                  :article            article
                  :nm-id              sku
                  :barcode            barcode
                  :subject            nil
                  :brand              nil
                  :doc-type           nil
                  :sale-percent       nil
                  :commission-pct     nil
                  :wb-reward          nil
                  :wb-kvw-prc         nil
                  :spp-prc            nil
                  :price-with-disc    nil
                  :delivery-amount    nil
                  :return-amount      nil
                  :delivery-cost      0
                  :for-pay            0
                  :penalty            nil
                  :storage-fee        0
                  :acceptance         0
                  :additional-payment nil
                  :deduction          nil
                  :acquiring-fee      0}
        row-no   (or (get rrow :rowNumber) 0)
        sale-row   (when (pos? (or (get dc :quantity) 0))
                     (let [q      (get dc :quantity)
                           amount (or (get dc :amount) 0)
                           fee    (or (get dc :standard_fee) 0)]
                       (assoc common
                              :rrd-id        (hash [:ozon-real :sale row-no article sku date-from date-to])
                              :operation     "sale"
                              :quantity      q
                              :retail-price  price
                              :retail-amount (* q price)
                              :wb-commission (- fee amount)
                              :for-pay       amount)))
        return-row (when (pos? (or (get rc :quantity) 0))
                     (let [q      (get rc :quantity)
                           amount (or (get rc :amount) 0)
                           fee    (or (get rc :standard_fee) 0)]
                       (assoc common
                              :rrd-id        (hash [:ozon-real :return row-no article sku date-from date-to])
                              :operation     "return"
                              :quantity      q
                              :retail-price  price
                              :retail-amount (* q price)
                              :wb-commission (- fee amount)
                              :for-pay       amount)))]
    (into [] (remove nil? [sale-row return-row]))))

(defn ->finance-from-realization
  "Transform a raw realization response (one month) into finance-rows.

   Input: {:header {:start_date :stop_date …} :rows [{…}]} (from one
   /v2/finance/realization response).

   Returns: vector of finance-row maps, 0–2 per realization row."
  [realization-raw]
  (let [header     (get realization-raw :header)
        rows       (get realization-raw :rows [])
        date-from  (get header :start_date)
        date-to    (get header :stop_date)]
    (into [] (mapcat #(realization-row->finance-rows % date-from date-to) rows))))

;; ---------------------------------------------------------------------------
;; Product stats (analytics-data response)
;; ---------------------------------------------------------------------------

(defn- metric-value [metrics-list metric-name]
  (some (fn [m]
          (when (= metric-name (get m :key))
            (get m :value)))
        metrics-list))

(defn- ->product-stat [row]
  (let [dimensions (get row :dimensions [])
        metrics    (get row :metrics [])
        article    (get (first dimensions) :id)]
    {:nm-id        nil
     :article      article
     :views        (metric-value metrics "views")
     :add-to-cart  (metric-value metrics "session_view_pdp")
     :orders       (metric-value metrics "ordered_units")
     :orders-sum   (metric-value metrics "revenue")
     :buyouts      (metric-value metrics "delivered_units")
     :buyouts-sum  (metric-value metrics "delivered_revenue")
     :cancel-count (metric-value metrics "cancellations")
     :cancel-sum   nil}))

(defn ->product-stats [response]
  (let [rows (get-in response [:result :data] [])]
    (mapv ->product-stat rows)))

;; ---------------------------------------------------------------------------
;; Prices
;; ---------------------------------------------------------------------------

(defn- ->price [raw]
  {:marketplace :ozon
   :nm-id       nil
   :article     (get raw :offer_id)
   :price       (get raw :marketing_price)
   :discount    (get raw :marketing_seller_price)
   :club-disc   nil})

(defn ->prices [raw-list]
  (mapv ->price raw-list))

;; ---------------------------------------------------------------------------
;; Storage costs
;; ---------------------------------------------------------------------------

(defn- ->storage-cost [raw]
  {:date           (get raw :date)
   :article        (get raw :offer_id)
   :nm-id          nil
   :barcode        (or (get raw :barcode) "")
   :warehouse      (or (get raw :warehouse_name) "")
   :cost           (or (get raw :cost) 0.0)
   :volume         nil
   :barcodes-count nil
   :marketplace    :ozon})

(defn ->storage-costs [raw-list]
  (mapv ->storage-cost raw-list))

;; ---------------------------------------------------------------------------
;; Cash flow statement
;; ---------------------------------------------------------------------------

(def ^:private cf-storage-names
  #{"MarketplaceServiceStorageItem"})

(def ^:private cf-packaging-names
  #{"MarketplaceServiceItemPackageMaterialsProvision"})

(def ^:private cf-warehouse-names
  #{"MarketplaceServiceProductMovementFromWarehouse"})

(def ^:private cf-returns-cargo-names
  #{"MarketplaceServiceSellerReturnsCargoAssortment"})

(def ^:private cf-subscription-names
  #{"ItemAgentServiceStarsMembership"})

(def ^:private cf-fines-names
  #{"FinesShipmentNonRecommendedSlot"
   "FinesErrorIndexExceeded"
   "FinesCustomerComplaints"
   "FinesCustomerReturn"})

(def ^:private cf-acquiring-names
  #{"MarketplaceRedistributionOfAcquiringOperation"})

(def ^:private cf-correction-names
  #{"MarketplaceSellerCorrectionOperation"})

(def ^:private cf-compensation-names
  #{"AccrualConsigWriteOff"
   "AccrualInternalClaim"})

(defn- sum-cf-items
  "Sum prices of service items matching given name set."
  [items names]
  (reduce + 0.0
    (map #(or (:price %) 0)
         (filter #(contains? names (:name %)) items))))

(defn- ->date [timestamp]
  (when timestamp (subs timestamp 0 10)))

(defn- ->cash-flow-period [cf detail]
  (let [svc-items    (get-in detail [:services :items] [])
        svc-total    (or (get-in detail [:services :total]) 0)
        oth-items    (get-in detail [:others :items] [])
        storage-v    (sum-cf-items svc-items cf-storage-names)
        packaging-v  (sum-cf-items svc-items cf-packaging-names)
        warehouse-v  (sum-cf-items svc-items cf-warehouse-names)
        ret-cargo-v  (sum-cf-items svc-items cf-returns-cargo-names)
        subscr-v     (sum-cf-items svc-items cf-subscription-names)
        fines-v      (sum-cf-items svc-items cf-fines-names)
        acquiring-v  (sum-cf-items oth-items cf-acquiring-names)
        correct-v    (sum-cf-items oth-items cf-correction-names)
        compens-v    (sum-cf-items oth-items cf-compensation-names)]
    {:period-begin       (->date (get-in cf [:period :begin]))
     :period-end         (->date (get-in cf [:period :end]))
     :orders-amount      (or (:orders_amount cf) 0)
     :returns-amount     (or (:returns_amount cf) 0)
     :commission-amount  (or (:commission_amount cf) 0)
     :delivery-amount    (or (get-in detail [:delivery :amount]) 0)
     :delivery-logistics (or (get-in detail [:delivery :delivery_services :total]) 0)
     :return-amount      (or (get-in detail [:return :amount]) 0)
     :return-logistics   (or (get-in detail [:return :return_services :total]) 0)
     :storage            storage-v
     :packaging          packaging-v
     :warehouse-movement warehouse-v
     :returns-cargo      ret-cargo-v
     :subscription       subscr-v
     :fines              fines-v
     :other-services     (- svc-total storage-v packaging-v warehouse-v
                            ret-cargo-v subscr-v fines-v)
     :acquiring          acquiring-v
     :corrections        correct-v
     :compensation       compens-v
     :payment            (reduce + 0.0 (map #(or (:payment %) 0) (get detail :payments [])))
     :begin-balance      (or (:begin_balance_amount detail) 0)
     :end-balance        (or (:end_balance_amount detail) 0)
     :invoice-transfer   (or (:invoice_transfer detail) 0)}))

(defn ->cash-flow-periods
  "Transform cash flow statement response into seq of period rows.
   Input: {:cash_flows [...] :details [...]}"
  [{:keys [cash_flows details]}]
  (let [detail-by-begin (into {} (map (fn [d] [(->date (get-in d [:period :begin])) d]) details))]
    (mapv (fn [cf]
            (let [begin  (->date (get-in cf [:period :begin]))
                  detail (get detail-by-begin begin)]
              (->cash-flow-period cf detail)))
          cash_flows)))
