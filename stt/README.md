# Speech to Text (Hindi + English) - Spring Boot + MySQL + TypeScript + Gemini Live

Real-time transcription: browser mic -> Spring Boot (WebSocket) -> Gemini Live API -> live text.
Transcriptions are stored in MySQL with a CRUD dashboard.

## Setup
1. Install JDK 17+ and MySQL, and start MySQL.
2. Edit `src/main/resources/secrets.properties`:
   - `gemini.api.key` = your free key from https://aistudio.google.com/apikey
   - `spring.datasource.password` = your MySQL password
3. Run `SttApplication` (IntelliJ: open `pom.xml` as a project, then Run).
4. Open http://localhost:8080

## Rebuild the TypeScript (only if you edit src/main/ts/app.ts)
    npm install
    npm run build

The compiled JS is committed in `src/main/resources/static/js` so deployment needs no Node.

## Environment variables for deployment
GEMINI_API_KEY, DB_URL, DB_USER, DB_PASS (and optional GEMINI_MODEL)
