package pl.matsuo.elm.server;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Thunk;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmList;

/**
 * Interprets the {@code Db a} effect description from the {@code Db} server library (see {@code
 * lib/Db.elm}) against a real JDBC {@link Connection}, returning the final {@code a}. Like {@link
 * pl.matsuo.elm.script.ScriptRunner} for {@code Posix.Io}, the {@code Db} value is a free structure
 * of constructors — {@code Query}/{@code Execute} carry the SQL, the bound parameters, and a
 * continuation that is applied to the typed result to yield the next step; {@code Done} ends it.
 *
 * <p>Parameters are bound through {@link PreparedStatement} (never spliced into the SQL), and result
 * cells are mapped to {@code Db.Value} constructors by JDBC column type. Any {@link SQLException}
 * becomes an {@code Err message} fed back to the Elm code, so a bad query is a value, not a crash.
 */
public final class DbRunner {

  private DbRunner() {}

  /** Whether {@code value} is a {@code Db} effect (so the server should interpret it, not treat it
   * as a plain {@code Response}). */
  public static boolean isDbEffect(Object value) {
    return Thunk.resolve(value) instanceof ElmData d
        && (d.ctor().equals("Done") || d.ctor().equals("Query") || d.ctor().equals("Execute"));
  }

  /** Walks the {@code db} description to completion against {@code conn}, returning the final value. */
  public static Object run(Object db, Connection conn) {
    Object cur = Thunk.resolve(db);
    while (true) {
      if (!(cur instanceof ElmData d)) {
        throw new ElmRuntimeError("a Db handler must return a Db value, got: " + cur);
      }
      switch (d.ctor()) {
        case "Done" -> {
          return d.arg(0);
        }
        case "Query" -> {
          Object result = runQuery(requireConnection(conn), str(d.arg(0)), params(d.arg(1)));
          cur = Thunk.resolve(Apply.apply(d.arg(2), result));
        }
        case "Execute" -> {
          Object result = runUpdate(requireConnection(conn), str(d.arg(0)), params(d.arg(1)));
          cur = Thunk.resolve(Apply.apply(d.arg(2), result));
        }
        default -> throw new ElmRuntimeError("unexpected Db constructor: " + d.ctor());
      }
    }
  }

  private static Connection requireConnection(Connection conn) {
    if (conn == null) {
      throw new ElmRuntimeError(
          "this handler runs a database query, but the server was started without a database"
              + " (pass --db <jdbc-url> to `elm server`)");
    }
    return conn;
  }

  // --- statement execution ------------------------------------------------

  private static Object runQuery(Connection conn, String sql, List<Object> params) {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        List<Object> rows = new ArrayList<>();
        while (rs.next()) {
          List<Object> cells = new ArrayList<>(cols);
          for (int i = 1; i <= cols; i++) {
            cells.add(cell(rs, i, meta.getColumnType(i)));
          }
          rows.add(ElmList.fromJava(cells));
        }
        return ok(ElmList.fromJava(rows));
      }
    } catch (SQLException e) {
      return err(message(e));
    }
  }

  private static Object runUpdate(Connection conn, String sql, List<Object> params) {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      bind(ps, params);
      return ok((long) ps.executeUpdate());
    } catch (SQLException e) {
      return err(message(e));
    }
  }

  // --- value <-> JDBC marshalling ----------------------------------------

  /** Binds each {@code Db.Value} parameter to its {@code ?} placeholder by JDBC type. */
  private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
    for (int i = 0; i < params.size(); i++) {
      int idx = i + 1;
      if (!(Thunk.resolve(params.get(i)) instanceof ElmData v)) {
        throw new SQLException("parameter " + idx + " is not a Db.Value");
      }
      switch (v.ctor()) {
        case "VInt" -> ps.setLong(idx, asLong(v.arg(0)));
        case "VText" -> ps.setString(idx, str(v.arg(0)));
        case "VReal" -> ps.setDouble(idx, asDouble(v.arg(0)));
        case "VBool" -> ps.setBoolean(idx, asBool(v.arg(0)));
        case "VNull" -> ps.setObject(idx, null);
        default -> throw new SQLException("unexpected Db.Value: " + v.ctor());
      }
    }
  }

  /** Maps a result-set cell to the matching {@code Db.Value} constructor by its JDBC column type. */
  private static ElmData cell(ResultSet rs, int i, int sqlType) throws SQLException {
    rs.getObject(i);
    if (rs.wasNull()) {
      return value("VNull");
    }
    return switch (sqlType) {
      case Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT ->
          value("VInt", rs.getLong(i));
      case Types.BOOLEAN, Types.BIT -> value("VBool", rs.getBoolean(i));
      case Types.FLOAT, Types.DOUBLE, Types.REAL, Types.DECIMAL, Types.NUMERIC ->
          value("VReal", rs.getDouble(i));
      default -> value("VText", rs.getString(i));
    };
  }

  // --- small Elm-value helpers -------------------------------------------

  private static List<Object> params(Object listArg) {
    return ((ElmList) Thunk.resolve(listArg)).toJava();
  }

  private static ElmData value(String ctor, Object... args) {
    return new ElmData(ctor, args);
  }

  private static ElmData ok(Object v) {
    return new ElmData("Ok", new Object[] {v});
  }

  private static ElmData err(String message) {
    return new ElmData("Err", new Object[] {message});
  }

  private static String str(Object o) {
    return (String) Thunk.resolve(o);
  }

  private static long asLong(Object o) {
    return ((Number) Thunk.resolve(o)).longValue();
  }

  private static double asDouble(Object o) {
    return ((Number) Thunk.resolve(o)).doubleValue();
  }

  private static boolean asBool(Object o) {
    return (Boolean) Thunk.resolve(o);
  }

  private static String message(SQLException e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }
}
