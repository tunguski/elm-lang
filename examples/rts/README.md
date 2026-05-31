# RTS Mini — a tiny real-time strategy game in Elm

A small but functional RTS (in the spirit of early WarCraft / Command & Conquer): build buildings,
train units, gather resources and uncover the whole map. There is **no enemy AI and no multiplayer** —
the objective is to explore the entire map. Written for this Elm implementation, cleanly split so the
**model**, the **logic** and the **view** live in separate modules, and shipping a **frontend** (a
browser app) alongside a **backend** (a server-side handler) that share one model module.

## Files

| Module | File | Role |
|---|---|---|
| `RTS.Model` | [Model.elm](Model.elm) | Data only: tiles, terrain, units, buildings, the `Msg` type and shared constants (map size, costs, colours). |
| `RTS.Logic` | [Logic.elm](Logic.elm) | Pure game rules: `init` (the generated map) and `update` — movement, fog-of-war reveal, gathering, training and building placement. No rendering, no effects. |
| `RTS.View` | [View.elm](View.elm) | Renders the model: the tile map (with fog), buildings and units as **SVG**, plus an HTML HUD (resources, build/train buttons, legend, messages). |
| `RTS.Main` | [Main.elm](Main.elm) | The frontend `Browser.element` program — wires `init`/`update`/`view` and a real-time clock (`Tick` 5×/second). |
| `RTS.Backend` | [Backend.elm](Backend.elm) | A server-side `handle : Request -> Response` sharing `RTS.Model`: a landing page, `/ping`, and `/api/map` (JSON world description). |

## How to play

- **Click a unit** to select it, then **click a tile** to move it there (impassable water/rock is
  refused).
- Move **workers** onto a **gold mine** or **forest** to gather gold / wood (income while standing on
  the resource).
- **Train a worker** at the base (50 gold); **build a barracks** (120 gold) then **train soldiers**
  (60 gold). Buildings and units clear the fog around them.
- **Win** by revealing every tile.

## Build & run

Compile the playable client to a single HTML page:

```sh
elm make examples/rts/Model.elm examples/rts/Logic.elm examples/rts/View.elm examples/rts/Main.elm -o rts.html
# then open rts.html in a browser
```

The backend (shares the model constants) can be served once the rts modules are on the path, and is
exercised directly in `RtsGameTest`. Its routes:

- `GET /` — an HTML landing page with the rules,
- `GET /ping` — `pong`,
- `GET /api/map` — a JSON description of the map size, costs and terrain palette.
