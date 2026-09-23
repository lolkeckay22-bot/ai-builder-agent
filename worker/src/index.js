const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };

function json(data, status = 200, extra = {}) {
  return new Response(JSON.stringify(data), { status, headers: { ...JSON_HEADERS, ...extra } });
}

function cors(request) {
  const origin = request.headers.get("origin") || "*";
  return {
    "access-control-allow-origin": origin,
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "content-type,x-workai-token",
    "access-control-max-age": "86400",
  };
}

function authorized(request, env) {
  const supplied = request.headers.get("x-workai-token") || "";
  return supplied.length > 20 && supplied === env.WORKAI_DEVICE_TOKEN;
}

const ALLOWED_MODELS = new Set([
  "nvidia/nemotron-3-super-120b-a12b",
  "nvidia/nemotron-3-ultra-550b-a55b",
  "agnes-2.5-flash",
  "agnes-3.0-flash",
  "north-mini-code-1-0",
  "nemotron-3-ultra-free",
  "mimo-v2.6-flash-free",
  "muse-spark-1.3-contributor-free",
  "muse-spark-1.2-contributor-free",
]);
const ZEN_MODELS=new Set(["nemotron-3-ultra-free","mimo-v2.6-flash-free","muse-spark-1.3-contributor-free","muse-spark-1.2-contributor-free"]);
const ZEN_RESPONSES_MODELS=new Set(["muse-spark-1.3-contributor-free","muse-spark-1.2-contributor-free"]);
function responsesInput(messages){const input=[];for(const m of messages){if(m.role==="tool"){input.push({type:"function_call_output",call_id:m.tool_call_id,output:String(m.content||"")});continue}if(m.tool_calls){if(m.content)input.push({role:m.role,content:m.content});for(const c of m.tool_calls)input.push({type:"function_call",call_id:c.id,name:c.function?.name||"",arguments:c.function?.arguments||"{}"});continue}input.push({role:m.role,content:m.content??""})}return input}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function sha256Hex(bytes) {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, "0")).join("");
}

function decodeBase64(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function safeHttpUrl(value) {
  try {
    const url = new URL(value);
    if (url.protocol !== "https:") return null;
    const host = url.hostname.toLowerCase();
    if (host === "localhost" || host.endsWith(".local") || /^(10\.|127\.|169\.254\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(host)) return null;
    return url;
  } catch { return null; }
}

function plainText(html) {
  return html.replace(/<script[\s\S]*?<\/script>/gi, " ").replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&#39;/g, "'")
    .replace(/&quot;/g, '"').replace(/\s+/g, " ").trim();
}

function decodeXml(value) {
  return plainText(String(value || "").replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1"))
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCharCode(parseInt(n, 16)));
}

async function webSearch(query) {
  const response = await fetch(`https://html.duckduckgo.com/html/?q=${encodeURIComponent(query)}`, { headers: { "user-agent": "Mozilla/5.0 WorkAI/0.1" } });
  if (!response.ok) throw new Error(`search_${response.status}`);
  const html = await response.text();
  const results = [];
  const pattern = /class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>[\s\S]{0,3000}?class="result__snippet"[^>]*>([\s\S]*?)<\/(?:a|div)>/gi;
  let match;
  while ((match = pattern.exec(html)) && results.length < 5) {
    let href = match[1].replace(/&amp;/g, "&");
    try { const u = new URL(href, "https://duckduckgo.com"); href = u.searchParams.get("uddg") || u.href; } catch {}
    if (safeHttpUrl(href)) results.push({ title: plainText(match[2]), url: href, snippet: plainText(match[3]).slice(0, 600) });
  }
  if(results.length)return results;
  const instant=await fetch(`https://api.duckduckgo.com/?q=${encodeURIComponent(query)}&format=json&no_html=1&skip_disambig=1`);
  if(instant.ok){
    const data=await instant.json();
    const topics=(data.RelatedTopics||[]).flatMap(x=>x.Topics||[x]).filter(x=>x.FirstURL&&x.Text).slice(0,5);
    if(topics.length)return topics.map(x=>({title:String(x.Text).split(" - ")[0],url:x.FirstURL,snippet:String(x.Text).slice(0,600)}));
  }
  const bing=await fetch(`https://www.bing.com/search?q=${encodeURIComponent(query)}&format=rss`,{headers:{"user-agent":"Mozilla/5.0 WorkAI/0.2","accept":"application/rss+xml,application/xml,text/xml"}});
  if(!bing.ok)throw new Error(`search_fallback_${bing.status}`);
  const xml=await bing.text(),fallback=[];const item=/<item>([\s\S]*?)<\/item>/gi;let entry;
  while((entry=item.exec(xml))&&fallback.length<5){const part=entry[1];const title=part.match(/<title>([\s\S]*?)<\/title>/i)?.[1];const link=part.match(/<link>([\s\S]*?)<\/link>/i)?.[1];const description=part.match(/<description>([\s\S]*?)<\/description>/i)?.[1];const url=decodeXml(link);if(title&&safeHttpUrl(url))fallback.push({title:decodeXml(title),url,snippet:decodeXml(description).slice(0,600)});}
  return fallback;
}

async function exchangeRate(base, quote) {
  const from=String(base||"USD").toUpperCase().replace(/[^A-Z]/g,"").slice(0,3),to=String(quote||"UAH").toUpperCase().replace(/[^A-Z]/g,"").slice(0,3);
  if(from==="USD"&&to==="UAH"){
    const response=await fetch("https://bank.gov.ua/NBUStatService/v1/statdirectory/exchange?valcode=USD&json");
    if(response.ok){const item=(await response.json())?.[0];if(item?.rate)return {ok:true,base:from,quote:to,rate:Number(item.rate),date:item.exchangedate,source:"https://bank.gov.ua/ua/markets/exchangerates"};}
  }
  const response=await fetch(`https://api.frankfurter.app/latest?from=${from}&to=${to}`);if(!response.ok)throw new Error(`exchange_${response.status}`);const data=await response.json();const rate=Number(data.rates?.[to]);if(!rate)throw new Error("exchange_rate_missing");return {ok:true,base:from,quote:to,rate,date:data.date,source:"https://frankfurter.app/"};
}

async function nvidia(env, messages, maxTokens = 2048, temperature = 0.45, requestedModel) {
  const selected=ALLOWED_MODELS.has(requestedModel)?requestedModel:(env.NVIDIA_MODEL||"nvidia/nemotron-3-super-120b-a12b");
  const agnes=selected.startsWith("agnes-"),cohere=selected==="north-mini-code-1-0",zen=ZEN_MODELS.has(selected),responses=ZEN_RESPONSES_MODELS.has(selected);
  const model=agnes?(env.NVIDIA_MODEL||"nvidia/nemotron-3-super-120b-a12b"):selected;
  const endpoint=responses?"https://opencode.ai/zen/v1/responses":zen?"https://opencode.ai/zen/v1/chat/completions":cohere?"https://api.cohere.com/compatibility/v1/chat/completions":"https://integrate.api.nvidia.com/v1/chat/completions";
  const apiKey=zen?env.OPENCODE_API_KEY:cohere?env.COHERE_API_KEY:env.NVIDIA_API_KEY;
  const provider=zen?"OPENCODE":cohere?"COHERE":"NVIDIA";if(!apiKey)throw new Error(`${provider}_API_KEY is not configured`);
  let lastStatus = 0;
  let lastText = "";
  for (let attempt = 0; attempt < 5; attempt++) {
    let response;
    try {
      response = await fetch(endpoint, {
        method: "POST",
        headers: {
          "authorization": `Bearer ${apiKey}`,
          "content-type": "application/json",
          "accept": "application/json",
        },
        body: JSON.stringify(responses?{model,input:responsesInput(messages),max_output_tokens:maxTokens,stream:false}:{model,messages,temperature,max_tokens:maxTokens,stream:false}),
      });
      lastStatus = response.status;
      lastText = await response.text();
      if (response.ok) {
        const data = JSON.parse(lastText);
        return String(responses?(data.output_text||data.output?.flatMap(x=>x.content||[]).map(x=>x.text||"").join("")):data.choices?.[0]?.message?.content||"").trim();
      }
      if (![408, 429, 500, 502, 503, 504].includes(response.status)) break;
      const retryAfter = Number(response.headers.get("retry-after"));
      const backoff = Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1000 : 700 * (2 ** attempt) + Math.floor(Math.random() * 350);
      await sleep(Math.min(backoff, 9000));
    } catch (error) {
      lastText = String(error?.message || error);
      if (attempt === 4) break;
      await sleep(700 * (2 ** attempt));
    }
  }
  throw new Error(`${zen?"OpenCode Zen":cohere?"Cohere":"NVIDIA"} ${lastStatus || "network"}: ${lastText.slice(0, 300)}`);
}

async function nvidiaStream(env, messages, requestedModel, requestedEffort, tools = []) {
  const model = ALLOWED_MODELS.has(requestedModel) ? requestedModel : (env.NVIDIA_MODEL || "nvidia/nemotron-3-super-120b-a12b");
  const agnes = model.startsWith("agnes-"),zen=ZEN_MODELS.has(model),responses=ZEN_RESPONSES_MODELS.has(model);
  const cohere = model === "north-mini-code-1-0";
  const endpoint = responses?"https://opencode.ai/zen/v1/responses":zen?"https://opencode.ai/zen/v1/chat/completions":cohere ? "https://api.cohere.com/compatibility/v1/chat/completions" : agnes ? "https://apihub.agnes-ai.com/v1/chat/completions" : "https://integrate.api.nvidia.com/v1/chat/completions";
  const apiKey = zen?env.OPENCODE_API_KEY:cohere ? env.COHERE_API_KEY : agnes ? env.AGNES_API_KEY : env.NVIDIA_API_KEY;
  const provider=zen?"OpenCode Zen":cohere?"Cohere":agnes?"Agnes":"NVIDIA";
  if(!apiKey)throw new Error(`${provider}_API_KEY is not configured`);
  const allowed = model.includes("ultra") ? new Set(["none", "medium", "high"]) : new Set(["none", "low", "high"]);
  const reasoningEffort = allowed.has(requestedEffort) ? requestedEffort : (model.includes("ultra") ? "medium" : "low");
  let lastText = "";
  for (let attempt = 0; attempt < 5; attempt++) {
    let response;
    try{response = await fetch(endpoint, {
      method: "POST",headers: { "authorization": `Bearer ${apiKey}`, "content-type": "application/json", "accept": "text/event-stream" },
      body: JSON.stringify(responses?{model,input:responsesInput(messages),max_output_tokens:4096,stream:true,...(tools.length?{tools:tools.map(t=>({type:"function",name:t.function.name,description:t.function.description,parameters:t.function.parameters})),tool_choice:"auto"}:{})}:{ model, messages, temperature: 0.45, max_tokens: 4096, stream: true, ...(tools.length?{tools,tool_choice:"auto"}:{}), ...((agnes||cohere||zen)?{}:{reasoning_effort: reasoningEffort}) }),
    });}catch(error){lastText=String(error?.message||error);if(attempt<4){await sleep(Math.min(700*(2**attempt),9000));continue}throw new Error(`${provider} network: ${lastText.slice(0,300)}`);}
    if (response.ok) return response;
    lastText = await response.text();
    if (![408,429,500,502,503,504].includes(response.status)) throw new Error(`${provider} ${response.status}: ${lastText.slice(0,300)}`);
    const wait = Number(response.headers.get("retry-after"));
    await sleep(Math.min(wait > 0 ? wait * 1000 : 700 * (2 ** attempt) + Math.random() * 350, 9000));
  }
  throw new Error(`${provider} overloaded: ${lastText.slice(0,300)}`);
}

async function relayProvider(upstream, send, turn, allowContentTerminated=false) {
    const reader=upstream.body.getReader();
    const decoder=new TextDecoder();let buffer="",completed=false,content="",reasoningSeen=false;const calls=[];
    try{
      while(true){
        const {done,value}=await reader.read();buffer+=decoder.decode(value||new Uint8Array(),{stream:!done});
        const frames=buffer.split(/\r?\n\r?\n/);buffer=frames.pop()||"";if(done&&buffer.trim()){frames.push(buffer);buffer=""}
        for(const frame of frames){
          for(const line of frame.split(/\r?\n/)){
            if(!line.startsWith("data:"))continue;const raw=line.slice(5).trim();
            if(raw==="[DONE]"){completed=true;continue}
            let packet;try{packet=JSON.parse(raw)}catch{continue}
            if(packet.type?.startsWith("response.")){
              if(packet.type==="response.output_text.delta"&&packet.delta)content+=String(packet.delta);
              if((packet.type==="response.reasoning_summary_text.delta"||packet.type==="response.reasoning_text.delta")&&packet.delta){if(!reasoningSeen){send({type:"thinking_start",turn});reasoningSeen=true}send({type:"thinking_delta",turn,delta:String(packet.delta)});}
              if(packet.type==="response.output_item.added"&&packet.item?.type==="function_call"){calls.push({id:packet.item.call_id||packet.item.id||crypto.randomUUID(),itemId:packet.item.id,type:"function",function:{name:packet.item.name||"",arguments:packet.item.arguments||""}});}
              if(packet.type==="response.function_call_arguments.delta"){const call=calls.find(x=>x.itemId===packet.item_id)||calls.at(-1);if(call)call.function.arguments+=String(packet.delta||"");}
              if(packet.type==="response.completed")completed=true;
              if(packet.type==="response.failed"||packet.type==="error")throw new Error(packet.response?.error?.message||packet.error?.message||"provider_response_failed");
              continue;
            }
            const choice=packet.choices?.[0]||{},delta=choice.delta||{};
            const thinking=delta.reasoning_content||delta.reasoning||delta.thinking||"";
            const rawText=delta.content??choice.message?.content??packet.token??"";const text=Array.isArray(rawText)?rawText.map(x=>x?.text||x?.content||"").join(""):rawText;
            if(thinking){if(!reasoningSeen){send({type:"thinking_start",turn});reasoningSeen=true}send({type:"thinking_delta",turn,delta:String(thinking)});}
            if(text)content+=String(text);
            for(const part of (delta.tool_calls||[])){
              const index=Number(part.index||0);calls[index]??={id:"",type:"function",function:{name:"",arguments:""}};
              if(part.id)calls[index].id=part.id;if(part.function?.name)calls[index].function.name+=part.function.name;if(part.function?.arguments)calls[index].function.arguments+=part.function.arguments;
            }
            if(choice.finish_reason)completed=true;
          }
        }
        if(done)break;
      }
      if(!completed&&!(allowContentTerminated&&(content.trim()||calls.length)))throw new Error("provider_stream_ended_without_completion");
      return {content,toolCalls:calls.filter(Boolean).map(call=>({...call,id:call.id||crypto.randomUUID()})),reasoningSeen};
    }catch(error){throw error}
}

const CHAT_TOOLS=[
  {type:"function",function:{name:"get_weather",description:"Получить текущую погоду. Используй только для погоды.",parameters:{type:"object",properties:{location:{type:"string"}},required:["location"]}}},
  {type:"function",function:{name:"get_exchange_rate",description:"Получить актуальный официальный курс валют. Используй для текущего курса валют вместо общего веб-поиска.",parameters:{type:"object",properties:{base:{type:"string",description:"Базовая валюта, например USD"},quote:{type:"string",description:"Валюта котировки, например UAH"}},required:["base","quote"]}}},
  {type:"function",function:{name:"web_search",description:"Искать актуальную внешнюю информацию. Не используй для создания файлов, обычных знаний, письма, перевода или математики.",parameters:{type:"object",properties:{query:{type:"string"}},required:["query"]}}}
];

async function executeChatTool(call,send){
  const name=String(call.function?.name||"");let args={};try{args=JSON.parse(call.function?.arguments||"{}")}catch{}
  if(name==="get_weather"){
    const location=String(args.location||"Киев").slice(0,80);send({type:"tool_call",id:call.id,name,label:`Проверяю погоду: ${location}`,icon:"search"});
    try{const geo=await fetch(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(location)}&count=1&language=ru&format=json`);const point=(await geo.json()).results?.[0];if(!point)throw new Error("location_not_found");const response=await fetch(`https://api.open-meteo.com/v1/forecast?latitude=${point.latitude}&longitude=${point.longitude}&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m&timezone=auto`);if(!response.ok)throw new Error(`weather_${response.status}`);const data=await response.json();const result={ok:true,location:point.name,country:point.country,current:data.current,source:"https://open-meteo.com/"};send({type:"tool_result",id:call.id,name,status:"tool_success",text:`Погода для ${point.name} получена`,icon:"search"});return {result:JSON.stringify(result),sources:[{url:"https://open-meteo.com/",title:"Open-Meteo — прогноз погоды",domain:"open-meteo.com",snippet:`Текущая погода для ${point.name}`,favicon:"https://open-meteo.com/favicon.ico"}]};}catch(error){const result={ok:false,error:String(error?.message||error)};send({type:"tool_result",id:call.id,name,status:"recoverable_error",text:`Не удалось получить погоду: ${result.error}`,icon:"error"});return {result:JSON.stringify(result),sources:[]};}
  }
  if(name==="get_exchange_rate"){
    const base=String(args.base||"USD"),quote=String(args.quote||"UAH");send({type:"tool_call",id:call.id,name,label:`Проверяю курс ${base.toUpperCase()} к ${quote.toUpperCase()}`,icon:"search"});
    try{const data=await exchangeRate(base,quote);const u=new URL(data.source);send({type:"tool_result",id:call.id,name,status:"tool_success",text:`Курс ${data.base}/${data.quote} получен`,icon:"search"});return {result:JSON.stringify(data),sources:[{url:data.source,title:data.source.includes("bank.gov.ua")?"Национальный банк Украины — официальный курс":"Frankfurter — exchange rates",domain:u.hostname.replace(/^www\./,""),snippet:`${data.date}: 1 ${data.base} = ${data.rate} ${data.quote}`,favicon:`${u.origin}/favicon.ico`}]};}catch(error){const result={ok:false,error:String(error?.message||error)};send({type:"tool_result",id:call.id,name,status:"recoverable_error",text:`Курс не получен: ${result.error}`,icon:"error"});return {result:JSON.stringify(result),sources:[]};}
  }
  if(name==="web_search"){
    const query=String(args.query||"").trim().slice(0,300);send({type:"tool_call",id:call.id,name,label:`Поиск «${query}»`,icon:"search"});
    try{const results=await webSearch(query);const sources=results.map(r=>{const u=new URL(r.url);return {url:r.url,title:r.title||u.hostname,domain:u.hostname.replace(/^www\./,""),snippet:r.snippet,favicon:`${u.origin}/favicon.ico`}});send({type:"tool_result",id:call.id,name,status:"tool_success",text:`Найдено результатов: ${results.length}`,icon:"search"});return {result:JSON.stringify({ok:true,query,results}),sources};}catch(error){const result={ok:false,error:String(error?.message||error)};send({type:"tool_result",id:call.id,name,status:"recoverable_error",text:`Поиск не выполнен: ${result.error}`,icon:"error"});return {result:JSON.stringify(result),sources:[]};}
  }
  const result=JSON.stringify({ok:false,error:"unknown_tool"});send({type:"tool_result",id:call.id,name,status:"fatal_error",text:`Неизвестный инструмент: ${name}`,icon:"error"});return {result,sources:[]};
}

function executionStream(env, body, model, messages, custom) {
  const encoder=new TextEncoder();
  return new ReadableStream({async start(controller){
    const send=value=>controller.enqueue(encoder.encode(`data: ${JSON.stringify(value)}\n\n`));
    try{
      const now=new Intl.DateTimeFormat("ru-RU",{timeZone:"Europe/Kyiv",dateStyle:"full",timeStyle:"long"}).format(new Date());
      const creationInstruction=body.creation_request&&String(body.mode)==="chat"?" Пользователь просит создать или изменить файл. Кратко и естественно объясни, что для фактического выполнения нужно перейти во вкладку «Работа»; не утверждай, что файл уже создаётся. Под ответом приложение покажет кнопки «Перейти» и «Пропустить». Перефразируй это самостоятельно, не используй шаблонную канцелярскую фразу.":"";
      const system={role:"system",content:`Ты WorkAI — мобильный AI-агент. Текущие дата и время в Киеве: ${now}. Отвечай на языке пользователя. Сам решай, нужен ли инструмент. Веб-поиск допустим только при прямой просьбе искать или для актуальных внешних данных; никогда не ищи способы создания MTZ/ZIP/APK и не используй поиск для обычных знаний.${creationInstruction} Если вызываешь инструмент, перед вызовом естественно и кратко скажи пользователю, что именно проверишь. После tool result обязательно проанализируй результат новым model turn и дай ответ. Ошибка инструмента не завершает задачу: попробуй исправимый альтернативный запрос либо честно объясни конкретную причину. Не заявляй об успехе до проверки результата. Не печатай внутренний JSON. Используй Markdown. Источники показывает интерфейс.${custom?`\n\nПользовательские инструкции:\n${custom}`:""}`};
      const history=[system,...messages],allSources=[],toolMemory=[];let finalText="",finished=false,emptyTurns=0;
      for(let turn=1;turn<=8&&!finished;turn++){
        send({type:"model_turn_start",turn});
        const upstream=await nvidiaStream(env,history,model,String(body.reasoning_effort||""),CHAT_TOOLS);
        const generated=await relayProvider(upstream,send,turn,ZEN_RESPONSES_MODELS.has(model));
        const assistant={role:"assistant",content:generated.content||null};if(generated.toolCalls.length)assistant.tool_calls=generated.toolCalls;history.push(assistant);
        if(!generated.toolCalls.length){finalText=generated.content.trim();if(!finalText){emptyTurns++;if(emptyTurns<2){history.push({role:"user",content:"Ты завершил ход без ответа. Продолжи: используй уже полученные tool results и сформируй содержательный финальный ответ пользователю. Не вызывай повторно тот же поиск без изменения запроса."});continue}const fallback=await nvidia(env,[...history,{role:"user",content:"Сформируй финальный ответ по истории и результатам инструментов. Если данных недостаточно, конкретно объясни это и предложи полезный следующий шаг."}],2048,0.35);finalText=fallback.trim()||"Не удалось получить содержательный ответ от выбранной модели. Попробуйте повторить запрос или выбрать другую модель.";}send({type:"text_delta",delta:finalText});finished=true;break;}
        if(generated.content.trim())send({type:"intermediate",text:generated.content.trim(),turn});
        for(const call of generated.toolCalls){const executed=await executeChatTool(call,send);history.push({role:"tool",tool_call_id:call.id,name:call.function.name,content:executed.result});toolMemory.push(`${call.function.name}: ${executed.result}`);allSources.push(...executed.sources);}
      }
      if(!finished)throw new Error("agent_turn_limit_reached");
      const unique=[...new Map(allSources.map(s=>[s.url,s])).values()].slice(0,8);if(unique.length)send({type:"sources",items:unique});
      if(toolMemory.length)send({type:"context_snapshot",text:`${finalText}\n\nРезультаты инструментов этой сессии:\n${toolMemory.join("\n")}`});
      send({type:"task_completed"});send({type:"done"});controller.close();
    }catch(error){send({type:"error",message:String(error?.message||error)});controller.close()}
  }});
}

async function github(env, path, init = {}) {
  const response = await fetch(`https://api.github.com/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}${path}`, {
    ...init,
    headers: {
      "authorization": `Bearer ${env.WORKAI_GITHUB_TOKEN}`,
      "accept": "application/vnd.github+json",
      "x-github-api-version": "2022-11-28",
      "user-agent": "WorkAI-Agent",
      ...(init.headers || {}),
    },
  });
  return response;
}

function parseJsonObject(text) {
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start < 0 || end <= start) return null;
  try { return JSON.parse(text.slice(start, end + 1)); } catch { return null; }
}

async function createPlan(env, prompt, kind, model) {
  const raw = await nvidia(env, [
    { role: "system", content: "Ты планировщик WorkAI. Верни только JSON без markdown: {\"reasoning_summary\":\"краткое фактическое резюме выбранного подхода и почему нужны указанные инструменты\",\"intro\":\"что именно ты сделаешь, одной естественной фразой без заявления об успехе\",\"skills\":[\"название подходящего навыка\"],\"tasks\":[\"...\"]}. Указывай только действительно подходящие навыки из file-creator, file-analysis, archive-editor, mtz-editor, android-app-builder. Никогда не выбирай web-research для создания файлов. Нужно 3-7 конкретных проверяемых этапов. Последние этапы: сборка, проверка, публикация файла." },
    { role: "user", content: `Тип результата: ${kind}. Задача: ${prompt}` },
  ], 900, 0.25, model);
  const parsed = parseJsonObject(raw);
  const tasks = Array.isArray(parsed?.tasks) ? parsed.tasks.map(String).filter(Boolean).slice(0, 7) : [];
  return {reasoningSummary:String(parsed?.reasoning_summary||"").trim(),intro:String(parsed?.intro||"Составлю план и начну выполнение задачи."),skills:Array.isArray(parsed?.skills)?parsed.skills.map(String).filter(Boolean).filter(x=>x!=="web-research").slice(0,4):[],tasks:tasks.length?tasks:["Анализ запроса","Создание файлов","Сборка","Проверка","Публикация файла"]};
}

async function startJob(request, env, url) {
  const body = await request.json();
  const prompt = String(body.prompt || "").trim();
  const kind = String(body.kind || "apk").toLowerCase();
  if (!prompt) return json({ error: "prompt_required" }, 400, cors(request));
  if (!["apk", "mtz", "zip"].includes(kind)) return json({ error: "unsupported_kind" }, 400, cors(request));
  const id = crypto.randomUUID();
  const plan = await createPlan(env, prompt, kind, body.model);
  const tasks = plan.tasks;
  const attachments = [];
  for (const item of (Array.isArray(body.attachments) ? body.attachments : []).slice(0, 8)) {
    const name = String(item?.name || "file.bin").replace(/[^\p{L}\p{N}._ -]/gu, "_").slice(0, 120);
    const mime = String(item?.mime || "application/octet-stream").slice(0, 120);
    const chunks = Array.isArray(item?.chunks) ? item.chunks.slice(0, 24).map((chunk, index) => ({ sha: String(chunk?.sha || ""), sha256: String(chunk?.sha256 || ""), size: Number(chunk?.size || 0), index: Number(chunk?.index ?? index) })) : [];
    if (!chunks.length) throw new Error(`invalid_attachment:${name}`);
    if (chunks.some((chunk, index) => !/^[0-9a-f]{40}$/.test(chunk.sha) || !/^[0-9a-f]{64}$/.test(chunk.sha256) || chunk.size < 1 || chunk.size > 5 * 1024 * 1024 || chunk.index !== index)) throw new Error(`invalid_chunks:${name}`);
    const size = Number(item?.size || 0), sha256 = String(item?.sha256 || "");
    if (size < 1 || size > 100 * 1024 * 1024 || chunks.reduce((sum, c) => sum + c.size, 0) !== size || !/^[0-9a-f]{64}$/.test(sha256)) throw new Error(`invalid_attachment_integrity:${name}`);
    attachments.push({ name, mime, chunks, size, sha256 });
  }
  const dispatch = await github(env, "/dispatches", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      event_type: "workai_build",
      client_payload: { id, prompt, kind, tasks, attachments, model: ALLOWED_MODELS.has(body.model) ? body.model : env.NVIDIA_MODEL, reasoning_effort: String(body.reasoning_effort || ""), system_prompt: String(body.system_prompt || "").slice(0, 8000), callback_origin: url.origin },
    }),
  });
  if (!dispatch.ok) return json({ error: "dispatch_failed", detail: (await dispatch.text()).slice(0, 500) }, 502, cors(request));
  return json({ id, kind, tasks, intro:plan.intro, reasoning_summary:plan.reasoningSummary, skills:plan.skills, status: "queued" }, 202, cors(request));
}

async function findRun(env, id) {
  const response = await github(env, "/actions/workflows/agent-build.yml/runs?event=repository_dispatch&per_page=50");
  if (!response.ok) throw new Error(`GitHub runs ${response.status}`);
  const data = await response.json();
  return (data.workflow_runs || []).find(r => String(r.display_title || "").includes(id));
}

async function jobStatus(request, env, id) {
  const run = await findRun(env, id);
  if (!run) return json({ id, status: "queued", steps: [] }, 200, cors(request));
  const jobsResponse = await github(env, `/actions/runs/${run.id}/jobs`);
  const jobsData = jobsResponse.ok ? await jobsResponse.json() : { jobs: [] };
  const steps = (jobsData.jobs || []).flatMap(job => (job.steps || []).map(step => ({
    title: step.name,
    status: step.status,
    conclusion: step.conclusion,
  })));
  let artifact = null;
  if (run.conclusion === "success") {
    const release = await github(env, `/releases/tags/job-${id}`);
    if (release.ok) {
      const releaseData = await release.json();
      const asset = (releaseData.assets || []).find(a=>a.name!=="SHA256SUMS.txt");
      if (asset) artifact = { name: asset.name, size: asset.size, download: `/v1/jobs/${id}/download` };
    }
  }
  return json({ id, status: run.status, conclusion: run.conclusion, runUrl: run.html_url, steps, artifact }, 200, cors(request));
}

async function downloadResult(request, env, id) {
  const release = await github(env, `/releases/tags/job-${id}`);
  if (!release.ok) return json({ error: "artifact_not_ready" }, 404, cors(request));
  const releaseData = await release.json();
  const asset = (releaseData.assets || []).find(a=>a.name!=="SHA256SUMS.txt");
  if (!asset) return json({ error: "artifact_not_ready" }, 404, cors(request));
  const binary = await fetch(asset.url, {
    headers: {
      "authorization": `Bearer ${env.WORKAI_GITHUB_TOKEN}`,
      "accept": "application/octet-stream",
      "user-agent": "WorkAI-Agent",
    },
    redirect: "follow",
  });
  if (!binary.ok) return json({ error: "download_failed" }, 502, cors(request));
  return new Response(binary.body, {
    status: 200,
    headers: {
      "content-type": asset.content_type || "application/octet-stream",
      "content-length": String(asset.size),
      "content-disposition": `attachment; filename=\"${asset.name.replaceAll('"', '')}\"`,
    },
  });
}

async function reviewJob(request,env,id){
  const body=await request.json(),artifact=String(body.artifact||"файл"),prompt=String(body.prompt||"").slice(0,6000),model=ALLOWED_MODELS.has(body.model)?body.model:env.NVIDIA_MODEL;
  const summary=await nvidia(env,[{role:"system",content:"Ты проверяющий агент WorkAI. Сформулируй одним коротким абзацем реальное резюме проверки уже завершённой задачи: что было проверено перед выдачей результата. Не выдумывай детали, которых нет во входных данных, и не заявляй о незавершённых действиях."},{role:"user",content:`Задача: ${prompt}\nGitHub Actions завершился успешно. Опубликован и найден артефакт: ${artifact}. Архив/сборка прошли встроенную проверку workflow.`}],500,0.2,model);
  return json({id,reasoning_summary:summary.trim()},200,cors(request));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors(request) });
    if (url.pathname === "/health") return json({ ok: true, service: "WorkAI", model: env.NVIDIA_MODEL }, 200, cors(request));
    if (!authorized(request, env)) return json({ error: "unauthorized" }, 401, cors(request));
    try {
      if (url.pathname === "/v1/chat" && request.method === "POST") {
        const body = await request.json();
        const messages = Array.isArray(body.messages) ? body.messages.slice(-30) : [];
        const model = ALLOWED_MODELS.has(body.model) ? body.model : env.NVIDIA_MODEL;
        const answer = await nvidia(env, [{ role: "system", content: `Ты WorkAI, точный русскоязычный ассистент. Текущая модель: ${model}. Если тебя спрашивают о модели, назови именно её.` }, ...messages], 2048, 0.45, model);
        return json({ answer, model }, 200, cors(request));
      }
      if (url.pathname === "/v1/chat/stream" && request.method === "POST") {
        const body = await request.json();
        const model = ALLOWED_MODELS.has(body.model) ? body.model : env.NVIDIA_MODEL;
        const messages = Array.isArray(body.messages) ? body.messages.slice(-30) : [];
        const custom = String(body.system_prompt || "").trim().slice(0, 8000);
        return new Response(executionStream(env,body,model,messages,custom), { status: 200, headers: { ...cors(request), "content-type": "text/event-stream; charset=utf-8", "cache-control": "no-cache", "x-accel-buffering": "no" } });
      }
      if (url.pathname === "/v1/uploads/blob" && request.method === "POST") {
        const body = await request.json(); const base64 = String(body.base64 || "");
        if (!base64 || base64.length > 8_000_000) return json({ error: "invalid_chunk" }, 400, cors(request));
        const bytes = decodeBase64(base64), expected = String(body.sha256 || "").toLowerCase(), actual = await sha256Hex(bytes);
        if (bytes.byteLength !== Number(body.size) || !/^[0-9a-f]{64}$/.test(expected) || actual !== expected) return json({ error: "chunk_integrity_failed" }, 422, cors(request));
        const created = await github(env, "/git/blobs", { method:"POST", headers:{"content-type":"application/json"}, body:JSON.stringify({content:base64,encoding:"base64"}) });
        if(!created.ok) return json({error:"chunk_upload_failed",detail:(await created.text()).slice(0,300)},502,cors(request));
        return json({sha:(await created.json()).sha,sha256:actual,size:bytes.byteLength,index:Number(body.index || 0)},201,cors(request));
      }
      if (url.pathname === "/v1/jobs" && request.method === "POST") return await startJob(request, env, url);
      const match = url.pathname.match(/^\/v1\/jobs\/([0-9a-f-]+)(\/download)?$/);
      if (match && request.method === "GET") return match[2] ? await downloadResult(request, env, match[1]) : await jobStatus(request, env, match[1]);
      const review=url.pathname.match(/^\/v1\/jobs\/([0-9a-f-]+)\/review$/);if(review&&request.method==="POST")return await reviewJob(request,env,review[1]);
      return json({ error: "not_found" }, 404, cors(request));
    } catch (error) {
      return json({ error: "internal_error", detail: String(error?.message || error).slice(0, 700) }, 500, cors(request));
    }
  },
};
