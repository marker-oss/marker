(ns analitica.feedback-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [analitica.db :as db]
            [analitica.feedback :as fb]
            [clojure.java.io :as io]))

(def ^:dynamic *root* nil)

(use-fixtures :each
  (fn [t]
    (let [tmp  (java.io.File/createTempFile "fb-" ".db")
          root (java.nio.file.Files/createTempDirectory "fb-root" (make-array java.nio.file.attribute.FileAttribute 0))]
      (with-redefs [db/db-spec {:dbtype "sqlite" :dbname (.getAbsolutePath tmp)}
                    fb/storage-root (constantly (.toString root))]
        (db/init!)
        (binding [*root* (.toString root)] (t)))
      (.delete tmp))))

(defn- tmpfile [content]
  (let [f (java.io.File/createTempFile "att-" ".png")]
    (spit f content) f))

(deftest create-stores-row-and-attachment
  (let [att {:filename "shot.png" :content-type "image/png" :size 5 :tempfile (tmpfile "12345")}
        res (fb/create! {:kind "bug" :message "broken" :page-url "/app/pulse"
                         :user-agent "test" :app-context "{}" :attachments [att]})]
    (is (pos? (:id res)))
    (is (= 1 (:attachments res)))
    (let [row (first (fb/list-recent 10))]
      (is (= "broken" (:message row)))
      (is (= 1 (count (:attachments row))))
      (is (.exists (io/file (:stored-path (first (:attachments row)))))))))

(deftest rejects-bad-type
  (is (thrown? clojure.lang.ExceptionInfo
        (fb/create! {:kind "bug" :message "x"
                     :attachments [{:filename "e.exe" :content-type "application/x-msdownload"
                                    :size 3 :tempfile (tmpfile "abc")}]}))))

(deftest rejects-oversize
  (is (thrown? clojure.lang.ExceptionInfo
        (fb/create! {:kind "bug" :message "x"
                     :attachments [{:filename "big.png" :content-type "image/png"
                                    :size (inc fb/max-file-bytes) :tempfile (tmpfile "x")}]}))))

(deftest allowed-type
  (is (fb/allowed-type? "image/png"))
  (is (fb/allowed-type? "application/pdf"))
  (is (not (fb/allowed-type? "application/x-msdownload"))))
