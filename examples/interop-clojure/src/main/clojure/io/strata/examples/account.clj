(ns io.strata.examples.account
  "A bank account in Clojure: only business logic, as pure functions of immutable state."
  (:require [strata.core :refer [defentity]]))

(defentity account
  {:mount "/accounts/{id}" :initial {:balance 0 :history []}}

  (command deposit {:post "/deposit"} [state amount]
    (when-not (pos? amount) (throw (ex-info "amount must be positive" {:amount amount})))
    (let [s (-> state (update :balance + amount) (update :history conj (str "deposit " amount)))]
      [s (:balance s)]))

  (command withdraw {:post "/withdraw"} [state amount]
    (when (> amount (:balance state)) (throw (ex-info "insufficient funds" {:amount amount})))
    (let [s (-> state (update :balance - amount) (update :history conj (str "withdraw " amount)))]
      [s (:balance s)]))

  (query balance {:get ""} [state] (:balance state))

  (query history {:get "/history"} [state] (:history state)))
