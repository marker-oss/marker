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

;; === C1: path-traversal / arbitrary file write ===

(deftest path-traversal-attachment-stays-inside-root
  ;; A filename with directory components MUST NOT escape the per-id directory.
  (let [att {:filename "../../../../tmp/evil.png"
             :content-type "image/png" :size 5 :tempfile (tmpfile "12345")}
        res (fb/create! {:kind "bug" :message "traversal-test"
                         :attachments [att]})]
    (is (pos? (:id res)))
    (let [row  (first (fb/list-recent 10))
          sp   (:stored-path (first (:attachments row)))
          root (java.io.File. *root*)]
      ;; The stored file must exist
      (is (.exists (io/file sp)) "stored file must exist on disk")
      ;; Its canonical path must be inside the storage root
      (is (.startsWith (.getCanonicalPath (io/file sp))
                       (.getCanonicalPath root))
          "stored path must be contained within storage root")
      ;; Nothing must have been written to /tmp with the evil name
      (is (not (.exists (io/file "/tmp/evil.png")))
          "file must NOT escape to /tmp/evil.png"))))

(deftest path-traversal-original-filename-preserved-in-db
  ;; DB metadata must record the ORIGINAL client filename, not the safe name.
  (let [original "../../../../tmp/evil.png"
        att {:filename original :content-type "image/png"
             :size 5 :tempfile (tmpfile "12345")}
        _res (fb/create! {:kind "bug" :message "meta-test"
                          :attachments [att]})]
    (let [row (first (fb/list-recent 10))
          db-filename (:filename (first (:attachments row)))]
      (is (= original db-filename)
          "DB filename column must preserve the original client-supplied name"))))

;; === M1: by-id returns row + attachments; nil for missing id ===

(deftest by-id-returns-row-with-attachments
  (let [att {:filename "shot.png" :content-type "image/png" :size 5 :tempfile (tmpfile "12345")}
        res (fb/create! {:kind "bug" :message "by-id test"
                         :attachments [att]})
        id  (:id res)
        row (fb/by-id id)]
    (is (some? row) "must return the row")
    (is (= id (:id row)))
    (is (= "by-id test" (:message row)))
    (is (= 1 (count (:attachments row))))
    (is (= "shot.png" (:filename (first (:attachments row)))))))

(deftest by-id-returns-nil-for-missing
  (is (nil? (fb/by-id 999999)) "must return nil for unknown id"))
