# SMEDIT Service App

Local React/TypeScript client for `smedit-service`.

```bash
cd smedit-service-app
npm install
npm run dev
```

Run the service in another terminal:

```bash
./gradlew :smedit-service:runService
```

The local API base URL is set in `.env.local`:

```bash
VITE_SMEDIT_SERVICE_URL=http://localhost:8080
```
