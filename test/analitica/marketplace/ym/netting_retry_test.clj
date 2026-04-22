(ns analitica.marketplace.ym.netting-retry-test
  "Unit tests for `ingest-ym-netting!` retry behaviour (T043a / FR-017).

   The YM `/v2/reports/united-netting/generate` endpoint is async: the POST
   returns a reportId; the report is built server-side and may take many
   minutes (observed 40+ min production wait 2026-04-22). The ingest must
   poll `GET /v2/reports/info/{reportId}` with exponential backoff, tolerate
   long waits, and degrade gracefully (no exception) on timeout/failure.

   These tests stub `ym-client/post-request` and `ym-client/get-request`
   with in-memory sequences so no real HTTP is made, and pass
   `:backoff-seq [0 0 0 ...]` so the test never actually sleeps."
  (:require [clojure.test :refer [deftest testing is]]
            [analitica.audit.test-helpers :as th]
            [analitica.db :as db]
            [analitica.ingest :as ingest]
            [analitica.marketplace.ym.client :as ym-client]
            [analitica.schema.loader :as schema-loader]
            [analitica.schema.registry :as schema-registry]))

;; ---------------------------------------------------------------------------
;; Fixtures — load the :ym/united-netting contract for each test.
;; ---------------------------------------------------------------------------

(defn- with-registry [f]
  (schema-registry/clear!)
  (schema-loader/load-all!)
  (try (f) (finally (schema-registry/clear!))))

(def ^:private fake-client
  (ym-client/map->YMClient {:oauth-token "t"
                            :campaign-id "c"
                            :business-id "673320"
                            :rate-limits {:default 600}}))

;; ---------------------------------------------------------------------------
;; Stub helpers
;; ---------------------------------------------------------------------------

(defn- stub-post-generate
  "Stub `ym-client/post-request` to return a canned report-generate response."
  [report-id]
  (fn [_client _path & _]
    {:result {:reportId report-id :estimatedGenerationTime 30000}}))

(defn- stub-get-info-sequence
  "Stub `ym-client/get-request` with a sequence of canned status responses.
   The first N-1 responses have status=PENDING; the Nth can be DONE/FAILED.
   Returns a fn [client path & {…}] and an atom counting invocations."
  [status-seq]
  (let [counter (atom 0)]
    [counter
     (fn [_client _path & _]
       (let [idx @counter
             resp (nth status-seq idx (last status-seq))]
         (swap! counter inc)
         resp))]))

(defn- done-response
  "Build a YM /v2/reports/info response with status=DONE and a stub file URL."
  [file-url]
  {:result {:status "DONE"
            :subStatus "OK"
            :file file-url}})

(def ^:private pending-response
  {:result {:status "PENDING"}})

(def ^:private failed-response
  {:result {:status "FAILED" :subStatus "ERROR"}})

;; ---------------------------------------------------------------------------
;; Test 1: successful retry after 3 PENDING, then DONE.
;; ---------------------------------------------------------------------------

(deftest polls-until-done-then-downloads
  (testing "FR-017: polls /reports/info with backoff, breaks on DONE, attempts download"
    (with-registry
      (fn []
        (th/with-isolated-db
          (fn []
            (let [report-id    "test-report-done"
                  file-url     "https://stub/report.zip"
                  download-hit (atom 0)
                  ;; 3× PENDING then DONE → 4 polls total
                  status-seq   [pending-response
                                pending-response
                                pending-response
                                (done-response file-url)]
                  [poll-counter get-stub] (stub-get-info-sequence status-seq)]
              (with-redefs [ym-client/post-request (stub-post-generate report-id)
                            ym-client/get-request  get-stub
                            ;; Stub the ZIP download step to return a minimal
                            ;; {:rows []} payload; see impl for the injection
                            ;; point (`fetch-and-unzip-netting`).
                            ingest/download-netting-zip
                            (fn [url]
                              (swap! download-hit inc)
                              (is (= file-url url))
                              {:rows []})]
                (let [result (ingest/ingest-ym-netting!
                               fake-client
                               :dateFrom "2026-03-01"
                               :dateTo   "2026-03-31"
                               :backoff-seq [0 0 0 0 0 0 0])]
                  (is (= 4 @poll-counter)
                      "should have polled 4 times before seeing DONE")
                  (is (= 1 @download-hit)
                      "should have attempted download exactly once")
                  (is (some? result) "non-nil result on success")
                  (is (= 0 (:rows-count result))
                      "empty rows payload → rows-count 0"))))))))))

;; ---------------------------------------------------------------------------
;; Test 2: all polls return PENDING → timeout, mu/log emitted, no throw.
;; ---------------------------------------------------------------------------

(deftest timeout-after-all-backoffs-graceful
  (testing "FR-017: PENDING across all backoff attempts → mu/log ::netting-timeout, nil"
    (with-registry
      (fn []
        (th/with-isolated-db
          (fn []
            (let [report-id "test-report-timeout"
                  status-seq (repeat 10 pending-response)
                  [poll-counter get-stub] (stub-get-info-sequence status-seq)
                  download-hit (atom 0)
                  result (atom ::unset)]
              (with-redefs [ym-client/post-request (stub-post-generate report-id)
                            ym-client/get-request  get-stub
                            ingest/download-netting-zip
                            (fn [_url] (swap! download-hit inc) {:rows []})]
                (try
                  (reset! result
                          (ingest/ingest-ym-netting!
                            fake-client
                            :dateFrom "2026-03-01"
                            :dateTo   "2026-03-31"
                            :backoff-seq [0 0 0]))
                  (catch Throwable t
                    (is false
                        (str "timeout path MUST NOT throw; got "
                             (type t) ": " (.getMessage t)))))
                (is (nil? @result) "should return nil on timeout")
                (is (zero? @download-hit)
                    "no download attempted after timeout")
                (is (= 3 @poll-counter)
                    "polled exactly once per backoff entry (3 entries → 3 polls)")))))))))

;; ---------------------------------------------------------------------------
;; Test 3: status=FAILED → mu/log ::netting-failed, nil, no throw.
;; ---------------------------------------------------------------------------

(deftest failed-status-returns-nil-no-throw
  (testing "FR-017: status=FAILED → mu/log ::netting-failed, nil, no exception"
    (with-registry
      (fn []
        (th/with-isolated-db
          (fn []
            (let [report-id "test-report-failed"
                  status-seq [failed-response]
                  [poll-counter get-stub] (stub-get-info-sequence status-seq)
                  download-hit (atom 0)
                  result (atom ::unset)]
              (with-redefs [ym-client/post-request (stub-post-generate report-id)
                            ym-client/get-request  get-stub
                            ingest/download-netting-zip
                            (fn [_url] (swap! download-hit inc) {:rows []})]
                (try
                  (reset! result
                          (ingest/ingest-ym-netting!
                            fake-client
                            :dateFrom "2026-03-01"
                            :dateTo   "2026-03-31"
                            :backoff-seq [0 0 0]))
                  (catch Throwable t
                    (is false
                        (str "failed-status path MUST NOT throw; got "
                             (type t) ": " (.getMessage t)))))
                (is (nil? @result) "should return nil on FAILED")
                (is (zero? @download-hit)
                    "download never attempted after FAILED status")
                (is (pos? @poll-counter)
                    "polled at least once to observe FAILED status")))))))))
