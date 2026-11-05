# Cartas do Caos (Android)

Jogo de cartas de humor para 2 a 20 jogadores, em modo retrato, jogável por **Wi-Fi / rede local** ou **Bluetooth**.
O baralho é o do arquivo `baralho_jogo_2600_cartas.json` (limpo, convertido e com mais de 700 cartas pretas e 3.000 brancas, todas escritas para este jogo, em `app/src/main/assets/deck.json`).

## Como compilar
1. Abra a pasta `CartasJogo` no **Android Studio** (Koala ou mais novo) e clique em *Run*; ou
2. No terminal, com o Android SDK instalado: `./gradlew assembleDebug`
   (APK em `app/build/outputs/apk/debug/app-debug.apk`).

Requisitos: JDK 17, Android SDK 34. O app roda no Android 8.0 (API 26) ou mais novo e não usa bibliotecas externas.

## Como jogar
- **Menu inicial**: apelido (3 a 15 caracteres) e **foto de perfil opcional** (escolhida da galeria; ambos ficam salvos no aparelho), criar sala, entrar em sala, como jogar.
- **Criar sala** (anfitrião): conexão (Wi-Fi, Bluetooth ou Online), **senha opcional** (com senha de 4 dígitos ou sala aberta), expansões (Família, Picante, Humor negro; uma ou várias), Também define o **máximo de jogadores (2 a 20)** e pode **expulsar** jogadores antes da partida começar.
  rodadas (3 a 15), tempo para jogar a carta (15/30/45/60/90 s) e tempo de cada resposta na tela (10 a 30 s).
  Com senha, o jogo gera 4 dígitos que o anfitrião passa para os amigos; sem senha, qualquer um entra.
- **Entrar**: por Wi-Fi, a sala criada na mesma rede aparece sozinha numa lista (o anfitrião a anuncia por broadcast UDP, sem IP e sem senha); toque nela e digite a senha. Se a rede bloquear broadcast, use "Não achou? Digitar o endereço";
  por Bluetooth, a lista mostra só aparelhos com uma sala do jogo (outros dispositivos Bluetooth são ignorados); escolha a sala e digite a senha.
- **Rodada**: o jogo sorteia uma carta preta; cada jogador joga uma das suas 7 cartas brancas. Depois cada resposta
  aparece, anônima, pelo tempo escolhido, e os outros jogadores dão nota de 1 a 5. Pontos = soma das notas recebidas.
- **Pausa**: botão no canto superior direito durante a partida, com as opções *Continuar* e *Sair da partida*.
  A pausa vale só para o seu aparelho; se o anfitrião sair, a sala é encerrada.
- **Ranking final** com a pontuação de todos os jogadores.

## Regras implementadas
- Cartas pretas sorteadas pelo jogo; cartas brancas saem de um monte único embaralhado, então **nenhuma se repete na partida**.
- Ninguém avalia a própria resposta; se todos já avaliaram, a próxima resposta entra após ~1 s.
- Quem sai no meio da partida continua no ranking marcado como "saiu"; com menos de 2 jogadores a partida termina.

## Estrutura
- `core/` regras, baralho, protocolo e estado da tela (Kotlin puro, testado no PC).
- `net/` conexões por linha de JSON sobre TCP (Wi-Fi) e RFCOMM (Bluetooth), sessões de anfitrião e convidado.
- `ui/` telas (Views criadas em código, sem XML de layout).
- `tests/` testes automáticos: `tests/run-tests.sh` (precisa de `kotlinc`).

## Jogar online
O jogo continua rodando no celular do anfitrião. Como celulares não aceitam conexões diretas da internet,
o app usa um servidor mínimo (`relay/`, Node, sem dependências) que mantém a **lista pública de salas** e repassa as mensagens.
1. Publique o `relay/` (veja `relay/README.md`: Render, Railway, Fly.io ou VPS).
2. Em cada celular: Menu inicial → **Servidor online** → cole a URL do relay.
3. Criar sala → **Online**; os outros abrem Entrar em sala → **Online** e escolhem a sala na lista.
Salas com senha aparecem com 🔒.

## Foto de perfil
- No menu, **Escolher da galeria** abre o seletor de imagens do Android (não pede permissão de galeria).
  O app recorta em quadrado, reduz para 96x96 e comprime em JPEG (cerca de 3 a 9 KB); a foto original nunca sai do aparelho.
- Ao entrar numa sala, a foto é enviada aos outros jogadores (pelo anfitrião ou pelo relay, sem ser guardada) e aparece
  no lobby, no resultado das rodadas e no ranking final. Sem foto, aparece a inicial do apelido numa cor.
- O **anfitrião decide**: ao criar a sala, *Fotos de perfil dos jogadores* → Permitir ou Desativar (em salas públicas
  online, desativar evita imagens inesperadas). O anfitrião recusa arquivos que não sejam JPEG pequeno válido.

## Novidades

- **Humor negro** (18+): baralho refeito, bem mais pesado. Ao escolher essa expansão aparece um termo de consentimento (maior de 18 anos, termos de uso, tudo é humor), com a caixa "Não aparecer mais". Sem tocar em "Sim", o modo não é ativado. Quem entra numa sala com Humor negro também precisa aceitar.
- **Pesadelo**: nova expansão (Halloween, contos e filmes de terror, cenas macabras).
- **Tempo de cada resposta** na tela: de 10 s a 1 min 30 (de 5 em 5 segundos).
- **Expulsão**: quem é expulso não vê a sala nas listas por 3 minutos (e não consegue entrar nela nesse prazo).
- **Idioma**: a interface segue o idioma do sistema (português, inglês, espanhol, francês, alemão e italiano; outros idiomas usam inglês). As traduções ficam em `app/src/main/assets/i18n/`. As cartas também são traduzidas (arquivos em `app/src/main/assets/cards/`): cada jogador vê as cartas no idioma do próprio sistema, mesmo jogando com pessoas que usam outro idioma. O jogo por dentro continua usando o texto em português; a tradução é só para exibição.
