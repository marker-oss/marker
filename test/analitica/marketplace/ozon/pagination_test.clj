(ns analitica.marketplace.ozon.pagination-test
  "TDD tests for Ozon FBO/FBS cursor-pagination fix.

   The bug: fbo-orders / fbs-orders exit after the first page because the
   cursor-equality check (`new-last-id == last-id`) fires immediately when
   Ozon returns last_id: \"\" on the first page.

   The fix: exit when `count(postings) < page-limit` (last page is short),
   and defensively exit with a warning when a full page returns an unchanged
   cursor (API not progressing) after the first call."
  (:require [clojure.test :refer [deftest testing is]]
            [analitica.marketplace.ozon.api :as ozon-api]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-client [] :stub-client)

(defn- make-postings
  "Generate n stub posting maps."
  [n]
  (mapv #(hash-map :posting_number (str "P-" %)) (range n)))

;; ---------------------------------------------------------------------------
;; FBO tests
;; ---------------------------------------------------------------------------

(deftest fbo-single-short-page
  (testing "single page with fewer items than limit → 1 API call, all items returned"
    (let [call-count (atom 0)]
      (with-redefs [analitica.marketplace.ozon.client/post-request
                    (fn [_client _path & _opts]
                      (swap! call-count inc)
                      {:result {:postings (make-postings 42) :last_id ""}})]
        (let [result (ozon-api/fbo-orders (make-client) "2024-01-01" "2024-01-31")]
          (is (= 1 @call-count) "exactly one API call for a short page")
          (is (= 42 (count result)) "all 42 postings returned"))))))

(deftest fbo-multiple-full-pages
  (testing "3 full pages of 100 items + 1 short page of 50 → 4 API calls, 350 items total"
    (let [call-count (atom 0)
          pages      {"" {:result {:postings (make-postings 100) :last_id "c1"}}
                      "c1" {:result {:postings (make-postings 100) :last_id "c2"}}
                      "c2" {:result {:postings (make-postings 100) :last_id "c3"}}
                      "c3" {:result {:postings (make-postings 50)  :last_id ""}}}]
      (with-redefs [analitica.marketplace.ozon.client/post-request
                    (fn [_client _path & {:keys [body]}]
                      (swap! call-count inc)
                      (get pages (get body "last_id" "")
                           {:result {:postings [] :last_id ""}}))]
        (let [result (ozon-api/fbo-orders (make-client) "2024-01-01" "2024-01-31")]
          (is (= 4 @call-count) "4 API calls: 3 full + 1 short")
          (is (= 350 (count result)) "100+100+100+50 = 350 postings"))))))

(deftest fbo-empty-cursor-on-full-page-still-recurses
  (testing "original bug: first page returns last_id \"\" but has 100 items → must recurse"
    ;; Page 1: 100 items, last_id: "" (same as initial cursor!)
    ;; Page 2: 50 items, last_id: "" → short page (<100), done cleanly
    ;; Total: 150 items, 2 calls — new code must NOT exit at page 1
    (let [call-count (atom 0)
          result-box (atom nil)]
      (with-redefs [analitica.marketplace.ozon.client/post-request
                    (fn [_client _path & _opts]
                      (let [n (swap! call-count inc)]
                        (if (= 1 n)
                          {:result {:postings (make-postings 100) :last_id ""}}
                          {:result {:postings (make-postings 50)  :last_id ""}})))]
        (reset! result-box (ozon-api/fbo-orders (make-client) "2024-01-01" "2024-01-31"))
        (is (= 2 @call-count) "2 API calls despite last_id being empty on page 1")
        (is (= 150 (count @result-box)) "100 + 50 = 150 postings")))))

(deftest fbo-unchanged-cursor-on-full-page-exits-with-warning
  (testing "full page but cursor never changes → exit with warning, no infinite loop"
    ;; Page 1: cursor "" → 100 items, new last_id "c1"
    ;; Page 2: cursor "c1" → 100 items, new last_id "c1" ← stalled!
    ;; Must exit with warning after 2 calls, return 200 items
    (let [call-count (atom 0)
          result-box (atom nil)
          output     (with-out-str
                       (with-redefs [analitica.marketplace.ozon.client/post-request
                                     (fn [_client _path & _opts]
                                       (let [n (swap! call-count inc)]
                                         (if (= 1 n)
                                           {:result {:postings (make-postings 100) :last_id "c1"}}
                                           {:result {:postings (make-postings 100) :last_id "c1"}})))]
                         (reset! result-box
                                 (ozon-api/fbo-orders (make-client) "2024-01-01" "2024-01-31"))))]
      (is (= 2 @call-count) "stops after detecting stalled cursor, no infinite loop")
      (is (= 200 (count @result-box)) "includes items from both pages before bail-out")
      (is (re-find #"(?i)warn|stall|loop|cursor" output)
          "warning printed to stdout"))))

;; ---------------------------------------------------------------------------
;; FBS tests — same fix applied to paste-twin function
;; ---------------------------------------------------------------------------

(deftest fbs-single-short-page
  (testing "FBS: single page with fewer items than limit → 1 API call, all items returned"
    (let [call-count (atom 0)]
      (with-redefs [analitica.marketplace.ozon.client/post-request
                    (fn [_client _path & _opts]
                      (swap! call-count inc)
                      {:result {:postings (make-postings 7) :last_id ""}})]
        (let [result (ozon-api/fbs-orders (make-client) "2024-01-01" "2024-01-31")]
          (is (= 1 @call-count))
          (is (= 7 (count result))))))))

(deftest fbs-empty-cursor-on-full-page-still-recurses
  (testing "FBS: original-bug scenario — full first page with last_id \"\" must not exit at page 1"
    ;; Paste-twin parity with fbo-empty-cursor-on-full-page-still-recurses.
    ;; If fbo gets the fix but fbs drifts, this guards against asymmetric edits.
    (let [call-count (atom 0)
          result-box (atom nil)]
      (with-redefs [analitica.marketplace.ozon.client/post-request
                    (fn [_client _path & _opts]
                      (let [n (swap! call-count inc)]
                        (if (= 1 n)
                          {:result {:postings (make-postings 100) :last_id ""}}
                          {:result {:postings (make-postings 25)  :last_id ""}})))]
        (reset! result-box (ozon-api/fbs-orders (make-client) "2024-01-01" "2024-01-31"))
        (is (= 2 @call-count) "FBS: 2 API calls despite last_id \"\" on page 1")
        (is (= 125 (count @result-box)) "FBS: 100 + 25 = 125 postings")))))

(deftest fbs-multiple-full-pages
  (testing "FBS: 2 full pages + 1 short → 3 calls, correct total"
    (let [call-count (atom 0)
          pages      {"" {:result {:postings (make-postings 100) :last_id "f1"}}
                      "f1" {:result {:postings (make-postings 100) :last_id "f2"}}
                      "f2" {:result {:postings (make-postings 30)  :last_id ""}}}]
      (with-redefs [analitica.marketplace.ozon.client/post-request
                    (fn [_client _path & {:keys [body]}]
                      (swap! call-count inc)
                      (get pages (get body "last_id" "")
                           {:result {:postings [] :last_id ""}}))]
        (let [result (ozon-api/fbs-orders (make-client) "2024-01-01" "2024-01-31")]
          (is (= 3 @call-count))
          (is (= 230 (count result))))))))

(deftest fbs-unchanged-cursor-exits-with-warning
  (testing "FBS: stalled cursor on full page → warning + bail-out"
    (let [call-count (atom 0)
          result-box (atom nil)
          output     (with-out-str
                       (with-redefs [analitica.marketplace.ozon.client/post-request
                                     (fn [_client _path & _opts]
                                       (let [n (swap! call-count inc)]
                                         (if (= 1 n)
                                           {:result {:postings (make-postings 100) :last_id "s1"}}
                                           {:result {:postings (make-postings 100) :last_id "s1"}})))]
                         (reset! result-box
                                 (ozon-api/fbs-orders (make-client) "2024-01-01" "2024-01-31"))))]
      (is (= 2 @call-count))
      (is (= 200 (count @result-box)))
      (is (re-find #"(?i)warn|stall|loop|cursor" output)))))
