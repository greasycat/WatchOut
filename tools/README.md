# Claude Code → WatchOut (the notifier hook)

Event path:

```
Claude Code hooks ──▶ notify.py ──▶ (FCM | direct | ntfy) ──▶ phone app ──▶ watch
```

`notify.py` classifies each hook into a status (thinking / needs-input / done) and ships
it over the chosen [transport](#transports). The phone app renders it and relays to the
watch over the Data Layer. FCM is one option; `direct` and `ntfy` need no Firebase.

## One-time Firebase setup (only for the `fcm` transport)

Skip this entirely if you use `direct` or `ntfy`.

1. Create a Firebase project at <https://console.firebase.google.com>.
2. Add an **Android app** with package `io.greasycat.watchout`.
3. Download **`google-services.json`** — you paste its contents into the phone app's
   Settings at runtime (no rebuild, no plugin), so it never needs to be on disk in the repo.
4. Project settings → **Service accounts** → *Generate new private key* → save the JSON on
   the dev machine (this is the credential `notify.py` sends with — keep it off git).

## Dev-machine setup

```sh
python3 -m venv tools/.venv
tools/.venv/bin/pip install google-auth requests
cp tools/config.example.json tools/config.json
# edit config.json: service_account path, project_id, device_token
tools/.venv/bin/python tools/notify.py --selftest   # no network; checks the mapping/parse logic
```

(Arch and other PEP-668 distros block a system `pip install`; the venv sidesteps that
and is why the hook commands below call `tools/.venv/bin/python` instead of `python3`.)

`device_token` comes from the phone: build & run `:mobile`, the token is shown on screen
(and in logcat under tag `ClaudeFCM`). Copy it in.

## Wire up the hooks

In `.claude/settings.json` (project or `~/.claude/settings.json`), point the hooks at the
script. `$CLAUDE_PROJECT_DIR` expands to the repo root, so this works for anyone who clones:

```json
{
  "hooks": {
    "Notification": [
      { "hooks": [ { "type": "command", "command": "$CLAUDE_PROJECT_DIR/tools/.venv/bin/python $CLAUDE_PROJECT_DIR/tools/notify.py" } ] }
    ],
    "Stop": [
      { "hooks": [ { "type": "command", "command": "$CLAUDE_PROJECT_DIR/tools/.venv/bin/python $CLAUDE_PROJECT_DIR/tools/notify.py" } ] }
    ],
    "PreToolUse": [
      { "matcher": "Edit|Write",
        "hooks": [ { "type": "command", "command": "$CLAUDE_PROJECT_DIR/tools/.venv/bin/python $CLAUDE_PROJECT_DIR/tools/notify.py" } ] }
    ]
  }
}
```

- `Notification` → "Claude needs you" (the come-back buzz).
- `Stop` → "Claude finished".
- `PreToolUse` on Edit/Write → "Claude working" + the file name.

## End-to-end test

```sh
echo '{"hook_event_name":"Stop","session_id":"abc123"}' | tools/.venv/bin/python tools/notify.py
```

The phone (and mirrored watch) should buzz with "Claude finished". If not, the FCM HTTP
status is logged to stderr.

## Transports

The hook reads `transport` from `config.json` — `direct` (default), `ntfy`, or `fcm`.
Pick the matching transport in the phone app's Settings. `direct` and `ntfy` skip the
Firebase setup above entirely.

### ntfy (self-host with Docker)

```sh
docker run -d --name ntfy -p 8080:80 binwiederhier/ntfy serve
```

- Phone app → Settings → **ntfy**; server `http://<host-ip>:8080` (emulator: `http://10.0.2.2:8080`), pick a topic.
- `config.json`: `"transport":"ntfy"`, `"ntfy_server":"http://<host-ip>:8080"`, `"ntfy_topic":"<same topic>"`.
- The app subscribes over WebSocket; the hook publishes with one HTTP POST. Plain HTTP works (the app allows cleartext).

### direct (phone-as-server, LAN / Tailscale)

- Phone app → Settings → **Direct**; note the shown `IP:port` and tap **Test listener**.
- `config.json`: `"transport":"direct"`, optional `"direct_host"`/`"direct_port"` (omit host to auto-discover via mDNS — needs the `zeroconf` pip package, already in the venv).
