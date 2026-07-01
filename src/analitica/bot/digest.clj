(ns analitica.bot.digest
  "Bot digest assembly: payload collection, readiness gating, and rendering.

   Contract (data-model §8, FR-007/FR-008):
     - DigestSource = output of analitica.web.pages.digest/collect-page-data!
       (server-rendered; REUSED — not recomputed here)
     - Numbers are consumed as-is from the payload (channel==screen, SC-002)
     - Gate layer reads :preliminary? / :freshness from the payload and delegates
       to analitica.domain.preliminary for Ozon monthly-realization state
       (frozen invariant — gate only reads, never mutates, P6)

   Metric slug dictionary (016, §3.C):
     - Bot consumes slugs from report_schemas/:canonical-metric-slugs
     - Interim fallback: format-kpi-value / metric-labels / valid-metrics
     - Coercion digest-key→slug (:margin→:margin-pct, :drr→:drr-pct,
       :profit→:net-profit) lives in a single place here (contracts/digest-payload.edn §4)

   Gate modes (FR-014..FR-018):
     - :flag    — preliminary MP figures rendered with «(предв.)» marker (default)
     - :withhold — preliminary MP figures removed from message
     - :missing  — no data loaded → :withhold, NOT treated as zero (FR-018)
   Whole-digest fallback (FR-017):
     - gate-when-empty=:skip  → outcome=:gated, detail=\"nothing final\", no send
     - gate-when-empty=:notice → short «отчёт ещё не готов» message sent

   Init: wired exclusively from analitica.bot.scheduler/fire! (core/start! chain)."
  (:require [analitica.bot.subscription :as sub])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [java.time.temporal ChronoUnit]))

;; ---------------------------------------------------------------------------
;; Coercion map: digest-key → 016 canonical slug (contracts/digest-payload.edn §4)
;; ---------------------------------------------------------------------------

(def digest-key->slug
  "Single coercion point between DigestSource KPI keys and 016 slug vocabulary."
  {:margin :margin-pct
   :drr    :drr-pct
   :profit :net-profit})

;; ---------------------------------------------------------------------------
;; Freshness thresholds per MP (days) — mirrors digest.clj:364,370,376
;; ---------------------------------------------------------------------------

(def ^:private freshness-thresholds
  {:wb   6
   :ozon 30
   :ym   2})

;; ---------------------------------------------------------------------------
;; Readiness gate (FR-014..FR-018)
;; ---------------------------------------------------------------------------

(defn- parse-iso-local
  "Parse an ISO datetime string to LocalDateTime, return nil on failure."
  [s]
  (when (seq s)
    (try
      (LocalDateTime/parse s (DateTimeFormatter/ISO_LOCAL_DATE_TIME))
      (catch Throwable _
        (try (LocalDateTime/parse s)
             (catch Throwable _ nil))))))

(defn- freshness-age-days
  "Days since `iso` string (as of now). nil when iso is nil or unparsable."
  [iso]
  (when-let [dt (parse-iso-local iso)]
    (.between ChronoUnit/DAYS dt (LocalDateTime/now))))

(defn gate-decision
  "Compute a GateDecision for one MP.

   `mp-row`     — one entry from DigestSource :by-marketplace
                  must contain :marketplace and :preliminary?
   `freshness`  — ISO datetime string for this MP (may be nil)
   `mp-kw`      — marketplace keyword (:wb/:ozon/:ym) for threshold lookup
   `gate-mode`  — :flag (default) or :withhold — controls action for :preliminary state

   Returns {:marketplace kw :state :final|:preliminary|:missing :action :send|:flag|:withhold}"
  ([mp-row freshness mp-kw]
   (gate-decision mp-row freshness mp-kw :flag))
  ([mp-row freshness mp-kw gate-mode]
   (let [mp         (or (:marketplace mp-row) mp-kw)
         prelim?    (:preliminary? mp-row)
         threshold  (get freshness-thresholds (keyword mp) 7)
         age        (freshness-age-days freshness)
         stale?     (or (nil? age) (> age threshold))
         missing?   (nil? freshness)
         state      (cond
                      missing?        :missing
                      (or prelim? stale?) :preliminary
                      :else               :final)
         action     (case state
                      :final       :send
                      :preliminary (if (= gate-mode :withhold) :withhold :flag)
                      :missing     :withhold)]
     {:marketplace mp
      :state       state
      :action      action})))

(defn- compute-gate-decisions
  "Return a vector of GateDecision for each MP in the payload,
   filtering to only the MPs relevant to `subscription`."
  [payload subscription gate-mode]
  (let [mp-filter  (keyword (:marketplace subscription))
        freshness  (:freshness payload)
        by-mp      (:by-marketplace payload)]
    (->> by-mp
         (filter (fn [mp-row]
                   (or (= mp-filter :all)
                       (= (keyword (:marketplace mp-row)) mp-filter))))
         (mapv (fn [mp-row]
                 (let [mp-kw (keyword (:marketplace mp-row))
                       fresh (get freshness mp-kw)]
                   (gate-decision mp-row fresh mp-kw gate-mode)))))))

;; ---------------------------------------------------------------------------
;; Rendering (FR-007) — plain-text Telegram Markdown
;; ---------------------------------------------------------------------------

(defn- fmt-rub [n]
  (when (number? n)
    (str (Math/round (double n)) " ₽")))

(defn- fmt-pct [n]
  (when (number? n)
    (format "%.1f%%" (double n))))

(defn- fmt-number [n]
  (when (number? n)
    (str (Math/round (double n)))))

(defn- render-kpi-value
  "Render a KPI value using its slug to pick the right format."
  [slug value]
  (let [pct-slugs #{:margin-pct :drr-pct}
        qty-slugs #{:orders}
        rub-slugs #{:revenue :net-profit :gross-margin :cogs :advertising :logistics
                    :storage :mp-commission :operating-expenses :ebitda :tax}]
    (cond
      (contains? pct-slugs slug)  (fmt-pct value)
      (contains? qty-slugs slug)  (fmt-number value)
      (contains? rub-slugs slug)  (fmt-rub value)
      :else                       (fmt-number value))))

(defn- slug-label
  "Human-readable label for a metric slug (interim fallback; 016 descriptors are
   the authoritative source once fully landed)."
  [slug]
  (get {:revenue          "Выручка"
        :net-profit       "Прибыль"
        :gross-margin     "Валовая прибыль"
        :margin-pct       "Маржа"
        :drr-pct          "ДРР"
        :orders           "Заказы"
        :advertising      "Реклама"
        :cogs             "Себестоимость"
        :logistics        "Логистика"
        :storage          "Хранение"
        :mp-commission    "Комиссия МП"
        :operating-expenses "Операционные расходы"
        :ebitda           "EBITDA"
        :tax              "Налог"}
       slug
       (name slug)))

(defn- kpi-key->slug
  "Map a DigestSource :kpi key to the 016 canonical slug."
  [k]
  (get digest-key->slug k k))

(defn- selected-metrics
  "Resolve the metric list for a subscription: either the stored slugs or
   the default set when empty."
  [subscription]
  (let [m (:metrics subscription)]
    (if (seq m) m sub/default-metric-set)))

(defn- kpi-value-for-slug
  "Extract the matching value from DigestSource :kpi map, handling digest-key→slug coercion."
  [kpi-map slug]
  ;; The DigestSource uses :margin/:drr/:profit; look up both the slug and reverse coercion
  (let [rev-map (into {} (map (fn [[dk sk]] [sk dk]) digest-key->slug))
        digest-key (get rev-map slug slug)]
    (or (get kpi-map slug)
        (get kpi-map digest-key))))

(defn- render-kpi-section
  "Render selected KPI metrics from the payload :kpi map."
  [kpi-map metrics]
  (let [lines (for [slug metrics
                    :let [val (kpi-value-for-slug kpi-map slug)]
                    :when (some? val)]
                (str (slug-label slug) ": " (render-kpi-value slug val)))]
    (when (seq lines)
      (str "*Ключевые метрики:*\n" (clojure.string/join "\n" lines)))))

(defn- render-mp-section
  "Render by-marketplace comparison block, applying gate decisions."
  [by-mp decisions gate-mode]
  (let [decision-map (into {} (map (juxt :marketplace identity) decisions))
        mp-names     {:wb "Wildberries" :ozon "Ozon" :ym "Яндекс Маркет"}
        lines        (for [mp-row by-mp
                           :let [mp-kw     (keyword (:marketplace mp-row))
                                 decision  (get decision-map mp-kw)
                                 action    (:action decision)]
                           :when (not= action :withhold)]
                       (let [prelim-marker (when (= action :flag) " (предв.)")
                             name  (get mp-names mp-kw (name mp-kw))
                             rev   (fmt-rub (:revenue mp-row))
                             prof  (fmt-rub (:profit mp-row))]
                         (str name prelim-marker ": выручка " rev ", прибыль " prof)))]
    (when (seq lines)
      (str "*По маркетплейсам:*\n" (clojure.string/join "\n" lines)))))

(defn- render-movers-section [movers fallers show-movers?]
  (when show-movers?
    (let [top-m (->> movers (take 3))
          top-f (->> fallers (take 3))
          m-lines (for [m top-m]
                    (str "  +" (format "%.0f%%" (double (:delta-pct m))) " " (:name m)))
          f-lines (for [f top-f]
                    (str "  " (format "%.0f%%" (double (:delta-pct f))) " " (:name f)))]
      (when (or (seq m-lines) (seq f-lines))
        (str (when (seq m-lines) (str "*Растут:*\n" (clojure.string/join "\n" m-lines)))
             (when (and (seq m-lines) (seq f-lines)) "\n")
             (when (seq f-lines) (str "*Падают:*\n" (clojure.string/join "\n" f-lines))))))))

(defn render-digest-message
  "Render a Telegram-ready Markdown message from `payload` and `subscription`.
   `decisions` — vector of GateDecision (pre-computed).
   Returns a non-nil string."
  [payload subscription decisions]
  (let [from          (:from payload)
        to            (:to payload)
        period-hdr    (str "📊 Дайджест " from " — " to "\n\n")
        metrics       (selected-metrics subscription)
        kpi-section   (render-kpi-section (:kpi payload) metrics)
        mp-section    (render-mp-section (:by-marketplace payload) decisions :flag)
        movers-section (render-movers-section (:movers payload) (:fallers payload)
                                               (:show-movers? subscription))
        parts         (keep identity [period-hdr kpi-section mp-section movers-section])]
    (clojure.string/join "\n\n" parts)))

;; ---------------------------------------------------------------------------
;; build-and-send! — the central assembly function (US1+US3)
;; ---------------------------------------------------------------------------

(defn build-and-send!
  "Assemble and send a digest message for `subscription`.

   `payload`     — DigestSource map (output of collect-page-data!; reused as-is)
   `subscription` — BotSubscription domain map
   `opts`         — {:gate-mode :flag|:withhold  :sender-fn (fn [chat-id text] result)}

   Returns:
     {:outcome :delivered :detail ...}
     {:outcome :gated    :detail ...}  — nothing-final (no send for :skip)
     {:outcome :failed   :detail ...}

   Invariant (P6): NEVER sends a preliminary period as final."
  [payload subscription {:keys [gate-mode sender-fn]
                          :or   {gate-mode :flag}}]
  (let [decisions  (compute-gate-decisions payload subscription gate-mode)
        all-withheld? (every? #(= :withhold (:action %)) decisions)
        chat-id    (:chat-id subscription)
        gwe        (keyword (:gate-when-empty subscription))]
    (cond
      ;; Whole-digest gate (FR-017): all MPs are withheld
      all-withheld?
      (case gwe
        :notice
        (let [notice-text (str "⏳ Отчёт ещё не готов.\n"
                               "Данные за период " (:from payload) " — " (:to payload)
                               " ещё в обработке (предварительные). "
                               "Дайджест будет отправлен, когда данные станут финальными.")
              result (try (sender-fn chat-id notice-text)
                          (catch Throwable t {:sent? false :detail (.getMessage t)}))]
          {:outcome :gated
           :detail  (if (:sent? result) "notice sent" "notice failed")})
        ;; :skip (default)
        {:outcome :gated :detail "nothing final"})

      ;; Normal delivery path — some MPs sendable
      :else
      (let [text   (render-digest-message payload subscription decisions)
            result (try (sender-fn chat-id text)
                        (catch Throwable t {:sent? false :detail (.getMessage t)}))]
        (if (:sent? result)
          {:outcome :delivered :detail (:detail result)}
          {:outcome :failed :detail (:detail result)})))))
