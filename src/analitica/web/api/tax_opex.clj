(ns analitica.web.api.tax-opex
  "HTTP handlers for tax/OPEX management endpoints.

   Routes (registered in server.clj):
     GET  /api/v1/opex/auto-rules       → list all auto-rules
     POST /api/v1/opex/auto-rules       → create auto-rule
     DELETE /api/v1/opex/auto-rules/:id → delete auto-rule

   Contracts: specs/015-management-taxes-opex/contracts/tax-opex-api.md §2b."
  (:require [analitica.domain.opex :as opex]
            [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- body-of [req]
  (let [b (:body req)]
    (if (map? b) b (or (:params req) {}))))

(defn- parse-id [req]
  (some-> (or (get-in req [:params :id])
              (get-in req [:route-params :id]))
          (Long/parseLong)))

;; ---------------------------------------------------------------------------
;; OPEX auto-rules handlers (US5 — T059/T060)
;; ---------------------------------------------------------------------------

(defn get-auto-rules
  "GET /api/v1/opex/auto-rules
   Returns {:rules [OpexAutoRule…]}"
  [_req]
  {:status 200
   :body   {:rules (vec (opex/fetch-rules))}})

(defn post-auto-rule
  "POST /api/v1/opex/auto-rules
   Body: OpexAutoRule map (without :id).
   Returns {:ok true :id n} or {:ok false :error string}."
  [req]
  (let [body (body-of req)
        ;; Coerce marketplace string → keyword if needed
        rule (cond-> body
               (string? (:marketplace body))
               (update :marketplace #(when (seq %) (keyword %)))
               (string? (:cadence body))
               (update :cadence keyword))]
    (if (m/validate opex/OpexAutoRule rule)
      (try
        (let [{:keys [id]} (opex/save-rule! rule)]
          {:status 200 :body {:ok true :id id}})
        (catch Exception e
          {:status 500 :body {:ok false :error (.getMessage e)}}))
      {:status 422
       :body   {:ok    false
                :error (let [ex (m/explain opex/OpexAutoRule rule)]
                         (str "Validation failed: "
                              (pr-str (mapv :message
                                           (:errors ex)))))}})))

(defn delete-auto-rule
  "DELETE /api/v1/opex/auto-rules/:id
   Returns {:ok true}."
  [req]
  (if-let [id (parse-id req)]
    (do
      (opex/delete-rule! id)
      {:status 200 :body {:ok true}})
    {:status 400 :body {:ok false :error "Missing or invalid id"}}))
