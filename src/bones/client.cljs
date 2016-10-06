(ns bones.client
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs-http.client :as http]
            ;; [cljs.spec :as s]
            [clojure.string :refer [ends-with? join]]
            [com.stuartsierra.component :as component]
            [cljs.core.async :as a]))

(def debug?
  ^boolean js/goog.DEBUG)

(defn init []
  (when debug?
    (enable-console-print!)))

(defn command [data & token]
  ;; maybe validate :command, :args present
  (go
    (let [url "http://localhost:8080/api/command"
          req {:edn-params data
               :headers (if token
                          {"authorization" (str "Token " token)}
                          {})
               }
          resp (<! (http/post url req))]
      resp)))

(defn login [data]
  (go
    (let [url "http://localhost:8080/api/login"
          req {:edn-params data}
          resp (<! (http/post url req))]
      resp)))

(defn logout []
  (go
    (let [url "http://localhost:8080/api/logout"
          resp (<! (http/get url))]
      resp)))

(defn query [data & token]
  ;; maybe validate :query, :args present
  (go
    (let [url "http://localhost:8080/api/query"
          req {:query-params data
               :headers (if token
                          {"authorization" (str "Token " token)}
                          {})}
          resp (<! (http/get url req))]
      resp)))

(comment
  #_(a/take! (command {:command :echo :args {:hello "mr"}}
                    (get-in @re-frame.core/db [:bones/token])))
  #_(a/take! (post "http://localhost:8080/api/login"
                 {:command :login
                  :args {:username "abc" :password "xyz"}} )
           println)
  )

(defn channels []
  (atom {"onopen" (a/chan)
         "onerror" (a/chan (a/dropping-buffer 10))
         "onmessage" (a/chan 100) ;;notsureifwanttoblockconnection
         "onclose" (a/chan)
         "send" (a/chan 10)
         "close" (a/chan)}))

(defonce conn (channels))

(defn listen [url {:keys [:constructor] :or {constructor js/WebSocket. }}]
  "binds a js/websocket to a core-async channel event bus"
  (let [websocket (constructor url)
        webbus (a/chan)
        event-bus (a/pub webbus first)
        publish! #(a/put! webbus [%1 %2])
        subscribe! #(a/sub event-bus %1 (get @conn %1))]
    (doto websocket
      (aset "binaryType" "arraybuffer") ;;notsureifrequired
      (aset "onopen"    #(publish! "onopen" "open!"))
      (aset "onerror"   #(publish! "onerror" %))
      (aset "onmessage" #(publish! "onmessage" %))
      (aset "onclose"   #(do (publish! "onclose" "closed!")
                             (swap! conn dissoc "event-bus")
                             (a/close! webbus))))
    (a/sub event-bus "send" (get @conn "send"))
    (a/sub event-bus "close" (get @conn "close"))
    (go-loop []
        (let [msg (a/<! (get @conn "send"))]
          #(.send websocket %)
          (recur)))
    (go-loop []
        (let [msg (a/<! (get @conn "close"))]
          #(.close websocket %)))

    (subscribe! "onopen")
    (subscribe! "onerror")
    (subscribe! "onmessage")
    (subscribe! "onclose")
    (swap! conn assoc "event-bus" event-bus)
    websocket))

(defn es []
  (.-readyState (js/EventSource. "/" )))

;; careful, chrome hides a 401 response so if you see a blankish request/response
;; try switching to firefox to see the 401 unauthorized response
(defrecord EventSource [event-source url onmessage onerror onopen state]
  component/Lifecycle
  (start [cmp]
    (if (:event-source cmp)
      (do
        (println "already started stream")
        cmp)
      (do
        (println "starting event stream")
        (let [src (js/EventSource. (:url cmp) #js{ :withCredentials true } )]
          (set! (.-onmessage src) (:onmessage cmp))
          (set! (.-onerror src) (fn [e] (reset! (:state cmp) :disruption)))
          (set! (.-onopen src) (fn [e] (reset! (:state cmp) :ok)))
          (-> cmp
              (assoc :event-source src)
              (assoc :state (.-readyState src)))))))
  (stop [cmp]
    (if (:event-source cmp)
      (do
        (println "closing stream")
        (.close (:event-source cmp))
        (dissoc cmp :event-source))
      (do
        (println "stream already closed")
        cmp))))

(defn event-source [{:keys [url onmessage onerror onopen]}]
  {:url url
   :onmessage (fn [m] ())})

(defrecord Client [conf event-source state]
  component/Lifecycle
  (start [cmp]
    ;; (let [event-source-map ])
    (-> cmp
        ;; (assoc :event-source (component/start (map->EventSource event-source-map)))
        (assoc :state (atom :before)))))

(defn validate [conf]
  (let [url (:url conf)]
    (assoc conf :url (goog.Uri.parse url))))

(defn remove-slash [url]
  (if (ends-with? url "/")
    (second (re-matches  #"(.*)(/$)" url))
    url))

(defn add-path [url part]
  (let [path (-> url .getPath remove-slash)]
    (.setPath url (str path "/" "events"))))

(defn event-handler [message]
  (.log js/console message))

(defn add-event-handlers [conf]
  ;; conf wins on conflict
  (merge
   {:es/url (add-path (:url conf) "events")
    :es/onmessage #(event-handler %)
    }
   conf ))

;; copied from bones.http/build-system
(defn build-system [sys conf]
  ;; simplify the api even more by not requiring a system-map from the user
  {:pre [(instance? cljs.core/Atom sys)
         (cljs.core.associative? conf)]}
  (swap! sys #(-> (apply component/system-map (reduce concat %)) ;; if already a system-map break apart and put back together
                  (assoc :conf (add-event-handlers (validate conf)))
                  (assoc :event-source (component/using (map->EventSource {:state (atom :before)})
                                                        [:conf]))
                  (assoc :client (component/using (map->Client {})
                                                  [:conf :event-source])))))

(defn start [sys]
  (swap! sys component/start-system))


(comment
  (def a (a/chan))
  (go-loop []
    (a/take! a println))
  (def e (component/start (map->EventSource {:msg-ch a :url "http://localhost:8080/api/events"})))
  (component/stop e)

  (a/take! (logout) println)


  (listen "ws://localhost:8080/api/ws" {})
  (js/WebSocket. "ws://localhost:8080/api/ws")

  )
