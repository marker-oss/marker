(ns analitica.logging-test
  (:require [clojure.test :refer [deftest is]]
            [analitica.logging :as logging]))

(deftest publisher-specs-has-console-and-file
  (let [specs (logging/publisher-specs)]
    (is (some #(= :console (:type %)) specs))
    (let [f (first (filter #(= :simple-file (:type %)) specs))]
      (is (some? f))
      (is (re-find #"events\.jsonl$" (:filename f))))))

(deftest log-file-path-default
  ;; env-safe: only assert the literal default when the override is actually unset
  (if (System/getenv "ANALITICA_LOG_FILE")
    (is (re-find #"\.jsonl$" (logging/log-file-path)))   ; override active → just sanity-check suffix
    (is (= "data/logs/events.jsonl" (logging/log-file-path)))))
