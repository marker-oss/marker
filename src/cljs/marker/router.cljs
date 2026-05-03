(ns marker.router
  "reitit-frontend router for the Marker SPA.
   Uses HTML5 history mode (no hash) under the /app base path.
   On every navigation match, dispatches ::events/set-page with the
   matched route name as a keyword so app-db stays in sync.
   Browser back/forward works because rfe/start! listens to popstate."
  (:require [reitit.frontend       :as rf-router]
            [reitit.frontend.easy  :as rfe]
            [re-frame.core         :as rf]
            [clojure.string]
            [marker.state.events   :as events]))

;; ---------------------------------------------------------------------------
;; Route table
;; ---------------------------------------------------------------------------

(def ^:private routes
  ["/app"
   ;; /app root — treated as :pulse (redirect handled in on-navigate)
   ["" {:name :app-root}]
   ["/pulse"     {:name :pulse}]
   ["/pnl"       {:name :pnl}]
   ["/unit"      {:name :unit}]
   ["/products"  {:name :products}]
   ["/cost-prices" {:name :cost-prices}]
   ["/plan"      {:name :plan}]
   ["/kit"       {:name :kit}]
   ;; Phase 9: schema-driven reports (10 sub-routes via :type path-param)
   ["/reports/:type" {:name :report}]])

(def ^:private router
  (rf-router/router routes))

;; ---------------------------------------------------------------------------
;; on-navigate callback
;; ---------------------------------------------------------------------------

(defn- on-navigate [match _history]
  (let [route-name (get-in match [:data :name])
        path-params (:path-params match)]
    (cond
      (= route-name :app-root)
      ;; /app with no suffix → redirect to /app/pulse
      (rfe/replace-state :pulse)

      (= route-name :report)
      ;; /app/reports/:type → set page to [:report <type-keyword>]
      (let [report-type (some-> (:type path-params) keyword)]
        (rf/dispatch [::events/set-page [:report report-type]]))

      :else
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
  "Navigate to a named route, pushing a new history entry.
   Accepts:
     - keyword (`:pnl`) or string id (`\"pnl\"`) for static routes
     - vector `[:report :finance]` for parameterised report routes
     - string with colon (`\"report:finance\"`) — same as the vector form.
       Used by the sidebar which keeps a flat string id space."
  [route-name]
  (cond
    (vector? route-name)
    (let [[k & params] route-name]
      (case k
        :report (rfe/push-state :report {:type (name (first params))})
        (rfe/push-state k)))

    (and (string? route-name) (clojure.string/includes? route-name ":"))
    (let [[k v] (clojure.string/split route-name #":" 2)]
      (case k
        "report" (rfe/push-state :report {:type v})
        (rfe/push-state (keyword k))))

    (keyword? route-name)
    (rfe/push-state route-name)

    :else
    (rfe/push-state (keyword route-name))))
