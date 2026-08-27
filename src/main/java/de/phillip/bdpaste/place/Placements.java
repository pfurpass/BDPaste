package de.phillip.bdpaste.place;

import de.phillip.bdpaste.BDPastePlugin;
import de.phillip.bdpaste.model.BdModel;
import de.phillip.bdpaste.model.Bounds;
import de.phillip.bdpaste.registry.Placement;
import de.phillip.bdpaste.util.Settings;
import org.bukkit.util.BoundingBox;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Where a model ends up in the world once a placement's pose is applied to it.
 *
 * <p>Three places used to work this out for themselves - the placement session, the labels and
 * the animation player - and a fourth wanted to. Two of them disagreeing about where a model is
 * is the kind of bug that only shows up as "the thing is in the wrong place".</p>
 */
public final class Placements {

    private Placements() {
    }

    /** The transform a placement puts on every part of its model. */
    public static Matrix4f userMatrix(BDPastePlugin plugin, Placement placement, BdModel model) {
        Vector3f pivot = plugin.settings().pivot == Settings.Pivot.CENTER
                ? model.pivotCenter()
                : new Vector3f();
        return UserTransform.of(pivot,
                placement.yaw(), placement.pitch(), placement.roll(), placement.scale());
    }

    /** The model's resting box under a transform, still relative to the model's own anchor. */
    public static Bounds.Box localBox(BdModel model, Matrix4f user) {
        return model.restBox().transformed(user);
    }

    /**
     * The world-space box of a model that has already been placed.
     *
     * <p>This is what a player clicks and points at, so it is the model as it stands - see
     * {@link BdModel#restBox()} for why that is not the same as the box the registry inherited
     * from earlier versions.</p>
     */
    public static BoundingBox worldBox(BDPastePlugin plugin, Placement placement, BdModel model) {
        Bounds.Box box = localBox(model, userMatrix(plugin, placement, model));
        return new BoundingBox(
                placement.x() + box.min().x, placement.y() + box.min().y, placement.z() + box.min().z,
                placement.x() + box.max().x, placement.y() + box.max().y, placement.z() + box.max().z);
    }
}
