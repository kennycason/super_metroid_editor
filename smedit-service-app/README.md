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

The app asks `GET /metadata` for supported patch IDs, randomizer filters, and colorize effects when the service is running. It can generate either a patched ROM plus IPS bundle through the JSON response, or an IPS-only binary response through `POST /patch?format=ips`.
