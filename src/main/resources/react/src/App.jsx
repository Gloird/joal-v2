
// Composant principal de l'application React JOAL.
// Gère la navigation, la connexion WebSocket, l'affichage des torrents, la configuration et les notifications.
// Optimisation possible : découpage en sous-composants, gestion d'erreurs plus fine, internationalisation.
import React, { useEffect, useState } from 'react';
import './App.css';
import TorrentsTable from './components/TorrentsTable';
import { connectWebSocket } from './api';
import ConfigModal from './components/ConfigModal';
import {
  AppBar, Toolbar, Typography, IconButton, Drawer, List, ListItem, ListItemIcon, ListItemText,
  CssBaseline, Box, Container, Snackbar
} from '@mui/material';
import SettingsIcon from '@mui/icons-material/Settings';
import DashboardIcon from '@mui/icons-material/Dashboard';
import InfoPrincipal from './components/InfoPrincipal';

/**
 * Composant principal de l'application JOAL.
 * Gère la navigation, la connexion WebSocket, l'affichage des torrents, la configuration et les notifications.
 */
function App() {
  // État de la page courante (dashboard ou config)
  const [page, setPage] = useState('dashboard');
  // État d'ouverture de la popup de configuration
  const [configOpen, setConfigOpen] = useState(false);
  // État de la snackbar (notification)
  const [snackbar, setSnackbar] = useState({ open: false, message: '' });
  // Liste des torrents
  const [torrents, setTorrents] = useState([]);
  // Vitesse d'upload par torrent
  const [speeds, setSpeeds] = useState({});
  // Infos globales du client
  const [globalInfo, setGlobalInfo] = useState({});
  // Configuration courante
  const [config, setConfig] = useState({});
  // Liste des clients BitTorrent disponibles
  const [clients, setClients] = useState([]);
  // Instance du client WebSocket
  const [clientWebSocket, setClientWebSocket] = useState(null);
  // Statut de la sauvegarde de la config
  const [configSaveStatus, setConfigSaveStatus] = useState({ saving: false, error: null, success: false });

  // --- FONCTIONS DE FUSION PAR TYPE D'ÉVÉNEMENT ---
  // Fusionne les infos d'un torrent ajouté
  function mergeTorrentFileAdded(prev, payload) {
    if (!payload || !payload.infoHash) return prev;
    const idx = prev.findIndex(t => t.infoHash === payload.infoHash);
    if (idx !== -1) {
      const merged = { ...prev[idx], ...payload };
      return [...prev.slice(0, idx), merged, ...prev.slice(idx + 1)];
    }
    return [...prev, { ...payload }];
  }

  // Fusionne les infos d'un torrent lors d'un announce
  function mergeWillAnnounce(prev, payload) {
    if (!payload || !payload.infoHash) return prev;
    const idx = prev.findIndex(t => t.infoHash === payload.infoHash);
    if (idx !== -1) {
      const merged = { ...prev[idx], ...payload };
      return [...prev.slice(0, idx), merged, ...prev.slice(idx + 1)];
    }
    return [...prev, { ...payload }];
  }

  // Fusionne les infos d'un torrent après announce réussi
  function mergeSuccessfullyAnnounce(prev, payload) {
    if (!payload || !payload.infoHash) return prev;
    const idx = prev.findIndex(t => t.infoHash === payload.infoHash);
    if (idx !== -1) {
      const merged = { ...prev[idx], ...payload };
      return [...prev.slice(0, idx), merged, ...prev.slice(idx + 1)];
    }
    return [...prev, { ...payload }];
  }

  // Met à jour la vitesse d'upload et le temps seedé
  function mergeSeedingSpeedHasChanged(prev, speedsArr) {
    if (!Array.isArray(speedsArr)) return prev;
    return prev.map(t => {
      const found = speedsArr.find(s => s && s.infoHash === t.infoHash);
      if (found) {
        return {
          ...t,
          antiHnRElapsedMs: typeof found.antiHnRElapsedMs === 'number' ? found.antiHnRElapsedMs : t.antiHnRElapsedMs,
          bytesPerSecond: found.bytesPerSecond !== undefined ? found.bytesPerSecond : t.bytesPerSecond
        };
      }
      return t;
    });
  }

  // Connexion au backend via WebSocket et gestion des événements
  useEffect(() => {
    setClientWebSocket(connectWebSocket({
      onGlobal: (global) => {
        // Réception des infos globales
        console.log('Received global info:', global);
        setGlobalInfo(global);
      },
      onConfig: (conf) => {
        // Réception de la configuration ou erreur
        console.log('Received config:', conf);
        // Gestion des erreurs INVALID_CONFIG
        if (conf && conf.type === 'INVALID_CONFIG' && conf.payload && conf.payload.error) {
          setSnackbar({ open: true, message: 'Erreur config : ' + conf.payload.error });
          setConfigSaveStatus({ saving: false, error: conf.payload.error, success: false });
          return;
        }
        // Peut être {type, payload: {config: ...}} ou juste {config: ...}
        let newConf = conf;
        if (conf && conf.config) newConf = conf.config;
        if (conf && conf.payload && conf.payload.config) newConf = conf.payload.config;
        console.log('Received config:', newConf);
        setConfig(newConf);
        // Succès : on ferme la popup
        setConfigSaveStatus({ saving: false, error: null, success: true });
        setConfigOpen(false);
      },
      onSpeed: (speedsArr) => {
        // Réception des vitesses d'upload
        console.log('Received speeds:', speedsArr);
        const speedMap = {};
        setTorrents(prevTorrents => mergeSeedingSpeedHasChanged(prevTorrents, speedsArr));
        if (Array.isArray(speedsArr)) {
          speedsArr.forEach(s => { if (s && s.infoHash) speedMap[s.infoHash] = s.bytesPerSecond; });
        }
        setSpeeds(speedMap);
      },
      onTorrent: (payload) => {
        // Réception d'une mise à jour torrent
        console.log('Received torrent update:', payload);
        setTorrents(prev => {
          let next = mergeTorrentFileAdded(prev, payload);
          next = mergeSuccessfullyAnnounce(next, payload);
          next = mergeWillAnnounce(next, payload);
          return next;
        });
      },
      onInit: (events) => {
        // Initialisation : fusionne tous les événements reçus
        console.log('Received init events:', events);
        let torrentsList = [];
        let speedMap = {};
        let global = {};
        let conf = {};
        let clientList = [];
        // On fusionne toutes les infos par infoHash
        const torrentMap = {};
        events.forEach(ev => {
          if (ev.type === 'TORRENT_FILE_ADDED' && ev.payload && ev.payload.infoHash) {
            torrentMap[ev.payload.infoHash] = { ...torrentMap[ev.payload.infoHash], ...ev.payload };
          }
          if (ev.type === 'SUCCESSFULLY_ANNOUNCE' && ev.payload && ev.payload.infoHash) {
            torrentMap[ev.payload.infoHash] = { ...torrentMap[ev.payload.infoHash], ...ev.payload };
          }
          if (ev.type === 'SEEDING_SPEED_HAS_CHANGED' && ev.payload && Array.isArray(ev.payload.speeds)) {
            ev.payload.speeds.forEach(s => {
              if (s && s.infoHash) {
                torrentMap[s.infoHash] = { ...torrentMap[s.infoHash], ...s };
                speedMap[s.infoHash] = s.bytesPerSecond;
              }
            });
          }
          if (ev.type === 'GLOBAL_SEED_STARTED' && ev.payload) {
            global = { ...global, client: ev.payload.client };
          }
          if (ev.type === 'CONFIG_HAS_BEEN_LOADED' && ev.payload) {
            conf = ev.payload.config;
          }
          if (ev.type === 'LIST_OF_CLIENT_FILES' && ev.payload) {
            clientList = ev.payload.clients;
          }
        });
        torrentsList = Object.values(torrentMap);
        setTorrents(torrentsList);
        setSpeeds(speedMap);
        setGlobalInfo(global);
        setConfig(conf);
        setClients(clientList);
      }
    }));
  }, []);

  // Gère la navigation entre dashboard et config
  const handleNav = (p) => {
    setPage(p);
    if (p === 'config') {
      setConfigSaveStatus({ saving: false, error: null, success: false });
      setConfigOpen(true);
    } else {
      setConfigOpen(false);
    }
  };

  // Affiche une notification snackbar
  const handleSnackbar = (msg) => {
    setSnackbar({ open: true, message: msg });
  };

  // Affichage principal de l'application
  return (
    <Box sx={{ display: 'flex', bgcolor: '#f7f7f7' }}>
      <CssBaseline />
      {/* Barre d'application avec navigation */}
      <AppBar position="fixed" sx={{ zIndex: 1201 }}>
        <Toolbar>
          <Typography variant="h6" noWrap sx={{ flexGrow: 1 }}>
            JOAL - Administration
          </Typography>
          <IconButton color="inherit" onClick={() => handleNav('config')} size="large">
            <SettingsIcon />
          </IconButton>
          <IconButton color="inherit" onClick={() => handleNav('dashboard')} size="large">
            <DashboardIcon />
          </IconButton>
        </Toolbar>
      </AppBar>
      <Box component="main" sx={{ flexGrow: 1, bgcolor: '#f7f7f7', minHeight: '100vh', pl: 2 }}>
        <Toolbar />
        <Container maxWidth="lg" sx={{ mt: 4 }}>
          {/* Affichage des infos globales et de la config */}
          <InfoPrincipal globalInfo={globalInfo} config={config} clients={clients} />
          {/* Tableau des torrents */}
          <TorrentsTable onSnackbar={handleSnackbar} torrents={torrents} speeds={speeds} globalInfo={globalInfo} config={config} clients={clients} />
        </Container>
      </Box>
      {/* Popup de configuration */}
      <ConfigModal
        open={configOpen}
        onClose={() => handleNav('dashboard')}
        clientWebSocket={clientWebSocket}
        config={config}
        clients={clients}
        configSaveStatus={configSaveStatus}
      />
      {/* Snackbar pour les notifications */}
      <Snackbar
        open={snackbar.open}
        autoHideDuration={4000}
        onClose={() => setSnackbar(s => ({ ...s, open: false }))}
        message={snackbar.message}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      />
    </Box>
  );
}

export default App;
