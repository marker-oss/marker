(ns analitica.config-security-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [aero.core :as aero]
            [analitica.config :as config]))

(deftest api-key-getter-reads-config
  (with-redefs [config/config (constantly {:api-key "secret-123"})]
    (is (= "secret-123" (config/api-key))))
  (with-redefs [config/config (constantly {})]
    (is (nil? (config/api-key)))))

(defn- read-tmp-config
  "Write `edn-str` to a temp file, read it with the real aero reader
   (same path config/load-config uses), return parsed config. Hermetic."
  [edn-str]
  (let [f (io/file (System/getProperty "java.io.tmpdir")
                   (str "analitica-cfg-test-" (System/nanoTime) ".edn"))]
    (try
      (spit f edn-str)
      (aero/read-config f)
      (finally (.delete f)))))

;; F1 regression: this project loads .env entries as JVM system properties
;; (config/load-env-file! → System/setProperty), which aero's #prop reads.
;; A key wired as #env reads System/getenv and is invisible to that path,
;; so config/api-key fail-opens to nil. Pin the documented #prop wiring.
(deftest api-key-resolves-from-system-property-via-prop
  (let [prop "API_KEY_TEST_F1"
        val  "prop-sourced-secret"]
    (try
      (System/setProperty prop val)
      ;; The documented setup sets the key as a system property (.env loader).
      ;; #prop must surface it…
      (is (= val (:api-key (read-tmp-config (str "{:api-key #prop " prop "}"))))
          "#prop must resolve a key set as a system property (the documented .env path)")
      ;; …and #env must NOT (it reads getenv) — this is exactly why #env was a bug.
      (is (nil? (:api-key (read-tmp-config (str "{:api-key #env " prop "}"))))
          "#env reads System/getenv, so a system-property key is invisible — the F1 fail-open")
      (finally (System/clearProperty prop)))))

(deftest cors-origins-defaults-when-unset
  (with-redefs [config/config (constantly {})]
    (is (= ["https://marker.shegida.ru" "http://localhost:3000"] (config/cors-origins))))
  (with-redefs [config/config (constantly {:cors-origins ["https://x.test"]})]
    (is (= ["https://x.test"] (config/cors-origins)))))
