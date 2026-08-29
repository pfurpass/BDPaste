package de.phillip.bdpaste.spawn;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import de.phillip.bdpaste.BDPastePlugin;
import de.phillip.bdpaste.model.BdPart;
import de.phillip.bdpaste.model.Decompose;
import de.phillip.bdpaste.model.Renames;
import de.phillip.bdpaste.parse.Snbt;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.joml.Matrix4f;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Turns {@link BdPart}s into live display entities. */
public final class ModelSpawner {

    private final BDPastePlugin plugin;

    public ModelSpawner(BDPastePlugin plugin) {
        this.plugin = plugin;
    }

    /** The invisible anchor every part rides on, so the whole model can be moved with one teleport. */
    public BlockDisplay spawnRoot(Location location, boolean preview) {
        return location.getWorld().spawn(location, BlockDisplay.class, root -> {
            root.setBlock(Material.AIR.createBlockData());
            root.setPersistent(!preview);
            root.setTeleportDuration(preview ? plugin.settings().previewTeleportDuration : 0);
            root.setViewRange(plugin.settings().viewRange);
            root.addScoreboardTag(BDPastePlugin.TAG);
            if (preview) root.addScoreboardTag(BDPastePlugin.TAG_PREVIEW);
        });
    }

    /**
     * Spawns one part and poses it.
     *
     * <p>The pose is set <em>after</em> the entity is in the world, not from inside the spawn
     * callback with everything else. That callback runs while the entity is still being built,
     * and how much of what you set there survives being added to the world is not the same on
     * every server version - a model whose parts all came out unposed, stacked into a single
     * cube, is what that looks like. Setting it afterwards is one call either way and cannot
     * be undone by anything.</p>
     *
     * <p>No flicker from doing it late: the spawn and the pose leave for the client in the same
     * tick, so it never sees the part untransformed.</p>
     */
    public Entity spawnPart(Location location, BdPart part, Matrix4f matrix, boolean preview) {
        Display display = switch (part.kind()) {
            case BLOCK -> location.getWorld().spawn(location, BlockDisplay.class, d -> {
                d.setBlock(blockDataOf(part.name()));
                applyCommon(d, part, preview);
            });
            case ITEM -> location.getWorld().spawn(location, ItemDisplay.class, d -> {
                d.setItemStack(itemStackOf(part));
                d.setItemDisplayTransform(itemTransformOf(part));
                applyCommon(d, part, preview);
            });
            case TEXT -> location.getWorld().spawn(location, TextDisplay.class, d -> {
                applyText(d, part);
                applyCommon(d, part, preview);
            });
        };
        pose(display, matrix);
        return display;
    }

    /**
     * Poses a display, working the four values out here rather than leaving it to the server.
     *
     * <p>A display entity stores a translation, a rotation, a scale and a second rotation - not
     * a matrix. {@code setTransformationMatrix} hands the server a matrix and lets it work those
     * out, and that is where models fell apart: on some versions the decomposition gives up and
     * returns no rotation and unit scale, which keeps every part's position and throws away its
     * shape. A whole model then stacks into a single cube.</p>
     *
     * <p>{@link Decompose} does it the same way on every version, so the server is handed four
     * finished numbers and has nothing left to get wrong.</p>
     */
    public static void pose(Display display, Matrix4f matrix) {
        Decompose.Parts parts = Decompose.of(matrix);
        display.setTransformation(new Transformation(
                parts.translation(), parts.leftRotation(), parts.scale(), parts.rightRotation()));
    }

    // ------------------------------------------------------------- properties

    private void applyCommon(Display display, BdPart part, boolean preview) {
        display.setPersistent(!preview);
        display.setViewRange(plugin.settings().viewRange);
        display.addScoreboardTag(BDPastePlugin.TAG);
        if (preview) display.addScoreboardTag(BDPastePlugin.TAG_PREVIEW);

        Map<String, Object> nbt = part.options();

        if (plugin.settings().forceBrightness) {
            display.setBrightness(new Display.Brightness(
                    plugin.settings().brightnessBlock, plugin.settings().brightnessSky));
        } else if (part.blockLight() != null || part.skyLight() != null) {
            int block = clamp(part.blockLight() == null ? 0 : part.blockLight());
            int sky = clamp(part.skyLight() == null ? 15 : part.skyLight());
            display.setBrightness(new Display.Brightness(block, sky));
        }

        String billboard = Snbt.stringOf(nbt.get("billboard"), null);
        if (billboard != null) {
            try {
                display.setBillboard(Display.Billboard.valueOf(billboard.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // leave the default
            }
        }

        Double viewRange = Snbt.doubleOf(nbt.get("view_range"), null);
        if (viewRange != null) display.setViewRange(viewRange.floatValue() * plugin.settings().viewRange);

        Double shadowRadius = Snbt.doubleOf(nbt.get("shadow_radius"), null);
        if (shadowRadius != null) display.setShadowRadius(shadowRadius.floatValue());

        Double shadowStrength = Snbt.doubleOf(nbt.get("shadow_strength"), null);
        if (shadowStrength != null) display.setShadowStrength(shadowStrength.floatValue());

        Double width = Snbt.doubleOf(nbt.get("width"), null);
        if (width != null) display.setDisplayWidth(width.floatValue());

        Double height = Snbt.doubleOf(nbt.get("height"), null);
        if (height != null) display.setDisplayHeight(height.floatValue());

        Integer glow = Snbt.intOf(nbt.get("glow_color_override"), null);
        if (glow != null && glow != 0) display.setGlowColorOverride(Color.fromARGB(glow));
    }

    private void applyText(TextDisplay display, BdPart part) {
        display.text(componentOf(part.name()));

        Map<String, Object> nbt = part.options();

        Integer lineWidth = Snbt.intOf(nbt.get("line_width"), null);
        if (lineWidth != null) display.setLineWidth(lineWidth);

        Integer background = Snbt.intOf(nbt.get("background"), null);
        if (background != null) {
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(background));
        }

        Integer opacity = Snbt.intOf(nbt.get("text_opacity"), null);
        if (opacity != null) display.setTextOpacity((byte) (int) opacity);

        String alignment = Snbt.stringOf(nbt.get("alignment"), null);
        if (alignment != null) {
            try {
                display.setAlignment(TextDisplay.TextAlignment.valueOf(alignment.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // leave the default
            }
        }

        Boolean seeThrough = Snbt.boolOf(nbt.get("see_through"), null);
        if (seeThrough != null) display.setSeeThrough(seeThrough);

        Boolean shadow = Snbt.boolOf(nbt.get("shadow"), null);
        if (shadow != null) display.setShadowed(shadow);

        Boolean defaultBackground = Snbt.boolOf(nbt.get("default_background"), null);
        if (defaultBackground != null) display.setDefaultBackground(defaultBackground);
    }

    // ------------------------------------------------------------- conversion

    private BlockData blockDataOf(String name) {
        String id = name.startsWith("minecraft:") ? name : "minecraft:" + name;
        try {
            return Bukkit.createBlockData(id);
        } catch (IllegalArgumentException ex) {
            // The block state was not understood. The block itself may still exist, so it is
            // worth trying plain - but that gets the block's default state, which can look
            // nothing like what the model asked for, so it is said out loud rather than
            // quietly substituted.
            int bracket = id.indexOf('[');
            String bare = bracket > 0 ? id.substring(0, bracket) : id;
            String state = bracket > 0 ? id.substring(bracket) : "";

            // Minecraft renamed it out from under the model - chain became iron_chain in
            // 1.21.11 - so try what it is called now, or what it used to be called.
            for (String other : Renames.alternatives(bare)) {
                try {
                    return Bukkit.createBlockData(other + state);
                } catch (IllegalArgumentException keepLooking) {
                    // not this one
                }
            }

            if (bracket > 0) {
                try {
                    BlockData plain = Bukkit.createBlockData(bare);
                    plugin.warnBlockState(name, bare);
                    return plain;
                } catch (IllegalArgumentException ignored) {
                    // fall through: the block does not exist either
                }
            }
            plugin.warnUnknown("block", name);
            return Material.STONE.createBlockData();
        }
    }

    private ItemStack itemStackOf(BdPart part) {
        // Items never carry a block state, so a "[...]" suffix is BDEngine metadata, not part of the id.
        int bracket = part.name().indexOf('[');
        String bare = bracket > 0 ? part.name().substring(0, bracket) : part.name();
        String id = bare.startsWith("minecraft:") ? bare : "minecraft:" + bare;
        Material material = Material.matchMaterial(id);
        if (material == null || material.isAir()) {
            // Same story as the blocks: scute became turtle_scute in 1.21.1.
            for (String other : Renames.alternatives(id)) {
                Material renamed = Material.matchMaterial(other);
                if (renamed != null && !renamed.isAir()) {
                    material = renamed;
                    break;
                }
            }
        }
        if (material == null || material.isAir()) {
            if (part.headTexture() != null) {
                material = Material.PLAYER_HEAD;
            } else {
                plugin.warnUnknown("item", part.name());
                material = Material.STONE;
            }
        }

        ItemStack stack = new ItemStack(material);
        if (part.headTexture() != null && stack.getItemMeta() instanceof SkullMeta skull) {
            applySkin(stack, skull, part.headTexture());
        }
        return stack;
    }

    private void applySkin(ItemStack stack, SkullMeta skull, String textureValue) {
        try {
            UUID id = UUID.nameUUIDFromBytes(textureValue.getBytes(StandardCharsets.UTF_8));
            PlayerProfile profile = Bukkit.createProfile(id);
            profile.setProperty(new ProfileProperty("textures", textureValue));
            skull.setPlayerProfile(profile);
            stack.setItemMeta(skull);
        } catch (RuntimeException ex) {
            plugin.getSLF4JLogger().warn("Could not apply a head texture: {}", ex.getMessage());
        }
    }

    /**
     * How an item display should be posed: from its NBT, else from its own name, else the config.
     *
     * <p>The name is not a fallback anybody would guess at - it is where BDEngine actually writes
     * it. A head on a sign comes through as {@code player_head[display=none]} and carries no
     * {@code item_display} in its NBT at all, so reading only the NBT left the whole decision to
     * {@code display.item-display-transform} in the config. Two servers with different values
     * there then drew the same model differently, which is not something a model author can do
     * anything about - the file said what it wanted and nobody read it.</p>
     *
     * <p>Order of preference: explicit NBT, then the name, then the config. Most specific
     * wins.</p>
     */
    private ItemDisplay.ItemDisplayTransform itemTransformOf(BdPart part) {
        ItemDisplay.ItemDisplayTransform fromNbt =
                transformNamed(Snbt.stringOf(part.options().get("item_display"), null));
        if (fromNbt != null) return fromNbt;

        ItemDisplay.ItemDisplayTransform fromName = transformNamed(displaySuffix(part.name()));
        if (fromName != null) return fromName;

        return plugin.settings().itemTransform;
    }

    /** Reads {@code display=<mode>} out of a name like {@code player_head[display=head]}. */
    private static String displaySuffix(String name) {
        int bracket = name.indexOf('[');
        if (bracket < 0 || !name.endsWith("]")) return null;

        for (String option : name.substring(bracket + 1, name.length() - 1).split(",")) {
            int equals = option.indexOf('=');
            if (equals > 0 && option.substring(0, equals).trim().equals("display")) {
                return option.substring(equals + 1).trim();
            }
        }
        return null;
    }

    /** The mode by name, or null when it is missing or not one Minecraft knows. */
    private ItemDisplay.ItemDisplayTransform transformNamed(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return ItemDisplay.ItemDisplayTransform.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** BDEngine stores text as a JSON component; fall back to legacy and then plain text. */
    private static Component componentOf(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        String trimmed = text.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")) {
            try {
                return GsonComponentSerializer.gson().deserialize(trimmed);
            } catch (RuntimeException ignored) {
                // not valid JSON after all
            }
        }
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }

    private static int clamp(int light) {
        return Math.max(0, Math.min(15, light));
    }
}
