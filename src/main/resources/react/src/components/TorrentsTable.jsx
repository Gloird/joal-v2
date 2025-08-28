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

// Formate une date/heure en chaîne locale
function formatDateTime(dt) {
  if (!dt) return '-';
  try {
    return new Date(dt).toLocaleString();
  } catch {
    return dt;
  }
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
    },
    { field: 'lastKnownSeeders', headerName: 'Seeders', minWidth: 90, valueGetter: p => p || '-' },
    { field: 'lastKnownLeechers', headerName: 'Leechers', minWidth: 90, valueGetter: p => p || '-' },
    {
      field: 'bytesPerSecond', headerName: 'Vitesse Upload (Mbit/s)', minWidth: 140, valueGetter: p => {
        if (typeof p === 'number') {
          // Conversion octets/s -> Mbit/s
          return (p * 8 / 1_000_000).toFixed(2) + ' Mbit/s';
        }
        return '-';
      }
    },
    { field: 'antiHnRElapsedMs', headerName: 'Temps seedé', minWidth: 120, valueGetter: p => formatDuration(p) || '-' },
    // Statut du torrent (OK ou Erreur)
    { field: 'statut', headerName: 'Statut', minWidth: 90, renderCell: p => p > 0 ? <span style={{ color: 'red' }}>Erreur</span> : <span style={{ color: 'green' }}>OK</span> },
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
