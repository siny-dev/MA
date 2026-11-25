"""Build a debug-signed next-version APK and verify self-update on the running AVD."""
from __future__ import annotations

import hashlib
import json
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time


ROOT = Path(__file__).resolve().parents[1]
TOOLCHAIN = Path(r"C:\AI-Work\Toolchains\MobileBasicTool")
ADB = TOOLCHAIN / "android-sdk" / "platform-tools" / "adb.exe"
PACKAGE = "com.siny.apps.ma_3c44c3e1b8"
APP_ID = "ma-3c44c3e1b8"
VERSION = "1.0.034"
VERSION_CODE = 1021
PORT = 18765


def run(command: list[str], **kwargs) -> None:
    check = kwargs.pop("check", True)
    subprocess.run(command, cwd=ROOT, check=check, **kwargs)


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="ma-silent-update-") as temp_name:
        temp = Path(temp_name)
        builder = temp / "build-candidate.cmd"
        builder.write_text(
            "@echo off\n"
            f'set "APP_VERSION_NAME={VERSION}"\n'
            f'set "APP_VERSION_CODE={VERSION_CODE}"\n'
            "call build-mobile.cmd debug\n"
            "exit /b %ERRORLEVEL%\n",
            encoding="ascii",
        )
        run([os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", str(builder)])
        candidate = temp / "candidate.apk"
        shutil.copy2(ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk", candidate)
        digest = hashlib.sha256(candidate.read_bytes()).hexdigest()
        build_environment = os.environ.copy()
        build_environment["APP_VERSION_NAME"] = "1.0.033"
        build_environment["APP_VERSION_CODE"] = "1019"
        run([os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c",
             str(ROOT / "build-mobile.cmd"), "debug"], env=build_environment)
        run([str(ADB), "install", "-r", str(ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk")])
        manifest = json.dumps({
            "schema": 1,
            "platform": "android",
            "app_id": APP_ID,
            "package_name": PACKAGE,
            "version": VERSION,
            "version_code": VERSION_CODE,
            "apk_url": f"http://127.0.0.1:{PORT}/candidate.apk",
            "sha256": digest,
        }).encode("utf-8")

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == "/manifest":
                    data = manifest
                    content_type = "application/json"
                elif self.path == "/candidate.apk":
                    data = candidate.read_bytes()
                    content_type = "application/vnd.android.package-archive"
                else:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *_):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            run([str(ADB), "reverse", f"tcp:{PORT}", f"tcp:{PORT}"])
            run([str(ADB), "shell", "appops", "set", PACKAGE, "REQUEST_INSTALL_PACKAGES", "allow"])
            env = os.environ.copy()
            env["LOCAL_UPDATE_MANIFEST_URL"] = f"http://127.0.0.1:{PORT}/manifest"
            env["LOCAL_UPDATE_VERSION_CODE"] = str(VERSION_CODE)
            env["APP_VERSION_NAME"] = "1.0.033"
            env["APP_VERSION_CODE"] = "1019"
            env["LOCAL_UPDATE_TEST_CLASS"] = (
                "com.siny.mobilebasic.modules.AutoUpdateLocalFeedTest"
                "#selfUpdateInstallsWithoutUserActionWhenAndroidRequirementsAreMet"
            )
            run([str(ADB), "logcat", "-c"])
            test_process = subprocess.Popen(
                ["build-mobile.cmd", "instrumented"], cwd=ROOT, env=env,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            deadline = time.monotonic() + 90
            installed_code = 0
            while time.monotonic() < deadline and test_process.poll() is None:
                package_state = subprocess.run(
                    [str(ADB), "shell", "dumpsys", "package", PACKAGE],
                    check=False, capture_output=True, text=True,
                    encoding="utf-8", errors="replace",
                ).stdout
                match = re.search(r"versionCode=(\d+)", package_state)
                installed_code = int(match.group(1)) if match else 0
                if installed_code == VERSION_CODE:
                    break
                time.sleep(0.25)
            test_exit = test_process.wait(timeout=max(1, deadline - time.monotonic()))
            test_log = subprocess.run(
                [str(ADB), "logcat", "-d", "-s", "TestRunner"],
                check=True, capture_output=True, text=True,
                encoding="utf-8", errors="replace",
            ).stdout
            test_passed = "run finished: 1 tests, 0 failed, 0 ignored" in test_log
            if installed_code != VERSION_CODE or test_exit != 0 or not test_passed:
                raise RuntimeError(
                    f"Silent self-update did not install versionCode {VERSION_CODE}; "
                    f"emulator reported {installed_code}; instrumentation exit code {test_exit}; "
                    f"test pass marker={test_passed}."
                )
        finally:
            server.shutdown()
            thread.join(timeout=5)
            run([str(ADB), "reverse", "--remove", f"tcp:{PORT}"], check=False)
    print("Android emulator self-update installed the newer signed-equivalent APK without a confirmation screen.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
