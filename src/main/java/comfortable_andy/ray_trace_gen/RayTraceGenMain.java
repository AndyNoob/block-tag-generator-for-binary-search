package comfortable_andy.ray_trace_gen;

import com.destroystokyo.paper.MaterialTags;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.datafixers.util.Pair;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RayTraceGenMain extends JavaPlugin {

    private final Gson gson = new GsonBuilder().setLenient().setPrettyPrinting().disableHtmlEscaping().create();

    @Override
    public void onEnable() {
        final long count = Arrays.stream(Material.values()).filter(Material::isBlock).count();
        getLogger().info("Block #: " + count);
        generate("non_full", Arrays.stream(Material.values()).filter(m -> m.isBlock() && m.isCollidable() && !m.isOccluding()).toList());
        generate("glass_panes", Arrays.stream(Material.values()).filter(m -> MaterialTags.GLASS_PANES.isTagged(m) && MaterialTags.STAINED_GLASS_PANES.isTagged(m)).toList());
    }

    private void generate(String name, List<Material> mats) {
        final long upper = closestLargerMultipleOfTwo(mats.size());
        getLogger().info("Tag start: " + upper);

        final File root = new File(getDataFolder(), "tags");
        root.mkdirs();
        final String fileName = name + ".json";

        final List<Pair<List<Material>, File>> toWriteTo = new ArrayList<>();
        toWriteTo.add(Pair.of(mats, root));

        long val = upper;
        while (val > 1) {
            final int half = (int) (val / 2);
            final List<Pair<List<Material>, File>> toAdd = new ArrayList<>();

            for (Pair<List<Material>, File> pair : toWriteTo) {
                final List<Material> current = pair.getFirst();
                final File parent = pair.getSecond();
                final File leftFolder = new File(parent, val + "l/");
                final File rightFolder = new File(parent, val + "r/");
                leftFolder.mkdirs();
                rightFolder.mkdirs();
                final File leftFile = new File(leftFolder, fileName);
                final File rightFile = new File(rightFolder, fileName);
                leftFile.delete();
                rightFile.delete();
                try {
                    leftFile.createNewFile();
                    rightFile.createNewFile();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                final List<Material> leftList = current.subList(0, Math.min(current.size(), half));
                final List<Material> rightList = current.subList(Math.min(current.size(), half), (int) Math.min(current.size(), val));
                try (FileWriter left = new FileWriter(leftFile); FileWriter right = new FileWriter(rightFile)) {
                    left.write(gson.toJson(new BlockTag(leftList)));
                    right.write(gson.toJson(new BlockTag(rightList)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                toAdd.add(Pair.of(leftList, leftFolder));
                toAdd.add(Pair.of(rightList, rightFolder));
            }

            toWriteTo.clear();
            toWriteTo.addAll(toAdd);
            val /= 2;
        }
    }

    private long closestLargerMultipleOfTwo(long val) {
        return 2048;
    }

    public static class BlockTag {
        private final List<String> blocks;
        public BlockTag(List<Material> mats) {
            this.blocks = mats.stream().map(m -> m.asBlockType().key().asString()).toList();
        }
    }

}
