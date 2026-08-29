package de.phillip.bdpaste.parse;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;

/**
 * Reads JSON without a depth ceiling, whichever Gson the server happens to bring.
 *
 * <p>Gson is provided by the server, so its version moves under the plugin: Paper 1.20.4 and
 * 1.21.1 bundle 2.10.1, 1.21.4 through 1.21.11 bundle 2.11.0, and 26.2 bundles 2.14.0. Newer
 * Gson enforces a nesting limit of 255 that the older ones did not have, and a BDEngine project
 * spends two levels of nesting per group - the object and its {@code children} array - so a
 * deeply grouped model runs out at about 127 groups deep.</p>
 *
 * <p>The effect was a model that imported on every supported server except the newest, where it
 * was rejected with {@code Nesting limit 255 reached at line 1 column ... path
 * $.children[0].children[0]...} - a message that tells a server owner nothing about what to do.
 * BDEngine itself has no such limit, so the file was never malformed.</p>
 *
 * <p>The limit is raised by reflection because the method that raises it does not exist on the
 * older Gson this is compiled against - and on those versions there is nothing to raise.</p>
 */
public final class Json {

    /** Far past anything a person could group by hand, and still a stop against a hostile file. */
    private static final int DEPTH = 8192;

    /** null once we know this Gson has no limit to raise, so it is looked up once. */
    private static java.lang.reflect.Method setNestingLimit;
    private static boolean looked;

    private Json() {
    }

    /** Parses a whole document, however deeply it is nested. */
    public static JsonElement parse(String json) {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(true);
        raiseLimit(reader);
        return JsonParser.parseReader(reader);
    }

    private static synchronized void raiseLimit(JsonReader reader) {
        if (!looked) {
            looked = true;
            try {
                setNestingLimit = JsonReader.class.getMethod("setNestingLimit", int.class);
            } catch (NoSuchMethodException older) {
                setNestingLimit = null;   // Gson without a limit; nothing to do.
            }
        }
        if (setNestingLimit == null) return;
        try {
            setNestingLimit.invoke(reader, DEPTH);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Reading it with whatever limit it has beats not reading it at all.
        }
    }
}
