(ns stream-of-redditness.events.reddit
  (:require [datival.core :as dv]
            [ajax.core :as ajax]
            [datascript.core :as d]
            [stream-of-redditness.conn :as c]
            [stream-of-redditness.util :as util]))

(defn make-datascript-event
  ([q f] (make-datascript-event q [:db/role :anchor] f []))
  ([q x1 x2]
   (if (not (fn? x2))
     (make-datascript-event q [:db/role :anchor] x1 x2)
     (make-datascript-event q x1 x2 [])))
  ([query id f sources]
   {:sources (conj sources :datascript)
    :body (fn [state args]
            (f state (update args :datascript
                             #(d/pull % query (if (fn? id) (id args) id)))))}))

(defn flatten-comment-tree
  [comments {:keys [kind data]}]
  (if (= "more" kind)
    (conj (->> data
               :children
               (partition-all 20)
               (map #(assoc data :kind :more :children %))
               (reduce conj comments))
          {:db/id (dv/tempid)
           :comment/id (:id data)})
    (let [{:keys [id score author body created_utc replies parent_id gilded edited
                  author_flair_css_class score_hidden author_flair_text]} data]
      (conj
       (reduce flatten-comment-tree comments (get-in replies [:data :children]))
       {:db/id (dv/tempid)
        :comment/id id
        :comment/score score
        :comment/author author
        :comment/body body
        :comment/created created_utc
        :comment/parent parent_id
        :comment/gilded? (or gilded false)
        :comment/edited? (or edited false)
        :comment/author-flair-css-class (or author_flair_css_class "")
        :comment/score-hidden? (or score_hidden false)
        :comment/author-flair-text (or author_flair_text "")
        :comment/children (->> replies
                               :data
                               :children
                               (map #(get-in % [:data :id]))
                               (map #(-> [:comment/id %])))}))))

(defn comment-tree-res
  [thread-id mores comments]
  (let [flat-tree (reduce flatten-comment-tree [] comments)
        pred #(= :more (:kind %))
        [new-mores comment-datoms] [(filter pred flat-tree)
                                    (remove pred flat-tree)]]
    {:dispatch [:begin-set-comment-markdown (->> comment-datoms
                                                 (filter :comment/body)
                                                 (map (fn [{:keys [comment/id comment/body] :as c}]
                                                        {:id id
                                                         :body body
                                                         :hash (.md5 js/window body)})))]
     :datascript (concat
                  comment-datoms
                  (->> comment-datoms
                       (remove #(nil? (:comment/parent %)))
                       (map (fn [{:keys [comment/id comment/parent]}]
                              (if (= (str "t3_" thread-id) parent)
                                {:db/id [:thread/id thread-id]
                                 :thread/top-level-comments [:comment/id id]}
                                {:db/id [:comment/id (subs parent 3)]
                                 :comment/children [:comment/id id]}))))
                  [{:db/id [:thread/id thread-id]
                    :thread/mores (concat mores new-mores)}])}))

(def root-poll-res
  (make-datascript-event
   [:thread/mores]
   (fn [{[id] :user}] [:thread/id id])
   (fn [_ {[thread-id [_ {{comments :children} :data}]] :user
           {:keys [thread/mores]} :datascript}]
     (comment-tree-res thread-id mores comments))))


(def more-poll-res
  (make-datascript-event
   [:thread/mores]
   (fn [{[id] :user}] [:thread/id id])
   (fn [_ {[thread-id {{{comments :things} :data} :json}] :user
           {:keys [thread/mores]} :datascript}]
     (comment-tree-res thread-id mores comments))))

(defn begin-set-comment-markdown
  [state {comments :user}]
  {:state (dv/deep-merge state {:markdown {:groups (partition-all 20 comments)
                                           :datoms []
                                           :root-comment-sizes {}}})
   :dispatch-after [150 :set-comment-markdown]})

(def set-comment-markdown
  {:sources [:datascript]
   :body (fn [{{:keys [cache groups datoms root-comment-sizes]} :markdown :as state}
              {db :datascript}]
           (let [[group & groups] groups]
             (if group
               (let [original-sizes (->> (map :id group)
                                         (d/q '[:find ?eid ?size
                                                :in $ [?cid ...]
                                                :where
                                                [?mid :markdown/size ?size]
                                                [?eid :comment/markdown ?mid]
                                                [?eid :comment/id ?cid]] db)
                                         (into {}))
                     reverse-comment-tree (->> (map :id group)
                                               (d/q '[:find (pull ?eid [:comment/size :comment/id
                                                                        {:comment/_children ...}])
                                                      :in $ [?cid ...]
                                                      :where [?eid :comment/id ?cid]] db)
                                               flatten)
                     root-comment-map (->> reverse-comment-tree
                                           (map #(loop [{:keys [comment/id comment/_children]} %]
                                                   (if _children
                                                     (recur _children)
                                                     {(:comment/id %) id})))
                                            (reduce merge {}))
                     [new-datoms cache] (reduce (fn [[new-datoms cache]
                                                     {:keys [id hash body]}]
                                                  (if (contains? cache hash)
                                                    [new-datoms cache]
                                                    [(conj new-datoms
                                                           (let [parsed (-> js/window .-markdown
                                                                            (.parse body "Maruku")
                                                                            js->clj)]
                                                             {:db/id (dv/tempid)
                                                              :comment/id id
                                                              :markdown/parsed parsed
                                                              :markdown/size (count (str parsed))
                                                              :markdown/hash hash}))
                                                     (clojure.set/union cache #{hash})]))
                                                [[] (or cache #{})] group)
                     root-comment-sizes (merge (->> reverse-comment-tree
                                                    (map #(loop [{:keys [comment/id comment/_children comment/size]} %]
                                                            (if _children
                                                              (recur _children)
                                                              {id (or size 0)})))
                                                    (reduce merge {}))
                                               root-comment-sizes)
                     root-comment-sizes (reduce
                                         (fn [root-comment-sizes {:keys [comment/id markdown/size]}]
                                           (let [original-size (or (original-sizes id) 0)
                                                 delta (- size original-size)
                                                 root-id (root-comment-map id)
                                                 root-original-size (or (root-comment-sizes root-id) 0)]
                                             (merge root-comment-sizes
                                                    {root-id (+ root-original-size delta)})))
                                         root-comment-sizes
                                         new-datoms)]
                 {:state (merge state {:markdown {:groups groups
                                                  :datoms (concat datoms
                                                                  (map #(dissoc % :comment/id) new-datoms)
                                                                  (map (fn [{:keys [id hash]}]
                                                                         {:db/id [:comment/id id]
                                                                          :comment/markdown [:markdown/hash hash]
                                                                          :comment/loaded? true}) group))
                                                  :cache cache
                                                  :root-comment-sizes root-comment-sizes}})
                  :dispatch-after [150 :set-comment-markdown]})
               (do
                 (dv/log :datoms-check (concat datoms (map (fn [[id size]]
                                                             {:db/id [:comment/id id]
                                                              :comment/size size})
                                                           root-comment-sizes)))
                 {:datascript (concat datoms (map (fn [[id size]]
                                                    {:db/id [:comment/id id]
                                                     :comment/size size})
                                                  root-comment-sizes))
                  :dispatch-after [100 :poll-reddit :loop]
                  :dispatch [:pick-rendered-comments]}))))})

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
   (fn [{{{:keys [web-host api-host]} :env}:_config}
        {{{:keys [polling/is-polling?  polling/threads]} :root/polling
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
                           :uri             (str "https://" api-host "/comments/"
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
                           :uri             (str "https://" web-host "/comments/"
                                                 (:thread/id thread-to-poll) ".json?sort=new")
                           :response-format (ajax/json-response-format {:keywords? true})
                           :on-success      [:reddit-root-poll-res (:thread/id thread-to-poll)]
                           :on-failure      [:poll-reddit :loop]}
                          :else
                          {:method          :get
                           :uri             (str "https://" api-host "/api/morechildren/")
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
       {}))
   [:now]))

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
   (fn [{{{:keys [web-host client-auth]} :env}:_config}
        {{{{:keys [user/refresh]} :auth/current-user} :root/auth} :datascript
           [[api-call on-failure] {:keys [response]}] :user}]
     (cond (and (= "Unauthorized" (:message response))
                (= 401 (:error response)))
           {:dispatch [:reddit-api-request
                       {:method          :post
                        :uri             (str "https://" web-host
                                              "/api/v1/access_token")
                        :response-format (ajax/json-response-format {:keywords? true})
                        :body            (str "grant_type=refresh_token"
                                              "&refresh_token=" refresh)
                        :on-success      [:refresh-success [api-call on-failure]]
                        :on-failure      [:refresh-failed on-failure]
                        :headers         {:authorization (str "Basic " client-auth)
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

(defn refresh-failed [_ _] (println "refresh-failed :[") {})

