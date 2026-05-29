# Target examples (elm-lang.org/examples)

The goal: every example on <https://elm-lang.org/examples> works across the applicable
backends. Slugs are the path under `/examples/<slug>`. The "Needs" column tracks the
runtime capability each example requires, which drives implementation order.

Status: ✅ = working & tested headlessly (interpreter/bytecode), ⏳ = not yet.

| # | Category | Title | Slug | Status | Needs |
|---|----------|-------|------|--------|-------|
| 1 | HTML | Hello | `hello` | ✅ | Html.text only (static) |
| 2 | HTML | Groceries | `groceries` | ✅ | Html elements (static) |
| 3 | HTML | Shapes | `shapes` | ✅ | Svg (static) |
| 4 | User Input | Buttons | `buttons` | ✅ | Browser.sandbox, onClick, update |
| 5 | User Input | Text Fields | `text-fields` | ✅ | Browser.sandbox, onInput, String fns |
| 6 | User Input | Forms | `forms` | ✅ | Browser.sandbox, multiple fields, styles |
| 7 | Random | Numbers | `numbers` | ✅ | Browser.element, Cmd, Random |
| 8 | Random | Cards | `cards` | ✅ | Random, custom types |
| 9 | Random | Positions | `positions` | ✅ | Random.map2, Svg |
| 10 | HTTP | Book | `book` | ✅ | Browser.element, Http.get, text (stubbed response) |
| 11 | HTTP | Quotes | `quotes` | ✅ | Http, Json.Decode (map4/field/int/string) |
| 12 | Time | Time | `time` | ✅ | Browser.element, Time, subscriptions, Task |
| 13 | Time | Clock | `clock` | ✅ | Time, Svg, subscriptions |
| 14 | Files | Upload | `upload` | File, File.Select, Http |
| 15 | Files | Drag-and-Drop | `drag-and-drop` | File, custom events, Json.Decode |
| 16 | Files | Image Previews | `image-previews` | File, File.toUrl, tasks |
| 17 | WebGL | Triangle | `triangle` | elm-explorations/webgl |
| 18 | WebGL | Cube | `cube` | webgl, matrices |
| 19 | WebGL | Crate | `crate` | webgl, textures |
| 20 | WebGL | Thwomp | `thwomp` | webgl, mouse |
| 21 | WebGL | First Person | `first-person` | webgl, keyboard |
| 22 | Playground | Picture | `picture` | ✅ | real evancz/elm-playground loaded via module system, rendered to SVG |
| 23 | Playground | Animation | `animation` | elm-playground, animate |
| 24 | Playground | Mouse | `mouse` | elm-playground, mouse |
| 25 | Playground | Keyboard | `keyboard` | elm-playground, keyboard |
| 26 | Playground | Turtle | `turtle` | elm-playground |
| 27 | Playground | Mario | `mario` | elm-playground, sprites |

## Backends

1. **JIT interpreter** — Truffle language (`TruffleLanguage`, self-specializing AST nodes).
   On GraalVM the Graal compiler partial-evaluates hot nodes, giving a real JIT.
2. **Compiler to JavaScript** — textual JS codegen plus a small kernel runtime, mirroring
   the approach of the official Elm compiler. This is the path that can realistically run
   the browser/WebGL/Playground examples.
3. **Compiler to bytecode** — Truffle Bytecode DSL interpreter (GraalVM bytecode generator).

## Notes on feasibility

- Examples 1–16 are achievable headless: pure rendering and The Elm Architecture (TEA)
  with Cmd/Sub/Http/Time/File effects, verified by driving update/view and inspecting the
  resulting virtual DOM (interpreter/bytecode) or generated JS run under Node with a DOM shim.
- Examples 17–21 (WebGL) and 22–27 (Playground) depend on external Elm packages
  (`elm-explorations/webgl`, `evancz/elm-playground`) and a real rendering surface; these are
  tackled last and primarily via the JS backend.
