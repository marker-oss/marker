(ns marker.pages.products.stocks-test
  "Pure helpers for the «Склады» tab (Phase 2 of the SPA UI restructure),
   extended by 016-US2: capitalization / GMROI / days-of-cover display."
  (:require [clojure.string :as str]
            [cljs.test :refer [deftest is testing]]
            [marker.pages.products.stocks :as stocks]))

(def NBSP " ")

;; ---------------------------------------------------------------------------
;; days->status thresholds (mirrors backend classification)
;; ---------------------------------------------------------------------------

(deftest days-status-thresholds
  (testing "nil → ok"
    (is (= "ok" (stocks/days->status nil))))
  (testing "below 7 → danger"
    (is (= "danger" (stocks/days->status 0)))
    (is (= "danger" (stocks/days->status 3)))
    (is (= "danger" (stocks/days->status 6))))
  (testing "7-13 → warning"
    (is (= "warning" (stocks/days->status 7)))
    (is (= "warning" (stocks/days->status 13))))
  (testing "14+ → success"
    (is (= "success" (stocks/days->status 14)))
    (is (= "success" (stocks/days->status 30)))
    (is (= "success" (stocks/days->status 999)))))

(deftest status-badge-class-mapping
  (testing "each status maps to its tokens.css badge modifier"
    (is (= "badge-danger"  (stocks/status->badge-class "danger")))
    (is (= "badge-warning" (stocks/status->badge-class "warning")))
    (is (= "badge-success" (stocks/status->badge-class "success"))))
  (testing "unknown coverage («ok») → neutral badge, NOT success"
    (is (= "badge-neutral" (stocks/status->badge-class "ok")))
    (is (= "badge-neutral" (stocks/status->badge-class "whatever")))))

;; ---------------------------------------------------------------------------
;; row-days — 016-US2 key with legacy fallback
;; ---------------------------------------------------------------------------

(deftest row-days-prefers-new-key
  (testing ":days-of-cover wins over legacy :days"
    (is (= 5 (stocks/row-days {:days-of-cover 5 :days 99}))))
  (testing "legacy :days used when new key absent"
    (is (= 99 (stocks/row-days {:days 99}))))
  (testing "zero coverage is a real value, not «missing»"
    (is (= 0 (stocks/row-days {:days-of-cover 0 :days 42}))))
  (testing "neither key → nil"
    (is (nil? (stocks/row-days {})))))

;; ---------------------------------------------------------------------------
;; row-display — capitalization / GMROI / days-of-cover cells
;; ---------------------------------------------------------------------------

(deftest na-cost-sku-renders-dash-not-zero
  (testing "SKU without cost basis: cap/GMROI cells are «—», never 0"
    (let [disp (stocks/row-display {:article "SKU-NA" :quantity-full 10})]
      (is (= "—" (:cap-by-cost disp)))
      (is (= "—" (:cap-by-price disp)))
      (is (= "—" (:gmroi disp)))
      (is (= "—" (:days-label disp)))
      (is (not (str/includes? (:cap-by-cost disp) "0")))
      (is (not (str/includes? (:gmroi disp) "0")))))
  (testing "a real zero still renders as an explicit 0 (only nil is N/A)"
    (let [disp (stocks/row-display {:cap-by-cost 0})]
      (is (= (str "0" NBSP "₽") (:cap-by-cost disp))))))

(deftest capitalization-cells-format-as-rubles
  (let [disp (stocks/row-display {:cap-by-cost  1234567
                                  :cap-by-price 2500000})]
    (testing "₽ with NBSP thousands grouping"
      (is (= (str "1" NBSP "234" NBSP "567" NBSP "₽") (:cap-by-cost disp)))
      (is (= (str "2" NBSP "500" NBSP "000" NBSP "₽") (:cap-by-price disp))))))

(deftest gmroi-uses-multiplier-suffix
  (testing "GMROI renders with × suffix and comma decimal"
    (is (= "1,5×" (:gmroi (stocks/row-display {:gmroi 1.53}))))
    (is (= "2,0×" (:gmroi (stocks/row-display {:gmroi 2}))))
    (is (= "0,4×" (:gmroi (stocks/row-display {:gmroi 0.42}))))))

(deftest days-badge-colour-thresholds
  (testing "danger < 7 дн."
    (is (= "badge badge-danger" (:badge-class (stocks/row-display {:days-of-cover 3}))))
    (is (= "badge badge-danger" (:badge-class (stocks/row-display {:days-of-cover 6})))))
  (testing "warning < 14 дн."
    (is (= "badge badge-warning" (:badge-class (stocks/row-display {:days-of-cover 7}))))
    (is (= "badge badge-warning" (:badge-class (stocks/row-display {:days-of-cover 13})))))
  (testing "success ≥ 14 дн."
    (is (= "badge badge-success" (:badge-class (stocks/row-display {:days-of-cover 14}))))
    (is (= "badge badge-success" (:badge-class (stocks/row-display {:days-of-cover 60})))))
  (testing "unknown coverage → neutral badge with «—» label"
    (let [disp (stocks/row-display {})]
      (is (= "badge badge-neutral" (:badge-class disp)))
      (is (= "—" (:days-label disp)))))
  (testing "known coverage renders «N Дн.» label"
    (is (= (str "12" NBSP "Дн.")
           (:days-label (stocks/row-display {:days-of-cover 12}))))))

;; ---------------------------------------------------------------------------
;; totals-display — Σ row + na-cost-count note
;; ---------------------------------------------------------------------------

(deftest totals-row-display
  (let [t (stocks/totals-display {:quantity           100
                                  :stock-qty-total    140
                                  :cap-by-cost-total  50000
                                  :cap-by-price-total 90000
                                  :na-cost-count      3})]
    (testing "available qty comes from :quantity, full qty from :stock-qty-total"
      (is (= "100" (:qty t)))
      (is (= "140" (:qty-full t))))
    (testing "cap totals format as rubles"
      (is (= (str "50" NBSP "000" NBSP "₽") (:cap-cost t)))
      (is (= (str "90" NBSP "000" NBSP "₽") (:cap-price t))))
    (testing "na-cost-count note present when some SKUs lack cost basis"
      (is (= "без себест.: 3 арт." (:na-note t))))))

(deftest totals-row-na-note-absent-when-complete
  (testing "na-cost-count 0 → no note"
    (is (nil? (:na-note (stocks/totals-display {:na-cost-count 0})))))
  (testing "na-cost-count absent → no note"
    (is (nil? (:na-note (stocks/totals-display {}))))))

(deftest totals-row-legacy-fallbacks
  (testing "payload without 016-US2 totals falls back to :quantity-full"
    (is (= "77" (:qty-full (stocks/totals-display {:quantity-full 77})))))
  (testing "missing cap totals render «—», not 0"
    (let [t (stocks/totals-display {:quantity 5})]
      (is (= "—" (:cap-cost t)))
      (is (= "—" (:cap-price t))))))

;; ---------------------------------------------------------------------------
;; warehouse sorting (pre-016 behaviour, kept)
;; ---------------------------------------------------------------------------

(deftest sort-warehouses-by-quantity-full-desc
  (testing "rows sorted by quantity-full descending"
    (let [rows [{:warehouse "A" :quantity-full 5}
                {:warehouse "B" :quantity-full 50}
                {:warehouse "C" :quantity-full 20}]]
      (is (= ["B" "C" "A"]
             (mapv :warehouse (stocks/sort-warehouses rows))))))
  (testing "missing :quantity-full treated as 0"
    (let [rows [{:warehouse "A"}
                {:warehouse "B" :quantity-full 10}
                {:warehouse "C"}]]
      (is (= "B" (-> (stocks/sort-warehouses rows) first :warehouse))))))
