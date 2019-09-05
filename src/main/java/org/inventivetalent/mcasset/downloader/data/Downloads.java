package org.inventivetalent.mcasset.downloader.data;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class Downloads {

	Download client;
	@SerializedName("client_mappings") Download clientMappings;
	Download server;
	@SerializedName("server_mappings") Download serverMappings;

}
