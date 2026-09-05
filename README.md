# Open Jarvis — édition Murena

Fork de **[tokenarc/open-jarvis](https://github.com/tokenarc/open-jarvis)**, licence MIT conservée. Cette branche fournit une interface française et un nouveau moteur modulaire ; ce n'est pas un autre projet nommé OpenJarvis.

> Version de développement `0.2.0-murena-preview`. Une compilation réussie ne valide ni tous les fournisseurs réels, ni une ROM particulière. Consulter les rapports associés au SHA exact dans GitHub Actions. Le contrôle universel du téléphone, le moteur LLM natif dans l'appareil et l'écoute permanente ne sont pas fournis par cette édition.

## Fonctionnalités raccordées

| Partie | Implémentation |
|---|---|
| Conversation | Texte, affichage progressif, historique chiffré borné, arrêt, journal des opérations. |
| Profils IA | Claude, Mistral, OpenAI Chat Completions et Responses, Gemini, Ollama, OpenRouter, Groq, LM Studio, llama.cpp server et API compatibles. Modèles saisis ou découverts sur le serveur. |
| Routage | Profils principal, rapide, puissant et privé ; liste de secours explicite. Aucun secours en mode Privé. Aucun changement de fournisseur ni répétition automatique après une opération exécutée. |
| Voix | Reconnaissance Android installée ou API Voxtral/Whisper ; synthèse Android installée ou API Voxtral/OpenAI/Kokoro compatible. Profils STT et TTS indépendants. |
| Synthèse système | Moteur Android `TextToSpeechService`, désactivé par défaut. Consentement explicite requis avant que le texte d'autres applications soit transmis au profil TTS choisi. |
| Commandes Android | Ouverture d'applications et de réglages, alarmes/minuteurs via l'horloge, préparation d'un appel ou d'un SMS, cartes, volume et commandes multimédias. Confirmation avant chaque opération. |
| Home Assistant | API REST, découverte des entités, liste d'entités/domaines autorisés, lecture d'état et services confirmés sur une seule cible. Acceptation de commande et état observé sont distincts. |
| MCP | Client Streamable HTTP : initialisation, version, session, outils paginés, liste d'autorisations et confirmation de chaque appel. Aucun sampling, téléchargement ou exécution de code fournis par le serveur. |
| Vision | Image choisie manuellement, confirmation avant envoi à un profil compatible. Aucune capture automatique d'écran. |
| Embeddings | Profil et test de l'API compatibles OpenAI/Ollama. La récupération vectorielle de conversations n'est pas encore raccordée ; l'historique conversationnel utilise la mémoire chiffrée locale. |

Les fournisseurs distants et les modèles peuvent imposer leurs propres coûts, quotas et restrictions. Aucun compte ni abonnement n'est fourni avec l'APK. Une URL Ollama/LM Studio désigne votre serveur ; elle n'installe pas un modèle sur le téléphone.

## Mise en route

1. Installer l'APK de développement validée, puis ouvrir **Open Jarvis**.
2. Dans **Connexions → Ajouter un profil**, choisir le fournisseur, renseigner la clé et le modèle. Le bouton **Tester l'API** effectue un appel réel pouvant consommer du quota.
3. Choisir le **Cerveau principal**. Les profils rapide, puissant et de secours sont facultatifs.
4. Pour parler à Jarvis, créer un profil **Voxtral — transcription** ou utiliser un moteur Android déjà installé. Pour l'entendre, créer un profil **Voxtral — synthèse vocale**, charger ses voix prédéfinies et choisir un identifiant de voix. Affecter ces deux profils dans **Voix**.
5. Utiliser **Tester le microphone** et **Tester la voix**. Les enregistrements restent en mémoire et sont transmis au fournisseur de transcription choisi ; ils ne sont pas conservés sur disque.

Sans modèle IA configuré, des commandes déterministes restent disponibles, par exemple **« Ouvre les réglages Wi-Fi »**, **« Volume à 30 % »** et **« Minuteur de 5 minutes »**. Cela n'est pas une conversation avec un LLM local.

### Fournir la voix aux autres applications

Dans **Voix**, choisir un profil TTS distant puis activer **Proposer cette voix aux autres applications Android** et lire le consentement. Ouvrir les paramètres de synthèse vocale Android et sélectionner **Open Jarvis — voix choisie**. Seules les applications utilisant la synthèse vocale Android peuvent utiliser ce moteur ; une voix intégrée à une autre application n'est pas remplacée automatiquement.

Le moteur TTS système ne lit pas l'écran et ne démarre pas le microphone. Les textes fournis par les applications clientes sont transmis au fournisseur choisi uniquement tant que le consentement reste actif. La lecture peut être arrêtée et ce consentement révoqué. Le mode Android interne évite de sélectionner le moteur Jarvis lui-même pour ne pas créer de boucle.

### Assistant Android

L'activité expose `ACTION_ASSIST`. **Accès → Choisir l'assistant par défaut** ouvre la demande de rôle Android lorsque la ROM le permet. Le geste d'invocation dépend de la ROM. Le bouton flottant, la tuile de réglages rapides et le service d'interaction vocale permanente ne sont pas intégrés à cette édition.

### Home Assistant et MCP

Créer un profil avec l'URL et le jeton requis. Dans **Outils**, découvrir les entités ou outils puis cocher uniquement ceux à autoriser. Une liste vide n'autorise rien. Pour Home Assistant, un nom exact tel que `light.salon` est préférable à un domaine entier. Une demande de déverrouillage, de scène ou de script reste soumise à une confirmation de ses paramètres exacts.

Les flux OAuth spécifiques à un serveur MCP ne sont pas implémentés ; configurer un jeton autorisé et, si nécessaire, des en-têtes dans le profil. Les cookies de navigateur et les jetons privés Codex/ChatGPT ne sont pas extraits ou réutilisés.

## Limites à connaître

Cette édition ne clique pas arbitrairement dans les interfaces des autres applications et ne lit pas leurs écrans ou notifications. Un SMS est **préparé**, pas envoyé automatiquement ; un appel est **préparé**, pas déclenché automatiquement. Les protections Android, les écrans verrouillés et les autorisations système ne sont pas contournés.

Le microphone exige un appui utilisateur et une application visible. Parler interrompt la voix en cours. Il n'y a pas de mot de réveil, d'écoute permanente ni de transcription système pour toutes les autres applications.

Les fonctions amont non raccordées ne sont pas présentées comme disponibles dans l'interface : chargement natif GGUF, surveillance d'écran, bridge Termux, notifications intelligentes, automatisations autonomes et génération d'applications. Les anciens services non enregistrés sont conservés à titre de référence dans `app/src/legacyReference/` ; ils ne reçoivent pas d'autorisations d'arrière-plan inutiles.

## Confidentialité et sécurité

Clés, en-têtes, profils et historique sont stockés avec Android Keystore et des préférences chiffrées. Aucune solution de repli en texte clair n'est utilisée si ce stockage échoue. Les sauvegardes Android de l'application sont désactivées. L'export des profils retire les secrets et les en-têtes personnalisés.

Le transport réseau ne journalise pas les corps ou identifiants et ne suit pas les redirections. HTTPS vérifie les certificats. HTTP nécessite une autorisation explicite par profil et reste limité aux adresses locales privées ou de boucle locale ; cette option ne rend pas une connexion Internet non chiffrée acceptable.

Les confirmations expirent en refus. Une réponse réseau incomplète n'exécute pas d'appel d'outil partiel. Les résultats des outils sont considérés comme des données non fiables. Le code ne considère pas une simple demande acceptée par un serveur comme la preuve d'un résultat physique.

## Compilation et vérification

Java 17, Gradle 8.2.1 et Android SDK 34 / Build Tools 34.0.0 sont utilisés. La définition de référence est `.github/workflows/android.yml`.

```sh
gradle --no-daemon --console=plain --stacktrace \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

Les tests locaux utilisent des serveurs simulés, jamais de clés payantes. Les tests instrumentés s'exécutent sur une image AOSP sans Google Play Services et vérifient l'interface, le stockage chiffré, les confirmations et le moteur TTS système. Seul le rapport d'une exécution terminée avec succès prouve leur réussite.

Les APK de développement sont signées avec une clé de débogage, pas avec une clé de publication personnelle. Une recompilation sur une autre machine peut changer de certificat : ne pas compter sur une mise à jour en place sans stratégie de signature stable. Ne pas publier de clé de signature dans le dépôt.

Le fichier `ci-results/build-info.txt`, l'empreinte SHA-256 et les rapports accompagnent chaque APK validée. Un essai sur émulateur ne remplace pas un essai matériel sur Fairphone/Murena ni une connexion à vos services réels.
