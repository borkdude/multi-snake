(ns snake.main
  "The page. Everything that decides anything runs on the server; the browser
  draws a table of class names and reports which key was pressed."
  (:require [babashka.nrepl.server :as nrepl]
            [buzz.core :refer [client defpart defui request server server!]]
            [buzz.handler :as buzz]
            [org.httpkit.server :as http]
            [snake.game :as game]))

;; The tick. Nothing drives it from a browser, so the world moves at the same
;; rate for everyone whether anyone is looking or not.
(defonce ticker
  (delay
    (future
      (loop []
        (Thread/sleep game/tick-ms)
        (try
          (when (seq (:players @game/state))
            (swap! game/state game/step))
          (catch Exception e
            (println "snake: tick failed -" (ex-message e))))
        (recur)))))

;; Which player a connection is. One tab is one snake, so the key is the
;; connection id the request carries. A tab that is open but not in here is a
;; spectator. Watched, so joining and leaving redraw.
(defonce players (atom {}))

(defn- my-pid [req] (get @players (buzz/connection req)))

;; Guarded on the player still being there rather than on the entry existing.
;; A player dropped for idling leaves the id behind, and refusing on that
;; would mean the join form comes back and does nothing.
(defn- join-here! [req nm]
  (when-not (game/me @game/state (my-pid req))
    (swap! players assoc (buzz/connection req) (game/join! nm))))

(defn- leave-here! [req]
  (game/leave! (my-pid req))
  (swap! players dissoc (buzz/connection req)))

(defpart score-row [p]
  [:li {:key (:id p) :class (str "p" (:color p) (when-not (:alive p) " out"))}
   [:span.swatch]
   [:span.who (:name p)]
   [:span.len (:len p)]
   [:span.pts (:score p)]])

(defui board []
  (let [rows   (server (game/rows @game/state))
        me     (server (game/me @game/state (my-pid (request))))
        scores (server (game/scoreboard @game/state))]
    [:div.game
     ;; The board holds the focus, so the keys reach it rather than the page.
     ;; A div only takes focus with a tabindex, and only takes it by itself if
     ;; something asks, which is what the mount hook is for.
     [:div.board
      {:tabindex 0
       :on-render (fn [{:keys [node lifecycle]}]
                    (when (= :mount lifecycle) (.focus node)))
       :on-key-down (fn [e]
                      (let [k (.-key e)]
                        (when (.startsWith k "Arrow") (.preventDefault e))
                        (server! (when-let [pid (my-pid (request))]
                                   (game/turn! pid (client k))))))}
      (for [row rows]
        [:div.row (for [c row] [:div {:class (str "cell " c)}])])]
     [:div.side
      (if me
        [:div.you {:class (str "p" (:color me))}
         [:p.mine (:name me) [:span.pts (:score me)]]
         [:p.hint (cond (:idle-in me) (str "still there? dropping you in " (:idle-in me) "s")
                        (:alive me)   "arrows or wasd"
                        :else         "respawning")]
         [:button.leave {:on-click (fn [_] (server! (leave-here! (request))))} "leave"]]
        [:div.join
         [:p "pick a name and join. everyone plays on the same board."]
         ;; The join button reads this box by class, so nothing else may
         ;; answer to `.name`.
         [:input.name
          {:placeholder "name"
           :autofocus true
           :on-key-down (fn [e]
                          (when (= "Enter" (.-key e))
                            (server! (join-here! (request) (client (.. e -target -value))))
                            (.focus (js/document.querySelector ".board"))))}]
         [:button.go
          {:on-click (fn [_]
                       (server! (join-here! (request)
                                            (client (.-value (js/document.querySelector ".name")))))
                       (.focus (js/document.querySelector ".board")))}
          "join"]])
      [:ul.scores (for [p scores] (score-row p))]]]))

(def ui
  (buzz/handler {:index "public/index.html"
                 :watch [game/state players]
                 :mounts [{:el "app" :component (fn [_] (board))}]
                 ;; A tab that goes away takes its snake with it, and the
                 ;; connection knows before the game does.
                 :on-close (fn [conn]
                             (game/leave! (get @players conn))
                             (swap! players dissoc conn))}))

(defn app [req]
  (or (ui req) {:status 404 :body "not found"}))

(defn -main [& args]
  @ticker
  (let [port (or (some-> (System/getenv "PORT") parse-long) 1350)]
    (http/run-server app {:port port})
    (println (str "http://localhost:" port)))
  (when (some #{"--nrepl"} args)
    (nrepl/start-server! {:port 1668})
    (println "nrepl://localhost:1668"))
  @(promise))
