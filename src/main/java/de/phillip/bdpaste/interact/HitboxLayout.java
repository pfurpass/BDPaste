package de.phillip.bdpaste.interact;

import de.phillip.bdpaste.model.BdModel;
import de.phillip.bdpaste.model.BdPart;
import de.phillip.bdpaste.model.Bounds;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * How to wrap a model in clickable boxes.
 *
 * <p>A vanilla {@code interaction} entity has <em>one</em> width for both horizontal axes, so
 * every box comes out square whatever shape the model is. Around anything long and thin that is
 * mostly air: Stitch is 2.57 by 1.12 blocks, and a single square around him is a 2.57 by 2.57
 * footprint - more than twice what he takes up, and all of it clickable grass.</p>
 *
 * <p>So a long model gets several boxes laid down its length instead of one big one, each grown
 * to hold the parts that fall into its stretch - which keeps them tight and lets a gap in the
 * middle of a model stay a gap. Every number of boxes up to the configured limit is tried, and
 * the single box is one of the candidates, so this can only ever tighten the fit and never
 * loosen it.</p>
 *
 * <p>Pure geometry, in the model's own space: add the placement's position for world space.</p>
 */
public final class HitboxLayout {

    /** Interaction entities are not meant to be enormous, and a huge one is unclickable anyway. */
    public static final float MAX_SIZE = 64f;

    /** Smaller than this and there is nothing left to aim at. */
    private static final float MIN_SIZE = 0.25f;

    private HitboxLayout() {
    }

    /**
     * One clickable box.
     *
     * @param anchor where the entity stands - the middle of the footprint, at its bottom
     * @param width  the footprint, square because that is all the entity can be
     */
    public record Shell(Vector3f anchor, float width, float height) {

        public double volume() {
            return (double) width * width * height;
        }
    }

    /**
     * Works out the boxes for a model.
     *
     * @param user     the placement's own rotation and scale
     * @param padding  blocks of slack around each box, on every side
     * @param maxBoxes how many boxes one model may be split into; 1 keeps it to a single box
     */
    public static List<Shell> of(BdModel model, Matrix4f user, double padding, int maxBoxes) {
        List<Bounds.Box> parts = new ArrayList<>();
        Bounds.Box whole = Bounds.Box.empty();
        for (BdPart part : model.parts()) {
            Bounds.Box one = Bounds.Box.empty();
            Bounds.expand(part, new Matrix4f(user).mul(part.restPose()), one.min(), one.max());
            parts.add(one);
            whole.min().min(one.min());
            whole.max().max(one.max());
        }
        if (parts.isEmpty()) {
            return List.of(new Shell(new Vector3f(), MIN_SIZE, MIN_SIZE));
        }

        Shell single = shellOf(whole, padding);

        // Every count is tried and the cheapest kept. Cheap to do - a handful of passes over
        // the parts - and it means no rule of thumb has to be right about how a model is
        // shaped. The single box is in the running, so this can only tighten the fit.
        List<Shell> best = List.of(single);
        double bestCost = single.volume();
        for (int count = 2; count <= Math.max(1, maxBoxes); count++) {
            List<Shell> tried = split(parts, whole, padding, count);
            if (tried.isEmpty()) continue;
            double cost = 0;
            for (Shell shell : tried) cost += shell.volume();
            if (cost < bestCost) {
                bestCost = cost;
                best = tried;
            }
        }
        return best;
    }

    /**
     * Lays {@code count} boxes down the long horizontal axis, one per stretch of it.
     *
     * <p>A part goes into the stretch its middle falls in, and that box is then grown to hold
     * all of it - so every part ends up completely inside exactly one box and you can never
     * click through a model into the space behind it.</p>
     *
     * <p>Putting a part into <em>every</em> stretch it touches sounds safer and is much worse:
     * a part sticking a little way over a boundary drags that box out on both sides, and on a
     * line of dancers whose parts are wider than the stretches, every box grew to three times
     * its stretch and the whole split came out bigger than one box around the lot.</p>
     */
    private static List<Shell> split(List<Bounds.Box> parts, Bounds.Box whole,
                                     double padding, int count) {
        if (count <= 1) return List.of();

        Vector3f size = whole.size();
        boolean alongX = size.x >= size.z;
        float length = alongX ? size.x : size.z;
        if (length <= MIN_SIZE) return List.of();

        float from = alongX ? whole.min().x : whole.min().z;
        float step = length / count;

        List<Bounds.Box> slabs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) slabs.add(Bounds.Box.empty());

        for (Bounds.Box part : parts) {
            float middle = alongX
                    ? (part.min().x + part.max().x) * 0.5f
                    : (part.min().z + part.max().z) * 0.5f;
            Bounds.Box slab = slabs.get(clamp((int) Math.floor((middle - from) / step), count));
            slab.min().min(part.min());
            slab.max().max(part.max());
        }

        List<Shell> shells = new ArrayList<>(count);
        for (Bounds.Box slab : slabs) {
            // A stretch with nothing in it needs no box - that is how a gap in the middle of a
            // model stays a gap you can look through.
            if (!slab.isEmpty()) shells.add(shellOf(slab, padding));
        }
        return shells;
    }

    private static int clamp(int index, int count) {
        return Math.max(0, Math.min(count - 1, index));
    }

    /** One box around a stretch of model, squared off and padded. */
    private static Shell shellOf(Bounds.Box box, double padding) {
        Vector3f size = box.size();
        float width = clampSize(Math.max(size.x, size.z) + 2 * padding);
        float height = clampSize(size.y + 2 * padding);
        Vector3f anchor = new Vector3f(
                (box.min().x + box.max().x) * 0.5f,
                (float) (box.min().y - padding),
                (box.min().z + box.max().z) * 0.5f);
        return new Shell(anchor, width, height);
    }

    private static float clampSize(double value) {
        return (float) Math.min(MAX_SIZE, Math.max(MIN_SIZE, value));
    }
}
