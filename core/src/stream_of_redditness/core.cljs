(ns stream-of-redditness.core

  (:require [stream-of-redditness.datival.core :as dv]
            [reagent.core :as reagent]
            [stream-of-redditness.conn :as conn]
            [stream-of-redditness.browser.browser :as browser]
            ))

(defn on-js-reload [] (browser/init))

(browser/init)
