(ns stream-of-redditness.views.tree
  (:require [stream-of-redditness.conn :as conn]
            [stream-of-redditness.dispatch :as dispatch]
            [stream-of-redditness.config :as c]
            [datival.core :as dv]
            [re-com.core :refer [hyperlink-href single-dropdown]]
            [stream-of-redditness.views.util :refer [box boxx boxv boxh]]
            ))

(def top-panel
  (dv/make-ui conn/conn
              [{:root/auth [:auth/flow
                            {:auth/current-user [:db/id :user/name]}
                            {:auth/users [:db/id :user/name]}]}]
              {:render (fn [[{{{:keys [db/id user/name]} :auth/current-user
                               :keys                     [auth/flow auth/users]} :root/auth :as all}]]
                         (let [dropdown-choices (reverse (conj (->> users
                                                                    (map #(-> {:id    (:db/id %)
                                                                               :label (:user/name %)}))
                                                                    (sort-by #(= id (:id %))))
                                                               {:id    :add-account
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
                                     [(box {} "Logged in as: ")
                                      (box {:size "1"}
                                           [single-dropdown
                                            :choices dropdown-choices
                                            :model id
                                            :render-fn render-fn
                                            :on-change on-selection
                                            :placeholder "You are not logged in"])]
                                     ["You are not logged in:"
                                      [hyperlink-href
                                       :label "Add a reddit account"
                                       :href oauth-link
                                       :target "_blank"
                                       :attr {:on-click #(dispatch/dispatch :auth-flow-begin)}]]
                                     ))
                            [box {:size "1"}
                             "add thread"]]))}))

(defn render-markdown
  [[type & args :as md]]
  ;; base-case
  (if (or (nil? type) (string? md)) (str md)
                                    (case type
                                      "markdown" (map render-markdown args)
                                      "para" [:p (map render-markdown args)]
                                      "link" [:a {:href (get (first args) "href")}
                                              (map render-markdown (rest args))]
                                      "em" [:i (map render-markdown args)]
                                      "strong" [:b (map render-markdown args)]
                                      "bulletlist" [:ul (map render-markdown args)]
                                      "listitem" [:li (map render-markdown args)]
                                      "table" [:table (map render-markdown args)]
                                      "thead" [:thead (map render-markdown args)]
                                      "tbody" [:tbody (map render-markdown args)]
                                      "tr" [:tr (map render-markdown args)]
                                      "th" [:th {:class (str "align-" (get (first args) "align"))}
                                            (map render-markdown (rest args))]
                                      "td" [:td {:class (str "align-" (get (first args) "align"))}
                                            (map render-markdown (rest args))]
                                      "header" (->> (rest args)
                                                    (map render-markdown)
                                                    (concat [(or ({1 :h1 2 :2 3 :h3 4 :h4 5 :h5} (get (first args) "level")) :h6)])
                                                    (into []))
                                      "blockquote" [:blockquote (map render-markdown args)]
                                      "code_block" [:pre [:code (map render-markdown args)]]
                                      "link_ref" [:span {:class (str "link-ref-" (get (first args) "ref"))}
                                                  (map render-markdown (rest args))]
                                      ;; unhandled
                                      (str md))))

(def comment-view
  (dv/make-ui conn/conn
              [{:comment/markdown [:markdown/parsed]} :comment/score
               :comment/created :comment/author :comment/id
               {:comment/children [:db/id :comment/created :comment/loaded?]}]
              {:id     (fn [[id]] id)
               :render (fn [[{:keys [comment/markdown comment/score comment/created
                                     comment/author comment/children]} id user color]]
                         (let [replies (->> children
                                            (filter :comment/loaded?)
                                            (sort-by :comment/created)
                                            reverse)]
                           [:li.list-group-item {:id id}
                            [boxh {:box {:dont-use true}}
                             [box {:size (if (nil? color) "0px" "20px")}
                              [:div {:style {:background-color (str "#" color)
                                             :height           "100%"
                                             :width            "100%"
                                             :margin-right     "10px"}} ""]]
                             [boxv {:box   {:size "1"}
                                    :width "100%"}
                              [boxh {}
                               [boxv {:box   {:size "1"}
                                      :width "100%"}
                                [:span {:class "smaller faded"}
                                 [:strong [:a {:class (str "author-" (->> author
                                                                          (drop 1) reverse
                                                                          (drop 1) reverse
                                                                          (clojure.string/join "")))
                                               :href  (str "https://reddit.com/u/" author)} author]]
                                 (str " " score " point "
                                      (.fromNow (.moment js/window (* 1000 created))))]
                                [boxv {}
                                 (render-markdown (:markdown/parsed markdown))]]]
                              [:div
                               [:strong {:class "smaller faded comment-actions"} "permalink"]]
                              (if (> (count replies) 0)
                                [box {}
                                 [:ul.list-group {:class "children-container"}
                                  (for [comment replies]
                                    ^{:key (:db/id comment)}
                                    [comment-view (:db/id comment) user nil])]])]]]))}))

(defn comment-bookend
  [extreme? side]
  (if-not extreme?
    ^{:key (str "more-comments-" side)} [:li.list-group-item [:i.fa.fa-spinner.fa-spin]]))

(def comment-stream
  (dv/make-ui conn/conn
              [{:root/render [:render/comments :render/container-top
                              :render/first-id :render/last-id]}]
              {:component-will-update #(dispatch/dispatch-sync :component-will-update)
               :component-did-update  #(dispatch/dispatch-sync :component-did-update)
               :render                (fn [[{{:keys [render/comments render/container-top
                                                     render/first-id render/last-id]} :root/render}]]
                                        [boxv {:attr  {:id        :el-comments-container
                                                       :on-scroll #(dispatch/dispatch :pick-rendered-comments)}
                                               :style {:overflow-y "scroll"
                                                       :height     (str (- (.. js/document -body -clientHeight)
                                                                           container-top) "px")}}
                                         [:ul#el-comment-root.list-group
                                          (-> (for [{:keys [db/id thread/color]} comments]
                                                ^{:key id} [comment-view id nil color])
                                              (conj (comment-bookend (= first-id (-> comments first :db/id)) "begin"))
                                              reverse
                                              (conj (comment-bookend (= last-id (-> comments last :db/id)) "end"))
                                              reverse
                                              (#(remove nil? %)))]])}))
#_(def main-panel
    (dv/make-ui conn/conn
                [{:root/auth [:auth/current-user]}]))
(defn main-panel []
  (fn []
    [boxv {:box {:dont-use true}}
     [box {} [top-panel]]
     [box {} [comment-stream]]]))
