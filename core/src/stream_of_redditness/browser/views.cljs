(ns stream-of-redditness.browser.views
  (:require [stream-of-redditness.conn :as conn]
            [stream-of-redditness.events :as events]
            [stream-of-redditness.datival.core :as dv]
            [stream-of-redditness.events.auth :as auth]))

(def main-panel
  (dv/make-ui conn/conn
              [{:root/auth [:auth/flow
                            {:auth/current-user [:db/id
                                                 :user/name]}]}]
              {:render (fn [[{{{:keys [db/id user/name]} :auth/current-user
                               :keys [auth/flow]} :root/auth :as all}]]
                         (cond
                           name [:div
                                 [:p (str "Welcome, " name)]
                                 [:button
                                  {:onClick #(events/dispatch :auth-logout)}
                                  "Click To Logout"]]
                           (not ((or flow :complete) #{:error :complete})) [:p (str "Loading... {" flow "}")]
                           :else [:a {:href (str "https://www.reddit.com"
                                                 "/api/v1/authorize"
                                                 "?client_id=33V8GP9wAN_11g"
                                                 "&response_type=code"
                                                 "&state=RANDOM_STRING"
                                                 "&redirect_uri="
                                                 "http://mdzugan.001www.com"
                                                 "&duration=permanent"
                                                 "&scope=edit read report save submit vote identity"
                                                 "")} "Click here to login"]))}))
