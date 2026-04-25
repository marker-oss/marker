(ns analitica.web.api.sync
  (:require [analitica.sync :as sync]
            [analitica.ingest :as ingest]
            [analitica.materialize :as materialize]
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
   - what:        keyword (:sales, :orders, :finance, :stocks, etc.)
   - :period      period keyword or map (optional)
   - :marketplace marketplace keyword (optional, default :wb)

   Returns {:ok true} or {:error \"already running\"}."
  [what & {:keys [period marketplace]}]
  (if (compare-and-set! sync-running? false true)
    (let [queue (LinkedBlockingQueue. 1000)
          fut   (future
                  (try
                    (println (str "[SYNC] Starting: what=" what ", period=" period ", marketplace=" marketplace))
                    (capture-output-to-queue queue
                      (fn []
                        (let [mp (or marketplace :wb)]
                          (case what
                            :1c (sync/sync-1c!)
                            (do (ingest/ingest! what :period period :marketplace mp)
                                (materialize/materialize! what :period period :marketplace mp))))))
                    (.offer queue {:type :done})
                    (println "[SYNC] Completed")
                    (catch InterruptedException _
                      (println "[SYNC] Cancelled by user")
                      (.offer queue {:type :error :text "Прервано пользователем"}))
                    (catch Exception e
                      (println (str "[SYNC] Error: " (.getMessage e)))
                      (μ/log ::sync-error
                             :what what :period period :marketplace marketplace
                             :error-message (.getMessage e) :error-type (type e))
                      (.offer queue {:type :error :text (str "Error: " (.getMessage e))}))
                    (finally
                      (reset! sync-running? false)
                      (reset! sync-future nil)
                      (println "[SYNC] Flag reset"))))]
      (reset! progress-channel queue)
      (reset! sync-future fut)
      {:ok true})
    {:error "already running"}))

(defn stop-sync!
  "Interrupt any running sync. Returns {:ok true} if a sync was running and
   cancellation was requested, or {:error \"not running\"}."
  []
  (if-let [fut @sync-future]
    (do (future-cancel fut)
        {:ok true})
    {:error "not running"}))

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
