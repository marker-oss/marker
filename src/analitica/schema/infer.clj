(ns analitica.schema.infer
  "Derive a candidate Malli schema from observed raw_data samples using
   malli.provider. The inferred schema is NOT registered automatically —
   it is printed (or written to a file) for the developer to review,
   promote to :manual source, and commit."
  (:require [analitica.db :as db]
            [malli.provider :as mp]))

(defn- fetch-samples
  "Read the last `n` raw_data payloads for a given marketplace source,
   returning the parsed bodies. Newest first."
  [marketplace n]
  (->> (db/query
         ["SELECT payload FROM raw_data
           WHERE source = ?
           ORDER BY ingested_at DESC
           LIMIT ?"
          (name marketplace) n])
       (mapv #(try (db/parse-json (:payload %))
                   (catch Exception _ nil)))
       (remove nil?)))

(defn infer-schema
  "Infer a Malli schema from the last `n` raw samples for `endpoint-id`.
   The endpoint's marketplace determines which raw_data rows are used.

   Returns a map with :schema (the inferred Malli) and :sample-count."
  [endpoint-id n]
  (let [mp (keyword (namespace endpoint-id))
        samples (fetch-samples mp n)]
    (when (empty? samples)
      (throw (ex-info (str "No raw_data samples for marketplace " mp)
                      {:endpoint-id endpoint-id :marketplace mp})))
    {:schema       (mp/provide samples)
     :sample-count (count samples)}))

(defn infer-contract
  "Build a full Contract map in the EDN format expected by the loader
   (see specs/001-openapi-schemas/contracts/edn-format.md), with
   `:contract/source.kind = :inferred` so the operator knows this is
   a starting point rather than a hand-verified contract."
  [endpoint-id n]
  (let [{:keys [schema sample-count]} (infer-schema endpoint-id n)
        marketplace (keyword (namespace endpoint-id))
        endpoint-slug (name endpoint-id)]
    {:endpoint/id           endpoint-id
     :endpoint/marketplace  marketplace
     :endpoint/api-path     (str "/TODO/" endpoint-slug)
     :endpoint/method       :post
     :endpoint/description  (str "Inferred from " sample-count " samples — update before committing")
     :contract/source       {:kind         :inferred
                             :generated-at (.toString (java.time.Instant/now))
                             :sample-count sample-count
                             :notes        "Run `schema infer` produced this; promote to :manual after review."}
     :contract/response-schema schema
     :contract/notes        "TODO: review field optionality, refine enums, consider [:maybe ...] wrapping."
     :contract/version      1}))
