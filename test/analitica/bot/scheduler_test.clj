(ns analitica.bot.scheduler-test
  "Smoke coverage for bot.scheduler period keys. Until 2026-07-02 this ns
   had zero test coverage AND zero callers, which let a broken Java-interop
   form (WeekFields/ISO.weekOfWeekBasedYear) ship uncompiled — the suite
   stayed green while the digest scheduler could not even load (audit P0-4)."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.bot.scheduler :as scheduler])
  (:import [java.time ZoneId]))

(def ^:private tz (ZoneId/of "Europe/Moscow"))

(deftest daily-period-shape
  (testing "yesterday as ISO date"
    (is (re-matches #"\d{4}-\d{2}-\d{2}" (scheduler/daily-period tz)))))

(deftest weekly-period-shape
  (testing "last completed ISO week as YYYY-Www"
    (is (re-matches #"\d{4}-W\d{2}" (scheduler/weekly-period tz)))))

(deftest start-stop-idempotent
  (testing "start!/stop! survive repeated calls without touching Telegram"
    ;; fire fns are only invoked by the executor after a 0s initial delay;
    ;; stub them so no digest work (or network) can run if it fires first.
    (with-redefs [scheduler/fire-daily!  (fn [] nil)
                  scheduler/fire-weekly! (fn [] nil)]
      (with-out-str
        (scheduler/start!)
        (scheduler/start!)   ; idempotent second call
        (scheduler/stop!)
        (scheduler/stop!)))
    (is true)))
