import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';

const env={WORKAI_DEVICE_TOKEN:'test-token-long-enough-123456',NVIDIA_API_KEY:'nvidia-test',OPENCODE_API_KEY:'zen-test',NVIDIA_MODEL:'nvidia/nemotron-3-super-120b-a12b'};
function request(body,path='/v1/chat') {return new Request(`https://example.test${path}`,{method:'POST',headers:{'x-workai-token':env.WORKAI_DEVICE_TOKEN,'content-type':'application/json'},body:JSON.stringify(body)});}

test('unknown model never silently switches provider',async()=>{
  const response=await worker.fetch(request({model:'obsolete-model',messages:[{role:'user',content:'test'}]}),env);
  assert.equal(response.status,500);
  assert.match((await response.json()).detail,/Unsupported model: obsolete-model/);
});

test('Zen without key reports configuration error instead of switching to NVIDIA',async()=>{
  const response=await worker.fetch(request({model:'nemotron-3-ultra-free',messages:[{role:'user',content:'test'}]}),{...env,OPENCODE_API_KEY:undefined});
  assert.match((await response.json()).detail,/OPENCODE_API_KEY is not configured/);
});

test('image sent to text model fails explicitly',async()=>{
  const response=await worker.fetch(request({model:'nemotron-3-ultra-free',messages:[{role:'user',content:[{type:'text',text:'What is this?'},{type:'image_url',image_url:{url:'data:image/png;base64,aGVsbG8='}}]}]}),env);
  assert.match((await response.json()).detail,/does not support image input/);
});

test('vision input is forwarded to selected model',async()=>{
  const previous=globalThis.fetch;let captured;
  globalThis.fetch=async (url,options)=>{captured={url,headers:options.headers,body:JSON.parse(options.body)};return new Response(JSON.stringify({choices:[{message:{content:'Image result'}}]}),{status:200});};
  try {
    const content=[{type:'text',text:'Describe'},{type:'image_url',image_url:{url:'data:image/png;base64,aGVsbG8='}}];
    const response=await worker.fetch(request({model:'deepseek-v4-flash-vision-exp',messages:[{role:'user',content}]}),env);
    assert.equal((await response.json()).answer,'Image result');
    assert.equal(captured.body.model,'deepseek-v4-flash-vision-exp');
    assert.deepEqual(captured.body.messages[1].content,content);
    assert.equal(captured.headers.authorization,'Bearer zen-test');
  }finally{globalThis.fetch=previous;}
});

test('uploaded image reference is integrity-checked and hydrated before model call',async()=>{
  const previous=globalThis.fetch;const bytes=new TextEncoder().encode('real-image-bytes');
  const {createHash}=await import('node:crypto');
  const sha256=createHash('sha256').update(bytes).digest('hex');
  let sent;
  globalThis.fetch=async (url,options)=>{
    if(String(url).includes('/git/blobs/'))return new Response(JSON.stringify({content:Buffer.from(bytes).toString('base64'),encoding:'base64'}),{status:200});
    sent=JSON.parse(options.body);
    return new Response(JSON.stringify({choices:[{message:{content:'Seen'}}]}),{status:200});
  };
  try{
    const attachment={name:'photo.png',mime:'image/png',size:bytes.length,sha256,chunks:[{sha:'a'.repeat(40),sha256,size:bytes.length,index:0}]};
    const response=await worker.fetch(request({model:'deepseek-v4-flash-vision-exp',messages:[{role:'user',content:'Describe',attachments:[attachment]}]}),{...env,WORKAI_GITHUB_TOKEN:'github-test',GITHUB_OWNER:'owner',GITHUB_REPO:'repo'});
    assert.equal((await response.json()).answer,'Seen');
    assert.equal(sent.messages[1].content[1].image_url.url,`data:image/png;base64,${Buffer.from(bytes).toString('base64')}`);
  }finally{globalThis.fetch=previous;}
});

test('job event log stores only real events and resumes from sequence',async()=>{
  const {JobEvents}=await import('../src/index.js');
  const memory=new Map();const durable=new JobEvents({storage:{get:async key=>memory.get(key),put:async(key,value)=>memory.set(key,value)}});
  const config={...env,JOB_EVENTS:{idFromName:id=>id,get:()=>durable}};
  const id='e73d953d-2c78-4b33-a923-b85ac3d408ab';
  const first=await worker.fetch(request({type:'tool.started',label:'Opened archive'},`/v1/jobs/${id}/events`),config);
  assert.equal(first.status,201);
  const second=await worker.fetch(request({type:'file.write',label:'Edited description.xml'},`/v1/jobs/${id}/events`),config);
  assert.equal(second.status,201);
  const get=new Request(`https://example.test/v1/jobs/${id}/events?after=1`,{headers:{'x-workai-token':config.WORKAI_DEVICE_TOKEN}});
  const response=await worker.fetch(get,config);
  assert.deepEqual((await response.json()).events.map(x=>x.type),['file.write']);
});

test('failed page read returns to the model for recovery',async()=>{
  const previous=globalThis.fetch;let providerCalls=0;
  globalThis.fetch=async (url)=>{
    if(String(url)==='https://example.org/')return new Response('Unavailable',{status:503});
    providerCalls++;
    const packet=providerCalls===1
      ? {choices:[{delta:{tool_calls:[{index:0,id:'call-1',function:{name:'read_page',arguments:JSON.stringify({url:'https://example.org/'})}}]},finish_reason:'tool_calls'}]}
      : {choices:[{delta:{content:'The page failed; try another source.'},finish_reason:'stop'}]};
    return new Response(`data: ${JSON.stringify(packet)}\n\ndata: [DONE]\n\n`,{status:200,headers:{'content-type':'text/event-stream'}});
  };
  try{
    const response=await worker.fetch(request({model:'nemotron-3-ultra-free',messages:[{role:'user',content:'Read the page'}]},'/v1/chat/stream'),env);
    const stream=await response.text();
    assert.equal(providerCalls,2);
    assert.match(stream,/recoverable_error/);
    assert.match(stream,/The page failed; try another source/);
    assert.match(stream,/task_completed/);
  }finally{globalThis.fetch=previous;}
});

test('cancelling Work Mode requests GitHub workflow cancellation',async()=>{
  const previous=globalThis.fetch;
  const id='78c0a7bf-5324-4cd0-9c22-443685a45fd7';
  const memory=new Map();const {JobEvents}=await import('../src/index.js');
  const durable=new JobEvents({storage:{get:async key=>memory.get(key),put:async(key,value)=>memory.set(key,value)}});
  let cancelled=false;
  globalThis.fetch=async (url,init)=>{
    if(String(url).includes('/actions/workflows/agent-build.yml/runs'))return new Response(JSON.stringify({workflow_runs:[{id:42,display_title:`WorkAI ${id}`,status:'in_progress'}]}),{status:200});
    if(String(url).endsWith('/actions/runs/42/cancel')&&init.method==='POST'){cancelled=true;return new Response(null,{status:202});}
    throw Error(`Unexpected endpoint ${url}`);
  };
  try{
    const config={...env,WORKAI_GITHUB_TOKEN:'github-test',GITHUB_OWNER:'owner',GITHUB_REPO:'repo',JOB_EVENTS:{idFromName:x=>x,get:()=>durable}};
    const response=await worker.fetch(request({},`/v1/jobs/${id}/cancel`),config);
    assert.equal(response.status,202);
    assert.equal(cancelled,true);
  }finally{globalThis.fetch=previous;}
});

test('Work Mode plan originates from selected model and is written to event log',async()=>{
  const previous=globalThis.fetch;const memoryByJob=new Map();let providerModel;
  globalThis.fetch=async (url,init)=>{
    if(String(url).includes('opencode.ai/zen')){
      const body=JSON.parse(init.body);providerModel=body.model;
      const task=body.messages[1].content.includes('theme')?'Inspect theme':'Inspect ZIP';
      return new Response(JSON.stringify({choices:[{message:{content:JSON.stringify({tasks:[{title:task,phase:'inspect'},{title:'Verify archive',phase:'verify'}],skills:['archive-editor']})}}]}),{status:200});
    }
    if(String(url).endsWith('/dispatches'))return new Response(null,{status:204});
    throw Error(`Unexpected URL ${url}`);
  };
  try{
    const {JobEvents}=await import('../src/index.js');
    const config={...env,WORKAI_GITHUB_TOKEN:'github-test',GITHUB_OWNER:'owner',GITHUB_REPO:'repo',JOB_EVENTS:{idFromName:id=>id,get:id=>{
      if(!memoryByJob.has(id)){
        const memory=new Map();memoryByJob.set(id,new JobEvents({storage:{get:async key=>memory.get(key),put:async(key,value)=>memory.set(key,value)}}));
      }
      return memoryByJob.get(id);
    }}};
    const response=await worker.fetch(request({model:'nemotron-3-ultra-free',prompt:'edit theme',kind:'mtz'},'/v1/jobs'),config);
    const result=await response.json();
    assert.equal(response.status,202);
    assert.equal(providerModel,'nemotron-3-ultra-free');
    assert.equal(result.tasks[0].title,'Inspect theme');
    const eventResponse=await memoryByJob.get(result.id).fetch(new Request('https://events.internal'));
    assert.equal((await eventResponse.json()).events[0].type,'todo.created');
  }finally{globalThis.fetch=previous;}
});

test('cancelling a chat stream aborts the provider request',async()=>{
  const previous=globalThis.fetch;let aborted=false;let started;
  const providerStarted=new Promise(resolve=>{started=resolve});
  globalThis.fetch=async (_url,options)=>new Promise((_,reject)=>{
    options.signal.addEventListener('abort',()=>{aborted=true;reject(new DOMException('Cancelled','AbortError'))});
    started();
  });
  try{
    const response=await worker.fetch(request({model:'nemotron-3-ultra-free',messages:[{role:'user',content:'test'}]},'/v1/chat/stream'),env);
    await providerStarted;
    await response.body.cancel();
    assert.equal(aborted,true);
  }finally{globalThis.fetch=previous;}
});
