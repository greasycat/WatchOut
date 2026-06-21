---
description: Update the WatchOut connection config (transport + fields) with guidance
argument-hint: "[direct|ntfy|fcm]"
allowed-tools: Read, Write, AskUserQuestion, Bash(cat:*), Bash(test:*), Bash(mkdir:*)
---

Help the user change the WatchOut phone/watch **connection config**. It is a JSON file at
`$WATCHOUT_CONFIG` if that env var is set, otherwise `~/.config/watchout/config.json`.

User-requested transport (may be empty): `$ARGUMENTS`

Do this:

1. **Find & show current config.** Resolve the path above. If the file exists, read it and
   show the current `transport` and its fields — but **never print secret values**
   (`device_token`, `service_account`); just say whether each is set. If it doesn't exist,
   say so; you'll create it.

2. **Pick the transport.** If `$ARGUMENTS` already names one of `direct`/`ntfy`/`fcm`, use it.
   Otherwise ask with AskUserQuestion. What each needs:
   - **direct** (default, no Firebase) — phone runs an HTTP listener on your LAN/Tailscale.
     `direct_host` = the phone's IP (shown in the app's Settings → Direct), or omit it to
     auto-discover via mDNS. Optional `direct_port` (default `8787`).
   - **ntfy** (no Firebase) — `ntfy_server` (e.g. `https://ntfy.sh` or `http://<host>:8080`)
     and `ntfy_topic` (must match the app's Settings → ntfy).
   - **fcm** (Firebase push) — `project_id`, `service_account` (absolute path to the
     service-account key JSON), and `device_token` (shown on the phone screen). Needs
     `google-auth` installed for the hook's python3.

3. **Collect only the fields the chosen transport needs.** Ask for any that are missing or
   that the user wants to change; keep existing values otherwise. Don't invent IPs/tokens —
   ask the user for the real values.

4. **Write the config.** Create the parent dir if needed, write valid JSON (2-space indent)
   to the resolved path. Keep it minimal: the `transport` key plus only that transport's
   fields (drop stale keys from other transports unless the user wants them kept).

5. **Confirm.** Print the final config with secrets masked, and remind the user to select the
   matching transport in the phone app's Settings. The change takes effect on the next hook
   fire (a new Claude Code session, since hook config is read at startup).

Validation before writing: `direct` needs `direct_host` or an explicit choice to use mDNS;
`ntfy` needs both `ntfy_server` and `ntfy_topic`; `fcm` needs `project_id`,
`service_account`, and `device_token`. If something required is missing, ask — don't write a
half-configured file.
