import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import clean from 'vite-plugin-clean';

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    plugins: [
    clean({
      targets: ['../public/*'] // adapte le chemin si besoin
    })],
    outDir: '../public', // Spécifie le dossier de sortie
  },
})
