(ns analitica.schema.openapi
  "Minimal OpenAPI 3 → Malli converter covering the subset used by the
   marketplaces we integrate (WB, YM). Handles: primitive types, objects
   with properties, arrays with items, required fields, nullable, enums,
   and $ref resolution. Unsupported constructs (oneOf/anyOf/allOf,
   discriminator) return a fallback schema and are flagged so the
   regenerate command can report them."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [jsonista.core :as j]))

;; Mutable warning collector used during a single parse traversal.
;; Dynamic so callers can drain it after `->malli` returns.
(def ^:dynamic ^:private *warnings*
  (atom []))

(defn- warn!
  "Record an unsupported-construct warning. Keeps the parse going but
   surfaces issues in the result map so the regenerate CLI can report them."
  [context detail]
  (swap! *warnings* conj {:context context :detail detail}))

(defn- resolve-ref
  "Resolve a JSON-Pointer style `$ref` within `root-spec`. Only the
   `#/components/schemas/Name` / `#/definitions/Name` patterns are
   supported — adequate for WB/YM specs."
  [root-spec ref-str]
  (let [path (->> (str/split (str/replace ref-str #"^#/" "") #"/")
                  (map (fn [seg]
                         (cond
                           (re-matches #"\d+" seg) (Long/parseLong seg)
                           :else                   (keyword seg)))))]
    (get-in root-spec path)))

(declare openapi->malli*)

(defn- primitive->malli [openapi-type openapi-format]
  (case openapi-type
    "integer" :int
    "number"  (if (= openapi-format "double") :double 'number?)
    "string"  :string
    "boolean" :boolean
    :any))

(defn- object->malli
  [{:keys [properties required nullable] :as obj} root-spec]
  (if (empty? properties)
    ;; Object with no declared properties: behaves like an open map.
    :map
    (let [required-set (set (map keyword required))
          entries (for [[prop-key prop-schema] properties
                        :let [k (if (keyword? prop-key) prop-key (keyword prop-key))
                              required? (contains? required-set k)
                              child (openapi->malli* prop-schema root-spec)]]
                    (if required?
                      [k child]
                      [k {:optional true} child]))
          base-map (into [:map] entries)]
      (if nullable [:maybe base-map] base-map))))

(defn- array->malli
  [{:keys [items nullable]} root-spec]
  (let [item-schema (if items (openapi->malli* items root-spec) :any)
        base [:sequential item-schema]]
    (if nullable [:maybe base] base)))

(defn- enum->malli [{:keys [enum nullable]}]
  (let [base (into [:enum] enum)]
    (if nullable [:maybe base] base)))

(defn- openapi->malli*
  "Recursive core of the converter. `spec` is the current node;
   `root-spec` is the full spec (for $ref resolution)."
  [spec root-spec]
  (cond
    (nil? spec)
    :any

    (contains? spec :$ref)
    (if-let [resolved (resolve-ref root-spec (:$ref spec))]
      (openapi->malli* resolved root-spec)
      (do (warn! :unresolved-ref (:$ref spec)) :any))

    (some spec [:oneOf :anyOf :allOf])
    (do (warn! :unsupported-composition
               {:kind (first (filter #(contains? spec %) [:oneOf :anyOf :allOf]))})
        :any)

    (:enum spec)
    (enum->malli spec)

    (= "array" (:type spec))
    (array->malli spec root-spec)

    (= "object" (:type spec))
    (object->malli spec root-spec)

    (:type spec)
    (let [base (primitive->malli (:type spec) (:format spec))]
      (if (:nullable spec) [:maybe base] base))

    :else
    (do (warn! :untyped-schema (select-keys spec [:description :example])) :any)))

(defn ->malli
  "Convert an OpenAPI schema node (Clojure map) to a Malli schema.
   `root-spec` is the full OpenAPI document, used for `$ref` resolution
   (pass the same map if you're parsing a fragment).

   Returns `{:schema <malli> :warnings [{:context :kw :detail …} …]}` so
   callers can report unsupported constructs without abandoning the
   successfully-parsed portion."
  ([spec] (->malli spec spec))
  ([spec root-spec]
   (binding [*warnings* (atom [])]
     (let [schema (openapi->malli* spec root-spec)]
       {:schema   schema
        :warnings @*warnings*}))))

;; ---------------------------------------------------------------------------
;; Loading upstream specs (YAML or JSON)
;; ---------------------------------------------------------------------------

(defn parse-spec
  "Parse an OpenAPI document from a string. Attempts JSON first (cheap
   check), falls back to YAML (slower but more forgiving). Returns the
   parsed map with keyword keys."
  [text]
  (try
    (j/read-value text (j/object-mapper {:decode-key-fn true}))
    (catch Exception _
      (yaml/parse-string text :keywords true))))

(defn spec-at-path
  "Walk `spec` along a JSON-Pointer-ish path string like
   \"$.paths./x.get.responses.200.content.application/json.schema\".
   Every segment is looked up as a keyword — parsed OpenAPI maps have
   keyword keys even for HTTP status codes like `:200`. MVP-grade path
   resolver; doesn't need the full JSON-Path grammar."
  [spec path-str]
  (let [clean (-> path-str
                  (str/replace #"^\$\." "")
                  (str/replace #"^\$" ""))
        segs  (str/split clean #"\.")]
    (reduce
     (fn [node seg]
       (when node
         (get node (keyword seg))))
     spec
     segs)))
