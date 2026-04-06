(ns analitica.domain.ads
  (:require [analitica.marketplace.protocol :as proto]
            [analitica.marketplace.registry :as registry]
            [analitica.marketplace.wb.api :as wb-api]
            [analitica.report.table :as table]
            [analitica.util.math :as math]))

(defn- get-mp [marketplace]
  (registry/get-marketplace (or marketplace :wb)))

(defn fetch-campaigns
  [& {:keys [marketplace] :or {marketplace :wb}}]
  (proto/fetch-ad-campaigns (get-mp marketplace)))

(defn fetch-product-stats
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [mp      (get-mp marketplace)
        [from to] (if (keyword? period)
                    (analitica.util.time/period period)
                    [(:from period) (:to period)])]
    (proto/fetch-product-stats mp from to)))

;; ---------------------------------------------------------------------------
;; Analysis
;; ---------------------------------------------------------------------------

(defn product-funnel
  "Product funnel: views → cart → orders → buyouts with conversion rates."
  [product-stats]
  (->> product-stats
       (map (fn [p]
              (let [views    (or (:views p) 0)
                    cart     (or (:add-to-cart p) 0)
                    orders   (or (:orders p) 0)
                    buyouts  (or (:buyouts p) 0)]
                (assoc p
                       :cart-rate   (math/percentage cart views)
                       :order-rate  (math/percentage orders views)
                       :buyout-rate (math/percentage buyouts orders)))))
       (sort-by #(or (:orders-sum %) 0) >)))

(defn overview
  "Print advertising overview with product funnel."
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (println "\nЗагрузка статистики товаров...")
  (let [stats   (fetch-product-stats period :marketplace marketplace)
        funnel  (product-funnel stats)
        totals  {:total-views   (reduce + 0 (map #(or (:views %) 0) funnel))
                 :total-cart    (reduce + 0 (map #(or (:add-to-cart %) 0) funnel))
                 :total-orders  (reduce + 0 (map #(or (:orders %) 0) funnel))
                 :total-buyouts (reduce + 0 (map #(or (:buyouts %) 0) funnel))
                 :total-revenue (math/round2 (reduce + 0.0 (map #(or (:orders-sum %) 0) funnel)))}]

    (table/print-summary
     "ВОРОНКА ПРОДАЖ"
     [["Просмотры"       (:total-views totals)]
      ["В корзину"       (:total-cart totals)]
      ["Заказы"          (:total-orders totals)]
      ["Выкупы"          (:total-buyouts totals)]
      ["Сумма заказов"   (:total-revenue totals)]
      ["Конверсия в корзину" (str (math/percentage (:total-cart totals) (:total-views totals)) "%")]
      ["Конверсия в заказ"   (str (math/percentage (:total-orders totals) (:total-views totals)) "%")]
      ["% выкупа"            (str (math/percentage (:total-buyouts totals) (:total-orders totals)) "%")]])

    (println "\n── Топ-20 товаров по заказам ──")
    (table/print-table
     [[:nm-id "NM ID"] [:article "Артикул"] [:views "Просм."]
      [:add-to-cart "Корзина"] [:orders "Заказы"] [:buyouts "Выкупы"]
      [:orders-sum "Сумма"] [:cart-rate "Корз.%"] [:order-rate "Заказ%"]
      [:buyout-rate "Выкуп%"]]
     (take 20 funnel))

    totals))
