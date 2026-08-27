package de.phillip.bdpaste.api;

import de.phillip.bdpaste.registry.Placement;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * An animation stopped running.
 *
 * <p>Fires whoever started it - a player with {@code /bdpaste animate}, a click, or your own
 * plugin - so it is the reliable way to know a model is done. {@link Reason} says why.</p>
 *
 * <pre>{@code
 * @EventHandler
 * public void onEnd(BdAnimationEndEvent event) {
 *     if (event.getReason() != BdAnimationEndEvent.Reason.FINISHED) return;
 *     if (!event.getAnimation().equals("open")) return;
 *
 *     openDoors.add(event.getModel().id());
 * }
 * }</pre>
 *
 * <p>Not cancellable - it reports something that already happened. Starting the next animation
 * from inside a handler is fine.</p>
 */
public class BdAnimationEndEvent extends Event {

    /** Why an animation is no longer running. */
    public enum Reason {
        /**
         * It played all the way through. Only a one-shot ever gets here - a looping animation
         * has no end of its own. The model keeps the pose it finished in.
         */
        FINISHED,
        /**
         * Something stopped it: {@code /bdpaste animate off}, {@link BdPasteApi#stop}, a click,
         * or another animation taking over the same model. The model is back in its resting
         * pose, unless a different one started right after.
         */
        STOPPED,
        /**
         * The entities went away underneath it - the chunk unloaded, or the model was removed.
         * Nothing was posed and nothing was reset.
         */
        GONE
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Placement model;
    private final String animation;
    private final Reason reason;

    public BdAnimationEndEvent(@NotNull Placement model, @NotNull String animation,
                               @NotNull Reason reason) {
        this.model = model;
        this.animation = animation;
        this.reason = reason;
    }

    @NotNull
    public Placement getModel() {
        return model;
    }

    /** The animation that stopped, by the name the editor gave it. */
    @NotNull
    public String getAnimation() {
        return animation;
    }

    @NotNull
    public Reason getReason() {
        return reason;
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
