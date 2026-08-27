package de.phillip.bdpaste.spawn;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import de.phillip.bdpaste.BDPastePlugin;
import de.phillip.bdpaste.model.BdPart;
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

    public Entity spawnPart(Location location, BdPart part, Matrix4f matrix, boolean preview) {
        Entity entity = switch (part.kind()) {
            case BLOCK -> location.getWorld().spawn(location, BlockDisplay.class, d -> {
                d.setBlock(blockDataOf(part.name()));
                applyCommon(d, part, matrix, preview);
            });
            case ITEM -> location.getWorld().spawn(location, ItemDisplay.class, d -> {
                d.setItemStack(itemStackOf(part));
                d.setItemDisplayTransform(itemTransformOf(part.options()));
                applyCommon(d, part, matrix, preview);
            });
            case TEXT -> location.getWorld().spawn(location, TextDisplay.class, d -> {
                applyText(d, part);
                applyCommon(d, part, matrix, preview);
            });
        };
        return entity;
    }

    // ------------------------------------------------------------- properties

    private void applyCommon(Display display, BdPart part, Matrix4f matrix, boolean preview) {
        display.setTransformationMatrix(new Matrix4f(matrix));
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
            // Unknown or renamed block: strip the state and try the plain material.
            int bracket = id.indexOf('[');
            if (bracket > 0) {
                try {
                    return Bukkit.createBlockData(id.substring(0, bracket));
                } catch (IllegalArgumentException ignored) {
                    // fall through
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

    private ItemDisplay.ItemDisplayTransform itemTransformOf(Map<String, Object> nbt) {
        String raw = Snbt.stringOf(nbt.get("item_display"), null);
        if (raw == null) return plugin.settings().itemTransform;
        try {
            return ItemDisplay.ItemDisplayTransform.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return plugin.settings().itemTransform;
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
