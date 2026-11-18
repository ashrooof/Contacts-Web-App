# Contacts Web App

An Android WebView demo that reads contacts only after Android's runtime permission is granted. The user must also explicitly agree in the app before contact names and phone numbers are packaged into `contacts.txt` and sent to the Telegram bot/chat configured by the user.

The bot token is entered at runtime and is not committed to this repository. Do not hardcode bot tokens in source code or APKs.

## Build

The project is built with the GitHub Actions workflow in `.github/workflows/build-apk.yml`. The generated debug APK is published as a workflow artifact.
