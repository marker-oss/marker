(ns analitica.web.report-schemas
  "Schema registry для UI-слоя. Каждая schema описывает shape отчёта для
   generic рендера: columns (с canon-anchors), tabs, presets, kpi, drill-down.
   Domain-слой не знает про эти схемы — они живут в UI.")

(def ^:private ue-schema
  {:report-type       :ue
   :title             "Юнит-экономика"
   :uses-period?      true
   :supports-compare? true
   :rows-mode         :per-article

   :tabs              [:table :chart :drawer]

   :kpi
   [{:key :total-revenue :title "Выручка"  :format :rub :delta-from :revenue}
    {:key :total-profit  :title "Прибыль"  :format :rub :delta-from :profit}
    {:key :margin-pct    :title "Маржа"    :format :pct :delta-from :margin}
    {:key :drr-pct       :title "ДРР"      :format :pct :delta-from :drr
     :delta-direction :inverted}]

   :column-groups
   {:identity {:title "Identity"     :anchor nil}
    :ue1      {:title "UE.1 Объём"   :anchor "UE.1"}
    :ue2      {:title "UE.2 Затраты" :anchor "UE.2"}
    :ue4      {:title "UE.4 Прибыль" :anchor "UE.4"}
    :ue7      {:title "UE.7 %"       :anchor "UE.7"}
    :per-unit {:title "UE.6 Per-unit" :anchor "UE.6"}}

   :columns
   [{:key :article     :title "Артикул"  :group :identity :format :text :default-visible? true}
    {:key :brand       :title "Бренд"    :group :identity :format :text :default-visible? true}
    {:key :subject     :title "Категория" :group :identity :format :text :default-visible? false}
    {:key :sales-qty   :title "Продажи"  :group :ue1 :format :int :canon-anchor "UE.1" :default-visible? true}
    {:key :returns-qty :title "Возвраты" :group :ue1 :format :int :canon-anchor "UE.1" :default-visible? true}
    {:key :buyout-rate :title "% выкупа" :group :ue1 :format :pct :canon-anchor "UE.1" :default-visible? true}
    {:key :revenue     :title "Выручка"  :group :ue2 :format :rub :canon-anchor "UE.2" :default-visible? true}
    {:key :wb-reward   :title "wb-reward" :group :ue2 :format :rub :canon-anchor "UE.2" :default-visible? false}
    {:key :logistics   :title "Логистика" :group :ue2 :format :rub :canon-anchor "UE.2" :default-visible? false}
    {:key :storage     :title "Хранение"  :group :ue2 :format :rub :canon-anchor "UE.2" :default-visible? false}
    {:key :for-pay     :title "for-pay"   :group :ue4 :format :rub :canon-anchor "UE.2" :default-visible? false}
    {:key :total-cost  :title "Себестоимость" :group :ue4 :format :rub :canon-anchor "UE.2" :default-visible? false}
    {:key :profit      :title "Прибыль"  :group :ue4 :format :rub :canon-anchor "UE.4" :default-visible? true}
    {:key :margin-pct  :title "Маржа %"  :group :ue7 :format :pct :canon-anchor "UE.7" :default-visible? true}
    {:key :drr-pct     :title "ДРР %"    :group :ue7 :format :pct :canon-anchor "UE.7" :default-visible? false}
    {:key :wb-cost-pct :title "МП-затраты %" :group :ue7 :format :pct :canon-anchor "UE.7" :default-visible? false}
    {:key :cogs-pct    :title "COGS %"   :group :ue7 :format :pct :canon-anchor "UE.7" :default-visible? false}
    {:key :logistics-pct :title "Логистика %" :group :ue7 :format :pct :canon-anchor "UE.7" :default-visible? false}
    {:key :revenue-per-unit :title "Выручка/ед" :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false}
    {:key :reward-per-unit :title "wb-reward/ед" :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false}
    {:key :logistics-per-op :title "Логистика/опер" :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false}
    {:key :logistics-per-unit :title "Логистика/ед" :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false}
    {:key :storage-per-unit :title "Хранение/ед" :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false}
    {:key :accept-per-unit :title "Приёмка/ед" :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false}
    {:key :acquiring-per-unit :title "Эквайринг/ед" :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false}
    {:key :cost-per-unit :title "Себестоимость/ед" :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false}
    {:key :payout-per-unit :title "Выплата/ед" :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false}
    {:key :profit-per-unit :title "Прибыль/ед" :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false}]

   :column-presets
   {:basic       [:article :brand :sales-qty :revenue :profit :margin-pct :buyout-rate]
    :full        :all
    :per-unit    [:article :revenue-per-unit :cost-per-unit :logistics-per-unit
                  :storage-per-unit :payout-per-unit :profit-per-unit]
    :percentages [:article :margin-pct :wb-cost-pct :cogs-pct :drr-pct :buyout-rate]}

   :chart
   {:type :bar :title "Топ артикулов по прибыли" :x :article :y :profit :limit 20}})

(def ^:private pnl-schema
  {:report-type       :pnl
   :title             "P&L"
   :uses-period?      true
   :supports-compare? false
   :rows-mode         :none
   :tabs              [:chart :drawer]

   :kpi
   [{:key :revenue      :title "Revenue"     :format :rub}
    {:key :gross-profit :title "Gross Profit" :format :rub}
    {:key :net-profit   :title "Net Profit"  :format :rub}
    {:key :margin-net   :title "Net Margin"  :format :pct}]

   :chart
   {:type :waterfall
    :title "P&L Waterfall"
    :metrics [:revenue :wb-commission :logistics :storage :penalties
              :ad-spend :total-cost :net-profit]}

   :drawer-metrics
   [:revenue :wb-commission :wb-reward :logistics :storage :acceptance
    :penalties :acquiring :deduction :additional :ad-spend :total-cost
    :gross-profit :net-profit :margin-gross :margin-net]})

(def ^:private sales-schema
  {:report-type :sales :title "Продажи"
   :uses-period? true :supports-compare? true :rows-mode :per-period
   :tabs [:table :chart :drawer]
   :kpi [{:key :total-revenue :title "Выручка" :format :rub}
         {:key :total-sales :title "Продажи" :format :int}
         {:key :total-returns :title "Возвраты" :format :int}]
   :columns [{:key :group :title "Дата" :group :identity :format :date :default-visible? true}
             {:key :sales-count :title "Продажи" :group :volume :format :int :default-visible? true}
             {:key :returns-count :title "Возвраты" :group :volume :format :int :default-visible? true}
             {:key :revenue :title "Выручка" :group :money :format :rub :default-visible? true}
             {:key :avg-price :title "Средняя цена" :group :money :format :rub :default-visible? true}]
   :column-groups {:identity {:title "Identity"}
                   :volume   {:title "Объём"}
                   :money    {:title "Деньги"}}
   :chart {:type :line :title "Динамика продаж" :x :group :y :revenue}})

(def ^:private finance-schema
  {:report-type :finance :title "Финансы"
   :uses-period? true :supports-compare? true :rows-mode :per-article
   :tabs [:table :chart :drawer]
   :kpi [{:key :total-revenue :title "Выручка" :format :rub}
         {:key :total-for-pay :title "К оплате" :format :rub}
         {:key :total-cost :title "Затраты" :format :rub}]
   :columns [{:key :article :title "Артикул" :group :identity :format :text :default-visible? true}
             {:key :sales-qty :title "Продажи" :group :volume :format :int :default-visible? true}
             {:key :revenue :title "Выручка" :group :money :format :rub :default-visible? true}
             {:key :wb-reward :title "Вознаграждение WB" :group :money :format :rub :default-visible? true}
             {:key :logistics :title "Логистика" :group :money :format :rub :default-visible? true}
             {:key :storage :title "Хранение" :group :money :format :rub :default-visible? true}
             {:key :for-pay :title "К оплате" :group :money :format :rub :default-visible? true}
             {:key :total-cost :title "Общие затраты" :group :money :format :rub :default-visible? true}]
   :column-groups {:identity {:title "Identity"} :volume {:title "Объём"} :money {:title "Деньги"}}
   :chart {:type :bar :title "Разбивка затрат" :x :article :y :for-pay :limit 20}})

(def ^:private abc-schema
  {:report-type :abc :title "ABC-анализ"
   :uses-period? true :supports-compare? false :rows-mode :per-article
   :tabs [:table :chart :drawer]
   :kpi [{:key :total-revenue :title "Выручка" :format :rub}
         {:key :a-count :title "А-класс" :format :int}
         {:key :b-count :title "B-класс" :format :int}
         {:key :c-count :title "C-класс" :format :int}]
   :columns [{:key :article :title "Артикул" :group :identity :format :text :default-visible? true}
             {:key :abc-category :title "Категория" :group :identity :format :text :default-visible? true}
             {:key :cum-pct :title "Накопленный %" :group :pct :format :pct :default-visible? true}
             {:key :revenue :title "Выручка" :group :money :format :rub :default-visible? true}
             {:key :for-pay :title "К оплате" :group :money :format :rub :default-visible? true}
             {:key :sales-qty :title "Продажи" :group :volume :format :int :default-visible? true}]
   :column-groups {:identity {:title "Identity"} :pct {:title "%"} :money {:title "Деньги"} :volume {:title "Объём"}}
   :chart {:type :line :title "Парето-кривая" :x :article :y :cum-pct}})

(def ^:private stock-schema
  {:report-type :stock :title "Остатки"
   :uses-period? false :supports-compare? false :rows-mode :per-article
   :tabs [:table :chart]
   :kpi [{:key :total-quantity :title "Всего на складах" :format :int}
         {:key :total-in-way-to :title "В пути к клиенту" :format :int}
         {:key :sku-count :title "SKU" :format :int}]
   :columns [{:key :article :title "Артикул" :group :identity :format :text :default-visible? true}
             {:key :quantity :title "Количество" :group :qty :format :int :default-visible? true}
             {:key :quantity-full :title "Полное кол-во" :group :qty :format :int :default-visible? true}
             {:key :in-way-to :title "В пути к клиенту" :group :qty :format :int :default-visible? true}
             {:key :in-way-from :title "В пути от клиента" :group :qty :format :int :default-visible? true}
             {:key :warehouses :title "Склады" :group :identity :format :text :default-visible? true}]
   :column-groups {:identity {:title "Identity"} :qty {:title "Количество"}}
   :chart {:type :bar :title "Остатки по артикулам" :x :article :y :quantity :limit 20}})

(def ^:private returns-schema
  {:report-type :returns :title "Возвраты"
   :uses-period? true :supports-compare? true :rows-mode :per-article
   :tabs [:table :chart :drawer]
   :kpi [{:key :total-sold :title "Продано" :format :int}
         {:key :total-returned :title "Возвращено" :format :int}
         {:key :avg-return-rate :title "% возврата" :format :pct}]
   :columns [{:key :article :title "Артикул" :group :identity :format :text :default-visible? true}
             {:key :sold :title "Продано" :group :volume :format :int :default-visible? true}
             {:key :returned :title "Возвращено" :group :volume :format :int :default-visible? true}
             {:key :total :title "Всего" :group :volume :format :int :default-visible? true}
             {:key :return-rate :title "% возврата" :group :pct :format :pct :default-visible? true}]
   :column-groups {:identity {:title "Identity"} :volume {:title "Объём"} :pct {:title "%"}}
   :chart {:type :line :title "Динамика возвратов" :x :article :y :return-rate}})

(def ^:private buyout-schema
  {:report-type :buyout :title "Выкуп"
   :uses-period? true :supports-compare? true :rows-mode :per-article
   :tabs [:table :chart :drawer]
   :kpi [{:key :total-ordered :title "Заказано" :format :int}
         {:key :total-bought :title "Выкуплено" :format :int}
         {:key :avg-buyout-rate :title "% выкупа" :format :pct}]
   :columns [{:key :article :title "Артикул" :group :identity :format :text :default-visible? true}
             {:key :ordered :title "Заказано" :group :volume :format :int :default-visible? true}
             {:key :bought :title "Выкуплено" :group :volume :format :int :default-visible? true}
             {:key :returned :title "Возвращено" :group :volume :format :int :default-visible? true}
             {:key :buyout-rate :title "% выкупа" :group :pct :format :pct :default-visible? true}]
   :column-groups {:identity {:title "Identity"} :volume {:title "Объём"} :pct {:title "%"}}
   :chart {:type :bar :title "Выкуп по артикулам" :x :article :y :buyout-rate :limit 20}})

(def ^:private geo-schema
  {:report-type :geo :title "География"
   :uses-period? true :supports-compare? false :rows-mode :per-region
   :tabs [:table :chart]
   :kpi [{:key :total-sum :title "Выручка" :format :rub}
         {:key :total-qty :title "Заказов" :format :int}
         {:key :region-count :title "Регионов" :format :int}]
   :columns [{:key :region :title "Регион" :group :identity :format :text :default-visible? true}
             {:key :qty :title "Количество" :group :volume :format :int :default-visible? true}
             {:key :sum :title "Сумма" :group :money :format :rub :default-visible? true}]
   :column-groups {:identity {:title "Identity"} :volume {:title "Объём"} :money {:title "Деньги"}}
   :chart {:type :bar :title "Продажи по регионам" :x :region :y :sum :limit 20}})

(def ^:private trends-schema
  {:report-type :trends :title "Тренды"
   :uses-period? true :supports-compare? false :rows-mode :per-metric
   :tabs [:table :chart]
   :kpi [{:key :revenue-current :title "Выручка сейчас" :format :rub}
         {:key :orders-current :title "Заказы сейчас" :format :int}
         {:key :profit-current :title "Прибыль сейчас" :format :rub}]
   :columns [{:key :metric :title "Метрика" :group :identity :format :text :default-visible? true}
             {:key :current :title "Текущий" :group :money :format :rub :default-visible? true}
             {:key :previous :title "Предыдущий" :group :money :format :rub :default-visible? true}
             {:key :change :title "Изменение" :group :money :format :rub :default-visible? true}
             {:key :change-pct :title "Изменение %" :group :pct :format :pct :default-visible? true}]
   :column-groups {:identity {:title "Identity"} :money {:title "Деньги"} :pct {:title "%"}}
   :chart {:type :bar :title "WoW/MoM" :x :metric :y :change-pct}})

(def ^:private registry
  {:ue      ue-schema
   :pnl     pnl-schema
   :sales   sales-schema
   :finance finance-schema
   :abc     abc-schema
   :stock   stock-schema
   :returns returns-schema
   :buyout  buyout-schema
   :geo     geo-schema
   :trends  trends-schema})

(defn get-schema
  "Return schema map for report-type keyword, or nil if unknown."
  [report-type]
  (get registry report-type))

(defn all-report-types
  "Return vector of all registered report-type keywords."
  []
  (vec (keys registry)))
