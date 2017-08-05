(ns stream-of-redditness.events.auth
  (:require [datival.core :as dv]
            [ajax.core :as ajax]
            [stream-of-redditness.conn :as c]))

(def flow-submit-code
  {:body (fn [_ {code :user}]
           {:datascript [{:db/path [[:db/role :anchor] :root/auth]
                          :auth/flow :submit-code}]
            :ajax {:method          :post
                   :uri             "https://localhost:8080/api/v1/access_token"
                   :response-format (ajax/json-response-format {:keywords? true})
                   :body            (str
                                     "grant_type=authorization_code"
                                     "&code=" (get code "code")
                                     "&redirect_uri=http://mdzugan.001www.com")
                   :on-success      [:auth-flow-token-success]
                   :on-failure      [:auth-error]
                   :headers         {:authorization "Basic MzNWOEdQOXdBTl8xMWc6"
                                     :content-type "application/x-www-form-urlencoded"}}})})

(def flow-token-success
  {:body (fn [_ {[_ {:keys [error access_token refresh_token]}] :user :as all}]
           (if error
             {:dispatch [:auth-error error]}
             {:datascript [{:db/path [[:db/role :anchor] :root/auth]
                            :auth/flow :get-me}]
              :ajax {:method          :get
                     :uri             "https://localhost:8080/api/v1/me"
                     :response-format (ajax/json-response-format {:keywords? true})
                     :on-success      [:auth-flow-me-success [access_token refresh_token]]
                     :on-failure      [:auth-error]
                     :headers         {:authorization (str "bearer " access_token)}}}))})

(def flow-me-success
  {:body (fn [_ {[[token refresh] {:keys [name]}] :user}]
           {:datascript [{:db/id (dv/tempid)
                          :user/name name
                          :user/token token
                          :user/refresh refresh}
                         {:db/path [[:db/role :anchor] :root/auth]
                          :auth/flow :complete
                          :auth/current-user [:user/name name]
                          :auth/users [:user/name name]}]})})

(def flow-begin
  {:body (fn [_ _]
           {:datascript [{:db/path [[:db/role :anchor] :root/auth]
                          :auth/flow :begin}]})})

(def error
  {:body (fn [_ {error-message :user}]
           {:datascript [{:db/path [[:db/role :anchor] :root/auth]
                          :auth/flow :error
                          :auth/error error-message}]})})

(def logout
  {:body (fn [_ _]
           {:datascript [{:db/retract-path [[:db/role :anchor]
                                            :root/auth
                                            :auth/current-user]}]})})
