package org.inventivetalent.mcasset.downloader;

import com.google.gson.*;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.inventivetalent.mcasset.downloader.data.Version;
import org.inventivetalent.mcasset.downloader.data.Versions;
import org.inventivetalent.mcasset.downloader.data.asset.Asset;
import org.inventivetalent.mcasset.downloader.data.asset.AssetObjects;
import org.inventivetalent.mcasset.downloader.data.asset.VersionAssetDetails;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Log4j2
public class Downloader {

	static final String VERSIONS_URL          = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
	static final String EXTERNAL_ASSET_FORMAT = "http://resources.download.minecraft.net/%s/%s";

	boolean gitEnabled  = true;
	String  gitRepo     = "https://github.com/InventivetalentDev/minecraft-assets.git";
	String  gitEmail    = "user@example.com";
	String  gitPassword = "myPassword";

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
			log.info("Downloading latest release & snapshot");
			downloadVersion(this.versions.getLatest().getRelease());
			downloadVersion(this.versions.getLatest().getSnapshot());
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
		if (!extractDirectory.exists()) { extractDirectory.mkdirs(); }

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
				Ref checkout = git.checkout().setName(safeVersion).call();
				if (checkout == null) {
					git.branchCreate().setName(safeVersion).call();

					git.add()
							.addFilepattern("assets")
							.call();
					git.add()
							.addFilepattern("data")
							.call();
					git.commit()
							.setMessage("Create new branch for version " + safeVersion)
							.setCommitter("InventiveBot", gitEmail)
							.call();
				}
			} else {
				log.info("Git is disabled");
			}

			// Write meta file
			File versionMetaFile = new File(extractDirectory, "version.json");
			versionMetaFile.createNewFile();
			versionObject.setDownloadTimestamp(System.currentTimeMillis());
			try (BufferedWriter writer = new BufferedWriter(new FileWriter(versionMetaFile))) {
				new GsonBuilder().setPrettyPrinting().create()
						.toJson(versionObject, writer);
			}

			VersionAssetDetails versionDetails = new Gson().fromJson(new JsonParser().parse(readUrl(versionObject.getUrl())), VersionAssetDetails.class);

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
				int count1=0;
				byte[] buffer = new byte[1024];
				while ((zipEntry = zipInputStream.getNextEntry()) != null) {
					log.info(zipEntry.getName());
					if (zipEntry.getName().startsWith("assets")) {
						File extractFile = new File(extractDirectory, zipEntry.getName());
						new File(extractFile.getParent()).mkdirs();
						try (FileOutputStream outputStream = new FileOutputStream(extractFile)) {
							int length;
							while ((length = zipInputStream.read(buffer)) > 0) {
								outputStream.write(buffer, 0, length);
							}
						}

						System.out.write(("\rExtracted " + (count++) + " asset files").getBytes());
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

						System.out.write(("\rExtracted " + (count1++) + " data files").getBytes());
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

			if (gitEnabled) {
				log.info("Pushing changes to remote repo...");

				git.add()
						.addFilepattern("assets")
						.addFilepattern("data")
						.addFilepattern("version.json")
						.call();
				RevCommit commit = git.commit()
						.setMessage("Create/Update assets for version " + version)
						.setCommitter("InventiveBot", gitEmail)
						.call();
				git.tag()
						.setObjectId(commit)
						.setName(safeVersion)
						.setForceUpdate(true)
						.call();
				git.push()
						.setForce(true)
						.setPushAll()
						.setPushTags()
						.setCredentialsProvider(credentialsProvider)
						.setProgressMonitor(new TextProgressMonitor(new OutputStreamWriter(System.out)))
						.call();
			}
		} catch (IOException | GitAPIException e) {
			throw new RuntimeException(e);
		}
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
