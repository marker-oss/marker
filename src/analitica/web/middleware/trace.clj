(ns analitica.web.middleware.trace
  "Ring middleware: wrap-request-trace — outer span per /api request (US2 T023).

   Emits a :marker/api-request mulog span for every /api/* request with:
     :endpoint    — normalised route template (NOT concrete param values — FR-014)
     :http-method — :get/:post/etc.
     :marketplace — optional, extracted from query params when present (≤3 values)
     :outcome     — :success | :error
     :duration-ms — auto-captured by mu/trace

   Bounded cardinality (FR-014): only allowed-label-keys reach mulog. The endpoint
   is normalised to a template (e.g. \"/api/v1/marker/reports/:type\") so it is
   not a per-request unique value that would explode cardinality.

   FAIL-OPEN (SC-005): span emission is async via mulog; a broken telemetry
   publisher never propagates to the request thread. Application errors
   (non-telemetry) are re-thrown after marking the span :error."
  (:require [com.brunobonacci.mulog :as mu]
            [analitica.telemetry :as tel]))

;; ---------------------------------------------------------------------------
;; Endpoint normalisation — map concrete URIs to route templates (FR-014)
;; ---------------------------------------------------------------------------

(def ^:private endpoint-patterns
  "Ordered pairs of [regex template]. First match wins.
   Patterns cover all routes in server.clj app-routes."
  [[#"/api/v1/marker/reports/[^/]+/article/[^/]+" "/api/v1/marker/reports/:type/article/:article"]
   [#"/api/v1/marker/reports/[^/]+"              "/api/v1/marker/reports/:type"]
   [#"/api/v1/marker/sku-detail/[^/]+"           "/api/v1/marker/sku-detail/:sku-id"]
   [#"/api/v1/marker/stocks/article/[^/]+"       "/api/v1/marker/stocks/article/:article"]
   [#"/api/v1/marker/chart/[^/]+"                "/api/v1/marker/chart/:type"]
   [#"/api/v1/marker/pulse-summary"              "/api/v1/marker/pulse-summary"]
   [#"/api/v1/marker/pnl"                        "/api/v1/marker/pnl"]
   [#"/api/v1/marker/sku-list"                   "/api/v1/marker/sku-list"]
   [#"/api/v1/marker/unit-baseline"              "/api/v1/marker/unit-baseline"]
   [#"/api/v1/marker/reconciliation"             "/api/v1/marker/reconciliation"]
   [#"/api/v1/marker/what-if-recalc"             "/api/v1/marker/what-if-recalc"]
   [#"/api/v1/marker/stocks/overview"            "/api/v1/marker/stocks/overview"]
   [#"/api/v1/marker/"                           "/api/v1/marker/:endpoint"]
   [#"/api/v1/"                                  "/api/v1/:endpoint"]
   [#"/api/"                                     "/api/:endpoint"]])

(defn- normalise-endpoint
  "Map a concrete URI to a bounded-cardinality route template.
   Falls back to the raw URI when no pattern matches (still bounded in practice
   since unknown routes are 404s)."
  [uri]
  (or (some (fn [[pat template]]
              (when (re-find pat uri) template))
            endpoint-patterns)
      uri))

(defn- parse-marketplace
  "Extract :marketplace from query params when present and valid.
   Returns nil when absent or unrecognised (keeps cardinality ≤ 3)."
  [params]
  (let [mp-str (or (get params :mp) (get params :marketplace) (get params "mp") (get params "marketplace"))]
    (when (string? mp-str)
      (let [kw (keyword mp-str)]
        (when (#{:ozon :wb :ym} kw) kw)))))

;; ---------------------------------------------------------------------------
;; wrap-request-trace — the outer ring middleware
;; ---------------------------------------------------------------------------

(defn wrap-request-trace
  "Ring middleware that emits a :marker/api-request span for every request.

   - Normalises the URI to a route-template endpoint (bounded cardinality).
   - Captures :outcome :success on normal return, :outcome :error on exception.
   - On exception: span is emitted as :error then the exception is RE-THROWN
     (fail-open = no telemetry death, not swallowing app errors).
   - Duration auto-captured by mu/trace.
   - Telemetry is asynchronous (mulog publishers); a broken publisher never
     propagates here (SC-005)."
  [handler]
  (fn [request]
    (let [uri      (or (:uri request) "/unknown")
          method   (or (:request-method request) :unknown)
          endpoint (normalise-endpoint uri)
          params   (or (:params request) {})
          mp       (parse-marketplace params)
          ;; Build allowed-only attrs for the span
          base-attrs (cond-> {:event/name  :marker/api-request
                              :endpoint    endpoint
                              :http-method method
                              :outcome     :success}
                       mp (assoc :marketplace mp))]
      ;; Validate attrs (enforces allow-list; throws on forbidden keys)
      ;; This call is side-effect free — just validates the map.
      (tel/span base-attrs)
      ;; Emit the mulog trace span around the actual handler call.
      ;; mu/trace auto-records duration; we inject :outcome.
      (mu/with-context {:endpoint    endpoint
                        :http-method method}
        (mu/trace :marker/api-request
          {:mulog/outcome :success}
          (try
            (let [response (handler request)]
              response)
            (catch Throwable t
              (mu/log :marker/api-request
                      :outcome :error
                      :error-message (.getMessage t))
              (throw t))))))))
