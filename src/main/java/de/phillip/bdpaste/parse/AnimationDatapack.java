package de.phillip.bdpaste.parse;

import de.phillip.bdpaste.model.BakedAnimation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the baked animation functions out of a BDEngine datapack export.
 *
 * <p>Each animation lives in {@code data/<ns>/function/k/<name>/keyframe_<tick>.mcfunction},
 * one {@code data merge entity} line per part that changed on that tick. Parts are addressed
 * by the tag {@code <ns>_<index>}, and that index is the order they are summoned in, which is
 * the same order the model parser produces them.</p>
 */
public final class AnimationDatapack {

    private static final Pattern KEYFRAME_FILE =
            Pattern.compile("(?:^|/)k/([^/]+)/keyframe_(\\d+)\\.mcfunction$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PART_TAG = Pattern.compile("tag=[A-Za-z0-9.-]+?_(\\d+)[,\\]]");
    private static final Pattern TRANSFORMATION = Pattern.compile("transformation:\\[([^]]+)]");

    /** Collects the files of one datapack as they are read, then hands out finished animations. */
    public static final class Collector {
        // animation name -> tick -> part index -> matrix
        private final Map<String, TreeMap<Integer, Map<Integer, Matrix4f>>> byName = new LinkedHashMap<>();

        /** @return true when the entry was a keyframe file and has been taken in */
        public boolean accept(String entryName, String content) {
            Matcher file = KEYFRAME_FILE.matcher(entryName.replace('\\', '/'));
            if (!file.find()) return false;

            String animation = file.group(1);
            int tick = Integer.parseInt(file.group(2));

            Map<Integer, Matrix4f> frame = new LinkedHashMap<>();
            for (String line : content.split("\\R")) {
                if (!line.contains("transformation:")) continue;

                Matcher tag = PART_TAG.matcher(line);
                Matcher matrix = TRANSFORMATION.matcher(line);
                if (!tag.find() || !matrix.find()) continue;

                float[] values = readFloats(matrix.group(1));
                if (values == null) continue;
                frame.put(Integer.parseInt(tag.group(1)), Matrices.fromRowMajor(values));
            }
            if (!frame.isEmpty()) {
                byName.computeIfAbsent(animation, k -> new TreeMap<>()).put(tick, frame);
            }
            return true;
        }

        public List<BakedAnimation> finish() {
            List<BakedAnimation> out = new ArrayList<>();
            for (Map.Entry<String, TreeMap<Integer, Map<Integer, Matrix4f>>> entry : byName.entrySet()) {
                TreeMap<Integer, Map<Integer, Matrix4f>> frames = entry.getValue();
                if (frames.isEmpty()) continue;

                // A gap in the numbering just means nothing changed on that tick.
                int last = frames.lastKey();
                List<Map<Integer, Matrix4f>> ticks = new ArrayList<>(last + 1);
                for (int t = 0; t <= last; t++) {
                    ticks.add(frames.getOrDefault(t, Map.of()));
                }
                out.add(new BakedAnimation(entry.getKey(), ticks));
            }
            return out;
        }
    }

    private AnimationDatapack() {
    }

    private static float[] readFloats(String body) {
        String[] parts = body.split(",");
        if (parts.length != 16) return null;

        float[] out = new float[16];
        for (int i = 0; i < 16; i++) {
            String value = parts[i].strip();
            if (value.endsWith("f") || value.endsWith("F")) {
                value = value.substring(0, value.length() - 1);
            }
            try {
                out[i] = Float.parseFloat(value);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return out;
    }
}
