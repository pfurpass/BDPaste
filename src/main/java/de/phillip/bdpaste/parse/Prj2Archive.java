package de.phillip.bdpaste.parse;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BDEngine's newer project container, used by everything the site serves through
 * {@code /api/v1/bde/project-file}. Once un-gzipped the bytes look like this:
 *
 * <pre>
 *   "PRJ2"                 4 bytes magic
 *   version                1 byte
 *   entryCount             uint32, little endian
 *   per entry:
 *     nameLength           uint16, little endian
 *     name                 UTF-8
 *     dataLength           uint32, little endian
 *     data
 * </pre>
 *
 * <p>The model itself lives in the {@code scene.json} entry and is the same project JSON
 * that a plain {@code .bdengine} file contains.</p>
 */
public final class Prj2Archive {

    private static final byte[] MAGIC = {'P', 'R', 'J', '2'};
    private static final int HEADER = MAGIC.length + 1 + Integer.BYTES;

    private Prj2Archive() {
    }

    public static boolean isArchive(byte[] data) {
        if (data == null || data.length < HEADER) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) return false;
        }
        return true;
    }

    /** Reads every entry. Names map to their raw bytes. */
    public static Map<String, byte[]> entries(byte[] data) throws IOException {
        if (!isArchive(data)) throw new IOException("Not a PRJ2 project container");

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(MAGIC.length + 1); // skip magic and version

        int count = buffer.getInt();
        if (count < 0 || count > 4096) throw new IOException("PRJ2 container claims " + count + " entries");

        Map<String, byte[]> out = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            if (buffer.remaining() < Short.BYTES) break;
            int nameLength = buffer.getShort() & 0xFFFF;
            if (buffer.remaining() < nameLength + Integer.BYTES) break;

            byte[] name = new byte[nameLength];
            buffer.get(name);

            int dataLength = buffer.getInt();
            if (dataLength < 0 || dataLength > buffer.remaining()) break;

            byte[] payload = new byte[dataLength];
            buffer.get(payload);
            out.put(new String(name, StandardCharsets.UTF_8), payload);
        }
        return out;
    }

    /** The project JSON: the {@code scene.json} entry, or the first JSON entry there is. */
    public static String sceneJson(byte[] data) throws IOException {
        Map<String, byte[]> entries = entries(data);

        byte[] scene = entries.get("scene.json");
        if (scene == null) {
            scene = entries.entrySet().stream()
                    .filter(e -> e.getKey().toLowerCase().endsWith(".json"))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
        if (scene == null) {
            throw new IOException("The project container has no scene.json (entries: " + entries.keySet() + ")");
        }
        return new String(scene, StandardCharsets.UTF_8);
    }
}
