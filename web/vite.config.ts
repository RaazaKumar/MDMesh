import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server. Every same-origin path the console calls is proxied to the dev stack's edge (Caddy on :8088, see
// docker-compose.dev.yml), which routes it exactly as production does: /rest, /files and /agent/ws to the server,
// /update and /recovery to the supervisor. Override the target with VITE_DEV_PROXY_TARGET.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const target = env.VITE_DEV_PROXY_TARGET || 'http://localhost:8088';
  // The server is session-cookie based (JSESSIONID), so cookies must be forwarded. /agent/ws is a WebSocket.
  const route = (ws = false) => ({ target, changeOrigin: true, cookieDomainRewrite: '', ws });

  return {
    plugins: [
      react(),
      {
        name: 'request-logger',
        configureServer(server) {
          server.middlewares.use((req, res, next) => {
            console.log(`[REQ] ${req.method} ${req.url} from ${req.socket.remoteAddress} | UA: ${req.headers['user-agent']}`);
            if (req.url && req.url.includes('agent.apk')) {
              res.setHeader('Content-Type', 'application/vnd.android.package-archive');
            }
            const t0 = Date.now();
            res.on('finish', () => {
              console.log(`[RES] ${req.method} ${req.url} -> ${res.statusCode} (${Date.now() - t0}ms)`);
            });
            next();
          });
        },
      },
    ],
    build: {
      rolldownOptions: {
        output: {
          // Third-party code in its own chunks: the app chunk stays under Vite's 500 kB warning, and a console
          // upgrade that only changes app code leaves the browser's cached vendor chunks valid.
          codeSplitting: {
            groups: [
              { name: 'react', test: /node_modules[\\/](react|react-dom|react-router|scheduler|cookie|set-cookie-parser)[\\/]/, priority: 2 },
              { name: 'leaflet', test: /node_modules[\\/]leaflet[\\/]/, priority: 2 },
              { name: 'vendor', test: /node_modules[\\/]/, priority: 1 },
            ],
          },
        },
      },
    },
    server: {
      host: true,
      port: 5173,
      allowedHosts: true,
      proxy: {
        '/rest': route(),
        '/files': route(),
        '/agent/ws': route(true),
        '/update': route(),
        '/recovery': route(),
      },
    },
  };
});
