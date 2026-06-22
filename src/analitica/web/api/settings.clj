(ns analitica.web.api.settings
  "HTTP handlers for /api/v1/settings/* — view (masked), validate, and update
   MP credentials + business params. Saving validates first, then applies live
   via core/reload-config! (no restart)."
  (:require [analitica.settings :as settings]
            [analitica.settings.validate :as validate]
            [analitica.core :as core]))

;; field-key → {:setting-key dotted, :secret? bool}
(def ^:private mp-fields
  {:wb   {:api-token   {:setting-key "mp.wb.api-token"   :secret? true}}
   :ozon {:client-id   {:setting-key "mp.ozon.client-id" :secret? false}
          :api-key     {:setting-key "mp.ozon.api-key"   :secret? true}}
   :ym   {:oauth-token {:setting-key "mp.ym.oauth-token" :secret? true}
          :campaign-id {:setting-key "mp.ym.campaign-id" :secret? false}
          :business-id {:setting-key "mp.ym.business-id" :secret? false}}})

(defn- body-of [req]
  (or (:body-params req) (:params req) {}))

(defn- parse-mp [req]
  (let [b  (body-of req)
        mp (some-> (or (:marketplace b) (get b "marketplace")) name keyword)]
    (when (contains? mp-fields mp) mp)))

(defn- cfg-from-body
  "Pull the per-MP cfg map (field-key → value) out of the request body, using
   the field-key names mp-fields expects (e.g. :api-token)."
  [mp req]
  (let [b (body-of req)]
    (reduce-kv (fn [acc field _]
                 (if-let [v (or (get b field) (get b (name field)))]
                   (assoc acc field v)
                   acc))
               {}
               (get mp-fields mp))))

(defn get-settings [_req]
  {:status 200 :body {:settings (settings/masked-all)}})

(defn test-marketplace [req]
  (if-let [mp (parse-mp req)]
    {:status 200 :body (validate/validate-credentials mp (cfg-from-body mp req))}
    {:status 400 :body {:error "Unknown or missing marketplace"}}))

(defn put-marketplace [req]
  (if-let [mp (parse-mp req)]
    (let [cfg     (cfg-from-body mp req)
          verdict (validate/validate-credentials mp cfg)]
      (if-not (:valid? verdict)
        {:status 422 :body verdict}
        (do
          (doseq [[field v] cfg]
            (let [{:keys [setting-key secret?]} (get-in mp-fields [mp field])]
              (settings/set! setting-key v :secret? secret?)))
          (let [reload (core/reload-config!)]
            {:status 200 :body {:ok true :valid? true
                                :marketplaces (:marketplaces reload)}}))))
    {:status 400 :body {:error "Unknown or missing marketplace"}}))
