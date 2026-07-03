#!/usr/bin/env python3
import os
import sys
import re
import argparse
import subprocess
import shutil
import xml.etree.ElementTree as ET

# ponytail: simple release automation script using stdlib to minimize dependencies.
# The script supports validation (dry-run + snapshot deploy) and actual release.

LOG_FILE = "release.log"

def parse_args():
    parser = argparse.ArgumentParser(description="Tus Java Server Release Automation Script")
    parser.add_argument("mode", choices=["validate", "release"], help="Execution mode")
    parser.add_argument("--release-version", help="Version to release (e.g. 1.0.0-3.2)")
    parser.add_argument("--tag", help="SCM tag name (e.g. 1.0.0-3.2)")
    parser.add_argument("--next-dev-version", help="Next development snapshot version")
    parser.add_argument("-y", "--yes", action="store_true", help="Bypass interactive prompts")
    return parser.parse_args()

def get_pom_version(pom_path="pom.xml"):
    if not os.path.exists(pom_path):
        return None
    try:
        tree = ET.parse(pom_path)
        root = tree.getroot()
        ns = {"mvn": "http://maven.apache.org/POM/4.0.0"}
        version_elem = root.find("mvn:version", ns)
        if version_elem is not None:
            return version_elem.text.strip()
    except Exception:
        pass

    # Fallback to regex in case namespace or formatting differs
    try:
        with open(pom_path, "r", encoding="utf-8") as f:
            content = f.read()
        match = re.search(r"<project[^>]*>.*?<version>\s*([^<]+)\s*</version>", content, re.DOTALL)
        if match:
            return match.group(1).strip()
    except Exception:
        pass
    return None

def propose_versions(current_version):
    if current_version.endswith("-SNAPSHOT"):
        release_version = current_version[:-9]
    else:
        release_version = current_version

    tag = release_version

    # Propose next dev version: find last number sequence and increment
    matches = list(re.finditer(r"\d+", release_version))
    if matches:
        last_match = matches[-1]
        start, end = last_match.span()
        last_num = int(last_match.group())
        next_num = last_num + 1
        next_dev_version = release_version[:start] + str(next_num) + release_version[end:] + "-SNAPSHOT"
    else:
        next_dev_version = release_version + ".1-SNAPSHOT"

    return release_version, tag, next_dev_version

def is_git_clean():
    try:
        result = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True, check=True)
        lines = result.stdout.strip().splitlines()
        modified = []
        for line in lines:
            path = line[3:].strip()
            # Ignore release.py and release.log themselves when determining git cleanliness
            if "release.py" in path or "release.log" in path:
                continue
            modified.append(line)
        return len(modified) == 0, modified
    except Exception as e:
        return False, [f"Error running git status: {e}"]

def check_changelog(release_version):
    if not os.path.exists("CHANGELOG.md"):
        return False, "CHANGELOG.md not found"
    try:
        with open("CHANGELOG.md", "r", encoding="utf-8") as f:
            content = f.read()
        # Look for ## [version] or ## version
        pattern = rf"##\s*\[?{re.escape(release_version)}\]?"
        if re.search(pattern, content):
            return True, None
        return False, f"Could not find a section for version '{release_version}' in CHANGELOG.md"
    except Exception as e:
        return False, f"Error reading CHANGELOG.md: {e}"

def get_changelog_notes(release_version):
    if not os.path.exists("CHANGELOG.md"):
        return ""
    try:
        with open("CHANGELOG.md", "r", encoding="utf-8") as f:
            lines = f.readlines()

        header_pattern = rf"##\s*\[?{re.escape(release_version)}\]?"
        start_idx = -1
        for idx, line in enumerate(lines):
            if re.match(header_pattern, line):
                start_idx = idx
                break

        if start_idx == -1:
            return ""

        notes_lines = []
        for line in lines[start_idx + 1:]:
            # Stop if we hit the next header starting with ##
            if re.match(r"^##\s+\S+", line):
                break
            notes_lines.append(line)

        return "".join(notes_lines).strip()
    except Exception:
        return ""

def run_step(step_name, command_args, log_file, env=None):
    print(f"[ ] {step_name}...", end="", flush=True)
    try:
        with open(log_file, "a", encoding="utf-8") as lf:
            lf.write(f"\n--- RUNNING: {' '.join(command_args)} ---\n")
            lf.flush()

            result = subprocess.run(
                command_args,
                stdout=lf,
                stderr=subprocess.STDOUT,
                env=env,
                text=True
            )

        if result.returncode == 0:
            print(f"\r[\033[92m✅\033[0m] {step_name}")
            return True
        else:
            print(f"\r[\033[91m❌\033[0m] {step_name}")
            print(f"\nError: Command '{' '.join(command_args)}' failed with exit code {result.returncode}.")
            print(f"Please check the log file: {log_file}")
            try:
                with open(log_file, "r", encoding="utf-8") as lf:
                    lines = lf.readlines()
                    print("\n--- Last 20 lines of log output ---")
                    for line in lines[-20:]:
                        print(line, end="")
                    print("-----------------------------------\n")
            except Exception:
                pass
            return False
    except Exception as e:
        print(f"\r[\033[91m❌\033[0m] {step_name}")
        print(f"\nException occurred while running step: {e}")
        return False

def locate_release_artifacts(version):
    checkout_target_dir = os.path.join("target", "checkout", "target")
    if not os.path.isdir(checkout_target_dir):
        return None, f"Directory {checkout_target_dir} not found. Did mvn release:perform run successfully?"

    expected_files = [
        f"tus-java-server-{version}.jar",
        f"tus-java-server-{version}-sources.jar",
        f"tus-java-server-{version}-javadoc.jar"
    ]

    paths = []
    for f in expected_files:
        p = os.path.join(checkout_target_dir, f)
        if not os.path.exists(p):
            return None, f"Expected artifact not found: {p}"
        paths.append(p)

    pom_src = os.path.join("target", "checkout", "pom.xml")
    pom_target = os.path.join(checkout_target_dir, f"tus-java-server-{version}.pom")

    if os.path.exists(pom_target):
        paths.append(pom_target)
    elif os.path.exists(pom_src):
        try:
            shutil.copy2(pom_src, pom_target)
            paths.append(pom_target)
        except Exception as e:
            return None, f"Failed to copy pom.xml to {pom_target}: {e}"
    else:
        return None, "Could not find pom.xml in target/checkout"

    return paths, None

def main():
    args = parse_args()

    # Clean/create the log file
    with open(LOG_FILE, "w", encoding="utf-8") as f:
        f.write("=== Release Automation Log ===\n")

    # Step 1: Pre-checks
    print("[ ] Checking repository status...", end="", flush=True)
    if not os.path.exists("pom.xml"):
        print(f"\r[\033[91m❌\033[0m] Checking repository status")
        print("Error: pom.xml not found in current directory. Run this script from the project root.")
        sys.exit(1)

    current_version = get_pom_version()
    if not current_version:
        print(f"\r[\033[91m❌\033[0m] Checking repository status")
        print("Error: Could not read current version from pom.xml")
        sys.exit(1)

    clean, details = is_git_clean()
    if not clean:
        print(f"\r[\033[91m❌\033[0m] Checking repository status")
        print("Error: Git working tree is not clean. Commit or stash your changes before releasing.")
        for line in details:
            print(f"  {line}")
        sys.exit(1)

    print(f"\r[\033[92m✅\033[0m] Checking repository status")

    # Determine versions
    prop_release_version, prop_tag, prop_next_dev_version = propose_versions(current_version)

    release_version = args.release_version or prop_release_version
    tag = args.tag or prop_tag
    next_dev_version = args.next_dev_version or prop_next_dev_version

    if not args.yes and not (args.release_version and args.tag and args.next_dev_version):
        print("=" * 60)
        print("Release Version Configuration:")
        print(f"  Current Version:  {current_version}")
        print(f"  Release Version:  {release_version}")
        print(f"  SCM Tag Name:     {tag}")
        print(f"  Next Development: {next_dev_version}")
        print("=" * 60)

        confirm = input("Are these versions correct? [Y/n]: ").strip().lower()
        if confirm not in ("", "y", "yes"):
            release_version = input(f"Enter release version [{release_version}]: ").strip() or release_version
            tag = input(f"Enter SCM tag name [{tag}]: ").strip() or tag
            next_dev_version = input(f"Enter next development version [{next_dev_version}]: ").strip() or next_dev_version
            print("\nUpdated Version Configuration:")
            print(f"  Release Version:  {release_version}")
            print(f"  SCM Tag Name:     {tag}")
            print(f"  Next Development: {next_dev_version}")
            print("=" * 60)

    # Check CHANGELOG.md has a section for the release version
    print("[ ] Checking CHANGELOG.md...", end="", flush=True)
    has_changelog, err_msg = check_changelog(release_version)
    if not has_changelog:
        print(f"\r[\033[91m❌\033[0m] Checking CHANGELOG.md")
        print(f"Error: {err_msg}")
        sys.exit(1)
    print(f"\r[\033[92m✅\033[0m] Checking CHANGELOG.md")

    # Setup environment for subprocesses
    # We must ensure we do not pass invalid GITHUB_TOKEN to gh CLI or maven if it causes auth problems
    sub_env = os.environ.copy()
    sub_env.pop("GITHUB_TOKEN", None)

    # Automatically set GPG_TTY to the current TTY if not already defined
    if "GPG_TTY" not in sub_env:
        for stream in [sys.stdin, sys.stdout, sys.stderr]:
            try:
                if stream and stream.isatty():
                    sub_env["GPG_TTY"] = os.ttyname(stream.fileno())
                    break
            except Exception:
                pass

    if args.mode == "validate":
        print(f"\n--- Starting Validation Run ({current_version} -> {release_version}) ---")

        # 1. Maven clean install
        if not run_step("Maven clean and install", ["mvn", "clean", "install"], LOG_FILE, env=sub_env):
            sys.exit(1)

        # 2. Maven clean deploy -P release
        if not run_step("Maven clean deploy (SNAPSHOT)", ["mvn", "clean", "deploy", "-P", "release"], LOG_FILE, env=sub_env):
            sys.exit(1)

        # 3. Maven release:clean
        if not run_step("Maven release clean", ["mvn", "release:clean"], LOG_FILE, env=sub_env):
            sys.exit(1)

        # 4. Maven release:prepare (Dry Run)
        dry_run_args = [
            "mvn", "release:prepare",
            "-DdryRun=true",
            "-Dresume=false",
            "-P", "release",
            f"-DreleaseVersion={release_version}",
            f"-Dtag={tag}",
            f"-DdevelopmentVersion={next_dev_version}",
            "-B"
        ]
        if not run_step("Maven release prepare (Dry Run)", dry_run_args, LOG_FILE, env=sub_env):
            sys.exit(1)

        print("\n\033[92mValidation Run Successful! ✅\033[0m")
        print("Please check the generated pom.xml.tag and release.properties files to confirm everything looks good.")
        print("Once satisfied, run the actual release using:")
        print(f"  python3 scripts/release.py release --release-version {release_version} --tag {tag} --next-dev-version {next_dev_version}")

    elif args.mode == "release":
        print(f"\n--- Starting Actual Release Run ({release_version}) ---")

        # 1. Maven release:clean
        if not run_step("Maven release clean", ["mvn", "release:clean"], LOG_FILE, env=sub_env):
            sys.exit(1)

        # 2. Maven release:prepare
        prep_args = [
            "mvn", "release:prepare",
            "-Dresume=false",
            "-P", "release",
            f"-DreleaseVersion={release_version}",
            f"-Dtag={tag}",
            f"-DdevelopmentVersion={next_dev_version}",
            "-B"
        ]
        if not run_step("Maven release prepare", prep_args, LOG_FILE, env=sub_env):
            sys.exit(1)

        # 3. Maven release:perform
        if not run_step("Maven release perform", ["mvn", "release:perform", "-P", "release", "-B"], LOG_FILE, env=sub_env):
            sys.exit(1)

        # 4. Locate built artifacts
        print("[ ] Locating built artifacts...", end="", flush=True)
        artifacts, err = locate_release_artifacts(release_version)
        if err:
            print(f"\r[\033[91m❌\033[0m] Locating built artifacts")
            print(f"Error: {err}")
            sys.exit(1)
        print(f"\r[\033[92m✅\033[0m] Locating built artifacts")
        for art in artifacts:
            print(f"  - Found: {art}")

        # 5. Create GitHub Release
        # Read release notes from CHANGELOG
        notes = get_changelog_notes(release_version)
        if not notes:
            notes = f"Release version {release_version}"

        # Create temp file for release notes to avoid command-line length limits/shell escape issues
        notes_file = "temp_release_notes.txt"
        with open(notes_file, "w", encoding="utf-8") as nf:
            nf.write(notes)

        gh_args = ["gh", "release", "create", tag] + artifacts + [
            "--title", f"Release {tag}",
            "--notes-file", notes_file
        ]

        gh_success = run_step("Creating GitHub Release & uploading artifacts", gh_args, LOG_FILE, env=sub_env)

        # Cleanup temp notes file
        if os.path.exists(notes_file):
            os.remove(notes_file)

        if not gh_success:
            sys.exit(1)

        # 6. Final Maven release clean
        if not run_step("Maven release final cleanup", ["mvn", "release:clean"], LOG_FILE, env=sub_env):
            sys.exit(1)

        print("\n\033[92mRelease Run Successful! 🎉 ✅\033[0m")
        print(f"Release {tag} has been tagged, published to staging, and created on GitHub with the release artifacts.")
        print("Please follow the last manual step on Sonatype Central Portal (close and release the deployment):")
        print("  https://central.sonatype.com/publishing/deployments")

if __name__ == "__main__":
    main()
