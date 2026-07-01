(ns analitica.marketplace.ozon.performance-test
  "US1 unit tests for the Ozon Performance transform + client (spec 011).

   T012 — :api per-SKU attribution.
   T013 — :spread revenue-weighted, Σ per-article == campaign total EXACTLY.
   T014 — cash≠bonus split (only :spend flows to finance.ad_cost).
   T016 — read-only posture (client only touches GET/stat + POST token/statistics)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [analitica.marketplace.ozon.performance.transform :as pt]
            [analitica.marketplace.ozon.performance.client :as pc]
            [analitica.schema.normalized.ad-spend :as ad-spend]))

(def ^:private synced-at "2026-05-01T00:00:00")

;; ---------------------------------------------------------------------------
;; T012 — :api per-SKU attribution
;; ---------------------------------------------------------------------------

(deftest api-attribution-per-sku
  (testing "a daily row carrying :sku maps to an :api ad_spend row via ozon_sku_map"
    (let [rows        [{:date "2026-04-10" :id "78901" :sku "12345678"
                        :moneySpent 410.00 :bonusSpent 0.0}]
          sku->article {"12345678" "ABC-123"}
          {:keys [api-rows]} (pt/attribute-daily-rows rows sku->article
                                                       {:synced-at synced-at
                                                        :campaign-types {"78901" "SKU"}})
          row (first api-rows)]
      (is (= 1 (count api-rows)))
      (is (= :ozon (:marketplace row)))
      (is (= :api (:attribution-source row)))
      (is (= "ABC-123" (:article row)) "sku resolved to article via ozon_sku_map")
      (is (= "12345678" (:sku row)))
      (is (= "2026-04-10" (:event-date row)) "day-grain event-date straight from source")
      (is (= "78901" (:campaign-id row)))
      (is (= 410.00 (:spend row)) ":spend == moneySpent")
      (is (= 0.0 (:bonus-spend row)))
      (is (string? (:basis row)) ":basis doc-string present (P6)")
      (is (ad-spend/valid-ad-spend? row) "conforms to shared §3.B canon schema"))))

;; ---------------------------------------------------------------------------
;; T013 — :spread revenue-weighted, Σ per-article == campaign total EXACTLY
;; ---------------------------------------------------------------------------

(deftest spread-revenue-weighted-exact
  (testing "campaign-level spend spreads over articles proportional to revenue,
            attribution-source :spread, Σ per-article == campaign total EXACTLY"
    (let [;; Campaign 78902 (banner): two days, total moneySpent = 1034.56.
          rows            [{:date "2026-04-10" :id "78902" :moneySpent 500.00 :bonusSpent 60.00}
                           {:date "2026-04-20" :id "78902" :moneySpent 534.56 :bonusSpent 40.00}]
          ;; Article revenue weights: 60% / 40% split — chosen so the
          ;; distribution has a rounding residue to route to largest-weight.
          article->revenue {"ART-A" 6000.0 "ART-B" 4000.0}
          spread-rows     (pt/spread-campaign-spend "78902" "BANNER" rows
                                                    article->revenue
                                                    {:synced-at synced-at})
          total-spend     (reduce + 0.0 (map :spend spread-rows))
          total-bonus     (reduce + 0.0 (map :bonus-spend spread-rows))]
      (is (seq spread-rows))
      (is (every? #(= :spread (:attribution-source %)) spread-rows))
      (is (every? #(= :ozon (:marketplace %)) spread-rows))
      (is (every? #(string? (:basis %)) spread-rows))
      (is (every? ad-spend/valid-ad-spend? spread-rows))
      ;; EXACT kopeck reconciliation: Σ spend == campaign total (1034.56).
      (is (= 103456 (Math/round (* total-spend 100.0)))
          "residue routed so Σ per-article == campaign total (1034.56) to the kopeck")
      (is (= 10000 (Math/round (* total-bonus 100.0)))
          "Σ bonus-spend == 100.00 exactly (60 + 40)")
      ;; Article ABC gets the larger revenue share.
      (let [by-article (group-by :article spread-rows)
            a-total    (reduce + 0.0 (map :spend (get by-article "ART-A")))
            b-total    (reduce + 0.0 (map :spend (get by-article "ART-B")))]
        (is (> a-total b-total) "larger-revenue article gets larger spend"))))

  (testing "zero-revenue period → flat-spread across days, total not lost"
    (let [rows        [{:date "2026-04-10" :id "78902" :moneySpent 500.00 :bonusSpent 0.0}
                       {:date "2026-04-20" :id "78902" :moneySpent 534.56 :bonusSpent 0.0}]
          ;; No article has revenue in the period.
          spread-rows (pt/spread-campaign-spend "78902" "BANNER" rows {} {:synced-at synced-at})
          total-spend (reduce + 0.0 (map :spend spread-rows))]
      (is (seq spread-rows) "flat fallback still emits rows")
      (is (= 103456 (Math/round (* total-spend 100.0)))
          "total preserved under flat fallback")
      (is (every? #(nil? (:article %)) spread-rows)
          "no revenue → account-level rows carry nil article"))))

;; ---------------------------------------------------------------------------
;; T014 — cash ≠ bonus split
;; ---------------------------------------------------------------------------

(deftest cash-not-bonus-split
  (testing "moneySpent=1234.56 bonusSpent=100.00 → spend=1234.56, bonus-spend=100.00;
            only :spend (cash) is the ad_cost contribution"
    (let [rows        [{:date "2026-04-10" :id "78901" :sku "12345678"
                        :moneySpent 1234.56 :bonusSpent 100.00}]
          sku->article {"12345678" "ABC-123"}
          {:keys [api-rows]} (pt/attribute-daily-rows rows sku->article
                                                       {:synced-at synced-at})
          row (first api-rows)]
      (is (= 1234.56 (:spend row)))
      (is (= 100.00 (:bonus-spend row)))
      ;; The finance.ad_cost contribution helper uses ONLY :spend, never bonus.
      (is (= 1234.56 (pt/ad-cost-contribution row))
          "ad_cost contribution == cash spend only (bonus excluded → no double-count)"))))

;; ---------------------------------------------------------------------------
;; T016 — read-only posture
;; ---------------------------------------------------------------------------

(deftest read-only-posture
  (testing "OzonPerfClient reaches ONLY read/stat + auth endpoints — never a
            campaign-management/write URL (FR-010, P2)"
    (let [urls (pc/reachable-urls)]
      (is (seq urls))
      ;; Every declared URL is one of: token (POST auth), campaign (GET),
      ;; statistics/daily (GET), statistics create (POST report), status/download.
      (is (every? (fn [{:keys [url method]}]
                    (and (str/starts-with? url "https://api-performance.ozon.ru")
                         ;; No write verbs beyond POST-token and POST-statistics
                         ;; (create-report is a read-side async report request).
                         (contains? #{:get :post} method)))
                  urls))
      ;; Explicitly assert no campaign-management / write path is reachable.
      (is (not-any? (fn [{:keys [url]}]
                      (or (str/includes? url "/campaign/")   ;; edit/activate/deactivate
                          (str/includes? url "update")
                          (str/includes? url "activate")
                          (str/includes? url "deactivate")
                          (str/includes? url "delete")))
                    urls)
          "no campaign-management/write endpoint is reachable"))))
