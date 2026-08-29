package de.phillip.bdpaste.label;

import de.phillip.bdpaste.BDPastePlugin;
import de.phillip.bdpaste.model.BdModel;
import de.phillip.bdpaste.model.BdPart;
import de.phillip.bdpaste.model.Bounds;
import de.phillip.bdpaste.place.Placements;
import de.phillip.bdpaste.registry.Placement;
import de.phillip.bdpaste.util.Msg;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The floating name over a placed model.
 *
 * <p>A plain {@code text_display} sitting above the model, billboarded so it turns to face
 * whoever is looking. The text is MiniMessage, so {@code <#ff8800>} and the rest of it work;
 * that source string is what gets stored, not the rendered component, so a label survives a
 * restart and can be shown back to whoever wants to edit it.</p>
 *
 * <p>Optional throughout. A model without one is exactly what it was before.</p>
 */
public final class Labels {

    private final BDPastePlugin plugin;

    /**
     * Placements whose label has been put where it belongs since the server came up.
     *
     * <p>A label can only be moved while its chunk is loaded, and at startup none of them are -
     * so the pass on enable reaches almost nothing and the labels of a world nobody has walked
     * into yet stay wherever the last version left them. This is what makes them settle as the
     * chunks come in, once each, instead of being reconsidered every couple of seconds.</p>
     */
    private final Set<UUID> settled = ConcurrentHashMap.newKeySet();

    private org.bukkit.scheduler.BukkitTask task;

    public Labels(BDPastePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweep, 100L, 40L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    /** Forgets what has settled, so everything is placed again - after a config change. */
    public void forget() {
        settled.clear();
    }

    private void sweep() {
        for (Placement placement : plugin.placed().all()) {
            String text = placement.label();
            if (text == null || text.isBlank()) continue;
            if (settled.contains(placement.id())) continue;

            Location at = placement.location();
            if (at == null || at.getWorld() == null) continue;
            if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;

            // Marked before the work, not after: the load is asynchronous, and a second sweep
            // starting the same one again would race it.
            settled.add(placement.id());
            refresh(placement);
        }
    }

    /**
     * The one part the label hangs off, and where it hangs relative to that part's origin.
     *
     * <p>Everything about the floating name comes out of this, standing still or moving, so the
     * two can never disagree and the label cannot jump the moment an animation starts.</p>
     *
     * <p>The part is whichever is the top of the <em>resting</em> model - on anything shaped
     * like a person, the head. Not the middle of the whole model: the farmer holds a pitchfork
     * out to one side, which drags that middle a quarter of a block off his head. And not
     * {@link Placement#bounds()} either, which covers everywhere the model reaches across its
     * whole animation and would put the name out in an empty field.</p>
     *
     * <p>What is remembered is an offset from the part's <em>origin</em> rather than from the
     * top of its box. A display's box is axis-aligned, so a part that merely turns makes its box
     * measure taller without anything actually moving - on the farmer by 0.32 blocks over one
     * animation, which is what the trembling was. An origin has no such thing.</p>
     */
    public record Ride(int part, Vector3f offset, Vector3f restOrigin) {

        /**
         * Works this out for a model already carrying its placement transform.
         *
         * <p>Split out from the plugin side of things on purpose: it is pure geometry, so it can
         * be run against the model files directly and checked.</p>
         *
         * @return null when the model has no parts at all
         */
        public static Ride of(BdModel model, Matrix4f user, double gap) {
            int best = -1;
            double bestTop = Double.NEGATIVE_INFINITY;
            Matrix4f bestPose = null;
            for (int i = 0; i < model.size(); i++) {
                BdPart part = model.parts().get(i);
                Matrix4f pose = new Matrix4f(user).mul(part.restPose());
                double top = Bounds.top(part, pose);
                if (top > bestTop) {
                    bestTop = top;
                    best = i;
                    bestPose = pose;
                }
            }
            if (best < 0) return null;

            Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
            Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
            Bounds.expand(model.parts().get(best), bestPose, min, max);

            Vector3f origin = new Vector3f(bestPose.m30(), bestPose.m31(), bestPose.m32());
            Vector3f offset = new Vector3f(
                    (min.x + max.x) * 0.5f,
                    (float) (max.y + gap),
                    (min.z + max.z) * 0.5f).sub(origin);

            return new Ride(best, offset, origin);
        }

        /** Where the label goes when the part it rides has its origin at {@code origin}. */
        public Vector3f at(Vector3f origin) {
            return new Vector3f(origin).add(offset);
        }

        /** Where the label goes while nothing is playing. */
        public Vector3f atRest() {
            return at(restOrigin);
        }
    }

    /** Works out what the label rides on this model; null when the model has no parts at all. */
    public Ride ride(Placement placement, BdModel model) {
        return Ride.of(model, Placements.userMatrix(plugin, placement, model), gap(placement));
    }

    /** Where the label belongs while the model is standing still. */
    private Location anchorOf(Placement placement, BdModel model) {
        World world = placement.bukkitWorld();
        if (world == null) return null;

        Ride ride = ride(placement, model);
        if (ride == null) return placement.location();

        Vector3f at = ride.atRest();
        return new Location(world,
                placement.x() + at.x, placement.y() + at.y, placement.z() + at.z);
    }

    /** How far the label floats over the model: the config gap plus this model's own nudge. */
    public double gap(Placement placement) {
        return plugin.settings().labelHeight + placement.labelOffset();
    }

    /**
     * Hunts down the label entity of a model.
     *
     * <p>Searched around the model's anchor over its whole reach rather than around where the
     * label ought to be, so it is still found after the height, the pose or the model itself
     * has changed under it.</p>
     */
    public Optional<TextDisplay> find(Placement placement) {
        Location at = placement.location();
        if (at == null || !at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            return Optional.empty();
        }

        BoundingBox box = placement.bounds();
        double reach = Math.max(4, Math.max(box.getWidthX(), Math.max(box.getHeight(), box.getWidthZ())))
                + Math.abs(gap(placement)) + 2;

        String wanted = placement.id().toString();
        for (Entity entity : at.getWorld().getNearbyEntities(at, reach, reach, reach)) {
            if (!(entity instanceof TextDisplay text)) continue;
            // A text display that is part of the model itself carries a part index; the label
            // is the one that does not.
            if (entity.getPersistentDataContainer().has(plugin.keyPartIndex(), PersistentDataType.INTEGER)) {
                continue;
            }
            String id = entity.getPersistentDataContainer()
                    .get(plugin.keyModelId(), PersistentDataType.STRING);
            if (wanted.equals(id)) return Optional.of(text);
        }
        return Optional.empty();
    }

    /**
     * Brings the label in line with what the placement says it should be: spawns it, rewrites
     * it, moves it, or takes it away when the text is empty.
     *
     * <p>Placing it needs the model file, to work out where the top of the model actually is,
     * so that part happens once the library has handed it over.</p>
     */
    public void refresh(Placement placement) {
        settled.add(placement.id());
        String text = placement.label();
        if (text == null || text.isBlank()) {
            remove(placement);
            return;
        }

        plugin.library().loadAsync(placement.source(), model -> place(placement, model), error -> {
            // No model file, no idea where its top is. Better to leave the label where it was
            // than to drop it somewhere arbitrary.
        });
    }

    private void place(Placement placement, BdModel model) {
        Location at = anchorOf(placement, model);
        if (at == null) return;
        if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;

        Optional<TextDisplay> existing = find(placement);
        if (existing.isPresent()) {
            TextDisplay display = existing.get();
            apply(display, placement);
            display.teleport(at);
            return;
        }
        at.getWorld().spawn(at, TextDisplay.class, display -> apply(display, placement));
    }

    private void apply(TextDisplay display, Placement placement) {
        // MiniMessage is lenient: a tag it does not recognise stays on screen as plain text
        // rather than throwing, so there is nothing here that can fail on bad markup.
        display.text(Msg.of(placement.label()));
        display.setBillboard(Display.Billboard.CENTER);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setSeeThrough(plugin.settings().labelSeeThrough);
        display.setShadowed(plugin.settings().labelShadow);
        display.setDefaultBackground(false);
        display.setBackgroundColor(Color.fromARGB(plugin.settings().labelBackground));
        display.setViewRange((float) plugin.settings().labelViewRange);

        float scale = (float) plugin.settings().labelScale;
        de.phillip.bdpaste.spawn.ModelSpawner.pose(display, new Matrix4f().scale(scale));

        // Moved by the animation player while the model is running, so it needs to glide.
        display.setTeleportDuration(Math.max(1, plugin.settings().animationInterval));
        display.setPersistent(true);
        display.addScoreboardTag(BDPastePlugin.TAG);
        display.getPersistentDataContainer()
                .set(plugin.keyModelId(), PersistentDataType.STRING, placement.id().toString());
        display.getPersistentDataContainer()
                .set(plugin.keyModelName(), PersistentDataType.STRING, placement.model());
    }

    /** Takes the label away. Returns whether there was one. */
    public boolean remove(Placement placement) {
        Optional<TextDisplay> existing = find(placement);
        existing.ifPresent(Entity::remove);
        return existing.isPresent();
    }

    /**
     * Puts every label back where it belongs, whether it has settled or not.
     *
     * <p>Only reaches models in loaded chunks; the rest are picked up by the sweep as their
     * chunks come in.</p>
     */
    public int refreshAll() {
        forget();
        int touched = 0;
        for (Placement placement : plugin.placed().all()) {
            if (placement.label() == null || placement.label().isBlank()) continue;
            Location at = placement.location();
            if (at == null || at.getWorld() == null) continue;
            if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
            refresh(placement);
            touched++;
        }
        return touched;
    }
}
