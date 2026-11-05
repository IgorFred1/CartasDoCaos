'use strict';
/**
 * Relay do Cartas do Caos (sem dependências externas, só Node 18+).
 *
 * O jogo continua rodando no celular do anfitrião; este servidor só:
 *   1. mantém a LISTA PÚBLICA de salas abertas (GET /rooms);
 *   2. repassa as mensagens entre o celular do anfitrião e os convidados
 *      (necessário porque celulares ficam atrás de NAT e não aceitam conexões da internet).
 * Ele não entende as regras do jogo e não guarda nada em disco.
 *
 * Protocolo (WebSocket, texto):
 *   anfitrião  -> wss://HOST/host
 *       ->  {"t":"open","name":"Maria","locked":true}      (cria a sala)
 *       <-  {"t":"opened","room":"KX7P"}
 *       ->  {"t":"info","players":3,"open":true}           (a cada poucos segundos)
 *       <-  {"t":"join","cid":7}   /  {"t":"left","cid":7}
 *       <-> {"t":"data","cid":7,"d":"<linha do protocolo do jogo>"}
 *       ->  {"t":"kick","cid":7}
 *   convidado  -> wss://HOST/join/KX7P  (texto puro: cada mensagem é uma linha do protocolo do jogo)
 */
const http = require('http');
const crypto = require('crypto');

const PORT = parseInt(process.env.PORT || '8080', 10);
const MAX_ROOMS = 300;
const MAX_ROOMS_PER_IP = 3;
const MAX_CLIENTS_PER_ROOM = 24;
const MAX_CLIENTS_PER_IP = 24;
const MAX_FRAME = 64 * 1024;
const ROOM_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

const rooms = new Map(); // id -> {id, name, locked, players, open, host, clients: Map(cid -> Ws), ip, created}
const ipRooms = new Map();
const ipClients = new Map();
let nextCid = 1;

function bump(map, key, d) {
  const v = (map.get(key) || 0) + d;
  if (v <= 0) map.delete(key); else map.set(key, v);
  return v;
}

function clientIp(req) {
  const xf = req.headers['x-forwarded-for'];
  if (xf) return String(xf).split(',')[0].trim();
  return req.socket.remoteAddress || '?';
}

function cleanName(s) {
  if (typeof s !== 'string') return null;
  const n = s.replace(/[^\p{L}\p{N} _.\-]/gu, '').replace(/\s+/g, ' ').trim().slice(0, 15);
  return n.length >= 3 ? n : null;
}

// ---------------------------------------------------------------- WebSocket mínimo (RFC 6455)

class Ws {
  constructor(socket) {
    this.socket = socket;
    this.buf = Buffer.alloc(0);
    this.frag = null;
    this.alive = true;
    this.closed = false;
    this.tokens = 100;
    this.lastRefill = Date.now();
    this.refillPerSec = 40;
    this.onText = () => {};
    this.onClose = () => {};
    socket.setNoDelay(true);
    socket.on('data', (d) => this._data(d));
    socket.on('close', () => this._closed());
    socket.on('error', () => this._closed());
  }

  allow() {
    const now = Date.now();
    this.tokens = Math.min(200, this.tokens + ((now - this.lastRefill) / 1000) * this.refillPerSec);
    this.lastRefill = now;
    if (this.tokens < 1) return false;
    this.tokens -= 1;
    return true;
  }

  _closed() {
    if (this.closed) return;
    this.closed = true;
    try { this.socket.destroy(); } catch (_) {}
    this.onClose();
  }

  _data(chunk) {
    this.alive = true;
    this.buf = Buffer.concat([this.buf, chunk]);
    for (;;) {
      if (this.buf.length < 2) return;
      const b0 = this.buf[0], b1 = this.buf[1];
      const fin = (b0 & 0x80) !== 0, op = b0 & 0x0f, masked = (b1 & 0x80) !== 0;
      let len = b1 & 0x7f, off = 2;
      if (len === 126) { if (this.buf.length < 4) return; len = this.buf.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (this.buf.length < 10) return; len = Number(this.buf.readBigUInt64BE(2)); off = 10; }
      if (!masked || len > MAX_FRAME) return this._fail(1009);
      if (this.buf.length < off + 4 + len) return;
      const mask = this.buf.subarray(off, off + 4);
      const payload = Buffer.from(this.buf.subarray(off + 4, off + 4 + len));
      for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
      this.buf = this.buf.subarray(off + 4 + len);
      if (op === 0x8) { this._fail(1000); return; }
      if (op === 0x9) { this._write(0xA, payload); continue; }
      if (op === 0xA) continue;
      if (op === 0x1 || op === 0x0) {
        if (op === 0x1) this.frag = [];
        if (!this.frag) return this._fail(1002);
        this.frag.push(payload);
        if (this.frag.reduce((a, b) => a + b.length, 0) > MAX_FRAME) return this._fail(1009);
        if (fin) {
          const text = Buffer.concat(this.frag).toString('utf8');
          this.frag = null;
          if (!this.allow()) return this._fail(1008);
          this.onText(text);
          if (this.closed) return;
        }
      } else {
        return this._fail(1003); // binário não é usado
      }
    }
  }

  _write(op, payload) {
    if (this.closed) return;
    let head;
    if (payload.length < 126) head = Buffer.from([0x80 | op, payload.length]);
    else if (payload.length < 65536) { head = Buffer.alloc(4); head[0] = 0x80 | op; head[1] = 126; head.writeUInt16BE(payload.length, 2); }
    else { head = Buffer.alloc(10); head[0] = 0x80 | op; head[1] = 127; head.writeBigUInt64BE(BigInt(payload.length), 2); }
    try { this.socket.write(Buffer.concat([head, payload])); } catch (_) { this._closed(); }
  }

  send(text) { this._write(0x1, Buffer.from(text, 'utf8')); }
  ping() { this._write(0x9, Buffer.alloc(0)); }

  _fail(code) { this.close(code); }

  close(code = 1000) {
    if (this.closed) return;
    const p = Buffer.alloc(2);
    p.writeUInt16BE(code, 0);
    this._write(0x8, p);
    setTimeout(() => this._closed(), 200).unref();
  }
}

function upgrade(req, socket) {
  const key = req.headers['sec-websocket-key'];
  if (!key || String(req.headers.upgrade || '').toLowerCase() !== 'websocket') {
    socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
    return null;
  }
  const accept = crypto.createHash('sha1').update(key + GUID).digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n' +
    'Sec-WebSocket-Accept: ' + accept + '\r\n\r\n'
  );
  return new Ws(socket);
}

function reject(socket, code, msg) {
  socket.end('HTTP/1.1 ' + code + ' ' + msg + '\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
}

// ---------------------------------------------------------------- salas

function newRoomId() {
  for (let tries = 0; tries < 50; tries++) {
    let id = '';
    for (let i = 0; i < 4; i++) id += ROOM_ALPHABET[crypto.randomInt(ROOM_ALPHABET.length)];
    if (!rooms.has(id)) return id;
  }
  return null;
}

function closeRoom(room, reason) {
  if (!rooms.has(room.id)) return;
  rooms.delete(room.id);
  bump(ipRooms, room.ip, -1);
  for (const c of room.clients.values()) c.close(1001);
  room.clients.clear();
  if (reason) console.log('sala', room.id, 'encerrada:', reason);
}

function handleHost(req, socket) {
  const ip = clientIp(req);
  if (rooms.size >= MAX_ROOMS || (ipRooms.get(ip) || 0) >= MAX_ROOMS_PER_IP) return reject(socket, 429, 'Too Many Requests');
  const ws = upgrade(req, socket);
  if (!ws) return;
  ws.refillPerSec = 400;
  ws.tokens = 400;
  let room = null;
  const timer = setTimeout(() => { if (!room) ws.close(1008); }, 10000);
  ws.onText = (text) => {
    let m;
    try { m = JSON.parse(text); } catch (_) { return ws.close(1003); }
    if (!m || typeof m !== 'object') return;
    if (!room) {
      if (m.t !== 'open') return ws.close(1002);
      const name = cleanName(m.name);
      const id = name && newRoomId();
      if (!id) return ws.close(1008);
      room = { id, name, locked: m.locked === true, players: 1, open: true, host: ws, clients: new Map(), ip, created: Date.now() };
      rooms.set(id, room);
      bump(ipRooms, ip, 1);
      clearTimeout(timer);
      ws.send(JSON.stringify({ t: 'opened', room: id }));
      console.log('sala', id, 'criada por', name);
      return;
    }
    if (m.t === 'info') {
      room.players = Math.max(0, Math.min(99, m.players | 0));
      room.open = m.open === true;
    } else if (m.t === 'data') {
      const c = room.clients.get(m.cid);
      if (c && typeof m.d === 'string' && m.d.length <= 16384) c.send(m.d);
    } else if (m.t === 'kick') {
      const c = room.clients.get(m.cid);
      if (c) c.close(1000);
    }
  };
  ws.onClose = () => { clearTimeout(timer); if (room) closeRoom(room, 'anfitrião saiu'); };
  watchdog(ws);
}

function handleJoin(req, socket, id) {
  const room = rooms.get(id);
  const ip = clientIp(req);
  if (!room || !room.open) return reject(socket, 404, 'Not Found');
  if (room.clients.size >= MAX_CLIENTS_PER_ROOM || (ipClients.get(ip) || 0) >= MAX_CLIENTS_PER_IP) return reject(socket, 429, 'Too Many Requests');
  const ws = upgrade(req, socket);
  if (!ws) return;
  const cid = nextCid++;
  room.clients.set(cid, ws);
  bump(ipClients, ip, 1);
  room.host.send(JSON.stringify({ t: 'join', cid }));
  ws.onText = (text) => {
    if (text.length > 16384) return ws.close(1009);
    room.host.send(JSON.stringify({ t: 'data', cid, d: text }));
  };
  ws.onClose = () => {
    bump(ipClients, ip, -1);
    if (room.clients.delete(cid) && !room.host.closed) room.host.send(JSON.stringify({ t: 'left', cid }));
  };
  watchdog(ws);
}

const sockets = new Set();
function watchdog(ws) { sockets.add(ws); const f = ws.onClose; ws.onClose = () => { sockets.delete(ws); f(); }; }
setInterval(() => {
  for (const ws of sockets) {
    if (!ws.alive) { ws._closed(); continue; }
    ws.alive = false;
    ws.ping();
  }
}, 20000).unref();

// ---------------------------------------------------------------- HTTP

const server = http.createServer((req, res) => {
  const url = (req.url || '').split('?')[0];
  if (req.method === 'GET' && url === '/rooms') {
    const list = [...rooms.values()].filter((r) => r.open).slice(0, 100)
      .map((r) => ({ id: r.id, name: r.name, players: r.players, locked: r.locked }));
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': '*' });
    return res.end(JSON.stringify(list));
  }
  if (url === '/health' || url === '/') {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('Cartas do Caos relay ok\n');
  }
  res.writeHead(404);
  res.end();
});

server.on('upgrade', (req, socket) => {
  socket.on('error', () => {});
  const url = (req.url || '').split('?')[0];
  if (url === '/host') return handleHost(req, socket);
  const m = /^\/join\/([A-Z0-9]{4})$/.exec(url);
  if (m) return handleJoin(req, socket, m[1]);
  reject(socket, 404, 'Not Found');
});

if (require.main === module) {
  server.listen(PORT, () => console.log('Relay ouvindo na porta', PORT));
}
module.exports = { server, rooms };
