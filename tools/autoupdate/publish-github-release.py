#!/usr/bin/env python3
"""Build MA and publish a private, app-specific GitHub Release using Git Credential Manager."""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ROOT = Path(__file__).absolute().parents[2]
REPOSITORY = "siny-dev/MA"
TOOLCHAIN = Path(r"C:\AI-Work\Toolchains\MobileBasicTool")
JAVA_HOME = TOOLCHAIN / "jdk17" / "jdk-17.0.21+9"
ANDROID_HOME = TOOLCHAIN / "android-sdk"
GRADLE_USER_HOME = TOOLCHAIN / "gradle-home"
APKSIGNER_JAR = ANDROID_HOME / "build-tools" / "36.0.0" / "lib" / "apksigner.jar"
AAPT = ANDROID_HOME / "build-tools" / "36.0.0" / "aapt.exe"
API_ROOT = f"https://api.github.com/repos/{REPOSITORY}"
USER_AGENT = "MA-GitHub-Release/1"
PINNED_SIGNERS = {
    "com.doumi.helper": "5f5951336912c1c8772c96fccc88799ba96beba2488fe3296d22c23469da8987",
    "com.siny.apps.ma_3c44c3e1b8": "d2206ba8af559c091b82d7dd65e16f597629c361c623f93f0da10a5a43c84679",
}
LOCAL_SIGNING_CONFIG = ROOT / "build" / "signing.local.json"


class GitHubError(RuntimeError):
    pass


def credential_token() -> str:
    result = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        text=True,
        capture_output=True,
        check=False,
        env={**os.environ, "GIT_TERMINAL_PROMPT": "0"},
    )
    if result.returncode != 0:
        raise GitHubError("Git Credential Manager에 저장된 GitHub 로그인을 찾지 못했습니다.")
    values = dict(
        line.split("=", 1) for line in result.stdout.splitlines() if "=" in line
    )
    token = values.get("password", "").strip()
    if not token:
        raise GitHubError("Git Credential Manager에서 GitHub 토큰을 얻지 못했습니다.")
    return token


def api_request(token: str, method: str, url: str, body=None, content_type=None):
    if body is None:
        data = None
    elif isinstance(body, (bytes, bytearray)):
        data = bytes(body)
    else:
        data = json.dumps(body, separators=(",", ":")).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": USER_AGENT,
    }
    if data is not None:
        headers["Content-Type"] = content_type or "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = response.read()
            if not payload:
                return None
            if response.headers.get_content_type() == "application/json":
                return json.loads(payload)
            return payload
    except urllib.error.HTTPError as exc:
        # Keep credentials and response headers out of build output.
        detail = ""
        try:
            detail = json.loads(exc.read().decode("utf-8", "replace")).get("message", "")
        except Exception:
            pass
        raise GitHubError(f"GitHub API 요청 실패 (HTTP {exc.code}) {detail}".strip()) from None
    except (urllib.error.URLError, TimeoutError) as exc:
        raise GitHubError(f"GitHub 연결 실패: {getattr(exc, 'reason', exc)}") from None


def update_versions(version_path: Path, asset_path: Path):
    old_main = version_path.read_bytes()
    old_asset = asset_path.read_bytes()
    version = json.loads(old_main.decode("utf-8-sig"))
    parts = version["version"].split(".")
    if len(parts) != 3 or any(not part.isdigit() for part in parts):
        raise GitHubError("version.json 버전은 x.y.z 형식이어야 합니다.")
    parts[2] = str(int(parts[2]) + 1).zfill(len(parts[2]))
    version["version"] = ".".join(parts)
    version["version_code"] = int(version["version_code"]) + 1
    serialized = json.dumps(version, ensure_ascii=False, indent=4) + "\n"
    version_path.write_text(serialized, encoding="utf-8")
    asset_path.write_text(serialized, encoding="utf-8")
    return old_main, old_asset, version


def run_build():
    env = os.environ.copy()
    env.update(
        {
            "JAVA_HOME": str(JAVA_HOME),
            "ANDROID_HOME": str(ANDROID_HOME),
            "ANDROID_SDK_ROOT": str(ANDROID_HOME),
            "GRADLE_USER_HOME": str(GRADLE_USER_HOME),
            "PATH": f"{JAVA_HOME / 'bin'};{ANDROID_HOME / 'platform-tools'};{os.environ.get('PATH', '')}",
        }
    )
    if not LOCAL_SIGNING_CONFIG.is_file():
        raise GitHubError("기존 MA 서명 설정이 없습니다. build/signing.local.json을 준비하세요.")
    try:
        signing = json.loads(LOCAL_SIGNING_CONFIG.read_text(encoding="utf-8"))
        keystore = Path(signing["keystore"])
        password_file = Path(signing["password_file"])
        alias = str(signing["key_alias"])
        password = password_file.read_text(encoding="utf-8-sig").strip()
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError):
        raise GitHubError("기존 MA 서명 설정을 읽지 못했습니다.") from None
    if not keystore.is_file() or not password or not alias:
        raise GitHubError("기존 MA 서명 키 파일, 암호 파일 또는 별칭이 올바르지 않습니다.")
    env.update({
        "MA_LEGACY_KEYSTORE": str(keystore),
        "MA_LEGACY_KEY_ALIAS": alias,
        "MA_LEGACY_STORE_PASSWORD": password,
        "MA_LEGACY_KEY_PASSWORD": password,
    })
    gradle = ROOT / "gradlew.bat"
    result = subprocess.run(
        f'"{gradle}" testDebugUnitTest assembleRelease',
        cwd=ROOT,
        env=env,
        check=False,
        shell=True,
    )
    if result.returncode:
        raise GitHubError(f"MA 테스트/빌드 실패 (exit {result.returncode})")


def expected_signer_for_package(package_name: str) -> str:
    signer = PINNED_SIGNERS.get(package_name)
    if not signer:
        raise GitHubError("릴리스 APK의 패키지에 등록된 MA 서명 인증서가 없습니다.")
    return signer


def verify_local_apk_signature(apk_path: Path, expected_signer_sha256: str | None = None):
    if not APKSIGNER_JAR.is_file():
        raise GitHubError("Android SDK apksigner.jar가 없습니다.")
    if expected_signer_sha256 is None:
        if not AAPT.is_file():
            raise GitHubError("Android SDK aapt.exe is missing.")
        badging = subprocess.run(
            [str(AAPT), "dump", "badging", str(apk_path)],
            text=True,
            capture_output=True,
            check=False,
        )
        package = re.search(r"^package: name='([^']+)'", badging.stdout, re.MULTILINE)
        if badging.returncode or not package:
            raise GitHubError("릴리스 APK 패키지 이름을 확인할 수 없습니다.")
        expected_signer_sha256 = expected_signer_for_package(package.group(1))
    result = subprocess.run(
        [str(JAVA_HOME / "bin" / "java.exe"), "-jar", str(APKSIGNER_JAR),
         "verify", "--verbose", "--print-certs", "--min-sdk-version", "21", str(apk_path)],
        text=True,
        capture_output=True,
        check=False,
    )
    output = result.stdout + result.stderr
    if result.returncode or "Verified using v1 scheme (JAR signing): true" not in output:
        raise GitHubError("릴리스 APK에 검증 가능한 v1 서명이 없습니다.")
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in output:
        raise GitHubError("릴리스 APK에 검증 가능한 v2 서명이 없습니다.")
    if expected_signer_sha256 and expected_signer_sha256.lower() not in output.lower():
        raise GitHubError("릴리스 APK 서명 인증서가 기존 MA 설치본과 다릅니다.")


def verify_apk_manifest_identity(apk_path: Path, manifest: dict):
    if not AAPT.is_file():
        raise GitHubError("Android SDK aapt.exe is missing.")
    result = subprocess.run(
        [str(AAPT), "dump", "badging", str(apk_path)],
        text=True,
        capture_output=True,
        check=False,
    )
    package = re.search(
        r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'",
        result.stdout,
        re.MULTILINE,
    )
    if result.returncode or not package:
        raise GitHubError("Published APK package metadata cannot be read.")
    if (
        package.group(1) != manifest.get("package_name")
        or int(package.group(2)) != manifest.get("version_code")
        or package.group(3) != manifest.get("version")
    ):
        raise GitHubError("Published APK package/version does not match its manifest.")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, new_url):
        return None


def download_private_asset(token: str, asset_url: str, maximum: int) -> bytes:
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/octet-stream",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": USER_AGENT,
    }
    opener = urllib.request.build_opener(NoRedirect())
    request = urllib.request.Request(asset_url, headers=headers)
    try:
        response = opener.open(request, timeout=30)
    except urllib.error.HTTPError as exc:
        if exc.code not in (301, 302, 303, 307, 308):
            raise GitHubError(f"GitHub 자산 검증 실패 (HTTP {exc.code})") from None
        location = exc.headers.get("Location", "")
        host = urllib.parse.urlparse(location).hostname or ""
        if not (
            location.startswith("https://")
            and host.lower().endswith(".githubusercontent.com")
        ):
            raise GitHubError("GitHub가 반환한 비공개 파일 주소를 확인할 수 없습니다.") from None
        # The bearer token is deliberately not sent to GitHub's signed asset CDN.
        response = urllib.request.urlopen(
            urllib.request.Request(location, headers={"Accept": "application/octet-stream"}),
            timeout=30,
        )
    with response:
        data = response.read(maximum + 1)
        if len(data) > maximum:
            raise GitHubError("검증 대상 GitHub 자산이 허용 크기를 초과했습니다.")
        return data


def verify_release(token: str, app_id: str, expected_version: str | None = None):
    tag = f"v{expected_version}" if expected_version else None
    if tag:
        release = api_request(
            token,
            "GET",
            f"{API_ROOT}/releases/tags/{urllib.parse.quote(tag, safe='')}",
        )
    else:
        releases = api_request(token, "GET", f"{API_ROOT}/releases?per_page=100")
        release = next(
            (
                item for item in releases
                if re.fullmatch(r"v\d+\.\d+\.\d+", item.get("tag_name", ""))
                and not item.get("draft") and not item.get("prerelease")
                and any(asset.get("name") == f"MA-{app_id}-manifest.json" for asset in item.get("assets", []))
            ),
            None,
        )
    if not release or release.get("draft") or release.get("prerelease"):
        raise GitHubError("게시된 MA GitHub Release를 찾지 못했습니다.")
    names = [asset.get("name") for asset in release.get("assets", [])]
    manifest_name = f"MA-{app_id}-manifest.json"
    if names.count(manifest_name) != 1:
        raise GitHubError("게시된 Release에 앱 manifest가 하나가 아닙니다.")
    asset_by_name = {asset["name"]: asset for asset in release["assets"]}
    manifest_asset = asset_by_name[manifest_name]
    manifest_bytes = download_private_asset(token, manifest_asset["url"], 256 * 1024)
    manifest = json.loads(manifest_bytes.decode("utf-8"))
    package_name = manifest.get("package_name")
    expected_signer = expected_signer_for_package(package_name or "")
    if (
        manifest.get("app_id") != app_id
        or manifest.get("platform") != "android"
        or manifest.get("version") != release["tag_name"][1:]
        or manifest.get("schema") != 1
        or not isinstance(manifest.get("version_code"), int)
        or manifest.get("version_code", 0) <= 0
        or not re.fullmatch(r"[0-9a-f]{64}", manifest.get("sha256", ""))
    ):
        raise GitHubError("게시된 manifest와 MA release 태그가 일치하지 않습니다.")
    if str(manifest.get("signer_sha256", expected_signer)).lower() != expected_signer:
        raise GitHubError("게시된 manifest 서명 인증서가 MA 패키지 정책과 다릅니다.")
    apk_name = manifest["apk_asset_name"]
    if names.count(apk_name) != 1 or apk_name not in asset_by_name:
        raise GitHubError("게시된 Release에 manifest가 가리키는 APK가 없습니다.")
    apk_asset = asset_by_name[apk_name]
    if apk_asset.get("size") != manifest.get("size"):
        raise GitHubError("게시된 APK 크기가 manifest와 다릅니다.")
    apk = download_private_asset(token, apk_asset["url"], 256 * 1024 * 1024)
    digest = hashlib.sha256(apk).hexdigest()
    if len(apk) != manifest["size"] or digest != manifest.get("sha256"):
        raise GitHubError("GitHub에 저장된 APK의 크기 또는 SHA-256이 manifest와 다릅니다.")
    with tempfile.TemporaryDirectory(prefix="ma-release-verify-") as temporary:
        apk_path = Path(temporary) / apk_name
        apk_path.write_bytes(apk)
        verify_local_apk_signature(apk_path, expected_signer)
        verify_apk_manifest_identity(apk_path, manifest)
    print(
        f"Verified {release['tag_name']}: {len(apk)} bytes; manifest size/SHA, "
        f"APK package/version and pinned v1+v2 signer match."
    )
    latest = api_request(token, "GET", f"{API_ROOT}/releases/latest")
    print(f"Repository latest release remains {latest.get('tag_name', '(none)')}.")


def main() -> int:
    allowed_args = {"--verify-only", "--publish-current"}
    if "--help" in sys.argv[1:] or "-h" in sys.argv[1:]:
        print("Usage: publish-github-release.py [--verify-only | --publish-current]")
        print("Default: build, verify and publish the next MA patch release.")
        print("--verify-only: verify the configured release without changing GitHub or local version files.")
        print("--publish-current: publish the version already recorded in version.json.")
        return 0
    unknown_args = [arg for arg in sys.argv[1:] if arg not in allowed_args]
    if unknown_args:
        raise GitHubError("알 수 없는 인수입니다. --help를 확인하세요.")
    if "--verify-only" in sys.argv and "--publish-current" in sys.argv:
        raise GitHubError("--verify-only와 --publish-current는 함께 사용할 수 없습니다.")

    metadata_path = ROOT / "mobile-app.json"
    version_path = ROOT / "version.json"
    asset_version_path = ROOT / "app" / "src" / "main" / "assets" / "version.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8-sig"))
    version = json.loads(version_path.read_text(encoding="utf-8-sig"))
    app_id = metadata["app_id"]
    if app_id != version["app_id"] or metadata["update_repository"] != REPOSITORY:
        raise GitHubError("앱 ID 또는 자동 업데이트 저장소 설정이 예상과 다릅니다.")

    token = credential_token()
    if "--verify-only" in sys.argv:
        verify_release(token, app_id, version["version"])
        return 0
    publish_current = "--publish-current" in sys.argv
    if publish_current:
        next_version = version["version"]
    else:
        next_parts = version["version"].split(".")
        next_parts[2] = str(int(next_parts[2]) + 1).zfill(len(next_parts[2]))
        next_version = ".".join(next_parts)
    tag = f"v{next_version}"
    release_url = f"{API_ROOT}/releases/tags/{urllib.parse.quote(tag, safe='')}"
    try:
        existing = api_request(token, "GET", release_url)
        if existing and not existing.get("draft"):
            raise GitHubError(f"이미 게시된 태그입니다: {tag}")
    except GitHubError as exc:
        if "HTTP 404" not in str(exc):
            raise

    old_main = old_asset = None
    if publish_current:
        bumped = version
    else:
        old_main, old_asset, bumped = update_versions(version_path, asset_version_path)
    draft_id = None
    try:
        run_build()
        apk_name = f"MA-{app_id}-{bumped['version']}.apk"
        apk_path = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
        if not apk_path.is_file() or apk_path.stat().st_size <= 0:
            raise GitHubError("디버그 APK가 생성되지 않았습니다.")
        expected_signer = expected_signer_for_package(metadata["application_id"])
        verify_local_apk_signature(apk_path, expected_signer)
        apk_size = apk_path.stat().st_size
        apk_sha256 = hashlib.sha256(apk_path.read_bytes()).hexdigest()
        manifest = {
            "schema": 1,
            "app_id": app_id,
            "package_name": metadata["application_id"],
            "signer_sha256": expected_signer,
            "platform": "android",
            "version": bumped["version"],
            "version_code": bumped["version_code"],
            "apk_asset_name": apk_name,
            "size": apk_size,
            "sha256": apk_sha256,
        }
        manifest_name = f"MA-{app_id}-manifest.json"
        manifest_path = ROOT / "build" / "github-release" / manifest_name
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        try:
            existing = api_request(token, "GET", release_url)
        except GitHubError as exc:
            if "HTTP 404" in str(exc):
                existing = None
            else:
                raise
        if existing and not existing.get("draft"):
            raise GitHubError(f"이미 게시된 태그입니다: {tag}")
        if existing:
            release = existing
            for asset in existing.get("assets", []):
                if asset.get("name") in (apk_name, manifest_name):
                    api_request(token, "DELETE", asset["url"])
        else:
            release = api_request(
                token,
                "POST",
                f"{API_ROOT}/releases",
                {
                    "tag_name": tag,
                    "name": f"MA {bumped['version']}",
                    "body": f"MA 자동 업데이트 · {app_id} · Android versionCode {bumped['version_code']}",
                    "draft": True,
                    "prerelease": False,
                    "make_latest": "true",
                },
            )
        draft_id = release["id"]
        upload_template = release["upload_url"].split("{", 1)[0]
        for path, name, mime in (
            (apk_path, apk_name, "application/vnd.android.package-archive"),
            (manifest_path, manifest_name, "application/json"),
        ):
            upload_url = upload_template + "?name=" + urllib.parse.quote(name, safe="")
            api_request(token, "POST", upload_url, path.read_bytes(), content_type=mime)
        api_request(
            token,
            "PATCH",
            f"{API_ROOT}/releases/{draft_id}",
            {"draft": False, "make_latest": "true"},
        )
        verify_release(token, app_id, bumped["version"])
        print(f"Published {tag} ({apk_size} bytes, SHA-256 {apk_sha256})")
        print(f"Release: https://github.com/{REPOSITORY}/releases/tag/{tag}")
        print("This public repository contains the MA update feed and release assets only.")
        return 0
    except Exception:
        if old_main is not None and old_asset is not None:
            version_path.write_bytes(old_main)
            asset_version_path.write_bytes(old_asset)
        if draft_id:
            print("A draft release was retained so it can be retried without publishing partial assets.", file=sys.stderr)
        raise


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GitHubError as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)
