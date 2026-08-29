package de.phillip.bdpaste.anim;

import de.phillip.bdpaste.BDPastePlugin;
import de.phillip.bdpaste.api.BdAnimationEndEvent;
import de.phillip.bdpaste.label.Labels;
import de.phillip.bdpaste.model.BakedAnimation;
import de.phillip.bdpaste.model.BdModel;
import de.phillip.bdpaste.model.BdPart;
import de.phillip.bdpaste.model.BdSound;
import de.phillip.bdpaste.model.NoteBlocks;
import de.phillip.bdpaste.place.Placements;
import de.phillip.bdpaste.spawn.ModelSpawner;
import de.phillip.bdpaste.registry.Placement;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Plays BDEngine keyframe tracks on placed models.
 *
 * <p>The transform of every animated display is rewritten every few ticks and the client is
 * told to interpolate across that gap, which is the same trick BDEngine's own datapack export
 * uses ({@code interpolation_duration:2, start_interpolation:0}). Parts with no keyframes
 * above them are never touched.</p>
 */
public final class AnimationPlayer {

    /** One model currently in motion. */
    private static final class Playing {
        final UUID id;
        final BdModel model;
        final Matrix4f user;
        final List<Display> displays = new ArrayList<>();
        final List<BdPart> parts = new ArrayList<>();
        /** Part index to display, only filled for baked playback. */
        final Map<Integer, Display> byIndex = new HashMap<>();
        /** Part index of each entry in {@link #displays}, so poses can be remembered. */
        final List<Integer> indices = new ArrayList<>();
        /** The last matrix actually sent for a part, to skip the ones that did not move. */
        final Map<Integer, Matrix4f> sent = new HashMap<>();
        final Location anchor;
        /** Baked frames from the datapack; null means the keyframes are evaluated instead. */
        final BakedAnimation baked;
        /** Which of the project timelines is running, or -1 while baked frames are used. */
        final int track;
        /** false plays the animation through once and leaves the model in its last pose. */
        final boolean loop;
        /** The name the editor gave this track, kept so it survives into the end event. */
        final String animationName;
        /** Told once, when this stops running. */
        final Consumer<BdAnimationEndEvent.Reason> onEnd;
        /** The note roll of this animation, or null when it is silent. */
        final BdSound sound;
        /**
         * Any one display of the model, whether it moves or not.
         *
         * <p>Used to tell whether the model is still there. A sound-only track poses nothing,
         * so there would otherwise be nothing left to ask.</p>
         */
        Display probe;
        /** How far down the roll the notes have been fired, so none is played twice. */
        double lastSoundStep = -1;
        /** The floating name over this model, if it has one and is meant to follow along. */
        TextDisplay label;
        /** Which part carries the label and where it sits on it - worked out once, at the start. */
        Labels.Ride labelRide;
        /** Where the label was last put, so it is only moved when it actually has to be. */
        Vector3f labelAt;
        double speed;
        double tick;
        int appliedTick = -1;

        Playing(UUID id, BdModel model, Matrix4f user, Location anchor, double speed,
                BakedAnimation baked, int track, boolean loop, String animationName,
                Consumer<BdAnimationEndEvent.Reason> onEnd, BdSound sound) {
            this.id = id;
            this.model = model;
            this.user = user;
            this.anchor = anchor;
            this.speed = speed;
            this.baked = baked;
            this.track = track;
            this.loop = loop;
            this.animationName = animationName;
            this.onEnd = onEnd;
            this.sound = sound;
        }
    }

    private final BDPastePlugin plugin;
    private final Map<UUID, Playing> playing = new HashMap<>();
    private final Set<UUID> starting = new HashSet<>();
    private BukkitTask task;
    private BukkitTask resumeTask;

    public AnimationPlayer(BDPastePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        int interval = plugin.settings().animationInterval;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickAll, interval, interval);
        // Models flagged as animating come back on their own once someone is nearby.
        resumeTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::resumeNearby, 60L, 40L);
    }

    public void stop() {
        if (task != null) task.cancel();
        if (resumeTask != null) resumeTask.cancel();
        for (Playing entry : List.copyOf(playing.values())) {
            restPose(entry);
        }
        playing.clear();
    }

    /**
     * The animation running on a model right now, by the name the editor gave it.
     *
     * <p>Read off what is actually playing rather than off the record, which only says what was
     * started last and stays behind after a one-shot has finished.</p>
     */
    public java.util.Optional<String> currentAnimation(UUID modelId) {
        Playing entry = playing.get(modelId);
        return entry == null || entry.animationName.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of(entry.animationName);
    }

    public boolean isPlaying(UUID modelId) {
        return playing.containsKey(modelId);
    }

    public int playingCount() {
        return playing.size();
    }

    // ------------------------------------------------------------------- ticking

    private void tickAll() {
        if (playing.isEmpty()) return;
        int interval = plugin.settings().animationInterval;
        double radius = plugin.settings().animationRadius;
        // A keyframe is not a game tick. The editor holds each one for 0.1s and exports its
        // datapack the same way - "schedule ... 0.1s" with interpolation_duration:2 - so one
        // keyframe is two game ticks, and a model played tick-for-tick runs at double speed.
        double perRun = (double) interval / plugin.settings().animationTicksPerKeyframe;

        for (Playing entry : List.copyOf(playing.values())) {
            if (entry.probe == null || !entry.probe.isValid()) {
                // Chunk unloaded or the model was removed - let it be picked up again later.
                end(entry, BdAnimationEndEvent.Reason.GONE);
                continue;
            }
            if (!hasViewer(entry.anchor, radius)) continue;

            entry.tick += perRun * entry.speed;

            if (entry.baked != null) {
                playBaked(entry, interval);
                continue;
            }

            // A note roll can outlast the keyframes, or be all there is. Whichever runs
            // longer sets the period, so the music is not cut off and a model that only makes
            // noise still has a timeline to walk along. Past its last keyframe an animation
            // holds its end pose, which is what the editor shows there too.
            double length = Math.max(entry.model.animationLength(entry.track), soundLength(entry));
            // Keyframes are numbered from zero, so the last pose of a 25 long track sits at 24.
            double last = Math.max(0, length - 1);
            double time = entry.loop
                    ? (length <= 0 ? 0 : ((entry.tick % length) + length) % length)
                    : Math.min(entry.tick, last);

            playNotes(entry, time);

            // Only worth watching out for when something is hanging over the model.
            boolean measure = entry.label != null && entry.label.isValid() && entry.labelRide != null;
            Vector3f carrier = null;

            for (int i = 0; i < entry.displays.size(); i++) {
                Display display = entry.displays.get(i);
                if (!display.isValid()) continue;
                Matrix4f pose = new Matrix4f(entry.user).mul(entry.parts.get(i).matrixAt(entry.track, time));
                apply(entry, entry.indices.get(i), display, pose, interval);
                if (measure && entry.indices.get(i) == entry.labelRide.part()) {
                    carrier = new Vector3f(pose.m30(), pose.m31(), pose.m32());
                }
            }
            if (carrier != null) moveLabel(entry, carrier);

            // The last pose has just gone out, so a one-shot is done - and stays in it.
            if (!entry.loop && entry.tick >= last) end(entry, BdAnimationEndEvent.Reason.FINISHED);
        }
    }

    /**
     * Takes an animation out of the loop and says so.
     *
     * <p>After {@link BdAnimationEndEvent.Reason#FINISHED} the model keeps the pose it ended
     * in rather than snapping back, which is what the editor does too: its non-looping export
     * hands over to {@code stop_anim}, and that only pauses. A door that swings open should
     * stay open. {@code /bdpaste animate off} is what puts a model back into its resting
     * pose.</p>
     *
     * <p>Removed from the map before anybody is told, so a handler is free to start the next
     * animation on the same model without tripping over the one that just ended.</p>
     */
    private void end(Playing entry, BdAnimationEndEvent.Reason reason) {
        if (playing.remove(entry.id) == null) return;

        // This runs from the middle of the tick loop, and the callback belongs to somebody
        // else. Letting it throw would take out every model still waiting its turn this tick,
        // so it is caught here and blamed on its owner instead.
        if (entry.onEnd != null) {
            try {
                entry.onEnd.accept(reason);
            } catch (RuntimeException | LinkageError ex) {
                plugin.getSLF4JLogger().warn(
                        "A plugin threw from its animation-end callback for model {} ({})",
                        entry.id, reason, ex);
            }
        }
        plugin.placed().get(entry.id).ifPresent(placement ->
                plugin.getServer().getPluginManager().callEvent(
                        new BdAnimationEndEvent(placement, entry.animationName, reason)));
    }

    /**
     * Keeps the floating name over a model that is moving underneath it.
     *
     * <p>It rides the part {@link Labels#ride} picked - the top of the resting model, the head
     * on anything shaped like a person - and it follows that part in all three directions. Only
     * the height used to be followed, so the rat danced off sideways and left its name standing
     * where it had been; over one round of that dance its head travels 2.3 blocks.</p>
     *
     * <p>What is tracked is the part's origin, not the top of its box, and the distance between
     * the two was fixed at the start. That is what stops the trembling: a display's box is
     * axis-aligned, so it measures taller the moment the part inside it turns, and reading the
     * height off the box every tick rippled the label by a third of a block on the farmer while
     * his hat only tilted.</p>
     */
    private void moveLabel(Playing entry, Vector3f carrier) {
        Vector3f wanted = entry.labelRide.at(carrier);
        // Small enough to follow a nod of the head, large enough not to spend a packet on noise.
        if (entry.labelAt != null && entry.labelAt.distanceSquared(wanted) < 0.03 * 0.03) return;

        entry.labelAt = wanted;
        Location at = entry.label.getLocation();
        entry.label.teleport(new Location(entry.anchor.getWorld(),
                entry.anchor.getX() + wanted.x,
                entry.anchor.getY() + wanted.y,
                entry.anchor.getZ() + wanted.z,
                at.getYaw(), at.getPitch()));
    }

    /** The note roll measured in keyframes, so it can be compared with an animation. */
    private double soundLength(Playing entry) {
        return entry.sound == null ? 0 : entry.sound.lengthInKeyframes();
    }

    /**
     * Fires whatever notes have fallen due since the last update.
     *
     * <p>The two timelines are counted differently: a note step spans
     * {@link BdSound#keyframesPerStep()} of the animation. Both start at zero, so one divides
     * straight into the other.</p>
     *
     * <p>Played at the model, not at the listener, so it fades with distance the way anything
     * else in the world does. The category is the one the editor's own export writes -
     * {@code playsound <sound> block @a ~ ~ ~} - so the player's blocks slider governs it.</p>
     */
    private void playNotes(Playing entry, double time) {
        BdSound sound = entry.sound;
        if (sound == null || !plugin.settings().animationSound) return;

        World world = entry.anchor.getWorld();
        if (world == null) return;

        // Exactly what the editor does: floor(currentTime / divisor), counted in keyframes.
        double step = time / sound.keyframesPerStep();
        // The loop came round again, so the whole roll is due afresh.
        if (step < entry.lastSoundStep) entry.lastSoundStep = -1;

        float scale = (float) plugin.settings().animationSoundVolume;
        for (BdSound.Note note : sound.notes()) {
            if (note.step() <= entry.lastSoundStep) continue;
            // Sorted by step, so once one is in the future the rest are too.
            if (note.step() > step) break;
            // A third of these models sits outside what the client will play, so the note
            // gets moved somewhere it can be heard - see NoteBlocks for what gives.
            NoteBlocks.Playable playable = NoteBlocks.playable(
                    note.sound(), note.pitch(), plugin.settings().animationSoundMode,
                    plugin.settings().animationSoundLow, plugin.settings().animationSoundHigh);
            world.playSound(entry.anchor, playable.sound(), SoundCategory.BLOCKS,
                    note.volume() * scale, playable.pitch());
        }
        entry.lastSoundStep = step;
    }

    /**
     * Steps through the baked frames. A tick only lists the parts that changed, so every tick
     * between the last one applied and the current one has to be replayed - skipping them
     * would leave parts behind on an old pose.
     */
    private void playBaked(Playing entry, int interval) {
        int length = entry.baked.length();
        if (length <= 0) return;

        int raw = (int) Math.floor(entry.tick);
        boolean done = !entry.loop && raw >= length - 1;
        int target = entry.loop ? Math.floorMod(raw, length) : Math.min(raw, length - 1);
        if (target == entry.appliedTick) {
            if (done) end(entry, BdAnimationEndEvent.Reason.FINISHED);
            return;
        }

        int from = entry.appliedTick;
        if (from < 0 || target < from) {
            // First run, or the loop wrapped: tick 0 carries every part, so it resets the pose.
            applyFrame(entry, 0, interval);
            from = 0;
        }
        for (int t = from + 1; t <= target; t++) {
            applyFrame(entry, t, interval);
        }
        entry.appliedTick = target;
        if (done) end(entry, BdAnimationEndEvent.Reason.FINISHED);
    }

    private void applyFrame(Playing entry, int tick, int interval) {
        for (Map.Entry<Integer, Matrix4f> change : entry.baked.at(tick).entrySet()) {
            Display display = entry.byIndex.get(change.getKey());
            if (display == null || !display.isValid()) continue;
            apply(entry, change.getKey(), display,
                    new Matrix4f(entry.user).mul(change.getValue()), interval);
        }
    }

    /**
     * How long the client is told to ease, in ticks.
     *
     * <p>One tick longer than the gap between updates, on purpose. Told to ease over exactly
     * the gap, the client finishes its glide and then sits still until the next packet lands -
     * and packets do land a tick late now and then, because a server tick is not a metronome.
     * That idle tick is the small stutter. With a tick of overlap the display always still has
     * runway left when the next pose arrives, so it never runs dry; it trails the exact pose by
     * well under a tick, which nobody can see.</p>
     */
    private int easeTicks(int interval) {
        return interval + 1;
    }

    /**
     * Hands one part its new pose and makes the client ease into it.
     *
     * <p>The client only restarts its easing when {@code start_interpolation} reaches it, and
     * the server sends a metadata field only when it sees the value change - so writing 0 over
     * a 0 that is already there is dropped on the floor, and the display snaps to the new
     * matrix instead of gliding into it. Nudging the value first makes the write real.
     * BDEngine never runs into this: its datapack rewrites the whole entity with
     * {@code data merge}, which puts every field back on the wire on every frame.</p>
     *
     * <p>A part that has not moved is skipped entirely. The editor does the same - its keyframe
     * functions only list what changed - and on the farmer that is a fifth of the traffic.</p>
     */
    private void apply(Playing entry, int index, Display display, Matrix4f matrix, int interval) {
        Matrix4f previous = entry.sent.get(index);
        if (previous != null && previous.equals(matrix, 1.0E-6f)) return;
        entry.sent.put(index, matrix);

        display.setInterpolationDelay(1);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(easeTicks(interval));
        ModelSpawner.pose(display, matrix);
    }

    /** Nobody around means nobody to see it, so do not burn packets on it. */
    private boolean hasViewer(Location anchor, double radius) {
        if (anchor.getWorld() == null) return false;
        double squared = radius * radius;
        for (Player player : anchor.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(anchor) <= squared) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ starting

    /** Result of trying to start a model, so the caller can explain what happened. */
    public enum Result { STARTED, NO_ANIMATION, NO_SUCH_ANIMATION, NO_ENTITIES, TOO_MANY, DISABLED }

    public void startAsync(Placement placement, double speed, String animationName,
                           java.util.function.Consumer<Result> done) {
        startAsync(placement, speed, animationName, true, done);
    }

    /**
     * @param loop false plays the animation once and leaves the model in its final pose
     */
    public void startAsync(Placement placement, double speed, String animationName, boolean loop,
                           java.util.function.Consumer<Result> done) {
        startAsync(placement, speed, animationName, loop, null, done);
    }

    /**
     * @param onEnd told once when this animation stops for whatever reason, or {@code null}
     */
    public void startAsync(Placement placement, double speed, String animationName, boolean loop,
                           Consumer<BdAnimationEndEvent.Reason> onEnd,
                           java.util.function.Consumer<Result> done) {
        if (!plugin.settings().animationEnabled) {
            done.accept(Result.DISABLED);
            return;
        }
        if (playing.size() >= plugin.settings().animationMaxPlaying) {
            done.accept(Result.TOO_MANY);
            return;
        }
        if (!starting.add(placement.id())) return;

        plugin.library().loadAsync(placement.source(),
                model -> {
                    starting.remove(placement.id());
                    done.accept(begin(placement, model, speed, animationName, loop, onEnd));
                },
                error -> {
                    starting.remove(placement.id());
                    done.accept(Result.NO_ENTITIES);
                });
    }

    private Result begin(Placement placement, BdModel model, double speed, String animationName,
                         boolean loop, Consumer<BdAnimationEndEvent.Reason> onEnd) {
        // A datapack brings finished frames; anything else is played from its keyframes.
        BakedAnimation baked = model.bakedAnimation(animationName);
        int track = baked != null ? -1 : model.trackIndex(animationName);
        if (baked == null && track < 0) {
            return model.hasAnimation() || model.hasBakedAnimation()
                    ? Result.NO_SUCH_ANIMATION
                    : Result.NO_ANIMATION;
        }

        Location anchor = placement.location();
        if (anchor == null) return Result.NO_ENTITIES;

        Matrix4f user = Placements.userMatrix(plugin, placement, model);

        // Resolve the name once, here, while both the track and the baked frames are at hand.
        String resolved = baked != null ? baked.name()
                : (track >= 0 && track < model.tracks().size() ? model.tracks().get(track).name() : "");

        // Only project files carry a note roll; a datapack bakes its sounds into functions,
        // which is a different thing and not read.
        BdSound sound = baked != null ? null : model.sound(track);

        Playing entry = new Playing(placement.id(), model, user, anchor, speed, baked, track, loop,
                resolved, onEnd, sound);
        String wanted = placement.id().toString();

        for (Entity entity : anchor.getWorld().getNearbyEntities(anchor, 4, 4, 4)) {
            if (!(entity instanceof Display display)) continue;
            var pdc = entity.getPersistentDataContainer();
            if (!wanted.equals(pdc.get(plugin.keyModelId(), PersistentDataType.STRING))) continue;

            Integer index = pdc.get(plugin.keyPartIndex(), PersistentDataType.INTEGER);
            if (index == null || index < 0 || index >= model.size()) continue;

            if (entry.probe == null) entry.probe = display;

            BdPart part = model.parts().get(index);
            if (baked != null) {
                // Baked frames address parts by index, and any of them may move.
                entry.byIndex.put(index, display);
                entry.indices.add(index);
                entry.displays.add(display);
                entry.parts.add(part);
                continue;
            }
            if (!part.animated(track)) continue;

            entry.indices.add(index);
            entry.displays.add(display);
            entry.parts.add(part);
        }

        if (plugin.settings().labelFollowAnimation && !placement.label().isBlank()) {
            entry.label = plugin.labels().find(placement).orElse(null);
            if (entry.label != null) entry.labelRide = plugin.labels().ride(placement, model);
        }

        // No entity of this model was found at all - most likely one placed before parts
        // carried an index. A track that only plays notes poses nothing, and that is fine.
        if (entry.probe == null) return Result.NO_ENTITIES;
        if (entry.displays.isEmpty() && sound == null) return Result.NO_ENTITIES;

        playing.put(placement.id(), entry);
        return Result.STARTED;
    }

    // ------------------------------------------------------------------ stopping

    /**
     * Takes a model out of the loop and puts it back where it started.
     *
     * <p>Only for internal use, where something else is about to take over - switching tracks,
     * say. Use {@link #reset} for a stop that a player asked for.</p>
     */
    public void stop(UUID modelId) {
        Playing entry = playing.get(modelId);
        if (entry == null) return;
        restPose(entry);
        end(entry, BdAnimationEndEvent.Reason.STOPPED);
    }

    /**
     * Stops whatever is running and makes sure the model ends up in its resting pose.
     *
     * <p>The difference to {@link #stop} matters after a one-shot: that leaves the model
     * standing in its final pose and is no longer running, so there is no live entry to read
     * the displays off. They have to be looked up again, which means loading the model file,
     * which is why this one cannot be used on a path that starts something right after.</p>
     */
    public void reset(UUID modelId) {
        Playing entry = playing.get(modelId);
        if (entry != null) {
            restPose(entry);
            end(entry, BdAnimationEndEvent.Reason.STOPPED);
            return;
        }
        plugin.placed().get(modelId).ifPresent(this::resetAsync);
    }

    private void resetAsync(Placement placement) {
        Location anchor = placement.location();
        if (anchor == null || anchor.getWorld() == null) return;

        plugin.library().loadAsync(placement.source(), model -> {
            Matrix4f user = Placements.userMatrix(plugin, placement, model);
            String wanted = placement.id().toString();

            for (Entity entity : anchor.getWorld().getNearbyEntities(anchor, 4, 4, 4)) {
                if (!(entity instanceof Display display)) continue;
                var pdc = entity.getPersistentDataContainer();
                if (!wanted.equals(pdc.get(plugin.keyModelId(), PersistentDataType.STRING))) continue;

                Integer index = pdc.get(plugin.keyPartIndex(), PersistentDataType.INTEGER);
                if (index == null || index < 0 || index >= model.size()) continue;

                display.setInterpolationDelay(0);
                display.setInterpolationDuration(plugin.settings().animationInterval);
                ModelSpawner.pose(display,
                        new Matrix4f(user).mul(model.parts().get(index).restPose()));
            }
        }, error -> {
            // Nothing to put back if the file is gone; the model just keeps its last pose.
        });
    }

    /**
     * Puts everything back to the pose it was placed in - the model's own rest transform,
     * not keyframe zero, which is a different thing entirely.
     */
    private void restPose(Playing entry) {
        for (int i = 0; i < entry.displays.size(); i++) {
            Display display = entry.displays.get(i);
            if (!display.isValid()) continue;
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(plugin.settings().animationInterval);
            ModelSpawner.pose(display, new Matrix4f(entry.user).mul(entry.parts.get(i).restPose()));
        }
        entry.appliedTick = -1;
        entry.lastSoundStep = -1;
        entry.labelAt = null;
        entry.sent.clear();
        // Back over the resting model, not over wherever the animation left off.
        plugin.placed().get(entry.id).ifPresent(placement -> plugin.labels().refresh(placement));
    }

    public void setSpeed(UUID modelId, double speed) {
        Playing entry = playing.get(modelId);
        if (entry != null) entry.speed = speed;
    }

    // ------------------------------------------------------------------ resuming

    /** Restarts models that are flagged as animating once their chunk and a player show up. */
    private void resumeNearby() {
        if (!plugin.settings().animationEnabled) return;
        if (playing.size() >= plugin.settings().animationMaxPlaying) return;

        double radius = plugin.settings().animationRadius;
        for (Placement placement : plugin.placed().all()) {
            if (!placement.animating() || playing.containsKey(placement.id())) continue;
            if (starting.contains(placement.id())) continue;

            Location anchor = placement.location();
            if (anchor == null || !anchor.getWorld().isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4)) {
                continue;
            }
            if (!hasViewer(anchor, radius)) continue;

            startAsync(placement, placement.animationSpeed(), placement.animationName(), result -> {
                if (result != Result.STARTED) {
                    plugin.getSLF4JLogger().warn("Could not resume the animation of {} ({})",
                            placement.shortId(), result);
                    // Do not keep retrying something that cannot work.
                    plugin.placed().setAnimating(placement.id(), false, placement.animationSpeed(),
                            placement.animationName());
                }
            });
            return; // one per pass keeps the load spread out
        }
    }
}
