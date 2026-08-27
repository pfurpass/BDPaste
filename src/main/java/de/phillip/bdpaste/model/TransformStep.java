package de.phillip.bdpaste.model;

import org.joml.Matrix4f;

/**
 * One link in the chain of matrices from the project root down to a part.
 *
 * <p>A part's world matrix is the product of its whole chain. Links that never change are
 * {@link Fixed} and get collapsed together at parse time, so a chain ends up as an
 * alternating sequence of one fixed block, one animated node, one fixed block, and so on.</p>
 */
public sealed interface TransformStep {

    /** The matrix of this link at the given tick. */
    Matrix4f at(double tick);

    /** A node whose transform never moves. */
    record Fixed(Matrix4f matrix) implements TransformStep {
        @Override
        public Matrix4f at(double tick) {
            return matrix;
        }
    }

    /** A collection that carries keyframes; the track replaces its static transform. */
    record Animated(BdAnimation animation) implements TransformStep {
        @Override
        public Matrix4f at(double tick) {
            return animation.at(tick);
        }
    }
}
