(ns analitica.web.api.sync
  (:require [analitica.sync :as sync]
            [analitica.ingest :as ingest]
            [analitica.materialize :as materialize]
            [analitica.sync.plan :as plan]
            [analitica.sync.executor :as executor]
            [analitica.audit.hook :as audit-hook]
            [ring.core.protocols :as ring-protocols]
            [com.brunobonacci.mulog :as μ])
  (:import [java.util.concurrent LinkedBlockingQueue]
           [java.io StringWriter PrintWriter]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce sync-running? (atom false))
(defonce progress-channel (atom nil))
;; Holds the running future so /api/sync/stop can cancel it.
(defonce sync-future (atom nil))

;; ---------------------------------------------------------------------------
;; Output capture
;; ---------------------------------------------------------------------------

(defn- make-streaming-writer
  "Creates a Writer that pushes each completed line into queue immediately.

   Note on the cbuf branch: java.io.PrintWriter can route writes through
   `write(String, int, int)` rather than the char[] overload. The proxy
   collapses both to one method, so cbuf may arrive as String *or* char[].
   Constructing `(String. aString off len)` raises 'No matching ctor for
   class java.lang.String' — which silently killed every /api/sync/start
   call before this branch existed."
  [queue]
  (let [buf (StringBuilder.)]
    (proxy [java.io.Writer] []
      (write [cbuf off len]
        (locking buf
          (.append buf (if (string? cbuf)
                         (.substring ^String cbuf (int off) (+ (int off) (int len)))
                         (String. ^chars cbuf (int off) (int len))))
          (loop []
            (let [s   (str buf)
                  nl  (.indexOf s "\n")]
              (when (>= nl 0)
                (let [line (.trim (.substring s 0 nl))]
                  (when (seq line)
                    (.offer queue {:type :message :text line})))
                (.delete buf 0 (inc nl))
                (recur))))))
      (flush [] nil)
      (close [] nil))))

(defn- capture-output-to-queue
  "Redirect *out* to queue, streaming each line as it is printed."
  [queue f]
  (binding [*out* (java.io.PrintWriter. (make-streaming-writer queue) true)]
    (f)))

;; ---------------------------------------------------------------------------
;; Sync management
;; ---------------------------------------------------------------------------


(defn start-sync!
  "Start sync in a separate thread.

   Parameters:
   - what:        keyword (:sales, :orders, :finance, :stocks, :all, etc.)
   - :period      period keyword, [from to] vector, or {:from :to} map (optional)
   - :marketplace marketplace keyword. :wb / :ozon / :ym for one MP, or
                  :all to fan out across [:wb :ozon :ym].

   Phase 8: for non-:1c `what`, delegates to plan/expand-plan + executor/run-parallel!
   so every trigger flows through the registry and is visible in the task matrix.

   Special case — :1c:
     1C is an in-process CSV upload, not a marketplace API call. It does not fit
     the registry model (no mp/entity-type/phase columns make sense for it, and it
     is rare). The pre-Phase-3 direct path is preserved: sync/sync-1c! runs directly
     under the SSE-streamed legacy log, and no sync_tasks rows are created.

   SSE stream (option a):
     The outer future is wrapped in capture-output-to-queue so the SSE /stream
     endpoint stays alive for backward compat. Per-task worker-thread stdout does
     NOT propagate through the binding (Java thread-pool inheritance caveat), so
     the log will show '[SYNC] Starting…' and '[SYNC] Completed' but not per-task
     lines. The task matrix is the canonical progress view.

   Returns {:ok true :run-id <uuid>} for non-:1c, {:ok true} for :1c,
   or {:error \"already running\"}."
  [what & {:keys [period marketplace]}]
  (if (compare-and-set! sync-running? false true)
    (if (= what :1c)
      ;; -----------------------------------------------------------------------
      ;; :1c path — legacy direct sync, no registry rows, SSE streamed
      ;; -----------------------------------------------------------------------
      (let [queue (LinkedBlockingQueue. 1000)
            fut   (future
                    (try
                      (println "[SYNC:1c] Starting 1C sync")
                      (capture-output-to-queue queue
                        (fn []
                          (sync/sync-1c!)))
                      (.offer queue {:type :done})
                      (println "[SYNC:1c] Completed")
                      (catch InterruptedException _
                        (println "[SYNC:1c] Cancelled by user")
                        (.offer queue {:type :error :text "Прервано пользователем"}))
                      (catch Exception e
                        (println (str "[SYNC:1c] Error: " (.getMessage e)))
                        (μ/log ::sync-1c-error :error-message (.getMessage e) :error-type (type e))
                        (.offer queue {:type :error :text (str "Error: " (.getMessage e))}))
                      (finally
                        (reset! sync-running? false)
                        (reset! sync-future nil)
                        (println "[SYNC:1c] Flag reset"))))]
        (reset! progress-channel queue)
        (reset! sync-future fut)
        {:ok true})
      ;; -----------------------------------------------------------------------
      ;; Plan-based path — registry + parallel executor
      ;; -----------------------------------------------------------------------
      (let [run-id (str (java.util.UUID/randomUUID))
            mp     (or marketplace :wb)
            pln    (plan/expand-plan :run-id      run-id
                                     :what        what
                                     :marketplace mp
                                     :period      period)
            _      (plan/persist-plan! pln)
            queue  (LinkedBlockingQueue. 1000)
            fut    (future
                     (try
                       (println (str "[SYNC] Starting: run-id=" run-id " what=" what
                                     " marketplace=" mp " tasks=" (count pln)))
                       ;; Wrap in capture-output-to-queue for SSE backward compat.
                       ;; Note: worker thread *out* does not propagate through bindings,
                       ;; so only the outer println lines appear in the SSE log.
                       ;; The task matrix is the canonical progress view.
                       (capture-output-to-queue queue
                         (fn []
                           (executor/run-parallel! pln :workers 8)
                           ;; E-6: post-materialize audit hook. Runs on the
                           ;; main thread so its prints are picked up by
                           ;; the capture writer and streamed to the client
                           ;; alongside the rest of the sync log. Hook
                           ;; silently skips when entity-type isn't
                           ;; finance-touching or period isn't a concrete
                           ;; range.
                           (audit-hook/audit-after-materialize!
                             {:entity-type what
                              :period      period
                              :marketplace mp})))
                       (.offer queue {:type :done})
                       (println "[SYNC] Completed")
                       (catch InterruptedException _
                         (println "[SYNC] Cancelled by user")
                         (.offer queue {:type :error :text "Прервано пользователем"}))
                       (catch Exception e
                         (println (str "[SYNC] Error: " (.getMessage e)))
                         (μ/log ::sync-error
                                :run-id run-id :what what :marketplace mp
                                :error-message (.getMessage e) :error-type (type e))
                         (.offer queue {:type :error :text (str "Error: " (.getMessage e))}))
                       (finally
                         (reset! sync-running? false)
                         (reset! sync-future nil)
                         (println "[SYNC] Flag reset"))))]
        (reset! progress-channel queue)
        (reset! sync-future fut)
        {:ok true :run-id run-id :total (count pln)}))
    {:error "already running"}))

(defn start-rematerialize!
  "Re-run materialize/materialize! over already-ingested raw_data — no
   HTTP calls to marketplaces. Useful after fixes to transform/canon
   logic, when the API data is fine but the analytical tables need
   recomputing without burning the rate-limit budget.

   Same single-flight + SSE plumbing as start-sync!."
  [what & {:keys [period marketplace]}]
  (if (compare-and-set! sync-running? false true)
    (let [queue (LinkedBlockingQueue. 1000)
          fut   (future
                  (try
                    (println (str "[REMATERIALIZE] Starting: what=" what
                                  ", period=" period ", marketplace=" marketplace))
                    (capture-output-to-queue queue
                      (fn []
                        (let [mp (or marketplace :wb)]
                          (materialize/materialize! what :period period :marketplace mp)
                          ;; E-6: same hook as start-sync!. Silent skip
                          ;; for non-finance targets or keyword periods.
                          (audit-hook/audit-after-materialize!
                            {:entity-type what
                             :period      period
                             :marketplace mp}))))
                    (.offer queue {:type :done})
                    (println "[REMATERIALIZE] Completed")
                    (catch InterruptedException _
                      (println "[REMATERIALIZE] Cancelled by user")
                      (.offer queue {:type :error :text "Прервано пользователем"}))
                    (catch Exception e
                      (println (str "[REMATERIALIZE] Error: " (.getMessage e)))
                      (μ/log ::rematerialize-error
                             :what what :period period :marketplace marketplace
                             :error-message (.getMessage e) :error-type (type e))
                      (.offer queue {:type :error :text (str "Error: " (.getMessage e))}))
                    (finally
                      (reset! sync-running? false)
                      (reset! sync-future nil)
                      (println "[REMATERIALIZE] Flag reset"))))]
      (reset! progress-channel queue)
      (reset! sync-future fut)
      {:ok true})
    {:error "already running"}))

(defn stop-sync!
  "Interrupt any running sync. Returns {:ok true} if a sync was running and
   cancellation was requested, or {:error \"not running\"}.

   Also clears stuck atoms when the future already completed but the
   `finally` block somehow did not run (e.g. JVM-level interrupt that
   bypassed Clojure's stack). Without this, the system gets wedged
   in 'already running' until restart."
  []
  (let [fut @sync-future]
    (when fut (future-cancel fut))
    (reset! sync-running? false)
    (reset! sync-future nil)
    (if fut
      {:ok true}
      {:error "not running"})))

;; ---------------------------------------------------------------------------
;; SSE Stream
;; ---------------------------------------------------------------------------

(defn- format-sse-event
  "Format an event map as SSE message."
  [{:keys [type text]}]
  (case type
    :message (str "event: message\ndata: " text "\n\n")
    :done    "event: done\ndata: sync complete\n\n"
    :error   (str "event: error\ndata: " text "\n\n")
    ""))

(defn sse-stream
  "Create SSE stream response for sync progress.
   
   Returns Ring streaming response with:
   - Content-Type: text/event-stream
   - Cache-Control: no-cache
   - X-Accel-Buffering: no
   
   Reads events from progress-channel with 30 second timeout.
   Sends keepalive messages on timeout.
   Sends events: message, done, error."
  [_request]
  (let [queue @progress-channel]
    {:status 200
     :headers {"Content-Type" "text/event-stream"
               "Cache-Control" "no-cache"
               "X-Accel-Buffering" "no"}
     ;; Ring requires :body to satisfy StreamableResponseBody; a bare fn
     ;; raises "No implementation of method :write-body-to-stream" and
     ;; HTMX-SSE retries every second (148 errors/min in dev). Reify the
     ;; protocol to write SSE frames directly to the response stream.
     :body (reify ring-protocols/StreamableResponseBody
             (write-body-to-stream [_ _ output-stream]
               (with-open [writer (java.io.OutputStreamWriter. output-stream)]
                 (loop []
                   (let [event (when queue
                                 (.poll queue 30 java.util.concurrent.TimeUnit/SECONDS))]
                     (if event
                       (do
                         (.write writer (format-sse-event event))
                         (.flush writer)
                         (when (not (#{:done :error} (:type event)))
                           (recur)))
                       (when queue
                         (.write writer ": keepalive\n\n")
                         (.flush writer)
                         (recur))))))))}))
