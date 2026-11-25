(ns strata.core
  "Durable entities in Clojure. State is an immutable map; a command is a function of the
  state (and an argument) returning [new-state result]; a query returns a result.

    (defentity account
      {:mount \"/accounts/{id}\" :initial {:balance 0}}
      (command deposit {:post \"/deposit\"} [state amount]
        (let [s (update state :balance + amount)] [s (:balance s)]))
      (query balance {:get \"\"} [state] (:balance state)))

  State, arguments and results travel as JSON: map keys come back as keywords, other values
  as strings, numbers, booleans, vectors and maps. List the var in strata.clojure.entities."
  (:import [io.strata EntityType Reply CommandHandler Durability]
           [java.util.function Supplier]))

(defn- ->json
  "Clojure data → JSON-friendly data (keywords become strings)."
  [x]
  (cond
    (map? x) (persistent! (reduce-kv (fn [m k v] (assoc! m (if (keyword? k) (subs (str k) 1) (str k)) (->json v))) (transient {}) x))
    (keyword? x) (subs (str x) 1)
    (set? x) (mapv ->json x)
    (sequential? x) (mapv ->json x)
    :else x))

(defn- <-json
  "Decoded JSON (Java maps and lists) → Clojure data with keyword keys."
  [x]
  (cond
    (instance? java.util.Map x) (persistent! (reduce (fn [m ^java.util.Map$Entry e] (assoc! m (keyword (str (.getKey e))) (<-json (.getValue e))))
                                                      (transient {}) (.entrySet ^java.util.Map x)))
    (instance? java.util.List x) (mapv <-json x)
    :else x))

(def ^:private http-keys {:post "POST" :put "PUT" :patch "PATCH" :delete "DELETE" :get "GET"})

(defn build-entity
  "Builds an EntityType from a name, options and operation maps (what defentity expands to)."
  ^EntityType [type-name {:keys [name initial mount durability]} ops]
  (let [b (EntityType/builder (or name type-name) Object)]
    (.initial b (reify Supplier (get [_] (->json initial))))
    (when durability (.durability b (Durability/parse (clojure.core/name durability))))
    (when mount (.mount b mount))
    (doseq [{:keys [kind op-name http takes-arg f]} ops]
      (let [query? (= kind :query)
            handler (reify CommandHandler
                      (handle [_ state arg]
                        (let [s (<-json state)
                              out (if takes-arg (f s (<-json arg)) (f s))]
                          (if query?
                            (Reply/read (->json out))
                            (do (when-not (and (vector? out) (= 2 (count out)))
                                  (throw (ex-info (str "command " op-name " must return [new-state result]") {})))
                                (Reply/update (->json (first out)) (->json (second out))))))))]
        (.command b ^String op-name (if takes-arg Object Void) (boolean query?) handler)
        (let [routes (select-keys http (keys http-keys))]
          (when (> (count routes) 1) (throw (ex-info (str op-name ": set at most one HTTP method") {})))
          (doseq [[k path] routes]
            (when (and (= k :get) takes-arg) (throw (ex-info (str op-name ": a GET route can't take an argument; use :post") {})))
            (.route b (http-keys k) path op-name)))))
    (.build b)))

(defn- parse-op [[kind op-sym & more]]
  (let [[http more] (if (map? (first more)) [(first more) (rest more)] [{} more])
        [params & body] more]
    (when-not (#{'command 'query} kind)
      (throw (IllegalArgumentException. (str "defentity: expected (command ...) or (query ...), got " kind))))
    (when-not (and (vector? params) (<= 1 (count params) 2))
      (throw (IllegalArgumentException. (str "defentity: " op-sym " takes [state] or [state arg]"))))
    `{:kind ~(keyword kind) :op-name ~(name op-sym) :http ~http :takes-arg ~(= 2 (count params))
      :f (fn ~params ~@body)}))

(defmacro defentity
  "Defines an entity type. Options: :initial (state), :mount (\"/things/{id}\"), :name,
  :durability (:ephemeral or :persistent). Then (command name {http} [state arg?] body) forms
  returning [new-state result], and (query name {http} [state arg?] body) forms returning a result."
  [sym opts & ops]
  `(def ~sym (build-entity ~(name sym) ~opts ~(mapv parse-op ops))))
