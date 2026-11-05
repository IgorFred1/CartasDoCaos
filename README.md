# Cartas do Caos (Android)

**Cartas do Caos** é um jogo de cartas de humor desenvolvido para Android, pensado para reunir amigos e proporcionar partidas cheias de criatividade, respostas inesperadas e muita diversão.

O jogo suporta de **2 a 20 jogadores** e pode ser jogado tanto por Wi-Fi/rede local quanto por Bluetooth ou online, tudo em modo retrato.

O baralho foi desenvolvido especialmente para o jogo e conta com mais de **700 cartas pretas e 3.000 cartas brancas**, armazenadas em `app/src/main/assets/deck.json`. As cartas foram organizadas, revisadas e convertidas a partir do arquivo original `baralho_jogo_2600_cartas.json`.

## Como compilar e executar

Você pode executar o projeto diretamente pelo Android Studio ou compilá-lo pelo terminal.

**Pelo Android Studio:**
1. Abra a pasta `CartasJogo` no Android Studio (Koala ou versão mais recente).
2. Aguarde a sincronização do projeto.
3. Clique em **Run** para executar o aplicativo.

**Pelo terminal:**

Com o Android SDK instalado, execute:

```bash
./gradlew assembleDebug
```

O APK será gerado em:

`app/build/outputs/apk/debug/app-debug.apk`

**Requisitos:**
- JDK 17.
- Android SDK 34.
- Android 8.0 (API 26) ou superior.

O aplicativo não depende de bibliotecas externas.

## Como jogar

### Menu inicial

Ao iniciar o jogo, cada jogador pode escolher um apelido de 3 a 15 caracteres e, opcionalmente, adicionar uma foto de perfil pela galeria do celular.

O menu também permite criar salas, entrar em partidas existentes ou consultar as instruções do jogo. O apelido e a foto ficam salvos localmente no aparelho.

### Criando uma sala

O jogador que cria a sala se torna o anfitrião e pode personalizar as configurações da partida:

- **Conexão:** Wi-Fi, Bluetooth ou Online.
- **Senha:** sala aberta ou protegida por uma senha de 4 dígitos gerada pelo jogo.
- **Expansões:** Família, Picante e Humor Negro, podendo selecionar uma ou várias.
- **Jogadores:** limite configurável de 2 a 20 participantes.
- **Rodadas:** de 3 a 15 rodadas.
- **Tempo para jogar:** 15, 30, 45, 60 ou 90 segundos.
- **Tempo de exibição das respostas:** de 10 a 30 segundos.
- **Gerenciamento da sala:** o anfitrião pode expulsar jogadores antes do início da partida.

Nas salas protegidas por senha, o anfitrião compartilha o código com os amigos. Nas salas abertas, qualquer jogador pode entrar.

### Entrando em uma sala

**Por Wi-Fi:**

As salas criadas na mesma rede local aparecem automaticamente em uma lista. O anfitrião anuncia a sala por meio de broadcast UDP, dispensando a necessidade de informar o endereço IP manualmente.

Basta selecionar a sala e inserir a senha, caso exista.

Se a rede bloquear a descoberta automática, também é possível utilizar a opção de inserir o endereço manualmente.

**Por Bluetooth:**

O jogo identifica os dispositivos que possuem uma sala ativa, ignorando outros aparelhos Bluetooth. Basta selecionar a sala desejada e informar a senha, se necessário.

### Durante a partida

O funcionamento das rodadas é simples:

1. O jogo sorteia uma carta preta com uma situação ou pergunta.
2. Cada jogador escolhe uma das suas 7 cartas brancas para responder.
3. As respostas são apresentadas individualmente e de forma anônima.
4. Os outros jogadores avaliam cada resposta com notas de 1 a 5.
5. A pontuação é calculada pela soma das notas recebidas.

O objetivo é conseguir as melhores avaliações e terminar a partida com a maior pontuação.

### Pausar ou sair

Durante a partida, existe um botão de pausa no canto superior direito da tela, com as opções de continuar jogando ou sair da partida.

A pausa afeta somente o aparelho do próprio jogador. Caso o anfitrião saia, a sala é encerrada.

### Ranking final

Ao término da partida, o jogo apresenta um ranking com a pontuação de todos os participantes, permitindo conferir quem teve as melhores respostas.

## Regras e mecânicas

O jogo possui algumas regras internas para manter as partidas organizadas e equilibradas:

- As cartas pretas são sorteadas automaticamente pelo jogo.
- As cartas brancas são retiradas de um único baralho embaralhado, evitando repetições durante a mesma partida.
- Nenhum jogador pode avaliar a própria resposta.
- Quando todos terminam de avaliar uma resposta, a próxima é apresentada após aproximadamente 1 segundo.
- Jogadores que abandonam a partida continuam aparecendo no ranking, identificados como desistentes.
- Se restarem menos de 2 jogadores, a partida é encerrada.

## Estrutura do projeto

O código foi organizado em módulos para facilitar a manutenção e a evolução do jogo.

- **`core/`**: contém as regras, o sistema de cartas, o protocolo de comunicação e o gerenciamento dos estados das telas. Desenvolvido em Kotlin puro e testado no PC.
- **`net/`**: responsável pelas conexões via TCP (Wi-Fi) e RFCOMM (Bluetooth), além do gerenciamento das sessões de anfitrião e convidados.
- **`ui/`**: reúne as telas e os elementos visuais do aplicativo, criados diretamente pelo código Kotlin, sem utilização de layouts XML.
- **`tests/`**: contém os testes automatizados do projeto.

Para executar os testes, utilize:

```bash
tests/run-tests.sh
```

É necessário ter o `kotlinc` instalado.

## Modo Online

Além das partidas por rede local e Bluetooth, o Cartas do Caos também permite jogar pela internet com pessoas que não estão na mesma rede.

Nesse modo, a partida continua sendo executada no celular do anfitrião. Como os celulares não recebem conexões diretas da internet de forma convencional, o projeto utiliza um pequeno servidor intermediário chamado **Relay**, desenvolvido em Node.js e sem dependências externas.

O servidor é responsável por manter uma lista pública de salas disponíveis e encaminhar as mensagens entre os participantes.

**Como configurar:**

1. Publique o conteúdo da pasta `relay/` em um serviço de hospedagem. As instruções estão disponíveis em `relay/README.md`, com opções como Render, Railway, Fly.io ou VPS.
2. Em cada celular, acesse o menu inicial e selecione **Servidor online**.
3. Informe a URL do servidor Relay.
4. Para criar uma partida, selecione a conexão **Online**.
5. Os demais jogadores podem acessar **Entrar em sala**, selecionar **Online** e escolher a sala desejada na lista.

Salas protegidas por senha são identificadas com um cadeado na lista de partidas.

## Fotos de perfil

O jogo permite que cada participante utilize uma foto de perfil personalizada.

A imagem é selecionada diretamente pela galeria do Android, sem necessidade de solicitar permissão geral de acesso às fotos.

Para otimizar o desempenho e reduzir o consumo de dados, o aplicativo:
- Recorta a imagem em formato quadrado.
- Redimensiona para 96x96 pixels.
- Comprime a imagem em JPEG, geralmente entre 3 e 9 KB.
- Mantém a imagem original armazenada apenas no aparelho do jogador.

Ao entrar em uma sala, a foto otimizada é compartilhada com os demais participantes por meio do anfitrião ou do servidor Relay, sem ser armazenada no servidor.

As fotos aparecem no lobby, nos resultados das rodadas e no ranking final. Caso o jogador não utilize uma imagem, o jogo exibe a inicial do seu apelido com uma cor de identificação.

**Controle de privacidade:**

O anfitrião pode decidir se as fotos de perfil serão permitidas ou desativadas ao criar uma sala.

Em partidas públicas online, desativar essa opção pode ajudar a evitar o recebimento de imagens inesperadas.

O aplicativo também valida os arquivos recebidos, aceitando apenas imagens JPEG pequenas e válidas.

## Novidades e funcionalidades recentes

O projeto continua recebendo melhorias e novas funcionalidades. Entre os destaques estão:

### Novas expansões

- **Humor Negro (18+):** expansão com um baralho reformulado e conteúdo de humor mais pesado. Sua ativação exige a aceitação de um termo de consentimento que informa a classificação etária, os termos de uso e a natureza humorística do conteúdo. Também existe a opção de não exibir esse aviso novamente. Sem a confirmação, a expansão não é ativada. Jogadores que entram em salas com essa expansão também precisam aceitar os termos.
- **Pesadelo:** nova expansão temática inspirada em Halloween, contos de terror, filmes e cenas macabras.

### Melhorias na partida

- **Tempo de exibição das respostas:** agora é possível configurar o tempo de cada resposta entre 10 segundos e 1 minuto e 30 segundos, com intervalos de 5 segundos.
- **Sistema de expulsão:** jogadores expulsos ficam impedidos de visualizar e entrar novamente na mesma sala durante 3 minutos.

### Suporte a vários idiomas

A interface do jogo acompanha automaticamente o idioma configurado no sistema do aparelho.

Atualmente, são suportados:
- Português.
- Inglês.
- Espanhol.
- Francês.
- Alemão.
- Italiano.

Caso o aparelho utilize um idioma não suportado, o jogo utiliza o inglês como padrão.

As traduções da interface ficam armazenadas em `app/src/main/assets/i18n/`.

As cartas também possuem traduções próprias, disponíveis em `app/src/main/assets/cards/`. Dessa forma, cada jogador visualiza as cartas no idioma configurado em seu próprio aparelho, mesmo quando está jogando com pessoas que utilizam outros idiomas.

Internamente, o jogo continua utilizando o português como idioma principal. As traduções são aplicadas apenas na exibição dos textos para cada participante.

## Sobre o projeto

O Cartas do Caos é um projeto em constante desenvolvimento, criado com o objetivo de oferecer uma experiência divertida, acessível e cheia de possibilidades para jogar com amigos.

Mais do que um simples jogo de cartas, a proposta é reunir diferentes formas de conexão, personalização e interação em um único aplicativo.

Novas melhorias, expansões e funcionalidades continuam sendo desenvolvidas para tornar cada partida ainda mais divertida e imprevisível.
