package org.inventivetalent.mcasset.downloader.data;

public record Download(
        String sha1,
        int size,
        String url
) {
}
