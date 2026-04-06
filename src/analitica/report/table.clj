(ns analitica.report.table
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [analitica.util.math :as math]))

(defn print-table
  "Print a table with column headers. Accepts:
   - cols: vector of keywords or [keyword label] pairs
   - rows: seq of maps"
  [cols rows]
  (let [col-defs  (mapv (fn [c] (if (vector? c) {:key (first c) :label (second c)} {:key c :label (name c)})) cols)
        headers   (mapv :label col-defs)
        keys      (mapv :key col-defs)
        str-rows  (mapv (fn [row]
                          (mapv (fn [k]
                                  (let [v (get row k)]
                                    (cond
                                      (nil? v)    ""
                                      (float? v)  (format "%.2f" (double v))
                                      (double? v) (format "%.2f" v)
                                      :else       (str v))))
                                keys))
                        rows)
        all-rows  (cons headers str-rows)
        widths    (reduce (fn [ws row]
                            (mapv (fn [w cell] (max w (count cell))) ws row))
                          (vec (repeat (count headers) 0))
                          all-rows)
        fmt-row   (fn [cells]
                    (str "│ "
                         (str/join " │ "
                                   (map-indexed (fn [i cell]
                                                  (let [w (nth widths i)]
                                                    (if (or (= i 0) (re-matches #"[^\d.].*" cell))
                                                      (format (str "%-" w "s") cell)
                                                      (format (str "%" w "s") cell))))
                                                cells))
                         " │"))
        separator (str "├─"
                       (str/join "─┼─" (map #(apply str (repeat % "─")) widths))
                       "─┤")
        top       (str "┌─"
                       (str/join "─┬─" (map #(apply str (repeat % "─")) widths))
                       "─┐")
        bottom    (str "└─"
                       (str/join "─┴─" (map #(apply str (repeat % "─")) widths))
                       "─┘")]
    (println top)
    (println (fmt-row headers))
    (println separator)
    (doseq [row str-rows]
      (println (fmt-row row)))
    (println bottom)))

(defn print-summary
  "Print key-value summary block."
  [title kvs]
  (println (str "\n═══ " title " ═══"))
  (doseq [[k v] kvs]
    (let [v-str (cond
                  (nil? v)    "—"
                  (float? v)  (format "%,.2f" (double v))
                  (double? v) (format "%,.2f" v)
                  (integer? v) (format "%,d" v)
                  :else       (str v))]
      (println (format "  %-25s %s" (str k ":") v-str)))))
