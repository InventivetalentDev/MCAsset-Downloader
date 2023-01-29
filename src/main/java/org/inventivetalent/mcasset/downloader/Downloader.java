package org.inventivetalent.mcasset.downloader;

import com.backblaze.b2.client.B2StorageClient;
import com.backblaze.b2.client.B2StorageClientFactory;
import com.backblaze.b2.client.contentSources.B2ContentTypes;
import com.backblaze.b2.client.contentSources.B2FileContentSource;
import com.backblaze.b2.client.structures.B2UploadFileRequest;
import com.google.gson.*;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.util.Strings;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.inventivetalent.mcasset.downloader.data.Downloads;
import org.inventivetalent.mcasset.downloader.data.Version;
import org.inventivetalent.mcasset.downloader.data.Versions;
import org.inventivetalent.mcasset.downloader.data.asset.Asset;
import org.inventivetalent.mcasset.downloader.data.asset.AssetObjects;
import org.inventivetalent.mcasset.downloader.data.asset.VersionAssetDetails;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Log4j2
public class Downloader {

    static final String VERSIONS_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final String EXTERNAL_ASSET_FORMAT = "https://resources.download.minecraft.net/%s/%s";

    boolean gitEnabled = true;
    String gitRepo = "https://github.com/InventivetalentDev/minecraft-assets.git";
    String gitEmail = "user@example.com";
    String gitPassword = "myPassword";
    String b2Bucket = "";
    String b2App = "";
    String b2AppKey = "";

    Versions versions;

    public void readConfig() {
        File configFile = new File("config.properties");
        if (!configFile.exists()) {
            log.info("Saving default config file...");
            URL input = getClass().getResource("/config.properties");
            try {
                FileUtils.copyURLToFile(input, configFile);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            log.info("Please edit the config file and restart");
            System.exit(0);
            return;
        }

        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(configFile));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        this.gitRepo = properties.getProperty("git.repo");
        this.gitEmail = properties.getProperty("git.email");
        this.gitPassword = properties.getProperty("git.password");

        this.b2Bucket = properties.getProperty("b2.bucket");
        this.b2App = properties.getProperty("b2.app");
        this.b2AppKey = properties.getProperty("b2.appkey");
    }

    public void setGitEnabled(boolean gitEnabled) {
        this.gitEnabled = gitEnabled;
    }

    public void initVersions() {
        JsonElement jsonElement = new JsonParser().parse(readUrl(VERSIONS_URL));
        JsonObject jsonObject = jsonElement.getAsJsonObject();

        this.versions = new Gson().fromJson(jsonObject, Versions.class);

        log.info("Versions initialized");
        log.info("Latest: " + this.versions.getLatest().getRelease() + " release / " + this.versions.getLatest().getSnapshot() + " snapshot");
        log.info("Found " + this.versions.getVersions().size() + " individual versions");
    }

    void downloadVersion(String version) {
        if ("latest-release".equals(version)) {
            log.info("Downloading latest release");
            downloadVersion(this.versions.getLatest().getRelease());
            return;
        }
        if ("latest-snapshot".equals(version)) {
            log.info("Downloading latest snapshot");
            downloadVersion(this.versions.getLatest().getSnapshot());
            return;
        }
        if ("latest".equals(version)) {
            log.info("Downloading latest snapshot & release");
            downloadVersion(this.versions.getLatest().getSnapshot());
            downloadVersion(this.versions.getLatest().getRelease());
            return;
        }

        if ("all-snapshots".equals(version)) {
            log.info("Downloading all snapshot versions...");
            this.versions.getVersions().stream().filter(version1 -> "snapshot".equals(version1.getType())).forEach(version1 -> downloadVersion(version1.getId()));
            return;
        }
        if ("all-releases".equals(version)) {
            log.info("Downloading all release versions...");
            this.versions.getVersions().stream().filter(version1 -> "release".equals(version1.getType())).forEach(version1 -> downloadVersion(version1.getId()));
            return;
        }

        // Validate version
        Version versionObject = null;
        for (Version version1 : versions.getVersions()) {
            if (version1.getId().equals(version)) {
                versionObject = version1;
                break;
            }
        }
        if (versionObject == null) {
            throw new IllegalArgumentException("Version " + version + " does not exist in index");
        }

        String safeVersion = version.replace("_", "__").replace(" ", "_");

        // Create extract directories
        System.out.println();
        System.out.println();
        log.info("Cleaning up old files...");
        File extractBaseDirectory = new File("extract");
        if (extractBaseDirectory.exists()) {
            try {
                FileUtils.deleteDirectory(extractBaseDirectory);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        extractBaseDirectory.mkdirs();
        File extractDirectory = new File(extractBaseDirectory, safeVersion);
        if (!extractDirectory.exists()) {extractDirectory.mkdirs();}

        try {
            // Init git
            Git git = null;
            CredentialsProvider credentialsProvider = null;
            if (gitEnabled) {
                log.info("Initializing Git...");
                credentialsProvider = new UsernamePasswordCredentialsProvider(gitEmail, gitPassword);
                log.info("Cloning repository...");
                git = Git.cloneRepository()
                        .setURI(gitRepo)
                        .setBranchesToClone(Arrays.asList("master", safeVersion))
                        .setDirectory(extractDirectory)
                        .setCredentialsProvider(credentialsProvider)
                        .setProgressMonitor(new TextProgressMonitor(new OutputStreamWriter(System.out)))
                        .call();
                StoredConfig config = git.getRepository().getConfig();
                config.setString("user", null, "email", gitEmail);
                config.save();

                // git checkout
                Ref checkout = null;
                try {
                    checkout = git.checkout().setName(safeVersion).call();
                } catch (RefNotFoundException ignored) {
                }
                if (checkout == null) {
                    checkout = git.branchCreate().setName(safeVersion).call();
                    checkout = git.checkout().setName(safeVersion).call();
                    git.commit()
                            .setMessage("Create new branch for version " + safeVersion)
                            .setCommitter("InventiveBot", gitEmail)
                            .call();
                }
            } else {
                log.info("Git is disabled");
            }

            B2StorageClient b2Client = null;
            if (!Strings.isBlank(this.b2App)) {
                try {
                    b2Client = B2StorageClientFactory.createDefaultFactory()
                            .create(this.b2App, this.b2AppKey, "MCAssetDownloader");
                } catch (Exception e) {
                    log.log(Level.WARN, "", e);
                }
            } else {
                log.info("B2 is disabled");
            }

            // delete any old data
            try {
                for (File file : extractDirectory.listFiles()) {
                    if (file.getName().contains("git")) {continue;}
                    if (file.isDirectory()) {
                        FileUtils.deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            //            if (gitEnabled) {
            //                try {
            //                    git.rm()
            //                            .addFilepattern("assets")
            //                            .addFilepattern("data")
            //                            .addFilepattern("mappings")
            //                            .addFilepattern(versionObject.getId() + ".json")
            //                            .addFilepattern("version.json")
            //                            .call();
            //                } catch (Exception e) {
            //                    e.printStackTrace();
            //                }
            //            }

            // Write meta file
            File versionMetaFile = new File(extractDirectory, "version.json");
            versionMetaFile.createNewFile();
            versionObject.setDownloadTimestamp(System.currentTimeMillis());
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(versionMetaFile))) {
                new GsonBuilder().setPrettyPrinting().create()
                        .toJson(versionObject, writer);
            }

            VersionAssetDetails versionDetails = new Gson().fromJson(new JsonParser().parse(readUrl(versionObject.getUrl())), VersionAssetDetails.class);
            downloadFile(versionObject.getUrl(), new File(extractDirectory, versionObject.getId() + ".json"), null);

            AssetObjects assets = new Gson().fromJson(new JsonParser().parse(readUrl(versionDetails.getAssetIndex().getUrl())), AssetObjects.class);

            // Download
            log.info("Downloading version " + version + "...");

            String jarDownload = versionDetails.getDownloads().getClient().getUrl();
            File tempFile = Files.createTempFile("mcasset-downloader", "").toFile();
            downloadFile(jarDownload, tempFile, null);
            System.out.println();

            // Extract assets
            System.out.println("Extracting archive...");

            try (ZipInputStream zipInputStream = new ZipInputStream(new BufferedInputStream(new FileInputStream(tempFile)))) {
                ZipEntry zipEntry;

                int count = 0;
                int count1 = 0;
                byte[] buffer = new byte[1024];
                while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                    if (zipEntry.getName().startsWith("assets")) {
                        File extractFile = new File(extractDirectory, zipEntry.getName());
                        new File(extractFile.getParent()).mkdirs();
                        try (FileOutputStream outputStream = new FileOutputStream(extractFile)) {
                            int length;
                            while ((length = zipInputStream.read(buffer)) > 0) {
                                outputStream.write(buffer, 0, length);
                            }
                        }

                        System.out.write(("\rExtracted " + (count++) + " asset files " + zipEntry.getName()).getBytes());
                    }
                    if (zipEntry.getName().startsWith("data")) {
                        File extractFile = new File(extractDirectory, zipEntry.getName());
                        new File(extractFile.getParent()).mkdirs();
                        try (FileOutputStream outputStream = new FileOutputStream(extractFile)) {
                            int length;
                            while ((length = zipInputStream.read(buffer)) > 0) {
                                outputStream.write(buffer, 0, length);
                            }
                        }

                        System.out.write(("\rExtracted " + (count1++) + " data files " + zipEntry.getName()).getBytes());
                    }
                }
            }
            System.out.println();

            // Delete temporary file
            tempFile.delete();

            // Download external assets
            log.info("Downloading external assets...");

            AtomicInteger count = new AtomicInteger();
            for (Map.Entry<String, Asset> entry : assets.getObjects().entrySet()) {
                String assetDownload = String.format(EXTERNAL_ASSET_FORMAT, entry.getValue().getHash().substring(0, 2), entry.getValue().getHash());
                File assetOutput = new File(extractDirectory, "assets/" + entry.getKey());
                new File(assetOutput.getParent()).mkdirs();
                count.incrementAndGet();
                downloadFile(assetDownload, assetOutput, new ProgressCallback() {
                    @Override
                    public void call(double now, double total) {
                        try {
                            String a = "Downloading asset " + (count.get()) + "/" + assets.getObjects().size();
                            String b = (Math.round(now * 100.0) / 100.0) + "MB/" + (Math.round(total * 100.0) / 100.0) + "MB";
                            System.out.write(("\r" + String.format("%-30s %s", a, b)).getBytes());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
            System.out.println();

            Downloads downloads = versionDetails.getDownloads();
            if (downloads.getClientMappings() != null && downloads.getServerMappings() != null) {
                // Download mappings
                log.info("Downloading mappings...");
                File mappingsOut = new File(extractDirectory, "mappings");
                mappingsOut.mkdirs();

                downloadFile(downloads.getClientMappings().getUrl(), new File(mappingsOut, "client.txt"), new ProgressCallback() {
                    @Override
                    public void call(double now, double total) {
                        try {
                            String b = (Math.round(now * 100.0) / 100.0) + "MB/" + (Math.round(total * 100.0) / 100.0) + "MB";
                            System.out.write(("\rclient.txt " + String.format("%-30s", b)).getBytes());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
                System.out.println();
                downloadFile(downloads.getServerMappings().getUrl(), new File(mappingsOut, "server.txt"), new ProgressCallback() {
                    @Override
                    public void call(double now, double total) {
                        try {
                            String b = (Math.round(now * 100.0) / 100.0) + "MB/" + (Math.round(total * 100.0) / 100.0) + "MB";
                            System.out.write(("\rserver.txt " + String.format("%-30s", b)).getBytes());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
            System.out.println();

            createFileListAndAllFile(extractDirectory);

            if (gitEnabled) {
                log.info("Pushing changes to remote repo...");

                git.add()
                        .addFilepattern(".")
                        .call();
                RevCommit commit = git.commit()
                        .setAll(true)
                        .setAllowEmpty(true)
                        .setMessage("Create/Update assets for version " + version)
                        .setCommitter("InventiveBot", gitEmail)
                        .call();
                System.out.println(commit.getId() + "  " + commit.getShortMessage());
                Ref ref = git.tag()
                        .setObjectId(commit)
                        .setName(safeVersion)
                        .setForceUpdate(true)
                        .call();
                System.out.println(ref.getName());
                Iterable<PushResult> result = git.push()
                        .setRemote("origin")
                        .setPushAll()
                        .setPushTags()
                        .setCredentialsProvider(credentialsProvider)
                        .setProgressMonitor(new TextProgressMonitor(new OutputStreamWriter(System.out)))
                        .call();
            }

            if (b2Client != null) {
                log.info("Uploading to b2...");

                ExecutorService uploadExecutor = Executors.newFixedThreadPool(64);

                B2StorageClient finalB2Client = b2Client;
                List<Future<?>> futures = Files.walk(extractDirectory.toPath())
                        .filter(Files::isRegularFile)
                        .filter(p -> !p.toString().contains(".git"))
                        .map(path -> {
                            final File file = path.toFile();
                            final String fullName = file.getPath().replaceFirst("extract/", "");
                            try {
                                if (file.length() > 5000000) {
                                    return uploadExecutor.submit(() -> {
                                        log.info("L" + fullName);
                                        try {
                                            finalB2Client.uploadLargeFile(B2UploadFileRequest
                                                    .builder(this.b2Bucket, fullName, B2ContentTypes.B2_AUTO, B2FileContentSource
                                                            .build(file)).build(), uploadExecutor);
                                        } catch (Exception e) {
                                            log.log(Level.WARN, "", e);
                                        }
                                    });
                                } else {
                                    return uploadExecutor.submit(() -> {
                                        log.info("S" + fullName);
                                        try {
                                            finalB2Client.uploadSmallFile(B2UploadFileRequest
                                                    .builder(this.b2Bucket, fullName, B2ContentTypes.B2_AUTO, B2FileContentSource
                                                            .build(file)).build());
                                        } catch (Exception e) {
                                            log.log(Level.WARN, "", e);
                                        }
                                    });
                                }
                            } catch (Exception e) {
                                log.log(Level.WARN, "", e);
                            }
                            return CompletableFuture.completedFuture(null);
                        }).collect(Collectors.toList());

                System.out.println("Waiting for uploads...");
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.MINUTES);
                }
                System.out.println("Waiting for upload executor...");
                uploadExecutor.shutdown();
                boolean b = uploadExecutor.awaitTermination(60, TimeUnit.MINUTES);
                System.out.println(b);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        System.out.println();

        System.out.println("Finished downloading " + version);
    }

    void createFileListAndAllFile(File directory) {
        if (!directory.isDirectory()) {return;}
        JsonArray fileNames = Arrays.stream(Objects.requireNonNull(directory.listFiles()))
                .filter(File::isFile)
                .map(File::getName)
                .sorted()
                .collect(JsonArray::new, JsonArray::add, JsonArray::add);
        JsonArray directoryNames = Arrays.stream(Objects.requireNonNull(directory.listFiles()))
                .filter(File::isDirectory)
                .map(File::getName)
                .filter(s -> !".git".equals(s))
                .sorted()
                .collect(JsonArray::new, JsonArray::add, JsonArray::add);
        JsonObject listJson = new JsonObject();
        listJson.add("directories", directoryNames);
        listJson.add("files", fileNames);
        File listFile = new File(directory, "_list.json");
        Gson gson = new Gson();
        try {
            listFile.createNewFile();
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(listFile), StandardCharsets.UTF_8))) {
                gson.toJson(listJson, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        JsonObject allObject = new JsonObject();
        Arrays.stream(Objects.requireNonNull(directory.listFiles()))
                .filter(File::isFile)
                .filter(f -> f.getName().endsWith(".json") && !"_list.json".equals(f.getName()))
                .sorted(Comparator.comparing(File::getName))
                .forEach(file -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                        JsonObject obj = gson.fromJson(reader, JsonObject.class);
                        allObject.add(file.getName().replace(".json", ""), obj);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
        if (!allObject.keySet().isEmpty()) {
            File allFile = new File(directory, "_all.json");
            try {
                allFile.createNewFile();
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(allFile), StandardCharsets.UTF_8))) {
                    gson.toJson(allObject, writer);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Arrays.stream(Objects.requireNonNull(directory.listFiles()))
                .filter(File::isDirectory)
                .forEach(this::createFileListAndAllFile);
    }

    void downloadFile(String inputUrl, File outputFile, ProgressCallback callback) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(inputUrl).openConnection();
        long totalFileSize = connection.getContentLength();
        long downloadedFileSize = 0;
        double totalMb = totalFileSize / 1024.0D / 1024.0D;
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream())) {
            try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(outputFile))) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = input.read(buffer, 0, 1024)) > 0) {
                    output.write(buffer, 0, length);
                    downloadedFileSize += length;

                    double downloadedMb = (double) downloadedFileSize / 1024.0D / 1024.0D;
                    if (callback != null) {
                        callback.call(downloadedMb, totalMb);
                    } else {
                        System.out.write(("\rDownloaded " + (Math.round(downloadedMb * 100.0) / 100.0) + "MB/" + (Math.round(totalMb * 100.0) / 100.0) + "MB").getBytes());
                    }
                }
            }
        } catch (IOException e) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }
            throw e;
        }
    }

    String readUrl(String urlString) {
        String string = "";
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    string += line;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return string;
    }

}
