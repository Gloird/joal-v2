package org.araymond.joal.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * Created by raymo on 24/01/2017.
 */
/**
 * Classe qui représente la configuration principale de l'application JOAL.
 * Tous les paramètres sont immuables et validés à la construction.
 * Optimisation possible : ajouter des setters pour permettre le hot reload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@EqualsAndHashCode
@Getter
public class AppConfiguration {

    private final long minUploadRate;
    private final long maxUploadRate;
    private final int simultaneousSeed;
    private final String client;
    private final boolean keepTorrentWithZeroLeechers;
    private final float uploadRatioTarget;
    private final long maxNonSeedingTimeMs;
    private final long requiredSeedingTimeMs;

    /**
     * Constructeur principal avec validation des paramètres.
     */
    @JsonCreator
    public AppConfiguration(
            @JsonProperty(value = "minUploadRate", required = true) final long minUploadRate,
            @JsonProperty(value = "maxUploadRate", required = true) final long maxUploadRate,
            @JsonProperty(value = "simultaneousSeed", required = true) final int simultaneousSeed,
            @JsonProperty(value = "client", required = true) final String client,
            @JsonProperty(value = "keepTorrentWithZeroLeechers", required = true) final boolean keepTorrentWithZeroLeechers,
            @JsonProperty(value = "uploadRatioTarget", required = false) final Float uploadRatioTarget,
            @JsonProperty(value = "maxNonSeedingTimeMs", required = false) final Long maxNonSeedingTimeMs,
            @JsonProperty(value = "requiredSeedingTimeMs", required = false) final Long requiredSeedingTimeMs
    ) {
        this.minUploadRate = minUploadRate;
        this.maxUploadRate = maxUploadRate;
        this.simultaneousSeed = simultaneousSeed;
        this.client = client;
        this.keepTorrentWithZeroLeechers = keepTorrentWithZeroLeechers;
        this.uploadRatioTarget = uploadRatioTarget == null ? -1.0f : uploadRatioTarget;
        this.maxNonSeedingTimeMs = maxNonSeedingTimeMs == null ? 72 * 60 * 60 * 1000L : maxNonSeedingTimeMs;
        this.requiredSeedingTimeMs = requiredSeedingTimeMs == null ? 7 * 24 * 60 * 60 * 1000L : requiredSeedingTimeMs;
        validate();
    }
    /**
     * Retourne le temps de seed requis (en ms).
     */
    public long getRequiredSeedingTimeMs() {
        return requiredSeedingTimeMs;
    }
    /**
     * Retourne le délai max sans seed (en ms).
     */
    public long getMaxNonSeedingTimeMs() {
        return maxNonSeedingTimeMs;
    }

    /**
     * Valide tous les paramètres de configuration à la construction.
     * Lance une exception si un paramètre est incohérent.
     */
    private void validate() {
        if (minUploadRate < 0) {
            throw new AppConfigurationIntegrityException("minUploadRate must be at least 0");
        }

        if (maxUploadRate < 0) {
            throw new AppConfigurationIntegrityException("maxUploadRate must greater or equal to 0");
        } else if (maxUploadRate < minUploadRate) {
            throw new AppConfigurationIntegrityException("maxUploadRate must be greater or equal to minUploadRate");
        }

        if (simultaneousSeed == 0 || simultaneousSeed < -1) {
            throw new AppConfigurationIntegrityException("simultaneousSeed must be greater than 0");
        }

        if (StringUtils.isBlank(client)) {
            throw new AppConfigurationIntegrityException("client is required, no file name given");
        }

        if (uploadRatioTarget < 0f && uploadRatioTarget != -1f){
            throw new AppConfigurationIntegrityException("uploadRatioTarget must be greater than 0 (or equal to -1)");
        }
    }
}
