// Independent draft-2020-12 validation of the shared input schema and fixture.
// Requires existing ajv/ajv-formats; no package installation or network request.
const fs=require('node:fs');
const Ajv=require('ajv/dist/2020');
const formats=require('ajv-formats');
const schema=JSON.parse(fs.readFileSync('api/demo-simulation-v1.schema.json'));
const ajv=new Ajv({strict:true,allErrors:true,coerceTypes:false});formats(ajv);
const validators={'manifest.json':ajv.compile(schema)};
for(const name of ['config','students','personas'])validators[name+'.json']=ajv.compile(schema.$defs[name]);
for(const [name,definition] of Object.entries({'id-map.json':'idMap','raw/index.json':'rawIndex','validation.json':'validation','state/export.json':'cashState'}))validators[name]=ajv.compile(schema.$defs[definition]);
const fixture='src/test/resources/simulation/bundle-contract-valid/';
const documents={};
for(const [name,validate] of Object.entries(validators)) {
  documents[name]=JSON.parse(fs.readFileSync(fixture+name));
  if(!validate(documents[name]))throw Error(name+': '+JSON.stringify(validate.errors));
}
const names=new Set(['unknown-root','wrong-version','fractional-seed','unsafe-seed','wrong-timezone','invalid-date','offset-date','unknown-student-field','path-traversal','null-student-id']);
let rejected=0;
for(const v of JSON.parse(fs.readFileSync('src/test/resources/simulation/rejection-vectors.json'))) {
  if(!names.has(v.name))continue;
  const doc=structuredClone(documents[v.file]);const path=v.path.split('/').slice(1);
  let parent=doc;for(const key of path.slice(0,-1))parent=parent[key];parent[path.at(-1)]=v.value;
  if(validators[v.file](doc))throw Error('Unexpected schema acceptance: '+v.name);
  rejected++;
}
if(rejected!==names.size)throw Error('Missing negative vector');
console.log(JSON.stringify({engine:'Ajv2020',positiveDocuments:Object.keys(validators).length,negativeVectors:rejected,domainValidationPerformed:false,readyForApplication:false}));

const eventValidator=ajv.compile(schema.$defs.event);
const eventVectors=JSON.parse(fs.readFileSync('src/test/resources/simulation/event-schema-vectors.json'));
for(const e of eventVectors.positive)if(!eventValidator(e))throw Error(JSON.stringify(eventValidator.errors));
for(const v of eventVectors.negative) {
  const e=structuredClone(eventVectors.positive[v.base]), path=v.path.split('/').slice(1);
  let parent=e;for(const key of path.slice(0,-1))parent=parent[key];parent[path.at(-1)]=v.value;
  if(eventValidator(e))throw Error('Unexpected event acceptance: '+v.name);
}
console.log(JSON.stringify({engine:'Ajv2020',positiveEvents:eventVectors.positive.length,negativeEvents:eventVectors.negative.length,fullDatasetValidationPerformed:false}));

documents['raw-positive.json']=JSON.parse(fs.readFileSync('src/test/resources/simulation/evidence-raw-positive.json'));
validators['raw-positive.json']=ajv.compile(schema.$defs.rawIndex);
if(!validators['raw-positive.json'](documents['raw-positive.json']))throw Error('Invalid positive raw index');
const evidenceVectors=JSON.parse(fs.readFileSync('src/test/resources/simulation/evidence-schema-vectors.json'));
for(const v of evidenceVectors) {
  const e=structuredClone(documents[v.file]), path=v.path.split('/').slice(1);
  let parent=e;for(const key of path.slice(0,-1))parent=parent[key];parent[path.at(-1)]=v.value;
  if(validators[v.file](e))throw Error('Unexpected evidence acceptance: '+v.name);
}
console.log(JSON.stringify({engine:'Ajv2020',negativeEvidenceVectors:evidenceVectors.length,executionVerified:false}));

const cashVectors=JSON.parse(fs.readFileSync('src/test/resources/simulation/cash-state-schema-vectors.json'));
for(const v of cashVectors) {
  const e=structuredClone(documents['state/export.json']), path=v.path.split('/').slice(1);
  let parent=e;for(const key of path.slice(0,-1))parent=parent[key];parent[path.at(-1)]=v.value;
  if(validators['state/export.json'](e))throw Error('Unexpected cash state acceptance: '+v.name);
}
console.log(JSON.stringify({engine:'Ajv2020',negativeCashStateVectors:cashVectors.length,fullRelationalStateValidationPerformed:false}));
