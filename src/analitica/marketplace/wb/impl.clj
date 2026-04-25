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
    ;; WB API ignores date-to for this endpoint, filtering done client-side
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
    (loop [page 1 result []]
      (let [resp  (api/nm-report-detail this date-from date-to :page page)
            cards (get-in resp [:data :cards] [])]
        (if (empty? cards)
          result
          (recur (inc page) (into result (t/->product-stats resp)))))))

  (fetch-storage-costs [this date-from date-to]
    ;; Async flow: create -> poll -> download
    (let [task-id (get-in (api/paid-storage-create this date-from date-to) [:data :taskId])]
      (when-not task-id
        (throw (ex-info "Paid storage task id is missing"
                        {:date-from date-from :date-to date-to})))
      (loop [attempts 0]
        (Thread/sleep 5000)
        (let [status (get-in (api/paid-storage-status this task-id) [:data :status])]
          (cond
            (= "done" status)
            (t/->storage-costs (or (api/paid-storage-download this task-id) []))

            (#{"error" "failed"} status)
            (throw (ex-info "Paid storage task failed"
                            {:task-id task-id :status status}))

            (>= attempts 24) ; 2 minutes max wait
            (throw (ex-info "Paid storage task timed out"
                            {:task-id task-id :status status}))

            :else
            (recur (inc attempts)))))))

  (fetch-ad-campaigns [this]
    (api/ad-campaigns this))

  (fetch-prices [this]
    (loop [offset 0 result []]
      (let [page  (api/prices this :limit 1000 :offset offset)
            items (get-in page [:data :listGoods] [])]
        (if (empty? items)
          result
          (recur (+ offset 1000) (into result (t/->prices page))))))))
