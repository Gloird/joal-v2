// Composant React pour l'affichage de la liste des torrents et de leurs statistiques.
// Utilise Material UI et DataGrid pour une présentation moderne et interactive.
// Optimisation possible : gestion des erreurs et actions sur les torrents (stop, remove, etc.)
import React, { useEffect, useState, useRef } from 'react';
import { Card, CardContent, Typography, Box, Select, MenuItem, Grid, Paper } from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';

// Formate une durée en millisecondes en chaîne lisible (ex: 1j 2h 3m 4s)
function formatDuration(ms) {
  let s = Math.floor(ms / 1000);
  const d = Math.floor(s / 86400); s %= 86400;
  const h = Math.floor(s / 3600); s %= 3600;
  const m = Math.floor(s / 60); s %= 60;
  let out = '';
  if (d) out += d + 'j ';
  if (h) out += h + 'h ';
  if (m) out += m + 'm ';
  out += s + 's';
  return out;
}

function unFormatDuration(str) {
  let total = 0;
  const regex = /(\d+)([dhms])/g;
  let match;
  while ((match = regex.exec(str)) !== null) {
    const value = parseInt(match[1], 10);
    const unit = match[2];
    if (unit === 'd') total += value * 86400000;
    else if (unit === 'h') total += value * 3600000;
    else if (unit === 'm') total += value * 60000;
    else if (unit === 's') total += value * 1000;
  }
  return total;
}

/**
 * Composant principal pour l'affichage des torrents.
 * @param torrents Liste des torrents à afficher
 * @param speeds Vitesse d'upload par torrent
 * @param globalInfo Infos globales du client
 * @param config Configuration courante
 * @param clients Liste des clients BitTorrent disponibles
 */
export default function TorrentsTable({ torrents, speeds, globalInfo, config, clients }) {
  // Animation du temps seedé (incrémente antiHnRElapsedMs toutes les secondes)
  const [now, setNow] = useState(Date.now());
  const intervalRef = useRef();
  const [torrentList, setTorrentList] = useState(torrents || []);

  useEffect(() => {
    setTorrentList(torrents || []);
    intervalRef.current = setInterval(() => {
      setTorrentList(old => old.map(torrent => {
        torrent.antiHnRElapsedMs += 1000;
        return torrent;
      }));
      // Tick d'animation pour affichage live
    }, 1000);
    return () => clearInterval(intervalRef.current);
  }, []);

  // Met à jour la liste si les torrents changent
  useEffect(() => {
    setTorrentList(torrents || []);
  }, [torrents]);

  // Définition des colonnes pour le tableau DataGrid
  const columns = [
    { field: 'torrentName', headerName: 'Nom', flex: 1, minWidth: 150 },
    {
      field: 'size',
      headerName: 'Taille',
      minWidth: 110,
      valueGetter: (r, v, f) => {
        const row = v;
        if (typeof row.size === 'number') return (row.size / 1024 / 1024 / 1024).toFixed(2) + ' Go';
        if (typeof row.torrentSize === 'number') return (row.torrentSize / 1024 / 1024 / 1024).toFixed(2) + ' Go';
        return '-';
      }
      , sortComparator: (v1, v2) => parseFloat(v1) - parseFloat(v2) 
    },
    { field: 'lastKnownSeeders', headerName: 'Seeders', minWidth: 90, valueGetter: p => p || '-' , sortComparator: (v1, v2) => (v1 === '-' ? 0 : parseInt(v1)) - (v2 === '-' ? 0 : parseInt(v2)) },
    { field: 'lastKnownLeechers', headerName: 'Leechers', minWidth: 90, valueGetter: p => p || '-', sortComparator: (v1, v2) => (v1 === '-' ? 0 : parseInt(v1)) - (v2 === '-' ? 0 : parseInt(v2)) },
    {
      field: 'bytesPerSecond', headerName: 'Vitesse Upload (Mbit/s)', minWidth: 140, valueGetter: p => {
        if (typeof p === 'number') {
          // Conversion octets/s -> Mbit/s
          return (p * 8 / 1_000_000).toFixed(2) + ' Mbit/s';
        }
        return '-';
      }
      , sortComparator: (v1, v2) => parseFloat(v1) - parseFloat(v2) 
    },
    { field: 'antiHnRElapsedMs', headerName: 'Temps seedé', minWidth: 120, valueGetter: p => formatDuration(p) || '-' 
      , sortComparator: (v1, v2) => unFormatDuration(v1) - unFormatDuration(v2) },
    // Statut du torrent (OK ou Erreur)
    { field: 'requestEvent', headerName: 'Statut', minWidth: 90, valueGetter: p => p === 'STARTED' ? 'Actif' : p === 'STOPPED' ? 'Arrêté' : p === 'COMPLETED' ? 'Terminé' : p || '-', 
      cellClassName: p =>  p.row.requestEvent === 'STARTED' ? 'status-active' : p.row.requestEvent === 'STOPPED' ? 'status-stopped' : p.row.requestEvent === 'COMPLETED' ? 'status-completed' : 'status-unknown'}
  ];

  // Affichage du tableau des torrents
  return (
    <Grid container spacing={3}>
      <Grid item xs={12} md={8}>
        <Paper elevation={2} sx={{ p: 2 }}>
          <Typography variant="h6" gutterBottom>Liste des torrents</Typography>
          <div style={{ height: 520, width: '100%' }}>
            <DataGrid
              rows={torrentList.map(t => ({ ...t, id: t.infoHash }))}
              columns={columns}
              pageSize={8}
              rowsPerPageOptions={[8, 16, 32]}
              disableSelectionOnClick
              sx={{ bgcolor: '#fff' }}
              getRowClassName={params => params.row.consecutiveFails > 0 ? 'row-error' : ''}
            />
          </div>
        </Paper>
      </Grid>
    </Grid>
  );
}
