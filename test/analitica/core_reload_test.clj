(ns analitica.core-reload-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [analitica.db :as db]
            [analitica.settings :as settings]
            [analitica.config :as config]
            [analitica.core :as core]
            [analitica.marketplace.registry :as registry]
            [analitica.marketplace.wb.client :as wb-client]))

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
