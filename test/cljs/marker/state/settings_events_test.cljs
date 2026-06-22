(ns marker.state.settings-events-test
  (:require [cljs.test :refer [deftest is]]
            [marker.state.events :as events]))

(deftest form->payload-drops-blanks
  (is (= {:api-token "tok"}
         (events/settings-form->payload {:api-token "tok" :unused ""})))
  (is (= {} (events/settings-form->payload {:api-token "" :x nil})))
  (is (= {} (events/settings-form->payload {:api-token "   "})))
  (is (= {:client-id "c" :api-key "k"}
         (events/settings-form->payload {:client-id "c" :api-key "k"}))))
