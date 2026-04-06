(ns analitica.domain.prices
  (:require [analitica.marketplace.protocol :as proto]
            [analitica.marketplace.registry :as registry]
            [analitica.report.table :as table]
            [analitica.util.math :as math]))

(defn- get-mp [marketplace]
  (registry/get-marketplace (or marketplace :wb)))

(defn fetch-prices
  [& {:keys [marketplace] :or {marketplace :wb}}]
  (proto/fetch-prices (get-mp marketplace)))

(defn current
  "Print current prices and discounts."
  [& {:keys [marketplace] :or {marketplace :wb}}]
  (println "\nЗагрузка цен...")
  (let [data (fetch-prices :marketplace marketplace)]

    (table/print-summary
     "ЦЕНЫ И СКИДКИ"
     [["Всего товаров" (count data)]])

    (println "\n── Все товары ──")
    (table/print-table
     [[:nm-id "NM ID"] [:article "Артикул"] [:price "Цена"]
      [:discount "Скидка%"] [:club-disc "WB Club%"]]
     (sort-by :price > data))

    data))

(defn high-discount
  "Show items with discount above threshold."
  [threshold & {:keys [marketplace] :or {marketplace :wb}}]
  (let [data (fetch-prices :marketplace marketplace)
        high (filter #(and (:discount %) (> (:discount %) threshold)) data)]

    (table/print-summary
     (str "СКИДКА > " threshold "%")
     [["Товаров" (count high)]])

    (when (seq high)
      (table/print-table
       [[:nm-id "NM ID"] [:article "Артикул"] [:price "Цена"]
        [:discount "Скидка%"]]
       (sort-by :discount > high)))

    high))
