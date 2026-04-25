(ns analitica.marketplace.wb.client
  (:require [analitica.util.http :as http]))

;; ---------------------------------------------------------------------------
;; WB API base URLs
;; ---------------------------------------------------------------------------

(def base-urls
  {:statistics  "https://statistics-api.wildberries.ru"
   :analytics   "https://seller-analytics-api.wildberries.ru"
   :advert      "https://advert-api.wildberries.ru"
   :marketplace "https://marketplace-api.wildberries.ru"
   :content     "https://content-api.wildberries.ru"
   :prices      "https://discounts-prices-api.wildberries.ru"
   :feedbacks   "https://feedbacks-api.wildberries.ru"
   :returns     "https://returns-api.wildberries.ru"})

;; ---------------------------------------------------------------------------
;; Client record
;; ---------------------------------------------------------------------------

(defrecord WBClient [token rate-limits])

(defn make-client
  "Creates a WB API client from config map {:api-token ... :rate-limits ...}"
  [{:keys [api-token rate-limits]}]
  (when-not api-token
    (throw (ex-info "WB API token is required" {})))
  (->WBClient api-token (or rate-limits
                                        {:statistics  1     ;; 1 req/min
                                         :analytics   3     ;; 3 req/min (nm-report)
                                         :advert      300   ;; 5 req/sec
                                         :marketplace 300   ;; 5 req/sec
                                         :content     100   ;; 100 req/min
                                         :prices      100})));; 10 req/6sec ≈ 100 rpm

;; ---------------------------------------------------------------------------
;; Request helpers
;; ---------------------------------------------------------------------------

(defn- api-url [section path]
  (str (get base-urls section) path))

(defn get-request
  "GET request to WB API."
  [^WBClient client section path & {:keys [query-params limiter-key limiter-rpm]}]
  (http/request {:method       :get
                 :url          (api-url section path)
                 :token        (:token client)
                 :query-params query-params
                 :limiter-key  (or limiter-key (keyword "wb" (name section)))
                 :limiter-rpm  (or limiter-rpm (get (:rate-limits client) section 5))}))

(defn post-request
  "POST request to WB API."
  [^WBClient client section path & {:keys [body query-params limiter-key limiter-rpm]}]
  (http/request {:method       :post
                 :url          (api-url section path)
                 :token        (:token client)
                 :body         body
                 :query-params query-params
                 :limiter-key  (or limiter-key (keyword "wb" (name section)))
                 :limiter-rpm  (or limiter-rpm (get (:rate-limits client) section 5))}))
