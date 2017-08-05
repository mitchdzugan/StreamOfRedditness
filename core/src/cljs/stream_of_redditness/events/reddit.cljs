(ns stream-of-redditness.events.reddit
  (:require [datival.core :as dv]
            [ajax.core :as ajax]
            [datascript.core :as d]
            [stream-of-redditness.conn :as c]
            [stream-of-redditness.util :as util]))

(defn make-datascript-event
  ([q f] (make-datascript-event q [:db/role :anchor] f))
  ([query id f]
   {:sources [:datascript :now]
    :body (fn [state args]
            (f state (merge args {:datascript (d/pull (:datascript args) query id)})))}))

(def set-threads
  (make-datascript-event
   [{:root/polling [{:polling/threads [:thread/id]}]}]
   (fn [_ {new-threads :user
           {{old-threads :polling/threads} :root/polling} :datascript}]
     (let [unique-thread-ids #(let [s (set (map :thread/id %2))]
                                (remove (fn [{:keys [thread/id]}]
                                          (s id)) %1))
           unborn-threads (unique-thread-ids new-threads old-threads)
           dead-threads (unique-thread-ids old-threads new-threads)]
       {:datascript (concat (->> unborn-threads
                                 (map (fn [{:keys[thread/id thread/color]}]
                                        {:db/path [[:db/role :anchor]
                                                   :root/polling
                                                   :polling/threads]
                                         :thread/id id
                                         :thread/color color
                                         :thread/introduced? false})))
                            (->> dead-threads
                                 (map :thread/id)
                                 (map #(-> {:db/retract-path [[:db/role :anchor]
                                                              :root/polling
                                                              :polling/threads
                                                              [:thread/id %]]}))))
        :dispatch [:poll-reddit :init]}))))

(def poll-thread
  (make-datascript-event
   [{:root/polling [:polling/is-polling?
                    {:polling/threads [:thread/id
                                       :thread/polls-since-root
                                       :thread/mores
                                       :thread/last-poll]}]}
    {:root/auth [{:auth/current-user [:user/token]}]}]
   (fn [_ {{{:keys [polling/is-polling?  polling/threads]} :root/polling
          {{:keys [user/token]} :auth/current-user} :root/auth} :datascript
           now :now poll-type :user}]
     (if-not (and (= :init poll-type) is-polling?)
       (if-let [thread-to-poll (->> threads
                                    (sort-by :thread/last-poll)
                                    first)]
         (let [polls-since-root (or (:thread/polls-since-root thread-to-poll)
                                    0)
               poll-root? (or (not token)
                              (> polls-since-root 3)
                              (empty? (:thread/mores thread-to-poll)))
               [{:keys [children]} mores] (util/pop-when (:thread/mores thread-to-poll) #(not poll-root?))
               api-call (cond
                          (and poll-root? token)
                          {:method          :get
                           :uri             (str "https://localhost:8080/comments/"
                                                 (:thread/id thread-to-poll))
                           :response-format (ajax/json-response-format {:keywords? true})
                           :format          :json
                           :params          {:sort "new"}
                           :headers         {:authorization (str "bearer " token)
                                             :content-type "application/json; charset=UTF-8"}
                           :on-success      [:reddit-root-poll-res (:thread/id thread-to-poll)]
                           :on-failure      [:poll-reddit :loop]}
                          poll-root?
                          {:method          :get
                           :uri             (str "https://www.reddit.com/comments/"
                                                 (:thread/id thread-to-poll) ".json?sort=new")
                           :response-format (ajax/json-response-format {:keywords? true})
                           :on-success      [:reddit-root-poll-res (:thread/id thread-to-poll)]
                           :on-failure      [:poll-reddit :loop]}
                          :else
                          {:method          :get
                           :uri             "https://localhost:8080/api/morechildren/"
                           :response-format (ajax/json-response-format {:keywords? true})
                           :format          :json
                           :params          {:api_type "json"
                                             :children (->> children
                                                            (reduce #(str %1 "," %2) "")
                                                            (#(subs % 1)))
                                             :link_id (str "t3_" (:thread/id thread-to-poll))
                                             :sort "new"}
                           :on-success      [:reddit-more-poll-res (:thread/id thread-to-poll)]
                           :on-failure      [:poll-reddit :loop]
                           :headers         {:authorization (str "bearer " token)
                                             :content-type "application/json; charset=UTF-8"}})]
           {:dispatch [:poll-request api-call]
            :datascript [{:db/path [[:db/role :anchor] :root/polling]
                          :polling/is-polling? true}
                         {:db/id [:thread/id (:thread/id thread-to-poll)]
                          :thread/polls-since-root (if poll-root? 0 (inc polls-since-root))
                          :thread/last-poll now
                          :thread/mores mores}]}
           )
         {:datascript [{:db/path [[:db/role :anchor] :root/polling]
                        :polling/is-polling? false}]})
       {}))))

(def poll-request
  (make-datascript-event
   [{:root/auth [{:auth/current-user [:user/token]}]}
    {:root/polling [:polling/calls-since-poll]}]
   (fn [_ {{{:keys [polling/calls-since-poll]} :root/polling
            {{:keys [user/token]} :auth/current-user} :root/auth} :datascript
         api-call :user}]
     (let [calls-since-poll (or calls-since-poll 0)]
       (if (= 0 calls-since-poll)
         {:dispatch [:reddit-api-request api-call]}
         {:dispatch-after [(* (if token 1000 30000) calls-since-poll)
                           :poll-request api-call]
          :datascript [{:db/path [[:db/role :anchor] :root/polling]
                        :polling/calls-since-poll 0}]})))))

(def api-request
  (make-datascript-event
   [{:root/polling [:polling/calls-since-poll]}]
   (fn [_ {{{:keys [polling/calls-since-poll]} :root/polling} :datascript
           api-call :user}]
     (let [calls-since-poll (or calls-since-poll 0)
           on-failure (:on-failure api-call)]
       {:ajax (merge api-call {:on-failure [:reddit-api-request-failed [api-call on-failure]]})
        :datascript [{:db/path [[:db/role :anchor] :root/polling]
                      :polling/calls-since-poll (inc calls-since-poll)}]}))))

(def api-request-failed
  (make-datascript-event
   [{:root/auth [{:auth/current-user [:user/refresh]}]}]
   (fn [_ {{{{:keys [user/refresh]} :auth/current-user} :root/auth} :datascript
           [[api-call on-failure] {:keys [response]}] :user}]
     (cond (and (= "Unauthorized" (:message response))
                (= 401 (:error response)))
           {:dispatch [:reddit-api-request
                       {:method          :post
                        :uri             "https://localhost:8080/api/v1/access_token"
                        :response-format (ajax/json-response-format {:keywords? true})
                        :body            (str "grant_type=refresh_token"
                                              "&refresh_token=" refresh)
                        :on-success      [:refresh-success [api-call on-failure]]
                        :on-failure      [:refresh-failed on-failure]
                        :headers         {:authorization "Basic MzNWOEdQOXdBTl8xMWc6"
                                          :content-type "application/x-www-form-urlencoded"}}]}
           on-failure {:dispatch on-failure}
           :else {}))))

(def refresh-success
  (make-datascript-event
   [{:root/auth [{:auth/current-user [:user/name]}]}]
   (fn [_ {{{{:keys [user/name]} :auth/current-user} :root/auth} :datascript
           [[api-call on-failure] {:keys [error access_token]}] :user}]
     (if error
       {:dispatch on-failure}
       {:datascript [{:db/id [:user/name name]
                      :user/token access_token}]
        :dispatch [:reddit-api-request
                   (assoc-in api-call
                             [:headers :authorization]
                             (str "bearer " access_token))]}))))

(def refresh-failed {:body (fn [_ _] (println "refresh-failed :[") {})})

(def root-poll-res {:body (fn [_ _])})
(def more-poll-res {:body (fn [_ _])})

