(ns snake.game
  "The game, and nothing about the web. One atom holds the world. A tick reads
  it and writes the next one, so a browser only ever sees a whole world, never
  half of one."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def width 32)
(def height 22)
(def tick-ms 120)
(def start-len 4)
(def respawn-ticks 16)
(def palette 8)

;; A snake nobody steers never dies, because the board wraps. It eats its way
;; to the width of the board and stays there. So a player who has not touched a
;; key for this long is dropped, and the snake becomes food.
(def idle-ticks (int (/ 30000 tick-ms)))
(def warn-ticks (int (/ 10000 tick-ms)))

;; A player is a snake plus a name and a score. The body is a vector of cells,
;; head first. A dead player keeps its row so its score survives the wait.
(defonce state (atom {:players {} :food #{} :next-id 0 :tick 0}))

(def ^:private dirs [[1 0] [-1 0] [0 1] [0 -1]])

(def ^:private key->dir
  {"ArrowUp" [0 -1] "ArrowDown" [0 1] "ArrowLeft" [-1 0] "ArrowRight" [1 0]
   "w" [0 -1] "s" [0 1] "a" [-1 0] "d" [1 0]
   "W" [0 -1] "S" [0 1] "A" [-1 0] "D" [1 0]
   "k" [0 -1] "j" [0 1] "h" [-1 0] "l" [1 0]})

;; The board wraps. Walls would make a crowded board a queue for the same two
;; corners; wrapping leaves every death to another snake, or to your own.
(defn- ahead [[x y] [dx dy]]
  [(mod (+ x dx) width) (mod (+ y dy) height)])

(defn- alive-players [g] (filter :alive (vals (:players g))))

(defn- taken [g]
  (into (set (:food g)) (mapcat :body) (alive-players g)))

(defn- free-cell [g]
  (let [occupied (taken g)]
    (first (remove occupied
                   (repeatedly 200 (fn [] [(rand-int width) (rand-int height)]))))))

;; A new snake starts as four cells in one place and unstacks as it moves, so a
;; spawn needs one free cell rather than a free run of them.
(defn- spawn [g p]
  (if-let [c (free-cell g)]
    (assoc p :alive true :body (vec (repeat start-len c))
           :dir (rand-nth dirs) :queued nil :dead 0)
    p))

(defn- by-id [ps] (into {} (map (juxt :id identity)) ps))

(defn- food-target [g] (max 3 (inc (count (alive-players g)))))

(defn- top-up-food [g]
  (loop [g g]
    (if (< (count (:food g)) (food-target g))
      (if-let [c (free-cell g)]
        (recur (update g :food conj c))
        g)
      g)))

(defn idle-for [g p] (- (:tick g) (:active p 0)))

;; Dropping the player rather than killing the snake, because a kill would
;; respawn it and leave the same snake wandering a minute later.
(defn- drop-idle [g]
  (let [gone (filter #(> (idle-for g %) idle-ticks) (vals (:players g)))]
    (if (seq gone)
      (let [left (remove (set gone) (vals (:players g)))
            food (-> (into #{} (comp (mapcat :body) (take-nth 2)) gone)
                     (set/difference (into #{} (mapcat :body) left)))]
        (-> g
            (assoc :players (by-id left))
            (update :food into food)))
      g)))

;; Respawns go one at a time, because each has to see where the last one landed.
(defn- respawn-all [g]
  (reduce (fn [g p]
            (if (:respawn p)
              (assoc-in g [:players (:id p)] (dissoc (spawn g p) :respawn))
              g))
          g
          (vals (:players g))))

(defn step
  "One tick. Every alive snake moves at once, so a head-on meeting kills both
  rather than whichever of them the map happened to hold first."
  [g]
  (let [alive   (alive-players g)
        bodies  (into #{} (mapcat :body) alive)
        moved   (mapv (fn [p]
                        (let [d (or (:queued p) (:dir p))]
                          (assoc p :dir d :queued nil
                                 :head (ahead (first (:body p)) d))))
                      alive)
        heads   (frequencies (map :head moved))
        judged  (mapv (fn [p]
                        (assoc p :dies (boolean (or (bodies (:head p))
                                                    (> (heads (:head p)) 1)))))
                      moved)
        eaten   (into #{} (comp (remove :dies) (map :head) (filter (:food g))) judged)
        living  (mapv (fn [p]
                        (let [ate  (contains? eaten (:head p))
                              body (into [(:head p)] (if ate (:body p) (butlast (:body p))))]
                          (cond-> (-> p (assoc :body body) (dissoc :head :dies))
                            ate (update :score inc))))
                      (remove :dies judged))
        ;; Half of what a snake was becomes food. All of it would make dying
        ;; pay, none of it would leave the board emptier after every collision.
        corpses (-> (into #{} (comp (mapcat :body) (take-nth 2)) (filter :dies judged))
                    (set/difference (into #{} (mapcat :body) living)))
        dead    (mapv (fn [p] (-> p (assoc :alive false :dead respawn-ticks :body [])
                                  (dissoc :head :dies)))
                      (filter :dies judged))
        ;; Players who were already waiting when the tick began.
        waiting (mapv (fn [p]
                        (let [n (dec (:dead p 0))]
                          (if (pos? n) (assoc p :dead n) (assoc p :dead 0 :respawn true))))
                      (remove :alive (vals (:players g))))]
    (-> g
        (update :tick inc)
        (assoc :players (by-id (concat living dead waiting)))
        (update :food #(into (set/difference % eaten) corpses))
        drop-idle
        respawn-all
        top-up-food)))

(defn- clean-name [nm]
  (let [nm (or (some-> nm str/trim not-empty) "anon")]
    (subs nm 0 (min 12 (count nm)))))

(defn join!
  "Adds a player and returns its id. It spawns on the next tick, so joining
  never drops a snake into a cell the current tick is about to use."
  [nm]
  (let [g (swap! state (fn [g]
                         (let [id (:next-id g)]
                           (-> g
                               (assoc :next-id (inc id))
                               (assoc-in [:players id]
                                         {:id id :name (clean-name nm) :score 0
                                          :color (mod id palette) :alive false
                                          :dead 1 :body [] :dir [1 0]
                                          :active (:tick g)})))))]
    (dec (:next-id g))))

(defn leave! [id]
  (when id (swap! state update :players dissoc id)))

(defn turn!
  "A key from a browser. Compared against the queued direction rather than the
  moving one, so two keys in one tick cannot fold a snake back on itself. A key
  that changes nothing still says someone is there."
  [id k]
  (when-let [[dx dy] (key->dir k)]
    (swap! state
           (fn [g]
             (if-let [p (get-in g [:players id])]
               (let [[cx cy] (or (:queued p) (:dir p))
                     turn?   (and (:alive p) (not (and (= dx (- cx)) (= dy (- cy)))))]
                 (assoc-in g [:players id]
                           (cond-> (assoc p :active (:tick g))
                             turn? (assoc :queued [dx dy]))))
               g)))))

;; What a browser is sent. A cell is a class name, so the page needs no styles
;; of its own and the patch is a table of short strings.

(defn- cell-classes [g]
  (let [alive (alive-players g)]
    (persistent!
     (as-> (transient {}) m
       (reduce #(assoc! %1 %2 "f") m (:food g))
       (reduce (fn [m p]
                 (let [c (str "s p" (:color p))]
                   (reduce #(assoc! %1 %2 c) m (:body p))))
               m alive)
       (reduce (fn [m p] (assoc! m (first (:body p)) (str "h p" (:color p)))) m alive)))))

(defn rows [g]
  (let [m (cell-classes g)]
    (mapv (fn [y] (mapv (fn [x] (get m [x y] "")) (range width)))
          (range height))))

(defn scoreboard [g]
  (->> (vals (:players g))
       (sort-by (juxt (comp - :score) :id))
       (mapv (fn [p] {:id (:id p) :name (:name p) :score (:score p)
                      :color (:color p) :alive (:alive p) :len (count (:body p))}))))

(defn me [g id]
  (when-let [p (and id (get (:players g) id))]
    (let [left (- idle-ticks (idle-for g p))]
      (cond-> {:name (:name p) :score (:score p) :alive (:alive p) :color (:color p)}
        ;; Being dropped is confusing without a countdown first.
        (<= left warn-ticks)
        (assoc :idle-in (max 0 (int (Math/ceil (/ (* left tick-ms) 1000.0)))))))))
