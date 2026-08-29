package de.phillip.bdpaste.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Other names the same thing has gone by, for ids Minecraft has since renamed.
 *
 * <p>A BDEngine file carries the block and item ids of whatever game version the model was built
 * on, and Minecraft occasionally renames one. The plugin supports Paper 1.20.4 up to 26.2, and
 * across that span exactly two ids disappeared - checked by pulling the {@code Material} constants
 * out of all six API jars and diffing them, which gives {@code CHAIN} and {@code SCUTE} and
 * nothing else.</p>
 *
 * <pre>
 *   minecraft:chain   -&gt; minecraft:iron_chain     renamed in 1.21.11 (copper_chain added beside it)
 *   minecraft:scute   -&gt; minecraft:turtle_scute   renamed in 1.21.1
 * </pre>
 *
 * <p>Without this, a model using a chain renders correctly on 1.21.8 and comes out as grey stone
 * cubes on 1.21.11 and 26.2 - no error, nothing red, just wrong, with one line in the log that
 * nobody reads.</p>
 *
 * <p>It goes both ways on purpose. A model built on 26.2 says {@code iron_chain}, and that is
 * just as unknown to a 1.20.4 server as {@code chain} is to a new one.</p>
 *
 * <p>A table rather than a rule. Guessing by stripping or adding a prefix would eventually match
 * something that only looks related, and two entries are cheap to keep honest.</p>
 */
public final class Renames {

    private static final Map<String, List<String>> ALIASES = Map.of(
            "minecraft:chain", List.of("minecraft:iron_chain"),
            "minecraft:iron_chain", List.of("minecraft:chain"),
            "minecraft:copper_chain", List.of("minecraft:chain"),
            "minecraft:scute", List.of("minecraft:turtle_scute"),
            "minecraft:turtle_scute", List.of("minecraft:scute"));

    private Renames() {
    }

    /**
     * What else to try when {@code id} is not a thing this server knows.
     *
     * @param id a namespaced id without any {@code [state]} suffix
     * @return the alternatives to try in order, or an empty list when there are none
     */
    public static List<String> alternatives(String id) {
        if (id == null) return List.of();
        return ALIASES.getOrDefault(id.toLowerCase(Locale.ROOT), List.of());
    }
}
