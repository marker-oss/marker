(ns analitica.logging
  "Centralized mulog publisher startup. Console for interactive use + a
   :simple-file publisher writing structured events to data/logs/events.jsonl
   (survives restart; one event per line)."
  (:require [com.brunobonacci.mulog :as mu]))

(defn log-file-path []
  (or (System/getenv "ANALITICA_LOG_FILE") "data/logs/events.jsonl"))

(defn publisher-specs []
  [{:type :console}
   {:type :simple-file :filename (log-file-path)}])

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
