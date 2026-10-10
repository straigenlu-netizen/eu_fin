#!/usr/bin/env python3
"""Bootstrap a disposable local SonarQube server and export CI evidence."""
import base64
import json
import os
from pathlib import Path
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HOST = "http://127.0.0.1:9000"
PROJECT = os.environ.get("SONAR_PROJECT_KEY", "project")
REPORTS = Path("target/sonarqube")


def request(path, fields=None, credentials=None):
    data = urllib.parse.urlencode(fields).encode() if fields is not None else None
    headers = {}
    if credentials is not None:
        headers["Authorization"] = "Basic " + base64.b64encode(credentials.encode()).decode()
    req = urllib.request.Request(HOST + path, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as response:
        body = response.read().decode()
    return json.loads(body) if body else {}


def export_env(name, value):
    if "\n" in value or "\r" in value:
        raise ValueError("Invalid environment value")
    print("::add-mask::" + value, flush=True)
    with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
        output.write(name + "=" + value + "\n")


def bootstrap():
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        try:
            if request("/api/system/status").get("status") == "UP":
                break
        except (OSError, ValueError):
            pass
        time.sleep(3)
    else:
        raise RuntimeError("SonarQube did not become ready within five minutes")
    password = secrets.token_urlsafe(32)
    # Default credentials belong only to the new loopback-only CI container.
    request("/api/users/change_password", {"login": "admin", "previousPassword": "admin", "password": password}, "admin:admin")
    credentials = "admin:" + password
    request("/api/projects/create", {"project": PROJECT, "name": PROJECT}, credentials)
    token = request("/api/user_tokens/generate", {"name": "ci-analysis"}, credentials)["token"]
    export_env("SONAR_TOKEN", token)
    print("Disposable SonarQube ready; generated token masked.", flush=True)


def collect():
    REPORTS.mkdir(parents=True, exist_ok=True)
    credentials = os.environ["SONAR_TOKEN"] + ":"
    endpoints = {
        "quality-gate.json": "/api/qualitygates/project_status?" + urllib.parse.urlencode({"projectKey": PROJECT}),
        "measures.json": "/api/measures/component?" + urllib.parse.urlencode({"component": PROJECT, "metricKeys": "ncloc,coverage,duplicated_lines_density,reliability_rating,security_rating,sqale_rating"}),
        "issues.json": "/api/issues/search?" + urllib.parse.urlencode({"componentKeys": PROJECT, "ps": 500, "p": 1}),
        "hotspots.json": "/api/hotspots/search?" + urllib.parse.urlencode({"projectKey": PROJECT, "ps": 500, "p": 1}),
    }
    failed = []
    for name, path in endpoints.items():
        try:
            result = request(path, credentials=credentials)
            (REPORTS / name).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        except (OSError, ValueError) as error:
            failed.append(name)
            (REPORTS / (name + ".error.txt")).write_text(type(error).__name__ + "\n", encoding="utf-8")
    gate_path = REPORTS / "quality-gate.json"
    status = json.loads(gate_path.read_text()).get("projectStatus", {}).get("status", "UNAVAILABLE") if gate_path.exists() else "UNAVAILABLE"
    summary = f"## SonarQube Community\n\nProject: `{PROJECT}`\n\nQuality Gate: **{status}**\n\nReports are in the `sonarqube-report` artifact. Issue/hotspot lists contain the first 500 entries; total counts are included in the API responses. The server and its history are disposable.\n"
    if failed:
        summary += "\nUnavailable report endpoints: " + ", ".join(failed) + ".\n"
    (REPORTS / "SUMMARY.md").write_text(summary, encoding="utf-8")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write(summary)
    # Missing reports are operational errors, not evidence of a clean scan.
    if failed:
        raise RuntimeError("Some SonarQube reports could not be exported")


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in {"bootstrap", "collect"}:
        raise SystemExit("Usage: sonar_ci.py bootstrap|collect")
    globals()[sys.argv[1]]()
