package org.inventivetalent.mcasset.downloader;

import org.apache.commons.cli.*;

public class Main {

	public static void main(String[] args) {
		Options options = new Options();

		Option version = new Option("v", "version", true, "Version to download\n"
				+ "Special versions: latest-release, latest-snapshot, all-releases, all-snapshots");
		version.setRequired(true);
		options.addOption(version);

		CommandLineParser parser = new BasicParser();
		CommandLine cmd;
		try {
			cmd=parser.parse(options, args);
		} catch (ParseException e) {
			System.out.println(e.getMessage());
			new HelpFormatter().printHelp("downloader.jar", options);
			System.exit(0);
			return;
		}

		// Start downloader
		Downloader downloader = new Downloader();
		downloader.readConfig();
		downloader.initVersions();
		downloader.downloadVersion(cmd.getOptionValue("version"));
	}

}
