package de.phillip.bdpaste.api;

import de.phillip.bdpaste.registry.Placement;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A model is about to be taken out of the world.
 *
 * <p>Fires before anything is removed, so cancelling leaves the model exactly where it is.
 * Use it to protect models your own plugin put down, or to clean up state that hangs off
 * one.</p>
 *
 * <p>Does not fire for {@code /bdpaste cleanup}, which is the admin escape hatch for wiping
 * everything in an area whether it is on record or not.</p>
 */
public class BdModelRemoveEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Placement model;
    private boolean cancelled;

    public BdModelRemoveEvent(@NotNull Placement model) {
        this.model = model;
    }

    @NotNull
    public Placement getModel() {
        return model;
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
