# ShotSense — Response Console (Receiver)

The laptop-side software for **first responders / security operators / admins**. It
receives gunshot alerts from the ShotSense phone app over HTTP and shows them on a
live map with an audible alarm and desktop notifications.

- **Zero dependencies.** Pure Node.js — no `npm install`. Just `node server.js`.
- **Live console** at `http://localhost:8080/` (map + alert feed via Server-Sent Events).
- **Persists** every alert to an append-only `alerts.ndjson` so nothing is lost on restart.

---

## 1. Run it

```bash
cd receiver
node server.js
```

You'll see something like:

```
 ShotSense Response Console
 Console:  http://localhost:8080/
 Endpoint: POST http://<this-laptop>:8080/alert
 On the same WiFi, set the app HTTP URL to one of:
   http://192.168.1.42:8080/alert
```

Open **http://localhost:8080/** in a browser, then click **🔔 Enable alarm** once
(browsers block sound/notifications until you interact with the page).

---

## 2. Point the phone at it

In the ShotSense app → **Settings**:
- Enable **HTTP** channel.
- Set the **HTTP URL** to your receiver's `/alert` endpoint (see options below).
- Keep the phone in **TEST mode** while you verify the pipe, then **ARM** it.

### Option A — same WiFi (simplest, no internet needed)
Use the `http://192.168.x.x:8080/alert` address the server printed on startup.
Phone and laptop must be on the same network. If nothing arrives, your Windows
firewall is likely blocking inbound 8080 — allow Node through it (see Troubleshooting).

### Option B — over cellular / anywhere (use a tunnel)
Your laptop is behind a router, so a phone on mobile data can't reach it directly.
A tunnel gives you a public HTTPS URL that forwards to your laptop.

**cloudflared** (free, no account):
```bash
cloudflared tunnel --url http://localhost:8080
```
It prints a URL like `https://random-words.trycloudflare.com`. Set the app's HTTP URL to:
```
https://random-words.trycloudflare.com/alert
```

**ngrok** (free, needs a free account/token):
```bash
ngrok http 8080
```
Use the printed `https://….ngrok-free.app/alert`.

> A public URL is reachable by anyone who guesses it. Turn on the shared secret below.

---

## 3. Optional shared secret (recommended for tunnels)

Start the server with a token:

```bash
# macOS/Linux
TOKEN=mysecret node server.js
# Windows PowerShell
$env:TOKEN="mysecret"; node server.js
```

Then append it to the URL you put in the app:
```
https://random-words.trycloudflare.com/alert?token=mysecret
```
Requests without the matching token get `401`. (The token can also be sent as an
`x-shotsense-token` header.)

---

## 4. Using the console

- **Map** plots each alert — red = real GUNSHOT, amber = TEST.
- **Feed** (right) shows newest first: device, shots, confidence
  (CONFIRMED sound+recoil vs SOUND ONLY), coordinates, sound peak, recoil g, accuracy.
- A new **real** alert flashes red, loops a siren, and raises a desktop notification.
  Click **Acknowledge** to silence it.
- **Showing: ALL / GUNSHOT / TEST** filters the feed.
- **Open in Maps** / **Show on map** jump to the location.

---

## What the app sends

`POST /alert`, `Content-Type: application/json`:

```json
{
  "deviceId": "shotsense-01",
  "timestamp": "2026-06-22T10:15:30Z",
  "lat": 5.6037,
  "lng": -0.1870,
  "accuracyMeters": 12.5,
  "shots": 2,
  "confirmed": true,
  "soundPeak": 0.94,
  "recoilG": 3.1,
  "test": false,
  "approximateLocation": false
}
```

`lat`/`lng`/`accuracyMeters` may be `null` if no location fix was available. The
server responds `200 {"ok":true,"id":"…"}`.

You can simulate the phone from the laptop:
```bash
curl -X POST http://localhost:8080/alert -H "Content-Type: application/json" \
  -d '{"deviceId":"test","lat":5.6,"lng":-0.18,"shots":1,"confirmed":true,"soundPeak":0.9,"recoilG":3.0,"test":false}'
```

---

## Configuration (env vars)

| Var | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | Listen port |
| `TOKEN` | _(none)_ | If set, requests must supply it (`?token=` or `x-shotsense-token`) |
| `DATA` | `./alerts.ndjson` | Append-only alert log |

---

## Troubleshooting

- **Phone can't reach it on WiFi** → allow Node through Windows Defender Firewall:
  *Windows Security → Firewall & network protection → Allow an app → add Node.js*,
  or run once in an **admin** PowerShell:
  ```powershell
  New-NetFirewallRule -DisplayName "ShotSense Receiver 8080" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8080
  ```
- **No sound / no notifications** → click **🔔 Enable alarm** and allow notifications;
  browsers require a click first.
- **Map is blank** → you're offline (map tiles need internet). The alert list still works.
- **Confirm the server is up** → open `http://localhost:8080/health`.

---

## Transport notes (why HTTP)

- **HTTP over cellular/WiFi is the primary channel** — instant, rich JSON, free, and
  feeds this console directly.
- **SMS is the offline fallback** (works with no data). Receiving SMS *on a laptop*
  needs a paid gateway (e.g. Twilio) or an Android phone as a modem, so SMS is best
  aimed at a responder's phone, not this console.
- **LoRaWAN** is for *dedicated battery sensors* with no cellular coverage — phones
  have no LoRa radio, so it doesn't apply to the phone prototype. It's the right path
  if the sensor later becomes standalone hardware.
