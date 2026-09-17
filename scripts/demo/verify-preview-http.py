"""Exercise the four real representative APIs in an owned local preview only."""
from pathlib import Path
import argparse
import http.client
import json
import os
import subprocess
from urllib.parse import urlsplit
from uuid import UUID


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('preview', type=Path)
    parser.add_argument('--output-name', default='api-verification')
    parser.add_argument('--verify-existing', action='store_true')
    args = parser.parse_args()
    root = args.preview.resolve(strict=True)
    config = json.loads((root / 'private-connection.json').read_text())
    assert json.loads((root / 'ready.json').read_text())['status'] == 'PASS'
    endpoint = urlsplit(config['origin'])
    assert endpoint.scheme == 'http' and endpoint.hostname == '127.0.0.1' and endpoint.port
    os.umask(0o077)
    assert args.output_name.replace('-', '').isalnum()
    output = root / args.output_name
    if args.verify_existing:
        assert output.is_dir() and not (output / 'verification.json').exists()
    else:
        output.mkdir(mode=0o700)
    owner = '00000000-0000-0000-0000-000000000301'
    accounts = {}
    for grade in range(3, 7):
        rows = json.loads((root / f'grade-{grade}-accounts.json').read_text())['items']
        assert len(rows) == 1 and rows[0]['cardBalanceAccountId'] != owner
        accounts[grade] = rows[0]
    assert len({row['cardBalanceAccountId'] for row in accounts.values()}) == 4
    database = config['databaseUrl'].split('/')[-1].split('?')[0]
    assert database.startswith('preview_') and database.replace('_', '').isalnum()
    container = config['containerId']
    assert len(container) == 64 and all(c in '0123456789abcdef' for c in container)
    ids = ','.join("'" + str(UUID(row['cardBalanceAccountId'])) + "'::uuid" for row in accounts.values())
    sql = f"""SELECT json_agg(row_to_json(x)) FROM (
      SELECT s.account_id,s.card_funds,o.actual_card_balance,o.source_kind,o.status,o.lookup_method
      FROM demo_simulation_account s JOIN LATERAL (
        SELECT * FROM balance_observation b WHERE b.account_id=s.account_id
        ORDER BY b.account_lookup_version DESC LIMIT 1
      ) o ON true WHERE s.account_id IN ({ids}) ORDER BY s.account_id
    ) x"""
    def database_readback():
        raw = subprocess.check_output(['docker', 'exec', container, 'psql', '-U', 'simulation', '-d', database, '-Atc', sql], text=True)
        return json.loads(raw)
    before = json.loads((output / 'database-before.json').read_text()) if args.verify_existing else database_readback()
    if not args.verify_existing:
        (output / 'database-before.json').write_text(json.dumps(before, indent=2) + '\n')
    expected_funds = {row['account_id']: row['card_funds'] for row in before}

    def request(grade, label, path, expected=200, method='GET', body=None):
        if args.verify_existing:
            evidence = json.loads((output / f'grade-{grade}-{label}.json').read_text())
            assert evidence['method'] == method and evidence['path'] == path and evidence['status'] == expected
            return evidence['body']
        connection = http.client.HTTPConnection(endpoint.hostname, endpoint.port, timeout=20)
        headers = {'Authorization': 'Bearer ' + config['tokens'][f'GRADE_{grade}']}
        encoded = None if body is None else json.dumps(body).encode()
        if encoded is not None:
            headers['Content-Type'] = 'application/json'
        connection.request(method, path, body=encoded, headers=headers)
        response = connection.getresponse()
        raw = response.read(2*1024*1024)
        value = json.loads(raw)
        evidence = {'method': method, 'path': path, 'status': response.status,
                    'cacheControl': response.getheader('Cache-Control'), 'body': value}
        (output / f'grade-{grade}-{label}.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
        connection.close()
        assert response.status == expected, (grade, label, response.status)
        return value

    summaries = {}
    for grade, account in accounts.items():
        account_id = str(UUID(account['cardBalanceAccountId']))
        academy = str(UUID(account['academyId']))
        base = '/v1/card-balance-accounts/' + account_id
        refresh = request(grade, 'refresh', base + '/balance-refreshes', method='POST')
        refreshed = request(grade, 'account', base)
        # A stored observation may predate the last cash event. Refresh must read
        # the current cash ledger, not preserve a stale displayed observation.
        assert refreshed['actualCardBalance'] == expected_funds[account_id], (grade, 'current cash funds')
        assert refreshed['lastRefreshStatus'] == 'SUCCESS'
        wishes = request(grade, 'wishes', base + '/wishes')
        weekly = request(grade, 'weekly', base + '/recaps/weekly?weekStart=2026-08-31')
        monthly = request(grade, 'monthly', base + '/recaps/monthly?month=2026-08')
        feed = request(grade, 'feed', '/v1/academies/' + academy + '/feed-results',
                       expected=201, method='POST', body={'limit': 5})
        other = accounts[3 if grade != 3 else 4]['cardBalanceAccountId']
        request(grade, 'other-account-denied', '/v1/card-balance-accounts/' + other, expected=404)
        request(grade, 'owner-account-denied', '/v1/card-balance-accounts/' + owner, expected=404)
        assert wishes['items'] and weekly['status'] == 'SUCCEEDED'
        assert monthly['status'] in ('SUCCEEDED', 'NOT_ELIGIBLE')
        assert feed['items'] and feed['sortSource'] == 'RECOMMENDATION'
        summaries[str(grade)] = {'accountId': account_id, 'actualCardBalance': refreshed['actualCardBalance'],
            'wishCount': len(wishes['items']), 'weeklyStatus': weekly['status'], 'monthlyStatus': monthly['status'],
            'feedItemCount': len(feed['items']), 'sortSource': feed['sortSource'], 'otherAndOwnerAccountsDenied': True}

    rows = database_readback()
    assert len(rows) == 4
    for row in rows:
        assert row['source_kind'] == 'SIMULATION' and row['status'] == 'SUCCEEDED'
        assert row['actual_card_balance'] == row['card_funds']
    (output / 'database-readback.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n')
    result = {'status': 'PASS', 'externalDemoWritten': False, 'realLocalHttp': True,
              'ordinaryRuntimeRole': True, 'representatives': summaries, 'simulationProviderDatabaseReadBack': True,
              'existingHttpEvidenceRechecked': args.verify_existing}
    (output / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
