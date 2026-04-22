(ns analitica.schema.registry
  "In-memory catalog of API endpoint contracts.

   A contract is a plain map with these keys (see
   specs/001-openapi-schemas/data-model.md):

     :endpoint/id          — unique keyword, e.g. :wb/report-detail-by-period
     :endpoint/marketplace — :wb | :ozon | :ym
     :endpoint/api-path    — string (API path)
     :endpoint/method      — :get | :post
     :contract/source      — {:kind … :generated-at …}
     :contract/response-schema — a Malli schema
     :contract/version     — int

   Registry is seeded once at startup via `analitica.schema.loader/load-all!`,
   then treated as immutable by validators. Tests can `clear!` + re-`register!`
   between runs for isolation.")

(defonce ^:private registry (atom {}))

(defn register!
  "Register a contract map keyed by its `:endpoint/id`.
   Replaces any prior registration with the same id. Returns the id."
  [contract]
  (let [id (:endpoint/id contract)]
    (when-not id
      (throw (ex-info "Contract missing :endpoint/id" {:contract contract})))
    (swap! registry assoc id contract)
    id))

(defn lookup
  "Return the contract registered for `endpoint-id`, or nil if not registered."
  [endpoint-id]
  (get @registry endpoint-id))

(defn all-endpoints
  "Return a vector of all registered contracts, sorted by :endpoint/id
   for deterministic iteration."
  []
  (->> (vals @registry)
       (sort-by :endpoint/id)
       vec))

(defn by-marketplace
  "Return a vector of contracts for the given marketplace keyword."
  [marketplace]
  (->> (all-endpoints)
       (filter #(= marketplace (:endpoint/marketplace %)))
       vec))

(defn clear!
  "Remove every registered contract. Primarily for tests that want a clean
   registry state. Returns nil."
  []
  (reset! registry {})
  nil)
