(ns stream-of-redditness.css
  (:require [garden.def :refer [defstyles]]))

(defstyles screen
           [:.smaller {:font-size "10px"}]
           [:.author-deleted {:pointer-events "none !important"
                              :cursor "default"
                              :color "Gray"}]
           [:.align-left {:text-align "left"}]
           [:.align-center {:text-align "center"}]
           [:.align-right {:text-align "right"}]
           [:.faded {:color "#aaa"}]
           [:#el-comments-container [:.list-group-item {:border-top-width          "0px"
                                                        :border-bottom-width       "0px"
                                                        :border-right-width        "0px"
                                                        :border-top-left-radius    "0"
                                                        :border-bottom-left-radius "0"
                                                        :padding-top "0px"
                                                        :padding-bottom "0px"
                                                        :margin-top "10px"
                                                        :margin-bottom "10px" }]]
           [:.comment-actions {:margin-top    "-5px"
                               :margin-bottom "5px"}]
           [:.list-group-item:hover {:background-color "#fff"}]
           [:.children-container {:margin-bottom "0px"}]
           )
