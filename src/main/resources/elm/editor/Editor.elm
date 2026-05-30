module Editor exposing (program, Model, Msg)

{-| A reusable, embeddable code playground: configure it with a list of `(filename, source)` files
and it renders an editable file browser plus the live result of the selected file's `main`. The
interpreter (Lang/Lexer/Parser/Eval) does the work; this module is only the UI and the wiring of
interpreted click handlers back through a Browser.sandbox-style program's `update`.

Each file is an independent program: the editor always evaluates and renders the **`main`** of the
selected file — a static `Html` value, a `Browser.sandbox { init, update, view }` app (rendered live
and interactive), or a plain value (shown as text). There is no entry-expression box and no choosing
other functions, by design. Reuse it elsewhere with `Editor.program myFiles`.
-}

import Browser
import Eval exposing (appInit, appUpdate, appView, applyHandler, hasApp, lookup, mainValue, renderValue)
import Html exposing (Html, button, div, h1, h3, input, li, node, p, pre, text, textarea, ul)
import Html.Attributes exposing (placeholder, style, value)
import Html.Events exposing (onClick, onInput)
import Lang exposing (Value(..))


type alias Model =
    { files : List ( String, String )
    , selected : String
    , app : Result String Value
    , newName : String
    }


type Msg
    = SelectFile String
    | EditSource String
    | SetNewName String
    | AddFile
    | RemoveFile String
    | Interp Value
    | NoOp


{-| Builds an editor program over the given files (the first file is selected initially). -}
program : List ( String, String ) -> Program () Model Msg
program files =
    Browser.sandbox { init = initModel files, update = update, view = view }


initModel : List ( String, String ) -> Model
initModel files =
    refreshApp
        { files = files
        , selected = files |> List.head |> Maybe.map Tuple.first |> Maybe.withDefault ""
        , app = Err ""
        , newName = ""
        }


selectedFile : Model -> List ( String, String )
selectedFile model =
    [ ( model.selected, lookup model.selected model.files |> Maybe.withDefault "" ) ]


{-| Re-initialises the running app from the selected file (its model becomes `init`) when that file
is a Browser.sandbox-style program; otherwise the app slot is unused. -}
refreshApp : Model -> Model
refreshApp model =
    { model
        | app =
            if hasApp (selectedFile model) then
                appInit (selectedFile model)

            else
                Err ""
    }


update : Msg -> Model -> Model
update msg model =
    case msg of
        SelectFile name ->
            refreshApp { model | selected = name }

        EditSource content ->
            refreshApp { model | files = setFile model.selected content model.files }

        SetNewName n ->
            { model | newName = n }

        AddFile ->
            let
                name =
                    if String.endsWith ".elm" model.newName then
                        model.newName

                    else
                        model.newName ++ ".elm"
            in
            if model.newName == "" || hasFile name model.files then
                model

            else
                refreshApp { model | files = model.files ++ [ ( name, "main = text \"new file\"" ) ], selected = name, newName = "" }

        RemoveFile name ->
            let
                remaining =
                    List.filter (\f -> Tuple.first f /= name) model.files
            in
            refreshApp
                { model
                    | files = remaining
                    , selected =
                        if model.selected == name then
                            remaining |> List.head |> Maybe.map Tuple.first |> Maybe.withDefault ""

                        else
                            model.selected
                }

        Interp interpMsg ->
            { model | app = model.app |> Result.andThen (\m -> appUpdate (selectedFile model) interpMsg m) }

        NoOp ->
            model


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


view : Model -> Html Msg
view model =
    div [ style "font-family" "system-ui, sans-serif", style "max-width" "1000px", style "margin" "20px auto", style "color" "#0f1720" ]
        [ h1 [] [ text "Elm-in-Elm playground" ]
        , p [] [ text "Pick an example on the left, edit it, and the result of its main runs live on the right." ]
        , div [ style "display" "flex", style "gap" "16px", style "align-items" "flex-start" ]
            [ fileSidebar model
            , div [ style "flex" "2" ]
                [ h3 [] [ text model.selected ]
                , textarea
                    [ onInput EditSource
                    , value (lookup model.selected model.files |> Maybe.withDefault "")
                    , style "width" "100%"
                    , style "height" "280px"
                    , style "font-family" "monospace"
                    , style "font-size" "13px"
                    , style "padding" "10px"
                    , style "box-sizing" "border-box"
                    ]
                    []
                , mainPane model
                ]
            ]
        ]


fileSidebar : Model -> Html Msg
fileSidebar model =
    div [ style "flex" "1", style "min-width" "180px" ]
        [ h3 [] [ text "Files" ]
        , ul [ style "list-style" "none", style "padding" "0", style "margin" "0" ]
            (List.map (fileRow model.selected) model.files)
        , div [ style "display" "flex", style "gap" "4px", style "margin-top" "8px" ]
            [ input [ placeholder "New.elm", value model.newName, onInput SetNewName, style "flex" "1", style "min-width" "0" ] []
            , button [ onClick AddFile ] [ text "+" ]
            ]
        ]


fileRow : String -> ( String, String ) -> Html Msg
fileRow selected file =
    let
        name =
            Tuple.first file
    in
    li [ style "display" "flex", style "align-items" "center", style "gap" "4px", style "margin" "2px 0" ]
        [ button
            [ onClick (SelectFile name)
            , style "flex" "1"
            , style "text-align" "left"
            , style "font-weight"
                (if name == selected then
                    "bold"

                 else
                    "normal"
                )
            , style "background"
                (if name == selected then
                    "#dbeeff"

                 else
                    "#f4f4f4"
                )
            ]
            [ text name ]
        , button [ onClick (RemoveFile name), style "color" "#a00" ] [ text "x" ]
        ]


{-| Renders the result of the selected file's `main`: a live Browser.sandbox app, a static Html
value, or a plain value as text. -}
mainPane : Model -> Html Msg
mainPane model =
    div [ style "margin-top" "12px" ]
        [ h3 [ style "margin" "0 0 6px 0" ] [ text "Result" ]
        , div
            [ style "border" "1px solid #d0d7de", style "border-radius" "8px", style "padding" "14px", style "background" "#fff" ]
            [ if hasApp (selectedFile model) then
                liveApp model

              else
                staticMain (selectedFile model)
            ]
        ]


liveApp : Model -> Html Msg
liveApp model =
    case model.app of
        Err e ->
            errorBox e

        Ok appModel ->
            case appView (selectedFile model) appModel of
                Ok html ->
                    renderHtml (selectedFile model) html

                Err e ->
                    errorBox e


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

        _ ->
            Nothing
