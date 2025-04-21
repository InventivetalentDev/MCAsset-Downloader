package org.inventivetalent.mcasset.downloader.data;

import java.util.Objects;

public final class Version {
    private final String id;
    private final String type;
    private final String url;
    private final String time;
    private final String releaseTime;

    private long downloadTimestamp = System.currentTimeMillis();

    public Version(
            String id,
            String type,
            String url,
            String time,
            String releaseTime
    ) {
        this.id = id;
        this.type = type;
        this.url = url;
        this.time = time;
        this.releaseTime = releaseTime;
    }

    public long downloadTimestamp() {
        return downloadTimestamp;
    }

    public void setDownloadTimestamp(long downloadTimestamp) {
        this.downloadTimestamp = downloadTimestamp;
    }

    public String id() {
        return id;
    }

    public String type() {
        return type;
    }

    public String url() {
        return url;
    }

    public String time() {
        return time;
    }

    public String releaseTime() {
        return releaseTime;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (Version) obj;
        return Objects.equals(this.id, that.id) &&
                Objects.equals(this.type, that.type) &&
                Objects.equals(this.url, that.url) &&
                Objects.equals(this.time, that.time) &&
                Objects.equals(this.releaseTime, that.releaseTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, url, time, releaseTime);
    }

    @Override
    public String toString() {
        return "Version[" +
                "id=" + id + ", " +
                "type=" + type + ", " +
                "url=" + url + ", " +
                "time=" + time + ", " +
                "releaseTime=" + releaseTime + ']';
    }

}
