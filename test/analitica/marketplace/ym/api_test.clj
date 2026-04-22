(ns analitica.marketplace.ym.api-test
  "Unit tests for YM raw API wrappers.

   FR-020 (spec 003 US1): `order-stats` :statuses kwarg is optional —
   when omitted or empty, the underlying request body MUST NOT contain
   a :statuses key (YM default = all statuses). When provided and
   non-empty, the key must be present and preserved verbatim."
  (:require [clojure.test :refer [deftest testing is]]
            [analitica.marketplace.ym.api :as api]
            [analitica.marketplace.ym.client :as client]))

(defn- capture-body
  "Invoke `f` with `client/post-request` stubbed to capture the first
   request's body. Returns the captured body map. The stub returns a
   single-page empty response so pagination terminates after one call."
  [f]
  (let [captured (atom nil)]
    (with-redefs [client/post-request
                  (fn [_client _path & {:keys [body]}]
                    (when (nil? @captured) (reset! captured body))
                    {:result {:orders [] :paging {:nextPageToken nil}}})]
      (f))
    @captured))

(def ^:private fake-client
  (client/map->YMClient {:oauth-token "t" :campaign-id "c" :business-id "b"
                         :rate-limits {:default 600}}))

(deftest order-stats-omits-statuses-when-not-supplied
  (testing "FR-020: no :statuses kwarg → request body MUST NOT contain :statuses"
    (let [body (capture-body #(api/order-stats fake-client "2026-01-01" "2026-01-31"))]
      (is (some? body) "post-request should have been invoked")
      (is (not (contains? body :statuses))
          ":statuses key must be absent so YM returns all statuses (default)")
      (is (= "2026-01-01" (:dateFrom body)))
      (is (= "2026-01-31" (:dateTo body))))))

(deftest order-stats-includes-statuses-when-supplied
  (testing "FR-020: explicit :statuses vector → body contains the exact value"
    (let [body (capture-body
                 #(api/order-stats fake-client "2026-01-01" "2026-01-31"
                                   :statuses ["DELIVERED"]))]
      (is (contains? body :statuses))
      (is (= ["DELIVERED"] (:statuses body)))))
  (testing "multi-status subset is preserved as-is"
    (let [body (capture-body
                 #(api/order-stats fake-client "2026-01-01" "2026-01-31"
                                   :statuses ["DELIVERED" "CANCELLED_IN_DELIVERY"]))]
      (is (= ["DELIVERED" "CANCELLED_IN_DELIVERY"] (:statuses body))))))

(deftest order-stats-treats-empty-statuses-as-omitted
  (testing "FR-020: empty :statuses vector → key MUST NOT be added (seq check)"
    (let [body (capture-body
                 #(api/order-stats fake-client "2026-01-01" "2026-01-31"
                                   :statuses []))]
      (is (not (contains? body :statuses))
          "empty vector is equivalent to nil — YM returns all statuses")))
  (testing "nil :statuses explicitly → same as omitted"
    (let [body (capture-body
                 #(api/order-stats fake-client "2026-01-01" "2026-01-31"
                                   :statuses nil))]
      (is (not (contains? body :statuses))))))
