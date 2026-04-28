(ns analitica.audit.cli
  "CLI entry point for scheduled / CI audit runs (E-6, 2026-04-28).

  Usage:
    clojure -M:audit --period 2026-04 [--marketplace ozon] [--report-dir reports/]
    clojure -M:audit --from 2026-04-01 --to 2026-04-26 [--marketplace all]

  Exit codes (per contracts/cli-audit.md):
    0  clean run — no :suspicious / :unclassified
    1  one or more :suspicious found
    2  one or more :unclassified found (rule threw or unsupported input)

  Side effects:
    - Pretty-prints a one-screen digest to stdout.
    - When `--report-dir` is given, writes
      `audit-<period>-<scope>-<timestamp>.edn` with the full report
      so a downstream tool (cron, CI, slack-bot) can diff successive
      runs."
  (:require [clojure.tools.cli :as cli]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [analitica.db :as db]
            [analitica.audit.core :as audit])
  (:import (java.time YearMonth LocalDate)
           (java.time.format DateTimeFormatter)))

(def cli-options
  [["-p" "--period MONTH"     "Period as YYYY-MM (mutually exclusive with --from/--to)"]
   ["-f" "--from DATE"        "Start date YYYY-MM-DD"]
   ["-t" "--to DATE"          "End date YYYY-MM-DD"]
   ["-m" "--marketplace SCOPE" "wb | ozon | ym | all"
    :default "all"
    :validate [#(contains? #{"wb" "ozon" "ym" "all"} %)
               "must be one of wb / ozon / ym / all"]]
   [nil  "--tolerance-abs N" "Default abs tolerance in RUB (per-rule overrides apply)"
    :default 100.0
    :parse-fn #(Double/parseDouble %)]
   [nil  "--tolerance-rel N" "Default rel tolerance fraction (0.01 = 1%)"
    :default 0.01
    :parse-fn #(Double/parseDouble %)]
   [nil  "--report-dir DIR"  "Write the full report EDN under DIR/"]
   ["-h" "--help"]])

(defn- ym->dates [period]
  (let [ym (YearMonth/parse period)
        from (.atDay ym 1)
        to   (.atEndOfMonth ym)]
    {:from (str from) :to (str to)}))

(defn- resolve-period [{:keys [period from to]}]
  (cond
    period         (ym->dates period)
    (and from to)  {:from from :to to}
    :else          (throw (ex-info "Provide --period YYYY-MM or both --from and --to" {}))))

(defn- print-digest [{:keys [marketplace period]} report]
  (let [summary (:report/summary report)
        counts  (:counts summary)
        discs   (:report/discrepancies report)]
    (println)
    (println "──────────────────────────────────────────────────────────────")
    (println (format "  Audit %s  %s → %s"
                     (name marketplace) (:from period) (:to period)))
    (println "──────────────────────────────────────────────────────────────")
    (println (format "  expected:     %5d" (or (:expected counts) 0)))
    (println (format "  suspicious:   %5d" (or (:suspicious counts) 0)))
    (println (format "  unclassified: %5d" (or (:unclassified counts) 0)))
    (println)
    (when (seq (filter #(#{:suspicious :unclassified} (:disc/classification %)) discs))
      (println "  Findings (non-:expected):")
      (doseq [d (filter #(#{:suspicious :unclassified} (:disc/classification %)) discs)]
        (println (format "  • %-32s %s   %s"
                         (name (:disc/rule-id d))
                         (name (:disc/classification d))
                         (:disc/classification-reason d))))
      (println))))

(defn- ensure-dir! [path]
  (.mkdirs (io/file path)))

(defn- write-report [report-dir scope period report]
  (ensure-dir! report-dir)
  (let [stamp (.format (LocalDate/now) (DateTimeFormatter/ofPattern "yyyyMMdd"))
        file  (format "%s/audit-%s_%s_%s-%s.edn"
                      report-dir (:from period) (:to period) (name scope) stamp)]
    (with-open [w (io/writer file)]
      (pp/pprint report w))
    file))

(defn -main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options)
      (do (println "Audit CLI — Phase E-6")
          (println summary)
          (System/exit 0))

      errors
      (do (binding [*out* *err*] (run! println errors))
          (System/exit 64))

      :else
      (let [period   (resolve-period options)
            scope    (keyword (:marketplace options))
            tol      {:abs (:tolerance-abs options) :rel (:tolerance-rel options)}]
        (try
          (db/init!)
          (let [{:keys [report exit-code]}
                (audit/run-reconcile!
                  {:marketplace scope :period period :tolerance tol})]
            (print-digest {:marketplace scope :period period} report)
            (when-let [dir (:report-dir options)]
              (let [path (write-report dir scope period report)]
                (println (str "  report written to " path))
                (println)))
            (System/exit (or exit-code 0)))
          (catch Throwable t
            (binding [*out* *err*]
              (println "audit run failed:" (.getMessage t)))
            (System/exit 70)))))))
