(ns analitica.schema.regenerate
  "Regenerate contracts from upstream OpenAPI specs.
   Only contracts with `:contract/source.kind = :upstream-openapi` are
   eligible. The fetched spec is parsed via analitica.schema.openapi,
   diffed against the current in-memory schema, and optionally written
   back to the source EDN file."
  (:require [analitica.schema.openapi :as openapi]
            [analitica.schema.registry :as registry]
            [clojure.pprint :as pp]
            [clojure.set :as set]
            [hato.client :as http]))

(defn fetch-spec
  "HTTP GET the upstream OpenAPI document. Returns the response body
   (string). Separated from `regenerate!` so tests can with-redefs it
   without touching the network."
  [url]
  (:body (http/get url {:timeout 30000})))

(defn- map-schema-keys
  "Return the set of field keys declared at the top of a Malli `[:map …]`
   schema, or at the inner map of a `[:sequential [:map …]]`. Used for
   shallow diff reporting."
  [schema]
  (let [form schema
        inner (cond
                (and (vector? form) (= :map (first form))) form
                (and (vector? form) (= :sequential (first form))
                     (vector? (second form)) (= :map (first (second form))))
                (second form)
                :else nil)]
    (when inner
      (->> (rest inner)
           (remove map?) ;; drop property-options map
           (keep (fn [entry]
                   (cond
                     (keyword? entry) entry
                     (vector? entry)  (first entry)
                     :else            nil)))
           set))))

(defn diff-schemas
  "Shallow diff: compare top-level field keys between old and new Malli
   schemas. Reports added / removed. Deep-type changes are not tracked
   in this MVP — intended as a tripwire for API drift, not a full
   semantic diff."
  [old-schema new-schema]
  (let [old-keys (or (map-schema-keys old-schema) #{})
        new-keys (or (map-schema-keys new-schema) #{})]
    {:added   (into [] (sort (set/difference new-keys old-keys)))
     :removed (into [] (sort (set/difference old-keys new-keys)))
     :common  (into [] (sort (set/intersection old-keys new-keys)))}))

(defn regenerate-contract!
  "Fetch + parse + diff one contract. Returns a map describing the
   outcome. When `apply?` is true AND the diff is non-empty, also
   overwrites the source EDN file (if `file-path` is provided) with
   the new schema and an incremented version."
  [contract {:keys [apply? file-path fetch-fn]
             :or {fetch-fn fetch-spec}}]
  (let [{kind :kind url :url path :path} (:contract/source contract)]
    (cond
      (not= :upstream-openapi kind)
      {:status :skipped
       :endpoint-id (:endpoint/id contract)
       :reason (str "source.kind = " kind " — not auto-generatable")}

      (or (nil? url) (nil? path))
      {:status :error
       :endpoint-id (:endpoint/id contract)
       :reason "upstream source missing :url or :path"}

      :else
      (try
        (let [body (fetch-fn url)
              spec (openapi/parse-spec body)
              node (openapi/spec-at-path spec path)
              _    (when (nil? node)
                     (throw (ex-info (str "no schema at " path) {})))
              {:keys [schema warnings]} (openapi/->malli node spec)
              old-schema (:contract/response-schema contract)
              diff (diff-schemas old-schema schema)
              new-contract (-> contract
                               (assoc :contract/response-schema schema)
                               (update :contract/version inc)
                               (assoc-in [:contract/source :generated-at]
                                         (.toString (java.time.Instant/now))))]
          (when (and apply? file-path
                     (or (seq (:added diff)) (seq (:removed diff))))
            (spit file-path
                  (with-out-str (pp/pprint new-contract))))
          {:status      (cond
                          (seq warnings) :partial
                          (and (empty? (:added diff)) (empty? (:removed diff))) :no-changes
                          :else :changed)
           :endpoint-id (:endpoint/id contract)
           :diff        diff
           :warnings    warnings
           :applied?    (boolean (and apply? file-path
                                      (or (seq (:added diff)) (seq (:removed diff)))))})
        (catch Exception e
          {:status :error
           :endpoint-id (:endpoint/id contract)
           :reason (.getMessage e)})))))

(defn regenerate-all!
  "Regenerate every eligible contract, optionally filtered by marketplace.
   Returns a sequence of outcome maps (one per endpoint considered)."
  [{:keys [marketplace apply? fetch-fn]}]
  (let [targets (cond->> (registry/all-endpoints)
                  marketplace (filter #(= marketplace (:endpoint/marketplace %))))]
    (mapv #(regenerate-contract!
            %
            {:apply?   apply?
             :fetch-fn fetch-fn
             ;; File path is inferred from endpoint-id slug; real CLI
             ;; passes explicit files. For tests we can skip.
             :file-path nil})
          targets)))
