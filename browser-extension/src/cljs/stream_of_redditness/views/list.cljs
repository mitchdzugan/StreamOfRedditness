(ns stream-of-redditness.views.list
  (:require [stream-of-redditness.conn :as conn]
            [stream-of-redditness.dispatch :as dispatch]
            [stream-of-redditness.config :as c]
            [datival.core :as dv]
            [re-com.core :refer [h-box v-box hyperlink-href single-dropdown]]))

(defn list-view [] (fn []))