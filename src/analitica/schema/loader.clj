(ns analitica.schema.loader
  "Loads endpoint contracts from EDN files under resources/schemas/.
   Each EDN file contains one contract map; see
   specs/001-openapi-schemas/contracts/edn-format.md for the expected shape."
  (:require [analitica.schema.registry :as reg]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m])
  (:import [java.io File]
           [java.nio.file Files Paths]))

;; ---------------------------------------------------------------------------
;; EDN-file shape validation (meta-schema)
;; ---------------------------------------------------------------------------

(def ^:private source-kind-set
  #{:upstream-openapi :manual :inferred})

(def ^:private marketplace-set
  #{:wb :ozon :ym})

(def ^:private method-set
  #{:get :post :put :delete :patch})

(def ^:private file-meta-schema
  "Structural invariants for a loaded EDN contract. Does NOT verify that
   :contract/response-schema is itself a valid Malli schema — that's done
   separately because Malli's meta-validator would be too strict about
   our schema format."
  [:map
   [:endpoint/id          :keyword]
   [:endpoint/marketplace [:enum :wb :ozon :ym]]
   [:endpoint/api-path    :string]
   [:endpoint/method      [:enum :get :post :put :delete :patch]]
   [:endpoint/description {:optional true} [:maybe :string]]
   [:contract/source
    [:map
     [:kind         [:enum :upstream-openapi :manual :inferred]]
     [:generated-at :string]
     [:url          {:optional true} :string]
     [:path         {:optional true} :string]
     [:author       {:optional true} :string]
     [:reference-url {:optional true} :string]
     [:notes        {:optional true} :string]
     [:sample-count {:optional true} :int]
     [:source-raw-ids {:optional true} [:vector :int]]
     [:generator-version {:optional true} :string]]]
   [:contract/response-schema :any]
   [:contract/notes   {:optional true} [:maybe :string]]
   [:contract/version :int]])

(defn- assert-response-schema-is-malli!
  "Attempt to compile :contract/response-schema via Malli. Throws ex-info
   with a useful message on failure. Contract is identified by :endpoint/id
   in the error data for traceability."
  [contract]
  (let [id (:endpoint/id contract)
        schema (:contract/response-schema contract)]
    (try
      (m/schema schema)
      (catch Throwable t
        (throw (ex-info (str "Invalid Malli schema in " id ": " (.getMessage t))
                        {:endpoint-id id
                         :schema      schema
                         :cause-class (.getName (class t))}))))))

(defn- assert-source-invariants! [{:keys [endpoint/id contract/source] :as _contract}]
  (case (:kind source)
    :upstream-openapi
    (when-not (and (:url source) (:path source))
      (throw (ex-info (str "upstream-openapi source missing :url or :path in " id)
                      {:endpoint-id id :source source})))
    :inferred
    (when-not (pos? (or (:sample-count source) 0))
      (throw (ex-info (str "inferred source requires :sample-count > 0 in " id)
                      {:endpoint-id id :source source})))
    :manual nil))

(defn validate-contract!
  "Validate the shape of a loaded contract map. Throws ex-info on any
   violation. Returns the (unchanged) contract on success."
  [contract]
  (when-not (m/validate file-meta-schema contract)
    (throw (ex-info "Contract file does not match meta-schema"
                    {:endpoint-id (:endpoint/id contract)
                     :errors      (m/explain file-meta-schema contract)})))
  (assert-source-invariants! contract)
  (assert-response-schema-is-malli! contract)
  contract)

;; ---------------------------------------------------------------------------
;; Loading
;; ---------------------------------------------------------------------------

(defn load-edn-file
  "Read and validate a single EDN contract file. Returns the validated
   contract map. Accepts either a File, a Path, or a String path."
  [file-or-path]
  (let [path (cond
               (instance? File file-or-path) (.getAbsolutePath ^File file-or-path)
               :else (str file-or-path))
        raw  (try
               (-> path slurp edn/read-string)
               (catch Throwable t
                 (throw (ex-info (str "Failed to read EDN file " path ": " (.getMessage t))
                                 {:file path :cause-class (.getName (class t))}))))]
    (when-not (map? raw)
      (throw (ex-info (str "Contract file must contain one map, got " (type raw))
                      {:file path})))
    (validate-contract! raw)))

(defn- classpath-resource-dir
  "Locate the directory on the classpath corresponding to `resource-path`
   (e.g. \"schemas\"). Returns a File, or nil if not found. Only supports
   directory resources on the filesystem (not jars) — sufficient for dev
   and uberjar layouts where `resources/` is extracted."
  [resource-path]
  (when-let [url (io/resource resource-path)]
    (let [f (File. (.toURI url))]
      (when (.isDirectory f) f))))

(defn discover-schema-files
  "Walk the classpath resource directory `schemas/` and return a sorted
   vector of File objects for every `*.edn` descendant."
  []
  (if-let [root (classpath-resource-dir "schemas")]
    (->> (file-seq root)
         (filter #(and (.isFile ^File %)
                       (str/ends-with? (.getName ^File %) ".edn")))
         (sort-by #(.getAbsolutePath ^File %))
         vec)
    []))

(defn load-all!
  "Discover every `resources/schemas/**/*.edn`, validate each, register in
   `analitica.schema.registry`. Returns a summary
   `{:loaded N :files [path …] :errors [{:file … :message …} …]}`.

   Errors do not abort the load — a bad file logs a warning and the rest
   of the registry still initialises. The rationale is that one typo in a
   schema file shouldn't prevent the whole system from running with
   partial validation coverage."
  []
  (let [files    (discover-schema-files)
        results  (reduce
                   (fn [acc file]
                     (try
                       (let [contract (load-edn-file file)]
                         (reg/register! contract)
                         (update acc :loaded conj (.getAbsolutePath ^File file)))
                       (catch Throwable t
                         (update acc :errors conj
                                 {:file    (.getAbsolutePath ^File file)
                                  :message (.getMessage t)}))))
                   {:loaded [] :errors []}
                   files)]
    {:loaded (count (:loaded results))
     :files  (:loaded results)
     :errors (:errors results)}))
