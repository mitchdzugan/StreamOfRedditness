(ns stream-of-redditness.events
  (:require [re-frame.core :as re-frame]
            [stream-of-redditness.db :as db]))

(re-frame/reg-event-db
 :initialize-db
 (fn  [_ _]
   db/default-db))
