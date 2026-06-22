(ns analitica.core-reload-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [analitica.db :as db]
            [analitica.settings :as settings]
            [analitica.config :as config]
            [analitica.core :as core]
            [analitica.marketplace.registry :as registry]
            [analitica.marketplace.wb.client :as wb-client]
            [analitica.marketplace.ozon.client :as ozon-client]
            [analitica.marketplace.ym.client :as ym-client]
            [analitica.sync.scheduler :as scheduler]
            [analitica.domain.cost-price :as cost-price]
            [analitica.schema.loader :as schema-loader]
            [analitica.sync :as sync]
            [com.brunobonacci.mulog :as mu]))

(use-fixtures :each
  (fn [t]
    (let [tmp-db  (java.io.File/createTempFile "reload-test-" ".db")
          tmp-cfg (java.io.File/createTempFile "reload-test-" ".edn")]
      (spit tmp-cfg (pr-str {:marketplaces {:wb {:api-token "EDN-DEFAULT" :rate-limits {}}}}))
      (with-redefs [db/db-spec {:dbtype "sqlite" :dbname (.getAbsolutePath tmp-db)}]
        (db/init!)
        (config/load-config (.getAbsolutePath tmp-cfg))
        (t))
      (.delete tmp-db) (.delete tmp-cfg))))

(deftest reload-rebuilds-wb-client-from-new-token
  ;; capture the token make-client was called with
  (let [seen (atom nil)]
    (with-redefs [wb-client/make-client (fn [cfg] (reset! seen (:api-token cfg)) ::wb-impl)]
      (settings/set! "mp.wb.api-token" "DB-OVERRIDE" :secret? true)
      (core/reload-config!)
      (is (= "DB-OVERRIDE" @seen))
      (is (= ::wb-impl (registry/get-marketplace :wb))))))

(deftest start!-applies-db-overrides-before-registering-clients
  ;; Verifies C1: config/reload! is called after db/init! so DB overrides are
  ;; applied before marketplace clients are constructed.
  ;;
  ;; We simulate the real cold-boot ordering by making db/initialized? start as
  ;; false (as it would be before db/init! runs) and flip to true only after
  ;; db/init! is called inside start!.  This means config/load-config at the top
  ;; of start! sees initialized?=false → skips overrides (EDN-only).  The C1 fix
  ;; (config/reload! after db/init!) then re-reads with initialized?=true and
  ;; picks up the DB override before register-marketplaces! is called.
  ;; Without C1 the client would be built with "EDN-DEFAULT", not "DB-OVERRIDE".
  (let [seen-token  (atom nil)
        db-init-ran (atom false)
        ;; starts false; flips to true once db/init! is called
        initialized?-fn (fn [] @db-init-ran)]
    (settings/set! "mp.wb.api-token" "DB-OVERRIDE" :secret? true)
    (with-redefs [db/initialized?           initialized?-fn
                  db/init!                  (fn [] (reset! db-init-ran true))
                  wb-client/make-client     (fn [cfg] (reset! seen-token (:api-token cfg)) ::wb-stub)
                  ozon-client/make-client   (fn [_] ::ozon-stub)
                  ym-client/make-client     (fn [_] ::ym-stub)
                  mu/start-publisher!       (fn [_] nil)
                  scheduler/start!          (fn [] nil)
                  cost-price/load-from-db!  (fn [] {:articles 1})
                  schema-loader/load-all!   (fn [] {:loaded 0 :errors []})
                  sync/status               (fn [] nil)]
      (core/start!))
    (is (= "DB-OVERRIDE" @seen-token)
        "start! must apply DB overrides (via config/reload!) before building MP clients")))
