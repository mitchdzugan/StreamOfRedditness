(ns stream-of-redditness.dispatch
  (:require [datival.core :as dv]
            [stream-of-redditness.events :as e]
            [stream-of-redditness.config :as c]))

(def dispatch (dv/make-event-system c/debug? (e/stream-of-redditness-events c/event-config)))
