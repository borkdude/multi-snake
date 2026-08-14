(ns snake.ds
  "The same game, served to Datastar instead of Buzz. `snake.game` is untouched:
  both servers hold the same world in the same atom and could run side by side
  in one process.

  What differs is what crosses the wire. Buzz sends the values a component's
  server slots produced and lets the browser render them; Datastar sends
  rendered HTML and lets the browser morph it into the page. Two ways of
  spending the same tick.

  Run with --diff to patch only the rows that changed instead of the whole
  board, which is the hand-written version of the diff Buzz does for you."
  (:require [babashka.nrepl.server :as nrepl]
            [cheshire.core :as json]
            [hiccup2.core :as h]
            [org.httpkit.server :as http]
            [snake.game :as game]))

;; tab -> {:ch channel :pid atom :sent atom}. A browser is a stream, the same
;; way a browser is a connection in the Buzz version, but nothing here is given
;; that for free: the id is minted here, sent to the browser as a signal, and
;; comes back on every request the browser makes.
(defonce conns (atom {}))
(defonce next-tab (atom 0))

;; Whole board or only the rows that changed.
(defonce mode (atom :full))

(def ^:private sse-headers
  {"Content-Type"      "text/event-stream"
   "Cache-Control"     "no-cache"
   "X-Accel-Buffering" "no"})

;; The two events Datastar listens for. `elements` has to stay on one line: a
;; newline inside the HTML would end the data line and split the frame.
(defn- patch-elements! [ch html]
  (http/send! ch (str "event: datastar-patch-elements\ndata: elements " html "\n\n") false))

(defn- patch-signals! [ch m]
  (http/send! ch (str "event: datastar-patch-signals\ndata: signals " (json/generate-string m) "\n\n") false))

;; The page. Morphing matches top-level elements by id, so every patched piece
;; needs one and has to keep it.

;; A cell gets an id only when something is going to patch it by id: the id is
;; ten bytes on every cell of every board, which is a poor trade for a mode that
;; never names one.
(defn- cell-html [x y c]
  (str (h/html (if (= :cells @mode)
                 [:div {:id (str "c" x "-" y) :class (str "cell " c)}]
                 [:div {:class (str "cell " c)}]))))

(defn- row-html [y row]
  (str (h/html [:div.row {:id (str "r" y)}
                (map-indexed (fn [x c] (h/raw (cell-html x y c))) row)])))

(defn- board-html [rows]
  (str (h/html [:div.board {:id "board"}
                (map-indexed (fn [y row] (h/raw (row-html y row))) rows)])))

(defn- you-html [me]
  [:div.you {:class (str "p" (:color me))}
   [:p.mine (:name me) [:span.pts (:score me)]]
   [:p.hint (cond (:idle-in me) (str "still there? dropping you in " (:idle-in me) "s")
                  (:alive me)   "arrows or wasd"
                  :else         "respawning")]
   [:button.leave {:data-on:click "@post('/leave')"} "leave"]])

;; `data-bind:nm` is what keeps the box usable. The side panel is rewritten
;; whenever a score moves, and a morph does not know that the half-typed name
;; in it was worth keeping; the signal does, and Datastar puts it back.
(defn- join-html []
  [:div.join
   [:p "pick a name and join. everyone plays on the same board."]
   [:input.name {:placeholder "name" :autofocus true
                 :data-bind:nm ""
                 :data-on:keydown "evt.key === 'Enter' && @post('/join')"}]
   [:button.go {:data-on:click "@post('/join')"} "join"]])

(defn- side-html [me scores]
  (str (h/html
        [:div.side {:id "side"}
         (if me (you-html me) (join-html))
         [:ul.scores
          (for [p scores]
            [:li {:class (str "p" (:color p) (when-not (:alive p) " out"))}
             [:span.swatch]
             [:span.who (:name p)]
             [:span.len (:len p)]
             [:span.pts (:score p)]])]])))

;; One tick, for one browser. Nothing goes out that the browser already has,
;; which is the same rule the Buzz handler applies to its slot values.
(defn- push! [{:keys [ch pid sent]} g rows board scores]
  (let [prev @sent
        side (side-html (game/me g @pid) scores)]
    (cond
      ;; Nothing to diff against on the first frame, and a row or a cell patch
      ;; has nothing to land on: the page ships with an empty board.
      (nil? (:rows prev))
      (patch-elements! ch board)

      :else
      (case @mode
        :full  (when (not= board (:board prev))
                 (patch-elements! ch board))
        :rows  (let [changed (keep-indexed (fn [y row]
                                             (when (not= row (get (:rows prev) y))
                                               (row-html y row)))
                                           rows)]
                 (when (seq changed)
                   (patch-elements! ch (apply str changed))))
        :cells (let [changed (for [[y row] (map-indexed vector rows)
                                   [x c]   (map-indexed vector row)
                                   :when   (not= c (get-in (:rows prev) [y x]))]
                               (cell-html x y c))]
                 (when (seq changed)
                   (patch-elements! ch (apply str changed))))))
    (when (not= side (:side prev))
      (patch-elements! ch side))
    (reset! sent {:board board :rows rows :side side})))

(defn- broadcast! []
  (let [g      @game/state
        rows   (game/rows g)
        board  (board-html rows)
        scores (game/scoreboard g)]
    (doseq [[_ conn] @conns]
      (push! conn g rows board scores))))

;; The tick. As in the Buzz version nothing in a browser drives it, so the world
;; moves at the same rate whether anyone is looking or not.
(defonce ticker
  (delay
    (future
      (loop []
        (Thread/sleep game/tick-ms)
        (try
          (when (seq (:players @game/state))
            (swap! game/state game/step))
          (broadcast!)
          (catch Exception e
            (println "snake: tick failed -" (ex-message e))))
        (recur)))))

;; Every request carries every signal the page holds, so the tab id arrives
;; without being asked for, and so does the name box.
(defn- signals [req]
  (json/parse-string (slurp (:body req)) true))

(defn- conn-for [sig] (get @conns (:tab sig)))

(defn- join! [sig]
  (when-let [{:keys [pid]} (conn-for sig)]
    (when-not (game/me @game/state @pid)
      (reset! pid (game/join! (:nm sig))))))

(defn- leave! [sig]
  (when-let [{:keys [pid]} (conn-for sig)]
    (game/leave! @pid)
    (reset! pid nil)))

(defn- turn! [sig]
  (when-let [{:keys [pid]} (conn-for sig)]
    (when @pid (game/turn! @pid (:k sig)))))

;; Answering an action with 204 rather than a stream: whatever it changed is on
;; the world atom, and the tick sends the world.
(defn- act [req f]
  (f (signals req))
  (broadcast!)
  {:status 204})

(defn- updates [req]
  (let [tab (swap! next-tab inc)
        pid (atom nil)]
    (http/as-channel
     req
     {:on-open  (fn [ch]
                  (http/send! ch {:status 200 :headers sse-headers} false)
                  (let [conn {:ch ch :pid pid :sent (atom {})}
                        g    @game/state]
                    (swap! conns assoc tab conn)
                    ;; The id first, so a key pressed during the first paint
                    ;; still knows who pressed it.
                    (patch-signals! ch {:tab tab})
                    (push! conn g (game/rows g) (board-html (game/rows g))
                           (game/scoreboard g))))
      ;; A tab that goes away takes its snake with it. The stream closing is
      ;; the whole signal; there is no session to expire.
      :on-close (fn [_ _]
                  (game/leave! @pid)
                  (swap! conns dissoc tab))})))

(defn app [{:keys [request-method uri] :as req}]
  (case [request-method uri]
    [:get "/"]        {:status 200
                       :headers {"Content-Type" "text/html; charset=utf-8"}
                       :body (slurp "public/ds.html")}
    [:get "/updates"] (updates req)
    [:post "/join"]   (act req join!)
    [:post "/leave"]  (act req leave!)
    [:post "/key"]    (act req turn!)
    {:status 404 :body "not found"}))

(defn -main [& args]
  (reset! mode (cond (some #{"--cells"} args) :cells
                     (some #{"--diff"} args)  :rows
                     :else                    :full))
  @ticker
  (let [port (or (some-> (System/getenv "PORT") parse-long) 1351)]
    (http/run-server app {:port port})
    (println (str "http://localhost:" port " (" (name @mode) ")")))
  (when (some #{"--nrepl"} args)
    (nrepl/start-server! {:port 1669})
    (println "nrepl://localhost:1669"))
  @(promise))
