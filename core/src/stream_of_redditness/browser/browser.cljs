(ns stream-of-redditness.browser.browser
  (:require [reagent.core :as reagent]
            [stream-of-redditness.datival.core :as datival]
            [stream-of-redditness.events :as events]
            [stream-of-redditness.browser.views :as views]))



(defn dev-setup []
  (when true
    (enable-console-print!)))

(defn mount-root []
  (reagent/render [views/main-panel]
                  (.getElementById js/document "app")))

(defn ^:export init []
  (dev-setup)
  ;; (events/dispatch :nothing)
  (mount-root))
