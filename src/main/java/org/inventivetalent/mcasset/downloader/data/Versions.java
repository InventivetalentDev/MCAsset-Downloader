package org.inventivetalent.mcasset.downloader.data;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Versions {

	LatestVersion latest;
	List<Version> versions = new ArrayList<>();

}
