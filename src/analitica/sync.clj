(ns analitica.sync
  "Row mappers and DB column definitions shared by the materialize pipeline,
   plus local 1C cost-price loader and DB status reporter.

   Legacy API-to-DB sync functions were removed on 2026-04-22: the canonical
   pipeline is now `ingest!` (API → raw_data) followed by `materialize!`
   (raw_data → analytical tables)."
  (:require [analitica.db :as db]
            [analitica.domain.cost-price :as cost-price])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(defn now-str []
  (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss")))

;; ---------------------------------------------------------------------------
;; Row mappers: domain map -> DB column vector
;; Each `*-columns` vector fixes the order that `*->row` emits.
;; ---------------------------------------------------------------------------

(defn sale->row [sale]
  [(:sale-id sale) (:date sale) (:article sale) (:nm-id sale)
   (:barcode sale) (:tech-size sale) (:subject sale) (:category sale)
   (:brand sale) (:warehouse sale) (:region sale) (name (:type sale))
   (:total-price sale) (:for-pay sale) (:finished-price sale)
   (:price-with-disc sale) (name (:marketplace sale)) (now-str)])

(def sales-columns
  [:sale_id :date :article :nm_id :barcode :tech_size :subject :category
   :brand :warehouse :region :type :total_price :for_pay :finished_price
   :price_with_disc :marketplace :synced_at])

(defn order->row [order]
  [(:order-id order) (:date order) (:article order) (:nm-id order)
   (:barcode order) (:tech-size order) (:subject order) (:category order)
   (:brand order) (:warehouse order) (:region order) (name (:status order))
   (:price order) (:price-with-disc order) (name (:marketplace order)) (now-str)])

(def orders-columns
  [:order_id :date :article :nm_id :barcode :tech_size :subject :category
   :brand :warehouse :region :status :price :price_with_disc :marketplace :synced_at])

(defn finance->row [f]
  [(:rrd-id f) (:report-id f) (:date-from f) (:date-to f)
   (:article f) (:nm-id f) (:barcode f) (:subject f) (:brand f)
   (:operation f) (:doc-type f) (:quantity f)
   (:retail-price f) (:retail-amount f) (:sale-percent f)
   (:commission-pct f) (:wb-commission f) (:wb-reward f)
   (:wb-kvw-prc f) (:spp-prc f) (:price-with-disc f)
   (:delivery-amount f) (:return-amount f) (:delivery-cost f)
   (:for-pay f) (:penalty f) (:storage-fee f) (:acceptance f)
   (:additional-payment f) (:deduction f) (:acquiring-fee f)
   (:ad-cost f)
   (name (or (:marketplace f) :wb)) (now-str)])

(def finance-columns
  [:rrd_id :report_id :date_from :date_to :article :nm_id :barcode
   :subject :brand :operation :doc_type :quantity
   :retail_price :retail_amount :sale_percent
   :commission_pct :wb_commission :wb_reward
   :wb_kvw_prc :spp_prc :price_with_disc
   :delivery_amount :return_amount :delivery_cost
   :for_pay :penalty :storage_fee :acceptance
   :additional_payment :deduction :acquiring_fee
   :ad_cost
   :marketplace :synced_at])

(defn storage->row [s]
  [(:date s) (:article s) (:nm-id s) (:barcode s) (:warehouse s)
   (:cost s) (:volume s) (:barcodes-count s)
   (name (or (:marketplace s) :wb)) (now-str)])

(def storage-columns
  [:date :article :nm_id :barcode :warehouse
   :cost :volume :barcodes_count :marketplace :synced_at])

(defn stock->row [s]
  [(:article s) (:nm-id s) (:barcode s) (:tech-size s)
   (:subject s) (:category s) (:brand s) (:warehouse s)
   (:quantity s) (:quantity-full s) (:in-way-to-client s)
   (:in-way-from-client s) (name (or (:marketplace s) :wb)) (now-str)])

(def stocks-columns
  [:article :nm_id :barcode :tech_size :subject :category :brand
   :warehouse :quantity :quantity_full :in_way_to :in_way_from
   :marketplace :synced_at])

;; ---------------------------------------------------------------------------
;; 1C cost prices: local CSV → cost_prices table.
;; Not a marketplace API; kept here because it still flows into analytical DB.
;; ---------------------------------------------------------------------------

(defn sync-1c!
  "Load cost prices from 1C CSV into SQLite."
  ([] (sync-1c! "1c/units.csv"))
  ([path]
   (cost-price/load-from-1c path)
   (let [prices (cost-price/all-prices)
         ts     (now-str)
         rows   (mapv (fn [[article price]]
                        [article "" price "" "" 0 ts])
                      prices)
         cnt    (db/insert-batch! :cost_prices
                                  [:article :barcode :cost_price :nomenclature :color :quantity_1c :updated_at]
                                  rows)]
     (println (str "Saved cost prices: " cnt " articles"))
     cnt)))

;; ---------------------------------------------------------------------------
;; Status
;; ---------------------------------------------------------------------------

(defn status
  "Show row counts per analytical table plus raw_data summary."
  []
  (println "\n=== DB status ===")
  (doseq [t [:sales :orders :finance :paid_storage :stocks
             :product_stats :prices :ad_stats :region_sales :cost_prices
             :cash_flow_periods]]
    (println (format "  %-15s %d rows" (name t) (db/count-rows t))))
  (let [raw (db/raw-status)]
    (when (seq raw)
      (println "\n=== Raw data ===")
      (doseq [r raw]
        (println (format "  %-6s %-15s %d batches, %d items  (%s .. %s)"
                         (:source r) (:entity-type r)
                         (:batch-count r) (:total-items r)
                         (or (:min-date r) "-") (or (:max-date r) "-")))))))
