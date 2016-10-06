(ns bones.client
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs-http.client :as http]
            ;; [cljs.spec :as s]
            [clojure.string :refer [ends-with? join]]
            [com.stuartsierra.component :as component]
            [cljs.core.async :as a]))

(def debug?
  ^boolean js/goog.DEBUG)

(when debug?
  (enable-console-print!))

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

;; TODO: look into whether event listeners can be added to this
(defn js-event-source [{:keys [url onmessage onerror onopen]}]
  (let [src js/EventSource. url #js{:withCredentials true}]
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
        (println "starting event stream")
        (let [{{:keys [:url :es/onmessage :es/onerror :es/onopen :es/constructor]
               :or {:es/onmessage   js/console.log
                    :es/onerror     js/console.log
                    :es/onopen      js/console.log
                    :es/constructor js-event-source}} :conf} cmp
              src (constructor {:url url
                                :onmessage onmessage
                                :onerror (fn [e]
                                           (reset! (:state cmp) :disruption)
                                           (onerror e cmp))
                                :onopen  (fn [e]
                                           (reset! (:state cmp) :ok)
                                           (apply onopen e cmp))})]
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


(defrecord Client [conf event-source state]
  component/Lifecycle
  (start [cmp]
    (let []
      ;; this requires a few milliseconds because the
      ;; start of the EventSource happens before this component
      ;; the duration of a web request would be sufficient
      ;; the result of the EventSource connection will be propagated here
      ;; designed like this for a simple user interface
      (add-watch (get-in cmp [:event-source :state])
                 :client
                 (fn [k r o n] ; n is new value
                   (reset! (:state cmp) n)))
      (-> cmp
          (assoc :state (or state (atom :before)))))))

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

(defn add-events-path [conf]
  ;; conf wins on conflict
  (merge
   {:es/url (add-path (:url conf) "events")}
   conf))

;; copied from bones.http/build-system
(defn build-system [sys conf]
  {:pre [(instance? cljs.core/Atom sys)
         (cljs.core.associative? conf)]}
  ;; simplify the api even more by not requiring a system-map from the user
  ;; sys may or may not already be a system-map
  ;; reduce-concat breaks it apart and puts it back together
  (swap! sys #(-> (apply component/system-map (reduce concat %))
                  (assoc :conf (add-events-path (validate conf)))
                  (assoc :event-source (component/using (map->EventSource {:state (atom :before)})
                                                        [:conf]))
                  (assoc :client (component/using (map->Client {:state (atom :before)})
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
