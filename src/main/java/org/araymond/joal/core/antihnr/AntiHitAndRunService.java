package org.araymond.joal.core.antihnr;

/**
 * Service qui gère la logique anti Hit&Run pour chaque torrent.
 * Il suit le temps seedé, le temps non seedé, et déclenche des avertissements si besoin.
 * Optimisation possible : rendre la logique configurable par torrent (plutôt que globale).
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
     */
    public void setTotalSeedingTime(long totalSeedingTime) {
        this.totalSeedingTime = totalSeedingTime;
    }

    /**
     * Retourne le temps seedé total (en ms).
     */
    public long getTotalSeedingTime() {
        return totalSeedingTime;
    }
    /**
     * Indique si le torrent est actuellement en cours de seed.
     */
    public boolean isSeeding() {
        return isSeeding;
    }
    /**
     * Retourne le timestamp du dernier démarrage de seed.
     */
    public long getLastSeedingTimestamp() {
        return lastSeedingTimestamp;
    }

    /**
     * Constructeur principal avec paramètres configurables.
     */
    public AntiHitAndRunService(long requiredSeedingTimeMs, long maxNonSeedingTimeMs) {
        this.requiredSeedingTimeMs = requiredSeedingTimeMs;
        this.maxNonSeedingTimeMs = maxNonSeedingTimeMs;
    }

    /**
     * Constructeur par défaut (valeurs classiques pour required et max delay).
     */
    public AntiHitAndRunService() {
        this(7 * 24 * 60 * 60 * 1000L, 72 * 60 * 60 * 1000L);
    }

    /**
     * À appeler quand le torrent commence à seed.
     * Met à jour les timestamps et vérifie les délais anti H&R.
     */
    public void onSeedingStart() {
        isSeeding = true;
        lastSeedingTimestamp = System.currentTimeMillis();
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
     */
    public void onSeedingStop() {
        isSeeding = false;
        long now = System.currentTimeMillis();
        totalSeedingTime += now - lastSeedingTimestamp;
        lastNonSeedingTimestamp = now;
    }

    /**
     * Vérification périodique pour déclencher les avertissements Hit&Run si besoin.
     */
    public void periodicCheck() {
        if (!isSeeding && totalSeedingTime < requiredSeedingTimeMs) {
            long now = System.currentTimeMillis();
            if ((now - lastNonSeedingTimestamp) > maxNonSeedingTimeMs && !warningSent) {
                sendWarning();
                warningSent = true;
            }
        }
    }

    /**
     * Affiche un avertissement Hit&Run dans la console (peut être remplacé par un event ou log).
     */
    private void sendWarning() {
        System.out.println("Avertissement Hit&Run");
        long seeded = totalSeedingTime;
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
     */
    public boolean isRequirementMet() {
        if (isSeeding) {
            long now = System.currentTimeMillis();
            return (totalSeedingTime + (now - lastSeedingTimestamp)) >= requiredSeedingTimeMs;
        }
        return totalSeedingTime >= requiredSeedingTimeMs;
    }
}
