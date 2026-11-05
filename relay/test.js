'use strict';
// Teste do relay: node test.js  (requer Node 22+, que traz WebSocket embutido)
const assert = require('assert');
const { server } = require('./server');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function open(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    ws.inbox = [];
    ws.onmessage = (e) => ws.inbox.push(e.data);
    ws.onopen = () => resolve(ws);
    ws.onerror = () => reject(new Error('falha ao abrir ' + url));
  });
}
async function next(ws, ms = 2000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (ws.inbox.length) return ws.inbox.shift(); await sleep(10); }
  throw new Error('timeout esperando mensagem');
}

(async () => {
  await new Promise((r) => server.listen(0, r));
  const port = server.address().port;
  const base = `127.0.0.1:${port}`;
  const rooms = async () => (await fetch(`http://${base}/rooms`)).json();

  assert.deepStrictEqual(await rooms(), []);
  const host = await open(`ws://${base}/host`);
  host.send(JSON.stringify({ t: 'open', name: 'Maria', locked: true }));
  const opened = JSON.parse(await next(host));
  assert.strictEqual(opened.t, 'opened');
  assert.match(opened.room, /^[A-Z0-9]{4}$/);
  const id = opened.room;
  console.log('ok - sala criada', id);

  host.send(JSON.stringify({ t: 'info', players: 3, open: true }));
  await sleep(100);
  let list = await rooms();
  assert.deepStrictEqual(list, [{ id, name: 'Maria', players: 3, locked: true }]);
  console.log('ok - sala aparece na lista pública');

  const c1 = await open(`ws://${base}/join/${id}`);
  const j = JSON.parse(await next(host));
  assert.strictEqual(j.t, 'join');
  c1.send('{"t":"hello","nick":"Ana","pass":"1234"}');
  const d = JSON.parse(await next(host));
  assert.deepStrictEqual([d.t, d.cid, d.d], ['data', j.cid, '{"t":"hello","nick":"Ana","pass":"1234"}']);
  host.send(JSON.stringify({ t: 'data', cid: j.cid, d: '{"t":"welcome","id":2}' }));
  assert.strictEqual(await next(c1), '{"t":"welcome","id":2}');
  console.log('ok - mensagens passam nos dois sentidos');

  // um convidado não consegue falar com outro nem se passar por outro cid
  const c2 = await open(`ws://${base}/join/${id}`);
  const j2 = JSON.parse(await next(host));
  host.send(JSON.stringify({ t: 'data', cid: j2.cid, d: 'so-para-c2' }));
  assert.strictEqual(await next(c2), 'so-para-c2');
  await sleep(100);
  assert.strictEqual(c1.inbox.length, 0);
  console.log('ok - cada convidado só recebe o que é dele');

  host.send(JSON.stringify({ t: 'kick', cid: j2.cid }));
  await new Promise((r) => { c2.onclose = r; });
  const left = JSON.parse(await next(host));
  assert.deepStrictEqual([left.t, left.cid], ['left', j2.cid]);
  console.log('ok - expulsar convidado avisa o anfitrião');

  host.send(JSON.stringify({ t: 'info', players: 3, open: false }));
  await sleep(100);
  assert.deepStrictEqual(await rooms(), []);
  await assert.rejects(open(`ws://${base}/join/${id}`));
  console.log('ok - sala fechada sai da lista e não aceita entrada');
  host.send(JSON.stringify({ t: 'info', players: 3, open: true }));

  await assert.rejects(open(`ws://${base}/join/ZZZZ`));
  console.log('ok - sala inexistente é recusada');

  const closed = new Promise((r) => { c1.onclose = r; });
  host.close();
  await closed;
  await sleep(100);
  assert.deepStrictEqual(await rooms(), []);
  console.log('ok - anfitrião saiu: convidados desconectados e sala removida');

  const bad = await open(`ws://${base}/host`);
  bad.send(JSON.stringify({ t: 'open', name: 'x', locked: false }));
  await new Promise((r) => { bad.onclose = r; });
  console.log('ok - nome inválido é recusado');

  console.log('\nTODOS OS TESTES DO RELAY PASSARAM');
  process.exit(0);
})().catch((e) => { console.error('FALHOU:', e); process.exit(1); });
