import React, { useEffect, useState } from 'react';
import { getConfig, updateConfig } from '../api';

export default function ConfigForm() {
  const [uploadSpeed, setUploadSpeed] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getConfig().then(cfg => {
      setUploadSpeed(cfg.uploadSpeed || '');
      setLoading(false);
    });
  }, []);

  const handleSubmit = async (e) => {
    e.preventDefault();
    await updateConfig({ uploadSpeed: Number(uploadSpeed) });
    alert('Configuration enregistrée !');
  };

  if (loading) return <div>Chargement…</div>;

  return (
    <form onSubmit={handleSubmit} style={{marginBottom: 32}}>
      <label>Vitesse d'upload (octets/s):
        <input type="number" min="0" value={uploadSpeed} onChange={e => setUploadSpeed(e.target.value)} />
      </label>
      <button type="submit">Enregistrer</button>
    </form>
  );
}
