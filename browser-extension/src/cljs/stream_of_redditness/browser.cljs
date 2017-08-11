(ns stream-of-redditness.browser
  (:require [reagent.core :as reagent]
            [markdown.js]
            [moment.js]
            [md5.js]
            [stream-of-redditness.views.tree :as tree]
            [stream-of-redditness.events :as events]
            [stream-of-redditness.config :as config]))


(defn dev-setup []
  (when config/debug?
    (enable-console-print!)))

(defn mount-root []
  (reagent/render [tree/main-panel]
                  (.getElementById js/document "app")))

(defn ^:export init []
  (dev-setup)
  (mount-root))
