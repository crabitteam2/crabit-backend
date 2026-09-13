"""Start the private loopback DB/app preview alongside actual Python services."""
from pathlib import Path
import argparse
import json
import os
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser()
    for name in ('data', 'backend', 'package', 'dump', 'classpath', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--console-digest', required=True)
    parser.add_argument('--code-sha', required=True)
    parser.add_argument('--already-applied-local', action='store_true')
    args = parser.parse_args()
    for name in ('data', 'backend', 'package', 'dump', 'classpath'):
        setattr(args, name, getattr(args, name).resolve(strict=True))
    os.umask(0o077)
    args.output.mkdir(mode=0o700)
    sys.path.insert(0, str(args.data))
    from synthetic_data.runtime import services
    cp = args.classpath.read_text().strip()
    classes = args.output / 'classes'
    classes.mkdir()
    java_home = Path(os.environ['JAVA_HOME'])
    subprocess.run([str(java_home / 'bin/javac'), '-cp', cp, '-d', str(classes),
                    str(args.backend / 'scripts/demo/PrepareDemoPreview.java')], check=True)
    with services(args.data, args.output) as env:
        command = [str(java_home / 'bin/java'), '-Xmx1g', '-cp', str(classes) + os.pathsep + cp,
                   'com.crabit.backend.simulation.PrepareDemoPreview', str(args.dump), str(args.package),
                   str(args.backend / 'api/demo-simulation-v1.schema.json'), str(args.output / 'target'),
                   args.console_digest, args.code_sha, str(args.backend)]
        if args.already_applied_local:
            command.append('APPLIED_LOCAL_PREVIEW')
        with (args.output / 'manager.log').open('x') as log:
            process = subprocess.Popen(command, cwd=args.backend, env=env, stdout=log, stderr=subprocess.STDOUT)
            try:
                result = process.wait()
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=30)
        (args.output / 'execution.json').write_text(json.dumps({'exitCode': result, 'externalDemoWritten': False}) + '\n')
        if result:
            raise RuntimeError('Local preview failed; inspect private manager.log and target/backend.log')


if __name__ == '__main__':
    main()
