# Relay do Cartas do Caos

Servidor mínimo (Node 18+, **sem dependências**) que permite jogar pela internet:
mantém a **lista pública de salas** e **repassa as mensagens** entre o celular do anfitrião e os convidados.
O jogo em si continua rodando no celular do anfitrião; o relay não guarda nada em disco.

## Rodar localmente
    node server.js          # porta 8080 (ou PORT=...)
    node test.js            # testes (Node 22+)

## Publicar (precisa de HTTPS/WSS)
Qualquer hospedagem que rode Node ou Docker e entregue HTTPS serve. O app usa `wss://`, que
normalmente é fornecido pela própria hospedagem (ela termina o TLS e repassa para a porta 8080).

- **Render / Railway / Koyeb**: crie um serviço web a partir desta pasta (`relay/`) com o `Dockerfile`
  (ou "Node": comando de início `node server.js`). A URL pública será algo como `https://cartas-relay.onrender.com`.
- **Fly.io**: `fly launch` nesta pasta (usa o Dockerfile) e `fly deploy`.
- **VPS próprio**: `node server.js` atrás de um proxy reverso com TLS (Caddy, nginx) apontando para a porta 8080.
  O proxy precisa repassar o `Upgrade: websocket` e o cabeçalho `X-Forwarded-For`.

Planos gratuitos costumam "dormir" sem uso; a primeira conexão do dia pode demorar alguns segundos.

## No app
Menu inicial → **Servidor online** → cole a URL (`https://...` ou `wss://...`).

## Limites embutidos
300 salas no total, 3 salas e 24 conexões por IP, 24 convidados por sala, mensagens de até 16 KB,
limite de mensagens por segundo e desconexão automática de conexões mortas.
