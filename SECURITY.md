# Security

## What this thing actually is

`dsh-watch-bridge` is a remote control for an AI agent that has a shell. Anyone
who holds a device token can

- read your conversation tails (which may contain secrets you pasted),
- **approve tool calls**, i.e. authorize command execution on the host,
- inject prompts into sessions,
- stop running turns,
- flip the approval policy.

Treat a token like an SSH private key. Treat the bridge port like an SSH port.

## What is implemented

| Control | Detail |
|---|---|
| Pairing code | 6 digits, 10-minute TTL, **invalidated after 5 wrong guesses**, one token per device |
| Code disclosure | **Only through the `watch_pair_code` agent tool.** Never in HTTP, never in logs, never on the `/watch` page |
| Token storage | 48-hex random, server keeps only `sha256`, `devices.json` mode 600 |
| Admin routes | `/watch` device list, `/api/selftest`, `/api/devices/revoke` are loopback-only |
| Reverse proxies | Any `X-Forwarded-For` / `X-Real-IP` / `Forwarded` header makes a request non-local, so an nginx front does not turn admin routes public |
| DNS rebinding | Loopback admin routes also require an IP-literal or `localhost` `Host` header |
| Query tokens | Rejected. `Authorization: Bearer` only |
| DoS | header / request / keep-alive timeouts on the listener |

## What you must decide

1. **TLS.** Default is plain HTTP. On a public address use `tlsCert`/`tlsKey`,
   terminate TLS in nginx, or keep the port private behind WireGuard/Tailscale/SSH.
2. **Firewall.** Do not leave the port open to `0.0.0.0/0` if you can avoid it.
3. **Lost watch.** Revoke the device: `/watch` → 撤销, or
   `curl -X POST 127.0.0.1:8787/api/devices/revoke -d '{"id":"..."}'`.
4. **APK updates.** Release APKs are signed with a key that is *not* in this
   repository. Verify before installing:
   `apksigner verify --print-certs dsh-watch.apk`.

## Reporting

Open an issue for anything that is not a live credential leak. For a leak,
rotate the affected token first (revoke the device), then report.
