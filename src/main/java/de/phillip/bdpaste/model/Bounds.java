package de.phillip.bdpaste.model;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Where a part actually ends up once its matrix is applied.
 *
 * <p>A display entity is not a point: a block display fills the unit cube from its origin, an
 * item or text display hangs centred on it. Taking the matrix translation for the position is
 * off by up to a whole block, and by more once the model is scaled - which is why anything that
 * needs a real edge goes through here.</p>
 */
public final class Bounds {

    private Bounds() {
    }

    /**
     * A min/max pair, and the few things worth asking of one.
     *
     * <p>Mutable on purpose: {@link #expand} grows it part by part, which is how every box in
     * here gets built.</p>
     */
    public record Box(Vector3f min, Vector3f max) {

        /** A box that has taken nothing in yet. */
        public static Box empty() {
            return new Box(
                    new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE),
                    new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE));
        }

        /** True while nothing has been put in it. */
        public boolean isEmpty() {
            return min.x > max.x;
        }

        public Vector3f size() {
            return new Vector3f(max).sub(min);
        }

        /**
         * This box after a transform: the eight corners moved, then boxed up again.
         *
         * <p>Corners rather than the two extremes, because a rotation turns a box into
         * something that is no longer a box, and only the corners tell you how far it now
         * reaches.</p>
         */
        public Box transformed(Matrix4f matrix) {
            Box out = empty();
            for (int corner = 0; corner < 8; corner++) {
                Vector4f point = new Vector4f(
                        (corner & 1) == 0 ? min.x : max.x,
                        (corner & 2) == 0 ? min.y : max.y,
                        (corner & 4) == 0 ? min.z : max.z,
                        1f);
                matrix.transform(point);
                out.min.min(new Vector3f(point.x, point.y, point.z));
                out.max.max(new Vector3f(point.x, point.y, point.z));
            }
            return out;
        }
    }

    /** Where this kind of display sits relative to its origin, before any transform. */
    private static float low(DisplayKind kind) {
        return kind == DisplayKind.BLOCK ? 0f : -0.5f;
    }

    private static float high(DisplayKind kind) {
        return kind == DisplayKind.BLOCK ? 1f : 0.5f;
    }

    /** Grows {@code min} and {@code max} to take in one posed part. */
    public static void expand(BdPart part, Matrix4f matrix, Vector3f min, Vector3f max) {
        float lo = low(part.kind());
        float hi = high(part.kind());

        for (int corner = 0; corner < 8; corner++) {
            Vector4f point = new Vector4f(
                    (corner & 1) == 0 ? lo : hi,
                    (corner & 2) == 0 ? lo : hi,
                    (corner & 4) == 0 ? lo : hi,
                    1f);
            matrix.transform(point);
            min.min(new Vector3f(point.x, point.y, point.z));
            max.max(new Vector3f(point.x, point.y, point.z));
        }
    }

    /** The highest point of one posed part. */
    public static double top(BdPart part, Matrix4f matrix) {
        float lo = low(part.kind());
        float hi = high(part.kind());

        double top = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            Vector4f point = new Vector4f(
                    (corner & 1) == 0 ? lo : hi,
                    (corner & 2) == 0 ? lo : hi,
                    (corner & 4) == 0 ? lo : hi,
                    1f);
            matrix.transform(point);
            top = Math.max(top, point.y);
        }
        return top;
    }
}
