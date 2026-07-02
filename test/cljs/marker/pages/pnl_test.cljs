(ns marker.pages.pnl-test
  "Tests for the P&L page.

   Pre-013: per-SKU derived-column formulas over marker.mock data.
   016-US3: the layered-waterfall pure helpers — group-waterfall re-nesting
   (layer order, expandable children, child exclusion from top level),
   line-pct-of-revenue honesty, and delta-class polarity (cost rising =
   adverse red, profit rising = favourable green, nil = neutral flat)."
  (:require [cljs.test :refer [deftest is testing]]
            [marker.mock :as mock]
            [marker.pages.pnl :as pnl]
            [marker.ui.metric-hint :refer [delta-class]]))

;; ---------------------------------------------------------------------------
;; 016-US3 fixture — a FLAT waterfall vector exactly as the backend
;; (analitica.domain.pnl/waterfall) emits it: expandable parents carry a
;; :children vector of KEYS, and the child lines follow inline. Kopeck
;; invariants hold: Σ direct children = direct-expenses, sales + direct =
;; gross-margin, gross + advertising + opex = EBITDA, EBITDA + tax = net.
;; ---------------------------------------------------------------------------

(def de-children
  [:cogs :mp-commission :logistics :storage :penalties :deduction :acceptance :additional])

(defn make-waterfall
  "Backend-shaped flat waterfall. Options:
     :managed? false → opex/tax/vat lines are 0 and EBITDA folds into
                       gross-margin + advertising (015 seam inert).
     :deltas?  true  → attach :delta/:delta-pct to every line (compare on);
                       the :vat line keeps a nil delta-pct (prev = 0)."
  [& {:keys [managed? deltas?] :or {managed? true}}]
  (let [opex   (if managed? -40.0 0.0)
        usn    (if managed? -30.0 0.0)
        vat    (if managed? -6.0 0.0)
        tax    (+ usn vat)
        gross  480.0
        ebitda (+ gross -80.0 opex)
        net    (+ ebitda tax)
        d      (fn [line] (if deltas?
                            (assoc line :delta 10.0 :delta-pct 5.0)
                            line))]
    [(d {:key :sales :label "Выручка (GROSS)" :amount 1000.0
         :layer :sales :positive-if-grow true})
     (d {:key :direct-expenses :label "Прямые расходы" :amount -520.0
         :layer :direct-expense :positive-if-grow false :children de-children})
     (d {:key :cogs :label "Себестоимость" :amount -300.0
         :layer :direct-expense :positive-if-grow false})
     (d {:key :mp-commission :label "Комиссия МП" :amount -150.0
         :layer :direct-expense :positive-if-grow false})
     (d {:key :logistics :label "Логистика" :amount -50.0
         :layer :direct-expense :positive-if-grow false})
     (d {:key :storage :label "Хранение" :amount -20.0
         :layer :direct-expense :positive-if-grow false})
     (d {:key :penalties :label "Штрафы" :amount -5.0
         :layer :direct-expense :positive-if-grow false})
     (d {:key :deduction :label "Удержания" :amount -5.0
         :layer :direct-expense :positive-if-grow false})
     (d {:key :acceptance :label "Приёмка" :amount -2.0
         :layer :direct-expense :positive-if-grow false})
     (d {:key :additional :label "Доплаты" :amount 12.0
         :layer :direct-expense :positive-if-grow true})
     (d {:key :gross-margin :label "Валовая прибыль" :amount gross
         :layer :gross-margin :positive-if-grow true})
     (d {:key :advertising :label "Реклама" :amount -80.0
         :layer :advertising :positive-if-grow false})
     (d {:key :operating-expenses :label "Операционные расходы" :amount opex
         :layer :operating-expense :positive-if-grow false})
     (d {:key :ebitda :label "Операционная прибыль (EBITDA)" :amount ebitda
         :layer :ebitda :positive-if-grow true})
     (d {:key :tax :label "Налог" :amount tax
         :layer :tax :positive-if-grow false :children [:tax-usn :vat]})
     (d {:key :tax-usn :label "Налог УСН" :amount usn
         :layer :tax :positive-if-grow false})
     ;; prev vat = 0 ⇒ backend sends delta with nil delta-pct (FR-026)
     (cond-> {:key :vat :label "НДС (исх.)" :amount vat
              :layer :tax :positive-if-grow false}
       deltas? (assoc :delta 10.0 :delta-pct nil))
     (d {:key :net-profit :label "Чистая прибыль" :amount net
         :layer :net-profit :positive-if-grow true})]))

;; ---------------------------------------------------------------------------
;; Waterfall layer order (016-US3 test 1)
;; ---------------------------------------------------------------------------

(deftest waterfall-top-level-order
  (testing "grouped top-level rows follow the canonical 8-layer order"
    (let [grouped (pnl/group-waterfall (make-waterfall))]
      (is (= pnl/waterfall-layer-order
             (mapv #(get-in % [:line :key]) grouped)))
      (is (= [:sales :direct-expenses :gross-margin :advertising
              :operating-expenses :ebitda :tax :net-profit]
             (mapv #(get-in % [:line :key]) grouped)))))

  (testing "sales is the GROSS top line, net-profit is last"
    (let [grouped (pnl/group-waterfall (make-waterfall))]
      (is (= "Выручка (GROSS)" (get-in (first grouped) [:line :label])))
      (is (= :net-profit (get-in (last grouped) [:line :key])))))

  (testing "child lines never appear as top-level rows"
    (let [top (into #{} (map #(get-in % [:line :key]))
                    (pnl/group-waterfall (make-waterfall)))]
      (doseq [k (conj (set de-children) :tax-usn :vat)]
        (is (not (contains? top k))
            (str k " must be nested, not top-level")))))

  (testing "nil / empty waterfall → [] (no error, no rows)"
    (is (= [] (pnl/group-waterfall nil)))
    (is (= [] (pnl/group-waterfall [])))))

;; ---------------------------------------------------------------------------
;; Expanding direct-expenses shows its component children (016-US3 test 2)
;; ---------------------------------------------------------------------------

(deftest waterfall-direct-expense-children
  (let [grouped (pnl/group-waterfall (make-waterfall))
        de      (some #(when (= :direct-expenses (get-in % [:line :key])) %) grouped)]
    (testing "direct-expenses is expandable"
      (is (pnl/expandable? (:line de))))

    (testing "children resolve to FULL line maps, in backend order"
      (is (= de-children (mapv :key (:children de))))
      (doseq [c (:children de)]
        (is (some? (:label c)) (str (:key c) " child must carry a label"))
        (is (number? (:amount c)))))

    (testing "children include the «Комиссия МП» component line"
      (let [comm (some #(when (= :mp-commission (:key %)) %) (:children de))]
        (is (some? comm))
        (is (= "Комиссия МП" (:label comm)))
        (is (= -150.0 (:amount comm)))))

    (testing "Σ children == direct-expenses amount (kopeck invariant)"
      (is (= (get-in de [:line :amount])
             (reduce + 0.0 (map :amount (:children de))))))

    (testing "plain layers are NOT expandable, tax IS"
      (let [by-key (into {} (map (fn [g] [(get-in g [:line :key]) g])) grouped)]
        (is (not (pnl/expandable? (:line (by-key :sales)))))
        (is (not (pnl/expandable? (:line (by-key :gross-margin)))))
        (is (pnl/expandable? (:line (by-key :tax))))
        (is (= [:tax-usn :vat] (mapv :key (:children (by-key :tax)))))))))

;; ---------------------------------------------------------------------------
;; Compare mode — deltas survive grouping; nil delta-pct stays nil
;; ---------------------------------------------------------------------------

(deftest waterfall-compare-deltas
  (let [grouped (pnl/group-waterfall (make-waterfall :deltas? true))
        by-key  (into {} (map (fn [g] [(get-in g [:line :key]) g])) grouped)]
    (testing "top-level lines keep their :delta / :delta-pct through grouping"
      (is (= 10.0 (get-in (by-key :sales) [:line :delta])))
      (is (= 5.0  (get-in (by-key :sales) [:line :delta-pct]))))

    (testing "child lines keep their deltas too"
      (let [cogs (some #(when (= :cogs (:key %)) %)
                       (:children (by-key :direct-expenses)))]
        (is (= 10.0 (:delta cogs)))))

    (testing "prev=0 line keeps nil delta-pct (neutral, never ±100%)"
      (let [vat (some #(when (= :vat (:key %)) %)
                      (:children (by-key :tax)))]
        (is (= 10.0 (:delta vat)))
        (is (nil? (:delta-pct vat)))))))

;; ---------------------------------------------------------------------------
;; delta-class polarity (016-US3 test 3)
;; ---------------------------------------------------------------------------

(deftest delta-class-polarity
  (testing "cost-like line (positive-if-grow=false) RISING → adverse red (down)"
    (is (= "down" (delta-class 500 false))))

  (testing "cost-like line FALLING → favourable green (up)"
    (is (= "up" (delta-class -500 false))))

  (testing "profit-like line (positive-if-grow=true) RISING → favourable green (up)"
    (is (= "up" (delta-class 500 true))))

  (testing "profit-like line FALLING → adverse red (down)"
    (is (= "down" (delta-class -500 true))))

  (testing "nil delta → neutral flat for BOTH polarities"
    (is (= "flat" (delta-class nil true)))
    (is (= "flat" (delta-class nil false))))

  (testing "zero / neutral band (|Δ| ≤ 0.05) → flat"
    (is (= "flat" (delta-class 0 true)))
    (is (= "flat" (delta-class 0.05 false)))
    (is (= "flat" (delta-class -0.05 true)))))

;; ---------------------------------------------------------------------------
;; % of revenue honesty
;; ---------------------------------------------------------------------------

(deftest pct-of-revenue-honesty
  (testing "abs share of the GROSS top-line"
    (is (= 52.0 (pnl/line-pct-of-revenue -520.0 1000.0)))
    (is (= 100.0 (pnl/line-pct-of-revenue 1000.0 1000.0))))

  (testing "zero revenue → nil (render «—», never a fake 0%)"
    (is (nil? (pnl/line-pct-of-revenue 100.0 0)))
    (is (nil? (pnl/line-pct-of-revenue 0 0))))

  (testing "non-numeric input → nil"
    (is (nil? (pnl/line-pct-of-revenue nil 1000.0)))
    (is (nil? (pnl/line-pct-of-revenue 100.0 nil)))))

;; ---------------------------------------------------------------------------
;; Management-layer inert (015 seam absent) — still renders, EBITDA folds
;; ---------------------------------------------------------------------------

(deftest waterfall-management-inert
  (let [grouped (pnl/group-waterfall (make-waterfall :managed? false))
        by-key  (into {} (map (fn [g] [(get-in g [:line :key]) g])) grouped)]
    (testing "all 8 layers still present — zero lines are NOT dropped"
      (is (= 8 (count grouped)))
      (is (zero? (get-in (by-key :operating-expenses) [:line :amount])))
      (is (zero? (get-in (by-key :tax) [:line :amount]))))

    (testing "EBITDA == gross-margin + advertising when opex = 0"
      (is (= (get-in (by-key :ebitda) [:line :amount])
             (+ (get-in (by-key :gross-margin) [:line :amount])
                (get-in (by-key :advertising) [:line :amount])))))

    (testing "net-profit == EBITDA when tax = 0"
      (is (= (get-in (by-key :net-profit) [:line :amount])
             (get-in (by-key :ebitda) [:line :amount]))))))

;; ---------------------------------------------------------------------------
;; Pre-013 tests — per-SKU derived columns over mock data (kept as-is)
;; ---------------------------------------------------------------------------

(defn- compute-net [sku]
  (- (* (:revenue sku) (:margin sku))
     (:ads-cost sku)))

(deftest sku-net-formula
  (testing "net = revenue * margin - ads-cost"
    (let [sku (first mock/skus)]
      (is (number? (compute-net sku)))
      (is (= (compute-net sku)
             (- (* (:revenue sku) (:margin sku))
                (:ads-cost sku))))))

  (testing "all SKUs have numeric net"
    (doseq [sku mock/skus]
      (is (number? (compute-net sku))
          (str "Expected numeric net for " (:id sku)))))

  (testing "SKU-1200 (index 0) net is reasonable"
    (let [sku (first mock/skus)
          net (compute-net sku)]
      ;; margin is always in [0.12, 0.44], revenue >= 40000,
      ;; ads-cost = revenue * [0.06, 0.24], so net can be positive or negative
      ;; but must be finite
      (is (js/isFinite net))
      (is (not (js/isNaN net)))))

  (testing "DRR = ads-cost / revenue"
    (let [sku (first mock/skus)
          drr (* (/ (:ads-cost sku) (:revenue sku)) 100)]
      (is (> drr 0))
      (is (< drr 100)))))

(deftest pnl-rows-shape
  (testing "pnl-rows has 11 entries"
    (is (= 11 (count mock/pnl-rows))))

  (testing "first row is revenue (positive)"
    (let [r (first mock/pnl-rows)]
      (is (= "revenue" (:key r)))
      (is (pos? (:cur r)))))

  (testing "last row is net profit (total group)"
    (let [r (last mock/pnl-rows)]
      (is (= "net" (:key r)))
      (is (= "total" (:group r)))))

  (testing "all rows have required keys"
    (doseq [r mock/pnl-rows]
      (is (contains? r :key))
      (is (contains? r :label))
      (is (contains? r :cur))
      (is (contains? r :prev))
      (is (contains? r :group))))

  (testing "delta pct calculation for revenue row"
    (let [r (first mock/pnl-rows)
          d-abs (- (:cur r) (:prev r))
          d-pct (* (/ d-abs (js/Math.abs (:prev r))) 100)]
      (is (pos? d-pct) "Revenue grew vs prev period")
      ;; 8420000 - 7510000 = 910000; 910000/7510000*100 ≈ 12.1%
      (is (< 10 d-pct 15)))))
