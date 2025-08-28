
// Composant React pour l'affichage des informations globales et de la configuration courante.
// Permet de visualiser rapidement le client émulé et les paramètres principaux.
import React, { useEffect, useState } from 'react';
import { Card, CardContent, Typography, Grid } from '@mui/material';

/**
 * Composant principal pour l'affichage des infos globales et de la config.
 * @param globalInfo Infos globales du client BitTorrent
 * @param config Configuration courante
 * @param clients Liste des clients BitTorrent disponibles
 */
export default function InfoPrincipal({ globalInfo, config, clients }) {
  // Affichage des infos globales et de la configuration dans une carte Material UI
  return (
    <Grid container spacing={3}>
      <Grid item xs={42} md={19}>
        <Card sx={{ mb: 2 }}>
          <CardContent>
            <Typography variant="h6" gutterBottom>Infos globales</Typography>
            {/* Affiche le client BitTorrent émulé */}
            <Typography variant="body2">Client émulé : <b>{globalInfo.client || '-'}</b></Typography>
            <Typography variant="subtitle2" sx={{ mt: 2 }}>Config :</Typography>
            {/* Liste des paramètres principaux */}
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              <li>Min upload : {config.minUploadRate} o/s</li>
              <li>Max upload : {config.maxUploadRate} o/s</li>
              <li>Simultaneous seed : {config.simultaneousSeed}</li>
              <li>Client utilisé : {config.client}</li>
              <li>Gardez le torrent sans leechers : {config.keepTorrentWithZeroLeechers ? 'Oui' : 'Non'}</li>
              <li>Upload ratio target : {config.uploadRatioTarget}</li>
            </ul>
          </CardContent>
        </Card>
      </Grid>
    </Grid>
  );
}