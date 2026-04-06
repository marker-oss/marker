(ns analitica.domain.unit-economics
  (:require [analitica.domain.finance :as finance]
            [analitica.domain.cost-price :as cost-price]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.math :as math]))

(defn calculate
  "Calculate unit economics with full WB cost breakdown.

   For each article, shows the full chain from retail price to profit:

   Выручка (retail_amount)
     — Вознаграждение WB (wb_reward = комиссия + НДС)
     — Логистика (delivery_rub = доставка + возвраты)
     — Хранение (storage_fee)
     — Приёмка (acceptance)
     — Штрафы (penalty)
     — Эквайринг (acquiring_fee)
     — Удержания (deduction)
     + Компенсация СПП (for_pay > revenue когда WB доплачивает)
   = К выплате (for_pay)
     — Себестоимость (из 1С по баркоду)
   = ПРИБЫЛЬ"
  [finance-data]
  (let [by-art (finance/by-article finance-data)]
    (->> by-art
         (map (fn [{:keys [sales-qty returns-qty revenue wb-reward wb-commission
                           acquiring logistics storage acceptance penalties
                           for-pay total-cost spp-amount] :as row}]
                (let [net-qty           (max 1 (- sales-qty returns-qty))
                      total-ops        (max 1 (+ sales-qty returns-qty))
                      ;; Все удержания WB
                      wb-reward-v      (or wb-reward 0)
                      logistics-v      (or logistics 0)
                      storage-v        (or storage 0)
                      acceptance-v     (or acceptance 0)
                      penalties-v      (or penalties 0)
                      acquiring-v      (or acquiring 0)
                      total-wb-costs   (+ wb-reward-v logistics-v storage-v
                                         acceptance-v penalties-v acquiring-v)
                      ;; СПП компенсация (когда for_pay > revenue)
                      spp-v            (or spp-amount 0)
                      ;; Прибыль
                      profit           (- for-pay total-cost)
                      ;; На единицу
                      revenue-per-unit (math/round2 (math/safe-div revenue sales-qty))
                      reward-per-unit  (math/round2 (math/safe-div wb-reward-v sales-qty))
                      logistics-per-unit (math/round2 (math/safe-div logistics-v total-ops))
                      storage-per-unit (math/round2 (math/safe-div storage-v net-qty))
                      accept-per-unit  (math/round2 (math/safe-div acceptance-v net-qty))
                      acquiring-per-unit (math/round2 (math/safe-div acquiring-v sales-qty))
                      cost-per-unit    (math/round2 (math/safe-div total-cost sales-qty))
                      payout-per-unit  (math/round2 (math/safe-div for-pay net-qty))
                      profit-per-unit  (math/round2 (math/safe-div profit net-qty))
                      margin-pct       (math/percentage profit revenue)
                      ;; Доля каждой статьи в выручке
                      wb-cost-pct      (math/percentage total-wb-costs revenue)
                      cogs-pct         (math/percentage total-cost revenue)
                      logistics-pct    (math/percentage logistics-v revenue)]
                  (assoc row
                         :total-wb-costs     (math/round2 total-wb-costs)
                         :spp-compensation   (math/round2 spp-v)
                         :profit             (math/round2 profit)
                         ;; Per unit
                         :revenue-per-unit   revenue-per-unit
                         :reward-per-unit    reward-per-unit
                         :logistics-per-unit logistics-per-unit
                         :storage-per-unit   storage-per-unit
                         :accept-per-unit    accept-per-unit
                         :acquiring-per-unit acquiring-per-unit
                         :cost-per-unit      cost-per-unit
                         :payout-per-unit    payout-per-unit
                         :profit-per-unit    profit-per-unit
                         ;; Percentages
                         :margin-pct         margin-pct
                         :wb-cost-pct        wb-cost-pct
                         :cogs-pct           cogs-pct
                         :logistics-pct      logistics-pct))))
         (sort-by :profit >))))

(defn totals [ue-data]
  (let [sum (fn [k] (reduce + 0.0 (map #(or (k %) 0) ue-data)))
        total-revenue   (sum :revenue)
        total-for-pay   (sum :for-pay)
        total-cost      (sum :total-cost)
        total-profit    (sum :profit)
        total-logistics (sum :logistics)
        total-storage   (sum :storage)
        total-wb-reward (sum :wb-reward)
        total-acceptance (sum :acceptance)
        total-penalties (sum :penalties)
        total-acquiring (sum :acquiring)
        total-wb-costs  (sum :total-wb-costs)
        total-spp       (sum :spp-compensation)
        sales-qty       (reduce + 0 (map :sales-qty ue-data))
        returns-qty     (reduce + 0 (map :returns-qty ue-data))
        net-qty         (- sales-qty returns-qty)]
    {:total-revenue    (math/round2 total-revenue)
     :total-wb-reward  (math/round2 total-wb-reward)
     :total-logistics  (math/round2 total-logistics)
     :total-storage    (math/round2 total-storage)
     :total-acceptance (math/round2 total-acceptance)
     :total-penalties  (math/round2 total-penalties)
     :total-acquiring  (math/round2 total-acquiring)
     :total-wb-costs   (math/round2 total-wb-costs)
     :total-spp        (math/round2 total-spp)
     :total-for-pay    (math/round2 total-for-pay)
     :total-cost       (math/round2 total-cost)
     :total-profit     (math/round2 total-profit)
     :margin-pct       (math/percentage total-profit total-revenue)
     :wb-cost-pct      (math/percentage total-wb-costs total-revenue)
     :cogs-pct         (math/percentage total-cost total-revenue)
     :profit-per-sale  (math/round2 (math/safe-div total-profit net-qty))
     :avg-check        (math/round2 (math/safe-div total-revenue sales-qty))
     :sales-qty        sales-qty
     :returns-qty      returns-qty
     :cost-loaded      (pos? total-cost)}))

(defn report
  [period & {:keys [marketplace source] :or {marketplace :wb source :db}}]
  (println "\nЗагрузка данных для юнит-экономики...")
  (let [fin-data (finance/fetch-finance period :marketplace marketplace :source source)
        ue-data  (calculate fin-data)
        s        (totals ue-data)]

    (when-not (:cost-loaded s)
      (println "\n! Себестоимость не загружена. Загрузите:")
      (println "  (cost-price/load-from-1c)   ;; из 1С")
      (println "  (sync/sync! :1c)            ;; сохранить в БД"))

    (table/print-summary
     "ЮНИТ-ЭКОНОМИКА — ПОЛНАЯ РАЗБИВКА"
     [["Выручка (розница)"      (:total-revenue s)]
      ["" nil]
      ["ИЗДЕРЖКИ WB:" nil]
      ["  Комиссия WB"          (:total-wb-reward s)]
      ["  Логистика"            (:total-logistics s)]
      ["  Хранение"             (:total-storage s)]
      ["  Приёмка"              (:total-acceptance s)]
      ["  Штрафы"               (:total-penalties s)]
      ["  Эквайринг"            (:total-acquiring s)]
      ["  Итого издержки WB"    (:total-wb-costs s)]
      ["  % от выручки"         (str (:wb-cost-pct s) "%")]
      ["" nil]
      ["  Компенсация СПП"      (:total-spp s)]
      ["= К выплате от WB"      (:total-for-pay s)]
      ["" nil]
      ["СЕБЕСТОИМОСТЬ:"          (:total-cost s)]
      ["  % от выручки"         (str (:cogs-pct s) "%")]
      ["" nil]
      ["= ПРИБЫЛЬ"              (:total-profit s)]
      ["  Маржа от выручки"     (str (:margin-pct s) "%")]
      ["  Прибыль на 1 продажу" (:profit-per-sale s)]
      ["  Средний чек"          (:avg-check s)]
      ["  Продажи/Возвраты"     (str (:sales-qty s) "/" (:returns-qty s))]])

    (println "\n── Топ-20 по прибыли (на единицу) ──")
    (table/print-table
     [[:article "Артикул"] [:sales-qty "Прод"]
      [:revenue-per-unit "Цена"] [:cost-per-unit "Себ/шт"]
      [:reward-per-unit "Ком/шт"] [:logistics-per-unit "Лог/шт"]
      [:storage-per-unit "Скл/шт"] [:payout-per-unit "Выпл/шт"]
      [:profit-per-unit "Приб/шт"] [:margin-pct "Маржа%"]]
     (take 20 ue-data))

    (println "\n── Убыточные артикулы ──")
    (let [losers (filter #(neg? (:profit %)) ue-data)]
      (if (seq losers)
        (table/print-table
         [[:article "Артикул"] [:sales-qty "Прод"]
          [:revenue-per-unit "Цена"] [:cost-per-unit "Себ/шт"]
          [:logistics-per-unit "Лог/шт"] [:payout-per-unit "Выпл/шт"]
          [:profit-per-unit "Приб/шт"] [:margin-pct "Маржа%"]]
         losers)
        (println "  Убыточных артикулов нет.")))

    (println "\n── Где больше всего теряем (% издержек WB от выручки) ──")
    (table/print-table
     [[:article "Артикул"] [:sales-qty "Прод"] [:revenue "Выручка"]
      [:total-wb-costs "Издержки WB"] [:wb-cost-pct "WB%"]
      [:logistics-pct "Лог%"] [:total-cost "Себест"] [:profit "Прибыль"]]
     (->> ue-data
          (filter #(pos? (:sales-qty %)))
          (sort-by :wb-cost-pct >)
          (take 15)))

    s))

;; ---------------------------------------------------------------------------
;; Export
;; ---------------------------------------------------------------------------

(def ^:private ue-export-cols
  [[:article "Article"] [:brand "Brand"] [:subject "Subject"]
   [:sales-qty "Sales"] [:returns-qty "Returns"]
   [:revenue "Revenue"] [:revenue-per-unit "Price/unit"]
   [:cost-per-unit "COGS/unit"] [:total-cost "COGS total"] [:cogs-pct "COGS%"]
   [:wb-reward "WB Commission"] [:reward-per-unit "Commission/unit"]
   [:logistics "Logistics"] [:logistics-per-unit "Logistics/unit"] [:logistics-pct "Logistics%"]
   [:storage "Storage"] [:storage-per-unit "Storage/unit"]
   [:acceptance "Acceptance"] [:penalties "Penalties"] [:acquiring "Acquiring"]
   [:total-wb-costs "Total WB costs"] [:wb-cost-pct "WB costs%"]
   [:spp-compensation "SPP compensation"]
   [:for-pay "WB Payout"] [:payout-per-unit "Payout/unit"]
   [:profit "Profit"] [:profit-per-unit "Profit/unit"] [:margin-pct "Margin%"]])

(defn export-excel [period path & opts]
  (let [fin-data (apply finance/fetch-finance period opts)
        ue-data  (calculate fin-data)]
    (export/to-excel path "Unit Economics" ue-export-cols ue-data)))
