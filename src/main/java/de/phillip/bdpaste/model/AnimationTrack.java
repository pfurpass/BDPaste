package de.phillip.bdpaste.model;

/**
 * One timeline of a BDEngine project.
 *
 * <p>Projects can hold several. The editor lists them in {@code listAnim} and stores their
 * keyframes on each node under {@code animation} for the first and {@code animation_<id>}
 * for the rest.</p>
 *
 * @param name   what the animation is called in the editor
 * @param length number of keyframes, so a track whose last one sits at 24 is 25 long
 * @param sound  the note roll that goes with it, or {@code null} for a silent animation
 */
public record AnimationTrack(String name, double length, BdSound sound) {
}
