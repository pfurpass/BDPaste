package de.phillip.bdpaste.api;

import de.phillip.bdpaste.BDPastePlugin;
import de.phillip.bdpaste.anim.AnimationPlayer;
import de.phillip.bdpaste.place.PlacementSession;
import de.phillip.bdpaste.registry.Placement;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Wires {@link BdPasteApi} onto the plugin's own pieces. Nothing clever happens here. */
public final class BdPasteApiImpl implements BdPasteApi {

    private final BDPastePlugin plugin;

    public BdPasteApiImpl(BDPastePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<Placement> models() {
        return plugin.placed().all();
    }

    @Override
    public Optional<Placement> model(UUID id) {
        return plugin.placed().get(id);
    }

    @Override
    public List<Placement> modelsNear(Location center, double radius) {
        return plugin.placed().near(center, radius);
    }

    @Override
    public Optional<Placement> modelLookingAt(Player player, double maxDistance) {
        return plugin.placed().lookingAt(player, maxDistance);
    }

    @Override
    public List<String> libraryModels() {
        return plugin.library().names();
    }

    @Override
    public void animations(Placement model, Consumer<List<String>> found, Consumer<String> failed) {
        plugin.library().loadAsync(model.source(),
                loaded -> found.accept(loaded.animationNames()),
                failed);
    }

    @Override
    public boolean isAnimating(UUID id) {
        return plugin.animations().isPlaying(id);
    }

    @Override
    public Optional<String> currentAnimation(UUID id) {
        return plugin.animations().currentAnimation(id);
    }

    @Override
    public void play(Placement model, String animation, double speed, Consumer<Boolean> done) {
        play(model, animation, speed, true, done);
    }

    @Override
    public void play(Placement model, String animation, double speed, boolean loop,
                     Consumer<Boolean> done) {
        play(model, animation, speed, loop, done, null);
    }

    @Override
    public void play(Placement model, String animation, double speed, boolean loop,
                     Consumer<Boolean> done, Consumer<BdAnimationEndEvent.Reason> ended) {
        // Switching tracks means the running one has to be taken down first, or both would
        // fight over the same displays.
        plugin.animations().stop(model.id());

        String wanted = animation == null ? "" : animation;
        plugin.animations().startAsync(model, speed, wanted, loop, ended, result -> {
            boolean started = result == AnimationPlayer.Result.STARTED;
            // Only on success, and only then: a start that failed - a name the model does not
            // have, most likely - must leave the record alone. Writing the name anyway would
            // saddle the model with an animation it cannot play, and a later /bdpaste animate
            // with no arguments would reach for exactly that one.
            //
            // The loop flag is what makes a model pick its animation back up after a restart,
            // which is a loop thing; a one-shot is recorded as not running.
            if (started) plugin.placed().setAnimating(model.id(), loop, speed, wanted);
            if (done != null) done.accept(started);
        });
    }

    @Override
    public boolean stop(UUID id) {
        boolean running = plugin.animations().isPlaying(id);
        // reset, not stop: a one-shot that already finished is no longer running but is still
        // standing in its final pose, and this is what a caller means by "put it back".
        plugin.animations().reset(id);
        plugin.placed().get(id).ifPresent(model ->
                plugin.placed().setAnimating(id, false, model.animationSpeed(), model.animationName()));
        return running;
    }

    @Override
    public int remove(Placement model) {
        return plugin.placed().delete(model);
    }

    @Override
    public String label(Placement model) {
        return plugin.placed().get(model.id()).map(Placement::label).orElse(model.label());
    }

    @Override
    public boolean setLabel(Placement model, String miniMessage) {
        String text = miniMessage == null ? "" : miniMessage;
        if (text.length() > 256) return false;
        plugin.placed().setLabel(model.id(), text);
        return true;
    }

    @Override
    public void place(String source, Location at,
                      Consumer<Placement> placed, Consumer<String> failed) {
        place(source, at, PlacementSession.Pose.DEFAULT, placed, failed);
    }

    @Override
    public void place(String source, Location at, float yaw, float scale,
                      Consumer<Placement> placed, Consumer<String> failed) {
        place(source, at, new PlacementSession.Pose(yaw, 0f, 0f, scale, 0, 0, 0), placed, failed);
    }

    private void place(String source, Location at, PlacementSession.Pose pose,
                       Consumer<Placement> placed, Consumer<String> failed) {
        plugin.library().loadAsync(source,
                model -> plugin.placement().placeHeadless(model, source, at, pose, placed, failed),
                failed);
    }

    @Override
    public boolean hasHitbox(Placement model) {
        // From the registry, not from the handed-in snapshot, which may be old.
        return plugin.placed().get(model.id()).map(Placement::clickable).orElse(false);
    }

    @Override
    public boolean setHitbox(Placement model, boolean enabled) {
        return plugin.placed().setClickable(model.id(), enabled);
    }
}
