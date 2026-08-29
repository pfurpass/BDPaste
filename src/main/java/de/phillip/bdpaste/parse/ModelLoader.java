package de.phillip.bdpaste.parse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.phillip.bdpaste.model.BdModel;
import de.phillip.bdpaste.model.BdPart;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns whatever BDEngine handed the user into a {@link BdModel}.
 *
 * <p>Supported inputs:</p>
 * <ul>
 *   <li>{@code .bdengine} project files (base64 of gzipped JSON)</li>
 *   <li>raw {@code .json} project exports</li>
 *   <li>{@code .txt} / {@code .mcfunction} files holding {@code /summon} commands</li>
 *   <li>{@code .zip} datapack exports (every {@code .mcfunction} inside is scanned)</li>
 *   <li>PRJ2 project containers, which is what block-display.com serves for newer models</li>
 * </ul>
 */
public final class ModelLoader {

    public static final List<String> EXTENSIONS = List.of(".bdengine", ".json", ".txt", ".mcfunction", ".zip");

    private ModelLoader() {
    }

    public static boolean isSupported(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    /** Strips the extension so {@code mater.bdengine} is addressed as {@code mater}. */
    public static String modelNameOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static BdModel load(Path file) throws IOException {
        return load(modelNameOf(file), file.getFileName().toString(), Files.readAllBytes(file));
    }

    public static BdModel load(String modelName, String fileName, byte[] data) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".zip") || isZip(data)) {
            return fromDatapack(modelName, data);
        }
        if (isGzip(data)) {
            return fromProjectBytes(modelName, gunzip(data));
        }

        String text = new String(data, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) throw new IOException("The file is empty");

        char first = text.charAt(0);
        if (first == '{' || first == '[') {
            BdModel fromApi = tryBlockDisplayJson(modelName, text);
            return fromApi != null ? fromApi : BdEngineParser.parse(modelName, text);
        }
        if (text.contains("summon")) {
            return SummonParser.parse(modelName, text);
        }
        // Anything else is treated as a base64 .bdengine payload.
        return fromProjectBytes(modelName, decodeBdEngine(text));
    }

    /** Project bytes are either the raw JSON or a PRJ2 container holding it. */
    private static BdModel fromProjectBytes(String modelName, byte[] data) throws IOException {
        if (Prj2Archive.isArchive(data)) {
            return BdEngineParser.parse(modelName, Prj2Archive.sceneJson(data));
        }
        return BdEngineParser.parse(modelName, new String(data, StandardCharsets.UTF_8));
    }

    /**
     * block-display.com answers with entity NBT as JSON, either bare or wrapped in
     * {@code {"data":{...},"success":true}}. Returns {@code null} if this is not that format.
     */
    private static BdModel tryBlockDisplayJson(String modelName, String text) {
        JsonElement root;
        try {
            root = Json.parse(text);
        } catch (RuntimeException ex) {
            return null;
        }
        if (!root.isJsonObject()) return null;

        JsonObject object = root.getAsJsonObject();
        if (object.has("data") && object.get("data").isJsonObject()) {
            object = object.getAsJsonObject("data");
        }
        if (!object.has("Passengers") || !object.get("Passengers").isJsonArray()) return null;

        String name = modelName;
        if (object.has("name") && object.get("name").isJsonPrimitive()) {
            String title = object.get("name").getAsString();
            if (!title.isBlank()) name = title;
        }
        return SummonParser.fromEntityTag(name, NbtJson.compound(object));
    }

    /** Decodes the base64 + gzip wrapper BDEngine uses for its project files. */
    public static byte[] decodeBdEngine(String text) throws IOException {
        String cleaned = text.replaceAll("\\s", "");
        byte[] raw = decodeBase64(cleaned);
        return isGzip(raw) ? gunzip(raw) : raw;
    }

    private static byte[] decodeBase64(String cleaned) throws IOException {
        try {
            return Base64.getDecoder().decode(cleaned);
        } catch (IllegalArgumentException first) {
            try {
                return Base64.getUrlDecoder().decode(cleaned);
            } catch (IllegalArgumentException second) {
                throw new IOException("Not a readable .bdengine file (invalid base64)");
            }
        }
    }

    /**
     * A BDEngine datapack holds the model in {@code _/create.mcfunction} and, when animations
     * were exported, the baked frames under {@code k/<name>/keyframe_<tick>.mcfunction}. Both
     * are picked up in one pass, and the summon order is what ties a part to its tag index.
     */
    private static BdModel fromDatapack(String modelName, byte[] data) throws IOException {
        List<BdPart> parts = new ArrayList<>();
        AnimationDatapack.Collector animations = new AnimationDatapack.Collector();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".mcfunction")) continue;

                String text = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                if (animations.accept(name, text)) continue;
                if (!text.contains("summon")) continue;
                parts.addAll(SummonParser.parse(modelName, text).parts());
            }
        }
        if (parts.isEmpty()) {
            throw new IOException("No summon commands found inside the datapack");
        }
        return BdModel.of(modelName, parts).withBaked(animations.finish());
    }

    private static boolean isGzip(byte[] data) {
        return data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B;
    }

    private static boolean isZip(byte[] data) {
        return data.length > 4 && data[0] == 'P' && data[1] == 'K' && (data[2] == 3 || data[2] == 5 || data[2] == 7);
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }
}
