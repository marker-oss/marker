(ns analitica.web.server
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.resource :as resource]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [hiccup.core]
            [analitica.db :as db]
            [analitica.core :as core]
            [analitica.util.time :as time]
            [analitica.util.period :as period]
            [analitica.web.layout :as layout]
            [analitica.web.pages.sync :as sync-page]
            [analitica.web.pages.dashboard :as dashboard-page]
            [analitica.web.pages.digest :as digest-page]
            [analitica.web.pages.plan]
            [analitica.web.pages.reports :as reports-page]
            [analitica.web.api.sync :as sync-api]
            [analitica.sync.plan :as sync-plan]
            [analitica.sync.executor :as sync-executor]
            [analitica.sync.registry :as sync-registry]
            [analitica.sync.runner :as sync-runner]
            [analitica.sync.scheduler :as sync-scheduler]
            [analitica.web.api.sync-coverage :as sync-coverage]
            [analitica.web.api.metrics :as metrics-api]
            [analitica.web.api.charts :as charts-api]
            [analitica.web.api.export :as export-api]
            [analitica.web.api.cost-prices :as cost-prices-api]
            [analitica.web.api.report :as report]
            [analitica.web.api.detail :as detail]
            [analitica.web.api.coverage :as coverage]
            [analitica.web.api.sku :as sku-api]
            [analitica.web.api.search :as search-api]
            [analitica.web.report-schemas :as rs]
            [analitica.domain.losses :as losses]
            [jsonista.core :as json])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Utilities
;; ---------------------------------------------------------------------------

;; Note: parse-period is now in analitica.util.time namespace

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(def valid-periods
  #{"last-week" "last-7-days" "last-30-days" "this-month"})

(def valid-marketplaces
  #{"wb" "ozon" "ym"})

(def valid-report-types
  #{"sales" "finance" "ue" "pnl" "abc" "stock" "returns" "buyout" "geo" "trends" "losses"})

(def valid-sync-what
  #{"sales" "orders" "finance" "storage" "stocks" "stats" "prices" "regions" "1c" "all"})

;; Superset of valid-sync-what for the plan-based /run endpoint —
;; adds cashflow (Ozon-only) which the legacy /start did not expose.
(def valid-run-what
  #{"sales" "orders" "finance" "storage" "stocks" "stats" "prices" "regions" "cashflow" "all"})

(defn- validate-run-what
  "Validate 'what' for the /api/sync/run endpoint. Returns keyword if valid, nil otherwise."
  [what-str]
  (when (and what-str (valid-run-what what-str))
    (keyword what-str)))

(defn validate-period
  "Validate period parameter. Returns period string if valid, nil otherwise.
  Also accepts custom date ranges in format YYYY-MM-DD,YYYY-MM-DD"
  [period-str]
  (when period-str
    (if (valid-periods period-str)
      period-str
      ;; Check for custom date range format
      (when (re-matches #"\d{4}-\d{2}-\d{2},\d{4}-\d{2}-\d{2}" period-str)
        period-str))))

(defn validate-marketplace
  "Validate marketplace parameter. Returns keyword if valid, nil otherwise."
  [mp-str]
  (when (and mp-str (valid-marketplaces mp-str))
    (keyword mp-str)))

(defn validate-report-type
  "Validate report-type parameter. Returns keyword if valid, nil otherwise."
  [type-str]
  (when (and type-str (valid-report-types type-str))
    (keyword type-str)))

(defn validate-sync-what
  "Validate sync what parameter. Returns keyword if valid, nil otherwise."
  [what-str]
  (when (and what-str (valid-sync-what what-str))
    (keyword what-str)))

(defn- resolve-period-from-params
  "Return a period value suitable for report-data:
   - If ?from AND ?to are present and match YYYY-MM-DD → {:from str :to str}
   - Else if ?period is a valid named period or date-range → keyword (named) or string (date-range)
   - Else fall back to :last-30-days

   Returns nil only when params are explicitly invalid (e.g. ?from present but ?to absent,
   or ?from has wrong format). Callers should 400 on nil."
  [params]
  (let [from       (get params :from)
        to         (get params :to)
        period-str (get params :period)
        iso-date?  #(and (seq %) (re-matches #"\d{4}-\d{2}-\d{2}" %))]
    (cond
      ;; Both from and to provided — validate both
      (and (seq from) (seq to))
      (if (and (iso-date? from) (iso-date? to))
        {:from from :to to}
        nil) ; malformed dates → signal 400

      ;; Only one of from/to provided — invalid partial range → 400
      (or (seq from) (seq to))
      nil

      ;; Named ?period param present — validate then resolve via parse-period
      ;; so that every string (including "last-week") becomes a {:from :to} map.
      ;; parse-period handles all valid-periods strings and date-range strings.
      (seq period-str)
      (when (validate-period period-str)
        (try
          (let [[from to] (time/parse-period period-str)]
            {:from from :to to})
          (catch Exception _ nil)))

      ;; Nothing provided — safe default: last 30 days as {:from :to} map.
      ;; Use period/default-state so the server's default range matches the
      ;; picker chip exactly (today − 29 → today, 30 days inclusive).
      :else
      (select-keys (period/default-state) [:from :to]))))

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

(defroutes app-routes
  ;; Pages
  (GET  "/plan" req (analitica.web.pages.plan/get-handler  req))
  (POST "/plan" req (analitica.web.pages.plan/post-handler req))
  ;; Phase 2: GET / now points to the action-first digest page.
  ;; Legacy summary-page kept at /dashboard/summary.
  (GET "/" {params :params}
    (let [from (get params :from)
          to   (get params :to)
          mp   (get params :marketplace)]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (layout/page "Главная"
                          (digest-page/page {:from from :to to :marketplace mp})
                          :active-route "/")}))
  ;; Legacy dashboard at /dashboard/summary (Phase 2: keep for backward compat)
  (GET "/dashboard/summary" {params :params}
    (if-let [period (resolve-period-from-params params)]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (layout/page "Dashboard (legacy)"
                          (dashboard-page/summary-page period)
                          :active-route "/")}
      {:status 400
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (layout/page "Ошибка"
                          [:div.text-center.py-12
                           [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверный параметр"]
                           [:p.text-gray-600 "Недопустимое значение периода"]]
                          :active-route "/")}))
  (GET "/wb" {params :params}
    (if-let [period (resolve-period-from-params params)]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (layout/page "Wildberries"
                          (dashboard-page/marketplace-dashboard :wb period)
                          :active-route "/wb")}
      {:status 400
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (layout/page "Ошибка"
                          [:div.text-center.py-12
                           [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверный параметр"]
                           [:p.text-gray-600 "Недопустимое значение периода"]]
                          :active-route "/wb")}))
  (GET "/ozon" {params :params}
    (if-let [period (resolve-period-from-params params)]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (layout/page "Ozon"
                          (dashboard-page/marketplace-dashboard :ozon period)
                          :active-route "/ozon")}
      {:status 400
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (layout/page "Ошибка"
                          [:div.text-center.py-12
                           [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверный параметр"]
                           [:p.text-gray-600 "Недопустимое значение периода"]]
                          :active-route "/ozon")}))
  (GET "/ym" {params :params}
    (if-let [period (resolve-period-from-params params)]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (layout/page "Yandex.Market"
                          (dashboard-page/marketplace-dashboard :ym period)
                          :active-route "/ym")}
      {:status 400
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (layout/page "Ошибка"
                          [:div.text-center.py-12
                           [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверный параметр"]
                           [:p.text-gray-600 "Недопустимое значение периода"]]
                          :active-route "/ym")}))
  (GET "/sync" [] 
    {:status 200 
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (layout/page "Синхронизация" 
                        (sync-page/sync-page)
                        :active-route "/sync")})
  
  ;; Report routes - all 10 report types
  (GET "/reports/sales" {params :params}
    (let [period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))
          compare-kw      (if (= (get params :compare) "prev") :prev :none)
          schema          (rs/get-schema :sales)
          hide-period?    (false? (:uses-period? schema))
          supports-compare? (not (false? (:supports-compare? schema)))]
      (if (and period-arg
               (or validated-mp (nil? marketplace-str) (= marketplace-str "all")))
        (let [data          (try (report/report-data :sales period-arg :marketplace validated-mp :compare compare-kw) (catch Exception _ {:rows [] :totals {}}))
              totals        (:totals data)
              show-no-data? (and (empty? (:rows data)) (empty? totals))]
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (layout/page "Отчёт: Продажи"
                              (reports-page/report-page :sales period-arg validated-mp
                                                        :show-no-data show-no-data? :totals totals
                                                        :compare (:compare data))
                              :active-route "/reports/sales"
                              :hide-period? hide-period?
                              :supports-compare? supports-compare?)})
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout/page "Ошибка"
                            [:div.text-center.py-12
                             [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверные параметры"]
                             [:p.text-gray-600 "Проверьте значения period/from/to и marketplace"]]
                            :active-route "/reports/sales")})))
  (GET "/reports/finance" {params :params}
    (let [period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))
          compare-kw      (if (= (get params :compare) "prev") :prev :none)
          schema          (rs/get-schema :finance)
          hide-period?    (false? (:uses-period? schema))
          supports-compare? (not (false? (:supports-compare? schema)))]
      (if (and period-arg
               (or validated-mp (nil? marketplace-str) (= marketplace-str "all")))
        (let [data          (try (report/report-data :finance period-arg :marketplace validated-mp :compare compare-kw) (catch Exception _ {:rows [] :totals {}}))
              totals        (:totals data)
              show-no-data? (and (empty? (:rows data)) (empty? totals))]
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (layout/page "Отчёт: Финансы"
                              (reports-page/report-page :finance period-arg validated-mp
                                                        :show-no-data show-no-data? :totals totals
                                                        :compare (:compare data))
                              :active-route "/reports/finance"
                              :hide-period? hide-period?
                              :supports-compare? supports-compare?)})
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout/page "Ошибка"
                            [:div.text-center.py-12
                             [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверные параметры"]
                             [:p.text-gray-600 "Проверьте значения period/from/to и marketplace"]]
                            :active-route "/reports/finance")})))
  (GET "/reports/ue" {params :params}
    (let [period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          article-str     (get params :article)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))
          compare-kw      (if (= (get params :compare) "prev") :prev :none)
          schema          (rs/get-schema :ue)
          hide-period?    (false? (:uses-period? schema))
          supports-compare? (not (false? (:supports-compare? schema)))]
      (if (and period-arg
               (or validated-mp (nil? marketplace-str) (= marketplace-str "all")))
        (let [data          (try (report/report-data :ue period-arg :marketplace validated-mp :article article-str :compare compare-kw) (catch Exception _ {:rows [] :totals {}}))
              losses-totals (try (:totals (losses/calculate period-arg :marketplace validated-mp)) (catch Exception _ {}))
              totals        (merge (:totals data) (select-keys losses-totals [:total-loss]))
              show-no-data? (and (empty? (:rows data)) (empty? totals))]
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (layout/page "Отчёт: Юнит-экономика"
                              (reports-page/report-page :ue period-arg validated-mp
                                                        :article article-str :show-no-data show-no-data? :totals totals
                                                        :compare (:compare data))
                              :active-route "/reports/ue"
                              :hide-period? hide-period?
                              :supports-compare? supports-compare?)})
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout/page "Ошибка"
                            [:div.text-center.py-12
                             [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверные параметры"]
                             [:p.text-gray-600 "Проверьте значения period/from/to и marketplace"]]
                            :active-route "/reports/ue")})))
  (GET "/reports/pnl" {params :params}
    (let [period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))
          compare-kw      (if (= (get params :compare) "prev") :prev :none)
          schema          (rs/get-schema :pnl)
          hide-period?    (false? (:uses-period? schema))
          supports-compare? (not (false? (:supports-compare? schema)))]
      (if (and period-arg
               (or validated-mp (nil? marketplace-str) (= marketplace-str "all")))
        (let [data          (try (report/report-data :pnl period-arg :marketplace validated-mp :compare compare-kw) (catch Exception _ {:rows [] :totals {}}))
              totals        (:totals data)
              show-no-data? (and (empty? (:rows data)) (empty? totals))]
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (layout/page "Отчёт: P&L"
                              (reports-page/report-page :pnl period-arg validated-mp
                                                        :show-no-data show-no-data? :totals totals
                                                        :compare (:compare data))
                              :active-route "/reports/pnl"
                              :hide-period? hide-period?
                              :supports-compare? supports-compare?)})
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout/page "Ошибка"
                            [:div.text-center.py-12
                             [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверные параметры"]
                             [:p.text-gray-600 "Проверьте значения period/from/to и marketplace"]]
                            :active-route "/reports/pnl")})))
  (GET "/reports/abc" {params :params}
    (let [period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))
          compare-kw      (if (= (get params :compare) "prev") :prev :none)
          schema          (rs/get-schema :abc)
          hide-period?    (false? (:uses-period? schema))
          supports-compare? (not (false? (:supports-compare? schema)))]
      (if (and period-arg
               (or validated-mp (nil? marketplace-str) (= marketplace-str "all")))
        (let [data          (try (report/report-data :abc period-arg :marketplace validated-mp :compare compare-kw) (catch Exception _ {:rows [] :totals {}}))
              totals        (:totals data)
              show-no-data? (and (empty? (:rows data)) (empty? totals))]
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (layout/page "Отчёт: ABC-анализ"
                              (reports-page/report-page :abc period-arg validated-mp
                                                        :show-no-data show-no-data? :totals totals
                                                        :compare (:compare data))
                              :active-route "/reports/abc"
                              :hide-period? hide-period?
                              :supports-compare? supports-compare?)})
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout/page "Ошибка"
                            [:div.text-center.py-12
                             [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверные параметры"]
                             [:p.text-gray-600 "Проверьте значения period/from/to и marketplace"]]
                            :active-route "/reports/abc")})))
  (GET "/reports/stock" {params :params}
    (let [period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))
          compare-kw      (if (= (get params :compare) "prev") :prev :none)
          schema          (rs/get-schema :stock)
          hide-period?    (false? (:uses-period? schema))
          supports-compare? (not (false? (:supports-compare? schema)))]
      (if (and period-arg
               (or validated-mp (nil? marketplace-str) (= marketplace-str "all")))
        (let [data          (try (report/report-data :stock period-arg :marketplace validated-mp :compare compare-kw) (catch Exception _ {:rows [] :totals {}}))
              totals        (:totals data)
              show-no-data? (and (empty? (:rows data)) (empty? totals))]
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (layout/page "Отчёт: Остатки"
                              (reports-page/report-page :stock period-arg validated-mp
                                                        :show-no-data show-no-data? :totals totals
                                                        :compare (:compare data))
                              :active-route "/reports/stock"
                              :hide-period? hide-period?
                              :supports-compare? supports-compare?)})
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout/page "Ошибка"
                            [:div.text-center.py-12
                             [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверные параметры"]
                             [:p.text-gray-600 "Проверьте значения period/from/to и marketplace"]]
                            :active-route "/reports/stock")})))
  (GET "/reports/returns" {params :params}
    (let [period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))
          compare-kw      (if (= (get params :compare) "prev") :prev :none)
          schema          (rs/get-schema :returns)
          hide-period?    (false? (:uses-period? schema))
          supports-compare? (not (false? (:supports-compare? schema)))]
      (if (and period-arg
               (or validated-mp (nil? marketplace-str) (= marketplace-str "all")))
        (let [data          (try (report/report-data :returns period-arg :marketplace validated-mp :compare compare-kw) (catch Exception _ {:rows [] :totals {}}))
              totals        (:totals data)
              show-no-data? (and (empty? (:rows data)) (empty? totals))]
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (layout/page "Отчёт: Возвраты"
                              (reports-page/report-page :returns period-arg validated-mp
                                                        :show-no-data show-no-data? :totals totals
                                                        :compare (:compare data))
                              :active-route "/reports/returns"
                              :hide-period? hide-period?
                              :supports-compare? supports-compare?)})
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout/page "Ошибка"
                            [:div.text-center.py-12
                             [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверные параметры"]
                             [:p.text-gray-600 "Проверьте значения period/from/to и marketplace"]]
                            :active-route "/reports/returns")})))
  (GET "/reports/buyout" {params :params}
    (let [period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))
          compare-kw      (if (= (get params :compare) "prev") :prev :none)
          schema          (rs/get-schema :buyout)
          hide-period?    (false? (:uses-period? schema))
          supports-compare? (not (false? (:supports-compare? schema)))]
      (if (and period-arg
               (or validated-mp (nil? marketplace-str) (= marketplace-str "all")))
        (let [data          (try (report/report-data :buyout period-arg :marketplace validated-mp :compare compare-kw) (catch Exception _ {:rows [] :totals {}}))
              totals        (:totals data)
              show-no-data? (and (empty? (:rows data)) (empty? totals))]
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (layout/page "Отчёт: Выкуп"
                              (reports-page/report-page :buyout period-arg validated-mp
                                                        :show-no-data show-no-data? :totals totals
                                                        :compare (:compare data))
                              :active-route "/reports/buyout"
                              :hide-period? hide-period?
                              :supports-compare? supports-compare?)})
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout/page "Ошибка"
                            [:div.text-center.py-12
                             [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверные параметры"]
                             [:p.text-gray-600 "Проверьте значения period/from/to и marketplace"]]
                            :active-route "/reports/buyout")})))
  (GET "/reports/geo" {params :params}
    (let [period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))
          compare-kw      (if (= (get params :compare) "prev") :prev :none)
          schema          (rs/get-schema :geo)
          hide-period?    (false? (:uses-period? schema))
          supports-compare? (not (false? (:supports-compare? schema)))]
      (if (and period-arg
               (or validated-mp (nil? marketplace-str) (= marketplace-str "all")))
        (let [data          (try (report/report-data :geo period-arg :marketplace validated-mp :compare compare-kw) (catch Exception _ {:rows [] :totals {}}))
              totals        (:totals data)
              show-no-data? (and (empty? (:rows data)) (empty? totals))]
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (layout/page "Отчёт: География"
                              (reports-page/report-page :geo period-arg validated-mp
                                                        :show-no-data show-no-data? :totals totals
                                                        :compare (:compare data))
                              :active-route "/reports/geo"
                              :hide-period? hide-period?
                              :supports-compare? supports-compare?)})
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout/page "Ошибка"
                            [:div.text-center.py-12
                             [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверные параметры"]
                             [:p.text-gray-600 "Проверьте значения period/from/to и marketplace"]]
                            :active-route "/reports/geo")})))
  (GET "/reports/trends" {params :params}
    (let [period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))
          compare-kw      (if (= (get params :compare) "prev") :prev :none)
          schema          (rs/get-schema :trends)
          hide-period?    (false? (:uses-period? schema))
          supports-compare? (not (false? (:supports-compare? schema)))]
      (if (and period-arg
               (or validated-mp (nil? marketplace-str) (= marketplace-str "all")))
        (let [data          (try (report/report-data :trends period-arg :marketplace validated-mp :compare compare-kw) (catch Exception _ {:rows [] :totals {}}))
              totals        (:totals data)
              show-no-data? (and (empty? (:rows data)) (empty? totals))]
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (layout/page "Отчёт: Тренды"
                              (reports-page/report-page :trends period-arg validated-mp
                                                        :show-no-data show-no-data? :totals totals
                                                        :compare (:compare data))
                              :active-route "/reports/trends"
                              :hide-period? hide-period?
                              :supports-compare? supports-compare?)})
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout/page "Ошибка"
                            [:div.text-center.py-12
                             [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверные параметры"]
                             [:p.text-gray-600 "Проверьте значения period/from/to и marketplace"]]
                            :active-route "/reports/trends")})))
  
  (GET "/reports/losses" {params :params}
    (let [period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))
          schema          (rs/get-schema :losses)
          hide-period?    (false? (:uses-period? schema))
          supports-compare? (not (false? (:supports-compare? schema)))]
      (if period-arg
        (let [data          (try (losses/calculate period-arg :marketplace validated-mp) (catch Exception _ {:rows [] :totals {}}))
              totals        (:totals data)
              show-no-data? (empty? (:rows data))
              ;; Notice for non-WB: losses only available for WB
              mp-notice     (when (and validated-mp (not= validated-mp :wb))
                              [:div.bg-blue-50.border-l-4.border-blue-400.p-4.mb-4
                               [:p.text-sm.text-blue-700
                                "Отчёт «Убытки» сейчас доступен только для Wildberries. "
                                "Для Ozon и Яндекс.Маркет данные по хранению не загружаются."]])]
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (layout/page "Отчёт: Убытки"
                              (into [:div]
                                    (keep identity
                                          [mp-notice
                                           (reports-page/report-page :losses period-arg validated-mp
                                                                      :show-no-data show-no-data? :totals totals)]))
                              :active-route "/reports/losses"
                              :hide-period? hide-period?
                              :supports-compare? supports-compare?)})
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout/page "Ошибка"
                            [:div.text-center.py-12
                             [:h2.text-2xl.font-bold.text-red-600.mb-4 "Неверные параметры"]
                             [:p.text-gray-600 "Проверьте значения period/from/to и marketplace"]]
                            :active-route "/reports/losses")})))

  ;; API endpoint for losses
  (GET "/api/report/losses" {params :params}
    (let [period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))]
      (if period-arg
        (let [data (try (losses/calculate period-arg :marketplace validated-mp)
                        (catch Exception _ {:rows [] :totals {}}))]
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/write-value-as-string {:rows (mapv (fn [r] (update r :loss-type name)) (:rows data))
                                  :totals (:totals data)})})
        {:status 400
         :headers {"Content-Type" "application/json"}
         :body (json/write-value-as-string {:error "Invalid or incomplete period parameters"})})))

  ;; Fallback for any other report type (404)
  (GET "/reports/:type" [type]
    {:status 404 
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (layout/page "Отчёт не найден" 
                        [:div.text-center.py-12
                         [:h2.text-2xl.font-bold.text-gray-900.mb-4 "Отчёт не найден"]
                         [:p.text-gray-600 (str "Отчёт типа '" type "' не существует.")]]
                        :active-route (str "/reports/" type))})
  
  ;; API endpoints
  (GET "/api/metrics" {params :params headers :headers}
    (let [period-str (get params :period "last-30-days")
          validated-period (validate-period period-str)
          marketplace-str (get params :marketplace)
          validated-mp (when marketplace-str (validate-marketplace marketplace-str))
          is-htmx? (get headers "hx-request")]
      (cond
        (and period-str (not validated-period))
        {:status 400 :body {:error (str "Invalid period: " period-str)}}
        
        (and marketplace-str (not validated-mp) (not= marketplace-str "all"))
        {:status 400 :body {:error (str "Invalid marketplace: " marketplace-str)}}
        
        :else
        (let [period (try
                       (time/parse-period (or validated-period "last-30-days"))
                       (catch Exception e
                         nil))]
          (if period
            (let [metrics (if validated-mp
                            (metrics-api/summary-metrics period :marketplace validated-mp)
                            (metrics-api/summary-metrics period))]
              (if is-htmx?
                ;; Return HTML fragment for HTMX
                {:status 200
                 :headers {"Content-Type" "text/html; charset=utf-8"}
                 :body (hiccup.core/html
                         [:div
                          [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-4.gap-6.mb-6
                           ((requiring-resolve 'analitica.web.components/metric-card)
                            {:title "Выручка"
                             :value (long (:revenue metrics))
                             :unit "₽"
                             :delta (:revenue-wow metrics)
                             :delta-label "WoW"})
                           ((requiring-resolve 'analitica.web.components/metric-card)
                            {:title "Заказы"
                             :value (:orders metrics)
                             :unit ""
                             :delta (:orders-wow metrics)
                             :delta-label "WoW"})
                           ((requiring-resolve 'analitica.web.components/metric-card)
                            {:title "Прибыль"
                             :value (long (:profit metrics))
                             :unit "₽"
                             :delta (:profit-wow metrics)
                             :delta-label "WoW"})
                           ((requiring-resolve 'analitica.web.components/metric-card)
                            {:title "Процент возвратов"
                             :value (format "%.1f" (:return-rate metrics))
                             :unit "%"
                             :delta (:return-rate-wow metrics)
                             :delta-label "WoW"})]
                          ;; Marketplace comparison table
                          (when-let [by-mp (:by-marketplace metrics)]
                            [:div.mb-6
                             ((requiring-resolve 'analitica.web.pages.dashboard/marketplace-comparison-table)
                              by-mp)])])}
                ;; Return JSON for non-HTMX requests
                {:status 200 :body metrics}))
            {:status 400 :body {:error (str "Invalid period: " period-str)}})))))
  
  (GET "/api/metrics/:marketplace" {params :params headers :headers}
    (let [marketplace-str (:marketplace params)
          validated-mp (validate-marketplace marketplace-str)
          period-str (get params :period "last-30-days")
          validated-period (validate-period period-str)
          is-htmx? (get headers "hx-request")]
      (cond
        (not validated-mp)
        {:status 400 :body {:error (str "Invalid marketplace: " marketplace-str)}}
        
        (and period-str (not validated-period))
        {:status 400 :body {:error (str "Invalid period: " period-str)}}
        
        :else
        (let [period (try
                       (time/parse-period (or validated-period "last-30-days"))
                       (catch Exception e
                         nil))]
          (if period
            (let [metrics (metrics-api/marketplace-metrics validated-mp period)]
              (if is-htmx?
                ;; Return HTML fragment for HTMX
                {:status 200
                 :headers {"Content-Type" "text/html; charset=utf-8"}
                 :body (hiccup.core/html
                         [:div
                          ;; Metrics cards
                          [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-4.gap-6.mb-6
                           ((requiring-resolve 'analitica.web.components/metric-card)
                            {:title "Выручка"
                             :value (long (:revenue metrics))
                             :unit "₽"
                             :delta (:revenue-wow metrics)
                             :delta-label "WoW"})
                           ((requiring-resolve 'analitica.web.components/metric-card)
                            {:title "Заказы"
                             :value (:orders metrics)
                             :unit ""
                             :delta (:orders-wow metrics)
                             :delta-label "WoW"})
                           ((requiring-resolve 'analitica.web.components/metric-card)
                            {:title "Прибыль"
                             :value (long (:profit metrics))
                             :unit "₽"
                             :delta (:profit-wow metrics)
                             :delta-label "WoW"})
                           ((requiring-resolve 'analitica.web.components/metric-card)
                            {:title "Процент возвратов"
                             :value (format "%.1f" (:return-rate metrics))
                             :unit "%"
                             :delta (:return-rate-wow metrics)
                             :delta-label "WoW"})]
                          
                          ;; Top products table
                          [:div.mb-6
                           ((requiring-resolve 'analitica.web.pages.dashboard/top-products-table)
                            (:top-products metrics))]
                          
                          ;; Top returns table
                          [:div
                           ((requiring-resolve 'analitica.web.pages.dashboard/top-returns-table)
                            (:top-returns metrics))]])}
                ;; Return JSON for non-HTMX requests
                {:status 200 :body metrics}))
            {:status 400 :body {:error (str "Invalid period: " period-str)}})))))
  (GET "/api/chart/sales" {params :params}
    (let [period-str (get params :period "last-30-days")
          validated-period (validate-period period-str)
          marketplace-str (get params :marketplace)
          validated-mp (when marketplace-str (validate-marketplace marketplace-str))]
      (cond
        (and period-str (not validated-period))
        {:status 400 :body {:error (str "Invalid period: " period-str)}}
        
        (and marketplace-str (not validated-mp) (not= marketplace-str "all"))
        {:status 400 :body {:error (str "Invalid marketplace: " marketplace-str)}}
        
        :else
        (let [period (try
                       (time/parse-period (or validated-period "last-30-days"))
                       (catch Exception e
                         nil))]
          (if period
            (let [chart-data (if validated-mp
                               (charts-api/sales-chart-data period :marketplace validated-mp)
                               (charts-api/sales-chart-data period))]
              {:status 200 :body chart-data})
            {:status 400 :body {:error (str "Invalid period: " period-str)}})))))
  
  (GET "/api/chart/share" {params :params}
    (let [period-str (get params :period "last-30-days")
          validated-period (validate-period period-str)]
      (if (or validated-period (nil? period-str))
        (let [period (try
                       (time/parse-period (or validated-period "last-30-days"))
                       (catch Exception e
                         nil))]
          (if period
            (let [chart-data (charts-api/share-chart-data period)]
              {:status 200 :body chart-data})
            {:status 400 :body {:error (str "Invalid period: " period-str)}}))
        {:status 400 :body {:error (str "Invalid period: " period-str)}})))
  (GET "/api/chart/report" {params :params}
    (let [report-type-str (get params :type)
          validated-type  (when report-type-str (validate-report-type report-type-str))
          period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))
          compare-kw      (if (= (get params :compare) "prev") :prev :none)]
      (cond
        (and report-type-str (not validated-type))
        {:status 400 :body {:error (str "Invalid report type: " report-type-str)}}

        (not period-arg)
        {:status 400 :body {:error "Invalid or incomplete period parameters (use ?period=, or both ?from= and ?to=)"}}

        (and marketplace-str (not validated-mp) (not= marketplace-str "all"))
        {:status 400 :body {:error (str "Invalid marketplace: " marketplace-str)}}

        :else
        (if validated-type
          (let [chart-data (charts-api/report-chart-data validated-type period-arg
                                                         :marketplace validated-mp
                                                         :compare compare-kw)]
            {:status 200 :body chart-data})
          {:status 400 :body {:error (str "Invalid report type: " report-type-str)}}))))

  (GET "/api/chart/finance-breakdown" {params :params}
    (let [marketplace-str (get params :marketplace)
          validated-mp (when marketplace-str (validate-marketplace marketplace-str))
          period-str (get params :period "last-30-days")
          validated-period (validate-period period-str)]
      (cond
        (and marketplace-str (not validated-mp) (not= marketplace-str "all"))
        {:status 400 :body {:error (str "Invalid marketplace: " marketplace-str)}}
        
        (and period-str (not validated-period))
        {:status 400 :body {:error (str "Invalid period: " period-str)}}
        
        :else
        (let [period (try
                       (time/parse-period (or validated-period "last-30-days"))
                       (catch Exception e
                         nil))]
          (if (and validated-mp period)
            (let [chart-data (charts-api/finance-breakdown-chart-data validated-mp period)]
              {:status 200 :body chart-data})
            {:status 400 :body {:error (str "Invalid parameters - marketplace: " marketplace-str ", period: " period-str)}})))))
  
  (GET "/api/chart/abc-distribution" {params :params}
    (let [marketplace-str (get params :marketplace)
          validated-mp (when marketplace-str (validate-marketplace marketplace-str))
          period-str (get params :period "last-30-days")
          validated-period (validate-period period-str)]
      (cond
        (and marketplace-str (not validated-mp) (not= marketplace-str "all"))
        {:status 400 :body {:error (str "Invalid marketplace: " marketplace-str)}}
        
        (and period-str (not validated-period))
        {:status 400 :body {:error (str "Invalid period: " period-str)}}
        
        :else
        (let [period (try
                       (time/parse-period (or validated-period "last-30-days"))
                       (catch Exception e
                         nil))]
          (if (and validated-mp period)
            (let [chart-data (charts-api/abc-distribution-chart-data validated-mp period)]
              {:status 200 :body chart-data})
            {:status 400 :body {:error (str "Invalid parameters - marketplace: " marketplace-str ", period: " period-str)}})))))
  
  ;; Phase 2: Digest API — returns JSON data for async digest sub-section refresh.
  ;; Accepts ?from=&to= or defaults to last-30-days.
  (GET "/api/digest" {params :params}
    (let [from (get params :from)
          to   (get params :to)
          mp   (get params :marketplace)
          data (try
                 (digest-page/collect-page-data!
                  :from from :to to
                  :marketplace (when (and mp (not= mp "all")) (keyword mp)))
                 (catch Exception e
                   {:error (.getMessage e)}))]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string data)}))

  ;; Command-palette search — returns JSON {:results [...]}.
  (GET "/api/search" {params :params}
    (let [q   (get params :q "")
          res (search-api/search q)]
      {:status  200
       :headers {"Content-Type" "application/json; charset=utf-8"}
       :body    (json/write-value-as-string res)}))

  ;; SKU drill-down panel fragment — returns text/html fragment, NOT JSON.
  ;; Must be listed BEFORE /api/report/:type to avoid catch-all conflict.
  (GET "/api/sku/:identifier" req
    (sku-api/handler req))

  ;; Per-article drill-down — must be listed BEFORE the /api/report/:type catch-all.
  ;; Article names may contain '/' and Cyrillic; the client must encodeURIComponent
  ;; so '/' becomes '%2F'. Compojure delivers the percent-decoded value in params;
  ;; we re-decode the raw path segment using URLDecoder to handle double-encoding
  ;; edge cases from some HTTP clients.
  (GET "/api/report/:type/article/:article" {params :params}
    (let [type-str      (:type params)
          validated-type (validate-report-type type-str)
          article        (try
                           (java.net.URLDecoder/decode (str (:article params)) "UTF-8")
                           (catch Exception _ (:article params)))
          period-str     (get params :period "last-30-days")
          validated-period (validate-period period-str)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))]
      (cond
        (not validated-type)
        {:status 400 :body {:error (str "Invalid report type: " type-str)}}

        (and period-str (not validated-period))
        {:status 400 :body {:error (str "Invalid period: " period-str)}}

        (and marketplace-str (not validated-mp) (not= marketplace-str "all"))
        {:status 400 :body {:error (str "Invalid marketplace: " marketplace-str)}}

        :else
        (let [period (try
                       (time/parse-period validated-period)
                       (catch Exception _ nil))
              [from to] (when period period)
              mp         validated-mp]
          (if period
            (let [data (detail/article-detail
                         validated-type article {:from from :to to}
                         :marketplace mp)]
              {:status 200 :body data})
            {:status 400 :body {:error (str "Invalid period: " period-str)}})))))

  (GET "/api/report/:type" {params :params}
    (let [report-type-str (:type params)
          validated-type  (validate-report-type report-type-str)
          period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))
          trend-type-str  (get params :trend-type)
          trend-type      (when trend-type-str (keyword trend-type-str))
          article-str     (get params :article)
          compare-kw      (if (= (get params :compare) "prev") :prev :none)]
      (cond
        (not validated-type)
        {:status 400 :body {:error (str "Invalid report type: " report-type-str)}}

        (not period-arg)
        {:status 400 :body {:error "Invalid or incomplete period parameters"}}

        (and marketplace-str (not validated-mp) (not= marketplace-str "all"))
        {:status 400 :body {:error (str "Invalid marketplace: " marketplace-str)}}

        :else
        (let [report-data ((requiring-resolve 'analitica.web.api.report/report-data)
                           validated-type period-arg
                           :marketplace validated-mp
                           :trend-type  trend-type
                           :article     article-str
                           :compare     compare-kw)]
          {:status 200 :body report-data}))))
  (GET "/api/export/:report" {params :params}
    (let [report-type-str (:report params)
          validated-type  (validate-report-type report-type-str)
          period-arg      (resolve-period-from-params params)
          marketplace-str (get params :marketplace)
          validated-mp    (when marketplace-str (validate-marketplace marketplace-str))
          format-str      (get params :format "excel")
          fmt             (keyword format-str)]
      (cond
        (not validated-type)
        {:status 400 :body {:error (str "Invalid report type: " report-type-str)}}

        (not period-arg)
        {:status 400 :body {:error "Invalid or incomplete period parameters"}}

        (and marketplace-str (not validated-mp) (not= marketplace-str "all"))
        {:status 400 :body {:error (str "Invalid marketplace: " marketplace-str)}}

        :else
        (export-api/export-report validated-type period-arg validated-mp fmt))))
  
  ;; Sync API endpoints
  (POST "/api/sync/start" {body :body params :params}
    (let [body-str (when body (slurp body))
          body-data (when (and body-str (not (empty? body-str)))
                      (json/read-value body-str json/keyword-keys-object-mapper))
          what-str (or (:what body-data) (:what params))
          period-str (or (:period body-data) (:period params))
          marketplace-str (or (:marketplace body-data) (:marketplace params))
          validated-what (when what-str (validate-sync-what (if (keyword? what-str) (name what-str) what-str)))
          validated-period (when period-str (validate-period period-str))
          mp-name        (when marketplace-str (if (keyword? marketplace-str) (name marketplace-str) marketplace-str))
          all-mp?        (= mp-name "all")
          validated-mp   (when (and marketplace-str (not all-mp?)) (validate-marketplace mp-name))]
      (cond
        (not validated-what)
        {:status 400 :body {:error (str "Invalid 'what' parameter: " what-str)}}

        (and period-str (not validated-period))
        {:status 400 :body {:error (str "Invalid period: " period-str)}}

        (and marketplace-str (not validated-mp) (not all-mp?))
        {:status 400 :body {:error (str "Invalid marketplace: " marketplace-str)}}

        :else
        (let [period (when validated-period
                       (try (time/parse-period validated-period)
                            (catch Exception _ nil)))
              ;; :all fans out across [:wb :ozon :ym] in start-sync!.
              mp     (if all-mp? :all validated-mp)
              result (sync-api/start-sync! validated-what
                                           :period      period
                                           :marketplace mp)]
          (if (:error result)
            {:status 409 :body result}
            {:status 200 :body result})))))
  
  ;; Plan-based sync run: generates a task plan, persists it, then executes
  ;; sequentially in a background future. Uses the same single-flight atom
  ;; as /api/sync/start to prevent two concurrent runs.
  (POST "/api/sync/run" {body :body params :params}
    (let [body-str  (when body (slurp body))
          body-data (when (and body-str (not (empty? body-str)))
                      (json/read-value body-str json/keyword-keys-object-mapper))
          what-str        (or (:what body-data) (:what params))
          period-str      (or (:period body-data) (:period params))
          marketplace-str (or (:marketplace body-data) (:marketplace params))
          validated-what  (when what-str
                            (validate-run-what
                              (if (keyword? what-str) (name what-str) what-str)))
          validated-period (when period-str (validate-period period-str))
          mp-name          (when marketplace-str
                             (if (keyword? marketplace-str) (name marketplace-str) marketplace-str))
          all-mp?          (= mp-name "all")
          validated-mp     (when (and marketplace-str (not all-mp?))
                             (validate-marketplace mp-name))]
      (cond
        (not validated-what)
        {:status 400 :body {:error (str "Invalid 'what' parameter: " what-str)}}

        (and period-str (not validated-period))
        {:status 400 :body {:error (str "Invalid period: " period-str)}}

        (and marketplace-str (not validated-mp) (not all-mp?))
        {:status 400 :body {:error (str "Invalid marketplace: " marketplace-str)}}

        (not (compare-and-set! sync-api/sync-running? false true))
        {:status 409 :body {:error "already running"}}

        :else
        (let [run-id  (str (java.util.UUID/randomUUID))
              period  (if validated-period
                        (try (time/parse-period validated-period)
                             (catch Exception _ :last-30-days))
                        :last-30-days)
              mp      (if all-mp? :all (or validated-mp :wb))
              plan    (sync-plan/expand-plan :run-id      run-id
                                             :what        validated-what
                                             :marketplace mp
                                             :period      period)
              _       (sync-plan/persist-plan! plan)]
          (future
            (try
              ;; Phase 5: parallel executor is default. Pool size 8 is
              ;; the documented choice in docs/superpowers/plans/2026-04-25-
              ;; sync-task-registry.md.
              (sync-executor/run-parallel! plan :workers 8)
              (finally
                (reset! sync-api/sync-running? false))))
          {:status 202 :body {:run-id run-id :total (count plan)}}))))

  ;; Phase 4 — task-matrix status API
  (GET "/api/sync/run/:run-id" [run-id]
    (let [summary (sync-executor/run-summary run-id)]
      (if summary
        {:status 200 :body summary}
        {:status 404 :body {:error "Run not found"}})))

  (GET "/api/sync/runs/recent" []
    {:status 200 :body (sync-executor/recent-runs)})

  ;; Phase 7 — manual retry for a single task by task-id.
  ;; Task IDs contain slashes (run-id/mp/type/phase), so we match the full
  ;; path via a wildcard route and strip the trailing /retry suffix.
  ;; Does NOT require a sync to be active (independent of sync-running? atom).
  (POST "/api/sync/tasks/*/retry" {uri :uri}
    (let [;; URI looks like /api/sync/tasks/<task-id>/retry
          ;; Strip the fixed prefix+suffix to recover the task-id.
          task-id (when (and (string? uri)
                             (re-find #"^/api/sync/tasks/.+/retry$" uri))
                    (subs uri
                          (count "/api/sync/tasks/")
                          (- (count uri) (count "/retry"))))
          row     (when task-id (sync-registry/find-task task-id))]
      (cond
        (nil? task-id)
        {:status 400 :body {:error "malformed task-id in URL"}}

        (nil? row)
        {:status 404 :body {:error "task not found"}}

        (#{:running :pending :retrying}
         (keyword (:status row)))
        {:status 409 :body {:error "task is not in a terminal state"}}

        :else
        (let [thunk (sync-plan/build-thunk-for-row row)
              _     (sync-registry/reset-for-retry! task-id)]
          (future (sync-runner/run-task! task-id thunk))
          {:status 202 :body {:ok true :task-id task-id :status "queued"}}))))

  (POST "/api/sync/stop" []
    (let [result (sync-api/stop-sync!)]
      (if (:error result)
        {:status 409 :body result}
        {:status 200 :body result})))

  ;; Rematerialize: replay materialize over existing raw_data, no MP
  ;; HTTP. Same shape as /api/sync/start (what / period / marketplace).
  (POST "/api/sync/rematerialize" {body :body params :params}
    (let [body-str (when body (slurp body))
          body-data (when (and body-str (not (empty? body-str)))
                      (json/read-value body-str json/keyword-keys-object-mapper))
          what-str (or (:what body-data) (:what params))
          period-str (or (:period body-data) (:period params))
          marketplace-str (or (:marketplace body-data) (:marketplace params))
          validated-what (when what-str (validate-sync-what (if (keyword? what-str) (name what-str) what-str)))
          validated-period (when period-str (validate-period period-str))
          validated-mp (when marketplace-str (validate-marketplace (if (keyword? marketplace-str) (name marketplace-str) marketplace-str)))]
      (cond
        (not validated-what)
        {:status 400 :body {:error (str "Invalid 'what' parameter: " what-str)}}

        (and period-str (not validated-period))
        {:status 400 :body {:error (str "Invalid period: " period-str)}}

        (and marketplace-str (not validated-mp) (not= marketplace-str "all"))
        {:status 400 :body {:error (str "Invalid marketplace: " marketplace-str)}}

        :else
        (let [period (when validated-period
                       (try (time/parse-period validated-period)
                            (catch Exception _ nil)))
              result (sync-api/start-rematerialize! validated-what
                                                    :period      period
                                                    :marketplace validated-mp)]
          (if (:error result)
            {:status 409 :body result}
            {:status 200 :body result})))))

  (GET "/api/sync/stream" request
    (sync-api/sse-stream request))
  
  (GET "/api/sync/coverage" []
    (let [coverage (metrics-api/sync-coverage)]
      {:status 200
       :body coverage}))

  (GET "/api/sync/coverage-days" []
    (let [data (sync-coverage/coverage-by-mp-and-type)]
      {:status 200 :body data}))

  ;; Data-coverage (used by the period-picker calendar): which days in a
  ;; range have at least one finance row. Optional `marketplace` narrows
  ;; by MP. Returns `{:days [iso …]}`. When `from` or `to` is missing we
  ;; fail with 400 rather than default to an arbitrary window.
  (GET "/api/coverage" {params :params}
    (let [from   (get params :from)
          to     (get params :to)
          mp-str (get params :marketplace)
          mp     (when (and mp-str (not= mp-str "all"))
                   (validate-marketplace mp-str))]
      (cond
        (not (and from to))
        {:status 400 :body {:error "from and to required"}}

        (and mp-str (not= mp-str "all") (not mp))
        {:status 400 :body {:error (str "Invalid marketplace: " mp-str)}}

        :else
        (let [days (coverage/days-with-data from to :marketplace mp)]
          {:status 200 :body {:days days}}))))

  ;; Cost prices: CostSource-backed ingest. 1C CSV upload today; future
  ;; 1C API / Мойсклад / … endpoints will live under the same prefix.
  (POST "/api/cost-prices/upload" request
    (cost-prices-api/upload-csv request))

  (POST "/api/cost-prices/preview" request
    (cost-prices-api/preview-csv request))
  (GET "/api/cost-prices/imports" request
    (cost-prices-api/list-imports request))
  (GET "/upload/cost-prices" []
    (let [recent (:body (cost-prices-api/list-imports {:params {:limit "10"}}))]
      (layout/page
        "Загрузка себестоимости"
        [:div.container
         [:h1 "Загрузка себестоимости из 1С"]
         [:p "Выберите CSV-файл (формат units.csv из 1С) и нажмите «Загрузить»."]
         [:form {:action "/api/cost-prices/upload"
                 :method "post"
                 :enctype "multipart/form-data"
                 :style "margin-top:1em;padding:1em;border:1px solid #ccc;border-radius:8px"}
          [:input {:type "file" :name "file" :accept ".csv,text/csv" :required true}]
          [:button {:type "submit" :style "margin-left:1em"} "Загрузить"]]
         [:p {:style "color:#666;font-size:0.9em;margin-top:1em"}
          "После загрузки откроется JSON с результатом: "
          [:code "{ :loaded :rejected :source … }"]
          ". Себестоимости обновятся немедленно, перезапуск не требуется."]
         [:h2 {:style "margin-top:2em"} "История загрузок (последние 10)"]
         [:table {:style "border-collapse:collapse;width:100%"}
          [:thead
           [:tr {:style "background:#f4f4f4"}
            [:th {:style "text-align:left;padding:6px;border-bottom:1px solid #ddd"} "#"]
            [:th {:style "text-align:left;padding:6px;border-bottom:1px solid #ddd"} "Когда"]
            [:th {:style "text-align:left;padding:6px;border-bottom:1px solid #ddd"} "Источник"]
            [:th {:style "text-align:right;padding:6px;border-bottom:1px solid #ddd"} "Загружено"]
            [:th {:style "text-align:right;padding:6px;border-bottom:1px solid #ddd"} "Отброшено"]
            [:th {:style "text-align:left;padding:6px;border-bottom:1px solid #ddd"} "Файл"]
            [:th {:style "text-align:left;padding:6px;border-bottom:1px solid #ddd"} "Примечание"]]]
          [:tbody
           (if (seq (:imports recent))
             (for [r (:imports recent)]
               [:tr
                [:td {:style "padding:6px;border-bottom:1px solid #eee"} (:id r)]
                [:td {:style "padding:6px;border-bottom:1px solid #eee"} (:imported-at r)]
                [:td {:style "padding:6px;border-bottom:1px solid #eee"} (:source r)]
                [:td {:style "padding:6px;border-bottom:1px solid #eee;text-align:right"} (:loaded r)]
                [:td {:style "padding:6px;border-bottom:1px solid #eee;text-align:right"} (:rejected r)]
                [:td {:style "padding:6px;border-bottom:1px solid #eee"} (or (:filename r) "")]
                [:td {:style "padding:6px;border-bottom:1px solid #eee"} (or (:notes r) "")]])
             [:tr [:td {:colspan 7 :style "padding:1em;color:#666;text-align:center"}
                   "Импортов пока не было."]])]]])))

  ;; Phase 9 — schedule CRUD
  (GET "/api/sync/schedule" []
    (let [sched (sync-scheduler/get-schedule)]
      (if sched
        {:status 200 :body sched}
        {:status 404 :body {:error "schedule not initialized"}})))

  (POST "/api/sync/schedule" {body :body params :params}
    (let [body-str  (when body (slurp body))
          body-data (when (and body-str (not (empty? body-str)))
                      (json/read-value body-str json/keyword-keys-object-mapper))
          raw-enabled (or (:enabled body-data) (:enabled params))
          enabled?    (if (boolean? raw-enabled)
                        raw-enabled
                        (contains? #{true 1 "true" "1"} raw-enabled))
          hour-raw    (or (:hour body-data) (:hour params))
          minute-raw  (or (:minute body-data) (:minute params))
          hour        (when hour-raw (try (Integer/parseInt (str hour-raw)) (catch Exception _ nil)))
          minute      (when minute-raw (try (Integer/parseInt (str minute-raw)) (catch Exception _ nil)))
          what-str    (or (:what body-data) (:what params))
          mp-str      (or (:marketplace body-data) (:marketplace params))
          period-str  (or (:period body-data) (:period params))]
      (cond
        (and (some? hour) (not (<= 0 hour 23)))
        {:status 400 :body {:error "hour must be 0-23"}}

        (and (some? minute) (not (<= 0 minute 59)))
        {:status 400 :body {:error "minute must be 0-59"}}

        (and what-str
             (not (contains? (conj valid-run-what "all") what-str)))
        {:status 400 :body {:error (str "Invalid what: " what-str)}}

        (and mp-str
             (not (contains? (conj valid-marketplaces "all") mp-str)))
        {:status 400 :body {:error (str "Invalid marketplace: " mp-str)}}

        (and period-str (not (validate-period period-str)))
        {:status 400 :body {:error (str "Invalid period: " period-str)}}

        :else
        (let [updated (sync-scheduler/update-schedule!
                       {:enabled?    enabled?
                        :hour        (or hour 6)
                        :minute      (or minute 0)
                        :what        (or what-str "all")
                        :marketplace (or mp-str "all")
                        :period      (or period-str "last-7-days")})]
          {:status 200 :body updated}))))

  ;; 404
  (route/not-found {:status 404 :body "Not Found"}))

;; ---------------------------------------------------------------------------
;; Middleware
;; ---------------------------------------------------------------------------

(defn wrap-json-response
  "Middleware to convert response body to JSON if it's a Clojure data structure."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (and (or (map? (:body response))
                   (vector? (:body response))
                   (seq? (:body response)))
               (not (string? (:body response))))
        (-> response
            (assoc :body (json/write-value-as-string (:body response)))
            (assoc-in [:headers "Content-Type"] "application/json; charset=utf-8"))
        response))))

(defn app []
  (-> app-routes
      (resource/wrap-resource "public")
      (wrap-multipart-params)
      (wrap-keyword-params)
      (wrap-params)
      (wrap-json-response)
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post :options])))

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(defonce server (atom nil))

(defn start!
  "Start Jetty server on specified port."
  [& {:keys [port] :or {port 3000}}]
  (when-not @server
    (println (str "Starting Analitica Web UI on port " port "..."))
    (core/start!)
    (let [s (jetty/run-jetty (app) {:port port :join? false})]
      (reset! server s)
      (println (str "Server running at http://localhost:" port))
      s)))

(defn stop!
  "Stop Jetty server."
  []
  (when-let [s @server]
    (.stop s)
    (reset! server nil)
    (println "Server stopped")))

;; ---------------------------------------------------------------------------
;; Main entry point
;; ---------------------------------------------------------------------------

(defn -main
  "Entry point for web server. Parses --port argument (default 3000)."
  [& args]
  (let [port-arg (some #(when (.startsWith % "--port=")
                          (subs % 7))
                       args)
        port     (if port-arg
                   (Integer/parseInt port-arg)
                   3000)]
    (start! :port port)
    ;; Keep main thread alive
    @(promise)))
