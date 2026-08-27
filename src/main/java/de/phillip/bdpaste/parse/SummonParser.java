package de.phillip.bdpaste.parse;

import de.phillip.bdpaste.model.BdModel;
import de.phillip.bdpaste.model.BdPart;
import de.phillip.bdpaste.model.DisplayKind;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the {@code /summon block_display ... {Passengers:[...]}} commands that BDEngine's
 * export dialog and its datapack export produce.
 *
 * <p>Note that passenger displays are <em>not</em> transformed by their vehicle in vanilla:
 * each one renders at the vehicle's position using its own transformation. So unlike the
 * project JSON, matrices here are taken as-is instead of being composed.</p>
 */
public final class SummonParser {

    private SummonParser() {
    }

    public static BdModel parse(String modelName, String text) {
        List<BdPart> parts = new ArrayList<>();
        for (String line : text.split("\\R")) {
            readCommand(line, parts);
        }
        return BdModel.of(modelName, parts);
    }

    /**
     * Builds a model straight from one entity tag and its passengers, for sources that hand out
     * the NBT itself instead of a command string.
     */
    public static BdModel fromEntityTag(String modelName, Map<String, Object> tag) {
        List<BdPart> parts = new ArrayList<>();
        readEntity("block_display", tag, parts);
        return BdModel.of(modelName, parts);
    }

    private static void readCommand(String line, List<BdPart> out) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return;

        int summonAt = trimmed.indexOf("summon ");
        if (summonAt < 0) return;

        int braceAt = trimmed.indexOf('{', summonAt);
        if (braceAt < 0) return;

        // the entity type sits right after "summon", before the coordinates
        String head = trimmed.substring(summonAt + "summon ".length(), braceAt).strip();
        String[] headParts = head.split("\\s+");
        String rootId = headParts.length > 0 ? headParts[0] : "";

        Map<String, Object> tag;
        try {
            tag = Snbt.compoundAt(trimmed, braceAt);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Could not read the NBT of a summon command: " + ex.getMessage(), ex);
        }
        readEntity(rootId, tag, out);
    }

    private static void readEntity(String fallbackId, Map<String, Object> tag, List<BdPart> out) {
        String id = normalise(Snbt.stringOf(tag.get("id"), fallbackId));

        BdPart part = toPart(id, tag);
        if (part != null) out.add(part);

        for (Object passenger : Snbt.listOf(tag.get("Passengers"))) {
            readEntity(id, Snbt.mapOf(passenger), out);
        }
    }

    private static BdPart toPart(String id, Map<String, Object> tag) {
        Matrix4f matrix = Matrices.fromNbt(tag.get("transformation"));

        Integer blockLight = null;
        Integer skyLight = null;
        if (tag.get("brightness") instanceof Map<?, ?>) {
            Map<String, Object> b = Snbt.mapOf(tag.get("brightness"));
            blockLight = Snbt.intOf(b.get("block"), null);
            skyLight = Snbt.intOf(b.get("sky"), null);
        }

        switch (id) {
            case "block_display" -> {
                String block = blockStateString(Snbt.mapOf(tag.get("block_state")));
                if (block == null || block.endsWith("air")) return null;
                return new BdPart(DisplayKind.BLOCK, block, matrix, blockLight, skyLight, null, tag, List.of());
            }
            case "item_display" -> {
                Map<String, Object> item = Snbt.mapOf(tag.get("item"));
                String itemId = normalise(Snbt.stringOf(item.get("id"), null));
                if (itemId == null || itemId.equals("air")) return null;
                return new BdPart(DisplayKind.ITEM, itemId, matrix, blockLight, skyLight, headTexture(item), tag, List.of());
            }
            case "text_display" -> {
                String text = Snbt.stringOf(tag.get("text"), null);
                if (text == null) return null;
                return new BdPart(DisplayKind.TEXT, text, matrix, blockLight, skyLight, null, tag, List.of());
            }
            default -> {
                return null;
            }
        }
    }

    /** {@code {Name:"minecraft:oak_stairs",Properties:{facing:"east"}}} to {@code minecraft:oak_stairs[facing=east]}. */
    private static String blockStateString(Map<String, Object> blockState) {
        String name = Snbt.stringOf(blockState.get("Name"), null);
        if (name == null) return null;

        Map<String, Object> properties = Snbt.mapOf(blockState.get("Properties"));
        if (properties.isEmpty()) return name;

        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(entry.getKey()).append('=').append(Snbt.stringOf(entry.getValue(), ""));
        }
        return sb.append(']').toString();
    }

    /** Pulls a base64 skin value out of either the modern profile component or the legacy tag. */
    private static String headTexture(Map<String, Object> item) {
        Map<String, Object> components = Snbt.mapOf(item.get("components"));
        Object profile = components.get("minecraft:profile");
        if (profile == null) profile = components.get("profile");
        if (profile instanceof Map<?, ?>) {
            for (Object property : Snbt.listOf(Snbt.mapOf(profile).get("properties"))) {
                Map<String, Object> p = Snbt.mapOf(property);
                if ("textures".equals(Snbt.stringOf(p.get("name"), ""))) {
                    return Snbt.stringOf(p.get("value"), null);
                }
            }
        }

        Map<String, Object> legacy = Snbt.mapOf(item.get("tag"));
        Map<String, Object> owner = Snbt.mapOf(legacy.get("SkullOwner"));
        Map<String, Object> props = Snbt.mapOf(owner.get("Properties"));
        for (Object texture : Snbt.listOf(props.get("textures"))) {
            String value = Snbt.stringOf(Snbt.mapOf(texture).get("Value"), null);
            if (value != null) return value;
        }
        return null;
    }

    private static String normalise(String id) {
        if (id == null) return null;
        String s = id.strip();
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }
}
