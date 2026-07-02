(ns analitica.core-wiring-test
  "Wiring regression for audit P0-4 (2026-07-02): core/start! must start
   BOTH schedulers — sync (daily auto-refresh) and bot (digest delivery).
   The bot scheduler shipped in spec 017 with a docstring claiming it is
   wired from core/start!, but no caller existed, so digests never fired.

   Heavy init fns are stubbed with-redefs; we only assert the scheduler
   calls happen."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.core :as core]
            [analitica.sync :as sync]
            [analitica.db :as db]
            [analitica.config :as config]
            [analitica.logging :as logging]
            [analitica.domain.cost-price :as cost-price]
            [analitica.schema.loader :as schema-loader]
            [analitica.sync.scheduler :as sync-scheduler]
            [analitica.bot.scheduler :as bot-scheduler]))

(deftest start!-arms-both-schedulers
  (let [calls (atom #{})]
    (with-redefs [config/load-config          (fn [] nil)
                  config/reload!              (fn [] nil)
                  db/init!                    (fn [] nil)
                  logging/start-publishers!   (fn [] nil)
                  cost-price/load-from-db!    (fn [] {:articles 1})
                  schema-loader/load-all!     (fn [] {:loaded 0 :errors []})
                  sync/status                 (fn [] nil)
                  sync-scheduler/start!       (fn [] (swap! calls conj :sync))
                  bot-scheduler/start!        (fn [] (swap! calls conj :bot))]
      ;; register-marketplaces! is private — stub via the var directly.
      (with-redefs-fn {#'analitica.core/register-marketplaces! (fn [] [])}
        (fn []
          (with-out-str (core/start!))
          (testing "sync scheduler armed"
            (is (contains? @calls :sync)))
          (testing "bot digest scheduler armed (audit P0-4)"
            (is (contains? @calls :bot))))))))
