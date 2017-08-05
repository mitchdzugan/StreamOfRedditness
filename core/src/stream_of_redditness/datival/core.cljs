(ns stream-of-redditness.datival.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [clojure.data :as data]
            [datascript.core :as d]
            [posh.reagent :as posh]
            [reagent.ratom :as r]
            [cljs.reader :refer [read-string]]
            [bidi.bidi :as bidi]
            [pushy.core :as pushy]
            [cljs.core.async :refer [put! chan <! >! timeout close!]]
            [ajax.core :as ajax]
            [goog.net.ErrorCode :as errors]))

(defn leaves
  ([m] (leaves m []))
  ([m l] (if (map? m) (reduce (fn [l [k v]] (concat l (leaves v))) l m) [m])))

(defn map-leaves
  [f m]
  (if (map? m) (->> m (map (fn [[k v]] [k (map-leaves f v)])) (into {})) (f m)))

(defn make-ui [conn query funcs]
  (let [funcs (merge {:id (fn [_] [:db/role :anchor])
                      :setup (fn [_] nil)}
                     funcs)]
    (fn [& args]
      ((:setup funcs) args)
      (fn [& args]
        (let [res @(posh/pull conn query ((:id funcs) args))]
          ((:render funcs) (conj args res)))))))

(def tempid-atom (atom -1))
(defn tempid []
  (let [retval @tempid-atom]
    (swap! tempid-atom dec)
    retval))

(defn handle-transaction [conn datoms]
  (let [path-cache (atom {})]
    (letfn [(lift [x] [x])
            (reduce-pull-path [acc curr] (assoc {} curr [acc]))
            (pull-path
              [eid-root path]
              (let [sel (->> path rest reverse (reduce reduce-pull-path :db/id) lift)]
                (d/pull (d/db conn) sel eid-root)))
            (use-path
              [datom]
              (if-let [path (:db/path datom)]
                (if-let [id (get @path-cache path)]
                  [(-> datom (dissoc :db/path) (assoc :db/id id))]
                  (let [eid-root (first path)
                        pull-res (pull-path eid-root path)
                        eid (or (get-in pull-res (concat (rest path) [:db/id]))
                                (tempid))
                        datoms (conj
                                (if-not pull-res
                                  (let [path-init (-> path reverse rest reverse)
                                        path-last (-> path reverse first)]
                                    (if (> (count path-init) 1)
                                      (use-path {:db/path path-init
                                                 path-last eid})
                                      [{:db/id (first path-init)
                                        path-last eid}])))
                                (-> datom (assoc :db/id eid) (dissoc :db/path)))]
                    (swap! path-cache assoc path (-> datoms last :db/id))
                    datoms))
                [datom]))
            (get-retract-ids
              [[curr & path] res]
              (if curr
                (let [datoms (mapcat #(get-retract-ids path %)
                                     (flatten [(get res curr)]))]
                  (if (and (:db/id res)
                           (= [] datoms))
                    [{:db/id (:db/id res)
                      :attribute curr}]
                    datoms))
                (if (:db/id res)
                  [{:db/id (:db/id res)
                    :attribute nil}]
                  [])))
            (use-retract-path
              [datom]
              (if-let [path (:db/retract-path datom)]
                (let [eid-root (first path)
                      reduce-pull-path #(assoc {} %2 [%1])
                      retract-ids (->> path
                                       butlast
                                       (pull-path eid-root)
                                       (or (pull-path eid-root path))
                                       (get-retract-ids (rest path)))]
                  (map (fn [{:keys [db/id attribute]}]
                         (if attribute
                           [:db.fn/retractAttribute id attribute]
                           [:db.fn/retractEntity id])) retract-ids))
                [datom]))]
      (let [new-datoms (->> datoms
                            (mapcat use-path)
                            (mapcat use-retract-path))]
        (d/transact! conn new-datoms)))))



(def log (js/console.log.bind js/console))
(def log-group (if (.-group js/console)
                 (js/console.group.bind js/console)
                 (js/console.log.bind   js/console)))
(def log-group-end (if (.-groupEnd js/console)
                     (js/console.groupEnd.bind js/console)
                     #()))

(defn transact!
  [debug? conn datoms]
  (let [id (or (:id debug?) [:db/role :anchor])
        selector (or (:selector debug?) '[*])
        orig-db (if (and debug?
                         (->> id (d/entity (d/db conn)) :db/id))
                  (d/pull (d/db conn) selector id))]
    (handle-transaction conn datoms)
    (if debug? [orig-db (d/pull (d/db conn) '[*] [:db/role :anchor])])))

(defn make-schema
  [schema]
  (->> (concat (map #(-> [% {:db/unique :db.unique/identity
                             :db/index true}]) (:ident schema))
               (map #(-> [% {:db/valueType :db.type/ref
                             :db/cardinality :db.cardinality/one
                             :db/isComponent true}]) (:single-ref schema))
               (map #(-> [% {:db/valueType :db.type/ref
                             :db/cardinality :db.cardinality/many
                             :db/isComponent true}]) (:many-ref schema)))
       (into {})))

(defn add-id-to-pull
  [sel]
  (->> sel
       (map #(if (map? %)
               (->> %
                    (map (fn [[k v]] [k (add-id-to-pull v)]))
                    (into {}))
               %))
       (#(conj % :db/id))
       (into #{})
       (into [])))

(defn pull-res-to-datoms
  [{:keys [db/id] :as res}]
  (->> res
       (filter (fn [[k v]] (not= :db/id k)))
       (reduce (fn [datoms [k v]]
                 (into [] (concat datoms
                                  (cond
                                    (and
                                     (map? v)
                                     (contains? v :db/id))
                                    (conj
                                     (pull-res-to-datoms v)
                                     {:db/id id
                                      k (:db/id v)})
                                    (and
                                     (sequential? v)
                                     (every? #(and
                                               (map? %)
                                               (contains? % :db/id)) v))
                                    (concat (mapcat pull-res-to-datoms v)
                                            (map #(-> {:db/id id
                                                       k (:db/id %)}) v))
                                    :else [{:db/id id
                                            k v}]))))
               [])
       (into [])))

(defn process-pull-datoms-res [res]
  (->> [res]
       (mapcat pull-res-to-datoms)
       (into #{})
       (into  [])))

(defn pull-datoms
  [pull-many db selector eids]
  (->> [eids]
       flatten
       (pull-many db (add-id-to-pull selector))
       process-pull-datoms-res))

(defn pull-datoms-reaction
  [conn selector eid]
  (let [pull-ratom (posh/pull conn (add-id-to-pull selector) eid)]
    (r/reaction (->> @pull-ratom process-pull-datoms-res))))

(defn sync-local-storage
  ([conn selector storage-key]
   (sync-local-storage conn selector [:db/role :anchor] storage-key))
  ([conn selector eid storage-key]
   (let [root-id (-> conn d/db (d/pull [:db/id] [:db/role :anchor]) :db/id)]
     (try (let [datoms (->> storage-key (.getItem js/localStorage) read-string)]
            ;; (if (s/valid? (s/coll-of (fn [{:keys [db/id]}] (or (int? id) (= id [:db/role :anchor])))) datoms))
            (transact! true conn datoms))
          (catch js/Object e (println e)))
     (let [dratoms (pull-datoms-reaction conn selector eid)]
       (r/run! (->> @dratoms
                    (map (fn [{:keys [db/id] :as datom}]
                           (if (= id root-id)
                             (merge datom {:db/id [:db/role :anchor]})
                             datom)))
                    (.setItem js/localStorage storage-key))))
     (.addEventListener
      js/window
      "storage"
      #(if (=
            storage-key
            (.-key %))
         (transact! true conn (-> % .-newValue read-string)))))))

(defn set-up-db [schema]
  (let [c (-> schema
                 (update :ident #(conj % :db/role))
                 (update :ident #(conj % :route/title))
                 (update :single-ref #(conj % :route/child))
                 (update :single-ref #(conj % :route/dependency))
                 make-schema
                 d/create-conn)]
    (.log js/console schema)
    (posh/posh! c)
    (.log js/console "how about here???")
    (transact! true c [{:db/id (tempid) :db/role :anchor}])
    c))

(defn deep-merge [m1 m2]
  (merge-with
   (fn [v1 v2]
     (cond (every? map? [v1 v2]) (deep-merge v1 v2)
           (every? coll? [v1 v2]) (concat v1 v2)
           :else v2)) m1 m2))

(defn log-diffs [tag s1 s2]
  (let [[only-before only-after] (data/diff s1 s2)
        changed? (or (some? only-before) (some? only-after))]
    (if changed?
      (do (log-group "sink diff for subsystem:" tag)
          (log "only before:" only-before)
          (log "only-after:" only-after)
          (log-group-end)))))


(defn ajax-xhrio-handler
  "ajax-request only provides a single handler for success and errors"
  [on-success on-failure xhrio [success? response]]
  ; see http://docs.closure-library.googlecode.com/git/class_goog_net_XhrIo.html
  (if success?
    (on-success response)
    (let [details (merge
                    {:uri             (.getLastUri xhrio)
                     :last-method     (.-lastMethod_ xhrio)
                     :last-error      (.getLastError xhrio)
                     :last-error-code (.getLastErrorCode xhrio)
                     :debug-message   (-> xhrio .getLastErrorCode (errors/getDebugMessage))}
                    response)]
      (on-failure details))))


(defn request->xhrio-options
  [dispatch
   {:as   request
    :keys [on-success on-failure]
    :or   {on-success      [:http-no-on-success]
           on-failure      [:http-no-on-failure]}}]
  ; wrap events in cljs-ajax callback
  (let [[tag-succ args-succ] on-success
        [tag-fail args-fail] on-failure
        api (new js/goog.net.XhrIo)]
    (-> request
        (assoc
          :api     api
          :handler (partial ajax-xhrio-handler
                            #(dispatch tag-succ [args-succ %])
                            #(dispatch tag-fail [args-fail %])
                            api))
        (dissoc :on-success :on-failure))))

(def ajax-system
  {:sinks {:ajax (fn [dispatch request]
                   (let [seq-request-maps (if (sequential? request) request [request])]
                     (doseq [request seq-request-maps]
                       (->> request (request->xhrio-options dispatch) ajax/ajax-request))
                     nil))}})

(defn datascript-system
  [debug? conn]
  {:events {:setup-local-sync {:body (fn [state {{:keys [selector key]} :user}] {:start-local-sync [selector key]})}}
   :sources {:datascript (fn [_] @conn)}
   :sinks {:datascript (fn [dispatch datoms] (transact! debug? conn datoms))
           :start-local-sync (fn [_ [selector key]] (sync-local-storage conn selector key) nil)}
   :initial-dispatching (if-let [local-storage-args (:sync-local-storage debug?)]
                          [[:setup-local-sync local-storage-args]]
                          [])})

(def dispatch-system
  {:sinks {:dispatch (fn [dispatch [event args]] (dispatch event args))
           :dispatch-after (fn [dispatch [ms event args]]
                             (js/setTimeout #(dispatch event args) ms)
                             nil)}})

(defn route-builder [routes path]
  (if (coll? routes)
    (->> routes
         (map (fn [[k v]]
                (if-let [new-step (-> v meta keys first)]
                  [k (route-builder v (concat path [new-step]))]
                  [k (route-builder v path)])))
         (into {}))
    [routes path]))

(defn datascript-set-route-event-res
  [state bidi-res]
  {:datascript [{:db/path [[:db/role :anchor]]
                 :root/routing bidi-res}]})

(defn route-system
  ([routes] (route-system routes {}))
  ([routes {:keys [from-location make-set-route-event-res]}]
   (let [routes (route-builder routes [])
         leaves (leaves routes)
         leaf-to-sym (fn [[leaf path]] (symbol (str leaf (reduce str "" (map str path)))))
         leaves (->> leaves (map (fn [leaf] [(leaf-to-sym leaf) leaf])) (into {}))
         routes (map-leaves leaf-to-sym routes)
         routes (mapcat identity routes)
         from-location (or from-location #(-> % .-hash (subs 1)))
         make-set-route-event-res (or make-set-route-event-res (fn [state {bidi-res :user}] {:state (deep-merge state {:routing {:current-route (merge bidi-res {:handler (get leaves {:handler bidi-res})})}})}))]
     {:events {:setup-routing {:body (fn [state args] {:start-pushy routes})}
               :set-route {:sources [:location]
                           :body (fn [state args]
                                   (make-set-route-event-res state
                                                             (update-in args [:user :handler] #(get leaves %))))}}
      :sources {:location (fn [] (.-location js/window))}
      :sinks {:start-pushy (fn [dispatch routes]
                             (pushy/start! (pushy/pushy (partial dispatch :set-route)
                                                        (fn [_] (bidi/match-route routes
                                                                                  (from-location (.-location js/window)))))))}
      :initial-dispatching [[:setup-routing]]})))


(defn make-event-system
  [debug? configs]
  (let [c (chan)
        initial-state {:_config (reduce deep-merge
                                        (if debug? {:debug []} {})
                                        (->> configs
                                             (map (fn [config] (-> config
                                                                   (dissoc :initial-dispatching)
                                                                   (update :events #(->> %
                                                                                         (map (fn [[k v]] [k (merge {:sources []} v)]))
                                                                                         (into {}))))))))}
        dispatch (fn [event args] (go (>! c [event args])) nil)]

    (defn process-event
      [state event-tag args]
      (let [event (get-in state [:_config :events event-tag])
            all-sources (get-in state [:_config :sources])
            all-sinks (get-in state [:_config :sinks])
            args (merge {:user args} (into {} (map #(-> [% ((% all-sources) args)]) (:sources event))))
            result (merge {:state state} ((:body event) state args))
            ]
        [(:state result) (->> (dissoc result :state)
                              (map (fn [[sink args]] [sink ((sink all-sinks) dispatch args)])))]))

    (defn process-event-wrapper
      [state event-tag args]
      (let [[new-state sink-results] (process-event state event-tag args)]
        (if debug? (do
                     (log-group "Event subsystem  changes for event:"
                                ;; TODO: chrome can't handle strings in these dics for some reason
                                event-tag (if (map? args)
                                            (dissoc args :key)
                                            args))
                     (log-diffs :state state new-state)
                     (doseq [[sink [s1 s2]] sink-results]
                       (if (or s1 s2) (log-diffs sink s1 s2)))
                     (log-group-end)))
        new-state))

    (let [post-initial-dispatching-state (->> configs
                                              (mapcat :initial-dispatching)
                                              (reduce (fn [state [event-tag args]]
                                                        (process-event-wrapper state event-tag args))
                                                      initial-state))]
      (go-loop [state post-initial-dispatching-state]
        (let [[event-tag args] (<! c)] (recur (process-event-wrapper state event-tag args)))))
    dispatch))