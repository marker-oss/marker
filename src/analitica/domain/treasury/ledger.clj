(ns analitica.domain.treasury.ledger
  "Decimal ledger aggregates: per-account balances + deltas (spec 019, T020).

   Contracts: ledger-entities.edn (balance is DERIVED — dsum of confirmed
   operations), cashflow-api.edn §3 (:balance). Arithmetic is EXCLUSIVELY the
   decimal-as-string block in util/math (m/d, m/dsum, m/d-, m/d->str) — NEVER
   (reduce + …) on a ledger amount.

   Balance semantics (R1, R3):
     • income   → +amount on account-id
     • expense  → −amount on account-id
     • transfer → −amount on account-id AND +amount on transfer-account-id
       (transfer moves money between two OWN accounts; business net = 0, R3)
   Only CONFIRMED operations (actuals) count toward a balance (R10)."
  (:require [analitica.db :as db]
            [analitica.util.math :as m]))

(defn- confirmed-operations
  "All confirmed treasury operations as raw kebab maps (for balance derivation)."
  []
  (db/treasury-query-operations "confirmed = 1" []))

(defn balances
  "Derived per-account balances from confirmed operations.
   Returns {:by-account {account-id BigDecimal} :total BigDecimal}, where
   :total is the algebraic sum of income − expense across own accounts
   (transfers net to zero because they debit one account and credit another)."
  ([] (balances (confirmed-operations)))
  ([ops]
   (let [by-account
         (reduce
           (fn [acc {:keys [account-id transfer-account-id amount direction]}]
             (let [amt (m/d amount)]
               (case direction
                 "income"  (update acc account-id (fnil m/d+ (m/d "0.00")) amt)
                 "expense" (update acc account-id (fnil m/d- (m/d "0.00")) amt)
                 "transfer" (-> acc
                                (update account-id (fnil m/d- (m/d "0.00")) amt)
                                (update transfer-account-id (fnil m/d+ (m/d "0.00")) amt))
                 acc)))
           {}
           ops)]
     {:by-account by-account
      :total      (m/dsum (vals by-account))})))

(defn account-balance
  "Return the BigDecimal balance of `account-id` from a `balances` result."
  [bal account-id]
  (get-in bal [:by-account account-id] (m/d "0.00")))

(defn delta
  "Change in `account-id`'s balance between two `balances` snapshots, as a
   \"0.00\"-string. delta = bal1 − bal0 (may be negative)."
  [bal0 bal1 account-id]
  (m/d->str (m/d- (account-balance bal1 account-id)
                  (account-balance bal0 account-id))))
