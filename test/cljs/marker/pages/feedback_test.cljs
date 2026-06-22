(ns marker.pages.feedback-test
  (:require [cljs.test :refer [deftest is]]
            [marker.pages.feedback :as fb]))

(deftest kind-options-present
  (is (= #{"bug" "idea" "question"} (set (map first fb/kind-options))))
  (is (= 3 (count fb/kind-options))))
