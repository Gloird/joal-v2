package org.araymond.joal.core.torrent.watcher;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.araymond.joal.core.SeedManager;
import org.araymond.joal.core.exception.NoMoreTorrentsFileAvailableException;
import org.araymond.joal.core.torrent.torrent.InfoHash;
import org.araymond.joal.core.torrent.torrent.MockedTorrent;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Collections.synchronizedMap;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

/**
 * Service qui gère la surveillance et la gestion des fichiers .torrent sur le disque.
 * Permet d'ajouter, supprimer, archiver et notifier les listeners sur les changements.
 * Optimisation possible : ajouter une gestion des erreurs plus robuste et un watcher multiplateforme.
 */
@Slf4j
// Gère la gestion des fichiers .torrent et l'archivage automatique.
public class TorrentFileProvider extends FileAlterationListenerAdaptor {

    private final TorrentFileWatcher watcher;
    private final Map<File, MockedTorrent> torrentFiles = synchronizedMap(new HashMap<>());
    private final Set<TorrentFileChangeAware> torrentFileChangeListeners;
    private final Path archiveFolder;

    /**
     * Initialise le provider avec le chemin des dossiers torrents et archive.
     */
    public TorrentFileProvider(final SeedManager.JoalFoldersPath joalFoldersPath) throws FileNotFoundException {
        Path torrentsDir = joalFoldersPath.getTorrentsDirPath();
        if (!isDirectory(torrentsDir)) {
            // TODO: shouldn't we check&throw in JoalFoldersPath instead?
            log.error("Folder [{}] does not exist", torrentsDir.toAbsolutePath());
            throw new FileNotFoundException(format("Torrent folder [%s] not found", torrentsDir.toAbsolutePath()));
        }

        this.archiveFolder = joalFoldersPath.getTorrentArchiveDirPath();
        this.watcher = new TorrentFileWatcher(this, torrentsDir);
        this.torrentFileChangeListeners = new HashSet<>();
    }

    /**
     * Démarre la surveillance des fichiers torrents.
     */
    public void start() {
        this.init();
        this.watcher.start();
    }

    @VisibleForTesting
    /**
     * Initialise le dossier d'archive si besoin.
     */
    void init() {
        if (!isDirectory(archiveFolder)) {
            if (Files.exists(archiveFolder)) {
                String errMsg = "Archive folder exists, but is not a directory";
                log.error(errMsg);
                throw new IllegalStateException(errMsg);
            }

            try {
                Files.createDirectory(archiveFolder);
            } catch (final IOException e) {
                String errMsg = "Failed to create archive folder";
                log.error(errMsg, e);
                throw new IllegalStateException(errMsg, e);
            }
        }
    }

    /**
     * Arrête la surveillance et nettoie la map des torrents.
     */
    public void stop() {
        this.watcher.stop();
        this.torrentFiles.clear();
    }

    /**
     * Appelé lors de la suppression d'un fichier .torrent sur le disque.
     */
    @Override
    public void onFileDelete(final File file) {
        ofNullable(this.torrentFiles.remove(file))
                .ifPresent(removedTorrent -> {
                    log.info("Torrent file deleting detected, hot deleted file [{}]", file.getAbsolutePath());
                    this.torrentFileChangeListeners.forEach(listener -> listener.onTorrentFileRemoved(removedTorrent));
                });
    }

    /**
     * Appelé lors de l'ajout d'un fichier .torrent sur le disque.
     */
    @Override
    public void onFileCreate(final File file) {
        log.info("Torrent file addition detected, hot creating file [{}]", file.getAbsolutePath());
        try {
            final MockedTorrent torrent = MockedTorrent.fromFile(file);
            this.torrentFiles.put(file, torrent);
            this.torrentFileChangeListeners.forEach(listener -> listener.onTorrentFileAdded(torrent));
        } catch (final IOException | NoSuchAlgorithmException e) {
            log.warn("Failed to read file [{}], moved to archive folder: {}", file.getAbsolutePath(), e);
            this.moveToArchiveFolder(file);
        } catch (final Exception e) {
            // This thread MUST NOT crash. we need handle any other exception
            log.error("Unexpected exception was caught for file [{}], moved to archive folder: {}", file.getAbsolutePath(), e);
            this.moveToArchiveFolder(file);
        }
    }

    /**
     * Appelé lors de la modification d'un fichier .torrent sur le disque.
     */
    @Override
    public void onFileChange(final File file) {
        log.info("Torrent file change detected, hot reloading file [{}]", file.getAbsolutePath());
        this.onFileDelete(file);
        this.onFileCreate(file);
    }

    /**
     * Enregistre un listener pour être notifié des changements de fichiers torrents.
     */
    public void registerListener(final TorrentFileChangeAware listener) {
        this.torrentFileChangeListeners.add(listener);
    }

    /**
     * Désenregistre un listener.
     */
    public void unRegisterListener(final TorrentFileChangeAware listener) {
        this.torrentFileChangeListeners.remove(listener);
    }

    /**
     * Retourne un torrent qui n'est pas dans la liste unwantedTorrents.
     */
    public MockedTorrent getTorrentNotIn(final Collection<InfoHash> unwantedTorrents) throws NoMoreTorrentsFileAvailableException {
        Preconditions.checkNotNull(unwantedTorrents, "unwantedTorrents cannot be null");

        return this.torrentFiles.values().stream()
                .filter(torrent -> !unwantedTorrents.contains(torrent.getTorrentInfoHash()))
                .collect(Collectors.collectingAndThen(toList(), collected -> {
                    Collections.shuffle(collected);
                    return collected.stream();
                }))
                .findAny()
                .orElseThrow(() -> new NoMoreTorrentsFileAvailableException("No more torrent files available"));
    }

    /**
     * Déplace un fichier .torrent dans le dossier d'archive.
     */
    void moveToArchiveFolder(final File torrentFile) {
        if (torrentFile == null || !torrentFile.exists()) {
            return;
        }
        this.onFileDelete(torrentFile);

        try {
            Path moveTarget = archiveFolder.resolve(torrentFile.getName());
            Files.move(torrentFile.toPath(), moveTarget, REPLACE_EXISTING);
            log.info("Successfully moved file [{}] to archive folder", torrentFile.getAbsolutePath());
        } catch (final IOException e) {
            log.error("Failed to archive file [{}], the file won't be used anymore for the current session, but it remains in the folder", torrentFile.getAbsolutePath());
        }
    }

    /**
     * Déplace le fichier .torrent correspondant à l'infoHash dans le dossier d'archive.
     */
    public void moveToArchiveFolder(final InfoHash infoHash) {
        this.torrentFiles.entrySet().stream()
                .filter(entry -> entry.getValue().getTorrentInfoHash().equals(infoHash))
                .findAny()
                .map(Map.Entry::getKey)
                .ifPresentOrElse(this::moveToArchiveFolder,
                        () -> log.warn("Cannot move torrent [{}] to archive folder. Torrent file seems not to be registered in TorrentFileProvider", infoHash));
    }

    /**
     * Retourne le nombre de torrents présents.
     */
    public int getTorrentCount() {
        return this.torrentFiles.size();
    }

    /**
     * Retourne la liste des torrents présents.
     */
    public List<MockedTorrent> getTorrentFiles() {
        return new ArrayList<>(this.torrentFiles.values());
    }

    /**
     * Retourne tous les torrents qui ne sont pas dans unwantedTorrents.
     */
    public Iterable<MockedTorrent> getAllTorrentsNotIn(Set<InfoHash> unwantedTorrents) {

        Preconditions.checkNotNull(unwantedTorrents, "unwantedTorrents cannot be null");

        return this.torrentFiles.values().stream()
                .filter(torrent -> !unwantedTorrents.contains(torrent.getTorrentInfoHash()))
                .collect(Collectors.collectingAndThen(toList(), collected -> {
                    Collections.shuffle(collected);
                    return collected.stream();
                }))
                .collect(Collectors.toList());
                

    }
}
