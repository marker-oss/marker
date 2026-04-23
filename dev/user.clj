(ns user
  (:require [analitica.web.server :as server]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.adapter.jetty :as jetty]))

(defonce dev-server (atom nil))

(defn stop!
  []
  (when-let [s @dev-server]
    (.stop s)
    (reset! dev-server nil)))

(defn go
  "Start the web server in dev mode with per-request code reload.
   Any edit to a namespace under src/ is picked up on the next HTTP request —
   no manual reload needed. Call (stop!) to shut it down, (go) to restart."
  [& {:keys [port] :or {port 3000}}]
  (stop!)
  (let [handler (wrap-reload (fn [req] ((server/app) req))
                             {:dirs ["src"]})
        s (jetty/run-jetty handler {:port port :join? false})]
    (reset! dev-server s)
    (println (str "Dev server running at http://localhost:" port " (live-reload on)"))
    s))

(comment
  (go)
  (stop!))
