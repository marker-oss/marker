(ns analitica.canonical.events.ym-test
  "Tests for YM raw → canonical item_event normalizer (Phase 5g)."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.canonical.events.ym :as ym-ev]))

(def ^:private ym-order
  "Trimmed YM order from /v2/campaigns/{id}/orders. Two items."
  {:id 56440963456
   :creationDate     "2026-04-25"
   :statusUpdateDate "2026-04-25T22:59:01.872+03:00"
   :status           "PROCESSING"
   :items
   [{:marketSku 4561038784
     :shopSku   "ART-YM-A"
     :count     1
     :prices    [{:type "MARKETPLACE" :costPerItem 1791.0 :total 1791.0}
                 {:type "BUYER"       :costPerItem 2279.0 :total 2279.0}]
     :offerName "Платье"}
    {:marketSku 4561038786
     :shopSku   "ART-YM-B"
     :count     2
     :prices    [{:type "MARKETPLACE" :costPerItem 1500.0 :total 3000.0}
                 {:type "BUYER"       :costPerItem 1900.0 :total 3800.0}]
     :offerName "Платье 2"}]})

(deftest ordered-events-emit-per-item-and-per-unit
  (testing "two items with counts 1+2 → 3 ordered events with item_seq 0,1,2"
    (let [events (ym-ev/order->events ym-order 5)
          ordered (filter #(= "ordered" (:event-type %)) events)]
      (is (= 3 (count ordered)))
      (is (= [0 1 2] (mapv :item-seq ordered)))
      (is (= ["ART-YM-A" "ART-YM-B" "ART-YM-B"] (mapv :article ordered)))
      (is (every? #(= "ym" (:marketplace %)) ordered))
      (is (every? #(= "56440963456" (:posting-id %)) ordered))
      (is (every? #(= "2026-04-25" (:event-date %)) ordered))
      (is (= [2279.0 1900.0 1900.0] (mapv :gross-price ordered))
          "BUYER price preferred over MARKETPLACE"))))

(deftest in-flight-status-no-delivered-or-cancelled-event
  (testing "PROCESSING / DELIVERY / PICKUP statuses produce only ordered events"
    (doseq [s ["PROCESSING" "DELIVERY" "PICKUP"]]
      (let [order (assoc ym-order :status s)
            events (ym-ev/order->events order 1)
            types (set (map :event-type events))]
        (is (= #{"ordered"} types) (str "status=" s " → only ordered"))))))

(deftest delivered-status-emits-delivered-events
  (testing "status=DELIVERED → ordered + delivered (3+3 events)"
    (let [order  (assoc ym-order :status "DELIVERED")
          events (ym-ev/order->events order 1)]
      (is (= 6 (count events)))
      (is (= 3 (count (filter #(= "delivered" (:event-type %)) events))))
      (is (every? #(= "2026-04-25" (:event-date %))
                  (filter #(= "delivered" (:event-type %)) events))
          "delivered event_date from statusUpdateDate"))))

(deftest cancelled-statuses-emit-cancelled-events
  (testing "any CANCELLED* status → cancelled events (one per unit)"
    (doseq [s ["CANCELLED" "CANCELLED_BEFORE_PROCESSING"
               "CANCELLED_IN_PROCESSING" "CANCELLED_IN_DELIVERY"]]
      (let [order  (assoc ym-order :status s)
            events (ym-ev/order->events order 1)
            cancl  (filter #(= "cancelled" (:event-type %)) events)]
        (is (= 3 (count cancl)) (str "status=" s " → 3 cancelled units"))))))

(deftest order-without-id-or-creation-skipped
  (testing "missing id or creationDate or items → drop the order"
    (is (empty? (ym-ev/order->events (dissoc ym-order :id) 1)))
    (is (empty? (ym-ev/order->events (dissoc ym-order :creationDate) 1)))
    (is (empty? (ym-ev/order->events (assoc ym-order :items []) 1)))))
