package de.phillip.bdpaste.place;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The transform a player applies on top of a model while placing it.
 *
 * <p>Kept free of any server API so the maths can be tested on its own.</p>
 */
public final class UserTransform {

    private UserTransform() {
    }

    /**
     * Rotates and scales around {@code pivot}, and puts {@code pivot} onto the anchor position.
     *
     * <p>The trailing {@code translate(-pivot)} is the part that matters: it makes the pivot the
     * fixed point of the whole transform, so the model stays under the crosshair no matter how
     * it is scaled or turned. Without it, scaling grows the model around a point that is not the
     * anchor, and the model visibly drifts away from the cursor.</p>
     */
    public static Matrix4f of(Vector3f pivot, float yawDegrees, float pitchDegrees,
                              float rollDegrees, float scale) {
        return new Matrix4f()
                .rotateY((float) Math.toRadians(yawDegrees))
                .rotateX((float) Math.toRadians(pitchDegrees))
                .rotateZ((float) Math.toRadians(rollDegrees))
                .scale(scale)
                .translate(new Vector3f(pivot).negate());
    }
}
