package de.phillip.bdpaste.model;

import org.joml.Vector3f;

/**
 * One BDEngine keyframe.
 *
 * <p>{@code tick} is a server tick, and {@code rotation} is an XYZ Euler in <em>radians</em> -
 * both straight out of the editor, which stores three.js values. A keyframe replaces the
 * node's local position, rotation and scale outright; it is not a delta.</p>
 */
public record BdKeyframe(double tick, Vector3f position, Vector3f rotation, Vector3f scale) {
}
