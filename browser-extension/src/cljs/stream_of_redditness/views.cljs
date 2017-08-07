(ns stream-of-redditness.views
  (:require [stream-of-redditness.conn :as conn]
            [stream-of-redditness.dispatch :as dispatch]
            [stream-of-redditness.config :as c]
            [datival.core :as dv]
            [re-com.core :refer [h-box v-box hyperlink-href single-dropdown]]
            ))

(defn box
  [config child]
  (apply re-com.core/box (concat (mapcat (fn [[k v]] [k v]) config)
                                  [:child child])))

(defn boxx
  [el config & children]
  (let [main (apply el (concat (mapcat (fn [[k v]] [k v]) (dissoc config :box))
                               [:children children]))]
    (if (get-in config [:box :dont-use])
      main
      (box (:box config) main))))

(defn boxv [config & children] (apply boxx v-box config children))
(defn boxh [config & children] (apply boxx h-box config children))

(def top-panel
  (dv/make-ui conn/conn
              [{:root/auth [:auth/flow
                            {:auth/current-user [:db/id :user/name]}
                            {:auth/users [:db/id :user/name]}]}]
              {:render (fn [[{{{:keys [db/id user/name]} :auth/current-user
                               :keys [auth/flow auth/users]} :root/auth :as all}]]
                         (let [dropdown-choices (reverse (conj (->> users
                                                                    (map #(-> {:id (:db/id %)
                                                                               :label (:user/name %)}))
                                                                    (sort-by #(= id (:id %))))
                                                               {:id :add-account
                                                                :label name}))
                               render-fn #(if (= :add-account (:id %)) "Add a Reddit account" (:label %))
                               oauth-link (str "https://www.reddit.com"
                                               "/api/v1/authorize"
                                               "?client_id=" c/client-id
                                               "&response_type=code"
                                               "&state=RANDOM_STRING"
                                               "&redirect_uri=" c/redirect-url
                                               "&duration=permanent"
                                               "&scope=edit read report save submit vote identity")
                               on-selection #(if (= :add-account %)
                                               (do
                                                 (.open js/window oauth-link "_blank")
                                                 (dispatch/dispatch :auth-flow-begin))
                                               (dispatch/dispatch :switch-account %))]
                           [boxh {}
                            (apply boxh {:box {:size "1"}}
                                   (if (> (count users) 0)
                                     [[box {} "Logged in as: "]
                                      [box {:size "1"}
                                       [single-dropdown
                                        :choices dropdown-choices
                                        :model id
                                        :render-fn render-fn
                                        :on-change on-selection
                                        :placeholder "You are not logged in"]]]
                                     ["You are not logged in:"
                                      [hyperlink-href
                                       :label "Add a reddit account"
                                       :href oauth-link
                                       :target "_blank"
                                       :attr {:on-click #(dispatch/dispatch :auth-flow-begin)}]]
                                     ))
                            [box {:size "1"}
                             "add thread"]]))}))

(def comment-view
  (dv/make-ui conn/conn
              [{:comment/markdown [:markdown/parsed]} :comment/score
               :comment/created :comment/author :comment/id
               {:comment/children [:db/id :comment/created :comment/loaded?]}]
              {:id (fn [[id]] id)
               :render (fn [[{:keys [comment/markdown comment/score comment/created
                                     comment/author comment/children] :as huh} id color]]
                         (let [replies (->> children
                                            (filter :comment/loaded?)
                                            (sort-by :comment/created)
                                            reverse)]
                           [:li.list-group-item {:id id}
                            [boxh {:box {:dont-use true}}
                             [box {:size (if (nil? color) "0px" "100px")}
                              [:div {:style {:background-color (str "#" color)
                                             :height "100%"
                                             :width "100%"}} ""]]
                             [boxv {:box {:size "1"}
                                    :width "100%"}
                              [boxh {}
                               [box {:size "none"
                                     :align-self :center}
                                [:span.badge (str score)]]
                               [boxv {:box {:size "1"}
                                      :width "100%"}
                                [boxh {:justify :between}
                                 [box {} author]
                                 [box {} (.fromNow (.moment js/window (* 1000 created)))]]
                                [box {} (str (:markdown/parsed markdown))]]]
                              (if (> (count replies) 0)
                                [box {}
                                 [:ul.list-group
                                  (for [comment replies]
                                    ^{:key (:db/id comment)}
                                    [comment-view (:db/id comment) nil])]])]]]))}))

(defn comment-bookend
  [extreme? side]
  (if-not extreme?
    ^{:key (str "more-comments-" side)} [:li.list-group-item [:i.fa.fa-spinner.fa-spin]]))

(def comment-stream
  (dv/make-ui conn/conn
              [{:root/render [:render/comments :render/container-top
                              :render/first-id :render/last-id]}]
              {:component-will-update #(dispatch/dispatch-sync :component-will-update)
               :component-did-update #(dispatch/dispatch-sync :component-did-update)
               :render (fn [[{{:keys [render/comments render/container-top
                                      render/first-id render/last-id]} :root/render}]]
                         (dv/log :comment-stream comments first-id last-id container-top)
                         [boxv {:attr {:id :el-comments-container
                                       :on-scroll #(dispatch/dispatch :pick-rendered-comments)}
                                :style {:overflow-y "scroll"
                                        :height (str (- (.. js/document -body -clientHeight)
                                                        container-top) "px")}}
                          [:ul#el-comment-root.list-group
                           (-> (for [{:keys [db/id thread/color]} comments]
                                 ^{:key id} [comment-view id color])
                               (conj (comment-bookend (= first-id (-> comments first :db/id)) "begin"))
                               reverse
                               (conj (comment-bookend (= last-id (-> comments last :db/id)) "end"))
                               reverse
                               (#(remove nil? %)))]])}))

(defn main-panel []
  (fn []
    [boxv {:box {:dont-use true}}
     [box {} [top-panel]]
     [box {} [comment-stream]]]))
