(ns analitica.web.api.settings-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.api.settings :as settings-api]
            [analitica.settings :as settings]
            [analitica.settings.validate :as validate]
            [analitica.core :as core]))

(deftest test-marketplace-returns-verdict-without-saving
  (with-redefs [validate/validate-credentials (fn [_ _] {:valid? true :detail "OK"})
                settings/set! (fn [& _] (throw (AssertionError. "must not save on /test")))]
    (let [resp (settings-api/test-marketplace
                 {:body-params {:marketplace "wb" :api-token "tok"}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :valid?]))))))

(deftest put-marketplace-422-on-invalid
  (with-redefs [validate/validate-credentials (fn [_ _] {:valid? false :detail "bad"})
                settings/set! (fn [& _] (throw (AssertionError. "must not save when invalid")))
                core/reload-config! (fn [] (throw (AssertionError. "must not reload when invalid")))]
    (let [resp (settings-api/put-marketplace
                 {:body-params {:marketplace "wb" :api-token "bad"}})]
      (is (= 422 (:status resp)))
      (is (false? (get-in resp [:body :valid?]))))))

(deftest put-marketplace-saves-and-reloads-on-valid
  (let [saved (atom {})
        reloaded (atom false)]
    (with-redefs [validate/validate-credentials (fn [_ _] {:valid? true :detail "OK"})
                  settings/set! (fn [k v & {:keys [secret?]}] (swap! saved assoc k {:v v :secret? (boolean secret?)}) k)
                  core/reload-config! (fn [] (reset! reloaded true) {:marketplaces [:wb]})]
      (let [resp (settings-api/put-marketplace
                   {:body-params {:marketplace "wb" :api-token "good"}})]
        (is (= 200 (:status resp)))
        (is (= {:v "good" :secret? true} (get @saved "mp.wb.api-token")))
        (is (true? @reloaded))
        (is (= [:wb] (get-in resp [:body :marketplaces])))))))

(deftest put-ozon-maps-id-and-secret-fields
  (let [saved (atom {})]
    (with-redefs [validate/validate-credentials (fn [_ _] {:valid? true :detail "OK"})
                  settings/set! (fn [k v & {:keys [secret?]}] (swap! saved assoc k (boolean secret?)) k)
                  core/reload-config! (fn [] {:marketplaces [:ozon]})]
      (settings-api/put-marketplace
        {:body-params {:marketplace "ozon" :client-id "cid" :api-key "key"}})
      (is (= false (get @saved "mp.ozon.client-id")))
      (is (= true  (get @saved "mp.ozon.api-key"))))))

(deftest unknown-marketplace-400
  (let [resp (settings-api/put-marketplace {:body-params {:marketplace "foo" :api-token "x"}})]
    (is (= 400 (:status resp)))))

(deftest get-settings-returns-masked
  (with-redefs [settings/masked-all (fn [] {"mp.wb.api-token" {:value "••••1234" :secret? true}})]
    (let [resp (settings-api/get-settings {})]
      (is (= 200 (:status resp)))
      (is (= "••••1234" (get-in resp [:body :settings "mp.wb.api-token" :value]))))))
