import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import net from 'node:net';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = new Map();
for (let i = 2; i < process.argv.length; i += 2) args.set(process.argv[i], process.argv[i + 1]);
const host = args.get('--host') || '127.0.0.1';
const port = Number(args.get('--port') || 18766);
const root = path.resolve(args.get('--root') || path.join(here, 'feed'));

function isPrivateHost(value) {
  if (value === '127.0.0.1' || value === 'localhost') return true;
  if (!net.isIPv4(value)) return false;
  const [a, b] = value.split('.').map(Number);
  return a === 10 || (a === 172 && b >= 16 && b <= 31)
    || (a === 192 && b === 168) || (a === 169 && b === 254);
}

if (!isPrivateHost(host)) throw new Error('Update server can bind only to loopback or a private IPv4 address.');
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid update server port.');

function reply(res, status, body, contentType = 'text/plain; charset=utf-8') {
  res.writeHead(status, {
    'Content-Type': contentType,
    'Cache-Control': 'no-store, max-age=0',
    'X-Content-Type-Options': 'nosniff',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

const server = http.createServer((req, res) => {
  if (req.method !== 'GET' && req.method !== 'HEAD') return reply(res, 405, 'Method not allowed');
  let pathname;
  try { pathname = decodeURIComponent(new URL(req.url, `http://${host}:${port}`).pathname); }
  catch { return reply(res, 400, 'Bad request'); }
  if (pathname === '/healthz') return reply(res, 200, 'ok');

  const match = /^\/([a-z0-9][a-z0-9._-]{0,62})\/(manifest\.json|install|update-(\d+)\.apk)$/.exec(pathname);
  if (!match) return reply(res, 404, 'Not found');
  const [, appId, filename, version] = match;
  const directory = path.join(root, appId);
  const manifestPath = path.join(directory, 'manifest.json');

  function readManifest() {
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    if (manifest.app_id !== appId || manifest.schema !== 1 || manifest.platform !== 'android') {
      throw new Error('Invalid update manifest');
    }
    return manifest;
  }

  if (filename === 'install') {
    try {
      const manifest = readManifest();
      if (!Number.isSafeInteger(manifest.version_code) || manifest.version_code <= 0) {
        return reply(res, 503, 'Update manifest unavailable');
      }
      const apkPath = path.join(directory, `update-${manifest.version_code}.apk`);
      if (!fs.statSync(apkPath).isFile()) return reply(res, 404, 'Not found');
      res.writeHead(302, {
        Location: `/${appId}/update-${manifest.version_code}.apk`,
        'Cache-Control': 'no-store, max-age=0',
        'Content-Length': 0,
      });
      return res.end();
    } catch {
      return reply(res, 503, 'Update manifest unavailable');
    }
  }

  const target = filename === 'manifest.json'
    ? manifestPath
    : path.join(directory, `update-${version}.apk`);

  let stat;
  try { stat = fs.statSync(target); } catch { return reply(res, 404, 'Not found'); }
  if (!stat.isFile() || stat.size <= 0) return reply(res, 404, 'Not found');

  if (filename === 'manifest.json') {
    try {
      const manifest = readManifest();
      return reply(res, 200, `${JSON.stringify(manifest)}\n`, 'application/json; charset=utf-8');
    } catch {
      return reply(res, 503, 'Update manifest unavailable');
    }
  }

  res.writeHead(200, {
    'Content-Type': 'application/vnd.android.package-archive',
    'Content-Length': stat.size,
    'Cache-Control': 'no-store, max-age=0',
    'X-Content-Type-Options': 'nosniff',
  });
  if (req.method === 'HEAD') return res.end();
  fs.createReadStream(target).on('error', () => res.destroy()).pipe(res);
});

server.listen(port, host, () => process.stdout.write(`MA local AutoUpdate listening on http://${host}:${port}\n`));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
