import json
import tempfile
import unittest
from pathlib import Path

from infra.scripts.verify_repository_policy import (
    ELM_COMMANDS,
    ELM_TRANSPORT,
    OBSERVATION_CONTRACT,
    SAFE_OBSERVATION_FIELDS,
    policy_violations,
)


class RepositoryPolicyTest(unittest.TestCase):
    def scan(self, files: dict[str, str]) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, text in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8")
            return policy_violations(root, list(files))

    def test_rejects_secrets_in_runtime_documentation_tests_and_fixtures(self) -> None:
        token = "ghp_" + "a" * 24
        violations = self.scan(
            {
                "apps/android/app/src/main/Secret.kt": (
                    'const val API_TOKEN = "actual-secret-value-123456"'
                ),
                "docs/example.md": token,
                "services/api/tests/test_fixture.py": token,
                "services/api/tests/fixtures/test.key": "not a real private key",
            }
        )
        self.assertEqual(len(violations), 4)
        self.assertTrue(any("hard-coded secret" in violation for violation in violations))
        self.assertEqual(
            sum("GitHub token" in violation for violation in violations),
            2,
        )
        self.assertTrue(any("committed secret" in violation for violation in violations))

    def test_rejects_real_endpoint_but_allows_local_and_placeholder_hosts(self) -> None:
        violations = self.scan(
            {
                "apps/android/app/src/main/Endpoints.kt": (
                    'val local = "http://127.0.0.1:8000"\n'
                    'val placeholder = "https://api.example.com"\n'
                    'val production = "https://api.real-fleet.net"\n'
                )
            }
        )
        self.assertEqual(len(violations), 1)
        self.assertIn("non-placeholder endpoint", violations[0])

    def test_rejects_diagnostic_and_raw_trace_upload_identifiers(self) -> None:
        violations = self.scan(
            {
                "apps/android/app/src/main/Upload.kt": (
                    "fun uploadRawDiagnostic(localSevenDayTuningTrace: String) = Unit"
                )
            }
        )
        self.assertEqual(len(violations), 1)
        self.assertIn("diagnostic/raw-trace", violations[0])

    def test_observation_contract_is_a_closed_reviewed_allow_list(self) -> None:
        properties = {field: {"type": "string"} for field in SAFE_OBSERVATION_FIELDS}
        properties["raw_obd"] = {"type": "string"}
        schema = {
            "additionalProperties": False,
            "required": sorted(properties),
            "properties": properties,
        }
        violations = self.scan({OBSERVATION_CONTRACT: json.dumps(schema)})
        self.assertTrue(any("reviewed" in violation for violation in violations))

    def test_rejects_unsafe_elm_command_and_alternate_transport(self) -> None:
        commands = """
            internal enum class Elm327Command(val wireValue: String, val displayName: String) {
                CLEAR_CODES("04", "Unsafe")
            }
            internal val Elm327Command.mode01Pid: Int? get() = null
        """
        transport = """
            fun query(command: Elm327Command): String {
                require(command.isAllowedReadOnlyCommand())
                return "${command.wireValue}\\r"
            }
        """
        violations = self.scan(
            {
                ELM_COMMANDS: commands,
                ELM_TRANSPORT: transport,
                "apps/android/app/src/main/OtherTransport.kt": "val socket: BluetoothSocket",
            }
        )
        self.assertTrue(any("unsafe ECU" in violation for violation in violations))
        self.assertTrue(any("alternate OBD" in violation for violation in violations))
        self.assertTrue(any("one guarded raw write" in violation for violation in violations))

    def test_rejects_extra_raw_write_inside_the_reviewed_obd_transport(self) -> None:
        commands = """
            internal enum class Elm327Command(val wireValue: String, val displayName: String) {
                ENGINE_RPM("010C", "RPM")
            }
            internal val Elm327Command.mode01Pid: Int? get() = 0x0C
        """
        transport = """
            fun query(command: Elm327Command): String {
                require(command.isAllowedReadOnlyCommand())
                socket.outputStream.write("${command.wireValue}\\r")
                socket.outputStream.write("04\\r")
                return ""
            }
        """
        violations = self.scan({ELM_COMMANDS: commands, ELM_TRANSPORT: transport})
        self.assertTrue(any("one guarded raw write" in item for item in violations))

    def test_rejects_unreviewed_android_egress_and_hardware_endpoint(self) -> None:
        violations = self.scan(
            {
                "apps/android/app/src/main/StealthUpload.kt": (
                    "import java.net.HttpURLConnection\nval upload: HttpURLConnection"
                ),
                "hardware/bom.csv": "telemetry,https://fleet.real-device.net",
            }
        )
        self.assertTrue(any("network egress" in item for item in violations))
        self.assertTrue(any("non-placeholder endpoint" in item for item in violations))


if __name__ == "__main__":
    unittest.main()
