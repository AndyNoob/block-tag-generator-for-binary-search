package comfortable_andy.ray_trace_gen;

import com.destroystokyo.paper.MaterialSetTag;
import com.destroystokyo.paper.MaterialTags;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.datafixers.util.Pair;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RayTraceGenMain extends JavaPlugin {

    private final Gson gson = new GsonBuilder().setLenient().setPrettyPrinting().disableHtmlEscaping().create();
    private final List<Tag<Material>> tags = List.of(Tag.STAIRS, Tag.SLABS, Tag.CANDLES, Tag.WALLS, Tag.ALL_SIGNS, Tag.ITEMS_SKULLS, Tag.ALL_HANGING_SIGNS, Tag.WOOL_CARPETS, Tag.DOORS, Tag.TRAPDOORS);

    @Override
    public void onEnable() {
        final long count = Arrays.stream(Material.values()).filter(Material::isBlock).count();
        final World world = Bukkit.getWorld(MinecraftServer.getServer().server.getHandle().getServer().getProperties().levelName);
        getLogger().info("Block #: " + count);

        final List<Material> nonFull = Arrays.stream(Material.values()).filter(Material::isBlock).filter(m -> {
            Block block = world.getBlockAt(0, world.getMaxHeight() - 1, 0);
            block.setType(m);
            return !isCube(block);
        }).toList();
        getLogger().info("Non full #: " + nonFull.size());


        generate("non_full", nonFull);
        generate("glass_panes", Arrays.stream(Material.values()).filter(m -> MaterialTags.GLASS_PANES.isTagged(m) && MaterialTags.STAINED_GLASS_PANES.isTagged(m)).toList());
    }

    private boolean isCube(Block block) {
        // from https://www.spigotmc.org/threads/how-to-check-if-a-block-is-realy-a-block.536470/#post-4314270
        VoxelShape voxelShape = block.getCollisionShape();
        BoundingBox boundingBox = block.getBoundingBox();
        return (voxelShape.getBoundingBoxes().size() == 1
                && boundingBox.getWidthX() == 1.0
                && boundingBox.getHeight() == 1.0
                && boundingBox.getWidthZ() == 1.0
        );
    }

    private void generate(String name, List<Material> mats) {
        final long upper = closestLargerMultipleOfTwo(mats.size());

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
        private final List<String> values;
        public BlockTag(List<Material> mats) {
            this.values = mats.stream().map(m -> m.asBlockType().key().asString()).toList();
        }
    }

}
