package org.inventivetalent.mcasset.downloader.data.asset;

import lombok.Data;
import org.inventivetalent.mcasset.downloader.data.Downloads;

@Data
public class VersionAssetDetails {

	String     id;
	String     assets;
	AssetIndex assetIndex;

	Downloads downloads;

}
