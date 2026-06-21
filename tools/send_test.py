#!/usr/bin/env python3
"""Send a battery of test status messages through FCM to eyeball how each one
renders on the phone / watch. Reuses notify.send(), so it exercises the real
data payload the phone's onMessageReceived() will see.

    tools/.venv/bin/python tools/send_test.py             # send all, 4s apart
    tools/.venv/bin/python tools/send_test.py --delay 8   # dwell longer on each
    tools/.venv/bin/python tools/send_test.py --only done thinking
    tools/.venv/bin/python tools/send_test.py --list      # case names, no network
    tools/.venv/bin/python tools/send_test.py --dry-run   # print payloads, no network

Note: the app uses a fixed notification id, so each message REPLACES the previous
one on the phone — watch them cycle, or raise --delay to dwell on each.
"""
import argparse
import sys
import time

import notify  # same dir; its top-level imports are stdlib-only

# label -> data payload exactly as the phone's onMessageReceived() receives it.
BATTERY = {
    "needs_input": {"status": "needs_input", "file": "",
                    "summary": "Allow Bash(rm -rf build)?", "session": "test1234",
                    "tok_in": "9800", "tok_out": "2100", "elapsed_s": "37"},
    "thinking": {"status": "thinking", "file": "MainActivity.kt",
                 "summary": "wiring the clipboard copy", "session": "test1234",
                 "tok_in": "12300", "tok_out": "4600", "elapsed_s": "83"},
    "done": {"status": "done", "file": "",
             "summary": "build green, 2 warnings", "session": "test1234",
             "tok_in": "45200", "tok_out": "8900", "elapsed_s": "142"},
    "update": {"status": "update", "file": "build.gradle.kts",
               "summary": "", "session": "test1234"},
    "empty": {"status": "done", "file": "", "summary": "", "session": ""},
    "long": {"status": "thinking",
             "file": "deeply/nested/path/SomeVeryLongFileNameThatMightWrap.kt",
             "summary": "a long thinking summary line that exceeds typical "
                        "notification width to check truncation on the watch "
                        "face and the phone shade",
             "session": "test1234",
             "tok_in": "238000", "tok_out": "61000", "elapsed_s": "734"},
}


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--delay", type=float, default=4.0, help="seconds between sends")
    p.add_argument("--only", nargs="+", metavar="CASE", help="send only these cases")
    p.add_argument("--session", default="test1234", help="session id (one tab per id)")
    p.add_argument("--project", default="WatchOut", help="project/session display name")
    p.add_argument("--list", action="store_true", help="list case names and exit")
    p.add_argument("--dry-run", action="store_true", help="print payloads, no network")
    args = p.parse_args()

    if args.list:
        print("\n".join(BATTERY))
        return 0

    cases = args.only or list(BATTERY)
    unknown = [c for c in cases if c not in BATTERY]
    if unknown:
        print(f"unknown case(s): {', '.join(unknown)} (try --list)", file=sys.stderr)
        return 1

    for i, name in enumerate(cases):
        data = {**BATTERY[name], "session": args.session, "project": args.project}
        print(f"[{name}] {data}")
        if not args.dry_run:
            notify.send(data)
            if i < len(cases) - 1:
                time.sleep(args.delay)
    return 0


if __name__ == "__main__":
    sys.exit(main())
