(ns marker.pages.settings-test
  (:require [cljs.test :refer [deftest is testing]]
            [marker.pages.settings :as settings]))

;; ---------------------------------------------------------------------------
;; MP credentials (pre-013)
;; ---------------------------------------------------------------------------

(deftest current-masked-reads-value
  (let [data {"mp.wb.api-token" {:value "••••1234" :secret? true}}]
    (is (= "••••1234" (settings/current-masked data "mp.wb.api-token")))
    (is (nil? (settings/current-masked data "mp.ozon.api-key")))))

(deftest mp-specs-cover-three-marketplaces
  (is (= #{:wb :ozon :ym} (set (map :mp settings/mp-specs)))))

(deftest wb-first-field-setting-key
  (let [wb-spec  (first (filter #(= :wb (:mp %)) settings/mp-specs))
        first-fk (:setting-key (first (:fields wb-spec)))]
    (is (= "mp.wb.api-token" first-fk))))

;; ---------------------------------------------------------------------------
;; Shared helpers
;; ---------------------------------------------------------------------------

(deftest parse-num-handles-ru-input
  (testing "comma decimal, thousands spaces, passthrough, garbage"
    (is (= 6 (settings/parse-num "6")))
    (is (= 6.5 (settings/parse-num "6,5")))
    (is (= 5000 (settings/parse-num "5 000")))
    (is (= 7 (settings/parse-num 7)))
    (is (nil? (settings/parse-num "")))
    (is (nil? (settings/parse-num "abc")))
    (is (nil? (settings/parse-num nil)))))

(deftest api-error-for-matches-url-prefix
  (let [errors {"/api/v1/opex?period=2026-06" {:message "boom" :status 500}
                "/api/v1/settings/tax?year=2026" {:message "tax-fail" :status 422}}]
    (is (= "boom" (settings/api-error-for errors "/api/v1/opex")))
    (is (= "tax-fail" (settings/api-error-for errors "/api/v1/settings/tax")))
    (is (nil? (settings/api-error-for errors "/api/v1/bot")))))

(deftest mp-name-label-covers-all-and-nil
  (is (= "WB" (settings/mp-name-label :wb)))
  (is (= "Ozon" (settings/mp-name-label "ozon")))
  (is (= "ЯМ" (settings/mp-name-label :ym)))
  (is (= "—" (settings/mp-name-label nil))))

;; ---------------------------------------------------------------------------
;; 015: tax config
;; ---------------------------------------------------------------------------

(deftest fraction->pct-rounds-cleanly
  (is (= 6 (settings/fraction->pct 0.06)))     ; not 6.000000000000001
  (is (= 20 (settings/fraction->pct 0.2)))
  (is (= 6.5 (settings/fraction->pct 0.065)))
  (is (= 0 (settings/fraction->pct nil))))

(deftest tax-month-roundtrip
  (let [server-row {:month 3 :taxation-type :usn-income
                    :usn-rate 0.06 :vat-rate 0.05 :official-cost-price true}
        form       (settings/tax-month->form server-row)]
    (testing "server fractions → editable percent strings"
      (is (= "6" (:usn-rate-pct form)))
      (is (= "5" (:vat-rate-pct form)))
      (is (= :usn-income (:taxation-type form)))
      (is (true? (:official-cost-price form))))
    (testing "form → PUT payload keeps pct keys, never fraction keys"
      (let [payload (settings/tax-form->payload 2026 [form])
            m       (first (:months payload))]
        (is (= 2026 (:year payload)))
        (is (= 6 (:usn-rate-pct m)))
        (is (= 5 (:vat-rate-pct m)))
        (is (not (contains? m :usn-rate)))   ; fraction key would win server-side
        (is (not (contains? m :vat-rate)))))))

(deftest tax-form->payload-defaults
  (let [m (first (:months (settings/tax-form->payload
                           2026
                           [{:month 1 :taxation-type nil
                             :usn-rate-pct "" :vat-rate-pct "abc"
                             :official-cost-price nil}])))]
    (is (= :none (:taxation-type m)))
    (is (= 0 (:usn-rate-pct m)))
    (is (= 0 (:vat-rate-pct m)))
    (is (false? (:official-cost-price m)))))

(deftest tax-month->form-nil-rates
  (let [form (settings/tax-month->form {:month 1})]
    (is (= "0" (:usn-rate-pct form)))
    (is (= :none (:taxation-type form)))))

;; ---------------------------------------------------------------------------
;; 015: opex rows + auto-rules
;; ---------------------------------------------------------------------------

(deftest opex-form->payload-valid
  (let [p (settings/opex-form->payload
           "2026-06" {:category "rent" :amount "15 000" :marketplace "wb" :note "склад"})]
    (is (= "2026-06" (:period-month p)))
    (is (= "rent" (:category p)))
    (is (= 15000 (:amount p)))
    (is (= :wb (:marketplace p)))
    (is (= "склад" (:note p)))))

(deftest opex-form->payload-omits-empty-optionals
  (let [p (settings/opex-form->payload
           "2026-06" {:category "other" :amount "100" :marketplace "" :note ""})]
    (is (not (contains? p :marketplace)))
    (is (not (contains? p :note)))))

(deftest opex-form->payload-invalid-returns-nil
  (is (nil? (settings/opex-form->payload "2026-06" {:category "rent" :amount ""})))
  (is (nil? (settings/opex-form->payload "2026-06" {:category "rent" :amount "0"})))
  (is (nil? (settings/opex-form->payload "2026-06" {:category "rent" :amount "-5"})))
  (is (nil? (settings/opex-form->payload "2026-06" {:category "" :amount "100"})))
  (is (nil? (settings/opex-form->payload nil {:category "rent" :amount "100"}))))

(deftest opex-category-label-known-and-unknown
  (is (= "Аренда" (settings/opex-category-label "rent")))
  (is (= "Аренда" (settings/opex-category-label :rent)))
  (is (= "custom" (settings/opex-category-label "custom"))))

(deftest auto-rule-form->payload-shape
  (let [p (settings/auto-rule-form->payload
           {:category "salary" :amount "40000" :marketplace "ozon"
            :effective-from "2026-01" :effective-to "2026-12"})]
    (is (= :monthly (:cadence p)))
    (is (= 40000 (:amount p)))
    (is (= :ozon (:marketplace p)))
    (is (= "2026-01" (:effective-from p)))
    (is (= "2026-12" (:effective-to p)))))

(deftest auto-rule-form->payload-open-ended-and-invalid
  (let [p (settings/auto-rule-form->payload
           {:category "rent" :amount "100" :marketplace "" :effective-from "2026-01"
            :effective-to ""})]
    (is (some? p))
    (is (not (contains? p :effective-to)))
    (is (not (contains? p :marketplace))))
  (is (nil? (settings/auto-rule-form->payload
             {:category "rent" :amount "100" :effective-from ""})))
  (is (nil? (settings/auto-rule-form->payload
             {:category "rent" :amount "" :effective-from "2026-01"}))))

;; ---------------------------------------------------------------------------
;; 017: bot subscriptions
;; ---------------------------------------------------------------------------

(deftest toggle-metric-add-remove-max
  (testing "add"
    (is (= [:revenue] (settings/toggle-metric [] :revenue 10))))
  (testing "remove keeps order of the rest"
    (is (= [:orders] (settings/toggle-metric [:revenue :orders] :revenue 10))))
  (testing "adding beyond max is a no-op"
    (is (= [:a :b] (settings/toggle-metric [:a :b] :c 2))))
  (testing "removal always allowed at max"
    (is (= [:b] (settings/toggle-metric [:a :b] :a 2))))
  (testing "nil metrics treated as empty"
    (is (= [:revenue] (settings/toggle-metric nil :revenue 10)))))

(deftest bot-form->payload-shape
  (let [p (settings/bot-form->payload
           {:chat-id " 12345 " :label "Основной" :daily? true :weekly? true
            :metrics [:revenue :net-profit] :show-movers? true
            :marketplace :wb :gate-when-empty :notice})]
    (is (= "12345" (:chat-id p)))                 ; trimmed
    (is (= #{:daily :weekly} (:cadences p)))
    (is (= [:revenue :net-profit] (:metrics p)))
    (is (true? (:show-movers? p)))
    (is (= :wb (:marketplace p)))
    (is (= :notice (:gate-when-empty p)))))

(deftest bot-form->payload-defaults
  (let [p (settings/bot-form->payload {:chat-id "1" :daily? true})]
    (is (= #{:daily} (:cadences p)))
    (is (= [] (:metrics p)))
    (is (= :all (:marketplace p)))
    (is (= :skip (:gate-when-empty p)))
    (is (false? (:show-movers? p)))))

(deftest bot-form-valid?-rules
  (is (true?  (settings/bot-form-valid? {:chat-id "42" :daily? true})))
  (is (false? (settings/bot-form-valid? {:chat-id "42"})))            ; no cadence
  (is (false? (settings/bot-form-valid? {:chat-id "  " :daily? true}))) ; blank chat
  (is (false? (settings/bot-form-valid? {:daily? true}))))

(deftest bot-sub->form-roundtrip
  (let [form (settings/bot-sub->form
              {:chat-id 555 :label "chat" :cadences #{:weekly}
               :metrics [:revenue] :show-movers? false
               :marketplace :ym :gate-when-empty :notice})]
    (is (= "555" (:chat-id form)))
    (is (false? (:new? form)))
    (is (false? (:daily? form)))
    (is (true? (:weekly? form)))
    (is (= [:revenue] (:metrics form)))
    (is (= :ym (:marketplace form)))
    (is (= :notice (:gate-when-empty form)))))

(deftest bot-metric-label-known-and-unknown
  (is (= "Выручка" (settings/bot-metric-label :revenue)))
  (is (= "ДРР, %" (settings/bot-metric-label :drr-pct)))
  (is (= "mystery" (settings/bot-metric-label :mystery))))

(deftest bot-metric-options-match-canonical-slugs
  ;; Mirror of backend report-schemas/canonical-metric-slugs (016 vocabulary).
  (is (= #{:revenue :orders :net-profit :gross-margin :margin-pct
           :cogs :mp-commission :logistics :storage :acceptance :penalties
           :deduction :additional :advertising :drr-pct
           :operating-expenses :ebitda :tax :vat
           :cap-by-cost :cap-by-price :gmroi :days-of-cover
           :revenue-abc :profit-abc}
         (set (map :slug settings/bot-metric-options)))))
