(ns analitica.domain.pnl
  (:require [analitica.domain.finance :as finance]
            [analitica.domain.cost-price :as cost-price]
            [analitica.db :as db]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.math :as math]))

(defn calculate
  "Build P&L from finance data + cost prices.
   Returns a map with all P&L line items."
  [finance-data]
  (let [by-art        (finance/by-article finance-data)
        revenue       (reduce + 0.0 (map :revenue by-art))
        wb-reward     (reduce + 0.0 (map :wb-reward by-art))
        logistics     (reduce + 0.0 (map :logistics by-art))
        storage       (reduce + 0.0 (map :storage by-art))
        acceptance    (reduce + 0.0 (map :acceptance by-art))
        penalties     (reduce + 0.0 (map :penalties by-art))
        for-pay       (reduce + 0.0 (map :for-pay by-art))
        cogs          (reduce + 0.0 (map :total-cost by-art))
        ad-spend      (or (try
                            (:spend (first (db/query ["SELECT sum(spend) as spend FROM ad_stats"])))
                            (catch Exception _ nil))
                          0.0)
        gross-profit  (- for-pay cogs)
        net-profit    (- gross-profit ad-spend)
        sales-qty     (reduce + 0 (map :sales-qty by-art))
        returns-qty   (reduce + 0 (map :returns-qty by-art))
        net-qty       (- sales-qty returns-qty)]
    {:revenue       (math/round2 revenue)
     :wb-reward     (math/round2 wb-reward)
     :logistics     (math/round2 logistics)
     :storage       (math/round2 storage)
     :acceptance    (math/round2 acceptance)
     :penalties     (math/round2 penalties)
     :for-pay       (math/round2 for-pay)
     :cogs          (math/round2 cogs)
     :ad-spend      (math/round2 (double ad-spend))
     :gross-profit  (math/round2 gross-profit)
     :net-profit    (math/round2 net-profit)
     :margin-gross  (math/percentage gross-profit revenue)
     :margin-net    (math/percentage net-profit revenue)
     :sales-qty     sales-qty
     :returns-qty   returns-qty
     :buyout-rate   (math/percentage sales-qty (+ sales-qty returns-qty))
     :avg-check     (math/round2 (math/safe-div revenue sales-qty))
     :profit-per-sale (math/round2 (math/safe-div net-profit net-qty))
     :articles      (count by-art)}))

(defn report
  "Print P&L report."
  [period & {:keys [marketplace source] :or {marketplace :wb source :db}}]
  (println "\nЗагрузка P&L...")
  (let [fin-data (finance/fetch-finance period :marketplace marketplace :source source)
        pnl      (calculate fin-data)]

    (table/print-summary
     "P&L (ОТЧЁТ О ПРИБЫЛЯХ И УБЫТКАХ)"
     [["ДОХОДЫ" nil]
      ["  Выручка (розница)"    (:revenue pnl)]
      ["  Вознаграждение WB"    (:wb-reward pnl)]
      ["  К выплате от WB"      (:for-pay pnl)]
      ["" nil]
      ["РАСХОДЫ" nil]
      ["  Себестоимость"        (:cogs pnl)]
      ["  Логистика WB"         (:logistics pnl)]
      ["  Хранение WB"          (:storage pnl)]
      ["  Приёмка WB"           (:acceptance pnl)]
      ["  Штрафы WB"            (:penalties pnl)]
      ["  Реклама"              (:ad-spend pnl)]
      ["" nil]
      ["РЕЗУЛЬТАТ" nil]
      ["  Валовая прибыль"      (:gross-profit pnl)]
      ["  Чистая прибыль"       (:net-profit pnl)]
      ["  Маржа валовая"        (str (:margin-gross pnl) "%")]
      ["  Маржа чистая"         (str (:margin-net pnl) "%")]
      ["" nil]
      ["ПОКАЗАТЕЛИ" nil]
      ["  Продажи"              (:sales-qty pnl)]
      ["  Возвраты"             (:returns-qty pnl)]
      ["  % выкупа"             (str (:buyout-rate pnl) "%")]
      ["  Средний чек"          (:avg-check pnl)]
      ["  Прибыль на продажу"   (:profit-per-sale pnl)]
      ["  Артикулов"            (:articles pnl)]])

    pnl))

(defn export-excel
  [period path & opts]
  (let [fin-data (apply finance/fetch-finance period opts)
        pnl      (calculate fin-data)
        pnl-rows [{:line "REVENUE"            :amount (:revenue pnl)}
                   {:line "  WB Reward"       :amount (:wb-reward pnl)}
                   {:line "  WB Payout"       :amount (:for-pay pnl)}
                   {:line "" :amount nil}
                   {:line "EXPENSES" :amount nil}
                   {:line "  COGS"            :amount (:cogs pnl)}
                   {:line "  Logistics"       :amount (:logistics pnl)}
                   {:line "  Storage"         :amount (:storage pnl)}
                   {:line "  Acceptance"      :amount (:acceptance pnl)}
                   {:line "  Penalties"       :amount (:penalties pnl)}
                   {:line "  Advertising"     :amount (:ad-spend pnl)}
                   {:line "" :amount nil}
                   {:line "GROSS PROFIT"      :amount (:gross-profit pnl)}
                   {:line "NET PROFIT"        :amount (:net-profit pnl)}
                   {:line "Gross Margin %"    :amount (:margin-gross pnl)}
                   {:line "Net Margin %"      :amount (:margin-net pnl)}
                   {:line "" :amount nil}
                   {:line "Sales qty"         :amount (:sales-qty pnl)}
                   {:line "Returns qty"       :amount (:returns-qty pnl)}
                   {:line "Buyout rate %"     :amount (:buyout-rate pnl)}
                   {:line "Avg check"         :amount (:avg-check pnl)}
                   {:line "Profit per sale"   :amount (:profit-per-sale pnl)}]]
    (export/to-excel path "PnL" [[:line "Line"] [:amount "Amount RUB"]] pnl-rows)))
