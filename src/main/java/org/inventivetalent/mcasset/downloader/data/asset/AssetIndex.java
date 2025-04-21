package org.inventivetalent.mcasset.downloader.data.asset;

public record AssetIndex(
        String id,
        String sha1,
        String url,
        long size,
        long totalSize
) {


}
