package de.phillip.bdpaste.interact;

import de.phillip.bdpaste.BDPastePlugin;
import de.phillip.bdpaste.api.BdModelClickEvent;
import de.phillip.bdpaste.registry.Placement;
import de.phillip.bdpaste.util.Msg;
import de.phillip.bdpaste.util.Settings;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a click on a placed model into a {@link BdModelClickEvent}, then does whatever the
 * config asks for if nobody cancelled it.
 *
 * <p>The two buttons arrive by different routes. A right click on the hitbox is an ordinary
 * {@link PlayerInteractEntityEvent}. A left click is not: an interaction entity is not
 * damageable and skips the whole hurt path, so no damage event is ever fired for it. What is
 * left is the arm swing, which is then checked against the model the player is aiming at.</p>
 */
public final class ClickListener implements Listener {

    /** Clicks arriving faster than this on the same model are the same click, near enough. */
    private static final long COOLDOWN_MS = 250;

    private final BDPastePlugin plugin;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public ClickListener(BDPastePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRightClick(PlayerInteractEntityEvent event) {
        // Both hands fire this; the off hand would double every click.
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Interaction)) return;

        modelOf(event.getRightClicked()).ifPresent(model ->
                handle(event.getPlayer(), model, BdModelClickEvent.Action.RIGHT, EquipmentSlot.HAND));
    }

    /**
     * Left clicks, by way of the arm swing.
     *
     * <p>Players swing constantly while mining, so this bails out as early as it can: nothing
     * to do when no listener is registered and the config has no left-click action either.</p>
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        if (plugin.settings().interactionLeftClick == Settings.ClickAction.NONE && nobodyListening()) {
            return;
        }

        Player player = event.getPlayer();
        double reach = plugin.settings().maxDistance;
        Optional<Placement> found = plugin.placed().lookingAt(player, reach);
        if (found.isEmpty()) return;

        Placement model = found.get();
        // Same rule as the right button: no hitbox, no click. Otherwise a swing at a model
        // would count while the block behind it breaks, which reads as a bug from both ends.
        if (!plugin.hitboxes().has(model)) return;
        if (behindTerrain(player, model, reach)) return;

        handle(player, model, BdModelClickEvent.Action.LEFT, null);
    }

    private boolean nobodyListening() {
        return BdModelClickEvent.getHandlerList().getRegisteredListeners().length == 0;
    }

    /** Is there solid ground between the player and the model? Then they hit that, not this. */
    private boolean behindTerrain(Player player, Placement model, double reach) {
        Location eye = player.getEyeLocation();
        RayTraceResult onModel = model.bounds().rayTrace(eye.toVector(), eye.getDirection(), reach);
        if (onModel == null) return true;

        RayTraceResult onBlock = player.rayTraceBlocks(reach);
        if (onBlock == null) return false;
        return onBlock.getHitPosition().distanceSquared(eye.toVector())
                < onModel.getHitPosition().distanceSquared(eye.toVector());
    }

    private void handle(Player player, Placement model, BdModelClickEvent.Action action,
                        EquipmentSlot hand) {
        // Placement mode owns both mouse buttons - a click there means "put it down".
        if (plugin.placement().isPlacing(player)) return;

        String permission = plugin.settings().interactionPermission;
        if (!permission.isBlank() && !player.hasPermission(permission)) return;

        if (onCooldown(player, model, action)) return;

        BdModelClickEvent event = new BdModelClickEvent(player, model, action, hand);
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        run(player, model, action == BdModelClickEvent.Action.RIGHT
                ? plugin.settings().interactionRightClick
                : plugin.settings().interactionLeftClick);
    }

    /** One entry per player and button, so left and right do not shadow each other. */
    private boolean onCooldown(Player player, Placement model, BdModelClickEvent.Action action) {
        UUID key = UUID.nameUUIDFromBytes(
                (player.getUniqueId() + "|" + model.id() + "|" + action).getBytes());
        long now = System.currentTimeMillis();
        Long previous = lastClick.get(key);
        if (previous != null && now - previous < COOLDOWN_MS) return true;
        lastClick.put(key, now);

        // The map would otherwise grow for every model anyone ever pokes.
        if (lastClick.size() > 512) {
            lastClick.entrySet().removeIf(entry -> now - entry.getValue() > 60_000);
        }
        return false;
    }

    private void run(Player player, Placement model, Settings.ClickAction action) {
        switch (action) {
            case NONE -> {
                // Someone else's job.
            }
            case TOGGLE -> toggle(player, model);
            case CYCLE -> cycle(player, model);
        }
    }

    /** Running becomes stopped, stopped becomes running, always the same animation. */
    private void toggle(Player player, Placement model) {
        if (plugin.animations().isPlaying(model.id())) {
            plugin.api().stop(model.id());
            return;
        }
        plugin.api().play(model, model.animationName(), model.animationSpeed(),
                plugin.settings().interactionLoop, started -> failNote(player, started));
    }

    /**
     * Steps to the next animation, and off the end back to standing still.
     *
     * <p>So a model with two of them goes: first, second, stopped, first again. A model with
     * only one behaves exactly like {@link #toggle}.</p>
     */
    private void cycle(Player player, Placement model) {
        plugin.api().animations(model, names -> {
            if (names.isEmpty()) {
                Msg.error(player, "That model has no animations.");
                return;
            }

            boolean running = plugin.animations().isPlaying(model.id());
            int current = running ? names.indexOf(model.animationName()) : -1;
            // A running model whose name we do not recognise counts as being on the first one.
            if (running && current < 0) current = 0;

            int next = current + 1;
            if (running && next >= names.size()) {
                plugin.api().stop(model.id());
                return;
            }
            plugin.api().play(model, names.get(next), model.animationSpeed(),
                    plugin.settings().interactionLoop, started -> failNote(player, started));
        }, error -> Msg.error(player, Msg.escape(error)));
    }

    private void failNote(Player player, boolean started) {
        if (!started && player.isOnline()) {
            Msg.error(player, "That animation could not be started.");
        }
    }

    private Optional<Placement> modelOf(Entity entity) {
        String id = entity.getPersistentDataContainer()
                .get(plugin.keyModelId(), PersistentDataType.STRING);
        if (id == null) return Optional.empty();
        try {
            return plugin.placed().get(UUID.fromString(id));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
