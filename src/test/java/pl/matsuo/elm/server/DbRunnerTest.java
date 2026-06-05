package pl.matsuo.elm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.util.Resources;

/**
 * Exercises the {@link DbRunner} JDBC interpreter end to end: a pure Elm program built on {@code
 * lib/Db.elm} describes queries, and the runner executes them against an in-memory H2 database. This
 * covers typed parameter binding, typed row decoding, NULL handling, parameterised-query safety, and
 * error surfacing — the whole point of the typed layer.
 */
class DbRunnerTest {

  private static final String DB_LIB = Resources.read("/elm/lib/Db.elm");

  private static final String APP =
      """
      module DbApp exposing (..)

      import Db exposing (..)


      schema : Db (Result String Int)
      schema =
          execute "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR, score DOUBLE, active BOOLEAN, note VARCHAR)" []
              |> andThen (\\_ -> insert 1 "Ada" 9.5 True (Just "first"))
              |> andThen (\\_ -> insert 2 "Linus" 7.0 False Nothing)
              |> andThen (\\_ -> insert 3 "Grace" 9.0 True Nothing)


      insert : Int -> String -> Float -> Bool -> Maybe String -> Db (Result String Int)
      insert id name score active note =
          execute "INSERT INTO users (id, name, score, active, note) VALUES (?, ?, ?, ?, ?)"
              [ int id, text name, real score, bool active, maybeText note ]


      maybeText : Maybe String -> Value
      maybeText m =
          case m of
              Just s ->
                  text s

              Nothing ->
                  null


      type alias User =
          { id : Int, name : String, score : Float, active : Bool, note : Maybe String }


      userDecoder : RowDecoder User
      userDecoder =
          row User
              |> andMap intColumn
              |> andMap textColumn
              |> andMap realColumn
              |> andMap boolColumn
              |> andMap (nullable textColumn)


      allUsers : Db (Result String (List User))
      allUsers =
          queryWith "SELECT id, name, score, active, note FROM users ORDER BY id" [] userDecoder


      activeNames : Db (Result String (List String))
      activeNames =
          queryWith "SELECT name FROM users WHERE active = ? ORDER BY name"
              [ bool True ]
              (row identity |> andMap textColumn)


      countWhereName : String -> Db (Result String (List Int))
      countWhereName name =
          queryWith "SELECT COUNT(*) FROM users WHERE name = ?" [ text name ] (row identity |> andMap intColumn)


      badQuery : Db (Result String (List Row))
      badQuery =
          query "SELECT * FROM nonexistent_table" []
      """;

  private Connection conn;
  private Project project;

  @BeforeEach
  void setUp() throws SQLException {
    // A fresh, private in-memory database per test.
    conn = DriverManager.getConnection("jdbc:h2:mem:dbrunner_" + System.nanoTime());
    project = Project.load(APP, DB_LIB);
    Object schemaResult = DbRunner.run(project.entryValue("schema"), conn);
    assertTrue(Show.plain(schemaResult).startsWith("Ok"), "schema setup: " + Show.plain(schemaResult));
  }

  @AfterEach
  void tearDown() throws SQLException {
    conn.close();
  }

  @Test
  void decodesTypedRowsIntoRecords() {
    String out = Show.plain(DbRunner.run(project.entryValue("allUsers"), conn));
    assertTrue(out.startsWith("Ok"), out);
    assertTrue(out.contains("Ada"), out);
    assertTrue(out.contains("Linus"), out);
    assertTrue(out.contains("Grace"), out);
    // Typed cells survived the round trip: a Float score and a Bool flag.
    assertTrue(out.contains("9.5"), out);
    assertTrue(out.contains("True"), out);
  }

  @Test
  void nullColumnDecodesToNothing() {
    String out = Show.plain(DbRunner.run(project.entryValue("allUsers"), conn));
    // Ada has note "first"; Linus/Grace have NULL -> Nothing.
    assertTrue(out.contains("Just \"first\""), out);
    assertTrue(out.contains("Nothing"), out);
  }

  @Test
  void boundParameterFiltersRows() {
    String out = Show.plain(DbRunner.run(project.entryValue("activeNames"), conn));
    // Only Ada and Grace are active; sorted by name.
    assertTrue(out.startsWith("Ok"), out);
    assertTrue(out.contains("Ada") && out.contains("Grace"), out);
    assertFalse(out.contains("Linus"), "inactive user must be filtered out: " + out);
  }

  @Test
  void parametersAreBoundNotInterpolated() {
    // A value that would be catastrophic if spliced into SQL must simply match no rows.
    String injection = Show.plain(applyString("countWhereName", "Ada'; DROP TABLE users; --"));
    // No row has that literal name, so the count is zero — and crucially the table still exists.
    assertTrue(injection.startsWith("Ok"), injection);
    assertTrue(injection.contains("0"), injection);
    // The table is untouched: the real query still returns all three users.
    String all = Show.plain(DbRunner.run(project.entryValue("allUsers"), conn));
    assertTrue(all.contains("Ada") && all.contains("Linus") && all.contains("Grace"), all);
  }

  @Test
  void sqlErrorBecomesErrValue() {
    String out = Show.plain(DbRunner.run(project.entryValue("badQuery"), conn));
    assertTrue(out.startsWith("Err"), out);
    assertFalse(out.contains("Exception"), "the error should be a clean message, not a stack trace: " + out);
  }

  @Test
  void recognisesDbEffects() {
    assertTrue(DbRunner.isDbEffect(project.entryValue("allUsers")));
    assertFalse(DbRunner.isDbEffect("just a string"));
  }

  /** Applies a one-argument Elm function (e.g. {@code countWhereName}) and runs the resulting Db. */
  private Object applyString(String fn, String arg) {
    Object db = pl.matsuo.elm.interp.Apply.apply(project.entryValue(fn), arg);
    return DbRunner.run(db, conn);
  }
}
