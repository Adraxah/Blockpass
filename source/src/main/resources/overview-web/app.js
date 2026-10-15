'use strict';
const $ = id => document.getElementById(id);
let state = null, receivedAt = 0, busy = false;
const formatDate = value => new Intl.DateTimeFormat('de-DE', {timeZone:'Europe/Berlin',day:'2-digit',month:'2-digit',year:'numeric',hour:'2-digit',minute:'2-digit'}).format(value) + ' Uhr';
const now = () => state ? state.serverNow + (performance.now() - receivedAt) : Date.now();
function duration(ms, seconds = false) {
  const total = Math.max(0, Math.floor(ms / 1000)), d = Math.floor(total / 86400), h = Math.floor(total % 86400 / 3600), m = Math.floor(total % 3600 / 60), s = total % 60;
  if (d) return `${d} T ${h} Std ${m} Min`;
  if (h) return `${h} Std ${m} Min`;
  return seconds ? `${m} Min ${String(s).padStart(2,'0')} Sek` : `${m} Min`;
}
function showText(id, text) { $(id).textContent = text; }
function renderTime() {
  if (!state) return;
  const current = now();
  for (const key of ['projectStart','netherOpen','endOpen']) {
    const time = state.milestones[key];
    showText(key + '-date', time === null ? 'Noch nicht festgelegt' : formatDate(time));
    if (time === null) { showText(key + '-tag','TERMIN OFFEN'); showText(key + '-countdown','Wird bald angekündigt'); }
    else if (time > current) { showText(key + '-tag','BALD GEHT’S LOS'); showText(key + '-countdown','Noch ' + duration(time - current, true)); }
    else { showText(key + '-tag',key === 'projectStart' ? 'GESTARTET' : 'TERMIN ERREICHT'); showText(key + '-countdown', key === 'projectStart' ? 'Seit ' + duration(current - time) : 'Geplant seit ' + formatDate(time)); }
  }
  if (state.live.available && current - state.live.observedAt >= 15000) {
    state.live.available = false; renderLive();
    $('connection-error').hidden = false; showText('connection-error','Die Live-Daten sind nicht mehr aktuell. Die Verbindung wird automatisch erneut geprüft.');
  }
  if (state.live.available && state.live.processStartedAt) {
    const elapsed = Math.max(0,current - state.live.processStartedAt);
    showText('uptime',elapsed >= 86400000 ? `${Math.floor(elapsed/86400000)} T ${Math.floor(elapsed%86400000/3600000)} Std` : duration(elapsed));
  }
}
function renderLive() {
  const data = state.live, fresh = data.available;
  $('status-badge').className = 'status-badge ' + (fresh ? 'online' : 'offline');
  showText('server-status',fresh ? 'Server verbunden' : 'Live-Daten nicht verfügbar');
  showText('updated', fresh ? 'Aktualisiert: ' + new Intl.DateTimeFormat('de-DE',{timeZone:'Europe/Berlin',hour:'2-digit',minute:'2-digit',second:'2-digit'}).format(data.observedAt) + ' Uhr' : 'Verbindung wird erneut geprüft');
  showText('online',fresh ? data.online : '—'); showText('max-players',fresh ? ' / ' + data.maxPlayers : '');
  showText('peak',fresh ? 'Höchststand seit Serverstart: ' + data.peakPlayers : 'Keine aktuellen Serverdaten');
  showText('tps',fresh && data.tps !== null ? data.tps.toFixed(1) : '—');
  $('tps').classList.toggle('bad-tps',fresh && data.tps !== null && data.tps < 18);
  showText('tps-caption',fresh && data.tps === null ? 'Messung startet nach einigen Sekunden' : 'Gemessene Tickrate · bis zu 60 s');
  showText('last-restart',fresh && data.processStartedAt ? formatDate(data.processStartedAt) : 'Keine aktuellen Serverdaten');
  if (!fresh || !data.processStartedAt) showText('uptime','—');
  showText('mc-version',fresh ? 'Minecraft Java · ' + data.version : 'Minecraft Java');
  const players = fresh ? data.players : [];
  showText('player-count',fresh ? String(players.length) + ' ONLINE' : '—');
  $('players-empty').hidden = fresh && players.length > 0;
  showText('players-empty',fresh ? 'Gerade ist niemand online. Die Welt wartet auf dich.' : 'Spielerliste aktuell nicht verfügbar.');
  const fragment = document.createDocumentFragment();
  for (const player of players) {
    const li = document.createElement('li'), icon = document.createElement('img'), label = document.createElement('span');
    li.className = 'player'; icon.className = 'avatar'; icon.alt = ''; icon.width = 32; icon.height = 32; icon.loading = 'lazy';
    icon.src = '/heads/' + encodeURIComponent(player.uuid) + '.png';
    icon.addEventListener('error',() => { icon.src = '/default-head.png'; },{once:true}); label.textContent = player.name;
    li.append(icon,label); fragment.append(li);
  }
  $('players').replaceChildren(fragment);
}
function render() {
  const s = state.content;
  document.title = s.title + ' · ' + s.brand;
  showText('brand',s.brand); showText('footer-brand',s.brand); showText('server-title',s.title);
  $('map-link').href = s.mapUrl; $('whitelist-link').href = s.whitelistUrl;
  $('copy-address').hidden = !s.address; showText('address',s.address);
  $('discord-link').hidden = !s.discordUrl; if (s.discordUrl) $('discord-link').href = s.discordUrl;
  $('maintenance').hidden = !s.maintenance;
  $('announcement').hidden = !s.announcementText;
  $('announcement').dataset.kind = s.announcementKind;
  showText('announcement-kind',s.announcementKind === 'event' ? 'NÄCHSTES EVENT' : s.announcementKind === 'warning' ? 'WICHTIGER HINWEIS' : 'SERVER-NEWS');
  showText('announcement-title',s.announcementTitle); $('announcement-title').hidden = !s.announcementTitle; showText('announcement-text',s.announcementText);
  $('tps-card').hidden = !s.showTps; $('uptime-card').hidden = !s.showUptime; $('timeline-section').hidden = !s.showTimeline; $('players-section').hidden = !s.showPlayers;
  renderLive(); renderTime();
}
async function refresh() {
  if (busy || document.hidden) return;
  busy = true;
  try {
    const response = await fetch('/api/overview',{cache:'no-store',signal:AbortSignal.timeout(8000)});
    if (!response.ok) throw new Error('HTTP ' + response.status);
    state = await response.json(); receivedAt = performance.now(); render();
    $('connection-error').hidden = state.live.available;
    if (!state.live.available) showText('connection-error','Das Plugin antwortet, aber es liegen noch keine aktuellen Minecraft-Daten vor.');
  } catch (_) {
    $('connection-error').hidden = false;
    showText('connection-error','Der Server ist gerade nicht erreichbar. Wir versuchen es automatisch erneut.');
    if (state) { state.live.available = false; renderLive(); }
    else { showText('server-status','Verbindung nicht verfügbar'); $('status-badge').className = 'status-badge offline'; showText('updated','Warte auf Verbindung'); showText('players-empty','Spielerliste aktuell nicht verfügbar.'); }
  } finally { busy = false; }
}
$('copy-address').addEventListener('click',async () => {
  const address = state?.content.address; if (!address) return;
  try { await navigator.clipboard.writeText(address); showText('copy-feedback','Serveradresse kopiert!'); }
  catch (_) {
    const selection = window.getSelection(), range = document.createRange(); range.selectNodeContents($('address')); selection.removeAllRanges(); selection.addRange(range);
    showText('copy-feedback','Adresse markiert. Kopiere sie mit Strg+C oder durch langes Antippen.');
  }
});
document.addEventListener('visibilitychange',() => { if (!document.hidden) refresh(); });
setInterval(refresh,10000); setInterval(renderTime,1000); refresh();
