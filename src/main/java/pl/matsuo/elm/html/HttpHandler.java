package pl.matsuo.elm.html;

/**
 * A pluggable HTTP backend for the headless {@link Tea} runner. When set, every {@code Http.get}/
 * {@code Http.post}/{@code Http.request} command a program issues is turned into a {@link Request}
 * (method, url, body and the {@code expect} kind), handed to {@link #handle}, and the returned
 * {@link Response} is fed back into the program as an {@code Ok}/{@code Err} message — exactly as a
 * browser's network layer would.
 *
 * <p>This is the seam that lets a test drive a real backend: a handler can forward the request to an
 * in-process server (see {@link LiveHttpHandler}) so the UI's actual reads <b>and writes</b> take
 * effect, and later steps observe the mutated state. Contrast with {@link Tea#start(Object,
 * java.util.Map)}, whose canned url→body map cannot see the method or request body.
 */
@FunctionalInterface
public interface HttpHandler {

  /** Performs the request and returns the response to deliver to the Elm program. */
  Response handle(Request request);

  /**
   * An outgoing HTTP request. {@code expectKind} is the Elm {@code Expect} constructor ({@code
   * $Expect_String}/{@code $Expect_Json}/{@code $Expect_Whatever}), in case a handler wants to vary
   * behaviour by the expected response type.
   */
  record Request(String method, String url, String body, String expectKind) {}

  /**
   * An HTTP response. A {@code status} of 0 (or negative) signals a connection failure, delivered to
   * the program as {@code Http.NetworkError}; a non-2xx status becomes {@code Http.BadStatus}; a 2xx
   * status delivers {@code Ok} (the body decoded per the request's {@code expect}).
   */
  record Response(int status, String body) {

    /** A connection failure — delivered to the program as {@code Http.NetworkError}. */
    public static final Response NETWORK_ERROR = new Response(0, null);

    public static Response of(int status, String body) {
      return new Response(status, body);
    }

    public boolean isSuccess() {
      return status >= 200 && status < 300;
    }
  }
}
