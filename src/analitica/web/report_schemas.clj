(ns analitica.web.report-schemas
  "Schema registry для UI-слоя. Каждая schema описывает shape отчёта для
   generic рендера: columns (с canon-anchors), tabs, presets, kpi, drill-down.
   Domain-слой не знает про эти схемы — они живут в UI.

   T004 adds Malli ColumnDescriptor (+ Suffix/FilterType enums, :closed false)
   per data-model §1.3 + contracts/descriptor-schema.edn. All new fields are
   optional/additive — existing consumers ignore unknown keys (:closed false).

   T005 adds :canonical-metric-slugs — exhaustive kebab-keyword metric set
   per contracts/descriptor-schema.edn §CANONICAL SLUG DICTIONARY. 016 OWNS
   this set; 017 and the US5 metric constructor consume it, define nothing.")

;; ---------------------------------------------------------------------------
;; T004 — Malli schemas for column descriptor (§3.C contract, OWNED by 016)
;; ---------------------------------------------------------------------------

(def Suffix
  "Rendering unit for a metric value column.
   :rub → ₽, :pct → %, :qty → шт, :days → Дн., :ratio → dimensionless (e.g. 4.2×)."
  [:enum :rub :pct :qty :days :ratio])

(def FilterType
  "Server-side filter strategy for a column.
   :text-contains — identifier substring match.
   :number-range  — numeric min/max range (FR-022)."
  [:enum :text-contains :number-range])

(def ColumnDescriptor
  "Open Malli schema for a single report column descriptor.
   Existing keys are mandatory; the four new keys (hint/suffix/filterType/positiveIfGrow)
   are optional/additive — existing consumers that don't know them simply ignore the extra
   keys (:closed false, FR-001/R1).

   :hint          — plain-language formula string; MUST state the calculation basis
                    (gross realisation / net sales / payout). Single source of truth for
                    the ⓘ tooltip (VR-d1). Absent on identifier columns (VR-d3).
   :suffix        — rendering unit keyword (Suffix enum); absent on identifier columns.
   :filterType    — filter strategy keyword (FilterType enum).
   :positiveIfGrow — true = profit-like (green up), false = cost-like (red up),
                     omitted = neutral/identifier (VR-d4)."
  [:map {:closed false}
   [:key              :keyword]
   [:title            :string]
   [:group            :keyword]
   [:format           [:enum :text :int :rub :pct :date :ratio]]
   [:canon-anchor     {:optional true} [:maybe :string]]
   [:default-visible? {:optional true} :boolean]
   [:delta-supported? {:optional true} :boolean]
   [:linkable?        {:optional true} :boolean]
   ;; NEW — thread a (FR-001..FR-006)
   [:hint             {:optional true} [:maybe :string]]
   [:suffix           {:optional true} Suffix]
   [:filterType       {:optional true} FilterType]
   [:positiveIfGrow   {:optional true} :boolean]])

;; ---------------------------------------------------------------------------
;; 016-US3 — P&L waterfall line descriptor (data-model §4 / contracts/waterfall-response.edn)
;; ---------------------------------------------------------------------------

(def WaterfallLine
  "Open Malli schema for one P&L waterfall line (spec 016 US3, §0.1 LOCKED
   GROSS-realisation top-line). Emitted by pnl/waterfall and forwarded verbatim
   by the pnl-handler. Delta colour is driven by :positive-if-grow (FR-019);
   :delta-pct is nil (neutral) when there is no comparison value (FR-026).

   :basis states the metric's basis in the line itself (P6/FR-004):
     :gross-realisation — sales + all directExpenses children + grossMargin
     :payout            — advertising (:ad-spend basis)
     :management        — opex/tax/EBITDA/netProfit (015 seam; render 0 pre-015)."
  [:map {:closed false}
   [:key    :keyword]
   [:label  :string]
   [:amount :double]
   [:layer  [:enum :sales :direct-expense :gross-margin :advertising
                   :operating-expense :ebitda :tax :net-profit]]
   [:basis  [:enum :gross-realisation :payout :management]]
   [:positive-if-grow {:optional true} :boolean]
   [:children  {:optional true} [:vector :keyword]]
   [:delta     {:optional true} [:maybe :double]]
   [:delta-pct {:optional true} [:maybe :double]]])

;; ---------------------------------------------------------------------------
;; T005 — canonical slug dictionary (016 OWNS; 017 and US5 consume-only)
;; ---------------------------------------------------------------------------

(def canonical-metric-slugs
  "Exhaustive set of metric :key keywords that downstream consumers
   (017 bot metric picker + plan/fact, 009/US5 custom metric constructor)
   may reference. A consumer slug NOT in this set is unknown (scrubbed at
   next save). 016 is the single place to extend this vocabulary."
  #{;; revenue & profit (P&L / UE basis lines)
    :revenue :orders :net-profit :gross-margin :margin-pct
    ;; direct-expense components (waterfall children)
    :cogs :mp-commission :logistics :storage :acceptance :penalties :deduction :additional
    ;; advertising / efficiency
    :advertising :drr-pct
    ;; management-basis layers (from 015 seam; render 0 pre-015)
    :operating-expenses :ebitda :tax :vat
    ;; inventory / stock (thread b)
    :cap-by-cost :cap-by-price :gmroi :days-of-cover
    ;; classification (thread d)
    :revenue-abc :profit-abc})

;; ---------------------------------------------------------------------------
;; Report schemas
;; ---------------------------------------------------------------------------

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
     :delta-direction :inverted}
    {:key :total-loss    :title "Убытки"   :format :rub :delta-direction :inverted
     :href "/reports/losses"}]

   :column-groups
   {:identity {:title "Identity"     :anchor nil}
    :ue1      {:title "UE.1 Объём"   :anchor "UE.1"}
    :ue2      {:title "UE.2 Затраты" :anchor "UE.2"}
    :ue4      {:title "UE.4 Прибыль" :anchor "UE.4"}
    :ue7      {:title "UE.7 %"       :anchor "UE.7"}
    :per-unit {:title "UE.6 Per-unit" :anchor "UE.6"}}

   :columns
   [;; ── Identity columns — :filterType :text-contains, NO :hint/:positiveIfGrow/:suffix (VR-d3)
    {:key :article     :title "Артикул"   :group :identity :format :text :default-visible? true :linkable? true
     :filterType :text-contains}
    {:key :brand       :title "Бренд"     :group :identity :format :text :default-visible? true
     :filterType :text-contains}
    {:key :subject     :title "Категория" :group :identity :format :text :default-visible? false
     :filterType :text-contains}

    ;; ── UE.1 — volume (int/pct, no money) ──
    {:key :sales-qty   :title "Продажи"  :group :ue1 :format :int :canon-anchor "UE.1" :default-visible? true :delta-supported? true
     :suffix :qty :filterType :number-range :positiveIfGrow true}
    {:key :returns-qty :title "Возвраты" :group :ue1 :format :int :canon-anchor "UE.1" :default-visible? true
     :suffix :qty :filterType :number-range :positiveIfGrow false}
    {:key :non-return-rate :title "Доля невозвратов" :group :ue1 :format :pct :canon-anchor "UE.1" :default-visible? true
     :suffix :pct :filterType :number-range :positiveIfGrow true}

    ;; ── UE.2 — monetary (all :format :rub; each :hint MUST state basis) ──
    {:key :revenue     :title "Выручка"  :group :ue2 :format :rub :canon-anchor "UE.2" :default-visible? true :delta-supported? true
     :hint "Выручка (GROSS-реализация): сумма, которую заплатил покупатель за проданные единицы. Basis: gross realisation."
     :suffix :rub :filterType :number-range :positiveIfGrow true}
    {:key :wb-reward   :title "Возмещение ПВЗ" :group :ue2 :format :rub :canon-anchor "UE.2" :default-visible? false
     :hint "Возмещение ПВЗ (wb-reward): субсидия маркетплейса за доставку через партнёрские ПВЗ (только WB). Basis: payout (ledger-only, не cash-flow)."
     :suffix :rub :filterType :number-range :positiveIfGrow true}
    {:key :logistics   :title "Логистика" :group :ue2 :format :rub :canon-anchor "UE.2" :default-visible? false
     :hint "Логистика: расходы на доставку заказов и возвратов (тариф × перемещения). Basis: payout."
     :suffix :rub :filterType :number-range :positiveIfGrow false}
    {:key :storage     :title "Хранение"  :group :ue2 :format :rub :canon-anchor "UE.2" :default-visible? false
     :hint "Хранение: плата за складское хранение, начисляется ежедневно по тарифу маркетплейса. Basis: payout."
     :suffix :rub :filterType :number-range :positiveIfGrow false}

    ;; ── UE.4 — P&L money ──
    {:key :for-pay     :title "К оплате"      :group :ue4 :format :rub :canon-anchor "UE.2" :default-visible? false
     :hint "К оплате (for-pay / net sales): выручка за вычетом всех комиссий и удержаний маркетплейса — сумма, переведённая продавцу. Basis: net sales (payout)."
     :suffix :rub :filterType :number-range :positiveIfGrow true}
    {:key :total-cost  :title "Себестоимость" :group :ue4 :format :rub :canon-anchor "UE.2" :default-visible? false
     :hint "Себестоимость (COGS): полные затраты на производство/закупку товара. Basis: cost (из cost_prices)."
     :suffix :rub :filterType :number-range :positiveIfGrow false}
    {:key :profit      :title "Прибыль"  :group :ue4 :format :rub :canon-anchor "UE.4" :default-visible? true :delta-supported? true
     :hint "Чистая прибыль = к оплате − себестоимость − реклама. Basis: net sales (payout) минус COGS и ad-spend."
     :suffix :rub :filterType :number-range :positiveIfGrow true}

    ;; ── UE.7 — % columns ──
    {:key :margin-pct  :title "Маржа %"   :group :ue7 :format :pct :canon-anchor "UE.7" :default-visible? true
     :suffix :pct :filterType :number-range :positiveIfGrow true}
    {:key :drr-pct     :title "ДРР %"     :group :ue7 :format :pct :canon-anchor "UE.7" :default-visible? false
     :suffix :pct :filterType :number-range :positiveIfGrow false}
    {:key :wb-cost-pct :title "МП-затраты %" :group :ue7 :format :pct :canon-anchor "UE.7" :default-visible? false
     :suffix :pct :filterType :number-range :positiveIfGrow false}
    {:key :cogs-pct    :title "COGS %"    :group :ue7 :format :pct :canon-anchor "UE.7" :default-visible? false
     :suffix :pct :filterType :number-range :positiveIfGrow false}
    {:key :logistics-pct :title "Логистика %" :group :ue7 :format :pct :canon-anchor "UE.7" :default-visible? false
     :suffix :pct :filterType :number-range :positiveIfGrow false}

    ;; ── UE.6 — per-unit money columns (all :format :rub, all :hint state payout basis) ──
    {:key :revenue-per-unit    :title "Выручка/ед"      :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false
     :hint "Выручка на единицу = выручка ÷ продажи. Basis: gross realisation per unit."
     :suffix :rub :filterType :number-range :positiveIfGrow true}
    {:key :reward-per-unit     :title "Возмещение/ед"   :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false
     :hint "Возмещение ПВЗ на единицу = wb-reward ÷ продажи. Basis: payout per unit."
     :suffix :rub :filterType :number-range :positiveIfGrow true}
    {:key :logistics-per-op    :title "Логистика/опер"  :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false
     :hint "Логистика на операцию = логистика ÷ число перемещений. Basis: payout per operation."
     :suffix :rub :filterType :number-range :positiveIfGrow false}
    {:key :logistics-per-unit  :title "Логистика/ед"    :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false
     :hint "Логистика на единицу = логистика ÷ продажи. Basis: payout per unit."
     :suffix :rub :filterType :number-range :positiveIfGrow false}
    {:key :storage-per-unit    :title "Хранение/ед"     :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false
     :hint "Хранение на единицу = хранение ÷ продажи. Basis: payout per unit."
     :suffix :rub :filterType :number-range :positiveIfGrow false}
    {:key :accept-per-unit     :title "Приёмка/ед"      :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false
     :hint "Приёмка на единицу = приёмка ÷ продажи. Basis: payout per unit."
     :suffix :rub :filterType :number-range :positiveIfGrow false}
    {:key :acquiring-per-unit  :title "Эквайринг/ед"    :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false
     :hint "Эквайринг на единицу = эквайринг ÷ продажи. Basis: payout per unit."
     :suffix :rub :filterType :number-range :positiveIfGrow false}
    {:key :cost-per-unit       :title "Себестоимость/ед" :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false
     :hint "Себестоимость на единицу = COGS ÷ продажи. Basis: cost per unit."
     :suffix :rub :filterType :number-range :positiveIfGrow false}
    {:key :payout-per-unit     :title "Выплата/ед"      :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false
     :hint "К оплате на единицу = for-pay ÷ продажи. Basis: net sales (payout) per unit."
     :suffix :rub :filterType :number-range :positiveIfGrow true}
    {:key :profit-per-unit     :title "Прибыль/ед"      :group :per-unit :format :rub :canon-anchor "UE.6" :default-visible? false
     :hint "Прибыль на единицу = чистая прибыль ÷ продажи. Basis: net sales (payout) per unit."
     :suffix :rub :filterType :number-range :positiveIfGrow true}]

   :column-presets
   {:basic       [:article :brand :sales-qty :revenue :profit :margin-pct :non-return-rate]
    :full        :all
    :per-unit    [:article :revenue-per-unit :cost-per-unit :logistics-per-unit
                  :storage-per-unit :payout-per-unit :profit-per-unit]
    :percentages [:article :margin-pct :wb-cost-pct :cogs-pct :drr-pct :non-return-rate]}

   :chart
   {:type :bar :title "Топ артикулов по прибыли" :x :article :y :profit :limit 20}})

(def ^:private pnl-schema
  {:report-type       :pnl
   :title             "P&L"
   :uses-period?      true
   ;; compare-mode: compute pnl/calculate for prev period; KPI tiles show prev + delta.
   ;; No table rows (rows-mode :none) so delta-supported? columns are not applicable.
   :supports-compare? true
   :rows-mode         :none
   :tabs              [:chart :drawer]

   :kpi
   [{:key :revenue      :title "Revenue"     :format :rub :delta-from :revenue}
    {:key :gross-profit :title "Gross Profit" :format :rub :delta-from :gross-profit}
    {:key :net-profit   :title "Net Profit"  :format :rub :delta-from :net-profit}
    {:key :margin-net   :title "Net Margin"  :format :pct :delta-from :margin-net}]

   :chart
   {:type :waterfall
    :title "P&L Waterfall"
    :metrics [:revenue :mp-commission :logistics :storage :penalties
              :ad-spend :total-cost :net-profit]}

   :drawer-metrics
   [:revenue :mp-commission :wb-reward :logistics :storage :acceptance
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
             {:key :sales-count :title "Продажи" :group :volume :format :int :default-visible? true :delta-supported? true}
             {:key :returns-count :title "Возвраты" :group :volume :format :int :default-visible? true}
             {:key :revenue :title "Выручка" :group :money :format :rub :default-visible? true :delta-supported? true}
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
   :columns [{:key :article :title "Артикул" :group :identity :format :text :default-visible? true :linkable? true}
             {:key :sales-qty :title "Продажи" :group :volume :format :int :default-visible? true :delta-supported? true}
             {:key :revenue :title "Выручка" :group :money :format :rub :default-visible? true :delta-supported? true}
             {:key :wb-reward :title "Возмещение ПВЗ" :group :money :format :rub :default-visible? true}
             {:key :logistics :title "Логистика" :group :money :format :rub :default-visible? true}
             {:key :storage :title "Хранение" :group :money :format :rub :default-visible? true}
             {:key :for-pay :title "К оплате" :group :money :format :rub :default-visible? true :delta-supported? true}
             {:key :total-cost :title "Общие затраты" :group :money :format :rub :default-visible? true}]
   :column-groups {:identity {:title "Identity"} :volume {:title "Объём"} :money {:title "Деньги"}}
   :chart {:type :bar :title "Разбивка затрат" :x :article :y :for-pay :limit 20}})

(def ^:private abc-schema
  {:report-type :abc :title "ABC-анализ"
   :uses-period? true
   ;; compare-mode: revenue/qty delta columns enabled. ABC class migration (was-B-now-A)
   ;; is deferred — the compare data carries prev revenue/sales-qty and their deltas only.
   :supports-compare? true
   :rows-mode :per-article
   :tabs [:table :chart :drawer]
   :kpi [{:key :total-revenue :title "Выручка" :format :rub :delta-from :total-revenue}
         {:key :a-count :title "А-класс" :format :int}
         {:key :b-count :title "B-класс" :format :int}
         {:key :c-count :title "C-класс" :format :int}]
   :columns [{:key :article :title "Артикул" :group :identity :format :text :default-visible? true :linkable? true}
             {:key :abc-category :title "Категория" :group :identity :format :text :default-visible? true}
             {:key :cum-pct :title "Накопленный %" :group :pct :format :pct :default-visible? true}
             {:key :revenue :title "Выручка" :group :money :format :rub :default-visible? true :delta-supported? true}
             {:key :for-pay :title "К оплате" :group :money :format :rub :default-visible? true}
             {:key :sales-qty :title "Продажи" :group :volume :format :int :default-visible? true :delta-supported? true}]
   :column-groups {:identity {:title "Identity"} :pct {:title "%"} :money {:title "Деньги"} :volume {:title "Объём"}}
   :chart {:type :line :title "Парето-кривая" :x :article :y :cum-pct}})

(def ^:private stock-schema
  {:report-type :stock :title "Остатки"
   :uses-period? false :supports-compare? false :rows-mode :per-article
   :tabs [:table :chart]
   :kpi [{:key :total-quantity :title "Всего на складах" :format :int}
         {:key :total-in-way-to :title "В пути к клиенту" :format :int}
         {:key :sku-count :title "SKU" :format :int}]
   :columns [{:key :article :title "Артикул" :group :identity :format :text :default-visible? true :linkable? true
              :filterType :text-contains}
             {:key :quantity :title "Количество" :group :qty :format :int :default-visible? true
              :suffix :qty :filterType :number-range}
             {:key :quantity-full :title "Полное кол-во" :group :qty :format :int :default-visible? true
              :suffix :qty :filterType :number-range}
             {:key :in-way-to :title "В пути к клиенту" :group :qty :format :int :default-visible? true
              :suffix :qty :filterType :number-range}
             {:key :in-way-from :title "В пути от клиента" :group :qty :format :int :default-visible? true
              :suffix :qty :filterType :number-range}
             {:key :warehouses :title "Склады" :group :identity :format :text :default-visible? true
              :filterType :text-contains}
             ;; ── 016-US2 capitalization / GMROI / turnover (T023) ──
             ;; Descriptors per contracts/descriptor-schema.edn :new-metric-descriptors.
             {:key :cap-by-cost :title "Капитализация (себест.)" :group :stock :format :rub
              :default-visible? true :suffix :rub :filterType :number-range :positiveIfGrow false
              :hint "Капитализация по себестоимости = (себест. + фулфилмент + НДС) × текущий остаток. N/A без себестоимости. Basis: cost."}
             {:key :cap-by-price :title "Капитализация (цена)" :group :stock :format :rub
              :default-visible? false :suffix :rub :filterType :number-range
              ;; INTENTIONALLY neutral (no :positiveIfGrow): capital-at-retail is ambiguous
              ;; (more value vs more lock-up) — no delta colour.
              :hint "Капитализация по цене = средневзвеш. розничная цена за период × текущий остаток (period weighted-avg, не last-full-week). Basis: gross realisation."}
             {:key :gmroi :title "GMROI" :group :stock :format :ratio
              :default-visible? true :suffix :ratio :filterType :number-range :positiveIfGrow true
              :hint "GMROI = чистая прибыль ÷ средний остаток по себестоимости (среднее по дням со собранным остатком; дни без остатка исключены). Безразмерное отношение (напр. 4.2×), НЕ рубли. N/A если остаток не собирался весь период. Basis: net profit (определение TS, не учебное)."}
             {:key :days-of-cover :title "Оборачиваемость" :group :stock :format :int
              :default-visible? true :suffix :days :filterType :number-range :positiveIfGrow true
              :hint "Дней покрытия = остаток ÷ (Σ проданных штук ÷ дней периода). Используется Σ количества, не число операций."}]
   :column-groups {:identity {:title "Identity"} :qty {:title "Количество"}
                   :stock {:title "Капитализация / оборачиваемость"}}
   :chart {:type :bar :title "Остатки по артикулам" :x :article :y :quantity :limit 20}})

(def ^:private returns-schema
  {:report-type :returns :title "Возвраты"
   :uses-period? true :supports-compare? true :rows-mode :per-article
   :tabs [:table :chart :drawer]
   :kpi [{:key :total-sold :title "Продано" :format :int}
         {:key :total-returned :title "Возвращено" :format :int}
         {:key :avg-return-rate :title "% возврата" :format :pct}]
   :columns [{:key :article :title "Артикул" :group :identity :format :text :default-visible? true :linkable? true}
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
         {:key :avg-buyout-rate :title "Доля невозвратов" :format :pct}]
   ;; :non-return-rate (sales-only: sold/ops, FR-008 canonical name) is kept default-visible
   ;; alongside :true-buyout-rate (sold/placed). The cancel-rate column surfaces
   ;; the gap between the two — see canonical-formulas.md §Buyout.7.
   :columns [{:key :article          :title "Артикул"      :group :identity :format :text :default-visible? true :linkable? true}
             {:key :placed           :title "Заказано"     :group :volume   :format :int  :default-visible? true}
             {:key :bought           :title "Выкуплено"    :group :volume   :format :int  :default-visible? true}
             {:key :cancelled        :title "Отменено"     :group :volume   :format :int  :default-visible? true}
             {:key :returned         :title "Возвращено"   :group :volume   :format :int  :default-visible? true}
             {:key :ordered          :title "Операций"     :group :volume   :format :int  :default-visible? false}
             {:key :true-buyout-rate :title "% выкупа (от заказов)" :group :pct :format :pct :default-visible? true}
             {:key :non-return-rate  :title "Доля невозвратов" :group :pct :format :pct :default-visible? true}
             {:key :cancel-rate      :title "% отмен"      :group :pct      :format :pct  :default-visible? true}]
   :column-groups {:identity {:title "Identity"} :volume {:title "Объём"} :pct {:title "%"}}
   :chart {:type :bar :title "Выкуп по артикулам" :x :article :y :true-buyout-rate :limit 20}})

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
   :uses-period? true
   ;; supports-compare? intentionally false: the Trends report is ALREADY inherently
   ;; compare-based — every row carries :current, :previous, :change, :change-pct.
   ;; Adding an outer compare layer would produce compare-of-compares, which is
   ;; meaningless. Users who want cross-period trends should change the WoW/MoM window
   ;; via period-picker instead.
   :supports-compare? false
   :rows-mode :per-metric
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

(def ^:private losses-schema
  {:report-type :losses
   :title "Убытки"
   :uses-period? true
   :supports-compare? false
   :rows-mode :per-article
   :tabs [:table :chart :drawer]
   :kpi [{:key :total-loss :title "Общие потери" :format :rub :delta-direction :inverted}
         {:key :dead-stock-count :title "Мёртвый сток" :format :int}
         {:key :storage-eats-count :title "Склад ест маржу" :format :int}
         {:key :forecast-count :title "Прогноз: в убыток" :format :int}]
   :columns [{:key :article :title "Артикул" :group :identity :format :text :default-visible? true :linkable? true}
             {:key :loss-type :title "Тип" :group :identity :format :text :default-visible? true}
             {:key :sales-qty :title "Продажи" :group :volume :format :int :default-visible? true}
             {:key :storage-cost :title "Хранение" :group :money :format :rub :default-visible? true :canon-anchor "Losses.1"}
             {:key :revenue :title "Выручка" :group :money :format :rub :default-visible? true}
             {:key :profit :title "Прибыль/Убыток" :group :money :format :rub :default-visible? true}
             {:key :storage-ratio :title "Склад %" :group :pct :format :pct :default-visible? true :canon-anchor "Losses.2"}
             {:key :days-to-break-even :title "До убытка (дней)" :group :forecast :format :int :default-visible? true :canon-anchor "Losses.3"}
             {:key :suggestion :title "Рекомендация" :group :action :format :text :default-visible? true}]
   :column-groups {:identity {:title "Identity"}
                   :volume {:title "Объём"}
                   :money {:title "Деньги"}
                   :pct {:title "%"}
                   :forecast {:title "Прогноз"}
                   :action {:title "Действие"}}
   :column-presets {:basic [:article :loss-type :storage-cost :profit :suggestion]
                    :full :all
                    :dead [:article :storage-cost :profit :suggestion]
                    :margin [:article :revenue :storage-ratio :profit :suggestion]
                    :forecast [:article :profit :days-to-break-even :suggestion]}
   :chart {:type :bar :title "Топ убыточных SKU" :x :article :y :profit :limit 20}})

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
   :trends  trends-schema
   :losses  losses-schema})

(defn get-schema
  "Return schema map for report-type keyword, or nil if unknown."
  [report-type]
  (get registry report-type))

(defn all-report-types
  "Return vector of all registered report-type keywords."
  []
  (vec (keys registry)))
