"""Verify language runtimes extracted from the real executable JAR using an isolated, disposable database.

Run after mvn package; optionally set PLAYWRIGHT_MODULE_PATH and CHROME_PATH to also exercise the real UI.
"""
import json
import datetime
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "target/hello-service-0.0.1-SNAPSHOT.jar"
JAVA = Path(os.environ["JAVA_HOME"]) / "bin/java"


def main():
    with tempfile.TemporaryDirectory(prefix="leon-packaged-workflows-") as directory:
        log = Path(directory) / "server.log"
        with log.open("w") as output:
            process = subprocess.Popen(
                [str(JAVA), "-Xmx384m", "-jar", str(JAR), "--server.port=0",
                 "--server.address=127.0.0.1", "--spring.datasource.url=jdbc:h2:mem:packaged_workflows",
                 "--spring.jpa.hibernate.ddl-auto=create-drop"], cwd=directory, stdout=output,
                stderr=subprocess.STDOUT, env={"PATH": "/usr/bin:/bin", "LANG": "en_US.UTF-8"})
            try:
                deadline = time.monotonic() + 35
                match = None
                while time.monotonic() < deadline:
                    match = re.search(r"Tomcat started on port (\d+)", log.read_text())
                    if match or process.poll() is not None:
                        break
                    time.sleep(.1)
                if not match:
                    raise AssertionError("Packaged application failed to start: " + log.read_text()[-4000:])
                base = "http://127.0.0.1:" + match.group(1)

                def api(route, body=None, method=None):
                    data = None if body is None else json.dumps(body).encode()
                    request = urllib.request.Request(base + "/api/workflows" + route, data=data, method=method,
                                                     headers={"Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest"})
                    with urllib.request.urlopen(request, timeout=35) as response:
                        return json.load(response)

                workflow = api("", {"name": "Packaged JS + Groovy smoke"})
                graph = {"nodes": [
                    {"id": "start", "type": "start", "name": "Start", "config": {}},
                    {"id": "js", "type": "script", "name": "JavaScript", "config": {"language": "javascript", "source": "function main(i,c){return {items:[{json:{value:i.items[0].json.value+1}}]};}", "timeoutMs": 15000}},
                    {"id": "groovy", "type": "script", "name": "Groovy", "config": {"language": "groovy", "source": "def main(Map i,Map c){return [items:[[json:[value:i.items[0].json.value*2]]]]}", "timeoutMs": 15000}},
                    {"id": "end", "type": "end", "name": "End", "config": {}}],
                    "edges": [{"id": "one", "source": "start", "target": "js", "branch": ""}, {"id": "two", "source": "js", "target": "groovy", "branch": ""}, {"id": "three", "source": "groovy", "target": "end", "branch": ""}]}
                workflow = api("/" + workflow["id"], {"name": workflow["name"], "revision": 1, "definition": graph}, "PUT")
                api("/" + workflow["id"] + "/publish", {"revision": workflow["revision"]})
                run = api("/" + workflow["id"] + "/runs", {"input": {"items": [{"json": {"value": 20}}]}})
                deadline = time.monotonic() + 30
                while run["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline:
                    time.sleep(.1)
                    run = api("/runs/" + run["id"])
                assert run["status"] == "SUCCEEDED", run.get("error")
                assert run["output"]["items"][0]["json"]["value"] == 42
                print("Packaged JAR smoke passed: JavaScript → Groovy → 42, isolated database.", flush=True)
                planned = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=6)
                cron = f"{planned.second} {planned.minute} {planned.hour} {planned.day} {planned.month} *"
                config = api('/' + workflow['id'] + '/schedule', {'enabled': True, 'cron': cron, 'timeZone': 'UTC',
                    'input': {'items': [{'json': {'value': 20}}]}, 'revision': 0}, 'PUT')
                deadline = time.monotonic() + 30
                scheduled = None
                while time.monotonic() < deadline:
                    listing = api('/runs?workflowId=' + workflow['id'] + '&trigger=SCHEDULED')
                    if listing['items']:
                        scheduled = api('/runs/' + listing['items'][0]['id'])
                        if config['enabled']:
                            config['enabled'] = False
                            config = api('/' + workflow['id'] + '/schedule', config, 'PUT')
                        if scheduled['status'] not in ('QUEUED', 'RUNNING'):
                            break
                    time.sleep(.15)
                assert scheduled and scheduled['status'] == 'SUCCEEDED', scheduled and scheduled.get('error')
                assert scheduled['triggerType'] == 'SCHEDULED' and scheduled['scheduledAt']
                assert scheduled['output']['items'][0]['json']['value'] == 42
                print('Packaged scheduler smoke passed: automatic trigger, published version, instance list and pause.', flush=True)
                if os.environ.get("PLAYWRIGHT_MODULE_PATH"):
                    subprocess.run(["node", str(ROOT / "src/test/browser/workflows-live.cjs")], check=True,
                                   env={**os.environ, "WORKFLOW_BASE_URL": base}, timeout=40)
            finally:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    main()
