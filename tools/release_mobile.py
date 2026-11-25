#!/usr/bin/env python3
"""Build, sign and publish a mobile app candidate; promote only after review."""
from __future__ import annotations

import argparse
import base64
import ctypes
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
TOOLCHAIN = Path(r"C:\AI-Work\Toolchains\MobileBasicTool")
APP_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,62}$")
VERSION_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}$")
SOURCE_REF_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$")
REPO_RE = re.compile(r"^https://github\.com/([A-Za-z0-9-]+)/([A-Za-z0-9._-]+)$")
MAX_APK_BYTES = 256 * 1024 * 1024
AUTOUPDATE_REPOSITORY = "siny-dev/auto-update"


class ReleaseError(RuntimeError):
    pass


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ReleaseError(f"Cannot read required JSON: {path.name}") from exc
    if not isinstance(value, dict):
        raise ReleaseError(f"Expected an object in {path.name}")
    return value


def validate_version(version_name: str, version_code: int) -> tuple[str, int]:
    if not VERSION_RE.fullmatch(version_name) or "/" in version_name or "\\" in version_name:
        raise ValueError("Version name must be a short version label without path characters.")
    if type(version_code) is not int or not 1 <= version_code <= 2_100_000_000:
        raise ValueError("versionCode must be a positive Android integer.")
    return version_name, version_code


def candidate_tag(version_name: str, version_code: int, app_id: str = "") -> str:
    validate_version(version_name, version_code)
    if app_id and not APP_ID_RE.fullmatch(app_id):
        raise ValueError("Invalid app_id for candidate release tag.")
    prefix = f"candidate-{app_id}-" if app_id else "candidate-"
    return f"{prefix}v{version_name}-{version_code}"


def parse_repository_url(value: str) -> tuple[str, str]:
    match = REPO_RE.fullmatch(value.rstrip("/"))
    if not match:
        raise ReleaseError("github-app.json must contain this app's HTTPS GitHub repository URL.")
    return match.group(1), match.group(2)

def update_target(mapping: dict[str, Any], app_id: str) -> tuple[str, str, str, str]:
    modules = mapping.get("module_repositories")
    updater = modules.get("AutoUpdate", {}) if isinstance(modules, dict) else {}
    repository = str(updater.get("repository") or AUTOUPDATE_REPOSITORY)
    match = re.fullmatch(r"([A-Za-z0-9-]+)/([A-Za-z0-9._-]+)", repository)
    if not match:
        raise ReleaseError("AutoUpdate module repository must be owner/repository.")
    owner, repo = match.groups()
    path = str(updater.get("manifest_path") or f"feeds/{app_id}/stable.json")
    if path != f"feeds/{app_id}/stable.json":
        raise ReleaseError("AutoUpdate feed path must be scoped to this app_id.")
    url = f"https://raw.githubusercontent.com/{owner}/{repo}/main/{path}"
    configured = str(mapping.get("update_manifest_url") or updater.get("manifest_url") or url)
    if configured != url:
        raise ReleaseError("AutoUpdate manifest URL does not match its fixed module repository.")
    return owner, repo, path, url


def build_release_metadata(app_id: str, package_name: str, version_name: str,
                           version_code: int, apk_name: str, digest: str) -> dict[str, Any]:
    validate_version(version_name, version_code)
    if not APP_ID_RE.fullmatch(app_id):
        raise ValueError("Invalid app_id in mobile-app.json.")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", package_name):
        raise ValueError("Invalid Android package name in mobile-app.json.")
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("Invalid APK SHA-256.")
    if Path(apk_name).name != apk_name or not apk_name.endswith(".apk"):
        raise ValueError("Invalid APK asset name.")
    return {"schema": 1, "app_id": app_id, "package_name": package_name,
            "version": version_name, "version_code": version_code,
            "apk_name": apk_name, "sha256": digest}


class DataBlob(ctypes.Structure):
    _fields_ = [("cbData", ctypes.c_uint32), ("pbData", ctypes.POINTER(ctypes.c_ubyte))]


def _windows_dpapi(data: bytes, protect: bool) -> bytes:
    if os.name != "nt":
        raise ReleaseError("Android release signing is available from this Windows development PC.")
    crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    source = ctypes.create_string_buffer(data)
    input_blob = DataBlob(len(data), ctypes.cast(source, ctypes.POINTER(ctypes.c_ubyte)))
    output_blob = DataBlob()
    flags = 0x1  # CRYPTPROTECT_UI_FORBIDDEN
    if protect:
        ok = crypt32.CryptProtectData(ctypes.byref(input_blob), None, None, None, None,
                                      flags, ctypes.byref(output_blob))
    else:
        ok = crypt32.CryptUnprotectData(ctypes.byref(input_blob), None, None, None, None,
                                        flags, ctypes.byref(output_blob))
    if not ok:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        return ctypes.string_at(output_blob.pbData, output_blob.cbData)
    finally:
        kernel32.LocalFree(output_blob.pbData)


def _signing_root(app_id: str) -> Path:
    local = os.environ.get("LOCALAPPDATA")
    if not local:
        raise ReleaseError("Windows local application data path is unavailable.")
    return Path(local) / "Siny" / "AndroidSigning" / app_id


def _run(command: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None,
         input_text: str | None = None) -> str:
    try:
        result = subprocess.run(command, cwd=cwd, env=env, input=input_text, text=True,
                                encoding="utf-8", errors="replace",
                                capture_output=True, check=False, timeout=1800,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ReleaseError(f"Command could not finish: {Path(command[0]).name}") from exc
    if result.returncode:
        detail = (result.stdout + "\n" + result.stderr).strip().splitlines()
        tail = "\n".join(detail[-12:])
        raise ReleaseError(f"{Path(command[0]).name} failed:\n" + (tail[:2400] if tail else "unknown error"))
    return result.stdout.strip()


def _tool(name: str) -> Path:
    executable = TOOLCHAIN / "jdk17" / "jdk-17.0.21+9" / "bin" / f"{name}.exe"
    if not executable.is_file():
        raise ReleaseError(f"Bundled Android tool is missing: {name}")
    return executable


def ensure_signing_key(app_id: str) -> tuple[Path, str, str]:
    app_metadata = read_json(ROOT / "mobile-app.json")
    if (app_id == "ma-3c44c3e1b8"
            and app_metadata.get("application_id") == "com.siny.apps.ma_3c44c3e1b8"):
        # Existing MA installs use this Windows profile's debug certificate.
        # Keep that signer so the clean rebuild remains installable as an update.
        key_path = Path.home() / ".android" / "debug.keystore"
        alias, password = "androiddebugkey", "android"
        expected = str(app_metadata.get("signing_certificate_sha256", "")).replace(":", "").lower()
        if not key_path.is_file() or not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise ReleaseError("Existing MA signing identity is unavailable; no replacement key was generated.")
        exported = subprocess.run(
            [str(_tool("keytool")), "-exportcert", "-keystore", str(key_path),
             "-storepass", password, "-alias", alias],
            capture_output=True, check=False,
        )
        actual = hashlib.sha256(exported.stdout).hexdigest() if exported.returncode == 0 else ""
        if actual != expected:
            raise ReleaseError("This PC's MA signing certificate does not match the installed app identity.")
        return key_path, alias, password

    root = _signing_root(app_id)
    root.mkdir(parents=True, exist_ok=True)
    config_path = root / "signing.json"
    key_path = root / "release.p12"
    alias = "app-release"
    keytool = _tool("keytool")
    if config_path.exists() or key_path.exists():
        if not config_path.is_file() or not key_path.is_file():
            raise ReleaseError("A partial signing-key setup exists. It was preserved; no new key was generated.")
        config = read_json(config_path)
        if config.get("schema") != 1 or config.get("app_id") != app_id or config.get("key_file") != str(key_path):
            raise ReleaseError("The existing signing-key record does not match this app; it was preserved.")
        try:
            password = _windows_dpapi(base64.b64decode(config["password_dpapi"], validate=True), False).decode("utf-8")
        except Exception as exc:
            raise ReleaseError("The protected signing-key password cannot be recovered under this Windows account.") from exc
        if not password:
            raise ReleaseError("The recovered signing-key password is empty.")
        return key_path, alias, password

    import secrets
    password = secrets.token_urlsafe(36)
    fd, password_file_name = tempfile.mkstemp(prefix=".android-signing-", suffix=".txt", dir=root)
    password_file = Path(password_file_name)
    try:
        with os.fdopen(fd, "w", encoding="ascii", newline="") as stream:
            stream.write(password)
        _run([str(keytool), "-genkeypair", "-noprompt", "-alias", alias,
              "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
              "-storetype", "PKCS12", "-keystore", str(key_path),
              "-storepass:file", str(password_file), "-keypass:file", str(password_file),
              "-dname", "CN=" + app_id])
        protected = _windows_dpapi(password.encode("utf-8"), True)
        config = {"schema": 1, "app_id": app_id, "key_file": str(key_path),
                  "key_alias": alias, "password_dpapi": base64.b64encode(protected).decode("ascii")}
        temporary = config_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, config_path)
        return key_path, alias, password
    except Exception:
        if key_path.exists():
            key_path.unlink()
        raise
    finally:
        try:
            password_file.unlink()
        except FileNotFoundError:
            pass


def _github_token() -> str:
    result = subprocess.run(["git", "credential", "fill"],
                            input="protocol=https\nhost=github.com\n\n", text=True,
                            encoding="utf-8", errors="replace",
                            capture_output=True, check=False,
                            env={**os.environ, "GIT_TERMINAL_PROMPT": "0"},
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if result.returncode:
        raise ReleaseError("Git Credential Manager has no GitHub sign-in for this Windows account.")
    values = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
    token = values.get("password", "")
    if not token:
        raise ReleaseError("Git Credential Manager returned no GitHub credential.")
    return token


def _api(method: str, url: str, token: str, body: dict[str, Any] | bytes | None = None,
         content_type: str = "application/vnd.github+json", accept: str = "application/vnd.github+json") -> dict[str, Any] | bytes:
    data = body if isinstance(body, bytes) else (json.dumps(body).encode("utf-8") if body is not None else None)
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Authorization", "Bearer " + token)
    request.add_header("Accept", accept)
    request.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data is not None:
        request.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            payload = response.read(MAX_APK_BYTES + 1)
            if len(payload) > MAX_APK_BYTES:
                raise ReleaseError("GitHub response exceeded the allowed file size.")
            if "json" in response.headers.get("Content-Type", ""):
                value = json.loads(payload.decode("utf-8"))
                return value if isinstance(value, dict) else {}
            return payload
    except urllib.error.HTTPError as exc:
        try:
            body_text = exc.read(4096).decode("utf-8", "replace")
            message = json.loads(body_text).get("message", "")
        except Exception:
            message = ""
        raise ReleaseError(f"GitHub API request failed (HTTP {exc.code}). {str(message)[:240]}") from exc
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise ReleaseError("GitHub could not be reached or returned invalid JSON.") from exc


def _download_public_asset(url: str) -> bytes:
    parsed = urllib.parse.urlparse(url)
    if (parsed.scheme != "https" or parsed.hostname != "github.com" or parsed.username
            or parsed.password or parsed.fragment or "/releases/download/" not in parsed.path):
        raise ReleaseError("Release asset URL is not a public GitHub release download.")
    request = urllib.request.Request(url, headers={"Accept": "application/octet-stream"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = response.read(MAX_APK_BYTES + 1)
    except (urllib.error.URLError, TimeoutError) as exc:
        raise ReleaseError("The public candidate release asset could not be downloaded.") from exc
    if not 0 < len(payload) <= MAX_APK_BYTES:
        raise ReleaseError("The candidate release asset is empty or too large.")
    return payload


def _load_identity(project: Path) -> tuple[dict[str, Any], dict[str, Any], str, str]:
    app = read_json(project / "mobile-app.json")
    mapping = read_json(project / "github-app.json")
    app_id = str(app.get("app_id", ""))
    if not APP_ID_RE.fullmatch(app_id) or mapping.get("app_id") != app_id:
        raise ReleaseError("mobile-app.json and github-app.json app IDs do not match.")
    if app.get("platform") != "android":
        raise ReleaseError("This release command accepts only an Android project.")
    owner, repository = parse_repository_url(str(mapping.get("repository_url", "")))
    package_name = str(app.get("application_id", ""))
    expected_clone = f"https://github.com/{owner}/{repository}.git"
    if (mapping.get("repository_name") != repository or mapping.get("application_id") != package_name
            or str(mapping.get("clone_url", expected_clone)).rstrip("/") not in (expected_clone, expected_clone[:-4])):
        raise ReleaseError("GitHub repository mapping does not match the generated app identity.")
    build_file = project / "app" / "build.gradle"
    if not package_name or not build_file.is_file():
        raise ReleaseError("Generated Android app identity or Gradle build file is missing.")
    return app, mapping, owner, repository


def _git(project: Path, *args: str) -> str:
    return _run(["git", "-C", str(project), *args])


def _assert_publishable_tree(project: Path) -> str:
    if _git(project, "status", "--porcelain"):
        raise ReleaseError("Git working tree has local edits. Commit or preserve them before releasing.")
    branch = _git(project, "branch", "--show-current")
    if branch != "main":
        raise ReleaseError("Releases must start from the main branch; no branch was switched automatically.")
    return _git(project, "rev-parse", "HEAD")


def _extract_archive(project: Path, source_ref: str, destination: Path) -> None:
    if not SOURCE_REF_RE.fullmatch(source_ref):
        raise ReleaseError("Rollback source must be a Git commit, tag, or branch name without option syntax.")
    raw = subprocess.run(["git", "-C", str(project), "archive", "--format=zip", source_ref],
                         capture_output=True, check=False, timeout=120)
    if raw.returncode:
        raise ReleaseError("Rollback source ref is not present in this repository.")
    destination.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(__import__("io").BytesIO(raw.stdout)) as archive:
        root = destination.resolve()
        for member in archive.infolist():
            rel = PurePosixPath(member.filename)
            if rel.is_absolute() or ".." in rel.parts:
                raise ReleaseError("Git archive contains an unsafe path; extraction stopped.")
            target = (destination / Path(*rel.parts)).resolve()
            if root not in target.parents and target != root:
                raise ReleaseError("Git archive path escaped the temporary project directory.")
        archive.extractall(destination)


def _stable_version(mapping: dict[str, Any], app_id: str, package_name: str) -> tuple[dict[str, Any], int]:
    _, _, _, url = update_target(mapping, app_id)
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read(256 * 1024 + 1)
    except (urllib.error.URLError, TimeoutError) as exc:
        raise ReleaseError("AutoUpdate stable feed could not be read from the fixed repository.") from exc
    if len(raw) > 256 * 1024:
        raise ReleaseError("AutoUpdate stable feed is too large.")
    try:
        manifest = json.loads(raw.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ReleaseError("AutoUpdate stable feed is invalid JSON.") from exc
    if (not isinstance(manifest, dict) or manifest.get("schema") != 1
            or manifest.get("platform") != "android" or manifest.get("app_id") != app_id
            or manifest.get("package_name") != package_name):
        raise ReleaseError("Stable update feed identity is invalid.")
    try:
        code = int(manifest["version_code"])
    except (KeyError, TypeError, ValueError) as exc:
        raise ReleaseError("Stable update feed versionCode is invalid.") from exc
    return manifest, code

def _publish_stable_feed(mapping: dict[str, Any], app_id: str, manifest: dict[str, Any], token: str) -> str:
    owner, repo, path, _ = update_target(mapping, app_id)
    api = f"https://api.github.com/repos/{owner}/{repo}"
    encoded_path = urllib.parse.quote(path, safe="/")
    current = _api("GET", api + "/contents/" + encoded_path, token)
    sha = current.get("sha") if isinstance(current, dict) else None
    if not isinstance(sha, str) or not sha:
        raise ReleaseError("AutoUpdate stable feed is missing from the fixed repository.")
    payload = {"message": f"Promote {app_id} Android {manifest['version']} ({manifest['version_code']})",
               "content": base64.b64encode((json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")).decode("ascii"),
               "sha": sha, "branch": "main"}
    result = _api("PUT", api + "/contents/" + encoded_path, token, payload)
    commit = result.get("commit", {}) if isinstance(result, dict) else {}
    return str(commit.get("html_url") or f"https://github.com/{owner}/{repo}/commits/main/{encoded_path}")


def _require_version_code_advance(version_code: int, stable_code: int) -> None:
    if version_code <= stable_code:
        raise ReleaseError(f"versionCode {version_code} must be higher than the stable versionCode {stable_code}.")


def _source_version_details(project: Path, source_ref: str) -> tuple[str, int]:
    if not SOURCE_REF_RE.fullmatch(source_ref):
        raise ReleaseError("Rollback source must be a Git commit, tag, or branch name without option syntax.")
    try:
        raw = subprocess.run(["git", "-C", str(project), "show", f"{source_ref}:version.json"],
                             capture_output=True, check=False, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ReleaseError("Rollback source version could not be read from Git.") from exc
    if raw.returncode:
        raise ReleaseError("Rollback source has no version.json; select a valid app source commit or tag.")
    try:
        source = json.loads(raw.stdout.decode("utf-8"))
        version, _ = validate_version(str(source["version"]), 1)
        source_code = source.get("version_code", 0)
        if type(source_code) is not int or source_code < 0:
            source_code = 0
    except (UnicodeError, json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
        raise ReleaseError("Rollback source version.json contains an invalid version name.") from exc
    return version, source_code


def _project_version_code(project: Path) -> int:
    try:
        data = json.loads((project / "version.json").read_text(encoding="utf-8"))
        code = data.get("version_code", 0)
        return code if type(code) is int and code > 0 else 0
    except (OSError, UnicodeError, json.JSONDecodeError):
        return 0


def _write_build_version(project: Path, version_name: str, version_code: int) -> None:
    validate_version(version_name, version_code)
    for relative in ("version.json", "app/src/main/assets/version.json"):
        path = project / relative
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise ReleaseError(f"Build source has no valid {relative} version metadata.") from exc
        if not isinstance(value, dict) or value.get("schema") != 1:
            raise ReleaseError(f"Build source {relative} has an unsupported version schema.")
        value["version"] = version_name
        value["version_code"] = version_code
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _rollback_candidate(project: Path, source_ref: str) -> tuple[str, str, int]:
    if not SOURCE_REF_RE.fullmatch(source_ref):
        raise ReleaseError("Rollback source must be a Git commit, tag, or branch name without option syntax.")
    app, mapping, _, _ = _load_identity(project)
    app_id = str(app["app_id"])
    package = str(app["application_id"])
    _assert_publishable_tree(project)
    _, stable_code = _stable_version(mapping, app_id, package)
    if stable_code >= 2_100_000_000:
        raise ReleaseError("Android versionCode limit reached; a rollback update cannot be created.")
    version_name, _ = _source_version_details(project, source_ref)
    version_code = max(stable_code, _project_version_code(project)) + 1
    candidate_url = _candidate(project, version_name, version_code, source_ref)
    return candidate_url, version_name, version_code


def _verify_apk(apk: Path, package_name: str, version_code: int) -> str:
    if not apk.is_file() or not 0 < apk.stat().st_size <= MAX_APK_BYTES:
        raise ReleaseError("Built APK is missing or outside the allowed size.")
    build_tools = TOOLCHAIN / "android-sdk" / "build-tools" / "36.0.0"
    apksigner = build_tools / "lib" / "apksigner.jar"
    aapt = build_tools / "aapt.exe"
    if not apksigner.is_file() or not aapt.is_file():
        raise ReleaseError("Bundled Android APK verification tools are missing.")
    _run([str(_tool("java")), "-Xmx1G", "-jar", str(apksigner), "verify", "--verbose", str(apk)])
    badging = _run([str(aapt), "dump", "badging", str(apk)])
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", badging, re.M)
    if not match or match.group(1) != package_name or int(match.group(2)) != version_code:
        raise ReleaseError("Signed APK package name or versionCode does not match release metadata.")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    return digest


def _build_environment(app_id: str, version_name: str, version_code: int) -> dict[str, str]:
    key, alias, password = ensure_signing_key(app_id)
    env = os.environ.copy()
    env.update({
        "JAVA_HOME": str(TOOLCHAIN / "jdk17" / "jdk-17.0.21+9"),
        "ANDROID_HOME": str(TOOLCHAIN / "android-sdk"),
        "ANDROID_SDK_ROOT": str(TOOLCHAIN / "android-sdk"),
        "GRADLE_USER_HOME": str(TOOLCHAIN / "gradle-home"),
        "APP_VERSION_NAME": version_name,
        "APP_VERSION_CODE": str(version_code),
        "ANDROID_KEYSTORE_PATH": str(key),
        "ANDROID_KEYSTORE_PASSWORD": password,
        "ANDROID_KEY_ALIAS": alias,
        "ANDROID_KEY_PASSWORD": password,
        "MA_LEGACY_KEYSTORE": str(key),
        "MA_LEGACY_KEY_ALIAS": alias,
        "MA_LEGACY_STORE_PASSWORD": password,
        "MA_LEGACY_KEY_PASSWORD": password,
    })
    env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + str(TOOLCHAIN / "android-sdk" / "platform-tools") + os.pathsep + env.get("PATH", "")
    return env


def _candidate(project: Path, version_name: str, version_code: int, source_ref: str | None) -> str:
    validate_version(version_name, version_code)
    if source_ref is not None and not SOURCE_REF_RE.fullmatch(source_ref):
        raise ReleaseError("Rollback source must be a Git commit, tag, or branch name without option syntax.")
    app, mapping, owner, repository = _load_identity(project)
    base_commit = _assert_publishable_tree(project)
    app_id = str(app["app_id"])
    update_owner, update_repo, _, _ = update_target(mapping, app_id)
    _, stable_code = _stable_version(mapping, app_id, str(app["application_id"]))
    _require_version_code_advance(version_code, stable_code)
    if source_ref is None and version_code < _project_version_code(project):
        raise ReleaseError("versionCode must not be lower than this project's current versionCode.")
    ref = source_ref or base_commit
    tag = candidate_tag(version_name, version_code, app_id)
    token = _github_token()
    api = f"https://api.github.com/repos/{update_owner}/{update_repo}"
    _api("GET", api, token)
    with tempfile.TemporaryDirectory(prefix="siny-mobile-release-") as temp_name:
        build_root = Path(temp_name) / "project"
        _extract_archive(project, ref, build_root)
        _write_build_version(build_root, version_name, version_code)
        build_script = build_root / "build-mobile.cmd"
        if not build_script.is_file():
            shared_builder = project / "build-mobile.cmd"
            if not shared_builder.is_file():
                raise ReleaseError("Shared isolated Android build command is missing.")
            shutil.copy2(shared_builder, build_script)
        env = _build_environment(str(app["app_id"]), version_name, version_code)
        command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", str(build_script), "release"]
        _run(command, cwd=build_root, env=env)
        apk = build_root / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
        digest = _verify_apk(apk, str(app["application_id"]), version_code)
        asset_name = f"{mapping['repository_name']}-{version_name}-{version_code}.apk"
        metadata = build_release_metadata(str(app["app_id"]), str(app["application_id"]),
                                          version_name, version_code, asset_name, digest)
        if source_ref is not None:
            source_name, source_code = _source_version_details(project, source_ref)
            metadata["rollback_source"] = {
                "ref": source_ref,
                "version": source_name,
                "source_version_code": source_code,
            }
        request_data = {"tag_name": tag, "target_commitish": "main", "name": tag,
                        "body": "Basic Tool signed Android candidate. Promote to stable only after device verification.",
                        "draft": True, "prerelease": True}
        release = _api("POST", api + "/releases", token, request_data)
        if not isinstance(release, dict) or not release.get("upload_url"):
            raise ReleaseError("GitHub created no upload destination for the candidate release.")
        upload_url = str(release["upload_url"]).split("{", 1)[0]
        if not upload_url.startswith("https://uploads.github.com/"):
            raise ReleaseError("GitHub returned an unexpected release upload URL.")
        _api("POST", upload_url + "?name=" + urllib.parse.quote(asset_name), token,
             apk.read_bytes(), "application/vnd.android.package-archive", "application/vnd.github+json")
        meta_name = "release-metadata.json"
        _api("POST", upload_url + "?name=" + urllib.parse.quote(meta_name), token,
             json.dumps(metadata, separators=(",", ":")).encode("utf-8"), "application/json", "application/vnd.github+json")
        _api("PATCH", api + "/releases/" + str(release["id"]), token,
             {"draft": False, "prerelease": True})
        return str(release.get("html_url") or f"https://github.com/{update_owner}/{update_repo}/releases/tag/{tag}")


def _promote(project: Path, tag: str) -> str:
    app, mapping, owner, repository = _load_identity(project)
    _assert_publishable_tree(project)
    app_id = str(app["app_id"])
    if not re.fullmatch(r"candidate-" + re.escape(app_id) + r"-v[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}-[1-9][0-9]*", tag):
        raise ReleaseError("Select a candidate release tag created for this app_id.")
    package = str(app["application_id"])
    current_feed, stable_code = _stable_version(mapping, app_id, package)
    update_owner, update_repo, _, _ = update_target(mapping, app_id)
    token = _github_token()
    api = f"https://api.github.com/repos/{update_owner}/{update_repo}"
    release = _api("GET", api + "/releases/tags/" + urllib.parse.quote(tag, safe=""), token)
    if not isinstance(release, dict) or release.get("draft") or not release.get("prerelease"):
        raise ReleaseError("Promotion accepts only a published candidate prerelease.")
    assets = release.get("assets", [])
    metadata_asset = next((x for x in assets if x.get("name") == "release-metadata.json"), None)
    if not metadata_asset:
        raise ReleaseError("Candidate has no release-metadata.json asset.")
    expected_asset_prefix = f"https://github.com/{update_owner}/{update_repo}/releases/download/{tag}/"
    metadata_url = str(metadata_asset.get("browser_download_url", ""))
    if metadata_url != expected_asset_prefix + "release-metadata.json":
        raise ReleaseError("Candidate metadata URL does not belong to the selected release tag.")
    metadata_bytes = _download_public_asset(metadata_url)
    try:
        metadata = json.loads(metadata_bytes.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ReleaseError("Candidate metadata asset is invalid.") from exc
    if metadata.get("schema") != 1 or metadata.get("app_id") != app_id or metadata.get("package_name") != package:
        raise ReleaseError("Candidate belongs to another app or package.")
    version_name, version_code = validate_version(str(metadata.get("version", "")), metadata.get("version_code"))
    if tag != candidate_tag(version_name, version_code, app_id):
        raise ReleaseError("Candidate tag does not match the version metadata.")
    _require_version_code_advance(version_code, stable_code)
    apk_asset = next((x for x in assets if x.get("name") == metadata.get("apk_name")), None)
    if not apk_asset:
        raise ReleaseError("Candidate APK asset is missing.")
    apk_url = str(apk_asset.get("browser_download_url", ""))
    if apk_url != expected_asset_prefix + urllib.parse.quote(str(metadata.get("apk_name", "")), safe=""):
        raise ReleaseError("Candidate APK URL does not match the selected release tag and asset name.")
    payload = _download_public_asset(apk_url)
    digest = hashlib.sha256(payload).hexdigest()
    if digest != metadata.get("sha256"):
        raise ReleaseError("Candidate APK SHA-256 does not match its signed release metadata.")
    with tempfile.TemporaryDirectory(prefix="siny-mobile-promote-") as temp_name:
        apk = Path(temp_name) / "candidate.apk"
        apk.write_bytes(payload)
        _verify_apk(apk, package, version_code)
    new_feed = dict(current_feed)
    new_feed.update({"version": version_name, "version_code": version_code,
                     "apk_url": apk_url, "sha256": digest, "release_tag": tag})
    rollback_versions = current_feed.get("rollback_versions", [])
    if isinstance(rollback_versions, list):
        new_feed["rollback_versions"] = [item for item in rollback_versions
                                         if isinstance(item, dict)
                                         and _positive_int(item.get("version_code"), 0) > version_code]
    return _publish_stable_feed(mapping, app_id, new_feed, token)


def _positive_int(value: Any, default: int = 0) -> int:
    return value if type(value) is int and value > 0 else default


def _register_rollback(project: Path, tag: str) -> str:
    app, mapping, _, _ = _load_identity(project)
    _assert_publishable_tree(project)
    app_id = str(app["app_id"])
    package = str(app["application_id"])
    current_feed, stable_code = _stable_version(mapping, app_id, package)
    owner, repo, _, _ = update_target(mapping, app_id)
    if not re.fullmatch(r"candidate-" + re.escape(app_id) + r"-v[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}-[1-9][0-9]*", tag):
        raise ReleaseError("Select an MA rollback candidate tag.")
    token = _github_token()
    api = f"https://api.github.com/repos/{owner}/{repo}"
    release = _api("GET", api + "/releases/tags/" + urllib.parse.quote(tag, safe=""), token)
    if not isinstance(release, dict) or release.get("draft") or not release.get("prerelease"):
        raise ReleaseError("Rollback registration accepts only a published candidate prerelease.")
    assets = release.get("assets", [])
    metadata_asset = next((asset for asset in assets if asset.get("name") == "release-metadata.json"), None)
    if not metadata_asset:
        raise ReleaseError("Rollback candidate has no release-metadata.json asset.")
    prefix = f"https://github.com/{owner}/{repo}/releases/download/{tag}/"
    metadata_url = str(metadata_asset.get("browser_download_url", ""))
    if metadata_url != prefix + "release-metadata.json":
        raise ReleaseError("Rollback candidate metadata URL does not match the selected release.")
    try:
        metadata = json.loads(_download_public_asset(metadata_url).decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ReleaseError("Rollback candidate metadata is invalid.") from exc
    if metadata.get("schema") != 1 or metadata.get("app_id") != app_id or metadata.get("package_name") != package:
        raise ReleaseError("Rollback candidate belongs to another app or package.")
    source = metadata.get("rollback_source")
    if not isinstance(source, dict) or not SOURCE_REF_RE.fullmatch(str(source.get("ref", ""))):
        raise ReleaseError("Candidate was not built from an identified historical app source.")
    source_version, _ = validate_version(str(source.get("version", "")), 1)
    source_code = source.get("source_version_code", 0)
    if type(source_code) is not int or source_code < 0:
        raise ReleaseError("Rollback source versionCode is invalid.")
    version_name, version_code = validate_version(str(metadata.get("version", "")), metadata.get("version_code"))
    if tag != candidate_tag(version_name, version_code, app_id) or version_code <= stable_code:
        raise ReleaseError("Rollback candidate tag or install versionCode is invalid.")
    apk_name = str(metadata.get("apk_name", ""))
    apk_asset = next((asset for asset in assets if asset.get("name") == apk_name), None)
    apk_url = str(apk_asset.get("browser_download_url", "")) if apk_asset else ""
    if not apk_name.endswith(".apk") or apk_url != prefix + urllib.parse.quote(apk_name, safe=""):
        raise ReleaseError("Rollback candidate APK URL does not match its release asset.")
    apk_payload = _download_public_asset(apk_url)
    digest = hashlib.sha256(apk_payload).hexdigest()
    if digest != metadata.get("sha256"):
        raise ReleaseError("Rollback candidate APK SHA-256 does not match its release metadata.")
    with tempfile.TemporaryDirectory(prefix="siny-mobile-rollback-register-") as temp_name:
        apk = Path(temp_name) / "rollback.apk"
        apk.write_bytes(apk_payload)
        _verify_apk(apk, package, version_code)
    entries = current_feed.get("rollback_versions", [])
    if not isinstance(entries, list):
        entries = []
    retained = [item for item in entries if isinstance(item, dict)
                and _positive_int(item.get("version_code"), 0) > stable_code
                and item.get("release_tag") != tag]
    retained.append({"version": source_version, "source_version_code": source_code,
                     "version_code": version_code, "apk_url": apk_url,
                     "sha256": digest, "release_tag": tag})
    retained.sort(key=lambda item: _positive_int(item.get("source_version_code"), 0), reverse=True)
    new_feed = dict(current_feed)
    new_feed["rollback_versions"] = retained
    return _publish_stable_feed(mapping, app_id, new_feed, token)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build a locally signed Android release and promote verified candidates to AutoUpdate stable.")
    sub = parser.add_subparsers(dest="command", required=True)
    candidate = sub.add_parser("candidate", help="build and upload a signed GitHub prerelease")
    candidate.add_argument("version_name")
    candidate.add_argument("version_code", type=int)
    candidate.add_argument("source_ref", nargs="?", help="optional old Git commit/tag to rebuild for a rollback")
    rollback = sub.add_parser("rollback", help="rebuild an older source as a higher-version candidate; stable feed is unchanged")
    rollback.add_argument("source_ref", help="old app commit or tag to rebuild")
    promote = sub.add_parser("promote", help="verify an uploaded candidate and publish it to the stable update feed")
    promote.add_argument("tag")
    register_rollback = sub.add_parser("register-rollback", help="add a verified historical-source candidate to the app's rollback list")
    register_rollback.add_argument("tag")
    args = parser.parse_args(argv)
    try:
        project = ROOT.resolve()
        if args.command == "candidate":
            result = _candidate(project, args.version_name, args.version_code, args.source_ref)
        elif args.command == "rollback":
            url, version_name, version_code = _rollback_candidate(project, args.source_ref)
            result = f"{url}\nRollback candidate: {version_name} (versionCode {version_code}); stable feed unchanged."
        elif args.command == "register-rollback":
            result = _register_rollback(project, args.tag)
        else:
            result = _promote(project, args.tag)
        print(result)
        return 0
    except (ReleaseError, ValueError, OSError) as exc:
        print("Mobile release stopped safely: " + str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
