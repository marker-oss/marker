(ns analitica.marketplace.ozon.client
  (:require [analitica.util.http :as http]))

;; ---------------------------------------------------------------------------
;; Ozon Seller API base URL
;; ---------------------------------------------------------------------------

(def ^:private base-url "https://api-seller.ozon.ru")

;; ---------------------------------------------------------------------------
;; Client record
;; ---------------------------------------------------------------------------

(defrecord OzonClient [client-id api-key rate-limits])

(defn make-client
  "Creates an Ozon API client from config map {:client-id \"...\" :api-key \"...\" :rate-limits ...}"
  [{:keys [client-id api-key rate-limits]}]
  (when-not client-id
    (throw (ex-info "Ozon client-id is required" {:missing-field :client-id})))
  (when-not api-key
    (throw (ex-info "Ozon api-key is required" {:missing-field :api-key})))
  (->OzonClient client-id api-key (or rate-limits {:default 60})))

;; ---------------------------------------------------------------------------
;; Request helpers
;; ---------------------------------------------------------------------------

(defn get-request
  "GET request to Ozon Seller API."
  [^OzonClient client path & {:keys [query-params limiter-key limiter-rpm]}]
  (http/request {:method        :get
                 :url           (str base-url path)
                 :extra-headers {"Client-Id" (:client-id client)
                                 "Api-Key"   (:api-key client)}
                 :query-params  query-params
                 :limiter-key   (or limiter-key :ozon/default)
                 :limiter-rpm   (or limiter-rpm (get (:rate-limits client) :default 60))}))

(defn post-request
  "POST request to Ozon Seller API."
  [^OzonClient client path & {:keys [body limiter-key limiter-rpm]}]
  (http/request {:method        :post
                 :url           (str base-url path)
                 :extra-headers {"Client-Id" (:client-id client)
                                 "Api-Key"   (:api-key client)}
                 :body          body
                 :limiter-key   (or limiter-key :ozon/default)
                 :limiter-rpm   (or limiter-rpm (get (:rate-limits client) :default 60))}))
