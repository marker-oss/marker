(ns analitica.util.http
  "HTTP request helpers (rate-limited, with mulog tracing).

   mulog events emitted here: `::request`, `::response`. Schema-drift
   warnings (`::schema-drift`) are emitted downstream in
   `analitica.schema.validator/validate!` when a response matches a
   registered contract but contains recoverable divergences (extra
   keys). The HTTP layer is transport-only and does not validate
   payloads — validation is opt-in per endpoint via the
   `with-validation` wrapper in ingest, not via HTTP middleware; see
   specs/001-openapi-schemas/research.md §R4 for the rationale."
  (:require [hato.client :as hc]
            [jsonista.core :as j]
            [com.brunobonacci.mulog :as mu])
  (:import [java.util.concurrent Semaphore]))

;; ---------------------------------------------------------------------------
;; Rate limiter (token-bucket style via Semaphore)
;; ---------------------------------------------------------------------------

(defn make-rate-limiter
  "Creates a rate limiter that allows `rpm` requests per minute.
   Returns a fn that blocks until a slot is available."
  [rpm]
  (let [interval-ms (long (/ 60000 (max 1 (double rpm))))
        permits     (max 1 (min (int rpm) 10))
        sem         (Semaphore. permits true)
        _refiller   (doto (Thread.
                          (fn []
                            (while (not (Thread/interrupted))
                              (try
                                (Thread/sleep interval-ms)
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

(defonce ^:private http-client (atom nil))

(defn- get-client []
  (or @http-client
      (let [c (hc/build-http-client {:connect-timeout 60000
                                     :redirect-policy :normal
                                     :version         :http-1.1})]
        (reset! http-client c)
        c)))

(defn- reset-client! []
  (reset! http-client nil))

(def ^:private json-mapper (j/object-mapper {:decode-key-fn true}))

(defn- parse-json [body]
  (when (and body (not= body ""))
    (j/read-value body json-mapper)))

(defn- connection-error? [e]
  (let [msg (str (type e) " " (.getMessage e))]
    (or (instance? java.nio.channels.ClosedChannelException e)
        (instance? java.io.IOException e)
        (.contains msg "ClosedChannel")
        (.contains msg "BUFFER_UNDERFLOW")
        (.contains msg "connect timed out"))))

(defn- retry-with-backoff
  "Retries f up to max-retries times with exponential backoff.
   Resets the HTTP client on connection errors before retrying."
  [f max-retries]
  (loop [attempt 0]
    (let [result (try
                   {:ok true :value (f)}
                   (catch Exception e
                     {:ok false :error e :attempt attempt}))]
      (if (:ok result)
        (:value result)
        (if (>= (inc attempt) max-retries)
          (throw (:error result))
          (do
            (when (connection-error? (:error result))
              (reset-client!))
            (Thread/sleep (max 3000 (* (long (Math/pow 2 attempt)) 1000)))
            (recur (inc attempt))))))))

(defn request
  "Makes an HTTP request with rate limiting, retry, and JSON parsing.

   Options:
     :method        - :get or :post (default :get)
     :url           - full URL
     :token         - API token (sent as-is in Authorization header)
     :extra-headers - map of additional headers to merge (e.g. {\"Client-Id\" \"...\"})
     :query-params  - map of query params
     :body          - request body (will be JSON-encoded if map)
     :limiter-key   - key for rate limiter
     :limiter-rpm   - requests per minute for this limiter
     :max-retries   - max retry attempts (default 5)"
  [{:keys [method url token extra-headers query-params body limiter-key limiter-rpm max-retries as]
    :or   {method :get max-retries 5 as :string}}]
  (when (and limiter-key limiter-rpm)
    (let [limiter (get-limiter limiter-key limiter-rpm)]
      (limiter)))
  (mu/log ::request :method method :url url)
  (retry-with-backoff
   (fn []
     (let [opts (cond-> {:http-client      (get-client)
                         :timeout          120000
                         :headers          (cond-> (merge {"Accept" "application/json"} extra-headers)
                                             token (assoc "Authorization" token))
                         :as               as}
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
         200 (if (= as :byte-array)
               (:body resp)
               (parse-json (:body resp)))
         204 nil
         429 (throw (ex-info "Rate limited" {:type :rate-limited :url url}))
         401 (throw (ex-info "Unauthorized — check your API token" {:type :unauthorized :url url}))
         (throw (ex-info (str "HTTP " (:status resp))
                         {:type   :http-error
                          :status (:status resp)
                          :url    url
                          :body   (:body resp)})))))
   max-retries))
