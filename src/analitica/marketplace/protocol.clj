(ns analitica.marketplace.protocol)

(defprotocol MarketplaceAPI
  "Core data retrieval contract. Each marketplace (WB, Ozon, YM) implements this."

  (fetch-orders [this date-from date-to]
    "Fetch orders for the period. Returns seq of order maps.")

  (fetch-sales [this date-from date-to]
    "Fetch sales and returns for the period. Returns seq of sale maps.")

  (fetch-stocks [this]
    "Fetch current stock levels. Returns seq of stock maps.")

  (fetch-finance-report [this date-from date-to]
    "Fetch financial report (commission, logistics, penalties, payouts).
     Returns seq of finance-line maps.")

  (fetch-product-stats [this date-from date-to]
    "Fetch product statistics (views, cart adds, orders, buyouts, conversion).
     Returns seq of product-stat maps.")

  (fetch-storage-costs [this date-from date-to]
    "Fetch paid storage costs. May be async (create task, poll, download).
     Returns seq of storage-cost maps.")

  (fetch-ad-campaigns [this]
    "Fetch active ad campaigns with stats. Returns seq of campaign maps.")

  (fetch-prices [this]
    "Fetch current prices and discounts. Returns seq of price maps."))
