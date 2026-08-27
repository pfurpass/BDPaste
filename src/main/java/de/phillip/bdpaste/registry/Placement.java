package de.phillip.bdpaste.registry;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

import java.util.UUID;

/** One model that was placed into the world. */
public record Placement(
        UUID id,
        /** Display name of the model, e.g. "purple gogzilla". */
        String model,
        /** The library file it was loaded from, e.g. "gogzilla" - needed to load it again. */
        String source,
        UUID world,
        double x, double y, double z,
        float yaw, float pitch, float roll,
        float scale,
        /** The manual nudge the player dialled in; already part of x/y/z, kept so a move can reapply it. */
        double offsetX, double offsetY, double offsetZ,
        int parts,
        boolean animating,
        double animationSpeed,
        /** Which baked track to play, or empty for the first one. */
        String animationName,
        /** MiniMessage source of the floating name over the model; empty for none. */
        String label,
        /**
         * Blocks to shift the label up or down from where it lands on its own.
         *
         * <p>How tall a model looks cannot be read off its parts: an item display is bounded by
         * a unit cube, but how much of that cube the item fills is up to the item's own model -
         * a player head uses half of it. So the automatic height is an estimate, and this is
         * what corrects it, per model, once.</p>
         */
        double labelOffset,
        /**
         * Whether this model may be clicked - {@code /bdpaste hitbox on|off}.
         *
         * <p>On record rather than left to whether an interaction entity happens to exist,
         * because the boxes are re-laid whenever the model file says they should be, and
         * something that gets rebuilt cannot also be what remembers that you did not want
         * it.</p>
         */
        boolean clickable,
        UUID owner,
        String ownerName,
        long placedAt,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ
) {

    public Placement {
        // Optional throughout, and read from a yml that may predate the field.
        if (label == null) label = "";
    }

    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    public Location location() {
        World w = bukkitWorld();
        return w == null ? null : new Location(w, x, y, z);
    }

    /**
     * Whether the stored box holds anything that is not a finite number.
     *
     * <p>It should never happen - the commands refuse to write one and the registry drops such
     * records when it loads them - but a single one used to be enough to take clicking down for
     * the whole server, so everything that reads the box checks first.</p>
     */
    private boolean brokenBounds() {
        return !finite(minX) || !finite(minY) || !finite(minZ)
                || !finite(maxX) || !finite(maxY) || !finite(maxZ);
    }

    /**
     * Squared distance from a point to the middle of this model's box.
     *
     * <p>Allocation-free on purpose - it is the first thing every click asks of every model on
     * the server, so it must not build a {@link Location} or a {@code Vector} to answer.</p>
     */
    public double distanceSquaredFrom(double px, double py, double pz) {
        double cx, cy, cz;
        if (brokenBounds()) {
            cx = finite(x) ? x : 0;
            cy = finite(y) ? y : 0;
            cz = finite(z) ? z : 0;
        } else {
            cx = (minX + maxX) * 0.5;
            cy = (minY + maxY) * 0.5;
            cz = (minZ + maxZ) * 0.5;
        }
        double dx = cx - px, dy = cy - py, dz = cz - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Half the diagonal of the box: nothing further than this from its middle is inside it. */
    public double boundsRadius() {
        if (brokenBounds()) return 0;
        double dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        // The 0.15 that bounds() pads with has to be in here too, or the cheap check could
        // reject something the real one would have hit.
        return 0.5 * Math.sqrt(dx * dx + dy * dy + dz * dz) + 0.15 * Math.sqrt(3);
    }

    /**
     * World-space bounds, padded a little so a click near an edge still hits.
     *
     * <p>Guarded, because {@link BoundingBox} refuses to hold anything that is not a finite
     * number and throws if you try. This is asked for once per model on every arm swing of
     * every player, so one broken record would not just break itself - it would throw out of
     * the middle of the loop and stop every other model on the server being clickable, several
     * times a second, for as long as the record existed.</p>
     *
     * <p>A broken one collapses to a point at the model's own position instead, and
     * {@link #distanceSquaredFrom} and {@link #boundsRadius} agree with it, so the model can
     * still be pointed at and removed where it stands rather than becoming furniture.</p>
     */
    public BoundingBox bounds() {
        if (brokenBounds()) {
            double ax = finite(x) ? x : 0, ay = finite(y) ? y : 0, az = finite(z) ? z : 0;
            return new BoundingBox(ax, ay, az, ax, ay, az).expand(0.15);
        }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ).expand(0.15);
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    public String shortId() {
        return id.toString().substring(0, 8);
    }
}
