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
