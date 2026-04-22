(ns analitica.marketplace.ym.client
  (:require [analitica.util.http :as http]))

;; ---------------------------------------------------------------------------
;; Yandex Market Partner API base URL
;; ---------------------------------------------------------------------------

(def ^:private base-url "https://api.partner.market.yandex.ru")

;; ---------------------------------------------------------------------------
;; Client record
;; ---------------------------------------------------------------------------

(defrecord YMClient [oauth-token campaign-id business-id rate-limits])

(defn make-client
  "Creates a Yandex Market API client from config map
   {:oauth-token \"...\" :campaign-id \"...\" :business-id \"...\" :rate-limits ...}"
  [{:keys [oauth-token campaign-id business-id rate-limits]}]
  (when-not oauth-token
    (throw (ex-info "YM oauth-token is required" {:missing-field :oauth-token})))
  (when-not campaign-id
    (throw (ex-info "YM campaign-id is required" {:missing-field :campaign-id})))
  (->YMClient oauth-token campaign-id business-id (or rate-limits {:default 600})))

;; ---------------------------------------------------------------------------
;; Request helpers
;; ---------------------------------------------------------------------------

(defn get-request
  "GET request to Yandex Market Partner API."
  [^YMClient client path & {:keys [query-params limiter-key limiter-rpm as]}]
  (http/request (cond-> {:method        :get
                         :url           (str base-url path)
                         :extra-headers {"Api-Key" (:oauth-token client)}
                         :query-params  query-params
                         :limiter-key   (or limiter-key :ym/default)
                         :limiter-rpm   (or limiter-rpm (get (:rate-limits client) :default 600))}
                  as (assoc :as as))))

(defn post-request
  "POST request to Yandex Market Partner API."
  [^YMClient client path & {:keys [body query-params limiter-key limiter-rpm]}]
  (http/request {:method        :post
                 :url           (str base-url path)
                 :extra-headers {"Api-Key" (:oauth-token client)}
                 :body          body
                 :query-params  query-params
                 :limiter-key   (or limiter-key :ym/default)
                 :limiter-rpm   (or limiter-rpm (get (:rate-limits client) :default 600))}))
