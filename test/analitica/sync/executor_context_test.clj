(ns analitica.sync.executor-context-test
  (:require [clojure.test :refer [deftest is]]
            [analitica.sync.executor :as executor]
            [com.brunobonacci.mulog :as mu]))

(deftest with-run-context-sets-run-id
  (is (= "RID-123"
         (:run-id (executor/with-run-context "RID-123"
                    (fn [] (mu/local-context))))))
  ;; returns the thunk's value
  (is (= 7 (executor/with-run-context "RID-123" (fn [] 7)))))

(deftest run-id-survives-the-executor-thread-boundary
  ;; The task runs on a worker thread (.submit). Because with-run-context wraps
  ;; the work INSIDE the submitted fn, the mulog context is bound on the worker
  ;; thread — prove it survives the boundary, not just same-thread.
  (let [pool (java.util.concurrent.Executors/newSingleThreadExecutor)]
    (try
      (let [fut (.submit pool ^Callable
                         (fn [] (executor/with-run-context "RID-thread"
                                  (fn [] (:run-id (mu/local-context))))))]
        (is (= "RID-thread" (.get fut))))
      (finally (.shutdown pool)))))
