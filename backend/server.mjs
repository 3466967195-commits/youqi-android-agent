import { createServer } from 'node:http';
import { createHmac, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';
import { mkdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';

const root = dirname(fileURLToPath(import.meta.url));
const dataDir = process.env.YOUQI_DATA_DIR || join(root, 'data');
mkdirSync(dataDir, { recursive: true });
const db = new DatabaseSync(join(dataDir, 'youqi.sqlite'));
const port = Number(process.env.PORT || 8787);
const jwtSecret = process.env.YOUQI_JWT_SECRET || '';
if (jwtSecret.length < 32) throw new Error('YOUQI_JWT_SECRET must contain at least 32 characters');

db.exec(`
  PRAGMA journal_mode = WAL;
  PRAGMA foreign_keys = ON;
  CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'user' CHECK(role IN ('user','admin')),
    enabled INTEGER NOT NULL DEFAULT 1,
    token_version INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    last_login_at TEXT
  );
  CREATE TABLE IF NOT EXISTS app_config (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
  );
  INSERT OR IGNORE INTO app_config(key,value) VALUES
    ('announcement',''), ('maintenance','false'), ('min_version_code','1');
`);

bootstrapAdmin();
const adminHtml = readFileSync(join(root, 'public', 'admin.html'), 'utf8');
const attempts = new Map();

createServer(async (req, res) => {
  try {
    securityHeaders(res);
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    if (req.method === 'OPTIONS') return respond(res, 204, null);
    if (req.method === 'GET' && url.pathname === '/health') return json(res, 200, { ok: true, service: 'youqi-control' });
    if (req.method === 'GET' && url.pathname === '/admin') return html(res, adminHtml);
    if (req.method === 'POST' && url.pathname === '/api/auth/register') return register(req, res);
    if (req.method === 'POST' && url.pathname === '/api/auth/login') return login(req, res, false);
    if (req.method === 'POST' && url.pathname === '/api/admin/login') return login(req, res, true);
    if (req.method === 'GET' && url.pathname === '/api/auth/me') return me(req, res);
    if (req.method === 'GET' && url.pathname === '/api/admin/users') return listUsers(req, res);
    if (req.method === 'GET' && url.pathname === '/api/admin/config') return getConfigRoute(req, res);
    if (req.method === 'PUT' && url.pathname === '/api/admin/config') return updateConfig(req, res);
    const userMatch = url.pathname.match(/^\/api\/admin\/users\/(\d+)$/);
    if (req.method === 'PATCH' && userMatch) return updateUser(req, res, Number(userMatch[1]));
    return json(res, 404, { error: 'not_found', message: '接口不存在' });
  } catch (error) {
    const status = error.status || 500;
    if (status === 500) console.error(error);
    return json(res, status, { error: error.code || 'server_error', message: status === 500 ? '服务器内部错误' : error.message });
  }
}).listen(port, '0.0.0.0', () => console.log(`YouQi control server: http://127.0.0.1:${port}/admin`));

async function register(req, res) {
  limit(req, 'register', 8, 15 * 60_000);
  const body = await bodyJson(req);
  const username = normalizeUsername(body.username);
  const password = validatePassword(body.password);
  const displayName = String(body.display_name || username).trim().slice(0, 40);
  if (!displayName) bad('显示名称不能为空');
  try {
    const now = new Date().toISOString();
    const result = db.prepare('INSERT INTO users(username,display_name,password_hash,created_at) VALUES(?,?,?,?)')
      .run(username, displayName, hashPassword(password), now);
    const user = db.prepare('SELECT * FROM users WHERE id=?').get(result.lastInsertRowid);
    return json(res, 201, sessionPayload(user));
  } catch (error) {
    if (String(error.message).includes('UNIQUE')) conflict('用户名已被注册');
    throw error;
  }
}

async function login(req, res, adminOnly) {
  limit(req, adminOnly ? 'admin-login' : 'login', 12, 15 * 60_000);
  const body = await bodyJson(req);
  const username = normalizeUsername(body.username);
  const user = db.prepare('SELECT * FROM users WHERE username=?').get(username);
  if (!user || !verifyPassword(String(body.password || ''), user.password_hash)) unauthorized('用户名或密码错误');
  if (!user.enabled) forbidden('账号已被停用');
  if (adminOnly && user.role !== 'admin') forbidden('需要管理员账号');
  const now = new Date().toISOString();
  db.prepare('UPDATE users SET last_login_at=? WHERE id=?').run(now, user.id);
  user.last_login_at = now;
  return json(res, 200, sessionPayload(user));
}

function me(req, res) {
  const user = authorize(req);
  const config = getConfig();
  return json(res, 200, { user: publicUser(user), config });
}

function listUsers(req, res) {
  authorize(req, true);
  const users = db.prepare('SELECT id,username,display_name,role,enabled,created_at,last_login_at FROM users ORDER BY id DESC').all();
  return json(res, 200, { users: users.map(normalizeUser) });
}

async function updateUser(req, res, id) {
  const admin = authorize(req, true);
  const body = await bodyJson(req);
  const target = db.prepare('SELECT * FROM users WHERE id=?').get(id);
  if (!target) notFound('用户不存在');
  if (target.id === admin.id && body.enabled === false) bad('不能停用当前管理员账号');
  if (typeof body.enabled !== 'boolean') bad('enabled 必须是布尔值');
  db.prepare('UPDATE users SET enabled=?, token_version=token_version+1 WHERE id=?').run(body.enabled ? 1 : 0, id);
  return json(res, 200, { user: publicUser(db.prepare('SELECT * FROM users WHERE id=?').get(id)) });
}

function getConfigRoute(req, res) {
  authorize(req, true);
  return json(res, 200, { config: getConfig() });
}

async function updateConfig(req, res) {
  authorize(req, true);
  const body = await bodyJson(req);
  const announcement = String(body.announcement || '').trim().slice(0, 500);
  const maintenance = Boolean(body.maintenance);
  const minVersion = Math.max(1, Math.min(1_000_000, Number(body.min_version_code || 1)));
  const put = db.prepare('INSERT INTO app_config(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value');
  db.exec('BEGIN');
  try {
    put.run('announcement', announcement);
    put.run('maintenance', String(maintenance));
    put.run('min_version_code', String(minVersion));
    db.exec('COMMIT');
  } catch (error) { db.exec('ROLLBACK'); throw error; }
  return json(res, 200, { config: getConfig() });
}

function authorize(req, adminOnly = false) {
  const header = String(req.headers.authorization || '');
  if (!header.startsWith('Bearer ')) unauthorized('登录已失效');
  const payload = verifyToken(header.slice(7));
  const user = db.prepare('SELECT * FROM users WHERE id=?').get(payload.sub);
  if (!user || !user.enabled || user.token_version !== payload.ver) unauthorized('账号已退出或被停用');
  if (adminOnly && user.role !== 'admin') forbidden('需要管理员权限');
  return user;
}

function sessionPayload(user) {
  return { token: signToken(user), user: publicUser(user), config: getConfig() };
}

function signToken(user) {
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const now = Math.floor(Date.now() / 1000);
  const payload = base64url(JSON.stringify({ sub: user.id, role: user.role, ver: user.token_version, iat: now, exp: now + 7 * 86400 }));
  const data = `${header}.${payload}`;
  return `${data}.${createHmac('sha256', jwtSecret).update(data).digest('base64url')}`;
}

function verifyToken(token) {
  const parts = token.split('.');
  if (parts.length !== 3) unauthorized('无效登录令牌');
  const expected = createHmac('sha256', jwtSecret).update(`${parts[0]}.${parts[1]}`).digest();
  let actual;
  try { actual = Buffer.from(parts[2], 'base64url'); } catch { unauthorized('无效登录令牌'); }
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) unauthorized('无效登录令牌');
  const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
  if (!payload.exp || payload.exp < Date.now() / 1000) unauthorized('登录已过期');
  return payload;
}

function hashPassword(password) {
  const salt = randomBytes(16);
  const derived = scryptSync(password, salt, 32, { N: 16384, r: 8, p: 1 });
  return `scrypt$16384$${salt.toString('base64url')}$${derived.toString('base64url')}`;
}

function verifyPassword(password, encoded) {
  try {
    const [, cost, salt, hash] = encoded.split('$');
    const expected = Buffer.from(hash, 'base64url');
    const actual = scryptSync(password, Buffer.from(salt, 'base64url'), expected.length, { N: Number(cost), r: 8, p: 1 });
    return timingSafeEqual(actual, expected);
  } catch { return false; }
}

function bootstrapAdmin() {
  const username = process.env.YOUQI_ADMIN_USER;
  const password = process.env.YOUQI_ADMIN_PASSWORD;
  if (!username || !password) return;
  const existing = db.prepare('SELECT id FROM users WHERE username=?').get(username);
  if (existing) return;
  validatePassword(password);
  db.prepare("INSERT INTO users(username,display_name,password_hash,role,created_at) VALUES(?,?,?,'admin',?)")
    .run(normalizeUsername(username), '王嘉泽', hashPassword(password), new Date().toISOString());
  console.log(`Created administrator: ${username}`);
}

function getConfig() {
  const rows = db.prepare('SELECT key,value FROM app_config').all();
  const map = Object.fromEntries(rows.map(row => [row.key, row.value]));
  return { announcement: map.announcement || '', maintenance: map.maintenance === 'true', min_version_code: Number(map.min_version_code || 1) };
}

function normalizeUsername(value) {
  const username = String(value || '').trim().toLowerCase();
  if (!/^[a-z0-9_]{4,24}$/.test(username)) bad('用户名需为 4-24 位字母、数字或下划线');
  return username;
}
function validatePassword(value) {
  const password = String(value || '');
  if (password.length < 8 || password.length > 72) bad('密码长度需为 8-72 位');
  return password;
}
function publicUser(user) { return normalizeUser({ id: user.id, username: user.username, display_name: user.display_name, role: user.role, enabled: user.enabled, created_at: user.created_at, last_login_at: user.last_login_at }); }
function normalizeUser(user) { return { ...user, enabled: Boolean(user.enabled) }; }
function base64url(value) { return Buffer.from(value).toString('base64url'); }

async function bodyJson(req) {
  let body = '';
  for await (const chunk of req) {
    body += chunk;
    if (body.length > 64 * 1024) throw httpError(413, '请求体过大');
  }
  try { return JSON.parse(body || '{}'); } catch { bad('JSON 格式错误'); }
}

function limit(req, bucket, maximum, windowMs) {
  const key = `${bucket}:${req.socket.remoteAddress}`;
  const now = Date.now();
  const current = attempts.get(key);
  if (!current || current.reset < now) { attempts.set(key, { count: 1, reset: now + windowMs }); return; }
  current.count++;
  if (current.count > maximum) throw httpError(429, '尝试次数过多，请稍后再试');
}

function securityHeaders(res) {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('Content-Security-Policy', "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'");
}
function respond(res, status, body, type = 'application/json; charset=utf-8') { res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' }); res.end(body == null ? '' : body); }
function json(res, status, value) { respond(res, status, JSON.stringify(value)); }
function html(res, value) { respond(res, 200, value, 'text/html; charset=utf-8'); }
function httpError(status, message, code) { return Object.assign(new Error(message), { status, code }); }
function bad(message) { throw httpError(400, message, 'bad_request'); }
function unauthorized(message) { throw httpError(401, message, 'unauthorized'); }
function forbidden(message) { throw httpError(403, message, 'forbidden'); }
function notFound(message) { throw httpError(404, message, 'not_found'); }
function conflict(message) { throw httpError(409, message, 'conflict'); }
