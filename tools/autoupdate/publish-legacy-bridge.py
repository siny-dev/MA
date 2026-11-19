#!/usr/bin/env python3
"""Publish one same-package/same-signer bridge for the historical MA updater."""

from __future__ import annotations

import base64
import hashlib
import importlib.util
import json
import sys
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).absolute().parents[2]
REPOSITORY = "siny-dev/ma-updates"
API_ROOT = f"https://api.github.com/repos/{REPOSITORY}"
APP_ID = "ma-3c44c3e1b8"
PACKAGE = "com.doumi.helper"
VERSION = "1.0.21"
VERSION_CODE = 1007
SIGNER_SHA256 = "5f5951336912c1c8772c96fccc88799ba96beba2488fe3296d22c23469da8987"
RELEASE_TAG = f"v{VERSION}"
APK_NAME = f"MA-{VERSION}.apk"
LEGACY_MANIFEST_NAME = "latest.json"


def load_release_tool():
    path = ROOT / "tools" / "autoupdate" / "publish-github-release.py"
    spec = importlib.util.spec_from_file_location("ma_release_tool", path)
    tool = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tool)
    return tool


def contents_file(tool, token: str):
    return tool.api_request(token, "GET", f"{API_ROOT}/contents/version.txt?ref=main")


def decode_contents(item) -> bytes:
    if item.get("encoding") != "base64":
        raise tool_error("GitHub version.txt did not use the expected base64 contents response.")
    try:
        return base64.b64decode(item.get("content", ""), validate=False)
    except Exception:
        raise tool_error("GitHub version.txt could not be decoded.") from None


def tool_error(message):
    return RuntimeError(message)


def verify_local(tool):
    metadata = json.loads((ROOT / "mobile-app.json").read_text(encoding="utf-8-sig"))
    version = json.loads((ROOT / "version.json").read_text(encoding="utf-8-sig"))
    apk_path = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    if (metadata.get("app_id") != APP_ID or metadata.get("application_id") != PACKAGE
            or metadata.get("signing_certificate_sha256") != SIGNER_SHA256
            or version.get("version") != VERSION or version.get("version_code") != VERSION_CODE):
        raise tool_error("The local MA package, signer or version differs from the reviewed bridge.")
    tool.verify_local_apk_signature(apk_path, SIGNER_SHA256)
    tool.verify_apk_manifest_identity(apk_path, {
        "package_name": PACKAGE, "version_code": VERSION_CODE, "version": VERSION,
    })
    apk_bytes = apk_path.read_bytes()
    manifest = {
        "packageName": PACKAGE,
        "versionCode": VERSION_CODE,
        "versionName": VERSION,
        "apkAssetName": APK_NAME,
        "sha256": hashlib.sha256(apk_bytes).hexdigest(),
        "size": len(apk_bytes),
    }
    baseline_path = ROOT / "build" / "diagnosis" / "release_bisect" / "ma-updates" / "v1.0.005-latest.json"
    if baseline_path.is_file():
        baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
        if set(manifest) != set(baseline):
            raise tool_error("The bridge metadata no longer matches the historical latest.json schema.")
    output = ROOT / "build" / "legacy-bridge"
    output.mkdir(parents=True, exist_ok=True)
    (output / LEGACY_MANIFEST_NAME).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return apk_path, output / LEGACY_MANIFEST_NAME, manifest


def get_tag_release(tool, token: str):
    try:
        return tool.api_request(token, "GET", f"{API_ROOT}/releases/tags/{urllib.parse.quote(RELEASE_TAG, safe='')}")
    except tool.GitHubError as exc:
        if "HTTP 404" in str(exc):
            return None
        raise


def find_asset(release, name: str):
    matches = [asset for asset in release.get("assets", []) if asset.get("name") == name]
    if len(matches) != 1:
        raise tool_error(f"Legacy bridge release must contain exactly one {name} asset.")
    return matches[0]


def verify_remote(tool, token: str, release, apk_path: Path, manifest_path: Path, manifest: dict):
    if not release or release.get("draft") or release.get("prerelease") or release.get("tag_name") != RELEASE_TAG:
        raise tool_error("The legacy bridge release is not published under the expected tag.")
    apk_asset = find_asset(release, APK_NAME)
    manifest_asset = find_asset(release, LEGACY_MANIFEST_NAME)
    apk = tool.download_private_asset(token, apk_asset["url"], 100 * 1024 * 1024)
    remote_manifest = tool.download_private_asset(token, manifest_asset["url"], 256 * 1024)
    expected_apk = apk_path.read_bytes()
    expected_manifest = manifest_path.read_bytes()
    if (apk != expected_apk or remote_manifest != expected_manifest
            or apk_asset.get("size") != manifest["size"]):
        raise tool_error("Authenticated bridge release assets differ from the locally verified APK/manifest.")
    with __import__("tempfile").TemporaryDirectory(prefix="ma-legacy-bridge-") as directory:
        staged = Path(directory) / APK_NAME
        staged.write_bytes(apk)
        tool.verify_local_apk_signature(staged, SIGNER_SHA256)
        tool.verify_apk_manifest_identity(staged, {
            "package_name": PACKAGE, "version_code": VERSION_CODE, "version": VERSION,
        })


def publish(tool, token: str, apk_path: Path, manifest_path: Path, manifest: dict):
    current = contents_file(tool, token)
    current_bytes = decode_contents(current)
    if current_bytes not in (b"1006\n", b"1007\n"):
        raise tool_error("The legacy version pointer is neither the reviewed 1006 baseline nor the already applied 1007.")
    if current_bytes == b"1007\n":
        release = get_tag_release(tool, token)
        verify_remote(tool, token, release, apk_path, manifest_path, manifest)
        print("Bridge release and version pointer are already published and verified.")
        return

    release = get_tag_release(tool, token)
    if release:
        if not release.get("draft"):
            verify_remote(tool, token, release, apk_path, manifest_path, manifest)
        else:
            upload_template = release["upload_url"].split("{", 1)[0]
            existing = {asset.get("name"): asset for asset in release.get("assets", [])}
            for path, name, mime in (
                (apk_path, APK_NAME, "application/vnd.android.package-archive"),
                (manifest_path, LEGACY_MANIFEST_NAME, "application/json"),
            ):
                if name in existing:
                    current_asset = tool.download_private_asset(token, existing[name]["url"], 100 * 1024 * 1024)
                    if current_asset != path.read_bytes():
                        raise tool_error(f"Draft already has a different {name}; refusing to overwrite it.")
                    continue
                tool.api_request(token, "POST", upload_template + "?name=" + urllib.parse.quote(name, safe=""),
                                 path.read_bytes(), content_type=mime)
            release = tool.api_request(token, "PATCH", f"{API_ROOT}/releases/{release['id']}",
                                       {"draft": False, "make_latest": "true"})
    else:
        release = tool.api_request(token, "POST", f"{API_ROOT}/releases", {
            "tag_name": RELEASE_TAG,
            "name": f"MA compatibility bridge {VERSION}",
            "body": "One-time AutoUpdate compatibility bridge for the original com.doumi.helper package and signing identity.",
            "draft": True,
            "prerelease": False,
            "make_latest": "true",
        })
        draft_id = release["id"]
        upload_template = release["upload_url"].split("{", 1)[0]
        for path, name, mime in (
            (apk_path, APK_NAME, "application/vnd.android.package-archive"),
            (manifest_path, LEGACY_MANIFEST_NAME, "application/json"),
        ):
            tool.api_request(token, "POST", upload_template + "?name=" + urllib.parse.quote(name, safe=""),
                             path.read_bytes(), content_type=mime)
        release = tool.api_request(token, "PATCH", f"{API_ROOT}/releases/{draft_id}",
                                   {"draft": False, "make_latest": "true"})

    verify_remote(tool, token, release, apk_path, manifest_path, manifest)
    updated = tool.api_request(token, "PUT", f"{API_ROOT}/contents/version.txt", {
        "message": f"Enable MA {VERSION} compatibility bridge",
        "content": base64.b64encode(b"1007\n").decode("ascii"),
        "sha": current["sha"],
        "branch": "main",
    })
    if updated.get("content", {}).get("path") != "version.txt":
        raise tool_error("GitHub did not confirm the version pointer update.")
    final = contents_file(tool, token)
    if decode_contents(final) != b"1007\n":
        raise tool_error("The old AutoUpdate version pointer did not read back as 1007 LF.")
    release = get_tag_release(tool, token)
    verify_remote(tool, token, release, apk_path, manifest_path, manifest)
    print(f"Published {RELEASE_TAG}; authenticated APK and latest.json downloads match local verified bytes.")
    print("Published old AutoUpdate version.txt as exactly 1007 LF after release verification.")


def main() -> int:
    tool = load_release_tool()
    apk_path, manifest_path, manifest = verify_local(tool)
    if "--publish" not in sys.argv:
        print(f"Validated bridge candidate {RELEASE_TAG}: {manifest['size']} bytes, SHA-256 {manifest['sha256']}.")
        print("No GitHub changes made; pass --publish to publish the release and then advance version.txt.")
        return 0
    token = tool.credential_token()
    publish(tool, token, apk_path, manifest_path, manifest)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)
