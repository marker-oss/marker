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
    :ue7      {:title "UE.7 %"       :anchor "UE.7"}}

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
    {:key :drr-pct     :title "ДРР %"    :group :ue7 :format :pct :canon-anchor "UE.7" :default-visible? false}]

   :column-presets
   {:basic       [:article :brand :sales-qty :revenue :profit :margin-pct :buyout-rate]
    :full        :all-default-visible
    :per-unit    [:article :revenue-per-unit :cost-per-unit :logistics-per-unit
                  :storage-per-unit :payout-per-unit :profit-per-unit]
    :percentages [:article :margin-pct :wb-cost-pct :cogs-pct :drr-pct :buyout-rate]}

   :chart
   {:type :bar :title "Топ артикулов по прибыли" :x :article :y :profit :limit 20}})

(def ^:private registry
  {:ue ue-schema})

(defn get-schema
  "Return schema map for report-type keyword, or nil if unknown."
  [report-type]
  (get registry report-type))

(defn all-report-types
  "Return vector of all registered report-type keywords."
  []
  (vec (keys registry)))
