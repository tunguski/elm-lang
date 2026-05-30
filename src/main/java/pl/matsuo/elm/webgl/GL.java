package pl.matsuo.elm.webgl;

/**
 * Column-major 4x4 matrix and 3-vector math for the {@code Math.Matrix4}/{@code Math.Vector3}
 * builtins, matching elm-explorations/linear-algebra (and WebGL) conventions. Pure data: the GPU is
 * not involved, but the geometry/transform math is computed correctly so programs run faithfully.
 */
public final class GL {

  private GL() {}

  public static double[] identity() {
    return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  }

  public static double[] mul(double[] a, double[] b) {
    double[] r = new double[16];
    for (int col = 0; col < 4; col++) {
      for (int row = 0; row < 4; row++) {
        double sum = 0;
        for (int k = 0; k < 4; k++) {
          sum += a[k * 4 + row] * b[col * 4 + k];
        }
        r[col * 4 + row] = sum;
      }
    }
    return r;
  }

  public static double[] perspective(double fovyDegrees, double aspect, double near, double far) {
    double f = 1.0 / Math.tan(fovyDegrees * Math.PI / 360.0);
    double[] m = new double[16];
    m[0] = f / aspect;
    m[5] = f;
    m[10] = (far + near) / (near - far);
    m[11] = -1;
    m[14] = (2 * far * near) / (near - far);
    return m;
  }

  public static double[] lookAt(
      double ex, double ey, double ez,
      double cx, double cy, double cz,
      double ux, double uy, double uz) {
    double[] z = normalize(ex - cx, ey - cy, ez - cz);
    double[] x = normalize(
        uy * z[2] - uz * z[1], uz * z[0] - ux * z[2], ux * z[1] - uy * z[0]);
    double[] y = new double[] {
      z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0]
    };
    return new double[] {
      x[0], y[0], z[0], 0,
      x[1], y[1], z[1], 0,
      x[2], y[2], z[2], 0,
      -(x[0] * ex + x[1] * ey + x[2] * ez),
      -(y[0] * ex + y[1] * ey + y[2] * ez),
      -(z[0] * ex + z[1] * ey + z[2] * ez),
      1
    };
  }

  public static double[] makeRotate(double angle, double ax, double ay, double az) {
    double[] a = normalize(ax, ay, az);
    double x = a[0];
    double y = a[1];
    double z = a[2];
    double c = Math.cos(angle);
    double s = Math.sin(angle);
    double t = 1 - c;
    return new double[] {
      c + x * x * t, y * x * t + z * s, z * x * t - y * s, 0,
      x * y * t - z * s, c + y * y * t, z * y * t + x * s, 0,
      x * z * t + y * s, y * z * t - x * s, c + z * z * t, 0,
      0, 0, 0, 1
    };
  }

  public static double[] makeTranslate(double x, double y, double z) {
    double[] m = identity();
    m[12] = x;
    m[13] = y;
    m[14] = z;
    return m;
  }

  public static double[] makeScale(double x, double y, double z) {
    double[] m = identity();
    m[0] = x;
    m[5] = y;
    m[10] = z;
    return m;
  }

  /** Transforms a 3-vector by the matrix (with implicit w=1 and perspective divide). */
  public static double[] transform(double[] m, double x, double y, double z) {
    double tx = m[0] * x + m[4] * y + m[8] * z + m[12];
    double ty = m[1] * x + m[5] * y + m[9] * z + m[13];
    double tz = m[2] * x + m[6] * y + m[10] * z + m[14];
    double tw = m[3] * x + m[7] * y + m[11] * z + m[15];
    if (tw != 0 && tw != 1) {
      tx /= tw;
      ty /= tw;
      tz /= tw;
    }
    return new double[] {tx, ty, tz};
  }

  private static double[] normalize(double x, double y, double z) {
    double len = Math.sqrt(x * x + y * y + z * z);
    if (len == 0) {
      return new double[] {0, 0, 0};
    }
    return new double[] {x / len, y / len, z / len};
  }
}
