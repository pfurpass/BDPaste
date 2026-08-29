package de.phillip.bdpaste.model;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Splits a transform into the four pieces Minecraft actually stores.
 *
 * <p>A display entity does not hold a matrix. It holds a translation, a rotation, a scale and a
 * second rotation, and rebuilds {@code T · R1 · S · R2} from them. Handing the server a matrix
 * instead means <em>it</em> has to work those four out, and that is where things went wrong:
 * a model whose parts kept their positions but lost every rotation and every scale is what a
 * failed decomposition leaves behind, and whether it fails depends on the server version.</p>
 *
 * <p>So it is done here instead, once, the same way everywhere. The server is then handed four
 * finished values and has nothing left to compute.</p>
 *
 * <p>The method is a one-sided Jacobi SVD on the upper-left 3×3: rotate pairs of columns until
 * they are orthogonal, at which point their lengths are the scale, the columns themselves are
 * the left rotation, and the accumulated rotations are the right one. It copes with the two
 * things BDEngine models really do - a scale of nearly zero, which is how a flat panel is made
 * out of a cube, and a mirrored part, which makes the determinant negative.</p>
 */
public final class Decompose {

    /** Sweeps are cheap and it converges in three or four; this is only a backstop. */
    private static final int MAX_SWEEPS = 32;

    /** Below this a column counts as having no length left to point anywhere. */
    private static final float TINY = 1.0E-20f;

    private Decompose() {
    }

    /** What a display entity stores, in the order it applies them. */
    public record Parts(Vector3f translation, Quaternionf leftRotation,
                        Vector3f scale, Quaternionf rightRotation) {
    }

    public static Parts of(Matrix4f matrix) {
        Vector3f translation = matrix.getTranslation(new Vector3f());

        // Columns of the upper-left 3x3, which is everything but the translation.
        Vector3f[] b = {
                new Vector3f(matrix.m00(), matrix.m01(), matrix.m02()),
                new Vector3f(matrix.m10(), matrix.m11(), matrix.m12()),
                new Vector3f(matrix.m20(), matrix.m21(), matrix.m22()),
        };
        Vector3f[] v = {
                new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1),
        };

        for (int sweep = 0; sweep < MAX_SWEEPS && !orthogonal(b); sweep++) {
            rotate(b, v, 0, 1);
            rotate(b, v, 0, 2);
            rotate(b, v, 1, 2);
        }

        float[] scale = new float[3];
        Vector3f[] u = new Vector3f[3];
        for (int i = 0; i < 3; i++) {
            scale[i] = b[i].length();
            u[i] = scale[i] > TINY ? new Vector3f(b[i]).div(scale[i]) : null;
        }
        fillGaps(u);

        // U and V have to come out as rotations, not reflections, because that is all a
        // quaternion can say. A sign moved into the scale keeps the product the same, and a
        // negative scale is something Minecraft stores quite happily.
        if (determinant(v) < 0) flip(v, scale, 2);
        if (determinant(u) < 0) flip(u, scale, smallest(scale));

        return new Parts(translation,
                quaternion(u),
                new Vector3f(scale[0], scale[1], scale[2]),
                quaternion(v).conjugate());
    }

    /** Are the columns already at right angles to one another? Then there is nothing to do. */
    private static boolean orthogonal(Vector3f[] b) {
        for (int p = 0; p < 3; p++) {
            for (int q = p + 1; q < 3; q++) {
                float dot = Math.abs(b[p].dot(b[q]));
                float lengths = b[p].length() * b[q].length();
                if (dot > 1.0E-9f && dot > 1.0E-7f * lengths) return false;
            }
        }
        return true;
    }

    /** One Jacobi step: turn columns p and q until they are orthogonal to each other. */
    private static void rotate(Vector3f[] b, Vector3f[] v, int p, int q) {
        float gamma = b[p].dot(b[q]);
        if (Math.abs(gamma) < TINY) return;

        float alpha = b[p].lengthSquared();
        float beta = b[q].lengthSquared();
        float zeta = (beta - alpha) / (2f * gamma);
        // The smaller root, which is the rotation under 45 degrees - the stable one.
        float t = Math.signum(zeta) / (Math.abs(zeta) + (float) Math.sqrt(1f + zeta * zeta));
        if (zeta == 0f) t = 1f;
        float c = 1f / (float) Math.sqrt(1f + t * t);
        float s = c * t;

        turn(b, p, q, c, s);
        turn(v, p, q, c, s);
    }

    private static void turn(Vector3f[] m, int p, int q, float c, float s) {
        Vector3f cp = new Vector3f(m[p]);
        Vector3f cq = new Vector3f(m[q]);
        m[p].set(cp).mul(c).sub(new Vector3f(cq).mul(s));
        m[q].set(cp).mul(s).add(new Vector3f(cq).mul(c));
    }

    /**
     * Gives a direction to any column whose scale collapsed to nothing.
     *
     * <p>A part squashed flat has a column of length zero, and a zero vector points nowhere -
     * but a rotation still needs three axes. The missing ones are filled with whatever is left
     * over, which is exactly the freedom a zero scale gives you.</p>
     */
    private static void fillGaps(Vector3f[] u) {
        int missing = 0;
        for (Vector3f column : u) if (column == null) missing++;
        if (missing == 0) return;

        if (missing == 3) {
            u[0] = new Vector3f(1, 0, 0);
            u[1] = new Vector3f(0, 1, 0);
            u[2] = new Vector3f(0, 0, 1);
            return;
        }
        if (missing == 2) {
            int known = u[0] != null ? 0 : (u[1] != null ? 1 : 2);
            Vector3f any = Math.abs(u[known].x) < 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
            Vector3f second = new Vector3f(u[known]).cross(any).normalize();
            for (int i = 0; i < 3; i++) {
                if (u[i] == null) {
                    u[i] = second;
                    break;
                }
            }
        }
        // One left: it is the cross product of the other two, up to a sign that the
        // determinant fix afterwards settles.
        int gap = u[0] == null ? 0 : (u[1] == null ? 1 : 2);
        int a = (gap + 1) % 3;
        int b = (gap + 2) % 3;
        u[gap] = new Vector3f(u[a]).cross(u[b]).normalize();
    }

    private static int smallest(float[] scale) {
        int best = 0;
        for (int i = 1; i < 3; i++) {
            if (Math.abs(scale[i]) < Math.abs(scale[best])) best = i;
        }
        return best;
    }

    /** Turns a reflection into a rotation by moving the sign into the scale. */
    private static void flip(Vector3f[] columns, float[] scale, int index) {
        columns[index].negate();
        scale[index] = -scale[index];
    }

    private static float determinant(Vector3f[] m) {
        return new Matrix3f(m[0], m[1], m[2]).determinant();
    }

    private static Quaternionf quaternion(Vector3f[] columns) {
        return new Matrix3f(columns[0], columns[1], columns[2])
                .getNormalizedRotation(new Quaternionf());
    }
}
