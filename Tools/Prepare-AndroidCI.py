"""Prepare the same checksum-pinned dependencies used by local Android builds."""

import argparse
import hashlib
from pathlib import Path
import subprocess


SIGNAL_FILES = {
    "libsignal-android-0.86.5.aar": "771e9a188fa30d96cef5433638e7c9ec00975c7afb64f94cdff97281330a0b93",
    "libsignal-android-0.86.5.module": "87cadeb64ed5b92f6b27e48bf6c6c65141403a2516947b9fbca26a3619b0e04c",
    "libsignal-android-0.86.5.pom": "781bdf8dba35247812ca0a08bd2ec90a2a53487f17f4933998b8c3265d425b4a",
    "libsignal-client-0.86.5.jar": "d300c272dae155b21ae8988f51b6b493001cd2d83f5d2f9ae15e743c7ff971bd",
    "libsignal-client-0.86.5.module": "72f97810822e465e6316df9a5a00ffe5e562454282cf3e578e3d641f22dfd3d4",
    "libsignal-client-0.86.5.pom": "0ec1f4ec5679808a1a3318df716cf5126cb666f742dfbc52a8830c3d87d23811",
}
MAVEN = "https://repo.maven.apache.org/maven2/"


def download_verified(url, target, expected):
    if target.is_file():
        with target.open("rb") as content:
            actual = hashlib.file_digest(content, "sha256").hexdigest()
        if actual == expected:
            print(f"Verified {target.name}")
            return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".part")
    try:
        subprocess.run([
            "curl", "--fail", "--location", "--retry", "5", "--retry-all-errors",
            "--connect-timeout", "30", "--max-time", "1800",
            "--output", str(temporary), url,
        ], check=True)
        with temporary.open("rb") as content:
            actual = hashlib.file_digest(content, "sha256").hexdigest()
        if actual != expected:
            raise ValueError(f"SHA-256 mismatch for {target.name}: {actual}")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependency-root", required=True, type=Path)
    root = parser.parse_args().dependency_root.resolve()
    for name, expected in SIGNAL_FILES.items():
        artifact = name.split("-0.86.5")[0]
        relative = f"org/signal/{artifact}/0.86.5/{name}"
        download_verified(MAVEN + relative, root / "maven-local" / relative, expected)
    download_verified(
        MAVEN + "com/google/protobuf/protobuf-javalite/3.25.8/protobuf-javalite-3.25.8.jar",
        root / "protobuf/downloads/protobuf-javalite-3.25.8.jar",
        "918bcc959cc0f5cb58798cec347a15d41cee9a1b8101c352954fd18d44e2e1c7",
    )


if __name__ == "__main__":
    main()
