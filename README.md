# PhoneCam Studio

Transforme un téléphone Android en **webcam sans fil pour PC**, réglable depuis le navigateur, sans filigrane ni appli côté PC.
Pensé pour OBS (puis caméra virtuelle → Discord, Zoom…), testé sur un smartphone Samsung récent.

- **Vidéo H.264 matérielle** 1080p30, envoyée par WiFi ou par câble USB
- **Page de contrôle** dans le navigateur du PC : aperçu en direct, exposition, zoom, objectif avant/arrière, torche, qualité
- **Basse latence** : chaque image est poussée dès qu'elle est encodée, sans file d'attente ; un lecteur qui prend du retard est resynchronisé au lieu d'accumuler du délai
- **Écran éteignable** : la caméra tourne dans un service au premier plan
- Reprise automatique si une autre appli prend la caméra ; réglages mémorisés

## Télécharger

**[➜ Dernière version (APK Android)](../../releases/latest)** — Android 10 minimum.
Ouvrir le fichier `.apk` sur le téléphone et autoriser l'installation si Android le demande.
Rien à installer sur le PC : tout passe par le navigateur (ou OBS).

## Utilisation

1. Sur le téléphone : ouvrir **PhoneCam Studio** → gros bouton rouge.
2. Sur le PC : ouvrir l'adresse affichée par l'appli, par ex. `http://192.168.1.20:8090`.
3. Dans OBS : source **Navigateur** sur `http://<ip>:8090/view` (1920×1080).

| Adresse | Contenu |
|---|---|
| `:8090/` | Page de contrôle (`?compact` = réglages sans aperçu, idéal en dock OBS) |
| `:8090/view` | Image seule pour OBS (H.264 ; `?src=jpeg` pour forcer le JPEG) |
| `:8091/h264` | Flux H.264 brut (OBS « Source média », ffplay, mpv) |
| `:8090/api/info`, `/api/set?zoom=2&torch=1…` | API JSON |

Mode USB : `adb forward tcp:18090 tcp:8090` et `adb forward tcp:18091 tcp:8091`, puis `http://localhost:18090`
(contexte sécurisé → décodage WebCodecs, latence minimale).

Guide détaillé, astuces de fluidité et dépannage : [UTILISATION.md](UTILISATION.md).

> Les serveurs n'ont pas d'authentification : n'importe qui sur le même réseau local peut voir et piloter la caméra.
> À utiliser sur un réseau de confiance.

## Architecture

```
caméra (camera2) ──► encodeur H.264 (MediaCodec, surface) ──► H264Server :8091 ──► /ws  (WebSocket)
        │                                                              └─► /h264 (Annex-B brut)
        └──► ImageReader JPEG (seulement si un aperçu JPEG est regardé) ──► MjpegHttpServer :8090
```

| Fichier | Rôle |
|---|---|
| `StreamService.kt` | Service au premier plan : possède caméra + encodeur, applique les réglages, reprise sur erreur |
| `CameraEngine.kt` | Session camera2 (sortie encodeur + JPEG optionnel), exposition, zoom, torche |
| `H264Encoder.kt` | Encodeur matériel faible latence (pas d'images B, priorité temps réel) |
| `H264Server.kt` | Diffusion H.264 aux clients, resynchronisation sur keyframe des clients en retard |
| `MjpegHttpServer.kt` | Page de contrôle, API, aperçu JPEG (WebSocket, long-poll, MJPEG) |
| `MainActivity.kt`, `Ui.kt` | Écran du téléphone (vues natives, sans AndroidX) |
| `assets/web/` | `control.html`, `view.html`, lecteurs `mse.js` (Media Source), `player.js` (WebCodecs), `jpeg.js` |

## Compilation

JDK 17+ et SDK Android 36 (`local.properties` → `sdk.dir=…`) :

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android 10 (API 29) minimum. Aucune dépendance en dehors de la bibliothèque standard Kotlin.

APK signée : créer `keystore.properties` à la racine (non versionné) puis `./gradlew assembleRelease` :

```properties
storeFile=signing/ma-cle.jks
storePassword=…
keyAlias=…
keyPassword=…
```
