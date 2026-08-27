package de.phillip.bdpaste.api;

import de.phillip.bdpaste.registry.Placement;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A model was put into the world and is on record.
 *
 * <p>Fires after the entities exist, so this is the place to hang your own state off a model -
 * a shop behind it, a marker, whatever. It also fires when a model is moved or replaced,
 * because both end in a fresh placement.</p>
 *
 * <p>Not cancellable: by the time it runs, the model is already standing there. Use
 * {@link BdPasteApi#remove} if you want it gone again.</p>
 */
public class BdModelPlaceEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Placement model;

    public BdModelPlaceEvent(@NotNull Placement model) {
        this.model = model;
    }

    @NotNull
    public Placement getModel() {
        return model;
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
