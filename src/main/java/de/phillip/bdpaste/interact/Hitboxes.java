package de.phillip.bdpaste.interact;

import de.phillip.bdpaste.BDPastePlugin;
import de.phillip.bdpaste.model.BdModel;
import de.phillip.bdpaste.place.Placements;
import de.phillip.bdpaste.registry.Placement;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The clickable shell around a placed model.
 *
 * <p>Display entities have no hitbox in vanilla - you cannot click one, and no amount of
 * plugin code changes that. So a model that wants to react to a click gets vanilla
 * {@code interaction} entities, which is exactly what that entity type is for: invisible,
 * weightless, and the only thing standing between a player and a
 * {@link de.phillip.bdpaste.api.BdModelClickEvent}.</p>
 *
 * <p>How many and how big is {@link HitboxLayout}'s business. Never one per part - a farmer is
 * 86 displays, and 86 hitboxes would make a mess of the entity count and the crosshair alike.</p>
 */
public final class Hitboxes {

    /** How far around the expected spot to look when hunting for boxes that belong to a model. */
    private static final double SEARCH = 1.5;

    /** Below this a resize is not worth a packet, and float noise is not worth a save. */
    private static final double SLACK = 1.0E-3;

    /** Models re-measured per sweep. Each one that moves writes the registry, so: not many. */
    private static final int PER_SWEEP = 8;

    private final BDPastePlugin plugin;

    /**
     * Models whose boxes have been checked against their model file since the server came up.
     *
     * <p>Same reason the labels have one: boxes can only be worked on while their chunk is
     * loaded, and at startup none of them are. This lets them settle as the chunks come in.</p>
     */
    private final Set<UUID> settled = ConcurrentHashMap.newKeySet();

    /**
     * Models known to be carrying boxes, so the click path does not have to go looking.
     *
     * <p>Only ever a shortcut: a miss falls back to searching for real. It goes stale if
     * somebody kills an interaction entity by hand, which {@code /bdpaste hitbox sync} fixes.</p>
     */
    private final Set<UUID> boxed = ConcurrentHashMap.newKeySet();

    private BukkitTask task;

    public Hitboxes(BDPastePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweep, 120L, 40L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    /** Forgets what has settled, so every model is measured again - after a config change. */
    public void forget() {
        settled.clear();
        boxed.clear();
    }

    // ------------------------------------------------------------------ settling

    /**
     * Brings models in line with their model file as their chunks come in.
     *
     * <p>How big a model is gets worked out when it is placed and then written to disk, so one
     * placed by an older version keeps that version's idea of its size for good. This is what
     * corrects those, and what puts the boxes where the current layout wants them, without
     * anybody having to run anything.</p>
     */
    private void sweep() {
        int budget = PER_SWEEP;
        for (Placement placement : plugin.placed().all()) {
            if (budget <= 0) return;
            if (settled.contains(placement.id())) continue;
            if (!loaded(placement.location())) continue;

            // Marked before the work, not after: the load is asynchronous, and a second sweep
            // starting the same one again would race it.
            settled.add(placement.id());
            budget--;
            remeasure(placement);
        }
    }

    private void remeasure(Placement placement) {
        plugin.library().loadAsync(placement.source(), model -> {
            // Back on the main thread, but a whole model load later - it may be gone by now.
            Placement current = plugin.placed().get(placement.id()).orElse(null);
            if (current == null) return;

            // Hunted down first, while the old size is still on record: that is what says where
            // to look, and an older version's boxes sit where that version thought the model
            // was - on the farmer 2.6 blocks out.
            List<Interaction> existing = findAll(current);

            plugin.placed().setBounds(current.id(), Placements.worldBox(plugin, current, model));
            Placement fixed = plugin.placed().get(current.id()).orElse(current);

            if (plugin.settings().interactionEnabled && fixed.clickable()) {
                apply(fixed, layout(fixed, model), existing);
            } else {
                existing.forEach(Entity::remove);
                boxed.remove(fixed.id());
            }
        }, error -> {
            // No model file, nothing to measure against. The boxes stay as they are.
        });
    }

    // -------------------------------------------------------------------- layout

    private List<HitboxLayout.Shell> layout(Placement placement, BdModel model) {
        return HitboxLayout.of(model,
                Placements.userMatrix(plugin, placement, model),
                plugin.settings().interactionPadding,
                plugin.settings().interactionMaxBoxes);
    }

    /** A shell in world space. */
    private Location worldAnchor(Placement placement, HitboxLayout.Shell shell) {
        World world = placement.bukkitWorld();
        if (world == null) return null;
        Vector3f at = shell.anchor();
        return new Location(world, placement.x() + at.x, placement.y() + at.y, placement.z() + at.z);
    }

    // -------------------------------------------------------------------- finding

    private boolean loaded(Location at) {
        return at != null && at.getWorld() != null
                && at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4);
    }

    /**
     * Every box belonging to a model.
     *
     * <p>Searched over the model's whole reach rather than around one expected spot, because
     * there is no one spot any more - a long model's boxes are spread down its length.</p>
     */
    public List<Interaction> findAll(Placement model) {
        Location at = model.location();
        if (!loaded(at)) return List.of();

        // Exactly as far as the model's own furthest corner, since every box of it lives
        // inside that. Half the width would do for a model that straddles its anchor and would
        // miss half the boxes of one placed on ORIGIN, which sits off to one side of it.
        BoundingBox box = model.bounds();
        double reach = SEARCH + Math.max(
                Math.max(Math.abs(box.getMinX() - at.getX()), Math.abs(box.getMaxX() - at.getX())),
                Math.max(
                        Math.max(Math.abs(box.getMinY() - at.getY()), Math.abs(box.getMaxY() - at.getY())),
                        Math.max(Math.abs(box.getMinZ() - at.getZ()), Math.abs(box.getMaxZ() - at.getZ()))));

        String wanted = model.id().toString();
        List<Interaction> found = new ArrayList<>();
        for (Entity entity : at.getWorld().getNearbyEntities(at, reach, reach, reach)) {
            if (!(entity instanceof Interaction interaction)) continue;
            String id = entity.getPersistentDataContainer()
                    .get(plugin.keyModelId(), PersistentDataType.STRING);
            if (wanted.equals(id)) found.add(interaction);
        }
        return found;
    }

    /**
     * Whether this model can be clicked.
     *
     * <p>On the click path, so it answers from what it already knows where it can. Searching for
     * real means scanning every entity around the model, and a player mining next to one swings
     * their arm four times a second.</p>
     */
    public boolean has(Placement model) {
        if (boxed.contains(model.id())) return true;
        if (findAll(model).isEmpty()) return false;
        boxed.add(model.id());
        return true;
    }

    // ------------------------------------------------------------------- writing

    /** Puts boxes around a model once its file has been read. */
    public void createAsync(Placement model) {
        plugin.library().loadAsync(model.source(),
                file -> apply(model, layout(model, file), findAll(model)),
                error -> {
                    // Without the file there is nothing to measure. Left without boxes, and the
                    // settling pass will try again after a restart.
                });
    }

    /**
     * Brings a model's boxes in line with what the layout asks for.
     *
     * <p>All or nothing: if anything is off, the lot is replaced. Matching up boxes one by one
     * would save a few entity spawns and cost far more than it saves in ways to get wrong.</p>
     *
     * @return whether anything changed
     */
    private boolean apply(Placement model, List<HitboxLayout.Shell> wanted, List<Interaction> existing) {
        if (!loaded(model.location())) return false;
        if (matches(model, wanted, existing)) return false;

        existing.forEach(Entity::remove);
        for (HitboxLayout.Shell shell : wanted) {
            Location at = worldAnchor(model, shell);
            if (at == null) continue;
            at.getWorld().spawn(at, Interaction.class, entity -> {
                entity.setInteractionWidth(shell.width());
                entity.setInteractionHeight(shell.height());
                // Responsive means the player sees their arm swing when they hit it, which is
                // the only feedback there is - the model itself cannot flash or knock back.
                entity.setResponsive(true);
                entity.setPersistent(true);
                entity.addScoreboardTag(BDPastePlugin.TAG);
                entity.getPersistentDataContainer()
                        .set(plugin.keyModelId(), PersistentDataType.STRING, model.id().toString());
                entity.getPersistentDataContainer()
                        .set(plugin.keyModelName(), PersistentDataType.STRING, model.model());
            });
        }
        if (wanted.isEmpty()) boxed.remove(model.id());
        else boxed.add(model.id());
        return true;
    }

    /** Is every box already the right size, in the right place, and are there the right number? */
    private boolean matches(Placement model, List<HitboxLayout.Shell> wanted, List<Interaction> existing) {
        if (wanted.size() != existing.size()) return false;

        List<Interaction> left = new ArrayList<>(existing);
        for (HitboxLayout.Shell shell : wanted) {
            Location at = worldAnchor(model, shell);
            if (at == null) return false;

            boolean paired = false;
            for (int i = 0; i < left.size(); i++) {
                Interaction box = left.get(i);
                if (Math.abs(box.getInteractionWidth() - shell.width()) > SLACK) continue;
                if (Math.abs(box.getInteractionHeight() - shell.height()) > SLACK) continue;
                if (box.getLocation().distanceSquared(at) > SLACK * SLACK) continue;
                left.remove(i);
                paired = true;
                break;
            }
            if (!paired) return false;
        }
        return true;
    }

    /** Adds or removes the boxes, whichever the argument asks for. */
    public void setEnabled(Placement model, boolean enabled) {
        if (enabled) {
            createAsync(model);
        } else {
            remove(model);
        }
    }

    /** Takes the boxes away again. Returns whether there were any. */
    public boolean remove(Placement model) {
        List<Interaction> existing = findAll(model);
        existing.forEach(Entity::remove);
        boxed.remove(model.id());
        return !existing.isEmpty();
    }

    /**
     * Has every model measured against its file again.
     *
     * <p>Nothing happens here and now: working out how big a model is means reading its file,
     * which cannot be answered on the spot. The settling pass works through them over the next
     * few seconds, and models in unloaded chunks wait for their chunk.</p>
     *
     * @return how many models were queued
     */
    public int syncAll() {
        forget();
        return plugin.placed().all().size();
    }
}
