#!/usr/bin/env python3
"""Claude Code hook -> WatchOut phone app (status update).

Reads the hook JSON on stdin, classifies it (thinking / needs-input / done +
current file, elapsed, tokens), and ships it over the configured transport:
  - direct (default): HTTP POST to the phone on the LAN  [stdlib only]
  - ntfy:             publish to an ntfy topic            [stdlib only]
  - fcm:              Firebase push                        [needs `google-auth`]

Config resolution (see _config_path): $WATCHOUT_CONFIG, else
$CLAUDE_PLUGIN_DATA/config.json (when run as a plugin), else config.json next to
this script. See the plugin README for the schema and setup.

A hook must never crash the session, so every failure logs to stderr and exits 0.
"""
import datetime
import json
import os
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
SCOPES = ["https://www.googleapis.com/auth/firebase.messaging"]


def classify(hook: dict) -> tuple[str, str]:
    """(status, file_path) from a Claude Code hook payload."""
    event = hook.get("hook_event_name", "")
    tool_input = hook.get("tool_input") or {}
    if event == "Notification":
        return "needs_input", ""
    if event == "Stop":
        return "done", ""
    if event == "UserPromptSubmit":  # turn started → blob starts animating
        return "thinking", ""
    if event == "PreToolUse":
        return "thinking", tool_input.get("file_path", "")
    return "update", tool_input.get("file_path", "")


def last_thinking(transcript_path: str) -> str:
    """Best-effort: last line of the most recent thinking block in the transcript."""
    if not transcript_path:
        return ""
    try:
        lines = pathlib.Path(transcript_path).read_text().splitlines()
    except OSError:
        return ""
    for line in reversed(lines[-40:]):
        try:
            obj = json.loads(line)
        except ValueError:
            continue
        content = obj.get("message", {}).get("content")
        if not isinstance(content, list):
            continue
        for block in content:
            if block.get("type") == "thinking" and block.get("thinking"):
                return block["thinking"].strip().splitlines()[-1][:120]
    return ""


def _parse_ts(ts: str):
    try:
        dt = datetime.datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except (ValueError, AttributeError):
        return None
    return dt.replace(tzinfo=datetime.timezone.utc) if dt.tzinfo is None else dt


def _load(transcript_path: str) -> list:
    if not transcript_path:
        return []
    try:
        lines = pathlib.Path(transcript_path).read_text().splitlines()
    except OSError:
        return []
    out = []
    for line in lines:
        try:
            out.append(json.loads(line))
        except ValueError:
            continue
    return out


def _is_human_prompt(obj: dict) -> bool:
    """A real typed-by-the-user prompt — not a tool result, meta, or subagent turn.

    In Claude Code transcripts tool results are ALSO type "user"; the actual turn
    boundary is a user entry whose content isn't a tool_result.
    """
    if obj.get("type") != "user" or obj.get("isMeta") or obj.get("isSidechain"):
        return False
    content = obj.get("message", {}).get("content")
    if isinstance(content, str):
        return True
    if isinstance(content, list):
        return not any(isinstance(b, dict) and b.get("type") == "tool_result" for b in content)
    return False


def _turn_start(objs: list):
    """Timestamp of the latest human prompt — the current turn's start. None if unknown."""
    start = None
    for obj in objs:
        if _is_human_prompt(obj):
            ts = _parse_ts(obj.get("timestamp", ""))
            if ts:
                start = ts
    return start


def turn_elapsed_seconds(transcript_path: str):
    """Seconds since the current turn's human prompt. None if unknown."""
    start = _turn_start(_load(transcript_path))
    if start is None:
        return None
    now = datetime.datetime.now(datetime.timezone.utc)
    return max(0, int((now - start).total_seconds()))


def token_usage(transcript_path: str):
    """(input, output) tokens for the CURRENT turn only — main thread, cache excluded.

    Claude Code logs one assistant line per content block, all carrying the same
    per-request `usage`, so we dedup by requestId to avoid 2-3x over-counting.
    """
    objs = _load(transcript_path)
    start = _turn_start(objs)
    seen = set()
    tin = tout = 0
    for obj in objs:
        if obj.get("type") != "assistant" or obj.get("isSidechain"):
            continue
        if start is not None:
            ts = _parse_ts(obj.get("timestamp", ""))
            if ts is not None and ts < start:
                continue
        usage = obj.get("message", {}).get("usage")
        if not isinstance(usage, dict):
            continue
        rid = obj.get("requestId") or obj.get("message", {}).get("id")
        if rid is not None:
            if rid in seen:
                continue
            seen.add(rid)
        tin += usage.get("input_tokens", 0) or 0
        tout += usage.get("output_tokens", 0) or 0
    return tin, tout


def build_data(hook: dict) -> dict:
    status, file_path = classify(hook)
    transcript = hook.get("transcript_path", "")
    if hook.get("hook_event_name") == "UserPromptSubmit":
        # Turn just started: the prompt may not be in the transcript yet, and
        # nothing's been consumed. Report a clean zero so the phone anchors at 0
        # (otherwise we'd anchor to the *previous* turn — the "too long" bug).
        tok_in, tok_out, elapsed = 0, 0, 0
    else:
        tok_in, tok_out = token_usage(transcript)
        elapsed = turn_elapsed_seconds(transcript)
    cwd = (hook.get("cwd") or "").rstrip("/")
    return {
        "status": status,
        "file": os.path.basename(file_path) if file_path else "",
        "summary": last_thinking(transcript),
        "session": hook.get("session_id") or "",
        # Live project name: basename of the session's CURRENT cwd, so it follows `cd`.
        "project": os.path.basename(cwd) or "—",
        "tok_in": str(tok_in),
        "tok_out": str(tok_out),
        "elapsed_s": "" if elapsed is None else str(elapsed),
    }


def _access_token(service_account: str) -> str:
    import google.auth.transport.requests
    from google.oauth2 import service_account as sa

    creds = sa.Credentials.from_service_account_file(service_account, scopes=SCOPES)
    creds.refresh(google.auth.transport.requests.Request())
    return creds.token


def _config_path() -> pathlib.Path:
    """Where config.json lives. As a plugin, CLAUDE_PLUGIN_DATA survives updates;
    locally it sits next to this script. WATCHOUT_CONFIG overrides everything."""
    env = os.environ.get("WATCHOUT_CONFIG")
    if env:
        return pathlib.Path(env)
    data = os.environ.get("CLAUDE_PLUGIN_DATA")
    if data:
        return pathlib.Path(data) / "config.json"
    return HERE / "config.json"


def _http_post(url: str, body: bytes, headers: dict | None = None, timeout: int = 10):
    """POST bytes via stdlib urllib. Returns (status, text); HTTP errors come back
    as their status (not raised) so callers can log the body. Connection errors raise."""
    import urllib.error
    import urllib.request

    req = urllib.request.Request(url, data=body, headers=headers or {}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode(errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")


def send(data: dict) -> None:
    """Dispatch to the configured transport: direct | ntfy | fcm."""
    cfg = json.loads(_config_path().read_text())
    transport = cfg.get("transport", "direct")
    if transport == "ntfy":
        _send_ntfy(cfg, data)
    elif transport == "fcm":
        _send_fcm(cfg, data)
    else:
        _send_direct(cfg, data)


def _send_fcm(cfg: dict, data: dict) -> None:
    # The only transport needing a third-party dep (google-auth, for JWT signing).
    url = f"https://fcm.googleapis.com/v1/projects/{cfg['project_id']}/messages:send"
    body = {"message": {"token": cfg["device_token"], "data": data, "android": {"priority": "high"}}}
    try:
        status, text = _http_post(
            url,
            json.dumps(body).encode(),
            {
                "Authorization": f"Bearer {_access_token(cfg['service_account'])}",
                "Content-Type": "application/json",
            },
        )
        if status >= 300:
            print(f"[notify] FCM {status}: {text}", file=sys.stderr)
    except Exception as exc:
        print(f"[notify] FCM post failed: {exc}", file=sys.stderr)


def _send_direct(cfg: dict, data: dict) -> None:
    host = cfg.get("direct_host") or _discover_host()
    if not host:
        print("[notify] direct: no host (set direct_host, or enable mDNS)", file=sys.stderr)
        return
    port = cfg.get("direct_port", 8787)
    headers = {"Content-Type": "application/json"}
    # Optional shared secret — must match the token shown in the phone's Direct settings.
    # Omit it and the phone accepts any LAN/Tailscale POST (legacy, less safe).
    if cfg.get("direct_token"):
        headers["X-WatchOut-Token"] = cfg["direct_token"]
    try:
        _http_post(f"http://{host}:{port}/", json.dumps(data).encode(), headers, timeout=4)
    except Exception as exc:
        print(f"[notify] direct post failed: {exc}", file=sys.stderr)


def _send_ntfy(cfg: dict, data: dict) -> None:
    server = (cfg.get("ntfy_server") or "https://ntfy.sh").rstrip("/")
    topic = cfg.get("ntfy_topic")
    if not topic:
        print("[notify] ntfy: no topic set", file=sys.stderr)
        return
    try:
        # JSON as the raw message body so ntfy delivers it verbatim (not a publish envelope).
        _http_post(f"{server}/{topic}", json.dumps(data).encode(), timeout=6)
    except Exception as exc:
        print(f"[notify] ntfy post failed: {exc}", file=sys.stderr)


_HOST_CACHE_TTL = 300  # seconds — mDNS is slow (~1.5s); don't re-discover every hook fire.
_O_NOFOLLOW = getattr(os, "O_NOFOLLOW", 0)  # not on Windows


def _host_cache_path() -> pathlib.Path:
    """Per-user, owner-only cache dir — NOT shared /tmp. The cached host decides where
    we POST the direct_token, so a world-writable predictable path would let another
    local user redirect the secret. ~/.cache is theirs alone."""
    base = pathlib.Path(os.path.expanduser("~/.cache/watchout"))
    base.mkdir(parents=True, exist_ok=True, mode=0o700)
    return base / "direct_host"


def _read_cached_host():
    """Fresh cached host, or None. Refuses anything not a regular owner-only file we own,
    and never follows a symlink — the value gates where the token is sent."""
    import time as _time

    try:
        fd = os.open(_host_cache_path(), os.O_RDONLY | _O_NOFOLLOW)
    except OSError:
        return None
    try:
        st = os.fstat(fd)
        if hasattr(os, "geteuid") and (st.st_uid != os.geteuid() or st.st_mode & 0o077):
            return None  # not ours, or group/world-accessible → don't trust it
        if _time.time() - st.st_mtime >= _HOST_CACHE_TTL:
            return None
        return os.read(fd, 256).decode(errors="replace").strip() or None
    finally:
        os.close(fd)


def _write_cached_host(host: str) -> None:
    try:
        fd = os.open(_host_cache_path(),
                     os.O_WRONLY | os.O_CREAT | os.O_TRUNC | _O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(host)
    except OSError:
        pass


def _discover_host():
    """The phone's host, cached so we don't pay the mDNS 1.5s sleep on every hook.
    Cache hit (fresh, owner-only) skips zeroconf entirely; miss re-discovers and rewrites."""
    host = _read_cached_host()
    if host:
        return host
    host = _mdns_discover()
    if host:
        _write_cached_host(host)
    return host


def _mdns_discover():
    """Best-effort mDNS lookup of the phone's _watchout._tcp service."""
    try:
        import socket
        import time as _time

        from zeroconf import ServiceBrowser, Zeroconf

        found = {}

        class _Listener:
            def add_service(self, zc, type_, name):
                info = zc.get_service_info(type_, name)
                if info and info.addresses:
                    found["host"] = socket.inet_ntoa(info.addresses[0])

            def update_service(self, *a):
                pass

            def remove_service(self, *a):
                pass

        zc = Zeroconf()
        ServiceBrowser(zc, "_watchout._tcp.local.", _Listener())
        _time.sleep(1.5)
        zc.close()
        return found.get("host")
    except Exception:
        return None


def main() -> int:
    if "--selftest" in sys.argv:
        return _selftest()
    hook = json.load(sys.stdin)
    send(build_data(hook))
    return 0


def _selftest() -> int:
    import tempfile

    assert classify({"hook_event_name": "Notification"})[0] == "needs_input"
    assert classify({"hook_event_name": "Stop"})[0] == "done"
    assert classify({"hook_event_name": "UserPromptSubmit"})[0] == "thinking"
    s, f = classify({"hook_event_name": "PreToolUse",
                     "tool_input": {"file_path": "/a/b/Foo.kt"}})
    assert (s, f) == ("thinking", "/a/b/Foo.kt")
    assert build_data({"hook_event_name": "PreToolUse",
                       "tool_input": {"file_path": "/a/b/Foo.kt"}})["file"] == "Foo.kt"

    with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False) as tf:
        tf.write(json.dumps({"message": {"content": [
            {"type": "thinking", "thinking": "step one\nfinal thought"},
        ]}}) + "\n")
        path = tf.name
    assert last_thinking(path) == "final thought", last_thinking(path)
    assert last_thinking("/no/such/file") == ""
    os.unlink(path)

    with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False) as tf2:
        # previous turn — must be excluded from token totals
        tf2.write(json.dumps({"type": "user", "timestamp": "2020-01-01T00:00:00Z",
                              "message": {"role": "user", "content": "old prompt"}}) + "\n")
        tf2.write(json.dumps({"type": "assistant", "timestamp": "2020-01-01T00:00:01Z",
                              "message": {"usage": {"input_tokens": 999, "output_tokens": 999}}}) + "\n")
        # current turn
        tf2.write(json.dumps({"type": "user", "timestamp": "2020-01-01T00:10:00Z",
                              "message": {"role": "user", "content": "new prompt"}}) + "\n")
        tf2.write(json.dumps({"type": "assistant", "timestamp": "2020-01-01T00:10:01Z",
                              "requestId": "rA",
                              "message": {"usage": {"input_tokens": 100, "output_tokens": 50}}}) + "\n")
        # a tool_result is type "user" but must NOT count as a new turn start
        tf2.write(json.dumps({"type": "user", "timestamp": "2020-01-01T00:10:05Z",
                              "message": {"role": "user",
                                          "content": [{"type": "tool_result", "content": "ok"}]}}) + "\n")
        tf2.write(json.dumps({"type": "assistant", "timestamp": "2020-01-01T00:10:06Z",
                              "requestId": "rB",
                              "message": {"usage": {"input_tokens": 200, "output_tokens": 70}}}) + "\n")
        # duplicate content-block line for the SAME request — must be deduped
        tf2.write(json.dumps({"type": "assistant", "timestamp": "2020-01-01T00:10:06Z",
                              "requestId": "rB",
                              "message": {"usage": {"input_tokens": 200, "output_tokens": 70}}}) + "\n")
        path2 = tf2.name
    # current turn only, requestId-deduped: 100+200 in, 50+70 out — old-turn 999s excluded
    assert token_usage(path2) == (300, 120), token_usage(path2)
    elapsed = turn_elapsed_seconds(path2)
    assert elapsed is not None and elapsed >= 0, elapsed
    assert token_usage("/no/such/file") == (0, 0)
    # UserPromptSubmit reports a clean zero start regardless of transcript
    d0 = build_data({"hook_event_name": "UserPromptSubmit", "transcript_path": path2})
    assert d0["tok_in"] == "0" and d0["tok_out"] == "0" and d0["elapsed_s"] == "0", d0
    os.unlink(path2)

    # project name = basename of cwd (follows cd)
    d1 = build_data({"hook_event_name": "Stop", "transcript_path": "/no", "cwd": "/home/u/MyProj/"})
    assert d1["project"] == "MyProj", d1["project"]

    # direct host cache: a fresh owner-only cache file is returned without touching mDNS
    _write_cached_host("10.1.2.3")
    assert _read_cached_host() == "10.1.2.3", _read_cached_host()
    assert _discover_host() == "10.1.2.3", _discover_host()
    cache = _host_cache_path()
    assert cache.stat().st_mode & 0o077 == 0, "cache must not be group/world accessible"
    cache.unlink()

    print("notify selftest: OK")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # a hook must never crash the session
        print(f"[notify] {exc}", file=sys.stderr)
        sys.exit(0)
