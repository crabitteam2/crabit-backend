"""Maintain the closed replay identity/raw evidence/report admission definitions."""
import json
from pathlib import Path
p=Path('api/demo-simulation-v1.schema.json');s=json.loads(p.read_text())
ID={'type':'string','pattern':'^[A-Za-z0-9:_-]{1,160}$'}
TEXT={'type':'string','minLength':1,'maxLength':240}
HASH={'type':'string','pattern':'^sha256:[0-9a-f]{64}$'}
UUID={'type':'string','pattern':'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'}
PATH={'type':'string','pattern':r'^[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*(?:\.[A-Za-z0-9_-]+)?$','minLength':1,'maxLength':240}
TIME={'type':'string','format':'date-time','pattern':r'^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?Z$'}
INT={'type':'integer','minimum':0,'maximum':9007199254740991}
def obj(**properties):return dict(type='object',additionalProperties=False,required=list(properties),properties=properties)
def arr(item):return dict(type='array',items=item,minItems=0,maxItems=1000000)
def null(item):return dict(oneOf=[item,dict(type='null')])
def enum(*values):return dict(type='string',enum=list(values))
def version(kind,**fields):return obj(schemaVersion=dict(const=1),schemaKind=dict(const=kind),datasetId=HASH,**fields)
s['$defs']['idMap']=version('demo-simulation-id-map',entries=arr(obj(entityKind=enum('ACADEMY','STUDENT','ACCOUNT','WISH','LEDGER_ROOT','LEDGER_EFFECT','BALANCE_OBSERVATION','ADJUSTMENT_CASE','SHARED_CARD','FEED_CONTEXT','BEHAVIOR_EVENT','RECAP_GENERATION'),logicalId=ID,replayUuid=UUID,ownerAccountId=null(ID))))
s['$defs']['rawIndex']=version('demo-simulation-raw-index',records=arr(obj(path=PATH,byteLength=dict(type='integer',minimum=0,maximum=134217728),sha256=HASH,contentType=dict(type='string',pattern='^[a-z0-9.+-]+/[a-z0-9.+-]+(?:; charset=utf-8)?$',maxLength=100),service=enum('BACKEND','POSTGRESQL','FEED','RECAP'),modelVersion=null(TEXT),eventId=ID,kind=enum('REQUEST','RESPONSE','STORED_DOCUMENT','RUNTIME_OBSERVATION'))))
error=obj(code=ID,rule=ID,logicalId=null(ID),occurredAt=null(TIME),message=TEXT,artifactRefs=arr(PATH))
rule=obj(rule=ID,status=enum('PASS','FAIL','NOT_RUN'),checkedCount=INT,errors=arr(error),artifactRefs=arr(PATH))
s['$defs']['validation']=version('demo-simulation-validation',configDigest=HASH,schemaDigest=HASH,ruleVersion=ID,rules=arr(rule),counts=arr(obj(name=ID,value=INT)),independentAggregates=arr(obj(name=ID,value=INT)))
roles=s['properties']['files']['items']['properties']['role']['enum']
if 'RAW_INDEX' not in roles:roles.append('RAW_INDEX')
p.write_text(json.dumps(s,ensure_ascii=False,indent=2)+'\n')
