# Contacts Web App

A lightweight Android WebView demo that requests the user's explicit `READ_CONTACTS` permission and displays contacts locally. Contact data is not uploaded or transmitted. The web interface is bundled in the Android app; GitHub Pages alone cannot read the device address book.

## Build

The project is built with the GitHub Actions workflow in `.github/workflows/build-apk.yml`. The generated debug APK is published as a workflow artifact.
