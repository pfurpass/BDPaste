package de.phillip.bdpaste.parse;

import org.joml.Matrix4f;

import java.util.List;

/** Row-major 16-float lists (both BDEngine and vanilla NBT use that layout) to JOML matrices. */
public final class Matrices {

    private Matrices() {
    }

    public static Matrix4f fromRowMajor(float[] v) {
        Matrix4f m = new Matrix4f();
        if (v == null || v.length != 16) return m;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                m.set(col, row, v[row * 4 + col]);
            }
        }
        return m;
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
