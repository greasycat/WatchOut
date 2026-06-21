# WatchOut — Claude Code plugin

Streams Claude Code status to the [WatchOut](../README.md) phone + Wear OS app.

```
Claude Code hooks ──▶ notify.py ──▶ (direct | ntfy | fcm) ──▶ phone app ──▶ watch
```

`notify.py` classifies each hook into a status (thinking / needs-input / done) with the
current file, elapsed time, and token usage, then ships it over the chosen
[transport](#transports). `direct` (default) and `ntfy` are **pure stdlib** — no `pip
install`, no venv. Only `fcm` needs `google-auth`.

## Install

```sh
# add this repo as a plugin marketplace, then install
claude plugin marketplace add greasycat/WatchOut
claude plugin install watchout@watchout
```

The hooks (`UserPromptSubmit`, `PreToolUse` on Edit/Write, `Notification`, `Stop`) are
wired automatically — no editing `.claude/settings.json`.

## Configure

Create **`~/.config/watchout/config.json`** (the path the hooks read by default; override
with the `WATCHOUT_CONFIG` env var). Start from [`config.example.json`](config.example.json):

```jsonc
{ "transport": "direct" }            // phone auto-discovered on the LAN via mDNS
```

Then pick the matching transport in the phone app's Settings. That's it for `direct`.

Sanity check (no network — exercises the parse/classify logic):

```sh
python3 "$(claude plugin root watchout)/scripts/notify.py" --selftest
```

## Transports

The hook reads `transport` from the config — `direct` (default), `ntfy`, or `fcm`.

### direct (phone-as-server, LAN / Tailscale) — default, stdlib

- Phone app → Settings → **Direct**; note the shown `IP:port`, tap **Test listener**.
- Config: `"transport":"direct"`. Optional `"direct_host"`/`"direct_port"` — omit the host
  to auto-discover via mDNS (needs the `zeroconf` pip package; without it, set `direct_host`).

### ntfy (self-host or ntfy.sh) — stdlib

```sh
docker run -d --name ntfy -p 8080:80 binwiederhier/ntfy serve
```

- Phone app → Settings → **ntfy**; server `http://<host-ip>:8080`, pick a topic.
- Config: `"transport":"ntfy"`, `"ntfy_server":"http://<host-ip>:8080"`, `"ntfy_topic":"<topic>"`.

### fcm (Firebase push) — needs `google-auth`

```sh
pip install -r "$(claude plugin root watchout)/requirements.txt"
```

1. Firebase console → create project → add an **Android app** (`io.greasycat.watchout`).
2. Paste the `google-services.json` contents into the phone app's Settings (runtime, no rebuild).
3. Project settings → **Service accounts** → *Generate new private key* → save the JSON locally.
4. Config: `"transport":"fcm"`, `"project_id"`, `"service_account":"/abs/path/key.json"`,
   `"device_token":"<shown on the phone screen>"`.

## Notes

- A hook never crashes the session: every failure logs to stderr and exits 0.
- `scripts/send_test.py` fires a battery of sample statuses through the real `send()` to
  eyeball how each renders: `python3 scripts/send_test.py --list`.
