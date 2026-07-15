import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const appRoot = path.resolve(here, '..');
const repoRoot = path.resolve(appRoot, '..');

function readEnv(file) {
  const result = {};
  if (!fs.existsSync(file)) return result;
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const index = line.indexOf('=');
    if (index < 1) continue;
    const key = line.slice(0, index).trim();
    let value = line.slice(index + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
    result[key] = value;
  }
  return result;
}

const values = { ...readEnv(path.join(repoRoot, '.env.example')), ...readEnv(path.join(repoRoot, '.env')), ...process.env };
const required = ['ANDROID_APPLICATION_ID', 'APP_NAME', 'PUBLIC_BASE_URL', 'LOCAL_BASE_URL'];
for (const key of required) {
  if (!values[key]) throw new Error(`Missing ${key}. Copy .env.example to .env and configure it.`);
}

const host = (url) => new URL(url).host;
const navigation = [...new Set([host(values.PUBLIC_BASE_URL), host(values.LOCAL_BASE_URL), values.AUTH_HOST].filter(Boolean))];
const config = {
  appId: values.ANDROID_APPLICATION_ID,
  appName: values.APP_NAME,
  webDir: 'www',
  server: { allowNavigation: navigation },
  android: { allowMixedContent: false, captureInput: true, webContentsDebuggingEnabled: false },
};
fs.writeFileSync(path.join(appRoot, 'capacitor.config.json'), `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
console.log(`Generated capacitor.config.json for ${navigation.join(', ')}`);
