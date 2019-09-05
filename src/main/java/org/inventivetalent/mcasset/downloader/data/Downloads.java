package org.inventivetalent.mcasset.downloader.data;

import lombok.Data;

@Data
public class Downloads {

	Download client;
	Download clientMappings;
	Download server;
	Download serverMappings;

}
