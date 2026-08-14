# Datastar next to Buzz

Two servers for the same game. `snake.game` is unchanged and holds one world in
one atom, so both can run in one process over the same snakes.

    bb serve        # buzz, http://localhost:1350
    bb ds           # datastar, http://localhost:1351
    bb ds-diff      # datastar, patching only rows that changed
    bb ds-cells     # datastar, patching only cells that changed
    bb bench 6 20   # both servers, one world, bytes and frame cost

## What crosses the wire

Both use one SSE stream per browser and plain POSTs for input. What differs is
the frame.

| | Buzz | Datastar |
| --- | --- | --- |
| stream | `GET /events` | `GET /updates` |
| frame | `["patch" id vals]`, JSON values | `datastar-patch-elements`, HTML |
| rendering | browser, from values | server, hiccup |
| client code | components compiled by squint, imported as a module | one script tag, no build |
| input | `server!` closure over `POST /rpc` | `@post('/key')` with every signal attached |
| tab state | per connection, `(atom nil)` in the mount | tab id minted on stream open, patched into a signal, returned with each request |

## Bytes

Six bots, 20 seconds a phase, one measuring client on each server, 120 ms tick.
Buzz runs in every phase as the control. Numbers are bytes a tick.

| datastar patches | buzz | datastar |
| --- | --- | --- |
| whole board | 2437 | 16997 |
| changed rows | 2435 | 6674 |
| changed cells | 2468 | 1052 |

A cell is a `div` with a class, so a board is 17 KB of HTML and a row is about
950 bytes. Naming every cell with an id costs ten bytes a cell on the first
paint and turns a tick into about a kilobyte.

Buzz sends 2.4 KB a tick whatever moves, because a mount sends all of its slot
values when any one of them differs, and the board is one slot holding 704
strings. A per-slot or per-cell patch would cut it the same way.

Neither number is compressed. HTML gzips better than the JSON does, so a proxy
that compresses the stream narrows the first two rows.

## Compressed

Nothing above is compressed. Compressing changes the answer, so it is worth
doing before changing either server.

    bb capture                  # both streams, three modes, into streams/
    clojure -M:squeeze streams

The capture is replayed through one encoder a connection, flushed at the end of
every frame. That is what a server has to do to keep a stream a stream, and it
is also what makes the numbers small: a frame is coded against the frames before
it, and two ticks of a snake board are nearly the same bytes.

Bytes a tick, same six bots.

| | | raw | gzip | br 5 | br 11 |
| --- | --- | --- | --- | --- | --- |
| whole board | buzz | 2541 | 79 | 69 | 56 |
| | ds | 17494 | 246 | 96 | 92 |
| changed rows | buzz | 2488 | 83 | 89 | 58 |
| | ds | 7447 | 154 | 98 | 93 |
| changed cells | buzz | 2432 | 80 | 89 | 50 |
| | ds | 1008 | 73 | 64 | 58 |

A seven times difference on the wire becomes about forty bytes a tick. Both
servers spend most of their bytes repeating themselves, and any of these
encoders takes that away.

What it costs, in milliseconds a frame a connection:

| | gzip | br 5 | br 11 |
| --- | --- | --- | --- |
| buzz | 0.016 to 0.027 | 0.014 to 0.029 | 1.5 to 1.8 |
| ds | 0.007 to 0.050 | 0.009 to 0.021 | 0.5 to 2.2 |

Quality 5 is free at this frame rate. Quality 11 is 2 ms a frame a connection,
which at eight frames a second is a core for every sixty browsers, and it buys
about thirty bytes a tick.

This is paid once a connection, unlike rendering, which is paid once a tick for
everyone. Encoder memory is per connection too, and window 22 is not small.
Neither was measured.

Browsers do take it. `snake.proxy` puts one brotli encoder in front of either
server, and both play through it:

    bb ds-cells
    clojure -M:proxy 1351          # http://localhost:1360

Chrome decodes frame by frame with `Content-Encoding: br` on `text/event-stream`,
through `fetch` for Datastar and through `EventSource` for Buzz. Measured
through the proxy with one player, a stream of 576 bytes a tick went out as 22.

Proxies are the catch. Compression of `text/event-stream` is off by default in
nginx, and buffering has to be off with it or frames arrive in batches.

## Frame cost

    buzz values    0.09 ms a frame
    ds html        1.17 ms a frame

Rendering the board as HTML with hiccup costs about 13 times what encoding the
same board as JSON costs. Both run once a tick for all watchers, so at 8 frames
a second this is 1% of a core against 0.07%.

## What the code has to say

Identity. Buzz hands a component per-connection state, and one atom in it is the
player id. Datastar has no per-connection anything, so the id is minted when the
stream opens, patched into the browser as a signal, and comes back on every
request the browser makes.

Keys. Buzz puts the handler on the board div, gives it a tabindex, and focuses
it from a mount hook. Datastar listens on the window and skips events whose
target is an input, so the name box stays typable.

The join box. The side panel is rewritten by the server whenever a score moves.
`data-bind:nm` is what keeps a half-typed name, because the signal holds it and
Datastar puts it back after a morph.

First paint. A row or cell patch needs the row or cell to exist, so the first
frame on a stream is always the whole board.

Attribute syntax. Datastar v1.0.2 splits a plugin from its key on a colon:
`data-on:click`, `data-bind:nm`, `data-on:keydown__window`. `data-on-click`
parses as a plugin named `on-click`, which does not exist, and is dropped
without a word in the console. The attributes page on data-star.dev still shows
the dashed form.

## Size

95 lines of code for the Buzz server, 161 for the Datastar one. About 20 of
those are the two hand-written diff modes.
The Datastar version needs hiccup to render with. The Buzz version needs nothing
extra, because its components are the page.

## Where each one is ahead

Buzz writes less code and needs no decision about what to put on the wire. Local
browser state stays in the browser, and a handler is a Clojure function that
closes over its arguments.

Datastar loads one script and needs no compile step for the client. Nothing
per-connection is built on the server. What a tick costs is decided in the push
function, and at cell granularity it costs less than Buzz does.

Uncompressed, that last point is worth 2.4 KB a tick a browser. Compressed at
quality 5, it is worth about twenty bytes, and a mount that patched only the
slots that changed would be worth less than that.
