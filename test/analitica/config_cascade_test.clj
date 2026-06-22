(ns analitica.config-cascade-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [analitica.db :as db]
            [analitica.settings :as settings]
            [analitica.config :as config]
            [clojure.java.io :as io]))

(def ^:dynamic *tmp-cfg* nil)

(use-fixtures :each
  (fn [t]
    (let [tmp-db  (java.io.File/createTempFile "cfg-test-" ".db")
          tmp-cfg (java.io.File/createTempFile "cfg-test-" ".edn")]
      (spit tmp-cfg (pr-str {:marketplaces {:wb {:api-token "EDN-DEFAULT"
                                                 :rate-limits {:statistics 1}}}
                             :business {:tax-scheme "usn-6"}}))
      (with-redefs [db/db-spec {:dbtype "sqlite" :dbname (.getAbsolutePath tmp-db)}]
        (db/init!)
        (config/load-config (.getAbsolutePath tmp-cfg))
        (binding [*tmp-cfg* (.getAbsolutePath tmp-cfg)]
          (t)))
      (.delete tmp-db)
      (.delete tmp-cfg))))

(deftest edn-base-when-no-overrides
  (is (= "EDN-DEFAULT" (get-in (config/config) [:marketplaces :wb :api-token]))))

(deftest db-override-wins-after-reload
  (settings/set! "mp.wb.api-token" "DB-OVERRIDE" :secret? true)
  (config/reload!)
  (is (= "DB-OVERRIDE" (get-in (config/config) [:marketplaces :wb :api-token])))
  ;; untouched keys keep their EDN value
  (is (= 1 (get-in (config/config) [:marketplaces :wb :rate-limits :statistics]))))

(deftest business-override
  (settings/set! "business.tax-scheme" "usn-15")
  (config/reload!)
  (is (= "usn-15" (get-in (config/config) [:business :tax-scheme]))))
