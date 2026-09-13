"""Maintain the currently implemented event admission vocabulary; not a replay runner."""
import json
from pathlib import Path
P=Path('api/demo-simulation-v1.schema.json')
s=json.loads(P.read_text())
ID=dict(type='string',pattern='^[A-Za-z0-9:_-]{1,160}$')
STR=dict(type='string',minLength=1,maxLength=200)
TIME=dict(type='string',format='date-time',pattern=r'^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?Z$')
DATE=dict(type='string',format='date',pattern=r'^[0-9]{4}-[0-9]{2}-[0-9]{2}$')
VERSION=dict(type='integer',minimum=0,maximum=9007199254740991)
MONEY=dict(type='integer',minimum=1,maximum=9007199254740991)
PATH=dict(type='string',minLength=1,maxLength=240,pattern=r'^[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*(?:\.[A-Za-z0-9_-]+)?$')
def obj(p,required=None):return dict(type='object',additionalProperties=False,required=list(p) if required is None else required,properties=p)
def arr(item,min=0,max=10000):return dict(type='array',items=item,minItems=min,maxItems=max)
def nullable(v):return dict(oneOf=[v,dict(type='null')])
def enum(*vs):return dict(type='string',enum=list(vs))
cmd={}
cmd['JOIN']=obj(dict(studentId=ID,accountId=ID,academyId=ID,grade=dict(type='integer',minimum=3,maximum=6)))
cmd['RETURN_FROM_DORMANCY']=obj(dict(priorDormancyEventId=ID))
cmd['GRANT']=obj(dict(accountId=ID,amountKrw=MONEY,cashEntryId=ID,budgetMonth=dict(type='string',pattern=r'^[0-9]{4}-(0[1-9]|1[0-2])$'),scheduledAt=TIME))
cmd['PURCHASE']=obj(dict(accountId=ID,amountKrw=MONEY,cashEntryId=ID))
cmd['BALANCE_LOOKUP']=obj(dict(accountId=ID,observationRef=PATH))
cmd['CREATE']=obj(dict(accountId=ID,wishId=ID,idempotencyKey=STR,purpose=STR,targetAmount=MONEY,startDate=nullable(DATE),targetDate=nullable(DATE),photoId=nullable(ID)))
for k in ['DEPOSIT','WITHDRAW']:
    cmd[k]=obj(dict(accountId=ID,wishId=ID,amount=MONEY,expectedVersion=VERSION,idempotencyKey=STR))
cmd['TRANSFER']=obj(dict(accountId=ID,sourceWishId=ID,destinationWishId=ID,amount=MONEY,sourceExpectedVersion=VERSION,destinationExpectedVersion=VERSION,idempotencyKey=STR,rootEventId=ID,sourceEffectId=ID,destinationEffectId=ID))
for k in ['COMPLETE','ABANDON','DELETE']:
    cmd[k]=obj(dict(accountId=ID,wishId=ID,expectedVersion=VERSION,idempotencyKey=STR))
cmd['COMPLETE']['properties']['confirmed']=dict(const=True)
cmd['COMPLETE']['required'].append('confirmed')
for k in ['FOLLOW','UNFOLLOW','BLOCK','UNBLOCK']:
    cmd[k]=obj(dict(academyId=ID,viewerStudentId=ID,ownerStudentId=ID))
for k in ['SHARE','VISIBILITY_CHANGE']:
    cmd[k]=obj(dict(accountId=ID,wishId=ID,expectedVersion=VERSION,visibility=enum('PRIVATE','FOLLOWERS','ACADEMY')))
cmd['FEED_QUERY']=obj(dict(academyId=ID,limit=dict(type='integer',minimum=1,maximum=100),cursor=nullable(dict(type='string',minLength=1,maxLength=8192)),resultContextId=ID,orderedCardIds=arr(ID,0,100),requestRef=PATH,responseRef=PATH))
for k in ['IMPRESSION','CLICK']:
    cmd[k]=obj(dict(academyId=ID,resultContextId=ID,cardId=ID,position=dict(type='integer',minimum=0,maximum=99),impressionId=ID))
cmd['CLICK']['properties']['clickKind']=dict(const='AUTHOR_PROFILE')
cmd['CLICK']['required'].append('clickKind')
cmd['PROFILE_VISIT']=obj(dict(academyId=ID,targetStudentId=ID,source=enum('DIRECT','FEED','PROFILE','OTHER'),sourceEventId=nullable(ID)))
cmd['INFLUENCED_DECISION']=obj(dict(signalType=enum('IMPRESSION','CLICK','PROFILE_VISIT'),signalEventId=ID,decisionEventId=ID))
for k in ['CLOSE_WEEK','CLOSE_MONTH']:
    cmd[k]=obj(dict(accountId=ID,startInclusive=DATE,endExclusive=DATE,snapshotRef=PATH,requestRef=PATH,responseRef=PATH,storedStateRef=PATH,generationId=ID))
outcome=dict(oneOf=[obj(dict(status=dict(const=k),resultRef=PATH)) for k in ['APPLIED','REJECTED','FAILED']])
s['$defs']['event']=dict(description='Closed implemented event vocabulary. ADJUST_ALLOCATION and CORRECT remain unsupported until their domain command binding is implemented. Admission is not replay or state validation.',oneOf=[obj(dict(eventId=ID,sequence=dict(type='integer',minimum=1,maximum=9007199254740991),occurredAt=TIME,actorStudentId=ID,kind=dict(const=k),causes=arr(ID,0,1000),command=v,outcome=outcome,artifactRefs=arr(PATH,1,1000))) for k,v in cmd.items()])
P.write_text(json.dumps(s,ensure_ascii=False,indent=2)+'\n')
print('event variants:',len(cmd))
