(ns analitica.backfill
  "One-shot backfill operations for migrations that need to derive new
   column values from existing data.

   Currently:
     E-2 (2026-04-28) — populate :operation-kind / :operation-subtype on
     finance rows materialised before the RFC-3 rollout.

   Each fn here is idempotent: re-running on already-backfilled data
   either no-ops or refines edge cases without harm."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [analitica.db :as db]))

;; ---------------------------------------------------------------------------
;; E-2: operation-kind / operation-subtype backfill
;; ---------------------------------------------------------------------------
;;
;; Phase B introduced :operation-kind / :operation-subtype to the FinanceRow
;; Malli schema, but DB columns and persistence were missing until E-1
;; landed (2026-04-28). For rows already in the DB, we derive the new
;; fields from the existing :operation string by per-marketplace mapping.
;;
;; Mapping is the same as in transform.clj (single source of truth):
;;
;;   WB:    Russian classifier  → kind + subtype = original string
;;          canonical "sale"/"return" → :sale/:return + nil subtype
;;   Ozon:  "sale" / "return"  → :sale / :return  + "realization"
;;          "service"          → :service        + nil
;;   YM:    "sale" / "return"  → :sale / :return  + nil
;;          "cancelled"        → :adjustment     + raw doc_type if present

(def ^:private wb-kind-map
  ;; Mirrors `analitica.marketplace.wb.transform/wb-operation-kind`.
  {"Продажа"                                                    :sale
   "Возврат"                                                    :return
   "Логистика"                                                  :service
   "Коррекция логистики"                                        :service
   "Хранение"                                                   :service
   "Платная приёмка"                                            :service
   "Сторно платной приёмки"                                     :service
   "Обработка товара"                                           :service
   "Возмещение издержек по перевозке/по складским операциям с товаром" :adjustment
   "Возмещение за выдачу и возврат товаров на ПВЗ"              :adjustment
   "Корректировка вознаграждения"                               :adjustment
   "Коррекция продаж"                                           :adjustment
   "Компенсация ущерба"                                         :adjustment
   "Компенсация подмены"                                        :adjustment
   "Компенсация скидки по программе лояльности"                 :adjustment
   "Штраф"                                                      :adjustment
   "Удержание"                                                  :adjustment
   "Доплата"                                                    :adjustment})

(defn classify
  "Return [kind-keyword subtype-string] for one finance row given its
   marketplace + raw :operation string. Returns [nil raw-string] when
   the operation is unrecognized — caller can choose to leave the row
   unbackfilled (operation_kind IS NULL) or surface for review."
  [marketplace operation]
  (let [op (some-> operation str)]
    (cond
      ;; Already-canonical English values across all MPs.
      (= "sale" op)      [:sale       (case marketplace
                                        "ozon" "realization"
                                        nil)]
      (= "return" op)    [:return     (case marketplace
                                        "ozon" "realization"
                                        nil)]
      (= "service" op)   [:service    nil]
      (= "adjustment" op) [:adjustment nil]

      ;; YM-specific.
      (and (= "ym" marketplace) (= "cancelled" op))
      [:adjustment "CANCELLED"]

      ;; WB-specific.
      (and (= "wb" marketplace) (contains? wb-kind-map op))
      [(get wb-kind-map op) op]

      :else
      [nil op])))

(defn- count-needs-backfill [ds]
  (-> (jdbc/execute-one!
        ds
        ["SELECT COUNT(*) AS n FROM finance WHERE operation_kind IS NULL"]
        {:builder-fn rs/as-unqualified-maps})
      :n))

(defn- distribution [ds]
  (jdbc/execute!
    ds
    ["SELECT marketplace, operation, COUNT(*) AS n
      FROM finance WHERE operation_kind IS NULL
      GROUP BY marketplace, operation
      ORDER BY n DESC"]
    {:builder-fn rs/as-unqualified-maps}))

(defn backfill-operation-kind!
  "Populate finance.operation_kind and finance.operation_subtype for rows
   that were materialised before the E-1 schema migration.

   Returns a summary map:
     :before        rows with operation_kind IS NULL before backfill
     :updated       rows whose kind was successfully derived
     :unmapped      rows where operation didn't match any classifier (left as NULL)
     :unmapped-by-mp distribution of un-classified rows for review

   Idempotent: only touches rows where operation_kind IS NULL."
  ([] (backfill-operation-kind! (db/ds)))
  ([ds]
   (let [before  (count-needs-backfill ds)
         dist    (distribution ds)
         summary (reduce
                   (fn [acc {:keys [marketplace operation n]}]
                     (let [[kind subtype] (classify marketplace operation)]
                       (if kind
                         (do
                           (jdbc/execute!
                             ds
                             ["UPDATE finance
                               SET operation_kind = ?, operation_subtype = ?
                               WHERE marketplace = ?
                                 AND ((operation IS NULL AND ? IS NULL)
                                      OR operation = ?)
                                 AND operation_kind IS NULL"
                              (name kind) subtype
                              marketplace operation operation])
                           (update acc :updated + n))
                         (-> acc
                             (update :unmapped + n)
                             (update :unmapped-by-mp conj
                                     {:marketplace marketplace
                                      :operation   operation
                                      :n           n})))))
                   {:updated 0 :unmapped 0 :unmapped-by-mp []}
                   dist)]
     (assoc summary :before before))))

;; ---------------------------------------------------------------------------
;; E-2 follow-up: normalize for_pay / quantity signs on legacy rows
;; ---------------------------------------------------------------------------
;;
;; RFC-14 / RFC-15 require for_pay ≥ 0 and quantity ≥ 0; direction lives in
;; operation_kind. New rows from transforms already obey this; legacy WB
;; rows still carry negative values for returns. After E-2 backfill puts
;; operation_kind in place, we can flip those signs safely.

(defn- count-negative [ds field]
  (-> (jdbc/execute-one!
        ds
        [(format "SELECT COUNT(*) AS n FROM finance WHERE %s < 0" field)]
        {:builder-fn rs/as-unqualified-maps})
      :n))

(defn normalize-signs!
  "Flip negative for_pay / quantity to absolute value on legacy finance
   rows. Direction is already encoded by :operation-kind after the E-2
   backfill, so the magnitude is what matters going forward.

   Idempotent: subsequent runs find no negatives to flip.

   Returns {:before-pay N :before-qty N :flipped-pay N :flipped-qty N}."
  ([] (normalize-signs! (db/ds)))
  ([ds]
   (let [before-pay (count-negative ds "for_pay")
         before-qty (count-negative ds "quantity")]
     (jdbc/execute! ds ["UPDATE finance SET for_pay = -for_pay WHERE for_pay < 0"])
     (jdbc/execute! ds ["UPDATE finance SET quantity = -quantity WHERE quantity < 0"])
     (let [after-pay (count-negative ds "for_pay")
           after-qty (count-negative ds "quantity")]
       {:before-pay  before-pay
        :before-qty  before-qty
        :flipped-pay (- before-pay after-pay)
        :flipped-qty (- before-qty after-qty)
        :remaining-negative-pay after-pay
        :remaining-negative-qty after-qty}))))

(defn run-all!
  "Convenience: backfill operation_kind/subtype, then normalize signs.
   Returns merged summary."
  ([] (run-all! (db/ds)))
  ([ds]
   {:operation-kind (backfill-operation-kind! ds)
    :sign-normalize (normalize-signs! ds)}))

