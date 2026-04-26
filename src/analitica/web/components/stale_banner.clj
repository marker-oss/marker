(ns analitica.web.components.stale-banner
  "Stale-data banner component.

   (stale-banner stale-info {:report :pnl :period \"2026-04-01_2026-04-26\"})

   Returns a yellow dismissible Hiccup vector when status = :stale,
   nil when status = :ok (nothing rendered in the DOM).

   V1 dismissal behaviour: inline onclick sets localStorage[key] = today's
   date and hides the banner via the 'hidden' attribute. The banner reappears
   on the next full page load (one-day dismissal). There is no on-load script
   to re-hide it — that is a V1 limitation documented here."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Private helpers
;; ---------------------------------------------------------------------------

(defn- format-iso-ru
  "\"2026-04-17T03:40:00\" → \"17.04 03:40\". Returns \"—\" for nil."
  [iso]
  (if (and iso (>= (count iso) 16))
    (let [date-part (subs iso 0 10)
          time-part (subs iso 11 16)
          [_ m d]   (str/split date-part #"-")]
      (str d "." m " " time-part))
    "—"))

;; ---------------------------------------------------------------------------
;; Public component
;; ---------------------------------------------------------------------------

(defn stale-banner
  "Yellow dismissible banner. Renders only when status is :stale.

   stale-info: map returned by freshness/stale-info or stale-info*
     {:status :stale :reason str :last-sync iso :age-days N
      :worst-pair [:mp :src] :max-lag-days N}
   context: {:report keyword :period string}

   Returns Hiccup vector or nil."
  [{:keys [status reason last-sync] :as _info}
   {:keys [report period]}]
  (when (= status :stale)
    (let [banner-key (str (name (or report "report")) "-" (or period "all"))]
      [:div.bg-amber-50.border-l-4.border-amber-400.p-3.mb-4.flex.items-center.justify-between
       {:data-stale-banner banner-key}
       [:div
        [:p.font-semibold "⚠ " reason]
        [:p.text-xs.text-gray-600
         (str "Последняя синхронизация: " (format-iso-ru last-sync))]]
       [:div.flex.items-center.gap-2
        [:a.text-amber-700.hover:underline {:href "/sync"} "→ Запустить синхронизацию"]
        [:button.text-amber-700
         {:onclick (str "this.closest('[data-stale-banner]').setAttribute('hidden', '');"
                        "localStorage.setItem('"
                        banner-key
                        "', new Date().toISOString().slice(0,10));")}
         "×"]]])))
