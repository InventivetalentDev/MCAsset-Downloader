package org.inventivetalent.mcasset.downloader;

public interface ProgressCallback {

	void call(double now, double total);

}
