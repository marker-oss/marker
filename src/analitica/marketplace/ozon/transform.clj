(ns analitica.marketplace.ozon.transform
  "Transform raw Ozon Seller API responses into the common domain model.
   All functions return nil for missing/null fields and never throw exceptions."
  (:require [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]))

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
     ;; Per-event date: Ozon txn/realization rows carry `operation_date`
     ;; already at day granularity (often "YYYY-MM-DD 00:00:00").
     :event-date         (when-let [d (get raw :operation_date)]
                           (subs d 0 10))
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
;;   - for_pay = amount + bonus + compensation + stars + bank_coinvestment
;;                + pick_up_point_coinvestment (full Ozon payout to seller)
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
                  ;; Realization is a month-level aggregate: no per-event
                  ;; date in rows. Use the report's start_date so that
                  ;; rows land inside the claimed month when filtered by
                  ;; event_date. Approximation — see data-dictionary
                  ;; §finance/known-gaps.
                  :event-date         date-from
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
        ;; Ozon seller payout = amount (from buyer) + bonus (Ozon platform
        ;; subsidy for promo discounts) + compensation + stars (loyalty).
        ;; Previous code used only `amount`, silently dropping ~50% of
        ;; seller income in periods with heavy Ozon promotions. Verified on
        ;; March 2026: amount 266,994 + bonus 303,674 = 570,668 real payout.
        ;; Commission = (total − for_pay) = what Ozon actually charged.
        ;; bank_coinvestment / pick_up_point_coinvestment added by Ozon
        ;; Dec 2024 — co-investment subsidies paid by bank/PUP and passed
        ;; through to seller. Verified present on realization rows in prod
        ;; data (2026-03 sample: bank_coinvestment ≈ 1% of amount).
        seller-payout (fn [c]
                        (+ (or (get c :amount) 0)
                           (or (get c :bonus) 0)
                           (or (get c :compensation) 0)
                           (or (get c :stars) 0)
                           (or (get c :bank_coinvestment) 0)
                           (or (get c :pick_up_point_coinvestment) 0)))
        sale-row   (when (pos? (or (get dc :quantity) 0))
                     (let [q      (get dc :quantity)
                           payout (seller-payout dc)
                           total  (or (get dc :total) 0)]
                       (assoc common
                              :rrd-id        (hash [:ozon-real :sale row-no article sku date-from date-to])
                              :operation     "sale"
                              :quantity      q
                              :retail-price  price
                              :retail-amount (* q total)
                              :wb-commission (max 0 (- (* q total) payout))
                              :for-pay       payout)))
        return-row (when (pos? (or (get rc :quantity) 0))
                     (let [q      (get rc :quantity)
                           payout (seller-payout rc)
                           total  (or (get rc :total) 0)]
                       (assoc common
                              :rrd-id        (hash [:ozon-real :return row-no article sku date-from date-to])
                              :operation     "return"
                              :quantity      q
                              :retail-price  price
                              :retail-amount (* q total)
                              :wb-commission (max 0 (- (* q total) payout))
                              :for-pay       payout)))]
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
;; US3A: Hybrid transform — transaction/list operations[].services[] → FinanceRow
;;
;; Each Ozon /v3/finance/transaction/list operation can carry 0..N services
;; (logistics, acquiring, packaging, storage, …). A service amount attributes
;; to one or more items in the same operation; the cascade is:
;;
;;   operation.items[].sku  →  article-lookup[sku]  →  offer_id (:article)
;;
;; where article-lookup is built from realization raw_data (US3B concern).
;;
;; Output rows carry ONE cost field populated (per service classification) and
;; are intended for merge into the existing FinanceRow space keyed by article
;; + date-from. They do NOT touch :for-pay / :retail-amount — those are owned
;; by the realization path (B-005).
;; ---------------------------------------------------------------------------

(def ^:const ozon-service-mapping
  "Ozon transaction-list operations[].services[].name → FinanceRow field.
   Last updated: 2026-04-22 based on live data (1754 services / 18 types).
   See specs/003-finance-row-completeness/data-model.md §3."
  {"MarketplaceServiceItemDirectFlowLogistic"            :delivery-cost
   "MarketplaceServiceItemRedistributionLastMileCourier" :delivery-cost
   "MarketplaceServiceItemReturnFlowLogistic"            :delivery-cost
   "MarketplaceServiceItemRedistributionReturnsPVZ"      :delivery-cost
   "MarketplaceServiceItemDropoffSC"                     :delivery-cost
   "MarketplaceServiceItemDropoffPVZ"                    :delivery-cost
   "MarketplaceServiceItemRedistributionDropOffApvz"     :delivery-cost
   "MarketplaceServiceItemDeliveryToHandoverPlaceOzon"   :delivery-cost
   "MarketplaceServiceItemPackageRedistribution"         :delivery-cost
   "MarketplaceServiceProductMovementFromWarehouse"      :delivery-cost
   "MarketplaceRedistributionOfAcquiringOperation"       :acquiring-fee
   "MarketplaceServiceItemPackageMaterialsProvision"     :acceptance
   "MarketplaceServiceItemTemporaryStorageRedistribution" :storage-fee
   "ItemAgentServiceStarsMembership"                     :additional-payment
   "MarketplaceServiceSellerReturnsCargoAssortment"      :additional-payment
   "MarketplaceServiceItemReturnNotDelivToCustomer"      :additional-payment
   "MarketplaceServiceItemReturnAfterDelivToCustomer"    :additional-payment
   "MarketplaceServiceItemReturnPartGoodsCustomer"       :additional-payment})

(def ^:const ozon-operation-mapping
  "Top-level operation_type → FinanceRow field (when service-level isn't used).
   For ops like MarketplaceMarketingActionCostOperation where the amount itself
   (not services[]) is attributed per-article."
  {"MarketplaceMarketingActionCostOperation" :ad-cost})

(defn classify-ozon-service
  "Map Ozon service name to FinanceRow field. Unknown → :additional-payment + mu/log."
  [service-name]
  (or (ozon-service-mapping service-name)
      (do (mu/log ::ozon-unknown-service :name service-name)
          :additional-payment)))

(defn- op-date->month-first
  "Convert operation_date \"2026-03-15 10:00:00\" → \"2026-03-01\" (month first day).
   Returns nil when input nil or too short to slice YYYY-MM."
  [op-date]
  (when (and (string? op-date) (>= (count op-date) 7))
    (str (subs op-date 0 7) "-01")))

(defn- distribute-service-amount
  "Distribute a service.price across products proportional to price × quantity.
   Fallback to equal split when total weight is 0. Sign of service-price is
   ignored — returned weights are always non-negative (abs).

   Args:
     service-price  number (can be negative — sign stripped via Math/abs)
     products       seq of maps (may contain :price and :quantity)

   Returns a vector of non-negative allocations, same length as products,
   summing to |service-price|. Returns [] when products is empty."
  [service-price products]
  (let [n         (count products)
        abs-price (Math/abs (double (or service-price 0)))]
    (if (zero? n)
      []
      (let [weights (mapv #(* (double (or (:price %) 0))
                              (double (or (:quantity %) 1)))
                          products)
            total   (reduce + 0.0 weights)]
        (if (pos? total)
          (mapv (fn [w] (* abs-price (/ w total))) weights)
          (vec (repeat n (/ abs-price n))))))))

(defn service-rrd-id
  "Stable hash for a service-row natural key (operation-id, sku, service-name).
   Deterministic across runs — uses String.hashCode which is stable per JLS."
  [operation-id sku service-name]
  (Math/abs (long (.hashCode (str operation-id "-" sku "-" service-name)))))

(defn- normalize-lookup
  "sku may arrive as Long from the API or String from normalized tables.
   Try direct lookup first, then String coercion for robustness."
  [article-lookup sku]
  (or (get article-lookup sku)
      (get article-lookup (str sku))))

(defn- services-rows
  "Build rows for each (item × service) pair using per-service classification."
  [operation items article-lookup month-first op-id]
  (let [services (get operation :services [])
        op-date  (when-let [d (get operation :operation_date)]
                   (when (>= (count d) 10) (subs d 0 10)))]
    (for [svc  services
          :let [svc-name   (get svc :name)
                svc-price  (get svc :price)
                field      (classify-ozon-service svc-name)
                allocs     (distribute-service-amount svc-price items)]
          [item alloc] (map vector items allocs)
          :let [sku     (get item :sku)
                article (normalize-lookup article-lookup sku)]
          :when article]
      {:marketplace :ozon
       :rrd-id      (service-rrd-id op-id sku svc-name)
       :article     article
       :nm-id       sku
       :date-from   month-first
       :date-to     month-first
       :event-date  op-date
       :operation   "sale"
       field        alloc})))

(defn- op-level-rows
  "Build rows when the operation_type itself maps to a field (e.g. Marketing).
   Uses |operation.amount| distributed across items by price×quantity weights."
  [operation items article-lookup month-first field op-id]
  (let [amount  (get operation :amount 0)
        allocs  (distribute-service-amount amount items)
        op-date (when-let [d (get operation :operation_date)]
                  (when (>= (count d) 10) (subs d 0 10)))]
    (for [[item alloc] (map vector items allocs)
          :let  [sku     (get item :sku)
                 article (normalize-lookup article-lookup sku)]
          :when article]
      {:marketplace :ozon
       :rrd-id      (service-rrd-id op-id sku (get operation :operation_type))
       :article     article
       :nm-id       sku
       :date-from   month-first
       :date-to     month-first
       :event-date  op-date
       :operation   "sale"
       field        alloc})))

(defn tx-op->service-rows
  "Transform ONE transaction/list operation into 0..N FinanceRow contribution
   maps (one per item × service, or one per item for operation-level mapping).

   Skip conditions (return []):
     - operation :type = \"compensation\"
     - missing posting.posting_number (account-level → handled via cash_flow_periods)
     - empty items[]
     - empty services[] AND operation_type has no ozon-operation-mapping entry
     - orphan: items whose sku does not resolve via article-lookup (per-item
       drop, with a mu/log event)

   Args:
     operation       one raw operation map (see /v3/finance/transaction/list)
     article-lookup  {sku offer_id} map — sku may be Long or String

   Each returned row carries :marketplace :ozon, a deterministic :rrd-id,
   :article, :date-from / :date-to (first day of operation month), and exactly
   ONE cost field populated according to the mapping table. The row does NOT
   set :for-pay or :retail-amount — the US3B merge path only applies cost-
   fields to existing finance-rows."
  [operation article-lookup]
  (let [op-type      (get operation :operation_type)
        posting-num  (get-in operation [:posting :posting_number])
        items        (get operation :items [])
        services     (get operation :services [])
        op-field     (ozon-operation-mapping op-type)
        month-first  (op-date->month-first (get operation :operation_date))
        op-id        (get operation :operation_id)]
    (cond
      (= "compensation" (get operation :type))
      []

      (or (nil? posting-num) (str/blank? posting-num))
      []

      (empty? items)
      []

      ;; Orphan: posting not in lookup → drop all items with mu/log
      (not-any? #(normalize-lookup article-lookup (get % :sku)) items)
      (do (mu/log ::orphan-posting
                  :posting-number posting-num
                  :operation-id   op-id
                  :skus           (mapv :sku items))
          [])

      op-field
      (vec (op-level-rows operation items article-lookup month-first op-field op-id))

      (empty? services)
      []

      :else
      (vec (services-rows operation items article-lookup month-first op-id)))))

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
