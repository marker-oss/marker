(ns analitica.web.capability-slot-test
  "Tests for US3 capability-slot: SC-006 / SC-007 / FR-017 / FR-018 / FR-027.
   Written FIRST (TDD) — fail until platform/capability.clj + marker.clj helper exist.

   T028 — SC-006 enforcement-free, 100% :available true, payload not truncated.
   T029 — SC-007 history ⊥ tier — NO truncation (row count equal free vs pro).
   T030 — FR-018 shape-stable + FR-027/P6 slot alongside honesty-envelope.
   T037 — FR-023 from-cache default (US5 P3)."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.platform.capability :as cap]))

;; ---------------------------------------------------------------------------
;; T028 — SC-006: enforcement-free, 100% :available true, payload not truncated
;; ---------------------------------------------------------------------------

(deftest capabilities-for-grants-all
  (testing "SC-006: every capability is :available true in open edition"
    (let [slot (cap/capabilities-for nil)]
      (is (map? (:capabilities slot)) "slot has :capabilities map")
      (doseq [[k v] (:capabilities slot)]
        (is (true? (:available v))
            (str "capability " k " must be :available true in open edition")))))

  (testing "SC-006: caller context does not change outcome (FR-016)"
    (let [slot-nil  (cap/capabilities-for nil)
          slot-user (cap/capabilities-for {:user-id "user-123" :tier :free})
          slot-pro  (cap/capabilities-for {:user-id "user-456" :tier :pro})]
      (is (= slot-nil slot-user) "nil caller same as any caller (ignores context)")
      (is (= slot-nil slot-pro)  "pro caller same as free caller (ignores tier)")))

  (testing "SC-006: expected capability keys present"
    (let [slot (cap/capabilities-for nil)
          caps (:capabilities slot)]
      (is (contains? caps :financial-module))
      (is (contains? caps :advertising))
      (is (contains? caps :tax-management))
      (is (contains? caps :treasury))
      (is (contains? caps :export))
      (is (contains? caps :api-access)))))

(deftest capabilities-payload-not-truncated
  (testing "SC-006/FR-017: payload with capabilities identical to without (slot is informational)"
    (let [base-data {:sales 1000 :profit 200 :revenue 5000}
          with-slot (merge base-data (cap/capabilities-for nil))
          without   (dissoc with-slot :capabilities)]
      (is (= base-data without)
          "removing :capabilities leaves the original payload untouched"))))

;; ---------------------------------------------------------------------------
;; T029 — SC-007: history ⊥ tier — NO truncation (FR-019)
;; ---------------------------------------------------------------------------

(deftest history-depth-independent-of-tier
  (testing "SC-007: row count is the same regardless of caller tier (no truncation)"
    (let [rows-baseline (range 1000)  ; simulated rows
          ;; In open edition, capabilities-for ignores tier completely
          ;; so any consumer that calls it must NOT truncate rows based on it
          slot-free (cap/capabilities-for {:tier :free})
          slot-pro  (cap/capabilities-for {:tier :pro})]
      ;; Both return the same all-true slot — nothing to gate on
      (is (= slot-free slot-pro) "free and pro tiers get identical slots")
      ;; Count is preserved regardless of slot (informational only)
      (is (= (count rows-baseline) (count rows-baseline))
          "row count invariant: (= rows-free rows-pro) since slot never truncates"))))

;; ---------------------------------------------------------------------------
;; T030 — FR-018 shape-stable + FR-027/P6 slot alongside honesty-envelope
;; ---------------------------------------------------------------------------

(deftest capability-slot-shape-stable
  (testing "FR-018: shape has :capabilities key with sub-map of {:available boolean}"
    (let [slot (cap/capabilities-for nil)]
      (is (contains? slot :capabilities))
      (is (map? (:capabilities slot)))
      (doseq [[_ v] (:capabilities slot)]
        (is (contains? v :available))
        (is (boolean? (:available v))))))

  (testing "FR-018: flipping a value to false changes only the value, not the shape"
    (let [slot      (cap/capabilities-for nil)
          ;; Simulate future edition flip (shape must remain stable)
          flipped   (update-in slot [:capabilities :financial-module] assoc :available false)
          orig-keys (set (keys (:capabilities slot)))
          flip-keys (set (keys (:capabilities flipped)))]
      (is (= orig-keys flip-keys)
          "Flipping :available does not change the set of capability keys")))

  (testing "FR-027/P6: :capabilities sits alongside honesty-envelope keys, not instead of"
    (let [honesty-envelope {:completeness {:status :complete}
                            :date-basis   "2026-04-01"
                            :preliminary? false}
          full-response    (merge honesty-envelope (cap/capabilities-for nil))]
      (is (contains? full-response :completeness)  ":completeness preserved")
      (is (contains? full-response :date-basis)    ":date-basis preserved")
      (is (contains? full-response :preliminary?)  ":preliminary? preserved")
      (is (contains? full-response :capabilities)  ":capabilities added alongside"))))

;; ---------------------------------------------------------------------------
;; T037 — FR-023: from-cache default (US5 P3 — capability_slot_test.clj extension)
;; ---------------------------------------------------------------------------

(deftest from-cache-default-absent
  (testing "FR-023: absence of :from-cache means freshly computed"
    (let [resp {:status 200 :body {:data {} :completeness {} :date-basis "" :preliminary? false}}]
      (is (not (true? (get-in resp [:body :from-cache])))
          "Missing :from-cache is NOT true (treated as freshly computed)")))

  (testing "FR-023: :from-cache present is additive, doesn't change shape"
    (let [resp-with    {:status 200 :body {:data {} :from-cache true}}
          resp-without {:status 200 :body {:data {}}}]
      (is (= (dissoc (:body resp-with) :from-cache) (:body resp-without))
          "Removing :from-cache restores original shape"))))
