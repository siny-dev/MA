from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]
TOOLCHAIN = Path(r"C:\AI-Work\Toolchains\MobileBasicTool")
JAVA = TOOLCHAIN / "jdk17" / "jdk-17.0.21+9"
SDK = TOOLCHAIN / "android-sdk"
BUILD_TOOLS = SDK / "build-tools" / "36.0.0"


@unittest.skipUnless(os.name == "nt" and (JAVA / "bin" / "keytool.exe").is_file()
                     and (SDK / "platforms" / "android-37.0" / "android.jar").is_file(),
                     "The bundled Android signing toolchain is required.")
class ReleaseBuildSmokeTests(unittest.TestCase):
    def test_isolated_release_build_is_signed_and_uses_requested_version(self):
        with tempfile.TemporaryDirectory(prefix="mobile-release-smoke-") as temporary:
            root = Path(temporary) / "project"

            def ignore(directory: str, names: list[str]) -> set[str]:
                blocked = set(names) & {".git", ".gradle", "build", ".idea", "__pycache__"}
                if Path(directory).resolve() == PROJECT.resolve():
                    blocked |= set(names) & {"local.properties", "build-mobile.ps1", "New-MobileProgram.ps1"}
                return blocked

            shutil.copytree(PROJECT, root, ignore=ignore)
            keystore = Path(temporary) / "smoke-test.p12"
            password = "temporary-smoke-test-only"
            subprocess.run([
                str(JAVA / "bin" / "keytool.exe"), "-genkeypair", "-noprompt", "-alias", "smoke-test",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "30", "-storetype", "PKCS12",
                "-keystore", str(keystore), "-storepass", password, "-keypass", password,
                "-dname", "CN=Temporary Release Smoke Test",
            ], check=True, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60)
            env = os.environ.copy()
            env.update({
                "JAVA_HOME": str(JAVA), "ANDROID_HOME": str(SDK), "ANDROID_SDK_ROOT": str(SDK),
                "GRADLE_USER_HOME": str(TOOLCHAIN / "gradle-home"),
                "APP_VERSION_NAME": "1.0.7", "APP_VERSION_CODE": "7",
                "ANDROID_KEYSTORE_PATH": str(keystore), "ANDROID_KEYSTORE_PASSWORD": password,
                "ANDROID_KEY_ALIAS": "smoke-test", "ANDROID_KEY_PASSWORD": password,
            })
            env["PATH"] = str(JAVA / "bin") + os.pathsep + str(SDK / "platform-tools") + os.pathsep + env.get("PATH", "")
            result = subprocess.run([os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c",
                                     str(root / "build-mobile.cmd"), "release"], cwd=root, env=env,
                                    capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=900)
            self.assertEqual(0, result.returncode, result.stdout[-5000:] + "\n" + result.stderr[-2000:])
            apk = root / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
            self.assertTrue(apk.is_file())
            verify = subprocess.run([str(JAVA / "bin" / "java.exe"), "-Xmx1G", "-jar",
                                     str(BUILD_TOOLS / "lib" / "apksigner.jar"), "verify", "--verbose", str(apk)],
                                    capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60)
            self.assertEqual(0, verify.returncode, (verify.stdout or "") + (verify.stderr or ""))
            badging = subprocess.run([str(BUILD_TOOLS / "aapt.exe"), "dump", "badging", str(apk)],
                                     capture_output=True, text=True, encoding="utf-8", errors="replace",
                                     check=True, timeout=60).stdout
            self.assertIn("package: name='com.siny.apps.ma_3c44c3e1b8' versionCode='7' versionName='1.0.7'", badging)


if __name__ == "__main__":
    unittest.main()
