(ns analitica.config-test
  "T005 (spec 011-ozon-performance-ads) — config/ozon-performance-config.

   FR-005 / research.md R7: the Ozon Performance (advertising) subsystem is
   OPT-IN. It activates only when BOTH credential parts are present under
   [:marketplaces :ozon :performance]. A partial credential (only client-id,
   or only client-secret) is treated as NOT configured (returns nil) — the
   feature stays off rather than half-booting."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [analitica.db :as db]
            [analitica.config :as config]))

(def ^:dynamic *cfg-path* nil)

(defn- write-cfg! [m]
  (let [f (java.io.File/createTempFile "perf-cfg-" ".edn")]
    (spit f (pr-str m))
    (.getAbsolutePath f)))

(use-fixtures :each
  (fn [t]
    (let [tmp-db (java.io.File/createTempFile "perf-cfg-" ".db")]
      (with-redefs [db/db-spec {:dbtype "sqlite" :dbname (.getAbsolutePath tmp-db)}]
        (db/init!)
        (t))
      (.delete tmp-db))))

(defn- load-with! [ozon-map]
  (let [path (write-cfg! {:marketplaces {:ozon ozon-map}})]
    (config/load-config path)))

(deftest ozon-performance-config-returns-map-when-both-creds-present
  (load-with! {:performance {:client-id "CID" :client-secret "CSECRET"}})
  (is (= {:client-id "CID" :client-secret "CSECRET"}
         (config/ozon-performance-config))
      "both cred parts present → full config map (feature ON)"))

(deftest ozon-performance-config-nil-when-section-absent
  (load-with! {:api-key "x"})
  (is (nil? (config/ozon-performance-config))
      "no :performance section → nil (feature OFF, FR-005)"))

(deftest ozon-performance-config-nil-when-client-id-missing
  (load-with! {:performance {:client-secret "CSECRET"}})
  (is (nil? (config/ozon-performance-config))
      "client-id missing → nil (partial creds = off)"))

(deftest ozon-performance-config-nil-when-client-secret-missing
  (load-with! {:performance {:client-id "CID"}})
  (is (nil? (config/ozon-performance-config))
      "client-secret missing → nil (partial creds = off)"))

(deftest ozon-performance-config-nil-when-cred-part-blank
  (load-with! {:performance {:client-id "CID" :client-secret ""}})
  (is (nil? (config/ozon-performance-config))
      "blank cred part (e.g. unresolved #prop) → nil (partial creds = off)"))
