#!/usr/bin/env python3
"""Dependency-free release gate for MichiSonae privacy and vehicle safety."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit


SAFE_OBSERVATION_FIELDS = {
    "confidence",
    "detected_at",
    "detector_version",
    "event_id",
    "installation_id",
    "kind",
    "latitude",
    "location_accuracy_m",
    "longitude",
    "severity",
    "source",
    "speed_mps",
}
OBSERVATION_CONTRACT = "contracts/events/road-observation.v1.schema.json"
ELM_COMMANDS = (
    "apps/android/app/src/main/java/io/github/priencelucifer/michisonae/Elm327.kt"
)
ELM_TRANSPORT = (
    "apps/android/app/src/main/java/io/github/priencelucifer/michisonae/"
    "Elm327BluetoothClient.kt"
)
ANDROID_RUNTIME = "apps/android/app/src/main/"

RUNTIME_PREFIXES = (
    ANDROID_RUNTIME,
    "services/api/src/",
    "firmware/roadsense/src/",
    "firmware/roadsense/include/",
    "contracts/",
    "infra/deploy/",
    "infra/production/",
    "infra/staging/",
    ".github/workflows/",
)
ENDPOINT_PREFIXES = tuple(prefix for prefix in RUNTIME_PREFIXES if prefix != "contracts/")
ENDPOINT_PREFIXES += ("hardware/",)
RUNTIME_FILES = {
    "Dockerfile",
    "apps/android/app/build.gradle.kts",
    "apps/android/build.gradle.kts",
    "apps/android/settings.gradle.kts",
}

PRIVATE_KEY = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")
HIGH_CONFIDENCE_SECRETS = (
    ("AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("Slack token", re.compile(r"\bxox[baprs]-[0-9A-Za-z-]{20,}\b")),
)
QUOTED_SECRET_ASSIGNMENT = re.compile(
    r"(?im)^\s*(?:private\s+|internal\s+|public\s+|const\s+|val\s+|var\s+)*"
    r"(?P<name>[A-Za-z_][A-Za-z0-9_]*(?:password|secret|api_?key|token)"
    r"[A-Za-z0-9_]*)\s*=\s*[\"'](?P<value>[^\"']+)[\"']"
)
CONFIG_SECRET_ASSIGNMENT = re.compile(
    r"(?m)^\s*(?P<name>[A-Z][A-Z0-9_]*(?:PASSWORD|SECRET|API_KEY|ACCESS_TOKEN|"
    r"REFRESH_TOKEN)[A-Z0-9_]*)\s*[:=]\s*(?P<value>[^\s#,}]+)"
)
URL = re.compile(r"https?://[^\s\"'<>\\)]+", re.IGNORECASE)
FORBIDDEN_UPLOAD_IDENTIFIERS = re.compile(
    r"(?:raw(?:Diagnostic|Diagnostics|Obd|Ecu|MicrophoneAudio|Sensor(?:Trace|Samples?))|"
    r"raw_(?:diagnostic|diagnostics|obd|ecu|microphone_audio|sensor_(?:trace|samples?))|"
    r"diagnostic(?:Ai)?(?:Prompt|Response|Payload|Upload)|"
    r"diagnostic_(?:ai_)?(?:prompt|response|payload|upload)|"
    r"(?:localSevenDay|local|sevenDay)?TuningTrace|"
    r"(?:local_seven_day_|local_|seven_day_)?tuning_trace|"
    r"upload(?:Raw)?(?:Diagnostic|Diagnostics|Obd|Ecu|MicrophoneAudio|SensorTrace)|"
    r"upload_(?:raw_)?(?:diagnostic|diagnostics|obd|ecu|microphone_audio|sensor_trace))"
)
ALLOWED_OBD_COMMAND = re.compile(r"01[0-9A-F]{2}")
ANDROID_HTTP_EGRESS = {
    "apps/android/app/src/main/java/io/github/priencelucifer/michisonae/HazardSnapshots.kt",
    "apps/android/app/src/main/java/io/github/priencelucifer/michisonae/MichiSonaeApi.kt",
}
ANDROID_HTTP_MARKERS = ("HttpURLConnection", ".openConnection()", "URL(")
ALLOWED_ANDROID_API_PATHS = {
    "/v1/auth:refresh",
    "/v1/installations/current",
    "/v1/installations:register",
    "/v1/observations:batch",
}
FORBIDDEN_API_PAYLOAD_TYPES = re.compile(
    r"\b(?:AudioRecord|Diagnostic|Elm327|ObdReading|RoadSample|SensorEvent|TuningTrace)"
)


def _line(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def _ignored_prose_or_test(path: str) -> bool:
    parts = path.lower().split("/")
    name = parts[-1]
    return (
        parts[0] == "docs"
        or "test" in parts
        or "tests" in parts
        or "fixtures" in parts
        or name.startswith(("readme", "license", "contributing"))
        or name.endswith(".md")
    )


def _runtime(path: str) -> bool:
    return path in RUNTIME_FILES or path.startswith(RUNTIME_PREFIXES)


def _endpoint_sensitive(path: str) -> bool:
    return path in RUNTIME_FILES or path.startswith(ENDPOINT_PREFIXES)


def _placeholder_secret(name: str, value: str) -> bool:
    lowered = value.strip("\"'").lower()
    if name.lower().endswith("_file") or lowered.startswith(("${", "/run/secrets/")):
        return True
    return any(
        marker in lowered
        for marker in (
            "change-me",
            "development",
            "dummy",
            "example",
            "invalid",
            "local",
            "michisonae",
            "placeholder",
            "replace-me",
            "test",
            "unused",
        )
    )


def _allowed_url(value: str) -> bool:
    host = (urlsplit(value).hostname or "").lower()
    return (
        host in {"localhost", "127.0.0.1", "::1", "schemas.android.com", "github.com"}
        or host == "example.com"
        or host.endswith((".example.com", ".example.invalid", ".invalid", ".test"))
    )


def _text(path: Path) -> str | None:
    try:
        payload = path.read_bytes()
    except OSError:
        return None
    if len(payload) > 1_048_576 or b"\0" in payload:
        return None
    try:
        return payload.decode("utf-8")
    except UnicodeDecodeError:
        return None


def _tracked_paths(root: Path) -> list[str]:
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z"],
        check=True,
        capture_output=True,
    )
    return [os.fsdecode(item) for item in result.stdout.split(b"\0") if item]


def policy_violations(root: Path, paths: list[str] | None = None) -> list[str]:
    """Return deterministic release-policy violations for tracked repository files."""
    repository_scan = paths is None
    selected_paths = _tracked_paths(root) if paths is None else paths
    tracked = sorted(path.replace("\\", "/") for path in selected_paths)
    tracked_set = set(tracked)
    texts: dict[str, str] = {}
    violations: list[str] = []

    for path in tracked:
        name = Path(path).name.lower()
        ignored = _ignored_prose_or_test(path)
        if (
            (name.startswith(".env") and not name.endswith(".env.example"))
            or name in {"id_rsa", "id_ed25519", "local.properties", "secrets.properties"}
            or Path(name).suffix in {".jks", ".key", ".keystore", ".p12", ".pfx", ".pem"}
        ):
            violations.append(f"{path}: committed secret or local-configuration file")

        text = _text(root / path)
        if text is None:
            continue
        texts[path] = text

        match = PRIVATE_KEY.search(text)
        if match:
            violations.append(f"{path}:{_line(text, match.start())}: private key material")
        for label, pattern in HIGH_CONFIDENCE_SECRETS:
            match = pattern.search(text)
            if match:
                violations.append(f"{path}:{_line(text, match.start())}: {label}")

        if ignored:
            continue

        if _runtime(path):
            for pattern in (QUOTED_SECRET_ASSIGNMENT, CONFIG_SECRET_ASSIGNMENT):
                for match in pattern.finditer(text):
                    if not _placeholder_secret(match.group("name"), match.group("value")):
                        violations.append(
                            f"{path}:{_line(text, match.start())}: hard-coded secret value"
                        )
            match = FORBIDDEN_UPLOAD_IDENTIFIERS.search(text)
            if match:
                violations.append(
                    f"{path}:{_line(text, match.start())}: forbidden diagnostic/raw-trace "
                    "network payload identifier"
                )

        if _endpoint_sensitive(path):
            for match in URL.finditer(text):
                if not _allowed_url(match.group()):
                    violations.append(
                        f"{path}:{_line(text, match.start())}: committed non-placeholder endpoint"
                    )

    if OBSERVATION_CONTRACT in tracked_set:
        try:
            schema = json.loads(texts[OBSERVATION_CONTRACT])
            fields = set(schema["properties"])
            required = set(schema["required"])
            if (
                fields != SAFE_OBSERVATION_FIELDS
                or required != SAFE_OBSERVATION_FIELDS
                or schema.get("additionalProperties") is not False
            ):
                violations.append(
                    f"{OBSERVATION_CONTRACT}: upload contract must contain only reviewed, "
                    "required observation fields"
                )
        except (KeyError, TypeError, ValueError, json.JSONDecodeError):
            violations.append(f"{OBSERVATION_CONTRACT}: invalid observation upload contract")

    if repository_scan and ELM_COMMANDS not in tracked_set:
        violations.append(f"{ELM_COMMANDS}: required typed ELM327 command allow-list is missing")
    if repository_scan and ELM_TRANSPORT not in tracked_set:
        violations.append(f"{ELM_TRANSPORT}: required guarded ELM327 transport is missing")

    if ELM_COMMANDS in tracked_set or ELM_TRANSPORT in tracked_set:
        elm = texts.get(ELM_COMMANDS, "")
        enum = elm.partition("internal enum class Elm327Command")[2].partition(
            "internal val Elm327Command.mode01Pid"
        )[0]
        commands = re.findall(r'^\s*[A-Z0-9_]+\("([A-Z0-9]+)",', enum, re.MULTILINE)
        unsafe = sorted(
            command
            for command in commands
            if command not in {"ATE0", "ATSP0", "03"}
            and ALLOWED_OBD_COMMAND.fullmatch(command) is None
        )
        if not commands:
            violations.append(f"{ELM_COMMANDS}: typed ELM327 command allow-list not found")
        if unsafe:
            violations.append(
                f"{ELM_COMMANDS}: unsafe ECU command(s) outside Mode 01/03: {', '.join(unsafe)}"
            )
        transport = texts.get(ELM_TRANSPORT, "")
        required_guards = (
            "query(command: Elm327Command)",
            "require(command.isAllowedReadOnlyCommand())",
            '"${command.wireValue}\\r"',
        )
        if ELM_TRANSPORT not in tracked_set or any(
            guard not in transport for guard in required_guards
        ):
            violations.append(f"{ELM_TRANSPORT}: ELM327 typed transport guard is incomplete")
        if transport.count("socket.outputStream.write(") != 1:
            violations.append(
                f"{ELM_TRANSPORT}: ELM327 transport must have one guarded raw write point"
            )

        for path in tracked:
            if not path.startswith(ANDROID_RUNTIME) or not path.endswith(".kt"):
                continue
            text = texts.get(path, "")
            if path != ELM_TRANSPORT and any(
                marker in text
                for marker in (
                    "BluetoothSocket",
                    "createRfcommSocketToServiceRecord",
                    "outputStream.write(",
                )
            ):
                violations.append(f"{path}: unreviewed alternate OBD command transport")

    for path in tracked:
        if not path.startswith(ANDROID_RUNTIME) or not path.endswith(".kt"):
            continue
        text = texts.get(path, "")
        if path not in ANDROID_HTTP_EGRESS and any(
            marker in text for marker in ANDROID_HTTP_MARKERS
        ):
            violations.append(f"{path}: unreviewed Android network egress implementation")

    api_text = texts.get(
        "apps/android/app/src/main/java/io/github/priencelucifer/michisonae/MichiSonaeApi.kt",
        "",
    )
    api_paths = set(re.findall(r'"(/v1/[^"$]+)"', api_text))
    unexpected_api_paths = sorted(api_paths - ALLOWED_ANDROID_API_PATHS)
    if unexpected_api_paths:
        violations.append(
            "apps/android/app/src/main/java/io/github/priencelucifer/michisonae/"
            f"MichiSonaeApi.kt: unreviewed API path(s): {', '.join(unexpected_api_paths)}"
        )
    match = FORBIDDEN_API_PAYLOAD_TYPES.search(api_text)
    if match:
        violations.append(
            "apps/android/app/src/main/java/io/github/priencelucifer/michisonae/"
            f"MichiSonaeApi.kt:{_line(api_text, match.start())}: forbidden local/raw type at "
            "the network boundary"
        )

    return sorted(set(violations))


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    try:
        violations = policy_violations(root)
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"Repository policy check could not inspect tracked files: {error}", file=sys.stderr)
        return 2
    if violations:
        print("Repository privacy/release policy failed:", file=sys.stderr)
        for violation in violations:
            print(f"- {violation}", file=sys.stderr)
        return 1
    print("Repository privacy/release policy passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
