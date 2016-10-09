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

;; TODO: look into whether event listeners can be added to this
(defn js-event-source [{:keys [url onmessage onerror onopen]}]
  (let [src (js/EventSource. url #js{:withCredentials true})]
    (set! (.-onmessage src) onmessage)
    (set! (.-onerror src) onerror)
    (set! (.-onopen src) onopen)
    src))

;; careful, chrome hides a 401 response so if you see a blankish request/response
;; try switching to firefox to see the 401 unauthorized response
(defrecord EventSource [conf src url onmessage onerror onopen state constructor]
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
              msg-ch (a/chan)
              src (constructor {:url events-url
                                :onmessage (fn [e]
                                             ;; TODO: maybe coerce from schema or something
                                             (let [msg (read-string e.data)]
                                               (a/put! msg-ch msg)
                                               (if (fn? onmessage) (onmessage msg))))
                                :onerror (fn [e]
                                           (reset! (:state cmp) :disruption)
                                           (if (fn? onerror) (onerror e)))
                                :onopen  (fn [e]
                                           (reset! (:state cmp) :ok)
                                           (if (fn? onopen) (onopen e)))})]
          (-> cmp
              (assoc :msg-ch msg-ch)
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

;; copied from bones.http/build-system
(defn build-system [sys conf]
  {:pre [(instance? cljs.core/Atom sys)
         (cljs.core.associative? conf)]}
  ;; simplify the api even more by not requiring a system-map from the user
  ;; sys may or may not already be a system-map
  ;; reduce-concat breaks it apart and puts it back together
  (swap! sys #(-> (apply component/system-map (reduce concat %))
                  (assoc :conf (validate conf))
                  (assoc :event-source (component/using (map->EventSource {:state (atom :before)})
                                                        [:conf]))
                  (assoc :client (component/using (map->Client {:state (atom :before)})
                                                  [:conf :event-source])))))

(defn start [sys]
  (swap! sys component/start-system))

(defprotocol Requests
  (login [this params])
  (logout [this])
  (command [this command args])
  (query [this params]))

(defprotocol Stream
  (stream [this channel])
  (publish-response [this channel resp-chan]))

(def not-started
  "Client not started: use (client/start sys)")

(defn token-header [token]
  {"authorization" (str "Token " token)})

(defrecord Client [conf event-source state]
  Stream
  (stream [cmp channel]
    (if-not (:sub-chan cmp) (throw not-started))
    (let [chan (a/chan)]
      (a/sub (:sub-chan cmp) channel chan)
      chan))
  (publish-response [{:keys [:pub-chan] :as cmp} channel resp-chan]
    (a/pipeline 1
                pub-chan
                (map #(assoc {:channel channel} :response %))
                resp-chan)
    cmp)
  Requests
  (login [cmp params]
    (if-not (:pub-chan cmp) (throw not-started))
    (let [{{:keys [:req/login-url :req/post-fn :auth/token]} :conf} cmp
          headers (if token (token-header token) {})]
      (publish-response cmp
                        :response/login
                        (post-fn login-url params headers))
      cmp))
  (logout [cmp]
    ;; makes a request in order to let the browser manage cors cookies
    (if-not (:pub-chan cmp) (throw not-started))
    (let [{{:keys [:req/logout-url :req/get-fn :auth/token]} :conf} cmp
          headers (if token (token-header token) {})]
      (publish-response cmp
                        :response/logout
                        (get-fn logout-url {} headers))
      cmp))
  (query [cmp params]
    (if-not (:pub-chan cmp) (throw not-started))
    (let [{{:keys [:req/query-url :req/get-fn :auth/token]} :conf} cmp
          headers (if token (token-header token) {})]
      (publish-response cmp
                        :response/query
                        (get-fn query-url params headers))
      cmp))
  (command [cmp command args]
    (if-not (:pub-chan cmp) (throw not-started))
    (let [{{:keys [:req/command-url :req/post-fn :auth/token]} :conf} cmp
          headers (if token (token-header token) {})
          params {:command command :args args}]
      (publish-response cmp
                        :response/command
                        (post-fn command-url params headers))
      cmp))
  component/Lifecycle
  (start [cmp]
    (let [pub-chan (a/chan 1)
          {{:keys [:state :msg-ch]} :event-source} cmp]
      ;; this requires a few milliseconds because the
      ;; start of the EventSource happens before this component
      ;; the duration of a web request would be sufficient
      ;; the result of the EventSource connection will be propagated here
      ;; designed like this for a simple user interface
      (add-watch state
                 :client
                 (fn [k r o n] ; n is new value
                   (reset! (:state cmp) n)))

      ;; (if [msg-ch (get-in cmp [:event-source :msg-ch])]
      ;;   (publish-response cmp :es/event msg-ch))
      (-> cmp
          (assoc :pub-chan pub-chan)
          (assoc :sub-chan (a/pub pub-chan :channel))
          (publish-response :es/event msg-ch)
          (assoc :state (or state (atom :before)))))))

