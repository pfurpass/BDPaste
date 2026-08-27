package de.phillip.bdpaste.model;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An animation exported from BDEngine as a datapack, already baked to one matrix per part
 * per tick.
 *
 * <p>This is used in preference to evaluating the keyframes ourselves. BDEngine composes a
 * group's custom pivot through nested animated groups in a way that could not be reproduced
 * from the project file alone, and the baked form sidesteps that entirely - it also carries
 * easing curves and shear, which the raw keyframes would need extra work to honour.</p>
 *
 * <p>Like the datapack itself, a tick only lists the parts that actually changed; tick 0
 * carries every part, so wrapping back to it restores the full pose.</p>
 */
public final class BakedAnimation {

    private final String name;
    private final List<Map<Integer, Matrix4f>> ticks;

    public BakedAnimation(String name, List<Map<Integer, Matrix4f>> ticks) {
        this.name = name;
        List<Map<Integer, Matrix4f>> copy = new ArrayList<>(ticks.size());
        for (Map<Integer, Matrix4f> tick : ticks) {
            copy.add(Map.copyOf(tick));
        }
        this.ticks = List.copyOf(copy);
    }

    public String name() {
        return name;
    }

    /** Number of ticks in one loop. */
    public int length() {
        return ticks.size();
    }

    public boolean isEmpty() {
        return ticks.isEmpty();
    }

    /** The parts that change on this tick, and what they change to. */
    public Map<Integer, Matrix4f> at(int tick) {
        if (ticks.isEmpty()) return Map.of();
        return ticks.get(Math.floorMod(tick, ticks.size()));
    }

    /** How many part transforms the whole animation carries, for reporting. */
    public int entryCount() {
        return ticks.stream().mapToInt(Map::size).sum();
    }
}
