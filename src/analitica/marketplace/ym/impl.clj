(ns analitica.marketplace.ym.impl
  (:require [analitica.marketplace.protocol :as proto]
            [analitica.marketplace.ym.client :as client]
            [analitica.marketplace.ym.api :as api]
            [analitica.marketplace.ym.transform :as transform])
  (:import [analitica.marketplace.ym.client YMClient]))

;; ---------------------------------------------------------------------------
;; Protocol implementation
;; ---------------------------------------------------------------------------

(extend-type YMClient
  proto/MarketplaceAPI

  (fetch-orders [this date-from date-to]
    (let [raw (api/orders this date-from date-to)]
      (transform/->orders raw)))

  (fetch-sales [this date-from date-to]
    (let [raw (api/orders this date-from date-to)]
      (transform/->sales raw)))

  (fetch-stocks [this]
    (let [raw (api/stocks this)]
      (transform/->stocks raw)))

  (fetch-finance-report [this date-from date-to]
    ;; Use order-stats as primary source — gives commission/cost breakdown
    (let [raw (api/order-stats this date-from date-to)]
      (transform/->finance-from-order-stats raw)))

  (fetch-product-stats [this date-from date-to]
    (let [resp (api/sku-stats this date-from date-to)]
      (transform/->product-stats resp)))

  (fetch-storage-costs [_this _date-from _date-to]
    [])

  (fetch-ad-campaigns [_this]
    [])

  (fetch-prices [this]
    (let [raw (api/prices this)]
      (transform/->prices raw))))
