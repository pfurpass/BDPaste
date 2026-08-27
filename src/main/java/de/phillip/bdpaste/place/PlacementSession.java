package de.phillip.bdpaste.place;

import de.phillip.bdpaste.BDPastePlugin;
import de.phillip.bdpaste.model.BdModel;
import de.phillip.bdpaste.model.BdPart;
import de.phillip.bdpaste.model.Bounds;
import de.phillip.bdpaste.registry.Placement;
import de.phillip.bdpaste.util.Msg;
import de.phillip.bdpaste.util.Settings;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One player's live placement mode: a real, fully built copy of the model follows the
 * crosshair until it is confirmed or thrown away.
 */
public final class PlacementSession {

    /** The value the scroll wheel currently edits. */
    public enum Param {
        DISTANCE("Distance"),
        YAW("Yaw"),
        PITCH("Pitch"),
        ROLL("Roll"),
        SCALE("Scale"),
        OFFSET_X("Offset X"),
        OFFSET_Y("Offset Y"),
        OFFSET_Z("Offset Z");

        private final String label;

        Param(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final BDPastePlugin plugin;
    /**
     * Whose crosshair this follows, or {@code null} for a placement nobody is steering.
     *
     * <p>A session put down by {@link PlacementManager#placeHeadless} has no player behind it -
     * another plugin asked for a model at a spot and that is all. Everything the player is for
     * belongs to the live preview: the crosshair, the throwaway stick, the action bar, the
     * control list, hiding the half-built copy from everyone else. A pinned session has none of
     * that, so all of it is skipped rather than faked.</p>
     */
    private final Player player;
    private final BdModel model;
    /** Library file name, needed to load this model again for /bdpaste move. */
    private final String source;
    private final UUID id = UUID.randomUUID();

    /** A live display together with the part it came from, so the two can never drift apart. */
    private record SpawnedPart(Display display, BdPart part, int index) {
    }

    /** Everything about how a model is set up, except where the crosshair put it. */
    public record Pose(float yaw, float pitch, float roll, float scale,
                       double offsetX, double offsetY, double offsetZ) {
        public static final Pose DEFAULT = new Pose(0f, 0f, 0f, 1f, 0, 0, 0);
    }

    /** Where a moved model came from, so cancelling can put it back. */
    public record Restore(BdModel model, String source, Location location, Pose pose) {
    }

    private BlockDisplay root;
    private final List<SpawnedPart> spawned = new ArrayList<>();
    private int spawnCursor;

    private double distance;
    private float yaw;
    private float pitch;
    private float roll;
    private float scale = 1f;
    private double offsetX;
    private double offsetY;
    private double offsetZ;
    private Settings.SnapMode snap;
    private boolean surface;
    private Param param = Param.YAW;

    /** Step ladders the left mouse button cycles through, one per kind of value. */
    private static final double[] ROTATION_STEPS = {1, 5, 15, 45, 90};
    private static final double[] OFFSET_STEPS = {0.03125, 0.0625, 0.125, 0.25, 0.5, 1};
    private static final double[] SCALE_STEPS = {1.01, 1.02, 1.05, 1.1, 1.25, 2};
    private static final double[] DISTANCE_STEPS = {0.25, 0.5, 1, 2, 5};

    private final EnumMap<Param, Double> steps = new EnumMap<>(Param.class);

    private boolean onSurface;
    private boolean suspended;
    private int toolSlot = -1;
    /** When set the model does not follow the crosshair but sits here. */
    private Location pinned;
    /** When set the model stays put so you can walk around and look at it. */
    private Location frozenBase;
    private Restore restore;
    private String completionNote;
    /** Keep handing out a fresh copy after every confirm. */
    private boolean repeat;
    /** Off for a move, where repeating would silently duplicate instead. */
    private boolean repeatable = true;
    /** Skips the control list, so placing twenty copies does not flood the chat. */
    private boolean quiet;
    private boolean dirty = true;
    private boolean passengersRideAlong = true;
    private Location lastTarget;
    private boolean finished;

    public PlacementSession(BDPastePlugin plugin, Player player, BdModel model, String source) {
        this.plugin = plugin;
        this.player = player;
        this.model = model;
        this.source = source;

        Settings s = plugin.settings();
        this.distance = s.defaultDistance;
        this.snap = s.snapDefault;
        this.surface = s.surfaceByDefault;

        resetSteps();
    }

    public double step() {
        return step(param);
    }

    public double step(Param target) {
        return steps.getOrDefault(target, 1.0);
    }

    public void setStep(Param target, double value) {
        steps.put(target, Math.abs(value));
    }

    private static double[] ladder(Param target) {
        return switch (target) {
            case DISTANCE -> DISTANCE_STEPS;
            case YAW, PITCH, ROLL -> ROTATION_STEPS;
            case SCALE -> SCALE_STEPS;
            case OFFSET_X, OFFSET_Y, OFFSET_Z -> OFFSET_STEPS;
        };
    }

    /** Moves to the next step size on the ladder for the active value, wrapping around. */
    public void cycleStep() {
        double[] options = ladder(param);
        double current = step();

        int nearest = 0;
        for (int i = 1; i < options.length; i++) {
            if (Math.abs(options[i] - current) < Math.abs(options[nearest] - current)) nearest = i;
        }
        // A custom step from /bdpaste step is not on the ladder; snap onto the nearest rung first.
        boolean onLadder = Math.abs(options[nearest] - current) < 1.0E-6;
        steps.put(param, options[onLadder ? (nearest + 1) % options.length : nearest]);
    }

    public Player player() {
        return player;
    }

    /** Who to blame in a log line or an ownership message. */
    public String owner() {
        return player == null ? "console" : player.getName();
    }

    public BdModel model() {
        return model;
    }

    public String source() {
        return source;
    }

    /** Anchors the model at a fixed spot instead of letting it follow the crosshair. */
    public void pin(Location location) {
        this.pinned = location.clone();
    }

    public boolean isPinned() {
        return pinned != null;
    }

    /** Starts from an existing rotation and scale rather than the defaults. */
    public void seedPose(Pose pose) {
        this.yaw = wrap(pose.yaw());
        this.pitch = wrap(pose.pitch());
        this.roll = wrap(pose.roll());
        this.scale = pose.scale();
        this.offsetX = pose.offsetX();
        this.offsetY = pose.offsetY();
        this.offsetZ = pose.offsetZ();
        this.dirty = true;
    }

    /** If this session is cancelled, put the given model back where it was. */
    public void restoreOnCancel(Restore restore) {
        this.restore = restore;
    }

    public java.util.Optional<Restore> restore() {
        return java.util.Optional.ofNullable(restore);
    }

    public void completionNote(String note) {
        this.completionNote = note;
    }

    public String completionNoteOrNull() {
        return completionNote;
    }

    public Pose pose() {
        return new Pose(yaw, pitch, roll, scale, offsetX, offsetY, offsetZ);
    }

    public boolean isBuilding() {
        return spawnCursor < model.size();
    }

    // ------------------------------------------------------------------ start

    public void start() {
        Location target = targetLocation();
        root = plugin.spawner().spawnRoot(target, true);
        hideFromOthers(root);
        lastTarget = target;
        if (pinned == null && player != null) {
            giveTool();
            if (quiet) {
                Msg.send(player, "<white>Next one: <yellow><model></yellow> <gray>(right click to place, "
                        + "/bdpaste repeat off to stop)</gray>", Msg.arg("model", model.name()));
            } else {
                printControls();
            }
        }
    }

    /**
     * Vanilla clients send nothing when you right click <em>air</em> with an empty hand, so with
     * nothing held you could only ever confirm while aiming at a block. Putting a throwaway item
     * in the hand fixes that - and an already occupied slot is never touched.
     */
    private void giveTool() {
        PlayerInventory inventory = player.getInventory();
        if (!inventory.getItemInMainHand().getType().isAir()) return;

        ItemStack tool = new ItemStack(Material.STICK);
        ItemMeta meta = tool.getItemMeta();
        meta.displayName(Msg.of("<gold>BDPaste</gold><gray> - right click to place</gray>")
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(plugin.keyTool(), PersistentDataType.BYTE, (byte) 1);
        tool.setItemMeta(meta);

        toolSlot = inventory.getHeldItemSlot();
        inventory.setItem(toolSlot, tool);
    }

    /** Removes the throwaway item again, wherever it ended up. */
    private void takeTool() {
        if (toolSlot < 0 || player == null) return;
        PlayerInventory inventory = player.getInventory();

        if (isTool(inventory.getItem(toolSlot))) {
            inventory.setItem(toolSlot, null);
        } else {
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                if (isTool(inventory.getItem(slot))) inventory.setItem(slot, null);
            }
        }
        toolSlot = -1;
    }

    private boolean isTool(ItemStack stack) {
        return stack != null
                && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer()
                .has(plugin.keyTool(), PersistentDataType.BYTE);
    }

    private void printControls() {
        Msg.send(player, "<white>Placing <yellow><model></yellow> <gray>(<parts> parts)</gray>",
                Msg.arg("model", model.name()), Msg.arg("parts", String.valueOf(model.size())));
        Msg.plain(player, "  <gray>Move mouse</gray> <dark_gray>-</dark_gray> <white>position the model");
        Msg.plain(player, "  <gray>Scroll</gray> <dark_gray>-</dark_gray> <white>change the selected value");
        Msg.plain(player, "  <gray>Sneak + Scroll</gray> <dark_gray>-</dark_gray> <white>select another value");
        Msg.plain(player, "  <gray>Right click</gray> or <gray>/bdpaste confirm</gray> <dark_gray>-</dark_gray> <green>place it");
        Msg.plain(player, "  <gray>Left click</gray> <dark_gray>-</dark_gray> <white>change the step size");
        Msg.plain(player, "  <gray>Sneak + Left click</gray> <dark_gray>-</dark_gray> <white>reset the selected value");
        Msg.plain(player, "  <gray>Swap hands (F)</gray> <dark_gray>-</dark_gray> <aqua>freeze / unfreeze<white> - walk around and check it");
        Msg.plain(player, "  <gray>Sneak + Swap hands</gray> <dark_gray>-</dark_gray> <white>snap: off / pixel / block");
        Msg.plain(player, "  <gray>Drop (Q)</gray> <dark_gray>-</dark_gray> <gold>pause<white> - build something to compare");
        Msg.plain(player, "  <gray>Sneak + Drop</gray> or <gray>/bdpaste cancel</gray> <dark_gray>-</dark_gray> <red>abort");
        if (isFrozen()) {
            Msg.plain(player, "<aqua>Picked up where it stood, frozen.</aqua> <gray>Tweak it right there - "
                    + "<white>F</white> hands it back to your crosshair.</gray>");
        }
    }

    // ------------------------------------------------------------------- tick

    public void tick() {
        if (finished || root == null || !root.isValid()) return;
        // A pinned session runs to completion on its own, so it must not stall when the
        // player logs out halfway through a move - or when there was never one.
        if (pinned == null && (player == null || !player.isOnline())) return;

        if (isBuilding()) {
            buildBatch();
        }
        if (dirty) {
            refreshTransforms();
            dirty = false;
        }

        Location target = targetLocation();
        if (lastTarget == null || target.distanceSquared(lastTarget) > 1.0E-4) {
            moveTo(target);
            lastTarget = target;
        }
        if (pinned == null && player != null) player.sendActionBar(hud());
    }

    private void buildBatch() {
        int end = Math.min(model.size(), spawnCursor + plugin.settings().spawnPerTick);
        Matrix4f user = userMatrix();
        Location at = root.getLocation();

        for (; spawnCursor < end; spawnCursor++) {
            BdPart part = model.parts().get(spawnCursor);
            Entity entity;
            try {
                entity = plugin.spawner().spawnPart(at, part, new Matrix4f(user).mul(part.restPose()), true);
            } catch (RuntimeException ex) {
                // One unusable part must not take the whole model down.
                plugin.warnPartFailed(part.name(), ex);
                continue;
            }
            hideFromOthers(entity);
            if (passengersRideAlong && !root.addPassenger(entity)) {
                detach("the anchor refused a passenger");
            }
            spawned.add(new SpawnedPart((Display) entity, part, spawnCursor));
        }
    }

    /** Builds every remaining part right now instead of spreading it over ticks. */
    public void buildAll() {
        while (isBuilding()) {
            buildBatch();
        }
        refreshTransforms();
        dirty = false;
    }

    /**
     * While previewing, only the placing player should see the half-finished model.
     *
     * <p>Nobody to show it to on a headless placement, and nothing to hide either - it is built
     * pinned and confirmed in the same breath.</p>
     */
    private void hideFromOthers(Entity entity) {
        if (pinned != null || player == null) return;
        if (!plugin.settings().previewHideFromOthers) return;
        entity.setVisibleByDefault(false);
        player.showEntity(plugin, entity);
    }

    private void moveTo(Location target) {
        if (passengersRideAlong) {
            if (root.teleport(target)) return;
            // The server would not move a vehicle, so stop using one.
            detach("this server will not teleport an entity that has passengers");
        }
        root.teleport(target);
        for (SpawnedPart spawnedPart : spawned) {
            if (spawnedPart.display().isValid()) spawnedPart.display().teleport(target);
        }
    }

    /**
     * Drops the vehicle relationship and moves every part on its own from here on.
     *
     * <p>Passengers get their position forced by their vehicle every tick, so they have to be
     * ejected first - teleporting a still-mounted passenger does nothing at all.</p>
     */
    private void detach(String reason) {
        if (!passengersRideAlong) return;
        passengersRideAlong = false;
        root.eject();
        plugin.getSLF4JLogger().info(
                "Preview for {} switched to per-part movement ({}). {} entities will be moved individually.",
                owner(), reason, model.size());
    }

    private void refreshTransforms() {
        Matrix4f user = userMatrix();
        for (SpawnedPart spawnedPart : spawned) {
            if (!spawnedPart.display().isValid()) continue;
            spawnedPart.display().setTransformationMatrix(
                    new Matrix4f(user).mul(spawnedPart.part().restPose()));
        }
    }

    // -------------------------------------------------------------- transform

    /**
     * Rotation and scale happen around the pivot, and the pivot itself is moved onto the anchor.
     *
     * <p>That last translation is what keeps the model stuck to the crosshair: without it,
     * scaling moves the model away from the cursor, because only the pivot stays put while
     * everything else grows around it.</p>
     */
    private Matrix4f userMatrix() {
        Vector3f pivot = plugin.settings().pivot == Settings.Pivot.CENTER
                ? model.pivotCenter()
                : new Vector3f();
        return UserTransform.of(pivot, yaw, pitch, roll, scale);
    }

    private Location targetLocation() {
        if (pinned != null) return pinned.clone();
        // Frozen keeps the anchor, not the final spot, so the offsets stay adjustable.
        Location base = frozenBase != null ? frozenBase.clone() : crosshairBase();
        return base.add(offsetX, offsetY, offsetZ);
    }

    /**
     * Where the crosshair currently points, before the offsets are applied.
     *
     * <p>Unreachable without a player: {@link #targetLocation} hands back the pinned spot before
     * it ever gets here, and a session with no player is always pinned. Saying so out loud beats
     * a null pointer somewhere further down, and beats quietly returning a spot that would put
     * the model in the wrong place.</p>
     */
    private Location crosshairBase() {
        if (player == null) {
            throw new IllegalStateException(
                    "a placement with nobody behind it has no crosshair to follow");
        }
        Location base = null;

        if (surface) {
            // Reach much further than the free-placement distance, otherwise you drop out of
            // surface mode as soon as you look a few blocks past your feet.
            double reach = Math.max(distance, plugin.settings().surfaceReach);
            RayTraceResult hit = player.rayTraceBlocks(reach, FluidCollisionMode.NEVER);
            if (hit != null && hit.getHitBlock() != null) {
                Block block = hit.getHitBlock();
                BlockFace face = hit.getHitBlockFace() == null ? BlockFace.UP : hit.getHitBlockFace();
                if (snap == Settings.SnapMode.BLOCK) {
                    base = new Location(player.getWorld(),
                            block.getX() + face.getModX(),
                            block.getY() + face.getModY(),
                            block.getZ() + face.getModZ());
                } else {
                    // Block faces sit on whole coordinates, so rounding to the pixel grid
                    // keeps the model flat on the surface it was aimed at.
                    Vector v = hit.getHitPosition();
                    base = new Location(player.getWorld(),
                            gridded(v.getX()), gridded(v.getY()), gridded(v.getZ()));
                }
                onSurface = true;
            }
        }
        if (base == null) {
            Location eye = player.getEyeLocation();
            base = eye.clone().add(eye.getDirection().multiply(distance));
            if (snap == Settings.SnapMode.BLOCK) {
                base = new Location(player.getWorld(),
                        Math.floor(base.getX()), Math.floor(base.getY()), Math.floor(base.getZ()));
            } else {
                base = new Location(player.getWorld(),
                        gridded(base.getX()), gridded(base.getY()), gridded(base.getZ()));
            }
            onSurface = false;
        }
        return base;
    }

    // ----------------------------------------------------------------- inputs

    /** Scroll wheel: {@code delta} is +1 per notch down, -1 per notch up. */
    public void adjust(int delta) {
        if (delta == 0) return;
        Settings s = plugin.settings();
        double step = step();
        switch (param) {
            case DISTANCE -> distance = clamp(distance + delta * step, 1.0, s.maxDistance);
            case YAW -> yaw = wrap(yaw + (float) (delta * step));
            case PITCH -> pitch = wrap(pitch + (float) (delta * step));
            case ROLL -> roll = wrap(roll + (float) (delta * step));
            // Scale multiplies instead of adding, so its step is a factor, not a summand.
            case SCALE -> scale = (float) clamp(scale * Math.pow(Math.max(1.0001, step), delta), 0.02, 64.0);
            case OFFSET_X -> offsetX += delta * step;
            case OFFSET_Y -> offsetY += delta * step;
            case OFFSET_Z -> offsetZ += delta * step;
        }
        if (param != Param.DISTANCE && !param.name().startsWith("OFFSET")) dirty = true;
    }

    public void cycleParam(int delta) {
        Param[] values = Param.values();
        int next = Math.floorMod(param.ordinal() + delta, values.length);
        param = values[next];
    }

    public void resetParam() {
        Settings s = plugin.settings();
        switch (param) {
            case DISTANCE -> distance = s.defaultDistance;
            case YAW -> yaw = 0f;
            case PITCH -> pitch = 0f;
            case ROLL -> roll = 0f;
            case SCALE -> scale = 1f;
            case OFFSET_X -> offsetX = 0;
            case OFFSET_Y -> offsetY = 0;
            case OFFSET_Z -> offsetZ = 0;
        }
        dirty = true;
    }

    /** Rounds a coordinate onto the pixel grid; OFF leaves it alone. */
    private double gridded(double value) {
        if (snap != Settings.SnapMode.PIXEL) return value;
        double grid = plugin.settings().pixelSnap;
        return Math.round(value / grid) * grid;
    }

    /** Steps to the next snap mode: off, pixel, block. */
    public Settings.SnapMode cycleSnap() {
        snap = snap.next();
        return snap;
    }

    /**
     * Parks the model where it is so the player can walk around it before accepting.
     * Rotation, scale and the offsets keep working while frozen.
     */
    public void toggleFreeze() {
        frozenBase = frozenBase == null ? crosshairBase() : null;
    }

    public boolean isFrozen() {
        return frozenBase != null;
    }

    /**
     * Starts out frozen at {@code base} instead of at the crosshair. Pass the anchor
     * <em>without</em> the offsets - {@link #targetLocation()} adds those back on.
     */
    public void freezeAt(Location base) {
        this.frozenBase = base.clone();
    }

    public boolean repeatEnabled() {
        return repeat;
    }

    public void setRepeat(boolean repeat) {
        this.repeat = repeat;
    }

    public boolean isRepeatable() {
        return repeatable;
    }

    public void setRepeatable(boolean repeatable) {
        this.repeatable = repeatable;
    }

    public void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    public boolean isSuspended() {
        return suspended;
    }

    /**
     * Steps out of placement mode for a moment. The preview parks where it is and the player
     * gets their hotbar and their clicks back, so they can build something to compare against.
     */
    public void suspend() {
        if (suspended) return;
        suspended = true;
        if (frozenBase == null) frozenBase = crosshairBase();
        takeTool();
    }

    public void resume() {
        if (!suspended) return;
        suspended = false;
        giveTool();
    }

    public void toggleSurface() {
        surface = !surface;
    }

    public void setParam(Param param) {
        this.param = param;
    }

    public Param activeParam() {
        return param;
    }

    public Settings.SnapMode snapMode() {
        return snap;
    }

    public void setSnap(Settings.SnapMode snap) {
        this.snap = snap;
    }

    public boolean surfaceEnabled() {
        return surface;
    }

    public void setSurface(boolean surface) {
        this.surface = surface;
    }

    /** Puts every step size back to what config.yml says. */
    public void resetSteps() {
        Settings s = plugin.settings();
        steps.put(Param.DISTANCE, 0.5);
        steps.put(Param.YAW, s.rotationStep);
        steps.put(Param.PITCH, s.rotationStep);
        steps.put(Param.ROLL, s.rotationStep);
        steps.put(Param.SCALE, s.scaleStep);
        steps.put(Param.OFFSET_X, s.offsetStep);
        steps.put(Param.OFFSET_Y, s.offsetStep);
        steps.put(Param.OFFSET_Z, s.offsetStep);
    }

    public boolean setValue(Param target, double value) {
        Settings s = plugin.settings();
        switch (target) {
            case DISTANCE -> distance = clamp(value, 1.0, s.maxDistance);
            case YAW -> yaw = wrap((float) value);
            case PITCH -> pitch = wrap((float) value);
            case ROLL -> roll = wrap((float) value);
            case SCALE -> scale = (float) clamp(value, 0.02, 64.0);
            case OFFSET_X -> offsetX = value;
            case OFFSET_Y -> offsetY = value;
            case OFFSET_Z -> offsetZ = value;
        }
        dirty = true;
        return true;
    }

    // --------------------------------------------------------------- teardown

    /**
     * A label to hang over the model once it lands, carried across a move or a replace so it
     * does not have to be typed again. Empty means none, which is the normal case.
     */
    private String label = "";

    private double labelOffset;

    public void seedLabel(String miniMessage) {
        this.label = miniMessage == null ? "" : miniMessage;
    }

    public void seedLabelOffset(double offset) {
        this.labelOffset = offset;
    }

    public String label() {
        return label;
    }

    /** Bakes the preview into a permanent placement. */
    public Placement confirm() {
        finished = true;
        restore = null;
        takeTool();
        refreshTransforms();

        Location at = root.getLocation();
        String idString = id.toString();

        stamp(root, idString);
        root.setTeleportDuration(0);
        for (SpawnedPart spawnedPart : spawned) {
            if (spawnedPart.display().isValid()) {
                stamp(spawnedPart.display(), idString);
                // The index is what lets the animation player match an entity to its keyframes.
                spawnedPart.display().getPersistentDataContainer()
                        .set(plugin.keyPartIndex(), PersistentDataType.INTEGER, spawnedPart.index());
            }
        }

        Vector min = new Vector();
        Vector max = new Vector();
        worldBounds(at, min, max);

        return new Placement(
                id, model.name(), source, at.getWorld().getUID(),
                at.getX(), at.getY(), at.getZ(),
                yaw, pitch, roll, scale,
                offsetX, offsetY, offsetZ,
                spawned.size(), false, 1.0, "", label, labelOffset, true,
                // No owner at all when nobody placed it. Null is what the registry already
                // writes for a missing one and what it reads back, and everything that asks
                // about an owner is written to cope with it - a model nobody owns is one only
                // an admin may edit, which is the right answer for one a plugin put down.
                player == null ? null : player.getUniqueId(), owner(),
                System.currentTimeMillis(),
                min.getX(), min.getY(), min.getZ(),
                max.getX(), max.getY(), max.getZ());
    }

    private void stamp(Entity entity, String idString) {
        entity.setVisibleByDefault(true);
        entity.setPersistent(true);
        entity.getPersistentDataContainer().set(plugin.keyModelId(), PersistentDataType.STRING, idString);
        entity.getPersistentDataContainer().set(plugin.keyModelName(), PersistentDataType.STRING, model.name());
    }

    /**
     * World-space box of the model as it stands, which is what it is clicked and pointed at
     * through. See {@link BdModel#restBox()} for why it is the resting pose and not the sweep.
     */
    private void worldBounds(Location origin, Vector minOut, Vector maxOut) {
        Bounds.Box box = Placements.localBox(model, userMatrix());
        minOut.setX(origin.getX() + box.min().x)
                .setY(origin.getY() + box.min().y)
                .setZ(origin.getZ() + box.min().z);
        maxOut.setX(origin.getX() + box.max().x)
                .setY(origin.getY() + box.max().y)
                .setZ(origin.getZ() + box.max().z);
    }

    /** Throws the preview away. */
    public void discard() {
        finished = true;
        takeTool();
        for (SpawnedPart spawnedPart : spawned) {
            if (spawnedPart.display().isValid()) spawnedPart.display().remove();
        }
        spawned.clear();
        if (root != null && root.isValid()) root.remove();
    }

    // -------------------------------------------------------------------- hud

    private net.kyori.adventure.text.Component hud() {
        StringBuilder sb = new StringBuilder();
        sb.append("<gold>").append(Msg.escape(model.name())).append("</gold> <dark_gray>|</dark_gray> ");
        if (isBuilding()) {
            int percent = (int) (100.0 * spawnCursor / Math.max(1, model.size()));
            sb.append("<yellow>building ").append(percent).append("%</yellow> <dark_gray>|</dark_gray> ");
        }
        sb.append("<aqua>").append(param.label()).append("</aqua> <white>")
                .append(currentValueText()).append("</white> ")
                .append("<dark_gray>").append(param == Param.SCALE ? "x" : "step ")
                .append(stepText()).append("</dark_gray> <dark_gray>|</dark_gray> ");
        sb.append("<gray>y</gray> ").append(fmt(yaw)).append("<gray>°</gray> ");
        sb.append("<gray>p</gray> ").append(fmt(pitch)).append("<gray>°</gray> ");
        sb.append("<gray>r</gray> ").append(fmt(roll)).append("<gray>°</gray> ");
        sb.append("<gray>x</gray> ").append(String.format(Locale.ROOT, "%.2f", scale)).append(" ");
        Location at = root != null && root.isValid() ? root.getLocation() : lastTarget;
        if (at != null) {
            sb.append("<dark_gray>|</dark_gray> <gray>@</gray> <white>")
                    .append(String.format(Locale.ROOT, "%.1f %.1f %.1f", at.getX(), at.getY(), at.getZ()))
                    .append("</white> ");
        }
        sb.append("<dark_gray>|</dark_gray> <gray>snap</gray> ").append(switch (snap) {
            case OFF -> "<red>off";
            case PIXEL -> "<aqua>pixel";
            case BLOCK -> "<green>block";
        });
        sb.append("<dark_gray> · </dark_gray>")
                .append(isFrozen() ? "<aqua><bold>FROZEN</bold>" : onSurface ? "<green>surface" : "<yellow>free");
        if (repeat && repeatable) sb.append("<dark_gray> · </dark_gray><light_purple>repeat");
        if (suspended) {
            sb.append("<dark_gray> · </dark_gray><gold><bold>PAUSED</bold></gold>")
                    .append(" <gray>/bdpaste resume</gray>");
        }
        return Msg.of(sb.toString());
    }

    private String currentValueText() {
        return switch (param) {
            case DISTANCE -> String.format(Locale.ROOT, "%.1f", distance);
            case YAW -> fmt(yaw) + "°";
            case PITCH -> fmt(pitch) + "°";
            case ROLL -> fmt(roll) + "°";
            case SCALE -> String.format(Locale.ROOT, "%.2f", scale);
            case OFFSET_X -> String.format(Locale.ROOT, "%.2f", offsetX);
            case OFFSET_Y -> String.format(Locale.ROOT, "%.2f", offsetY);
            case OFFSET_Z -> String.format(Locale.ROOT, "%.2f", offsetZ);
        };
    }

    /** Trims trailing zeroes so the action bar shows 0.25 and 15, not 0.2500 and 15.0000. */
    private String stepText() {
        String text = String.format(Locale.ROOT, "%.5f", step());
        text = text.replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    private static String fmt(float degrees) {
        return String.format(Locale.ROOT, "%.0f", degrees);
    }

    /**
     * Keeps a value inside a range, and treats a non-number as the bottom of it.
     *
     * <p>Written the plain way this lets NaN straight through: {@code Math.min} and
     * {@code Math.max} both hand NaN back. The command layer rejects those before they get
     * here, but this is the last place they could do damage, and one NaN in a scale is a model
     * that can never be looked at again.</p>
     */
    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static float wrap(float degrees) {
        float d = degrees % 360f;
        return d < 0 ? d + 360f : d;
    }
}
