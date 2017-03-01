(ns bones.client
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs-http.client :as http]
            [cljs.reader :refer [read-string]]
            [clojure.string :refer [ends-with? join]]
            [com.stuartsierra.component :as component]
            [cljs.core.async :as a]))

(def debug?
  ^boolean js/goog.DEBUG)

(defn log [msg]
  (when debug?
    (println msg)))

(when debug?
  (enable-console-print!))

;; returns a channel
(defn post
  ([url params]
   (post url params {}))
  ([url params headers]
   (let [req {:edn-params params
              :headers headers}]
     (http/post url req))))

;; returns a channel
(defn get-req
  ([url params]
   (get-req url params {}))
  ([url params headers]
   (let [req {:query-params params
              :headers headers}]
     (http/get url req))))

(defn js-websocket [{:keys [url onmessage onerror onopen on-exception]}]
  ;; react-native wants a string, so, it gets a string
  (try
    (let [src (js/WebSocket. (str url))]
      (set! (.-onmessage src) onmessage)
      (set! (.-onerror src) onerror)
      (set! (.-onopen src) onopen)
      src)
    (catch :default e
      (if (fn? on-exception)
        (on-exception)
        (throw e)))))

(defn js-event-source [{:keys [url onmessage onerror onopen on-exception]}]
  (try
    (let [src (js/EventSource. url #js{:withCredentials true})]
      (set! (.-onmessage src) onmessage)
      (set! (.-onerror src) onerror)
      (set! (.-onopen src) onopen)
      src)
    (catch :default e
      (if (fn? on-exception)
        (on-exception)
        (throw e)))))

;; careful, chrome hides a 401 response so if you see a blankish request/response
;; try switching to firefox to see the 401 unauthorized response
(defrecord EventSource [conf src url msg-ch onmessage onerror onopen es-state constructor]
  component/Lifecycle
  (start [cmp]
    (if (:src cmp)
      (do
        (log "already started stream")
        cmp)
      (do
        (let [conf (:conf cmp)
              {:keys [:req/events-url :req/websocket-url :es/connection-type
                      :es/onmessage :es/onerror :es/onopen :es/constructor]
               :or {onmessage log
                    connection-type :event-source
                    constructor (if (= connection-type :websocket)
                                  js-websocket
                                  js-event-source)}} conf
              client-status (fn [logged-in?] (new js/MessageEvent
                                                  "client-status"
                                                  ;; this data acts like a message from SSE or websocket
                                                  (clj->js {:data (str {:bones/logged-in? logged-in?})})))
              logged-in #(a/put! msg-ch (client-status true))
              logged-out #(a/put! msg-ch (client-status false))
              ;; maybe declare websocket instead of infer it
              src (constructor {:url (if (= connection-type :websocket)
                                       websocket-url
                                       events-url)
                                :onmessage (fn [e]
                                             (a/put! msg-ch e)
                                             (if (fn? onmessage)
                                               (let [msg (read-string (.-data e))]
                                                 (onmessage msg))))
                                :onerror (fn [e]
                                           (reset! es-state :disruption)
                                           (if (fn? onerror) (onerror e)))
                                :onopen  (fn [e]
                                           (logged-in)
                                           (reset! es-state :ok)
                                           (if (fn? onopen) (onopen e)))
                                :on-exception logged-out})]
          (-> cmp
              (assoc :src src))))))
  (stop [cmp]
    (if (:src cmp)
      (do
        (log "closing stream")
        (.close (:src cmp))
        (reset! es-state :done)
        (assoc cmp :src nil))
      (do
        (log "stream already closed")
        cmp))))

(defn remove-slash [url]
  (if (ends-with? url "/")
    (second (re-matches  #"(.*)(/$)" url))
    url))

(defn add-path [url part]
  (let [path (-> url .getPath remove-slash)]
    (.setPath (.clone url) (str path "/" part))))

(defn add-ws-scheme [url]
  (let [scheme (if (= (.getScheme url) "https") "wss" "ws")]
    (.setScheme (.clone url) scheme)))

(defn conform [conf]
  (let [url (goog.Uri.parse (get conf :url "/api"))
        {:keys [:req/login-url
                :req/logout-url
                :req/command-url
                :req/query-url
                :req/events-url
                :req/websocket-url
                :req/post-fn
                :req/get-fn
                :stream-handler]
         :or {login-url   (add-path url "login")
              logout-url  (add-path url "logout")
              command-url (add-path url "command")
              query-url   (add-path url "query")
              events-url  (add-path url "events")
              websocket-url (add-ws-scheme (add-path url "ws"))
              post-fn     post
              get-fn      get-req
              stream-handler log
              }} conf]
    (-> conf
        (assoc :req/login-url login-url)
        (assoc :req/logout-url logout-url)
        (assoc :req/command-url command-url)
        (assoc :req/query-url query-url)
        (assoc :req/events-url events-url)
        (assoc :req/websocket-url websocket-url)
        (assoc :req/post-fn post-fn)
        (assoc :req/get-fn get-fn)
        (assoc :url url)
        (assoc :stream-handler stream-handler))))

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
  (stream-loop [this])
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
  (stream-loop [cmp]
    ; this lets a function handle the streaming messages, formatted as list,
    ; perfect for re-frame
    (let [s (stream cmp)
          handler (:stream-handler conf)]
      (assoc cmp :stream-loop
             (go-loop []
               ;; revent is a response or event
               (let [revent (a/<! s)
                     body (or (get-in revent [:response :body])
                              (:event revent))]
                 (handler [(:channel revent)
                        ;; No body is OK sometimes. Logout, for example, only
                        ;; needs a header but the response(event) may be
                        ;; useful, use schema/spec after this point
                        body
                        (get-in revent [:response :status])
                        (:tap revent)]))
               (recur)))))
  (publish-response [{:keys [:pub-chan] :as cmp} channel resp-chan tap]
    (go
      (let [response (a/<! resp-chan)]
        (a/>! pub-chan {:channel channel
                        :response response
                        :tap tap})))
    cmp)
  (publish-events [{:keys [:pub-chan] :as cmp} msg-ch]
    (if-not (:pipeline cmp)
      (assoc cmp :pipeline
             (a/pipeline 1
                         pub-chan
                         (map (fn [e] {:channel (keyword (symbol "event" e.type))
                                       :event (read-string e.data)}))
                         msg-ch))
      (do (log "already publishing events")
          cmp)))

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
    (if-not (:event-source cmp) (throw "event-source missing from Client"))
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
          (publish-events msg-ch)
          (stream-loop)))))

;; copied from bones.http/build-system
(defn build-system [sys conf]
  {:pre [(instance? cljs.core/Atom sys)
         (cljs.core.associative? conf)]}
  ;; simplify the api even more by not requiring a system-map from the user
  ;; sys may or may not already be a system-map
  ;; reduce-concat breaks it apart and puts it back together
  (swap! sys #(-> (apply component/system-map (reduce concat %))
                  (assoc :conf (conform conf))
                  (assoc :event-source (component/using (map->EventSource {:es-state (atom :before)
                                                                           :msg-ch (a/chan)})
                                                        [:conf]))
                  (assoc :client (component/using (map->Client {:client-state (atom :before)
                                                                :pub-chan (a/chan)})
                                                  [:conf :event-source])))))

(defn start [sys]
  (swap! sys component/start-system))

(defn stop [sys]
  (swap! sys component/stop-system))

;; this is important because attributes of the client need to be cleared to
;; start again
(defn restart [sys]
  (stop sys)
  (start sys))
