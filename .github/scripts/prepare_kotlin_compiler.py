#!/usr/bin/env python3
"""Prefill Kotlin's official compiler JAR; let Gradle resolve and index it normally."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import tomllib
import urllib.parse
import urllib.request

from prepare_gradle_distribution import PrepareError, _open, _retry


API = "https://api.github.com/repos/JetBrains/kotlin"
MAVEN = "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable"


@dataclass(frozen=True)
class Compiler:
    version: str
    filename: str
    gradle_user_home: Path


def load_compiler(versions_file: Path, gradle_user_home: Path) -> Compiler:
    with versions_file.open("rb") as source:
        versions = tomllib.load(source).get("versions", {})
    version = versions.get("kotlin") if isinstance(versions, dict) else None
    if not isinstance(version, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise PrepareError("A stable Kotlin version is required in the version catalog")
    return Compiler(version, f"kotlin-compiler-embeddable-{version}.jar", gradle_user_home)


def get_release_asset(config: Compiler, token: str) -> dict:
    def read_release():
        with _open(f"{API}/releases/tags/v{config.version}", token, "application/vnd.github+json") as response:
            return response.read(2 * 1024 * 1024)
    try:
        release = json.loads(_retry(read_release))
        assets = [asset for asset in release["assets"] if asset.get("name") == config.filename]
        if len(assets) != 1:
            raise PrepareError("Exactly one official Kotlin compiler asset is required")
        asset = assets[0]
        if (type(asset.get("id")) is not int or asset["id"] <= 0
                or type(asset.get("size")) is not int or asset["size"] <= 0
                or not isinstance(asset.get("digest"), str)
                or not re.fullmatch(r"sha256:[0-9a-f]{64}", asset["digest"])
                or asset.get("url") != f"{API}/releases/assets/{asset['id']}"):
            raise PrepareError("Invalid official Kotlin compiler asset metadata")
        return asset
    except (KeyError, TypeError, AttributeError, json.JSONDecodeError):
        raise PrepareError("Invalid official Kotlin release metadata") from None


class MavenRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        parsed = urllib.parse.urlsplit(new_url)
        if (parsed.scheme != "https" or parsed.hostname != "repo.maven.apache.org"
                or parsed.username or parsed.password or parsed.port not in (None, 443)):
            raise PrepareError("Refusing a nonofficial Maven checksum destination")
        return super().redirect_request(request, response, code, message, headers, new_url)


def get_maven_sha1(config: Compiler) -> str:
    # This separate client never receives the step's GitHub token.
    url = f"{MAVEN}/{config.version}/{config.filename}.sha1"
    def read_checksum():
        request = urllib.request.Request(url, headers={"User-Agent": "kotlin-compiler-prefetch"})
        with urllib.request.build_opener(MavenRedirectHandler()).open(request, timeout=60) as response:
            return response.read(256).decode("ascii").strip()
    sha1 = _retry(read_checksum)
    if not re.fullmatch(r"[0-9a-fA-F]{40}", sha1):
        raise PrepareError("Invalid Maven Central compiler SHA1")
    return sha1.lower()


def artifact_path(config: Compiler, sha1: str) -> Path:
    if not re.fullmatch(r"[0-9a-f]{40}", sha1):
        raise PrepareError("A verified Maven SHA1 is required for the artifact cache")
    root = config.gradle_user_home.resolve()
    # Gradle's GroupedAndNamedUniqueFileStore omits leading zeros from the hash directory.
    destination = (root / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable"
                   / config.version / (sha1.lstrip("0") or "0") / config.filename).resolve()
    if not destination.is_relative_to(root):
        raise PrepareError("Compiler cache path escapes GRADLE_USER_HOME")
    return destination


def _hashes(path: Path) -> tuple[str, str]:
    sha1, sha256 = hashlib.sha1(), hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            sha1.update(chunk)
            sha256.update(chunk)
    return sha1.hexdigest(), sha256.hexdigest()


def download_compiler(asset: dict, destination: Path, sha1: str, token: str) -> None:
    expected_sha256 = asset["digest"].removeprefix("sha256:")
    def transfer():
        total = 0
        actual_sha1, actual_sha256 = hashlib.sha1(), hashlib.sha256()
        with _open(asset["url"], token, "application/octet-stream") as response, destination.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > asset["size"]:
                    raise PrepareError("Kotlin compiler download exceeds the official asset size")
                actual_sha1.update(chunk)
                actual_sha256.update(chunk)
                output.write(chunk)
        if (total != asset["size"] or actual_sha1.hexdigest() != sha1
                or actual_sha256.hexdigest() != expected_sha256):
            raise PrepareError("Kotlin compiler size, Maven SHA1 and official SHA256 must all match")
    _retry(transfer)


def prepare_compiler(config: Compiler, token: str = "", cached_only: bool = False) -> str:
    asset = get_release_asset(config, token)
    sha1 = get_maven_sha1(config)
    destination = artifact_path(config, sha1)
    expected_sha256 = asset["digest"].removeprefix("sha256:")
    if (destination.is_file() and destination.stat().st_size == asset["size"]
            and _hashes(destination) == (sha1, expected_sha256)):
        return "cached-jar"
    if cached_only:
        raise PrepareError("No compiler JAR matches both official checksums in the local cache")
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=config.filename + ".", suffix=".part", dir=destination.parent)
    os.close(descriptor)
    temporary = Path(temporary)
    try:
        download_compiler(asset, temporary, sha1, token)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)
    return "downloaded"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--versions-file", type=Path, default=Path("gradle/libs.versions.toml"))
    parser.add_argument("--gradle-user-home", type=Path,
                        default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")))
    parser.add_argument("--cached-only", action="store_true", help="Require a verified local JAR; never download the asset")
    args = parser.parse_args(argv)
    try:
        config = load_compiler(args.versions_file, args.gradle_user_home)
        result = prepare_compiler(config, os.environ.get("GH_TOKEN", ""), args.cached_only)
    except (PrepareError, OSError, ValueError, UnicodeError):
        print("Kotlin compiler preparation failed; version, official metadata, Maven SHA1 and downloaded SHA256 must agree.", file=sys.stderr)
        return 1
    print(f"Kotlin compiler {config.filename}: {result}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
