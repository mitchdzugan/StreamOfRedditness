(ns stream-of-redditness.util)

(defn pop-when
  [l pred]
  (reduce (fn [[popped filtered] v]
            (if (and (nil? popped) (pred v))
              [v filtered]
              [popped (conj filtered v)])) [nil []] l))
