package org.araymond.joal.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.araymond.joal.core.SeedManager;
import org.araymond.joal.core.events.config.ConfigHasBeenLoadedEvent;
import org.araymond.joal.core.events.config.ConfigurationIsInDirtyStateEvent;
import org.springframework.context.ApplicationEventPublisher;

import javax.inject.Provider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;

import static java.lang.String.format;
import static java.nio.file.Files.isRegularFile;

/**
 * Service qui gère la lecture et l'écriture du fichier de configuration principal (config.json).
 * Permet de charger, sauvegarder et publier la configuration courante.
 * Optimisation possible : valider la config avant sauvegarde et permettre le hot reload.
 */
@Slf4j
// Fournisseur de configuration pour l'application JOAL.
public class JoalConfigProvider implements Provider<AppConfiguration> {
    private static final String CONF_FILE_NAME = "config.json";

    private final Path joalConfFile;
    private final ObjectMapper objectMapper;
    private AppConfiguration config;
    private final ApplicationEventPublisher appEventPublisher;

    /**
     * Initialise le provider avec le chemin du fichier config et l'ObjectMapper JSON.
     */
    public JoalConfigProvider(final ObjectMapper objectMapper, final SeedManager.JoalFoldersPath joalFoldersPath,
                              final ApplicationEventPublisher appEventPublisher) throws FileNotFoundException {
        this.objectMapper = objectMapper;
        this.appEventPublisher = appEventPublisher;

        this.joalConfFile = joalFoldersPath.getConfDirRootPath().resolve(CONF_FILE_NAME);
        if (!isRegularFile(joalConfFile)) {
            throw new FileNotFoundException(format("App configuration file [%s] not found", joalConfFile));
        }

        log.debug("App configuration file will be searched for in [{}]", joalConfFile.toAbsolutePath());
    }

    /**
     * Charge la configuration depuis le fichier et la publie en event.
     */
    public AppConfiguration init() {
        this.config = this.loadConfiguration();
        return this.config;
    }

    /**
     * Retourne la configuration courante (doit être initialisée avant).
     */
    @Override
    public AppConfiguration get() {
        if (this.config == null) {
            log.error("App configuration has not been loaded yet");
            throw new IllegalStateException("Attempted to get configuration before init");
        }
        return this.config;
    }

    /**
     * Charge la configuration depuis le fichier JSON.
     */
    @VisibleForTesting
    AppConfiguration loadConfiguration() {
        final AppConfiguration conf;
        try {
            log.debug("Reading json configuration from [{}]...", joalConfFile.toAbsolutePath());
            conf = objectMapper.readValue(joalConfFile.toFile(), AppConfiguration.class);
            log.debug("Successfully read json configuration");
        } catch (final IOException e) {
            log.error("Failed to read configuration file", e);
            throw new IllegalStateException(e);
        }

        log.info("App configuration has been successfully loaded");
        this.appEventPublisher.publishEvent(new ConfigHasBeenLoadedEvent(conf));
        return conf;
    }

    // TODO: verify that the new config ends up under this.config after saving new!
    /**
     * Sauvegarde une nouvelle configuration dans le fichier config.json et publie un event.
     */
    public void saveNewConf(final AppConfiguration conf) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(joalConfFile.toFile(), conf);
            appEventPublisher.publishEvent(new ConfigurationIsInDirtyStateEvent(conf));
        } catch (final IOException e) {
            log.error("Failed to write new configuration file", e);
            throw new IllegalStateException(e);
        }
    }
}
