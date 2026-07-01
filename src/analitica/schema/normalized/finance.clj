(ns analitica.schema.normalized.finance
  "Canonical FinanceRow — the contract between transform (L3) and
   materialize (L4). See docs/data-dictionary.md#finance for canonical
   semantics.

   Rows carrying extra keys are accepted ({:closed false}); missing
   required keys are rejected. Validation is non-fatal by default:
   `validate-rows` returns bad rows alongside good ones so a schema
   drift never silently drops data."
  (:require [malli.core :as m]
            [malli.error :as me]
            [clojure.string]
            [analitica.schema.util :as schema-util]))

(def FinanceRow
  "Minimal contract every transform must satisfy. Identity + the three
   numeric pillars (for-pay, retail-amount, quantity) are required.
   Every other field is optional-nilable because not every marketplace
   has every field (see data-dictionary.md §finance.source-mapping
   for the coverage matrix).

   Operation classification (RFC-3, two-level — see canonical-formulas
   §4.3 and concept-crosswalk §2.1):
   - :operation         — legacy string, deprecated; kept for backward
                          compat with existing DB rows. New writers should
                          set it to (name operation-kind).
   - :operation-kind    — canonical enum {:sale :return :service
                          :adjustment}. Required for L2 formulas.
                          Optional in schema during rollout; once all
                          rows are re-materialized this becomes required
                          and `:operation` is dropped.
   - :operation-subtype — raw classifier from the marketplace
                          (e.g. \"Продажа\" / \"Логистика\" / \"DELIVERED\"
                          / \"MarketplaceServiceItemReturnAfterDelivery\").
                          Optional. Used by audit/UI for drill-down.

   Sign conventions (RFC-14, RFC-15 — invariants enforced by transforms,
   not yet by schema until DB backfill is complete):
   - :for-pay   ≥ 0 for WB/Ozon (sign encoded in :operation-kind: sale = +,
                return = − under L2 mp_payout formula). For :service /
                :adjustment rows :for-pay is 0 — actual money lives in
                dedicated fields (delivery-cost / storage-fee /
                additional-payment / penalty / deduction).
                YM EXCEPTION (spec 012): a YM sale row's :for-pay MAY be
                negative (gross − Σdeductions can be < 0 on a loss-making,
                heavily-returned SKU). The ≥0 assumption is enforced by the
                WB/Ozon transforms only, not by this schema. YM returns keep
                +abs for the L2 pair. Schema type is `number?` accordingly.
   - :net-sales YM post-discount BUYER amount (Σ BUYER×qty); nil for WB/Ozon
                where there is no platform-side discount (net == gross).
   - :quantity  ≥ 0 always. Returns use positive quantity together with
                operation-kind = :return."
  [:map {:closed false}
   [:marketplace    [:enum :wb :ozon :ym]]
   [:rrd-id         [:or :int :double]]
   [:date-from      :string]
   [:date-to        :string]
   ;; :event-date is required going forward (2026-04-23 migration).
   ;; Legacy rows that pre-date it may have nil until re-materialized;
   ;; optional-nilable during the rollout window.
   [:event-date     {:optional true} [:maybe :string]]
   [:article        [:maybe :string]]
   [:operation      :string]
   [:operation-kind    {:optional true} [:maybe [:enum :sale :return :service :adjustment]]]
   [:operation-subtype {:optional true} [:maybe :string]]
   [:quantity       [:maybe [:or :int :double]]]
   ;; number? (not [:or :int :double]) — YM sale rows may be negative (spec 012)
   ;; and the value can be a ratio/BigDecimal from upstream Clojure math.
   [:for-pay        number?]

   [:report-id          {:optional true} [:maybe [:or :int :double]]]
   ;; :nm-id holds the marketplace's internal product id — WB `nmId`,
   ;; Ozon `sku`, YM `marketSku`. The WB-era prefix is a legacy of the
   ;; project's single-MP origin; despite the name, the field is
   ;; cross-MP. RFC-1 (concept-crosswalk §1.2) was closed 2026-04-28
   ;; *without* a rename — the cost-benefit of touching ~256 references
   ;; (incl. UI data-attributes, URL params, JS payloads) outweighed the
   ;; cosmetic gain. Semantics are now anchored in this docstring and in
   ;; data-dictionary.md §finance.
   [:nm-id              {:optional true} [:maybe [:or :int :double]]]
   [:barcode            {:optional true} [:maybe :string]]
   [:subject            {:optional true} [:maybe :string]]
   [:brand              {:optional true} [:maybe :string]]
   [:doc-type           {:optional true} [:maybe :string]]
   [:retail-price       {:optional true} [:maybe [:or :int :double]]]
   [:retail-amount      {:optional true} [:maybe [:or :int :double]]]
   ;; :net-sales (spec 012) — YM post-discount BUYER amount (Σ BUYER×qty).
   ;; nil for WB/Ozon (no platform discount → net == gross == :retail-amount).
   [:net-sales          {:optional true} [:maybe number?]]
   [:sale-percent       {:optional true} [:maybe [:or :int :double]]]
   [:commission-pct     {:optional true} [:maybe [:or :int :double]]]
   ;; RFC-6 (closed 2026-04-28): renamed from :wb-commission. The
   ;; WB-prefixed name was misleading — Ozon and YM also use this field
   ;; for MP commission RUB. The legacy alias :wb-commission is no longer
   ;; written by transforms; readers should accept :mp-commission only.
   [:mp-commission      {:optional true} [:maybe [:or :int :double]]]
   [:wb-reward          {:optional true} [:maybe [:or :int :double]]]
   [:wb-kvw-prc         {:optional true} [:maybe [:or :int :double]]]
   [:spp-prc            {:optional true} [:maybe [:or :int :double]]]
   [:price-with-disc    {:optional true} [:maybe [:or :int :double]]]
   [:delivery-amount    {:optional true} [:maybe [:or :int :double]]]
   [:return-amount      {:optional true} [:maybe [:or :int :double]]]
   [:delivery-cost      {:optional true} [:maybe [:or :int :double]]]
   [:penalty            {:optional true} [:maybe [:or :int :double]]]
   [:storage-fee        {:optional true} [:maybe [:or :int :double]]]
   [:acceptance         {:optional true} [:maybe [:or :int :double]]]
   [:additional-payment {:optional true} [:maybe [:or :int :double]]]
   [:deduction          {:optional true} [:maybe [:or :int :double]]]
   [:acquiring-fee      {:optional true} [:maybe [:or :int :double]]]
   [:ad-cost            {:optional true} [:maybe [:or :int :double]]]
   [:synced-at          {:optional true} [:maybe :string]]])

(def ^:private validator (m/validator FinanceRow))
(def ^:private explainer (m/explainer FinanceRow))

(defn valid? [row] (validator row))

(defn explain [row] (some-> (explainer row) me/humanize))

(defn validate-rows [rows]
  (schema-util/validate-rows validator explain rows))

(defn summarize-bad
  [bad]
  (->> bad
       (group-by (fn [{:keys [row error]}]
                   [(or (:marketplace row) :unknown)
                    (first (keys error))]))
       (map (fn [[[mp k] xs]]
              (str "  " (name mp) " → " (name (or k :unknown))
                   ": " (count xs) " rows")))
       (clojure.string/join "\n")))
