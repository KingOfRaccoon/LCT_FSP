#!/usr/bin/env python3
"""Local PostgreSQL recovery drill: create a fresh target, never overwrite a database.

The dump and report are private local artifacts. This is a logical restore exercise,
not PITR, a production backup schedule, or proof of a chosen production RPO/RTO.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid


def validate_target(name):
    if not re.fullmatch(r'fsp_restore_drill_[a-zA-Z0-9_]{1,40}', name):
        raise ValueError('Target must be a fresh fsp_restore_drill_* database')


def quote_identifier(name):
    return '"' + name.replace('"', '""') + '"'


def checked_command(command):
    result = subprocess.run(command, capture_output=True, check=False)
    if result.returncode:
        # PostgreSQL errors can contain connection or row data. Keep the report clean.
        raise RuntimeError(f'{Path(command[0]).name} failed; inspect local PostgreSQL logs')
    return result.stdout


def fingerprint(binary, connection, database, snapshot=None):
    if snapshot is not None and not re.fullmatch(r'[0-9A-Fa-f-]+', snapshot):
        raise ValueError('Invalid exported snapshot')
    def query(sql):
        if snapshot:
            sql = f"BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET TRANSACTION SNAPSHOT '{snapshot}'; " + sql
        return checked_command([binary, *connection, '-d', database, '-X', '-qAt',
                                '-v', 'ON_ERROR_STOP=1', '-c', sql])
    tables = query("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename").decode().splitlines()
    result = {}
    for table in tables:
        identifier = 'public.' + quote_identifier(table)
        count = int(query(f'SELECT count(*) FROM {identifier}'))
        rows = query("SET timezone='UTC'; "
                     f'COPY (SELECT row_to_json(t)::text FROM {identifier} t ORDER BY 1) TO STDOUT')
        result[table] = {'rows': count, 'sha256': hashlib.sha256(rows).hexdigest()}
    return result


def drill(args):
    validate_target(args.target)
    if args.host not in ('127.0.0.1', 'localhost', '::1'):
        raise ValueError('This drill only permits a local PostgreSQL server')
    if args.source != 'fsp_dev':
        raise ValueError('Only the local prototype fsp_dev database is an allowed source')
    root = Path(__file__).resolve().parents[1]
    output = Path(args.out_dir).resolve()
    if not output.is_relative_to(root / 'local'):
        raise ValueError('Private dump/report output must be under this checkout/local/')
    output.mkdir(parents=True, exist_ok=False)
    binary = lambda name: str(Path(args.pg_bin) / name) if args.pg_bin else name
    connection = ['-w', '-h', args.host, '-p', str(args.port), '-U', args.user]
    backup = output / 'database.dump'
    started = time.monotonic()
    # Keep a read-only exported snapshot alive while both fingerprints and pg_dump
    # read it. Concurrent application writes cannot invalidate the comparison.
    exporter = subprocess.Popen([binary('psql'), *connection, '-d', args.source,
                                 '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True)
    try:
        exporter.stdin.write('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSELECT pg_export_snapshot();\n')
        exporter.stdin.flush()
        snapshot = exporter.stdout.readline().strip()
        if not re.fullmatch(r'[0-9A-Fa-f-]+', snapshot):
            raise RuntimeError('Unable to export read-only source snapshot')
        original = fingerprint(binary('psql'), connection, args.source, snapshot)
        checked_command([binary('pg_dump'), *connection, '-d', args.source, '--format=custom',
                         '--snapshot', snapshot, '--no-owner', '--no-acl', '--file', str(backup)])
    finally:
        exporter.stdin.close()
        exporter.wait(timeout=30)
    checked_command([binary('createdb'), *connection, '--template=template0', args.target])
    try:
        checked_command([binary('pg_restore'), *connection, '-d', args.target,
                         '--no-owner', '--no-acl', '--exit-on-error', str(backup)])
        restored = fingerprint(binary('psql'), connection, args.target)
        if original != restored:
            raise RuntimeError('Source snapshot/restored fingerprints differ')
        result = {'checked_at': datetime.now(timezone.utc).isoformat(),
                  'scope': 'local_logical_backup_restore_not_pitr',
                  'source_database': args.source, 'restored_database': args.target,
                  'backup_bytes': backup.stat().st_size,
                  'backup_sha256': hashlib.sha256(backup.read_bytes()).hexdigest(),
                  'elapsed_seconds': round(time.monotonic() - started, 3),
                  'tables': restored, 'snapshot_fingerprints_match': True,
                  'source_checksum_comparison': 'same exported read-only snapshot as pg_dump',
                  'database_left_for_inspection': True,
                  'production_rpo_rto': 'not_measured'}
        (output / 'report.json').write_text(json.dumps(result, indent=2) + '\n')
        return result
    except Exception:
        # Do not clean/drop a database automatically, even after a failed restore.
        raise RuntimeError('Restore check failed; isolated target retained for inspection') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', default='fsp_dev')
    parser.add_argument('--target', default='fsp_restore_drill_' + uuid.uuid4().hex[:16])
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=55432)
    parser.add_argument('--user', default='fsp')
    parser.add_argument('--pg-bin', default='')
    parser.add_argument('--out-dir', default='local/recovery/' + uuid.uuid4().hex)
    args = parser.parse_args()
    try:
        result = drill(args)
    except (ValueError, RuntimeError) as error:
        parser.exit(1, f'{error}\n')
    print(json.dumps({k: v for k, v in result.items() if k != 'tables'}, indent=2))


if __name__ == '__main__':
    main()
