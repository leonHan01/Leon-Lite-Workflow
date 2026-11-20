#!/usr/bin/env python3
"""Build and verify the Android management client; does not start or deploy the server."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="Use only existing Gradle dependencies")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    env = os.environ.copy()
    sdk_candidates = [env.get("ANDROID_HOME"), env.get("ANDROID_SDK_ROOT"),
                      str(Path.home() / "Library/Android/sdk"), "/opt/homebrew/share/android-commandlinetools",
                      str(Path.home() / "Android/Sdk")]
    sdk = next((Path(value) for value in sdk_candidates if value and (Path(value) / "platforms/android-35/android.jar").is_file()), None)
    if sdk is None:
        parser.error("Install Android SDK platform 35 and set ANDROID_HOME to its SDK directory")
    env["ANDROID_HOME"] = str(sdk)
    node = shutil.which("node")
    if not node:
        parser.error("Node.js 18+ is required for workspace integration tests")
    subprocess.run([node, "--test", "android/tests/workspace.test.cjs"], cwd=root, env=env, check=True)
    wrapper = "gradlew.bat" if sys.platform == "win32" else "./gradlew"
    command = [wrapper, ":app:assembleDebug", ":app:testDebugUnitTest", ":app:lintDebug", "--no-daemon"]
    if args.offline:
        command.append("--offline")
    subprocess.run(command, cwd=root / "android", env=env, check=True)
    apk_dir = root / "android/app/build/outputs/apk/debug"
    metadata = json.loads((apk_dir / "output-metadata.json").read_text())
    artifact = metadata["elements"][0]
    output = root / "dist/android"
    output.mkdir(parents=True, exist_ok=True)
    apk = output / f"leon-workflow-admin-{artifact['versionName']}-debug.apk"
    shutil.copy2(apk_dir / artifact["outputFile"], apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    apk.with_suffix(".apk.sha256").write_text(f"{digest}  {apk.name}\n")
    print(f"Verified APK: {apk}")
    print(f"SHA-256: {digest}")


if __name__ == "__main__":
    main()
