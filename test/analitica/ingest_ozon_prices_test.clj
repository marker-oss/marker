(ns analitica.ingest-ozon-prices-test
  "Regression tests for ingest-ozon-prices!:

   - reads `:items` at the top level of /v3/product/info/list (the
     previous code read `[:result :items]` which doesn't exist on v3
     and silently produced 0 prices).
   - batches offer_ids (Ozon caps each request, undocumented but
     mirrors the 500 used by sync-sku-map!).
   - one bad batch does not abort the rest of the ingest."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.ingest :as ingest]
            [analitica.marketplace.ozon.api :as ozon-api]
            [analitica.db :as db]))

(defn- with-stub-db [f]
  (let [captured (atom nil)]
    (with-redefs [db/insert-raw!
                  (fn [_src _entity _from _to data]
                    (reset! captured (vec data))
                    (count data))]
      (f captured))))

(deftest ingest-ozon-prices-uses-v3-items-path
  (testing "products extracted from :items, not [:result :items]"
    (with-stub-db
      (fn [captured]
        (with-redefs [ozon-api/product-list (fn [_] [{:offer_id "A"} {:offer_id "B"}])
                      ;; v3 shape: {:items [...]} at top level
                      ozon-api/product-info (fn [_ _] {:items [{:offer_id "A" :marketing_price 100}
                                                               {:offer_id "B" :marketing_price 200}]})]
          (let [n (#'ingest/ingest-ozon-prices! :stub-client)]
            (is (= 2 n))
            (is (= 2 (count @captured)))
            (is (= ["A" "B"] (mapv :offer_id @captured)))))))))

(deftest ingest-ozon-prices-batches-offer-ids
  (testing "more than 500 offer_ids → multiple product-info calls, results concatenated"
    (with-stub-db
      (fn [captured]
        (let [calls (atom 0)
              all-offer-ids (mapv #(str "OF-" %) (range 1100))]
          (with-redefs [ozon-api/product-list (fn [_] (mapv #(hash-map :offer_id %) all-offer-ids))
                        ozon-api/product-info (fn [_ batch]
                                                (swap! calls inc)
                                                {:items (mapv #(hash-map :offer_id % :price 1) batch)})]
            (let [n (#'ingest/ingest-ozon-prices! :stub-client)]
              (is (= 1100 n) "all offer_ids produce a price record")
              (is (= 3 @calls) "1100 / 500 = 3 batches (500 + 500 + 100)")
              (is (= 1100 (count @captured))))))))))

(deftest ingest-ozon-prices-one-bad-batch-is-tolerated
  (testing "throwing on a single batch only loses that batch, not the whole ingest"
    (with-stub-db
      (fn [captured]
        (let [call-no (atom 0)
              all-offer-ids (mapv #(str "OF-" %) (range 1500))]
          (with-redefs [ozon-api/product-list (fn [_] (mapv #(hash-map :offer_id %) all-offer-ids))
                        ozon-api/product-info (fn [_ batch]
                                                (swap! call-no inc)
                                                (if (= 2 @call-no)
                                                  (throw (ex-info "boom" {}))
                                                  {:items (mapv #(hash-map :offer_id % :price 1) batch)}))]
            (let [n (#'ingest/ingest-ozon-prices! :stub-client)]
              ;; 3 batches × 500, second throws → 500 + 0 + 500 = 1000
              (is (= 1000 n))
              (is (= 1000 (count @captured))))))))))
