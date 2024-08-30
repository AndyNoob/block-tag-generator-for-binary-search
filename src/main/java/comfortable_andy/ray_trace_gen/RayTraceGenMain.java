package comfortable_andy.ray_trace_gen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import comfortable_andy.ray_trace_gen.accessors.*;
import me.kcra.takenaka.accessor.platform.MapperPlatform;
import me.kcra.takenaka.accessor.platform.MapperPlatforms;
import org.apache.commons.cli.*;
import org.jetbrains.annotations.NotNull;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.*;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public final class RayTraceGenMain {

    private static final Gson GSON = new GsonBuilder()
            .setStrictness(Strictness.LENIENT)
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static void main(String[] args) throws Throwable {
        Option minecraftVer = Option.builder("v")
                .longOpt("version")
                .desc("Minecraft client jar version")
                .hasArg()
                .build();
        Option files = Option.builder("f")
                .longOpt("files")
                .desc("Block tag json files")
                .hasArg()
                .build();
        CommandLine cl = new DefaultParser().parse(
                new Options().addOption(minecraftVer).addOption(files),
                args
        );
        VersionManifest manifest = GSON.fromJson(Jsoup.connect("https://launchermeta.mojang.com/mc/game/version_manifest.json").ignoreContentType(true).get().body().text(), VersionManifest.class);
        String minecraftVersion = cl.getOptionValue(minecraftVer);
        if (minecraftVersion != null) runServer(manifest, minecraftVersion);
        else {
            generate(Arrays.stream(Objects.requireNonNull(Paths.get("").toAbsolutePath().toFile().listFiles(f -> !f.isDirectory() && f.getName().split("\\.")[1].equals("json")))).collect(Collectors.toList()), (root, file) -> {
                try {
                    Files.copy(file.toPath(), root.toPath().resolve(file.getName()));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void runServer(VersionManifest manifest, String minecraftVersion) throws Throwable {
        VersionManifest.Version version = manifest.getVersion(minecraftVersion);
        VersionData data = GSON.fromJson(Jsoup.connect(version.url()).ignoreContentType(true).get().body().text(), VersionData.class);

        final Path folder = Files
                .createTempDirectory(UUID.randomUUID() + "-" + new Date());

        System.out.println("Downloading server... (" + data.downloads().server().url() + ")");
        URL url = downloadFile(folder, "server.jar", data.downloads().server().url()).toUri().toURL();

        System.out.println("Done: " + url);

        final List<File> delete = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{url}, RayTraceGenMain.class.getClassLoader())) {
            try (WatchService service = FileSystems.getDefault().newWatchService()) {
                File eula = new File("eula.txt");
                try (FileWriter writer = new FileWriter(eula)) {
                    writer.write("eula=true");
                }
                eula.deleteOnExit();
                Paths.get("").register(service, StandardWatchEventKinds.ENTRY_CREATE);
                try (FileWriter writer = new FileWriter(new File(folder.toFile(), "eula.txt"))) {
                    writer.write("eula=true\n");
                }

                final URLClassLoader minecraftLoader = bootServer(loader);

                MapperPlatforms.setCurrentPlatform(
                        MapperPlatform.create(
                                minecraftVersion,
                                minecraftLoader,
                                "source"
                        )
                );

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
                    out.println("Waiting 2 seconds for new logs...");
                    Thread.sleep(2000);
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
                    } else file.delete();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static URLClassLoader bootServer(URLClassLoader loader) throws Throwable {
        final Object main = loader.loadClass("net.minecraft.bundler.Main").getConstructor().newInstance();

        String defaultMainClassName;

        try (InputStream stream = main.getClass().getResourceAsStream("/META-INF/main-class")) {
            assert stream != null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                defaultMainClassName = reader.readLine().trim();
            }
        }

        String mainClassName = System.getProperty("bundlerMainClass", defaultMainClassName);
        String repoDir = System.getProperty("bundlerRepoDir", "");
        Path outputDir = Paths.get(repoDir);
        Files.createDirectories(outputDir);
        List<URL> extractedUrls = new ArrayList<>();
        Method readAndExtractDir = main.getClass().getDeclaredMethod("readAndExtractDir", String.class, Path.class, List.class);
        readAndExtractDir.trySetAccessible();
        readAndExtractDir.invoke(main, "versions", outputDir, extractedUrls);
        readAndExtractDir.invoke(main, "libraries", outputDir, extractedUrls);
        if (mainClassName == null || mainClassName.isEmpty()) {
            System.out.println("Empty main class specified, exiting");
            System.exit(0);
        }
        System.out.println("Starting " + mainClassName);
        final Pair<Thread, URLClassLoader> pair = makeThread(loader, mainClassName, extractedUrls);
        pair.first().start();
        return pair.second();
    }

    @NotNull
    private static Pair<Thread, URLClassLoader> makeThread(URLClassLoader loader, String mainClassName, List<URL> extractedUrls) {
        final URLClassLoader minecraftLoader = new URLClassLoader(extractedUrls.toArray(URL[]::new), loader);
        final Thread runThread = new Thread(() -> {
            try {
                Class<?> mainClass = Class.forName(mainClassName, true, minecraftLoader);
                MethodHandle mainHandle = MethodHandles
                        .lookup()
                        .findStatic(
                                mainClass,
                                "main",
                                MethodType.methodType(Void.TYPE, String[].class)
                        ).asFixedArity();
                mainHandle.invoke((Object[]) new String[]{"nogui"});
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }

        }, "ServerMain");
        runThread.setContextClassLoader(minecraftLoader);
        return Pair.of(runThread, minecraftLoader);
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

    private static <T> void generate(List<T> files, BiConsumer<File, T> saver) throws IOException {
        final long upper = closestLargerMultipleOfTwo(files.size());

        final File root = new File("tags");

        final List<Pair<List<T>, File>> toWriteTo = new ArrayList<>();
        toWriteTo.add(Pair.of(files, root));

        root.mkdirs();

        for (T file : files) {
            saver.accept(root, file);
        }

        long val = upper;
        while (val > 1) {
            final int half = (int) (val / 2);
            final List<Pair<List<T>, File>> toAdd = new ArrayList<>();

            for (Pair<List<T>, File> pair : toWriteTo) {
                final List<T> current = pair.first();
                final File parent = pair.second();
                parent.mkdirs();
                final File leftFolder = new File(parent, val + "l/");
                final File rightFolder = new File(parent, val + "r/");
                leftFolder.mkdirs();
                rightFolder.mkdirs();
                final List<T> leftList = current.subList(0, Math.min(current.size(), half));
                final List<T> rightList = current.subList(Math.min(current.size(), half), (int) Math.min(current.size(), val));
                for (T file : leftList) {
                    saver.accept(leftFolder, file);
                }
                for (T file : rightList) {
                    saver.accept(rightFolder, file);
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
        int multiple = 1;
        while (multiple < val) {
            multiple *= 2;
        }
        return multiple;
    }

    public static record BlockTag(List<String> values) {
    }

    public record Pair<A, B>(A first, B second) {
        public static <C, D> Pair<C, D> of(C c, D d) {
            return new Pair<>(c, d);
        }
    }

}
