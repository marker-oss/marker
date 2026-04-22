(ns analitica.audit.bank-test
  "Tests for analitica.audit.bank — bank CSV parser (T017 / T023).

   Required behaviours:
     - Auto-detect delimiter: `,` or `;` based on header line
     - Skip UTF-8 BOM if present (Excel always prepends it)
     - Parse `date,amount` into {:sum N :by-date {date→amount} :missing-dates [...]}
     - Report missing dates relative to a requested period (FR-011)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [analitica.audit.bank :as bank])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; parse-bank-sum (scalar)
;; ---------------------------------------------------------------------------

(deftest parse-bank-sum-accepts-numeric-string
  (is (= 12345.67 (bank/parse-bank-sum "12345.67")))
  (is (= 1000.0   (bank/parse-bank-sum "1000")))
  (is (= 1000.0   (bank/parse-bank-sum "1000.0"))))

(deftest parse-bank-sum-rejects-garbage
  (is (thrown? clojure.lang.ExceptionInfo (bank/parse-bank-sum "abc")))
  (is (thrown? clojure.lang.ExceptionInfo (bank/parse-bank-sum nil))))

;; ---------------------------------------------------------------------------
;; parse-bank-csv — delimiter detection
;; ---------------------------------------------------------------------------

(defn- write-temp [content]
  (let [f (File/createTempFile "bank-test-" ".csv")]
    (.deleteOnExit f)
    (spit f content :encoding "UTF-8")
    (.getCanonicalPath f)))

(def ^:private period {:from "2026-03-01" :to "2026-03-03"})

(deftest parse-bank-csv-comma-delimiter
  (let [path (write-temp "date,amount\n2026-03-01,1000.0\n2026-03-02,2000.0\n2026-03-03,3000.0\n")
        result (bank/parse-bank-csv path period)]
    (is (= 6000.0 (:sum result)))
    (is (= {"2026-03-01" 1000.0
            "2026-03-02" 2000.0
            "2026-03-03" 3000.0}
           (:by-date result)))
    (is (= [] (:missing-dates result)))))

(deftest parse-bank-csv-semicolon-delimiter
  (let [path (write-temp "date;amount\n2026-03-01;1000.0\n2026-03-02;2000.0\n2026-03-03;3000.0\n")
        result (bank/parse-bank-csv path period)]
    (is (= 6000.0 (:sum result)))
    (is (= 3 (count (:by-date result))))))

;; ---------------------------------------------------------------------------
;; BOM handling (Excel always writes UTF-8 BOM)
;; ---------------------------------------------------------------------------

(deftest parse-bank-csv-strips-utf8-bom
  (let [bom-content (str "﻿date,amount\n2026-03-01,500.0\n")
        path (write-temp bom-content)
        result (bank/parse-bank-csv path period)]
    (is (= 500.0 (:sum result))
        "BOM at start of header must not break delimiter detection or parsing")
    (is (= 500.0 (get-in result [:by-date "2026-03-01"])))))

;; ---------------------------------------------------------------------------
;; Missing dates — FR-011 gating for KPI baseline
;; ---------------------------------------------------------------------------

(deftest parse-bank-csv-reports-missing-dates
  (let [path (write-temp "date,amount\n2026-03-01,1000.0\n2026-03-03,3000.0\n")
        result (bank/parse-bank-csv path period)]
    (is (= 4000.0 (:sum result)))
    (is (= ["2026-03-02"] (:missing-dates result))
        "2026-03-02 present in period but missing in CSV → must be reported")))

(deftest parse-bank-csv-empty-missing-when-complete
  (let [path (write-temp "date,amount\n2026-03-01,1.0\n2026-03-02,2.0\n2026-03-03,3.0\n")
        result (bank/parse-bank-csv path period)]
    (is (empty? (:missing-dates result)))))

;; ---------------------------------------------------------------------------
;; Decimal formats — Russian locale often uses comma; we accept dot only
;; (document behaviour)
;; ---------------------------------------------------------------------------

(deftest parse-bank-csv-accepts-integer-amounts
  (let [path (write-temp "date,amount\n2026-03-01,100\n")
        result (bank/parse-bank-csv path period)]
    (is (= 100.0 (:sum result)))))
