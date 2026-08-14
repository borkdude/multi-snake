# multi-snake

Snake for as many players as show up, written with
[Buzz](https://github.com/borkdude/buzz). Everyone plays on one board, in one
world, held in one atom on the server.

    bb serve    # http://localhost:1350
    bb dev      # the same, plus an nrepl on 1668

Open the page in two windows to see it.

## How to play

Use arrows, `wasd` or `hjkl`. This game doesn't have a wall, but you can die by running into a snake, your own included. A dead snake leaves half of itself behind as food and comes back a second later, keeping its score.
This game keeps one player per browser tab. If you close the tab, your snake dies and leaves food behind.
Without a wall an unsteered snake never dies, so 30 seconds without a key drops you and turns your snake into food. The last 10 of those seconds are counted down next to your name.

It runs at https://multi-snake.michielborkent.nl.
