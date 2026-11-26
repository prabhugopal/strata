(ns io.strata.examples.counter
  "A counter defined in Clojure through plain Java interop."
  (:import [io.strata EntityType Reply Strata NoArgHandler CommandHandler]
           [java.util.function Supplier Function]))

(def counter
  (-> (EntityType/builder "counter" Long)
      (.initial (reify Supplier (get [_] 0)))
      (.command "increment" (reify NoArgHandler (handle [_ s] (let [v (inc s)] (Reply/update v v)))))
      (.command "add" Long (reify CommandHandler (handle [_ s n] (let [v (+ s n)] (Reply/update v v)))))
      (.query "get" (reify Function (apply [_ s] s)))
      (.build)))

(defn demo
  "Starts a node from `config`, increments `id` `times` times and returns the final count."
  [config id times]
  (with-open [rt (Strata/start config)]
    (.register rt counter)
    (dotimes [_ times] (-> (.ref rt counter id) (.call "increment" Long) (.toCompletableFuture) (.join)))
    (-> (.ref rt counter id) (.call "get" Long) (.toCompletableFuture) (.join))))
