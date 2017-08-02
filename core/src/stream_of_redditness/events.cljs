(ns stream-of-redditness.events
  (:require [datival.core :as dv]))

(def stream-of-redditness-events
  {
   :add-thread {:body (fn [_ [_ id color]]{:datascript [{:db/path [[:db/role :anchor] :root/polling :polling/threads]}
                                                        :thread/id id
                                                        :thread/introduced? false
                                                        :thread/color color]
                                           :dispatch [:poll-reddit :init]})}
   :poll-reddit {:sources [:datascript]
                 :body (fn [_ _])}
   })

