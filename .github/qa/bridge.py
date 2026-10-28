#!/usr/bin/env python3
"""The emulators of one job of a nightly QA run (qa-devices.yml).

Builds the run's apps, boots a few Android 8.1 emulators shaped like the
XP3, installs the apps on each, and then lends them to the command center:
it says hello, long-polls for the commands QA agents send with
`delta qa device ...`, runs each with adb, and posts the result back. See
"Nightly QA" in thooton/delta's AGENTS.md.

    QA_DELTA_SERVER=https://... QA_DEVICE_TOKEN=... \
      python3 .github/qa/bridge.py --run <run id> --runner <id> --apps "list mail"

Nothing secret is printed: the logs are public. Only which command ran on
which emulator, and whether it worked.
"""
import argparse
import base64
import json
import os
import re
import shlex
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "/usr/local/lib/android/sdk")
ADB = str(SDK / "platform-tools" / "adb")
EMULATOR = str(SDK / "emulator" / "emulator")
IMAGE = "system-images;android-27;default;x86_64"
BUILD_TOOLS = os.environ.get("ANDROID_BUILD_TOOLS", "35.0.1")
DEVICES = 3
# Where avdmanager writes the emulators and the emulator finds them: newer
# command-line tools default to somewhere else than the emulator looks.
AVD_HOME = Path.home() / ".android" / "avd"
os.environ["ANDROID_AVD_HOME"] = str(AVD_HOME)
LIFETIME = 5 * 3600 + 45 * 60  # the Actions job's limit is six hours
OUTPUT_LIMIT = 200_000

# The XP3: 240x320 at mdpi, a d-pad and keypad, hardware Back, Home and Menu.
AVD_CONFIG = {
    "hw.lcd.width": "240",
    "hw.lcd.height": "320",
    "hw.lcd.density": "160",
    "hw.keyboard": "yes",
    "hw.dPad": "yes",
    "hw.mainKeys": "yes",
    "hw.ramSize": "1536",
    "vm.heapSize": "128",
    "hw.camera.back": "none",
    "hw.camera.front": "none",
    "hw.audioInput": "no",
    "hw.gpu.enabled": "yes",
    "hw.gpu.mode": "swiftshader_indirect",
    "showDeviceFrame": "no",
    "skin.name": "240x320",
    "skin.path": "_no_skin",
    "disk.dataPartition.size": "2G",
}


def log(*args):
    print("[bridge]", *args, flush=True)


def run(cmd, cwd=None, timeout=None, check=True):
    p = subprocess.run(cmd, cwd=cwd, timeout=timeout, capture_output=True)
    if check and p.returncode != 0:
        tail = (p.stdout + p.stderr).decode(errors="replace")[-4000:]
        raise RuntimeError(f"{cmd[0]} exited {p.returncode}\n{tail}")
    return p


def adb(serial, *args, timeout=120, check=True):
    return run([ADB, "-s", serial, *args], timeout=timeout, check=check)


# ---------------------------------------------------------------------------
# Building
# ---------------------------------------------------------------------------

def gradle_task(dir_):
    return f":{dir_}:app:assembleRelease"


def build(apps, tree=ROOT):
    """Builds apps in tree and returns {dir: apk path}."""
    gradle = [a for a in apps if a != "launcher"]
    if gradle:
        run(["./gradlew", "--no-daemon", "-q", *[gradle_task(a) for a in gradle]], cwd=tree, timeout=3600)
    apks = {}
    for a in gradle:
        for apk in (tree / a / "android/app/build/outputs/apk/release/app-release.apk",
                    tree / a / "app/build/outputs/apk/release/app-release.apk"):
            if apk.exists():
                apks[a] = apk
    if "launcher" in apps:
        env = dict(os.environ, PATH=f"{SDK}/build-tools/{BUILD_TOOLS}:{os.environ['PATH']}")
        p = subprocess.run(["./launcher/build.sh"], cwd=tree, env=env, capture_output=True)
        if p.returncode != 0:
            raise RuntimeError("the launcher's build failed\n" + (p.stdout + p.stderr).decode(errors="replace")[-4000:])
        apks["launcher"] = tree / "launcher" / "AppDrawer.apk"
    return apks


def package_of(apk):
    aapt2 = SDK / "build-tools" / BUILD_TOOLS / "aapt2"
    out = run([str(aapt2), "dump", "packagename", str(apk)]).stdout.decode().strip()
    return out.splitlines()[-1].strip()


# ---------------------------------------------------------------------------
# Emulators
# ---------------------------------------------------------------------------

def boot(n):
    """Creates and boots n emulators; returns their serials once booted."""
    avdmanager = "avdmanager"
    AVD_HOME.mkdir(parents=True, exist_ok=True)
    serials = []
    for i in range(n):
        name = f"xp3_{i}"
        p = subprocess.run([avdmanager, "create", "avd", "-n", name, "-k", IMAGE, "-d", "pixel", "--force"],
                           input=b"no\n", capture_output=True)
        if p.returncode != 0:
            raise RuntimeError("avdmanager: " + (p.stdout + p.stderr).decode(errors="replace")[-2000:])
        ini = AVD_HOME / f"{name}.avd" / "config.ini"
        lines = [l for l in ini.read_text().splitlines() if l.split("=", 1)[0].strip() not in AVD_CONFIG]
        lines += [f"{k}={v}" for k, v in AVD_CONFIG.items()]
        ini.write_text("\n".join(lines) + "\n")
        port = 5554 + 2 * i
        subprocess.Popen([EMULATOR, "-avd", name, "-port", str(port), "-no-window", "-no-audio",
                          "-no-boot-anim", "-no-snapshot", "-gpu", "swiftshader_indirect", "-accel", "on"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        serials.append(f"emulator-{port}")
    return serials


def wait_booted(serials, timeout=900):
    deadline = time.time() + timeout
    for s in serials:
        while True:
            p = adb(s, "shell", "getprop", "sys.boot_completed", timeout=30, check=False)
            if p.stdout.decode().strip() == "1":
                break
            if time.time() > deadline:
                raise RuntimeError(f"{s} didn't boot")
            time.sleep(5)
        # No animations (steadier screenshots), the screen always on, and
        # Android's setup and lock screen out of the way.
        for cmd in ("settings put global window_animation_scale 0",
                    "settings put global transition_animation_scale 0",
                    "settings put global animator_duration_scale 0",
                    "settings put global stay_on_while_plugged_in 7",
                    "settings put secure show_ime_with_hard_keyboard 0",
                    "locksettings set-disabled true",
                    "input keyevent KEYCODE_WAKEUP",
                    "input keyevent KEYCODE_MENU"):
            adb(s, "shell", cmd, check=False)
        log(s, "booted")


def install(serial, apk):
    adb(serial, "install", "-r", "-d", "-g", str(apk), timeout=300)


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

class Bridge:
    def __init__(self, args, serials, apks):
        self.args = args
        self.serials = serials
        self.apks = dict(apks)  # dir -> apk, as last built
        self.packages = {a: package_of(p) for a, p in apks.items()}
        self.locks = {s: threading.Lock() for s in serials}
        self.build_lock = threading.Lock()
        self.worktrees = 0

    def package(self, app):
        if app not in self.packages:
            raise RuntimeError(f"{app} wasn't built for this run")
        return self.packages[app]

    def op_key(self, serial, app, args):
        if not args:
            raise RuntimeError("name at least one key (DPAD_DOWN, BACK, 5, ...)")
        codes = []
        for k in args:
            k = k.strip().upper()
            if not re.fullmatch(r"[A-Z0-9_]+", k):
                raise RuntimeError(f"{k!r} isn't a key name")
            codes.append(k if k.isdigit() and len(k) > 1 else ("KEYCODE_" + k.removeprefix("KEYCODE_")))
        for code in codes:
            adb(serial, "shell", "input", "keyevent", code)
            time.sleep(0.25)
        time.sleep(0.5)
        return {"output": ""}

    def op_text(self, serial, app, args):
        text = " ".join(args)
        escaped = "".join("%s" if c == " " else ("\\" + c if c in "\\\"'()<>|;&*~$`!?#[]{}" else c) for c in text)
        adb(serial, "shell", "input", "text", escaped)
        time.sleep(0.5)
        return {"output": ""}

    def op_shot(self, serial, app, args):
        png = adb(serial, "exec-out", "screencap", "-p").stdout
        if not png.startswith(b"\x89PNG"):
            raise RuntimeError("the screenshot failed")
        return {"output": "", "file": base64.b64encode(png).decode()}

    def op_ui(self, serial, app, args):
        adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=60)
        return {"output": adb(serial, "exec-out", "cat", "/sdcard/ui.xml").stdout.decode(errors="replace")}

    def op_log(self, serial, app, args):
        if args and args[0] == "--clear":
            adb(serial, "logcat", "-c")
            return {"output": "Cleared the log."}
        out = adb(serial, "logcat", "-d", "-v", "time", "*:W").stdout.decode(errors="replace")
        lines = out.splitlines()[-400:]
        return {"output": "\n".join(lines) or "Nothing logged at warning level or above."}

    def op_launch(self, serial, app, args):
        pkg = self.package(app)
        adb(serial, "shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(2)
        return {"output": ""}

    def op_stop(self, serial, app, args):
        adb(serial, "shell", "am", "force-stop", self.package(app))
        return {"output": ""}

    def op_reset(self, serial, app, args):
        adb(serial, "shell", "pm", "clear", self.package(app))
        return {"output": ""}

    def op_shell(self, serial, app, args):
        p = adb(serial, "shell", " ".join(args), timeout=280, check=False)
        out = (p.stdout + p.stderr).decode(errors="replace")
        return {"output": out, "ok": p.returncode == 0, "error": "" if p.returncode == 0 else f"exited {p.returncode}"}

    def op_install(self, serial, app, args):
        ref = args[0] if args else "master"
        if not re.fullmatch(r"[A-Za-z0-9._/-]+", ref) or ref.startswith("-"):
            raise RuntimeError(f"{ref!r} isn't a branch or commit")
        with self.build_lock:
            self.worktrees += 1
            tree = Path(f"/tmp/qa-tree-{self.worktrees}")
            run(["git", "fetch", "-q", "origin", ref], cwd=ROOT, timeout=300)
            run(["git", "worktree", "add", "-f", "--detach", str(tree), "FETCH_HEAD"], cwd=ROOT)
            try:
                charge = ROOT / "charge/android/.tokens.json"
                if charge.exists():
                    (tree / "charge/android").mkdir(parents=True, exist_ok=True)
                    (tree / "charge/android/.tokens.json").write_bytes(charge.read_bytes())
                apk = build([app], tree)[app]
                kept = Path(f"/tmp/qa-{app}-{self.worktrees}.apk")
                kept.write_bytes(apk.read_bytes())
            finally:
                run(["git", "worktree", "remove", "--force", str(tree)], cwd=ROOT, check=False)
        self.packages[app] = package_of(kept)
        install(serial, kept)
        commit = run(["git", "rev-parse", "--short", "FETCH_HEAD"], cwd=ROOT).stdout.decode().strip()
        return {"output": f"Built {app} at {ref} ({commit}) and installed it."}

    def handle(self, cmd):
        serial, op, app, args = cmd.get("serial"), cmd.get("op"), cmd.get("app"), cmd.get("args") or []
        fn = getattr(self, "op_" + str(op), None)
        res = {"id": cmd.get("id"), "ok": True, "output": ""}
        if serial not in self.locks or fn is None:
            res.update(ok=False, error=f"can't do {op!r} on {serial!r}")
        else:
            with self.locks[serial]:
                try:
                    res.update(fn(serial, app, args))
                except subprocess.TimeoutExpired:
                    res.update(ok=False, error="it took too long")
                except Exception as e:  # reported to the agent, not the log
                    res.update(ok=False, error=str(e)[-6000:])
        res["output"] = res.get("output", "")[-OUTPUT_LIMIT:]
        log(f"{op} on {serial}: {'ok' if res['ok'] else 'failed'}")
        try:
            self.call("POST", "/qa/device/result", res)
        except Exception as e:
            log("couldn't post a result:", type(e).__name__)

    # -----------------------------------------------------------------------

    def call(self, method, path, body=None, timeout=60):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.args.server + path, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self.args.token)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read() or b"{}")

    def hello(self):
        return self.call("POST", "/qa/device/hello", {"runner": self.args.runner, "run": self.args.run, "serials": self.serials})

    def serve(self):
        started = time.time()
        if self.hello().get("quit"):
            log("the run is over")
            return
        log("lent", len(self.serials), "emulators to the run")
        failures = 0
        while time.time() - started < LIFETIME:
            try:
                got = self.call("GET", "/qa/device/poll?runner=" + urllib.parse.quote(self.args.runner), timeout=60)
                failures = 0
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    if self.hello().get("quit"):
                        return
                    continue
                failures += 1
                time.sleep(min(60, 2 ** failures))
                continue
            except Exception:
                failures += 1
                if failures > 30:
                    log("the command center is out of reach; giving up")
                    return
                time.sleep(min(60, 2 ** min(failures, 6)))
                continue
            if got.get("quit"):
                log("the run is done with these emulators")
                return
            for cmd in got.get("commands") or []:
                threading.Thread(target=self.handle, args=(cmd,), daemon=True).start()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--runner", required=True)
    ap.add_argument("--apps", required=True, help="the apps' directories, space-separated")
    ap.add_argument("--devices", type=int, default=DEVICES)
    args = ap.parse_args()
    args.server = os.environ.get("QA_DELTA_SERVER", "").rstrip("/")
    args.token = os.environ.get("QA_DEVICE_TOKEN", "")
    if not args.server or not args.token:
        log("QA_DELTA_SERVER and QA_DEVICE_TOKEN aren't set: run `delta qa setup` from a Delta session")
        return 1
    apps = [a for a in args.apps.split() if re.fullmatch(r"[a-z0-9_-]+", a)]
    if not apps:
        log("no apps to build")
        return 1

    serials = boot(args.devices)
    log("building", len(apps), "apps")
    apks = build(apps)
    missing = sorted(set(apps) - set(apks))
    if missing:
        log("no APK for", " ".join(missing))
    wait_booted(serials)
    for s in serials:
        for a, apk in apks.items():
            try:
                install(s, apk)
            except Exception:
                log(f"couldn't install {a} on {s}")
    Bridge(args, serials, apks).serve()
    return 0


if __name__ == "__main__":
    sys.exit(main())
