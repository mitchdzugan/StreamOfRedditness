(ns stream-of-redditness.views.util
  (:require [re-com.core :refer [h-box v-box hyperlink-href single-dropdown]]))

(defn box
  [config child]
  (apply re-com.core/box (concat (mapcat (fn [[k v]] [k v]) config)
                                 [:child child])))

(defn boxx
  [el config & children]
  (let [main (apply el (concat (mapcat (fn [[k v]] [k v]) (dissoc config :box))
                               [:children children]))]
    (if (get-in config [:box :dont-use])
      main
      (box (:box config) main))))

(defn boxv [config & children] (apply boxx v-box config children))
(defn boxh [config & children] (apply boxx h-box config children))
