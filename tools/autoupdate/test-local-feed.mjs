import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

const base = (process.argv[2] || process.env.MA_LOCAL_UPDATE_URL || '').replace(/\/$/, '');
assert.match(base, /^http:\/\/(localhost|127\.0\.0\.1|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|169\.254\.\d{1,3}\.\d{1,3}):\d+\/[a-z0-9][a-z0-9._-]{0,62}$/, 'Pass the local MA manifest URL.');

const manifestResponse = await fetch(`${base}/manifest.json`, { cache: 'no-store' });
assert.equal(manifestResponse.status, 200, 'manifest should be available');
const manifest = await manifestResponse.json();
assert.equal(manifest.schema, 1);
assert.equal(manifest.app_id, base.split('/').at(-1));
assert.equal(manifest.package_name, 'com.siny.apps.ma_3c44c3e1b8');
assert.equal(manifest.platform, 'android');
assert.ok(Number.isSafeInteger(manifest.version_code) && manifest.version_code > 0);
assert.match(manifest.version, /^\d+\.\d+\.\d+$/);
assert.match(manifest.sha256, /^[a-f0-9]{64}$/);

const apkUrl = new URL(manifest.apk_url);
assert.equal(apkUrl.origin, new URL(base).origin, 'APK must remain on this local update server');
assert.equal(apkUrl.pathname, `/${manifest.app_id}/update-${manifest.version_code}.apk`);

const redirect = await fetch(`${base}/install`, { redirect: 'manual' });
assert.equal(redirect.status, 302, 'one-time install route should redirect to the current APK');
assert.equal(redirect.headers.get('location'), apkUrl.pathname);

const apkResponse = await fetch(manifest.apk_url, { cache: 'no-store' });
assert.equal(apkResponse.status, 200, 'versioned APK should be downloadable');
const apk = Buffer.from(await apkResponse.arrayBuffer());
assert.ok(apk.length > 0);
assert.equal(createHash('sha256').update(apk).digest('hex'), manifest.sha256, 'APK SHA-256 should match manifest');

const installResponse = await fetch(`${base}/install`, { cache: 'no-store' });
assert.equal(installResponse.status, 200, 'install route should serve the current APK after redirect');
assert.equal(createHash('sha256').update(Buffer.from(await installResponse.arrayBuffer())).digest('hex'), manifest.sha256);

const traversal = await fetch(`${new URL(base).origin}/%2e%2e/server.mjs`);
assert.equal(traversal.status, 404, 'server must reject paths outside the app feed');
const method = await fetch(`${new URL(base).origin}/healthz`, { method: 'POST' });
assert.equal(method.status, 405, 'server must reject non-read methods');

console.log(`Local AutoUpdate feed OK: ${manifest.version} (${manifest.version_code}), ${apk.length} APK bytes, SHA-256 verified.`);
