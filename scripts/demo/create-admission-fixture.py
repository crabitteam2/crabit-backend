"""Build ONLY a deterministic admission-test fixture, never a replay/applicable dataset.
Run from repository root. Output is confined to the named test fixture directory.
"""
import hashlib
import json
import uuid
from pathlib import Path

def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()

def digest(raw):
    return 'sha256:' + hashlib.sha256(raw).hexdigest()

def generate():
    root = Path('src/test/resources/simulation/bundle-contract-valid')
    root.mkdir(parents=True, exist_ok=True)
    versions = dict(java='21', postgresql='16', python='3.12', node='22', feedModel='TEST_ONLY', recapModel='TEST_ONLY')
    config = dict(schemaVersion=1, seed=20260910, timezone='Asia/Seoul', startInclusive='2026-05-31T15:00:00Z', endExclusive='2026-09-10T15:00:00Z', academyCount=1, population=100, grades=[3,4,5,6], studentsPerGrade=25, initialStudentsPerGrade=20, laterStudentsPerGrade=5, assumptions=['ADMISSION TEST ONLY: no history was replayed'], policyVersion='test-only-v1', namespace='admission-test', monthlyBudgetRules=dict(minimumKrw=10000, maximumKrw=30000, partialMonthPolicy='ACTUAL_SCHEDULED_GRANTS_ONLY'), runtimeVersions=versions)
    students = [dict(logicalStudentId=f'student-{g}-{i:02}', logicalAccountId=f'account-{g}-{i:02}', logicalAcademyId='academy-1', grade=g, syntheticDisplayName=f'Test {g}-{i:02}', joinedAt='2026-05-31T15:00:00Z' if i<20 else '2026-06-30T15:00:00Z', archetypeInputs=[dict(name='assumption',value='TEST_ONLY')], isOwner=g==3 and i==0) for g in range(3,7) for i in range(25)]
    personas = [dict(persona=f'grade-{g}', grade=g, displayName=f'Test {g}', logicalStudentId=f'student-{g}-01', logicalAccountId=f'account-{g}-01') for g in range(3,7)]
    schema = Path('api/demo-simulation-v1.schema.json').read_bytes()
    codes={k:'0'*40 for k in ['crabit-backend','crabit-data','crabit-frontend']}
    config_digest=digest(canonical(config)); schema_digest=digest(schema)
    dataset=digest(canonical(dict(schemaVersion=1,configDigest=config_digest,codeShas=codes,normalizationVersion=1)))
    def mapping(kind,logical,owner=None):
        return dict(entityKind=kind,logicalId=logical,replayUuid=str(uuid.uuid5(uuid.NAMESPACE_URL,'admission-test:'+kind+':'+logical)),ownerAccountId=owner)
    entries=[mapping('ACADEMY','academy-1')]
    for student in students:
        entries += [mapping('STUDENT',student['logicalStudentId']),mapping('ACCOUNT',student['logicalAccountId'],student['logicalAccountId'])]
    entries.sort(key=lambda e:e['entityKind']+':'+e['logicalId'])
    id_map=dict(schemaVersion=1,schemaKind='demo-simulation-id-map',datasetId=dataset,entries=entries)
    raw_index=dict(schemaVersion=1,schemaKind='demo-simulation-raw-index',datasetId=dataset,records=[])
    validation=dict(schemaVersion=1,schemaKind='demo-simulation-validation',datasetId=dataset,configDigest=config_digest,schemaDigest=schema_digest,ruleVersion='admission-test-v1',rules=[dict(rule='domain-replay',status='NOT_RUN',checkedCount=0,errors=[],artifactRefs=[])],counts=[],independentAggregates=[])
    cash_state=dict(schemaVersion=1,schemaKind='demo-simulation-cash-state',datasetId=dataset,ledger=[],balances=[dict(accountId=s['logicalAccountId'],amountKrw=0,sequence=0) for s in students])
    payloads = {'config.json':('CONFIG',config),'students.json':('STUDENTS',students),'personas.json':('PERSONAS',personas),'events.ndjson':('EVENTS',b''),'id-map.json':('ID_MAP',id_map), 'raw/index.json':('RAW_INDEX',raw_index),'state/export.json':('STATE',cash_state),'validation.json':('VALIDATION',validation),'normalized.json':('NORMALIZED',[]),'demo-simulation-v1.schema.json':('SCHEMA',schema)}
    files=[]
    for path,(role,value) in sorted(payloads.items()):
        raw=value if isinstance(value,bytes) else canonical(value)+b'\n'
        target=root/path; target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
        count=0 if role=='EVENTS' else len(value) if isinstance(value,list) else 1
        files.append(dict(path=path,role=role,byteLength=len(raw),sha256=digest(raw),recordCount=count))
    manifest=dict(schemaVersion=1,schemaKind='demo-simulation-manifest',configDigest=digest(canonical(config)),schemaDigest=digest(schema),normalizationVersion=1,logicalDigest=digest(canonical([])),codeShas={k:'0'*40 for k in ['crabit-backend','crabit-data','crabit-frontend']},runtimeVersions=versions,files=files)
    identity={k:manifest[k] for k in ['schemaVersion','configDigest','codeShas','normalizationVersion']}
    manifest['datasetId']=digest(canonical(identity))
    (root/'manifest.json').write_bytes(canonical(manifest)+b'\n')
    print(json.dumps(dict(datasetId=manifest['datasetId'],manifestDigest=digest((root/'manifest.json').read_bytes()),domainValidationPerformed=False,readyForApplication=False)))

if __name__=='__main__':
    generate()
