package org.inventivetalent.mcasset.downloader.data.asset;

import org.inventivetalent.mcasset.downloader.data.Downloads;

public record VersionAssetDetails(
        String id,
        String assets,
        AssetIndex assetIndex,

        Downloads downloads
) {


}
