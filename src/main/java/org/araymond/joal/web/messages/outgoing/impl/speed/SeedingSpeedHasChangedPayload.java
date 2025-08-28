package org.araymond.joal.web.messages.outgoing.impl.speed;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.araymond.joal.core.SeedManager;
import org.araymond.joal.core.events.speed.SeedingSpeedsHasChangedEvent;
import org.araymond.joal.core.torrent.torrent.InfoHash;
import org.araymond.joal.web.messages.outgoing.MessagePayload;

import java.util.List;

import static java.util.stream.Collectors.toList;

@Getter
public class SeedingSpeedHasChangedPayload implements MessagePayload {
    private final List<SpeedPayload> speeds;

    public SeedingSpeedHasChangedPayload(final SeedingSpeedsHasChangedEvent event, final SeedManager seedManager) {
        this.speeds = event.getSpeeds().entrySet().stream()
                .map(entry -> new SpeedPayload(
                        entry.getKey(),
                        entry.getValue().getBytesPerSecond(),
                        seedManager.getSeedingTimeMsForTorrent(entry.getKey().getHumanReadable())
                ))
                .collect(toList());
    }

    @Getter
    @RequiredArgsConstructor
    public static final class SpeedPayload {
        private final InfoHash infoHash;
        private final long bytesPerSecond;
        private final long antiHnRElapsedMs;
    }
}
