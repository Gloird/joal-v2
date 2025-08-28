
import { Client } from '@stomp/stompjs';
// API utilitaires pour REST et WebSocket

// Générateur UUID v4 compatible navigateur
function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = crypto.getRandomValues(new Uint8Array(1))[0] & 15;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

export const getConfig = async () => {
  const res = await fetch('/api/config');
  return await res.json();
};

export const updateConfig = async (config) => {
  await fetch('/api/config', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config)
  });
};

let client;
let onSpeedUpdate = () => {};
let onTorrentUpdate = () => {};
let onInitEvents = () => {};
let onGlobalUpdate = () => {};
let onConfigUpdate = () => {};

export function connectWebSocket({ onSpeed, onTorrent, onInit, onGlobal, onConfig }) {
  onSpeedUpdate = onSpeed;
  onTorrentUpdate = onTorrent;
  onInitEvents = onInit;
  onGlobalUpdate = onGlobal;
  onConfigUpdate = onConfig;

  console.log(import.meta.env.VITE_WS_PATH, import.meta.env.VITE_WS_HOST);
  const WS_PATH = import.meta.env.VITE_WS_PATH || window.location.pathname;
  const WS_HOST = import.meta.env.VITE_WS_HOST || window.location.host;
  const WS_URL = (window.location.protocol === 'https:' ? 'wss://' : 'ws://') + WS_HOST +'/ws';

    // Récupère le token depuis la config ou l'environnement
    const secretToken = 'SECRET_TOKEN';
    const username = uuidv4();

    client = new Client({
      brokerURL: WS_URL,
      reconnectDelay: 5000,
      debug: str => console.log(str),
      connectHeaders: {
        'X-Joal-Auth-Token': secretToken,
        'X-Joal-Username': username
      }
    });

  client.onConnect = () => {
    // S'abonner à tous les topics utiles avec ack client
    client.subscribe('/global', message => {
      message.ack && message.ack();
      try {
        const payload = JSON.parse(message.body);
        // TODO: callback globale si besoin
        onGlobalUpdate(payload.payload || payload);
      } catch (e) {
        console.error('Erreur parsing /global', e, message.body);
      }
    }, { ack: 'client' });
    client.subscribe('/announce', message => {
      message.ack && message.ack();
      try {
        const payload = JSON.parse(message.body);
        onTorrentUpdate(payload.payload || payload); // supporte {type, payload} ou direct
      } catch (e) {
        console.error('Erreur parsing /announce', e, message.body);
      }
    }, { ack: 'client' });
    client.subscribe('/config', message => {
      message.ack && message.ack();
      try {
        const payload = JSON.parse(message.body);
        // TODO: callback config si besoin
        onConfigUpdate(payload);
      } catch (e) {
        console.error('Erreur parsing /config', e, message.body);
      }
    }, { ack: 'client' });
    client.subscribe('/torrents', message => {
      message.ack && message.ack();
      try {
        const payload = JSON.parse(message.body);
        onTorrentUpdate(payload.payload || payload);
      } catch (e) {
        console.error('Erreur parsing /torrents', e, message.body);
      }
    }, { ack: 'client' });
    client.subscribe('/speed', message => {
      message.ack && message.ack();
      try {
        const payload = JSON.parse(message.body);
        onSpeedUpdate((payload.payload && payload.payload.speeds) || payload.speeds || payload);
      } catch (e) {
        console.error('Erreur parsing /speed', e, message.body);
      }
    }, { ack: 'client' });
    // S'abonner à l'initialisation (topic correct)
    client.subscribe('/joal/initialize-me', message => {
      message.ack && message.ack();
      const events = JSON.parse(message.body);
      onInitEvents(events);
    }, { ack: 'client' });
    client.publish({
      destination: '/joal/events/initialize-me',
      body: '',
    });
  };

  client.activate();
  return client;
}

export function disconnectWebSocket() {
  if (client) client.deactivate();
}
