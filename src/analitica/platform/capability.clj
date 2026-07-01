(ns analitica.platform.capability
  "Capability-slot: always-true, non-gating forward-compat seam (US3 T031).

   Open edition (epic §7.2 D2): EVERY capability is {:available true}.
   The helper `capabilities-for` accepts a caller context (single-API-key today;
   FR-028 forward seam for per-user later) and IGNORES it entirely — grants all.

   This is purely INFORMATIONAL: no endpoint truncates / hides / denies payload
   based on the slot (FR-017). History depth is INDEPENDENT of the slot —
   NO tier-based history truncation, ever (FR-019/SC-007, explicit AVOID).

   The slot is added ALONGSIDE the existing honesty-envelope keys
   (:completeness / :date-basis / :preliminary?) — NOT instead of them (FR-027/P6).

   Shape is STABLE for a future edition flip: only the :available VALUE changes;
   the response shape (set of keys) never changes (FR-018 additive retrofit).

   Malli schema: contracts/capability-slot.edn :capability-slot-malli"
  (:require [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; §2.2 Malli schema (data-model §2.2 / contracts/capability-slot.edn)
;; ---------------------------------------------------------------------------

(def CapabilitySlot
  "Schema for the capability-slot fragment.
   Open: every {:available true}. The value MAY become false in a future
   edition with NO shape change (FR-018)."
  [:map
   [:capabilities
    [:map-of :keyword [:map [:available :boolean]]]]])

(def ^:private capability-slot-validator (m/validator CapabilitySlot))

;; ---------------------------------------------------------------------------
;; §2.3 Open edition capability set (contracts/capability-slot.edn :envelope-fragment)
;; ---------------------------------------------------------------------------

(def ^:private open-edition-capabilities
  "All capabilities granted in the open edition (§7.2 D2 — grants everything).
   Keys mirror TrueStats financialMod/advertising/etc. but are ALWAYS true here
   (no hidden stats, no gating, no paywall)."
  {:financial-module {:available true}
   :advertising      {:available true}
   :tax-management   {:available true}
   :treasury         {:available true}
   :export           {:available true}
   :api-access       {:available true}})

;; ---------------------------------------------------------------------------
;; capabilities-for — the producer helper (contracts/capability-slot.edn :helper)
;; ---------------------------------------------------------------------------

(defn capabilities-for
  "Return the capability-slot map for the given caller context.

   Open edition: IGNORES caller entirely — grants all capabilities {:available true}.
   The caller argument is accepted as a forward-seam for the multi-user transition
   (FR-028: single→multi-user additive) but reads nothing from it today.

   Returns {:capabilities {:financial-module {:available true} …}}"
  [_caller]
  {:capabilities open-edition-capabilities})
