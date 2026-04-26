(ns analitica.domain.order-status
  "Canonicalize raw `orders.status` strings into 4 buckets shared across all
   marketplaces: `:cancelled`, `:delivered`, `:returned`, `:in-flight`.

   Each marketplace uses its own status taxonomy:

     WB    — `active`, `cancelled` (delivered orders stay `active`)
     Ozon  — `awaiting_packaging`, `delivering`, `delivered`, `cancelled`
     YM    — `PROCESSING`, `DELIVERY`, `PARTIALLY_DELIVERED`, `DELIVERED`,
             `PICKUP`, `CANCELLED_BEFORE_PROCESSING`,
             `CANCELLED_IN_PROCESSING`, `CANCELLED_IN_DELIVERY`, `RETURNED`

   WB does not expose a final `delivered` state in the orders endpoint —
   completed WB orders stay `active`. For WB the `:delivered` count must
   therefore come from the `sales` table (post-settlement events), not from
   this canonicalizer. The canonicalizer only resolves cancellations and
   YM/Ozon-side delivery confirmations.")

(def ^:private cancelled-statuses
  #{"cancelled"
    "CANCELLED_BEFORE_PROCESSING"
    "CANCELLED_IN_PROCESSING"
    "CANCELLED_IN_DELIVERY"})

(def ^:private delivered-statuses
  #{"delivered"
    "DELIVERED"
    "PARTIALLY_DELIVERED"
    "PICKUP"})

(def ^:private returned-statuses
  #{"RETURNED"})

(defn canonicalize
  "Return one of `:cancelled`, `:delivered`, `:returned`, `:in-flight` for a
   raw status string. Anything not explicitly classified is `:in-flight` —
   that includes WB `active`, Ozon `delivering`/`awaiting_packaging`, YM
   `PROCESSING`/`DELIVERY`."
  [status]
  (cond
    (contains? cancelled-statuses status) :cancelled
    (contains? delivered-statuses status) :delivered
    (contains? returned-statuses status)  :returned
    :else                                 :in-flight))
