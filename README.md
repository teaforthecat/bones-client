# bones.client

A Clojurescript library designed to make http requests to the a CQRS 

## Overview

The interface consists of two parts, configuration and a protocol.

### Configuration
The happy path is when a `bones.http` server is running and serving the static assets that
include your cljs app, and the user/browser has already authenticated/logged in.
If those three things are true `bones.client` will establish a SSE connection
when the client is started.
```clojure
(require '[bones.client :as client])
(def sys (atom {}))
(client/build-system sys {})
(client/start sys)
(get-in @sys [:client :state]) ;;=> :ok
```


### Protocol

```clojure
(client/login (:client @sys) {:username "abc" :password "123"})
(client/logout (:client @sys))
(client/command (:client @sys) :who {:name "abc" :role "user"})
(client/query (:client @sys) {:q {"abc" 123}})
(client/stream (:client @sys)) ;; returns a core.async/chan
;; => {:channel :response/login :response {:status 200 ...}}
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
