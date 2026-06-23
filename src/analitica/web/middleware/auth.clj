(ns analitica.web.middleware.auth
  "X-API-Key auth for mutating API routes. Belt-and-suspenders over Caddy
   basic-auth. Fail-open when no key is configured (logged) so a missing
   config never bricks the pilot; fail-closed when a key IS configured."
  (:require [analitica.config :as config]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]))

(defn constant-time-eq?
  "Length-aware constant-time string comparison."
  [a b]
  (let [a (str a) b (str b)]
    (if (not= (count a) (count b))
      false
      (zero? (reduce (fn [acc i]
                       (bit-or acc (bit-xor (int (.charAt a i)) (int (.charAt b i)))))
                     0 (range (count a)))))))

(defn- mutating? [request]
  (and (contains? #{:post :put :delete} (:request-method request))
       ;; Decode the URI before the prefix check: Jetty may deliver an
       ;; un-decoded :uri (e.g. "/%61pi/...") that Compojure later decodes and
       ;; routes — without decoding here that would bypass auth.
       (let [uri     (or (:uri request) "")
             decoded (try (java.net.URLDecoder/decode uri "UTF-8") (catch Exception _ uri))]
         (str/starts-with? decoded "/api"))))

(defn wrap-api-key [handler]
  (fn [request]
    (if-not (mutating? request)
      (handler request)
      (let [expected (config/api-key)]
        (cond
          (str/blank? (str expected))
          (do (mu/log ::api-key-not-configured :uri (:uri request))
              (handler request))

          (constant-time-eq? (get-in request [:headers "x-api-key"]) expected)
          (handler request)

          :else
          {:status 401 :body {:error "unauthorized"}})))))
