package org.inventivetalent.mcasset.downloader.data.asset;

import lombok.Data;

@Data
public class AssetIndex {

	String id;
	String sha1;
	String url;
	long   size;
	long   totalSize;

}
