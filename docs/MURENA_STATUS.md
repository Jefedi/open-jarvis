# Open Jarvis pour Murena — état réel du développement

Date du point de contrôle : 5 septembre 2026.
Dépôt : `Jefedi/open-jarvis`, fork de `tokenarc/open-jarvis`.
Branche de travail : `murena/build-foundation`. La branche `main` n'est pas modifiée.

> Travail préparatoire en cours. Cette branche n'est pas une application complète, validée ou prête à installer. Les mentions « Complete » du README amont ne constituent pas une preuve de fonctionnement.

## Modifications réellement enregistrées

La chaîne GitHub Actions utilise Java 17, Gradle 8.2.1, Android SDK 34 et les dépendances déclarées par le projet. Elle lance `:app:assembleDebug`, `:app:testDebugUnitTest` et `:app:lintDebug`, sans clé API ni appel payant dans les tests ajoutés. Les actions sont référencées par leurs SHA. Les diagnostics et une archive des sources exactes sont conservés même lorsque la compilation échoue. Une APK ne doit être publiée par ce workflow qu'après réussite des tâches et vérification de sa signature.

Le client HTTP partagé ne journalise plus les requêtes, réponses, en-têtes ou clés API, même en mode debug. Un délai maximal par appel a été ajouté. Trois tests de non-régression utilisent un serveur HTTP local fictif. Cette correction n'est pas un audit de tous les transports de l'application.

Les dépendances Compose Activity et Material Components manquantes ont été ajoutées. Le thème Compose référencé par les écrans mais absent des sources a été créé. L'écran principal actualise les autorisations au retour des réglages Android ; les fournisseurs peuvent être configurés sans activer au préalable le contrôle du téléphone.

Les types d'état incohérents dans le gestionnaire local ont été corrigés. Les paramètres de fournisseur chargés depuis les préférences ne sont plus transmis comme chaînes nullables à des constructeurs non nullables. Le fournisseur personnalisé existant est visible dans la liste.

La persistance des automatisations utilise maintenant une conversion explicite entre l'entité Room et les horaires métier. Deux tests vérifient l'aller-retour des horaires et le rejet d'un type inconnu. Les requêtes périodiques et ponctuelles WorkManager sont distinctes ; les intervalles inférieurs à quinze minutes sont rejetés. Le worker amont annonçait un succès après une simple attente, sans exécuter la commande : il signale maintenant « Non exécuté » tant que le moteur n'est pas raccordé.

Les erreurs de syntaxe du client MCP ont été corrigées, ainsi que la lecture de certains résultats JSON. Ce transport reste un client JSON ancien : ni conformité MCP moderne, ni authentification complète, ni compatibilité Home Assistant ne sont validées.

Le moteur d'agent a été remis en forme pour corriger ses accolades, son étiquette de retour et un nom d'argument erroné. Les actions non implémentées sont refusées au lieu d'être ignorées puis annoncées comme réussies. Le constructeur du lecteur d'écran a été corrigé ; les nœuds trouvés ne sont plus recyclés avant leur remise à l'appelant.

## Essais effectués et preuves

1. Essai `33983346143` : échec d'installation du SDK (`sdkmanager` absent du PATH). Point corrigé dans le workflow suivant.
2. Essai `33983533093`, commit `7b05267c897edd379c4b9be155e700d82af6b635` : le SDK et Gradle fonctionnent ; échec à `kspDebugKotlin`, avec erreurs Room et erreurs de syntaxe Kotlin. Les diagnostics ont été téléchargés et inspectés.
3. Essai `33984034398`, commit `44e3d75c1f584894e21792f25c1755b04635f789` : `kspDebugKotlin` passe. `compileDebugKotlin` échoue ensuite avec 81 lignes de diagnostic. Il s'agit de diagnostics, pas nécessairement de 81 défauts indépendants. L'ambiguïté de constructeur ScreenReader et l'import ActionPlan inexistant ont été corrigés après cet essai. Les autres diagnostics restent à traiter.

Les cinq tests ajoutés n'ont pas encore été exécutés : la compilation du code applicatif bloque leur lancement. Aucun essai sur Fairphone, Murena ou émulateur n'a été effectué. Aucune APK réussie ou validée n'est annoncée.

Les exécutions et leurs archives sont consultables dans l'onglet Actions du dépôt. Pour chaque résultat, vérifier le SHA exact : un test sur un ancien commit ne valide pas les modifications suivantes.

## Blocages connus

- Le manifeste désigne `.MainActivity`, alors que la classe est `.ui.MainActivity`. La tentative de modification de ce manifeste a été refusée par l'outil de cette session ; le manifeste est resté inchangé. Aucun contournement n'a été appliqué.
- La compilation Kotlin signale encore des méthodes inexistantes, des objets compagnons dupliqués, deux définitions de GeminiProvider, des imports Compose manquants et des usages incorrects d'API Android. Les groupes concernés incluent voix, vision, interface, notifications, pont Termux, moteur de secours, compétences et générateur d'applications.
- Le chargement local ne fait actuellement que changer des états dans ModelManager : cela ne prouve pas qu'un moteur natif charge et exécute le modèle. La reprise des téléchargements nécessite également une correction.
- Le service d'overlay examiné n'attache pas encore son widget à une fenêtre. Plusieurs écrans de configuration contiennent des états locaux non raccordés aux composants correspondants.
- Les confirmations sensibles doivent être imposées par du code avant toute action, sans acceptation automatique à l'expiration d'un délai. Une consigne dans le prompt du modèle ne constitue pas ce contrôle. Ne pas utiliser cette branche sur des applications bancaires ou pour des actions irréversibles.

## Objectif fonctionnel restant à développer

Le cahier des charges reste celui d'un assistant complet pour Murena, pas seulement d'un chatbot. Il exige des fournisseurs indépendants pour le raisonnement, la transcription, la synthèse vocale, la vision et les outils ; des profils multiples ; des paramètres persistants et des erreurs compréhensibles.

Les intégrations prioritaires sont Claude, Mistral, OpenAI, les API compatibles, Ollama et les fournisseurs existants, avec streaming et appels d'outils lorsqu'ils sont pris en charge. Toute connexion OpenAI par abonnement doit s'appuyer sur une méthode officielle autorisée ; ne pas extraire ou réutiliser des cookies ni des jetons privés Codex.

Voxtral pour la transcription et la synthèse vocale, la voix système Android, le rôle d'assistant par défaut, les accès rapides, Home Assistant et un client MCP conforme restent à développer et à tester. Les cinq tests ajoutés ne valident aucune de ces intégrations.

## Reproduction de la compilation

La procédure effectivement utilisée se trouve dans `../.github/workflows/android.yml`. Le dépôt n'a pas encore de Gradle Wrapper versionné. Dans un environnement disposant de Java 17, Gradle 8.2.1 et du SDK Android 34 :

```sh
gradle --no-daemon --console=plain --stacktrace --continue \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Conserver les journaux, les rapports de tests et le SHA des sources. Ne pas fusionner en tant que version prête à installer avant une compilation réussie, la correction du lancement, les tests de sécurité et les essais de fonctionnement réels.
