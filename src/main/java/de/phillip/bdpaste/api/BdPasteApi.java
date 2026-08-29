package de.phillip.bdpaste.api;

import de.phillip.bdpaste.registry.Placement;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * What other plugins get to work with.
 *
 * <p>Grab it from the services manager:</p>
 *
 * <pre>{@code
 * RegisteredServiceProvider<BdPasteApi> provider =
 *         Bukkit.getServicesManager().getRegistration(BdPasteApi.class);
 * BdPasteApi bdpaste = provider == null ? null : provider.getProvider();
 * }</pre>
 *
 * <p>Add BDPaste to your {@code plugin.yml} under {@code softdepend} so it loads first, and
 * keep the null check - a server may well be running without it.</p>
 *
 * <p>Everything here is main-thread only. The methods that have to read a model file off the
 * disk take callbacks instead of blocking, and those callbacks come back on the main thread, so
 * they are safe to touch entities from.</p>
 *
 * <p>Version 8 added {@link #clickCommand} and {@link #setClickCommand}: a model can carry a
 * command it runs when somebody right clicks it, which covers the common case - a sofa you
 * can sit on - without anybody having to write a listener for it.</p>
 *
 * <p>Version 7 added {@link #place}, so a plugin can put a model into the world by itself.
 * Before that the only way in was a player standing there with a crosshair, which made anything
 * built out of models - a shop front, a quest marker, a decorated arena - something that had to
 * be placed by hand once and could never be rebuilt from a config file.</p>
 */
public interface BdPasteApi {

    /**
     * Bumped whenever something here changes in a way that could break a caller. Check it if
     * you want to be strict about which BDPaste you are talking to.
     */
    int VERSION = 8;

    // ------------------------------------------------------------------ models in the world

    /** Every model currently placed, across all worlds. */
    List<Placement> models();

    Optional<Placement> model(UUID id);

    /** Models whose anchor sits within {@code radius} blocks of {@code center}. */
    List<Placement> modelsNear(Location center, double radius);

    /**
     * The model the player is aiming at, tested against each model's bounding box.
     *
     * <p>This is a ray cast and needs no hitbox entity, so it also finds models placed before
     * hitboxes were switched on.</p>
     */
    Optional<Placement> modelLookingAt(Player player, double maxDistance);

    /** Names of the files in the models folder, which is what {@code /bdpaste place} takes. */
    List<String> libraryModels();

    // ------------------------------------------------------------------ animation

    /**
     * The animations a placed model carries, in the order the editor lists them.
     *
     * <p>Reading them means loading the model file, so the answer arrives later. Both callbacks
     * run on the main thread; {@code failed} gets a message fit to show a player.</p>
     */
    void animations(Placement model, Consumer<List<String>> found, Consumer<String> failed);

    boolean isAnimating(UUID id);

    /**
     * Which animation is playing on this model at this moment, or empty when none is.
     *
     * <p>This is the live state, not what was written down. After a one-shot has run its course
     * it goes empty again, while {@code Placement.animationName()} still names the one that
     * played - useful if you want to know what it last did, but not the same question.</p>
     *
     * <pre>{@code
     * String now = bdpaste.currentAnimation(model.id()).orElse("nothing");
     * }</pre>
     */
    Optional<String> currentAnimation(UUID id);

    /**
     * Starts an animation on a loop, or switches to a different one if the model is already
     * running.
     *
     * @param animation which one, or {@code null} for the first
     * @param speed     1.0 is the speed the model was built at
     * @param done      handed {@code true} when it actually started
     */
    void play(Placement model, String animation, double speed, Consumer<Boolean> done);

    /**
     * The same, with a say in whether it repeats.
     *
     * <p>With {@code loop} false the animation runs through once and the model keeps the pose
     * it ended in - a door that swings open stays open. {@link #stop} is what puts it back into
     * its resting pose. A one-shot is also not written down as running, so it will not start
     * itself again after a restart, and {@link #isAnimating} goes false the moment it
     * finishes.</p>
     *
     * @param loop true repeats forever, false plays it once
     */
    void play(Placement model, String animation, double speed, boolean loop, Consumer<Boolean> done);

    /**
     * The same again, and it tells you when the animation is over.
     *
     * <p>{@code ended} runs once, on the main thread, whichever way it stopped - played through,
     * stopped by somebody, or the entities went away. That last part matters: a one-shot in a
     * chunk that unloads halfway never finishes, and you would wait forever for a callback that
     * only fired on success.</p>
     *
     * <pre>{@code
     * bdpaste.play(model, "open", 1.0, false,
     *         started -> { },
     *         reason -> {
     *             if (reason == BdAnimationEndEvent.Reason.FINISHED) doorIsOpen.add(model.id());
     *         });
     * }</pre>
     *
     * <p>On a loop it only fires when something stops it. If you would rather hear about every
     * model on the server, listen for {@link BdAnimationEndEvent} instead - that fires whoever
     * started the animation, including a player typing {@code /bdpaste animate}.</p>
     *
     * @param started handed {@code true} when it actually started; {@code ended} will not run
     *                at all if this is {@code false}
     * @param ended   told once when the animation stops, or {@code null}
     */
    void play(Placement model, String animation, double speed, boolean loop,
              Consumer<Boolean> started, Consumer<BdAnimationEndEvent.Reason> ended);

    /** Stops it and puts the model back into its resting pose. Was it running? */
    boolean stop(UUID id);

    // ------------------------------------------------------------------ putting one down

    /**
     * Puts a model from the library into the world, with nobody standing there to do it.
     *
     * <p>The counterpart to {@link #remove}. {@code source} is the library name -
     * the file in the models folder without its extension, the same thing
     * {@code /bdpaste place} takes and the same thing {@link #libraryModels} lists.</p>
     *
     * <p>Call it from the main thread. The model file is read off the disk on another one, so
     * the answer always arrives on a later tick - never before this method has returned, not
     * even for a name that does not exist. Both callbacks run on the main thread, which is what
     * makes it safe to go on and animate or label the model straight away:</p>
     *
     * <pre>{@code
     * bdpaste.place("fermier", spot,
     *         model -> bdpaste.play(model, "labourer", 1.0, true, started -> { }),
     *         reason -> sender.sendMessage(reason));
     * }</pre>
     *
     * <p>The model lands standing on {@code at}, unturned and at its own size, and is written to
     * the registry like any other - it survives a restart, it can be clicked, and
     * {@link BdModelPlaceEvent} fires for it. It has no owner, so only somebody with
     * {@code bdpaste.admin} can edit or remove it in game.</p>
     *
     * @param placed handed the finished record, once the entities are up
     * @param failed handed a message fit to show a player - no such model, no world, or not a
     *               single part could be spawned
     */
    void place(String source, Location at, Consumer<Placement> placed, Consumer<String> failed);

    /**
     * The same, turned and resized.
     *
     * <p>{@code yaw} is degrees clockwise from south, the way Minecraft counts everywhere else,
     * and {@code scale} multiplies the model's own size - 1 leaves it alone. Pitch, roll and the
     * fine offsets a player can dial in are not here on purpose: they exist for nudging a
     * preview into place by eye, and something placing models from a config file wants a spot
     * and a heading, not seven numbers.</p>
     *
     * @param yaw   degrees clockwise from south
     * @param scale multiplier on the model's own size; 1 is unchanged
     */
    void place(String source, Location at, float yaw, float scale,
               Consumer<Placement> placed, Consumer<String> failed);

    // ------------------------------------------------------------------ changing things

    /**
     * Removes a model and its entities.
     *
     * @return how many entities went away, or {@code -1} if a listener cancelled
     *         {@link BdModelRemoveEvent}
     */
    int remove(Placement model);

    /**
     * The floating name over a model, as MiniMessage source; empty when it has none.
     *
     * <p>What comes back is what was typed - {@code <#ff8800>Shop} - not the rendered
     * component, so it can be shown back for editing or matched on.</p>
     */
    String label(Placement model);

    /**
     * Hangs a name over a model, or clears it with {@code null} or an empty string.
     *
     * <p>MiniMessage, so hex colours and the rest of the tags work. It never rejects markup -
     * a tag it does not know stays on screen as plain text - so the only thing that can turn
     * this down is length.</p>
     *
     * @return false when the text is longer than 256 characters, in which case nothing changed
     */
    boolean setLabel(Placement model, String miniMessage);

    /**
     * The command this model runs when it is right clicked, without its leading slash, or an
     * empty string when it has none.
     *
     * <p>A {@code console:} prefix means it runs from the console rather than as the player.</p>
     */
    String clickCommand(Placement model);

    /**
     * Sets that command, or clears it with an empty string.
     *
     * <p>It runs <em>instead of</em> whatever {@code interaction.right-click} says in the
     * config, not as well as it - a sofa somebody can sit on should not also cycle through its
     * animations. The placeholders {@code %player%}, {@code %uuid%}, {@code %model%},
     * {@code %source%}, {@code %id%}, {@code %world%}, {@code %x%}, {@code %y%} and {@code %z%}
     * are filled in when it runs.</p>
     *
     * <p>Nothing happens on a model with no hitbox, since nobody can click one.
     * {@link #setHitbox} sorts that out.</p>
     *
     * @return false when the command is longer than 256 characters, in which case nothing changed
     */
    boolean setClickCommand(Placement model, String command);

    /**
     * Whether this model is set to be clickable.
     *
     * <p>Read off the record, so it answers the same whether or not the interaction entities
     * happen to be up at this moment - they are taken down with their chunk and put back a
     * moment after it loads.</p>
     */
    boolean hasHitbox(Placement model);

    /**
     * Gives a model a hitbox or takes it away.
     *
     * <p>A hitbox is one or more vanilla {@code interaction} entities wrapped around the model.
     * Without one a display entity cannot be clicked at all - that is a vanilla limitation, not
     * something BDPaste chose.</p>
     *
     * <p>Kept on the model's record, so it survives a restart. The entities themselves follow
     * a tick or two later when switching on, because how to wrap a model has to be read out of
     * its file first.</p>
     *
     * @return true if this changed anything - false when it was already set that way
     */
    boolean setHitbox(Placement model, boolean enabled);
}
