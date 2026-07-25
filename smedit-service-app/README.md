# SMEDIT Lite

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

If port `8080` is already taken, run the service on another port and set the app to the same URL:

```bash
./gradlew :smedit-service:runService -Dsmedit.service.port=8090
```

The local API base URL is set in `.env.local`:

```bash
VITE_SMEDIT_SERVICE_URL=http://localhost:8080
```

The app asks `GET /metadata` for supported patch IDs, room names, randomizer filters, and colorize effects when the service is running. It can generate either a patched ROM plus IPS bundle through the JSON response, or an IPS-only binary response through `POST /patch?format=ips`.

The current ROM is kept in browser storage until removed or replaced. Patch, colorize, color seed, randomizer, selected ROM, API URL, fanfare, Ceres timer, and bomb-tuning settings are remembered in localStorage for the next session.

The Patch Toggles section includes common convenience patches like Skip Ceres + intro, Ceres escape timer, Quick item fanfares, Energy-free shinesparks, Enable moonwalk, Spider Ball, and Bomb tuning. Ceres timer exposes minutes and seconds, Bomb tuning exposes max active bombs, fuse frames, cooldown frames, and explosion delay, and Spider Ball exposes room, x/y coordinates, and visible/Chozo/hidden placement type.
