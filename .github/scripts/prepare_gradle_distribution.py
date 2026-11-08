#!/usr/bin/env python3
"""Prefill the wrapper ZIP from Gradle's official GitHub release, with its pinned SHA.

The wrapper still owns extraction, locking and its .ok marker. Run before Gradle,
after setup-gradle restores its cache; never change the configured distribution URL.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


API = "https://api.github.com/repos/gradle/gradle-distributions"
OFFICIAL_HOSTS = {"api.github.com", "release-assets.githubusercontent.com",
                  "objects.githubusercontent.com", "github-releases.githubusercontent.com"}


class PrepareError(ValueError):
    """A sanitized failure which contains no signed URL or credential."""


@dataclass(frozen=True)
class Distribution:
    url: str
    sha256: str
    version: str
    filename: str
    zip_path: Path
    distribution_dir: Path


def url_hash(url: str) -> str:
    # Gradle PathAssembler: positive MD5 BigInteger rendered in radix 36.
    number = int.from_bytes(hashlib.md5(url.encode("utf-8")).digest(), "big")
    alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
    result = ""
    while number:
        number, digit = divmod(number, 36)
        result = alphabet[digit] + result
    return result or "0"


def _cache_path(home: Path, relative: str) -> Path:
    if "\\" in relative or ":" in relative or any(
        part in ("", ".", "..") for part in relative.split("/")
    ):
        raise PrepareError("Wrapper cache path must be a safe relative path")
    result = (home / relative).resolve()
    if not result.is_relative_to(home.resolve()):
        raise PrepareError("Wrapper cache path escapes GRADLE_USER_HOME")
    return result


def load_distribution(properties_path: Path, gradle_user_home: Path) -> Distribution:
    properties = {}
    for line in properties_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if not separator or key.strip() in properties:
            raise PrepareError("Unsupported or duplicate wrapper property")
        properties[key.strip()] = value.strip().replace("\\:", ":")
    url = properties.get("distributionUrl", "")
    match = re.fullmatch(
        r"https://(?:services|downloads)\.gradle\.org/distributions/gradle-([0-9]+(?:\.[0-9]+){1,2})-bin\.zip",
        url,
    )
    checksum = properties.get("distributionSha256Sum", "")
    if not match or not re.fullmatch(r"[0-9a-fA-F]{64}", checksum):
        raise PrepareError("An official Gradle release URL and pinned SHA-256 are required")
    if any(properties.get(key, "GRADLE_USER_HOME") != "GRADLE_USER_HOME"
           for key in ("distributionBase", "zipStoreBase")):
        raise PrepareError("Only GRADLE_USER_HOME wrapper storage is supported")
    version = match.group(1)
    filename = f"gradle-{version}-bin.zip"
    suffix = f"{filename[:-4]}/{url_hash(url)}"
    zip_root = _cache_path(gradle_user_home, properties.get("zipStorePath", "wrapper/dists"))
    dist_root = _cache_path(gradle_user_home, properties.get("distributionPath", "wrapper/dists"))
    zip_path = _cache_path(zip_root, f"{suffix}/{filename}")
    distribution_dir = _cache_path(dist_root, f"{suffix}/gradle-{version}")
    return Distribution(url, checksum.lower(), version, filename, zip_path, distribution_dir)


def _official_url(url: str) -> None:
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname not in OFFICIAL_HOSTS
            or parsed.username or parsed.password or parsed.port not in (None, 443)):
        raise PrepareError("Refusing a nonofficial Gradle asset destination")


class OfficialRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        _official_url(new_url)
        redirected = super().redirect_request(request, response, code, message, headers, new_url)
        if redirected is not None and urllib.parse.urlsplit(new_url).hostname != "api.github.com":
            redirected.remove_header("Authorization")
        return redirected


def _open(url: str, token: str, accept: str):
    _official_url(url)
    headers = {"Accept": accept, "User-Agent": "gradle-wrapper-prefetch",
               "X-GitHub-Api-Version": "2022-11-28"}
    if token and urllib.parse.urlsplit(url).hostname == "api.github.com":
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    return urllib.request.build_opener(OfficialRedirectHandler()).open(request, timeout=60)


def _retry(operation):
    for attempt in range(3):
        try:
            return operation()
        except (urllib.error.URLError, http.client.HTTPException, OSError):
            if attempt == 2:
                raise PrepareError("Official Gradle API/download failed after three attempts") from None
            time.sleep(attempt + 1)


def get_release_asset(config: Distribution, token: str) -> dict:
    def read_release():
        with _open(f"{API}/releases/tags/v{config.version}", token, "application/vnd.github+json") as response:
            return response.read(2 * 1024 * 1024)
    try:
        release = json.loads(_retry(read_release))
        matches = [item for item in release["assets"] if item.get("name") == config.filename]
        if len(matches) != 1:
            raise PrepareError("Official release must contain exactly one matching Gradle ZIP")
        asset = matches[0]
        if (type(asset.get("id")) is not int or asset["id"] <= 0
                or type(asset.get("size")) is not int or asset["size"] <= 0
                or asset.get("digest") != f"sha256:{config.sha256}"
                or asset.get("url") != f"{API}/releases/assets/{asset['id']}"):
            raise PrepareError("Official asset metadata does not match the pinned wrapper SHA-256")
        return asset
    except (KeyError, TypeError, AttributeError, json.JSONDecodeError):
        raise PrepareError("Invalid official Gradle release metadata") from None


def _sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download_asset(asset: dict, destination: Path, expected_sha: str, token: str) -> None:
    def transfer():
        digest = hashlib.sha256()
        total = 0
        with _open(asset["url"], token, "application/octet-stream") as response, destination.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > asset["size"]:
                    raise PrepareError("Official Gradle ZIP exceeds the release asset size")
                digest.update(chunk)
                output.write(chunk)
        if total != asset["size"] or digest.hexdigest() != expected_sha:
            raise PrepareError("Official Gradle ZIP size or SHA-256 does not match")
    _retry(transfer)


def prepare_distribution(config: Distribution, token: str = "") -> str:
    marker = config.zip_path.with_name(config.filename + ".ok")
    if (marker.is_file() and (config.distribution_dir / "bin/gradle").is_file()
            and (config.distribution_dir / "lib" / f"gradle-launcher-{config.version}.jar").is_file()):
        return "cached-distribution"
    if config.zip_path.is_file() and _sha256(config.zip_path) == config.sha256:
        return "cached-zip"
    asset = get_release_asset(config, token)
    config.zip_path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=config.filename + ".", suffix=".part", dir=config.zip_path.parent)
    os.close(descriptor)
    temporary = Path(temporary)
    try:
        download_asset(asset, temporary, config.sha256, token)
        os.replace(temporary, config.zip_path)
    finally:
        temporary.unlink(missing_ok=True)
    return "downloaded"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--properties", type=Path, default=Path("gradle/wrapper/gradle-wrapper.properties"))
    parser.add_argument("--gradle-user-home", type=Path,
                        default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")))
    args = parser.parse_args(argv)
    try:
        config = load_distribution(args.properties, args.gradle_user_home)
        result = prepare_distribution(config, os.environ.get("GH_TOKEN", ""))
    except (PrepareError, OSError, ValueError):
        # urllib errors may contain signed redirect URLs; never print raw errors.
        print("Gradle distribution preparation failed; wrapper configuration, official metadata, download and SHA-256 must agree.", file=sys.stderr)
        return 1
    print(f"Gradle distribution {config.filename}: {result}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
