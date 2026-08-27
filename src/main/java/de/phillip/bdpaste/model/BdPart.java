package de.phillip.bdpaste.model;

import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;

/**
 * One display entity of a model, already flattened out of the BDEngine
 * collection tree.
 *
 * @param kind        which display entity to spawn
 * @param name        block state string, item id or raw text, depending on {@link #kind()}
 * @param matrix      transformation matrix relative to the model origin
 * @param blockLight  block light override, or {@code null} for "inherit"
 * @param skyLight    sky light override, or {@code null} for "inherit"
 * @param headTexture base64 texture value for player head items, or {@code null}
 * @param options     leftover display NBT (billboard, line_width, ...), never {@code null}
 * @param chains      one chain of matrices per animation track, running from the project root
 *                    down to this part. A chain is empty when nothing on the way here moves in
 *                    that track, in which case {@link #matrix()} is the whole story.
 */
public record BdPart(
        DisplayKind kind,
        String name,
        Matrix4f matrix,
        Integer blockLight,
        Integer skyLight,
        String headTexture,
        Map<String, Object> options,
        List<List<TransformStep>> chains
) {
    public BdPart {
        if (options == null) options = Map.of();
        chains = chains == null ? List.of() : chains.stream().map(List::copyOf).toList();
    }

    /**
     * The pose this part holds while nothing is playing.
     *
     * <p>For an animated part that is the first keyframe, not {@link #matrix()}.
     * {@code transforms} is wherever the author happened to leave the editor and can be far
     * from where the model belongs - on the farmer it is 2.97 blocks off, with the hay bale he
     * is meant to toss standing full size in his chest. BDEngine agrees: the
     * {@code create.mcfunction} of its own datapack export summons every part in the keyframe 0
     * pose, matching it to four decimals while {@code transforms} does not come close.</p>
     *
     * <p>Parts the first animation does not touch fall back to {@link #matrix()}, which is
     * exactly what the editor exports for them, and models without keyframes are unaffected.</p>
     */
    public Matrix4f restPose() {
        return matrixAt(0, 0);
    }

    /** Whether this part moves in any of the model's animations. */
    public boolean animated() {
        for (List<TransformStep> chain : chains) {
            if (!chain.isEmpty()) return true;
        }
        return false;
    }

    /** Whether this part moves in one particular animation. */
    public boolean animated(int track) {
        return track >= 0 && track < chains.size() && !chains.get(track).isEmpty();
    }

    /** World matrix at a point in time; the static one when this part does not move here. */
    public Matrix4f matrixAt(int track, double tick) {
        if (!animated(track)) return matrix;

        Matrix4f out = new Matrix4f();
        for (TransformStep step : chains.get(track)) {
            out.mul(step.at(tick));
        }
        return out;
    }
}
