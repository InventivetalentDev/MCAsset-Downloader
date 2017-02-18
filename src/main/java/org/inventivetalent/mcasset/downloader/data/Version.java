package org.inventivetalent.mcasset.downloader.data;

import lombok.Data;

@Data
public class Version {

	String id;
	String type;
	String url;
	String time;
	String releaseTime;
	long   downloadTimestamp;

}
