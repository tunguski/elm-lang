module Editor exposing (program, Model, Msg)

{-| A reusable, embeddable code playground: configure it with a list of example **URLs**, which it
fetches over HTTP at startup and presents as editable files (alongside a built-in starter so it is
never empty). It renders an editable file browser plus the live result of the selected file's
`main`. The interpreter (Lang/Lexer/Parser/Eval) does the work; this module is the UI plus the
loading and the wiring of interpreted click handlers back through a Browser.sandbox-style `update`.

Each file is an independent program: the editor always evaluates and renders the **`main`** of the
selected file — a static `Html` value, a `Browser.sandbox`/`Browser.element` app (rendered live and
interactive), or a plain value (shown as text). There is no entry-expression box and no choosing
other functions, by design. Reuse it elsewhere with `Editor.program myExampleUrls`.
-}

import Browser
import Browser.Events
import Browser.Navigation
import Eval exposing (appInit, appInitCmd, appSubscription, appUpdate, appUpdateCmd, appView, applyHandler, applyMsgIn, fileSelectCmd, fileSelected, gameInitMem, gameStep, gameView, hasApp, httpCmd, httpResult, lookup, mainValue, randomCmd, renderValue, taskResult)
import File
import Json.Decode as Decode
import Set exposing (Set)
import Html exposing (Html, button, div, input, li, node, pre, span, text, textarea, ul)
import Html.Attributes exposing (placeholder, style, title, value)
import Html.Events exposing (onClick, onInput, onMouseDown, on)
import Highlight
import Assist
import Share
import Http
import Lang exposing (Value(..))
import Time
import WebGL


type alias Model =
    { files : List ( String, String )
    , selected : String
    , app : Result String Value
    , newName : String
    , seed : Int
    , gameMem : Maybe Value
    , gameKeys : Set String
    , gameTime : Float
    , history : List Value -- successive app models (time-travel debugger)
    , msgLog : List Value -- the message that produced each model transition (msgLog[k] -> history[k+1])
    , historyAt : Int -- the index currently shown (last = live)
    , caret : Int -- the textarea's caret offset (for autocomplete)
    , completions : List String -- live autocomplete candidates for the word at the caret
    , shareText : String -- the encoded share string (shown to copy, or pasted in to restore)
    , restored : Bool -- True once a permalink (#hash) session has replaced the defaults
    }


type Msg
    = SelectFile String
    | EditAt String Int
    | AcceptCompletion String
    | DismissCompletions
    | SetNewName String
    | AddFile
    | OpenFile
    | OpenedFile String String
    | RemoveFile String
    | Interp Value
    | Rewind Int
    | Tick Int
    | KeyDown String
    | KeyUp String
    | Frame Float
    | HttpResult Value (Result Http.Error String)
    | Loaded String (Result Http.Error String)
    | FilePicked Value String String
    | Share
    | ShareInput String
    | Restore
    | GotHash String
    | NoOp


{-| A built-in starter file so the editor is usable immediately (and offline / before fetches). -}
starter : ( String, String )
starter =
    ( "Buttons.elm"
    , "module Main exposing (main)\n\nimport Browser\nimport Html exposing (button, div, text)\nimport Html.Events exposing (onClick)\n\nmain = Browser.sandbox { init = init, update = update, view = view }\n\ninit = 0\n\nupdate msg model =\n    case msg of\n        Increment ->\n            model + 1\n\n        Decrement ->\n            model - 1\n\nview model =\n    div []\n        [ button [ onClick Decrement ] [ text \"-\" ]\n        , div [] [ text (String.fromInt model) ]\n        , button [ onClick Increment ] [ text \"+\" ]\n        ]\n"
    )


{-| Builds an editor that fetches each example URL at startup and lets the user edit them. -}
program : List String -> Program () Model Msg
program urls =
    Browser.element
        { init = \_ -> ( initModel, Cmd.batch [ Browser.Navigation.getHash GotHash, fetchAll urls ] )
        , update = update
        , view = view
        , subscriptions = subscriptions
        }


{-| Wires live effects: a `game`'s keyboard + animation-frame loop, or a `Time.every` tick. -}
subscriptions : Model -> Sub Msg
subscriptions model =
    case model.gameMem of
        Just _ ->
            Sub.batch
                [ Browser.Events.onKeyDown (Decode.map KeyDown (Decode.field "key" Decode.string))
                , Browser.Events.onKeyUp (Decode.map KeyUp (Decode.field "key" Decode.string))
                , Browser.Events.onAnimationFrameDelta Frame
                ]

        Nothing ->
            case model.app of
                Ok m ->
                    case appSubscription (selectedFile model) m of
                        Just ( interval, _ ) ->
                            Time.every (toFloat interval) (\posix -> Tick (Time.posixToMillis posix))

                        Nothing ->
                            Sub.none

                Err _ ->
                    Sub.none


fetchAll : List String -> Cmd Msg
fetchAll urls =
    Cmd.batch (List.map (\url -> Http.get { url = url, expect = Http.expectString (Loaded url) }) urls)


initModel : Model
initModel =
    refreshApp
        { files = [ starter ]
        , selected = Tuple.first starter
        , app = Err ""
        , newName = ""
        , seed = 1
        , gameMem = Nothing
        , gameKeys = Set.empty
        , gameTime = 0
        , history = []
        , msgLog = []
        , historyAt = 0
        , caret = 0
        , completions = []
        , shareText = ""
        , restored = False
        }


{-| The file name from a URL ({@code examples/Foo.elm} -> {@code Foo.elm}). -}
baseName : String -> String
baseName url =
    url |> String.split "/" |> List.reverse |> List.head |> Maybe.withDefault url


selectedFile : Model -> List ( String, String )
selectedFile model =
    [ ( model.selected, lookup model.selected model.files |> Maybe.withDefault "" ) ]


{-| Re-initialises the running app from the selected file (its model becomes `init`) when that file
is a Browser.sandbox-style program; otherwise the app slot is unused. -}
refreshApp : Model -> Model
refreshApp model =
    let
        app =
            if hasApp (selectedFile model) then
                appInit (selectedFile model)

            else
                Err ""
    in
    { model
        | app = app
        , gameMem = gameInitMem (selectedFile model)
        , gameKeys = Set.empty
        , gameTime = 0
        , history = app |> Result.map (\m -> [ m ]) |> Result.withDefault []
        , msgLog = []
        , historyAt = 0
    }


{-| Records the app's next model (and the message that produced it) in the time-travel history
(capped) and jumps the cursor to the new state. -}
recordModel : Model -> Value -> Value -> Model
recordModel model msg m =
    let
        hist =
            List.take 200 (model.history ++ [ m ])

        log =
            List.take 199 (model.msgLog ++ [ msg ])
    in
    { model | app = Ok m, history = hist, msgLog = log, historyAt = List.length hist - 1 }


{-| The app model currently shown — the one the history cursor points at (live = the last). -}
shownModel : Model -> Result String Value
shownModel model =
    case nth model.historyAt model.history of
        Just m ->
            Ok m

        Nothing ->
            model.app


nth : Int -> List a -> Maybe a
nth i xs =
    List.head (List.drop i xs)


{-| Runs one interpreted message through `update`, then handles the command it produces:
`Random.generate` is sampled with the editor's seed and the generated message dispatched (so
`Roll`-style buttons randomise); `Http.get` issues a real request whose response is fed back. `fuel`
bounds the command-chasing in case an app loops. -}
stepApp : Int -> Model -> Value -> ( Model, Cmd Msg )
stepApp fuel model interpMsg =
    case shownModel model of
        Ok m ->
            -- Continue from whatever state is shown; if rewound, drop the (now-superseded) future.
            let
                truncated =
                    { model
                        | history = List.take (model.historyAt + 1) model.history
                        , msgLog = List.take model.historyAt model.msgLog
                    }
            in
            case appUpdateCmd (selectedFile model) interpMsg m of
                Ok ( m2, cmd ) ->
                    runCmd fuel (recordModel truncated interpMsg m2) cmd

                Err e ->
                    ( { model | app = Err e }, Cmd.none )

        Err _ ->
            ( model, Cmd.none )


{-| Handles an interpreted command: a `Random.generate` is resolved synchronously and its message
re-dispatched; an `Http.get` becomes a real request (its response comes back as `HttpResult`);
anything else is inert. -}
runCmd : Int -> Model -> Value -> ( Model, Cmd Msg )
runCmd fuel model cmd =
    case randomCmd (selectedFile model) model.seed cmd of
        Just ( genMsg, seed2 ) ->
            if fuel <= 0 then
                ( { model | seed = seed2 }, Cmd.none )

            else
                stepApp (fuel - 1) { model | seed = seed2 } genMsg

        Nothing ->
            case httpCmd cmd of
                Just ( url, toMsg ) ->
                    ( model
                    , Http.get { url = url, expect = Http.expectString (HttpResult toMsg) }
                    )

                Nothing ->
                    case taskResult (selectedFile model) cmd of
                        Just (Ok stepMsg) ->
                            if fuel <= 0 then
                                ( model, Cmd.none )

                            else
                                stepApp (fuel - 1) model stepMsg

                        Just (Err _) ->
                            ( model, Cmd.none )

                        Nothing ->
                            case fileSelectCmd cmd of
                                Just toMsg ->
                                    -- Open a real browser file picker; the choice returns via FilePicked.
                                    ( model, File.openPicker (\name content -> FilePicked toMsg name content) )

                                Nothing ->
                                    ( model, Cmd.none )


{-| Refreshes the running app/game from the selected file and issues its `init` command (so a
`Browser.element` that fetches on startup — like the book example — actually loads). -}
refreshAndRun : Model -> ( Model, Cmd Msg )
refreshAndRun model =
    let
        m =
            refreshApp model
    in
    case appInitCmd (selectedFile m) of
        Ok ( _, cmd ) ->
            runCmd 100 m cmd

        Err _ ->
            ( m, Cmd.none )


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        SelectFile name ->
            refreshAndRun { model | selected = name }

        EditAt content caret ->
            -- Recompute autocomplete candidates for the word the caret sits in, then re-run the file.
            let
                word =
                    Assist.wordAt content caret
            in
            refreshAndRun
                { model
                    | files = setFile model.selected content model.files
                    , caret = caret
                    , completions = Assist.completions content word
                }

        AcceptCompletion choice ->
            -- Replace the half-typed word with the chosen completion.
            let
                source =
                    lookup model.selected model.files |> Maybe.withDefault ""

                ( newSource, newCaret ) =
                    Assist.accept source model.caret choice
            in
            refreshAndRun
                { model
                    | files = setFile model.selected newSource model.files
                    , caret = newCaret
                    , completions = []
                }

        DismissCompletions ->
            ( { model | completions = [] }, Cmd.none )

        Share ->
            -- Encode the session into a string to copy, and into the URL fragment for a permalink.
            let
                encoded =
                    Share.encodeFiles model.files
            in
            ( { model | shareText = encoded }, Browser.Navigation.setHash encoded )

        ShareInput text ->
            ( { model | shareText = text }, Cmd.none )

        GotHash hash ->
            -- A permalink (#hash) opened the editor: restore that session in place of the defaults.
            case Share.decodeFiles hash of
                [] ->
                    ( model, Cmd.none )

                files ->
                    refreshAndRun
                        { model | files = files, selected = Tuple.first (firstFile files), restored = True }

        Restore ->
            -- Replace the session with the files decoded from the pasted share string.
            case Share.decodeFiles model.shareText of
                [] ->
                    ( model, Cmd.none )

                files ->
                    refreshAndRun
                        { model | files = files, selected = Tuple.first (firstFile files) }

        SetNewName n ->
            ( { model | newName = n }, Cmd.none )

        AddFile ->
            let
                name =
                    if String.endsWith ".elm" model.newName then
                        model.newName

                    else
                        model.newName ++ ".elm"
            in
            if model.newName == "" || hasFile name model.files then
                ( model, Cmd.none )

            else
                refreshAndRun { model | files = model.files ++ [ ( name, "main = text \"new file\"" ) ], selected = name, newName = "" }

        OpenFile ->
            -- Open a local .elm from disk via the browser file picker; its contents arrive as OpenedFile.
            ( model, File.openPicker (\name content -> OpenedFile name content) )

        OpenedFile name content ->
            let
                unique =
                    if hasFile name model.files then
                        "imported-" ++ name

                    else
                        name
            in
            refreshAndRun { model | files = model.files ++ [ ( unique, content ) ], selected = unique }

        RemoveFile name ->
            let
                remaining =
                    List.filter (\f -> Tuple.first f /= name) model.files
            in
            refreshAndRun
                { model
                    | files = remaining
                    , selected =
                        if model.selected == name then
                            remaining |> List.head |> Maybe.map Tuple.first |> Maybe.withDefault ""

                        else
                            model.selected
                }

        Interp interpMsg ->
            stepApp 100 model interpMsg

        Rewind i ->
            ( { model | historyAt = clamp 0 (List.length model.history - 1) i }, Cmd.none )

        Tick t ->
            -- A Time.every tick: feed the subscription's message (toMsg (millisToPosix t)) to update.
            case model.app of
                Ok m ->
                    case appSubscription (selectedFile model) m of
                        Just ( _, toMsg ) ->
                            case applyMsgIn (selectedFile model) toMsg (VNum (toFloat t)) of
                                Ok interpMsg ->
                                    stepApp 100 model interpMsg

                                Err _ ->
                                    ( model, Cmd.none )

                        Nothing ->
                            ( model, Cmd.none )

                Err _ ->
                    ( model, Cmd.none )

        HttpResult toMsg result ->
            -- A real HTTP request finished: build the interpreted message and feed it to `update`.
            case httpResult (selectedFile model) toMsg (Result.toMaybe result) of
                Ok interpMsg ->
                    stepApp 100 model interpMsg

                Err _ ->
                    ( model, Cmd.none )

        FilePicked toMsg name content ->
            -- The user chose a file: apply the program's File handler to it and step the app.
            case fileSelected (selectedFile model) toMsg name content of
                Ok interpMsg ->
                    stepApp 100 model interpMsg

                Err _ ->
                    ( model, Cmd.none )

        KeyDown key ->
            ( { model | gameKeys = Set.insert key model.gameKeys }, Cmd.none )

        KeyUp key ->
            ( { model | gameKeys = Set.remove key model.gameKeys }, Cmd.none )

        Frame dt ->
            -- Advance the game one animation frame: `update computer memory` with the held keys.
            case model.gameMem of
                Just mem ->
                    let
                        time =
                            model.gameTime + dt
                    in
                    case gameStep (selectedFile model) (Set.toList model.gameKeys) time mem of
                        Ok mem2 ->
                            ( { model | gameMem = Just mem2, gameTime = time }, Cmd.none )

                        Err _ ->
                            ( { model | gameTime = time }, Cmd.none )

                Nothing ->
                    ( model, Cmd.none )

        Loaded url result ->
            if model.restored then
                -- A permalink session is active; ignore the default examples still arriving over HTTP.
                ( model, Cmd.none )

            else
                case result of
                    Ok content ->
                        -- Add (or refresh) the fetched example as an editable file.
                        let
                            name =
                                baseName url

                            files =
                                if hasFile name model.files then
                                    setFile name content model.files

                                else
                                    model.files ++ [ ( name, content ) ]
                        in
                        ( refreshApp { model | files = files }, Cmd.none )

                    Err _ ->
                        ( model, Cmd.none )

        NoOp ->
            ( model, Cmd.none )


{-| The first file of a session (a safe fallback when a restored session is somehow empty). -}
firstFile : List ( String, String ) -> ( String, String )
firstFile files =
    case files of
        f :: _ ->
            f

        [] ->
            ( "Main.elm", "" )


setFile : String -> String -> List ( String, String ) -> List ( String, String )
setFile name content files =
    List.map
        (\f ->
            if Tuple.first f == name then
                ( name, content )

            else
                f
        )
        files


hasFile : String -> List ( String, String ) -> Bool
hasFile name files =
    List.any (\f -> Tuple.first f == name) files



-- VIEW


{-| A share/restore bar: "Share" encodes the whole session into the text box (copy it to share);
pasting a shared string and pressing "Restore" replaces the session with it. Pure (no ports). -}
shareBar : Model -> Html Msg
shareBar model =
    div [ style "margin-left" "auto", style "display" "flex", style "gap" "6px", style "align-items" "center" ]
        [ button [ onClick Share, style "font-size" "12px" ] [ text "Share" ]
        , input
            [ value model.shareText
            , onInput ShareInput
            , placeholder "paste a shared session…"
            , style "width" "180px"
            , style "font-size" "12px"
            ]
            []
        , button [ onClick Restore, style "font-size" "12px" ] [ text "Restore" ]
        ]


view : Model -> Html Msg
view model =
    div
        [ style "font-family" "system-ui, -apple-system, Segoe UI, sans-serif"
        , style "min-height" "100vh"
        , style "background" "#eef1f4"
        , style "color" "#0f1720"
        , style "margin" "0"
        ]
        [ div
            [ style "background" "#1f2933"
            , style "color" "#e6edf3"
            , style "padding" "14px 24px"
            , style "display" "flex"
            , style "align-items" "baseline"
            , style "gap" "12px"
            ]
            [ span [ style "font-size" "20px", style "font-weight" "700" ] [ text "Elm-in-Elm playground" ]
            , span [ style "color" "#9fb3c8", style "font-size" "13px" ]
                [ text "edit a file on the left; its main runs live on the right" ]
            , shareBar model
            ]
        , div
            [ style "display" "flex"
            , style "gap" "16px"
            , style "align-items" "flex-start"
            , style "max-width" "1080px"
            , style "margin" "20px auto"
            , style "padding" "0 16px"
            ]
            [ fileSidebar model
            , div [ style "flex" "2", style "min-width" "0" ]
                [ div
                    [ style "border-radius" "10px"
                    , style "overflow" "hidden"
                    , style "box-shadow" "0 4px 14px rgba(0,0,0,0.08)"
                    ]
                    [ div
                        [ style "background" "#0f1720"
                        , style "color" "#9fb3c8"
                        , style "font-family" "monospace"
                        , style "font-size" "12px"
                        , style "padding" "8px 14px"
                        ]
                        [ text model.selected ]
                    , codeEditor model (lookup model.selected model.files |> Maybe.withDefault "")
                    ]
                , mainPane model
                ]
            ]
        ]


{-| The syntax-highlighted code editor: a transparent `<textarea>` (which owns the caret, selection
and typing) layered over a `<pre>` of coloured `<span>`s. Both share the exact same font, padding and
wrapping, so the highlighted text underneath stays aligned with what's typed (the react-simple-code-
editor technique). The `<pre>` is in normal flow and sets the height; the textarea fills it. -}
codeEditor : Model -> String -> Html Msg
codeEditor model source =
    div [ style "position" "relative" ]
        [ errorRibbon model source
        , div [ style "position" "relative", style "background" "#0f1720" ]
            [ pre
                (style "margin" "0"
                    :: style "pointer-events" "none"
                    :: style "color" (segColor "")
                    :: codeStyles
                )
                (List.map renderSegment (Highlight.segments source) ++ [ text "\n" ])
            , squiggleOverlay model source
            , textarea
                (onEdit
                    :: value source
                    :: style "position" "absolute"
                    :: style "top" "0"
                    :: style "left" "0"
                    :: style "height" "100%"
                    :: style "color" "transparent"
                    :: style "background" "transparent"
                    :: style "caret-color" "#e6edf3"
                    :: style "border" "none"
                    :: style "outline" "none"
                    :: style "resize" "none"
                    :: style "overflow" "hidden"
                    :: codeStyles
                )
                []
            , completionBar model
            ]
        ]


{-| A `<textarea>` input handler that captures both the new text and the caret offset
(`selectionStart`), so autocomplete knows the word being typed. -}
onEdit : Html.Attribute Msg
onEdit =
    on "input"
        (Decode.map2 EditAt
            (Decode.at [ "target", "value" ] Decode.string)
            (Decode.at [ "target", "selectionStart" ] Decode.int)
        )


{-| The autocomplete suggestions for the word at the caret, as a click-to-insert bar. Empty (and so
invisible) when there are no candidates. -}
completionBar : Model -> Html Msg
completionBar model =
    if List.isEmpty model.completions then
        text ""

    else
        div
            [ style "position" "absolute"
            , style "bottom" "6px"
            , style "left" "14px"
            , style "right" "14px"
            , style "display" "flex"
            , style "flex-wrap" "wrap"
            , style "gap" "6px"
            , style "background" "#1b2535"
            , style "border" "1px solid #2f3e54"
            , style "border-radius" "6px"
            , style "padding" "6px"
            ]
            (List.map completionChip (List.take 12 model.completions))


completionChip : String -> Html Msg
completionChip label =
    button
        [ onMouseDown (AcceptCompletion label)
        , style "background" "#243149"
        , style "color" "#cbd5e1"
        , style "border" "1px solid #36507a"
        , style "border-radius" "4px"
        , style "padding" "2px 8px"
        , style "font-family" "monospace"
        , style "font-size" "12px"
        , style "cursor" "pointer"
        ]
        [ text label ]


{-| When the selected file fails to evaluate, surface the error — and, if it names an identifier that
appears in the source, the line/column where it is (computed by `Assist.squiggleFor`). -}
errorRibbon : Model -> String -> Html Msg
errorRibbon model source =
    case model.app of
        Err message ->
            if message == "" then
                text ""

            else
                div
                    [ style "background" "#3a1d1d"
                    , style "color" "#ffb4b4"
                    , style "font-family" "monospace"
                    , style "font-size" "12px"
                    , style "padding" "6px 14px"
                    ]
                    [ text ("⚠ " ++ located model source ++ message) ]

        Ok _ ->
            text ""


{-| A "line N, col C: " prefix locating the offending identifier in the source, or "" if none. -}
located : Model -> String -> String
located model source =
    case squiggle model source of
        Just loc ->
            "line " ++ String.fromInt (loc.line + 1) ++ ", col " ++ String.fromInt (loc.column + 1) ++ ": "

        Nothing ->
            ""


{-| The location to squiggle: where the current error's offending identifier first appears, if any. -}
squiggle : Model -> String -> Maybe { line : Int, column : Int, length : Int }
squiggle model source =
    case model.app of
        Err message ->
            Maybe.andThen (Assist.squiggleFor source) (Assist.errorName message)

        Ok _ ->
            Nothing


{-| An overlay aligned exactly over the highlight `<pre>` (same font/padding/wrapping) that draws a
wavy red underline under the offending identifier: the text is transparent, so the syntax-highlighted
code shows through, but the underline's own colour marks the error in place. -}
squiggleOverlay : Model -> String -> Html Msg
squiggleOverlay model source =
    case squiggle model source of
        Nothing ->
            text ""

        Just loc ->
            let
                start =
                    Assist.offsetOf loc.line loc.column source

                before =
                    String.left start source

                marked =
                    String.slice start (start + loc.length) source

                after =
                    String.dropLeft (start + loc.length) source
            in
            pre
                (style "position" "absolute"
                    :: style "top" "0"
                    :: style "left" "0"
                    :: style "margin" "0"
                    :: style "pointer-events" "none"
                    :: style "color" "transparent"
                    :: codeStyles
                )
                [ text before
                , span
                    [ style "text-decoration" "underline wavy #ff5555"
                    , style "text-decoration-skip-ink" "none"
                    ]
                    [ text marked ]
                , text after
                ]


{-| The font/size/padding/wrapping shared by the highlight `<pre>` and the editing `<textarea>` so
they line up character for character. -}
codeStyles : List (Html.Attribute Msg)
codeStyles =
    [ style "font-family" "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"
    , style "font-size" "13px"
    , style "line-height" "1.5"
    , style "padding" "14px"
    , style "box-sizing" "border-box"
    , style "white-space" "pre-wrap"
    , style "word-break" "break-word"
    , style "width" "100%"
    , style "min-height" "300px"
    ]


renderSegment : ( String, String ) -> Html Msg
renderSegment ( cls, txt ) =
    span [ style "color" (segColor cls) ] [ text txt ]


{-| The colour for each highlighter class (a dark theme; `""` is the default foreground). -}
segColor : String -> String
segColor cls =
    if cls == "kw" then
        "#c792ea"

    else if cls == "type" then
        "#82aaff"

    else if cls == "num" then
        "#f78c6c"

    else if cls == "str" then
        "#c3e88d"

    else if cls == "com" then
        "#637084"

    else if cls == "op" then
        "#89ddff"

    else
        "#e6edf3"


fileSidebar : Model -> Html Msg
fileSidebar model =
    div
        [ style "flex" "1"
        , style "min-width" "200px"
        , style "max-width" "240px"
        , style "background" "#fff"
        , style "border-radius" "10px"
        , style "padding" "12px"
        , style "box-shadow" "0 4px 14px rgba(0,0,0,0.08)"
        ]
        [ div [ style "font-size" "12px", style "font-weight" "700", style "color" "#52606d", style "text-transform" "uppercase", style "letter-spacing" "0.05em", style "margin-bottom" "8px" ]
            [ text "Files" ]
        , ul [ style "list-style" "none", style "padding" "0", style "margin" "0", style "max-height" "60vh", style "overflow" "auto" ]
            (List.map (fileRow model.selected) model.files)
        , div [ style "display" "flex", style "gap" "4px", style "margin-top" "10px" ]
            [ input
                [ placeholder "New.elm"
                , value model.newName
                , onInput SetNewName
                , style "flex" "1"
                , style "min-width" "0"
                , style "padding" "6px 8px"
                , style "border" "1px solid #d0d7de"
                , style "border-radius" "6px"
                ]
                []
            , button
                [ onClick AddFile
                , style "border" "none"
                , style "background" "#3a7bd5"
                , style "color" "#fff"
                , style "border-radius" "6px"
                , style "padding" "0 12px"
                , style "cursor" "pointer"
                ]
                [ text "+" ]
            ]
        , button
            [ onClick OpenFile
            , style "margin-top" "6px"
            , style "width" "100%"
            , style "border" "1px solid #d0d7de"
            , style "background" "#f6f8fa"
            , style "color" "#24292f"
            , style "border-radius" "6px"
            , style "padding" "6px"
            , style "cursor" "pointer"
            , style "font-size" "13px"
            ]
            [ text "Open .elm…" ]
        ]


fileRow : String -> ( String, String ) -> Html Msg
fileRow selected file =
    let
        name =
            Tuple.first file

        active =
            name == selected
    in
    li [ style "display" "flex", style "align-items" "center", style "gap" "4px", style "margin" "2px 0" ]
        [ button
            [ onClick (SelectFile name)
            , style "flex" "1"
            , style "text-align" "left"
            , style "border" "none"
            , style "border-radius" "6px"
            , style "padding" "7px 10px"
            , style "font-size" "13px"
            , style "cursor" "pointer"
            , style "font-weight"
                (if active then
                    "600"

                 else
                    "400"
                )
            , style "color"
                (if active then
                    "#fff"

                 else
                    "#3e4c59"
                )
            , style "background"
                (if active then
                    "#3a7bd5"

                 else
                    "#f0f3f6"
                )
            ]
            [ text name ]
        , button
            [ onClick (RemoveFile name)
            , style "border" "none"
            , style "background" "none"
            , style "color" "#cc9a9a"
            , style "cursor" "pointer"
            , style "font-size" "16px"
            ]
            [ text "x" ]
        ]


{-| Renders the result of the selected file's `main`: a live Browser.sandbox app, a static Html
value, or a plain value as text. -}
mainPane : Model -> Html Msg
mainPane model =
    div [ style "margin-top" "14px" ]
        [ div [ style "font-size" "12px", style "font-weight" "700", style "color" "#52606d", style "text-transform" "uppercase", style "letter-spacing" "0.05em", style "margin-bottom" "6px" ]
            [ text "Result" ]
        , div
            [ style "border" "1px solid #d0d7de", style "border-radius" "10px", style "padding" "16px", style "background" "#fff", style "box-shadow" "0 4px 14px rgba(0,0,0,0.06)" ]
            [ case model.gameMem of
                Just mem ->
                    gamePane model mem

                Nothing ->
                    if hasApp (selectedFile model) then
                        liveApp model

                    else
                        staticMain (selectedFile model)
            ]
        ]


{-| Renders a running `game`'s current frame (its `view computer memory`). -}
gamePane : Model -> Value -> Html Msg
gamePane model mem =
    case gameView (selectedFile model) (Set.toList model.gameKeys) model.gameTime mem of
        Ok html ->
            renderHtml (selectedFile model) html

        Err e ->
            errorBox e


liveApp : Model -> Html Msg
liveApp model =
    case shownModel model of
        Err e ->
            errorBox e

        Ok appModel ->
            case appView (selectedFile model) appModel of
                Ok html ->
                    div [] [ debugBar model, renderHtml (selectedFile model) html, msgLogPanel model ]

                Err e ->
                    errorBox e


{-| The time-travel debugger: a scrubber over the recorded app models. Shown once a TEA app has
taken at least one step; dragging it re-renders an earlier state, and dispatching a message from
there continues history from that point. -}
debugBar : Model -> Html Msg
debugBar model =
    let
        last =
            List.length model.history - 1
    in
    if last < 1 then
        text ""

    else
        div
            [ style "display" "flex"
            , style "align-items" "center"
            , style "gap" "8px"
            , style "margin-bottom" "10px"
            , style "padding" "6px 10px"
            , style "background" "#1f2933"
            , style "border-radius" "6px"
            , style "color" "#cbd2d9"
            , style "font" "12px system-ui, sans-serif"
            ]
            [ span [ style "font-weight" "700" ] [ text "⏱ time travel" ]
            , Html.node "input"
                [ Html.Attributes.attribute "type" "range"
                , Html.Attributes.attribute "min" "0"
                , Html.Attributes.attribute "max" (String.fromInt last)
                , value (String.fromInt model.historyAt)
                , onInput (\s -> Rewind (Maybe.withDefault last (String.toInt s)))
                , style "flex" "1"
                ]
                []
            , span [] [ text ("msg " ++ String.fromInt model.historyAt ++ " / " ++ String.fromInt last) ]
            , button
                [ onClick (Rewind last)
                , style "border" "none"
                , style "border-radius" "4px"
                , style "background" (if model.historyAt == last then "#3a7bd5" else "#52606d")
                , style "color" "#fff"
                , style "cursor" "pointer"
                , style "padding" "2px 8px"
                ]
                [ text "live" ]
            ]


{-| The message log beneath the live app: one clickable chip per dispatched message (rendered with
`renderValue`), in dispatch order. Clicking a chip rewinds to the state that message produced; the
chip for the currently-shown state is highlighted. Together with the scrubber this turns the
time-travel debugger into a proper "what message led here" view. -}
msgLogPanel : Model -> Html Msg
msgLogPanel model =
    if List.isEmpty model.msgLog then
        text ""

    else
        div
            [ style "display" "flex"
            , style "flex-wrap" "wrap"
            , style "gap" "4px"
            , style "margin-top" "10px"
            , style "max-height" "120px"
            , style "overflow" "auto"
            , style "font" "11px ui-monospace, monospace"
            ]
            (span [ style "color" "#9aa5b1", style "font-weight" "700", style "margin-right" "4px" ] [ text "messages:" ]
                :: List.indexedMap (msgChip model) model.msgLog
            )


msgChip : Model -> Int -> Value -> Html Msg
msgChip model i msg =
    let
        isCurrent =
            model.historyAt == i + 1
    in
    button
        [ onClick (Rewind (i + 1))
        , title (renderValue msg)
        , style "border" "none"
        , style "border-radius" "4px"
        , style "padding" "2px 6px"
        , style "cursor" "pointer"
        , style "max-width" "200px"
        , style "overflow" "hidden"
        , style "text-overflow" "ellipsis"
        , style "white-space" "nowrap"
        , style "background"
            (if isCurrent then
                "#3a7bd5"

             else
                "#323f4b"
            )
        , style "color" "#e4e7eb"
        ]
        [ text (String.fromInt (i + 1) ++ ". " ++ renderValue msg) ]


staticMain : List ( String, String ) -> Html Msg
staticMain files =
    case mainValue files of
        Ok v ->
            renderHtml files v

        Err e ->
            errorBox e


errorBox : String -> Html Msg
errorBox e =
    pre [ style "color" "#a00", style "margin" "0", style "white-space" "pre-wrap" ] [ text ("Error: " ++ e) ]


{-| Converts an interpreted Html `Value` tree into real `Html Msg`, wiring interpreted event handlers
back to the editor as `Interp` messages; a non-Html value is shown via its rendering. {@code files}
is threaded so an `onInput` handler can be applied to the input string at event time. -}
renderHtml : List ( String, String ) -> Value -> Html Msg
renderHtml files v =
    case v of
        VCtor "Html.text" [ VStr s ] ->
            text s

        VCtor "Html.node" [ VStr tag, VList attrs, VList children ] ->
            node tag (List.filterMap (renderAttr files) attrs) (List.map (renderHtml files) children)

        VCtor "WebGL.scene" [ VList attrs, entities ] ->
            -- Render live: a real <canvas> whose entities are handed to the JS WebGL runtime by the
            -- `WebGL.glAttr` kernel bridge (which converts the interpreter's values to GL data).
            node "canvas"
                (List.filterMap (renderAttr files) attrs ++ [ WebGL.glAttr entities ])
                []

        _ ->
            text (renderValue v)


renderAttr : List ( String, String ) -> Value -> Maybe (Html.Attribute Msg)
renderAttr files v =
    case v of
        VCtor "Html.on" [ VStr "click", msg ] ->
            Just (onClick (Interp msg))

        VCtor "Html.on" [ VStr "input", handler ] ->
            -- Apply the handler to the typed text to build the message, then dispatch it.
            Just
                (onInput
                    (\s ->
                        case applyHandler files handler s of
                            Ok msg ->
                                Interp msg

                            Err _ ->
                                NoOp
                    )
                )

        VCtor "Html.style" [ VStr k, VStr val ] ->
            Just (style k val)

        VCtor "Html.attr" [ VStr k, VStr val ] ->
            Just (Html.Attributes.attribute k val)

        VCtor "Html.attr" [ VStr k, VBool b ] ->
            if b then
                Just (Html.Attributes.attribute k k)

            else
                Nothing

        VCtor "Html.attr" [ VStr k, other ] ->
            Just (Html.Attributes.attribute k (renderValue other))

        _ ->
            Nothing
