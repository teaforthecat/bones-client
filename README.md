# bones.client

A Clojurescript library designed to make http requests to a CQRS and SSE server.
All responses and events are sent into a single core.async channel. 

## Overview

The interface consists of two parts, configuration and a protocol.

### Configuration

Minimal configuration is required. The `bones.client` will establish a SSE connection
when the client is started. It the client is not authenticated the
client will have to be started again upon login. 

```clojure
(require '[bones.client :as client])
(def sys (atom {}))
(client/build-system sys {:url "/api"})
(client/start sys)
(get-in @sys [:client :state]) ;;=> :ok
```


### Protocol

These functions return the client, which isn't very interesting.

```clojure
(client/login (:client @sys) {:username "abc" :password "123"})
(client/logout (:client @sys))
(client/command (:client @sys) :who {:name "abc" :role "user"})
(client/query (:client @sys) {:q {"abc" 123}})
```

The responses are emitted on the stream:

```clojure
(client/stream (:client @sys)) ;; returns a core.async/chan
;; => {:channel :response/login :response {:status 200 ...}}
;; => {:channel :response/logout :response {:status 200 ...}}
;; => {:channel :response/command :response {:status 200 ...}}
;; => {:channel :response/query :response {:status 200 ...}}
```

If the client received an event on the SSE connection such as:

```
event: mmm
data: {:what "whopper"}
```

Then the stream would emit: 

```clojure
(client/stream (:client @sys)) ;; returns a core.async/chan
;; => {:channel :event/mmm :event {:what "whopper"}}
```


## Development

To get an interactive development environment run:

    lein figwheel

and open your browser at [localhost:3449](http://localhost:3449/).
This will auto compile and send all changes to the browser without the
need to reload. After the compilation process is complete, you will
get a Browser Connected REPL. An easy way to try it is:

    (js/alert "Am I connected?")

and you should see an alert in the browser window.

To clean all compiled files:

    lein clean

To create a production build run:

    lein do clean, cljsbuild once min

And open your browser in `resources/public/index.html`. You will not
get live reloading, nor a REPL. 

## License

Copyright © 2014 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
