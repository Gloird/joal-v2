package org.araymond.joal.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.araymond.joal.core.bandwith.BandwidthDispatcher;
import org.araymond.joal.core.antihnr.AntiHitAndRunService;
import org.araymond.joal.core.persistence.ElapsedTimePersistenceService;
import org.araymond.joal.core.bandwith.RandomSpeedProvider;
import org.araymond.joal.core.bandwith.Speed;
import org.araymond.joal.core.bandwith.SpeedChangedListener;
import org.araymond.joal.core.client.emulated.BitTorrentClient;
import org.araymond.joal.core.client.emulated.BitTorrentClientProvider;
import org.araymond.joal.core.config.AppConfiguration;
import org.araymond.joal.core.config.JoalConfigProvider;
import org.araymond.joal.core.events.config.ListOfClientFilesEvent;
import org.araymond.joal.core.events.global.state.GlobalSeedStartedEvent;
import org.araymond.joal.core.events.global.state.GlobalSeedStoppedEvent;
import org.araymond.joal.core.events.speed.SeedingSpeedsHasChangedEvent;
import org.araymond.joal.core.events.torrent.files.FailedToAddTorrentFileEvent;
import org.araymond.joal.core.torrent.torrent.InfoHash;
import org.araymond.joal.core.torrent.torrent.MockedTorrent;
import org.araymond.joal.core.torrent.watcher.TorrentFileProvider;
import org.araymond.joal.core.ttorrent.client.ClientBuilder;
import org.araymond.joal.core.ttorrent.client.ClientFacade;
import org.araymond.joal.core.ttorrent.client.ConnectionHandler;
import org.araymond.joal.core.ttorrent.client.DelayQueue;
import org.araymond.joal.core.ttorrent.client.announcer.AnnouncerFacade;
import org.araymond.joal.core.ttorrent.client.announcer.AnnouncerFactory;
import org.araymond.joal.core.ttorrent.client.announcer.request.AnnounceDataAccessor;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static java.nio.file.Files.isDirectory;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.apache.commons.lang3.ObjectUtils.firstNonNull;
import static org.springframework.http.HttpHeaders.USER_AGENT;

/**
 * This is the outer boundary of our the business logic. Most (if not all)
 * torrent-related handling is happening here & downstream.
 */

// Classe principale qui gère la logique métier de l'application JOAL.
// Elle orchestre la gestion des torrents, l'anti Hit&Run, la persistance du temps seedé, la configuration, etc.
@Slf4j
public class SeedManager {


    // Client HTTP utilisé pour les communications réseau (announces trackers, etc.)
    private final CloseableHttpClient httpClient;
    // Indique si le seed est en cours
    @Getter private boolean seeding;
    // Chemins des dossiers de configuration, torrents, archives, etc.
    private final JoalFoldersPath joalFoldersPath;
    // Fournisseur de configuration (lecture/écriture du fichier config)
    private final JoalConfigProvider configProvider;
    // Fournisseur de fichiers .torrent (ajout, suppression, archivage)
    private final TorrentFileProvider torrentFileProvider;
    // Fournisseur de clients BitTorrent émulés
    private final BitTorrentClientProvider bitTorrentClientProvider;
    // Pour publier des événements Spring (utilisé pour la communication interne)
    private final ApplicationEventPublisher appEventPublisher;
    // Gère les connexions réseau
    private final ConnectionHandler connectionHandler = new ConnectionHandler();
    // Gère la bande passante simulée
    private BandwidthDispatcher bandwidthDispatcher;
    // Client principal qui orchestre le seeding
    private ClientFacade client;

    // Map infoHash -> service anti Hit&Run (un par torrent)
    private final Map<String, AntiHitAndRunService> antiHnRServices = new HashMap<>();
    // Persistance du temps seedé par infoHash
    private final ElapsedTimePersistenceService elapsedTimePersistenceService;
    // Thread qui vérifie périodiquement l'état anti Hit&Run
    private Thread antiHnRThread;

    /**
     * Constructeur principal du SeedManager.
     * @param joalConfRootPath Chemin racine du dossier de configuration JOAL
     * @param mapper           ObjectMapper Jackson pour la sérialisation JSON
     * @param appEventPublisher Publisher Spring pour les événements
     */
    public SeedManager(final String joalConfRootPath, final ObjectMapper mapper,
                       final ApplicationEventPublisher appEventPublisher) throws IOException {
        this.joalFoldersPath = new JoalFoldersPath(Paths.get(joalConfRootPath));
        this.torrentFileProvider = new TorrentFileProvider(joalFoldersPath);
        this.configProvider = new JoalConfigProvider(mapper, joalFoldersPath, appEventPublisher);
        this.bitTorrentClientProvider = new BitTorrentClientProvider(configProvider, mapper, joalFoldersPath);
        this.appEventPublisher = appEventPublisher;
        this.elapsedTimePersistenceService = new ElapsedTimePersistenceService(mapper, joalFoldersPath.getConfDirRootPath());

    final SocketConfig sc = SocketConfig.custom()
        .setSoTimeout(30_000)
        .build();
        final PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
        connManager.setDefaultMaxPerRoute(100);
        connManager.setMaxTotal(200);
        connManager.setValidateAfterInactivity(1000);
        connManager.setDefaultSocketConfig(sc);

        RequestConfig requestConf = RequestConfig.custom()
                .setConnectTimeout(10_000)
                .setConnectionRequestTimeout(5000)  // timeout for requesting connection from connection manager
                .setSocketTimeout(5000)
                .build();

        this.httpClient = HttpClients.custom()
                .setConnectionTimeToLive(1, TimeUnit.MINUTES)
                .setConnectionManager(connManager)
                .setConnectionManagerShared(true)
                .setDefaultRequestConfig(requestConf)
                .build();
    }

    /**
     * Initialise les gestionnaires de connexion et de fichiers torrents.
     */
    public void init() throws IOException {
        this.connectionHandler.start();
        this.torrentFileProvider.start();
    }

    /**
     * Arrête proprement tous les services et threads.
     */
    public void tearDown() {
        this.connectionHandler.close();
        this.torrentFileProvider.stop();
        if (this.client != null) {
            this.client.stop();
            this.client = null;
        }
    }

    /**
     * Démarre le seed de tous les torrents présents, initialise l'anti Hit&Run et lance le thread de vérification périodique.
     */
    public void startSeeding() throws IOException {
        if (this.seeding) {
            log.warn("startSeeding() called, but already running");
            return;
        }
        this.seeding = true;

        final AppConfiguration appConfig = this.configProvider.init();

        // Active l'anti Hit&Run pour chaque torrent, initialise le temps seedé depuis la persistance
    // Pour chaque torrent, on instancie un service anti Hit&Run et on restaure le temps seedé depuis la persistance
    for (MockedTorrent torrent : this.getTorrentFiles()) {
            String infoHash = torrent.getTorrentInfoHash().getHumanReadable();
            AntiHitAndRunService service = new AntiHitAndRunService(
                appConfig.getRequiredSeedingTimeMs(),
                appConfig.getMaxNonSeedingTimeMs()
            );
            // Charger le temps seedé depuis la persistance
            long persistedElapsed = elapsedTimePersistenceService.get(infoHash);
            if (persistedElapsed > 0) {
                service.setTotalSeedingTime(persistedElapsed);
            }
            antiHnRServices.put(infoHash, service);
            service.onSeedingStart();
            // Sauvegarde immédiate à l'initialisation (utile si nouveau torrent)
            elapsedTimePersistenceService.save(infoHash, persistedElapsed);
        }


    // Thread qui vérifie toutes les X secondes si un torrent a atteint son temps de seed requis
        antiHnRThread = new Thread(() -> {
            while (seeding) {
                // Pour éviter ConcurrentModificationException lors de la suppression
                // Liste des infoHash à archiver (pour éviter ConcurrentModificationException)
                List<String> toArchive = new ArrayList<>();
                for (Map.Entry<String, AntiHitAndRunService> entry : antiHnRServices.entrySet()) {
                    String infoHash = entry.getKey();
                    AntiHitAndRunService service = entry.getValue();
                    service.periodicCheck();
                    // Persiste le temps seedé actuel (important pour la reprise après redémarrage)
                    long elapsed = service.getTotalSeedingTime();
                    if (service.isSeeding()) {
                        elapsed += System.currentTimeMillis() - service.getLastSeedingTimestamp();
                    }
                    elapsedTimePersistenceService.save(infoHash, elapsed);
                    // Si le temps de seed requis est atteint, on archive le torrent
                    if (service.isRequirementMet()) {
                        toArchive.add(infoHash);
                    }
                }
                for (String infoHash : toArchive) {
                    try {
                        // Recherche du MockedTorrent correspondant à l'infoHash pour l'archivage
                        MockedTorrent torrent = getTorrentFiles().stream()
                            .filter(t -> t.getTorrentInfoHash().getHumanReadable().equals(infoHash))
                            .findFirst().orElse(null);
                        if (torrent != null) {
                            // Archive le torrent et retire le service anti H&R associé
                            deleteTorrent(torrent.getTorrentInfoHash());
                            antiHnRServices.remove(infoHash);
                            log.info("Torrent {} archivé automatiquement (seeding time atteint)", infoHash);
                        } else {
                            log.warn("Impossible de trouver le MockedTorrent pour infoHash {} lors de l'archivage automatique", infoHash);
                        }
                    } catch (Exception e) {
                        log.warn("Erreur lors de l'archivage automatique du torrent {}: {}", infoHash, e.getMessage());
                    }
                }
                try {
                    Thread.sleep(60000); // toutes les heures
                } catch (InterruptedException ignored) {}
            }
        });
    // On lance le thread en mode daemon pour qu'il ne bloque pas l'arrêt de l'appli
    antiHnRThread.setDaemon(true);
    antiHnRThread.start();

    // (déjà déclaré plus haut)
        this.appEventPublisher.publishEvent(new ListOfClientFilesEvent(this.listClientFiles()));
        final BitTorrentClient bitTorrentClient = bitTorrentClientProvider.generateNewClient();

        this.bandwidthDispatcher = new BandwidthDispatcher(5000, new RandomSpeedProvider(appConfig));  // TODO: move interval to config
        this.bandwidthDispatcher.setSpeedListener(new SeedManagerSpeedChangeListener(this.appEventPublisher));
        this.bandwidthDispatcher.start();

        final AnnounceDataAccessor announceDataAccessor = new AnnounceDataAccessor(bitTorrentClient, bandwidthDispatcher, connectionHandler);

        this.client = ClientBuilder.builder()
                .withAppConfiguration(appConfig)
                .withTorrentFileProvider(this.torrentFileProvider)
                .withBandwidthDispatcher(this.bandwidthDispatcher)
                .withAnnouncerFactory(new AnnouncerFactory(announceDataAccessor, httpClient, appConfig))
                .withEventPublisher(this.appEventPublisher)
                .withDelayQueue(new DelayQueue<>())
                .build();

        this.client.start();
        appEventPublisher.publishEvent(new GlobalSeedStartedEvent(bitTorrentClient));
    }

    /**
     * Sauvegarde une nouvelle configuration dans le fichier config.json
     */
    public void saveNewConfiguration(final AppConfiguration config) {
        this.configProvider.saveNewConf(config);
    }

    /**
     * Sauvegarde un fichier .torrent sur le disque après validation.
     */
    public void saveTorrentToDisk(final String name, final byte[] bytes) {
        try {
            MockedTorrent.fromBytes(bytes);  // test if torrent file is valid or not

            final String torrentName = name.endsWith(".torrent") ? name : name + ".torrent";
            Files.write(this.joalFoldersPath.getTorrentsDirPath().resolve(torrentName), bytes, StandardOpenOption.CREATE);
        } catch (final Exception e) {
            log.warn("Failed to save torrent file", e);
            // If NullPointerException occurs (when the file is an empty file) there is no message.
            final String errorMessage = firstNonNull(e.getMessage(), "Empty/bad file");
            this.appEventPublisher.publishEvent(new FailedToAddTorrentFileEvent(name, errorMessage));
        }
    }

    /**
     * Archive un torrent (déplacement dans le dossier d'archive) et sauvegarde le temps seedé.
     */
    public void deleteTorrent(final InfoHash torrentInfoHash) {
        // Sauvegarde le temps avant suppression
        String infoHash = torrentInfoHash.getHumanReadable();
        AntiHitAndRunService service = antiHnRServices.get(infoHash);
        if (service != null) {
            long elapsed = service.getTotalSeedingTime();
            if (service.isSeeding()) {
                elapsed += System.currentTimeMillis() - service.getLastSeedingTimestamp();
            }
            elapsedTimePersistenceService.save(infoHash, elapsed);
        }
        this.torrentFileProvider.moveToArchiveFolder(torrentInfoHash);
    }

    /**
     * Retourne la liste des torrents présents dans le dossier.
     */
    public List<MockedTorrent> getTorrentFiles() {
        return torrentFileProvider.getTorrentFiles();
    }

    /**
     * Retourne la liste des fichiers clients BitTorrent disponibles.
     */
    public List<String> listClientFiles() {
        return bitTorrentClientProvider.listClientFiles();
    }

    /**
     * Retourne la liste des announcers en cours de seed.
     */
    public List<AnnouncerFacade> getCurrentlySeedingAnnouncers() {
        return this.client == null ? emptyList() : client.getCurrentlySeedingAnnouncers();
    }

    /**
     * Retourne la map des vitesses de seed par infoHash.
     */
    public Map<InfoHash, Speed> getSpeedMap() {
        return this.bandwidthDispatcher == null ? emptyMap() : bandwidthDispatcher.getSpeedMap();
    }

    /**
     * Retourne la configuration courante (depuis le cache ou le fichier).
     */
    public AppConfiguration getCurrentConfig() {
        try {
            return this.configProvider.get();
        } catch (final IllegalStateException e) {
            return this.configProvider.init();
        }
    }

    /**
     * Retourne le nom du client BitTorrent émulé actuellement utilisé.
     */
    public String getCurrentEmulatedClient() {
        try {
            return this.bitTorrentClientProvider.get().getHeaders().stream()
                    .filter(hdr -> USER_AGENT.equalsIgnoreCase(hdr.getKey()))
                    .findFirst()
                    .map(Map.Entry::getValue)
                    .orElse("Unknown");
        } catch (final IllegalStateException e) {
            return "None";
        }
    }

    /**
     * Arrête le seed, sauvegarde tous les temps seedés et stoppe les threads/services.
     */
    public void stop() {
        this.seeding = false;
        // Désactive l'anti Hit&Run pour tous les torrents et persiste le temps seedé
        for (Map.Entry<String, AntiHitAndRunService> entry : antiHnRServices.entrySet()) {
            String infoHash = entry.getKey();
            AntiHitAndRunService service = entry.getValue();
            service.onSeedingStop();
            long elapsed = service.getTotalSeedingTime();
            elapsedTimePersistenceService.save(infoHash, elapsed);
        }
        if (antiHnRThread != null && antiHnRThread.isAlive()) {
            antiHnRThread.interrupt();
        }
        if (client != null) {
            this.client.stop();
            this.appEventPublisher.publishEvent(new GlobalSeedStoppedEvent());
            this.client = null;
        }
        if (this.bandwidthDispatcher != null) {
            this.bandwidthDispatcher.stop();
            this.bandwidthDispatcher.setSpeedListener(null);
            this.bandwidthDispatcher = null;
        }
    }
    // Permet d'obtenir le temps seedé pour un infoHash
    /**
     * Permet d'obtenir le temps seedé (en ms) pour un infoHash donné.
     */
    public long getSeedingTimeMsForTorrent(String infoHash) {
        AntiHitAndRunService service = antiHnRServices.get(infoHash);
        if (service == null) return 0L;
        long seeded = service.getTotalSeedingTime();
        if (service.isSeeding()) {
            seeded += System.currentTimeMillis() - service.getLastSeedingTimestamp();
        }
        return seeded;
    }


    /**
     * Contains the references to all the directories
     * containing settings/configurations/torrent sources
     * for JOAL.
     */
    // TODO: move to config, also rename?
    /**
     * Classe utilitaire qui centralise les chemins des dossiers de config, torrents, archives, clients.
     */
    @Getter
    public static class JoalFoldersPath {
        private final Path confDirRootPath;  // all other directories stem from this
        private final Path torrentsDirPath;
        private final Path torrentArchiveDirPath;
        private final Path clientFilesDirPath;

        /**
         * Resolves, stores & exposes location to various configuration file-paths.
         */
    /**
     * Initialise les chemins à partir du dossier racine de conf.
     */
    public JoalFoldersPath(final Path confDirRootPath) {
            this.confDirRootPath = confDirRootPath;
            this.torrentsDirPath = this.confDirRootPath.resolve("torrents");
            this.torrentArchiveDirPath = this.torrentsDirPath.resolve("archived");
            this.clientFilesDirPath = this.confDirRootPath.resolve("clients");

            if (!isDirectory(confDirRootPath)) {
                log.warn("No such directory: [{}]", this.confDirRootPath);
            }
            if (!isDirectory(torrentsDirPath)) {
                log.warn("Sub-folder 'torrents' is missing in joal conf folder: [{}]", this.torrentsDirPath);
            }
            if (!isDirectory(clientFilesDirPath)) {
                log.warn("Sub-folder 'clients' is missing in joal conf folder: [{}]", this.clientFilesDirPath);
            }
        }
    }

    /**
     * Listener interne pour relayer les changements de vitesse de seed via Spring Event.
     */
    @RequiredArgsConstructor
    private static final class SeedManagerSpeedChangeListener implements SpeedChangedListener {
        private final ApplicationEventPublisher appEventPublisher;

        @Override
        public void speedsHasChanged(final Map<InfoHash, Speed> speeds) {
            this.appEventPublisher.publishEvent(new SeedingSpeedsHasChangedEvent(speeds));
        }
    }
}
