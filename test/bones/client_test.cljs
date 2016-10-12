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
;; mimics a web request
(defn bad-event-source [{:keys [url onmessage onerror onopen]}]
  (go (a/<! (a/timeout 100))
        (onerror 'error))
  {})

(defn ok-event-source [{:keys [url onmessage onerror onopen]}]
  (go (a/<! (a/timeout 100))
      (onopen 'ok))
  {})

(defn msg-event-source [{:keys [url onmessage onerror onopen]}]
  (go (a/<! (a/timeout 100))
      (onmessage (new js/MessageEvent
                      "message"
                      #js{:data (str {:a "message"})}))
  {}))

(def sys (atom {}))

(deftest initial-state
  (testing "client call onmessage function with clojure data"
    (async done
           (client/build-system sys {:url "url"
                                     :es/onmessage (fn [msg]
                                                     (is (= {:a "message"} msg))
                                                     (done))
                                     :es/constructor msg-event-source})
           (client/start sys)
           (let []
             (is (= :before @(get-in @sys [:client :state]))))))
  (testing "client starts at :before and proceeds to :ok when successfully connected"
    (async done
           (client/build-system sys {:url "url"
                                     :es/onopen (fn [e]
                                                   (is (= :ok
                                                          @(get-in @sys [:client :state])))
                                                   (done))
                                     :es/constructor ok-event-source})
           (client/start sys)
           (let []
             (is (= :before @(get-in @sys [:client :state]))))))
  (testing "client starts at :before and proceeds to :disruption onerror given a bad url"
    (async done
           (client/build-system sys {:url "url"
                                     :es/onerror (fn [e]
                                                   (is (= :disruption
                                                          @(get-in @sys [:client :state])))
                                                   (done))
                                     :es/constructor bad-event-source})
           (client/start sys)
           (let []
             (is (= :before @(get-in @sys [:client :state])))))))

(deftest url-resolving
  (testing "merging defaults"
    (let []
      (is (= "/api/login" (-> (client/validate {})
                              :req/login-url
                              .toString))))))

(deftest login
  (testing "response returned on channel"
    (async done
           (let [_ (client/build-system sys {:url "url"
                                             :es/constructor ok-event-source
                                             :req/post-fn (fn [url params] (go {:status 200}))})
                 _ (client/start sys)
                 c (get-in @sys [:client])
                 response (client/stream c)]
             ;; setup async assertion
             (a/take! response
                      (fn [res]
                        (is (= {:channel :response/login, :response {:status 200}}
                               res))
                        (done)))
             ;; action
             (client/login c {:username "wat" :password "hi"})))))


(deftest logout
  (testing "response returned on channel"
    (async done
           (let [_ (client/build-system sys {:url "url"
                                             :es/constructor ok-event-source
                                             :req/get-fn (fn [url params] (go {:status 200}))})
                 _ (client/start sys)
                 c (get-in @sys [:client])
                 response (client/stream c)]
             ;; setup async assertion
             (a/take! response
                      (fn [res]
                        (is (= {:channel :response/logout, :response {:status 200}}
                               res))
                        (done)))
             ;; action
             (client/logout c )))))


(deftest query
  (testing "response returned on channel"
    (async done
           (let [_ (client/build-system sys {:url "url"
                                             :es/constructor ok-event-source
                                             :req/get-fn (fn [url params] (go {:status 200}))})
                 _ (client/start sys)
                 c (get-in @sys [:client])
                 response (client/stream c)]
             ;; setup async assertion
             (a/take! response
                      (fn [res]
                        (is (= {:channel :response/query, :response {:status 200}}
                               res))
                        (done)))
             ;; action
             (client/query c {:q 'nothin})))))

(deftest command
  (testing "response returned on channel"
    (async done
           (let [_ (client/build-system sys {:url "url"
                                             :es/constructor ok-event-source
                                             :req/post-fn (fn [url params] (go {:status 200}))})
                 _ (client/start sys)
                 c (get-in @sys [:client])
                 response (client/stream c)]
             ;; setup async assertion
             (a/take! response
                      (fn [res]
                        (is (= {:channel :response/command, :response {:status 200}}
                               res))
                        (done)))
             ;; action
             (client/command c :where {:city "saint paul" :state "mn"})))))

(deftest event-stream
  (testing "response returned on channel"
    (async done
           (let [
                 _ (client/build-system sys {:url "url"
                                             :es/onmessage nil
                                             :es/constructor msg-event-source})
                 _ (client/start sys)
                 c (get-in @sys [:client])
                 event (client/stream c)
                 ]
            (a/take! event
                      (fn [res]
                        (is (= {:channel :event/message, :event {:a "message"}}
                               res))
                        (done)))
             ;; action - no acton here
             ))))
