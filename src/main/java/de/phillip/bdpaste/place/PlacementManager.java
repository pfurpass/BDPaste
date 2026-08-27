package de.phillip.bdpaste.place;

import de.phillip.bdpaste.BDPastePlugin;
import de.phillip.bdpaste.model.BdModel;
import de.phillip.bdpaste.registry.Placement;
import de.phillip.bdpaste.util.Msg;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Owns the active placement sessions and translates player input into edits. */
public final class PlacementManager implements Listener {

    private final BDPastePlugin plugin;
    private final Map<UUID, PlacementSession> sessions = new HashMap<>();
    private BukkitTask task;

    public PlacementManager(BDPastePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (PlacementSession session : Map.copyOf(sessions).values()) {
            Optional<PlacementSession.Restore> restore = session.restore();
            session.discard();
            // Shutting down in the middle of a move must not swallow the model.
            restore.ifPresent(r -> restoreNow(session.player(), r));
        }
        sessions.clear();
    }

    /** Rebuilds a picked-up model at its old spot in one go, without waiting for ticks. */
    private void restoreNow(Player player, PlacementSession.Restore restore) {
        try {
            PlacementSession session =
                    new PlacementSession(plugin, player, restore.model(), restore.source());
            session.pin(restore.location());
            session.seedPose(restore.pose());
            session.start();
            session.buildAll();

            Placement placement = session.confirm();
            if (placement.parts() > 0) {
                plugin.placed().add(placement);
            } else {
                session.discard();
            }
        } catch (RuntimeException ex) {
            plugin.getSLF4JLogger().error("Could not restore a moved model for {}",
                    player == null ? "console" : player.getName(), ex);
        }
    }

    /**
     * Puts a model down at a fixed spot with nobody behind it.
     *
     * <p>What {@code /bdpaste place} does takes a player: a preview follows their crosshair, they
     * turn and scale it, and a right click accepts it. There is none of that here - the spot and
     * the pose are given, so the whole thing is built and confirmed in one go, the same way a
     * cancelled move is put back.</p>
     *
     * <p>Deliberately not registered in {@link #sessions}: that map is keyed by player, and this
     * session lives and dies inside this method. It never ticks, so nothing else can reach it.</p>
     *
     * <p>{@link de.phillip.bdpaste.api.BdModelPlaceEvent} fires from
     * {@link de.phillip.bdpaste.registry.PlacedModels#add}, which is the same call the player
     * path ends in - so a listener sees these models exactly like any other. It is not
     * cancellable, and by the time it runs the entities are already standing there.</p>
     *
     * @param placed handed the finished record; not called if anything went wrong
     * @param failed handed a message fit to show a player
     */
    public void placeHeadless(BdModel model, String source, Location at,
                              PlacementSession.Pose pose,
                              Consumer<Placement> placed, Consumer<String> failed) {
        if (at == null || at.getWorld() == null) {
            failed.accept("That location has no world.");
            return;
        }

        PlacementSession session = null;
        try {
            session = new PlacementSession(plugin, null, model, source);
            session.pin(at);
            session.seedPose(pose);
            session.start();
            session.buildAll();

            Placement placement = session.confirm();
            if (placement.parts() == 0) {
                // Every part failed to spawn. An empty record would be a model that is on the
                // books, takes up an id, and is not there.
                session.discard();
                failed.accept("Nothing was placed - not a single part of '" + source
                        + "' could be spawned. The server console says why.");
                return;
            }

            plugin.placed().add(placement);
            placed.accept(placement);
        } catch (RuntimeException ex) {
            if (session != null) session.discard();
            plugin.getSLF4JLogger().error("Could not place '{}' from the API", source, ex);
            failed.accept("Could not place '" + source + "': " + ex.getMessage()
                    + ". The full error is in the server console.");
        }
    }

    private void tickAll() {
        for (PlacementSession session : Map.copyOf(sessions).values()) {
            try {
                session.tick();
                // A pinned session is not steered by anyone; it finishes as soon as it is built.
                if (session.isPinned() && !session.isBuilding()) {
                    confirm(session.player());
                }
            } catch (RuntimeException ex) {
                plugin.getSLF4JLogger().error("Placement session for {} failed, cancelling it",
                        session.owner(), ex);
                // Only sessions with a player behind them are ever in this map, but the ones
                // without would have nowhere to send this and no key to be cancelled by.
                Player player = session.player();
                if (player == null) continue;
                cancel(player, false);
                Msg.error(player, "Placement stopped: " + Msg.escape(String.valueOf(ex.getMessage())));
                Msg.plain(player, "<gray>The full error is in the server console.</gray>");
            }
        }
    }

    public Optional<PlacementSession> session(Player player) {
        return Optional.ofNullable(sessions.get(player.getUniqueId()));
    }

    public boolean isPlacing(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public PlacementSession begin(Player player, BdModel model, String source) {
        return begin(player, model, source, null);
    }

    /**
     * @param before runs after the remembered settings are applied but before the first entity
     *               is spawned, so a session can start already posed or frozen
     */
    public PlacementSession begin(Player player, BdModel model, String source,
                                  java.util.function.Consumer<PlacementSession> before) {
        cancel(player, false);
        PlacementSession session = new PlacementSession(plugin, player, model, source);
        plugin.prefs().applyTo(player.getUniqueId(), session);
        if (before != null) before.accept(session);
        sessions.put(player.getUniqueId(), session);
        session.start();
        return session;
    }

    /**
     * Builds a model at a fixed spot and finishes on its own - used by
     * {@code /bdpaste replace} and to put a cancelled move back.
     */
    public void beginFixed(Player player, BdModel model, String source, Location at,
                           PlacementSession.Pose pose, String note) {
        beginFixed(player, model, source, at, pose, note, "");
    }

    /** @param label carried over from whatever stood here before, so a replace keeps its name */
    public void beginFixed(Player player, BdModel model, String source, Location at,
                           PlacementSession.Pose pose, String note, String label) {
        beginFixed(player, model, source, at, pose, note, label, 0);
    }

    public void beginFixed(Player player, BdModel model, String source, Location at,
                           PlacementSession.Pose pose, String note, String label, double labelOffset) {
        cancel(player, false);
        PlacementSession session = new PlacementSession(plugin, player, model, source);
        session.pin(at);
        session.seedPose(pose);
        session.seedLabel(label);
        session.seedLabelOffset(labelOffset);
        session.completionNote(note);
        sessions.put(player.getUniqueId(), session);
        session.start();
    }

    public void cancel(Player player, boolean announce) {
        PlacementSession session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        session.discard();
        if (announce) Msg.send(player, "<red>Placement cancelled.</red>");

        // A cancelled move must not lose the model it picked up.
        session.restore().ifPresent(restore -> beginFixed(player, restore.model(), restore.source(),
                restore.location(), restore.pose(), "Restored"));
    }

    /** Finishes a placement. Right-clicking does this, and so does {@code /bdpaste confirm}. */
    public void confirm(Player player) {
        PlacementSession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        if (session.isBuilding()) {
            Msg.send(player, "<yellow>Still building the preview - one moment.</yellow>");
            return;
        }

        sessions.remove(player.getUniqueId());
        Placement placement = session.confirm();

        if (placement.parts() == 0) {
            // Every part failed to spawn - saving an empty marker would only be confusing.
            session.discard();
            if (player.isOnline()) {
                Msg.error(player, "Nothing was placed - not a single part could be spawned.");
                Msg.plain(player, "<gray>The server console says why.</gray>");
            }
            return;
        }
        plugin.placed().add(placement);
        if (!player.isOnline()) return;

        String note = session.completionNoteOrNull();
        Msg.send(player, "<green><verb> <white><model></white> <gray>(<parts> parts, id <id>)</gray>",
                Msg.arg("verb", note == null ? "Placed" : note),
                Msg.arg("model", placement.model()),
                Msg.arg("parts", String.valueOf(placement.parts())),
                Msg.arg("id", placement.shortId()));
        Msg.plain(player, "<gray>Undo with <white>/bdpaste undo</white>.</gray>");

        // Straight back into placing, so a row of copies needs no command in between.
        if (session.repeatEnabled() && session.isRepeatable() && !session.isPinned()) {
            PlacementSession.Pose pose = session.pose();
            begin(player, session.model(), session.source(), fresh -> {
                fresh.seedPose(pose);
                fresh.setQuiet(true);
            });
        }
    }

    // ---------------------------------------------------------------- listeners

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onScroll(PlayerItemHeldEvent event) {
        PlacementSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.isSuspended()) return;

        event.setCancelled(true);
        int delta = scrollDelta(event.getPreviousSlot(), event.getNewSlot());
        if (event.getPlayer().isSneaking()) {
            session.cycleParam(delta);
            remember(event.getPlayer(), session);
        } else {
            session.adjust(delta);
        }
    }

    /** Hotbar slots wrap around, so pick the shorter way as the scroll direction. */
    private static int scrollDelta(int previous, int next) {
        int delta = next - previous;
        if (delta > 4) delta -= 9;
        if (delta < -4) delta += 9;
        return delta;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        PlacementSession session = sessions.get(player.getUniqueId());
        if (session == null || session.isSuspended()) return;
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) return;

        event.setCancelled(true);
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> confirm(player);
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> {
                if (player.isSneaking()) {
                    session.resetParam();
                } else {
                    session.cycleStep();
                    remember(player, session);
                }
            }
            default -> {
                // physical interactions are simply swallowed
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        PlacementSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        if (session.isSuspended()) {
            // Paused means hands off - except for the one combo that comes back.
            if (!event.getPlayer().isSneaking()) return;
            event.setCancelled(true);
            resume(event.getPlayer());
            return;
        }

        event.setCancelled(true);
        if (event.getPlayer().isSneaking()) {
            announceSnap(event.getPlayer(), session.cycleSnap());
            remember(event.getPlayer(), session);
        } else {
            session.toggleFreeze();
            announceFreeze(event.getPlayer(), session);
        }
    }

    /** Q parks the preview, sneak + Q throws it away. */
    public void suspend(Player player) {
        PlacementSession session = sessions.get(player.getUniqueId());
        if (session == null || session.isSuspended()) return;
        session.suspend();
        Msg.send(player, "<gold>Paused.</gold> <gray>Build away - the preview stays put.</gray>");
        Msg.plain(player, "<gray>Back in with <white>Q</white>, <white>Sneak + F</white> or "
                + "<white>/bdpaste resume</white>.</gray>");
    }

    public void resume(Player player) {
        PlacementSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.isSuspended()) return;
        session.resume();
        Msg.send(player, "<green>Back in placement mode.</green> <gray>Still frozen - F to let it follow again.</gray>");
    }

    public void announceSnap(Player player, de.phillip.bdpaste.util.Settings.SnapMode mode) {
        String description = switch (mode) {
            case OFF -> "<red>off</red> <gray>- exactly where you point";
            case PIXEL -> "<aqua>pixel</aqua> <gray>- a sixteenth of a block";
            case BLOCK -> "<green>block</green> <gray>- whole blocks";
        };
        Msg.send(player, "Snap: " + description);
    }

    private void announceFreeze(Player player, PlacementSession session) {
        if (session.isFrozen()) {
            Msg.send(player, "<aqua>Frozen.</aqua> <gray>Walk around and look at it. "
                    + "F again to unfreeze, right click to accept.</gray>");
        } else {
            Msg.send(player, "<gray>Unfrozen - following your crosshair again.</gray>");
        }
    }

    /** Same as pressing F, for anyone who would rather type it. */
    public void toggleFreeze(Player player) {
        PlacementSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        session.toggleFreeze();
        announceFreeze(player, session);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent event) {
        PlacementSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;

        event.setCancelled(true);
        if (session.isSuspended()) {
            resume(event.getPlayer());
        } else if (event.getPlayer().isSneaking()) {
            cancel(event.getPlayer(), true);
        } else {
            suspend(event.getPlayer());
        }
    }

    /** Keeps this player's step sizes and toggles for the next model they place. */
    public void remember(Player player, PlacementSession session) {
        plugin.prefs().remember(player.getUniqueId(), session);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent event) {
        PlacementSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && !session.isSuspended()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent event) {
        PlacementSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && !session.isSuspended()) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), false);
    }
}
