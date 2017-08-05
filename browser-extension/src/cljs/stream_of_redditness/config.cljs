(ns stream-of-redditness.config)

(def debug?
  ^boolean goog.DEBUG)

(def redirect-url (if debug? "http://mdzugan.001www.com"))
(def client-id (if debug? "33V8GP9wAN_11g"))

(def event-config
  (merge {:platform :web
          :client-id client-id
          :redirect-url redirect-url}
         (if debug?
           {:web-host "localhost:8080"
            :api-host "localhost:8080"
            :client-auth "MzNWOEdQOXdBTl8xMWc6"}
           {:web-host "www.reddit.com"
            :api-host "oauth.reddit.com"})))
