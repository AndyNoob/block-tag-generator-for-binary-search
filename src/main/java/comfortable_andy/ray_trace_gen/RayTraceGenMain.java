package comfortable_andy.ray_trace_gen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import me.kcra.takenaka.accessor.platform.MapperPlatform;
import me.kcra.takenaka.accessor.platform.MapperPlatforms;
import org.apache.commons.cli.*;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public final class RayTraceGenMain {

    private static final Gson GSON = new GsonBuilder()
            .setStrictness(Strictness.LENIENT)
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static void main(String[] args) throws ParseException, ReflectiveOperationException, IOException {
        Option minecraftVer = Option.builder("v")
                .longOpt("version")
                .required()
                .desc("Minecraft client jar version")
                .hasArg()
                .build();
        CommandLine cl = new DefaultParser().parse(
                new Options().addOption(minecraftVer),
                args
        );
        VersionManifest manifest = GSON.fromJson(Jsoup.connect("https://launchermeta.mojang.com/mc/game/version_manifest.json").ignoreContentType(true).get().body().text(), VersionManifest.class);
        VersionManifest.Version version = manifest.getVersion(cl.getOptionValue(minecraftVer));
        VersionData data = GSON.fromJson(Jsoup.connect(version.url()).ignoreContentType(true).get().body().text(), VersionData.class);

        final Path folder = Files
                .createTempDirectory(UUID.randomUUID() + "-" + new Date());
        final List<URL> urls = new ArrayList<>();

        System.out.println("Downloading server... (" + data.downloads().client().url() + ")");
        URL url = downloadFile(folder, "server.jar", data.downloads().server().url()).toUri().toURL();

        System.out.println("Done: " + url);

        urls.add(url);

        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new))) {
            System.setProperty("user.dir", folder.toString());

            System.out.println(Paths.get("").toAbsolutePath());
            try (FileWriter writer = new FileWriter(new File(folder.toFile(), "eula.txt"))) {
                writer.write("eula=true\n");
            }
            MapperPlatforms.setCurrentPlatform(
                    MapperPlatform.create(cl.getOptionValue(minecraftVer), loader, "source")
            );
            loader.loadClass("net.minecraft.bundler.Main").getDeclaredMethod("main", String[].class).invoke(null, (Object) new String[]{"nogui"});
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        System.out.println("Grabbing information...");
    }

    private static Path downloadFile(Path folder, String name, String url) throws IOException {
        Path path = folder.resolve(name);
        File file = path.toFile();
        file.getParentFile().mkdirs();
        file.createNewFile();
        file.deleteOnExit();
        Connection.Response response = Jsoup.connect(url).ignoreContentType(true).userAgent("block-tag-generator").execute();
        try (BufferedInputStream bufferStream = response.bodyStream(); FileOutputStream fileStream = new FileOutputStream(file)) {
            bufferStream.transferTo(fileStream);
        }
        return path;
    }

    private static void generate(String name, List<String> mats) {
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
                    left.write(GSON.toJson(new BlockTag(leftList)));
                    right.write(GSON.toJson(new BlockTag(rightList)));
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

    private static long closestLargerMultipleOfTwo(long val) {
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
