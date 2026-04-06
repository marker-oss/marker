(ns analitica.util.http
  (:require [hato.client :as hc]
            [jsonista.core :as j]
            [com.brunobonacci.mulog :as mu])
  (:import [java.util.concurrent Semaphore TimeUnit]))

;; ---------------------------------------------------------------------------
;; Rate limiter (token-bucket style via Semaphore)
;; ---------------------------------------------------------------------------

(defn make-rate-limiter
  "Creates a rate limiter that allows `rps` requests per second.
   Returns a fn that blocks until a slot is available."
  [rps]
  (let [permits  (max 1 (int rps))
        sem      (Semaphore. permits true)
        refiller (doto (Thread.
                        (fn []
                          (while (not (Thread/interrupted))
                            (try
                              (Thread/sleep (long (/ 1000 permits)))
                              (when (< (.availablePermits sem) permits)
                                (.release sem))
                              (catch InterruptedException _
                                (.interrupt (Thread/currentThread)))))))
                   (.setDaemon true)
                   (.start))]
    (fn wait-for-slot []
      (.acquire sem)
      nil)))

(defonce ^:private limiters (atom {}))

(defn get-limiter [key rps]
  (or (get @limiters key)
      (let [l (make-rate-limiter rps)]
        (swap! limiters assoc key l)
        l)))

;; ---------------------------------------------------------------------------
;; HTTP client
;; ---------------------------------------------------------------------------

(defonce ^:private client
  (hc/build-http-client {:connect-timeout 10000
                         :redirect-policy :normal}))

(def ^:private json-mapper (j/object-mapper {:decode-key-fn true}))

(defn- parse-json [body]
  (when (and body (not= body ""))
    (j/read-value body json-mapper)))

(defn- retry-with-backoff
  "Retries f up to max-retries times with exponential backoff."
  [f max-retries]
  (loop [attempt 0]
    (let [result (try
                   {:ok (f)}
                   (catch Exception e
                     {:error e :attempt attempt}))]
      (if (:ok result)
        (:ok result)
        (if (>= (inc attempt) max-retries)
          (throw (:error result))
          (do
            (Thread/sleep (* (long (Math/pow 2 attempt)) 1000))
            (recur (inc attempt))))))))

(defn request
  "Makes an HTTP request with rate limiting, retry, and JSON parsing.

   Options:
     :method       - :get or :post (default :get)
     :url          - full URL
     :token        - Bearer token
     :query-params - map of query params
     :body         - request body (will be JSON-encoded if map)
     :limiter-key  - key for rate limiter
     :limiter-rps  - requests per second for this limiter
     :max-retries  - max retry attempts (default 3)"
  [{:keys [method url token query-params body limiter-key limiter-rps max-retries]
    :or   {method :get max-retries 3}}]
  (when (and limiter-key limiter-rps)
    (let [limiter (get-limiter limiter-key limiter-rps)]
      (limiter)))
  (mu/log ::request :method method :url url)
  (retry-with-backoff
   (fn []
     (let [opts (cond-> {:http-client  client
                         :headers      (cond-> {"Accept" "application/json"}
                                         token (assoc "Authorization" (str "Bearer " token)))
                         :as           :string}
                  query-params (assoc :query-params query-params)
                  (and body (map? body))
                  (-> (assoc :body (j/write-value-as-string body))
                      (assoc-in [:headers "Content-Type"] "application/json"))
                  (and body (string? body))
                  (assoc :body body))
           resp (case method
                  :get  (hc/get url opts)
                  :post (hc/post url opts))]
       (mu/log ::response :status (:status resp) :url url)
       (case (:status resp)
         200 (parse-json (:body resp))
         204 nil
         429 (throw (ex-info "Rate limited" {:type :rate-limited :url url}))
         401 (throw (ex-info "Unauthorized — check your API token" {:type :unauthorized :url url}))
         (throw (ex-info (str "HTTP " (:status resp))
                         {:type   :http-error
                          :status (:status resp)
                          :url    url
                          :body   (:body resp)})))))
   max-retries))
