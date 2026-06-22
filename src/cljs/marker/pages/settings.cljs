(ns marker.pages.settings
  "Settings page — view (masked), validate, and save MP credentials.
   Drives /api/v1/settings/* (transit). Changes apply live (no restart)."
  (:require [uix.core :refer [$ defui use-state use-effect]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.state.subs   :as subs]
            [marker.state.events :as events]))

(def mp-specs
  [{:mp :wb :label "Wildberries"
    :fields [{:k :api-token :label "API-токен" :secret? true :setting-key "mp.wb.api-token"}]}
   {:mp :ozon :label "Ozon"
    :fields [{:k :client-id :label "Client-Id" :setting-key "mp.ozon.client-id"}
             {:k :api-key   :label "API-Key" :secret? true :setting-key "mp.ozon.api-key"}]}
   {:mp :ym :label "Яндекс.Маркет"
    :fields [{:k :oauth-token  :label "OAuth-токен"  :secret? true :setting-key "mp.ym.oauth-token"}
             {:k :campaign-id  :label "Campaign-Id"  :setting-key "mp.ym.campaign-id"}
             {:k :business-id  :label "Business-Id"  :setting-key "mp.ym.business-id"}]}])

(defn current-masked
  "Masked/plain current value for a dotted setting-key, or nil if unset."
  [data setting-key]
  (get-in data [setting-key :value]))

(defui ^:private mp-card [{:keys [spec data status]}]
  (let [[form set-form!] (use-state {})
        mp (:mp spec)
        st (get status mp)]
    ($ :section {:class "card section-card"}
       ($ :div {:class "section-head"}
          ($ :h3 {:class "section-title"} (:label spec)))
       ($ :div {:style {:display "flex" :flex-direction "column" :gap "10px"}}
          (for [{:keys [k label secret? setting-key]} (:fields spec)]
            ($ :div {:key (name k) :style {:display "flex" :flex-direction "column" :gap "4px"}}
               ($ :label {:style {:font-size "12px" :color "var(--color-fg-muted)"}}
                  label
                  (when-let [cur (current-masked data setting-key)]
                    ($ :span {:class "mono" :style {:margin-left "8px"}} "текущее: " cur)))
               ($ :input {:type        (if secret? "password" "text")
                          :placeholder (if (current-masked data setting-key)
                                         "(оставьте пустым — без изменений)"
                                         "")
                          :value       (get form k "")
                          :on-change   (fn [e] (set-form! (assoc form k (.. e -target -value))))}))))
       ($ :div {:style {:display "flex" :gap "8px" :margin-top "12px" :align-items "center"}}
          ($ :button {:class    "btn btn-secondary"
                      :disabled (boolean (:testing? st))
                      :on-click #(rf/dispatch [::events/test-marketplace mp form])}
             (if (:testing? st) "Проверка..." "Проверить"))
          ($ :button {:class    "btn btn-primary"
                      :disabled (boolean (:saving? st))
                      :on-click #(rf/dispatch [::events/save-marketplace mp form])}
             (if (:saving? st) "Сохранение..." "Сохранить"))
          (when-let [v (:verdict st)]
            ($ :span {:class (str "badge " (if (:valid? v) "badge-success" "badge-danger"))}
               (if (:valid? v) "OK" (str "✗ " (:detail v)))))
          (when (:saved? st)
            ($ :span {:class "badge badge-success"} "Сохранено и применено"))
          (when-let [err (:error st)]
            ($ :span {:class "badge badge-danger"} err))))))

(defui settings []
  (let [data   (use-subscribe [::subs/settings-data])
        status (use-subscribe [::subs/settings-status])]
    (use-effect (fn [] (rf/dispatch [::events/load-settings]) js/undefined) [])
    ($ :div {:class "page-content"}
       ($ :p {:style {:color "var(--color-fg-muted)" :margin-bottom "12px"}}
          "Секреты хранятся на сервере и применяются сразу — перезапуск не нужен. "
          "Пустое поле при сохранении оставляет текущее значение без изменений.")
       (for [spec mp-specs]
         ($ mp-card {:key (name (:mp spec)) :spec spec
                     :data (or data {}) :status (or status {})})))))
