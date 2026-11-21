const fs = require('fs');
const path = require('path');
const root = __dirname;
const read = (p) => {
  const b = fs.readFileSync(p);
  return b.toString(b[0] === 255 ? 'utf16le' : 'utf8').replace(/^\uFEFF/, '').trim();
};
const round = (v) => Math.round(v * 10) / 10;
for (const vus of ['baseline-1','baseline-2','baseline-3','candidate-1','candidate-2','candidate-retry-3']) {
  const file = (n) => read(path.join(root,String(vus),n));
  const pts = file('samples.json').split(/\r?\n/).map(JSON.parse).filter(p => p.type==='Point' && p.metric==='http_req_duration' && p.data.tags.name==='allocate').map(p=>p.data);
  const latency = (ps) => {
    const a=ps.map(p=>p.value).sort((x,y)=>x-y);
    const q=f=>{const i=f*(a.length-1);return round(a[Math.floor(i)]+(i%1)*(a[Math.ceil(i)]-a[Math.floor(i)]));};
    return [a.length,round(a.reduce((x,y)=>x+y,0)/a.length),q(.50),q(.95),q(.99)];
  };
  const start=Math.min(...pts.map(p=>Date.parse(p.time)/1000-p.value/1000)),end=Math.max(...pts.map(p=>Date.parse(p.time)/1000));
  const lines=(s)=>s.split(/\r?\n/).filter(l=>/^\d/.test(l));
  const db=lines(file('db.txt')).map(l=>l.split('|').map(Number)).filter(r=>r[0]>=start && r[0]<=end);
  const resources=(f)=>{
    const a=lines(file(f)).map(l=>l.split(/\s+/)).map(([t,_,cpu,mem])=>[+t,+cpu,+mem]);
    const rows=a.slice(1).map((r,i)=>[r[0],a[i][0],(r[1]-a[i][1])/(r[0]-a[i][0])/1e6,r[2]/1048576]).filter(r=>r[0]>=start && r[1]<=end);
    return [round(Math.max(...rows.map(r=>r[2]))),round(Math.max(...rows.map(r=>r[3])))];
  };
  const acquire=f=>JSON.parse(file(f))[0].measurements.map(m=>m.value);
  const before=acquire('hikari-before.json'),after=acquire('hikari-after.json');
  const result={trial:vus,attemptsPerSec:Number(file("runner.log").match(/iterations\.+:\s+100\s+([\d.]+)\/s/)[1]),meanPoolWaiters:round(db.reduce((s,r)=>s+r[4],0)/db.length),all:latency(pts),grant:latency(pts.filter(p=>p.tags.status==='201')),reject:latency(pts.filter(p=>p.tags.status==='409')),dbSamples:db.length,peakConnections:Math.max(...db.map(r=>r[1])),peakActive:Math.max(...db.map(r=>r[2])),peakWaiters:Math.max(...db.map(r=>r[3])),peakPoolWaiters:Math.max(...db.map(r=>r[4])),borrowCount:after[0]-before[0],avgBorrowMs:round(1000*(after[1]-before[1])/(after[0]-before[0])),maxBorrowMs:round(after[2]*1000),app:resources('app.txt'),postgres:resources('postgres.txt'),verify:file('runner.log').match(/^\d+\|.*\|t$/m)?.[0]};
  console.log(JSON.stringify(result));
}


