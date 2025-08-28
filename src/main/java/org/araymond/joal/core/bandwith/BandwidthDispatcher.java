package org.araymond.joal.core.bandwith;

import com.google.common.annotations.VisibleForTesting;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.araymond.joal.core.bandwith.weight.PeersAwareWeightCalculator;
import org.araymond.joal.core.bandwith.weight.WeightHolder;
import org.araymond.joal.core.torrent.torrent.InfoHash;
import org.araymond.joal.core.ttorrent.client.announcer.response.BandwidthDispatcherNotifier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.apache.commons.io.FileUtils.byteCountToDisplaySize;
import static org.apache.commons.lang3.ObjectUtils.getIfNull;

/**
 * Service qui gère la répartition de la bande passante simulée entre les torrents.
 * - Met à jour les vitesses de seed selon les peers et le poids
 * - Rafraîchit périodiquement les stats d'upload
 * Optimisation possible : utiliser un scheduler pour plus de précision et batcher les updates.
 */
@Slf4j
@RequiredArgsConstructor
// Gère la logique de répartition de la bande passante et le suivi des stats d'upload.
public class BandwidthDispatcher implements BandwidthDispatcherFacade, Runnable {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final WeightHolder<InfoHash> weightHolder = new WeightHolder<>(new PeersAwareWeightCalculator());
    private final Map<InfoHash, TorrentSeedStats> torrentsSeedStats = new HashMap<>();
    private final Map<InfoHash, Speed> speedMap = new HashMap<>();
    private SpeedChangedListener speedChangedListener;
    private int threadLoopCounter;
    private volatile boolean stop;
    private Thread thread;

    private final int threadPauseIntervalMs;
    private final RandomSpeedProvider randomSpeedProvider;

    private static final long TWENTY_MINS_MS = MINUTES.toMillis(2);

    /**
     * Définit le listener à notifier lors d'un changement de vitesse.
     */
    public void setSpeedListener(final SpeedChangedListener speedListener) {
        this.speedChangedListener = speedListener;
    }

    /**
     * This method does not benefit from the lock, because the value will never be accessed in a ambiguous way.
     * And even if it happens, we return 0 by default.
     */
    /**
     * Retourne les stats d'upload pour un torrent donné.
     */
    public TorrentSeedStats getSeedStatForTorrent(final InfoHash infoHash) {
        return getIfNull(this.torrentsSeedStats.get(infoHash), TorrentSeedStats::new);
    }

    /**
     * Retourne la map des vitesses de seed pour tous les torrents.
     */
    public Map<InfoHash, Speed> getSpeedMap() {
        try {
            this.lock.readLock().lock();
            return new HashMap<>(speedMap);
        } finally {
            this.lock.readLock().unlock();
        }
    }

    /**
     * Démarre le thread de répartition de la bande passante.
     */
    public void start() {
        this.stop = false;
        this.thread = new Thread(this);
        this.thread.setName("bandwidth-dispatcher");
        this.thread.start();
    }

    /**
     * Arrête le thread de répartition de la bande passante.
     */
    public void stop() {
        this.stop = true;
        this.thread.interrupt();
        try {
            this.thread.join();
        } catch (final InterruptedException ignored) {
        }
    }

    @Override
    /**
     * Boucle principale du thread : met à jour les stats et rafraîchit la bande passante.
     */
    public void run() {
        try {
            while (!this.stop) {
                MILLISECONDS.sleep(this.threadPauseIntervalMs);

                // refresh bandwidth every 20 minutes:
                this.threadLoopCounter++;
                if (this.threadLoopCounter == TWENTY_MINS_MS / this.threadPauseIntervalMs) {
                    this.refreshCurrentBandwidth();
                    this.threadLoopCounter = 0;
                }

                // This method as to run as fast as possible to avoid blocking other ones. Because we want this loop
                //  to be scheduled as precise as we can. Locking too much will delay the Thread.sleep and cause stats
                //  to be undervalued
                this.lock.readLock().lock();
                final Set<Map.Entry<InfoHash, TorrentSeedStats>> seedStatsView = new HashSet<>(this.torrentsSeedStats.entrySet());
                this.lock.readLock().unlock();

                // update the uploaded data amount tallies:
                seedStatsView.forEach(entry -> {
                    final long speedInBytesPerSecond = ofNullable(this.speedMap.get(entry.getKey()))
                            .map(Speed::getBytesPerSecond)
                            .orElse(0L);
                    // Divide by 1000 because of the thread pause interval being in milliseconds
                    // The multiplication HAS to be done before the division, otherwise we're going to have trailing zeroes
                    // TODO: maybe instead of trusting threadPauseIntervalMs, use wall clock for calculations?
                    entry.getValue().addUploaded(speedInBytesPerSecond * this.threadPauseIntervalMs / 1000);
                });
            }
        } catch (final InterruptedException ignore) {
        }
    }

    /**
     * Met à jour le nombre de seeders/leechers pour un torrent et recalcule les vitesses.
     */
    public void updateTorrentPeers(final InfoHash infoHash, final int seeders, final int leechers) {
        log.debug("Updating Peers stats for {}", infoHash.getHumanReadable());
        this.lock.writeLock().lock();
        try {
            this.weightHolder.addOrUpdate(infoHash, new Peers(seeders, leechers));
            this.recomputeSpeeds();
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /**
     * Enregistre un nouveau torrent pour le suivi de la bande passante.
     */
    public void registerTorrent(final InfoHash infoHash) {
        log.debug("{} has been added to bandwidth dispatcher", infoHash.getHumanReadable());
        this.lock.writeLock().lock();
        try {
            this.torrentsSeedStats.put(infoHash, new TorrentSeedStats());
            this.speedMap.put(infoHash, new Speed(0));
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /**
     * Désenregistre un torrent du suivi de la bande passante.
     */
    public void unregisterTorrent(final InfoHash infoHash) {
        log.debug("{} has been removed from bandwidth dispatcher", infoHash.getHumanReadable());
        this.lock.writeLock().lock();
        try {
            this.weightHolder.remove(infoHash);
            this.torrentsSeedStats.remove(infoHash);
            this.speedMap.remove(infoHash);
            this.recomputeSpeeds();
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /**
     * Rafraîchit la bande passante globale simulée.
     */
    @VisibleForTesting
    void refreshCurrentBandwidth() {
        log.debug("Refreshing global bandwidth");
        this.lock.writeLock().lock();
        try {
            this.randomSpeedProvider.refresh();
            this.recomputeSpeeds();
            if (log.isDebugEnabled()) {
                log.debug("Global bandwidth refreshed, new value is {}/s", byteCountToDisplaySize(this.randomSpeedProvider.getCurrentSpeed()));
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /**
     * Update the values of the {@code speedMap}, introducing change to the current torrent speeds.
     */
    /**
     * Recalcule les vitesses de seed pour tous les torrents selon leur poids.
     */
    @VisibleForTesting
    void recomputeSpeeds() {
        log.debug("Refreshing all torrents speeds");
        this.torrentsSeedStats.keySet().forEach(infohash -> this.speedMap.compute(infohash, (hash, speed) -> {
            if (speed == null) {  // TODO: could it ever be null? both maps are updated under same lock anyway
                return new Speed(0);
            }
            double percentOfSpeedAssigned = this.weightHolder.getTotalWeight() == 0.0
                    ? 0.0
                    : this.weightHolder.getWeightFor(infohash) / this.weightHolder.getTotalWeight();
            speed.setBytesPerSecond((long) (this.randomSpeedProvider.getCurrentSpeed() * percentOfSpeedAssigned));

            return speed;
        }));

        if (speedChangedListener != null) {
            this.speedChangedListener.speedsHasChanged(new HashMap<>(this.speedMap));
        }

        if (log.isDebugEnabled() && !this.speedMap.isEmpty()) {
            final StringBuilder sb = new StringBuilder("All torrents speeds have been refreshed:\n");
            final double totalWeight = this.weightHolder.getTotalWeight();
            this.speedMap.forEach((infoHash, speed) -> {
                final String humanReadableSpeed = byteCountToDisplaySize(speed.getBytesPerSecond());
                final double torrentWeight = this.weightHolder.getWeightFor(infoHash);
                final double weightInPercent = torrentWeight > 0.0
                        ? totalWeight / torrentWeight * 100
                        : 0;
                sb.append("      ")
                        .append(infoHash.getHumanReadable())
                        .append(":")
                        .append("\n          ").append("current speed: ").append(humanReadableSpeed).append("/s")
                        .append("\n          ").append("overall upload: ").append(byteCountToDisplaySize(this.torrentsSeedStats.get(infoHash).getUploaded()))
                        .append("\n          ").append("weight: ").append(weightInPercent).append("% (").append(torrentWeight).append(" out of ").append(totalWeight).append(")")
                        .append("\n");
            });
            sb.setLength(sb.length() - 1); // remove last \n
            log.debug(sb.toString());
        }
    }
}
