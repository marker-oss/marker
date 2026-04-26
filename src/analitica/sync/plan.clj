(ns analitica.sync.plan
  "V4 Sync Plan Generator — Phase 3/5.
   Pure planning logic (no I/O except persist-plan! which writes to the registry).
   Produces a vector of task descriptors in topological order (ingest before materialize).

   Phase 5: WB storage ingest is expanded into weekly-chunked sub-tasks so that
   progress is visible per chunk; materialize depends on ALL chunk tasks."
  (:require [analitica.sync.registry :as registry]
            [analitica.util.time :as time]
            [analitica.ingest :as ingest]
            [analitica.materialize :as materialize]))

;; ---------------------------------------------------------------------------
;; Compatibility matrix
;; ---------------------------------------------------------------------------

;; Which (marketplace, entity-type) pairs are valid.
;; Based on ingest!/materialize! source analysis:
;;   WB:   sales, orders, finance, stocks, prices, stats, storage, regions
;;   Ozon: sales, orders, finance, stocks, prices, stats, cashflow
;;   YM:   sales, orders, finance, stocks, prices, stats
(def ^:private mp-entity-matrix
  {:wb   #{:sales :orders :finance :stocks :prices :stats :storage :regions :ad_stats}
   :ozon #{:sales :orders :finance :stocks :prices :stats :cashflow}
   :ym   #{:sales :orders :finance :stocks :prices :stats}})

(def ^:private all-marketplaces [:wb :ozon :ym])

;; All entity types that appear in any marketplace
(def ^:private all-entity-types
  (into #{} (mapcat val mp-entity-matrix)))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- compatible?
  "Returns true when (mp, entity-type) is a valid combination."
  [mp entity-type]
  (contains? (get mp-entity-matrix mp #{}) entity-type))

(defn- task-id
  "Build canonical task ID string: '<run-id>/<mp>/<entity>/<phase>'.

   The run-id prefix is what makes IDs unique across runs — without it
   the second invocation hits a sync_tasks PRIMARY KEY collision."
  [run-id mp entity-type phase]
  (str run-id "/" (name mp) "/" (name entity-type) "/" (name phase)))

;; Per-(mp, entity-type) chunk size (days). nil = no chunking. Picked from
;; observed API behaviour:
;;
;;   wb/storage  — 7  : weekly report splits, async pipeline already exists
;;   wb/finance  — 30 : full-period requests scale linearly (241s on 7 days
;;                      observed; chunking caps single-task duration ~80s)
;;   wb/regions  — 30 : the regions endpoint returns 400 on >30-day windows
;;                      (observed 2026-04-25 on a 84-day request)
;;   ym/finance  — 30 : /stats/orders payload grows linearly with the
;;                      window; 30 days keeps individual responses bounded
;;
;; Other endpoints either snapshot (stocks/prices), are already chunked
;; internally (ozon/postings, ozon/transactions, ozon/realization), or are
;; small enough that chunking adds overhead without benefit.
(def ^:private chunk-days
  {[:wb :storage]   7
   [:wb :finance]   30
   [:wb :regions]   30
   [:wb :ad_stats]  30   ;; WB /adv/v2/fullstats caps a single body at 31 days
   [:ym :finance]   30})

(defn chunk-spec
  "Return the set of [chunk-from chunk-to] period pairs for one (mp, entity-type).

   Chunking strategy is data-driven via the `chunk-days` table above. When
   no entry exists for a pair, returns a single-element vector
   [[period-from period-to]] (no chunking)."
  [mp entity-type period-from period-to]
  (if-let [days (get chunk-days [mp entity-type])]
    (time/date-chunks period-from period-to days)
    [[period-from period-to]]))

(defn- make-task-group
  "Return a flat vector of task descriptors for one (mp, entity-type) pair.

   For most pairs: [ingest-descriptor materialize-descriptor] (unchanged from Phase 3).
   For WB storage: [chunk-ingest-1 ... chunk-ingest-N materialize-descriptor]
     where the materialize task depends on ALL chunk-ingest IDs."
  [run-id mp entity-type period-from period-to]
  (let [chunks      (chunk-spec mp entity-type period-from period-to)
        mat-id      (task-id run-id mp entity-type :materialize)
        mat-period  [period-from period-to]
        ;; Build one ingest descriptor per chunk
        ingest-tasks
        (mapv (fn [[chunk-from chunk-to]]
                (let [chunked? (> (count chunks) 1)
                      ;; Chunked tasks get a sub-id; single tasks keep the plain id
                      id       (if chunked?
                                 (str (task-id run-id mp entity-type :ingest)
                                      "/" chunk-from "_to_" chunk-to)
                                 (task-id run-id mp entity-type :ingest))
                      period-vec [chunk-from chunk-to]]
                  (cond-> {:id           id
                           :run-id       run-id
                           :marketplace  mp
                           :entity-type  entity-type
                           :phase        :ingest
                           :max-attempts 3
                           :period-from  chunk-from
                           :period-to    chunk-to
                           :depends-on   []
                           :thunk        #(or (ingest/ingest! entity-type
                                                              :marketplace mp
                                                              :period period-vec)
                                              0)}
                    chunked? (assoc :chunk (str chunk-from "_to_" chunk-to)))))
              chunks)
        ingest-ids  (mapv :id ingest-tasks)
        mat-task    {:id           mat-id
                     :run-id       run-id
                     :marketplace  mp
                     :entity-type  entity-type
                     :phase        :materialize
                     :max-attempts 1
                     :period-from  period-from
                     :period-to    period-to
                     :depends-on   ingest-ids
                     :thunk        #(or (materialize/materialize! entity-type
                                                                  :marketplace mp
                                                                  :period mat-period)
                                        0)}]
    (conj ingest-tasks mat-task)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn expand-plan
  "Generate a vector of task descriptors for one user-triggered run.

   Args (kwargs):
     :run-id      — string, generated by caller (e.g. UUID-string)
     :what        — :all | :sales | :orders | :finance | :stocks | :prices |
                    :storage | :stats | :regions | :cashflow | ...
     :marketplace — :all | :wb | :ozon | :ym | nil (defaults :wb if not :all)
     :period      — keyword like :last-30-days, OR a [from to] vector,
                    OR a {:from :to} map. Stored as :period-from/:period-to
                    on each task row.

   Returns: vector of task descriptors in topological order (all ingest first,
   then all materialize). Each descriptor:
     {:id           string  e.g. \"wb/sales/ingest\"
      :run-id       string
      :marketplace  keyword
      :entity-type  keyword
      :phase        :ingest | :materialize
      :period-from  iso-date string
      :period-to    iso-date string
      :depends-on   [task-id ...]
      :thunk        () → items-count}"
  [& {:keys [run-id what marketplace period]
      :or   {period :last-30-days}}]
  (let [;; Resolve period to [from to] strings
        [period-from period-to] (time/resolve-period (or period :last-30-days))
        ;; Determine which marketplaces to fan out to
        mps   (if (or (nil? marketplace) (= marketplace :all))
                all-marketplaces
                [marketplace])
        ;; Determine which entity types to expand
        types (if (or (nil? what) (= what :all))
                (vec all-entity-types)
                [what])
        ;; Filter to valid (mp, type) combinations
        pairs (for [mp    mps
                    etype types
                    :when (compatible? mp etype)]
                [mp etype])
        ;; Build task groups — ingest first (possibly chunked), materialize second
        all-tasks (mapcat (fn [[mp etype]]
                            (make-task-group run-id mp etype period-from period-to))
                          pairs)
        ;; Re-sort: all ingest tasks first, then all materialize tasks
        ;; This ensures topological order across the full plan vector.
        {ingest-tasks    :ingest
         materialize-tasks :materialize}
        (group-by :phase all-tasks)]
    (vec (concat ingest-tasks materialize-tasks))))

(defn persist-plan!
  "Insert each task descriptor into sync_tasks via registry/create-task!.
   Returns the input plan unchanged (caller still has the :thunk values it needs)."
  [plan]
  (doseq [task plan]
    (registry/create-task!
      {:id           (:id task)
       :run-id       (:run-id task)
       :marketplace  (when (:marketplace task) (name (:marketplace task)))
       :entity-type  (when (:entity-type task) (name (:entity-type task)))
       :phase        (when (:phase task) (name (:phase task)))
       :max-attempts (:max-attempts task)
       :period-from  (:period-from task)
       :period-to    (:period-to task)
       :depends-on   (seq (:depends-on task))}))
  plan)

(defn build-thunk-for-row
  "Reconstruct the runtime thunk for a sync_tasks row. Used by the manual
   retry endpoint to re-execute a single task without re-running the
   whole plan.

   Returns a 0-arity fn returning items count, or throws if the
   (mp, entity-type, phase) tuple isn't recognised.

   Row keys come from next.jdbc in kebab-case; values may be strings
   (\"wb\", \"sales\", \"ingest\") rather than keywords — we convert with
   keyword before dispatch."
  [{:keys [marketplace entity-type phase period-from period-to]}]
  (let [mp          (keyword marketplace)
        etype       (keyword entity-type)
        ph          (keyword phase)
        period-vec  [period-from period-to]]
    (case ph
      :ingest
      #(or (ingest/ingest! etype
                           :marketplace mp
                           :period period-vec)
           0)

      :materialize
      #(or (materialize/materialize! etype
                                     :marketplace mp
                                     :period period-vec)
           0)

      (throw (ex-info "Unknown phase — cannot build thunk"
                      {:phase phase :marketplace marketplace :entity-type entity-type})))))
