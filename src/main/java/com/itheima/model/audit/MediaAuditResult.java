package com.itheima.model.audit;

import java.time.LocalDateTime;
import java.util.List;

public class MediaAuditResult {
    private LocalDateTime scanTime;
    private int total;
    private int existing;
    private int missing;
    private int invalid;
    private int notScanned;
    private List<Long> orphanMediaIds;
    private List<Long> contentsWithoutMedia;
    private List<MediaAuditItem> items;

    public LocalDateTime getScanTime() {
        return scanTime;
    }

    public void setScanTime(LocalDateTime scanTime) {
        this.scanTime = scanTime;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getExisting() {
        return existing;
    }

    public void setExisting(int existing) {
        this.existing = existing;
    }

    public int getMissing() {
        return missing;
    }

    public void setMissing(int missing) {
        this.missing = missing;
    }

    public int getInvalid() {
        return invalid;
    }

    public void setInvalid(int invalid) {
        this.invalid = invalid;
    }

    public int getNotScanned() {
        return notScanned;
    }

    public void setNotScanned(int notScanned) {
        this.notScanned = notScanned;
    }

    public List<Long> getOrphanMediaIds() {
        return orphanMediaIds;
    }

    public void setOrphanMediaIds(List<Long> orphanMediaIds) {
        this.orphanMediaIds = orphanMediaIds;
    }

    public List<Long> getContentsWithoutMedia() {
        return contentsWithoutMedia;
    }

    public void setContentsWithoutMedia(List<Long> contentsWithoutMedia) {
        this.contentsWithoutMedia = contentsWithoutMedia;
    }

    public List<MediaAuditItem> getItems() {
        return items;
    }

    public void setItems(List<MediaAuditItem> items) {
        this.items = items;
    }
}
