# WatchOut

See what Claude Code is doing — on your phone and your wrist.

WatchOut is an Android + Wear OS app fed by [Claude Code](https://docs.claude.com/en/docs/claude-code)
hooks. A small Python hook turns each Claude Code event into a status update and ships it to
the phone app, which renders it (animated status blob, current file, elapsed time, token
usage, recent events) and relays it to the watch — with a buzz when Claude finishes or needs
your input.

```
Claude Code hooks ──▶ tools/notify.py ──▶ (FCM | direct | ntfy) ──▶ phone app ──▶ watch
```

## Features

- **Live status** — thinking / needs-input / done, current file, elapsed time, token usage.
- **Multiple sessions** — one tab per project (by `cwd`), swipe between them on phone and watch.
- **Watch app** — mirrors the phone over the Wear Data Layer; buzzes on done / needs-input.
- **Watch complications** — *Claude status* (text) and *Claude signal* (red/amber/green dot).
- **Three transports**, pick one in Settings — no Firebase required for the first two:
  - **direct** (default) — phone runs a small HTTP listener on your LAN / Tailscale (mDNS auto-discovery).
  - **ntfy** — self-host with Docker or use ntfy.sh.
  - **FCM** — push via Firebase; paste `google-services.json` in Settings, no rebuild needed.
- **Persistent notification** (optional) mirroring the home screen.

## Repo layout

| Path | What |
|------|------|
| `mobile/` | Phone app (Kotlin) — UI, transports, watch sync, notifications |
| `wear/`   | Wear OS app (Compose) — status screen + complications |
| `tools/`  | `notify.py` hook + `send_test.py` battery — see [tools/README.md](tools/README.md) |

## Quick start

1. **Build & install** the apps from Android Studio (`:mobile` to your phone, `:wear` to the watch).
2. **Pick a transport** in the phone app's Settings (defaults to *direct* — no Firebase).
3. **Wire the hook** — set up `tools/.venv` and point Claude Code's hooks at `notify.py`.
   Full steps (venv, config, transports, FCM) are in **[tools/README.md](tools/README.md)**.

`.claude/settings.json` is gitignored (it holds machine-specific paths); use
`$CLAUDE_PROJECT_DIR` in your own copy so it stays portable.

## License

[MIT](LICENSE)
