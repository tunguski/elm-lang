package pl.matsuo.elm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.util.Resources;

/**
 * End-to-end test of a database-backed HTTP handler: an Elm {@code handle : Request -> Db Response}
 * built on {@code lib/Server.elm} + {@code lib/Db.elm}, dispatched through {@link ServerRunner} with
 * a JDBC URL so each request runs its queries against H2 — exactly what {@code elm server --db} does.
 */
class ServerDbTest {

  private static final String SERVER_LIB = Resources.read("/elm/lib/Server.elm");
  private static final String DB_LIB = Resources.read("/elm/lib/Db.elm");

  private static final String APP =
      """
      module DbServer exposing (handle, schema)

      import Db exposing (..)
      import Server exposing (Request, Response)


      schema : Db (Result String Int)
      schema =
          execute "CREATE TABLE items (id INT PRIMARY KEY, name VARCHAR)" []
              |> andThen (\\_ -> execute "INSERT INTO items VALUES (1, 'sock'), (2, 'shoe'), (3, 'hat')" [])


      handle : Request -> Db Response
      handle req =
          case Server.segments req of
              [ "count" ] ->
                  queryWith "SELECT COUNT(*) FROM items" [] (row identity |> andMap intColumn)
                      |> map
                          (\\result ->
                              case result of
                                  Ok (n :: _) ->
                                      Server.text (String.fromInt n)

                                  _ ->
                                      Server.response 500 "text/plain" "no count"
                          )

              [ "item", id ] ->
                  queryWith "SELECT name FROM items WHERE id = ?"
                      [ int (Maybe.withDefault 0 (String.toInt id)) ]
                      (row identity |> andMap textColumn)
                      |> map
                          (\\result ->
                              case result of
                                  Ok (name :: _) ->
                                      Server.json ("{\\"name\\":\\"" ++ name ++ "\\"}")

                                  _ ->
                                      Server.notFound
                          )

              _ ->
                  succeed Server.notFound
      """;

  private String url;
  private Connection keepAlive;
  private Object handler;

  @BeforeEach
  void setUp() throws SQLException {
    // DB_CLOSE_DELAY=-1 keeps the in-memory DB alive across the per-request connections the runner
    // opens; `keepAlive` holds one connection so the database survives for the whole test.
    url = "jdbc:h2:mem:serverdb_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    keepAlive = DriverManager.getConnection(url);
    Project project = Project.load(APP, DB_LIB, SERVER_LIB);
    DbRunner.run(project.entryValue("schema"), keepAlive);
    handler = project.entryValue("handle");
  }

  @AfterEach
  void tearDown() throws SQLException {
    keepAlive.close();
  }

  @Test
  void runsAQueryPerRequest() {
    ServerRunner.Resp count = ServerRunner.dispatch(handler, url, "GET", "/count", "", "");
    assertEquals(200, count.status());
    assertEquals("3", count.body());
    assertEquals("text/plain", count.contentType());
  }

  @Test
  void boundPathParameterReachesTheDatabase() {
    ServerRunner.Resp shoe = ServerRunner.dispatch(handler, url, "GET", "/item/2", "", "");
    assertEquals(200, shoe.status());
    assertEquals("application/json", shoe.contentType());
    assertTrue(shoe.body().contains("shoe"), shoe.body());

    // A row that does not exist routes to 404 — the decoder yields an empty list, not an error.
    assertEquals(404, ServerRunner.dispatch(handler, url, "GET", "/item/99", "", "").status());
  }

  @Test
  void pureHandlerStillWorksWithoutAUrl() {
    // Same handler, no JDBC URL: unmatched route returns notFound without touching a database.
    ServerRunner.Resp r = ServerRunner.dispatch(handler, null, "GET", "/nope", "", "");
    assertEquals(404, r.status());
  }
}
