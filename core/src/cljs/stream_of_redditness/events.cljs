(ns stream-of-redditness.events
  (:require [datival.core :as dv]
            [ajax.core :as ajax]
            [cemerick.url :as url]
            [stream-of-redditness.conn :as c]
            [stream-of-redditness.events.auth :as auth]
            [stream-of-redditness.events.reddit :as reddit]
            ))

(defn stream-of-redditness-events
  [{:keys [platform] :as config}]
  (concat
    (:event-systems config)
    [dv/dispatch-system
     dv/ajax-system
     (dv/datascript-system {:pull               [{:root/auth [:auth/flow
                                                              :auth/error
                                                              {:auth/current-user [:user/name
                                                                                   :user/token
                                                                                   :user/refresh]
                                                               :auth/users        [:user/name
                                                                                   :user/token
                                                                                   :user/refresh]}]}
                                                 {:root/polling [:polling/calls-since-poll
                                                                 :polling/is-polling?
                                                                 {:polling/threads [:thread/id
                                                                                    :thread/polls-since-root
                                                                                    :thread/last-poll
                                                                                    :thread/mores]}]}]
                            :sync-local-storage {:platform platform
                                                 :selector
                                                           [{:root/auth [:auth/flow
                                                                         :auth/error
                                                                         {:auth/current-user [:user/name
                                                                                              :user/token
                                                                                              :user/refresh]}
                                                                         {:auth/users [:user/name
                                                                                       :user/token
                                                                                       :user/refresh]}]}]
                                                 :key      "datoms"}} c/conn)
     {:sources {:now (fn [] ((:now config)))}
      :events  {:set-threads                reddit/set-threads
                :poll-reddit                reddit/poll-thread
                :poll-request               reddit/poll-request
                :reddit-api-request         reddit/api-request
                :reddit-api-request-failed  reddit/api-request-failed
                :reddit-root-poll-res       reddit/root-poll-res
                :reddit-more-poll-res       reddit/more-poll-res
                :refresh-success            reddit/refresh-success
                :refresh-failed             reddit/refresh-failed
                :begin-set-comment-markdown reddit/begin-set-comment-markdown
                :set-comment-markdown       reddit/set-comment-markdown
                :auth-flow-submit-code      auth/flow-submit-code
                :auth-flow-token-success    auth/flow-token-success
                :auth-flow-me-success       auth/flow-me-success
                :auth-flow-begin            auth/flow-begin
                :auth-error                 auth/error
                :auth-logout                auth/logout
                :switch-account             {:body (fn [_ _])}}}]))
