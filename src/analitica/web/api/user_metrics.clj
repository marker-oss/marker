(ns analitica.web.api.user-metrics
  "HTTP handlers for the user-defined metric constructor (spec 016 US5).

   Routes (registered in server.clj):
     GET    /api/v1/metrics       → list all user metrics
     POST   /api/v1/metrics       → create/update a user metric (invalid formula → 422)
     DELETE /api/v1/metrics/:id   → delete a user metric

   The store (validate + persist) lives in analitica.web.report-schemas
   (save-user-metric! / fetch-user-metrics / delete-user-metric!). Handlers stay
   thin: parse the request body, coerce string enums, delegate, map exceptions to
   {:ok false :error …}. Match the tax-opex handler conventions."
  (:require [analitica.web.report-schemas :as rs]))

;; ---------------------------------------------------------------------------
;; Helpers (mirror tax-opex.clj)
;; ---------------------------------------------------------------------------

(defn- body-of [req]
  (let [b (:body req)]
    (if (map? b) b (or (:params req) {}))))

(defn- parse-id
  "Route/query :id as a Long, or nil when absent or non-numeric
   (nil lets handlers answer 4xx instead of a parseLong 500)."
  [req]
  (when-let [raw (or (get-in req [:params :id])
                     (get-in req [:route-params :id]))]
    (try (Long/parseLong raw) (catch NumberFormatException _ nil))))

;; ---------------------------------------------------------------------------
;; GET /api/v1/metrics
;; ---------------------------------------------------------------------------

(defn get-metrics
  "GET /api/v1/metrics → {:metrics [UserMetric…]}. Formula is an EDN AST."
  [_req]
  {:status 200
   :body   {:metrics (vec (rs/fetch-user-metrics))}})

;; ---------------------------------------------------------------------------
;; POST /api/v1/metrics
;; ---------------------------------------------------------------------------

(defn post-metric
  "POST /api/v1/metrics
   Body: a UserMetric map (slug/name/formula [+ suffix/filterType/positiveIfGrow/basis]).
   :slug and enum values may arrive as strings; :formula may be an EDN AST or an
   EDN string. On an invalid/unsafe formula → 422 {:ok false :error string}.
   Returns {:ok true :id n}."
  [req]
  (let [body (body-of req)]
    (try
      (let [{:keys [id]} (rs/save-user-metric! body)]
        {:status 200 :body {:ok true :id id}})
      (catch Exception e
        {:status 422 :body {:ok false :error (.getMessage e)}}))))

;; ---------------------------------------------------------------------------
;; DELETE /api/v1/metrics/:id
;; ---------------------------------------------------------------------------

(defn delete-metric
  "DELETE /api/v1/metrics/:id → {:ok true} (idempotent)."
  [req]
  (if-let [id (parse-id req)]
    (do
      (rs/delete-user-metric! id)
      {:status 200 :body {:ok true}})
    {:status 400 :body {:ok false :error "Missing or invalid id"}}))
