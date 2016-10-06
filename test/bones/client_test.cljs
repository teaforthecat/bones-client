(ns bones.client-test
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec :as s]
            [bones.client :as client]))
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
(def sys (atom {}))

(deftest initial-state
  (testing "client starts at :before and proceeds to :disruption given a bad url"
    (async done
           (let [_ (client/build-system sys {:url "url"
                                             :es/onerror (fn [] (done))})
                 _ (client/start sys)]
             (is (= :before (get-in @sys [:client :state])))))))


(deftest url-resolving
  (let [url (goog.Uri.parse "http://localhost:8080/api/")]
    (is (= "http://localhost:8080/api/events"
           (.toString (:es/url (client/add-event-handlers {:url url})))))))
