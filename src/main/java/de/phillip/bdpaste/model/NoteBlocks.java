package de.phillip.bdpaste.model;

import java.util.Map;
import java.util.Set;

/**
 * Gets a note into a range Minecraft can actually play.
 *
 * <p>The editor lets you put a note anywhere on the roll and stores the playback rate that goes
 * with it. Its own preview runs on Web Audio, which will happily play at any rate, so nothing
 * stops you writing notes four octaves apart - the rat on block-display spans -30 to +15
 * semitones, RUSH E goes -30 to +22. The game will not: the client clamps playback to 0.5..2.0,
 * one octave either side. BDEngine writes the raw value into its datapack export anyway, and
 * since {@code /playsound} only accepts a pitch up to 2.0, those lines do not even parse.</p>
 *
 * <p>Minecraft's answer is the instrument, not the pitch. Every note block sound covers two
 * octaves and they sit an octave apart, so a note too low for the harp can be handed to a
 * deeper sound and comes out at exactly the frequency it was written at. Only the timbre
 * changes, which is how note block music has always been arranged.</p>
 *
 * <p>Which sound takes over is a matter of taste and cannot be settled from the file, so it is
 * left to the config - see {@link #playable}.</p>
 */
public final class NoteBlocks {

    /** What to do with a note the client cannot play as written. */
    public enum Mode {
        /**
         * Hand it to a note block sound that covers its octave. Right note, right octave,
         * different timbre.
         */
        INSTRUMENT,
        /**
         * Keep the instrument and move the note by whole octaves until it fits. Same timbre all
         * the way through, like the editor, but a bassline ends up in the melody's octave.
         */
        OCTAVE,
        /**
         * Send it as written and let the client flatten it against the edge of its range. What
         * BDEngine's own export does, and what a third of the notes get wrong.
         */
        OFF
    }

    /**
     * Where each note block sound sits, in semitones from the harp.
     *
     * <p>Every one of them spans two octaves around its own note, so a sound at {@code -24}
     * reaches from -36 to -12 and picks up exactly where the harp gives out.</p>
     */
    private static final Map<String, Integer> OFFSETS = Map.ofEntries(
            Map.entry("block.note_block.bass", -24),
            Map.entry("block.note_block.didgeridoo", -24),
            Map.entry("block.note_block.guitar", -12),
            Map.entry("block.note_block.harp", 0),
            Map.entry("block.note_block.bit", 0),
            Map.entry("block.note_block.banjo", 0),
            Map.entry("block.note_block.pling", 0),
            Map.entry("block.note_block.iron_xylophone", 0),
            Map.entry("block.note_block.flute", 12),
            Map.entry("block.note_block.cow_bell", 12),
            Map.entry("block.note_block.bell", 24),
            Map.entry("block.note_block.chime", 24),
            Map.entry("block.note_block.xylophone", 24));

    /** Instruments sharing the harp's two octaves, so a note of theirs can be handed on. */
    private static final Set<String> MIDDLE = Set.of(
            "block.note_block.harp",
            "block.note_block.bit",
            "block.note_block.banjo",
            "block.note_block.pling",
            "block.note_block.iron_xylophone");

    private static final float MIN = 0.5f;
    private static final float MAX = 2.0f;

    private NoteBlocks() {
    }

    /** A note as it will be sent to the client. */
    public record Playable(String sound, float pitch) {
    }

    /** Whether this sound is one the substitution knows where to place. */
    public static boolean known(String sound) {
        return sound != null && OFFSETS.containsKey(sound);
    }

    /**
     * @param low  the sound to hand notes below the playable range to
     * @param high the sound for notes above it
     */
    public static Playable playable(String sound, float pitch, Mode mode, String low, String high) {
        if (mode == Mode.OFF) return new Playable(sound, pitch);
        if (pitch >= MIN && pitch <= MAX) return new Playable(sound, pitch);
        if (mode == Mode.OCTAVE) return new Playable(sound, fold(pitch));

        // A drum has no octave worth moving, and a sound from outside the family has nowhere
        // to be handed on to.
        if (sound == null || !MIDDLE.contains(sound)) return new Playable(sound, clamp(pitch));

        String target = pitch < MIN ? low : high;
        Integer offset = OFFSETS.get(target);
        if (offset == null) return new Playable(sound, clamp(pitch));

        // Move the rate by however far the substitute's own note sits away, so the two shifts
        // cancel and the note sounds at the frequency it was written at.
        return new Playable(target, clamp(shift(pitch, -offset)));
    }

    /** Doubles or halves until the note is playable, so it keeps its pitch class. */
    private static float fold(float pitch) {
        float out = pitch;
        while (out < MIN) out *= 2f;
        while (out > MAX) out /= 2f;
        return out;
    }

    /** Moves a playback rate by a number of semitones. */
    private static float shift(float pitch, int semitones) {
        return (float) (pitch * Math.pow(2, semitones / 12.0));
    }

    private static float clamp(float pitch) {
        return Math.max(MIN, Math.min(MAX, pitch));
    }
}
