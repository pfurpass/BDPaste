package de.phillip.bdpaste.model;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The keyframe track of a single BDEngine collection. */
public final class BdAnimation {

    private final List<BdKeyframe> keys;
    private final boolean stepped;
    private final Vector3f pivot;
    private final Vector3f parentPivot;

    public BdAnimation(List<BdKeyframe> keys, boolean stepped, Vector3f pivot, Vector3f parentPivot) {
        this.pivot = pivot == null ? new Vector3f() : new Vector3f(pivot);
        this.parentPivot = parentPivot == null ? new Vector3f() : new Vector3f(parentPivot);
        List<BdKeyframe> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.comparingDouble(BdKeyframe::tick));
        this.keys = List.copyOf(sorted);
        this.stepped = stepped;
    }

    public List<BdKeyframe> keys() {
        return keys;
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    /** Tick of the last keyframe, which is how long one loop takes. */
    public double length() {
        return keys.isEmpty() ? 0 : keys.get(keys.size() - 1).tick();
    }

    public Vector3f pivot() {
        return new Vector3f(pivot);
    }

    /**
     * The node's local matrix at {@code tick}.
     *
     * <p>Rotation is interpolated per Euler component, not by quaternion slerp. That is
     * deliberate: the editor does the very same thing for keyframes that store an Euler, and
     * only falls back to slerp for keyframes holding a raw quaternion. It also matters -
     * a propeller turning 6*PI has antipodal end quaternions, so slerp could not tell which
     * way round it should go, while a linear Euler ramp just keeps spinning.</p>
     */
    public Matrix4f at(double tick) {
        if (keys.isEmpty()) return new Matrix4f();
        if (keys.size() == 1) return matrixOf(keys.get(0));

        // Deliberately no wrapping here. Tracks of one model have wildly different ranges -
        // one group may run 0..10 while its neighbour runs 9..24 - so looping each track by
        // its own length would run the groups at different periods and tear the model apart.
        // The model loops as a whole; outside its own range a track holds its end pose.
        BdKeyframe first = keys.get(0);
        BdKeyframe last = keys.get(keys.size() - 1);
        if (tick <= first.tick()) return matrixOf(first);
        if (tick >= last.tick()) return matrixOf(last);

        BdKeyframe previous = first;
        for (int i = 1; i < keys.size(); i++) {
            BdKeyframe next = keys.get(i);
            if (tick <= next.tick()) {
                double span = next.tick() - previous.tick();
                double alpha = span <= 1.0E-9 ? 0 : (tick - previous.tick()) / span;
                return stepped ? matrixOf(previous) : blend(previous, next, (float) alpha);
            }
            previous = next;
        }
        return matrixOf(last);
    }

    private Matrix4f blend(BdKeyframe from, BdKeyframe to, float alpha) {
        Vector3f position = new Vector3f(from.position()).lerp(to.position(), alpha);
        Vector3f rotation = new Vector3f(from.rotation()).lerp(to.rotation(), alpha);
        Vector3f scale = new Vector3f(from.scale()).lerp(to.scale(), alpha);
        return compose(position, rotation, scale);
    }

    private Matrix4f matrixOf(BdKeyframe key) {
        return compose(key.position(), key.rotation(), key.scale());
    }

    /**
     * {@code T(parentPivot) * T(position) * Rx * Ry * Rz * S * T(-pivot)}.
     *
     * <p>The middle of that is how three.js composes an object matrix. The two pivot shifts
     * around it are BDEngine's, and they are not guesswork - the editor writes the very same
     * product into every node's static {@code transforms}, which is what let us read the rule
     * straight off the file:</p>
     *
     * <pre>transforms == T(parent.pivotCustom) * T(pos) * R * S * T(-pivotCustom)</pre>
     *
     * <p>It holds to the last digit on 41 of the farmer's 42 collections, the odd one out
     * being a node whose {@code defaultTransform} the editor left stale.</p>
     *
     * <p>At rest the two shifts cancel out between a parent and its children, which is why the
     * static import never needed them. Once a group animates they no longer do: the trailing
     * {@code T(-pivot)} is what makes rotation and scale happen around the pivot instead of
     * around the group origin, and the leading {@code T(parentPivot)} puts the group back into
     * the frame its parent hands down. Dropping that leading term is what used to scatter
     * groups nested inside other animated groups - a child of a group with a pivot came out
     * short by exactly that pivot.</p>
     */
    private Matrix4f compose(Vector3f position, Vector3f rotation, Vector3f scale) {
        return new Matrix4f()
                .translation(new Vector3f(parentPivot).add(position))
                .rotateXYZ(rotation.x, rotation.y, rotation.z)
                .scale(scale)
                .translate(new Vector3f(pivot).negate());
    }
}
