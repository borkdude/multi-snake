(ns snake.main
  "The page. Everything that decides anything runs on the server; the browser
  draws a table of class names and reports which key was pressed."
  (:require [babashka.nrepl.server :as nrepl]
            [buzz.core :refer [client defpart defui server server!]]
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

;; `pid` is a per-connection atom, so each browser is one player. It starts
;; empty: a tab that is open but has not joined is a spectator.
(defn- join-here! [pid nm]
  (when-not @pid
    (reset! pid (game/join! nm))))

(defn- leave-here! [pid]
  (game/leave! @pid)
  (reset! pid nil))

;; A tab that goes away has to take its snake with it, and the connection knows
;; before the game does. Buzz drops the session from `conns` on close, so the
;; sessions that disappeared are the players that left.
(defonce leave-watch
  (add-watch buzz/conns ::leave
             (fn [_ _ old new]
               (doseq [[session conn] old
                       :when (not (contains? new session))
                       mount (:mounted conn)
                       :let [pid (:pid (:state mount))]
                       :when (and pid @pid)]
                 (game/leave! @pid)))))

(defpart score-row [p]
  [:li {:key (:id p) :class (str "p" (:color p) (when-not (:alive p) " out"))}
   [:span.swatch]
   [:span.who (:name p)]
   [:span.len (:len p)]
   [:span.pts (:score p)]])

(defui board [pid]
  (let [rows   (server (game/rows @game/state))
        me     (server (game/me @game/state @pid))
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
                        (server! (when @pid (game/turn! @pid (client k))))))}
      (for [row rows]
        [:div.row (for [c row] [:div {:class (str "cell " c)}])])]
     [:div.side
      (if me
        [:div.you {:class (str "p" (:color me))}
         [:p.mine (:name me) [:span.pts (:score me)]]
         [:p.hint (if (:alive me) "arrows or wasd" "respawning…")]
         [:button.leave {:on-click (fn [_] (server! (leave-here! pid)))} "leave"]]
        [:div.join
         [:p "pick a name and join. everyone plays on the same board."]
         ;; The join button reads this box by class, so nothing else may
         ;; answer to `.name`.
         [:input.name
          {:placeholder "name"
           :autofocus true
           :on-key-down (fn [e]
                          (when (= "Enter" (.-key e))
                            (server! (join-here! pid (client (.. e -target -value))))
                            (.focus (js/document.querySelector ".board"))))}]
         [:button.go
          {:on-click (fn [_]
                       (server! (join-here! pid (client (.-value (js/document.querySelector ".name")))))
                       (.focus (js/document.querySelector ".board")))}
          "join"]])
      [:ul.scores (for [p scores] (score-row p))]]]))

(def ui
  (buzz/handler {:index "public/index.html"
                 :watch [game/state]
                 :mounts [{:el "app"
                           :state (fn [] {:pid (atom nil)})
                           :component (fn [{:keys [pid]}] (board pid))}]}))

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
