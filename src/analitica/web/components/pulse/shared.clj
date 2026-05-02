(ns analitica.web.components.pulse.shared
  "Chrome shared by every Pulse section. Centralizing layout here keeps
   8 individual section namespaces focused on data → metric mapping.")

(defn section-card
  "Wrap section content in a titled white card.
   Options:
     :title    — heading string (required)
     :subtitle — optional grey subtitle under the title
     :body     — hiccup vector with the section's metrics
     :footer   — optional hiccup vector (e.g. note / link)"
  [{:keys [title subtitle body footer]}]
  [:section.bg-white.rounded-lg.shadow-sm.border.border-gray-200.p-5.mb-4
   [:header.mb-3
    [:h2.text-lg.font-semibold.text-gray-900 title]
    (when subtitle [:p.text-sm.text-gray-500.mt-1 subtitle])]
   [:div body]
   (when footer [:footer.mt-3.pt-3.border-t.border-gray-100 footer])])

(defn coming-soon
  "Stub body shown by sections that aren't implemented yet."
  [label]
  [:div.text-sm.text-gray-500.italic
   (str "Раздел «" label "» — скоро. Готовим расчёт.")])

(defn empty-state
  "Body for sections that have no data this period."
  [label]
  [:div.text-sm.text-gray-500
   (str "Нет данных для раздела «" label "» за выбранный период.")])
