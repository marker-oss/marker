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

;; ---------------------------------------------------------------------------
;; I3: narrow over-broad catch — once DB is initialized, errors from
;; settings/overrides must propagate, not be silently swallowed.
;; ---------------------------------------------------------------------------

(deftest i3-real-error-from-overrides-is-rethrown-when-db-initialized
  ;; The fixture has already called db/init!, so DB is initialized.
  ;; A real runtime error inside settings/overrides (corrupt DB, lock, etc.)
  ;; must NOT be caught silently — it must propagate so operators notice it.
  (with-redefs [analitica.settings/overrides (fn [] (throw (ex-info "boom" {:test true})))]
    (is (thrown? Throwable (config/reload!))
        "When DB is initialized, exceptions from settings/overrides must re-throw")))

(deftest i3-pre-init-path-yields-edn-value-without-throwing
  ;; When the DB has NOT been initialized, load-config must silently return {}
  ;; for overrides rather than propagating any error.
  ;; We simulate a pre-init state by stubbing db/initialized? to return false.
  (with-redefs [db/initialized? (fn [] false)]
    ;; No exception should escape; the EDN base value survives.
    (config/reload!)
    (is (= "EDN-DEFAULT" (get-in (config/config) [:marketplaces :wb :api-token]))
        "Pre-init path must return EDN value without throwing")))
