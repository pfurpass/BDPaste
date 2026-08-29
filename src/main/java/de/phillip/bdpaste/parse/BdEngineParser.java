package de.phillip.bdpaste.parse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.phillip.bdpaste.model.BdAnimation;
import de.phillip.bdpaste.model.BdKeyframe;
import de.phillip.bdpaste.model.BdModel;
import de.phillip.bdpaste.model.BdPart;
import de.phillip.bdpaste.model.BdSound;
import de.phillip.bdpaste.model.DisplayKind;
import de.phillip.bdpaste.model.TransformStep;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the native BDEngine project JSON.
 *
 * <p>The document is a tree of nodes. Collections ({@code isCollection}) carry a
 * {@code transforms} matrix and {@code children}; leaves carry {@code isBlockDisplay},
 * {@code isItemDisplay} or {@code isTextDisplay} plus their own matrix. Child matrices
 * are relative to their parent, so we compose on the way down and hand out a flat list.</p>
 *
 * <p>A project can hold several timelines. They are listed on the root under {@code listAnim}
 * and their keyframes live on each node under {@code animation} for the first one and
 * {@code animation_<id>} for the rest, so every part gets one chain of matrices per track.</p>
 */
public final class BdEngineParser {

    private BdEngineParser() {
    }

    /**
     * The animation fields present in a project, what the editor calls them, and the note roll
     * that goes with each - all three in the same order.
     */
    private record Tracks(List<String> fields, List<String> names, List<BdSound> sounds) {
    }

    public static BdModel parse(String modelName, String json) {
        JsonElement root = Json.parse(json);
        List<BdPart> parts = new ArrayList<>();

        List<JsonElement> roots = new ArrayList<>();
        if (root.isJsonArray()) {
            root.getAsJsonArray().forEach(roots::add);
        } else {
            roots.add(root);
        }

        Tracks tracks = tracksOf(roots);
        List<List<TransformStep>> empty = new ArrayList<>();
        for (int i = 0; i < tracks.fields().size(); i++) {
            empty.add(List.of());
        }

        for (JsonElement child : roots) {
            walk(child, new Matrix4f(), new Vector3f(), tracks.fields(), empty, parts);
        }
        return BdModel.of(modelName, parts, tracks.names(), tracks.sounds());
    }

    /**
     * Works out which animation fields to read.
     *
     * <p>{@code listAnim} is the list the editor keeps and gives us the names, with the entry
     * id deciding the field suffix. Older projects have no such list, so as a fallback the
     * tree is scanned for whatever {@code animation_<n>} fields the nodes happen to carry.</p>
     */
    private static Tracks tracksOf(List<JsonElement> roots) {
        List<String> fields = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<BdSound> sounds = new ArrayList<>();
        Map<Integer, BdSound> soundsById = soundsOf(roots);

        for (JsonElement element : roots) {
            if (!element.isJsonObject()) continue;
            JsonObject root = element.getAsJsonObject();
            if (!root.has("listAnim") || !root.get("listAnim").isJsonArray()) continue;

            for (JsonElement entry : root.getAsJsonArray("listAnim")) {
                if (!entry.isJsonObject()) continue;
                JsonObject animation = entry.getAsJsonObject();
                int id = animation.has("id") ? animation.get("id").getAsInt() : fields.size() + 1;
                String field = fieldOf(id);
                if (fields.contains(field)) continue;
                fields.add(field);
                names.add(string(animation, "name", field));
                // The editor pairs a note roll with an animation by id, not by position.
                sounds.add(soundsById.get(id));
            }
        }
        if (!fields.isEmpty()) return new Tracks(fields, names, sounds);

        int highest = 0;
        for (JsonElement element : roots) {
            highest = Math.max(highest, highestAnimationField(element));
        }
        for (int id = 1; id <= highest; id++) {
            fields.add(fieldOf(id));
            names.add(fieldOf(id));
            sounds.add(soundsById.get(id));
        }
        return new Tracks(fields, names, sounds);
    }

    /**
     * Reads {@code listSound} off the root, keyed by the id it shares with an animation.
     *
     * <p>An entry holds one roll per Minecraft sound; the notes of all of them are flattened
     * into a single list, since playing them back only cares about what falls due when. The
     * array of notes is called {@code piano} - the editor draws it as a piano roll - and any
     * other array on a track is read the same way in case that name ever changes.</p>
     */
    private static Map<Integer, BdSound> soundsOf(List<JsonElement> roots) {
        Map<Integer, BdSound> out = new LinkedHashMap<>();

        for (JsonElement element : roots) {
            if (!element.isJsonObject()) continue;
            JsonObject root = element.getAsJsonObject();
            if (!root.has("listSound") || !root.get("listSound").isJsonArray()) continue;

            for (JsonElement entry : root.getAsJsonArray("listSound")) {
                if (!entry.isJsonObject()) continue;
                JsonObject sound = entry.getAsJsonObject();
                int id = sound.has("id") ? sound.get("id").getAsInt() : out.size() + 1;

                List<BdSound.Note> notes = new ArrayList<>();
                if (sound.has("tracks") && sound.get("tracks").isJsonArray()) {
                    for (JsonElement element2 : sound.getAsJsonArray("tracks")) {
                        if (!element2.isJsonObject()) continue;
                        readNotes(element2.getAsJsonObject(), notes);
                    }
                }
                if (notes.isEmpty()) continue;

                int tick = sound.has("tick") ? sound.get("tick").getAsInt() : 1;
                out.put(id, BdSound.of(string(sound, "name", "sound"), tick, notes));
            }
        }
        return out;
    }

    private static void readNotes(JsonObject track, List<BdSound.Note> out) {
        String sound = string(track, "id", null);
        if (sound == null || sound.isBlank()) return;

        for (Map.Entry<String, JsonElement> field : track.entrySet()) {
            if (!field.getValue().isJsonArray()) continue;
            for (JsonElement element : field.getValue().getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject note = element.getAsJsonObject();
                if (!note.has("time")) continue;
                out.add(new BdSound.Note(
                        note.get("time").getAsInt(),
                        sound,
                        note.has("volume") ? note.get("volume").getAsFloat() : 1f,
                        note.has("pitch") ? note.get("pitch").getAsFloat() : 1f));
            }
        }
    }

    private static String fieldOf(int id) {
        return id <= 1 ? "animation" : "animation_" + id;
    }

    /** Highest {@code animation_<n>} suffix anywhere in the tree, or 1 for a plain one. */
    private static int highestAnimationField(JsonElement element) {
        if (element == null || !element.isJsonObject()) return 0;
        JsonObject node = element.getAsJsonObject();

        int highest = 0;
        for (String key : node.keySet()) {
            if (!key.startsWith("animation")) continue;
            if (!node.get(key).isJsonArray() || node.getAsJsonArray(key).isEmpty()) continue;
            if (key.equals("animation")) {
                highest = Math.max(highest, 1);
            } else if (key.startsWith("animation_")) {
                try {
                    highest = Math.max(highest, Integer.parseInt(key.substring("animation_".length())));
                } catch (NumberFormatException ignored) {
                    // A field we do not know about; nothing to read from it.
                }
            }
        }
        if (node.has("children") && node.get("children").isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray("children")) {
                highest = Math.max(highest, highestAnimationField(child));
            }
        }
        return highest;
    }

    /**
     * @param parent      the composed matrix of everything above, at rest
     * @param parentPivot {@code pivotCustom} of the node above, which keyframes here are
     *                    relative to
     * @param chains      the steps above this node, one list per track, only tracked once
     *                    something up there animates in that track
     */
    private static void walk(JsonElement element, Matrix4f parent, Vector3f parentPivot,
                             List<String> fields, List<List<TransformStep>> chains, List<BdPart> out) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject node = element.getAsJsonObject();

        Matrix4f local = readTransforms(node);
        Vector3f pivot = pivotOf(node);

        // The rest pose always comes from "transforms". Keyframe 0 is NOT the same thing:
        // "transforms" is where the editor left the model, while the timeline holds its own
        // poses. Using keyframe 0 here scatters every animated group.
        Matrix4f world = new Matrix4f(parent).mul(local);

        List<List<TransformStep>> here = new ArrayList<>(fields.size());
        for (int track = 0; track < fields.size(); track++) {
            List<TransformStep> chain = chains.get(track);
            BdAnimation animation = readAnimation(node, fields.get(track), pivot, parentPivot);
            if (animation != null) {
                // From here down every part has to remember how it got here.
                here.add(append(chain.isEmpty()
                                ? List.of(new TransformStep.Fixed(new Matrix4f(parent)))
                                : chain,
                        new TransformStep.Animated(animation)));
            } else if (!chain.isEmpty()) {
                here.add(append(chain, new TransformStep.Fixed(local)));
            } else {
                here.add(chain);
            }
        }

        JsonArray children = node.has("children") && node.get("children").isJsonArray()
                ? node.getAsJsonArray("children")
                : null;
        if (children != null) {
            for (JsonElement child : children) {
                walk(child, world, pivot, fields, here, out);
            }
            return;
        }

        DisplayKind kind = kindOf(node);
        if (kind == null) return;

        Map<String, Object> options = Snbt.compound(string(node, "nbt", null));

        Integer blockLight = null;
        Integer skyLight = null;
        if (node.has("brightness") && node.get("brightness").isJsonObject()) {
            JsonObject b = node.getAsJsonObject("brightness");
            blockLight = b.has("block") ? b.get("block").getAsInt() : null;
            skyLight = b.has("sky") ? b.get("sky").getAsInt() : null;
        } else if (options.get("brightness") instanceof Map<?, ?>) {
            Map<String, Object> b = Snbt.mapOf(options.get("brightness"));
            blockLight = Snbt.intOf(b.get("block"), null);
            skyLight = Snbt.intOf(b.get("sky"), null);
        }

        String headTexture = null;
        if (node.has("tagHead") && node.get("tagHead").isJsonObject()) {
            JsonObject head = node.getAsJsonObject("tagHead");
            if (head.has("Value") && head.get("Value").isJsonPrimitive()) {
                headTexture = head.get("Value").getAsString();
            }
        }
        if (headTexture == null) {
            headTexture = string(node, "defaultTextureValue", null);
        }

        String name = string(node, "name", "");
        if (kind == DisplayKind.TEXT && options.containsKey("text")) {
            name = Snbt.stringOf(options.get("text"), name);
        }
        if (kind == DisplayKind.ITEM && name.indexOf('[') > 0) {
            // BDEngine writes the item display transform block-state style: player_head[display=head]
            int bracket = name.indexOf('[');
            options = withSuffixOptions(options, name.substring(bracket));
            name = name.substring(0, bracket);
        }

        out.add(new BdPart(kind, name, world, blockLight, skyLight, headTexture, options, here));
    }

    /** Collapses trailing fixed links so a chain stays short no matter how deep the tree is. */
    private static List<TransformStep> append(List<TransformStep> chain, TransformStep step) {
        List<TransformStep> out = new ArrayList<>(chain);
        if (step instanceof TransformStep.Fixed fixed
                && !out.isEmpty()
                && out.get(out.size() - 1) instanceof TransformStep.Fixed last) {
            out.set(out.size() - 1, new TransformStep.Fixed(new Matrix4f(last.matrix()).mul(fixed.matrix())));
        } else {
            out.add(step);
        }
        return out;
    }

    /**
     * Reads one keyframe track of a node. {@code isStepCurve} means the editor jumps between
     * keyframes instead of easing through them.
     */
    private static BdAnimation readAnimation(JsonObject node, String field,
                                             Vector3f pivot, Vector3f parentPivot) {
        if (!node.has(field) || !node.get(field).isJsonArray()) return null;
        JsonArray frames = node.getAsJsonArray(field);
        if (frames.isEmpty()) return null;

        List<BdKeyframe> keys = new ArrayList<>(frames.size());
        for (JsonElement frame : frames) {
            if (!frame.isJsonObject()) continue;
            JsonObject f = frame.getAsJsonObject();
            keys.add(new BdKeyframe(
                    f.has("time") ? f.get("time").getAsDouble() : 0,
                    vector(f, "position", 0f),
                    vector(f, "rotation", 0f),
                    vector(f, "scale", 1f)));
        }
        if (keys.isEmpty()) return null;

        // A track with a single keyframe still pins its group to that pose while the rest of
        // the model animates, so it is kept even though it has no length of its own.
        return new BdAnimation(keys, bool(node, "isStepCurve"), pivot, parentPivot);
    }

    /** {@code pivotCustom} is the point a group rotates and scales around while animating. */
    private static Vector3f pivotOf(JsonObject node) {
        if (!node.has("pivotCustom") || !node.get("pivotCustom").isJsonArray()) return new Vector3f();
        JsonArray pivot = node.getAsJsonArray("pivotCustom");
        if (pivot.size() < 3) return new Vector3f();
        return new Vector3f(pivot.get(0).getAsFloat(), pivot.get(1).getAsFloat(), pivot.get(2).getAsFloat());
    }

    private static Vector3f vector(JsonObject node, String key, float fallback) {
        if (!node.has(key) || !node.get(key).isJsonObject()) return new Vector3f(fallback);
        JsonObject v = node.getAsJsonObject(key);
        return new Vector3f(
                v.has("x") ? v.get("x").getAsFloat() : fallback,
                v.has("y") ? v.get("y").getAsFloat() : fallback,
                v.has("z") ? v.get("z").getAsFloat() : fallback);
    }

    /**
     * Folds a {@code [display=head,...]} suffix into the display options. Anything already set
     * in the node's own NBT wins, and {@code display} is the vanilla {@code item_display} field.
     */
    private static Map<String, Object> withSuffixOptions(Map<String, Object> options, String suffix) {
        String body = suffix.replaceAll("^\\[|]$", "");
        if (body.isBlank()) return options;

        Map<String, Object> merged = new LinkedHashMap<>(options);
        for (String pair : body.split(",")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            String key = pair.substring(0, equals).strip();
            String value = pair.substring(equals + 1).strip();
            merged.putIfAbsent("display".equals(key) ? "item_display" : key, value);
        }
        return merged;
    }

    private static DisplayKind kindOf(JsonObject node) {
        if (bool(node, "isTextDisplay")) return DisplayKind.TEXT;
        if (bool(node, "isItemDisplay")) return DisplayKind.ITEM;
        if (bool(node, "isBlockDisplay")) return DisplayKind.BLOCK;
        return null;
    }

    private static Matrix4f readTransforms(JsonObject node) {
        if (!node.has("transforms") || !node.get("transforms").isJsonArray()) return new Matrix4f();
        JsonArray array = node.getAsJsonArray("transforms");
        if (array.size() != 16) return new Matrix4f();
        float[] v = new float[16];
        for (int i = 0; i < 16; i++) {
            v[i] = array.get(i).getAsFloat();
        }
        return Matrices.fromRowMajor(v);
    }

    private static boolean bool(JsonObject node, String key) {
        return node.has(key) && node.get(key).isJsonPrimitive() && node.get(key).getAsBoolean();
    }

    private static String string(JsonObject node, String key, String def) {
        return node.has(key) && node.get(key).isJsonPrimitive() ? node.get(key).getAsString() : def;
    }
}
