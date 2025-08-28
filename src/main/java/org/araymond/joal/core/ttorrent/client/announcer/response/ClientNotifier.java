package org.araymond.joal.core.ttorrent.client.announcer.response;

import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage.RequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.araymond.joal.core.ttorrent.client.Client;
import org.araymond.joal.core.ttorrent.client.announcer.Announcer;
import org.araymond.joal.core.ttorrent.client.announcer.exceptions.TooManyAnnouncesFailedInARowException;
import org.araymond.joal.core.ttorrent.client.announcer.request.SuccessAnnounceResponse;

/**
 * Classe qui relie les réponses d'annonce tracker au client principal.
 * Permet de notifier le client des événements importants (ratio atteint, plus de peers, etc.).
 * Optimisation possible : ajouter des hooks pour la gestion avancée des erreurs.
 */
@Slf4j
@RequiredArgsConstructor
public class ClientNotifier implements AnnounceResponseHandler {
    private final Client client;

    /**
     * Appelé juste avant qu'un announce soit envoyé au tracker.
     * (Actuellement non utilisé)
     */
    @Override
    public void onAnnouncerWillAnnounce(final Announcer announcer, final RequestEvent event) {
        // noop
    }

    /**
     * Appelé quand le premier announce réussit. Archive si plus de seeders/leechers.
     */
    @Override
    public void onAnnounceStartSuccess(final Announcer announcer, final SuccessAnnounceResponse result) {
        if (result.getSeeders() < 1 || result.getLeechers() < 1) {
            this.client.onNoMorePeers(announcer.getTorrentInfoHash());
        }
    }

    /**
     * Appelé quand le premier announce échoue.
     */
    @Override
    public void onAnnounceStartFails(final Announcer announcer, final Throwable throwable) {
        // noop
    }

    /**
     * Appelé à chaque announce régulier réussi. Archive si plus de peers ou ratio atteint.
     */
    @Override
    public void onAnnounceRegularSuccess(final Announcer announcer, final SuccessAnnounceResponse result) {
        if (result.getSeeders() < 1 || result.getLeechers() < 1) {
            this.client.onNoMorePeers(announcer.getTorrentInfoHash());
            return;
        }
        if (announcer.hasReachedUploadRatioLimit()) {
            this.client.onUploadRatioLimitReached(announcer.getTorrentInfoHash());
        }
    }

    /**
     * Appelé à chaque announce régulier échoué.
     */
    @Override
    public void onAnnounceRegularFails(final Announcer announcer, final Throwable throwable) {
        // noop
    }

    /**
     * Appelé quand l'annonce d'arrêt réussit. Notifie le client.
     */
    @Override
    public void onAnnounceStopSuccess(final Announcer announcer, final SuccessAnnounceResponse result) {
        log.debug("Notify client that a torrent has stopped");
        this.client.onTorrentHasStopped(announcer);
    }

    /**
     * Appelé quand l'annonce d'arrêt échoue.
     */
    @Override
    public void onAnnounceStopFails(final Announcer announcer, final Throwable throwable) {
        // noop
    }

    /**
     * Appelé quand trop d'announces échouent à la suite. Notifie le client.
     */
    @Override
    public void onTooManyAnnounceFailedInARow(final Announcer announcer, final TooManyAnnouncesFailedInARowException e) {
        log.debug("Notify client that a torrent has failed too many times");
        this.client.onTooManyFailedInARow(announcer);
    }
}
