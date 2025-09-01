package org.araymond.joal.web.messages.outgoing.impl.files;

import lombok.Getter;
import org.araymond.joal.core.events.torrent.files.TorrentFileAddedEvent;
import org.araymond.joal.core.torrent.torrent.InfoHash;
import org.araymond.joal.web.messages.outgoing.MessagePayload;

/**
 * Created by raymo on 10/07/2017.
 */
@Getter

public class TorrentFileAddedPayload implements MessagePayload {
    private final InfoHash infoHash;
    private final String name;
    private final Long size;
    private final Long antiHnRElapsedMs;
    private final String trackerUrl;

    public TorrentFileAddedPayload(final TorrentFileAddedEvent event, long antiHnRElapsedMs) {
        this.infoHash = event.getTorrent().getTorrentInfoHash();
        this.name = event.getTorrent().getName();
        this.size = event.getTorrent().getSize();
        this.antiHnRElapsedMs = antiHnRElapsedMs;
        this.trackerUrl = event.getTorrent().getPrimaryTrackerUrl();
    }

    // Ancien constructeur conservé pour compatibilité
    public TorrentFileAddedPayload(final TorrentFileAddedEvent event) {
        this(event, 0L);
    }

    public Long getAntiHnRElapsedMs() {
        return antiHnRElapsedMs;
    }

    public String getTrackerUrl() {
        return trackerUrl;
    }
}
