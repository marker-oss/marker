(ns analitica.settings-table-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [analitica.db :as db]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; Use an isolated on-disk temp DB so we never touch the real analitica.db.
(use-fixtures :once
  (fn [t]
    (let [tmp (java.io.File/createTempFile "settings-test-" ".db")]
      (System/setProperty "ANALITICA_DB" (.getAbsolutePath tmp))
      ;; db-spec reads ANALITICA_DB via System/getenv, not getProperty, so
      ;; point the datasource directly for the test instead.
      (with-redefs [db/db-spec {:dbtype "sqlite" :dbname (.getAbsolutePath tmp)}]
        (db/init!)
        (t))
      (.delete tmp))))

(deftest app-settings-table-exists
  (let [cols (->> (jdbc/execute! (db/ds) ["PRAGMA table_info(app_settings)"]
                                 {:builder-fn rs/as-unqualified-maps})
                  (map :name)
                  set)]
    (is (= #{"key" "value" "secret" "updated_at"} cols))))
