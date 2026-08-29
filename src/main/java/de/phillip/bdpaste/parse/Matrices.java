package de.phillip.bdpaste.parse;

import org.joml.Matrix4f;

import java.util.List;

/** Row-major 16-float lists (both BDEngine and vanilla NBT use that layout) to JOML matrices. */
public final class Matrices {

    private Matrices() {
    }

    /**
     * Builds the matrix in one go, through the constructor, and then says so out loud.
     *
     * <p>This used to fill the matrix cell by cell with {@code set(col, row, value)}, and that
     * is a trap. A JOML matrix carries a bitmask of things it believes about itself - "I am the
     * identity", "I am only a translation" - and {@code mul} reads it to take shortcuts. In
     * JOML 1.10.5 that setter does not clear the bitmask, so a matrix built this way out of a
     * fresh identity still claimed to <em>be</em> the identity, and every multiplication after
     * it quietly threw it away. Whole models came out as a single cube, with every part holding
     * its position and having lost its rotation and its size.</p>
     *
     * <p>JOML 1.10.8 clears the flags and the same code was fine - which is exactly why this
     * only showed up on some servers. Paper bundles JOML, and 1.20.4 and 1.21.1 ship 1.10.5
     * while 26.2 ships 1.10.8. The version under the plugin is not the plugin's to choose.</p>
     *
     * <p>The constructor works out the flags itself on every version, and
     * {@link Matrix4f#determineProperties()} afterwards makes it true rather than merely
     * likely. JOML's own order is column-major, so it is fed column by column.</p>
     */
    public static Matrix4f fromRowMajor(float[] v) {
        if (v == null || v.length != 16) return new Matrix4f();
        return new Matrix4f(
                v[0], v[4], v[8], v[12],
                v[1], v[5], v[9], v[13],
                v[2], v[6], v[10], v[14],
                v[3], v[7], v[11], v[15])
                .determineProperties();
    }

    /** Reads a transformation from either a 16-float list or a decomposed compound. */
    public static Matrix4f fromNbt(Object value) {
        if (value instanceof List<?> list && list.size() == 16) {
            float[] v = new float[16];
            for (int i = 0; i < 16; i++) {
                Double d = Snbt.doubleOf(list.get(i), 0d);
                v[i] = d == null ? 0f : d.floatValue();
            }
            return fromRowMajor(v);
        }
        if (value instanceof java.util.Map<?, ?>) {
            var map = Snbt.mapOf(value);
            org.joml.Vector3f translation = vec3(map.get("translation"), 0f);
            org.joml.Vector3f scale = vec3(map.get("scale"), 1f);
            org.joml.Quaternionf left = quat(map.get("left_rotation"));
            org.joml.Quaternionf right = quat(map.get("right_rotation"));
            return new Matrix4f()
                    .translation(translation)
                    .rotate(left)
                    .scale(scale)
                    .rotate(right);
        }
        return new Matrix4f();
    }

    private static org.joml.Vector3f vec3(Object value, float fallback) {
        List<Object> list = Snbt.listOf(value);
        if (list.size() < 3) return new org.joml.Vector3f(fallback);
        return new org.joml.Vector3f(
                Snbt.doubleOf(list.get(0), (double) fallback).floatValue(),
                Snbt.doubleOf(list.get(1), (double) fallback).floatValue(),
                Snbt.doubleOf(list.get(2), (double) fallback).floatValue());
    }

    private static org.joml.Quaternionf quat(Object value) {
        List<Object> list = Snbt.listOf(value);
        if (list.size() == 4) {
            return new org.joml.Quaternionf(
                    Snbt.doubleOf(list.get(0), 0d).floatValue(),
                    Snbt.doubleOf(list.get(1), 0d).floatValue(),
                    Snbt.doubleOf(list.get(2), 0d).floatValue(),
                    Snbt.doubleOf(list.get(3), 1d).floatValue());
        }
        if (list.size() == 3) { // axis-angle style {angle, axis:[x,y,z]} is not used by BDEngine, but be safe
            return new org.joml.Quaternionf();
        }
        return new org.joml.Quaternionf();
    }
}
