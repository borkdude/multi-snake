(ns snake.proxy
  "A compressing reverse proxy, to find out whether a browser takes a brotli
  stream one frame at a time or waits for the end of it.

      bb serve                                  # or bb ds-cells
      clojure -M:proxy 1350                     # play on http://localhost:1360

  Everything but the stream is passed through untouched. The stream is encoded
  with one brotli encoder for the connection, flushed at every chunk that
  arrives from upstream, which is what the numbers in doc/datastar.md assume."
  (:require [org.httpkit.server :as http])
  (:import [com.aayushatharva.brotli4j Brotli4jLoader]
           [com.aayushatharva.brotli4j.encoder BrotliOutputStream Encoder$Parameters]
           [java.io OutputStream]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(def ^:private client (HttpClient/newHttpClient))

(defonce ^:private upstream (atom "http://localhost:1350"))

(def ^:private streams #{"/events" "/updates"})

(defn- request [uri method body]
  (-> (HttpRequest/newBuilder (URI. (str @upstream uri)))
      (.method method (if body
                        (HttpRequest$BodyPublishers/ofByteArray body)
                        (HttpRequest$BodyPublishers/noBody)))
      (.header "Content-Type" "application/json")
      (.build)))

;; What the encoder writes goes straight out as one chunk. http-kit takes a
;; byte array, so nothing here has to pretend the bytes are text.
(defn- channel-stream [ch]
  (proxy [OutputStream] []
    (write
      ([b]
       (if (bytes? b)
         (http/send! ch b false)
         (http/send! ch (byte-array [(byte b)]) false)))
      ([b off len]
       (let [out (byte-array len)]
         (System/arraycopy b off out 0 len)
         (http/send! ch out false))))
    (flush [])
    (close [])))

(defn- pipe! [ch uri]
  (let [in  (.body (.send client (request uri "GET" nil) (HttpResponse$BodyHandlers/ofInputStream)))
        par (doto (Encoder$Parameters.) (.setQuality 5) (.setWindow 22))]
    (http/send! ch {:status 200
                    :headers {"Content-Type"     "text/event-stream"
                              "Content-Encoding" "br"
                              "Cache-Control"    "no-cache"}}
                false)
    (with-open [out (BrotliOutputStream. (channel-stream ch) par)]
      (let [buf (byte-array 65536)]
        (loop []
          (let [r (.read in buf)]
            (when (pos? r)
              (.write out buf 0 r)
              (.flush out)
              (recur))))))))

(defn- passthrough [{:keys [uri request-method body]}]
  (let [bs   (when body (.readAllBytes body))
        resp (.send client
                    (request uri (.toUpperCase (name request-method)) bs)
                    (HttpResponse$BodyHandlers/ofByteArray))]
    {:status  (.statusCode resp)
     :headers {"Content-Type" (-> resp .headers (.firstValue "Content-Type") (.orElse "text/html"))}
     :body    (.body resp)}))

(defn app [{:keys [uri] :as req}]
  (if (streams uri)
    (http/as-channel req {:on-open (fn [ch] (future (pipe! ch uri)))})
    (passthrough req)))

(defn -main [& args]
  (Brotli4jLoader/ensureAvailability)
  (reset! upstream (str "http://localhost:" (or (first args) 1350)))
  (let [port (or (some-> (second args) parse-long) 1360)]
    (http/run-server app {:port port})
    (println (str "http://localhost:" port " -> " @upstream " with brotli")))
  @(promise))
