(ns analitica.feedback
  "Stores in-app user feedback (bug/idea/question) + attachments. Attachment
   files live on disk under storage-root/<feedback-id>/; the DB holds metadata."
  (:require [analitica.db :as db]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def max-file-bytes (* 10 1024 1024))

(def ^:private allowed-types
  #{"image/png" "image/jpeg" "image/webp" "image/gif" "application/pdf"
    "application/msword"
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "application/vnd.ms-excel"
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})

(defn allowed-type? [content-type]
  (contains? allowed-types content-type))

(defn storage-root []
  (or (System/getenv "ANALITICA_FEEDBACK_DIR") "data/feedback"))

(defn- validate-attachment! [{:keys [content-type size filename]}]
  (when-not (allowed-type? content-type)
    (throw (ex-info (str "Attachment type not allowed: " content-type " (" filename ")")
                    {:type :bad-attachment :reason :type :content-type content-type})))
  (when (> (or size 0) max-file-bytes)
    (throw (ex-info (str "Attachment too large: " filename)
                    {:type :bad-attachment :reason :size :size size}))))

(defn create!
  [{:keys [kind message page-url user-agent app-context attachments]}]
  (doseq [a attachments] (validate-attachment! a))
  (let [id (jdbc/with-transaction [tx (db/ds)]
              (jdbc/execute! tx ["INSERT INTO feedback (created_at, kind, message, page_url, user_agent, app_context, status)
                                  VALUES (datetime('now'), ?, ?, ?, ?, ?, 'new')"
                                 kind message page-url user-agent app-context])
              (-> (jdbc/execute! tx ["SELECT last_insert_rowid() AS id"]
                                 {:builder-fn rs/as-unqualified-kebab-maps})
                  first :id))
        dir (io/file (storage-root) (str id))]
    (doseq [{:keys [filename content-type size tempfile]} attachments]
      (.mkdirs dir)
      (let [dest (io/file dir filename)]
        (io/copy tempfile dest)
        (db/execute! ["INSERT INTO feedback_attachments (feedback_id, filename, content_type, size, stored_path)
                       VALUES (?, ?, ?, ?, ?)"
                      id filename content-type size (.getAbsolutePath dest)])))
    {:id id :attachments (count attachments)}))

(defn list-recent [limit]
  (let [n    (max 1 (min 200 (or limit 50)))
        rows (db/query ["SELECT id, created_at, kind, message, page_url, user_agent, app_context, status
                         FROM feedback ORDER BY id DESC LIMIT ?" n])
        atts (when (seq rows)
               (group-by :feedback-id
                 (db/query ["SELECT feedback_id, filename, content_type, size, stored_path
                             FROM feedback_attachments"])))]
    (mapv (fn [r] (assoc r :attachments (vec (get atts (:id r) [])))) rows)))
