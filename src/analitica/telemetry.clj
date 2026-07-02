(ns analitica.telemetry
  "Observability — OTel-style tracing over the already-present mulog (US2).

   AUGMENTS existing :console + :simple-file logging — does NOT replace it
   (FR-013 / analitica.logging). No new dependencies: mu/trace + mu/with-context
   from com.brunobonacci/mulog 0.9.0 which is ALREADY on the classpath (R4).

   The key contract: `span` enforces a HARD allow-list on attribute keys so
   per-SKU / per-article / per-posting labels can NEVER reach a metrics backend
   and explode cardinality (FR-014 / data-model §4.3).

   Allowed:  #{:endpoint :http-method :operation :marketplace :outcome}
   Forbidden: #{:sku :article :posting-id :nm-id :order-id} — THROWS.

   `with-span` wraps a body in mu/trace with bounded-cardinality attributes.
   Duration is auto-captured by mu/trace; :outcome is set to :success or :error.

   FAIL-OPEN: this namespace is pure logic; the transport (mulog publishers)
   is owned by analitica.logging. A broken publisher never propagates to the
   request thread because mulog publishes asynchronously."
  (:require [malli.core :as m]
            [com.brunobonacci.mulog :as mu]))

;; ---------------------------------------------------------------------------
;; §4.3 Bounded-cardinality allow-list (HARD RULE — FR-014/R6)
;; ---------------------------------------------------------------------------

(def ^:private allowed-label-keys
  "Attribute keys accepted by span (contract: observability.edn :allowed-labels)."
  #{:endpoint :http-method :operation :marketplace :outcome})

(def ^:private forbidden-label-keys
  "Attribute keys that MUST NOT appear in a span (cardinality explosion)."
  #{:sku :article :posting-id :nm-id :order-id})

;; ---------------------------------------------------------------------------
;; §4.2 Malli schema (data-model §4.2)
;; ---------------------------------------------------------------------------

(def Span
  "Schema for a valid telemetry span map (contracts/observability.edn :span-malli)."
  [:map
   [:event/name  :keyword]
   [:endpoint    {:optional true} :string]
   [:http-method {:optional true} :keyword]
   [:operation   {:optional true} :keyword]
   [:marketplace {:optional true} [:enum :ozon :wb :ym]]
   [:duration-ms {:optional true} :double]
   [:outcome     [:enum :success :error]]])

(def ^:private span-validator (m/validator Span))

;; ---------------------------------------------------------------------------
;; span — validate + emit (low-level, synchronous span map)
;; ---------------------------------------------------------------------------

(defn span
  "Validate attributes against the allow-list and return a span map.

   Throws ex-info if any FORBIDDEN label key is present (FR-014 HARD RULE).
   Extra keys not in allowed-label-keys are silently stripped to prevent
   accidental cardinality leakage.

   :event/name and :outcome are REQUIRED. Other allowed keys are optional."
  [attrs]
  {:pre [(map? attrs)]}
  ;; 1. Detect forbidden keys — THROW immediately (hard guard)
  (let [forbidden-found (clojure.set/intersection
                          (set (keys attrs))
                          forbidden-label-keys)]
    (when (seq forbidden-found)
      (throw (ex-info
               (str "telemetry/span: FORBIDDEN label keys detected — "
                    "these would explode cardinality in any metrics backend. "
                    "Forbidden keys: " forbidden-found
                    ". Allowed: " allowed-label-keys)
               {:forbidden-keys forbidden-found
                :allowed-keys   allowed-label-keys
                :fr             "FR-014"}))))
  ;; 2. Keep only event/name + allowed keys (strip unknown extras silently)
  (let [cleaned (-> attrs
                    (select-keys (conj allowed-label-keys :event/name)))]
    ;; 3. Validate Malli schema (outcome is required)
    (when-not (span-validator cleaned)
      (throw (ex-info
               (str "telemetry/span: invalid span shape — "
                    (m/explain Span cleaned))
               {:span cleaned})))
    cleaned))

;; ---------------------------------------------------------------------------
;; with-span — wrap a body in mu/trace with bounded-cardinality attributes
;; ---------------------------------------------------------------------------

(defmacro with-span
  "Wrap `body` in a mu/trace block that emits a span with bounded-cardinality
   attributes. `attrs` is a map satisfying the span allow-list; the macro
   validates it at expansion-time and re-validates at runtime to guard against
   dynamically-constructed forbidden keys.

   :outcome is injected automatically:
     - :success when body returns normally
     - :error   when body throws (exception is re-thrown — fail-open means
                 telemetry never swallows application errors)

   Duration is auto-captured by mu/trace.

   Usage:
     (with-span {:event/name :marker/data-load :endpoint \"/api/...\"}
       (do-work!))"
  [attrs & body]
  `(let [validated-attrs# (span ~attrs)
         event-name#      (:event/name validated-attrs#)
         trace-attrs#     (dissoc validated-attrs# :event/name :outcome)]
     (mu/with-context trace-attrs#
       (mu/trace event-name#
         ;; outcome injected into the mulog event automatically
         {:mulog/outcome :success}
         (try
           (let [result# (do ~@body)]
             result#)
           (catch Throwable t#
             ;; Mark span as :error and re-throw — fail-open: telemetry never
             ;; swallows application errors (SC-005 = no telemetry death,
             ;; not error swallowing).
             (mu/log event-name# :outcome :error :error-message (.getMessage t#))
             (throw t#)))))))
