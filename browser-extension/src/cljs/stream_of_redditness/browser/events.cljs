(ns stream-of-redditness.browser.events
  (:require [datascript.core :as d]
            [datival.core :as dv]
            [stream-of-redditness.events.reddit :as r]))

(defn getElementById
  [id]
  (->> id str
       (.getElementById js/document)))

(defn offsetTop
  [id]
  (->> id getElementById
       (#(if % (->> % (.-offsetTop))))))

(defn scrollTop
  [id]
  (->> id getElementById
       (.-scrollTop)))

(defn scrollHeight
  [id]
  (->> id getElementById
       (.-scrollHeight)))

(defn last-that-satisfies
  [p l]
  (loop [[[x1 & [x2 & _ :as xs]] store] [l {}]]
    (let [store (merge store
                       {x1 (or (get store x1) (if (p x1) :yes :no))}
                       (if x2
                         {x2 (or (get store x2) (if (p x2) :yes :no))}
                         {}))
          px1 (= :yes (get store x1))
          px2 (= :yes (get store x2))]
      (cond
        (and px1 (nil? xs)) x1
        (and px1 (not px2)) x1
        (not px1) nil
        :else (recur [xs store])))))

(defn get-extreme-id
  [chars-per-pixel target-amount-off-screen get-furthest-off-screen rendered-comments all-comments]
  (let [first-rendered-id (-> rendered-comments first :db/id)
        target-delta-size (* (- target-amount-off-screen
                                (-> first-rendered-id get-furthest-off-screen))
                             chars-per-pixel)]
    (if (> target-delta-size 0)
      (->> all-comments
           (take-while #(not= first-rendered-id (:db/id %)))
           reverse
           (reduce (fn [{:keys [target-id size-acc]} {:keys [db/id comment/size]}]
                     (let [curr-size (+ size-acc size)]
                       {:target-id (if (and (not target-id)
                                            (> curr-size target-delta-size))
                                     id
                                     target-id)
                        :size-acc curr-size}))
                   {:size-acc 0})
           :target-id
           (#(or % (-> all-comments first :db/id))))
      (:db/id (or
               (last-that-satisfies #(> (get-furthest-off-screen (:db/id %))
                                        target-amount-off-screen)
                                    (drop 3 rendered-comments))
               (first rendered-comments))))))

(defn pick-rendered-comments-sink
  [[all-comments last-rendered last-char-count]
   {dispatch :set-rendered-comments}]
  (dispatch
   (let [comments (if (not last-char-count)
                    (->> all-comments (take 20))
                    (let [comments-top (offsetTop "el-comments-container")
                          display-height (.-innerHeight js/window)
                          rendered-height (scrollHeight "el-comments-container")
                          chars-per-pixel (/ last-char-count rendered-height)
                          base-target (* display-height 4)
                          adjust-threshold (* display-height 2)
                          distance-calc-top #(- comments-top
                                                (if-let [el (.getElementById js/document (str %))]
                                                  (-> el .getBoundingClientRect .-top)))
                          distance-calc-bottom #(- (if-let [el (.getElementById js/document (str %))]
                                                     (-> el .getBoundingClientRect .-bottom))
                                                   (+ display-height comments-top))
                          prev-first-id (-> last-rendered first :db/id)
                          prev-last-id (-> last-rendered last :db/id)
                          should-adjust? (or (< (distance-calc-top prev-first-id) adjust-threshold)
                                             (< (distance-calc-bottom prev-last-id) adjust-threshold))
                          first-id (if should-adjust?
                                     (get-extreme-id chars-per-pixel
                                                     base-target
                                                     distance-calc-top
                                                     last-rendered
                                                     all-comments)
                                     prev-first-id)
                          last-id (if should-adjust?
                                    (get-extreme-id chars-per-pixel
                                                    base-target
                                                    distance-calc-bottom
                                                    (reverse last-rendered)
                                                    (reverse all-comments))
                                    prev-last-id)]
                      (->> all-comments
                           (drop-while #(not= (:db/id %) first-id))
                           reverse
                           (drop-while #(not= (:db/id %) last-id))
                           reverse)))]
     {:char-count (reduce #(+ %1 (:comment/size %2)) 0 comments)
      :comments comments})))

(def pick-rendered-comments-event
  (r/make-datascript-event
   [{:root/render [:render/last-char-count :render/comments]}
    {:root/polling [{:polling/threads [{:thread/top-level-comments [:db/id :comment/created :comment/loaded? :comment/size]}
                                       :thread/color]}]}]
   (fn [
        {{:keys [rendered-change?]} :react :as state}

        {{{:keys [:render/last-char-count]
           last-rendered :render/comments} :root/render
          {:keys [polling/threads]} :root/polling} :datascript}]
     (let [all-comments (->> threads
                             (mapcat (fn [{:keys [thread/color] :as thread}]
                                       (->> (:thread/top-level-comments thread)
                                            (filter :comment/loaded?)
                                            (map #(assoc % :thread/color color)))))
                             (sort-by #(* -1 (:comment/created %))))]
       (dv/deep-merge {:datascript [{:db/path [[:db/role :anchor] :root/render]
                                     :render/first-id (-> all-comments first :db/id)
                                     :render/last-id (-> all-comments last :db/id)}]
                       :state state}
                      {:state {:react {:queue? (not rendered-change?)}}}
                      (if rendered-change?
                        {:pick-rendered-comments [all-comments last-rendered last-char-count]}
                        {}))))))

(def pick-rendered-comments-system
  {:sources {:component-will-update
             {:dependencies [:datascript]
              :body
              (fn [{db :datascript}]
                (let [{{:keys [render/comments]} :root/render}
                      (d/pull db [{:root/render [:render/comments]}] [:db/role :anchor])
                      scroll-top (scrollTop "el-comments-container")
                      first-on-screen (->> comments (map :db/id)
                                           (drop-while #(< (offsetTop %)
                                                           scroll-top))
                                           first)]
                  {:scroll-position (if first-on-screen
                                      {:id first-on-screen
                                       :offset (- scroll-top
                                                  (offsetTop first-on-screen))})
                   :container-top (offsetTop "el-comments-container")}))}
             :component-did-update
             {:dependencies [:datascript]
              :body (fn [{db :datascript} {{:keys [scroll-position]} :react}]
                      (let [{{:keys [render/first-id]} :root/render}
                            (d/pull db [{:root/render [:render/first-id]}] [:db/role :anchor])]
                        (and scroll-position
                             (not (if-let [first-comment (getElementById first-id)]
                                    (let [comments-cont (getElementById "el-comments-container")]
                                      (not (> (.-scrollTop comments-cont)
                                              (- (->> first-comment .getBoundingClientRect .-bottom)
                                                 (.-offsetTop comments-cont))))))))))}}

   :sinks {:set-scroll (fn [{:keys [id offset]}]
                         (set! (.-scrollTop (getElementById "el-comments-container"))
                               (+ offset (offsetTop id))))
           :pick-rendered-comments {:dispatchers [:set-rendered-comments]
                                    :body pick-rendered-comments-sink}}
   :events {:component-will-update {:sources [:component-will-update]
                                    :body (fn [state {{:keys [container-top scroll-position]} :component-will-update}]
                                            {:state (assoc-in state [:react :scroll-position] scroll-position)
                                             :datascript [{:db/path [[:db/role :anchor] :root/render]
                                                           :render/container-top container-top}]})}
            :component-did-update {:sources [:component-did-update]
                                   :body (fn [state {set-scroll? :component-did-update}]
                                           (if set-scroll?
                                             {:state (update state :react #(merge % {:rendered-change? true}))
                                              :set-scroll (get-in state [:react :scroll-position])}
                                             {:state (update state :react #(merge % {:rendered-change? true}))
                                              }))}
            :pick-rendered-comments pick-rendered-comments-event
            :set-rendered-comments (fn [{{:keys [queue?]} :react :as state}
                                        {{:keys [comments char-count]} :user}]
                                     (merge (if queue? {:dispatch [:pick-rendered-comments]})
                                            {:datascript [{:db/path [[:db/role :anchor] :root/render]
                                                           :render/last-char-count char-count
                                                           :render/comments comments}]}))}})
