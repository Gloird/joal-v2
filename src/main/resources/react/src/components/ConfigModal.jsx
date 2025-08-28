// Composant React pour la gestion et la modification de la configuration JOAL.
// Affiche une popup (modal) permettant de modifier les paramètres principaux et de les envoyer au backend.
// Optimisation possible : validation des champs côté frontend et feedback utilisateur plus détaillé.
import React, { useEffect, useState } from 'react';
import {
  Dialog, DialogTitle, DialogContent, DialogActions,
  Button, TextField, FormControlLabel, Checkbox, MenuItem, Box
} from '@mui/material';


/**
 * Composant principal pour la popup de configuration.
 * @param open Indique si la popup est ouverte
 * @param onClose Fonction de fermeture
 * @param clientWebSocket WebSocket client pour envoyer la config
 * @param clients Liste des clients BitTorrent disponibles
 * @param config Configuration courante
 * @param configSaveStatus Statut de la sauvegarde (success, error, saving)
 */
export default function ConfigModal({ open, onClose, clientWebSocket, clients, config, configSaveStatus }) {
  // configSaveStatus: { saving: bool, error: string|null, success: bool }
  // Indique si la sauvegarde est en cours
  const [saving, setSaving] = useState(false);
  // Stocke la nouvelle configuration à éditer
  const [newConfig, setNewConfig] = useState({
    minUploadRate: config.minUploadRate || 0,
    maxUploadRate: config.maxUploadRate || 0,
    simultaneousSeed: config.simultaneousSeed || 1,
    client: config.client || '',
    keepTorrentWithZeroLeechers: config.keepTorrentWithZeroLeechers || false,
    uploadRatioTarget: config.uploadRatioTarget !== undefined ? config.uploadRatioTarget : -1.0,
    maxNonSeedingTimeMs: config.maxNonSeedingTimeMs !== undefined ? config.maxNonSeedingTimeMs : 259200000,
    requiredSeedingTimeMs: config.requiredSeedingTimeMs !== undefined ? config.requiredSeedingTimeMs : 604800000 // 7j par défaut
  });

  // Gère la fermeture automatique de la popup après sauvegarde réussie
  useEffect(() => {
    if (configSaveStatus.success) {
      setSaving(configSaveStatus.saving);
      onClose();
    }else if (configSaveStatus.error) {
      setSaving(false);
    }
  }, [configSaveStatus.success, onClose]);

  // Met à jour la config locale si la config globale change
  useEffect(() => {
    setNewConfig({
      minUploadRate: config.minUploadRate || 0,
      maxUploadRate: config.maxUploadRate || 0,
      simultaneousSeed: config.simultaneousSeed || 1,
      client: config.client || '',
      keepTorrentWithZeroLeechers: config.keepTorrentWithZeroLeechers || false,
      uploadRatioTarget: config.uploadRatioTarget !== undefined ? config.uploadRatioTarget : -1.0,
      maxNonSeedingTimeMs: config.maxNonSeedingTimeMs !== undefined ? config.maxNonSeedingTimeMs : 259200000,
      requiredSeedingTimeMs: config.requiredSeedingTimeMs !== undefined ? config.requiredSeedingTimeMs : 604800000
    });
  }, [config]);

  /**
   * Gère la modification des champs du formulaire
   */
  const handleChange = e => {
    const { name, value, type, checked } = e.target;
    setNewConfig(cfg => ({
      ...cfg,
      [name]: type === 'checkbox' ? checked : value
    }));
  };

  /**
   * Envoie la nouvelle configuration au backend via WebSocket
   */
  const handleSave = () => {
    setSaving(true);
    if (clientWebSocket) {
      clientWebSocket.publish({
        destination: '/joal/config/save',
        body: JSON.stringify({
          minUploadRate: Number(newConfig.minUploadRate),
          maxUploadRate: Number(newConfig.maxUploadRate),
          simultaneousSeed: Number(newConfig.simultaneousSeed),
          client: newConfig.client,
          keepTorrentWithZeroLeechers: !!newConfig.keepTorrentWithZeroLeechers,
          uploadRatioTarget: String(newConfig.uploadRatioTarget),
          maxNonSeedingTimeMs: Math.round(Number(newConfig.maxNonSeedingTimeMs)),
          requiredSeedingTimeMs: Math.round(Number(newConfig.requiredSeedingTimeMs))
        })
      });
      // On attend la réponse du backend pour fermer la popup
    } else {
      alert('WebSocket non connecté');
      setSaving(false);
    }
  };

  // Affichage du formulaire de configuration dans une popup Material UI
  return (
    <Dialog open={open} onClose={saving ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Paramètres JOAL</DialogTitle>
      <DialogContent>
        <Box component="form" sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 1 }}>
          {/* Champ pour la vitesse d'upload minimale */}
          <TextField
            label="Min upload (o/s)"
            name="minUploadRate"
            type="number"
            value={newConfig.minUploadRate}
            onChange={handleChange}
            fullWidth
          />
          {/* Champ pour la vitesse d'upload maximale */}
          <TextField
            label="Max upload (o/s)"
            name="maxUploadRate"
            type="number"
            value={newConfig.maxUploadRate}
            onChange={handleChange}
            fullWidth
          />
          {/* Champ pour le nombre de torrents seedés simultanément */}
          <TextField
            label="Simultaneous seed"
            name="simultaneousSeed"
            type="number"
            value={newConfig.simultaneousSeed}
            onChange={handleChange}
            fullWidth
          />
          {/* Sélection du client BitTorrent émulé */}
          <TextField
            select
            label="Client BitTorrent"
            name="client"
            value={newConfig.client}
            onChange={handleChange}
            fullWidth
          >
            {clients.map(c => <MenuItem key={c} value={c}>{c}</MenuItem>)}
          </TextField>
          {/* Option pour garder les torrents sans leechers */}
          <FormControlLabel
            control={<Checkbox checked={!!newConfig.keepTorrentWithZeroLeechers} name="keepTorrentWithZeroLeechers" onChange={handleChange} />}
            label="Garder les torrents sans leechers"
          />
          {/* Champ pour le ratio d'upload cible */}
          <TextField
            label="Upload ratio target"
            name="uploadRatioTarget"
            type="number"
            value={newConfig.uploadRatioTarget}
            onChange={handleChange}
            fullWidth
          />
          {/* Champ pour le délai max sans seed (en heures) */}
          <TextField
            label="Délai max sans seed (heures)"
            name="maxNonSeedingTimeMs"
            type="number"
            value={Math.floor(newConfig.maxNonSeedingTimeMs / 3600000)}
            onChange={e => handleChange({
              ...e,
              target: {
                ...e.target,
                name: 'maxNonSeedingTimeMs',
                value: String(Number(e.target.value) * 3600000)
              }
            })}
            fullWidth
            helperText="Durée max autorisée sans seed avant avertissement (en heures)"
          />
          {/* Champ pour la durée de seed requise (en heures) */}
          <TextField
            label="Durée seed requise (heures)"
            name="requiredSeedingTimeMs"
            type="number"
            value={Math.floor(newConfig.requiredSeedingTimeMs / 3600000)}
            onChange={e => handleChange({
              ...e,
              target: {
                ...e.target,
                name: 'requiredSeedingTimeMs',
                value: String(Number(e.target.value) * 3600000)
              }
            })}
            fullWidth
            helperText="Durée totale de seed requise avant validation (en heures)"
          />
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={saving}>Annuler</Button>
        <Button onClick={handleSave} variant="contained" disabled={saving}>Enregistrer</Button>
      </DialogActions>
    </Dialog>
  );
}
