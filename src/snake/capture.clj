(ns snake.capture
  "Saves what each server puts on the wire, so it can be squeezed later.

      bb capture              # 6 bots, 20 seconds a mode, into streams/
      bb capture 12 30 out/

  Both servers run over one world, as in the bench, and each mode is captured
  from both at the same time. The first read is dropped: a first paint counted
  in would say more about the capture than about the stream."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.java.io :as io]
            [org.httpkit.server :as server]
            [snake.bench :as bench]
            [snake.ds :as ds]
            [snake.game :as game]
            [snake.main :as main]))

(defn- save! [url file ms]
  (let [in  (:body (http/get url {:as :stream :throw false}))
        buf (byte-array 65536)
        _   (.read in buf)
        end (+ (System/currentTimeMillis) ms)]
    (try
      (with-open [out (io/output-stream file)]
        (loop []
          (when (< (System/currentTimeMillis) end)
            (let [r (.read in buf)]
              (when (pos? r) (.write out buf 0 r))
              (recur)))))
      (finally (.close in)))))

(defn -main [& args]
  (let [n    (or (some-> (first args) parse-long) 6)
        secs (or (some-> (second args) parse-long) 20)
        dir  (or (nth args 2 nil) "streams")
        ms   (* 1000 secs)]
    (fs/create-dirs dir)
    (server/run-server main/app {:port 1350})
    (server/run-server ds/app {:port 1351})
    @ds/ticker
    (bench/bots! n)
    (println n "bots," secs "seconds a mode, into" dir)
    (doseq [mode [:full :rows :cells]]
      (reset! ds/mode mode)
      (let [m (name mode)
            a (future (save! "http://localhost:1350/events" (str dir "/buzz-" m ".sse") ms))
            b (future (save! "http://localhost:1351/updates" (str dir "/ds-" m ".sse") ms))]
        @a @b
        (println "  " m "done")))
    (spit (str dir "/meta.edn") (pr-str {:seconds secs :tick-ms game/tick-ms :bots n}))
    (System/exit 0)))
