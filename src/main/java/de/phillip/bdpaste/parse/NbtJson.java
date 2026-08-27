package de.phillip.bdpaste.parse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts JSON-shaped entity NBT into the same {@link Map}/{@link List} structure {@link Snbt}
 * produces, so both can be walked by the same code.
 *
 * <p>block-display.com hands out its models this way: the vanilla NBT keys, but as JSON.</p>
 */
public final class NbtJson {

    private NbtJson() {
    }

    public static Object convert(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                Object value = convert(entry.getValue());
                if (value != null) out.put(entry.getKey(), value);
            }
            return out;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            List<Object> out = new ArrayList<>(array.size());
            for (JsonElement item : array) {
                out.add(convert(item));
            }
            return out;
        }

        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return primitive.getAsNumber();
        return primitive.getAsString();
    }

    public static Map<String, Object> compound(JsonElement element) {
        Object value = convert(element);
        return Snbt.mapOf(value);
    }
}
