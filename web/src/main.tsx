import '@fontsource-variable/jetbrains-mono';
import '@fontsource-variable/hanken-grotesk';
import './styles/tokens.css';
import './styles/app.css';
import './styles/chat.css';
import './styles/pipeline.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { installApi } from './api';
import { App } from './App';

async function boot() {
  // The mock is only bundled when VITE_MOCK=1 (the condition is replaced at build time).
  if (import.meta.env.VITE_MOCK === '1') {
    const { mockApi } = await import('./mock');
    installApi(mockApi);
  }
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

void boot();
