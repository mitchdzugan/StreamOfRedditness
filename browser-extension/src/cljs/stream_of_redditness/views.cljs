(ns stream-of-redditness.views
  (:require [stream-of-redditness.conn :as conn]
            [stream-of-redditness.dispatch :as dispatch]
            [stream-of-redditness.config :as c]
            [datival.core :as dv]))

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
                                  {:onClick #(dispatch/dispatch :auth-logout)}
                                  "Click To Logout"]]
                           (not ((or flow :complete) #{:error :complete})) [:p (str "Loading... {" flow "}")]
                           :else [:a {:href (str "https://www.reddit.com"
                                                 "/api/v1/authorize"
                                                 "?client_id="
                                                 c/client-id
                                                 "&response_type=code"
                                                 "&state=RANDOM_STRING"
                                                 "&redirect_uri="
                                                 c/redirect-url
                                                 "&duration=permanent"
                                                 "&scope=edit read report save submit vote identity"
                                                 "")} "Click here to login"]))}))
