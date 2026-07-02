(ns analitica.logging
  "Centralized mulog publisher startup. Console for interactive use + a
   :simple-file publisher writing structured events to data/logs/events.jsonl
   (survives restart; one event per line).

   018-platform-seams T027: AUGMENTS with an optional fail-open OTLP/trace
   publisher ONLY when [:telemetry :enabled] is true in config. Without it,
   :console + :simple-file run exactly as before (FR-013 augment-not-replace).
   A broken telemetry endpoint never fails a request — mulog publishes
   asynchronously and publisher exceptions are isolated (SC-005)."
  (:require [com.brunobonacci.mulog :as mu]))

(defn log-file-path []
  (or (System/getenv "ANALITICA_LOG_FILE") "data/logs/events.jsonl"))

(defn- telemetry-enabled?
  "Return true when telemetry is enabled in config.
   Uses requiring-resolve so logging.clj does NOT hard-depend on config at
   load-time (avoids circular dep during startup before config is loaded)."
  []
  (try
    (let [cfg-fn (requiring-resolve 'analitica.config/telemetry-config)]
      (boolean (:telemetry/enabled (cfg-fn))))
    (catch Throwable _
      false)))

(defn- telemetry-endpoint
  "Return OTLP endpoint string or nil."
  []
  (try
    (let [cfg-fn (requiring-resolve 'analitica.config/telemetry-config)]
      (:telemetry/endpoint (cfg-fn)))
    (catch Throwable _
      nil)))

(defn publisher-specs
  "Return mulog publisher specs for startup.

   Always includes :console + :simple-file (FR-013 augment-not-replace).
   Appends an OTLP/trace publisher ONLY when :telemetry/enabled is true AND
   :telemetry/endpoint is configured. Without endpoint, no export publisher is
   added — exactly as before (SC-005: self-host without collector works normally)."
  []
  (let [base [{:type :console}
              {:type :simple-file :filename (log-file-path)}]]
    (if (and (telemetry-enabled?) (telemetry-endpoint))
      ;; Fail-open: any exception in the OTLP publisher is isolated by mulog's
      ;; async dispatch — it never propagates to the request thread (SC-005).
      ;; We use a :custom publisher wrapping a no-op for now (real OTLP export
      ;; would use mulog-zipkin or mulog-cloudwatch; this seam is the injection
      ;; point without adding new deps beyond what's already on classpath).
      (conj base {:type     :custom
                  :fqn-function 'analitica.logging/otlp-publisher-fn
                  :endpoint (telemetry-endpoint)})
      base)))

(defn otlp-publisher-fn
  "Fail-open OTLP publisher function (mulog :custom publisher).
   Called asynchronously by mulog — any exception here is isolated and
   never propagates to the request thread (SC-005).
   In MVP: logs to console that telemetry export is active.
   Replace the body with a real OTLP HTTP POST when mulog-zipkin lands."
  [{:keys [endpoint]}]
  (fn [events]
    (try
      ;; MVP: simple structured log of trace events to file-backed console.
      ;; A real implementation would POST to endpoint with OTLP/JSON.
      ;; This is the injection seam; the key contract is fail-open isolation.
      (doseq [evt events]
        (when (#{:marker/api-request :marker/background-op
                 :marker/data-load :marker/compute :marker/encode}
               (:event/name evt))
          (println (str "[telemetry] " endpoint " event=" (:event/name evt)
                        " outcome=" (:mulog/outcome evt)))))
      (catch Throwable _
        ;; Silently swallow — telemetry export must never fail a request (SC-005)
        nil))))

(defonce ^:private publishers (atom nil))

(defn start-publishers!
  "Start all configured mulog publishers; returns the vector of stop fns.
   Idempotent: on a re-call (start! is not strictly once-per-process — it runs
   config/reload!), stops the prior set first so the :simple-file OS file handle
   is not leaked. :simple-file auto-creates the parent dir (io/make-parents)."
  []
  (when-let [stops @publishers]
    (doseq [s stops] (try (s) (catch Throwable _))))
  (reset! publishers (mapv mu/start-publisher! (publisher-specs))))
