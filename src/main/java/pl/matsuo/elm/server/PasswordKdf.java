package pl.matsuo.elm.server;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password key-derivation for the {@code Backend} server library, exposed to H2 as a user-defined
 * function ({@code CREATE ALIAS SFA_KDF FOR 'pl.matsuo.elm.server.PasswordKdf.hash'}). Uses
 * <b>PBKDF2-HMAC-SHA256</b> (a standardized, HMAC-based KDF — far stronger than iterated raw SHA) at a
 * high iteration count, so a leaked password column can't be brute-forced cheaply. JDK-native, no
 * extra dependency. The salt is a per-user random string; the derived key is stored as VARBINARY.
 */
public final class PasswordKdf {

  private PasswordKdf() {}

  /** OWASP-class work factor for PBKDF2-HMAC-SHA256; ~tens of ms per call, fine for login/register. */
  private static final int ITERATIONS = 210_000;

  private static final int KEY_BITS = 256;

  /** Derives the password hash. Called by H2 per row (one row per login/register). */
  public static byte[] hash(String password, String salt) {
    if (password == null || salt == null) {
      throw new IllegalArgumentException("password and salt are required");
    }
    try {
      PBEKeySpec spec =
          new PBEKeySpec(
              password.toCharArray(),
              salt.getBytes(StandardCharsets.UTF_8),
              ITERATIONS,
              KEY_BITS);
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (Exception e) {
      throw new RuntimeException("PBKDF2 key derivation failed: " + e.getMessage(), e);
    }
  }
}
