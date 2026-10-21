# 📺 TV Fact Checker AI - Android TV APK

Aplicativo nativo para **Android TV, Google TV e TV Box** que realiza **checagem de fatos em tempo real** de notícias, debates e declarações da **TV aberta**, utilizando a **API da MiniMax AI** e exibindo alertas flutuantes (*overlays*) discretos no canto da tela sem interromper a programação.

---

## 💡 Como Funciona

1. **Escuta Ambiente em Segundo Plano:** Um `ForegroundService` no Android TV captura o áudio ambiente da TV através do microfone integrado da Smart TV ou microfone USB.
2. **Filtragem & Detecção de Alegações Fatuais:** O pipeline (inspirado no projeto open-source `video-db/fact-checker`) analisa os blocos de áudio e descarta conversas informais, piadas e comerciais, isolando apenas alegações que exigem checagem.
3. **Análise Crítica com MiniMax:** O texto é submetido à API da MiniMax (`MiniMax-Text-01`) para confrontar o que foi dito com dados oficiais e agências de checagem.
4. **Alerta Flutuante na TV (Overlay):** Se a informação for identificada como **FALSA** ou **ENGANOSA**, um card translúcido no estilo `TvOverlay` surge no canto superior direito da TV por 10 segundos com a correção e a fonte.

---

## 🚀 Como Compilar o APK Automaticamente (GitHub Actions)

Como o projeto já inclui a automação do **GitHub Actions** em `.github/workflows/build-apk.yml`, você não precisa instalar o Android SDK nem Java no seu computador:

### Passo a Passo para Gerar o APK:

1. **Crie um repositório no seu GitHub** (ex: `tv-fact-check-android`).
2. **Faça o push do código para o repositório:**
   ```bash
   cd "tv-fact-check-android"
   git init
   git add .
   git commit -m "feat: Android TV Fact Checker with MiniMax AI"
   git branch -M main
   git remote add origin https://github.com/SEU_USUARIO/tv-fact-check-android.git
   git push -u origin main
   ```
3. **Download do APK:**
   * Vá até a aba **Actions** no seu repositório no GitHub.
   * O fluxo **Build TV Fact Checker APK** começará a rodar e terminará em ~2 minutos.
   * Clique na execução e baixe o artefato **`TV-Fact-Checker-Debug-APK`** (ou vá em **Releases** para baixar o `app-debug.apk` direto).

---

## 📲 Como Instalar o APK na sua Android TV

Você pode usar qualquer um destes 3 métodos:

### Método 1: App "Send Files to TV" (Mais fácil)
1. Instale o app gratuito **Send Files to TV** na sua TV (pela Google Play da TV) e no seu celular.
2. Baixe o arquivo `.apk` no seu celular.
3. Abra o app no celular e envie o arquivo para a sua TV via Wi-Fi.
4. Na TV, abra o APK com um gerenciador de arquivos (como *X-plore* ou *File Commander*) e clique em **Instalar**.

### Método 2: Pendrive USB
1. Copie o arquivo `app-debug.apk` para um pendrive.
2. Conecte o pendrive na porta USB da sua TV.
3. Abra qualquer gerenciador de arquivos na TV e instale o APK.

### Método 3: Via ADB (Rede Wi-Fi)
Se você tiver o modo desenvolvedor ativo na TV:
```bash
adb connect IP_DA_SUA_TV:5555
adb install app-debug.apk
```

---

## ⚙️ Permissões Necessárias na TV

Ao abrir o app pela primeira vez com o controle remoto:
1. **Permissão de Microfone:** Clique em Permitir para que o app consiga ouvir o áudio das caixas de som da TV.
2. **Sobreposição de Tela (Desenhar sobre outros apps):**
   * Clique no botão **"Conceder Permissão de Sobreposição"**.
   * O Android TV abrirá a tela do sistema. Marque a chave para **Permitir**.
   * *Isso é o que permite que o alerta apareça por cima da TV aberta ou de outros canais.*
3. **Teste:** Clique em **"Testar Alerta na TV"** para ver a notificação flutuante aparecer imediatamente na tela.
4. **Ativação:** Clique em **"Iniciar Monitoramento em Segundo Plano"** e saia para o canal da TV aberta normalmente. O serviço continuará ativo.

---

## 🛠️ Tecnologias Utilizadas
* **Plataforma:** Android TV (API 26+) / Kotlin / Coroutines
* **IA & LLM:** MiniMax API (`MiniMax-Text-01`)
* **Áudio:** Android `AudioRecord` (16kHz PCM com Voice Activity Detection)
* **Overlay:** Android `WindowManager` com `TYPE_APPLICATION_OVERLAY`
* **CI/CD:** GitHub Actions com runner Ubuntu + JDK 17
