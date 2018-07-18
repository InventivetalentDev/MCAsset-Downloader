package org.inventivetalent.mcasset.downloader.data;

import lombok.Data;

@Data
public class Download {

	String sha1;
	int size;
	String url;

}
