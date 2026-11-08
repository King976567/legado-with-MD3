#!/usr/bin/env python3
"""Prepare the official Kotlin compiler and POM in a dedicated local Maven repository."""

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
import xml.etree.ElementTree as ET

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


def _maven_bytes(config: Compiler, filename: str, limit: int) -> bytes:
    # This separate client never receives the step's GitHub token.
    url = f"{MAVEN}/{config.version}/{filename}"
    def read_file():
        request = urllib.request.Request(url, headers={"User-Agent": "kotlin-compiler-prefetch"})
        with urllib.request.build_opener(MavenRedirectHandler()).open(request, timeout=60) as response:
            result = response.read(limit + 1)
            if len(result) > limit:
                raise PrepareError("Official Maven file exceeds the expected limit")
            return result
    return _retry(read_file)


def get_maven_sha1(config: Compiler, filename: str | None = None) -> str:
    sha1 = _maven_bytes(config, (filename or config.filename) + ".sha1", 256).decode("ascii").strip()
    if not re.fullmatch(r"[0-9a-fA-F]{40}", sha1):
        raise PrepareError("Invalid Maven Central compiler SHA1")
    return sha1.lower()


def get_maven_pom(config: Compiler) -> tuple[bytes, str]:
    filename = config.filename.removesuffix(".jar") + ".pom"
    pom = _maven_bytes(config, filename, 1024 * 1024)
    sha1 = get_maven_sha1(config, filename)
    if hashlib.sha1(pom).hexdigest() != sha1:
        raise PrepareError("Official Kotlin POM does not match its Maven SHA1")
    try:
        root = ET.fromstring(pom)
    except ET.ParseError:
        raise PrepareError("Official Kotlin POM is not valid XML") from None
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    if (root.tag != "{http://maven.apache.org/POM/4.0.0}project"
            or root.findtext("m:groupId", namespaces=namespace) != "org.jetbrains.kotlin"
            or root.findtext("m:artifactId", namespaces=namespace) != "kotlin-compiler-embeddable"
            or root.findtext("m:version", namespaces=namespace) != config.version
            or root.find("m:distributionManagement/m:relocation", namespace) is not None):
        raise PrepareError("Official Kotlin POM coordinates do not match the version catalog")
    return pom, sha1


def repository_path(config: Compiler) -> Path:
    root = config.gradle_user_home.resolve()
    destination = (root / "codex-official-kotlin-maven").resolve()
    if not destination.is_relative_to(root):
        raise PrepareError("Compiler repository path escapes GRADLE_USER_HOME")
    return destination


def artifact_path(config: Compiler) -> Path:
    root = repository_path(config)
    destination = (root / "org/jetbrains/kotlin/kotlin-compiler-embeddable"
                   / config.version / config.filename).resolve()
    if not destination.is_relative_to(root):
        raise PrepareError("Compiler artifact path escapes its dedicated repository")
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
    pom, pom_sha1 = get_maven_pom(config)
    destination = artifact_path(config)
    pom_path = destination.with_suffix(".pom")
    expected_sha256 = asset["digest"].removeprefix("sha256:")
    valid_jar = (destination.is_file() and destination.stat().st_size == asset["size"]
                 and _hashes(destination) == (sha1, expected_sha256))
    companions = {pom_path: pom, destination.with_suffix(".jar.sha1"): (sha1 + "\n").encode("ascii"),
                  pom_path.with_suffix(".pom.sha1"): (pom_sha1 + "\n").encode("ascii")}
    if valid_jar and all(path.is_file() and path.read_bytes() == data for path, data in companions.items()):
        return "cached-repository"
    if cached_only:
        raise PrepareError("No complete verified compiler Maven repository is available")
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not valid_jar:
        descriptor, temporary = tempfile.mkstemp(prefix=config.filename + ".", suffix=".part", dir=destination.parent)
        os.close(descriptor)
        temporary = Path(temporary)
        try:
            download_compiler(asset, temporary, sha1, token)
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)
    for path, data in companions.items():
        descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".part", dir=path.parent)
        os.close(descriptor)
        temporary = Path(temporary)
        try:
            temporary.write_bytes(data)
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)
    return "prepared-repository"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--versions-file", type=Path, default=Path("gradle/libs.versions.toml"))
    parser.add_argument("--gradle-user-home", type=Path,
                        default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")))
    parser.add_argument("--cached-only", action="store_true", help="Require a complete verified local repository; never download the asset")
    args = parser.parse_args(argv)
    try:
        config = load_compiler(args.versions_file, args.gradle_user_home)
        result = prepare_compiler(config, os.environ.get("GH_TOKEN", ""), args.cached_only)
    except (PrepareError, OSError, ValueError, UnicodeError):
        print("Kotlin compiler preparation failed; official POM, version, Maven SHA1 and official JAR SHA256 must agree.", file=sys.stderr)
        return 1
    print(f"Kotlin compiler {config.filename}: {result}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
