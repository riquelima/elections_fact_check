# 📺 TV Fact Checker AI - Android TV APK

Aplicativo nativo para **Android TV, Google TV e TV Box** que realiza **checagem de fatos em tempo real** de notícias, debates e declarações da **TV aberta e uniTV**, utilizando **Inteligência Artificial neural** e exibindo alertas flutuantes (*overlays*) discretos no canto da tela sem interromper a programação.

---

## 💡 Como Funciona

1. **Escuta Ambiente em Segundo Plano:** Um `ForegroundService` com trava `WakeLock` no Android TV captura o áudio ambiente da TV através do microfone integrado da Smart TV ou microfone USB.
2. **Filtragem & Detecção de Alegações Fatuais:** O pipeline analisa os blocos de áudio e descarta conversas informais, piadas e comerciais, isolando apenas alegações que exigem checagem.
3. **Análise Crítica com Inteligência Artificial:** O texto é submetido ao motor de IA neural para confrontar o que foi dito com dados oficiais e agências de checagem.
4. **Pílula de Status Ambiente & Card Flutuante (Overlay):** Uma flag discreta no canto superior direito permanece contínua durante todo o monitoramento com 3 estados limpos (`Escutando áudio` -> `Processando` -> `Checando fatos`). Se a informação for identificada como **FALSA**, **ENGANOSA** ou **VERDADEIRA**, o card expande suavemente com a correção e a fonte oficial e recolhe de volta.

---

## 🚀 Como Compilar o APK Automaticamente (GitHub Actions)

O projeto inclui a automação do **GitHub Actions** em `.github/workflows/build-apk.yml`, compilando o arquivo oficial **`TVFactChecker_v1.2.1.apk`** diretamente na nuvem.

---

## 📲 Como Instalar o APK na sua Android TV / uniTV

1. Baixe o arquivo **`TVFactChecker_v1.2.1.apk`** da aba **Releases**.
2. Envie para a TV usando o app **Send Files to TV** ou via pendrive USB.
3. Abra um gerenciador de arquivos na TV e instale o APK.
4. Conceda as permissões com o Botão 1 e inicie o monitoramento com o Botão 2!

---

## ⚙️ Interface Simplificada para Controle Remoto (2 Botões)

1. **Botão 1: Conceder Permissões da TV**
   * Concede sobreposição sobre outros apps/canais (uniTV, TV aberta) e acesso ao microfone.
2. **Botão 2: Iniciar / Parar Monitoramento em Segundo Plano**
   * Liga o monitoramento em segundo plano. A flag flutuante ficará no canto da tela alternando suavemente entre `Escutando áudio`, `Processando` e `Checando fatos` antes de exibir o card de alerta na TV.
