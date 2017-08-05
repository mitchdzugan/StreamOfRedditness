(ns stream-of-redditness.events.auth
  (:require [datival.core :as dv]
            [ajax.core :as ajax]
            [stream-of-redditness.conn :as c]))

(def flow-submit-code
  {:body (fn [{{{:keys [client-auth web-host redirect-url]} :env}:_config}
              {code :user}]
           {:datascript [{:db/path [[:db/role :anchor] :root/auth]
                          :auth/flow :submit-code}]
            :ajax {:method          :post
                   :uri             (str "https://" web-host "/api/v1/access_token")
                   :response-format (ajax/json-response-format {:keywords? true})
                   :body            (str
                                     "grant_type=authorization_code"
                                     "&code=" (get code "code")
                                     "&redirect_uri="
                                     redirect-url)
                   :on-success      [:auth-flow-token-success]
                   :on-failure      [:auth-error]
                   :headers         {:authorization (str "Basic " client-auth)
                                     :content-type "application/x-www-form-urlencoded"}}})})

(def flow-token-success
  {:body (fn [{{{:keys [api-host]} :env}:_config}
              {[_ {:keys [error access_token refresh_token]}] :user :as all}]
           (if error
             {:dispatch [:auth-error error]}
             {:datascript [{:db/path [[:db/role :anchor] :root/auth]
                            :auth/flow :get-me}]
              :ajax {:method          :get
                     :uri             (str "https://" api-host "/api/v1/me")
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
