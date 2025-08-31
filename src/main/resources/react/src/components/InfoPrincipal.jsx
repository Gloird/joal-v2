
// Composant React pour l'affichage des informations globales et de la configuration courante.
// Permet de visualiser rapidement le client émulé et les paramètres principaux.
import React, { use, useEffect, useState } from 'react';
import { Card, CardContent, Typography, Grid, Switch } from '@mui/material';

/**
 * Composant principal pour l'affichage des infos globales et de la config.
 * @param globalInfo Infos globales du client BitTorrent
 * @param config Configuration courante
 * @param clients Liste des clients BitTorrent disponibles
 */
export default function InfoPrincipal({ globalInfo, config, torrents, clientWebSocket }) {

  const handleChangeRunning = (event) => {

    if (clientWebSocket) {
      if (event.target.checked) {
        clientWebSocket.publish({
          destination: '/joal/global/start',
        });
      } else {
        clientWebSocket.publish({
          destination: '/joal/global/stop',
        });
      }

    }
  };

    // Affichage des infos globales et de la configuration dans une carte Material UI
    return (
      <Grid container spacing={3}>
        <Grid item xs={12} md={8} flexDirection={'row'} flex={1} display={'flex'}>
          <Card sx={{ mb: 2, mr: 2, flex: 1 }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>Infos globales</Typography>

              <Typography variant="body2">Client émulé : <b>{config.client || '-'}</b></Typography>
              <Typography variant="body2">Min upload : <b>{config.minUploadRate || '-'}</b></Typography>
              <Typography variant="body2">Max upload : <b>{config.maxUploadRate || '-'}</b></Typography>
              <Typography variant="body2">Simultaneous seed : <b>{config.simultaneousSeed || '-'}</b></Typography>
              <Typography variant="body2">Gardez le torrent sans leechers : <b>{config.keepTorrentWithZeroLeechers ? 'Oui' : 'Non'}</b></Typography>
              <Typography variant="body2">Upload ratio target : <b>{config.uploadRatioTarget || '-'}</b></Typography>

            </CardContent>
          </Card>
          <Card sx={{ mb: 2 }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>En cours d'exécution</Typography>
              <Switch onChange={(event) => { handleChangeRunning(event) }} checked={globalInfo.isStarted || false} />
              <Typography variant="h6" gutterBottom>Statut du service</Typography>
              <Typography variant="body2">Torrents actifs : <b>{torrents?.filter(t => t.requestEvent == "STARTED")?.length}/{torrents?.length}</b></Typography>
              <Typography variant="body2">Vitesse d'upload totale : <b>{torrents ? (torrents?.reduce((p, c) => p + c.bytesPerSecond, 0) * 8 / 1_000_000).toFixed(2) + ' Mbit/s' : '0 Mbit/s'}</b></Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    );
  }