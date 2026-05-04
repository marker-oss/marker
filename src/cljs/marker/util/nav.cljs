(ns marker.util.nav
  "Pure helpers for SPA navigation: section/tab structure,
   legacy URL redirects.

   The SPA uses a 5-section sidebar (Главная, Финансы, Товары,
   Динамика, Синхронизация); the latter three sections render an
   internal tabs strip and are addressed by [:section :tab] vectors.

   Page values across re-frame:
   - keyword              : single-page route, e.g. :pulse, :sync
   - [:section :tab]      : sectioned route, e.g. [:finance :pnl]")

(def ^:private SECTION-TABS
  "Ordered tab definitions per section."
  {:finance  [{:id :pnl        :label "P&L"}
              {:id :unit-calc  :label "Юнит-эк (калькулятор)"}
              {:id :unit-table :label "Юнит-эк (таблица)"}
              {:id :returns    :label "Возвраты"}
              {:id :losses     :label "Потери"}
              {:id :finance    :label "Финансовый отчёт"}
              {:id :plan-fact  :label "План/Факт"}]
   :products [{:id :skus        :label "SKU-список"}
              {:id :stocks      :label "Склады"}
              {:id :abc         :label "ABC"}
              {:id :cost-prices :label "Себестоимость"}
              {:id :storage     :label "Хранение"}]
   :dynamics [{:id :trends :label "Тренды"}
              {:id :sales  :label "Продажи"}
              {:id :geo    :label "География"}
              {:id :buyout :label "Выкуп"}]})

(def ^:private LEGACY-MAP
  "Map old route value → new [:section :tab].
   Keys are either keywords (single-page legacy routes) or
   vectors `[:report :type]` (parameterised report routes).

   Any URL not in this map is treated as unknown and routed to
   :pulse by callers."
  {:pnl               [:finance :pnl]
   :unit              [:finance :unit-calc]
   :cost-prices       [:products :cost-prices]
   [:report :ue]      [:finance :unit-table]
   [:report :returns] [:finance :returns]
   [:report :losses]  [:finance :losses]
   [:report :finance] [:finance :finance]
   [:report :stock]   [:products :stocks]
   [:report :abc]     [:products :abc]
   [:report :trends]  [:dynamics :trends]
   [:report :sales]   [:dynamics :sales]
   [:report :geo]     [:dynamics :geo]
   [:report :buyout]  [:dynamics :buyout]})

(defn legacy-redirect
  "Given an old route value, return [:section :tab] or nil."
  [old]
  (get LEGACY-MAP old))

(defn section-tabs
  "Ordered list of {:id :label} for a section, or empty vector."
  [section]
  (or (get SECTION-TABS section) []))

(defn default-tab
  "Default :tab for a section (first tab), or nil if section has
   no tabs (e.g. single-page :pulse / :sync)."
  [section]
  (some-> (first (section-tabs section)) :id))

(defn valid-tab?
  "True if tab-id is a valid tab inside the given section."
  [section tab-id]
  (boolean (some #(= tab-id (:id %)) (section-tabs section))))
