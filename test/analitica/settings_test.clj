(ns analitica.settings-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.settings :as settings]))

(use-fixtures :each
  (fn [t]
    (let [tmp (java.io.File/createTempFile "settings-test-" ".db")]
      (with-redefs [db/db-spec {:dbtype "sqlite" :dbname (.getAbsolutePath tmp)}]
        (db/init!)
        (t))
      (.delete tmp))))

(deftest set-get-roundtrip
  (settings/set! "mp.wb.api-token" "secret-token-1234" :secret? true)
  (let [all (settings/get-all)]
    (is (= "secret-token-1234" (get-in all ["mp.wb.api-token" :value])))
    (is (true? (get-in all ["mp.wb.api-token" :secret?])))
    (is (some? (get-in all ["mp.wb.api-token" :updated-at])))))

(deftest set-upserts
  (settings/set! "sync.schedule.hour" "6")
  (settings/set! "sync.schedule.hour" "9")
  (is (= "9" (get-in (settings/get-all) ["sync.schedule.hour" :value])))
  (is (= 1 (count (settings/get-all)))))

(deftest overrides-is-flat
  (settings/set! "mp.wb.api-token" "tok" :secret? true)
  (settings/set! "business.tax-scheme" "usn-6")
  (is (= {"mp.wb.api-token" "tok" "business.tax-scheme" "usn-6"}
         (settings/overrides))))

(deftest delete-removes
  (settings/set! "x" "1")
  (settings/delete! "x")
  (is (empty? (settings/get-all))))

(deftest masking
  (is (= "••••1234" (settings/mask "secret-token-1234")))
  (is (= "••••" (settings/mask "ab")))
  (is (nil? (settings/mask nil))))

(deftest masked-all-hides-secrets
  (settings/set! "mp.wb.api-token" "secret-token-1234" :secret? true)
  (settings/set! "business.tax-scheme" "usn-6")
  (let [m (settings/masked-all)]
    (is (= "••••1234" (get-in m ["mp.wb.api-token" :value])))
    (is (= "usn-6" (get-in m ["business.tax-scheme" :value])))))
