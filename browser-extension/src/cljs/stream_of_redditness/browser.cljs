(ns stream-of-redditness.browser
  (:require [reagent.core :as reagent]
            [stream-of-redditness.views :as views]
            [stream-of-redditness.events :as events]
            [stream-of-redditness.config :as config]))


(defn dev-setup []
  (when config/debug?
    (enable-console-print!)))

(defn mount-root []
  (reagent/render [views/main-panel]
                  (.getElementById js/document "app")))

(defn ^:export init []
  (dev-setup)
  (mount-root))
