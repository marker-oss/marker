(ns analitica.costsource.csv1c-test
  "Unit tests for the CSV-with-diagnostics parser used by the
   /api/cost-prices/preview endpoint. Exercises the three error reasons
   plus header/blank skipping. parse-file (the legacy silent-drop path)
   is exercised indirectly via integration tests."
  (:require [clojure.test :refer [deftest testing is]]
            [analitica.costsource.csv1c :as csv1c]
            [clojure.java.io :as io])
  (:import [java.io File]))

(defn- write-tmp! [content]
  (let [f (File/createTempFile "csv1c-" ".csv")]
    (spit f content :encoding "UTF-8")
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(deftest parse-file-with-diagnostics-rows
  (testing "valid 1C-style rows are parsed"
    ;; 1C convention: price uses comma as thousand separator, qty uses
    ;; period as decimal — see parse-russian-number doc.
    (let [path (write-tmp!
                (str "Параметры отчёта\n"
                     "Склад,,,Номенклатура,,,Штрихкод,Цена,Количество\n"
                     "Склад1,,,\"Платье арт. 1234\",,,4640000000001,\"1,590\",10.000\n"
                     "Склад1,,,\"Платье арт. 5678\",,,4640000000002,\"2,100\",5.000\n"))
          {:keys [rows errors skipped total-lines]} (csv1c/parse-file-with-diagnostics path)]
      (is (= 2 (count rows)) "two product rows parsed")
      (is (= 0 (count errors)) "no errors")
      (is (= 2 skipped) "header + meta lines skipped")
      (is (= 4 total-lines))
      (is (= "1234" (:article-num (first rows))))
      (is (= 1590.0 (:cost-price (first rows)))))))

(deftest parse-file-with-diagnostics-errors
  (testing "rows missing the article column become :missing-article errors"
    (let [path (write-tmp!
                (str "Склад,,,,,,,,\n"
                     "Склад1,,,\"Платье арт. 1234\",,,40000000001,\"1 000,00\",1\n"))
          {:keys [rows errors]} (csv1c/parse-file-with-diagnostics path)]
      (is (= 1 (count rows)))
      (is (= 1 (count errors)))
      (is (= :missing-article (:reason (first errors))))))

  (testing "rows with too few columns are rejected separately"
    (let [path (write-tmp! "одна,две,колонки\n")
          {:keys [errors]} (csv1c/parse-file-with-diagnostics path)]
      (is (= 1 (count errors)))
      (is (= :too-few-columns (:reason (first errors))))))

  (testing "rows with article but blank price → :missing-cost-price"
    (let [path (write-tmp! "Склад1,,,\"Платье арт. 9999\",,,40000000099,,1\n")
          {:keys [errors]} (csv1c/parse-file-with-diagnostics path)]
      (is (= 1 (count errors)))
      (is (= :missing-cost-price (:reason (first errors)))))))

(deftest parse-file-with-diagnostics-skipping
  (testing "totals and blank lines count as :skipped, not :errors"
    (let [path (write-tmp!
                (str "Параметры отчёта\n"
                     "\n"
                     "Итого,,,,,,,,,\n"))
          {:keys [rows errors skipped]} (csv1c/parse-file-with-diagnostics path)]
      (is (= 0 (count rows)))
      (is (= 0 (count errors)))
      (is (= 3 skipped))))

  (testing "BOM-prefixed all-comma first line is :skipped, not :error"
    ;; Real 1C exports begin with U+FEFF BOM + commas, e.g. "﻿,,,,,,,,,"
    ;; — must not surface as an error when previewing.
    (let [path (write-tmp! "﻿,,,,,,,,,\nКлад1,,,\"Платье арт. 1\",,,1,\"100\",1.0\n")
          {:keys [errors skipped]} (csv1c/parse-file-with-diagnostics path)]
      (is (= 0 (count errors)) "BOM line is skipped, not flagged")
      (is (>= skipped 1)))))

(deftest parse-file-with-diagnostics-missing-file
  (testing "missing file throws ex-info for caller to translate to 4xx"
    (is (thrown? clojure.lang.ExceptionInfo
                 (csv1c/parse-file-with-diagnostics "/tmp/does-not-exist-csv1c-test.csv")))))
