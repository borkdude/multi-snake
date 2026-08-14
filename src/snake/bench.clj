(ns snake.bench
  "What each version puts on the wire for the same game.

  Both servers run here in one process over one world, so the two streams
  describe the same snakes moving the same way at the same time. Bytes are
  counted where a browser would count them: at the socket, off a client that
  opens the stream and reads it.

      bb bench            # 6 bots, 15 seconds a phase
      bb bench 12 30

  Phases run one after the other and Buzz is measured in each, so its number is
  the control: if it moves between phases, the world got busier, not better."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [org.httpkit.server :as server]
            [snake.ds :as ds]
            [snake.game :as game]
            [snake.main :as main]))

(def ^:private keys' ["ArrowUp" "ArrowDown" "ArrowLeft" "ArrowRight"])

;; A bot has to keep steering or the idle drop takes it, and a board where
;; nothing turns is a board where every row is the same as the last one.
(defn bots! [n]
  (let [pids (mapv #(game/join! (str "bot" %)) (range n))]
    (future
      (loop []
        (Thread/sleep 300)
        (doseq [pid pids] (game/turn! pid (rand-nth keys')))
        (recur)))
    pids))

(defn- read-for
  "Opens the stream and reads it for `ms`, answering with the bytes that came
  down it. The read blocks between frames, so the clock is checked between
  them rather than during one.

  The first read is thrown away. Both servers open with a whole page, and a
  first paint counted into fifteen seconds would say more about how often the
  bench reconnects than about how either one runs."
  [url ms]
  (let [in  (:body (http/get url {:as :stream :throw false}))
        buf (byte-array 65536)
        _   (.read in buf)
        end (+ (System/currentTimeMillis) ms)]
    (try
      (loop [n 0]
        (if (< (System/currentTimeMillis) end)
          (let [r (.read in buf)]
            (if (pos? r) (recur (+ n r)) n))
          n))
      (finally (.close in)))))

(defn- phase [label ms]
  (let [buzz (future (read-for "http://localhost:1350/events" ms))
        dstar (future (read-for "http://localhost:1351/updates" ms))
        secs (/ ms 1000.0)
        per  (fn [n] (format "%8.1f KB/s  %6.0f B/tick"
                             (/ n 1024.0 secs)
                             (/ n (/ (* secs 1000) game/tick-ms))))]
    (println (format "%-12s buzz %s" label (per @buzz)))
    (println (format "%-12s ds   %s" label (per @dstar)))))

;; The other half of a tick: what it costs to make the frame in the first place.
;; Buzz turns the board into values and encodes them; Datastar turns the same
;; board into HTML. Both run once a tick no matter how many are watching.
(defn- render [label f n]
  (dotimes [_ 200] (f))
  (let [start (System/nanoTime)]
    (dotimes [_ n] (f))
    (println (format "%-12s %6.2f ms a frame" label
                     (/ (- (System/nanoTime) start) n 1e6)))))

(defn- renders []
  (let [g @game/state]
    (println)
    (reset! ds/mode :full)
    (render "buzz values" #(json/generate-string (game/rows g)) 500)
    (render "ds html"     #(@#'ds/board-html (game/rows g)) 500)))

(defn -main [& args]
  (let [n    (or (some-> (first args) parse-long) 6)
        secs (or (some-> (second args) parse-long) 15)]
    (server/run-server main/app {:port 1350})
    (server/run-server ds/app {:port 1351})
    ;; Only the Datastar ticker runs: it is the one that steps the world, and
    ;; Buzz patches from a watch on the same atom. Two tickers would step twice.
    @ds/ticker
    (bots! n)
    (println n "bots," secs "seconds a phase, one client on each server\n")
    (reset! ds/mode :full)
    (phase "whole board" (* 1000 secs))
    (reset! ds/mode :rows)
    (phase "changed rows" (* 1000 secs))
    (reset! ds/mode :cells)
    (phase "changed cells" (* 1000 secs))
    (renders)
    (shutdown-agents)
    (System/exit 0)))
