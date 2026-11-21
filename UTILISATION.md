# PhoneCam Studio — mode d'emploi

Webcam sans fil : téléphone Android → PC, réglages depuis le PC, zéro filigrane.

Dans ce guide, `<ip>` est l'adresse du téléphone affichée dans l'appli (par ex. `192.168.1.20`).

## Installation

1. Télécharger `PhoneCam-Studio-x.y.apk` dans les [Releases](../../releases/latest).
2. L'ouvrir sur le téléphone (autoriser l'installation d'applications inconnues si Android le demande).
3. Au premier lancement, accepter l'accès à la caméra et aux notifications.

Téléphone et PC doivent être sur le même réseau WiFi (ou reliés en USB, voir plus bas).

## Démarrage (quotidien)

1. **Téléphone** : ouvrir l'app **PhoneCam Studio** → gros bouton rouge (**Démarrer le stream**)
   - L'écran du téléphone peut s'éteindre ensuite (service au premier plan)
   - L'app affiche l'aperçu, l'adresse à ouvrir sur le PC (toucher = copier),
     l'objectif (arrière/avant) et la torche
   - Les réglages (objectif, qualité, zoom, exposition) sont mémorisés d'une session à l'autre
2. **PC — tour de contrôle** : navigateur → `http://<ip>:8090`
   - Aperçu live + réglages : Exposition (compensation EV), Zoom (presets + slider),
     Objectif, Torche, Qualité (50-92)
   - Outils sur l'aperçu : grille des tiers (**G**), capture PNG (**S**), plein écran (**F**), torche (**T**)
   - Double-clic sur le slider EV ou zoom = remise à zéro
3. **OBS** : Source **Navigateur** → URL `http://<ip>:8090/view` (1920×1080)
   - Puis « Démarrer la caméra virtuelle » → dispo partout (Discord, Zoom, Teams…)
4. **Fin** : bouton « Arrêter » dans l'app (ou dans la notification)

## Options avancées

- **Mode USB** (zéro WiFi, téléphone en charge) : activer le débogage USB sur le téléphone, puis sur le PC
  ```
  adb forward tcp:18090 tcp:8090
  adb forward tcp:18091 tcp:8091
  ```
  → tour de contrôle sur `http://localhost:18090`, OBS sur `http://localhost:18090/view`.
  `localhost` est un contexte sécurisé : le navigateur décode le H.264 avec WebCodecs (latence minimale).
- **Dock OBS intégré** : OBS → menu **Docks** → *Docks de navigateur personnalisés…* →
  nom « PhoneCam » + URL `http://<ip>:8090/?compact` → les réglages vivent DANS OBS,
  sans second aperçu
- **Forcer la source de l'aperçu** : `?src=jpeg` ou `?src=mse` sur `/` ou `/view`
- **Flux H.264 brut** : OBS Source **média** (décocher « Fichier local ») → `http://<ip>:8091/h264`,
  Format d'entrée `h264`, tampon réseau au minimum — un peu plus de délai (~0,5 s)
- **Lecteur externe** : `ffplay -fflags nobuffer -flags low_delay -framedrop -f h264 http://<ip>:8091/h264`
  puis OBS **Capture de fenêtre**
- **API** : `http://<ip>:8090/api/info` (état en JSON), `http://<ip>:8090/api/set?zoom=2&torch=1`

## Fluidité / latence

- Aperçu web et OBS `/view` : **H.264** (~11 Mbit/s, décodage matériel du PC) via Media Source en WiFi,
  via WebCodecs en USB ; le JPEG (~60 Mbit/s) n'est qu'un secours
- Tant que personne n'utilise le JPEG, le téléphone n'en encode pas → caméra et WiFi soulagés
- H.264 : pas d'images B, priorité temps réel, tampons réseau réduits ; si un lecteur prend du retard,
  il est resynchronisé sur une image clé au lieu d'accumuler du délai
- Caméra : 30 i/s fixes, débruitage/netteté en mode « rapide », stabilisation électronique coupée
  (elle retient des images)
- WiFi du téléphone maintenu en mode haute performance + faible latence pendant le stream
- **Le plus réactif** : mode USB + `/view`
- Chaque page avec aperçu = un flux de plus sur le WiFi : pendant un appel, fermer la page de
  contrôle ou utiliser `/?compact` (réglages sans aperçu)

## Économie de batterie

- La capture JPEG s'arrête ~4 s après que plus personne ne la regarde (seul le H.264 continue)
- Écran du téléphone éteint pendant le stream : normal et prévu

## Limites connues

- **30 i/s** : beaucoup de capteurs n'offrent pas de 60 i/s à l'API camera2
- **Exposition = compensation EV** : certains constructeurs (Samsung notamment) verrouillent le mode
  manuel vitesse/ISO ; contre le flou de mouvement, baisser l'EV et ajouter de la lumière
- Pas d'authentification : à utiliser sur un réseau de confiance

## En cas de pépin

| Symptôme | Solution |
|---|---|
| Page/OBS noire | Téléphone : le stream tourne ? Le redémarrer. OBS : clic droit → Actualiser |
| Adresse inaccessible | L'IP du téléphone a changé → relire l'adresse affichée dans l'app (pour l'éviter : réserver l'IP du téléphone dans la box, « bail DHCP statique ») |
| Tout est lent / saccadé | Téléphone en WiFi 5 GHz, pas trop loin de la box ; baisser la Qualité ; fermer les aperçus en trop ; ou passer en USB |
| Autre appli caméra ouverte | Le stream reprend tout seul quand la caméra se libère (nouvel essai toutes les 1,5 à 10 s) |
| Mise à jour de l'appli | Le service s'arrête → relancer « Démarrer le stream » |
