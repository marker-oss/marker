(ns marker.pages.sync-test
  "Tests for pure helpers in marker.pages.sync."
  (:require [cljs.test :refer [deftest is testing]]
            [marker.pages.sync :refer [task-display-id task-row-data]]))

;; ---------------------------------------------------------------------------
;; task-display-id
;; ---------------------------------------------------------------------------

(deftest task-display-id-normal
  (testing "returns last two segments"
    (is (= "sales/extract"
           (task-display-id "abc-123/wb/sales/extract"))))
  (testing "two-segment id is returned as-is"
    (is (= "sales/extract"
           (task-display-id "sales/extract"))))
  (testing "single-segment id is returned as-is"
    (is (= "extract"
           (task-display-id "extract"))))
  (testing "nil returns nil"
    (is (nil? (task-display-id nil)))))

;; ---------------------------------------------------------------------------
;; task-row-data
;; ---------------------------------------------------------------------------

(def ^:private sample-task
  {:id          "run-1/wb/sales/extract"
   :marketplace "wb"
   :entity-type "sales"
   :phase       "extract"
   :status      "completed"
   :items       42})

(deftest task-row-data-fields
  (testing "picks and transforms all display fields"
    (let [row (task-row-data sample-task)]
      (is (= "sales/extract" (:display-id row)))
      (is (= :wb             (:marketplace row)))
      (is (= "sales"         (:entity-type row)))
      (is (= "extract"       (:phase row)))
      (is (= "completed"     (:status row)))
      (is (= 42              (:items row)))))

  (testing "nil marketplace becomes nil (not keyword)"
    (let [row (task-row-data (dissoc sample-task :marketplace))]
      (is (nil? (:marketplace row)))))

  (testing "nil status stays nil"
    (let [row (task-row-data (dissoc sample-task :status))]
      (is (nil? (:status row)))))

  (testing "failed task has status 'failed'"
    (let [row (task-row-data (assoc sample-task :status "failed"))]
      (is (= "failed" (:status row))))))

;; ---------------------------------------------------------------------------
;; format-iso / duration-s are private defn- so not directly importable.
;; task-display-id / task-row-data are the extracted public surface.
;; ---------------------------------------------------------------------------
