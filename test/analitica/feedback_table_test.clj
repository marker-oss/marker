(ns analitica.feedback-table-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [analitica.db :as db]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(use-fixtures :once
  (fn [t]
    (let [tmp (java.io.File/createTempFile "fb-table-" ".db")]
      (with-redefs [db/db-spec {:dbtype "sqlite" :dbname (.getAbsolutePath tmp)}]
        (db/init!) (t))
      (.delete tmp))))

(defn- cols [table]
  (->> (jdbc/execute! (db/ds) [(str "PRAGMA table_info(" table ")")]
                      {:builder-fn rs/as-unqualified-maps})
       (map :name) set))

(deftest feedback-tables-exist
  (is (= #{"id" "created_at" "kind" "message" "page_url" "user_agent" "app_context" "status"}
         (cols "feedback")))
  (is (= #{"id" "feedback_id" "filename" "content_type" "size" "stored_path"}
         (cols "feedback_attachments"))))
