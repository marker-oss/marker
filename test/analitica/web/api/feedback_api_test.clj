(ns analitica.web.api.feedback-api-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [analitica.db :as db]
            [analitica.feedback :as fb]
            [analitica.feedback.notify :as notify]
            [analitica.web.api.feedback :as api]))

(use-fixtures :each
  (fn [t]
    (let [tmp  (java.io.File/createTempFile "fbapi-" ".db")
          root (java.nio.file.Files/createTempDirectory "fbapi-root" (make-array java.nio.file.attribute.FileAttribute 0))]
      (with-redefs [db/db-spec {:dbtype "sqlite" :dbname (.getAbsolutePath tmp)}
                    fb/storage-root (constantly (.toString root))
                    notify/notify-async! (fn [_] nil)]
        (db/init!) (t))
      (.delete tmp))))

(defn- png-part []
  (let [f (java.io.File/createTempFile "png-" ".png")] (spit f "12345")
    {:filename "p.png" :content-type "image/png" :size 5 :tempfile f}))

(deftest submit-requires-message
  (let [resp (api/submit {:multipart-params {"message" "  "}})]
    (is (= 400 (:status resp)))))

(deftest submit-stores-and-returns-201
  (let [resp (api/submit {:multipart-params {"message" "broken" "kind" "bug"
                                             "page_url" "/app/pulse"
                                             "attachments" (png-part)}})]
    (is (= 201 (:status resp)))
    (is (pos? (get-in resp [:body :id])))
    (is (= 1 (get-in resp [:body :attachments])))))

(deftest list-omits-stored-path
  (api/submit {:multipart-params {"message" "x" "attachments" (png-part)}})
  (let [resp (api/list-recent {:params {}})
        att  (first (:attachments (first (get-in resp [:body :feedback]))))]
    (is (= 200 (:status resp)))
    (is (contains? att :filename))
    (is (not (contains? att :stored-path)))))

(deftest bad-type-415
  (let [resp (api/submit {:multipart-params {"message" "x"
                                             "attachments" {:filename "e.exe" :content-type "application/x-msdownload"
                                                            :size 3 :tempfile (doto (java.io.File/createTempFile "exe-" ".exe") (spit "abc"))}}})]
    (is (= 415 (:status resp)))))

;; === I2: GET limit param must be honoured ===

(deftest list-honors-limit-param
  ;; Insert 3 feedback rows then request limit=2; must get at most 2 back.
  (doseq [i (range 3)]
    (api/submit {:multipart-params {"message" (str "msg-" i) "kind" "bug"}}))
  (let [resp (api/list-recent {:params {:limit "2"}})
        rows (get-in resp [:body :feedback])]
    (is (= 200 (:status resp)))
    (is (<= (count rows) 2) "limit=2 must return at most 2 rows")))
