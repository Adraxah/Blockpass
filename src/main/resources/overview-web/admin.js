'use strict';
const $ = id => document.getElementById(id);
const textFields = ['brand','title','address','mapUrl','discordUrl','projectStart','netherOpen','endOpen','announcementTitle','announcementText','announcementKind','playerPauseStart','playerPauseEnd'];
const flags = ['maintenance','showPlayers','showTps','showUptime','showTimeline','playerPauseEnabled'];
let csrf = '', revision = null, dirty = false, saving = false;
function message(id,text,tone = '') { $(id).textContent = text; $(id).className = 'small feedback ' + (tone ? tone + '-text' : ''); }
async function api(path,data) {
  const options = {cache:'no-store',credentials:'same-origin',signal:AbortSignal.timeout(12000),headers:{}};
  if (data !== undefined) { options.method = 'POST'; options.headers['Content-Type'] = 'application/json'; options.headers['X-CSRF-Token'] = csrf; options.body = JSON.stringify(data); }
  let response;
  try { response = await fetch('/api/admin/' + path,options); }
  catch (_) { throw new Error('Keine Verbindung. Bitte erneut versuchen. Deine Eingaben bleiben erhalten.'); }
  const body = await response.json();
  if (!response.ok) { const error = new Error(body.error || 'Anfrage fehlgeschlagen.'); error.status = response.status; throw error; }
  return body;
}
function loginView(text = '') { csrf = ''; $('dashboard').hidden = true; $('login-panel').hidden = false; message('login-message',text,text ? 'error' : ''); }
function fill(document) {
  revision = document.revision;
  for (const key of textFields) $(key).value = document.settings[key];
  for (const key of flags) $(key).checked = document.settings[key];
  dirty = false;
  message('save-message','Aktueller Stand geladen.');
}
async function enter(session) {
  csrf = session.csrf;
  if (revision === null) fill(await api('settings'));
  $('login-panel').hidden = true; $('dashboard').hidden = false; $('password').value = '';
  if (dirty) message('save-message','Deine ungespeicherten Eingaben sind noch da. Bitte erneut speichern.');
}
function changed() { dirty = true; message('save-message','Ungespeicherte Änderungen.'); }
$('settings-form').addEventListener('input',changed);
$('settings-form').addEventListener('change',changed);
window.addEventListener('beforeunload',e => { if (dirty) { e.preventDefault(); e.returnValue = ''; } });
$('login-form').addEventListener('submit',async e => {
  e.preventDefault(); $('login-button').disabled = true; message('login-message','Anmeldung läuft …');
  try { await enter(await api('login',{username:$('username').value.trim(),password:$('password').value})); }
  catch (error) { message('login-message',error.message,'error'); }
  finally { $('login-button').disabled = false; }
});
$('settings-form').addEventListener('submit',async e => {
  e.preventDefault(); if (saving) return;
  const settings = {};
  for (const key of textFields) settings[key] = $(key).value.trim();
  for (const key of flags) settings[key] = $(key).checked;
  saving = true; const formControls = [...$('settings-form').elements]; formControls.forEach(el => el.disabled = true); $('logout').disabled = true;
  message('save-message','Änderungen werden gespeichert …');
  try { const result = await api('settings',{revision,settings}); fill(result); message('save-message','Gespeichert! Die Übersicht übernimmt deine Änderungen.','success'); }
  catch (error) {
    message('save-message',error.message,'error');
    if (error.status === 401) loginView('Deine Sitzung ist abgelaufen. Bitte erneut anmelden. Deine Eingaben bleiben erhalten.');
  } finally { saving = false; formControls.forEach(el => el.disabled = false); $('logout').disabled = false; }
});
$('reload-content').addEventListener('click',async () => {
  if (dirty && !window.confirm('Aktuellen gespeicherten Stand laden? Deine ungespeicherten Änderungen werden verworfen.')) return;
  $('reload-content').disabled = true;
  try { fill(await api('settings')); }
  catch (error) { message('save-message',error.message,'error'); if (error.status === 401) loginView('Bitte erneut anmelden.'); }
  finally { $('reload-content').disabled = false; }
});
$('start-now').addEventListener('click',async () => {
  $('start-now').disabled = true;
  try {
    const response = await fetch('/api/overview',{cache:'no-store',signal:AbortSignal.timeout(8000)}); if (!response.ok) throw new Error();
    const data = await response.json(), parts = new Intl.DateTimeFormat('en-GB',{timeZone:'Europe/Berlin',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(data.serverNow);
    const p = Object.fromEntries(parts.map(part => [part.type,part.value])); $('projectStart').value = `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`; changed();
  } catch (_) { message('save-message','Serverzeit konnte nicht geladen werden. Bitte Datum manuell eintragen.','error'); }
  finally { $('start-now').disabled = false; }
});
$('logout').addEventListener('click',async () => {
  if (dirty && !window.confirm('Abmelden und ungespeicherte Änderungen verwerfen?')) return;
  $('logout').disabled = true;
  try { await api('logout',{}); dirty = false; revision = null; $('settings-form').reset(); loginView(); }
  catch (error) { if (error.status === 401) { dirty = false; revision = null; $('settings-form').reset(); loginView(); } else message('save-message',error.message,'error'); }
  finally { $('logout').disabled = false; }
});
(async () => { try { await enter(await api('session')); } catch (error) { loginView(error.status === 401 ? '' : error.message); } })();
