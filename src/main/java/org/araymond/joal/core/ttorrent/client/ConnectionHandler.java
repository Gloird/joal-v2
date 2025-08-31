package org.araymond.joal.core.ttorrent.client;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.channels.ServerSocketChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * <h1>ConnectionHandler</h1>
 * <p>
 * Cette classe a deux fonctions principales :<br>
 * <ul>
 *     <li>Établit un socket sur un port ouvert pour accepter les connexions entrantes (pair à pair).</li>
 *     <li>Résout périodiquement l'adresse IP externe de la machine pour l'annoncer aux trackers.</li>
 * </ul>
 * <br>
 * <b>Rôle :</b><br>
 * - Gérer le port d'écoute pour les connexions BitTorrent.<br>
 * - Maintenir à jour l'adresse IP publique à annoncer.<br>
 * - Permettre la récupération du port et de l'IP courante.<br>
 * <br>
 * <b>Maintenance :</b> Toute modification doit être documentée pour garantir la compréhension de la gestion réseau.<br>
 * <br>
 * Note : Ce port et cette IP sont reportés aux trackers via les announces, si le client utilise les placeholders adéquats.<br>
 * </p>
 */
@Slf4j
public class ConnectionHandler {

    public static final int PORT_RANGE_START = 49152;
    public static final int PORT_RANGE_END = 65534;

    private ServerSocketChannel channel;
    @Getter private InetAddress ipAddress;
    private Thread ipFetcherThread;
    private static final String[] IP_PROVIDERS = new String[]{
            "http://whatismyip.akamai.com",
            "http://ipecho.net/plain",
            "http://ip.tyk.nu/",
            "http://l2.io/ip",
            "http://ident.me/",
            "http://icanhazip.com/",
            "https://api.ipify.org",
            "https://ipinfo.io/ip",
            "https://checkip.amazonaws.com"
    };

    /**
     * Retourne le port actuellement utilisé pour accepter les connexions entrantes.
     * @return port TCP utilisé
     */
    public int getPort() {
        return this.channel.socket().getLocalPort();
    }

    /**
     * Démarre la gestion réseau :
     * - Ouvre un port pour accepter les connexions entrantes.
     * - Récupère l'IP publique à annoncer aux trackers.
     * - Lance un thread qui met à jour l'IP toutes les 90 minutes.
     *
     * @throws IOException si aucun port n'est disponible ou en cas d'erreur réseau
     */
    public void start() throws IOException {
        // Ouvre un port pour accepter les connexions entrantes
        this.channel = this.bindToPort();
        log.info("Listening for incoming peer connections on port {}", getPort());

        // Récupère l'IP publique à annoncer
        this.ipAddress = fetchIp();
        log.info("IP reported to tracker will be: {}", this.getIpAddress().getHostAddress());

        // Thread qui met à jour l'IP toutes les 90 minutes
        this.ipFetcherThread = new Thread(() -> {
            while (this.ipFetcherThread == null || !this.ipFetcherThread.isInterrupted()) {
                try {
                    MINUTES.sleep(90);  // TODO: rendre configurable
                    this.ipAddress = this.fetchIp();
                } catch (final UnknownHostException e) {
                    log.warn("Failed to fetch external IP", e);
                } catch (final InterruptedException e) {
                    log.info("IP fetcher thread has been stopped");
                }
            }
        });

        this.ipFetcherThread.start();
    }

    /**
     * Récupère l'adresse IP publique depuis un fournisseur donné (URL).
     * @param providerUrl URL du service de récupération d'IP
     * @return L'adresse IP publique
     * @throws IOException en cas d'échec de connexion ou de lecture
     */
    @VisibleForTesting
    InetAddress readIpFromProvider(final String providerUrl) throws IOException {
        final URLConnection urlConnection = new URL(providerUrl).openConnection();
        urlConnection.setRequestProperty("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/65.0.3325.181 Safari/537.36");  // TODO: rendre configurable
        try (final BufferedReader in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), Charsets.UTF_8))) {
            return InetAddress.getByName(in.readLine());
        } finally {
            // S'assure que tous les flux liés à la connexion HTTP sont fermés
            final InputStream errStream = ((HttpURLConnection) urlConnection).getErrorStream();
            try { if (errStream != null) errStream.close(); }
            catch (final IOException ignored) {}
        }
    }

    /**
     * Tente de récupérer l'IP publique auprès de plusieurs fournisseurs connus (shuffle pour load balancing).
     * @return L'adresse IP si trouvée, sinon Optional.empty()
     */
    @VisibleForTesting
    Optional<InetAddress> tryToFetchFromProviders() {
        final List<String> shuffledList = Arrays.asList(IP_PROVIDERS);
        Collections.shuffle(shuffledList);

        for (final String ipProviderUrl : shuffledList) {
            log.info("Fetching ip from {}", ipProviderUrl);
            try {
                return Optional.of(this.readIpFromProvider(ipProviderUrl));
            } catch (final IOException e) {
                log.warn("Failed to fetch IP from [" + ipProviderUrl + "]", e);
            }
        }

        return Optional.empty();
    }

    /**
     * Récupère l'IP publique en essayant tous les fournisseurs connus.
     * Si aucun fournisseur ne répond, retourne la dernière IP connue ou localhost.
     * @return L'adresse IP publique
     * @throws UnknownHostException si aucune IP n'est trouvée
     */
    @VisibleForTesting
    InetAddress fetchIp() throws UnknownHostException {
        final Optional<InetAddress> ip = this.tryToFetchFromProviders();
        if (ip.isPresent()) {
            log.info("Successfully fetched public IP address: [{}]", ip.get().getHostAddress());
            return ip.get();
        } else if (this.ipAddress != null) {
            log.warn("Failed to fetch public IP address, reusing last known IP address: [{}]", this.ipAddress.getHostAddress());
            return this.ipAddress;
        }

        log.warn("Failed to fetch public IP address, fallback to localhost");
        return InetAddress.getLocalHost();
    }

    /**
     * Tente de binder un port dans la plage autorisée pour accepter les connexions entrantes.
     * @return Le ServerSocketChannel ouvert et prêt
     * @throws IOException si aucun port n'est disponible
     */
    @VisibleForTesting
    ServerSocketChannel bindToPort() throws IOException {
        // Parcourt la plage de ports pour trouver un port disponible
        ServerSocketChannel channel = null;

        for (int port = ConnectionHandler.PORT_RANGE_START; port <= ConnectionHandler.PORT_RANGE_END; port++) {
            final InetSocketAddress tryAddress = new InetSocketAddress(port);

            try {
                channel = ServerSocketChannel.open();
                channel.socket().bind(tryAddress);
                channel.configureBlocking(false);
                break;
            } catch (final IOException ioe) {
                // Ignore, essaie le port suivant
                log.warn("Could not bind to port {}: {}", tryAddress.getPort(), ioe.getMessage());
                log.warn("trying next port...");
                try {
                    if (channel != null) channel.close();
                } catch (final IOException ignored) {
                }
            }
        }

        if (channel == null || !channel.socket().isBound()) {
            throw new IOException("No available port for the BitTorrent client!");
        }
        return channel;
    }

    /**
     * Ferme proprement le port d'écoute et arrête le thread de récupération d'IP.
     * À appeler lors de l'arrêt de l'application pour libérer les ressources réseau.
     */
    public void close() {
        log.debug("Closing ConnectionHandler...");
        try {
            if (this.channel != null) {
                this.channel.close();
            }
        } catch (final Exception e) {
            log.warn("ConnectionHandler channel has failed to release channel, but the shutdown will proceed", e);
        } finally {
            this.channel = null;
        }

        try {
            if (this.ipFetcherThread != null) {
                this.ipFetcherThread.interrupt();
            }
        } finally {
            this.ipFetcherThread = null;
        }
        log.debug("ConnectionHandler closed");
    }
}
