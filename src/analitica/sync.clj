(ns analitica.sync
  (:require [analitica.db :as db]
            [analitica.marketplace.protocol :as proto]
            [analitica.marketplace.registry :as registry]
            [analitica.domain.cost-price :as cost-price]
            [analitica.util.time :as t]
            [clojure.string :as str])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(defn- now-str []
  (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss")))

(defn- resolve-period [period]
  (if (keyword? period)
    (t/period period)
    [(:from period) (:to period)]))

(defn- get-mp [marketplace]
  (registry/get-marketplace (or marketplace :wb)))

;; ---------------------------------------------------------------------------
;; Sales sync
;; ---------------------------------------------------------------------------

(defn- sale->row [sale]
  [(:sale-id sale) (:date sale) (:article sale) (:nm-id sale)
   (:barcode sale) (:tech-size sale) (:subject sale) (:category sale)
   (:brand sale) (:warehouse sale) (:region sale) (name (:type sale))
   (:total-price sale) (:for-pay sale) (:finished-price sale)
   (:price-with-disc sale) (name (:marketplace sale)) (now-str)])

(def ^:private sales-columns
  [:sale_id :date :article :nm_id :barcode :tech_size :subject :category
   :brand :warehouse :region :type :total_price :for_pay :finished_price
   :price_with_disc :marketplace :synced_at])

(defn sync-sales!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to] (resolve-period period)
        mp        (get-mp marketplace)
        data      (proto/fetch-sales mp from to)
        rows      (mapv sale->row data)
        cnt       (db/insert-batch! :sales sales-columns rows)]
    (println (str "Синхронизировано продаж: " cnt))
    cnt))

;; ---------------------------------------------------------------------------
;; Orders sync
;; ---------------------------------------------------------------------------

(defn- order->row [order]
  [(:order-id order) (:date order) (:article order) (:nm-id order)
   (:barcode order) (:tech-size order) (:subject order) (:category order)
   (:brand order) (:warehouse order) (:region order) (name (:status order))
   (:price order) (:price-with-disc order) (name (:marketplace order)) (now-str)])

(def ^:private orders-columns
  [:order_id :date :article :nm_id :barcode :tech_size :subject :category
   :brand :warehouse :region :status :price :price_with_disc :marketplace :synced_at])

(defn sync-orders!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to] (resolve-period period)
        mp        (get-mp marketplace)
        data      (proto/fetch-orders mp from to)
        rows      (mapv order->row data)
        cnt       (db/insert-batch! :orders orders-columns rows)]
    (println (str "Синхронизировано заказов: " cnt))
    cnt))

;; ---------------------------------------------------------------------------
;; Finance sync
;; ---------------------------------------------------------------------------

(defn- finance->row [f]
  [(:rrd-id f) (:report-id f) (:date-from f) (:date-to f)
   (:article f) (:nm-id f) (:barcode f) (:subject f) (:brand f)
   (:operation f) (:quantity f) (:retail-amount f) (:delivery-cost f)
   (:for-pay f) (:penalty f) (:storage-fee f) (:acceptance f)
   (:additional-payment f) (:commission-pct f) (:price-with-disc f)
   (name (or (:marketplace f) :wb)) (now-str)])

(def ^:private finance-columns
  [:rrd_id :report_id :date_from :date_to :article :nm_id :barcode
   :subject :brand :operation :quantity :retail_amount :delivery_cost
   :for_pay :penalty :storage_fee :acceptance :additional_payment
   :commission_pct :price_with_disc :marketplace :synced_at])

(defn sync-finance!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to] (resolve-period period)
        mp        (get-mp marketplace)
        data      (proto/fetch-finance-report mp from to)
        rows      (mapv finance->row data)
        cnt       (db/insert-batch! :finance finance-columns rows)]
    (println (str "Синхронизировано финансовых записей: " cnt))
    cnt))

;; ---------------------------------------------------------------------------
;; Stocks sync (full replace)
;; ---------------------------------------------------------------------------

(defn- stock->row [s]
  [(:article s) (:nm-id s) (:barcode s) (:tech-size s)
   (:subject s) (:category s) (:brand s) (:warehouse s)
   (:quantity s) (:quantity-full s) (:in-way-to-client s)
   (:in-way-from-client s) (name (or (:marketplace s) :wb)) (now-str)])

(def ^:private stocks-columns
  [:article :nm_id :barcode :tech_size :subject :category :brand
   :warehouse :quantity :quantity_full :in_way_to :in_way_from
   :marketplace :synced_at])

(defn sync-stocks!
  [& {:keys [marketplace] :or {marketplace :wb}}]
  (let [mp   (get-mp marketplace)
        data (proto/fetch-stocks mp)]
    (db/clear-table! :stocks)
    (let [rows (mapv stock->row data)
          cnt  (db/insert-batch! :stocks stocks-columns rows)]
      (println (str "Синхронизировано остатков: " cnt))
      cnt)))

;; ---------------------------------------------------------------------------
;; 1C cost prices sync
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
     (println (str "Себестоимость сохранена в БД: " cnt " артикулов"))
     cnt)))

;; ---------------------------------------------------------------------------
;; Combined sync
;; ---------------------------------------------------------------------------

(defn sync!
  "Sync data from API to SQLite.
   Usage:
     (sync! :sales :last-30-days)
     (sync! :finance {:from \"2026-03-01\" :to \"2026-03-31\"})
     (sync! :stocks)
     (sync! :all :last-30-days)"
  [what & [period & {:keys [marketplace] :or {marketplace :wb} :as opts}]]
  (case what
    :sales   (sync-sales! period :marketplace marketplace)
    :orders  (sync-orders! period :marketplace marketplace)
    :finance (sync-finance! period :marketplace marketplace)
    :stocks  (sync-stocks! :marketplace marketplace)
    :1c      (sync-1c!)
    :all     (do
               (println "\n=== Полная синхронизация ===")
               (sync-sales! (or period :last-30-days) :marketplace marketplace)
               (sync-orders! (or period :last-30-days) :marketplace marketplace)
               (sync-finance! (or period :last-30-days) :marketplace marketplace)
               (sync-stocks! :marketplace marketplace)
               (println "=== Синхронизация завершена ===\n")
               (println "  sales:"   (db/count-rows :sales))
               (println "  orders:"  (db/count-rows :orders))
               (println "  finance:" (db/count-rows :finance))
               (println "  stocks:"  (db/count-rows :stocks)))))

(defn status
  "Show sync status — row counts per table."
  []
  (println "\n=== Статус БД ===")
  (doseq [t [:sales :orders :finance :stocks :cost_prices]]
    (println (format "  %-15s %d записей" (name t) (db/count-rows t)))))
