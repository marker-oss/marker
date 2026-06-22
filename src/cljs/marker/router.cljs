(ns marker.router
  "reitit-frontend router for the Marker SPA.

   URL scheme:
     /app                    → redirects to /app/pulse
     /app/pulse              → page :pulse
     /app/sync               → page :sync
     /app/<section>          → redirects to /app/<section>/<default-tab>
     /app/<section>/<tab>    → page [<section> <tab>]
                                where <section> ∈ {finance, products, dynamics}

   Legacy routes (kept for old bookmarks; redirect cleanly):
     /app/pnl, /app/unit, /app/cost-prices,
     /app/reports/<type>     (where <type> ∈ {ue, returns, losses, finance,
                                              stock, abc, trends, sales, geo, buyout})

   On every navigation match, dispatches ::events/set-page so app-db
   stays in sync. Browser back/forward works because rfe/start! listens
   to popstate."
  (:require [reitit.frontend       :as rf-router]
            [reitit.frontend.easy  :as rfe]
            [re-frame.core         :as rf]
            [clojure.string]
            [marker.state.events   :as events]
            [marker.util.nav       :as nav]))

;; ---------------------------------------------------------------------------
;; Route table
;; ---------------------------------------------------------------------------

(def ^:private routes
  ["/app"
   ;; Root → redirect to /app/pulse handled in on-navigate.
   ["" {:name :app-root}]

   ;; Single-page routes.
   ["/pulse"    {:name :pulse}]
   ["/sync"     {:name :sync}]
   ["/settings" {:name :settings}]

   ;; Sectioned routes: bare /app/finance redirects to default tab;
   ;; /app/finance/:tab dispatches as [:finance :tab].
   ["/finance"       {:name :finance-root}]
   ["/finance/:tab"  {:name :finance}]
   ["/products"      {:name :products-root}]
   ["/products/:tab" {:name :products}]
   ["/dynamics"      {:name :dynamics-root}]
   ["/dynamics/:tab" {:name :dynamics}]

   ;; Legacy routes — preserved so old bookmarks redirect cleanly.
   ["/pnl"           {:name :legacy-pnl}]
   ["/unit"          {:name :legacy-unit}]
   ["/cost-prices"   {:name :legacy-cost-prices}]
   ["/reports/:type" {:name :legacy-report}]])

(def ^:private router
  (rf-router/router routes))

;; ---------------------------------------------------------------------------
;; on-navigate callback
;; ---------------------------------------------------------------------------

(defn- redirect-to
  "Replace the current history entry with target.
   Target is either a keyword (single-page) or [:section :tab] vector."
  [target]
  (cond
    (vector? target)
    (rfe/replace-state (first target) {:tab (name (second target))})

    (keyword? target)
    (rfe/replace-state target)))

(defn- handle-section
  "Validate :tab path-param against nav/valid-tab?; if valid, dispatch
   set-page; if invalid, redirect to the section's default tab."
  [section path-params]
  (let [tab-kw (some-> (:tab path-params) keyword)]
    (if (nav/valid-tab? section tab-kw)
      (rf/dispatch [::events/set-page [section tab-kw]])
      (redirect-to [section (nav/default-tab section)]))))

(defn- on-navigate [match _history]
  (let [route-name  (get-in match [:data :name])
        path-params (:path-params match)]
    (case route-name
      :app-root
      (rfe/replace-state :pulse)

      ;; Sectioned roots → redirect to default tab.
      :finance-root  (redirect-to [:finance  (nav/default-tab :finance)])
      :products-root (redirect-to [:products (nav/default-tab :products)])
      :dynamics-root (redirect-to [:dynamics (nav/default-tab :dynamics)])

      ;; Sectioned tabs → set page or redirect on bad tab.
      :finance  (handle-section :finance  path-params)
      :products (handle-section :products path-params)
      :dynamics (handle-section :dynamics path-params)

      ;; Legacy redirects.
      :legacy-pnl         (redirect-to (nav/legacy-redirect :pnl))
      :legacy-unit        (redirect-to (nav/legacy-redirect :unit))
      :legacy-cost-prices (redirect-to (nav/legacy-redirect :cost-prices))
      :legacy-report      (let [type-kw (some-> (:type path-params) keyword)]
                            (if-let [target (nav/legacy-redirect [:report type-kw])]
                              (redirect-to target)
                              (rfe/replace-state :pulse)))

      ;; Single-page: pulse, sync.
      (rf/dispatch [::events/set-page (or route-name :pulse)]))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn init!
  "Start the HTML5 history listener.  Safe to call multiple times
   (rfe/start! tears down the previous listener automatically)."
  []
  (rfe/start! router on-navigate {:use-fragment false}))

(defn nav!
  "Navigate to a named route, pushing a new history entry. Accepts:
     - keyword (`:pulse`, `:sync`)               — single-page routes
     - vector `[:finance :pnl]`                  — sectioned routes
     - string `\"finance/pnl\"`                  — sidebar form (id+default-tab)
     - bare string `\"pulse\"` / `\"finance\"`   — section root, redirects."
  [route]
  (cond
    (vector? route)
    (let [[section tab] route]
      (rfe/push-state section {:tab (name tab)}))

    (keyword? route)
    (rfe/push-state route)

    (and (string? route) (clojure.string/includes? route "/"))
    (let [[section tab] (clojure.string/split route #"/" 2)]
      (rfe/push-state (keyword section) {:tab tab}))

    (string? route)
    (rfe/push-state (keyword route))))
