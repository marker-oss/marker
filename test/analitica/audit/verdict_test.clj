(ns analitica.audit.verdict-test
  "Tests for analitica.audit.verdict — BugHypothesisVerdict markdown parser (US3).

   Covers:
     T038 — parse-verdicts-file: all 9 verdicts from live verdicts.md,
            id/title/conclusion extraction, placeholder tolerance, idempotence.
     T039 — verdict-show: full section markdown by id (case-insensitive),
            nil/ex-info for unknown id, inclusion of `### Update` postscripts."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [analitica.audit.verdict :as verdict]))

;; ---------------------------------------------------------------------------
;; T038 — parser tests
;; ---------------------------------------------------------------------------

(deftest parses-all-nine-verdicts
  (testing "parse-verdicts-file reads all 9 sections from live verdicts.md"
    (let [parsed (verdict/parse-verdicts-file)]
      (is (= 9 (count parsed))
          "expected exactly B-001 through B-009")
      (is (= ["B-001" "B-002" "B-003" "B-004"
              "B-005" "B-006" "B-007" "B-008" "B-009"]
             (mapv :verdict/id parsed))))))

(deftest extracts-id-and-title-for-each-verdict
  (testing "each verdict has :verdict/id and :verdict/title"
    (let [parsed (verdict/parse-verdicts-file)]
      (doseq [v parsed]
        (is (string? (:verdict/id v)) (str "id for " v))
        (is (re-matches #"B-\d{3}" (:verdict/id v)))
        (is (string? (:verdict/title v)) (str "title for " (:verdict/id v)))
        (is (seq (:verdict/title v)) (str "non-empty title for " (:verdict/id v)))))))

(deftest extracts-conclusion-for-each-verdict
  (testing "each verdict has :verdict/conclusion as keyword"
    (let [parsed    (verdict/parse-verdicts-file)
          by-id     (into {} (map (juxt :verdict/id identity) parsed))
          valid-set #{:refuted :confirmed :fixed
                      :not-yet-verdicted :confirmed-deferred}]
      (doseq [v parsed]
        (is (contains? valid-set (:verdict/conclusion v))
            (str "conclusion for " (:verdict/id v)
                 " must be one of " valid-set
                 " got: " (pr-str (:verdict/conclusion v)))))
      ;; Spot-check specific known verdicts from sweep done 2026-04-22.
      (is (= :refuted            (:verdict/conclusion (by-id "B-001"))))
      (is (= :refuted            (:verdict/conclusion (by-id "B-002"))))
      (is (= :confirmed-deferred (:verdict/conclusion (by-id "B-003"))))
      (is (= :refuted            (:verdict/conclusion (by-id "B-004"))))
      (is (= :fixed              (:verdict/conclusion (by-id "B-005"))))
      (is (= :confirmed          (:verdict/conclusion (by-id "B-006"))))
      (is (= :fixed              (:verdict/conclusion (by-id "B-007"))))
      (is (= :fixed              (:verdict/conclusion (by-id "B-008"))))
      (is (= :fixed              (:verdict/conclusion (by-id "B-009")))))))

(deftest extracts-linked-ticket-handling-placeholders
  (testing "linked-ticket placeholder `—` yields nil; real values are strings"
    (let [parsed (verdict/parse-verdicts-file)
          by-id  (into {} (map (juxt :verdict/id identity) parsed))]
      ;; B-001 / B-002 / B-004 all have "Linked ticket: —" → nil.
      (is (nil? (:verdict/linked-ticket (by-id "B-001"))))
      (is (nil? (:verdict/linked-ticket (by-id "B-002"))))
      (is (nil? (:verdict/linked-ticket (by-id "B-004")))))))

(deftest handles-missing-file-gracefully
  (testing "parse-verdicts-file on non-existent path returns empty vector"
    (is (= [] (verdict/parse-verdicts-file "/tmp/does-not-exist-verdicts.md")))))

(deftest parsing-is-idempotent
  (testing "calling parse-verdicts-file twice returns structurally equal results"
    (let [run1 (verdict/parse-verdicts-file)
          run2 (verdict/parse-verdicts-file)]
      (is (= (mapv :verdict/id run1) (mapv :verdict/id run2)))
      (is (= (mapv :verdict/conclusion run1) (mapv :verdict/conclusion run2)))
      (is (= (mapv :verdict/title run1) (mapv :verdict/title run2))))))

(deftest each-verdict-carries-raw-markdown
  (testing ":verdict/raw-markdown holds the section text including header"
    (let [parsed (verdict/parse-verdicts-file)]
      (doseq [v parsed]
        (is (string? (:verdict/raw-markdown v)))
        (is (str/starts-with? (:verdict/raw-markdown v)
                              (str "## " (:verdict/id v) " —"))
            (str "raw-markdown must start with `## " (:verdict/id v)
                 " —` for verdict " (:verdict/id v)))))))

;; ---------------------------------------------------------------------------
;; T039 — verdict-show tests
;; ---------------------------------------------------------------------------

(deftest verdict-show-returns-section-for-known-id
  (testing "verdict-show \"B-001\" returns string with title + structured fields"
    (let [md (verdict/verdict-show "B-001")]
      (is (string? md))
      (is (str/includes? md "B-001"))
      (is (str/includes? md "Двойное вычитание логистики"))
      (is (str/includes? md "**Hypothesis**"))
      (is (str/includes? md "**Method**"))
      (is (str/includes? md "**Evidence**"))
      (is (str/includes? md "**Conclusion**")))))

(deftest verdict-show-is-case-insensitive
  (testing "lower-case and mixed-case ids both resolve"
    (let [canonical (verdict/verdict-show "B-001")
          lower     (verdict/verdict-show "b-001")
          mixed     (verdict/verdict-show "b-001")]
      (is (= canonical lower))
      (is (= canonical mixed)))))

(deftest verdict-show-unknown-id-returns-nil
  (testing "verdict-show on non-existent id returns nil"
    (is (nil? (verdict/verdict-show "B-999")))
    (is (nil? (verdict/verdict-show "bogus")))))

(deftest verdict-show-includes-update-postscripts
  (testing "multi-update verdict (B-005) includes all `### Update ...` sections"
    (let [md (verdict/verdict-show "B-005")]
      (is (string? md))
      ;; B-005 has THREE postscripts: "Root cause найден", "Update 2026-04-22 (late)",
      ;; "Update 2026-04-22 (evening)".
      (is (str/includes? md "### Root cause найден"))
      (is (str/includes? md "### Update 2026-04-22 (late)"))
      (is (str/includes? md "### Update 2026-04-22 (evening)"))
      ;; But MUST NOT spill into next verdict B-006.
      (is (not (str/includes? md "## B-006")))
      (is (not (str/includes? md "Audit-инструмент"))))))

(deftest verdict-show-b-009-includes-update-postscript
  (testing "B-009 also has a `### Update` postscript that must be included"
    (let [md (verdict/verdict-show "B-009")]
      (is (string? md))
      (is (str/includes? md "### Update 2026-04-22:"))
      (is (str/includes? md "Post-fix measurements")))))

;; ---------------------------------------------------------------------------
;; verdicts-list
;; ---------------------------------------------------------------------------

(deftest verdicts-list-returns-summary-rows
  (testing "verdicts-list returns abbreviated rows with id/title/conclusion/linked-ticket"
    (let [rows (verdict/verdicts-list)]
      (is (= 9 (count rows)))
      (doseq [row rows]
        (is (contains? row :verdict/id))
        (is (contains? row :verdict/title))
        (is (contains? row :verdict/conclusion))))))

(deftest verdicts-list-filters-by-conclusion
  (testing "optional :conclusion-filter restricts rows"
    (let [fixed-rows (verdict/verdicts-list :conclusion-filter :fixed)
          ids        (set (map :verdict/id fixed-rows))]
      ;; B-005, B-007, B-008, B-009 are :fixed per current verdicts.md.
      (is (= #{"B-005" "B-007" "B-008" "B-009"} ids)))))
