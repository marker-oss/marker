(ns analitica.marketplace.ozon.performance.client
  "Ozon Performance (advertising) API client — spec 011-ozon-performance-ads (T017).

   The Performance API lives on a DISTINCT host `https://api-performance.ozon.ru`
   (Seller API is `api-seller.ozon.ru`) and uses a DIFFERENT auth scheme:
   OAuth client_credentials → short-lived Bearer token, refreshed on TTL
   (`expires_in`) or on a 401. Credentials are a SEPARATE Performance
   Client-Id + Client-Secret pair (research.md R1) — never the Seller keys.

   READ-ONLY posture (FR-010, P2): the client can reach ONLY
     - POST /api/client/token            (auth)
     - GET  /api/client/campaign         (list campaigns)
     - GET  /api/client/statistics/daily (P0 fast-path spend)
     - POST /api/client/statistics       (create async report — read-side, P1)
     - GET  /api/client/statistics/{UUID}(poll status, P1)
     - GET  /api/client/statistics/report(download, P1)
   No campaign-management / write endpoint is reachable. `reachable-urls`
   enumerates the exact surface so T016 can assert this.

   No new HTTP stack: `util/http/request` already supports :extra-headers
   (for `Authorization: Bearer …`) and JSON bodies (research.md R1)."
  (:require [analitica.util.http :as http]
            [com.brunobonacci.mulog :as mu]))

(def ^:private host "https://api-performance.ozon.ru")

(def endpoints
  "The COMPLETE read-only endpoint surface (FR-010). Any URL the client can
   construct is derived from this map; T016 asserts none is a write path."
  {:token      {:method :post :path "/api/client/token"}
   :campaign   {:method :get  :path "/api/client/campaign"}
   :daily      {:method :get  :path "/api/client/statistics/daily"}
   :stat-create   {:method :post :path "/api/client/statistics"}
   :stat-status   {:method :get  :path "/api/client/statistics/"}   ;; + UUID
   :stat-download {:method :get  :path "/api/client/statistics/report"}})

(defn reachable-urls
  "Return [{:url … :method …} …] — the full set of URLs the client can hit.
   Used by the read-only posture test (T016)."
  []
  (mapv (fn [{:keys [method path]}]
          {:url (str host path) :method method})
        (vals endpoints)))

;; ---------------------------------------------------------------------------
;; Client protocol — lets ingest call the client while tests inject a stub.
;; ---------------------------------------------------------------------------

(defprotocol PerformanceApi
  (-token        [this]            "Return a valid Bearer access-token string (cached).")
  (-list-campaigns [this]          "Return [Campaign …] (the :list vector).")
  (-daily-stats  [this campaign-ids from to] "Return [DailyStatRow …] (the :rows vector).")
  ;; Async statistics report (P1 / US3) — create → poll → download, same
  ;; pattern as the Ozon Seller storage-report. READ-ONLY (create-report is a
  ;; read-side async request, not a campaign write).
  (-create-statistics-report [this campaign-ids from to]
    "POST /api/client/statistics (groupBy DATE) → the report UUID string.")
  (-statistics-status [this uuid]
    "GET /api/client/statistics/{UUID} → status map, e.g. {:state \"OK\"}.")
  (-download-statistics-report [this uuid]
    "GET /api/client/statistics/report?UUID= → {:rows [StatisticsReportRow …]}."))

;; ---------------------------------------------------------------------------
;; Token cache — atom holding {:token … :expires-at <epoch-ms>}.
;; ---------------------------------------------------------------------------

(defn- now-ms [] (System/currentTimeMillis))

(defn- fetch-token!
  "POST /api/client/token → {:access_token … :expires_in …}. Caches the token
   with an expiry a little before the real TTL (30s safety margin)."
  [{:keys [client-id client-secret token-cache] :as _client}]
  (let [{:keys [method path]} (:token endpoints)
        resp (http/request
               {:method       method
                :url          (str host path)
                :body         {:client_id     client-id
                               :client_secret client-secret
                               :grant_type    "client_credentials"}
                :limiter-key  :ozon/performance
                :limiter-rpm  60})
        token   (:access_token resp)
        expires (or (:expires_in resp) 1800)]
    (reset! token-cache {:token token
                         :expires-at (+ (now-ms) (* 1000 (max 0 (- expires 30))))})
    token))

(defn- valid-cached-token [{:keys [token-cache]}]
  (let [{:keys [token expires-at]} @token-cache]
    (when (and token expires-at (< (now-ms) expires-at))
      token)))

;; ---------------------------------------------------------------------------
;; OzonPerfClient
;; ---------------------------------------------------------------------------

(declare do-authed-request do-authed-body-request)

(defrecord OzonPerfClient [client-id client-secret token-cache rate-limits]
  PerformanceApi
  (-token [this]
    (or (valid-cached-token this) (fetch-token! this)))

  (-list-campaigns [this]
    (let [{:keys [path]} (:campaign endpoints)]
      (:list (do-authed-request this :get path nil))))

  (-daily-stats [this campaign-ids from to]
    (let [{:keys [path]} (:daily endpoints)]
      (:rows (do-authed-request this :get path
                                {:campaignId (vec campaign-ids)
                                 :dateFrom   from
                                 :dateTo     to}))))

  (-create-statistics-report [this campaign-ids from to]
    (let [{:keys [path]} (:stat-create endpoints)]
      ;; POST body (not query params) — do-authed-body-request carries the JSON.
      (:UUID (do-authed-body-request this :post path
                                     {:campaigns (vec campaign-ids)
                                      :from      from
                                      :to        to
                                      :groupBy   "DATE"}))))

  (-statistics-status [this uuid]
    (let [{:keys [path]} (:stat-status endpoints)]
      (do-authed-request this :get (str path uuid) nil)))

  (-download-statistics-report [this uuid]
    (let [{:keys [path]} (:stat-download endpoints)]
      (do-authed-request this :get path {:UUID uuid}))))

(defn- do-authed-request
  "Issue an authorized request, transparently refreshing the Bearer token
   once on a 401 (research.md R1 — refresh-on-401). `query-params` may be nil."
  [client method path query-params]
  (let [do-req (fn [tok]
                 (http/request
                   {:method        method
                    :url           (str host path)
                    :extra-headers {"Authorization" (str "Bearer " tok)}
                    :query-params  query-params
                    :limiter-key   :ozon/performance
                    :limiter-rpm   60}))]
    (try
      (do-req (-token client))
      (catch clojure.lang.ExceptionInfo e
        (if (= :unauthorized (:type (ex-data e)))
          ;; Expired/invalid token → force a single refresh, then retry once.
          (do (mu/log ::performance-token-refresh :path path)
              (reset! (:token-cache client) nil)
              (do-req (-token client)))
          (throw e))))))

(defn- do-authed-body-request
  "Like do-authed-request but sends a JSON BODY (for the POST create-report
   read-side request), transparently refreshing the Bearer token once on 401."
  [client method path body]
  (let [do-req (fn [tok]
                 (http/request
                   {:method        method
                    :url           (str host path)
                    :extra-headers {"Authorization" (str "Bearer " tok)}
                    :body          body
                    :limiter-key   :ozon/performance
                    :limiter-rpm   60}))]
    (try
      (do-req (-token client))
      (catch clojure.lang.ExceptionInfo e
        (if (= :unauthorized (:type (ex-data e)))
          (do (mu/log ::performance-token-refresh :path path)
              (reset! (:token-cache client) nil)
              (do-req (-token client)))
          (throw e))))))

(defn make-client
  "Build an OzonPerfClient from {:client-id … :client-secret … :rate-limits …}.
   Both credential parts are required (partial creds = feature off, handled
   upstream by config/ozon-performance-config)."
  [{:keys [client-id client-secret rate-limits]}]
  (when-not (and client-id client-secret)
    (throw (ex-info "Ozon Performance client-id and client-secret are required"
                    {:missing (cond-> []
                                (not client-id) (conj :client-id)
                                (not client-secret) (conj :client-secret))})))
  (->OzonPerfClient client-id client-secret (atom nil) (or rate-limits {:default 60})))

;; ---------------------------------------------------------------------------
;; Stub client — used by tests to serve fixtures without touching the network.
;; ---------------------------------------------------------------------------

(defrecord StubPerfClient [data]
  PerformanceApi
  (-token [_] (get-in data [:token :access_token] "stub-token"))
  (-list-campaigns [_] (:campaigns data))
  (-daily-stats [_ _campaign-ids _from _to] (:daily-rows data))
  ;; Async report: create returns a fixed UUID, status is always OK, download
  ;; serves the fixture :report-rows. Lets US3 ingest run without the network.
  (-create-statistics-report [_ _campaign-ids _from _to]
    (get data :report-uuid "stub-report-uuid"))
  (-statistics-status [_ _uuid] (get data :report-status {:state "OK"}))
  (-download-statistics-report [_ _uuid] {:rows (:report-rows data)}))

(defn stub-client
  "Create a StubPerfClient serving the given fixture data:
     {:token {:access_token …} :campaigns [..] :daily-rows [..]
      :report-uuid \"…\" :report-status {:state \"OK\"} :report-rows [..]}."
  [data]
  (->StubPerfClient data))
