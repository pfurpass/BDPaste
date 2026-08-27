package de.phillip.bdpaste.api;

import de.phillip.bdpaste.registry.Placement;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A player clicked a placed model.
 *
 * <p>Only fires for models that have a hitbox, since a display entity on its own cannot be
 * clicked. See {@link BdPasteApi#setHitbox}.</p>
 *
 * <p>A right click is the hitbox being used, and is exact. A left click reaches us as an arm
 * swing checked against the model the player is aiming at, because an interaction entity is
 * not damageable and never produces a damage event - so a swing that lands on a mob standing
 * in front of the model can still count as a click on it. Blocks in the way are ruled out.</p>
 *
 * <p>Cancelling stops BDPaste from doing its own thing with the click - whatever
 * {@code interaction.right-click} and {@code interaction.left-click} are set to in the config.
 * That is how you take a model over completely:</p>
 *
 * <pre>{@code
 * @EventHandler
 * public void onClick(BdModelClickEvent event) {
 *     if (event.getAction() != BdModelClickEvent.Action.RIGHT) return;
 *     if (!event.getModel().model().equals("shop")) return;
 *
 *     event.setCancelled(true);
 *     openShop(event.getPlayer());
 * }
 * }</pre>
 */
public class BdModelClickEvent extends Event implements Cancellable {

    /** Which mouse button it was. */
    public enum Action {
        /** Attacked - left mouse button. */
        LEFT,
        /** Used - right mouse button. */
        RIGHT
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Placement model;
    private final Action action;
    private final EquipmentSlot hand;
    private boolean cancelled;

    public BdModelClickEvent(@NotNull Player player, @NotNull Placement model,
                             @NotNull Action action, @Nullable EquipmentSlot hand) {
        this.player = player;
        this.model = model;
        this.action = action;
        this.hand = hand;
    }

    @NotNull
    public Player getPlayer() {
        return player;
    }

    /** The model that was clicked, as BDPaste has it on record right now. */
    @NotNull
    public Placement getModel() {
        return model;
    }

    @NotNull
    public Action getAction() {
        return action;
    }

    /** The hand used, or {@code null} for a left click, which has no hand of its own. */
    @Nullable
    public EquipmentSlot getHand() {
        return hand;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
