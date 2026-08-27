package de.phillip.bdpaste.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The note track that goes with one animation.
 *
 * <p>BDEngine keeps these under {@code listSound} on the project root, one entry per animation,
 * paired by id. An entry holds several tracks, each naming a Minecraft sound and carrying a
 * piano roll of notes; they are flattened into one list here, because playing them back is a
 * matter of firing whatever falls due on a given step, no matter which track it came from.</p>
 *
 * <p>Only project files carry these. A datapack export bakes its sounds into functions, which
 * is a different thing entirely and not read.</p>
 *
 * @param name       what the timeline is called in the editor
 * @param tick       the editor's own {@code tick} field, kept as written for display
 * @param notes      every note of every track, sorted by step
 */
public record BdSound(String name, int tick, List<Note> notes) {

    /**
     * One note.
     *
     * @param step   position on the roll, {@link #keyframesPerStep()} apart
     * @param sound  Minecraft sound key, e.g. {@code block.note_block.harp}
     * @param volume as stored; scaled by the config before it is played
     * @param pitch  playback rate. The editor allows a wider range than the game does - the rat
     *               goes from 0.18 to 2.38 - so {@link NoteBlocks} decides what to do with the
     *               ones that fall outside.
     */
    public record Note(int step, String sound, float volume, float pitch) {
    }

    public static BdSound of(String name, int tick, List<Note> notes) {
        List<Note> sorted = new ArrayList<>(dropDuplicates(notes));
        sorted.sort(Comparator.comparingInt(Note::step));
        return new BdSound(name, tick, List.copyOf(sorted));
    }

    /**
     * Throws away notes that are the exact same note, twice, at the exact same moment.
     *
     * <p>Real rolls are full of them - RUSH E has 111 across 59 steps, one of them five deep.
     * Two identical samples starting on the same tick at the same spot do not sound like two
     * notes, they sound like one at twice the amplitude, in phase, and five of them is a
     * distorted mess. The editor fires them all and has no limiter, which it gets away with
     * because Web Audio has headroom; the game does not.</p>
     *
     * <p>Only exact duplicates go - same step, same sound, same pitch. A chord is several
     * different pitches on one step and is left alone.</p>
     */
    private static List<Note> dropDuplicates(List<Note> notes) {
        Set<String> seen = new HashSet<>();
        List<Note> out = new ArrayList<>(notes.size());
        for (Note note : notes) {
            if (seen.add(note.step() + "|" + note.sound() + "|" + note.pitch())) out.add(note);
        }
        return out;
    }

    /**
     * How much of the animation timeline one step of this roll covers.
     *
     * <p>The name {@code tick} is misleading: it is an index into a table of step sizes, not a
     * count of ticks. The editor's scheduler divides by it,</p>
     *
     * <pre>needTime = Math.floor(currentTime / (1 === tick ? .5 : tick - 1))</pre>
     *
     * <p>with {@code currentTime} counting keyframes, and its exporter spells the same thing out
     * in game ticks and in seconds:</p>
     *
     * <pre>
     * getSoundStepTicks: 1 -&gt; 1,    2 (and default) -&gt; 2,    3 -&gt; 4       game ticks
     * schedule delay:    1 -&gt; .05s, 2 -&gt; .1s,               3 -&gt; .2s
     * </pre>
     *
     * <p>All three agree, and the UI clamps {@code tick} to 1..3 on save, so those are the only
     * values a project can hold. Reading it as a plain tick count is right for 1 and 2 and wrong
     * for 3: RUSH E is a 3 and played a third too fast until this was traced back to the editor.
     * The exporter's default is what an out-of-range value falls back to here.</p>
     */
    public double keyframesPerStep() {
        // Straight off the exporter's own table, which is the one that has to hold up in game:
        //   getSoundStepTicks: case 1 -> 1, case 2 default -> 2, case 3 -> 4   (game ticks)
        // A keyframe is two game ticks. The editor's UI clamps tick to 1..3 on save, so the
        // default only ever catches a hand-edited or truncated file.
        return switch (tick) {
            case 1 -> 0.5;
            case 3 -> 2.0;
            default -> 1.0;
        };
    }

    /** The whole roll, measured in keyframes. */
    public double lengthInKeyframes() {
        return length() * keyframesPerStep();
    }

    public boolean isEmpty() {
        return notes.isEmpty();
    }

    /** One past the last note, so the roll covers {@code 0..length-1}. */
    public int length() {
        return notes.isEmpty() ? 0 : notes.get(notes.size() - 1).step() + 1;
    }
}
