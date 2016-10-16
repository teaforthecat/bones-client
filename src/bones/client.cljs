(ns bones.client
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs-http.client :as http]
            [cljs.reader :refer [read-string]]
            [clojure.string :refer [ends-with? join]]
            [com.stuartsierra.component :as component]
            [cljs.core.async :as a]))

(def debug?
  ^boolean js/goog.DEBUG)

(when debug?
  (enable-console-print!))

;; returns a channel
(defn post [url params & headers]
  (let [req {:edn-params params
             :headers headers}]
    (http/post url req)))

;; returns a channel
(defn get-req [url params & headers]
  (let [req {:query-params params
             :headers headers}]
    (http/get url req)))

(defn js-event-source [{:keys [url onmessage onerror onopen]}]
  (let [src (js/EventSource. url #js{:withCredentials true})]
    (set! (.-onmessage src) onmessage)
    (set! (.-onerror src) onerror)
    (set! (.-onopen src) onopen)
    src))

;; careful, chrome hides a 401 response so if you see a blankish request/response
;; try switching to firefox to see the 401 unauthorized response
(defrecord EventSource [conf src url msg-ch onmessage onerror onopen es-state constructor]
  component/Lifecycle
  (start [cmp]
    (if (:src cmp)
      (do
        (println "already started stream")
        cmp)
      (do
        (let [conf (:conf cmp)
              {:keys [:req/events-url :es/onmessage :es/onerror :es/onopen :es/constructor]
                :or {onmessage   (if debug? js/console.log)
                     constructor js-event-source}} conf
              src (constructor {:url events-url
                                :onmessage (fn [e]
                                             (let [msg (read-string e.data)]
                                               (a/put! msg-ch e)
                                               (if (fn? onmessage) (onmessage msg))))
                                :onerror (fn [e]
                                           (reset! es-state :disruption)
                                           (if (fn? onerror) (onerror e)))
                                :onopen  (fn [e]
                                           (reset! es-state :ok)
                                           (if (fn? onopen) (onopen e)))})]
          (-> cmp
              (assoc :src src))))))
  (stop [cmp]
    (if (:src cmp)
      (do
        (println "closing stream")
        (.close (:src cmp))
        (dissoc cmp :src))
      (do
        (println "stream already closed")
        cmp))))

(defn remove-slash [url]
  (if (ends-with? url "/")
    (second (re-matches  #"(.*)(/$)" url))
    url))

(defn add-path [url part]
  (let [path (-> url .getPath remove-slash)]
    (.setPath (.clone url) (str path "/" part))))

(defn validate [conf]
  (let [url (goog.Uri.parse (get conf :url "/api"))
        {:keys [:req/login-url
                :req/logout-url
                :req/command-url
                :req/query-url
                :req/events-url
                :req/post-fn
                :req/get-fn]
         :or {login-url   (add-path url "login")
              logout-url  (add-path url "logout")
              command-url (add-path url "command")
              query-url   (add-path url "query")
              events-url  (add-path url "events")
              post-fn     post
              get-fn      get-req
              }} conf]
    (-> conf
        (assoc :req/login-url login-url)
        (assoc :req/logout-url logout-url)
        (assoc :req/command-url command-url)
        (assoc :req/query-url query-url)
        (assoc :req/events-url events-url)
        (assoc :req/post-fn post-fn)
        (assoc :req/get-fn get-fn)
        (assoc :url url))))

(defprotocol Requests
  (login [this params]
         [this params tap])
  (logout [this]
          [this tap])
  (command [this command-name args]
           [this command-name args tap])
  (query [this params]
         [this params tap]))

(defprotocol Stream
  (stream [this])
  (publish-response [this channel resp-chan tap])
  (publish-events [this event-stream]))

(def not-started
  "Client not started: use (client/start sys)")

(defn token-header [token]
  {"authorization" (str "Token " token)})

(defrecord Client [conf event-source pub-chan client-state]
  Stream
  (stream [cmp]
    (if-not (:pub-chan cmp) (throw not-started))
    (:pub-chan cmp))
  (publish-response [{:keys [:pub-chan] :as cmp} channel resp-chan tap]
    (go
      (let [response (a/<! resp-chan)]
        (a/>! pub-chan {:channel channel
                        :response response
                        :tap tap})))
    cmp)
  (publish-events [{:keys [:pub-chan] :as cmp} msg-ch]
    (a/pipeline 1
                pub-chan
                (map (fn [e] {:channel (keyword (symbol "event" e.type))
                              :event (read-string e.data)}))
                msg-ch)
    cmp)
  Requests
  (login [cmp params]
    (login cmp params {}))
  (login [cmp params tap]
    (if-not (:pub-chan cmp) (throw not-started))
    (let [{{:keys [:req/login-url :req/post-fn]} :conf} cmp]
      (publish-response cmp
                        :response/login
                        (post-fn login-url params)
                        tap)
      cmp))
  (logout [cmp]
    (logout cmp {}))
  (logout [cmp tap]
    ;; makes a request in order to let the browser manage cors cookies
    (if-not (:pub-chan cmp) (throw not-started))
    (let [{{:keys [:req/logout-url :req/get-fn :auth/token]} :conf} cmp
          headers (if token (token-header token) {})]
      (publish-response cmp
                        :response/logout
                        (get-fn logout-url {} headers)
                        tap)
      cmp))
  (query [cmp params]
    (query cmp params {}))
  (query [cmp params tap]
    (if-not (:pub-chan cmp) (throw not-started))
    (let [{{:keys [:req/query-url :req/get-fn :auth/token]} :conf} cmp
          headers (if token (token-header token) {})]
      (publish-response cmp
                        :response/query
                        (get-fn query-url params headers)
                        tap)
      cmp))
  (command [cmp command-name args]
    (command cmp command-name args {}))
  (command [cmp command-name args tap]
    (if-not (:pub-chan cmp) (throw not-started))
    (let [{{:keys [:req/command-url :req/post-fn :auth/token]} :conf} cmp
          headers (if token (token-header token) {})
          params {:command command-name :args args}]
      (publish-response cmp
                        :response/command
                        (post-fn command-url params headers)
                        tap)
      cmp))
  component/Lifecycle
  (start [cmp]
    (let [{{:keys [:es-state :msg-ch]} :event-source} cmp]
      ;; When the state of the event source changes, it will be propagated here.
      ;; This way the user can be concerned with only the client, instead of the
      ;; client and the event source.
      (if es-state
        (do
          ;; in case multiple watchers(?)
          (remove-watch es-state :client)
          (add-watch es-state
                     :client
                     (fn [k r o n] ; n is new value
                       (reset! client-state n)))))
      (-> cmp
          (publish-events msg-ch)))))

;; copied from bones.http/build-system
(defn build-system [sys conf]
  {:pre [(instance? cljs.core/Atom sys)
         (cljs.core.associative? conf)]}
  ;; simplify the api even more by not requiring a system-map from the user
  ;; sys may or may not already be a system-map
  ;; reduce-concat breaks it apart and puts it back together
  (swap! sys #(-> (apply component/system-map (reduce concat %))
                  (assoc :conf (validate conf))
                  (assoc :event-source (component/using (map->EventSource {:es-state (atom :before)
                                                                           :msg-ch (a/chan)})
                                                        [:conf]))
                  (assoc :client (component/using (map->Client {:client-state (atom :before)
                                                                :pub-chan (a/chan)})
                                                  [:conf :event-source])))))

(defn start [sys]
  (swap! sys component/start-system))
