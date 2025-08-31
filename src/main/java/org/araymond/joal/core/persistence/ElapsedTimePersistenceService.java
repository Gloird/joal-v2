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
 * <h1>ElapsedTimePersistenceService</h1>
 * <p>
 * Service de persistance du temps seedé par torrent (infoHash) dans un fichier JSON.<br>
 * Permet de restaurer le temps seedé après redémarrage et d'assurer le suivi anti Hit&Run.<br>
 * <br>
 * <b>Rôle :</b><br>
 * - Sauvegarder le temps seedé pour chaque torrent (infoHash) dans un fichier JSON.<br>
 * - Permettre la restauration du temps seedé à chaque redémarrage.<br>
 * - Fournir un accès thread-safe à la map des temps seedés.<br>
 * <br>
 * <b>Optimisation possible :</b> utiliser une base de données légère (ex: SQLite) si le nombre de torrents devient important.<br>
 * </p>
 */
@Slf4j
// Gère la lecture/écriture du fichier elapsed-times.json pour chaque infoHash.
public class ElapsedTimePersistenceService {
    private final Path elapsedFile;
    private final ObjectMapper objectMapper;
    private Map<String, Long> elapsedMap;

    /**
     * Initialise le service avec le chemin du dossier de configuration et l'ObjectMapper JSON.
     * @param objectMapper L'ObjectMapper Jackson pour la sérialisation/désérialisation JSON
     * @param confDirRootPath Le chemin du dossier de configuration racine
     */
    public ElapsedTimePersistenceService(ObjectMapper objectMapper, Path confDirRootPath) {
        this.objectMapper = objectMapper;
        this.elapsedFile = confDirRootPath.resolve("elapsed-times.json");
        this.elapsedMap = load();
    }

    /**
     * Sauvegarde le temps seedé (en ms) pour un infoHash donné.
     * Appelé à chaque tick ou événement important.
     * @param infoHash L'identifiant unique du torrent
     * @param elapsedMs Temps seedé en millisecondes
     */
    public synchronized void save(String infoHash, long elapsedMs) {
        // Met à jour la map en mémoire
        elapsedMap.put(infoHash, elapsedMs);
        // Persiste la map sur disque
        persist();
    }

    /**
     * Retourne le temps seedé (en ms) pour un infoHash donné.
     * @param infoHash L'identifiant unique du torrent
     * @return Temps seedé en millisecondes (0 si inconnu)
     */
    public synchronized long get(String infoHash) {
        return elapsedMap.getOrDefault(infoHash, 0L);
    }

    /**
     * Retourne la map complète des temps seedés pour tous les torrents.
     * @return Map non modifiable infoHash -> temps seedé (ms)
     */
    public synchronized Map<String, Long> getAll() {
        return Collections.unmodifiableMap(elapsedMap);
    }

    /**
     * Persiste la map en JSON sur le disque.
     * Optimisation possible : batcher les écritures pour limiter l'I/O.
     * Cette méthode est appelée à chaque sauvegarde pour garantir la persistance.
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
     * @return Map infoHash -> temps seedé (ms), vide si le fichier n'existe pas ou en cas d'erreur
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
