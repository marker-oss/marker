(ns analitica.report.export
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.ative.docjure.spreadsheet :as xl]))

;; ---------------------------------------------------------------------------
;; CSV export (UTF-8 BOM for Excel compatibility)
;; ---------------------------------------------------------------------------

(defn- ensure-parent-dirs [path]
  (let [f (io/file path)]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))))

(defn- format-csv-value [v]
  (cond
    (nil? v)     ""
    (string? v)  (if (or (str/includes? v ",")
                         (str/includes? v "\"")
                         (str/includes? v "\n"))
                   (str "\"" (str/replace v "\"" "\"\"") "\"")
                   v)
    (float? v)   (format "%.2f" (double v))
    (double? v)  (format "%.2f" v)
    :else        (str v)))

(defn to-csv
  "Export data to CSV file (UTF-8 with BOM for Excel).
   cols: vector of [key label] pairs
   rows: seq of maps
   Returns the path."
  [path cols rows]
  (ensure-parent-dirs path)
  (let [headers (mapv second cols)
        keys    (mapv first cols)]
    (with-open [w (io/writer path :encoding "UTF-8")]
      ;; UTF-8 BOM
      (.write w "\uFEFF")
      ;; Header
      (.write w (str (str/join ";" (map format-csv-value headers)) "\n"))
      ;; Rows
      (doseq [row rows]
        (.write w (str (str/join ";" (map #(format-csv-value (get row %)) keys)) "\n"))))
    (println (str "CSV сохранён: " path " (" (count rows) " строк)"))
    path))

;; ---------------------------------------------------------------------------
;; Excel export (via docjure)
;; ---------------------------------------------------------------------------

(defn to-excel
  "Export data to Excel .xlsx file.
   sheets: vector of {:name \"Sheet\" :cols [[key label]...] :rows [maps...]}
   or single sheet: path sheet-name cols rows"
  ([path sheets]
   (ensure-parent-dirs path)
   (let [wb (xl/create-workbook
             (-> sheets first :name)
             (let [{:keys [cols rows]} (first sheets)
                   headers (mapv second cols)
                   keys    (mapv first cols)]
               (into [headers]
                     (mapv (fn [row]
                             (mapv (fn [k]
                                     (let [v (get row k)]
                                       (if (or (nil? v) (keyword? v))
                                         (str v)
                                         v)))
                                   keys))
                           rows))))]
     ;; Style header row
     (let [sheet    (xl/select-sheet (-> sheets first :name) wb)
           header-style (xl/create-cell-style! wb {:font {:bold true}
                                                    :background :pale_blue})]
       (xl/set-row-style! (first (xl/row-seq sheet)) header-style)
       ;; Auto-size columns
       (doseq [i (range (count (-> sheets first :cols)))]
         (.autoSizeColumn sheet i)))
     ;; Additional sheets
     (doseq [{:keys [name cols rows]} (rest sheets)]
       (let [headers (mapv second cols)
             keys    (mapv first cols)
             sheet   (xl/add-sheet! wb name)]
         (xl/add-rows! sheet
                       (into [headers]
                             (mapv (fn [row]
                                     (mapv (fn [k]
                                             (let [v (get row k)]
                                               (if (or (nil? v) (keyword? v))
                                                 (str v)
                                                 v)))
                                           keys))
                                   rows)))
         (let [header-style (xl/create-cell-style! wb {:font {:bold true}
                                                        :background :pale_blue})]
           (xl/set-row-style! (first (xl/row-seq sheet)) header-style)
           (doseq [i (range (count cols))]
             (.autoSizeColumn sheet i)))))
     (xl/save-workbook! path wb)
     (println (str "Excel сохранён: " path " (" (count sheets) " лист(ов))"))
     path))
  ([path sheet-name cols rows]
   (to-excel path [{:name sheet-name :cols cols :rows rows}])))
