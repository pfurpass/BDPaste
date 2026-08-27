package de.phillip.bdpaste.model;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** A parsed BDEngine project: a flat list of parts plus its bounding box. */
public record BdModel(String name, List<BdPart> parts, Vector3f min, Vector3f max,
                      List<AnimationTrack> tracks, List<BakedAnimation> baked) {

    /** How many poses across an animation are measured when working out the bounds. */
    private static final int BOUNDS_SAMPLES = 12;

    public static BdModel of(String name, List<BdPart> parts) {
        return of(name, parts, List.of());
    }

    /**
     * @param trackNames names from the project's {@code listAnim}, in track order; may be
     *                   shorter than the number of tracks actually found, or empty
     */
    public static BdModel of(String name, List<BdPart> parts, List<String> trackNames) {
        return of(name, parts, trackNames, List.of());
    }

    /**
     * @param trackSounds the note roll per track, in track order; entries may be {@code null}
     *                    and the list may be shorter than the number of tracks
     */
    public static BdModel of(String name, List<BdPart> parts, List<String> trackNames,
                             List<BdSound> trackSounds) {
        List<AnimationTrack> tracks = trackList(parts, trackNames, trackSounds);

        Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);

        for (BdPart part : parts) {
            // An animated part sweeps through space, so the box has to cover every loop it takes
            // part in as well as the rest pose it sits in while stopped.
            List<Matrix4f> poses = new ArrayList<>();
            poses.add(part.restPose());
            for (int track = 0; track < tracks.size(); track++) {
                double length = tracks.get(track).length();
                if (length <= 0 || !part.animated(track)) continue;
                for (int s = 0; s <= BOUNDS_SAMPLES; s++) {
                    poses.add(part.matrixAt(track, length * s / BOUNDS_SAMPLES));
                }
            }

            for (Matrix4f m : poses) {
                Bounds.expand(part, m, min, max);
            }
        }

        if (parts.isEmpty()) {
            min.set(0f);
            max.set(0f);
        }
        return new BdModel(name, List.copyOf(parts), min, max, tracks, List.of());
    }

    /** Works out how many tracks the parts carry and how long each of them runs. */
    private static List<AnimationTrack> trackList(List<BdPart> parts, List<String> names,
                                                  List<BdSound> sounds) {
        int count = 0;
        for (BdPart part : parts) {
            count = Math.max(count, part.chains().size());
        }

        double[] lengths = new double[count];
        for (BdPart part : parts) {
            for (int track = 0; track < part.chains().size(); track++) {
                for (TransformStep step : part.chains().get(track)) {
                    if (step instanceof TransformStep.Animated animated) {
                        lengths[track] = Math.max(lengths[track], animated.animation().length());
                    }
                }
            }
        }

        List<AnimationTrack> tracks = new ArrayList<>(count);
        for (int track = 0; track < count; track++) {
            String name = track < names.size() && names.get(track) != null && !names.get(track).isBlank()
                    ? names.get(track)
                    : "animation" + (track == 0 ? "" : "_" + (track + 1));
            // Keyframes are numbered from zero, so a track whose last one sits at 24 is 25 long.
            // That is also how the editor exports it: 25 keyframe functions, the last of them
            // handing back to the first. Using the last index as the period would drop a frame.
            BdSound sound = track < sounds.size() ? sounds.get(track) : null;
            if (sound != null && sound.isEmpty()) sound = null;
            tracks.add(new AnimationTrack(name, lengths[track] > 0 ? lengths[track] + 1 : 0, sound));
        }
        return tracks;
    }

    /** Same model, now carrying the animations that came with its datapack. */
    public BdModel withBaked(List<BakedAnimation> animations) {
        return new BdModel(name, parts, min, max, tracks, List.copyOf(animations));
    }

    public boolean hasBakedAnimation() {
        return !baked.isEmpty();
    }

    /** The named animation, or the first one when no name is given. */
    public BakedAnimation bakedAnimation(String wanted) {
        if (baked.isEmpty()) return null;
        if (wanted == null || wanted.isBlank()) return baked.get(0);
        return baked.stream()
                .filter(a -> a.name().equalsIgnoreCase(wanted))
                .findFirst()
                .orElse(null);
    }

    /**
     * Whether a track has anything to play - keyframes, notes, or both.
     *
     * <p>Both halves count on their own. A model can carry a timeline that only moves parts,
     * and it can carry one that only plays music: RUSH E on block-display is 1071 notes and
     * not a single keyframe.</p>
     */
    public boolean playable(int track) {
        return animationLength(track) > 0 || sound(track) != null;
    }

    /**
     * Index of a track by name, or {@code -1} when there is no such animation.
     * Without a name the first track with anything to play wins.
     */
    public int trackIndex(String wanted) {
        if (wanted == null || wanted.isBlank()) {
            for (int track = 0; track < tracks.size(); track++) {
                if (playable(track)) return track;
            }
            return -1;
        }
        for (int track = 0; track < tracks.size(); track++) {
            if (tracks.get(track).name().equalsIgnoreCase(wanted)) return track;
        }
        return -1;
    }

    /** The note roll of one animation, or {@code null} when it is silent. */
    public BdSound sound(int track) {
        return track >= 0 && track < tracks.size() ? tracks.get(track).sound() : null;
    }

    public boolean hasSound() {
        return tracks.stream().anyMatch(track -> track.sound() != null);
    }

    public double animationLength(int track) {
        return track >= 0 && track < tracks.size() ? tracks.get(track).length() : 0;
    }

    /** Longest of the model's animations, which is what the bounding box had to cover. */
    public double animationLength() {
        return tracks.stream().mapToDouble(AnimationTrack::length).max().orElse(0);
    }

    /**
     * Every animation this model can play, in the order the editor lists them.
     *
     * <p>A datapack import answers with its baked tracks, anything else with the timelines
     * from the project file. Both are the names you can hand to {@code /bdpaste animate}.</p>
     */
    public List<String> animationNames() {
        if (!baked.isEmpty()) return baked.stream().map(BakedAnimation::name).toList();
        List<String> names = new ArrayList<>();
        for (int track = 0; track < tracks.size(); track++) {
            // Playable, not moving: a track that only carries notes belongs on this list too,
            // or a click would find nothing to cycle through on a model like RUSH E.
            if (playable(track)) names.add(tracks.get(track).name());
        }
        return List.copyOf(names);
    }

    /** Whether anything here can be played at all, moving or not. */
    public boolean hasAnimation() {
        for (int track = 0; track < tracks.size(); track++) {
            if (playable(track)) return true;
        }
        return false;
    }

    /** Whether any track actually moves parts, as opposed to only making noise. */
    public boolean hasMovement() {
        return tracks.stream().anyMatch(track -> track.length() > 0);
    }

    public int animatedParts() {
        return (int) parts.stream().filter(BdPart::animated).count();
    }

    public int size() {
        return parts.size();
    }

    /**
     * The box the model fills while nothing is playing.
     *
     * <p>Deliberately not {@link #min()}/{@link #max()}. Those cover everywhere the model
     * reaches across every one of its animations - on the farmer 8.4 blocks wide, because he
     * throws a hay bale. That is the right box for working out where to set the model down, and
     * the wrong one for anything a player has to click on or point at: it would put a clickable
     * shell over several blocks of empty field.</p>
     */
    public Bounds.Box restBox() {
        Bounds.Box box = Bounds.Box.empty();
        for (BdPart part : parts) {
            Bounds.expand(part, part.restPose(), box.min(), box.max());
        }
        if (box.isEmpty()) {
            box.min().set(0f);
            box.max().set(0f);
        }
        return box;
    }

    /** Horizontal centre at the bottom of the model - the natural pivot for placing something down. */
    public Vector3f pivotCenter() {
        return new Vector3f((min.x + max.x) * 0.5f, min.y, (min.z + max.z) * 0.5f);
    }

    public Vector3f dimensions() {
        return new Vector3f(max).sub(min);
    }
}
