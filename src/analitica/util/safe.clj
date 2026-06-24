(ns analitica.util.safe
  "Run risky expressions without silently swallowing errors. `safely` logs a
   structured mulog event on throw and returns a fallback, replacing bare
   (catch Exception _ <default>) sites that hide failures."
  (:require [com.brunobonacci.mulog :as mu]))

(defn report-error!
  "Emit a structured mulog error event for a caught Throwable under ctx.
   (Indirection fn so tests can assert logging — mu/log is a macro and is not
   redefinable.)"
  [ctx ^Throwable t]
  (mu/log ctx
          :error-message (.getMessage t)
          :error-type    (.getName (class t))
          :stacktrace    (mapv str (.getStackTrace t))))

(defn safely*
  "Run thunk; on Throwable, report via mulog and return fallback. Never rethrows."
  [thunk fallback ctx]
  (try (thunk)
       (catch Throwable t
         (report-error! ctx t)
         fallback)))

(defmacro safely
  "(safely expr fallback ::ctx) — evaluate expr; on throw log + return fallback."
  [expr fallback ctx]
  `(safely* (fn [] ~expr) ~fallback ~ctx))
