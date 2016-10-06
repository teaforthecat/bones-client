(ns bones.client-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec :as s]
            [bones.client :as client]
            [cljs.core.async :as a]))
(comment
  (s/conform even? 1000)
  )

(def states #{:before
              :loading
              :new
              :one
              :some
              :too-many
              :invalid
              :disruption
              :ok
              :done})

(def parent-states #{:empty
                     :active
                     :error})

(def state-hierachy (-> (make-hierarchy)
                        (derive :before :empty)
                        (derive :loading :empty)
                        (derive :new :empty)
                        (derive :some :active)
                        (derive :too-many :active)
                        (derive :invalid :error)
                        (derive :disruption :error)
                        (derive :ok :active)
                        (derive :done :empty)))

;; shares same signature as client/js-event-source
(defn bad-event-source [{:keys [url onmessage onerror onopen]}]
  (go (a/<! (a/timeout 100 ))
        (onerror 'error))
  {})

(def sys (atom {}))

(deftest initial-state
  (testing "client starts at :before and proceeds to :disruption onerror given a bad url"
    (async done
           (client/build-system sys {:url "url"
                                     :es/onerror (fn [e cmp]
                                                   (is (= :disruption
                                                          @(get-in @sys [:client :state])))
                                                   (done))
                                     :es/constructor bad-event-source})
           (client/start sys)
           (let []
             (is (= :before @(get-in @sys [:client :state])))))))

(deftest url-resolving
  (let [url (goog.Uri.parse "http://localhost:8080/api/")]
    (is (= "http://localhost:8080/api/events"
           (.toString (:es/url (client/add-events-path {:url url})))))))
