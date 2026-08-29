package de.phillip.bdpaste.registry;

import de.phillip.bdpaste.BDPastePlugin;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Keeps track of placed models so they can be listed, found and taken back out again. */
public final class PlacedModels {

    /** How often the registry is written out, in ticks, and only then if it changed. */
    private static final long WRITE_EVERY = 40L;

    private final BDPastePlugin plugin;
    private final File file;
    private final Map<UUID, Placement> placements = new LinkedHashMap<>();
    private final Map<UUID, Deque<UUID>> undoStacks = new LinkedHashMap<>();

    /** Guards the file itself, since the writing happens off the main thread. */
    private final Object writeLock = new Object();

    private volatile boolean dirty;
    private org.bukkit.scheduler.BukkitTask writer;

    /**
     * Which snapshot each write is carrying, so an older one cannot land on top of a newer.
     *
     * <p>Two writes can be in flight at once if a disk is slow, and the pool gives no promise
     * about which finishes first. Without this the file could end up one version behind and
     * stay there until the next change.</p>
     */
    private final java.util.concurrent.atomic.AtomicLong stamped = new java.util.concurrent.atomic.AtomicLong();
    private long written;

    public PlacedModels(BDPastePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "placements.yml");
    }

    public void start() {
        writer = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::flush, WRITE_EVERY, WRITE_EVERY);
    }

    /** Stops the writer and gets the last changes down, because there is no later. */
    public void stop() {
        if (writer != null) writer.cancel();
        saveNow();
    }

    // ------------------------------------------------------------- persistence

    public void load() {
        placements.clear();
        if (!file.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("placements");
        if (root == null) return;

        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            try {
                UUID id = UUID.fromString(key);
                placements.put(id, new Placement(
                        id,
                        s.getString("model", "?"),
                        s.getString("source", s.getString("model", "?")),
                        UUID.fromString(s.getString("world", "")),
                        s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                        (float) s.getDouble("yaw"), (float) s.getDouble("pitch"), (float) s.getDouble("roll"),
                        (float) s.getDouble("scale", 1.0),
                        s.getDouble("offsetX"), s.getDouble("offsetY"), s.getDouble("offsetZ"),
                        s.getInt("parts"),
                        s.getBoolean("animating", false),
                        s.getDouble("animationSpeed", 1.0),
                        s.getString("animationName", ""),
                        s.getString("label", ""),
                        s.getDouble("labelOffset", 0.0),
                        s.getBoolean("clickable", true),
                        s.getString("clickCommand", ""),
                        s.getString("owner") == null ? null : UUID.fromString(s.getString("owner")),
                        s.getString("ownerName", "?"),
                        s.getLong("placedAt"),
                        s.getDouble("minX"), s.getDouble("minY"), s.getDouble("minZ"),
                        s.getDouble("maxX"), s.getDouble("maxY"), s.getDouble("maxZ")));
            } catch (IllegalArgumentException ex) {
                plugin.getSLF4JLogger().warn("Skipping unreadable placement entry {}", key);
            }
        }

        // A file somebody edited by hand can hold anything, and a NaN in a position or a scale
        // spreads: the transform goes to NaN, so does the bounding box, and a box of NaN can
        // never be hit by a ray - the model would sit there unlookable and unremovable.
        int broken = 0;
        for (UUID id : List.copyOf(placements.keySet())) {
            if (!sane(placements.get(id))) {
                placements.remove(id);
                broken++;
            }
        }
        if (broken > 0) {
            plugin.getSLF4JLogger().warn(
                    "Dropped {} placement(s) holding impossible numbers - their entities are "
                            + "still in the world, /bdpaste cleanup <radius> clears them", broken);
        }
        plugin.getSLF4JLogger().info("Loaded {} placed model(s)", placements.size());
    }

    /** Whether every number on a placement is a real one. */
    private static boolean sane(Placement p) {
        return finite(p.x()) && finite(p.y()) && finite(p.z())
                && finite(p.yaw()) && finite(p.pitch()) && finite(p.roll())
                && finite(p.scale()) && p.scale() > 0
                && finite(p.offsetX()) && finite(p.offsetY()) && finite(p.offsetZ())
                && finite(p.animationSpeed()) && finite(p.labelOffset())
                && finite(p.minX()) && finite(p.minY()) && finite(p.minZ())
                && finite(p.maxX()) && finite(p.maxY()) && finite(p.maxZ());
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    /**
     * Notes that something changed. The file follows within a couple of seconds.
     *
     * <p>Not written here and now, because writing means serialising every placement on the
     * server and pushing it to disk - on the main thread, in the middle of whatever asked for
     * it. That is invisible with ten models and a stutter with a few thousand, and the settling
     * pass alone asks for it eight times every two seconds after a restart.</p>
     */
    public void save() {
        dirty = true;
    }

    /** Writes now, on this thread, if there is anything to write. For shutdown. */
    public void saveNow() {
        if (!dirty) return;
        dirty = false;
        write(List.copyOf(placements.values()));
    }

    private void flush() {
        if (!dirty) return;
        dirty = false;
        // The snapshot is taken here, on the main thread; Placement is a record, so what goes
        // to the other thread cannot change under it.
        List<Placement> snapshot = List.copyOf(placements.values());
        long stamp = stamped.incrementAndGet();
        plugin.getServer().getScheduler()
                .runTaskAsynchronously(plugin, () -> write(snapshot, stamp));
    }

    private void write(List<Placement> snapshot) {
        write(snapshot, stamped.incrementAndGet());
    }

    private void write(List<Placement> snapshot, long stamp) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Placement p : snapshot) {
            String base = "placements." + p.id();
            yaml.set(base + ".model", p.model());
            yaml.set(base + ".source", p.source());
            yaml.set(base + ".world", p.world().toString());
            yaml.set(base + ".x", p.x());
            yaml.set(base + ".y", p.y());
            yaml.set(base + ".z", p.z());
            yaml.set(base + ".yaw", p.yaw());
            yaml.set(base + ".pitch", p.pitch());
            yaml.set(base + ".roll", p.roll());
            yaml.set(base + ".scale", p.scale());
            yaml.set(base + ".offsetX", p.offsetX());
            yaml.set(base + ".offsetY", p.offsetY());
            yaml.set(base + ".offsetZ", p.offsetZ());
            yaml.set(base + ".parts", p.parts());
            yaml.set(base + ".animating", p.animating());
            yaml.set(base + ".animationSpeed", p.animationSpeed());
            yaml.set(base + ".animationName", p.animationName());
            yaml.set(base + ".label", p.label());
            yaml.set(base + ".labelOffset", p.labelOffset());
            yaml.set(base + ".clickable", p.clickable());
            yaml.set(base + ".clickCommand", p.clickCommand());
            yaml.set(base + ".owner", p.owner() == null ? null : p.owner().toString());
            yaml.set(base + ".ownerName", p.ownerName());
            yaml.set(base + ".placedAt", p.placedAt());
            yaml.set(base + ".minX", p.minX());
            yaml.set(base + ".minY", p.minY());
            yaml.set(base + ".minZ", p.minZ());
            yaml.set(base + ".maxX", p.maxX());
            yaml.set(base + ".maxY", p.maxY());
            yaml.set(base + ".maxZ", p.maxZ());
        }
        synchronized (writeLock) {
            // Something newer already went down; this one would only undo it.
            if (stamp < written) return;
            written = stamp;
            try {
                yaml.save(file);
            } catch (IOException ex) {
                plugin.getSLF4JLogger().error("Could not write placements.yml", ex);
                // Left marked, so the next pass tries again rather than losing the change.
                dirty = true;
            }
        }
    }

    // ------------------------------------------------------------------- state

    public void add(Placement placement) {
        placements.put(placement.id(), placement);
        if (placement.owner() != null) {
            undoStacks.computeIfAbsent(placement.owner(), k -> new ArrayDeque<>()).push(placement.id());
        }
        save();

        if (plugin.settings().interactionEnabled && placement.clickable() && plugin.hitboxes() != null) {
            // Needs the model file to know how to wrap it, so the boxes land a tick or two late.
            plugin.hitboxes().createAsync(placement);
        }
        if (plugin.labels() != null) plugin.labels().refresh(placement);
        plugin.getServer().getPluginManager().callEvent(
                new de.phillip.bdpaste.api.BdModelPlaceEvent(placement));
    }

    /** Records whether a model should be animating, so it comes back after a restart. */
    public void setAnimating(UUID id, boolean animating, double speed) {
        setAnimating(id, animating, speed, get(id).map(Placement::animationName).orElse(""));
    }

    public void setAnimating(UUID id, boolean animating, double speed, String animationName) {
        Placement old = placements.get(id);
        if (old == null) return;

        placements.put(id, new Placement(
                old.id(), old.model(), old.source(), old.world(),
                old.x(), old.y(), old.z(),
                old.yaw(), old.pitch(), old.roll(), old.scale(),
                old.offsetX(), old.offsetY(), old.offsetZ(),
                old.parts(), animating, speed, animationName == null ? "" : animationName,
                old.label(), old.labelOffset(), old.clickable(), old.clickCommand(),
                old.owner(), old.ownerName(), old.placedAt(),
                old.minX(), old.minY(), old.minZ(),
                old.maxX(), old.maxY(), old.maxZ()));
        save();
    }

    /** Rewrites the floating name over a model, or clears it when the text is empty. */
    public void setLabel(UUID id, String label) {
        Placement old = placements.get(id);
        if (old != null) relabel(old, label == null ? "" : label, old.labelOffset());
    }

    /** Nudges the label of a model up or down from where it lands on its own. */
    public void setLabelOffset(UUID id, double offset) {
        Placement old = placements.get(id);
        if (old != null) relabel(old, old.label(), offset);
    }

    private void relabel(Placement old, String text, double offset) {
        Placement updated = new Placement(
                old.id(), old.model(), old.source(), old.world(),
                old.x(), old.y(), old.z(),
                old.yaw(), old.pitch(), old.roll(), old.scale(),
                old.offsetX(), old.offsetY(), old.offsetZ(),
                old.parts(), old.animating(), old.animationSpeed(), old.animationName(),
                text, offset, old.clickable(), old.clickCommand(),
                old.owner(), old.ownerName(), old.placedAt(),
                old.minX(), old.minY(), old.minZ(), old.maxX(), old.maxY(), old.maxZ());

        placements.put(old.id(), updated);
        save();
        if (plugin.labels() != null) plugin.labels().refresh(updated);
    }

    /**
     * Rewrites the box a model is clicked and pointed at through.
     *
     * <p>Needed because the box is worked out when the model is placed and written to disk, so
     * every model placed by an older version carries whatever that version thought was right.
     * {@link de.phillip.bdpaste.interact.Hitboxes} recomputes it as the chunks come in.</p>
     *
     * @return whether the box actually moved
     */
    public boolean setBounds(UUID id, BoundingBox box) {
        Placement old = placements.get(id);
        if (old == null) return false;
        if (box.getMinX() == old.minX() && box.getMinY() == old.minY() && box.getMinZ() == old.minZ()
                && box.getMaxX() == old.maxX() && box.getMaxY() == old.maxY()
                && box.getMaxZ() == old.maxZ()) {
            return false;
        }

        placements.put(id, new Placement(
                old.id(), old.model(), old.source(), old.world(),
                old.x(), old.y(), old.z(),
                old.yaw(), old.pitch(), old.roll(), old.scale(),
                old.offsetX(), old.offsetY(), old.offsetZ(),
                old.parts(), old.animating(), old.animationSpeed(), old.animationName(),
                old.label(), old.labelOffset(), old.clickable(), old.clickCommand(),
                old.owner(), old.ownerName(), old.placedAt(),
                box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ()));
        save();
        return true;
    }

    /**
     * Records whether a model may be clicked, and puts its boxes up or takes them down.
     *
     * @return whether this changed anything
     */
    public boolean setClickable(UUID id, boolean clickable) {
        Placement old = placements.get(id);
        if (old == null || old.clickable() == clickable) return false;

        Placement updated = new Placement(
                old.id(), old.model(), old.source(), old.world(),
                old.x(), old.y(), old.z(),
                old.yaw(), old.pitch(), old.roll(), old.scale(),
                old.offsetX(), old.offsetY(), old.offsetZ(),
                old.parts(), old.animating(), old.animationSpeed(), old.animationName(),
                old.label(), old.labelOffset(), clickable, old.clickCommand(),
                old.owner(), old.ownerName(), old.placedAt(),
                old.minX(), old.minY(), old.minZ(), old.maxX(), old.maxY(), old.maxZ());

        placements.put(id, updated);
        save();
        if (plugin.hitboxes() != null) plugin.hitboxes().setEnabled(updated, clickable);
        return true;
    }

    /** Sets the command a model runs when clicked, or clears it when the text is empty. */
    public void setClickCommand(UUID id, String command) {
        Placement old = placements.get(id);
        if (old == null) return;

        String text = command == null ? "" : command.trim();
        placements.put(id, new Placement(
                old.id(), old.model(), old.source(), old.world(),
                old.x(), old.y(), old.z(),
                old.yaw(), old.pitch(), old.roll(), old.scale(),
                old.offsetX(), old.offsetY(), old.offsetZ(),
                old.parts(), old.animating(), old.animationSpeed(), old.animationName(),
                old.label(), old.labelOffset(), old.clickable(), text,
                old.owner(), old.ownerName(), old.placedAt(),
                old.minX(), old.minY(), old.minZ(), old.maxX(), old.maxY(), old.maxZ()));
        save();
    }

    public Optional<Placement> get(UUID id) {
        return Optional.ofNullable(placements.get(id));
    }

    public List<Placement> all() {
        return List.copyOf(placements.values());
    }

    /** Resolves a full or shortened (8 character) id. */
    public Optional<Placement> resolve(String idPrefix) {
        String needle = idPrefix.toLowerCase();
        return placements.values().stream()
                .filter(p -> p.id().toString().startsWith(needle))
                .findFirst();
    }

    public List<Placement> near(Location location, double radius) {
        UUID world = location.getWorld().getUID();
        double r2 = radius * radius;
        return placements.values().stream()
                .filter(p -> p.world().equals(world))
                .filter(p -> location.toVector().distanceSquared(new Vector(p.x(), p.y(), p.z())) <= r2)
                .sorted(Comparator.comparingDouble(
                        p -> location.toVector().distanceSquared(new Vector(p.x(), p.y(), p.z()))))
                .toList();
    }

    /**
     * The model the player is looking at - display entities have no hitbox, so the stored
     * bounds are ray-traced instead.
     *
     * <p>On the click path: this runs on every arm swing once anything is listening for
     * {@link de.phillip.bdpaste.api.BdModelClickEvent}, and a player mining swings four times a
     * second. So each model gets a distance check first, which is a handful of multiplications,
     * and only what could possibly be in reach gets the ray-box intersection - on a server with
     * a few thousand models that is a few of them rather than all of them.</p>
     */
    public Optional<Placement> lookingAt(Player player, double maxDistance) {
        Location eye = player.getEyeLocation();
        Vector origin = eye.toVector();
        Vector direction = eye.getDirection();
        UUID world = player.getWorld().getUID();
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();

        Placement best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Placement placement : placements.values()) {
            if (!placement.world().equals(world)) continue;

            // Inverted on purpose. A placement holding a NaN answers false to every
            // comparison, so the plain form would wave it through to the ray trace; this form
            // skips it, which is what a model nobody can point at deserves.
            double reach = maxDistance + placement.boundsRadius();
            if (!(placement.distanceSquaredFrom(ex, ey, ez) <= reach * reach)) continue;

            RayTraceResult hit = placement.bounds().rayTrace(origin, direction, maxDistance);
            if (hit == null) continue;
            double distance = hit.getHitPosition().distanceSquared(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = placement;
            }
        }
        return Optional.ofNullable(best);
    }

    public Optional<UUID> popUndo(UUID player) {
        Deque<UUID> stack = undoStacks.get(player);
        while (stack != null && !stack.isEmpty()) {
            UUID id = stack.pop();
            if (placements.containsKey(id)) return Optional.of(id);
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- deletion

    /**
     * Removes the entities and forgets the record.
     *
     * @return how many entities went away, or {@code -1} when a plugin cancelled
     *         {@link de.phillip.bdpaste.api.BdModelRemoveEvent}
     */
    public int delete(Placement placement) {
        de.phillip.bdpaste.api.BdModelRemoveEvent event =
                new de.phillip.bdpaste.api.BdModelRemoveEvent(placement);
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) return -1;

        // A model that is about to disappear must not stay in the animation loop.
        if (plugin.animations() != null) plugin.animations().stop(placement.id());
        if (plugin.hitboxes() != null) plugin.hitboxes().remove(placement);
        if (plugin.labels() != null) plugin.labels().remove(placement);
        int removed = deleteEntities(placement);
        placements.remove(placement.id());
        save();
        return removed;
    }

    private int deleteEntities(Placement placement) {
        World world = placement.bukkitWorld();
        if (world == null) return 0;

        Location location = placement.location();
        // Every part rides the anchor, so they all share the anchor's position.
        List<Entity> matches = new ArrayList<>(world.getNearbyEntities(location, 4, 4, 4));
        String wanted = placement.id().toString();

        int removed = 0;
        for (Entity entity : matches) {
            String id = entity.getPersistentDataContainer().get(plugin.keyModelId(), PersistentDataType.STRING);
            if (wanted.equals(id)) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Admin escape hatch: wipe every BDPaste display around a point, tracked or not. */
    public int cleanup(Location center, double radius) {
        int removed = 0;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity.getScoreboardTags().contains(BDPastePlugin.TAG)) {
                String id = entity.getPersistentDataContainer().get(plugin.keyModelId(), PersistentDataType.STRING);
                if (id != null) {
                    try {
                        UUID modelId = UUID.fromString(id);
                        if (plugin.animations() != null) plugin.animations().stop(modelId);
                        placements.remove(modelId);
                    } catch (IllegalArgumentException ignored) {
                        // not one of ours after all
                    }
                }
                entity.remove();
                removed++;
            }
        }
        if (removed > 0) save();
        return removed;
    }
}
