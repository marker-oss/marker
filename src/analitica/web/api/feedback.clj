(ns analitica.web.api.feedback
  "HTTP handlers for in-app feedback. POST is multipart (message + optional
   attachments); GET lists recent feedback (admin)."
  (:require [analitica.feedback :as fb]
            [analitica.feedback.notify :as notify]
            [clojure.string :as str]))

(defn- file-parts
  "All multipart parts that look like uploaded files (value is a map with :tempfile)."
  [multipart]
  (->> (vals multipart)
       (mapcat (fn [v] (cond (and (map? v) (:tempfile v)) [v]
                             (and (sequential? v))         (filter #(and (map? %) (:tempfile %)) v)
                             :else                          nil)))
       vec))

(defn submit [request]
  (let [mp      (:multipart-params request)
        message (get mp "message")
        kind    (or (get mp "kind") "bug")]
    (cond
      (or (nil? message) (str/blank? message))
      {:status 400 :body {:error "message required"}}
      :else
      (try
        (let [atts (file-parts (dissoc mp "message" "kind" "page_url" "user_agent" "app_context"))
              res  (fb/create! {:kind kind :message message
                                :page-url    (get mp "page_url")
                                :user-agent  (or (get mp "user_agent")
                                                 (get-in request [:headers "user-agent"]))
                                :app-context (get mp "app_context")
                                :attachments atts})]
          (notify/notify-async! (fb/by-id (:id res)))
          {:status 201 :body res})
        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type reason]} (ex-data e)]
            (if (= type :bad-attachment)
              {:status (if (= reason :size) 413 415) :body {:error (.getMessage e)}}
              (throw e))))))))

(defn list-recent [request]
  (let [raw  (get-in request [:params :limit])
        n    (try (Integer/parseInt (str raw)) (catch Exception _ 50))
        n    (min n 200)
        rows (->> (fb/list-recent n)
                  (mapv (fn [r]
                          (update r :attachments
                                  (fn [as] (mapv #(select-keys % [:filename :content-type :size]) as))))))]
    {:status 200 :body {:feedback rows}}))
