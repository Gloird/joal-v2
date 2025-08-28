package org.araymond.joal.core.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Service de persistance du temps seedé par torrent (infoHash) dans un fichier JSON.
 * Permet de restaurer le temps seedé après redémarrage et d'assurer le suivi anti Hit&Run.
 * Optimisation possible : utiliser une base de données légère (ex: SQLite) si le nombre de torrents devient important.
 */
@Slf4j
// Gère la lecture/écriture du fichier elapsed-times.json pour chaque infoHash.
public class ElapsedTimePersistenceService {
    private final Path elapsedFile;
    private final ObjectMapper objectMapper;
    private Map<String, Long> elapsedMap;

    /**
     * Initialise le service avec le chemin du dossier de configuration et l'ObjectMapper JSON.
     */
    public ElapsedTimePersistenceService(ObjectMapper objectMapper, Path confDirRootPath) {
        this.objectMapper = objectMapper;
        this.elapsedFile = confDirRootPath.resolve("elapsed-times.json");
        this.elapsedMap = load();
    }

    /**
     * Sauvegarde le temps seedé (en ms) pour un infoHash donné.
     * Appelé à chaque tick ou événement important.
     */
    public synchronized void save(String infoHash, long elapsedMs) {
        elapsedMap.put(infoHash, elapsedMs);
        persist();
    }

    /**
     * Retourne le temps seedé (en ms) pour un infoHash donné.
     */
    public synchronized long get(String infoHash) {
        return elapsedMap.getOrDefault(infoHash, 0L);
    }

    /**
     * Retourne la map complète des temps seedés pour tous les torrents.
     */
    public synchronized Map<String, Long> getAll() {
        return Collections.unmodifiableMap(elapsedMap);
    }

    /**
     * Persiste la map en JSON sur le disque.
     * Optimisation possible : batcher les écritures pour limiter l'I/O.
     */
    private void persist() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(elapsedFile.toFile(), elapsedMap);
        } catch (IOException e) {
            log.error("Failed to persist elapsed times", e);
        }
    }

    /**
     * Charge la map depuis le fichier JSON au démarrage.
     */
    private Map<String, Long> load() {
        if (!Files.exists(elapsedFile)) return new HashMap<>();
        try {
            return objectMapper.readValue(elapsedFile.toFile(), new TypeReference<Map<String, Long>>(){});
        } catch (IOException e) {
            log.error("Failed to load elapsed times, starting empty", e);
            return new HashMap<>();
        }
    }
}
