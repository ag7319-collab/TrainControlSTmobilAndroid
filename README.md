# TrainControlSTmobilAndroid

**Android-App zur Überwachung von Zugverbindungen in Südtirol**

Eine mobile Anwendung, entwickelt für Android, zur automatischen Überwachung von Zugverbindungen und Verspätungen in Südtirol – optimiert für Pendler.

## Über das Projekt
Diese App ist die mobile Ergänzung zur Desktop-Version von TrainControlSTmobil. Sie wurde entwickelt, um Pendler in Südtirol proaktiv über Verspätungen auf ihren täglichen Strecken (z.B. Home <-> Work) zu informieren. Im Gegensatz zur manuellen Suche in Fahrplan-Apps prüft diese Anwendung im Hintergrund zu festgelegten Zeiten die gewählten Verbindungen und schlägt nur dann Alarm, wenn tatsächlich eine Verspätung oder ein Ausfall vorliegt.

Die App erkennt dabei automatisch über GPS, ob sich der Nutzer am "Heimat-Bahnhof" oder der "Arbeits-Bahnhof" befindet, und wählt die entsprechende Gegenstation als Ziel.

## Hauptfunktionen
*   **Hintergrund-Überwachung:** Automatische Prüfung der Verbindungen zu konfigurierbaren Uhrzeiten (z.B. kurz vor Abfahrt).
*   **Standorterkennung:** Automatische Ermittlung des Abfahrtsbahnhofs basierend auf dem GPS-Standort.
*   **Intelligente Benachrichtigungen:** Benachrichtigungen (einschließlich Signalton) erfolgen nur bei relevanten Verspätungen oder Ausfällen.
*   **Garmin-Support:** Benachrichtigungen sind so formatiert, dass sie auch auf Garmin-Smartwatches (via Android Notification System) optimal lesbar sind.
*   **Detaillierte Filter:** Auswahlmöglichkeit zwischen Regionalzügen, Fernverkehr (Frecciarossa, Italo, RJ) und Schienenersatzverkehr (Bus).

## Datenquelle
Die Anwendung kombiniert Daten aus 3 Quellen:
1.  **Südtirol Mobil (EFA):** Grundlegende Fahrplandaten und offizielle Echtzeit-Informationen der STA.
2.  **RFI (Rete Ferroviaria Italiana):** Zusätzliche Abfrage der offiziellen italienischen Bahn-Monitore und züber Viaggiatreno (ViaggiaTreno/IecHub), um eine höhere Genauigkeit bei Verspätungsangaben auf dem staatlichen Schienennetz zu erreichen.

## Installation & Start
1.  **Repository klonen:**
    ```bash
    git clone https://github.com/ag7319-collab/TrainControlSTmobilAndroid.git
    ```
2.  **Bauen:** Das Projekt kann direkt in Android Studio geöffnet und auf ein Android-Gerät (ab API 31 / Android 12) installiert werden.
3.  **Berechtigungen:** Für den vollen Funktionsumfang benötigt die App Berechtigungen für Standort (GPS), Benachrichtigungen und das Deaktivieren von Akku-Optimierungen (um Hintergrund-Checks zuverlässig auszuführen).

## Tech Stack
*   **Sprache:** Kotlin
*   **UI-Framework:** Android Views (Material Design)
*   **Hintergrund-Verarbeitung:** WorkManager & AlarmManager
*   **Netzwerk & Parsing:** Jsoup (HTML Scraping) & JSONObject (EFA API)
*   **Standort:** Google Play Services Fused Location Provider
*   **Asynchronität:** Kotlin Coroutines

## Warum diese App?
Während andere Apps zumeist eine manuelle Suche erfordern, arbeitet diese hier nach dem "Set and Forget"-Prinzip. Einmal eingerichtet, meldet sich das Smartphone nur noch, wenn es wirklich wichtig ist – man braucht sich nicht mehr darum zu kümmern.
