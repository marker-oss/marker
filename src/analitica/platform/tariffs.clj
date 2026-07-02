(ns analitica.platform.tariffs
  "Tariffs-as-data: inert, non-enforcing edition/tariff catalogue (US4 T036).

   The catalogue is a `def` — pure data. Adding or editing a tariff is a
   DATA-ONLY change; no code modification is needed (FR-022/SC-008).

   Limit-like attributes (:sales-limit, :duration-period-days) are INERT in the
   open edition — they ENFORCE NOTHING (FR-021). A seller 'exceeding' a declared
   sales-limit is NOT denied / throttled / truncated.

   No billing / payments / paywall in scope (NON-goal). The catalogue is a
   readable declaration only; it is forward-compatible with a later per-user
   entitlement model (single→multi-user additive, FR-028).

   Shape modelled on TrueStats app-product (recon notes/09 §1.3):
     {id, title, cost, duration_period_days, demo_period_days, sales_limit, type}

   Malli schema: contracts/tariff.edn :tariff-malli"
  (:require [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; §3.2 Malli schema (data-model §3.2 / contracts/tariff.edn :tariff-malli)
;; ---------------------------------------------------------------------------

(def Tariff
  "Schema for a tariff/edition descriptor.
   All limit-like fields (:sales-limit, :duration-period-days) are INERT data —
   they are never read by request handlers to gate or throttle (FR-021)."
  [:map
   [:id                   :string]
   [:title                :string]
   [:cost                 [:int {:min 0}]]
   [:duration-period-days [:int {:min 0}]]
   [:demo-period-days     {:optional true} [:int {:min 0}]]
   [:sales-limit          {:optional true} [:maybe [:int {:min 0}]]]
   [:type                 [:enum :edition :tier :addon]]])

(def ^:private tariff-validator (m/validator Tariff))

(defn valid-tariff? [t] (tariff-validator t))

;; ---------------------------------------------------------------------------
;; §3.3 Catalogue — data only; adding/editing requires NO code change (FR-022)
;;
;; To add a tariff: append a map to this vector — that is the ONLY change.
;; No handler, no gate, no DB migration needed in the open edition.
;; ---------------------------------------------------------------------------

(def catalogue
  "Tariff/edition catalogue (data-only, contracts/tariff.edn :catalogue).

   free     — Open / Self-host; cost=0; unlimited; the open-edition default.
   starter  — DECLARATION for a possible future commercial edition; INERT.
   pro      — DECLARATION for a possible future commercial edition; INERT.

   The declarations below ENFORCE NOTHING in the open edition:
     - :sales-limit    → inert; never read to deny/throttle/truncate (FR-021)
     - :duration-period-days → inert; never used to expire data access (FR-021)
     - Adding a new entry here has ZERO behavioral impact (SC-008)"
  [{:id                   "free"
    :title                "Open / Self-host"
    :cost                 0
    :duration-period-days 0
    :demo-period-days     0
    :sales-limit          nil
    :type                 :edition}

   ;; The following are declarations a future commercial edition MAY read.
   ;; In the open edition they enforce NOTHING (no paywall, no throttle, no
   ;; truncation — FR-021/SC-006):
   {:id                   "starter"
    :title                "Starter"
    :cost                 1990
    :duration-period-days 30
    :demo-period-days     14
    :sales-limit          1000000
    :type                 :tier}

   {:id                   "pro"
    :title                "Pro"
    :cost                 4990
    :duration-period-days 30
    :demo-period-days     14
    :sales-limit          10000000
    :type                 :tier}])
