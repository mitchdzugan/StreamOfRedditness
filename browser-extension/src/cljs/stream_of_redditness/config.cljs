(ns stream-of-redditness.config
  (:require [datival.core :as dv]
            [cemerick.url :as url]
            [stream-of-redditness.browser.events :as e]))

(def debug?
  ^boolean goog.DEBUG)

(def redirect-url (if debug? "http://mdzugan.001www.com"))
(def client-id (if debug? "33V8GP9wAN_11g"))

(def routes
  (if debug?
    {"/" {"" :auth
          "r/StreamReddit/" {"" :home
                             ["stream/" [#".*" :threads]] :stream}}}
    {}))

(defn make-set-route-event-res
  [_ {{[page _] :handler
       route-params :route-params :as user} :user
      location :location :as args}]
  (dv/deep-merge
   (case page
     :auth {:dispatch [:auth-flow-submit-code
                       (:query (url/url (.-href location)))]}
     :stream {:dispatch [:set-threads
                         (->> route-params
                              :threads
                              (#(clojure.string/split % #";"))
                              (map #(clojure.string/split % #":"))
                              (map (fn [[id color]]
                                     {:thread/id id
                                      :thread/color color})))]}
     {})
   (dv/datascript-set-route-event-res nil user)))

(def route-system
  (dv/route-system routes
                   {:make-set-route-event-res make-set-route-event-res
                    :from-location #(-> % .-pathname)}))


(def event-config
  {:now #(.getTime (js/Date.))
   :event-systems [route-system
                   e/pick-rendered-comments-system
                   {:env (if debug?
                           {:web-host "localhost:8080"
                            :api-host "localhost:8080"
                            :client-auth "MzNWOEdQOXdBTl8xMWc6"
                            :client-id client-id
                            :redirect-url redirect-url
                            }
                           {:web-host "www.reddit.com"
                            :api-host "oauth.reddit.com"
                            :client-id client-id
                            :redirect-url redirect-url
                            })}]
   :platform :web})
