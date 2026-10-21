# 📺 TV Fact Checker AI - Android TV APK

Aplicativo nativo para **Android TV, Google TV e TV Box** que realiza **checagem de fatos em tempo real** de notícias, debates e declarações da **TV aberta e uniTV**, utilizando **Inteligência Artificial neural** e exibindo alertas flutuantes (*overlays*) discretos no canto da tela sem interromper a programação.

---

## 💡 Como Funciona

1. **Escuta Ambiente em Segundo Plano:** Um `ForegroundService` com trava `WakeLock` no Android TV captura o áudio ambiente da TV através do microfone integrado da Smart TV ou microfone USB.
2. **Filtragem & Detecção de Alegações Fatuais:** O pipeline analisa os blocos de áudio e descarta conversas informais, piadas e comerciais, isolando apenas alegações que exigem checagem.
3. **Análise Crítica com Inteligência Artificial:** O texto é submetido ao motor de IA neural para confrontar o que foi dito com dados oficiais e agências de checagem.
4. **Pílula de Status Ambiente & Card Flutuante (Overlay):** Uma pílula discreta no canto superior direito indica a escuta ativa (`🟢 🎙️ Ouvindo TV...`). Se a informação for identificada como **FALSA**, **ENGANOSA** ou **VERDADEIRA**, o card expande suavemente com a correção e a fonte oficial e recolhe de volta.

---

## 🚀 Como Compilar o APK Automaticamente (GitHub Actions)

O projeto inclui a automação do **GitHub Actions** em `.github/workflows/build-apk.yml`, compilando o arquivo oficial **`TVFactChecker_v1.0.4.apk`** diretamente na nuvem.

---

## 📲 Como Instalar o APK na sua Android TV / uniTV

1. Baixe o arquivo **`TVFactChecker_v1.0.4.apk`** da aba **Releases**.
2. Envie para a TV usando o app **Send Files to TV** ou via pendrive USB.
3. Abra um gerenciador de arquivos na TV e instale o APK.
4. Conceda as permissões de microfone e sobreposição de tela.
5. Inicie o monitoramento!

---

## ⚙️ Permissões Necessárias na TV

Ao abrir o app pela primeira vez com o controle remoto:
1. **Permissão de Microfone:** Clique em Permitir para que o app consiga ouvir o áudio das caixas de som da TV.
2. **Sobreposição de Tela (Desenhar sobre outros apps):**
   * Clique no botão **"Conceder Permissão de Sobreposição"**.
   * O Android TV abrirá a tela do sistema. Marque a chave para **Permitir**.
   * *Isso é o que permite que a pílula e o card apareçam por cima da TV aberta, uniTV ou qualquer outro canal.*
3. **Teste:** Clique em **"Testar Alerta na TV"** para ver a notificação flutuante aparecer imediatamente na tela.
4. **Ativação:** Clique em **"Iniciar Monitoramento em Segundo Plano"** e saia para o canal de TV aberta ou uniTV normalmente. O serviço continuará ativo.
