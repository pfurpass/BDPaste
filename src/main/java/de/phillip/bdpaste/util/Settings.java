package de.phillip.bdpaste.util;

import org.bukkit.configuration.file.FileConfiguration;
import de.phillip.bdpaste.model.NoteBlocks;
import org.bukkit.entity.ItemDisplay;

import java.util.Locale;

/** Typed view over config.yml, re-read on {@code /bdpaste reload}. */
public final class Settings {

    public enum Pivot { ORIGIN, CENTER }

    /** What BDPaste itself does when somebody clicks a placed model. */
    public enum ClickAction {
        /** Nothing - the click is only handed to other plugins as an event. */
        NONE,
        /** Start the animation, or stop it if it is already running. */
        TOGGLE,
        /** Step to the next animation, and past the last one back to standing still. */
        CYCLE
    }

    /** How far the model origin is rounded while it follows the crosshair. */
    public enum SnapMode {
        /** Exactly where you point. */
        OFF,
        /** Texture pixels - a sixteenth of a block by default. */
        PIXEL,
        /** Whole blocks, lining up with the build grid. */
        BLOCK;

        public SnapMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public int maxParts;
    public int spawnPerTick;

    public boolean previewHideFromOthers;
    public int previewTeleportDuration;

    public double defaultDistance;
    public double maxDistance;
    public double surfaceReach;
    public double rotationStep;
    public double scaleStep;
    public double offsetStep;
    public SnapMode snapDefault;
    public double pixelSnap;
    public boolean surfaceByDefault;
    public Pivot pivot;

    public float viewRange;
    public ItemDisplay.ItemDisplayTransform itemTransform;
    public boolean forceBrightness;
    public int brightnessBlock;
    public int brightnessSky;

    public double labelHeight;
    public double labelScale;
    public boolean labelShadow;
    public boolean labelSeeThrough;
    public int labelBackground;
    public double labelViewRange;
    public boolean labelFollowAnimation;
    public boolean interactionEnabled;
    public ClickAction interactionRightClick;
    public ClickAction interactionLeftClick;
    public String interactionPermission;
    public boolean interactionLoop;
    public double interactionPadding;
    public int interactionMaxBoxes;
    public boolean animationEnabled;
    public int animationInterval;
    public int animationTicksPerKeyframe;
    public boolean animationSound;
    public double animationSoundVolume;
    public NoteBlocks.Mode animationSoundMode;
    public String animationSoundLow;
    public String animationSoundHigh;
    public int animationMaxPlaying;
    public double animationRadius;

    public boolean downloadsEnabled;
    public int downloadMaxMb;

    public void load(FileConfiguration c) {
        maxParts = c.getInt("max-parts", 6000);
        spawnPerTick = Math.max(1, c.getInt("spawn-per-tick", 250));

        previewHideFromOthers = c.getBoolean("preview.hide-from-others", true);
        previewTeleportDuration = Math.max(0, Math.min(59, c.getInt("preview.teleport-duration", 2)));

        defaultDistance = c.getDouble("placement.default-distance", 5.0);
        maxDistance = Math.max(2.0, c.getDouble("placement.max-distance", 48.0));
        surfaceReach = Math.max(1.0, c.getDouble("placement.surface-reach", 32.0));
        rotationStep = c.getDouble("placement.rotation-step", 15.0);
        scaleStep = Math.max(1.001, c.getDouble("placement.scale-step", 1.1));
        offsetStep = c.getDouble("placement.offset-step", 0.25);
        // The old boolean is still honoured so existing configs keep working.
        SnapMode fallback = c.getBoolean("placement.snap-by-default", true) ? SnapMode.BLOCK : SnapMode.OFF;
        snapDefault = enumOf(SnapMode.class, c.getString("placement.snap"), fallback);
        pixelSnap = Math.max(1.0E-4, c.getDouble("placement.pixel-snap", 1.0 / 16.0));
        surfaceByDefault = c.getBoolean("placement.surface-by-default", true);
        pivot = enumOf(Pivot.class, c.getString("placement.pivot", "CENTER"), Pivot.CENTER);

        viewRange = (float) c.getDouble("display.view-range", 1.0);
        itemTransform = enumOf(ItemDisplay.ItemDisplayTransform.class,
                c.getString("display.item-display-transform", "NONE"),
                ItemDisplay.ItemDisplayTransform.NONE);
        forceBrightness = c.getBoolean("display.force-brightness", false);
        brightnessBlock = clampLight(c.getInt("display.brightness-block", 15));
        brightnessSky = clampLight(c.getInt("display.brightness-sky", 15));

        labelHeight = c.getDouble("label.height", 0.4);
        labelScale = Math.max(0.05, Math.min(16.0, c.getDouble("label.scale", 1.0)));
        labelShadow = c.getBoolean("label.shadow", true);
        labelSeeThrough = c.getBoolean("label.see-through", false);
        labelBackground = colorOf(c.getString("label.background"), 0x40000000);
        labelViewRange = Math.max(0.1, Math.min(10.0, c.getDouble("label.view-range", 1.0)));
        labelFollowAnimation = c.getBoolean("label.follow-animation", true);

        interactionEnabled = c.getBoolean("interaction.enabled", true);
        interactionRightClick = enumOf(ClickAction.class, c.getString("interaction.right-click"), ClickAction.CYCLE);
        interactionLeftClick = enumOf(ClickAction.class, c.getString("interaction.left-click"), ClickAction.NONE);
        interactionPermission = c.getString("interaction.permission", "").trim();
        interactionLoop = c.getBoolean("interaction.loop", true);
        interactionPadding = Math.max(0, Math.min(1, c.getDouble("interaction.padding", 0.0)));
        interactionMaxBoxes = Math.max(1, Math.min(16, c.getInt("interaction.max-boxes", 8)));

        animationEnabled = c.getBoolean("animation.enabled", true);
        // The client blends between updates, so this is how smooth it looks, not how fast it runs.
        animationInterval = Math.max(1, Math.min(20, c.getInt("animation.update-interval", 1)));
        animationTicksPerKeyframe = Math.max(1, Math.min(20, c.getInt("animation.ticks-per-keyframe", 2)));
        animationSound = c.getBoolean("animation.sound", true);
        animationSoundVolume = Math.max(0.0, Math.min(4.0, c.getDouble("animation.sound-volume", 1.0)));
        // Used to be a boolean, so true and false still mean what they did.
        String transpose = c.getString("animation.sound-transpose", "instrument");
        animationSoundMode = switch (transpose == null ? "" : transpose.trim().toLowerCase(Locale.ROOT)) {
            case "false", "off", "none", "raw" -> NoteBlocks.Mode.OFF;
            case "octave", "fold" -> NoteBlocks.Mode.OCTAVE;
            default -> NoteBlocks.Mode.INSTRUMENT;
        };
        animationSoundLow = soundName(c.getString("animation.sound-low"), "block.note_block.bass");
        animationSoundHigh = soundName(c.getString("animation.sound-high"), "block.note_block.bell");
        animationMaxPlaying = Math.max(0, c.getInt("animation.max-playing", 12));
        animationRadius = Math.max(8.0, c.getDouble("animation.activation-radius", 64.0));

        downloadsEnabled = c.getBoolean("downloads.enabled", true);
        downloadMaxMb = Math.max(1, c.getInt("downloads.max-size-mb", 25));
    }

    private static int clampLight(int value) {
        return Math.max(0, Math.min(15, value));
    }

    /**
     * Reads a colour written as {@code #aarrggbb}, {@code #rrggbb} or plain hex.
     *
     * <p>Six digits mean fully opaque, so {@code #ff8800} is what anyone would expect it to be
     * rather than an invisible one.</p>
     */
    private static int colorOf(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String hex = raw.trim().replace("#", "").replace("0x", "");
        try {
            long value = Long.parseLong(hex, 16);
            return hex.length() <= 6 ? (int) (0xFF000000L | value) : (int) value;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** Accepts a bare instrument name as well as the full sound key, and checks it is one we place. */
    private static String soundName(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String name = raw.trim().toLowerCase(Locale.ROOT);
        if (!name.contains(".")) name = "block.note_block." + name;
        return NoteBlocks.known(name) ? name : fallback;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw, E fallback) {
        if (raw == null) return fallback;
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
