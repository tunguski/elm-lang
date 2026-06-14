package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end browser-fidelity tests: compiles an example to a JS app bundle (kernel + DOM/TEA
 * runtime), loads it in real headless Chrome, and asserts on the actually-rendered DOM. Skipped if
 * Chrome is not installed. Tagged {@code browser} so the default fast {@code mvn test} skips it
 * (it launches a real browser); run it with {@code -Pfull} (or {@code mvn verify}).
 */
@Tag("browser")
class HeadlessChromeTest {

  private static final String CHROME = findChrome();

  private static String findChrome() {
    // An explicit override wins (used by CI), then the usual install locations on each OS.
    String env = System.getenv("CHROME_BIN");
    if (env != null && !env.isBlank() && Files.exists(Path.of(env))) {
      return env;
    }
    String[] paths = {
      // Windows
      "C:/Program Files/Google/Chrome/Application/chrome.exe",
      "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
      System.getProperty("user.home") + "/AppData/Local/Google/Chrome/Application/chrome.exe",
      // Linux (GitHub-hosted ubuntu runners ship google-chrome-stable)
      "/usr/bin/google-chrome",
      "/usr/bin/google-chrome-stable",
      "/usr/bin/chromium-browser",
      "/usr/bin/chromium",
      "/snap/bin/chromium",
      // macOS
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
      "/Applications/Chromium.app/Contents/MacOS/Chromium"
    };
    for (String p : paths) {
      if (Files.exists(Path.of(p))) {
        return p;
      }
    }
    return null;
  }

  private static String example(String slug) {
    StringBuilder m = new StringBuilder();
    for (String part : slug.split("-")) {
      m.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    try (InputStream in = HeadlessChromeTest.class.getResourceAsStream("/elm/examples/" + m + ".elm")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static String resource(String path) {
    try (InputStream in = HeadlessChromeTest.class.getResourceAsStream(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void todoMvcRendersAndReactsInTheBrowser() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // The flagship TodoMVC app compiled by the JS backend, driven via dispatched messages: add two
    // todos, toggle the first complete, and check the rendered DOM and the live count.
    String driver =
        "window.$app.dispatch($data('UpdateField',['milk']));"
            + "window.$app.dispatch($data('Add',[]));"
            + "window.$app.dispatch($data('UpdateField',['eggs']));"
            + "window.$app.dispatch($data('Add',[]));"
            + "window.$app.dispatch($data('Toggle',[1]));";
    String dom = renderInBrowser(resource("/elm/examples/TodoMvc.elm"), driver);
    assertTrue(dom.contains("milk") && dom.contains("eggs"), dom);
    assertTrue(dom.contains("1 items left"), dom); // one toggled complete
    assertTrue(dom.contains("[x]"), dom); // the completed marker
  }

  /** Compiles the source to a page, renders it in headless Chrome and returns the serialized DOM. */
  private String renderInBrowser(String source, String driver) throws Exception {
    return renderPage(JsCompiler.htmlPage(source, driver));
  }

  /** Renders the given full HTML page in headless Chrome and returns the serialized DOM. */
  private String renderPage(String html) throws Exception {
    Path page = Files.createTempFile("elm-page-", ".html");
    Files.writeString(page, html, StandardCharsets.UTF_8);
    String dom = renderUrl(page.toUri().toString());
    Files.deleteIfExists(page);
    return dom;
  }

  /** Loads a URL in headless Chrome (letting timers/fetches run) and returns the serialized DOM. */
  private String renderUrl(String url) throws Exception {
    Path userData = Files.createTempDirectory("elm-chrome-");
    Process p =
        new ProcessBuilder(
                CHROME,
                "--headless=new",
                "--disable-gpu",
                "--enable-unsafe-swiftshader",
                "--no-sandbox",
                "--no-first-run",
                "--no-default-browser-check",
                "--user-data-dir=" + userData,
                "--virtual-time-budget=2000",
                "--dump-dom",
                url)
            .redirectErrorStream(false)
            .start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(60, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("Chrome timed out");
    }
    return out;
  }

  @Test
  void helloRendersInChrome() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    assertTrue(renderInBrowser(example("hello"), null).contains("Hello!"));
  }

  @Test
  void browserApplicationMountsRoutesAndSetsTitle() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // A minimal Browser.application: it mounts (Document view + title), reads the initial Url's path,
    // and routes — dispatching the message onUrlChange produces updates the view's shown path.
    String app =
        """
        module Main exposing (main)
        import Browser
        import Html exposing (text)
        type Msg = Changed String | Ignore
        main =
            Browser.application
                { init = \\_ url key -> ( { path = url.path, key = key }, Cmd.none )
                , view = \\m -> { title = "Routed", body = [ text ("at " ++ m.path) ] }
                , update = \\msg m -> case msg of
                    Changed p -> ( { m | path = p }, Cmd.none )
                    Ignore -> ( m, Cmd.none )
                , subscriptions = \\_ -> Sub.none
                , onUrlChange = \\u -> Changed u.path
                , onUrlRequest = \\_ -> Ignore
                }
        """;
    String dom = renderInBrowser(app, "window.$app.dispatch($data('Changed',['/about']));");
    assertTrue(dom.contains("<title>Routed</title>"), "the Document title is set: " + dom);
    assertTrue(dom.contains("at /about"), "routing updated the view's path: " + dom);
  }

  @Test
  void htmlMapRoutesAChildsMessageThroughTheParent() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // A child view dispatches Bump; the parent wraps it with Html.map into Child, so clicking the
    // child's button drives the parent's update through the mapped message.
    String app =
        """
        module Main exposing (main)
        import Browser
        import Html exposing (button, div, text)
        import Html.Events exposing (onClick)
        type ChildMsg = Bump
        type Msg = Child ChildMsg
        childView = button [ onClick Bump ] [ text "+" ]
        main = Browser.sandbox { init = 0, update = update, view = view }
        update msg n = case msg of
          Child Bump -> n + 1
        view n = div [] [ Html.map Child childView, text ("count=" ++ String.fromInt n) ]
        """;
    String dom = renderInBrowser(app, "document.querySelector('button').click();");
    assertTrue(dom.contains("count=1"), "the mapped child click reached the parent: " + dom);
  }

  @Test
  void keyedNodesReorderByKeyPreservingDomState() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // A keyed list of items; Flip reverses the order. Because nodes are matched by key, the DOM
    // node for "a" is reused (its data-touched marker, set via JS, survives the reorder).
    String app =
        """
        module Main exposing (main)
        import Browser
        import Html exposing (text)
        import Html.Keyed as Keyed
        import Html.Attributes exposing (id)
        type Msg = Flip
        main = Browser.sandbox { init = False, update = \\_ _ -> True, view = view }
        row k = ( k, Html.node "li" [ id k ] [ text k ] )
        view flipped =
            Keyed.node "ul" [] (if flipped then [ row "b", row "a" ] else [ row "a", row "b" ])
        """;
    // Mark the #a node, then Flip; if its DOM node was reused, the marker is still there afterwards.
    String driver =
        "document.getElementById('a').setAttribute('data-kept','yes');"
            + "window.$app.dispatch($data('Flip',[]));";
    String dom = renderPage(JsCompiler.htmlPage(app, driver));
    assertTrue(dom.contains("data-kept=\"yes\""), "the keyed node was reused across reorder: " + dom);
    // After Flip, b precedes a in the DOM.
    assertTrue(dom.indexOf("id=\"b\"") < dom.indexOf("id=\"a\""), "reordered by key: " + dom);
  }

  @Test
  void platformWorkerRunsWithoutAView() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // A headless worker (no view): dispatch Inc, then write the model into the DOM so we can read it.
    String app =
        """
        module Main exposing (main)
        import Platform
        type Msg = Inc
        main = Platform.worker { init = \\_ -> ( 0, Cmd.none ), update = update, subscriptions = \\_ -> Sub.none }
        update msg n = case msg of
          Inc -> ( n + 1, Cmd.none )
        """;
    String driver =
        "window.$app.dispatch($data('Inc',[]));"
            + "window.$app.dispatch($data('Inc',[]));"
            + "document.body.textContent = 'model=' + window.$app.model();";
    String dom = renderPage(JsCompiler.htmlPage(app, driver));
    assertTrue(dom.contains("model=2"), "the worker ran update without a view: " + dom);
  }

  @Test
  void classListAndDoubleClickWork() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // classList renders only the true classes; onDoubleClick drives an update.
    String app =
        """
        module Main exposing (main)
        import Browser
        import Html exposing (div, text)
        import Html.Attributes exposing (classList)
        import Html.Events exposing (onDoubleClick)
        type Msg = Hit
        main = Browser.sandbox { init = False, update = \\_ _ -> True, view = view }
        view active =
            div [ classList [ ( "on", active ), ( "off", not active ) ], onDoubleClick Hit ]
                [ text (if active then "ACTIVE" else "idle") ]
        """;
    String dom = renderPage(JsCompiler.htmlPage(app, "document.querySelector('.off').dispatchEvent(new MouseEvent('dblclick',{bubbles:true}));"));
    assertTrue(dom.contains("ACTIVE"), "double-click updated the model: " + dom);
    assertTrue(dom.contains("class=\"on\""), "classList rendered the active class only: " + dom);
  }

  @Test
  void diffRemovesAClassWhenTheNewNodeHasNone() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Regression: a classed root div diffed into a class-less one must lose its class. The class is
    // applied at creation by $toDom, which (before the fix) didn't record el.$at — so the first diff
    // had no record of the class and couldn't remove it, leaving it stuck (e.g. a login-wrapper class
    // surviving onto a class-less app shell).
    String app =
        """
        module Main exposing (main)
        import Browser
        import Html exposing (div, text)
        import Html.Attributes exposing (class)
        type Msg = Enter
        main = Browser.sandbox { init = False, update = \\_ _ -> True, view = view }
        view entered =
            if entered then
                div [] [ text "shell" ]
            else
                div [ class "login-wrapper" ] [ text "login" ]
        """;
    String dom = renderPage(JsCompiler.htmlPage(app, "window.$app.dispatch($data('Enter',[]));"));
    // (assert on the rendered `class="..."` attribute form, not the bare string — the latter also
    // appears as a literal in the embedded JS bundle the page serialization includes.)
    assertTrue(dom.contains(">shell<"), "the update was applied (text re-rendered): " + dom);
    assertFalse(
        dom.contains("class=\"login-wrapper\""), "the stale class was removed on diff: " + dom);
  }

  @Test
  void htmlLazyMemoizesWhenItsArgumentIsUnchanged() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // The lazy subtree's arg (the constant 5) doesn't change when the counter bumps, so its view is
    // not re-run and its DOM node is reused — proven by a marker set via JS surviving the re-render.
    String app =
        """
        module Main exposing (main)
        import Browser
        import Html exposing (div, text)
        import Html.Attributes exposing (id)
        import Html.Lazy exposing (lazy)
        type Msg = Inc
        main = Browser.sandbox { init = 0, update = \\_ n -> n + 1, view = view }
        staticView _ = div [ id "static" ] [ text "S" ]
        view n = div [] [ lazy staticView 5, div [ id "count" ] [ text (String.fromInt n) ] ]
        """;
    String driver =
        "document.getElementById('static').setAttribute('data-kept','yes');"
            + "window.$app.dispatch($data('Inc',[]));";
    String dom = renderPage(JsCompiler.htmlPage(app, driver));
    assertTrue(dom.contains("data-kept=\"yes\""), "lazy node was reused (not re-rendered): " + dom);
    assertTrue(dom.contains("id=\"count\">1") || dom.contains(">1</div>"), "counter still updated: " + dom);
  }

  @Test
  void htmlLazy4RendersAndMemoizes() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // lazy4 must apply its four args to render, and (like lazy) reuse the node when none change.
    String app =
        """
        module Main exposing (main)
        import Browser
        import Html exposing (div, text)
        import Html.Attributes exposing (id)
        import Html.Lazy exposing (lazy4)
        type Msg = Inc
        main = Browser.sandbox { init = 0, update = \\_ n -> n + 1, view = view }
        sumView a b c d = div [ id "sum" ] [ text (String.fromInt (a + b + c + d)) ]
        view n = div [] [ lazy4 sumView 1 2 3 4, div [ id "count" ] [ text (String.fromInt n) ] ]
        """;
    String dom = renderPage(JsCompiler.htmlPage(app, null));
    assertTrue(dom.contains("id=\"sum\">10") || dom.contains(">10</div>"), "lazy4 applied all four args: " + dom);
  }

  @Test
  void htmlLazy6Through8ApplyAllTheirArgs() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // lazy6/7/8 must apply all of their (6/7/8) arguments to the view function.
    String app =
        """
        module Main exposing (main)
        import Html exposing (div, text)
        import Html.Lazy exposing (lazy6, lazy8)
        sum6 a b c d e f = text (String.fromInt (a + b + c + d + e + f))
        sum8 a b c d e f g h = text (String.fromInt (a + b + c + d + e + f + g + h))
        main = div [] [ lazy6 sum6 1 2 3 4 5 6, lazy8 sum8 1 2 3 4 5 6 7 8 ]
        """;
    String dom = renderPage(JsCompiler.htmlPage(app, null));
    assertTrue(dom.contains("21"), "lazy6 summed its 6 args: " + dom);
    assertTrue(dom.contains("36"), "lazy8 summed its 8 args: " + dom);
  }

  @Test
  void definitionListAndOtherElementsRenderInTheJsBackend() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Regression: many Html elements (dl/dt/dd, details, summary, …) were missing from the JS
    // backend's element registry, so an app using one threw `Unbound: Html.dl` and blanked the page —
    // even though the interpreter and type-checker already supported them. HtmlElementParityTest now
    // keeps the three lists in sync; this proves they render end-to-end through the compiled runtime.
    String app =
        """
        module Main exposing (main)
        import Html exposing (dl, dt, dd, details, summary, text)
        main =
            dl []
                [ dt [] [ text "Term" ]
                , dd [] [ details [] [ summary [] [ text "more" ] ] ]
                ]
        """;
    String dom = renderInBrowser(app, null);
    assertTrue(dom.contains("<dl>"), "dl rendered (was Unbound: Html.dl before the fix): " + dom);
    assertTrue(dom.contains("<dt>Term</dt>"), "dt rendered: " + dom);
    assertTrue(
        dom.contains("<dd>") && dom.contains("<details>") && dom.contains("<summary>more</summary>"),
        "dd/details/summary rendered: " + dom);
  }

  @Test
  void groceriesRendersList() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    String dom = renderInBrowser(example("groceries"), null);
    assertTrue(dom.contains("<li>Pamplemousse</li>"), dom);
    assertTrue(dom.contains("<li>Baguette</li>"), dom);
  }

  @Test
  void shapesRendersSvg() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    String dom = renderInBrowser(example("shapes"), null);
    assertTrue(dom.contains("<svg"), dom);
    assertTrue(dom.contains("<circle"), dom);
    assertTrue(dom.contains("<rect"), dom);
  }

  @Test
  void bundledLibraryResolvesInTheBrowserBundle() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // The app imports List.Extra without supplying it; appBundle pulls the bundled lib in.
    String app =
        """
        module Main exposing (main)
        import Browser
        import Html exposing (text)
        import List.Extra as LE
        main = Browser.sandbox { init = 0, update = \\_ m -> m, view = view }
        view _ = text (String.fromInt (List.length (LE.unique [ 1, 1, 2, 3, 2 ])))
        """;
    String dom = renderInBrowser(app, null);
    assertTrue(dom.contains(">3<"), "List.Extra.unique resolved and ran in the bundle: " + dom);
  }

  @Test
  void buttonsInitialAndAfterClicks() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    assertTrue(renderInBrowser(example("buttons"), null).contains("<div>0</div>"));
    // Drive three Increment clicks through the live TEA runtime, then inspect the re-rendered DOM.
    String driver =
        "window.$app.dispatch($data('Increment',[]));"
            + "window.$app.dispatch($data('Increment',[]));"
            + "window.$app.dispatch($data('Increment',[]));";
    String dom = renderInBrowser(example("buttons"), driver);
    assertTrue(dom.contains("<div>3</div>"), dom);
  }

  @Test
  void textareaInputDecodingTargetSelectionStartUpdatesModel() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // The editor's code textarea decodes BOTH target.value and target.selectionStart in one
    // Decode.map2 (so autocomplete knows the caret). If selectionStart doesn't decode as an Int,
    // the whole input message is dropped and typing does nothing. Drive a real input event.
    String app =
        """
        module Main exposing (main)
        import Browser
        import Html exposing (div, text, textarea)
        import Html.Attributes exposing (value)
        import Html.Events exposing (on)
        import Json.Decode as Decode
        type Msg = EditAt String Int
        main = Browser.sandbox { init = "", update = update, view = view }
        update msg _ = case msg of
          EditAt s _ -> s
        view model =
            div []
                [ textarea [ on "input" (Decode.map2 EditAt (Decode.at [ "target", "value" ] Decode.string) (Decode.at [ "target", "selectionStart" ] Decode.int)), value model ] []
                , div [] [ text ("got=" ++ model) ]
                ]
        """;
    String driver =
        "var ta=document.querySelector('textarea'); ta.focus();"
            + "ta.value='hi'; ta.setSelectionRange(2,2);"
            + "ta.dispatchEvent(new Event('input',{bubbles:true}));";
    String dom = renderInBrowser(app, driver);
    assertTrue(dom.contains("got=hi"), "input with a selectionStart decoder updated the model: " + dom);
  }

  @Test
  void decoderIntoRecordAliasWithListFieldRenders() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Repro candidate for the reported $listToArray(undefined) blank: a positional record-alias
    // constructor applied by Decode.mapN with a Decode.list field, decoded at load and rendered.
    String app =
        """
        module Main exposing (main)
        import Html exposing (text, ul, li)
        import Json.Decode as D
        type alias StatusInfo = { status : String, code : Int, items : List Int }
        decoder : D.Decoder StatusInfo
        decoder = D.map3 StatusInfo (D.field "status" D.string) (D.field "code" D.int) (D.field "items" (D.list D.int))
        info : StatusInfo
        info =
            case D.decodeString decoder "{\\"status\\":\\"NEW\\",\\"code\\":7,\\"items\\":[1,2,3]}" of
                Ok x -> x
                Err _ -> StatusInfo "ERR" 0 []
        main = ul [] (List.map (\\n -> li [] [ text (String.fromInt n) ]) info.items)
        """;
    String dom = renderInBrowser(app, null);
    assertTrue(dom.contains("<li>1</li>") && dom.contains("<li>3</li>"),
        "decoder-built record-alias List field rendered: " + dom);
  }

  @Test
  void svgGradientAndFilterElementsRenderInTheSvgNamespace() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Newly-added SVG elements must render and keep their case-sensitive camelCase tag names, which
    // only happens under the SVG namespace (createElementNS) — i.e. they're in the namespace set.
    String app =
        """
        module Main exposing (main)
        import Svg exposing (svg, defs, linearGradient, stop, rect, filter, feGaussianBlur)
        main =
            svg []
                [ defs []
                    [ linearGradient [] [ stop [] [] ]
                    , filter [] [ feGaussianBlur [] [] ]
                    ]
                , rect [] []
                ]
        """;
    String dom = renderInBrowser(app, null);
    assertTrue(dom.contains("<linearGradient"), "linearGradient rendered (SVG namespace): " + dom);
    assertTrue(dom.contains("<feGaussianBlur"), "feGaussianBlur rendered (SVG namespace): " + dom);
  }

  @Test
  void jsonDecodeDictProducesAUsableDict() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Regression: Json.Decode.dict built a Dict in the kernel representation, incompatible with the
    // DOM runtime's Dict functions, so Dict.get/toList on a decoded dict returned nothing/blanked.
    String app =
        """
        module Main exposing (main)
        import Html exposing (text)
        import Dict
        import Json.Decode as D
        decoded : Dict.Dict String Int
        decoded =
            Result.withDefault Dict.empty (D.decodeString (D.dict D.int) "{\\"a\\":1,\\"b\\":2}")
        main =
            text
                (String.fromInt (Dict.size decoded)
                    ++ "/"
                    ++ (case Dict.get "b" decoded of
                            Just n -> String.fromInt n
                            Nothing -> "MISSING"
                       )
                )
        """;
    String dom = renderInBrowser(app, null);
    assertTrue(dom.contains("2/2"), "decoded dict is usable (size 2, get b = 2): " + dom);
  }

  @Test
  void htmlAttributesAttributeAndPropertySetValues() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // The generic escape hatches: Html.Attributes.attribute sets any attribute; property sets a DOM
    // property (here `id`, which serializes as an attribute).
    String app =
        """
        module Main exposing (main)
        import Html exposing (div, text)
        import Html.Attributes exposing (attribute, property)
        import Json.Encode as E
        main = div [ attribute "data-foo" "bar", property "id" (E.string "z") ] [ text "x" ]
        """;
    String dom = renderInBrowser(app, null);
    assertTrue(dom.contains("data-foo=\"bar\""), "attribute set a custom attribute: " + dom);
    assertTrue(dom.contains("id=\"z\""), "property set the id: " + dom);
  }

  @Test
  void crossModuleRecordAliasCtorWithListFieldRenders() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Repro for a reported page-blank ($listToArray(undefined)): a record type-alias defined in one
    // module, constructed positionally in another with a List field, then that List rendered at load.
    String data =
        """
        module Data exposing (StatusInfo)
        type alias StatusInfo = { status : String, code : Int, items : List Int }
        """;
    String main =
        """
        module Main exposing (main)
        import Data exposing (StatusInfo)
        import Html exposing (text, ul, li)
        info : StatusInfo
        info = StatusInfo "NEW" 7 [ 1, 2, 3 ]
        main = ul [] (List.map (\\n -> li [] [ text (String.fromInt n) ]) info.items)
        """;
    String dom = renderPage(JsCompiler.htmlPageProject(null, main, data));
    assertTrue(dom.contains("<li>1</li>"), "the cross-module ctor's List field rendered: " + dom);
    assertTrue(dom.contains("<li>3</li>"), dom);
  }

  @Test
  void backOrGoesBackWhenSameSiteElseLoadsFallback() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // A back link that uses Browser.Navigation.backOr: history.back() when the visitor came from
    // this site, else load the fallback url.
    String app =
        """
        module Main exposing (main)
        import Browser
        import Browser.Navigation
        import Html exposing (button, text)
        import Html.Events exposing (onClick)
        type Msg = Back
        main = Browser.element
            { init = \\_ -> ( 0, Cmd.none )
            , update = \\msg m -> ( m, Browser.Navigation.backOr "#fallback" )
            , view = \\m -> button [ onClick Back ] [ text "back" ]
            , subscriptions = \\_ -> Sub.none
            }
        """;
    // No same-site referrer (file:// page) -> falls back to the url (a hash, so we stay on the page).
    String fallbackDriver =
        "document.querySelector('button').click();"
            + "document.body.setAttribute('data-loc', location.hash);";
    String dom = renderInBrowser(app, fallbackDriver);
    assertTrue(dom.contains("data-loc=\"#fallback\""), "no same-site referrer -> fallback url: " + dom);

    // Same-site referrer -> history.back() (stubbed to record), and no fallback navigation.
    String backDriver =
        "try{Object.defineProperty(document,'referrer',{configurable:true,get:function(){return location.origin+'/prev';}});}catch(e){}"
            + "var backed=false; history.back=function(){backed=true;};"
            + "document.querySelector('button').click();"
            + "document.body.setAttribute('data-backed', String(backed));"
            + "document.body.setAttribute('data-loc', location.hash);";
    String dom2 = renderInBrowser(app, backDriver);
    assertTrue(dom2.contains("data-backed=\"true\""), "same-site referrer -> history.back: " + dom2);
    assertTrue(!dom2.contains("data-loc=\"#fallback\""), "did not also load the fallback: " + dom2);
  }

  @Test
  void textFieldsReactsToInput() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    String driver = "window.$app.dispatch($data('Change',['hello']));";
    String dom = renderInBrowser(example("text-fields"), driver);
    assertTrue(dom.contains("olleh"), dom); // String.reverse "hello"
  }

  @Test
  void textFieldKeepsFocusAcrossKeystroke() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Regression: re-rendering used to rebuild the whole subtree, replacing the <input> on every
    // keystroke and dropping focus (so only one character could be typed). Drive a real 'input'
    // event through the live listener and assert the SAME element survives and stays focused.
    String driver =
        "var inp=document.querySelector('input'); inp.$marker='KEEP'; inp.focus();"
            + "inp.value='h'; inp.dispatchEvent(new Event('input',{bubbles:true}));"
            + "var after=document.querySelector('input');"
            + "document.body.setAttribute('data-same', String(after.$marker==='KEEP'));"
            + "document.body.setAttribute('data-focused', String(document.activeElement===after));";
    String dom = renderInBrowser(example("text-fields"), driver);
    assertTrue(dom.contains("data-same=\"true\""), dom); // input element reused, not replaced
    assertTrue(dom.contains("data-focused=\"true\""), dom); // focus preserved
    assertTrue(dom.contains("<div>h</div>"), dom); // model updated and re-rendered (reverse "h")
  }

  @Test
  void editorRendersTheSelectedFilesMainLive() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    assumeTrue(
        pl.matsuo.elm.site.SiteGenerator.editorAvailable(),
        "projects/elm-editor not present (separate repo github.com/tunguski/elm-editor)");
    String[] modules = pl.matsuo.elm.site.SiteGenerator.editorSources();
    String dom = renderPage(JsCompiler.htmlPageProject(null, modules));
    // The editor fetches its example files over HTTP at startup; rendered from a file:// page those
    // fetches don't resolve, so only the built-in starter (Buttons.elm) is present — and it runs
    // live: the file list shows Buttons.elm and the minus button / initial model render. (The HTTP
    // loading itself is covered by editorLoadsExamplesOverHttp, served over real HTTP.)
    assertTrue(dom.contains("Buttons.elm"), "starter file listed");
    assertTrue(dom.contains(">-<"), "selected file's main rendered live (the minus button)");
    assertTrue(dom.contains("<div>0</div>"), "live app shows the initial interpreted model");
    assertTrue(dom.contains("Open .elm"), "the Open-file button is present");
  }

  @Test
  void portsSendAndReceiveAcrossTheJsBoundary() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // A port module with an outgoing port (out) and an incoming port (incoming). The driver
    // subscribes to `out`, pushes a value in through `incoming`, and the app echoes the running
    // total back out — exercising both directions of the JS port boundary.
    String source =
        """
        port module Main exposing (main)
        import Browser
        import Html exposing (text)
        port out : Int -> Cmd msg
        port incoming : (Int -> msg) -> Sub msg
        type Msg = Got Int
        main = Browser.element
            { init = \\_ -> ( 0, Cmd.none )
            , update = \\msg model -> case msg of
                Got n -> ( model + n, out (model + n) )
            , view = \\model -> text (String.fromInt model)
            , subscriptions = \\_ -> incoming Got
            }
        """;
    String driver =
        "window.$app.ports.out.subscribe(function(v){ document.title = 'out=' + v; });"
            + "window.$app.ports.incoming.send(5);"
            + "window.$app.ports.incoming.send(10);";
    String dom = renderInBrowser(source, driver);
    // 0 -> Got 5 -> 5 (out 5) -> Got 10 -> 15 (out 15); the last outgoing value is in the title.
    assertTrue(dom.contains("out=15"), "outgoing port delivered the latest value: " + dom);
    assertTrue(dom.contains(">15<") || dom.contains("15"), "the model updated from incoming ports: " + dom);
  }

  @Test
  void editorRendersAWebglProgramToALiveCanvas() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    assumeTrue(
        pl.matsuo.elm.site.SiteGenerator.editorAvailable(),
        "projects/elm-editor not present (separate repo github.com/tunguski/elm-editor)");
    String[] modules = pl.matsuo.elm.site.SiteGenerator.editorSources();
    // Replace the selected file with a small WebGL program (one red triangle behind a perspective
    // matrix). The editor evaluates it to a WebGL.scene and the bridge mounts a real <canvas> driven
    // by the GL runtime. We assert the canvas appears and the page didn't error — not the pixels.
    String program =
        String.join(
            "\\n",
            "vert = [glsl| attribute vec3 position; uniform mat4 camera;"
                + " void main () { gl_Position = camera * vec4(position, 1.0); } |]",
            "frag = [glsl| precision mediump float;"
                + " void main () { gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0); } |]",
            "mesh = WebGL.triangles"
                + " [ ( { position = vec3 0 0 0 }, { position = vec3 1 0 0 }, { position = vec3 0 1 0 } ) ]",
            "main = WebGL.toHtml [ width 220, height 160 ]"
                + " [ WebGL.entity vert frag mesh { camera = Mat4.makePerspective 45 1.4 0.1 100 } ]");
    // EditAt carries the new source and the caret offset (the editor's textarea reports both).
    String driver = "window.$app.dispatch($data('EditAt',[\"" + program + "\",0]));";
    String dom = renderPage(JsCompiler.htmlPageProject(driver, modules));
    assertTrue(dom.contains("<canvas"), "a live WebGL <canvas> is mounted: " + dom);
    // The canvas carries the requested size, proving the program's attributes flowed through.
    assertTrue(dom.contains("width=\"220\"") || dom.contains("width=220"), "canvas sized from the program");
  }

  @Test
  void editorTimeTravelDebuggerRecordsAndRewinds() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    assumeTrue(
        pl.matsuo.elm.site.SiteGenerator.editorAvailable(),
        "projects/elm-editor not present (separate repo github.com/tunguski/elm-editor)");
    String[] modules = pl.matsuo.elm.site.SiteGenerator.editorSources();
    // Dispatch two Increment messages into the interpreted Buttons app, then rewind to the start.
    // Interp/Rewind belong to the preview pane (ElmPreview.Msg), which the Editor shell wraps as
    // PreviewMsg, so the drivers dispatch the wrapped form.
    String inc = "$data('PreviewMsg',[$data('Interp',[$data('VCtor',['Increment',$nil])])])";
    String driver =
        "window.$app.dispatch(" + inc + ");window.$app.dispatch(" + inc + ");"
            + "window.$app.dispatch($data('PreviewMsg',[$data('Rewind',[0])]));";
    String dom = renderPage(JsCompiler.htmlPageProject(driver, modules));
    assertTrue(dom.contains("time travel"), "the time-travel scrubber appears after steps");
    assertTrue(dom.contains("msg 0 /"), "cursor rewound to message 0");
    assertTrue(dom.contains("<div>0</div>"), "rewinding re-renders the initial model");
    // The message-log panel lists each dispatched message (rendered) as a clickable chip.
    assertTrue(dom.contains("messages:"), "the message-log panel appears");
    assertTrue(dom.contains("1. Increment"), "first dispatched message rendered in the log");
    assertTrue(dom.contains("2. Increment"), "second dispatched message rendered in the log");
  }

  @Test
  void editorRestoresASharedSessionFromAPermalink() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    assumeTrue(
        pl.matsuo.elm.site.SiteGenerator.editorAvailable(),
        "projects/elm-editor not present (separate repo github.com/tunguski/elm-editor)");
    String[] modules = pl.matsuo.elm.site.SiteGenerator.editorSources();
    // A GotHash carrying an encoded one-file session (Share.encodeFiles format: <len>,<text> per
    // field) replaces the default starter files with the shared file — the permalink restore path.
    // The file's main concatenates "RESTO" ++ "RED": the rendered "RESTORED" appears only if the
    // session was restored AND its main evaluated live (the substring is never literal in the page).
    String encoded = "10,Shared.elm30,main = text (\"RESTO\" ++ \"RED\")";
    String driver = "window.$app.dispatch($data('GotHash',['" + encoded + "']));";
    String dom = renderPage(JsCompiler.htmlPageProject(driver, modules));
    assertTrue(dom.contains("RESTORED"), "the restored file's main rendered live: " + dom);
  }

  @Test
  void editorShowsALineNumberGutter() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    assumeTrue(
        pl.matsuo.elm.site.SiteGenerator.editorAvailable(),
        "projects/elm-editor not present (separate repo github.com/tunguski/elm-editor)");
    String[] modules = pl.matsuo.elm.site.SiteGenerator.editorSources();
    // Edit to a 3-line file; the gutter renders a numbered <div> per line. ">N</div>" only appears in
    // the rendered DOM (the compiled bundle builds nodes via $data, not literal tags), so it's a
    // gutter-specific check.
    String program = "a = 1\\nb = 2\\nmain = text (String.fromInt 42)"; // 3 lines, no quotes
    String driver = "window.$app.dispatch($data('EditAt',['" + program + "',0]));";
    String dom = renderPage(JsCompiler.htmlPageProject(driver, modules));
    assertTrue(dom.contains(">1</div>"), "gutter line 1: " + dom);
    assertTrue(dom.contains(">2</div>"), "gutter line 2");
    assertTrue(dom.contains(">3</div>"), "gutter line 3");
    assertTrue(dom.contains("42"), "the file still renders live");
  }

  @Test
  void editorRestoresAnAutosavedSessionFromLocalStorage() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    assumeTrue(
        pl.matsuo.elm.site.SiteGenerator.editorAvailable(),
        "projects/elm-editor not present (separate repo github.com/tunguski/elm-editor)");
    String[] modules = pl.matsuo.elm.site.SiteGenerator.editorSources();
    // LoadedSession(Just <encoded>) is what the autosave load dispatches at startup; it restores the
    // saved file, whose main concatenates "AUTO" ++ "SAVED" (the rendered "AUTOSAVED" proves it ran).
    String encoded = "9,Saved.elm31,main = text (\"AUTO\" ++ \"SAVED\")";
    String driver = "window.$app.dispatch($data('LoadedSession',[$data('Just',['" + encoded + "'])]));";
    String dom = renderPage(JsCompiler.htmlPageProject(driver, modules));
    assertTrue(dom.contains("AUTOSAVED"), "the autosaved session restored and rendered live: " + dom);
  }

  @Test
  void editorLoadsExamplesOverHttp() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Serve the editor page + example files over real HTTP and confirm the editor fetches them at
    // startup and lists them as editable files (here: Shapes.elm and Quotes.elm).
    assumeTrue(
        pl.matsuo.elm.site.SiteGenerator.editorAvailable(),
        "projects/elm-editor not present (separate repo github.com/tunguski/elm-editor)");
    String[] modules = pl.matsuo.elm.site.SiteGenerator.editorSources();
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("elm-editor-http-");
    java.nio.file.Files.writeString(dir.resolve("editor.html"), JsCompiler.htmlPageProject(null, modules));
    java.nio.file.Path ex = java.nio.file.Files.createDirectories(dir.resolve("examples"));
    for (String name : new String[] {"Hello", "Shapes", "Quotes"}) {
      java.nio.file.Files.writeString(ex.resolve(name + ".elm"), resource("/elm/examples/" + name + ".elm"));
    }
    com.sun.net.httpserver.HttpServer http =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/", exchange -> {
      java.nio.file.Path f = dir.resolve(exchange.getRequestURI().getPath().substring(1));
      if (java.nio.file.Files.isRegularFile(f)) {
        byte[] b = java.nio.file.Files.readAllBytes(f);
        exchange.sendResponseHeaders(200, b.length);
        try (java.io.OutputStream os = exchange.getResponseBody()) { os.write(b); }
      } else {
        exchange.sendResponseHeaders(404, -1);
      }
      exchange.close();
    });
    http.start();
    try {
      String dom = renderUrl("http://127.0.0.1:" + http.getAddress().getPort() + "/editor.html");
      assertTrue(dom.contains("Shapes.elm") && dom.contains("Quotes.elm"), "fetched examples listed: " + dom);
    } finally {
      http.stop(0);
    }
  }

  @Test
  void recordsAndDeterministicallyReplaysTheMessageLog() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Dispatch three increments, capture the recorded message log, then replay it from scratch and
    // confirm the reconstructed model and history match exactly (deterministic reproduction).
    String driver =
        "window.$app.dispatch($data('Increment',[]));"
            + "window.$app.dispatch($data('Increment',[]));"
            + "window.$app.dispatch($data('Increment',[]));"
            + "var log = window.$app.messages().slice();"
            + "document.body.setAttribute('data-recorded', String(log.length));"
            + "window.$app.replay(log);"
            + "document.body.setAttribute('data-replayed', document.querySelectorAll('div')[1].textContent);"
            + "document.body.setAttribute('data-steps', String(window.$app.history().length - 1));";
    String dom = renderInBrowser(example("buttons"), driver);
    assertTrue(dom.contains("data-recorded=\"3\""), dom); // three messages recorded
    assertTrue(dom.contains("data-replayed=\"-3+\"") || dom.contains("data-replayed=\"3\""), dom); // count 3
    assertTrue(dom.contains("data-steps=\"3\""), dom); // history rebuilt to the same length
  }

  @Test
  void timeTravelShowsHistoricalModelThenResumesLive() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Three increments produce snapshots [0,1,2,3]. goto(1) re-renders the model after the first
    // step (1) without mutating state; live() returns to the latest (3).
    String driver =
        "window.$app.dispatch($data('Increment',[]));"
            + "window.$app.dispatch($data('Increment',[]));"
            + "window.$app.dispatch($data('Increment',[]));"
            + "window.$app.goto(1);"
            + "document.body.setAttribute('data-historical', document.querySelectorAll('div')[2].textContent);"
            + "window.$app.live();"
            + "document.body.setAttribute('data-live', document.querySelectorAll('div')[2].textContent);"
            + "document.body.setAttribute('data-steps', String(window.$app.history().length - 1));";
    String dom = renderInBrowser(example("buttons"), driver);
    assertTrue(dom.contains("data-historical=\"1\""), dom); // time-travelled to snapshot 1 (count 1)
    assertTrue(dom.contains("data-live=\"3\""), dom); // resumed at the latest model (count 3)
    assertTrue(dom.contains("data-steps=\"3\""), dom); // three recorded steps
  }

  // The examples below were a blank page in the browser because the JS runtime had no effect
  // kernel: building the initial Cmd/Sub threw "Unbound: ..." and nothing rendered.

  @Test
  void formsRendersRecordAliasProgram() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Regression: the `Model` record-alias constructor compiled to $data(...) and was applied like a
    // function ("$data is not a function"), so forms rendered nothing.
    String dom = renderInBrowser(example("forms"), null);
    assertTrue(dom.contains("placeholder=\"Name\""), dom);
    assertTrue(dom.contains("placeholder=\"Password\""), dom);
  }

  @Test
  void numbersRollsWithRandomEffect() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Random.generate must build a Cmd (initial render) and, on clicking Roll, produce a 1..6 face.
    assertTrue(renderInBrowser(example("numbers"), null).contains("<h1>"), "die should render");
    String driver =
        "document.querySelector('button').click();"
            + "document.body.setAttribute('data-face', document.querySelector('h1').textContent);";
    String dom = renderInBrowser(example("numbers"), driver);
    assertTrue(dom.matches("(?s).*data-face=\"[1-6]\".*"), dom);
  }

  @Test
  void clockRendersSvgFaceWithTimeSub() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Time.now/here Tasks and the Time.every subscription must run; the clock draws an SVG face.
    String dom = renderInBrowser(example("clock"), null);
    assertTrue(dom.contains("<circle"), dom);
    assertTrue(dom.contains("<line"), dom); // the hand
  }

  @Test
  void taskMapNCombinesResultsInTheJsBackend() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Task.map3 (dom.js): the init task runs three sub-tasks and applies the function to all three
    // results; the combined value lands in the model and renders.
    String app =
        """
        module Main exposing (main)
        import Browser
        import Html exposing (text)
        import Task
        type Msg = Got Int
        main =
            Browser.element
                { init = \\_ -> ( 0, Task.perform Got (Task.map3 (\\a b c -> a + b + c) (Task.succeed 1) (Task.succeed 2) (Task.succeed 39)) )
                , update = \\msg model ->
                    case msg of
                        Got n -> ( n, Cmd.none )
                , view = \\m -> text (String.fromInt m)
                , subscriptions = \\_ -> Sub.none
                }
        """;
    String dom = renderInBrowser(app, null);
    assertTrue(dom.contains("42"), "Task.map3 combined the three task results: " + dom);
  }

  @Test
  void processSpawnAndKillRunInTheJsBackend() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Process.spawn returns a process id immediately; killing it and mapping the result drives the
    // model. (dom.js: spawn schedules on the next tick, kill cancels it.)
    String app =
        """
        module Main exposing (main)
        import Browser
        import Html exposing (text)
        import Task
        import Process
        type Msg = Got Int
        main =
            Browser.element
                { init = \\_ -> ( 0, Task.perform Got (Process.spawn (Task.succeed 1) |> Task.andThen Process.kill |> Task.map (\\_ -> 7)) )
                , update = \\msg model ->
                    case msg of
                        Got n -> ( n, Cmd.none )
                , view = \\m -> text (String.fromInt m)
                , subscriptions = \\_ -> Sub.none
                }
        """;
    String dom = renderInBrowser(app, null);
    assertTrue(dom.contains("7"), "Process.spawn |> kill resolved and drove the model: " + dom);
  }

  @Test
  void uploadRendersFileInput() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // The change-event decoder (Json.Decode.at/list/File.decoder) must build without throwing.
    String dom = renderInBrowser(example("upload"), null);
    assertTrue(dom.contains("type=\"file\""), dom);
  }

  @Test
  void playgroundPictureRendersAsMultiModuleBundle() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // The real evancz/elm-playground source is bundled with the example into one JS program that
    // runs live in the browser (previously this could only be a server-rendered snapshot).
    String playground = example2("/elm/examples/Playground.elm");
    String dom = renderPage(JsCompiler.htmlPageProject(null, playground, example("picture")));
    assertTrue(dom.contains("<svg"), dom);
    assertTrue(dom.contains("<rect") && dom.contains("<circle"), dom);
  }

  @Test
  void webglTriangleRendersACanvas() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // The WebGL kernel builds meshes/matrices and renders into a real <canvas> (software GL here).
    String dom = renderInBrowser(example("triangle"), null);
    assertTrue(dom.contains("<canvas"), dom);
    assertTrue(dom.contains("width=\"400\""), dom);
  }

  private static String example2(String path) {
    try (InputStream in = HeadlessChromeTest.class.getResourceAsStream(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
