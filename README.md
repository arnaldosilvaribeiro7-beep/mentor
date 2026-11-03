# GitHub Manager - Android

<div align="center">

<img width="1200" height="475" alt="GHBanner" src="https://github.com/user-attachments/assets/0aa67016-6eaf-458a-adb2-6e31a0763ed6" />

<h1>GitHub Manager</h1>
<p>Aplicativo Android nativo em Kotlin e Jetpack Compose (Material 3) para gerenciar repositórios, código, automações do GitHub Actions, Pull Requests, Issues e Copilot.</p>

</div>

---

## 🚀 Compilação Automática via GitHub Actions

Este projeto já está 100% configurado com pipeline de Integração Contínua (CI) através do arquivo `.github/workflows/android-build.yml`.

### O que o workflow gera automaticamente:
1. **APK Debug (`app-debug.apk`)**: Pronto para instalação imediata no celular e testes de desenvolvimento.
2. **APK Release (`app-release.apk`)**: Versão otimizada e minificada para distribuição direta.
3. **AAB Release (`app-release.aab`)**: Android App Bundle oficial para publicação na Google Play Store.

---

## 🛠️ Como disparar a compilação manualmente

1. Acesse o seu repositório no GitHub pelo navegador.
2. Clique na aba **Actions** no menu superior.
3. Na lista à esquerda, selecione o workflow **Android CI / GitHub Actions Build**.
4. Clique no botão **Run workflow** à direita.
5. Selecione a branch (geralmente `main`) e confirme em **Run workflow**.
6. Acompanhe a execução em tempo real até o status verde de conclusão.

---

## 📦 Como baixar os arquivos compilados (APKs e AAB)

1. Vá até a aba **Actions** no seu repositório.
2. Clique na execução concluída mais recente do workflow.
3. Role a página até o final na seção **Artifacts** (Artefatos).
4. Você encontrará os 3 arquivos disponíveis para download direto em formato `.zip`:
   - `github-manager-apk-debug`
   - `github-manager-apk-release`
   - `github-manager-aab-release`

---

## 🔐 Como configurar a assinatura de Release (GitHub Secrets)

Se você não configurar nenhum Secret, o workflow **não falha**: ele gera automaticamente uma chave temporária para que os APKs e o AAB sejam compilados normalmente.

Para assinar a versão de Release com sua chave de produção definitiva:

### 1. Obtenha sua chave Keystore em Base64
No terminal Linux/macOS ou PowerShell, execute:
```bash
base64 -w 0 sua-chave.jks > keystore_base64.txt
```
*(No macOS, use `base64 -i sua-chave.jks`)*

### 2. Cadastre os Secrets no GitHub
1. No seu repositório GitHub, vá em **Settings** > **Secrets and variables** > **Actions**.
2. Clique no botão **New repository secret** e adicione:

| Nome do Secret | Descrição |
| :--- | :--- |
| `KEYSTORE_BASE64` | O conteúdo do arquivo Base64 gerado no passo anterior |
| `KEYSTORE_PASSWORD` | Senha do arquivo Keystore |
| `KEY_ALIAS` | Alias da chave (exemplo: `upload` ou seu alias customizado) |
| `KEY_PASSWORD` | Senha da chave específica dentro do Keystore |

Pronto! Na próxima compilação, o workflow utilizará sua chave oficial automaticamente.

---

## 💻 Compilação Local com Gradle Wrapper

Caso deseje compilar em sua máquina local via terminal:

```bash
# Dar permissão de execução ao wrapper (Linux/macOS)
chmod +x gradlew

# Compilar APK Debug
./gradlew assembleDebug

# Compilar APK e AAB Release
./gradlew assembleRelease bundleRelease

# Executar testes unitários
./gradlew testDebugUnitTest
```

Os arquivos gerados ficarão localizados em:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`
