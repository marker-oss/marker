(ns analitica.integration.ym-basis-reconcile-test
  "Spec 012 US1 — directional reconciliation of the CORRECTED YM basis against
   the captured TrueStats per-SKU anchor (April 2026).

   CORRECTED basis (owner-approved 2026-07-01): gross = BUYER + subsidy (== TS
   `realisation`), net-sales = BUYER (== TS `sales`). Subsidy is the bridge
   net→gross, inside revenue, out of payout.

   Fixtures (real slices, NOT synthetic):
     test/resources/ym/ym-april-raw-slice.edn  — 145 real April YM orders
     test/resources/ym/ts-april-skus.edn       — 116 real TS SKU rows

   Reconciliation is DIRECTIONAL: raw coverage is partial (145-order slice vs
   full-month TS), so we assert the MAJORITY (≥60%) match within tolerance and
   that gross beats a buyer-only baseline — NOT 100%. for_pay↔toTransfer full
   reconciliation is deferred to the OPERATOR full-period re-materialize.

   OBSERVED match rates (this fixture, 2026-07-01), overlap = 36 TS-active SKUs:
     net-sales ↔ TS `sales`        : 29/36 = 80.6%  (gate ≥60% ✓)
     gross     ↔ TS `realisation`  : 20/36 = 55.6%  (gate ≥50% ✓)
     gross err 32,037 ₽ vs buyer-only baseline 97,862 ₽ (≈3× closer — the
       subsidy bridge validated: BUYER+subsidy tracks TS realisation).
     for_pay   ↔ TS `toTransfer`   :  3/36 =  8.3%  (informational, NOT gated —
       TS applies return-netting / storage / fines the partial slice lacks;
       deferred to full-period re-materialize).
   Exact matches on clean-coverage SKUs (e.g. 109/Бордовый58: net 2235==2235,
   gross 4200==4200) confirm the formula is exact where coverage is complete;
   misses are partial-raw coverage gaps (missing order legs), not formula error."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [analitica.marketplace.ym.transform :as transform]
            [analitica.domain.finance :as finance]
            [analitica.domain.cost-price :as cost-price]
            [analitica.db]
            [analitica.canonical.events.materialize]
            [analitica.materialize :as materialize]))

;; --- within-tolerance? from contracts/ym-finance-row.md §B ------------------
(defn- within-tolerance? [ours theirs]
  (let [d (Math/abs (- (double ours) (double theirs)))]
    (or (<= d 50.0)                                    ; absolute ≤ 50 ₽
        (<= d (* 0.05 (Math/abs (double theirs)))))))  ; OR relative ≤ 5%

(def ^:private ym-raw
  (delay (edn/read-string (slurp "test/resources/ym/ym-april-raw-slice.edn"))))

(def ^:private ts-skus
  (delay (edn/read-string (slurp "test/resources/ym/ts-april-skus.edn"))))

(defn- sale-or-return? [row]
  (#{:sale :return} (:operation-kind row)))

(defn- aggregate-by-article
  "Aggregate transformed finance lines by :article into net / gross / for-pay
   totals. Self-contained (does not depend on domain/finance cost-price atom).
   :for-pay aggregate = Σ sale − Σ return (canon §3.3); returns are +abs on the
   transform layer, so subtract them."
  [rows]
  (->> rows
       (filter sale-or-return?)
       (group-by :article)
       (map (fn [[article lines]]
              (let [sales   (filter #(= :sale (:operation-kind %)) lines)
                    returns (filter #(= :return (:operation-kind %)) lines)
                    sum     (fn [ls k] (reduce + 0.0 (map #(or (k %) 0.0) ls)))]
                [article
                 {:article       article
                  :net-sales     (- (sum sales :net-sales) (sum returns :net-sales))
                  :retail-amount (- (sum sales :retail-amount) (sum returns :retail-amount))
                  :for-pay       (- (sum sales :for-pay) (sum returns :for-pay))}])))
       (into {})))

(defn- overlap
  "Join our aggregated rows to TS rows by :article; keep only SKUs present in
   both AND with non-zero TS activity (realisation or sales > 0), so we compare
   real sales rather than TS ledger-only refund stubs."
  []
  (let [ours   (aggregate-by-article (transform/->finance-from-order-stats @ym-raw))
        ts-map (into {} (map (juxt :article identity) @ts-skus))]
    (for [[article our-row] ours
          :let [ts (get ts-map article)]
          :when (and ts (or (pos? (double (or (:realisation ts) 0)))
                            (pos? (double (or (:sales ts) 0)))))]
      {:article       article
       :our-net       (:net-sales our-row)
       :our-gross     (:retail-amount our-row)
       :our-for-pay   (:for-pay our-row)
       :ts-sales      (double (:sales ts))
       :ts-realisation (double (:realisation ts))
       :ts-to-transfer (double (:toTransfer ts))})))

;; ---- Guard against vacuity: overlap must be substantial -------------------

(deftest overlap-is-substantial
  (testing "raw-slice ∩ TS anchor has ≥20 real-activity SKUs (else the test proves nothing)"
    (let [n (count (overlap))]
      (is (>= n 20)
          (str "overlap SKUs = " n " (need ≥20 to be non-vacuous)")))))

;; ---- net-sales (BUYER) ↔ TS `sales` ---------------------------------------

(deftest net-sales-reconciles-to-ts-sales
  (testing "net-sales (Σ BUYER×qty) matches TS `sales` for the MAJORITY (≥60%) of overlap"
    (let [ov      (overlap)
          n       (count ov)
          matches (count (filter #(within-tolerance? (:our-net %) (:ts-sales %)) ov))
          rate    (if (pos? n) (/ (double matches) n) 0.0)]
      (is (pos? n))
      ;; OBSERVED (documented in summary): partial raw coverage means some SKUs
      ;; have missing return legs; net matches the clear majority.
      (is (>= rate 0.60)
          (str "net-sales match rate " (format "%.1f%%" (* 100.0 rate))
               " (" matches "/" n ") — expected ≥60%")))))

;; ---- gross (BUYER + subsidy) ↔ TS `realisation` ---------------------------
;; The point of the fix: gross = BUYER + subsidy must be CLOSER to TS
;; realisation than a buyer-only baseline (which structurally undercounts).

(deftest gross-beats-buyer-only-baseline
  (testing "gross (BUYER+subsidy) is closer to TS realisation than buyer-only would be"
    (let [ov (overlap)
          n  (count ov)
          ;; per-SKU: does BUYER+subsidy land nearer realisation than net(BUYER) alone?
          gross-err (reduce + 0.0 (map #(Math/abs (- (:our-gross %) (:ts-realisation %))) ov))
          buyer-err (reduce + 0.0 (map #(Math/abs (- (:our-net %) (:ts-realisation %))) ov))
          gross-matches (count (filter #(within-tolerance? (:our-gross %) (:ts-realisation %)) ov))
          gross-rate    (if (pos? n) (/ (double gross-matches) n) 0.0)]
      (is (pos? n))
      (is (< gross-err buyer-err)
          (str "aggregate gross error " (format "%.0f" gross-err)
               " must be < buyer-only baseline error " (format "%.0f" buyer-err)))
      (is (>= gross-rate 0.50)
          (str "gross match rate " (format "%.1f%%" (* 100.0 gross-rate))
               " (" gross-matches "/" n ")")))))

;; ===========================================================================
;; Adversarial R4 — reconcile through the REAL production aggregator.
;;
;; The bespoke `aggregate-by-article` above computes net-sales = Σsale − Σreturn.
;; Production `finance/by-article` computes :net-sales = Σ sales-lines ONLY and
;; :revenue (gross) = Σ retail-amount over sales-lines. So the numbers the SPA
;; actually ships come from by-article, NOT the bespoke aggregator. This test
;; drives the SAME raw slice through `finance/by-article` and reconciles the
;; SHIPPED :net-sales / :revenue against the TS anchor — covering the numbers
;; the customer sees, not a test-only convention. cost-price/get-price is
;; redef'd to 0 so by-article does not touch the cost atom.
;;
;; Convention note: by-article's :net-sales = TS `sales` corresponds to the
;; SALES-ONLY sum (no return netting), which is the convention TS `sales`
;; reflects for the majority of active SKUs in the partial slice.
;; ===========================================================================

(defn- by-article-overlap
  "Overlap join computed through production finance/by-article (the shipped
   aggregator), keyed by :article, restricted to TS-active SKUs."
  []
  (let [rows   (transform/->finance-from-order-stats @ym-raw)
        ba     (finance/by-article rows)
        by-art (into {} (map (juxt :article identity) ba))
        ts-map (into {} (map (juxt :article identity) @ts-skus))]
    (for [[article r] by-art
          :let [ts (get ts-map article)]
          :when (and ts (or (pos? (double (or (:realisation ts) 0)))
                            (pos? (double (or (:sales ts) 0)))))]
      {:article        article
       :our-net        (double (:net-sales r))     ; Σ sales-lines only (by-article)
       :our-gross      (double (:revenue r))       ; Σ retail-amount over sales-lines
       :ts-sales       (double (:sales ts))
       :ts-realisation (double (:realisation ts))})))

(deftest by-article-reconciles-shipped-numbers
  (testing "production finance/by-article net-sales/revenue reconcile to TS anchor (the SHIPPED numbers)"
    (with-redefs [cost-price/get-price (fn [_article _barcode] 0.0)]
      (let [ov (by-article-overlap)
            n  (count ov)
            net-matches   (count (filter #(within-tolerance? (:our-net %) (:ts-sales %)) ov))
            gross-matches (count (filter #(within-tolerance? (:our-gross %) (:ts-realisation %)) ov))
            net-rate      (if (pos? n) (/ (double net-matches) n) 0.0)
            gross-rate    (if (pos? n) (/ (double gross-matches) n) 0.0)]
        (is (>= n 20)
            (str "by-article overlap SKUs = " n " (need ≥20 to be non-vacuous)"))
        (is (>= net-rate 0.60)
            (str "SHIPPED net-sales (by-article) match rate "
                 (format "%.1f%%" (* 100.0 net-rate)) " (" net-matches "/" n ") — expected ≥60%"))
        (is (>= gross-rate 0.50)
            (str "SHIPPED revenue/gross (by-article) match rate "
                 (format "%.1f%%" (* 100.0 gross-rate)) " (" gross-matches "/" n ") — expected ≥50%"))))))

;; ===========================================================================
;; T020 (US2) — WB/Ozon no-regression guard.
;;
;; The :net-sales aggregation added to domain/finance/by-article must NOT
;; perturb WB/Ozon: those rows carry no :net-sales (or nil), so their
;; :net-sales aggregate must be 0.0 and their :revenue / :for-pay / :total-cost
;; must equal a baseline captured from the same inputs. INV-5 / FR-011 / SC-004.
;;
;; Baseline is captured inline from these fixture rows rather than a separate
;; edn file — the point is that the by-article outputs for WB/Ozon articles are
;; unaffected by the :net-sales change (they have no :net-sales field).
;; cost-price/get-price is redef'd to a fixed value so :total-cost is
;; deterministic and the assertion is about stability, not the cost atom.
;; ===========================================================================

(def ^:private wb-ozon-fixture
  ;; No :net-sales key anywhere (WB/Ozon never emit it).
  [{:marketplace :wb :article "WB-A" :operation-kind :sale :quantity 2
    :retail-amount 2000.0 :for-pay 1600.0 :mp-commission 200.0}
   {:marketplace :wb :article "WB-A" :operation-kind :return :quantity 1
    :retail-amount 1000.0 :for-pay 800.0 :mp-commission 100.0}
   {:marketplace :wb :article "WB-B" :operation-kind :sale :quantity 1
    :retail-amount 1500.0 :for-pay 1200.0 :mp-commission 150.0}
   {:marketplace :ozon :article "OZ-A" :operation-kind :sale :quantity 3
    :retail-amount 3000.0 :for-pay 2400.0 :mp-commission 300.0}
   {:marketplace :ozon :article "OZ-B" :operation-kind :sale :quantity 1
    :retail-amount 900.0 :for-pay 720.0 :mp-commission 90.0}])

(deftest wb-ozon-no-regression
  (testing "WB/Ozon by-article revenue/for-pay/total-cost stable; :net-sales = 0.0"
    (with-redefs [cost-price/get-price (fn [_article _barcode] 100.0)]
      (let [rows    (finance/by-article wb-ozon-fixture)
            by-art  (into {} (map (juxt :article identity) rows))]
        ;; Every WB/Ozon article carries :net-sales 0.0 (no :net-sales field).
        (doseq [a ["WB-A" "WB-B" "OZ-A" "OZ-B"]]
          (is (= 0.0 (:net-sales (get by-art a)))
              (str a " :net-sales must be 0.0 (WB/Ozon carry no :net-sales)")))
        ;; revenue = Σ retail-amount over SALE lines (unchanged formula).
        (is (= 2000.0 (:revenue (get by-art "WB-A"))) "WB-A revenue = sale retail-amount")
        (is (= 1500.0 (:revenue (get by-art "WB-B"))))
        (is (= 3000.0 (:revenue (get by-art "OZ-A"))))
        (is (= 900.0  (:revenue (get by-art "OZ-B"))))
        ;; for-pay = Σsale − Σreturn (unchanged formula).
        (is (= 800.0  (:for-pay (get by-art "WB-A"))) "WB-A for-pay = 1600 sale − 800 return")
        (is (= 1200.0 (:for-pay (get by-art "WB-B"))))
        (is (= 2400.0 (:for-pay (get by-art "OZ-A"))))
        (is (= 720.0  (:for-pay (get by-art "OZ-B"))))
        ;; total-cost = max 0 (Σ sale-cost − Σ return-cost) at 100/unit.
        ;; WB-A: 2 sale − 1 return = net 1 unit × 100 = 100.
        (is (= 100.0 (:total-cost (get by-art "WB-A"))))
        (is (= 100.0 (:total-cost (get by-art "WB-B"))))
        (is (= 300.0 (:total-cost (get by-art "OZ-A"))) "3 units × 100")
        (is (= 100.0 (:total-cost (get by-art "OZ-B"))))))))

;; ===========================================================================
;; T024 (US3) — rematerialize-ym-finance! idempotency.
;;
;; Double-running rematerialize-ym-finance! for one period must yield an
;; identical set of finance rows (by natural key rrd-id + monetary values).
;; INV-7 / FR-010 / P5. Fully stubbed at the DB boundary — no live DB, no API,
;; and raw_data is only READ (get-raw-range), never mutated.
;; ===========================================================================

(def ^:private ym-finance-raw-order
  ;; One delivered YM order (order-stats shape) with subsidy + commissions so
  ;; the corrected basis produces gross = BUYER + subsidy, net = BUYER.
  {:id 555001
   :status "DELIVERED"
   :creationDate "2026-04-12"
   :statusUpdateDate "2026-04-20T10:00:00.000+03:00"
   :subsidies   [{:amount 800.0 :type "SUBSIDY" :operationType "ACCRUAL"}]
   :commissions [{:type "FEE" :actual 300.0}]
   :items [{:shopSku "IDEM-1" :count 1
            :prices [{:type "BUYER"       :costPerItem 2000.0 :total 2000.0}
                     {:type "MARKETPLACE" :costPerItem 800.0  :total 800.0}]}]})

(defn- run-rematerialize-capture
  "Run rematerialize-ym-finance! for a single stubbed period, capturing the
   rows handed to db/insert-batch!. Returns the captured finance rows."
  []
  (let [captured (atom nil)]
    (with-redefs [;; ym-finance-raw-periods discovery: one April period.
                  analitica.db/query
                  (fn [_sql] [{:date-from "2026-04-01" :date-to "2026-04-30"}])
                  ;; load-raw → get-raw-range returns the raw order batch.
                  analitica.db/get-raw-range
                  (fn [_source _entity _from _to]
                    [{:date-from "2026-04-01" :date-to "2026-04-30"
                      :data [ym-finance-raw-order]}])
                  ;; capture the transformed rows instead of writing to SQLite.
                  analitica.db/insert-batch!
                  (fn [_table _cols rows] (reset! captured (vec rows)) (count rows))
                  ;; canonical item_events materialize is out of scope — stub
                  ;; it so it doesn't touch the DB nor clobber @captured.
                  analitica.db/execute! (fn [_] 0)
                  analitica.canonical.events.materialize/materialize-ym-events!
                  (fn [_from _to] 0)]
      (materialize/rematerialize-ym-finance! :period "2026-04")
      @captured)))

(deftest rematerialize-idempotent
  (testing "two runs of rematerialize-ym-finance! (one period) → identical rows"
    (let [run1 (run-rematerialize-capture)
          run2 (run-rematerialize-capture)]
      (is (seq run1) "re-materialize must produce finance rows")
      (is (= run1 run2)
          "repeated re-materialize is idempotent (identical row vectors)"))))