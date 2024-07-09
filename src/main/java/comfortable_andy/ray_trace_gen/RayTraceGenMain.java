package comfortable_andy.ray_trace_gen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import comfortable_andy.ray_trace_gen.accessors.*;
import me.kcra.takenaka.accessor.platform.MapperPlatform;
import me.kcra.takenaka.accessor.platform.MapperPlatforms;
import org.apache.commons.cli.*;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class RayTraceGenMain {

    private static final Gson GSON = new GsonBuilder()
            .setStrictness(Strictness.LENIENT)
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void main(String[] args) throws ParseException, ReflectiveOperationException, IOException, InterruptedException {
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

        System.out.println("Downloading server... (" + data.downloads().client().url() + ")");
        URL url = downloadFile(folder, "server.jar", data.downloads().server().url()).toUri().toURL();

        System.out.println("Done: " + url);

        final List<File> delete = new ArrayList<>();

        try (WatchService service = FileSystems.getDefault().newWatchService()) {
            File eula = new File("eula.txt");
            try (FileWriter writer = new FileWriter(eula)) {
                writer.write("eula=true");
            }
            eula.deleteOnExit();
            Paths.get("").register(service, StandardWatchEventKinds.ENTRY_CREATE);
            try (URLClassLoader loader = new URLClassLoader(new URL[]{url})) {
                try (FileWriter writer = new FileWriter(new File(folder.toFile(), "eula.txt"))) {
                    writer.write("eula=true\n");
                }
                MapperPlatforms.setCurrentPlatform(
                        MapperPlatform.create(
                                cl.getOptionValue(minecraftVer),
                                loader,
                                "spigot"
                        )
                );
                loader.loadClass("net.minecraft.bundler.Main").getDeclaredMethod("main", String[].class).invoke(null, (Object) new String[]{"nogui"});
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            WatchKey key;
            while ((key = service.poll(5, TimeUnit.SECONDS)) != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    String created = String.valueOf(event.context());
                    delete.add(new File(created));
                    System.out.println("Marked " + created + " for deletion.");
                }
                key.reset();
            }
            System.out.println("Grabbing information...");

            int fullCounter = 0;
            int nonFullCounter = 0;

            Object blockRegistry = BuiltInRegistriesAccessor.FIELD_BLOCK.get();

            if (blockRegistry == null) {
                System.out.println("Could not find block registry, exiting...");
                return;
            }

            for (Object o : ((Iterable<?>) blockRegistry)) {
                Class<?> blockClass = BlockAccessor.TYPE.get();
                if (!blockClass.isAssignableFrom(o.getClass()))
                    throw new IllegalStateException("Values in the BLOCK registry isn't of type Block?");
                Object blockState = BlockAccessor.METHOD_DEFAULT_BLOCK_STATE.get().invoke(o);
                Object emptyGetter = EmptyBlockGetterAccessor.FIELD_INSTANCE.get();
                Object blockPosZero = BlockPosAccessor.FIELD_ZERO.get();
                Object emptyCollisionContext = CollisionContextAccessor.METHOD_EMPTY.get().invoke(null);
                Method getShape = BlockBehaviourAccessor.METHOD_GET_COLLISION_SHAPE.get();
                Object shape = getShape.invoke(
                        o,
                        blockState,
                        emptyGetter,
                        blockPosZero,
                        emptyCollisionContext
                );
                if ((boolean) BlockAccessor.METHOD_IS_SHAPE_FULL_BLOCK.get().invoke(null, shape)) fullCounter++;
                else nonFullCounter++;
            }
            System.out.println("Found " + fullCounter + " full blocks");
            System.out.println("Found " + nonFullCounter + " non full blocks");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            System.out.println("Closing server...");
            ByteArrayInputStream inputStream =
                    new ByteArrayInputStream("/stop\r".getBytes(StandardCharsets.UTF_8));
            InputStream in = System.in;

            // finally executes after closing
            //noinspection TryFinallyCanBeTryWithResources
            try {
                System.out.println("Hijacking input stream...");
                System.setIn(inputStream);
            } finally {
                System.setIn(in);
                System.out.println("Done.");
                inputStream.close();
            }

            PrintStream out = System.out;
            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            final PrintStream newOut = new PrintStream(outputStream);
            System.setOut(newOut);
            do {
                out.println("Waiting 5 seconds for new logs...");
                Thread.sleep(5000);
            } while (!outputStream.toString().isEmpty());
            outputStream.close();
            newOut.close();
            System.setOut(out);

            System.out.println("Server (should be) down.");
            System.out.println("Doing cleanups...");
            for (File file : delete) {
                System.out.println("Deleting " + file.toPath());
                if (file.isDirectory()) {
                    Files.walk(file.toPath()).forEach(p -> p.toFile().delete());
                    file.delete();
                }
                else file.delete();
            }
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
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
