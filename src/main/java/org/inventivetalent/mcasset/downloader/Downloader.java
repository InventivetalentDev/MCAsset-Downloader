package org.inventivetalent.mcasset.downloader;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.inventivetalent.mcasset.downloader.data.Version;
import org.inventivetalent.mcasset.downloader.data.Versions;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Log4j2
public class Downloader {

	static final String VERSIONS_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
	static final String JAR_FORMAT   = "https://s3.amazonaws.com/Minecraft.Download/versions/%s/%s.jar";

	String gitRepo     = "https://github.com/InventivetalentDev/minecraft-assets.git";
	String gitEmail    = "user@example.com";
	String gitPassword = "myPassword";

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

	public void initVersions() {
		JsonElement jsonElement = new JsonParser().parse(readUrl(VERSIONS_URL));
		JsonObject jsonObject = jsonElement.getAsJsonObject();

		this.versions = new Gson().fromJson(jsonObject, Versions.class);

		log.info("Versions initialized");
		log.info("Latest: " + this.versions.getLatest().getRelease() + " release / " + this.versions.getLatest().getSnapshot() + " snapshot");
		log.info("Found " + this.versions.getVersions().size() + " individual versions");
	}

	void downloadVersion(String version) {
		if ("latest".equals(version) || "latest-release".equals(version)) {
			downloadVersion(this.versions.getLatest().getRelease());
			return;
		}
		if ("latest-snapshot".equals(version)) {
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
		boolean valid = false;
		for (Version version1 : versions.getVersions()) {
			if (version1.getId().equals(version)) {
				valid = true;
				break;
			}
		}
		if (!valid) {
			throw new IllegalArgumentException("Version " + version + " does not exist in index");
		}

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
		File extractDirectory = new File(extractBaseDirectory, version);
		if (!extractDirectory.exists()) { extractDirectory.mkdirs(); }

		try {
			// Init git
			log.info("Initializing Git...");
			CredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(gitEmail, gitPassword);
			Git git = Git.cloneRepository()
					.setURI(gitRepo)
					.setDirectory(extractDirectory)
					.setCredentialsProvider(credentialsProvider)
					.call();
			StoredConfig config = git.getRepository().getConfig();
			config.setString("user", null, "email", gitEmail);
			config.save();

			// git checkout
			git.branchCreate().setName(version).call();
			git.checkout().setForce(true).setName(version).call();

			// Download
			log.info("Downloading version " + version + "...");

			String jarDownload = String.format(JAR_FORMAT, version, version);

			File tempFile = Files.createTempFile("mcasset-downloader", "").toFile();

			HttpURLConnection connection = (HttpURLConnection) new URL(jarDownload).openConnection();
			long totalFileSize = connection.getContentLength();
			long downloadedFileSize = 0;
			try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream())) {
				try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(tempFile))) {
					byte[] buffer = new byte[1024];
					int length;
					while ((length = input.read(buffer, 0, 1024)) > 0) {
						output.write(buffer, 0, length);
						downloadedFileSize += length;

						System.out.write(("\rDownloaded " + downloadedFileSize + "/" + totalFileSize).getBytes());
					}
				}
			}
			System.out.println();

			// Extract assets
			System.out.println("Extracting archive...");

			try (ZipInputStream zipInputStream = new ZipInputStream(new BufferedInputStream(new FileInputStream(tempFile)))) {
				ZipEntry zipEntry;

				int count = 0;
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

						System.out.write(("\rExtracted " + (count++) + " asset files").getBytes());
					}
				}
			}
			System.out.println();

			// Delete temporary file
			tempFile.delete();

			log.info("Pushing changes to remote repo...");

			git.add()
					.addFilepattern("assets")
					.call();
			git.commit()
					.setMessage("Create/Update assets for version " + version)
					.setCommitter("InventiveBot", gitEmail)
					.call();
			git.push()
					.setForce(true)
					.setPushAll()
					.setCredentialsProvider(credentialsProvider)
					.setProgressMonitor(new TextProgressMonitor(new OutputStreamWriter(System.out)))
					.call();
		} catch (IOException | GitAPIException e) {
			throw new RuntimeException(e);
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
