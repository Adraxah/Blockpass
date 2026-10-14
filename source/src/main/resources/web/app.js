'use strict';
const el=id=>document.getElementById(id);
let info=null,ticket=null,busy=false,polling=false;
const storageKey='blockpass-v2-ticket';
function message(id,text){el(id).textContent=text;el(id).hidden=!text;}
function remember(value){ticket=value;try{if(value)sessionStorage.setItem(storageKey,JSON.stringify(value));else sessionStorage.removeItem(storageKey);}catch{}}
async function api(path,data){
 const response=await fetch('/api/'+path,{method:data?'POST':'GET',headers:data?{'Content-Type':'application/json'}:{},body:data?JSON.stringify(data):undefined,cache:'no-store',signal:AbortSignal.timeout(12000)});
 const result=await response.json();if(!response.ok){const error=Error(result.error||'Anfrage fehlgeschlagen.');error.status=response.status;throw error;}return result;
}
function render(){
 el('join-form').hidden=!!ticket;el('result').hidden=!ticket;
 el('submit').disabled=busy||!info;el('submit').textContent=busy?'Wird geprüft …':'Freischalten →';
 el('username').disabled=busy;el('code').disabled=busy;
 if(!ticket)return;
 const success=ticket.status==='applied',failed=ticket.status==='failed';
 el('result-icon').className='result-icon '+(success?'success':failed?'':'pending');el('result-icon').textContent=success?'OK':failed?'!':'...';
 el('result-title').textContent=success?'FREIGESCHALTET!':failed?'NOCH NICHT GEKLAPPT':'EINEN MOMENT …';
 el('result-text').textContent=success?ticket.username+', du stehst auf der Whitelist. Du kannst jetzt beitreten.':failed?(ticket.error||'Bitte das Server-Team kontaktieren.'):(ticket.username+', deine Anfrage wird verarbeitet.');
 el('waiting').hidden=success||failed;el('another').hidden=!success&&!failed;
 el('copy-address').hidden=!success||!info?.serverAddress;
 el('server-address').textContent=info?.serverAddress||'';
}
async function loadInfo(){try{info=await api('info');el('server-name').textContent=info.serverName;el('connection').classList.add('online');el('connection-text').textContent='Server online';render();}catch{info=null;el('connection').classList.remove('online');el('connection-text').textContent='Nicht erreichbar';render();}}
async function poll(){
 if(!ticket||ticket.status==='applied'||polling)return;polling=true;
 try{const result=await api('status',{receipt:ticket.receipt});remember({...ticket,...result});message('result-error','');render();}
 catch(error){
  if(error.status===404&&Date.now()-(ticket.started||0)>30000){remember(null);render();message('form-error','Anfrage nicht gefunden. Bitte Name und Code erneut eingeben.');}
  else if(error.status!==404)message('result-error','Verbindung unterbrochen. Wir versuchen es automatisch erneut.');
 }finally{polling=false;}
}
el('show-code').addEventListener('click',()=>{const show=el('code').type==='password';el('code').type=show?'text':'password';el('show-code').setAttribute('aria-pressed',String(show));el('show-code').setAttribute('aria-label',show?'Code verbergen':'Code anzeigen');el('show-code').textContent=show?'***':'ABC';});
el('join-form').addEventListener('submit',async event=>{
 event.preventDefault();if(busy)return;busy=true;message('form-error','');render();
 const nonce=Array.from(crypto.getRandomValues(new Uint8Array(24)),v=>v.toString(16).padStart(2,'0')).join('');
 const pending={receipt:nonce,username:el('username').value.trim(),status:'pending',started:Date.now()};
 // Save only the receipt, never the invitation code. A lost HTTP response is recoverable.
 try{sessionStorage.setItem(storageKey,JSON.stringify(pending));}catch{}
 try{const result=await api('claim',{username:pending.username,code:el('code').value,receipt:nonce});remember({...pending,...result});el('code').value='';message('result-error','');}
 catch(error){if(error.status){remember(null);message('form-error',error.message);}else{remember(pending);message('result-error','Antwort noch nicht angekommen. Wir prüfen deinen Status.');}}
 finally{busy=false;render();poll();}
});
el('another').addEventListener('click',()=>{remember(null);message('result-error','');message('form-error','');render();el('username').focus();});
el('copy-address').addEventListener('click',async()=>{
 const address=info?.serverAddress;if(!address)return;
 try{if(navigator.clipboard&&window.isSecureContext)await navigator.clipboard.writeText(address);else{const input=document.createElement('textarea');input.value=address;input.className='copy-helper';input.readOnly=true;document.body.append(input);input.select();const ok=document.execCommand('copy');input.remove();if(!ok)throw Error();}el('copy-label').textContent='Kopiert';setTimeout(()=>{el('copy-label').textContent='Kopieren';},2000);}
 catch{message('result-error','Bitte die angezeigte Serveradresse markieren und manuell kopieren.');}
});
try{const stored=JSON.parse(sessionStorage.getItem(storageKey)||'null');if(stored&&/^[a-f0-9]{48}$/.test(stored.receipt)&&/^[A-Za-z0-9_]{3,16}$/.test(stored.username))ticket=stored;}catch{}
render();loadInfo();poll();setInterval(loadInfo,15000);setInterval(poll,3000);

const packIds=['modrinth','prism','curseforge'];
function showModpacks(packs){for(const id of packIds){const link=el('download-'+id),pack=packs.find(p=>p.id===id);const available=pack?.available===true;link.setAttribute('aria-disabled',String(!available));link.tabIndex=available?0:-1;
 if(available){link.href='/downloads/'+id;link.setAttribute('download',pack.filename);link.title=pack.filename;const mb=pack.size/1048576;link.querySelector('small').textContent=(pack.version?pack.version+' · ':'')+(mb>=1?mb.toLocaleString('de-DE',{maximumFractionDigits:1})+' MB':Math.max(1,Math.round(pack.size/1024))+' KB');}
 else{link.removeAttribute('href');link.removeAttribute('download');link.removeAttribute('title');link.querySelector('small').textContent='Noch nicht verfügbar';}
}}
async function loadModpacks(){try{const data=await api('modpacks');showModpacks(data.packs||[]);}catch{showModpacks([]);}}
loadModpacks();setInterval(loadModpacks,15000);
