"""Two real replays, recorded transport fault injection, bounded local evidence storage.

Only NEW replay output is compressed. All paths and decoded bytes remain intact.
The original bundle is read-only. Faults are derived from its captured HTTP absence;
the proxy forwards the real request to Python and records its real upstream bytes.
"""
import argparse
from contextlib import contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import urlsplit
import uuid


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return 'sha256:' + h.hexdigest()


def write(path, value):
    with Path(path).open('x') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)


def fault_schedule(bundle):
    schedule = {}
    for line in (bundle / 'events.ndjson').read_bytes().splitlines():
        event = json.loads(line)
        if event['kind'] != 'FEED_QUERY':
            continue
        base = bundle / 'raw/feed' / ('event-' + str(event['sequence']))
        page = Path(str(base) + '-page.json')
        request = Path(str(base) + '-request.json')
        if not page.is_file() or not request.is_file():
            continue
        captured = json.loads(page.read_bytes())
        if captured['pythonInvoked'] and not captured['responseCaptured']:
            assert captured['page']['sortSource'] == 'LATEST'
            assert not Path(str(base) + '-response.json').exists()
            assert not Path(str(base) + '-http.json').exists()
            at = json.loads(request.read_bytes())['recommendation_at']
            assert at not in schedule
            schedule[at] = dict(eventId=event['eventId'], sequence=event['sequence'],
                               originalRequestDigest=digest(request), originalPageDigest=digest(page),
                               injection='WITHHOLD_REAL_UPSTREAM_RESPONSE_FOR_35_SECONDS')
    return schedule


@contextmanager
def proxy(endpoint, schedule, output):
    target = urlsplit(endpoint)
    assert target.hostname == '127.0.0.1' and target.scheme == 'http'
    counts = {key: 0 for key in schedule}
    failures = []
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            connection = None
            try:
                assert self.path == target.path
                length = int(self.headers['Content-Length'])
                assert 0 < length <= 262144
                body = self.rfile.read(length)
                at = json.loads(body)['recommendation_at']
                fault = schedule.get(at)
                if fault:
                    with lock:
                        counts[at] += 1
                        assert counts[at] == 1
                    folder = output / ('event-' + str(fault['sequence']))
                    folder.mkdir()
                    (folder / 'upstream-request.json').write_bytes(body)
                connection = http.client.HTTPConnection(target.hostname, target.port, timeout=5)
                started = time.monotonic()
                connection.request('POST', target.path, body,
                                   {'Content-Type': 'application/json', 'Authorization': self.headers['Authorization']})
                response = connection.getresponse()
                payload = response.read(65537)
                assert len(payload) <= 65536
                if fault:
                    (folder / 'upstream-response.json').write_bytes(payload)
                    write(folder / 'injection.json', dict(**fault, status=response.status,
                          actualRequestDigest=digest(folder / 'upstream-request.json'),
                          actualResponseDigest=digest(folder / 'upstream-response.json'),
                          upstreamElapsedSeconds=time.monotonic()-started,
                          withheldSeconds=35, replayDeadlineBudgetMillis=30000,
                          responseDeliveredToBackendBeforeDeadline=False))
                    time.sleep(35)
                self.send_response(response.status)
                self.send_header('Content-Type', response.getheader('Content-Type', 'application/octet-stream'))
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass  # The configured, bounded replay deadline closes this response connection.
            except Exception as error:
                failures.append(type(error).__name__)
                self.close_connection = True
            finally:
                if connection:
                    connection.close()

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}{target.path}', counts, failures
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def compress_completed(root, audit, final=False):
    groups = {}
    now = time.time()
    for path in root.rglob('*'):
        if path.is_symlink() or not path.is_file():
            continue
        stat = path.stat()
        if stat.st_size < 65536 or stat.st_flags & 32 or (not final and now-stat.st_mtime < 10):
            continue
        groups.setdefault((stat.st_dev, stat.st_ino), []).append(path)
    with tempfile.TemporaryDirectory(prefix='crabit-new-replay-compress-') as temporary:
        for paths in groups.values():
            source = paths[0]
            old = source.stat()
            # Live compression requires an indexed RAW link, proving Java sealed this output.
            if old.st_nlink != len(paths) or (not final and (old.st_nlink < 2 or not any('/raw/' in str(p) for p in paths))):
                continue
            before = digest(source)
            candidate = Path(temporary) / 'candidate'
            subprocess.run(['/usr/bin/nice', '-n', '20', '/usr/bin/ditto', '--hfsCompression', str(source), str(candidate)], check=True)
            new = candidate.stat()
            assert new.st_size == old.st_size and digest(candidate) == before
            if new.st_blocks >= old.st_blocks:
                candidate.unlink()
                continue
            for path in paths:
                current = path.stat()
                assert (current.st_dev, current.st_ino, current.st_size, current.st_mtime_ns) == (old.st_dev, old.st_ino, old.st_size, old.st_mtime_ns)
            for path in paths:
                replacement = path.parent / ('.compressed-' + uuid.uuid4().hex)
                os.link(candidate, replacement)
                os.replace(replacement, path)
                assert path.stat().st_ino == new.st_ino
            audit.write(json.dumps(dict(paths=[str(p.relative_to(root)) for p in paths], digest=before,
                             logicalBytes=old.st_size, physicalBytes=new.st_blocks*512,
                             savedBytes=(old.st_blocks-new.st_blocks)*512))+'\n')
            audit.flush()
            candidate.unlink()


def main():
    parser = argparse.ArgumentParser()
    for name in ('data', 'backend', 'package', 'output', 'classpath'):
        parser.add_argument('--'+name, required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    assert shutil.disk_usage(args.output).free >= 8*1024**3
    sys.path.insert(0, str(args.data))
    from synthetic_data.bundle import verify_package
    from synthetic_data.runtime import services
    from synthetic_data.replay import compare_replays
    from synthetic_data.provenance import stage_inventory
    package = verify_package(args.package)
    source_before = stage_inventory(args.data, args.backend, args.output / 'source-before.json', 'fixed-replay-start')
    schedule = fault_schedule(args.package / 'bundle')
    write(args.output / 'transport-schedule.json', schedule)
    write(args.output / 'runner.json', dict(scriptDigest=digest(__file__), manifestDigest=package['manifestDigest'],
          minimumFreeBytes=3*1024**3, heapBytes=6*1024**3, sequentialFreshDatabases=2,
          javaFlags=['-Xmx6g'],
          replayFeedDeadlineBudgetMillis=30000, servingLatencyPolicyValidated=False,
          inputMutation=False, newOutputCompression='APFS_TRANSPARENT_BYTE_VERIFIED'))
    with services(args.data, args.output) as environment:
        for name in ('first', 'second'):
            run = args.output / name
            run.mkdir()
            evidence = run / 'transport-injection'
            evidence.mkdir()
            with proxy(environment['CRABIT_SIMULATION_FEED_URL'], schedule, evidence) as (endpoint, counts, failures):
                env = dict(environment, CRABIT_SIMULATION_FEED_URL=endpoint, CRABIT_SIMULATION_FEED_BUDGET_MS='30000')
                command = ['/usr/bin/time', '-l', str(Path(env['JAVA_HOME']) / 'bin/java'),
                           '-Xmx6g', '-Xlog:gc:file='+str(run / 'gc.log'), '-cp',
                           args.classpath.read_text().strip(), 'com.crabit.backend.simulation.SimulationReplayRun',
                           str(args.package / 'bundle'), str(args.backend / 'api/demo-simulation-v1.schema.json'),
                           package['manifestDigest'], str(run / 'replay')]
                started = time.monotonic()
                with (run / 'process.log').open('x') as log, (run / 'compression.jsonl').open('x') as audit:
                    process = subprocess.Popen(command, cwd=args.backend, env=env, stdout=log, stderr=subprocess.STDOUT,
                                               start_new_session=True)
                    try:
                        while process.poll() is None:
                            if shutil.disk_usage(run).free < 3*1024**3:
                                raise RuntimeError('REPLAY_DISK_RESERVE')
                            compress_completed(run / 'replay', audit)
                            try:
                                process.wait(timeout=20)
                            except subprocess.TimeoutExpired:
                                pass
                        compress_completed(run / 'replay', audit, final=True)
                    finally:
                        if process.poll() is None:
                            os.killpg(process.pid, signal.SIGTERM)
                            process.wait(timeout=30)
                write(run / 'execution.json', dict(exitCode=process.returncode, elapsedSeconds=time.monotonic()-started,
                      recordedFaultCounts=counts, proxyFailures=failures, freeBytes=shutil.disk_usage(run).free))
                assert process.returncode == 0, 'Replay failed; see '+str(run / 'process.log')
                assert not failures and all(count == 1 for count in counts.values())
    source_after = stage_inventory(args.data, args.backend, args.output / 'source-after.json', 'fixed-replay-finish')
    assert source_before['dataSourceDigest'] == source_after['dataSourceDigest']
    assert source_before['backendOverlayDigest'] == source_after['backendOverlayDigest']
    result = compare_replays(args.output / 'first/replay', args.output / 'second/replay',
                             expected_manifest_digest=package['manifestDigest'])
    result['manifestDigest'] = package['manifestDigest']
    result['transportFaultSchedule'] = schedule
    write(args.output / 'comparison.json', result)
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
