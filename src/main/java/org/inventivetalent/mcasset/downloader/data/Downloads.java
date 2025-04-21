package org.inventivetalent.mcasset.downloader.data;

import com.google.gson.annotations.SerializedName;

public record Downloads(
        Download client,
        @SerializedName("client_mappings") Download clientMappings,
        Download server,
        @SerializedName("server_mappings") Download serverMappings
) {
}
