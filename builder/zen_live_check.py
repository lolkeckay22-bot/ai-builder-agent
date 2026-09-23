"""Live contract tests for configured OpenCode Zen credentials."""
import json
import os
import urllib.error
import urllib.request

KEY=os.environ.get('OPENCODE_API_KEY','')
if not KEY: raise SystemExit('OPENCODE_API_KEY is absent from this GitHub Actions runtime')
MODELS={
    'nemotron-3-ultra-free':'chat/completions',
    'mimo-v2.6-flash-free':'chat/completions',
    'muse-spark-1.3-contributor-free':'responses',
    'deepseek-v4-flash-vision-exp':'chat/completions',
}
PNG='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lB8AAAAASUVORK5CYII='

def send(model,endpoint,stream=False,image=False):
    if image:
        content=[{'type':'text','text':'Describe this image in one word.'},{'type':'image_url','image_url':{'url':'data:image/png;base64,'+PNG}}]
    else: content='Reply with OK.'
    messages=[{'role':'user','content':content}]
    payload={'model':model,'stream':stream}
    if endpoint=='responses':
        payload.update(input=messages,max_output_tokens=160)
    else: payload.update(messages=messages,max_tokens=160)
    request=urllib.request.Request('https://opencode.ai/zen/v1/'+endpoint,data=json.dumps(payload).encode(),headers={'Authorization':'Bearer '+KEY,'Content-Type':'application/json','Accept':'text/event-stream' if stream else 'application/json'})
    try:
        with urllib.request.urlopen(request,timeout=90) as response:
            if stream:
                frames=response.read().decode('utf-8',errors='replace')
                if not ('data:' in frames and ('[DONE]' in frames or 'response.completed' in frames)):
                    raise RuntimeError('SSE stream ended without completion')
                return
            data=json.load(response)
            text=''.join(part.get('text','') for item in data.get('output',[]) for part in item.get('content',[])) if endpoint=='responses' else str(data.get('choices',[{}])[0].get('message',{}).get('content') or '')
            if not text.strip() and not data.get('output_text','').strip(): raise RuntimeError('Provider returned an empty answer')
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'{model}: HTTP {error.code}: {error.read().decode(errors="replace")[:400]}') from error

for model,endpoint in MODELS.items():
    send(model,endpoint)
    print(f'{model}: basic request passed')
    send(model,endpoint,stream=True)
    print(f'{model}: streaming passed')
send('deepseek-v4-flash-vision-exp','chat/completions',image=True)
print('deepseek-v4-flash-vision-exp: image request passed')
