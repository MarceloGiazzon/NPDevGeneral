<!-- Gerado por `scripts/docs/generate_manager_guides.py` a partir de `docs/content/manager-guides.yml`, `NPDevManager/ui/navigation.json` e `NPDevManager/ui/help-glossary.json`. Edite esses, não este arquivo. -->

# O NPDev Manager, em palavras simples

*[English](MANAGER_GUIDE.md)*

**Para quem:** quer criar um app com o NPDev e nunca escreveu Java, Python ou SQL. Você não precisa
saber o que são. Este guia diz *para que serve* cada coisa, para você saber o que está instalando e
por quê.

O NPDev constrói aplicações web a partir de uma descrição. Você descreve o seu app (o que ele guarda,
quem pode fazer o quê); o NPDev escreve, compila e roda o programa. O **NPDev Manager** é a janela
onde você faz tudo isso, sem terminal.

Com pressa? Vá direto para [Seu primeiro app em 10 minutos](FIRST_APP_IN_10_MINUTES.pt.md).

## Os quatro lugares

A barra no alto do Manager tem quatro lugares. Cada um responde a uma pergunta. Dentro de um lugar,
uma segunda linha mostra as telas dele, com uma frase dizendo para que ele serve.

| Lugar | Para que serve | Telas dentro dele |
|---|---|---|
| 🔧 **Preparar** | Deixe este computador pronto para criar apps. Você faz isso uma vez só. | Verificar o computador, Instalar o que falta |
| ▦ **Meus apps** | Crie um app, inicie, altere e compartilhe. | Todos os apps, Iniciar e parar, Editar, Perguntar à IA, Dados, Compartilhar, Testes no navegador (testes), Testes de regras (testes), Impacto (avançado), Pacotes (avançado) |
| ◉ **Atividade** | Tudo o que está rodando neste computador, num relance. | Apps em execução |
| ⚙ **Configurações** | Provedores de IA, senhas, versões do NPDev. Você ajusta isso uma vez. | Provedores de IA, Senhas e chaves, Versões do NPDev, Este computador, Verificações da plataforma (avançado), Avaliações de IA (avançado) |

## Cinco caminhos pelo Manager

1. **A primeira vez.** **Preparar › Verificar o computador**, depois **Preparar › Instalar o que falta**, depois
   **Meus apps › Todos os apps** para criar um app, e **Meus apps › Iniciar e parar** para iniciá-lo e abri-lo no navegador.
   É o [Seu primeiro app em 10 minutos](FIRST_APP_IN_10_MINUTES.pt.md).
2. **No dia a dia.** **Meus apps**: altere o app em **Meus apps › Editar** ou **Meus apps › Perguntar à IA**,
   e inicie-o em **Meus apps › Iniciar e parar**.
3. **Mostrar para alguém.** Teste em **Meus apps › Testes no navegador**, depois **Meus apps › Compartilhar**.
4. **Algo deu errado.** **Atividade** mostra todos os apps deste computador: qual está rodando,
   qual parou, qual tem problema, e os logs de cada um.
5. **Uma vez e pronto.** **Configurações**: provedores de IA, senhas e chaves, versões do NPDev.

## O que o NPDev instala no seu computador, e por quê

Tudo abaixo da primeira tabela é opcional. Nada é instalado sem você apertar um botão, e as cópias
privadas que o Manager baixa não mexem em mais nada do seu computador.

**Instalado uma vez, usado por tudo**

| O quê | O que é | Por que importa | Se faltar ou falhar |
|---|---|---|---|
| **Java (JDK 17)** | O motor em que o seu app gerado roda. | O NPDev transforma o seu modelo num programa Java. O Java é quem executa esse programa, como um player toca um arquivo de música. | Nada pode ser construído nem iniciado. **Preparar › Instalar o que falta** baixa uma cópia privada (cerca de 180 MB) que não mexe em mais nada do seu computador. |
| **Python** | Executa as ferramentas do próprio NPDev (o comando npdev). | As verificações, o lançador do gerador e o Monitor são pequenos programas em Python. Você nunca escreve Python. | O Manager não consegue perguntar nada ao NPDev e a maioria das telas fica vazia. **Preparar › Instalar o que falta** adiciona uma cópia privada só se o computador não tiver nenhuma. |
| **NPDev e a preparação dele** | O próprio NPDev, baixado como uma versão e depois preparado uma vez neste computador. | A preparação constrói ou baixa as peças com que todo app gerado é montado. Depois disso, criar um app é rápido. | Não dá para gerar apps, e **Preparar › Verificar o computador** mostra 'NPDev jars: not staged'. A preparação costuma levar um minuto, às vezes uns dez; as linhas de progresso mostram que está andando. |

**Onde um app guarda os dados (você escolhe um por app)**

| O quê | O que é | Por que importa | Se faltar ou falhar |
|---|---|---|---|
| **H2Local** | Um banco de dados pequeno que vive num arquivo ao lado do seu app. É o padrão, e é por onde começar. | Nada para instalar, nada para configurar. Os dados continuam lá quando o app para. | Nada para instalar. Se ele se recusar a iniciar, provavelmente outra cópia do mesmo app ainda está rodando e segurando o arquivo. |
| **PostgreSQL, MySQL, SQL Server** | Servidores de banco de dados completos, a escolha comum para uso real ou para o banco que uma empresa já tem. | Muitas pessoas podem usar o app ao mesmo tempo, e os dados ficam separados do app. | Só são necessários se você escolher um deles. O NPDev os roda pelo Docker, ou se conecta a um que você já tenha (marque 'This server is mine' ao criar o app). Use Test connection para ver exatamente o que falha. |

**Opcional: só alguns recursos precisam**

| O quê | O que é | Por que importa | Se faltar ou falhar |
|---|---|---|---|
| **Docker** | Um programa que roda outros programas em caixas isoladas. | O NPDev o usa para iniciar um banco PostgreSQL, MySQL ou SQL Server para o seu app sem instalar nenhum. | Tudo bem. Um '!' ao lado de Docker é esperado. Escolha H2Local e tudo funciona. |
| **git** | Guarda um histórico de cada versão do modelo do seu app. | O seu modelo é o seu app. Com o git você sempre pode voltar a uma versão que funcionava. | Tudo bem. O Manager não o instala e nada depende dele; o seu app apenas fica sem histórico até você instalá-lo. |
| **Testes no navegador (ScrapForAI)** | Um navegador-robô que clica pelo seu app para ver se ele continua funcionando. | Testa se uma pessoa conseguiria mesmo usar as telas, e não só se o programa inicia. | Opcional. Sem ele, **Meus apps › Testes no navegador** não roda; todo o resto funciona. |
| **Túnel (cloudflared)** | Um pequeno programa que dá a este computador um endereço público temporário na web. | Para outra pessoa abrir o seu app numa demonstração rápida, sem alugar um servidor. | Opcional. Só é necessário para o nível 1 de **Meus apps › Compartilhar**, 'Shared by link'. |

## Palavras que você vai ver

| O quê | O que é | Por que importa | Se faltar ou falhar |
|---|---|---|---|
| **Modelo** | A descrição do seu app (o arquivo model.json): o que ele guarda, quem pode fazer o quê, quais telas existem. | Você, ou uma IA, escreve o modelo; o NPDev escreve o programa. O modelo é a parte que vale guardar: o programa sempre pode ser gerado de novo a partir dele. | Todo app começa de um: ao criar um app, o NPDev escreve um primeiro modelo que já funciona. |
| **Conceito** | Um tipo de coisa que o seu app guarda, como Cliente, Pedido ou Conserto. | Cada conceito vira uma tela de lista, um formulário, uma tabela no banco e uma API, tudo gerado. | Um app sem conceitos não tem nada para mostrar. |
| **Porta** | O 'número da porta' em que um app atende, como em localhost:8080. | Dois programas não podem atender na mesma porta, então cada app em execução precisa da sua. | Se o app compila mas não inicia, a porta está ocupada (muitas vezes por uma cópia antiga do mesmo app). Pare-a em **Atividade**, ou escolha outro número de porta. |
| **Chave de acesso (API key)** | A chave que abre o seu app novo, criada na primeira vez que ele inicia. | Um app novo ainda não tem usuários, então alguém precisa conseguir entrar. O log de execução a mostra uma vez, como 'X-Api-Key: …', e ela fica guardada no arquivo secrets/api-key.env do app. | Perdeu o log? Abra secrets/api-key.env na pasta do app. A linha diz NPDEV_AUTH_API_KEYS=<chave>=dev:developer:admin; a chave é o trecho entre os dois sinais '='. |
| **Spring Boot** | O framework Java pronto sobre o qual todo app gerado é construído. | Ele dá a cada app um servidor web, login e acesso ao banco de dados de fábrica, e assim o NPDev só gera o que é específico do seu app. | Nunca é instalado à parte: vem dentro de cada app gerado. Se um erro menciona Spring, a causa costuma ser o Java, a porta ou o banco de dados. |
| **Gradle** | A ferramenta que compila o seu app em algo que roda. | Construir um app Java é compilar muitos arquivos e buscar bibliotecas. A primeira compilação baixa bastante coisa e leva alguns minutos; as seguintes são rápidas. | Vem com cada app. Uma primeira compilação que falha durante o download costuma ser problema de rede, VPN ou proxy. |

## Quando algo dá errado

- **"O Windows protegeu o computador"** ao instalar: esperado, o Manager ainda não tem assinatura
  digital. Clique em *Mais informações* e depois em *Executar assim mesmo*.
- **Um download falha:** quase sempre é rede de empresa ou VPN bloqueando o GitHub. Tente outra rede.
- **"NPDev jars: not staged"** em **Preparar › Verificar o computador**: a preparação não terminou. O botão
  **Fix this** dessa linha leva você a **Preparar › Instalar o que falta**; aperte **Run setup** lá.
- **O app compila mas não inicia:** a porta dele está ocupada. Troque **Port** em
  **Meus apps › Iniciar e parar** por outro número (8081, 8090...) e aperte **▶ Run** de novo.
- **Qualquer outra coisa:** tire uma captura de tela de **Atividade** e do log, e mande junto
  com o passo em que você estava. "Não funcionou no meu computador" é o relato mais útil que existe.

## Para ir além

- [Seu primeiro app em 10 minutos](FIRST_APP_IN_10_MINUTES.pt.md): do zero a um app rodando, passo a passo.
- [MANAGER.md](../MANAGER.md) (em inglês): todas as telas e configurações, em detalhe.
- [YOUR_FIRST_APP.md](../YOUR_FIRST_APP.md) (em inglês): escrever o seu próprio modelo à mão, quando quiser controle total.

Os botões *dentro* das telas ainda aparecem em inglês; a navegação já segue o idioma escolhido no
seletor do alto do Manager, e o resto está sendo traduzido.
