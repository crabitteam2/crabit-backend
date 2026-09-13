"""Freeze the cash sub-export only; the full relational export remains unimplemented."""
import copy
import json
from pathlib import Path
p = Path('api/demo-simulation-v1.schema.json')
s = json.loads(p.read_text())
cash = json.loads(Path('src/simulation/resources/cash-oracle-v1.schema.json').read_text())
s['$defs']['cashState'] = dict(type='object', additionalProperties=False,
    description='Cash sub-export for independent event/ledger/balance comparison. Not the complete relational import state.',
    required=['schemaVersion','schemaKind','datasetId','ledger','balances'],
    properties=dict(schemaVersion=dict(const=1), schemaKind=dict(const='demo-simulation-cash-state'),
        datasetId=dict(type='string',pattern='^sha256:[0-9a-f]{64}$'),
        ledger=copy.deepcopy(cash['properties']['ledger']), balances=copy.deepcopy(cash['properties']['balances'])))
p.write_text(json.dumps(s, ensure_ascii=False, indent=2)+'\n')
