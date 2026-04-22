(ns analitica.audit.fixture
  "Ground-truth fixtures — snapshot + replay PnL/UE calculations (US4).

   Implements FR-007 / SC-004 per:
     - data-model.md §GroundTruthFixture       — on-disk shape
     - contracts/cli-audit.md §audit fixture   — CLI behaviours
     - research.md §R2                          — EDN under specs/.../fixtures/

   Public API:
     `capture-fixture!` — snapshot finance rows + computed PnL/UE for a given
                          period + marketplace; write EDN to
                          `specs/002-calculation-audit/fixtures/<id>.edn`.
     `verify-fixture!`  — replay a captured fixture against the current DB;
                          returns one of `:match | :diff | :sha-mismatch` with
                          a diff payload describing mismatching keys.
     `list-fixtures`    — enumerate fixture files as compact metadata maps
                          (without :fixture/expected, so listing stays fast).

   Determinism:
     `:fixture/sha256-of-rows` hashes the sorted-by-PK list of primary keys of
     finance rows in scope. Same DB state → identical hash. Used by
     `verify-fixture!` to detect that the database drifted away from the
     snapshot and regressions would produce meaningless diffs.

   Tolerance (floating-point friendly comparison):
     Expected numerical values are compared within (`abs` 0.01) or
     (`rel` 1e-4), matching the 2-decimal rounding applied in
     `domain.pnl/calculate` + `domain.unit-economics/calculate`."
  (:require [analitica.db :as db]
            [analitica.domain.finance :as finance]
            [analitica.domain.pnl :as pnl]
            [analitica.domain.unit-economics :as ue]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp])
  (:import [java.security MessageDigest]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(def ^:const default-fixtures-dir
  "Canonical location for ground-truth fixture EDN files (research §R2)."
  "specs/002-calculation-audit/fixtures")

(def ^:private value-match-tolerance
  "Tolerance used when comparing expected vs actual numeric values.
   Matches the 2-decimal rounding applied throughout the domain layer so
   floating-point jitter does not produce false diffs."
  {:abs 0.01 :rel 1.0e-4})

;; ---------------------------------------------------------------------------
;; SHA-256 of finance rows
;; ---------------------------------------------------------------------------

(defn- sha256-hex
  "Hex-encoded SHA-256 of a UTF-8 string."
  [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes s "UTF-8"))]
    (->> bs
         (map #(format "%02x" %))
         (apply str))))

(defn- row-pk-string
  "Serialize one finance row's primary key to a stable string.

   Finance PK is (marketplace, rrd_id); we also include date_from to
   disambiguate historical rows that were rrd_id-reused by WB."
  [row]
  (str (or (:marketplace row) "")
       "|"
       (or (:rrd-id row) (:rrd_id row) "")
       "|"
       (or (:date-from row) (:date_from row) "")))

(defn- compute-sha256-of-rows
  "Deterministic hash of finance-rows by PK alone (not payload).

   Sorting is explicit and stable; two runs with the same DB state produce
   the same digest regardless of query-result ordering."
  [finance-rows]
  (->> finance-rows
       (map row-pk-string)
       sort
       (clojure.string/join "\n")
       sha256-hex))

;; ---------------------------------------------------------------------------
;; Snapshot (read-side)
;; ---------------------------------------------------------------------------

(defn- fetch-finance-rows
  "Fetch finance rows scoped to marketplace + period from the DB."
  [marketplace {:keys [from to]}]
  (finance/fetch-finance {:from from :to to}
                         :marketplace marketplace
                         :source :db))

(defn- compute-pnl
  "Pure P&L calculation for the given finance rows + marketplace."
  [finance-rows marketplace]
  (pnl/calculate finance-rows :marketplace marketplace))

(defn- compute-unit-economics
  "Compute unit-economics keyed by article.

   Returns `{\"ARTICLE-X\" {:sales-qty N :revenue N :profit N :margin-pct N ...}}`.
   Only a stable subset of fields is retained — enough to detect formula
   regressions without being noisy about every per-unit division."
  [finance-rows]
  (let [rows (ue/calculate finance-rows)]
    (->> rows
         (map (fn [r]
                [(or (:article r) "")
                 (select-keys r [:sales-qty :returns-qty :revenue
                                 :for-pay :total-cost :profit
                                 :margin-pct :wb-cost-pct :cogs-pct])]))
         (filter (fn [[art _]] (and art (seq art))))
         (into (sorted-map)))))

(defn- sales-qty-total
  "Sum of finance sales quantity (operation=\"sale\" or \"Продажа\") for
   triangulation-friendly summary. Kept minimal (total only) — per-article
   counts live inside :unit-economics."
  [finance-rows]
  (reduce + 0
          (keep (fn [r]
                  (when (#{"sale" "Продажа"} (:operation r))
                    (or (:quantity r) 0)))
                finance-rows)))

;; ---------------------------------------------------------------------------
;; EDN I/O
;; ---------------------------------------------------------------------------

(defn- fixture-path
  "Absolute-or-relative path where fixture `id` is stored inside `dir`."
  [dir id]
  (str dir java.io.File/separator id ".edn"))

(defn- write-fixture-edn!
  "Serialise `fixture-map` to `path` using `clojure.pprint/pprint` for
   human-readability (git-friendly)."
  [fixture-map path]
  (io/make-parents path)
  (with-open [w (io/writer path)]
    (binding [*out* w
              pp/*print-right-margin* 100]
      (pp/pprint fixture-map))))

(defn- read-fixture-edn
  "Read and return the fixture map at `path`, or nil when file is absent."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (edn/read r)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- now-iso [] (str (Instant/now)))

(defn capture-fixture!
  "Snapshot finance rows + computed PnL/UE for the given marketplace+period.

   Required options:
     :id          — string, kebab-case fixture id, e.g. \"wb-2026-03\"
     :marketplace — keyword (:wb, :ozon, :ym)
     :period      — {:from \"YYYY-MM-DD\" :to \"YYYY-MM-DD\"}

   Optional:
     :from-report   — report-id string to attach under :fixture/source
     :fixtures-dir  — override storage directory
                       (default: `default-fixtures-dir`)
     :captured-by   — operator name (default: $USER env var)
     :notes         — free-form operator narrative

   Returns the written fixture map. Side-effect: writes EDN file at
   `<fixtures-dir>/<id>.edn`."
  [{:keys [id marketplace period from-report fixtures-dir captured-by notes]
    :or   {fixtures-dir default-fixtures-dir}}]
  (when-not (and (string? id) (seq id))
    (throw (ex-info "fixture :id required (non-empty string)" {:id id})))
  (when-not (keyword? marketplace)
    (throw (ex-info ":marketplace required (keyword)" {:marketplace marketplace})))
  (when-not (and (map? period) (:from period) (:to period))
    (throw (ex-info ":period required {:from ... :to ...}" {:period period})))
  (let [finance-rows (fetch-finance-rows marketplace period)
        pnl-map      (compute-pnl finance-rows marketplace)
        ue-map       (compute-unit-economics finance-rows)
        sales-qty    (sales-qty-total finance-rows)
        fixture
        (cond-> {:fixture/id            id
                 :fixture/marketplace   marketplace
                 :fixture/period        period
                 :fixture/captured-at   (now-iso)
                 :fixture/source        (if from-report
                                          {:report-id from-report}
                                          {:report-id :direct})
                 :fixture/sha256-of-rows (compute-sha256-of-rows finance-rows)
                 :fixture/row-count     (count finance-rows)
                 :fixture/expected
                 {:pnl            pnl-map
                  :unit-economics ue-map
                  :sales-qty      {:total sales-qty}}}
          captured-by (assoc :fixture/captured-by captured-by)
          notes       (assoc :fixture/notes notes))
        path (fixture-path fixtures-dir id)]
    (write-fixture-edn! fixture path)
    fixture))

;; ---------------------------------------------------------------------------
;; verify-fixture!
;; ---------------------------------------------------------------------------

(defn- within-tolerance?
  "Return true when `a` and `b` are close enough to be considered equal for
   regression purposes. Handles integers, doubles, and nil."
  [a b]
  (cond
    (and (nil? a) (nil? b)) true
    (or (nil? a) (nil? b))  false
    (and (number? a) (number? b))
    (let [da   (double a)
          db   (double b)
          diff (Math/abs (- da db))
          mag  (max (Math/abs da) (Math/abs db) 1.0)]
      (or (<= diff (:abs value-match-tolerance))
          (<= (/ diff mag) (:rel value-match-tolerance))))
    :else (= a b)))

(defn- diff-map
  "Return a map of keys where `expected` and `actual` disagree outside
   tolerance. Each entry is `{key {:expected v1 :actual v2}}`. Keys in
   `expected` that are missing from `actual` surface as :actual nil."
  [expected actual]
  (reduce
    (fn [acc [k v-exp]]
      (let [v-act (get actual k)]
        (if (within-tolerance? v-exp v-act)
          acc
          (assoc acc k {:expected v-exp :actual v-act}))))
    {}
    expected))

(defn- diff-unit-economics
  "Diff per-article UE maps. Returns a map of article → {missing-keys} OR
   {key → {:expected :actual}}."
  [expected-ue actual-ue]
  (reduce
    (fn [acc [art v-exp]]
      (let [v-act (get actual-ue art)]
        (cond
          (nil? v-act)
          (assoc acc art :missing-in-current-db)

          :else
          (let [d (diff-map v-exp v-act)]
            (if (seq d)
              (assoc acc art d)
              acc)))))
    {}
    expected-ue))

(defn verify-fixture!
  "Replay fixture against current DB and return a verdict map.

   Returns one of:
     {:verdict :match  :fixture-id id}
     {:verdict :diff   :fixture-id id :pnl-diff {...} :ue-diff {...} :sales-qty-diff {...}}
     {:verdict :sha-mismatch :fixture-id id
                              :fixture-sha <old> :current-sha <new>
                              :row-count {:fixture N :current M}}
     {:verdict :not-found :fixture-id id :path <path>}

   When `:sha-mismatch` is returned, the caller should treat the underlying DB
   as having drifted — regressing against stale expected values would be
   meaningless."
  [fixture-id & {:keys [fixtures-dir]
                 :or   {fixtures-dir default-fixtures-dir}}]
  (let [path    (fixture-path fixtures-dir fixture-id)
        fixture (read-fixture-edn path)]
    (if-not fixture
      {:verdict :not-found :fixture-id fixture-id :path path}
      (let [{:fixture/keys [marketplace period sha256-of-rows row-count expected]} fixture
            finance-rows (fetch-finance-rows marketplace period)
            current-sha  (compute-sha256-of-rows finance-rows)]
        (if (not= sha256-of-rows current-sha)
          {:verdict      :sha-mismatch
           :fixture-id   fixture-id
           :fixture-sha  sha256-of-rows
           :current-sha  current-sha
           :row-count    {:fixture row-count
                          :current (count finance-rows)}}
          (let [pnl-actual        (compute-pnl finance-rows marketplace)
                ue-actual         (compute-unit-economics finance-rows)
                sales-actual      (sales-qty-total finance-rows)
                pnl-diff          (diff-map (:pnl expected) pnl-actual)
                ue-diff           (diff-unit-economics (:unit-economics expected) ue-actual)
                sales-exp         (get-in expected [:sales-qty :total])
                sales-diff        (when-not (within-tolerance? sales-exp sales-actual)
                                    {:expected sales-exp :actual sales-actual})]
            (if (and (empty? pnl-diff) (empty? ue-diff) (nil? sales-diff))
              {:verdict :match :fixture-id fixture-id}
              (cond-> {:verdict :diff :fixture-id fixture-id}
                (seq pnl-diff)       (assoc :pnl-diff pnl-diff)
                (seq ue-diff)        (assoc :ue-diff ue-diff)
                sales-diff           (assoc :sales-qty-diff sales-diff)))))))))

;; ---------------------------------------------------------------------------
;; list-fixtures
;; ---------------------------------------------------------------------------

(defn list-fixtures
  "Enumerate fixture EDN files in `fixtures-dir`.

   Returns a vector of maps containing the metadata columns relevant for an
   operator listing (drops :fixture/expected to keep output small). Sorted
   alphabetically by fixture id.

   Non-existent or empty directory → empty vector (no error — FR-008-style
   graceful handling)."
  [& {:keys [fixtures-dir]
      :or   {fixtures-dir default-fixtures-dir}}]
  (let [dir (io/file fixtures-dir)]
    (if-not (.exists dir)
      []
      (->> (.listFiles dir)
           (keep (fn [^java.io.File f]
                   (when (and (.isFile f)
                              (.endsWith (.getName f) ".edn"))
                     (try
                       (let [m (read-fixture-edn (.getAbsolutePath f))]
                         (when m
                           (-> m
                               (dissoc :fixture/expected)
                               (assoc :fixture/path (.getAbsolutePath f)))))
                       (catch Throwable _ nil)))))
           (sort-by :fixture/id)
           vec))))
