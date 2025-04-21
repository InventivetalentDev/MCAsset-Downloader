package org.inventivetalent.mcasset.downloader.data;

import java.util.List;

public record Versions(
		LatestVersion latest,
		List<Version> versions
) {

}
