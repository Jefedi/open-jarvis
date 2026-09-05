# Open Jarvis Murena — résultat vérifié de la livraison

Date : **5 septembre 2026**.
Dépôt : `Jefedi/open-jarvis`, fork de `tokenarc/open-jarvis`.
Branche : `murena/build-foundation`. La branche `main` n'est pas fusionnée.

## APK réellement produite et vérifiée

- Version : `0.2.0-murena-preview`, versionCode `2`.
- Application : `com.openjarvis` ; activité `.ui.MainActivity`.
- Variante : **debug**, avec certificat de développement, pas une signature de publication personnelle stable.
- Taille : **61 352 359 octets**.
- Commit compilé et testé : **`3f3752a63213926988b58b7bedb5b305636c3677`**.
- Exécution GitHub Actions : **[33989156889 — terminée avec succès](https://github.com/Jefedi/open-jarvis/actions/runs/33989156889)**.
- Artefact APK : `9976140116`, `open-jarvis-debug-3f3752a63213926988b58b7bedb5b305636c3677`.
- Artefact de diagnostics : `9976140872`, `diagnostics-3f3752a63213926988b58b7bedb5b305636c3677`.

SHA-256 de l'APK livrée :

```text
791adc0b6b0ea4964ad602120e2f63ab9b3dfaa060788e448a6c7c43644c0ce8
```

L'APK et les diagnostics ont été téléchargés. Les octets de l'APK correspondent à l'empreinte de l'intégration continue. Le vérificateur de livraison a contrôlé la cohérence du commit, l'intégrité ZIP, les rapports JUnit, le rapport Lint, la signature, le premier lancement et le rôle d'assistant. Cette mise à jour de documentation est postérieure au commit testé ; elle ne remplace pas le SHA de référence ci-dessus.

## Résultats des contrôles

| Contrôle | Résultat |
|---|---|
| Compilation APK et APK de tests | Réussie |
| Tests locaux/JVM | **48/48**, zéro échec, erreur ou test ignoré |
| Tests instrumentés sur AOSP Android 14 / API 34 / x86_64 | **6/6**, zéro échec, erreur ou test ignoré |
| Analyse Android Lint | **0 erreur bloquante, 87 avertissements** |
| Installation indépendante après nettoyage du lanceur de tests | Réussie |
| Premier lancement sans données ni profil | Réussi, `Status: ok`, activité réellement démarrée |
| Attribution du rôle `android.app.role.ASSISTANT` sur l'émulateur | Réussie pour `com.openjarvis` |
| Signature | Vérifiée par `apksigner`, schéma APK v2 |
| Comparaison SHA-256 des octets livrés | Réussie |
| Essai matériel Fairphone / Murena | **Non effectué** |
| Connexion avec les vrais comptes IA ou services personnels de l'utilisateur | **Non effectuée** |

Les six parcours Android vérifient : l'interface française sans Google Play Services et les activités TTS déclarées ; la création d'un profil par l'interface et sa conservation chiffrée après recréation d'activité ; une conversation HTTP avec un serveur simulé ; l'absence de changement de volume avant confirmation et après refus, puis le changement après autorisation ; le mode privé sans secours cloud ; la synthèse d'un fichier WAV par le véritable moteur TTS Android avec une réponse réseau simulée.

Les tests locaux portent notamment sur les adaptateurs de protocoles, les flux et appels d'outils, les confirmations, les délais, les refus, la protection des identifiants, les permissions Home Assistant/MCP, les réponses audio PCM/WAV et les limites des connexions HTTP locales. Aucun test n'utilise de clé payante réelle. Les règles Lint et les tests n'ont pas été désactivés pour publier l'APK.

## Fonctionnalités raccordées

L'interface française propose cinq onglets : Assistant, Connexions, Voix, Outils et Accès. Les profils, clés, en-têtes, paramètres et conversations sont chiffrés avec Android Keystore. L'export retire les secrets. L'historique est borné et effaçable.

Les adaptateurs LLM implémentés incluent Claude natif, Mistral, OpenAI Chat Completions et Responses, Gemini, Ollama, OpenRouter, Groq, LM Studio, llama.cpp server et les API compatibles. Les profils principal, rapide, puissant et privé sont indépendants. La liste des fournisseurs de secours est explicite ; le mode privé n'utilise aucun secours. Aucun fournisseur n'est substitué automatiquement après l'exécution d'un outil.

La transcription et la synthèse sont configurées séparément : moteur Android installé, Voxtral, Whisper compatible et TTS compatible OpenAI/Kokoro. La génération vocale Voxtral non progressive décode sa réponse JSON contenant des données audio ; le flux progressif convertit le PCM float32 en PCM16. Le microphone est activé par un bouton dans l'application visible. Aucun enregistrement n'est sauvegardé sur disque par ce nouveau moteur.

Un véritable `TextToSpeechService` permet aux applications utilisant le TTS Android de demander la voix du profil choisi. Il reste désactivé tant que l'utilisateur n'a pas accepté le traitement des textes par ce fournisseur. Les activités de vérification, exemple et configuration de voix sont déclarées pour les réglages Android. La voix interne Android évite le moteur Jarvis lui-même pour prévenir une boucle.

Les commandes Android utilisent des interfaces publiques : ouverture d'applications ou de panneaux de réglages, alarmes et minuteurs via l'horloge, préparation d'un numéro ou d'un SMS, destination dans les cartes, réglage du volume et commandes multimédias. Chaque opération exige une confirmation. Un appel ou un message préparé n'est pas annoncé comme envoyé.

Home Assistant utilise l'API REST avec autorisation d'entités ou de domaines et confirmation de chaque service. L'acceptation de la demande et l'état observé sont distincts. MCP utilise Streamable HTTP avec initialisation, versions négociées, session, pagination et liste d'outils explicitement autorisés. La vision porte sur une image choisie manuellement puis confirmée avant envoi. L'API d'embeddings est configurable et testable, mais la recherche vectorielle dans les conversations n'est pas raccordée.

## Limites à ne pas masquer

Cette livraison est une **version de développement utilisable**, pas l'intégralité du cahier des charges de contrôle total du téléphone.

Le contrôle générique des interfaces par accessibilité et le module groupé overlay/interaction vocale ont été refusés par l'outil pendant le développement. Ils ne sont pas présents sous une autre forme dans l'APK. Le périmètre exécuté est celui des commandes Android explicites et des services autorisés décrits ci-dessus.

Ne sont pas fournis : clics et saisie arbitraires dans les applications, lecture automatique d'écran ou de notifications, capture autonome, envoi automatique de SMS, écoute permanente, mot de réveil, bouton flottant, tuile de réglages rapides, moteur LLM natif sur le téléphone, authentification directe par abonnement Codex, ni conformité à tous les transports et mécanismes OAuth MCP. La compatibilité physique Fairphone/Murena et les modèles des comptes réels restent à tester.

L'APK contient les architectures ARM64, ARMv7, x86 et x86_64 de la bibliothèque OCR héritée. Ses segments ELF ont un alignement de **4096 octets**. La compatibilité avec une ROM imposant des pages de **16 Ko** n'a pas été validée et peut exiger une mise à jour ou suppression de cette dépendance. Le nouveau parcours Vision n'utilise pas cet OCR. Aucun essai ARM physique n'est annoncé.

Les services amont inutilisés et non déclarés sont conservés à titre de référence dans `app/src/legacyReference/`, et non activés en demandant des permissions supplémentaires. Les anciennes mentions « Complete » ne prouvent pas le raccordement d'une fonction.

## Reproduction

Le dépôt contient désormais le Gradle Wrapper 8.2.1 et ses vérifications d'intégrité. Java 17, SDK Android 34 et Build Tools 34.0.0 sont nécessaires.

```sh
./gradlew --no-daemon --console=plain --stacktrace \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

Avec un appareil ou un émulateur Android prêt :

```sh
./gradlew --no-daemon --console=plain --stacktrace :app:connectedDebugAndroidTest
```

Le workflow final est en lecture seule sur le dépôt : il compile les sources enregistrées, sans les modifier. Il réinstalle la même APK après que le lanceur de tests a nettoyé ses paquets, puis vérifie séparément le premier lancement et le rôle d'assistant. Les APK de développement issues de recompilations différentes peuvent avoir un certificat différent ; aucune continuité de mise à jour n'est garantie sans clé de signature stable gérée séparément.
