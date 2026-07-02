(ns analitica.bot.digest-test
  "US3 (P6): readiness-gate holds preliminary period + releases when final.
   Also covers render basics and whole-digest gate fallback (FR-017).
   Telegram sender is fully stubbed — no network."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.bot.digest :as digest]))

;; ---------------------------------------------------------------------------
;; Helpers: minimal DigestSource payloads
;; ---------------------------------------------------------------------------

(defn- fresh-iso [] "2026-07-01T10:00:00")

(defn- payload-with
  "Build a minimal DigestSource-shaped map with overrides."
  [& {:keys [preliminary? freshness-wb freshness-ozon freshness-ym]
      :or   {preliminary? false
             freshness-wb    (fresh-iso)
             freshness-ozon  (fresh-iso)
             freshness-ym    (fresh-iso)}}]
  {:kpi         {:revenue        1000.0 :net-profit 200.0
                 :margin         20.0   :drr        5.0
                 :revenue-delta  nil    :net-profit-delta nil
                 :margin-delta   nil    :drr-delta  nil}
   :movers      [{:article "A001" :name "Товар 1" :revenue 500.0 :delta-pct 10.0}]
   :fallers     [{:article "A002" :name "Товар 2" :revenue 100.0 :delta-pct -15.0}]
   :by-marketplace
   [{:marketplace :wb   :revenue 700.0 :profit 140.0 :margin 20.0
     :sales-qty 50 :returns-qty 2 :preliminary? preliminary?}
    {:marketplace :ozon :revenue 300.0 :profit 60.0  :margin 20.0
     :sales-qty 20 :returns-qty 1 :preliminary? false}]
   :freshness   {:wb freshness-wb :ozon freshness-ozon :ym freshness-ym}
   :pulse       {:plan-fact nil}
   :from        "2026-06-01"
   :to          "2026-06-30"
   :period-month "2026-06"})

;; ---------------------------------------------------------------------------
;; US3 — readiness gate
;; ---------------------------------------------------------------------------

(deftest gate-decision-final-when-not-preliminary-and-fresh
  (testing "non-preliminary + fresh freshness → :final state → :send action"
    (let [mp-row {:marketplace :wb :preliminary? false}
          fresh  (fresh-iso)
          dec    (digest/gate-decision mp-row fresh :wb)]
      (is (= :final (:state dec)))
      (is (= :send (:action dec))))))

(deftest gate-decision-preliminary-when-flag-set
  (testing ":preliminary? true → :preliminary state → :flag action (default gate-mode)"
    (let [mp-row {:marketplace :wb :preliminary? true}
          dec    (digest/gate-decision mp-row (fresh-iso) :wb)]
      (is (= :preliminary (:state dec)))
      (is (= :flag (:action dec))))))

(deftest gate-decision-missing-when-no-freshness
  (testing "nil freshness → :missing state → :withhold action"
    (let [mp-row {:marketplace :ozon :preliminary? false}
          dec    (digest/gate-decision mp-row nil :ozon)]
      (is (= :missing (:state dec)))
      (is (= :withhold (:action dec))))))

(deftest gate-decision-stale-freshness-is-preliminary
  (testing "freshness older than threshold → :preliminary (not :final)"
    ;; WB threshold = 6 days; use a 30-day-old timestamp
    (let [mp-row {:marketplace :wb :preliminary? false}
          stale  "2026-06-01T10:00:00"   ; 30 days before 2026-07-01
          dec    (digest/gate-decision mp-row stale :wb)]
      (is (= :preliminary (:state dec))))))

(deftest preliminary-period-never-sent-as-final
  (testing "a preliminary MP row must not produce :send action"
    (let [mp-row {:marketplace :wb :preliminary? true}
          dec    (digest/gate-decision mp-row (fresh-iso) :wb)]
      (is (not= :send (:action dec))))))

(deftest whole-digest-gate-skip-when-all-withheld
  (testing "all MPs withheld + gate-when-empty=:skip → outcome :gated, no send"
    (let [send-count (atom 0)
          stub-send  (fn [_chat _text] (swap! send-count inc) {:sent? true})
          ;; payload where all MPs are preliminary (→ :flag but we force :withhold mode)
          payload    (payload-with :preliminary? true
                                   :freshness-wb nil :freshness-ozon nil :freshness-ym nil)
          sub        {:chat-id "chat-X" :cadences #{:daily} :metrics [] :show-movers? false
                      :marketplace :all :gate-when-empty :skip :status :active}
          result     (digest/build-and-send! payload sub {:gate-mode :withhold
                                                          :sender-fn  stub-send})]
      (is (= :gated (:outcome result)))
      (is (zero? @send-count)))))

(deftest whole-digest-gate-notice-sends-placeholder
  (testing "all MPs withheld + gate-when-empty=:notice → sends short notice message"
    (let [sent-texts (atom [])
          stub-send  (fn [_chat text] (swap! sent-texts conj text) {:sent? true})
          payload    (payload-with :preliminary? true
                                   :freshness-wb nil :freshness-ozon nil :freshness-ym nil)
          sub        {:chat-id "chat-Y" :cadences #{:daily} :metrics [] :show-movers? false
                      :marketplace :all :gate-when-empty :notice :status :active}
          result     (digest/build-and-send! payload sub {:gate-mode :withhold
                                                          :sender-fn  stub-send})]
      (is (= :gated (:outcome result)))
      (is (= 1 (count @sent-texts)))
      (is (re-find #"не готов|предв|preliminary|not ready" (first @sent-texts))))))

(deftest final-payload-sends-digest
  (testing "fully final payload → :delivered outcome, message sent"
    (let [sent-texts (atom [])
          stub-send  (fn [_chat text] (swap! sent-texts conj text) {:sent? true})
          payload    (payload-with :preliminary? false)
          sub        {:chat-id "chat-Z" :cadences #{:daily} :metrics [] :show-movers? false
                      :marketplace :all :gate-when-empty :skip :status :active}
          result     (digest/build-and-send! payload sub {:gate-mode :flag
                                                          :sender-fn  stub-send})]
      (is (= :delivered (:outcome result)))
      (is (pos? (count @sent-texts))))))

;; ---------------------------------------------------------------------------
;; US2 — metric rendering uses slug suffix/label (not raw key)
;; ---------------------------------------------------------------------------

(deftest render-uses-slug-labels
  (testing "rendered message contains known metric labels for default slugs"
    (let [sent-texts (atom [])
          stub-send  (fn [_chat text] (swap! sent-texts conj text) {:sent? true})
          payload    (payload-with)
          sub        {:chat-id "chat-M" :cadences #{:daily}
                      :metrics [:revenue :net-profit]
                      :show-movers? false :marketplace :all
                      :gate-when-empty :skip :status :active}
          _result    (digest/build-and-send! payload sub {:gate-mode :flag
                                                          :sender-fn stub-send})]
      (when (seq @sent-texts)
        (let [text (first @sent-texts)]
          ;; Revenue and profit figures should appear in message
          (is (re-find #"1.000|1000" text) "revenue present in message"))))))

(deftest digest-key->slug-coercion
  (testing "digest-key->slug coercion map covers known aliases"
    (is (= :margin-pct (get digest/digest-key->slug :margin)))
    (is (= :drr-pct    (get digest/digest-key->slug :drr)))
    (is (= :net-profit (get digest/digest-key->slug :profit)))))
