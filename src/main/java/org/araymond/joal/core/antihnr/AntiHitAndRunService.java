package org.araymond.joal.core.antihnr;

/**
 * <h1>AntiHitAndRunService</h1>
 * <p>
 * Service qui gère la logique anti Hit&Run pour chaque torrent.<br>
 * Il suit le temps seedé, le temps non seedé, et déclenche des avertissements si besoin.<br>
 * <br>
 * <b>Rôle :</b><br>
 * - Suivre le temps seedé total pour un torrent.<br>
 * - Détecter les périodes de non-seed trop longues.<br>
 * - Déclencher des avertissements si le temps de seed requis n'est pas respecté.<br>
 * - Permettre la configuration du temps de seed requis et du temps max de non-seed.<br>
 * <br>
 * <b>Maintenance :</b> Toute modification doit être documentée pour garantir la compréhension de la logique anti H&R.<br>
 * <br>
 * <b>Optimisation possible :</b> rendre la logique configurable par torrent (plutôt que globale).
 * </p>
 */
public class AntiHitAndRunService {
    private final long requiredSeedingTimeMs;
    private final long maxNonSeedingTimeMs;
    private long totalSeedingTime = 0;
    private long lastSeedingTimestamp = 0;
    private long lastNonSeedingTimestamp = 0;
    private boolean isSeeding = false;
    private boolean warningSent = false;

    /**
     * Définit le temps seedé total (utilisé lors de la restauration depuis la persistance).
     * @param totalSeedingTime Temps total seedé en millisecondes
     */
    public void setTotalSeedingTime(long totalSeedingTime) {
        this.totalSeedingTime = totalSeedingTime;
    }

    /**
     * Retourne le temps seedé total (en ms).
     * @return temps seedé total en millisecondes
     */
    public long getTotalSeedingTime() {
        return totalSeedingTime;
    }
    /**
     * Indique si le torrent est actuellement en cours de seed.
     * @return true si le torrent est en seed, false sinon
     */
    public boolean isSeeding() {
        return isSeeding;
    }
    /**
     * Retourne le timestamp du dernier démarrage de seed.
     * @return timestamp en ms depuis epoch
     */
    public long getLastSeedingTimestamp() {
        return lastSeedingTimestamp;
    }

    /**
     * Constructeur principal avec paramètres configurables.
     * @param requiredSeedingTimeMs Temps de seed requis en ms
     * @param maxNonSeedingTimeMs Temps max de non-seed toléré en ms
     */
    public AntiHitAndRunService(long requiredSeedingTimeMs, long maxNonSeedingTimeMs) {
        this.requiredSeedingTimeMs = requiredSeedingTimeMs;
        this.maxNonSeedingTimeMs = maxNonSeedingTimeMs;
    }

    /**
     * Constructeur par défaut (valeurs classiques pour required et max delay).
     * 7 jours de seed requis, 72h max de non-seed toléré.
     */
    public AntiHitAndRunService() {
        this(7 * 24 * 60 * 60 * 1000L, 72 * 60 * 60 * 1000L);
    }

    /**
     * À appeler quand le torrent commence à seed.
     * Met à jour les timestamps et vérifie les délais anti H&R.
     * Si la période de non-seed précédente dépasse la tolérance, déclenche un avertissement.
     */
    public void onSeedingStart() {
        isSeeding = true;
        lastSeedingTimestamp = System.currentTimeMillis();
        // Si on reprend après une longue période de non-seed, vérifier si un avertissement doit être envoyé
        if (lastNonSeedingTimestamp > 0) {
            long nonSeedingDuration = lastSeedingTimestamp - lastNonSeedingTimestamp;
            if (nonSeedingDuration > maxNonSeedingTimeMs && !warningSent && totalSeedingTime < requiredSeedingTimeMs) {
                sendWarning();
                warningSent = true;
            }
        }
    }

    /**
     * À appeler quand le torrent arrête de seed.
     * Met à jour le temps seedé et le timestamp d'arrêt.
     * Cette méthode doit être appelée à chaque arrêt de seed pour garantir un suivi précis.
     */
    public void onSeedingStop() {
        isSeeding = false;
        long now = System.currentTimeMillis();
        // Ajoute la durée de la dernière session de seed au total
        totalSeedingTime += now - lastSeedingTimestamp;
        lastNonSeedingTimestamp = now;
    }

    /**
     * Vérification périodique pour déclencher les avertissements Hit&Run si besoin.
     * À appeler régulièrement (ex: via un thread) pour surveiller le respect des règles anti H&R.
     */
    public void periodicCheck() {
        // Si le torrent n'est pas en seed et que le temps seedé est insuffisant
        if (!isSeeding && totalSeedingTime < requiredSeedingTimeMs) {
            long now = System.currentTimeMillis();
            // Si la période de non-seed dépasse la tolérance, déclencher un avertissement
            if ((now - lastNonSeedingTimestamp) > maxNonSeedingTimeMs && !warningSent) {
                sendWarning();
                warningSent = true;
            }
        }
    }

    /**
     * Affiche un avertissement Hit&Run dans la console (peut être remplacé par un event ou log).
     * Affiche le temps seedé, le temps requis et le temps restant à seed.
     */
    private void sendWarning() {
        System.out.println("Avertissement Hit&Run");
        long seeded = totalSeedingTime;
        // Si le torrent est en seed, inclure la session en cours
        if (isSeeding) {
            seeded += System.currentTimeMillis() - lastSeedingTimestamp;
        }
        long remaining = Math.max(0, requiredSeedingTimeMs - seeded);
        String seededStr = formatDuration(seeded);
        String remainingStr = formatDuration(remaining);
        System.out.println("Avertissement Hit&Run : vous devez continuer à seed !");
        System.out.println("Temps seedé : " + seededStr + " / Temps requis : " + formatDuration(requiredSeedingTimeMs));
        System.out.println("Temps restant à seed : " + remainingStr);
    }

    /**
     * Formate une durée en ms en chaîne lisible (ex: 2j 3h 4m 5s).
     * @param millis Durée en millisecondes
     * @return Chaîne formatée lisible par un humain
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("j ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString();
    }

    /**
     * Indique si le temps de seed requis est atteint pour ce torrent.
     * @return true si le temps seedé total (incluant la session en cours) atteint le minimum requis
     */
    public boolean isRequirementMet() {
        if (isSeeding) {
            long now = System.currentTimeMillis();
            return (totalSeedingTime + (now - lastSeedingTimestamp)) >= requiredSeedingTimeMs;
        }
        return totalSeedingTime >= requiredSeedingTimeMs;
    }
}
