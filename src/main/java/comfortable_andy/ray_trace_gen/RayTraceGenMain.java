package comfortable_andy.ray_trace_gen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import comfortable_andy.ray_trace_gen.accessors.BlockAccessor;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RayTraceGenMain {

    private final Gson gson = new GsonBuilder()
            .setStrictness(Strictness.LENIENT)
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static void main(String[] args) throws ParseException {
        Option minecraftLoc = Option.builder("m")
                .longOpt("minecraft")
                .required()
                .desc("Minecraft client jar file location")
                .hasArg()
                .build();
        CommandLine cl = new DefaultParser().parse(
                new Options().addOption(minecraftLoc),
                args
        );
        final File file = new File(cl.getOptionValue(minecraftLoc));
        try (URLClassLoader loader = new URLClassLoader(new URL[]{file.toURI().toURL()})) {
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void generate(String name, List<String> mats) {
        final long upper = closestLargerMultipleOfTwo(mats.size());

        final File root = new File("tags");
        root.mkdirs();
        final String fileName = name + ".json";

        final List<Pair<List<String>, File>> toWriteTo = new ArrayList<>();
        toWriteTo.add(Pair.of(mats, root));

        long val = upper;
        while (val > 1) {
            final int half = (int) (val / 2);
            final List<Pair<List<String>, File>> toAdd = new ArrayList<>();

            for (Pair<List<String>, File> pair : toWriteTo) {
                final List<String> current = pair.first();
                final File parent = pair.second();
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
                final List<String> leftList = current.subList(0, Math.min(current.size(), half));
                final List<String> rightList = current.subList(Math.min(current.size(), half), (int) Math.min(current.size(), val));
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

    public static record BlockTag(List<String> values) {
    }

    public record Pair<A, B>(A first, B second) {
        public static <C, D> Pair<C, D> of(C c, D d) {
            return new Pair<>(c, d);
        }
    }

}
