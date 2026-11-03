<!-- Gerado por `scripts/docs/generate_manager_guides.py` a partir de `docs/content/manager-guides.yml`, `NPDevManager/ui/navigation.json` e `NPDevManager/ui/help-glossary.json`. Edite esses, não este arquivo. -->

# Seu primeiro app em 10 minutos

*[English](FIRST_APP_IN_10_MINUTES.md)*

Uns dez minutos da sua atenção. Os downloads e a primeira compilação podem fazer você esperar mais
numa conexão lenta (até uma hora no pior caso); o Manager mostra o progresso o tempo todo, então dá
para fazer outra coisa enquanto isso.

Você precisa de: Windows 10 ou 11 (64 bits) ou Linux de 64 bits com interface gráfica, **5 GB livres**
no disco, e internet. Você **não** precisa de Java, Python nem de nenhuma ferramenta de programação:
o Manager traz as suas.

## 1. Instale o Manager

Baixe um arquivo da [versão mais recente](https://github.com/MarceloGiazzon/NPDevGeneral/releases/latest):
no Windows, o que termina em `_x64-setup.exe`; no Linux, o que termina em `_amd64.AppImage`. Abra-o.

O Windows provavelmente vai dizer **"O Windows protegeu o computador"**: o Manager ainda não tem
assinatura digital. Clique em *Mais informações* e depois em *Executar assim mesmo*. No Linux, marque
o AppImage como executável (botão direito › Propriedades) e dê um duplo clique.

## 2. Escolha o idioma

O Manager abre em **Preparar**. O seletor de idioma fica no canto superior direito; escolha
**Português**. A navegação muda na hora; os botões dentro das telas ainda estão em inglês.

## 3. Instale o que falta

Abra **Preparar › Instalar o que falta** e aperte os botões **na ordem**, esperando cada um terminar:

1. **Install private JDK**: o Java (JDK 17), cerca de 180 MB.
2. **Install private Python**: só se o computador não tiver nenhum; senão a tela já diz que encontrou o seu.
3. **Refresh list**, mantenha a versão mais nova, **Download this version**: o próprio NPDev.
4. **Run setup**: costuma levar um minuto, às vezes uns dez.

Não sabe o que é algum deles? Veja a tabela em [O NPDev Manager, em palavras simples](MANAGER_GUIDE.pt.md).

## 4. Verifique o computador

Abra **Preparar › Verificar o computador** e aperte **Re-check**. Cada linha tem uma marca:

- **✓** tudo certo;
- **!** opcional, normalmente pode ignorar (um **!** ao lado de Docker ou git é esperado);
- **✗** precisa ser resolvido antes; a própria linha diz como.

Quando não sobrar nenhum **✗**, o computador está pronto. Você nunca mais precisa repetir os passos 3 e 4.

## 5. Crie o seu app

Abra **Meus apps › Todos os apps**. Em **New app**:

- **Name**: um nome curto com letras, números e hífens, como `minha-loja`;
- **Folder**: onde guardá-lo, por exemplo a sua pasta Documentos (**Browse…** ajuda);
- **Database**: deixe **H2Local**. Não precisa instalar nada e guarda os seus dados.

Aperte **Create**. O NPDev escreve um primeiro modelo (`model.json`), que já funciona, numa pasta nova. Esse
arquivo é o seu app.

## 6. Inicie o app

Abra **Meus apps › Iniciar e parar**, escolha o seu app em **App**, deixe **Port** em `8080` e aperte **▶ Run**.

A primeira vez constrói a aplicação inteira, então leva alguns minutos; o log abaixo mostra cada
etapa. Termina quando uma linha diz **ready in …** com um endereço como `http://localhost:8080`.

## 7. Abra e entre

Clique no link **App:**. A página que abre pede uma **API Key**: o log a mostrou logo depois da linha
**ready in …**, como `X-Api-Key: …`. Copie o texto depois de `X-Api-Key:`, cole no campo **API Key**
e aperte **Open business screens** para ver as telas do seu app.

Pronto: uma aplicação funcionando, com banco de dados, telas para listar e editar os seus dados, e login.

## 8. Mude alguma coisa

Deixe o app rodando. Abra o `model.json` na pasta do seu app, acrescente um campo a um dos
conceitos (Cliente, Pedido...), e salve. **Meus apps › Iniciar e parar** percebe a mudança e a aplica em segundos (ou cerca de
um minuto quando precisa recompilar); atualize o navegador para ver. Se você salvar um erro, o log diz exatamente onde, e o app continua
rodando na última versão que funcionava.

Prefere descrever a mudança com palavras? Configure uma IA em **Configurações › Provedores de IA** e use
**Meus apps › Perguntar à IA**.

## Próximos passos

- **Atividade**: todos os apps deste computador, num relance.
- **Meus apps › Compartilhar**: dê ao seu app um endereço que outras pessoas possam abrir.
- [O NPDev Manager, em palavras simples](MANAGER_GUIDE.pt.md): o que significa cada lugar e cada palavra do Manager.
