(ns snake.squeeze
  "What a captured stream costs once it is compressed.

      bb capture                  # writes streams/
      clojure -M:squeeze streams

  A stream is compressed the way a server would have to compress it: one
  encoder for the connection, flushed at the end of every frame, so a frame is
  deliverable when it is written and every frame after the first is coded
  against the ones before it. Compressing each frame on its own would miss
  that, and it is the whole reason a board that barely changes gets cheap."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [com.aayushatharva.brotli4j Brotli4jLoader]
           [com.aayushatharva.brotli4j.encoder BrotliOutputStream Encoder$Parameters]
           [java.io ByteArrayOutputStream]
           [java.util.zip GZIPOutputStream]))

(defn- frames
  "The SSE frames in a file, whole ones only. A capture stops mid-frame as often
  as not, and half a frame would compress like nothing else in the stream."
  [file]
  (->> (re-seq #"(?s).*?\n\n" (slurp file))
       (mapv #(.getBytes ^String % "UTF-8"))))

(defn- gzipped [frames]
  (let [bos (ByteArrayOutputStream.)]
    (with-open [out (GZIPOutputStream. bos 8192 true)]
      (doseq [^bytes f frames]
        (.write out f)
        (.flush out)))
    (.size bos)))

(defn- brotlied [frames quality]
  (let [bos    (ByteArrayOutputStream.)
        params (doto (Encoder$Parameters.) (.setQuality (int quality)) (.setWindow 22))]
    (with-open [out (BrotliOutputStream. bos params)]
      (doseq [^bytes f frames]
        (.write out f)
        (.flush out)))
    (.size bos)))

;; Compressing is paid once a connection, not once a tick, so what it costs a
;; frame is worth as much as what it saves.
(defn- timed [f]
  (let [start (System/nanoTime)
        n     (f)]
    [n (/ (- (System/nanoTime) start) 1e6)]))

(defn -main [& args]
  (Brotli4jLoader/ensureAvailability)
  (let [dir   (or (first args) "streams")
        {:keys [seconds tick-ms bots]} (edn/read-string (slurp (str dir "/meta.edn")))
        ticks (/ (* seconds 1000.0) tick-ms)
        per   (fn [n] (/ n ticks))
        rows  (for [mode ["full" "rows" "cells"]
                    side ["buzz" "ds"]
                    :let [file (str dir "/" side "-" mode ".sse")]
                    :when (.exists (io/file file))
                    :let  [fs (frames file)]]
                {:mode mode :side side
                 :raw  (reduce + (map alength fs))
                 :n    (count fs)
                 :gzip (timed #(gzipped fs))
                 :br5  (timed #(brotlied fs 5))
                 :br11 (timed #(brotlied fs 11))})]
    (println bots "bots," seconds "seconds a mode\n")
    (println "bytes a tick")
    (println (format "%-6s %-5s %8s %8s %8s %8s" "mode" "" "raw" "gzip" "br 5" "br 11"))
    (doseq [{:keys [mode side raw gzip br5 br11]} rows]
      (println (format "%-6s %-5s %8.0f %8.0f %8.0f %8.0f"
                       (if (= "buzz" side) mode "") side
                       (per raw) (per (first gzip))
                       (per (first br5)) (per (first br11)))))
    (println "\nms a frame, a connection")
    (println (format "%-6s %-5s %8s %8s %8s %8s" "mode" "" "" "gzip" "br 5" "br 11"))
    (doseq [{:keys [mode side n gzip br5 br11]} rows]
      (println (format "%-6s %-5s %8s %8.3f %8.3f %8.3f"
                       (if (= "buzz" side) mode "") side ""
                       (/ (second gzip) n) (/ (second br5) n) (/ (second br11) n))))
    (println "\none encoder a connection, flushed a frame, window 22")
    (shutdown-agents)))
