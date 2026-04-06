(ns analitica.marketplace.wb.impl
  (:require [analitica.marketplace.protocol :as proto]
            [analitica.marketplace.wb.api :as api]
            [analitica.marketplace.wb.transform :as t]
            [analitica.marketplace.wb.client])
  (:import [analitica.marketplace.wb.client WBClient]))

(extend-type WBClient
  proto/MarketplaceAPI

  (fetch-orders [this date-from date-to]
    ;; flag=0: all changes since date-from (up to 100k rows)
    ;; WB API ignores date-to for this endpoint — filtering done client-side
    (->> (api/orders this date-from :flag 0)
         t/->orders
         (filterv #(and (<= (compare date-from (subs (:date %) 0 10)) 0)
                        (>= (compare date-to   (subs (:date %) 0 10)) 0)))))

  (fetch-sales [this date-from date-to]
    ;; flag=0: all changes since date-from
    (->> (api/sales this date-from :flag 0)
         t/->sales
         (filterv #(and (<= (compare date-from (subs (:date %) 0 10)) 0)
                        (>= (compare date-to   (subs (:date %) 0 10)) 0)))))

  (fetch-stocks [this]
    (t/->stocks (api/stocks this)))

  (fetch-finance-report [this date-from date-to]
    (t/->finance-report (api/report-detail-by-period-all this date-from date-to)))

  (fetch-product-stats [this date-from date-to]
    (t/->product-stats (api/nm-report-detail this date-from date-to)))

  (fetch-storage-costs [this date-from date-to]
    ;; Async flow: create → poll → download
    (let [task    (api/paid-storage-create this date-from date-to)
          task-id (:data (:taskId task))]
      (when task-id
        (loop [attempts 0]
          (Thread/sleep 5000)
          (let [status (api/paid-storage-status this task-id)]
            (cond
              (= "done" (:data (:status status)))
              (api/paid-storage-download this task-id)

              (>= attempts 24) ; 2 minutes max wait
              (throw (ex-info "Paid storage task timed out" {:task-id task-id}))

              :else
              (recur (inc attempts))))))))

  (fetch-ad-campaigns [this]
    (api/ad-campaigns this))

  (fetch-prices [this]
    (t/->prices (api/prices this))))
