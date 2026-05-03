(ns marker.router
  "reitit-frontend router for the Marker SPA.
   Uses HTML5 history mode (no hash) under the /app base path.
   On every navigation match, dispatches ::events/set-page with the
   matched route name as a keyword so app-db stays in sync.
   Browser back/forward works because rfe/start! listens to popstate."
  (:require [reitit.frontend       :as rf-router]
            [reitit.frontend.easy  :as rfe]
            [re-frame.core         :as rf]
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
   ["/returns"   {:name :returns}]
   ["/products"  {:name :products}]
   ["/warehouse" {:name :warehouse}]
   ["/plan"      {:name :plan}]
   ["/kit"       {:name :kit}]])

(def ^:private router
  (rf-router/router routes))

;; ---------------------------------------------------------------------------
;; on-navigate callback
;; ---------------------------------------------------------------------------

(defn- on-navigate [match _history]
  (let [route-name (get-in match [:data :name])]
    (if (= route-name :app-root)
      ;; /app with no suffix → redirect to /app/pulse
      (rfe/replace-state :pulse)
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
  "Navigate to a named route, pushing a new history entry."
  [route-name]
  (rfe/push-state route-name))
