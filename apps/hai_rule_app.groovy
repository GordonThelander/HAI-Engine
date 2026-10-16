import hubitat.helper.RMUtils
import hubitat.helper.NetworkUtils
import groovy.transform.Field
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.Instant

definition(
name: 'HAI Rule Container (DEV)',
namespace: 'gordonthelander',
author: 'HAI',
description: 'Dev only. One HAI rule: it owns its definition, its subscriptions, its schedules and its state.',
category: 'Automations',
parent: 'gordonthelander:HAI Engine (DEV)',
iconUrl: '',
iconX2Url: '',
singleThreaded: true
)
preferences {
page(name: 'rulePage')
}
def rulePage() {
dynamicPage(name: 'rulePage', title: 'HAI Rule (Dev)', install: true, uninstall: false) {
if (state.editorLink) {
section {
paragraph "<a id='hai-edit' href='${state.editorLink}' style='font-size:1.15em;font-weight:600'>Open this rule in the editor</a>"
}
}
section {
paragraph state.editorLink
? 'Managed by HAI. Change it in the editor, and pause, stop or remove it there.'
: 'Managed by HAI. Change it in the editor, which you open from the HAI Engine (Dev) app page, and pause, stop or remove it there.'
input 'ruleDevices', 'capability.*', title: 'Devices this rule uses - HAI keeps this list matching the rule; change devices in the editor', multiple: true, required: false
paragraph ruleStatusText()
}
}
}
def haiEditorLink(String url) {
state.editorLink = url
return true
}
def installed() { ruleSelfSchedule() }
def updated() { ruleSelfSchedule() }
void ruleSelfSchedule() {
unschedule('haiSelfCheck')
Map rt = state.rt
List due = rt == null ? [] : ((Map) (rt.deadlines ?: [:])).values().findAll { ((Map) it).firedAt == null }.collect { ((Map) it).dueAt as Long }.findAll { it != null }
Long at = due.isEmpty() ? null : ((due.min() as long) + 30000L + 5000L)
if (at != null) runOnce(new Date(Math.max(at, now() + 1000L)), 'haiSelfCheck', [overwrite: true])
if (rt != null && ((rt.selfCheck == true) != (at != null) || rt.selfCheckAt != at)) {
rt.selfCheck = at != null
if (at != null) rt.selfCheckAt = at else rt.remove('selfCheckAt')
state.rt = rt
}
}
def haiSelfCheck() {
try {
Long armed = ((Map) state.rt)?.selfCheckAt as Long
if (armed != null && now() < armed - 60000L) {
unschedule('haiSelfCheck')
runOnce(new Date(armed), 'haiSelfCheck', [overwrite: true])
return
}
rtOverdue(rulePlat(), now(), null, null, true)
} catch (Exception e) {
log.warn "HAI Rule: self check failed: ${e.message}"
}
}
void ruleAnnounceIndex(String why) {
try {
Map rec = rtRule(rulePlat())
Map idx = [appId: app.id, ruleId: rec?.id, epoch: rec?.epoch, ruleHash: rec?.ruleHash,
name: ((Map) rec?.doc)?.name, paused: rec?.paused == true, why: why]
sendLocationEvent(name: RULE_INDEX_EVENT, value: app.id as String, isStateChange: true,
displayed: false, data: groovy.json.JsonOutput.toJson(idx),
descriptionText: "HAI rule ${app.id} ${why}".toString())
} catch (Exception e) {
log.warn "HAI Rule: index not announced: ${e.message}"
}
}
Map haiIndex() {
Map rec = rtRule(rulePlat())
if (rec == null) return [appId: app.id, empty: true]
return [appId: app.id, ruleId: rec.id, epoch: rec.epoch, ruleHash: rec.ruleHash,
name: ((Map) rec.doc)?.name, paused: rec.paused == true]
}
def uninstalled() {
unschedule()
unsubscribe()
try { removeAllInUseGlobalVar() } catch (Exception e) { }
}
Map ruleRetainedDoc(String epoch) {
Map rt = state.rt
if (rt == null || epoch == null) return null
Map docs = rt.docs
Object got = docs == null ? null : docs.get(epoch.toString())
return got instanceof Map ? got : null
}
Map ruleAdoptRetainedDoc(String epoch, Map doc) {
if (epoch == null || !(doc instanceof Map)) return [ok: false, code: 'BAD_ARGS']
Map rt = state.rt
if (rt == null) return [ok: false, code: 'NO_STATE']
Map docs = rt.docs
if (docs == null) { docs = [:]; rt.docs = docs }
docs.put(epoch.toString(), doc)
state.rt = rt
Map back = ((Map) ((Map) state.rt).docs).get(epoch.toString())
return back instanceof Map ? [ok: true] : [ok: false, code: 'NOT_STORED']
}
String ruleStatusText() {
Map rt = state.rt
if (rt == null) return 'No rule has been committed here yet. Open it in the editor and press Put on hub.'
Map rules = ((Map) (rt.world ?: [:])).rules ?: [:]
Map rec = rt.ruleId == null ? null : rules.get(rt.ruleId)
List rows = []
boolean paused = rec != null && rec.paused
boolean stopped = rec != null && rec.disabled
String running = stopped ? 'Stopped' : (paused ? 'Paused' : 'Running')
rows << ruleRow('Status', "${running}, version ${rec?.epoch ?: 0}".toString())
if (rt.firstCommittedAt) rows << ruleRow('Created', ruleWhen(rt.firstCommittedAt as long))
else rows << ruleRow('Created', 'not recorded')
Map committed = rt.committed
if (committed != null && committed.at) rows << ruleRow('Last changed', ruleWhen(committed.at as long))
Map lastRun = ruleLastRun(rt)
if (lastRun != null) {
String how = lastRun.how ? " - ${lastRun.how}".toString() : ' - still running'
rows << ruleRow('Last run', (ruleWhen(lastRun.at as long) + how).toString())
} else {
rows << ruleRow('Last run', 'not since this rule was last committed')
}
Long next = ruleNextRun(rt)
rows << ruleRow('Next run', next == null ? 'nothing scheduled' : ruleWhen(next))
if (rt.totalRuns != null) {
long runs = rt.totalRuns as long
if (rt.runsObservedSince) rows << ruleRow("Runs since ${ruleWhen(rt.runsObservedSince as long)}".toString(), runs.toString())
else rows << ruleRow('Runs', runs.toString())
} else {
rows << ruleRow('Runs', 'not counted before this version')
}
String hash = rt.activeHash ? (rt.activeHash as String).take(12) : 'none'
rows << ruleRow('Identity', "rule ${rt.ruleId ?: '-'}, hash ${hash}, ${rt.bytes ?: 0} bytes of state".toString())
return "<table style='border-collapse:collapse'>${rows.join('')}</table>".toString()
}
String ruleRow(String label, String value) {
return "<tr><td style='padding:2px 14px 2px 0;color:#666;white-space:nowrap'>${label}</td><td style='padding:2px 0'>${value}</td></tr>".toString()
}
String ruleWhen(Long ms) {
if (ms == null || ms <= 0L) return 'unknown'
try {
return new Date(ms).format('d MMM yyyy, HH:mm', location.timeZone ?: TimeZone.getDefault())
} catch (Exception e) {
return new Date(ms).toString()
}
}
Map ruleLastRun(Map rt) {
List runs = ((Map) (rt.trace ?: [:])).runs
if (runs == null || runs.isEmpty()) return null
Map last = runs[runs.size() - 1]
List head = last.head
Long at = head != null && !head.isEmpty() ? (((Map) head[0]).t as Long) : null
String how = null
for (List part : [last.head, last.tail]) {
if (part == null) continue
for (Object o : part) {
Map e = o
if (e.w == 'runEnded') how = e.how as String
else if (e.w == 'runCancelled') how = 'cancelled'
}
}
if (at == null) return null
return [at: at, how: how]
}
Long ruleNextRun(Map rt) {
Map deadlines = rt.deadlines
if (deadlines == null || deadlines.isEmpty()) return null
Long best = null
for (Object k : deadlines.keySet()) {
String key = k as String
int dot = key.lastIndexOf('.')
if (dot < 0) continue
String tail = key.substring(dot + 1)
if (!tail.isNumber()) continue
Long at = tail as Long
if (best == null || at < best) best = at
}
return best
}
Map rulePlat() {
return [
now : { Object u -> now() },
uptime : { Object u -> location.hub.uptime as long },
zone : { Object u -> location.timeZone?.ID },
sun : { Object date -> ruleSun(date as String) },
mode : { Object u -> location.mode?.toString() },
inventory : { Object u -> ruleInventory() },
device : { Object dev -> def d = ruleDevice(dev as String); d == null ? null : rtDeviceLite(d) },
selfCheck : { Object at -> unschedule('haiSelfCheck'); if (at instanceof Number) runOnce(new Date(Math.max((at as long), now() + 1000L)), 'haiSelfCheck', [overwrite: true]) },
currentState: { Object dev, Object attr -> ruleCurrent(dev as String, attr as String) },
currentFresh: { Object dev, Object attr -> ruleCurrent(dev as String, attr as String, true) },
driver : { Object dev -> ruleDevice(dev as String)?.typeName?.toString() },
command : { List a -> ruleCommand(a[0] as String, a[1] as String, a[2]) },
listen : { Object dev, Object attr -> ruleListen(dev as String, attr as String) },
unlistenAll : { Object u -> unsubscribe() },
timerAt : { List a -> runOnce(new Date(a[0] as long), a[1] as String, [data: a[2], overwrite: false]) },
unscheduleHandler: { Object h -> unschedule(h as String) },
timerIn : { List a -> runInMillis(a[0] as long, a[1] as String, [data: a[2], overwrite: false]) },
atomicGet : { Object k -> atomicState[k as String] },
atomicPut : { List a -> atomicState[a[0] as String] = a[1] },
sendNow : { Object u -> true },
idle : { Object b -> if ((state.haiIdle == true) != (b == true)) { if (b == true) state.haiIdle = true else state.remove('haiIdle') } },
loadState : { Object u -> state.rt },
saveState : { Map m -> state.rt = m },
execId : { Object u -> "${now()}-${UUID.randomUUID()}".toString() },
selfId : { Object u -> app.id?.toString() },
variable : { Object name -> def g = getGlobalVar(name as String); g == null ? null : [g.value, g.type?.toString()] },
setVariable : { List a -> setGlobalVar(a[0] as String, a[1]) },
announceInvoke: { Map call ->
sendLocationEvent(name: RT_INVOKE_EVENT, value: call.target as String, isStateChange: true, displayed: false,
data: groovy.json.JsonOutput.toJson([from: call.from, run: call.run, depth: call.depth, caller: call.caller, input: call.input]),
descriptionText: "HAI rule ${call.from} runs ${call.target}".toString())
},
announceControl: { Map call ->
sendLocationEvent(name: RT_CONTROL_EVENT, value: call.target as String, isStateChange: true, displayed: false,
data: groovy.json.JsonOutput.toJson([op: call.op, value: call.value, from: call.from, depth: call.depth]),
descriptionText: "HAI rule ${call.from}: ${call.op} ${call.target}".toString())
},
log : { Map line -> line.level == 'warn' ? log.warn(line.text as String) : log.info(line.text as String) },
setMode : { Object mode -> location.setMode(mode as String) },
setHsm : { Object op -> sendLocationEvent(name: 'hsmSetArm', value: op as String) },
announceReturn: { Map call ->
sendLocationEvent(name: RT_RETURN_EVENT, value: call.rule as String, isStateChange: true, displayed: false,
data: groovy.json.JsonOutput.toJson([run: call.run, value: call.value, how: call.how]),
descriptionText: "HAI rule answered ${call.rule}".toString())
},
announceCommitted: { Map n ->
sendLocationEvent(name: RT_COMMITTED_EVENT, value: n.ruleId as String, isStateChange: true, displayed: false,
data: groovy.json.JsonOutput.toJson((n ?: [:]) + [childId: app.id as String]),
descriptionText: "HAI rule ${n.ruleId} committed version ${n.epoch}".toString())
},
announcePaused: { Map call ->
sendLocationEvent(name: RT_PAUSED_EVENT, value: call.rule as String, isStateChange: true, displayed: false,
data: groovy.json.JsonOutput.toJson([paused: call.paused == true]),
descriptionText: "HAI rule ${call.rule} ${call.paused ? 'paused' : 'resumed'}".toString())
},
rmAction : { Map call -> RMUtils.sendAction((List) call.ids, call.action as String, app.label, '5.0') },
appDisable : { Map call ->
String body = groovy.json.JsonOutput.toJson([id: call.appId, disable: call.disable == true])
Map res = [:]
httpPost([uri: 'http://127.0.0.1:8080/installedapp/disable', contentType: 'application/json',
requestContentType: 'application/json', body: body, timeout: 20]) { resp ->
res = [status: resp?.status]
}
if (!(res.status in [200, 201, 202, 204])) {
throw new Exception("the hub refused to ${call.disable == true ? 'disable' : 'enable'} app ${call.appId} (HTTP ${res.status})")
}
return res
},
useVariables: { Object names ->
removeAllInUseGlobalVar()
if (names) addInUseGlobalVar((List) names)
},
net : { Map req -> ruleNet(req) },
file : { Map req -> ruleFile(req) },
readFile : { Object name ->
byte[] b = null
try { b = downloadHubFile(name as String) } catch (Exception e) { b = null }
b == null ? null : new String(b, 'UTF-8')
},
]
}
void ruleNet(Map req) {
Map data = [run: req.run, token: req.token]
if (req.kind == 'ping') {
runInMillis(1, 'haiPing', [data: data + [host: req.host], overwrite: false])
return
}
Map params = [uri: req.url, timeout: req.timeoutSeconds]
if (req.headers) params.headers = req.headers
if (req.method == 'post') {
params.requestContentType = req.contentType
params.body = req.body
asynchttpPost('haiHttpReply', params, data)
} else asynchttpGet('haiHttpReply', params, data)
}
void ruleFile(Map req) {
String name = req.name as String
if (req.op == 'delete') { deleteHubFile(name); return }
String text = (req.text ?: '') as String
if (req.op == 'append') {
byte[] had = null
try { had = downloadHubFile(name) } catch (Exception e) { had = null }
if (had != null) text = new String(had, 'UTF-8') + text
}
if (text.length() > NET_MAX_BODY_CHARS) throw new Exception("the file would be ${text.length()} characters, over ${NET_MAX_BODY_CHARS}".toString())
uploadHubFile(name, text.getBytes('UTF-8'))
}
def ruleDevice(String dev) {
String want = rtBareId(dev)
return ((settings.ruleDevices ?: []) as List).find { it?.id?.toString() == want }
}
void ruleListen(String dev, String attr) {
if (dev == RT_LOCATION) {
if (attr == 'mode') subscribe(location, 'mode', 'haiMode')
if (attr == RT_INVOKE_EVENT) subscribe(location, RT_INVOKE_EVENT, 'haiInvoked')
if (attr == RT_CONTROL_EVENT) subscribe(location, RT_CONTROL_EVENT, 'haiControlled')
if (attr == RT_PAUSED_EVENT) subscribe(location, RT_PAUSED_EVENT, 'haiRulePausedEvent')
if (attr == RT_SWITCHED_EVENT) subscribe(location, RT_SWITCHED_EVENT, 'haiRmSwitched')
if (attr == RT_RETURN_EVENT) subscribe(location, RT_RETURN_EVENT, 'haiReturned')
if (attr in ['hsmStatus', 'hsmAlert'] || attr in LOCATION_EVENTS) subscribe(location, attr, 'haiLocation')
return
}
if (dev.startsWith(HUB_VARIABLE_PREFIX)) {
subscribe(location, "variable:${dev.substring(HUB_VARIABLE_PREFIX.length())}".toString(), 'haiVariable')
return
}
def d = ruleDevice(dev)
if (d != null) subscribe(d, attr, 'haiEvent')
}
List ruleCurrent(String dev, String attr, boolean fresh = false) {
if (dev == RT_LOCATION) return attr == 'hsmStatus' && location.hsmStatus != null ? [location.hsmStatus.toString(), null] : null
def d = ruleDevice(dev)
if (d == null) return null
def st = null
if (fresh) { try { st = d.currentState(attr, true) } catch (Exception e) { st = d.currentState(attr) } }
else st = d.currentState(attr)
if (st == null) return null
return [st.value, st.date?.getTime()]
}
void ruleCommand(String dev, String cmd, List args) {
def d = ruleDevice(dev)
if (d == null) throw new Exception("device ${dev} is not held by this rule")
rtDriverCommand(d, cmd, args)
}
Map ruleInventory() {
Map inv = invNew()
((settings.ruleDevices ?: []) as List).each { d -> if (d != null) { Map row = rtDeviceRow(d, null); inv.devices[row.id] = row } }
location.modes.each { m -> inv.modes << m.name.toString() }
inv.currentMode = location.mode?.toString()
try { ((Map) inv.variables).putAll(schemaHubVariableRows(getAllGlobalVars())) } catch (Exception e) { }
return inv
}
Map ruleSun(String date) {
try {
def fmt = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm')
fmt.setTimeZone(location.timeZone)
Date noon = fmt.parse("${date} 12:00".toString())
Map s = getSunriseAndSunset(date: noon)
if (!s?.sunrise || !s?.sunset) return null
return [sunrise: s.sunrise.format('HH:mm', location.timeZone), sunset: s.sunset.format('HH:mm', location.timeZone)]
} catch (Exception e) {
return null
}
}
boolean ruleAsleep() { return state.haiIdle == true }
def haiEvent(evt) {
if (ruleAsleep()) return
Object number = null
try { number = evt.numericValue } catch (Exception e) { number = null }
Object at = null
try { at = evt.date?.getTime() } catch (Exception e) { at = null }
rtOnEvents(rulePlat(), rtEventsFrom(evt.deviceId, evt.name as String, evt.value, number, at, evt.id, evt.type))
}
def haiLocation(evt) {
if (ruleAsleep()) return
Object at = null
try { at = evt.date?.getTime() } catch (Exception e) { at = null }
rtOnEvents(rulePlat(), [rtLocationEvent(evt.name as String, evt.value, at, evt.id)])
}
def haiMode(evt) {
if (ruleAsleep()) return
Object at = null
try { at = evt.date?.getTime() } catch (Exception e) { at = null }
rtOnEvents(rulePlat(), [rtModeEvent(evt.value, at, evt.id)])
}
def haiVariable(evt) {
if (ruleAsleep()) return
String name = (evt.name as String).substring('variable:'.length())
Object at = null
try { at = evt.date?.getTime() } catch (Exception e) { at = null }
String type = null
try { type = getGlobalVar(name)?.type?.toString() } catch (Exception e) { type = null }
rtOnEvents(rulePlat(), [rtVariableEvent(name, evt.value, type, at, evt.id)])
}
def haiInvoked(evt) {
if (evt.value != state.rt?.ruleId) return
Map call = [:]
try { call = new groovy.json.JsonSlurper().parseText(evt.data as String) } catch (Exception e) { call = [:] }
rtOnInvoke(rulePlat(), evt.value as String, call)
}
def haiControlled(evt) {
if (evt.value != state.rt?.ruleId) return
Map call = [:]
try { call = new groovy.json.JsonSlurper().parseText(evt.data as String) } catch (Exception e) { call = [:] }
rtOnControl(rulePlat(), evt.value as String, call)
}
def haiReturned(evt) {
if (evt.value != state.rt?.ruleId) return
Map d = [:]
try { d = new groovy.json.JsonSlurper().parseText(evt.data as String) } catch (Exception e) { d = [:] }
rtOnReturn(rulePlat(), evt.value as String, d)
}
def haiRulePausedEvent(evt) {
if (evt.value == state.rt?.ruleId) return
Map d = [:]
try { d = new groovy.json.JsonSlurper().parseText(evt.data as String) } catch (Exception e) { d = [:] }
Long at = null
try { at = evt.date?.getTime() } catch (Exception e) { at = null }
rtOnRulePaused(rulePlat(), evt.value as String, d.paused, evt.id, at)
}
def haiRmSwitched(evt) {
Map d = [:]
try { d = new groovy.json.JsonSlurper().parseText(evt.data as String) } catch (Exception e) { return }
rtOnSwitched(rulePlat(), (d.pairs instanceof Map ? d.pairs : [:]))
}
void renameVariable(String oldName, String newName) {
rtRenameVariable(rulePlat(), oldName, newName)
}
def haiHttpReply(resp, Map data) {
Map reply
try {
int status = (resp.status ?: 0) as int
if (status >= 200 && status < 300) reply = [status: status, body: resp.data as String]
else reply = status > 0 ? [status: status] : [error: (resp.getErrorMessage() ?: 'no answer') as String]
} catch (Exception e) {
reply = [error: e.message]
}
rtOnNetReply(rulePlat(), data?.run, data?.token, reply)
}
def haiPing(Map data) {
Map reply
try {
def p = NetworkUtils.ping(data.host as String, 3)
reply = [transmitted: p.packetsTransmitted, received: p.packetsReceived, loss: p.packetLoss, rttMin: p.rttMin, rttAvg: p.rttAvg, rttMax: p.rttMax]
} catch (Exception e) {
reply = [error: e.message]
}
rtOnNetReply(rulePlat(), data?.run, data?.token, reply)
}
def haiTimer(Map data) { if (ruleAsleep()) return; rtOnTimer(rulePlat(), data?.token as String, 'timer') }
def haiContinue(Map data) { rtOnTimer(rulePlat(), data?.token as String, 'continuation') }
def haiCommit(Map data) { rtCommit(rulePlat(), data?.claimId as String) }
Map haiBuild() { return [build: RT_CHILD_BUILD, version: RT_VERSION] }
Map haiSetup(List devices, List allow, Map purposes, Map options = [:]) {
List ok = ((devices ?: []) as List).collect { rtBareId(it) }.findAll { ((allow ?: []) as List).contains(it) }
List have = ((settings.ruleDevices ?: []) as List).collect { rtBareId(it.id?.toString()) }.sort()
if (have != ok.sort()) app.updateSetting('ruleDevices', [type: 'capability.*', value: ok])
Map mine = ((purposes ?: [:]) as Map).findAll { k, v -> ok.contains(rtBareId(k)) }
return rtSetAllow(rulePlat(), ok, mine, options?.writableVariables, options?.rules, options?.paused, options?.modeAllowed == true,
options?.switched instanceof Map ? options.switched : null)
}
Map haiStage(Map doc, String ruleHash, String challengeId, String claimId, Object baseEpoch, Object expiresAt,
Object startStopped = false) {
return rtStage(rulePlat(), doc, ruleHash, challengeId, claimId, baseEpoch as int, expiresAt as long,
startStopped instanceof String ? startStopped : startStopped == true)
}
Map haiLifecycle(String op, String runId) { return rtLifecycle(rulePlat(), op, runId) }
Map haiOverdue(Object parentNow, List allow, Object lastWatchdogAt, Object allowHash = null) {
return rtWatchdogCall(rulePlat(), parentNow as long, allow, lastWatchdogAt, allowHash)
}
Map haiRuntimeState() { return rtState(rulePlat()) }
Map haiRecent() { return rtRecentView(rulePlat()) }
Map haiResumeStaged() { return rtResumeStaged(rulePlat()) }
Map haiCommitNow(String claimId) { return rtCommitDirect(rulePlat(), claimId) }
Map haiDiscardStaged(String reason) { return rtDiscard(rulePlat(), reason) }
Map haiReconcile() { return rtReconcile(rulePlat()) }
Map haiDevWithhold(Object n) { return rtDevWithhold(rulePlat(), n as int) }
@Field static final int LIMIT_PARALLEL_RUNS = 4
@Field static final int LIMIT_REPEAT_PASSES = 1000
@Field static final long LIMIT_REPEAT_MIN_MS = 1000L
@Field static final int RT_TRACE_BYTES = 6144
@Field static final int RT_MIN_RUN_BYTES = 1024
@Field static final int RT_LEDGER_RESERVE_BYTES = 3072
@Field static final int RT_NONTERMINAL_RUNS = 8
@Field static final int RT_WORKING_BYTES = 29971
@Field static final int RT_STATE_BYTES = 62739
String jsonCanonicalise(Object value, Map defaults = [:]) {
StringBuilder out = new StringBuilder()
jsonWrite(value, out, defaults, '')
return out.toString()
}
String jsonHash(Object value, Map defaults = [:]) {
byte[] bytes = jsonCanonicalise(value, defaults).getBytes('UTF-8')
return MessageDigest.getInstance('SHA-256').digest(bytes).collect { String.format('%02x', it) }.join('')
}
void jsonWrite(Object value, StringBuilder out, Map defaults, String path) {
if (value instanceof Map) {
out.append('{')
List keys = new ArrayList(((Map) value).keySet())
Collections.sort(keys)
boolean first = true
keys.each { Object k ->
String key = (String) k
String childPath = path.isEmpty() ? key : (path + '.' + key)
Object child = ((Map) value).get(key)
if (defaults.containsKey(childPath) && defaults.get(childPath) == child) return
if (!first) out.append(',')
first = false
jsonWriteString(key, out)
out.append(':')
jsonWrite(child, out, defaults, childPath)
}
out.append('}')
return
}
if (value instanceof List) {
out.append('[')
boolean first = true
((List) value).each { Object child ->
if (!first) out.append(',')
first = false
jsonWrite(child, out, defaults, path)
}
out.append(']')
return
}
if (value instanceof String) { jsonWriteString((String) value, out); return }
if (value instanceof Boolean) { out.append(((Boolean) value) ? 'true' : 'false'); return }
if (value instanceof BigDecimal) { out.append(((BigDecimal) value).stripTrailingZeros().toPlainString()); return }
if (value instanceof Integer || value instanceof Long) { out.append(value.toString()); return }
throw new Exception('not canonicalisable: ' + (value == null ? 'null' : value.toString()))
}
void jsonWriteString(String value, StringBuilder out) {
out.append('"')
for (int i = 0; i < value.length(); i++) {
char c = value.charAt(i)
if (c == ('"' as char)) { out.append('\\"') }
else if (c == ('\\' as char)) { out.append('\\\\') }
else if (c == ('\b' as char)) { out.append('\\b') }
else if (c == ('\f' as char)) { out.append('\\f') }
else if (c == ('\n' as char)) { out.append('\\n') }
else if (c == ('\r' as char)) { out.append('\\r') }
else if (c == ('\t' as char)) { out.append('\\t') }
else if (c < 0x20) { out.append(String.format('\\u%04x', (int) c)) }
else { out.append(c) }
}
out.append('"')
}
@Field static final String LOGIC_TRUE = 'true'
@Field static final String LOGIC_FALSE = 'false'
@Field static final String LOGIC_UNKNOWN = 'unknown'
@Field static final String LOGIC_NOT_EVALUATED = 'notEvaluated'
String logicNot(String v) {
if (v == LOGIC_TRUE) return LOGIC_FALSE
if (v == LOGIC_FALSE) return LOGIC_TRUE
return LOGIC_UNKNOWN
}
String logicAnd(List values) {
if (values.isEmpty()) return LOGIC_TRUE
boolean sawUnknown = false
for (String v : values) {
if (v == LOGIC_FALSE) return LOGIC_FALSE
if (v == LOGIC_UNKNOWN) sawUnknown = true
}
return sawUnknown ? LOGIC_UNKNOWN : LOGIC_TRUE
}
String logicOr(List values) {
if (values.isEmpty()) return LOGIC_FALSE
boolean sawUnknown = false
for (String v : values) {
if (v == LOGIC_TRUE) return LOGIC_TRUE
if (v == LOGIC_UNKNOWN) sawUnknown = true
}
return sawUnknown ? LOGIC_UNKNOWN : LOGIC_FALSE
}
String logicXor(List values) {
if (values.any { it == LOGIC_UNKNOWN }) return LOGIC_UNKNOWN
return values.count { it == LOGIC_TRUE } % 2 == 1 ? LOGIC_TRUE : LOGIC_FALSE
}
String logicCount(List values, int min, Integer max) {
int t = values.count { it == LOGIC_TRUE } as int
int u = values.count { it == LOGIC_UNKNOWN || it == LOGIC_NOT_EVALUATED } as int
if (t + u < min || (max != null && t > max)) return LOGIC_FALSE
if (t >= min && (max == null || t + u <= max)) return LOGIC_TRUE
return LOGIC_UNKNOWN
}
String logicCountNode(Map node, List values) {
int min = node.min instanceof Number ? ((Number) node.min).intValue() : 1
Integer max = node.max instanceof Number ? ((Number) node.max).intValue() : null
return logicCount(values, min, max)
}
Map logicEvaluate(Map node, Closure leaf) {
List trace = []
String value = logicEvalNode(node, 'conditions', leaf, trace, true)
return [value: value, trace: trace]
}
Map logicNode(String path, String type, String value, Object saw = null) {
Map m = [path: path, type: type, value: value]
if (saw != null) m.saw = saw
return m
}
String logicEvalNode(Map node, String path, Closure leaf, List trace, boolean live) {
if (node == null) return LOGIC_UNKNOWN
String type = node.type as String
if (!live) {
trace << logicNode(path, type, LOGIC_NOT_EVALUATED)
logicMarkUnread(node, path, trace)
return LOGIC_NOT_EVALUATED
}
if (type == 'all' || type == 'any' || type == 'xor' || type == 'count') {
List members = (node.of ?: [])
List values = []
boolean decided = false
String decidedBy = (type == 'all') ? LOGIC_FALSE : LOGIC_TRUE
members.eachWithIndex { Object c, int i ->
String childPath = "${path}.of.${i}".toString()
String v = logicEvalNode((Map) c, childPath, leaf, trace, !decided)
if (!decided) {
values << v
if (v == decidedBy && type != 'xor' && type != 'count') decided = true
}
}
String result = type == 'all' ? logicAnd(values) : type == 'any' ? logicOr(values) :
type == 'count' ? logicCountNode(node, values) : logicXor(values)
trace << logicNode(path, type, result)
return result
}
if (type == 'not') {
String v = logicEvalNode((Map) node.of, "${path}.of".toString(), leaf, trace, true)
String result = logicNot(v)
trace << logicNode(path, type, result)
return result
}
List got = leaf(node, path)
String value = got[0] as String
trace << logicNode(path, type, value, got.size() > 1 ? got[1] : null)
return value
}
void logicMarkUnread(Map node, String path, List trace) {
if (node.of instanceof List) {
((List) node.of).eachWithIndex { Object c, int i ->
Map child = c
String p = "${path}.of.${i}".toString()
trace << logicNode(p, child.type as String, LOGIC_NOT_EVALUATED)
logicMarkUnread(child, p, trace)
}
} else if (node.of instanceof Map) {
Map child = node.of
String p = "${path}.of".toString()
trace << logicNode(p, child.type as String, LOGIC_NOT_EVALUATED)
logicMarkUnread(child, p, trace)
}
}
@Field static final String TIME_EXACT = 'exact'
@Field static final String TIME_SHIFTED = 'shifted'
@Field static final String TIME_AMBIGUOUS = 'ambiguous'
@Field static final String TIME_UNRESOLVABLE = 'unresolvable'
@Field static final int TIME_MAX_GAP_MINUTES = 180
Map timeResolveOccurrence(LocalDateTime intended, String zoneName) {
ZoneId zone = ZoneId.of(zoneName)
ZonedDateTime zdt = intended.atZone(zone)
if (zdt.toLocalDateTime() != intended) {
LocalDateTime probe = intended.withSecond(0).withNano(0)
for (int i = 0; i < TIME_MAX_GAP_MINUTES; i++) {
probe = probe.plusMinutes(1)
ZonedDateTime candidate = probe.atZone(zone)
if (candidate.toLocalDateTime() == probe) {
return [instant: candidate, status: TIME_SHIFTED, intended: intended]
}
}
return [instant: null, status: TIME_UNRESOLVABLE, intended: intended,
why: "no valid local time within ${TIME_MAX_GAP_MINUTES} minutes after ${intended} in ${zoneName}".toString()]
}
ZonedDateTime earlier = zdt.withEarlierOffsetAtOverlap()
ZonedDateTime later = zdt.withLaterOffsetAtOverlap()
if (earlier.getOffset() != later.getOffset()) {
return [instant: earlier, status: TIME_AMBIGUOUS, intended: intended, suppressedSecond: later]
}
return [instant: zdt, status: TIME_EXACT, intended: intended]
}
String timeOccurrenceId(String scheduleRevision, LocalDateTime intended, ZonedDateTime resolved) {
return "${scheduleRevision}|${intended}|${resolved.toInstant().toEpochMilli()}".toString()
}
Map timeResolveWindow(LocalDate date, LocalTime from, LocalTime to, String zoneName) {
List notes = []
if (from == to) {
return [empty: true, overnight: false, notes: ['start and end are the same moment']]
}
boolean overnight = to.isBefore(from)
Map startR = timeResolveOccurrence(LocalDateTime.of(date, from), zoneName)
LocalDate endDate = overnight ? date.plusDays(1) : date
Map endR = timeResolveOccurrence(LocalDateTime.of(endDate, to), zoneName)
if (startR.status == TIME_UNRESOLVABLE || endR.status == TIME_UNRESOLVABLE) {
return [empty: false, unresolvable: true, overnight: overnight,
notes: [startR.why ?: endR.why]]
}
if (startR.status != TIME_EXACT) notes << "start ${startR.status}".toString()
if (endR.status != TIME_EXACT) notes << "end ${endR.status}".toString()
return [from: startR.instant, to: endR.instant, empty: false, overnight: overnight, notes: notes]
}
boolean timeInWindow(ZonedDateTime at, Map window) {
if (window.empty || window.unresolvable) return false
long t = at.toInstant().toEpochMilli()
long a = ((ZonedDateTime) window.from).toInstant().toEpochMilli()
long b = ((ZonedDateTime) window.to).toInstant().toEpochMilli()
return t >= a && t < b
}
@Field static final List TIME_CRON_MONTHS = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']
@Field static final List TIME_CRON_DAYS = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']
Map timeCronParse(Object text) {
if (!(text instanceof String)) return [error: 'a periodic schedule needs a cron string']
List parts = ((String) text).trim().toUpperCase().split(/\s+/) as List
if (parts.size() < 6 || parts.size() > 7) return [error: 'a cron string has 6 or 7 fields: second minute hour day-of-month month day-of-week [year]']
Map out = [:]
List spec = [['sec', 0, 59, null, 'second'], ['min', 0, 59, null, 'minute'], ['hour', 0, 23, null, 'hour'], ['month', 1, 12, TIME_CRON_MONTHS, 'month']]
List idx = [0, 1, 2, 4]
for (int i = 0; i < 4; i++) {
Object f = timeCronField((String) parts[idx[i] as int], spec[i][1] as int, spec[i][2] as int, spec[i][3], 1)
if (f instanceof String) return [error: "${spec[i][4]}: ${f}".toString()]
out.put(spec[i][0], f)
}
if (parts.size() == 7) {
Object y = timeCronField((String) parts[6], 1970, 2199, null, 0)
if (y instanceof String) return [error: "year: ${y}".toString()]
out.year = y
}
String dom = parts[3] as String, dow = parts[5] as String
boolean domAny = dom in ['*', '?'], dowAny = dow in ['*', '?']
if (!domAny && !dowAny) return [error: 'choose days of the month or days of the week, not both (put ? in the other)']
if (!domAny) {
if (dom == 'L') out.dom = [last: 0]
else if (dom ==~ /L-\d{1,2}/) out.dom = [last: (dom.substring(2) as int)]
else if (dom == 'LW') out.dom = [lastWeekday: true]
else if (dom ==~ /\d{1,2}W/) {
int n = dom[0..-2] as int
if (n < 1 || n > 31) return [error: 'day of month: the day must be 1 to 31']
out.dom = [nearWeekday: n]
} else {
Object f = timeCronField(dom, 1, 31, null, 0)
if (f instanceof String) return [error: "day of month: ${f}".toString()]
out.dom = [days: f]
}
}
if (!dowAny) {
java.util.regex.Matcher nth = dow =~ /^([A-Z]{3}|\d)#([1-5])$/
java.util.regex.Matcher last = dow =~ /^([A-Z]{3}|\d)L$/
if (nth.matches() || last.matches()) {
java.util.regex.Matcher m = nth.matches() ? nth : last
Object d = timeCronField(m.group(1), 1, 7, TIME_CRON_DAYS, 1)
if (d instanceof String) return [error: "day of week: ${d}".toString()]
out.dow = nth.matches() ? [nth: (m.group(2) as int), day: ((List) d)[0]] : [lastOf: ((List) d)[0]]
} else {
Object f = timeCronField(dow, 1, 7, TIME_CRON_DAYS, 1)
if (f instanceof String) return [error: "day of week: ${f}".toString()]
out.dow = [days: f]
}
}
return out
}
Object timeCronField(String s, int lo, int hi, List names, int nameBase) {
if (s == '*' || s == '?') return (lo..hi).toList()
Set vals = [] as Set
for (String part : s.split(',')) {
java.util.regex.Matcher m = part =~ /^(\*|[A-Z0-9]+)(?:-([A-Z0-9]+))?(?:\/(\d+))?$/
if (!m.matches()) return "'${part}' is not understood".toString()
Integer a = m.group(1) == '*' ? lo : timeCronValue(m.group(1), names, nameBase)
Integer b = m.group(2) != null ? timeCronValue(m.group(2), names, nameBase) : (m.group(1) == '*' || m.group(3) != null ? hi : a)
int step = m.group(3) != null ? (m.group(3) as int) : 1
if (a == null || b == null) return "'${part}' is not understood".toString()
if (a < lo || b > hi || a > b) return "'${part}' is outside ${lo} to ${hi}".toString()
if (step < 1) return "'${part}' needs a step of at least 1".toString()
for (int v = a; v <= b; v += step) vals << v
}
return vals.toList().sort()
}
Integer timeCronValue(String s, List names, int nameBase) {
if (s ==~ /\d+/) return s as int
int i = names ? names.indexOf(s) : -1
return i < 0 ? null : i + nameBase
}
boolean timeCronDay(Map c, LocalDate d) {
if (!((List) c.month).contains(d.getMonthValue())) return false
if (c.year != null && !((List) c.year).contains(d.getYear())) return false
int len = d.lengthOfMonth()
if (c.dom != null) {
Map m = c.dom
if (m.days != null) return ((List) m.days).contains(d.getDayOfMonth())
if (m.last != null) return d.getDayOfMonth() == len - (m.last as int)
int target = m.lastWeekday ? len : Math.min(m.nearWeekday as int, len)
LocalDate t = d.withDayOfMonth(target)
int wd = t.getDayOfWeek().getValue()
if (wd == 6) t = target == 1 ? t.plusDays(2) : t.minusDays(1)
else if (wd == 7) t = target == len ? t.minusDays(2) : t.plusDays(1)
return t == d
}
if (c.dow != null) {
Map m = c.dow
int q = d.getDayOfWeek().getValue() % 7 + 1
if (m.days != null) return ((List) m.days).contains(q)
if (m.nth != null) return q == (m.day as int) && (int) ((d.getDayOfMonth() - 1) / 7) + 1 == (m.nth as int)
return q == (m.lastOf as int) && d.getDayOfMonth() + 7 > len
}
return true
}
LocalDateTime timeCronNext(Map c, LocalDateTime after) {
LocalDate d = after.toLocalDate()
LocalTime a = after.toLocalTime()
LocalDate day = d
for (int i = 0; i < 1900; i++) {
if (i > 0) day = day.plusDays(1)
if (c.year != null && !((List) c.year).contains(day.getYear())) {
Object ny = ((List) c.year).find { (it as int) > day.getYear() }
if (ny == null) return null
day = LocalDate.of(ny as int, 1, 1)
}
if (!timeCronDay(c, day)) continue
boolean first = day == d
for (Object ho : c.hour) {
int h = ho as int
if (first && h < a.getHour()) continue
for (Object mo : c.min) {
int mi = mo as int
if (first && h == a.getHour() && mi < a.getMinute()) continue
for (Object so : c.sec) {
int s = so as int
if (first && h == a.getHour() && mi == a.getMinute() && s <= a.getSecond()) continue
return LocalDateTime.of(day, LocalTime.of(h, mi, s))
}
}
}
}
return null
}
@Field static final Map SCHEMA_DEFAULTS = [
'rerunPolicy' : 'restart',
'onUnknown' : 'block',
'ifUnknown' : 'stop',
'errorPolicy' : 'stopRun',
'enabled' : true,
'initialEvaluation': false,
'conditionTiming' : 'atTrigger',
]
@Field static final List CAPTURE_ATTRIBUTES = ['switch', 'level', 'colorTemperature', 'hue', 'saturation', 'colorMode', 'valve', 'lock',
'position', 'speed', 'thermostatMode', 'heatingSetpoint', 'coolingSetpoint', 'thermostatFanMode']
@Field static final Map TIME_UNITS = [seconds: 1L, minutes: 60L, hours: 3600L, days: 86400L]
@Field static final List MATH_BINARY = ['+', '-', '*', '/', '%']
@Field static final List CONDITION_TYPES = ['all', 'any', 'not', 'deviceAttribute', 'variable', 'mode', 'timeWindow', 'triggeredBy', 'rulePaused', 'waitOutcome',
'xor', 'count', 'dayOfWeek', 'dateRange', 'timeSince', 'hsmStatus']
@Field static final int REGEX_MAX_CHARS = 200
@Field static final int REGEX_MAX_SUBJECT = 4000
@Field static final String REGEX_NESTED_QUANTIFIER = '\\([^()]*[*+][^()]*\\)[*+]'
@Field static final List EVENT_TYPES = ['physical', 'digital']
@Field static final String LOCATION_DEVICE = 'location'
@Field static final List LOCATION_EVENTS = ['systemStart', 'severeLoad', 'zigbeeOff', 'zigbeeOn', 'zwaveCrashed', 'sunrise', 'sunset', 'sunriseTime', 'sunsetTime', 'lowMemory']
@Field static final List CORE_DAY_NAMES = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun']
@Field static final Map FIELD_RANGES = [
delaySeconds : [1, 604800],
timeoutSeconds: [1, 86400],
durationSeconds: [1, 86400],
freshSeconds : [1, 86400],
buttonNumber : [1, 100],
offsetMinutes : [-720, 720],
repeatSeconds : [1, 86400],
repeatTimes : [1, 1000],
netTimeoutSeconds: [1, 60],
rssItems : [1, 20],
flashSeconds : [1, 60],
fadeSeconds : [1, 86400],
]
@Field static final int FADE_MAX_STEPS = 100
@Field static final int NET_DEFAULT_TIMEOUT_SECONDS = 10
@Field static final int KEEP_MAX_CHARS = 1024
@Field static final int NET_MAX_BODY_CHARS = 65536
@Field static final Map CATALOGUE = { Map dflt, Map diffs ->
Map out = [:]
diffs.each { k, v -> Map e = new LinkedHashMap(dflt); e.putAll((Map) v); out.put(k, e) }
out
}([args: [], shape: 'absolute', contextual: false, reads: [], ack: 'attribute', emits: true, repeat: 'noChange', retry: false, risk: 'none'], [
'on': [writes: ['switch'], requires: ['Switch'], retry: true],
'off': [writes: ['switch'], requires: ['Switch'], retry: true],
'toggle': [shape: 'toggling', contextual: true, writes: ['switch'], reads: ['switch'], requires: ['Switch'], repeat: 'inverts'],
'setLevel': [args: [[type: 'number', min: 0, max: 100, required: true], [type: 'number', min: 0, max: 3600, required: false]], writes: ['level', 'switch'], requires: ['SwitchLevel'], repeat: 'restartsRamp', retry: true],
'setColorTemperature': [args: [[type: 'integer', min: 1000, max: 30000, required: true], [type: 'number', min: 0, max: 100, required: false], [type: 'number', min: 0, max: 3600, required: false]], writes: ['colorTemperature'], requires: ['ColorTemperature'], retry: true],
'lock': [writes: ['lock'], requires: ['Lock'], risk: 'access'],
'unlock': [writes: ['lock'], requires: ['Lock'], risk: 'access'],
'open': [writes: ['door'], requires: ['GarageDoorControl'], risk: 'motion'],
'close': [writes: ['door'], requires: ['GarageDoorControl'], risk: 'motion'],
'siren': [writes: ['alarm'], requires: ['Alarm'], risk: 'alarm'],
'alarmOff': [writes: ['alarm'], requires: ['Alarm'], risk: 'alarm'],
'strobe': [writes: ['alarm'], requires: ['Alarm'], risk: 'alarm'],
'both': [writes: ['alarm'], requires: ['Alarm'], risk: 'alarm'],
'push': [args: [[type: 'integer', min: 1, max: 100, required: true]], shape: 'none', contextual: true, writes: [], requires: ['PushableButton'], ack: 'none', repeat: 'repeats'],
'hold': [args: [[type: 'integer', min: 1, max: 100, required: true]], shape: 'none', contextual: true, writes: [], requires: ['HoldableButton'], ack: 'none', repeat: 'repeats'],
'doubleTap': [args: [[type: 'integer', min: 1, max: 100, required: true]], shape: 'none', contextual: true, writes: [], requires: ['DoubleTapableButton'], ack: 'none', repeat: 'repeats'],
'release': [args: [[type: 'integer', min: 1, max: 100, required: true]], shape: 'none', contextual: true, writes: [], requires: ['ReleasableButton'], ack: 'none', repeat: 'repeats'],
'deviceNotification': [args: [[type: 'string', maxChars: 1024, required: true]], shape: 'none', writes: [], requires: ['Notification'], ack: 'none', emits: false, repeat: 'repeats'],
'refresh': [shape: 'none', writes: [], requires: ['Refresh'], ack: 'none', repeat: 'repeats'],
'speak': [args: [[type: 'string', maxChars: 1024, required: true], [type: 'integer', min: 0, max: 100, required: false]], shape: 'none', writes: [], requires: ['SpeechSynthesis'], ack: 'none', emits: false, repeat: 'repeats'],
'setVolume': [args: [[type: 'integer', min: 0, max: 100, required: true]], writes: ['volume'], requires: ['AudioVolume'], retry: true],
'initialize': [shape: 'none', writes: [], requires: ['Initialize'], ack: 'none', emits: false, repeat: 'repeats'],
'mute': [writes: ['mute'], requires: ['AudioVolume'], retry: true],
'unmute': [writes: ['mute'], requires: ['AudioVolume'], retry: true],
'beep': [shape: 'none', writes: [], requires: ['Tone'], ack: 'none', emits: false, repeat: 'repeats'],
'playSound': [args: [[type: 'integer', min: 1, max: 100, required: true]], shape: 'none', writes: [], requires: ['Chime'], ack: 'none', emits: false, repeat: 'repeats'],
'setColor': [args: [[type: 'number', min: 0, max: 100, required: true], [type: 'number', min: 0, max: 100, required: true], [type: 'number', min: 0, max: 100, required: false]], writes: ['hue', 'saturation'], requires: ['ColorControl'], retry: true],
'toggleColorTemperature': [args: [[type: 'integer', min: 1000, max: 30000, required: true], [type: 'number', min: 0, max: 100, required: false]], shape: 'toggling', contextual: true, writes: ['colorTemperature', 'switch'], reads: ['switch'], requires: ['Switch', 'ColorTemperature'], repeat: 'inverts'],
'toggleColor': [args: [[type: 'number', min: 0, max: 100, required: true], [type: 'number', min: 0, max: 100, required: true], [type: 'number', min: 0, max: 100, required: false]], shape: 'toggling', contextual: true, writes: ['hue', 'saturation', 'switch'], reads: ['switch'], requires: ['Switch', 'ColorControl'], repeat: 'inverts'],
'flash': [args: [[type: 'integer', min: 100, max: 10000, required: false]], shape: 'none', writes: [], requires: [], mustOffer: true, ack: 'none', repeat: 'repeats'],
'adjustLevel': [args: [[type: 'number', min: -100, max: 100, required: true], [type: 'number', min: 0, max: 3600, required: false]], shape: 'relative', contextual: true, writes: ['level', 'switch'], reads: ['level'], requires: ['SwitchLevel'], repeat: 'repeats'],
'toggleLevel': [args: [[type: 'number', min: 1, max: 100, required: true]], shape: 'toggling', contextual: true, writes: ['level', 'switch'], reads: ['switch'], requires: ['Switch', 'SwitchLevel'], repeat: 'inverts'],
'startLevelChange': [args: [[type: 'string', values: ['up', 'down'], required: true]], shape: 'none', writes: [], requires: ['ChangeLevel'], ack: 'none', repeat: 'repeats'],
'stopLevelChange': [shape: 'none', writes: [], requires: ['ChangeLevel'], ack: 'none', repeat: 'repeats'],
'openShade': [writes: ['windowShade'], requires: ['WindowShade'], retry: true],
'closeShade': [writes: ['windowShade'], requires: ['WindowShade'], retry: true],
'setPosition': [args: [[type: 'integer', min: 0, max: 100, required: true]], writes: ['position'], requires: ['WindowShade'], retry: true],
'stopPositionChange': [shape: 'none', writes: [], requires: ['WindowShade'], ack: 'none', repeat: 'repeats'],
'openValve': [writes: ['valve'], requires: ['Valve'], retry: true],
'closeValve': [writes: ['valve'], requires: ['Valve'], retry: true],
'setSpeed': [args: [[type: 'string', values: ['low', 'medium-low', 'medium', 'medium-high', 'high', 'on', 'off', 'auto'], required: true]], writes: ['speed'], requires: ['FanControl'], retry: true],
'cycleSpeed': [shape: 'toggling', contextual: true, writes: ['speed'], reads: ['speed'], requires: ['FanControl'], ack: 'none', repeat: 'repeats'],
'setHeatingSetpoint': [args: [[type: 'number', min: 0, max: 120, required: true]], writes: ['heatingSetpoint'], requires: ['Thermostat'], retry: true],
'setCoolingSetpoint': [args: [[type: 'number', min: 0, max: 120, required: true]], writes: ['coolingSetpoint'], requires: ['Thermostat'], retry: true],
'adjustHeatingSetpoint': [args: [[type: 'number', min: -20, max: 20, required: true]], shape: 'relative', contextual: true, writes: ['heatingSetpoint'], reads: ['heatingSetpoint'], requires: ['Thermostat'], repeat: 'repeats'],
'adjustCoolingSetpoint': [args: [[type: 'number', min: -20, max: 20, required: true]], shape: 'relative', contextual: true, writes: ['coolingSetpoint'], reads: ['coolingSetpoint'], requires: ['Thermostat'], repeat: 'repeats'],
'setThermostatMode': [args: [[type: 'string', values: ['auto', 'off', 'heat', 'emergency heat', 'cool'], required: true]], writes: ['thermostatMode'], requires: ['Thermostat'], retry: true],
'setThermostatFanMode': [args: [[type: 'string', values: ['auto', 'on', 'circulate'], required: true]], writes: ['thermostatFanMode'], requires: ['Thermostat'], retry: true],
'play': [writes: ['status'], requires: ['MusicPlayer']],
'pause': [writes: ['status'], requires: ['MusicPlayer']],
'stop': [writes: ['status'], requires: ['MusicPlayer']],
'nextTrack': [shape: 'none', writes: [], requires: ['MusicPlayer'], ack: 'none', repeat: 'repeats'],
'previousTrack': [shape: 'none', writes: [], requires: ['MusicPlayer'], ack: 'none', repeat: 'repeats'],
'playTrack': [args: [[type: 'string', maxChars: 1024, required: true]], shape: 'none', writes: [], requires: [], mustOffer: true, ack: 'none', repeat: 'repeats'],
'restoreTrack': [args: [[type: 'string', maxChars: 1024, required: true]], shape: 'none', writes: [], requires: ['MusicPlayer'], ack: 'none', repeat: 'repeats'],
'resumeTrack': [args: [[type: 'string', maxChars: 1024, required: true]], shape: 'none', writes: [], requires: ['MusicPlayer'], ack: 'none', repeat: 'repeats'],
'playText': [args: [[type: 'string', maxChars: 1024, required: true]], shape: 'none', writes: [], requires: ['MusicPlayer'], ack: 'none', emits: false, repeat: 'repeats'],
'volumeUp': [shape: 'none', writes: [], requires: ['AudioVolume'], ack: 'none', repeat: 'repeats'],
'volumeDown': [shape: 'none', writes: [], requires: ['AudioVolume'], ack: 'none', repeat: 'repeats'],
'togglePlay': [shape: 'toggling', contextual: true, writes: ['status'], reads: ['status'], requires: ['MusicPlayer'], repeat: 'inverts'],
'toggleMute': [shape: 'toggling', contextual: true, writes: ['mute'], reads: ['mute'], requires: ['AudioVolume'], repeat: 'inverts'],
'setTrack': [args: [[type: 'string', maxChars: 1024, required: true]], shape: 'none', writes: [], requires: ['MusicPlayer'], ack: 'none', repeat: 'repeats'],
'poll': [shape: 'none', writes: [], requires: ['Polling'], ack: 'none', repeat: 'repeats'],
])
Map schemaOperation(String command) { return CATALOGUE.get(command) }
@Field static final String HUB_VARIABLE_PREFIX = 'hv:'
@Field static final String RULE_VARIABLE_PREFIX = 'rv:'
@Field static final String PRIVATE_BOOLEAN_ID = 'rv:privateBoolean'
@Field static final String RULE_INPUT_ID = 'rv:input'
@Field static final String RULE_STATE_PREFIX = 'rule:'
@Field static final String RULE_PAUSED_ATTRIBUTE = 'paused'
@Field static final Map HUB_VARIABLE_TYPES = [integer: 'number', bigdecimal: 'number', boolean: 'boolean',
string: 'string', datetime: 'datetime']
Map schemaHubVariableRows(Map all, Collection writable = null) {
Map out = [:]
((Map) (all ?: [:])).each { k, v ->
String name = k.toString()
String hubType = (((Map) v)?.type ?: 'string').toString().toLowerCase()
Map row = [id: HUB_VARIABLE_PREFIX + name, name: name, type: HUB_VARIABLE_TYPES.get(hubType) ?: 'string',
scope: 'hub', hubType: hubType, value: ((Map) v)?.value]
if (hubType == 'integer') row.integer = true
row.writable = writable == null || writable.contains(name)
Object dev = ((Map) v)?.deviceId
if (dev != null && dev.toString() != '' && dev.toString() != '0') row.connector = "dev:${dev}".toString()
out.put(row.id, row)
}
return out
}
Object schemaHubValue(String hubType, Object v) {
if (v == null) return null
switch (hubType) {
case 'integer':
try { BigDecimal d = new BigDecimal(v.toString()); return d.stripTrailingZeros().scale() <= 0 ? d.intValueExact() : null }
catch (Exception e) { return null }
case 'bigdecimal':
try { return new BigDecimal(v.toString()) } catch (Exception e) { return null }
case 'boolean':
String b = v.toString().toLowerCase()
return b == 'true' ? true : b == 'false' ? false : null
default:
return v.toString()
}
}
@Field static final Map COMMAND_BASIS = [
'toggle' : ['on', 'off'],
'alarmOff': ['off'],
'adjustLevel': ['setLevel'], 'toggleLevel': ['off', 'setLevel'], 'toggleColor': ['off', 'setColor'],
'toggleColorTemperature': ['off', 'setColorTemperature'],
'togglePlay': ['play', 'pause'], 'toggleMute': ['mute', 'unmute'],
'openShade': ['open'], 'closeShade': ['close'], 'openValve': ['open'], 'closeValve': ['close'],
'adjustHeatingSetpoint': ['setHeatingSetpoint'], 'adjustCoolingSetpoint': ['setCoolingSetpoint'],
]
String schemaFileProblem(Object name) {
String n = name == null ? '' : name.toString()
if (!(n ==~ /[A-Za-z0-9][A-Za-z0-9._-]{0,63}/)) return "'${n}' is not a file name: letters, digits, dots, dashes and underscores, up to 64".toString()
if (n.toLowerCase().startsWith('hai-') || n.toLowerCase().startsWith('hai_')) return "'${n}' is one of HAI's own files".toString()
return null
}
String schemaUrlHost(Object url) {
java.util.regex.Matcher m = ((url ?: '') as String) =~ /^(?i)https?:\/\/(?:[^@\/?#]*@)?(\[[0-9a-fA-F:.]+\]|[^:\/?#]+)(?::\d{1,5})?(?:[\/?#].*)?$/
return m.matches() ? m.group(1).toLowerCase() : null
}
boolean schemaHubMaintenance(Object url) {
return url instanceof String && (url =~ /(?i)^https?:\/\/[^\/]+\/hub\/[^?#]*(reboot|shutdown|restart|update|restore|reset|backup|delete)/).find()
}
boolean schemaHubSelfHost(Object host) {
String h = ((host ?: '') as String).trim().toLowerCase()
if (h.startsWith('[') && h.endsWith(']')) h = h.substring(1, h.length() - 1)
if (h.endsWith('.')) h = h.substring(0, h.length() - 1)
if (h.isEmpty()) return false
if (h == 'localhost' || h.endsWith('.localhost')) return true
if (h.contains(':')) {
if (h.startsWith('::ffff:')) return schemaHubSelfHost(h.substring(7))
return h ==~ /[0:]*:[0:]*1?/
}
if (!(h ==~ /(0x[0-9a-f]+|[0-9]+)(\.(0x[0-9a-f]+|[0-9]+)){0,3}/)) return false
List parts
try {
parts = h.tokenize('.').collect { String x ->
x.startsWith('0x') ? new BigInteger(x.substring(2) ?: '0', 16) : (x.length() > 1 && x.startsWith('0') ? new BigInteger(x, 8) : new BigInteger(x))
}
} catch (NumberFormatException e) { return false }
BigInteger first = (BigInteger) parts[0]
if (parts.size() == 1) return first.shiftRight(24) == 127G || first == 0G
return first == 127G || (first == 0G && parts.every { it == 0G })
}
@Field static final String CORE_DEVICE_DISABLE_URL = 'http://127.0.0.1:8080/device/disable'
@Field static final String CORE_DEVICE_JSON_URL = 'http://127.0.0.1:8080/device/fullJson/'
@Field static final long CORE_FUTURE_TOLERANCE_MS = 5000L
@Field static final long CORE_DEDUPE_WINDOW_MS = 60000L
@Field static final int CORE_MAX_STEPS_PER_RUN = 2048
@Field static final int CORE_MAX_CALL_DEPTH = 8
@Field static final int CORE_MAX_RETRIES = 1
@Field static final String CORE_VARIABLE_ATTRIBUTE = 'value'
@Field static final Map CORE_HSM_RESULT = [armAway: ['hsmStatus', 'armedAway'], armHome: ['hsmStatus', 'armedHome'],
armNight: ['hsmStatus', 'armedNight'], disarm: ['hsmStatus', 'disarmed'], disarmAll: ['hsmStatus', 'allDisarmed'],
cancelAlerts: ['hsmAlert', 'cancel']]
@Field static final String CONF_REQUESTED = 'requested'
@Field static final String CONF_DISPATCHED = 'dispatched'
@Field static final String CONF_REPORTED = 'reportedStateMatch'
@Field static final String CONF_OBSERVED = 'observedEffect'
@Field static final String CONF_UNKNOWN = 'unknown'
@Field static final String CONF_NOT_SENT = 'notSent'
@Field static final String ATTR_ENGINE = 'likelyThisEngine'
@Field static final String ATTR_EXTERNAL = 'possibleExternal'
@Field static final String ATTR_PHYSICAL = 'confirmedPhysical'
@Field static final String ATTR_UNKNOWN = 'unknown'
@Field static final long CORE_DEFAULT_VERIFY_MS = 5000L
@Field static final long CORE_ECHO_SKEW_MS = 1000L
@Field static final Map CORE_TRAVELLING = ['open': 'opening', 'closed': 'closing']
@Field static final long CORE_TRAVEL_MS = 15000L
@Field static final int CORE_TRAVEL_WAITS = 5
Map coreNewWorld() {
return [
seq : 0L,
rules : [:],
runs : [:],
nextRun : 0,
nextToken : 0,
blocked : [],
ledger : [:],
latestIntent: [:],
verifyMs : CORE_DEFAULT_VERIFY_MS,
seenSources: [:],
occurrences: [] as Set,
holds : [:],
gaps : [],
]
}
Closure corePort(Map w, String member) { return (Closure) ((Map) w.port).get(member) }
@Field static final String SEV_BLOCK = 'block'
Map invNew() { return [devices: [:], variables: [:], modes: [] as Set, rules: [] as Set, rmRules: [] as Set, currentMode: null] }
Map invDevice(Map inv, String id) { return inv.devices.get(id) }
Map invVariable(Map inv, String id) { return inv.variables.get(id) }
List vBlocking(Map r) { return r.findings.findAll { it.severity == SEV_BLOCK } }
boolean vActivatable(Map r) { return vBlocking(r).isEmpty() && r.complete }
Map coreValidate(Map w, Map doc) { Closure f = corePort(w, 'validate'); return f(doc) }
long coreNow(Map w) { Closure f = corePort(w, 'now'); return f() as long }
int coreEpoch(Map w) { Closure f = corePort(w, 'restartEpoch'); return f() as int }
String coreZone(Map w) { Closure f = corePort(w, 'zone'); return f() as String }
Map coreInv(Map w) { Closure f = corePort(w, 'inventory'); return f() }
Map coreDevice(Map w, String dev) {
Closure f = corePort(w, 'device')
return f != null ? f(dev) : invDevice(coreInv(w), dev)
}
boolean coreHalted(Map w) { Closure f = corePort(w, 'halted'); return f() as boolean }
Map coreRead(Map w, String dev, Object attr) {
Map h = w.hypothetical
if (h != null && h.device == dev && h.attribute == attr) return h.cell
Closure f = corePort(w, 'read')
return f(dev, attr)
}
Map coreReadLive(Map w, String dev, Object attr) {
w.liveRead = true
try { return coreRead(w, dev, attr) } finally { w.remove('liveRead') }
}
void coreRecord(Map w, String dev, Object attr, Map cell) { Closure f = corePort(w, 'record'); f(dev, attr, cell) }
void coreSchedule(Map w, Map timer) { Closure f = corePort(w, 'schedule'); f(timer) }
void coreDispatch(Map w, Map effect) { Closure f = corePort(w, 'dispatch'); f(effect) }
void corePersist(Map w) { Closure f = corePort(w, 'persist'); f(w) }
void coreSubscribe(Map w, String ruleId, Map doc) { Closure f = corePort(w, 'subscribe'); f(ruleId, doc) }
LocalTime coreTimeOfDay(Map w, LocalDate date, Object specIn, Object offsetIn, Map doc = null) {
String spec = specIn instanceof Map ? coreVarTime(w, doc, specIn) : specIn as String
Object offsetMinutes = offsetIn instanceof Map ? coreNumber(coreOperand(w, null, doc, offsetIn)) : offsetIn
if (spec == null || (offsetIn instanceof Map && offsetMinutes == null)) {
String gap = "a time or offset from a variable has no value on ${date}".toString()
if (!((List) w.gaps).contains(gap)) ((List) w.gaps) << gap
return null
}
LocalTime base
if (spec == 'sunrise' || spec == 'sunset') {
Closure sun = corePort(w, 'sun')
Map day = sun(date.toString())
if (day == null || day.get(spec) == null) {
String gap = "no ${spec} supplied for ${date}".toString()
if (!((List) w.gaps).contains(gap)) ((List) w.gaps) << gap
return null
}
base = LocalTime.parse(day.get(spec) as String)
} else {
base = LocalTime.parse(spec)
}
long off = offsetMinutes == null ? 0L : (offsetMinutes as long)
return base.plusMinutes(off)
}
String coreVarTime(Map w, Map doc, Object ref) {
String s = coreOperand(w, null, doc, ref)?.toString()
if (s == null) return null
java.util.regex.Matcher m = s =~ /^(?:\d{4}-\d{2}-\d{2}T)?(\d{2}:\d{2}(?::\d{2})?)/
if (m.find()) return m.group(1)
return s in ['sunrise', 'sunset'] ? s : null
}
void coreLog(Map w, String what, Map detail = [:]) {
Map entry = [at: coreNow(w), what: what]
entry.putAll(detail)
Closure f = corePort(w, 'trace')
f(entry)
}
long coreNextSeq(Map w) { w.seq = w.seq + 1; return w.seq }
String coreNewToken(Map w) { w.nextToken = w.nextToken + 1; return "t${w.nextToken}".toString() }
ZonedDateTime coreLocal(Map w, long at) {
return Instant.ofEpochMilli(at).atZone(ZoneId.of(coreZone(w)))
}
Map coreLoad(Map w, Map doc) {
Map v = coreValidate(w, doc)
if (!vActivatable(v)) {
coreLog(w, 'loadRefused', [rule: doc.id, blocking: vBlocking(v).collect { it.code }])
return v
}
String id = doc.id as String
Map rec = [id: id, doc: doc, epoch: 1, paused: false, disabled: false,
queue: [], running: [] as Set, durations: [:], thresholds: [:]]
w.rules.put(id, rec)
coreSubscribe(w, id, doc)
coreArmSchedules(w, rec)
if (doc.initialEvaluation == true) coreConsider(w, rec, [kind: 'initial'], 0)
coreLog(w, 'loaded', [rule: id, epoch: rec.epoch])
corePersist(w)
return v
}
Map coreActivate(Map w, Map newDoc) {
Map rec = w.rules.get(newDoc.id)
Map v = coreValidate(w, newDoc)
if (!vActivatable(v)) {
coreLog(w, 'activateRefused', [rule: newDoc.id, keptEpoch: rec?.epoch,
blocking: vBlocking(v).collect { it.code }])
return v
}
if (rec == null) return coreLoad(w, newDoc)
int oldEpoch = rec.epoch as int
rec.epoch = oldEpoch + 1
rec.doc = newDoc
coreSubscribe(w, rec.id as String, newDoc)
rec.queue.clear()
w.ledger.values().each { Map intent ->
if (intent.rule == rec.id && intent.epoch == oldEpoch && !(intent.state in [CONF_REPORTED, CONF_OBSERVED])) {
intent.possiblyInFlight = true
}
}
w.runs.values().each { Map run ->
if (run.rule == rec.id && run.epoch == oldEpoch && run.status in ['running', 'waiting']) {
run.superseded = true
}
}
if (!rec.disabled) coreArmSchedules(w, rec)
coreLog(w, 'activated', [rule: rec.id, from: oldEpoch, to: rec.epoch])
corePersist(w)
return v
}
void corePause(Map w, String ruleId) {
Map rec = w.rules.get(ruleId)
rec.paused = true
int dropped = rec.queue.size()
rec.queue.clear()
coreLog(w, 'paused', [rule: ruleId, queuedDiscarded: dropped])
coreAnnouncePaused(w, ruleId, true)
corePersist(w)
}
void coreAnnouncePaused(Map w, String ruleId, boolean paused) {
if (((Map) w.port).containsKey('rulePaused')) { Closure f = corePort(w, 'rulePaused'); f([rule: ruleId, paused: paused]); return }
coreSchedule(w, [at: coreNow(w), kind: 'variableChanged', variable: RULE_STATE_PREFIX + ruleId, attribute: RULE_PAUSED_ATTRIBUTE,
value: paused, before: !paused, depth: 0])
}
void coreResume(Map w, String ruleId) {
Map rec = w.rules.get(ruleId)
rec.paused = false
coreLog(w, 'resumed', [rule: ruleId, stillDisabled: rec.disabled])
new ArrayList(w.runs.values()).each { Object ro ->
Map run = ro
if (run.rule != ruleId || !(run.held instanceof Map)) return
Map held = run.held
run.remove('held')
if (held.kind == 'timeout') coreWaitTimedOut(w, run, held.token as String)
else coreWake(w, run, held.token as String, held.why as String)
}
coreAnnouncePaused(w, ruleId, false)
corePersist(w)
}
void coreDisable(Map w, String ruleId) {
Map rec = w.rules.get(ruleId)
rec.disabled = true
Closure unsubscribe = corePort(w, 'unsubscribe')
unsubscribe(ruleId)
rec.queue.clear()
w.runs.values().each { Map run ->
if (run.rule == ruleId && run.status in ['running', 'waiting']) coreCancelRun(w, run, 'disabled')
}
rec.durations.clear()
w.holds.values().each { Object c -> ((Map) c).remove(ruleId) }
coreLog(w, 'disabled', [rule: ruleId])
corePersist(w)
}
void coreEnable(Map w, String ruleId) {
Map rec = w.rules.get(ruleId)
rec.disabled = false
coreSubscribe(w, ruleId, rec.doc)
coreArmSchedules(w, rec)
if (rec.doc.initialEvaluation == true) coreConsider(w, rec, [kind: 'initial'], 0)
coreLog(w, 'enabled', [rule: ruleId, stillPaused: rec.paused])
corePersist(w)
}
void coreCancelRuns(Map w, String ruleId, Object exceptRun = null) {
Map rec = w.rules.get(ruleId)
if (rec == null) return
rec.queue.clear()
new ArrayList(w.runs.values()).each { Map run ->
if (run.rule == ruleId && run.id != exceptRun && run.status in ['running', 'waiting'] && run.inCancel != true) coreCancelRun(w, run, 'timersCancelled')
}
coreLog(w, 'timersCancelled', [rule: ruleId])
corePersist(w)
}
void coreRuleControl(Map w, Map run, Map rec, Map doc, Map a, String path) {
int depth = (run.depth as int) + 1
((List) a.rules).each { Object key ->
Map ref = ((Map) (doc.refs ?: [:])).get(key)
String kind = ref?.kind as String
String target = kind == 'self' ? rec.id as String : ref?.id as String
if (kind == 'rmRule' && !(a.type == 'controlRule' && a.op in ['disable', 'enable']) && ((Map) w.port).containsKey('rmSwitched')) {
Closure sw = corePort(w, 'rmSwitched')
String copy = sw(target) as String
if (copy) {
coreLog(w, 'rmRuleRetargeted', [run: run.id, rule: target, to: copy, action: a.type == 'setRuleBoolean' ? 'setBoolean' : a.op, path: path])
target = copy
kind = 'rule'
}
}
if (kind == 'rmRule') {
if (a.type == 'controlRule' && a.op in ['disable', 'enable']) {
boolean off = a.op == 'disable'
if (((Map) w.port).containsKey('appDisable')) {
Closure ad = corePort(w, 'appDisable'); ad([appId: target, disable: off])
}
coreLog(w, 'rmRuleDisabled', [run: run.id, rule: target, disable: off, path: path])
return
}
String action = a.type == 'setRuleBoolean' ? (a.value == true ? 'setRuleBooleanTrue' : 'setRuleBooleanFalse') :
[pause: 'pauseRule', resume: 'resumeRule', cancelTimers: 'stopRuleAct', runActions: 'runRuleAct'].get(a.op)
if (((Map) w.port).containsKey('rmAction')) { Closure rm = corePort(w, 'rmAction'); rm([ids: [target], action: action]) }
coreLog(w, 'rmRuleAction', [run: run.id, rule: target, action: action, path: path])
return
}
Map tgt = w.rules.get(target)
if (a.type == 'setRuleBoolean') {
if (tgt != null) {
coreSetRuleBoolean(w, target, a.value as boolean, run.id, depth)
} else if (((Map) w.port).containsKey('ruleBoolean')) {
Closure rb = corePort(w, 'ruleBoolean'); rb([target: target, value: a.value, from: rec.id, depth: depth])
}
coreLog(w, 'ruleBooleanSet', [run: run.id, rule: target, value: a.value, path: path])
return
}
if (tgt != null) {
if (a.op == 'pause') corePause(w, target)
else if (a.op == 'resume') coreResume(w, target)
else if (a.op == 'cancelTimers') coreCancelRuns(w, target, run.id)
else if (a.op == 'runActions') coreRunActions(w, target, rec.id as String, depth)
else if (a.op in ['disable', 'enable'] && ((Map) w.port).containsKey('controlRule')) {
Closure cr2 = corePort(w, 'controlRule'); cr2([target: target, op: a.op, from: rec.id])
}
} else if (((Map) w.port).containsKey('controlRule')) {
Closure cr = corePort(w, 'controlRule'); cr([target: target, op: a.op, from: rec.id, depth: depth])
}
coreLog(w, 'ruleControlled', [run: run.id, rule: target, op: a.op, path: path])
}
}
void coreStopRun(Map w, String runId) {
Map run = w.runs.get(runId)
if (run != null && run.status in ['running', 'waiting']) coreCancelRun(w, run, 'stopped')
corePersist(w)
}
void coreCancelRun(Map w, Map run, String why) {
run.status = 'cancelled'
run.cancelReason = why
run.wait = null
Map rec = w.rules.get(run.rule)
rec?.running?.remove(run.id)
coreLog(w, 'runCancelled', [run: run.id, rule: run.rule, why: why])
if (((Map) run.cause)?.caller instanceof Map) coreSendAnswer(w, ((Map) run.cause).caller, [value: null, how: 'cancelled'])
coreCancelSteps(w, run, rec)
}
void coreCancelSteps(Map w, Map run, Map rec) {
if (run.background == true || run.inCancel == true) return
List steps = (((Map) rec?.doc)?.onCancel ?: [])
if (steps.isEmpty()) return
run.inCancel = true
run.status = 'running'
run.wait = null
run.steps = 0
run.frames = [[list: steps, index: 0, path: 'onCancel']]
coreLog(w, 'cancelPath', [run: run.id, rule: run.rule, why: run.cancelReason])
coreStep(w, run)
}
boolean coreAdmissible(Map rec) { return !rec.paused && !rec.disabled }
Map coreInject(Map w, Map event) {
Map adm = coreAdmit(w, event)
corePersist(w)
return adm
}
Map coreAdmit(Map w, Map event) {
if (coreHalted(w)) return [accepted: false, halted: true]
long seq = coreNextSeq(w)
List flags = []
long ts
if (!(event.ts instanceof Number)) {
ts = coreNow(w)
flags << 'timestampMissing'
} else {
ts = (event.ts as Number).longValue()
if (ts > coreNow(w) + CORE_FUTURE_TOLERANCE_MS) {
ts = coreNow(w)
flags << 'timestampFuture'
}
}
if (event.sourceId) {
String slot = "${event.kind}|${event.device}|${event.attribute}|${event.number}".toString()
Object seen = w.seenSources.get(slot)
if (seen instanceof List && ((List) seen)[0] == event.sourceId &&
coreNow(w) - (((List) seen)[1] as long) <= CORE_DEDUPE_WINDOW_MS) {
coreLog(w, 'duplicate', [sourceId: event.sourceId])
return [accepted: false, duplicate: true, seq: seq]
}
w.seenSources.put(slot, [event.sourceId, coreNow(w)])
}
Map adm = [accepted: true, seq: seq, ts: ts, flags: flags, event: event,
attribution: coreAttribute(w, event)]
if (event.kind == 'button') {
coreLog(w, 'accepted', [kind: 'button', device: event.device, number: event.number,
action: event.action, seq: seq, attribution: adm.attribution])
coreDispatchTriggers(w, adm)
coreWakeWaits(w, adm)
return adm
}
if (event.kind == 'mode') {
Closure readMode = corePort(w, 'readMode')
Closure recordMode = corePort(w, 'recordMode')
String prev = readMode()
recordMode(event.value)
adm.changed = (prev != event.value)
adm.previous = prev
coreLog(w, 'accepted', [kind: 'mode', value: event.value, seq: seq])
coreDispatchTriggers(w, adm)
coreAfterAdmission(w, adm)
return adm
}
String dev = event.device as String
String attr = event.attribute as String
Map cur = coreRead(w, dev, attr)
boolean fresher = cur == null || ts > (cur.ts as long) ||
(ts == (cur.ts as long) && seq > (cur.seq as long))
adm.previous = cur
adm.late = !fresher
if (!fresher) flags << 'late'
if (fresher) {
coreRecord(w, dev, attr, [value: event.value, ts: ts, seq: seq, source: event.source, epoch: coreEpoch(w)])
String seenKey = "${dev}|${attr}|${event.value}".toString()
if (w.rules.values().any { ((List) coreTimeSinceKeys((Map) it)).contains(seenKey) }) {
Map seen = new LinkedHashMap((Map) (w.lastSeen ?: [:])); seen.put(seenKey, ts); w.lastSeen = seen
}
if (event.source != 'engine') { Closure seen = corePort(w, 'observed'); seen(dev, attr, event.value) }
adm.transition = (cur != null && cur.value != event.value)
adm.firstObservation = (cur == null)
} else {
adm.transition = false
adm.firstObservation = false
}
coreLog(w, 'accepted', [kind: 'attribute', device: dev, attribute: attr, value: event.value,
ts: ts, seq: seq, flags: flags, attribution: adm.attribution,
stateUpdated: fresher])
coreCorrelate(w, adm)
coreDispatchTriggers(w, adm)
coreAfterAdmission(w, adm)
if ((adm.transition == true || adm.firstObservation == true) && attr == CORE_VARIABLE_ATTRIBUTE) coreRearmFromVariable(w, dev)
new ArrayList(w.rules.values()).each { coreRequiredLapsed(w, it) }
return adm
}
String coreAttribute(Map w, Map event) {
if (event.source == 'physical') return ATTR_PHYSICAL
if (event.source == 'engine') return ATTR_ENGINE
if (event.source == 'external') return ATTR_EXTERNAL
return ATTR_UNKNOWN
}
void coreCorrelate(Map w, Map adm) {
Object intentId = adm.event.intent
if (intentId == null) { coreCorrelateEcho(w, adm); return }
Map intent = w.ledger.get(intentId)
if (intent == null) return
if (intent.state in [CONF_DISPATCHED, CONF_UNKNOWN]) {
intent.state = (adm.event.observed == true) ? CONF_OBSERVED : CONF_REPORTED
intent.confirmedAt = coreNow(w)
coreLog(w, 'confirmed', [intent: intentId, state: intent.state])
}
}
void coreDispatchTriggers(Map w, Map adm) {
Map event = adm.event
new ArrayList(w.rules.values()).each { Object r ->
Map rec = r
if (rec.disabled) return
Map doc = rec.doc
String before = null
((List) (doc.triggers ?: [])).eachWithIndex { Object t, int ti ->
Map trig = t
if (coreTriggerMatches(w, rec, trig, ti, adm)) {
if (doc.requiredExpression != null && before == null) before = coreRequired(w, rec, adm)
Map cause = [kind: trig.kind, trigger: ti, seq: adm.seq]
if (before != null) cause.required = before
coreConsider(w, rec, coreEventSaid(event, cause), ((event.depth ?: 0) as int))
}
}
}
}
Map coreEventSaid(Map event, Map into) {
if (event.device != null) into.device = event.device
if (event.attribute != null) into.attribute = event.attribute
if (event.kind == 'button') { into.value = event.number; into.action = event.action }
else if (event.value != null) into.value = event.value
return into
}
String coreRefId(Map doc, Object refKey) {
Map ref = ((Map) (doc.refs ?: [:])).get(refKey)
return ref ? (ref.id as String) : null
}
String coreVarId(Object ruleId, String vid) {
return vid != null && vid.startsWith(RULE_VARIABLE_PREFIX) ? "${vid}@${ruleId}".toString() : vid
}
boolean coreTriggerMatches(Map w, Map rec, Map trig, int ti, Map adm) {
Map doc = rec.doc
Map event = adm.event
String kind = trig.kind as String
if (trig.disabled == true) return false
if (kind == 'button') {
return event.kind == 'button' && coreRefId(doc, trig.ref) == event.device &&
(trig.number == null || (trig.number as int) == (event.number as int)) &&
(trig.action == null || trig.action == event.action)
}
if (kind == 'mode') {
if (event.kind != 'mode' || !adm.changed) return false
return trig.to == null || ((List) trig.to).contains(event.value)
}
if (event.kind != 'attribute') return false
if (kind in ['hsmStatus', 'hsmAlert', 'locationEvent']) {
if (event.device != LOCATION_DEVICE) return false
if (kind == 'locationEvent') return ((List) (trig.events ?: [])).contains(event.attribute)
List want = (kind == 'hsmStatus' ? trig.to : trig.alerts)
return event.attribute == kind && (!want || want.contains(event.value)) &&
(kind == 'hsmAlert' || adm.transition == true || adm.firstObservation == true)
}
String dev = coreRefId(doc, trig.ref)
if (kind == 'hubVariable') {
return coreVarId(doc.id, dev) == event.device && event.attribute == CORE_VARIABLE_ATTRIBUTE && adm.transition == true
}
if (kind == 'rulePaused') {
return RULE_STATE_PREFIX + dev == event.device && event.attribute == RULE_PAUSED_ATTRIBUTE &&
(adm.transition == true || adm.firstObservation == true) &&
event.value == (trig.value != false)
}
if (kind == 'deviceAttribute') {
if (dev != event.device || trig.attribute != event.attribute) return false
if (trig.eventType != null && event.eventType != trig.eventType) return false
if (trig.op != null) {
Object want = trig.value instanceof Map ? coreOperand(w, null, doc, trig.value) : trig.value
String regexProblem = trig.op == 'matches' ? coreRegexInputProblem(event.value) : null
if (regexProblem != null) {
coreLog(w, 'regexRefused', [rule: doc.id, reason: regexProblem])
return false
}
if (coreCompare(event.value, trig.op as String, want) != LOGIC_TRUE) return false
} else if (trig.containsKey('value') && trig.value != event.value) return false
String discipline = (trig.discipline ?: 'transition') as String
return discipline == 'event' ? true : (adm.transition == true)
}
if (kind == 'threshold') {
if (dev != event.device || trig.attribute != event.attribute) return false
if (adm.late || adm.firstObservation || adm.previous == null) return false
boolean before = coreCompare(((Map) adm.previous).value, trig.op as String, trig.value) == LOGIC_TRUE
boolean after = coreCompare(event.value, trig.op as String, trig.value) == LOGIC_TRUE
return !before && after
}
return false
}
List coreModeNames(Map doc, Map node) {
Map refs = (doc?.refs ?: [:])
List keys = node?.refs instanceof List ? node.refs : (node?.ref != null ? [node.ref] : [])
List out = []
keys.each { Object k ->
Map ref = refs.get(k)
if (ref?.name != null) out << (ref.name as String)
}
if (node?.value instanceof String) out << (node.value as String)
return out.unique()
}
String coreConsider(Map w, Map rec, Map cause, int depth) {
if (rec.disabled) return 'disabled'
if (!coreAdmissible(rec)) {
coreLog(w, 'notAdmitted', [rule: rec.id, why: rec.paused ? 'paused' : 'disabled', cause: cause.kind])
return rec.paused ? 'paused' : 'disabled'
}
Map doc = rec.doc
if (doc.requiredExpression != null && cause.kind != 'manual') {
String req = (cause.remove('required') ?: coreRequired(w, rec, null)) as String
if (req != LOGIC_TRUE) { coreLog(w, 'notAdmitted', [rule: rec.id, why: 'requiredExpression', value: req, cause: cause.kind]); return 'requiredExpression' }
}
Map ct = cause.trigger != null && cause.kind != 'manual' ? ((List) (doc.triggers ?: []))[cause.trigger as int] : null
if (ct?.condition != null) {
String cv = logicEvaluate((Map) ct.condition) { Map node, String p -> coreLeaf(w, doc, node, false, cause) }.value as String
if (cv != LOGIC_TRUE) { coreLog(w, 'notAdmitted', [rule: rec.id, why: 'triggerCondition', value: cv, cause: cause.kind]); return 'triggerCondition' }
}
if ((doc.conditionTiming ?: 'atTrigger') == 'atTrigger') {
String gate = coreGate(w, rec, false, cause)
if (gate != LOGIC_TRUE) return 'conditions'
}
String policy = (doc.rerunPolicy ?: 'restart') as String
Set running = (Set) rec.running
if (!running.isEmpty()) {
if (policy == 'ignore') {
coreLog(w, 'ignored', [rule: rec.id, cause: cause.kind]); return 'ignored'
}
if (policy == 'restart') {
new ArrayList(running).each { Object rid -> coreCancelRun(w, w.runs.get(rid), 'restarted') }
}
if (policy == 'queue') {
int limit = (doc.queueLimit ?: 1) as int
if (rec.queue.size() >= limit) {
coreLog(w, 'queueOverflow', [rule: rec.id, limit: limit]); return 'queueOverflow'
}
cause.queued = true
rec.queue << cause
coreLog(w, 'queued', [rule: rec.id, depth: rec.queue.size()])
return null
}
if (policy == 'parallel') {
int limit = (doc.parallelLimit ?: 1) as int
if (running.size() >= limit) {
coreLog(w, 'parallelLimit', [rule: rec.id, limit: limit]); return 'parallelLimit'
}
}
}
coreStartRun(w, rec, cause, depth)
return null
}
String coreRequired(Map w, Map rec, Map adm) {
Map doc = rec.doc
Map ev = adm?.event
if (ev?.kind == 'attribute' && adm.previous != null) w.hypothetical = [device: ev.device, attribute: ev.attribute, cell: adm.previous]
try {
return logicEvaluate((Map) doc.requiredExpression) { Map node, String p -> coreLeaf(w, doc, node, false, null) }.value as String
} finally { w.remove('hypothetical') }
}
boolean coreRequiredLapsed(Map w, Map rec) {
Map doc = rec.doc
if (doc.requiredExpression == null || doc.cancelOnFalse != true || ((Set) rec.running).isEmpty()) return false
if (coreRequired(w, rec, null) == LOGIC_TRUE) return false
coreLog(w, 'requiredFalse', [rule: rec.id])
coreCancelRuns(w, rec.id as String)
return true
}
String coreGate(Map w, Map rec, boolean needFresh, Map cause) {
Map doc = rec.doc
if (doc.conditions == null) return LOGIC_TRUE
if (cause?.kind == 'manual') { coreLog(w, 'gate', [rule: rec.id, value: LOGIC_TRUE, why: 'runNow']); return LOGIC_TRUE }
boolean regexRefused = false
Map out = logicEvaluate((Map) doc.conditions) { Map node, String path ->
List seen = coreLeaf(w, doc, node, needFresh, cause)
Object evidence = seen.size() > 1 ? seen[1] : null
if ((evidence instanceof String && ((String) evidence).startsWith('the regex input is ')) ||
(evidence instanceof List && ((List) evidence).any { it instanceof String && ((String) it).startsWith('the regex input is ') })) regexRefused = true
return seen
}
String v = out.value as String
if (regexRefused) {
coreLog(w, 'gate', [rule: rec.id, value: LOGIC_FALSE, why: 'regexRefused', trace: out.trace])
return LOGIC_FALSE
}
coreLog(w, 'gate', [rule: rec.id, value: v, trace: out.trace])
if (v == LOGIC_UNKNOWN && (doc.onUnknown ?: 'block') == 'proceed') return LOGIC_TRUE
return v
}
String coreRegexFind(String text, String pattern) {
if (text == null || pattern == null) { return null }
if (text.length() > REGEX_MAX_SUBJECT) { return null }
if (pattern.length() > REGEX_MAX_CHARS) { return null }
if (pattern =~ REGEX_NESTED_QUANTIFIER) { return null }
try {
java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(text)
if (!m.find()) { return null }
return m.groupCount() > 0 && m.group(1) != null ? m.group(1) : m.group(0)
} catch (Exception e) {
return null
}
}
String coreRegexInputProblem(Object text) {
if (text == null) return null
int length = text.toString().length()
return length > REGEX_MAX_SUBJECT ? "the regex input is ${length} characters, over ${REGEX_MAX_SUBJECT}; no shortened text was searched".toString() : null
}
boolean coreInvocable(Map doc) {
if (doc == null) { return false }
return ((List) (doc.triggers ?: [])).any { Object t ->
t instanceof Map && ((Map) t).kind == 'ruleInvocation' && ((Map) t).disabled != true
}
}
String coreCompare(Object seen, String op, Object want) {
if (op == 'isEmpty') return seen == null || seen.toString().isEmpty() ? LOGIC_TRUE : LOGIC_FALSE
if (seen == null || want == null) return LOGIC_UNKNOWN
switch (op ?: 'is') {
case 'is': return seen.toString() == want.toString() ? LOGIC_TRUE : LOGIC_FALSE
case 'isNot': return seen.toString() != want.toString() ? LOGIC_TRUE : LOGIC_FALSE
case 'contains': return seen.toString().contains(want.toString()) ? LOGIC_TRUE : LOGIC_FALSE
case 'matches':
if (coreRegexInputProblem(seen) != null) return LOGIC_UNKNOWN
Boolean hit = coreRegexFind(seen.toString(), want.toString()) != null
return hit ? LOGIC_TRUE : LOGIC_FALSE
case 'notBetween': return logicNot(coreCompare(seen, 'between', want))
case 'between':
if (!(want instanceof List) || ((List) want).size() != 2) return LOGIC_UNKNOWN
BigDecimal x = coreNumber(seen), lo = coreNumber(((List) want)[0]), hi = coreNumber(((List) want)[1])
if (x == null || lo == null || hi == null) return LOGIC_UNKNOWN
return x >= lo && x <= hi ? LOGIC_TRUE : LOGIC_FALSE
}
BigDecimal a, b
try { a = new BigDecimal(seen.toString()); b = new BigDecimal(want.toString()) }
catch (Exception e) {
Map x = coreDateValue(seen), y = coreDateValue(want)
if (x == null || y == null || (x.kind == 'time') != (y.kind == 'time')) return LOGIC_UNKNOWN
int c = x.at.compareTo(y.at) as int
a = c; b = 0
}
switch (op) {
case 'above': return a > b ? LOGIC_TRUE : LOGIC_FALSE
case 'below': return a < b ? LOGIC_TRUE : LOGIC_FALSE
case 'atLeast': return a >= b ? LOGIC_TRUE : LOGIC_FALSE
case 'atMost': return a <= b ? LOGIC_TRUE : LOGIC_FALSE
}
return LOGIC_UNKNOWN
}
List coreLeaf(Map w, Map doc, Map node, boolean needFresh = false, Map cause = null, Map run = null) {
String type = node.type as String
if (type == 'waitOutcome') {
String got = run?.waitOutcome as String
if (got == null) return [LOGIC_UNKNOWN, 'no wait has finished in this run']
String want = (node.value ?: 'event') as String
return [got == want ? LOGIC_TRUE : LOGIC_FALSE, "the wait ended on ${got}".toString()]
}
if (type == 'triggeredBy') {
Object started = cause?.trigger
if (started == null) return [LOGIC_UNKNOWN, "started by ${cause?.kind ?: 'no trigger'}".toString()]
int ti = started as int
boolean hit = ((List) (node.triggers ?: [])).any { (it as int) == ti }
return [hit ? LOGIC_TRUE : LOGIC_FALSE, "trigger ${ti}".toString()]
}
if (type == 'deviceAttribute') {
List keys = []
if (node.ref) keys << node.ref
if (node.refs instanceof List) keys.addAll((List) node.refs)
List values = []
List saw = []
keys.each { Object k ->
String dev = coreRefId(doc, k)
Map st = dev ? coreRead(w, dev, node.attribute) : null
if (st == null) { values << LOGIC_UNKNOWN; saw << null; return }
boolean cached = (st.epoch == null ? 0 : st.epoch as int) != coreEpoch(w)
if (cached && (needFresh || node.freshSeconds != null)) {
values << LOGIC_UNKNOWN; saw << "cached:${st.value}".toString(); return
}
if (node.freshSeconds != null &&
coreNow(w) - (st.ts as long) > ((node.freshSeconds as long) * 1000L)) {
values << LOGIC_UNKNOWN; saw << "stale:${st.value}".toString(); return
}
String regexProblem = node.op == 'matches' ? coreRegexInputProblem(st.value) : null
if (regexProblem != null) {
coreLog(w, 'regexRefused', [rule: doc.id, reason: regexProblem])
values << LOGIC_UNKNOWN
saw << regexProblem
return
}
values << coreCompare(st.value, node.op as String, node.value instanceof Map ? coreOperand(w, null, doc, node.value) : node.value)
saw << (cached ? "cached:${st.value}".toString() : st.value)
}
if (node.unknownAs == 'false') values = values.collect { it == LOGIC_UNKNOWN ? LOGIC_FALSE : it }
String match = (node.match ?: 'any') as String
String v = (match == 'all') ? logicAnd(values) : logicOr(values)
return [v, saw.size() == 1 ? saw[0] : saw]
}
if (type == 'variable') {
String vid = coreVarId(doc.id, coreRefId(doc, node.ref))
Closure readVariable = corePort(w, 'readVariable')
Map cell = readVariable(vid)
if (cell == null) {
Map vref = ((Map) (doc.refs ?: [:])).get(node.ref)
if (vref != null && vref.containsKey('initial')) cell = [value: vref.initial]
}
String unknownIs = node.unknownAs == 'false' ? LOGIC_FALSE : LOGIC_UNKNOWN
if (cell == null) return node.op == 'isEmpty' ? [LOGIC_TRUE, 'unset'] : [unknownIs, 'unset']
String regexProblem = node.op == 'matches' ? coreRegexInputProblem(cell.value) : null
if (regexProblem != null) {
coreLog(w, 'regexRefused', [rule: doc.id, reason: regexProblem])
return [unknownIs, regexProblem]
}
String got = coreCompare(cell.value, node.op as String, node.value instanceof Map ? coreOperand(w, null, doc, node.value) : node.value)
return [got == LOGIC_UNKNOWN ? unknownIs : got, cell.value]
}
if (type == 'rulePaused') {
String target = coreRefId(doc, node.ref)
Map other = w.rules.get(target)
Object paused = other != null ? other.paused : coreRead(w, RULE_STATE_PREFIX + target, RULE_PAUSED_ATTRIBUTE)?.value
if (!(paused instanceof Boolean)) return [LOGIC_UNKNOWN, null]
return [(paused == (node.value != false)) ? LOGIC_TRUE : LOGIC_FALSE, paused]
}
if (type == 'hsmStatus') {
Object hsm = coreRead(w, LOCATION_DEVICE, 'hsmStatus')?.value
if (hsm == null) return [LOGIC_UNKNOWN, null]
return [((List) (node.value ?: [])).contains(hsm.toString()) ? LOGIC_TRUE : LOGIC_FALSE, hsm]
}
if (type == 'mode') {
List want = coreModeNames(doc, node)
Closure readMode = corePort(w, 'readMode')
Object mode = readMode()
if (mode == null) return [LOGIC_UNKNOWN, null]
return [want.contains(mode) ? LOGIC_TRUE : LOGIC_FALSE, mode]
}
if (type == 'timeSince') {
String dev = coreRefId(doc, node.ref)
Long last = null
if (node.attribute != null) {
Map cur = coreRead(w, dev, node.attribute)
if (!node.containsKey('value') || cur?.value?.toString() == node.value?.toString()) last = cur?.ts as Long
else last = ((Map) (w.lastSeen ?: [:])).get("${dev}|${node.attribute}|${node.value}".toString()) as Long
} else {
((Collection) (((Map) ((Map) (coreInv(w).devices ?: [:])).get(dev))?.attributes ?: [])).each { Object a ->
Long t = coreRead(w, dev, a)?.ts as Long
if (t != null && (last == null || t > last)) last = t
}
}
if (last == null) return [LOGIC_UNKNOWN, 'never seen']
long elapsed = coreNow(w) - last
boolean ok = node.op == 'below' ? elapsed < (node.seconds as long) * 1000L : elapsed >= (node.seconds as long) * 1000L
return [ok ? LOGIC_TRUE : LOGIC_FALSE, "${(long) (elapsed / 1000L)} s".toString()]
}
if (type == 'dayOfWeek') {
String today = CORE_DAY_NAMES[coreLocal(w, coreNow(w)).getDayOfWeek().getValue() - 1]
return [((List) node.days).contains(today) ? LOGIC_TRUE : LOGIC_FALSE, today]
}
if (type == 'dateRange') {
LocalDate today = coreLocal(w, coreNow(w)).toLocalDate()
String f = coreDateBound(w, doc, node.from), t = node.to == null ? f : coreDateBound(w, doc, node.to)
if (f == null || t == null) return [LOGIC_UNKNOWN, today.toString()]
boolean inside
if (f.size() == 10 && t.size() == 10) inside = !today.isBefore(LocalDate.parse(f)) && !today.isAfter(LocalDate.parse(t))
else {
String md = today.toString().substring(5), a = f.substring(f.size() - 5), b = t.substring(t.size() - 5)
inside = a <= b ? (md >= a && md <= b) : (md >= a || md <= b)
}
return [inside ? LOGIC_TRUE : LOGIC_FALSE, today.toString()]
}
if (type == 'timeWindow') {
Boolean inside = coreInWindow(w, node, doc)
String when = coreLocal(w, coreNow(w)).toLocalDateTime().toString()
if (inside == null) return [LOGIC_UNKNOWN, when]
return [inside ? LOGIC_TRUE : LOGIC_FALSE, when]
}
return [LOGIC_UNKNOWN, null]
}
String coreDateBound(Map w, Map doc, Object x) {
Object v = x instanceof Map ? coreOperand(w, null, doc, x) : x
if (v == null) return null
String s = v.toString()
if (s ==~ /\d{4}-\d{2}-\d{2}.*/) return s.substring(0, 10)
return s ==~ /\d{2}-\d{2}/ ? s : null
}
Boolean coreInWindow(Map w, Map node, Map doc = null) {
ZonedDateTime now = coreLocal(w, coreNow(w))
LocalDate today = now.toLocalDate()
Map w1 = coreWindowFor(w, node, today, doc)
if (w1 == null || w1.unresolvable) return null
if (timeInWindow(now, w1)) return true
if (!w1.empty && w1.overnight) {
Map w0 = coreWindowFor(w, node, today.minusDays(1), doc)
if (w0 == null || w0.unresolvable) return null
if (timeInWindow(now, w0)) return true
}
return false
}
Map coreWindowFor(Map w, Map node, LocalDate date, Map doc = null) {
LocalTime f = coreTimeOfDay(w, date, node.from, node.fromOffsetMinutes, doc)
if (f == null) return null
LocalTime tSameDay = coreTimeOfDay(w, date, node.to, node.toOffsetMinutes, doc)
if (tSameDay == null) return null
LocalTime t = tSameDay
if (t.isBefore(f) && (node.to == 'sunrise' || node.to == 'sunset')) {
t = coreTimeOfDay(w, date.plusDays(1), node.to, node.toOffsetMinutes, doc)
if (t == null) return null
}
return timeResolveWindow(date, f, t, coreZone(w))
}
void coreStartRun(Map w, Map rec, Map cause, int depth) {
w.nextRun = w.nextRun + 1
String rid = "run${w.nextRun}".toString()
Map doc = rec.doc
cause.started = true
Map run = [id: rid, rule: rec.id, epoch: rec.epoch, status: 'running', cause: cause,
frames: [[list: (doc.actions ?: []), index: 0]], wait: null,
inFailure: false, depth: depth, startedAt: coreNow(w), steps: 0, errors: [],
restartEpoch: coreEpoch(w), recovering: false]
w.runs.put(rid, run)
((Set) rec.running).add(rid)
coreLog(w, 'runStarted', [run: rid, rule: rec.id, epoch: rec.epoch, cause: cause.kind])
if (cause.containsKey('input')) {
Closure writeVariable = corePort(w, 'writeVariable')
writeVariable(coreVarId(rec.id, RULE_INPUT_ID), [value: cause.input, writtenBy: rid, at: coreNow(w)])
}
if ((doc.conditionTiming ?: 'atTrigger') == 'atRunStart') {
String gate = coreGate(w, rec, false, cause)
if (gate != LOGIC_TRUE) { coreFinish(w, run, 'gateFalse'); return }
}
coreStep(w, run)
}
boolean coreRunLive(Map w, Map run) {
if (!(run.status in ['running', 'waiting'])) return false
Map rec = w.rules.get(run.rule)
if (rec == null || rec.disabled) return false
if (run.superseded || (run.epoch as int) != (rec.epoch as int)) return false
return true
}
void coreStep(Map w, Map run) {
while (run.status == 'running') {
if (coreHalted(w)) return
if (!coreRunLive(w, run)) {
if (run.status == 'running') coreFinish(w, run, run.superseded ? 'superseded' : 'notLive')
return
}
run.steps = (run.steps as int) + 1
if ((run.steps as int) > CORE_MAX_STEPS_PER_RUN) {
coreFail(w, run, 'RUN_STEP_LIMIT', "run exceeded ${CORE_MAX_STEPS_PER_RUN} steps")
continue
}
List frames = run.frames
if (frames.isEmpty()) { coreFinish(w, run, 'completed'); return }
Map frame = frames[-1]
List list = frame.list
if ((frame.index as int) >= list.size()) {
if (frame.repeat instanceof Map && coreRepeatNext(w, run, frame)) continue
if (frame.gradual instanceof Map && coreGradualNext(w, run, frame)) continue
frames.remove(frames.size() - 1)
continue
}
Map action = list[frame.index as int]
String path = "${frame.path ?: 'actions'}.${frame.index}".toString()
frame.index = (frame.index as int) + 1
coreExecute(w, run, action, path)
}
}
void coreExecute(Map w, Map run, Map a, String path) {
Map rec = w.rules.get(run.rule)
Map doc = rec.doc
String type = a.type as String
if (a.disabled == true) { coreLog(w, 'skipped', [run: run.id, path: path, why: 'disabled']); return }
if (coreRequiredLapsed(w, rec)) return
if (type == 'comment') return
if (type == 'exit') { coreLog(w, 'exited', [run: run.id, path: path]); coreFinish(w, run, 'exited'); return }
if (type == 'capture') {
Map kept = new LinkedHashMap((Map) (rec.captured ?: [:]))
((List) a.refs).each { Object k ->
String dev = coreRefId(doc, k)
Map vals = [:]
Set has = (Set) invDevice(coreInv(w), dev)?.attributes
CAPTURE_ATTRIBUTES.each { String at ->
if (has != null && !has.contains(at)) return
Object v = coreRead(w, dev, at)?.value
if (v != null) vals.put(at, v)
}
kept.put(dev, vals)
}
rec.captured = kept
coreLog(w, 'captured', [run: run.id, devices: ((List) a.refs).size(), path: path])
return
}
if (type == 'restore') {
if (!(run.restoreQueue instanceof List)) {
coreStopGradual(w, rec.id as String, null, ((List) a.refs).collect { coreRefId(doc, it) } as Set, path, run.id)
run.restoreQueue = coreRestorePlan(w, doc, a)
}
List queue = run.restoreQueue
if (queue.isEmpty()) { run.remove('restoreQueue'); coreLog(w, 'restored', [run: run.id, path: path]); return }
Map next = queue.remove(0)
Map frame = ((List) run.frames)[-1]
frame.index = (frame.index as int) - 1
coreCommand(w, run, doc, next, "${path}.r${next.n}".toString())
return
}
if (type == 'log') {
String text = coreExpandText(w, run, doc, a.text as String)
if (((Map) w.port).containsKey('log')) { Closure f = corePort(w, 'log'); f([level: a.level ?: 'info', text: text]) }
coreLog(w, 'logged', [run: run.id, level: a.level ?: 'info', text: text, path: path])
return
}
if (type == 'setMode') {
String mode = a.containsKey('ref') ? (((Map) ((Map) (doc.refs ?: [:])).get(a.ref))?.name as String) : (a.value as String)
if (((Map) w.port).containsKey('setMode')) { Closure f = corePort(w, 'setMode'); f(mode) }
else coreSchedule(w, [at: coreNow(w), kind: 'modeSet', value: mode, depth: (run.depth as int) + 1])
coreLog(w, 'modeSet', [run: run.id, mode: mode, path: path])
return
}
if (type == 'deviceEnabled') {
String problem = !((Map) w.port).containsKey('net') ? 'this runtime cannot send requests' :
coreDeviceEnabledSend(w, run, doc, a, path, 0, 'set')
if (problem != null) coreFail(w, run, 'DEVICE_ENABLE_FAILED', "${path}: ${problem}".toString())
return
}
if (type == 'hsm') {
if (((Map) w.port).containsKey('setHsm')) {
try { Closure f = corePort(w, 'setHsm'); f(a.op) } catch (Exception e) { coreFail(w, run, 'HSM_FAILED', "${path}: ${e.message}".toString()); return }
} else if (CORE_HSM_RESULT.containsKey(a.op)) {
List to = CORE_HSM_RESULT.get(a.op)
coreSchedule(w, [at: coreNow(w), kind: 'hsmSet', attribute: to[0], value: to[1], depth: (run.depth as int) + 1])
}
coreLog(w, 'hsmSet', [run: run.id, op: a.op, path: path])
return
}
if (type == 'return') {
Object value = a.value
if (a.containsKey('ref')) {
Closure readVariable = corePort(w, 'readVariable')
Map vref = ((Map) (doc.refs ?: [:])).get(a.ref)
Map cell = readVariable(coreVarId(doc.id, coreRefId(doc, a.ref)))
value = cell != null ? cell.value : vref?.initial
}
run.returned = value
coreLog(w, 'returned', [run: run.id, value: value, path: path])
coreFinish(w, run, 'returned')
return
}
if ((type == 'command' || type == 'customCommand') && (a.refs instanceof List || coreIsEventDevice(doc, a.ref))) {
coreFanOut(w, run, doc, a, path)
return
}
if (type == 'command' || type == 'customCommand') { coreCommand(w, run, doc, a, path); return }
if (type == 'setVariable') {
String vid = coreVarId(doc.id, coreRefId(doc, a.ref))
Object before = coreVariableBefore(w, vid, ((Map) (doc.refs ?: [:])).get(a.ref))
Object value = a.value
if (a.op != null) {
Map got = coreSetVarOp(w, run, doc, a, vid)
if (got.error) { coreFail(w, run, 'SET_VARIABLE_FAILED', "${a.op} at ${path}: ${got.error}".toString()); return }
value = got.value
}
Closure writeVariable = corePort(w, 'writeVariable')
writeVariable(vid, [value: value, writtenBy: run.id, at: coreNow(w)])
coreLog(w, 'variableWritten', [run: run.id, variable: vid, value: value])
if (!vid.startsWith(HUB_VARIABLE_PREFIX) || !((Map) w.port).containsKey('hubEchoesVariables')) {
coreSchedule(w, [at: coreNow(w), kind: 'variableChanged', variable: vid, value: value, before: before,
depth: (run.depth as int) + 1])
}
return
}
if (type == 'wait') { coreWait(w, run, doc, a, path); return }
if (type == 'http' || type == 'ping') { coreNet(w, run, doc, a, path); return }
if (type == 'file') { coreFile(w, run, doc, a, path); return }
if (type == 'later') { coreLater(w, run, rec, doc, a, path); return }
if (type == 'cancelDelays') {
int n = 0
new ArrayList(w.runs.values()).each { Object ro ->
Map r = ro
if (r.rule == rec.id && r.status == 'waiting' && ((Map) r.wait)?.kind == 'delay' && ((Map) ((Map) r.wait).action)?.cancelable == true) {
coreCancelRun(w, r, 'delaysCancelled')
n++
}
}
coreLog(w, 'delaysCancelled', [run: run.id, cancelled: n, path: path])
return
}
if (type == 'setRuleBoolean' || type == 'controlRule') { coreRuleControl(w, run, rec, doc, a, path); return }
if (type == 'if') { coreDecide(w, run, doc, a, path); return }
if (type == 'repeat') { coreRepeatStart(w, run, doc, a, path); return }
if (type == 'stopRepeat') { coreStopRepeats(w, run, path); return }
if (type == 'byMode') { coreByMode(w, run, doc, a, path); return }
if (type == 'flashSwitches' || type == 'fade') { coreGradualStart(w, run, doc, a, path); return }
if (type == 'stopGradual') { coreStopGradual(w, rec.id as String, a.what as String, null, path, run.id); return }
if (type == 'track') { coreTrack(w, run, doc, a, path); return }
if (type == 'runRule') {
String target = coreRefId(doc, a.ref)
Map tgt = w.rules.get(target)
if ((run.depth as int) >= CORE_MAX_CALL_DEPTH) {
coreFail(w, run, 'CALL_DEPTH', "call depth over ${CORE_MAX_CALL_DEPTH}"); return
}
Map caller = a.wait == true ? [rule: rec.id, run: run.id] : null
if (tgt == null && ((Map) w.port).containsKey('invoke')) {
Closure invoke = corePort(w, 'invoke')
Map msg = [target: target, from: rec.id, run: run.id, depth: (run.depth as int) + 1]
if (caller != null) msg.caller = caller
if (a.containsKey('input')) msg.input = a.input
invoke(msg)
coreLog(w, 'invoked', [run: run.id, target: target, path: path])
if (caller != null) coreWaitForReturn(w, run, a, target, path)
return
}
if (tgt == null) { coreFail(w, run, 'CALL_MISSING', "no rule ${target}"); return }
if (!coreInvocable((Map) tgt.doc)) {
coreFail(w, run, 'CALL_NOT_INVOCABLE',
"${target} has no enabled 'another rule runs this one' trigger, so it cannot be run by this rule".toString())
return
}
Map call = [kind: 'ruleInvocation', from: run.id]
if (caller != null) call.caller = caller
if (a.containsKey('input')) call.input = a.input
((List) (((Map) tgt.doc).triggers ?: [])).eachWithIndex { Object t, int ti ->
if (!call.containsKey('trigger') && t instanceof Map && ((Map) t).kind == 'ruleInvocation') call.trigger = ti
}
if (caller != null) coreWaitForReturn(w, run, a, target, path)
coreConsider(w, tgt, call, (run.depth as int) + 1)
coreNotRunAnswer(w, call)
return
}
coreFail(w, run, 'UNKNOWN_ACTION', "unknown action type ${type}")
}
void coreLater(Map w, Map run, Map rec, Map doc, Map a, String path) {
Long ms = coreDelayMs(w, run, doc, a, path)
if (ms == null) return
int pending = w.runs.values().count { Object r -> ((Map) r).rule == rec.id && ((Map) r).later != null && ((Map) r).status == 'waiting' } as int
if (pending >= LIMIT_PARALLEL_RUNS) {
coreFail(w, run, 'LATER_LIMIT', "the steps at ${path} were not set aside: ${LIMIT_PARALLEL_RUNS} lots of this rule's steps already wait to be done later".toString())
return
}
w.nextRun = w.nextRun + 1
String rid = "run${w.nextRun}".toString()
Map cause = new LinkedHashMap((Map) run.cause)
cause.remove('caller')
String token = coreNewToken(w)
long now = coreNow(w)
Map child = [id: rid, rule: rec.id, epoch: run.epoch, status: 'waiting', cause: cause, later: run.id,
frames: [[list: (a.actions ?: []), index: 0, path: "${path}.actions".toString()]],
wait: [token: token, kind: 'delay', action: a, path: path, since: now],
inFailure: run.inFailure, depth: run.depth, startedAt: now, steps: 0, errors: [],
restartEpoch: coreEpoch(w), recovering: false]
if (run.waitEvent != null) child.waitEvent = run.waitEvent
w.runs.put(rid, child)
coreLog(w, 'runStarted', [run: rid, rule: rec.id, epoch: run.epoch, cause: 'later', from: run.id])
coreSchedule(w, [at: now + ms, kind: 'resume', run: rid, token: token, epoch: run.epoch])
coreLog(w, 'waiting', [run: rid, kind: 'later', token: token, ms: ms])
}
String coreRunNow(Map w, String ruleId, Object by) {
Map rec = w.rules.get(ruleId)
if (rec == null) return 'noRule'
return coreConsider(w, rec, [kind: 'manual', by: by], 0)
}
void coreRunActions(Map w, String ruleId, String from, int depth) {
Map rec = w.rules.get(ruleId)
if (rec == null) return
if (depth > CORE_MAX_CALL_DEPTH) { coreLog(w, 'callRefused', [rule: ruleId, from: from, why: 'depth']); return }
coreConsider(w, rec, [kind: 'manual', by: from], depth)
}
void coreInvoked(Map w, String ruleId, Map call, int depth) {
Map rec = w.rules.get(ruleId)
if (rec == null) return
Map c = [kind: 'ruleInvocation', from: call.from]
if (call.caller instanceof Map) c.caller = call.caller
if (call.containsKey('input') && call.input != null) c.input = call.input
if (depth > CORE_MAX_CALL_DEPTH) { coreLog(w, 'callRefused', [rule: ruleId, from: call.from, why: 'depth']); coreNotRunAnswer(w, c); return }
((List) (((Map) rec.doc).triggers ?: [])).eachWithIndex { Object t, int ti ->
if (!c.containsKey('trigger') && t instanceof Map && ((Map) t).kind == 'ruleInvocation') c.trigger = ti
}
if (!c.containsKey('trigger')) { coreLog(w, 'callRefused', [rule: ruleId, from: call.from, why: 'noInvocationTrigger']); coreNotRunAnswer(w, c); return }
coreConsider(w, rec, c, depth)
coreNotRunAnswer(w, c)
}
void coreWaitForReturn(Map w, Map run, Map a, String target, String path) {
String token = coreNewToken(w)
run.status = 'waiting'
run.wait = [token: token, kind: 'forReturn', action: a, path: path, since: coreNow(w), callee: target]
coreSchedule(w, [at: coreNow(w) + ((a.timeoutSeconds as long) * 1000L), kind: 'waitTimeout', run: run.id, token: token, epoch: run.epoch])
coreLog(w, 'waiting', [run: run.id, kind: 'forReturn', token: token, callee: target])
}
void coreNotRunAnswer(Map w, Map cause) {
if (!(cause.caller instanceof Map) || cause.started == true || cause.queued == true) return
coreSendAnswer(w, cause.caller, [value: null, how: 'notRun'])
}
void coreSendAnswer(Map w, Map caller, Map answer) {
if (w.rules.containsKey(caller.rule)) {
coreReturned(w, caller.run as String, answer.value, answer.how as String)
} else if (((Map) w.port).containsKey('returnTo')) {
Closure f = corePort(w, 'returnTo'); f([rule: caller.rule, run: caller.run, value: answer.value, how: answer.how])
}
}
void coreReturned(Map w, String runId, Object value, String how) {
Map run = w.runs.get(runId)
if (run == null || run.status != 'waiting' || ((Map) run.wait)?.kind != 'forReturn') return
Map a = ((Map) run.wait).action
Map doc = ((Map) w.rules.get(run.rule)).doc
coreLog(w, 'answered', [run: run.id, value: value, how: how])
if (a.result instanceof String && value != null) {
String vid = coreVarId(doc.id, coreRefId(doc, a.result))
Object before = coreVariableBefore(w, vid, ((Map) (doc.refs ?: [:])).get(a.result))
try {
Closure writeVariable = corePort(w, 'writeVariable')
writeVariable(vid, [value: value, writtenBy: run.id, at: coreNow(w)])
coreLog(w, 'variableWritten', [run: run.id, variable: vid, value: value])
if (!vid.startsWith(HUB_VARIABLE_PREFIX) || !((Map) w.port).containsKey('hubEchoesVariables')) {
coreSchedule(w, [at: coreNow(w), kind: 'variableChanged', variable: vid, value: value, before: before, depth: (run.depth as int) + 1])
}
} catch (Exception e) {
coreLog(w, 'answerNotKept', [run: run.id, why: e.message])
}
}
coreWake(w, run, (String) ((Map) run.wait).token, 'answered')
}
void coreDecide(Map w, Map run, Map doc, Map a, String path) {
boolean fallThrough = (doc.ifUnknown ?: 'stop') == 'otherwise'
List missed = []
List branches = [[condition: a.condition, then: a.then, path: "${path}.then".toString()]]
((List) (a.elseIf ?: [])).eachWithIndex { Object e, int i ->
Map b = e
branches << [condition: b.condition, then: b.then, path: "${path}.elseIf.${i}.then".toString()]
}
for (Object bo : branches) {
Map b = bo
Map out = logicEvaluate((Map) b.condition) { Map node, String p -> coreLeaf(w, doc, node, run.recovering == true, run.cause, run) }
coreLog(w, 'decision', [run: run.id, path: path, value: out.value, trace: out.trace])
if (out.value == LOGIC_TRUE) {
((List) run.frames) << [list: (b.then ?: []), index: 0, path: b.path]
return
}
if (out.value == LOGIC_UNKNOWN) {
if (fallThrough) {
coreLog(w, 'decisionUnknownSkipped', [run: run.id, path: path, branch: b.path,
why: 'a reading in this condition could not be read, so the rule treats it as not met (ifUnknown: otherwise)'])
missed << b.path
continue
}
coreFail(w, run, 'DECISION_UNKNOWN',
"the condition at ${path} could not be evaluated, so neither branch was taken")
return
}
}
if (a.otherwise instanceof List) {
if (missed) coreLog(w, 'decisionElseOnUnknown', [run: run.id, path: path, branch: "${path}.otherwise".toString(), missed: missed,
why: 'a reading was missing, so the ELSE ran (ifUnknown: otherwise)'])
((List) run.frames) << [list: a.otherwise, index: 0, path: "${path}.otherwise".toString()]
}
}
void coreFail(Map w, Map run, String code, String message) {
((List) run.errors) << [code: code, message: message, at: coreNow(w)]
coreLog(w, 'runError', [run: run.id, code: code, message: message, inFailure: run.inFailure])
if (run.inFailure) { coreFinish(w, run, 'failedInFailurePath'); return }
if (run.inCancel) { coreFinish(w, run, 'failedInCancelPath'); return }
Map rec = w.rules.get(run.rule)
Map doc = rec.doc
String policy = (doc.errorPolicy ?: 'stopRun') as String
if (policy == 'continue') return
if (policy == 'failurePath') {
run.inFailure = true
run.status = 'running'
run.wait = null
run.frames = [[list: (doc.onFailure ?: []), index: 0, path: 'onFailure']]
return
}
coreFinish(w, run, 'failed')
}
void coreFinish(Map w, Map run, String how) {
if (run.status in ['done', 'failed', 'cancelled']) return
run.status = (how == 'completed' || how == 'gateFalse') ? 'done' : (how.startsWith('failed') ? 'failed' : 'done')
run.endedHow = how
run.wait = null
Map rec = w.rules.get(run.rule)
((Set) rec.running).remove(run.id)
coreLog(w, 'runEnded', [run: run.id, rule: run.rule, how: how])
if (run.background == true) return
if (((Map) run.cause)?.caller instanceof Map) coreSendAnswer(w, ((Map) run.cause).caller, [value: run.returned, how: how])
if (run.later == null && !rec.queue.isEmpty() && coreAdmissible(rec)) {
Map next = rec.queue.remove(0)
coreStartRun(w, rec, next, 0)
}
}
void coreWait(Map w, Map run, Map doc, Map a, String path) {
String token = coreNewToken(w)
String kind = a.waitKind as String
if (kind == 'until' && a.staysSeconds == null) {
Map out = logicEvaluate((Map) a.predicate) { Map node, String p -> coreLeaf(w, doc, node, run.recovering == true, run.cause, run) }
if (out.value == LOGIC_TRUE) { coreLog(w, 'waitSatisfiedOnEntry', [run: run.id, path: path]); return }
}
Long delayMs = kind == 'delay' ? coreDelayMs(w, run, doc, a, path) : 0L
if (delayMs == null) return
if (kind == 'delay' && delayMs == 0L) { coreLog(w, 'noDelay', [run: run.id, path: path]); return }
run.status = 'waiting'
run.wait = [token: token, kind: kind, action: a, path: path, since: coreNow(w)]
if (kind == 'delay') {
coreSchedule(w, [at: coreNow(w) + delayMs, kind: 'resume',
run: run.id, token: token, epoch: run.epoch])
} else if (a.timeoutSeconds != null) {
coreSchedule(w, [at: coreNow(w) + ((a.timeoutSeconds as long) * 1000L), kind: 'waitTimeout',
run: run.id, token: token, epoch: run.epoch])
}
Map said = [run: run.id, kind: kind, token: token]
if (a.modeSeconds != null || a.randomSeconds != null) said.ms = delayMs
coreLog(w, 'waiting', said)
if (kind == 'forEvents' || a.staysSeconds != null) coreWaitArm(w, run, doc, a)
}
Long coreDelayMs(Map w, Map run, Map doc, Map a, String path) {
Object secs = a.seconds
if (a.modeSeconds instanceof List) {
Closure readMode = corePort(w, 'readMode')
Object mode = readMode()
Map hit = ((List) a.modeSeconds).find { Object m -> m instanceof Map && ((Map) ((Map) (doc.refs ?: [:])).get(((Map) m).ref))?.name == mode }
if (hit != null) secs = hit.seconds
}
long ms = 0L
if (secs instanceof Map) {
BigDecimal n = coreNumber(coreOperand(w, run, doc, secs))
List range = FIELD_RANGES.delaySeconds
BigDecimal s = n == null ? null : n * ([minutes: 60, hours: 3600].get(((Map) secs).unit) ?: 1)
if (s == null || s < 0 || s > (range[1] as BigDecimal)) {
coreFail(w, run, 'DELAY_UNUSABLE', "the delay at ${path} read ${n} from its variable, which is not a usable length".toString())
return null
}
ms = (s * 1000).longValue()
} else if (secs != null) ms = (secs as long) * 1000L
if (a.randomSeconds != null) ms += (long) (Math.random() * ((a.randomSeconds as long) * 1000L + 1L))
return ms
}
void coreWaitArm(Map w, Map run, Map doc, Map a) {
Map pending = run.wait
pending.seen = []
pending.stays = [:]
if (a.waitKind == 'until') { coreWaitStay(w, run, doc, 'p', a.predicate, a.staysSeconds as long); return }
((List) a.events).eachWithIndex { Object eo, int i ->
Map ev = eo
if (ev.disabled == true || run.wait?.token != pending.token) return
if (ev.kind == 'duration') coreWaitStay(w, run, doc, i.toString(), ev.predicate, ev.seconds as long)
if (ev.kind != 'schedule') return
LocalDateTime next = coreNextScheduled(w, doc, ev, coreLocal(w, coreNow(w)).toLocalDateTime())
Map occ = next == null ? null : timeResolveOccurrence(next, coreZone(w))
if (occ == null || occ.status == TIME_UNRESOLVABLE) return
coreSchedule(w, [at: ((ZonedDateTime) occ.instant).toInstant().toEpochMilli(), kind: 'waitEvent', run: run.id,
token: "${pending.token}.e${i}".toString(), wait: pending.token, key: i.toString(), epoch: run.epoch])
}
}
void coreWaitStay(Map w, Map run, Map doc, String key, Map predicate, long seconds) {
Map pending = run.wait
Map stays = pending.stays
Map st = stays.get(key)
Map out = logicEvaluate(predicate) { Map node, String p -> coreLeaf(w, doc, node, true, run.cause) }
if (out.value != LOGIC_TRUE) {
if (st != null) {
stays.remove(key)
Closure cancel = corePort(w, 'cancel')
cancel(st.token)
coreLog(w, 'stayBroken', [run: run.id, key: key])
}
return
}
if (st != null) return
String token = coreNewToken(w)
long at = coreNow(w) + seconds * 1000L
stays.put(key, [token: token, restartEpoch: coreEpoch(w)])
coreSchedule(w, [at: at, kind: 'waitStay', run: run.id, token: token, wait: pending.token, key: key, epoch: run.epoch])
coreLog(w, 'stayArmed', [run: run.id, key: key, until: at])
}
void coreWaitStayDue(Map w, Map run, Map t) {
Map pending = run.wait
Map st = ((Map) pending.stays)?.get(t.key)
if (st == null || st.token != t.token) return
((Map) pending.stays).remove(t.key)
Map doc = ((Map) w.rules.get(run.rule)).doc
Map a = pending.action
Map ev = t.key == 'p' ? null : ((List) a.events)[t.key as int]
Map predicate = (ev == null ? a.predicate : ev.predicate)
long seconds = (ev == null ? a.staysSeconds : ev.seconds) as long
Map out = logicEvaluate(predicate) { Map node, String p -> coreLeaf(w, doc, node, true, run.cause) }
if ((st.restartEpoch as int) != coreEpoch(w) || out.value != LOGIC_TRUE) { coreWaitStay(w, run, doc, t.key as String, predicate, seconds); return }
coreWaitSeen(w, run, t.key as String, null)
}
void coreWaitSeen(Map w, Map run, String key, Map said) {
Map pending = run.wait
Map a = pending.action
if (said != null) run.waitEvent = said
if (key == 'p') { run.waitOutcome = 'event'; coreWake(w, run, (String) pending.token, 'predicate'); return }
List seen = pending.seen
if (!seen.contains(key)) seen << key
List want = []
((List) a.events).eachWithIndex { Object ev, int i -> if (((Map) ev).disabled != true) want << i.toString() }
coreLog(w, 'waitEventSeen', [run: run.id, key: key, seen: seen.size(), of: want.size()])
if (a.match != 'all' || seen.containsAll(want)) { run.waitOutcome = 'event'; coreWake(w, run, (String) pending.token, 'event') }
}
void coreAfterAdmission(Map w, Map adm) {
coreWakeWaits(w, adm)
coreDurations(w, adm)
}
void coreWakeWaits(Map w, Map adm) {
Map event = adm.event
new ArrayList(w.runs.values()).each { Object ro ->
Map run = ro
if (run.status != 'waiting' || run.wait == null) return
if (!coreRunLive(w, run)) return
Map pending = run.wait
Map a = pending.action
Map rec = w.rules.get(run.rule)
Map doc = rec.doc
if (pending.kind == 'forEvent') {
if (event.kind == 'attribute' && coreRefId(doc, a.ref) == event.device &&
(a.attribute == null || a.attribute == event.attribute) &&
(!a.containsKey('value') || a.value == event.value)) {
run.waitEvent = coreEventSaid(event, [kind: event.kind])
run.waitOutcome = 'event'
coreWake(w, run, (String) pending.token, 'event')
}
} else if (pending.kind == 'forEvents') {
((List) a.events).eachWithIndex { Object eo, int i ->
Map ev = eo
if (run.wait?.token != pending.token || ev.disabled == true) return
if (ev.kind == 'duration') coreWaitStay(w, run, doc, i.toString(), ev.predicate, ev.seconds as long)
else if (ev.kind != 'schedule' && coreTriggerMatches(w, rec, ev, -1, adm)) coreWaitSeen(w, run, i.toString(), coreEventSaid(event, [kind: event.kind]))
}
} else if (pending.kind == 'until' && a.staysSeconds != null) {
coreWaitStay(w, run, doc, 'p', a.predicate, a.staysSeconds as long)
} else if (pending.kind == 'until') {
Map out = logicEvaluate((Map) a.predicate) { Map node, String p -> coreLeaf(w, doc, node, run.recovering == true, run.cause, run) }
if (out.value == LOGIC_TRUE) coreWake(w, run, (String) pending.token, 'predicate')
}
}
}
boolean coreHoldWhilePaused(Map w, Map run, String token, String kind, String why) {
Map rec = w.rules.get(run.rule)
if (rec == null || !rec.paused || ((Map) rec.doc).pauseMode != 'hold') return false
if (!(run.held instanceof Map)) {
run.held = [kind: kind, token: token, why: why]
coreLog(w, 'held', [run: run.id, kind: kind])
}
return true
}
void coreWake(Map w, Map run, String token, String why) {
if (run.status != 'waiting' || run.wait == null || run.wait.token != token) return
if (coreHoldWhilePaused(w, run, token, 'wake', why)) return
if (!coreRunLive(w, run)) {
coreLog(w, 'resumeDropped', [run: run.id, why: run.superseded ? 'superseded' : 'notLive'])
coreFinish(w, run, run.superseded ? 'superseded' : 'notLive')
return
}
if (!coreRecover(w, run)) return
run.elapsed = ((coreNow(w) - (run.wait.since as long)).intdiv(1000L)) as long
run.wait = null
run.status = 'running'
run.steps = 0
coreLog(w, 'resumed', [run: run.id, why: why])
coreStep(w, run)
}
boolean coreRecover(Map w, Map run) {
if ((run.restartEpoch as int) == coreEpoch(w)) return true
Map a = run.wait.action
String policy = (run.background == true ? 'catchUp' : a.onRestart ?: 'reevaluate') as String
run.restartEpoch = coreEpoch(w)
run.recovering = true
coreLog(w, 'recovering', [run: run.id, policy: policy, path: run.wait.path])
if (policy == 'skip') {
coreFinish(w, run, 'skippedAfterRestart')
return false
}
if (policy == 'reevaluate') {
Map rec = w.rules.get(run.rule)
String gate = coreGate(w, rec, true, run.cause)
if (gate != LOGIC_TRUE) {
coreFinish(w, run, gate == LOGIC_UNKNOWN ? 'unknownAfterRestart' : 'gateFalseAfterRestart')
return false
}
}
return true
}
void coreWaitTimedOut(Map w, Map run, String token) {
if (run.status != 'waiting' || run.wait == null || run.wait.token != token) return
if (coreHoldWhilePaused(w, run, token, 'timeout', 'timeout')) return
if (!coreRecover(w, run)) return
Map a = run.wait.action
if (run.wait.kind == 'forReply') {
String why = (run.remove('netError') ?: "no answer within ${a.timeoutSeconds ?: NET_DEFAULT_TIMEOUT_SECONDS} seconds") as String
run.wait = null
run.status = 'running'
coreFail(w, run, a.type == 'ping' ? 'PING_FAILED' : a.type == 'deviceEnabled' ? 'DEVICE_ENABLE_FAILED' : 'HTTP_FAILED', why)
coreStep(w, run)
return
}
if (run.wait.kind in ['forEvent', 'forEvents', 'until']) run.waitEvent = [timeout: true]
if (run.wait.kind in ['forEvent', 'forEvents', 'until']) run.waitOutcome = 'timeout'
run.elapsed = ((coreNow(w) - (run.wait.since as long)).intdiv(1000L)) as long
String behaviour = (a.onTimeout ?: 'stopRun') as String
coreLog(w, 'waitTimeout', [run: run.id, behaviour: behaviour])
run.wait = null
run.status = 'running'
run.steps = 0
if (behaviour == 'continue') { coreStep(w, run); return }
if (behaviour == 'failurePath') {
coreFail(w, run, 'WAIT_TIMEOUT', 'wait timed out')
coreStep(w, run)
return
}
coreFinish(w, run, 'waitTimedOut')
}
void coreRepeatStart(Map w, Map run, Map doc, Map a, String path) {
List frames = run.frames
Map frame = [list: (a.steps ?: []), index: 0, path: "${path}.steps".toString(), action: a, repeat: [path: path, pass: 0]]
frames << frame
if (!coreRepeatPass(w, run, doc, frame, true)) frames.remove(frames.size() - 1)
}
boolean coreRepeatPass(Map w, Map run, Map doc, Map frame, boolean first) {
Map a = frame.action
Map st = frame.repeat
Map rec = w.rules.get(run.rule)
String kind = a.repeatKind as String
String why = null
if (st.stopped == true) why = 'stopped'
else if (a.times != null && (st.pass as int) >= (a.times as int)) why = 'times'
else if (!first && rec.paused && doc.pauseMode != 'hold') why = 'paused'
else if (kind == 'while' || (kind == 'until' && !first)) {
Map out = logicEvaluate((Map) a.condition) { Map node, String p -> coreLeaf(w, doc, node, run.recovering == true, run.cause, run) }
coreLog(w, 'decision', [run: run.id, path: st.path, value: out.value, trace: out.trace])
if (out.value == LOGIC_UNKNOWN) {
coreFail(w, run, 'REPEAT_UNKNOWN', "the condition of the repeat at ${st.path} could not be evaluated, so it did not go round again".toString())
return false
}
if ((out.value == LOGIC_TRUE) != (kind == 'while')) why = kind == 'while' ? 'whileFalse' : 'untilTrue'
}
if (why == null && (a.everySeconds == null || (a.everySeconds as long) < 60L) && (st.pass as int) >= LIMIT_REPEAT_PASSES) {
coreFail(w, run, 'REPEAT_LIMIT', "the repeat at ${st.path} went round ${LIMIT_REPEAT_PASSES} times, the most HAI allows, so it was stopped".toString())
return false
}
if (why != null) {
coreLog(w, 'repeatEnded', [run: run.id, path: st.path, passes: st.pass, why: why])
return false
}
st.pass = (st.pass as int) + 1
st.at = coreNow(w)
frame.index = 0
coreLog(w, 'repeatPass', [run: run.id, path: st.path, pass: st.pass])
return true
}
boolean coreRepeatNext(Map w, Map run, Map frame) {
Map a = frame.action
Map st = frame.repeat
Map doc = ((Map) w.rules.get(run.rule)).doc
if (st.armed == true) { st.remove('armed'); return coreRepeatPass(w, run, doc, frame, false) }
long due = (st.at as long) + (a.everySeconds == null ? LIMIT_REPEAT_MIN_MS : (a.everySeconds as long) * 1000L)
if ((a.everySeconds == null && due <= coreNow(w)) || st.stopped == true || (a.times != null && (st.pass as int) >= (a.times as int))) {
return coreRepeatPass(w, run, doc, frame, false)
}
String token = coreNewToken(w)
due = Math.max(coreNow(w), due)
st.armed = true
run.status = 'waiting'
run.wait = [token: token, kind: 'repeat', action: a, path: st.path, since: coreNow(w)]
coreSchedule(w, [at: due, kind: 'resume', run: run.id, token: token, epoch: run.epoch])
coreLog(w, 'waiting', [run: run.id, kind: 'repeat', token: token])
return true
}
void coreStopRepeats(Map w, Map run, String path) {
int n = 0
new ArrayList(w.runs.values()).each { Object ro ->
Map r = ro
if (r.rule != run.rule || !(r.status in ['running', 'waiting'])) return
((List) r.frames).each { Object fo -> Map f = fo; if (f.repeat instanceof Map) { ((Map) f.repeat).stopped = true; n++ } }
Map wt = r.wait
if (r.status == 'waiting' && wt?.kind == 'repeat') coreSchedule(w, [at: coreNow(w), kind: 'resume', run: r.id, token: wt.token, epoch: r.epoch])
}
coreLog(w, 'repeatsStopped', [run: run.id, repeats: n, path: path])
}
void coreByMode(Map w, Map run, Map doc, Map a, String path) {
Closure readMode = corePort(w, 'readMode')
Object mode = readMode()
if (mode == null) { coreFail(w, run, 'MODE_UNKNOWN', "the mode is not known, so no case of the step at ${path} was chosen".toString()); return }
List cases = (a.cases ?: [])
for (int i = 0; i < cases.size(); i++) {
Map c = cases[i]
if (((List) (c.modes ?: [])).contains(mode)) {
coreLog(w, 'modeCase', [run: run.id, path: path, mode: mode, chose: i])
((List) run.frames) << [list: (c.then ?: []), index: 0, path: "${path}.cases.${i}.then".toString()]
return
}
}
coreLog(w, 'modeCase', [run: run.id, path: path, mode: mode, chose: 'otherwise'])
if (a.otherwise instanceof List) ((List) run.frames) << [list: a.otherwise, index: 0, path: "${path}.otherwise".toString()]
}
void coreGradualStart(Map w, Map run, Map doc, Map a, String path) {
Map rec = w.rules.get(run.rule)
String what = (a.type == 'flashSwitches' ? 'flash' : (a.attribute ?: 'level')) as String
List keys = (a.refs ?: [])
coreStopGradual(w, rec.id as String, what, keys.collect { coreRefId(doc, it) } as Set, path, run.id)
Map g = [path: path, what: what, tick: 0]
if (a.type == 'flashSwitches') {
g.n = a.times != null ? (a.times as int) * 2 : null
g.stepMs = (a.seconds as long) * 1000L
} else {
Map from = [:]
BigDecimal most = BigDecimal.ZERO
keys.each { Object k ->
String dev = coreRefId(doc, k)
BigDecimal v = what == 'level' && coreCurrent(w, dev, 'switch') == 'off' ? BigDecimal.ZERO : coreNumber(coreCurrent(w, dev, what))
if (v == null) { coreLog(w, 'fadeSkipped', [run: run.id, ref: k, path: path, why: "its ${what} is not known".toString()]); return }
from.put(k, v)
most = most.max((coreNumber(a.to) - v).abs())
}
long secs = a.seconds as long
int units = (what == 'level' ? most : most.divide(new BigDecimal(10), 0, java.math.RoundingMode.CEILING)).intValue()
int n = a.stepSeconds != null ? (int) Math.max(1L, Math.round(secs / (a.stepSeconds as double))) :
Math.max(1, Math.min(units, (int) Math.min(secs, (long) FADE_MAX_STEPS)))
g.n = Math.min(n, LIMIT_REPEAT_PASSES)
g.stepMs = (secs * 1000L).intdiv(g.n as int)
g.from = from
}
w.nextRun = w.nextRun + 1
String rid = "run${w.nextRun}".toString()
Map cause = new LinkedHashMap((Map) (run.cause ?: [:]))
['caller', 'started', 'queued', 'input'].each { cause.remove(it) }
Map bg = [id: rid, rule: rec.id, epoch: rec.epoch, status: 'running', cause: cause, background: true, startedBy: run.id,
frames: [[list: [], index: 0, path: path, action: a, gradual: g]], wait: null,
inFailure: false, depth: run.depth, startedAt: coreNow(w), steps: 0, errors: [],
restartEpoch: coreEpoch(w), recovering: false]
w.runs.put(rid, bg)
coreLog(w, 'gradualStarted', [run: run.id, background: rid, change: what, path: path, steps: g.n])
coreStep(w, bg)
}
boolean coreGradualNext(Map w, Map run, Map frame) {
Map g = frame.gradual
Map rec = w.rules.get(run.rule)
Map doc = rec.doc
String why = g.stopped == true ? 'stopped' : g.n != null && (g.tick as int) >= (g.n as int) ? 'done' :
g.n == null && (g.tick as int) >= LIMIT_REPEAT_PASSES * 2 ? 'limit' :
(g.tick as int) > 0 && rec.paused && doc.pauseMode != 'hold' ? 'paused' : null
if (why != null) {
coreLog(w, 'gradualEnded', [run: run.id, path: g.path, change: g.what, steps: g.tick, why: why])
return false
}
if ((g.tick as int) > 0 && g.armed != true) {
String token = coreNewToken(w)
g.armed = true
run.status = 'waiting'
run.wait = [token: token, kind: 'gradual', action: frame.action, path: g.path, since: coreNow(w)]
long due = ((g.startAt ?: coreNow(w)) as long) + (g.tick as long) * (g.stepMs as long)
coreSchedule(w, [at: Math.max(coreNow(w), due), kind: 'resume', run: run.id, token: token, epoch: run.epoch])
return true
}
g.remove('armed')
if ((g.tick as int) == 0) g.startAt = coreNow(w)
g.tick = (g.tick as int) + 1
frame.list = coreGradualSteps(doc, frame.action, g)
frame.index = 0
return true
}
List coreGradualSteps(Map doc, Map a, Map g) {
int k = g.tick as int
if (k < 1) return []
if (g.what == 'flash') return [[type: 'command', refs: a.refs, command: k % 2 == 1 ? 'on' : 'off']]
List out = []
BigDecimal to = coreNumber(a.to)
int n = g.n as int
((Map) (g.from ?: [:])).each { Object key, Object f ->
BigDecimal from = coreNumber(f)
Closure at = { int i -> (from + (to - from) * i / n).setScale(0, java.math.RoundingMode.HALF_UP).intValue() }
int v = at(k) as int
if (k > 1 && v == (at(k - 1) as int)) return
out << [type: 'command', ref: key, command: g.what == 'level' ? 'setLevel' : 'setColorTemperature', args: [v]]
}
return out
}
void coreStopGradual(Map w, String ruleId, String what, Set devs, String path, Object by) {
int n = 0
new ArrayList(w.runs.values()).each { Object ro ->
Map r = ro
if (r.rule != ruleId || r.background != true || !(r.status in ['running', 'waiting'])) return
Map f = ((List) r.frames).find { ((Map) it).gradual instanceof Map }
Map g = f?.gradual
if (g == null || g.stopped == true || (what != null && g.what != what)) return
if (devs != null) {
Map doc = ((Map) w.rules.get(ruleId)).doc
if (!((List) (((Map) f.action).refs ?: [])).any { devs.contains(coreRefId(doc, it)) }) return
}
g.stopped = true
n++
Map wt = r.wait
if (r.status == 'waiting' && wt?.kind == 'gradual') coreSchedule(w, [at: coreNow(w), kind: 'resume', run: r.id, token: wt.token, epoch: r.epoch])
}
if (n > 0 || devs == null) coreLog(w, 'gradualStopped', [run: by, change: what, stopped: n, path: path])
}
void coreTrack(Map w, Map run, Map doc, Map a, String path) {
if (!(run.fanOut instanceof List)) {
Map cause = (run.cause ?: [:])
String attr = a.attribute as String
Object v = cause.device == null ? null : cause.attribute == attr ? cause.value : coreCurrent(w, cause.device as String, attr)
if ((attr == 'switch' && !(v in ['on', 'off'])) || (attr == 'level' && coreNumber(v) == null)) {
coreLog(w, 'nothingToTrack', [run: run.id, path: path, device: cause.device, attribute: attr])
return
}
run.trackCommand = attr == 'switch' ? [command: v, args: []] :
[command: 'setLevel', args: [coreNumber(v).max(BigDecimal.ZERO).min(new BigDecimal(100)).setScale(0, java.math.RoundingMode.HALF_UP).intValue()]]
}
Map tc = run.trackCommand
coreFanOut(w, run, doc, [type: 'command', refs: a.refs, command: tc.command, args: tc.args], path)
if (!(run.fanOut instanceof List)) run.remove('trackCommand')
}
void coreNet(Map w, Map run, Map doc, Map a, String path) {
boolean http = a.type == 'http'
String token = coreNewToken(w)
Map req = [kind: a.type, rule: doc.id, run: run.id, token: token, timeoutSeconds: (a.timeoutSeconds ?: NET_DEFAULT_TIMEOUT_SECONDS) as int]
String to
if (http) {
req.method = a.method ?: 'get'
req.url = coreExpandText(w, run, doc, a.url as String)
if (schemaHubMaintenance(a.url)) req.approvedMaintenance = true
if (schemaHubSelfHost(schemaUrlHost(a.url))) req.namedHubSelf = true
Map hs = [:]
((Map) (a.headers ?: [:])).each { k, v -> hs.put(k as String, coreExpandText(w, run, doc, v as String)) }
if (hs) req.headers = hs
if (req.method == 'post') { req.body = coreExpandText(w, run, doc, (a.body ?: '') as String); req.contentType = a.contentType ?: 'application/json' }
to = schemaUrlHost(req.url)
} else {
req.host = coreExpandText(w, run, doc, a.host as String)
to = (req.host ==~ /[A-Za-z0-9.:-]{1,253}/) ? req.host as String : null
}
String problem = to == null ? (http ? 'the address is not an http or https address' : 'that is not a host name or address') :
!((Map) w.port).containsKey('net') ? 'this runtime cannot send requests' : null
if (problem == null) {
try { Closure f = corePort(w, 'net'); f(req) } catch (Exception e) { problem = e.message }
}
if (problem != null) { coreFail(w, run, http ? 'HTTP_REFUSED' : 'PING_REFUSED', "${path}: ${problem}".toString()); return }
coreLog(w, 'netSent', [run: run.id, kind: a.type, to: to, token: token, path: path])
if (!(http ? (a.result instanceof String || a.saveFile instanceof String) : (a.into instanceof Map && !((Map) a.into).isEmpty()))) return
run.status = 'waiting'
run.wait = [token: token, kind: 'forReply', action: a, path: path, since: coreNow(w)]
coreSchedule(w, [at: coreNow(w) + ((req.timeoutSeconds as long) * 1000L), kind: 'waitTimeout', run: run.id, token: token, epoch: run.epoch])
coreLog(w, 'waiting', [run: run.id, kind: 'forReply', token: token])
}
void coreNetReply(Map w, String runId, String token, Map reply) {
Map run = w.runs.get(runId)
Map r = (reply ?: [:])
coreLog(w, 'netReply', [run: runId, token: token, status: r.status, failed: r.error != null ? true : null])
if (run == null || run.status != 'waiting' || ((Map) run.wait)?.kind != 'forReply' || run.wait.token != token) return
Map a = run.wait.action
Map doc = ((Map) w.rules.get(run.rule)).doc
if (a.type == 'deviceEnabled') { coreDeviceEnabledReply(w, run, doc, token, r); return }
Map keep = [:]
String error = r.error != null ? r.error as String : null
if (error == null && a.type == 'http') {
int status = (r.status ?: 0) as int
String body = (r.body ?: '') as String
if (status < 200 || status > 299) error = "the server answered ${status}".toString()
else if (body.length() > NET_MAX_BODY_CHARS && !a.containsKey('rssItems')) error = "the answer is ${body.length()} characters, over ${NET_MAX_BODY_CHARS}".toString()
else if (a.saveFile instanceof String && (a.result != null || a.field != null)) error = 'the HTTP step cannot both write a file and keep an answer in a variable'
else if (a.saveFile instanceof String) {
String name = coreExpandText(w, run, doc, a.saveFile as String)
error = schemaFileProblem(name)
String text = body
if (error == null && a.containsKey('rssItems')) {
if (body.length() > NET_MAX_BODY_CHARS * 16) error = 'the RSS answer is too large to select safely'
else {
text = coreFirstRssItems(body, a.rssItems as int)
if (text == null) error = 'the answer is not an RSS feed with items'
}
}
if (error == null && a.select instanceof List) {
Object parsed = null
try { parsed = new groovy.json.JsonSlurper().parseText(body) }
catch (Exception e) { error = 'the answer is not JSON, so the requested stories cannot be selected' }
List picked = []
if (error == null) {
((List) a.select).each { Object path ->
if (error != null) return
Object value = parsed
((String) path).split('\\.').each { String part ->
if (error != null) return
if (value instanceof Map && ((Map) value).containsKey(part)) value = ((Map) value).get(part)
else if (value instanceof List && part ==~ /\d{1,4}/ && (part as int) < ((List) value).size()) value = ((List) value)[part as int]
else error = "the answer has no ${path}".toString()
}
if (error == null) picked << value
}
}
if (error == null) text = groovy.json.JsonOutput.toJson(picked)
}
if (error == null && text.length() > NET_MAX_BODY_CHARS) error = 'the selected answer is too large for a hub file'
if (error == null) {
try { corePort(w, 'file')([op: 'write', name: name, text: text]) }
catch (Exception e) { error = 'the hub could not write the response file' }
if (error == null) coreLog(w, 'fileWritten', [run: run.id, op: 'write', file: name, path: run.wait.path])
}
} else {
Object v = body
if (a.field) {
try { v = new groovy.json.JsonSlurper().parseText(body) } catch (Exception e) { error = 'the answer is not JSON, so it has no fields' }
((a.field as String).split('\\.') as List).each { String part ->
if (error != null) return
if (v instanceof Map && ((Map) v).containsKey(part)) v = ((Map) v).get(part)
else if (v instanceof List && part ==~ /\d{1,4}/ && (part as int) < ((List) v).size()) v = ((List) v)[part as int]
else error = "the answer has no ${a.field}".toString()
}
}
if (error == null) keep.put(a.result, v)
}
} else if (error == null) {
((Map) a.into).each { k, v -> keep.put(v, r.get(k)) }
}
keep.each { Object key, Object v -> if (error == null) error = coreKeep(w, run, doc, key, v) }
if (error != null) { run.netError = error; coreWaitTimedOut(w, run, token); return }
coreWake(w, run, token, 'answered')
}
String coreDeviceEnabledSend(Map w, Map run, Map doc, Map a, String path, int i, String phase) {
Object key = ((List) (a.refs ?: []))[i]
String id = (((Map) ((Map) (doc.refs ?: [:])).get(key))?.id ?: '') as String
String bare = id.startsWith('dev:') ? id.substring(4) : id
if (!(bare ==~ /\d{1,12}/)) return "${key} is not a hub device".toString()
String token = coreNewToken(w)
Map req = [kind: 'http', rule: doc.id, run: run.id, token: token, timeoutSeconds: NET_DEFAULT_TIMEOUT_SECONDS, namedHubSelf: true]
if (phase == 'set') {
req.putAll([method: 'post', url: CORE_DEVICE_DISABLE_URL, contentType: 'application/json',
body: groovy.json.JsonOutput.toJson([id: bare as long, disable: a.enabled != true])])
} else {
req.putAll([method: 'get', url: CORE_DEVICE_JSON_URL + bare])
}
try { Closure f = corePort(w, 'net'); f(req) } catch (Exception e) { return e.message }
run.status = 'waiting'
run.wait = [token: token, kind: 'forReply', action: a, path: path, since: coreNow(w), index: i, phase: phase]
coreSchedule(w, [at: coreNow(w) + (NET_DEFAULT_TIMEOUT_SECONDS as long) * 1000L, kind: 'waitTimeout', run: run.id, token: token, epoch: run.epoch])
coreLog(w, 'waiting', [run: run.id, kind: 'forReply', token: token])
return null
}
void coreDeviceEnabledReply(Map w, Map run, Map doc, String token, Map r) {
Map a = run.wait.action
String path = run.wait.path as String
int i = run.wait.index as int
String phase = run.wait.phase as String
Object key = ((List) a.refs)[i]
String name = (((Map) ((Map) (doc.refs ?: [:])).get(key))?.name ?: key) as String
boolean disable = a.enabled != true
int status = (r.status ?: 0) as int
String error = r.error != null ? r.error as String : (status < 200 || status > 299) ? "the hub answered ${status}".toString() : null
if (error == null && phase == 'check') {
Boolean flag = coreDeviceDisabledFlag(r.body)
if (flag == null) error = "the hub's record for ${name} has no disabled flag to read back".toString()
else if (flag != disable) error = "${name} is still ${flag ? 'disabled' : 'enabled'} after the hub's own call".toString()
}
if (error == null) {
if (phase == 'set') error = coreDeviceEnabledSend(w, run, doc, a, path, i, 'check')
else if (i + 1 < ((List) a.refs).size()) error = coreDeviceEnabledSend(w, run, doc, a, path, i + 1, 'set')
else {
coreLog(w, 'deviceEnabledSet', [run: run.id, enabled: !disable, refs: a.refs, path: path])
coreWake(w, run, token, 'answered')
}
if (error == null) return
}
run.netError = "${path}: ${name}: ${error}".toString()
coreWaitTimedOut(w, run, token)
}
Boolean coreDeviceDisabledFlag(Object body) {
Object doc = null
try { doc = new groovy.json.JsonSlurper().parseText((body ?: '') as String) } catch (Exception e) { return null }
if (!(doc instanceof Map)) return null
Object flag = ((Map) doc).device instanceof Map ? ((Map) ((Map) doc).device).disabled : ((Map) doc).disabled
return flag instanceof Boolean ? (Boolean) flag : null
}
String coreFirstRssItems(String body, int count) {
if (!(body =~ /(?is)<rss\b/) || !(body =~ /(?is)<channel\b/)) return null
java.util.regex.Matcher items = body =~ /(?is)<item(?:\s[^>]*)?>.*?<\/item>/
int first = -1
int last = -1
List<String> kept = []
while (items.find()) {
if (first < 0) first = items.start()
last = items.end()
if (kept.size() < count) kept << items.group()
}
if (first < 0 || last < 0) return null
return body.substring(0, first) + kept.join('') + body.substring(last)
}
String coreKeep(Map w, Map run, Map doc, Object key, Object v) {
Map ref = ((Map) (doc.refs ?: [:])).get(key)
String vid = coreVarId(doc.id, coreRefId(doc, key))
String type = ((ref?.scope == 'hub' ? invVariable(coreInv(w), ref.id as String)?.type : null) ?: ref?.type ?: 'string') as String
String name = (ref?.name ?: key) as String
if (v == null) return "the answer has no value for ${name}".toString()
Object value
if (type == 'number') {
value = coreNumber(v)
if (value == null) return "'${v}' is not a number, which ${name} holds".toString()
} else if (type == 'boolean') {
String b = v.toString().toLowerCase()
if (!(b in ['true', 'false'])) return "'${v}' is not true or false, which ${name} holds".toString()
value = b == 'true'
} else {
value = (v instanceof Map || v instanceof List) ? groovy.json.JsonOutput.toJson(v) : v.toString()
if ((value as String).length() > KEEP_MAX_CHARS) return "the value is ${(value as String).length()} characters; a variable keeps at most ${KEEP_MAX_CHARS}".toString()
}
Object before = coreVariableBefore(w, vid, ref)
try {
Closure writeVariable = corePort(w, 'writeVariable')
writeVariable(vid, [value: value, writtenBy: run.id, at: coreNow(w)])
} catch (Exception e) { return e.message }
coreLog(w, 'variableWritten', [run: run.id, variable: vid, value: value])
if (!vid.startsWith(HUB_VARIABLE_PREFIX) || !((Map) w.port).containsKey('hubEchoesVariables')) {
coreSchedule(w, [at: coreNow(w), kind: 'variableChanged', variable: vid, value: value, before: before, depth: (run.depth as int) + 1])
}
return null
}
void coreFile(Map w, Map run, Map doc, Map a, String path) {
String name = coreExpandText(w, run, doc, a.name as String)
Map req = [op: a.op, name: name]
if (a.op != 'delete') req.text = coreExpandText(w, run, doc, (a.text ?: '') as String)
String problem = schemaFileProblem(name) ?: (((Map) w.port).containsKey('file') ? null : 'this runtime cannot write files')
if (problem == null) {
try { Closure f = corePort(w, 'file'); f(req) } catch (Exception e) { problem = e.message }
}
if (problem != null) { coreFail(w, run, 'FILE_FAILED', "${path}: ${problem}".toString()); return }
coreLog(w, 'fileWritten', [run: run.id, op: a.op, file: name, path: path])
}
void coreEndpoint(Map w, String ruleId, Object value) {
Map rec = w.rules.get(ruleId)
if (rec == null) return
Map c = [kind: 'endpoint']
if (value != null && value != '') c.value = value
((List) (((Map) rec.doc).triggers ?: [])).eachWithIndex { Object t, int ti ->
if (!c.containsKey('trigger') && t instanceof Map && ((Map) t).kind == 'endpoint' && ((Map) t).disabled != true) c.trigger = ti
}
if (!c.containsKey('trigger')) { coreLog(w, 'endpointRefused', [rule: ruleId, why: 'noEndpointTrigger']); return }
coreLog(w, 'endpointCalled', [rule: ruleId, trigger: c.trigger])
coreConsider(w, rec, c, 0)
}
@Field static final Map CORE_CUSTOM_OP = [args: [], writes: [], reads: [], requires: [], ack: 'none', retry: false, risk: 'none', repeat: 'repeats', shape: 'none']
Map coreIntentOp(Map intent) { return intent.custom ? CORE_CUSTOM_OP : (schemaOperation(intent.command as String) ?: CORE_CUSTOM_OP) }
void coreCommand(Map w, Map run, Map doc, Map a, String path) {
Map rec = w.rules.get(run.rule)
String dev = coreRefId(doc, a.ref)
Map op = a.type == 'customCommand' ? CORE_CUSTOM_OP : (schemaOperation(a.command as String) ?: CORE_CUSTOM_OP)
Map d = coreDevice(w, dev)
if (d?.disabled == true) { coreLog(w, 'skipped', [run: run.id, path: path, why: 'disabled', device: dev]); return }
Map gone = coreDispatchCheck(w, d, dev, op)
if (gone != null) { coreFail(w, run, gone.code as String, gone.message as String); return }
List args = a.args instanceof List ? ((List) a.args).withIndex().collect { Object v, int i ->
if (v instanceof Map) return coreOperand(w, run, doc, v)
(v instanceof String && (a.type == 'customCommand' || ((Map) ((List) op.args)[i])?.type == 'string')) ? coreExpandText(w, run, doc, (String) v) : v
} : a.args
String bad = coreArgProblem(a, op, args)
if (bad != null) { coreFail(w, run, 'ARGS_FROM_VARIABLE', "${a.command} at ${path}: ${bad}".toString()); return }
String sendAs = a.command == 'toggle' ? coreToggleTo(w, dev) : null
List targets = sendAs != null ? [[attribute: 'switch', value: sendAs]] : null
if (COMMAND_BASIS.containsKey(a.command) && a.command != 'toggle' && a.command != 'alarmOff') {
Map built = coreBuild(w, dev, a.command as String, args)
if (built.error != null) { coreFail(w, run, 'CURRENT_VALUE_UNKNOWN', "${a.command} at ${path}: ${built.error}".toString()); return }
sendAs = built.send as String
args = built.args
targets = coreTargets([command: built.as, args: args], op)
}
if (targets == null) targets = coreTargets(a + [args: args], op)
for (Object to : targets) {
String slot = "device:${dev}:${((Map) to).attribute}".toString()
Map winner = coreOutranking(w, slot, run.rule as String)
if (winner != null) {
coreLog(w, 'suppressed', [run: run.id, slot: slot, heldBy: winner.owner, explicit: winner.explicit,
priority: winner.priority, path: path])
return
}
}
run.dispatchCount = ((run.dispatchCount ?: 0) as int) + 1
String intentId = "i${coreNextSeq(w)}".toString()
Map intent = [id: intentId, sk: run.dispatchCount, run: run.id, rule: rec.id, epoch: rec.epoch, device: dev,
command: a.command, args: args, sendAs: sendAs, targets: targets, state: CONF_REQUESTED, custom: a.type == 'customCommand',
requestedAt: coreNow(w), path: path, retries: 0]
String stepKey = coreStepKey(intent)
w.ledger.put(intentId, intent)
targets.each { Object to -> w.latestIntent.put("device:${dev}:${((Map) to).attribute}".toString(), intentId) }
boolean said = false
if (((Map) w.port).containsKey('dispatchNow')) {
intent.restartEpoch = coreEpoch(w)
coreLog(w, 'requested', [run: run.id, intent: intentId, device: dev, command: a.command, path: path])
said = true
intent.dispatchedAt = coreNow(w)
Closure sendNow = corePort(w, 'dispatchNow')
Object r = sendNow([at: coreNow(w), intent: intentId, stepKey: stepKey, rule: rec.id, run: run.id,
device: dev, command: sendAs ?: a.command, args: args])
if (r != 'defer') {
if (r instanceof Map) { coreSendRefused(w, run, intent, r); return }
intent.state = CONF_DISPATCHED
coreLog(w, 'dispatched', [intent: intentId, device: dev, command: a.command, path: path, run: run.id, same: true])
coreScheduleConfirmation(w, intent, op)
return
}
intent.remove('dispatchedAt')
}
if (((Map) w.port).containsKey('defer')) {
String token = coreNewToken(w)
intent.restartEpoch = coreEpoch(w)
run.status = 'waiting'
run.wait = [token: token, kind: 'dispatch', intent: intentId, action: a, path: path, since: coreNow(w)]
if (!said) coreLog(w, 'requested', [run: run.id, intent: intentId, device: dev, command: a.command, path: path])
Closure defer = corePort(w, 'defer')
defer([at: coreNow(w), kind: 'dispatch', run: run.id, intent: intentId, stepKey: stepKey, token: token, epoch: run.epoch])
return
}
intent.state = CONF_DISPATCHED
intent.dispatchedAt = coreNow(w)
coreDispatch(w, [at: coreNow(w), intent: intentId, rule: rec.id, run: run.id,
device: dev, command: sendAs ?: a.command, args: args])
coreLog(w, 'dispatched', [intent: intentId, device: dev, command: a.command, path: path])
coreScheduleConfirmation(w, intent, op)
}
Map coreDispatchCheck(Map w, Map d, String dev, Map op) {
if (d == null) return [code: 'DISPATCH_DEVICE_GONE', message: "device ${dev} is no longer present".toString()]
if (d.disabled == true) return [code: 'DISPATCH_DEVICE_DISABLED', message: "device ${dev} is disabled".toString()]
Set caps = (d.capabilities as Set)
List missing = ((List) (op?.requires ?: [])).findAll { !caps.contains(it) }
if (missing) return [code: 'DISPATCH_CAPABILITY', message: "device ${dev} no longer has ${missing}".toString()]
return null
}
Map coreOutranking(Map w, String slot, String rule) {
Map claims = w.holds.get(slot)
if (claims == null) return null
long now = coreNow(w)
List live = claims.values().findAll { (it.expiresAt as long) > now }
Map mine = live.find { it.owner == rule }
List others = live.findAll { it.owner != rule }
if (others.isEmpty()) return null
Map best = others.max { a, b -> coreClaimCompare((Map) a, b) }
if (mine == null) return best
return coreClaimCompare(best, mine) > 0 ? best : null
}
int coreClaimCompare(Map a, Map b) {
int c = (a.explicit ? 1 : 0) <=> (b.explicit ? 1 : 0)
if (c == 0) c = (a.priority as int) <=> (b.priority as int)
if (c == 0) c = (a.declaredAt as long) <=> (b.declaredAt as long)
if (c == 0) c = (a.seq as long) <=> (b.seq as long)
return c
}
String coreToggleTo(Map w, String dev) {
Object now = coreReadLive(w, dev, 'switch')?.value
if (!(now in ['on', 'off'])) {
Closure readBack = corePort(w, 'readBack')
now = ((Map) readBack(dev, 'switch'))?.value
}
return now == 'on' ? 'off' : now == 'off' ? 'on' : null
}
Object coreCurrent(Map w, String dev, String attr) {
Object v = coreReadLive(w, dev, attr)?.value
if (v != null) return v
Closure readBack = corePort(w, 'readBack')
return ((Map) readBack(dev, attr))?.value
}
Map coreBuild(Map w, String dev, String cmd, List args) {
if (cmd in ['openShade', 'closeShade', 'openValve', 'closeValve']) return [send: cmd.startsWith('open') ? 'open' : 'close', args: [], as: cmd]
if (cmd == 'toggleLevel') {
Object sw = coreCurrent(w, dev, 'switch')
if (!(sw in ['on', 'off'])) return [error: 'the switch is neither on nor off']
return sw == 'on' ? [send: 'off', args: [], as: 'off'] : [send: 'setLevel', args: [args[0]], as: 'setLevel']
}
if (cmd == 'togglePlay') {
Object st = coreCurrent(w, dev, 'status')
if (st == null) return [error: 'whether it is playing is not known']
return st == 'playing' ? [send: 'pause', args: [], as: 'pause'] : [send: 'play', args: [], as: 'play']
}
if (cmd == 'toggleMute') {
Object mu = coreCurrent(w, dev, 'mute')
if (!(mu in ['muted', 'unmuted'])) return [error: 'whether it is muted is not known']
return mu == 'muted' ? [send: 'unmute', args: [], as: 'unmute'] : [send: 'mute', args: [], as: 'mute']
}
if (cmd == 'toggleColorTemperature') {
Object sw = coreCurrent(w, dev, 'switch')
if (!(sw in ['on', 'off'])) return [error: 'the switch is neither on nor off']
return sw == 'on' ? [send: 'off', args: [], as: 'off'] : [send: 'setColorTemperature', args: args, as: 'setColorTemperature']
}
if (cmd == 'toggleColor') {
Object sw = coreCurrent(w, dev, 'switch')
if (!(sw in ['on', 'off'])) return [error: 'the switch is neither on nor off']
return sw == 'on' ? [send: 'off', args: [], as: 'off'] : [send: 'setColor', args: args, as: 'setColor']
}
String attr = cmd == 'adjustLevel' ? 'level' : cmd == 'adjustHeatingSetpoint' ? 'heatingSetpoint' : 'coolingSetpoint'
BigDecimal now = coreNumber(coreCurrent(w, dev, attr))
if (now == null) return [error: "the ${attr} is not known".toString()]
BigDecimal to = now + coreNumber(args[0])
String send = 'set' + attr.capitalize()
if (cmd == 'adjustLevel') {
to = to.max(BigDecimal.ZERO).min(new BigDecimal(100)).setScale(0, java.math.RoundingMode.HALF_UP)
return [send: send, args: [to.intValue()] + (args.size() > 1 && args[1] != null ? [args[1]] : []), as: send]
}
return [send: send, args: [to], as: send]
}
List coreTargets(Map a, Map op) {
String cmd = a.command as String
List out = []
if (cmd == 'on' || cmd == 'off') out << [attribute: 'switch', value: cmd]
else if (cmd == 'setLevel') {
out << [attribute: 'level', value: (a.args ?: [null])[0]]
out << [attribute: 'switch', value: 'on']
}
else if (cmd == 'setColorTemperature') out << [attribute: 'colorTemperature', value: (a.args ?: [null])[0]]
else if (cmd == 'lock' || cmd == 'unlock') out << [attribute: 'lock', value: cmd == 'lock' ? 'locked' : 'unlocked']
else if (cmd == 'open' || cmd == 'close') out << [attribute: 'door', value: cmd == 'open' ? 'open' : 'closed']
else if (cmd in ['siren', 'strobe', 'both', 'alarmOff']) out << [attribute: 'alarm', value: cmd == 'alarmOff' ? 'off' : cmd]
else if (cmd == 'setVolume') out << [attribute: 'volume', value: (a.args ?: [null])[0]]
else if (cmd == 'mute' || cmd == 'unmute') out << [attribute: 'mute', value: cmd == 'mute' ? 'muted' : 'unmuted']
else if (cmd == 'setColor') { out << [attribute: 'hue', value: (a.args ?: [null])[0]]; out << [attribute: 'saturation', value: (a.args ?: [null, null])[1]] }
else if (cmd in ['openShade', 'closeShade', 'openValve', 'closeValve']) out << [attribute: op.writes[0], value: cmd.startsWith('open') ? 'open' : 'closed']
else if (cmd in ['play', 'pause', 'stop']) out << [attribute: 'status', value: cmd == 'play' ? 'playing' : cmd == 'pause' ? 'paused' : 'stopped']
else if (cmd in ['setPosition', 'setHeatingSetpoint', 'setCoolingSetpoint', 'setThermostatMode', 'setThermostatFanMode', 'setSpeed']) {
Object v = (a.args ?: [null])[0]
out << [attribute: op.writes[0], value: cmd == 'setSpeed' && v == 'on' ? null : v]
}
else ((List) op.writes).each { out << [attribute: it, value: null] }
return out
}
void coreScheduleConfirmation(Map w, Map intent, Map op) {
Closure ackModel = corePort(w, 'ackModel')
ackModel(intent, op)
if (op.ack == 'none') {
intent.state = CONF_UNKNOWN
intent.unconfirmable = true
return
}
if (w.noRetry) { coreConfirmNow(w, intent); return }
coreSchedule(w, [at: coreNow(w) + (w.verifyMs as long), kind: 'verify', intent: intent.id])
}
void coreConfirmNow(Map w, Map intent) {
List open = []
String outcome = coreVerifyOutcome(w, intent, open, false)
if (outcome == 'match') {
intent.state = CONF_REPORTED
coreLog(w, 'confirmedByRead', [intent: intent.id])
return
}
intent.state = CONF_UNKNOWN
if (open) intent.awaitEcho = open
Map line = [intent: intent.id, reason: outcome == 'unknown' ? 'no fresh evidence of the result' : "read back as ${outcome}".toString()]
if (open) line.awaitingEvent = true
coreLog(w, 'unverifiable', line)
}
void coreCorrelateEcho(Map w, Map adm) {
Map event = adm.event
Object id = ((Map) (w.latestIntent ?: [:])).get("device:${event.device}:${event.attribute}".toString())
Map intent = id == null ? null : w.ledger.get(id)
if (intent == null || intent.state != CONF_UNKNOWN || !(intent.awaitEcho instanceof List) || intent.dispatchedAt == null) return
List open = new ArrayList((List) intent.awaitEcho)
if (!open.contains(event.attribute)) return
long ts = (adm.ts ?: coreNow(w)) as long
long sent = intent.dispatchedAt as long
if (ts < sent - CORE_ECHO_SKEW_MS || ts > sent + ((w.verifyMs ?: CORE_DEFAULT_VERIFY_MS) as long)) return
Map t = ((List) intent.targets).find { ((Map) it).attribute == event.attribute }
if (t == null || t.value == null || !coreSameValue(event.value, t.value)) return
open.remove(event.attribute)
if (open) { intent.awaitEcho = open; return }
intent.remove('awaitEcho')
intent.state = CONF_REPORTED
intent.confirmedBy = 'event'
intent.confirmedAt = coreNow(w)
coreLog(w, 'confirmedByEvent', [intent: intent.id])
}
void coreVerify(Map w, Map timer) {
Map intent = w.ledger.get(timer.intent)
if (intent == null || intent.state in [CONF_REPORTED, CONF_OBSERVED]) return
String outcome = coreVerifyOutcome(w, intent)
if (outcome == 'travelling') {
int waited = (intent.travelWaits ?: 0) as int
if (waited < CORE_TRAVEL_WAITS) {
intent.travelWaits = waited + 1
if (waited == 0) coreLog(w, 'stillTravelling', [intent: intent.id])
coreSchedule(w, [at: coreNow(w) + CORE_TRAVEL_MS, kind: 'verify', intent: intent.id])
return
}
outcome = 'mismatch'
}
intent.verification = outcome
if (outcome == 'match') {
intent.state = CONF_REPORTED
intent.confirmedBy = 'read'
coreLog(w, 'confirmedByRead', [intent: intent.id])
return
}
intent.state = CONF_UNKNOWN
if (outcome == 'unknown') {
coreLog(w, 'unverifiable', [intent: intent.id, reason: 'no fresh evidence of the result'])
return
}
coreLog(w, 'mismatch', [intent: intent.id])
Map verdict = coreRetryVerdict(w, intent)
coreLog(w, verdict.allowed ? 'retrying' : 'retryRejected', [intent: intent.id, reason: verdict.reason])
if (!verdict.allowed) return
intent.retries = (intent.retries as int) + 1
intent.state = CONF_DISPATCHED
coreDispatch(w, [at: coreNow(w), intent: intent.id, rule: intent.rule, run: intent.run,
device: intent.device, command: intent.command, args: intent.args, retry: true])
coreScheduleConfirmation(w, intent, coreIntentOp(intent))
}
String coreVerifyOutcome(Map w, Map intent, List open = null, boolean keep = true) {
String dev = intent.device as String
boolean anyUnknown = false
boolean anyMismatch = false
boolean anyTravelling = false
((List) intent.targets).each { Object to ->
Map t = to
if (t.value == null) { anyUnknown = true; return }
if (open != null) open << t.attribute
Object seen = null
boolean fresh = false
Closure readBack = corePort(w, 'readBack')
Map read = readBack(dev, t.attribute)
if (read != null) {
seen = read.value
fresh = true
if (keep) coreRecord(w, dev, t.attribute, [value: seen, ts: coreNow(w), seq: coreNextSeq(w), source: 'read',
epoch: coreEpoch(w)])
}
if (!fresh) {
Map st = coreRead(w, dev, t.attribute)
if (st != null && (st.epoch as int) == coreEpoch(w) &&
(st.ts as long) >= (intent.dispatchedAt as long)) {
seen = st.value
fresh = true
}
}
if (!fresh) { anyUnknown = true; return }
if (coreSameValue(seen, t.value)) { if (open != null) open.remove(t.attribute); return }
if (CORE_TRAVELLING.containsKey(t.value)) { anyTravelling = true; return }
anyMismatch = true
}
if (anyMismatch) return 'mismatch'
if (anyTravelling) return 'travelling'
if (anyUnknown) return 'unknown'
return 'match'
}
boolean coreSameValue(Object a, Object b) {
if (a == null || b == null) return a == b
if (a instanceof Number || b instanceof Number) {
try { return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0 }
catch (Exception e) { return false }
}
return a.toString() == b.toString()
}
Map coreRetryVerdict(Map w, Map intent) {
if (w.noRetry) return [allowed: false, reason: 'automatic retry is not available in this runtime']
Map op = coreIntentOp(intent)
if (op == null || op.retry != true) return [allowed: false, reason: 'catalogue does not permit retry for this operation']
if (op.shape != 'absolute') return [allowed: false, reason: 'only an absolute operation is a retry candidate']
if ((intent.retries as int) >= CORE_MAX_RETRIES) return [allowed: false, reason: 'retry limit reached']
Map run = w.runs.get(intent.run)
Map rec = w.rules.get(intent.rule)
if (rec == null || rec.disabled) return [allowed: false, reason: 'rule no longer enabled']
if ((intent.epoch as int) != (rec.epoch as int)) return [allowed: false, reason: 'rule version superseded']
if (run != null && run.status == 'cancelled') return [allowed: false, reason: 'run was cancelled']
Map gone = coreDispatchCheck(w, coreDevice(w, intent.device as String), intent.device as String, op)
if (gone != null) return [allowed: false, reason: gone.message]
for (Object to : intent.targets) {
String slot = "device:${intent.device}:${((Map) to).attribute}".toString()
if (w.latestIntent.get(slot) != intent.id) {
return [allowed: false, reason: "a newer command has since written ${slot}"]
}
Map winner = coreOutranking(w, slot, intent.rule as String)
if (winner != null) return [allowed: false, reason: "${slot} is now held by ${winner.owner}"]
}
return [allowed: true, reason: 'all preconditions hold']
}
void coreDurations(Map w, Map adm) {
new ArrayList(w.rules.values()).each { Object ro ->
Map rec = ro
if (rec.disabled) return
Map doc = rec.doc
((List) (doc.triggers ?: [])).eachWithIndex { Object t, int ti ->
Map trig = t
if (trig.kind != 'duration') return
coreDurationStep(w, rec, trig, ti, adm)
}
}
}
void coreDurationStep(Map w, Map rec, Map trig, int ti, Map adm) {
Map doc = rec.doc
Map durations = rec.durations
Map iv = durations.get(ti.toString())
if (adm.late && iv != null && adm.event.kind == 'attribute') {
long evTs = adm.ts as long
if (evTs >= (iv.evidenceTs as long) && coreLateContradicts(w, doc, trig.predicate, adm.event)) {
coreLog(w, 'continuityBroken', [rule: rec.id, trigger: ti, lateTs: evTs])
durations.remove(ti.toString())
iv = null
coreDurationArm(w, rec, trig, ti)
}
return
}
Map out = logicEvaluate((Map) trig.predicate) { Map node, String p -> coreLeaf(w, doc, node, true) }
String v = out.value as String
if (v == LOGIC_TRUE) {
if (iv == null) coreDurationArm(w, rec, trig, ti)
else coreArmFreshness(w, rec, trig.predicate, ti, iv)
return
}
if (iv != null) {
coreLog(w, v == LOGIC_UNKNOWN ? 'continuityBroken' : 'durationCancelled', [rule: rec.id, trigger: ti])
}
durations.remove(ti.toString())
((Map) rec.durations).remove("fired:${ti}".toString())
}
void coreDurationArm(Map w, Map rec, Map trig, int ti) {
Map doc = rec.doc
if (trig.disabled == true) return
if (((Map) rec.durations).containsKey("fired:${ti}".toString())) return
Map out = logicEvaluate((Map) trig.predicate) { Map node, String p -> coreLeaf(w, doc, node, true) }
if (out.value != LOGIC_TRUE) return
if (doc.requiredExpression != null && coreRequired(w, rec, null) != LOGIC_TRUE) return
String token = coreNewToken(w)
long deadline = coreNow(w) + ((trig.seconds as long) * 1000L)
Map iv = [token: token, startedAt: coreNow(w), evidenceTs: coreEvidenceTs(w, doc, trig.predicate),
deadline: deadline, epoch: rec.epoch]
((Map) rec.durations).put(ti.toString(), iv)
coreSchedule(w, [at: deadline, kind: 'duration', rule: rec.id, trigger: ti, token: token])
coreLog(w, 'durationArmed', [rule: rec.id, trigger: ti, deadline: deadline])
coreArmFreshness(w, rec, trig.predicate, ti, iv)
}
void coreArmFreshness(Map w, Map rec, Map predicate, int ti, Map iv) {
Long at = coreFreshExpiry(w, rec.doc, predicate)
if (at == null || at >= (iv.deadline as long) || at == (iv.freshAt as Long)) return
iv.freshAt = at
coreSchedule(w, [at: at, kind: 'freshness', rule: rec.id, trigger: ti, token: iv.token])
}
Long coreFreshExpiry(Map w, Map doc, Map node) {
if (node == null) return null
Long best = null
List kids = node.of instanceof List ? node.of : (node.of instanceof Map ? [node.of] : [])
kids.each { Object k ->
Long e = coreFreshExpiry(w, doc, k)
if (e != null && (best == null || e < best)) best = e
}
if (node.type == 'deviceAttribute' && node.freshSeconds != null) {
List keys = []
if (node.ref) keys << node.ref
if (node.refs instanceof List) keys.addAll((List) node.refs)
keys.each { Object key ->
Map st = coreRead(w, coreRefId(doc, key), node.attribute)
if (st == null) return
long e = (st.ts as long) + ((node.freshSeconds as long) * 1000L) + 1L
if (e > coreNow(w) && (best == null || e < best)) best = e
}
}
return best
}
void coreFreshnessCheck(Map w, Map timer) {
Map rec = w.rules.get(timer.rule)
if (rec == null || rec.disabled) return
Map iv = ((Map) rec.durations).get(timer.trigger?.toString())
if (iv == null || iv.token != timer.token) return
Map trig = ((List) rec.doc.triggers)[timer.trigger as int]
Map out = logicEvaluate((Map) trig.predicate) { Map node, String p -> coreLeaf(w, rec.doc, node, true) }
if (out.value == LOGIC_TRUE) {
coreArmFreshness(w, rec, trig.predicate, timer.trigger as int, iv)
return
}
((Map) rec.durations).remove(timer.trigger?.toString())
coreLog(w, 'continuityBroken', [rule: rec.id, trigger: timer.trigger, reason: 'evidence went stale'])
}
long coreEvidenceTs(Map w, Map doc, Map predicate) {
long oldest = coreNow(w)
corePredicateSlots(doc, predicate).each { List slot ->
Map st = coreRead(w, slot[0] as String, slot[1])
if (st != null && (st.ts as long) < oldest) oldest = st.ts as long
}
return oldest
}
List corePredicateSlots(Map doc, Map node) {
List out = []
if (node == null) return out
if (node.of instanceof List) { ((List) node.of).each { out.addAll(corePredicateSlots(doc, it)) }; return out }
if (node.of instanceof Map) { out.addAll(corePredicateSlots(doc, node.of)); return out }
if (node.type == 'deviceAttribute') {
List keys = []
if (node.ref) keys << node.ref
if (node.refs instanceof List) keys.addAll((List) node.refs)
keys.each { out << [coreRefId(doc, it), node.attribute] }
}
return out
}
boolean coreLateContradicts(Map w, Map doc, Map predicate, Map event) {
boolean touches = corePredicateSlots(doc, predicate).any { it[0] == event.device && it[1] == event.attribute }
if (!touches) return false
Map real = coreRead(w, event.device as String, event.attribute)
w.hypothetical = [device: event.device, attribute: event.attribute,
cell: [value: event.value, ts: real.ts, seq: real.seq, source: 'hypothetical', epoch: real.epoch]]
Map out
try {
out = logicEvaluate(predicate) { Map node, String p -> coreLeaf(w, doc, node, true) }
} finally {
w.remove('hypothetical')
}
return out.value != LOGIC_TRUE
}
void coreDurationDeadline(Map w, Map timer) {
Map rec = w.rules.get(timer.rule)
if (rec == null || rec.disabled) return
Map iv = ((Map) rec.durations).get(timer.trigger?.toString())
if (iv == null || iv.token != timer.token) return
Map trig = ((List) rec.doc.triggers)[timer.trigger as int]
Map out = logicEvaluate((Map) trig.predicate) { Map node, String p -> coreLeaf(w, rec.doc, node, true) }
((Map) rec.durations).remove(timer.trigger?.toString())
if (out.value != LOGIC_TRUE) {
coreLog(w, 'durationLapsed', [rule: rec.id, value: out.value]); return
}
((Map) rec.durations).put("fired:${timer.trigger}".toString(), true)
coreLog(w, 'durationFired', [rule: rec.id, trigger: timer.trigger])
coreConsider(w, rec, [kind: 'duration', trigger: timer.trigger], 0)
}
void coreArmSchedules(Map w, Map rec) {
((List) (((Map) rec.doc).triggers ?: [])).eachWithIndex { Object t, int ti ->
if (((Map) t).kind in ['schedule', 'periodic']) coreArmNextOccurrence(w, rec, ti, coreLocal(w, coreNow(w)).toLocalDateTime())
}
}
void coreArmNextOccurrence(Map w, Map rec, int ti, LocalDateTime after) {
Map trig = ((List) rec.doc.triggers)[ti]
Map cron = trig.kind == 'periodic' ? timeCronParse(trig.cron) : null
if (cron?.error) return
LocalDateTime from = after
for (int i = 0; i < 200; i++) {
LocalDateTime intended = cron != null ? timeCronNext(cron, from) : coreNextScheduled(w, rec.doc, trig, from)
if (intended == null) return
from = intended
Map occ = timeResolveOccurrence(intended, coreZone(w))
if (occ.status == TIME_UNRESOLVABLE) {
((List) w.gaps) << (occ.why as String)
continue
}
long when = ((ZonedDateTime) occ.instant).toInstant().toEpochMilli()
if (when <= coreNow(w) && occ.status == TIME_AMBIGUOUS && occ.suppressedSecond != null) {
long span = (((ZonedDateTime) occ.suppressedSecond).toInstant().toEpochMilli() - when) / 1000L
long lo = 0L, hi = span
while (lo < hi) {
long mid = (long) ((lo + hi) / 2L)
if (timeResolveOccurrence(intended.plusSeconds(mid), coreZone(w)).status == TIME_AMBIGUOUS) lo = mid + 1L else hi = mid
}
from = intended.plusSeconds(lo).minusSeconds(1)
continue
}
if (when > coreNow(w)) {
String oid = timeOccurrenceId("${rec.id}#${rec.epoch}#${ti}".toString(), intended, (ZonedDateTime) occ.instant)
Map timer = [at: when, kind: 'schedule', rule: rec.id, trigger: ti, epoch: rec.epoch, occurrence: oid, status: occ.status]
if (coreScheduleGen(rec, ti) > 0) timer.gen = coreScheduleGen(rec, ti)
coreSchedule(w, timer)
return
}
}
}
LocalDateTime coreNextScheduled(Map w, Map doc, Map trig, LocalDateTime after) {
String date = trig.date as String
String fromVar = trig.at instanceof Map ? coreOperand(w, null, doc, trig.at)?.toString() : null
if (fromVar ==~ /\d{4}-\d{2}-\d{2}T.*/ && !fromVar.startsWith('9999')) date = fromVar.substring(0, 10)
LocalDate once = date ==~ /\d{4}-\d{2}-\d{2}/ ? LocalDate.parse(date) : null
LocalDate start = once ?: after.toLocalDate()
int span = once != null ? 1 : (date != null ? 1500 : (trig.days != null ? 8 : 3))
for (int i = 0; i < span; i++) {
LocalDate d = start.plusDays(i)
if (date != null && once == null && String.format('%02d-%02d', d.getMonthValue(), d.getDayOfMonth()) != date) continue
if (trig.days instanceof List && !((List) trig.days).contains(CORE_DAY_NAMES[d.getDayOfWeek().getValue() - 1])) continue
LocalTime at = coreTimeOfDay(w, d, trig.at, trig.offsetMinutes, doc)
if (at == null) continue
LocalDateTime ldt = LocalDateTime.of(d, at)
if (ldt.isAfter(after)) return ldt
}
return null
}
List coreTimeSinceKeys(Map rec) {
Map doc = rec.doc
if (rec.tsKeysFor == doc.hashCode()) return rec.tsKeys
List keys = []
List stack = [doc.conditions]
((List) (doc.actions ?: [])).each { stack << it }
((List) (doc.onFailure ?: [])).each { stack << it }
while (!stack.isEmpty()) {
Object o = stack.pop()
if (o instanceof List) { stack.addAll((List) o); continue }
if (!(o instanceof Map)) continue
Map m = o
if (m.type == 'timeSince' && m.attribute != null && m.containsKey('value')) keys << "${coreRefId(doc, m.ref)}|${m.attribute}|${m.value}".toString()
m.values().each { if (it instanceof Map || it instanceof List) stack << it }
}
rec.tsKeys = keys
rec.tsKeysFor = doc.hashCode()
return keys
}
int coreScheduleGen(Map rec, int ti) {
return (((Map) (rec.schedGen ?: [:])).get(ti as String) ?: 0) as int
}
void coreRearmFromVariable(Map w, String vid) {
w.rules.values().each { Object ro ->
Map rec = ro
if (rec.disabled) return
Map doc = rec.doc
((List) (doc.triggers ?: [])).eachWithIndex { Object t, int ti ->
Map trig = t
if (trig.kind != 'schedule' || trig.disabled == true) return
if (![trig.at, trig.offsetMinutes].any { it instanceof Map && coreVarId(doc.id, coreRefId(doc, ((Map) it).ref)) == vid }) return
Map gens = new LinkedHashMap((Map) (rec.schedGen ?: [:]))
gens.put(ti as String, coreScheduleGen(rec, ti) + 1)
rec.schedGen = gens
coreLog(w, 'scheduleRearmed', [rule: rec.id, trigger: ti, variable: vid])
coreArmNextOccurrence(w, rec, ti, coreLocal(w, coreNow(w)).toLocalDateTime())
}
}
}
void coreScheduleFired(Map w, Map timer) {
Map rec = w.rules.get(timer.rule)
if (rec == null) return
if ((timer.epoch as int) != (rec.epoch as int)) return
if (((timer.gen ?: 0) as int) != coreScheduleGen(rec, timer.trigger as int)) return
if (((Set) w.occurrences).contains(timer.occurrence)) return
((Set) w.occurrences).add(timer.occurrence)
coreLog(w, 'scheduleFired', [rule: rec.id, trigger: timer.trigger, occurrence: timer.occurrence,
status: timer.status])
if (rec.disabled) return
coreConsider(w, rec, [kind: 'schedule', trigger: timer.trigger], 0)
coreArmNextOccurrence(w, rec, timer.trigger as int, coreAfterOccurrence(w, timer))
}
LocalDateTime coreAfterOccurrence(Map w, Map timer) {
LocalDateTime was = coreLocal(w, timer.at as long).toLocalDateTime()
LocalDateTime now = coreLocal(w, coreNow(w)).toLocalDateTime()
return was.isAfter(now) ? was : now
}
void coreScheduleMissed(Map w, Map timer, long lateMs, String via) {
if (coreHalted(w)) return
Map rec = w.rules.get(timer.rule)
if (rec == null || (timer.epoch as int) != (rec.epoch as int)) return
if (((timer.gen ?: 0) as int) != coreScheduleGen(rec, timer.trigger as int)) return
if (((Set) w.occurrences).contains(timer.occurrence)) return
((Set) w.occurrences).add(timer.occurrence)
coreLog(w, 'timerRecoveryExceeded', [rule: rec.id, kind: 'schedule', trigger: timer.trigger, occurrence: timer.occurrence,
code: 'TIMER_RECOVERY_EXCEEDED', lateMs: lateMs, via: via])
if (!rec.disabled) coreArmNextOccurrence(w, rec, timer.trigger as int, coreAfterOccurrence(w, timer))
corePersist(w)
}
void coreFire(Map w, Map t) {
if (coreHalted(w)) return
switch (t.kind) {
case 'resume':
Map run = w.runs.get(t.run)
if (run != null) coreWake(w, run, t.token as String, 'delay')
break
case 'waitTimeout':
Map run = w.runs.get(t.run)
if (run != null) coreWaitTimedOut(w, run, t.token as String)
break
case 'waitEvent':
case 'waitStay':
Map run = w.runs.get(t.run)
if (run == null || run.status != 'waiting' || run.wait?.token != t.wait || !coreRunLive(w, run)) break
if (t.kind == 'waitStay') coreWaitStayDue(w, run, t)
else coreWaitSeen(w, run, t.key as String, null)
break
case 'verify':
coreVerify(w, t)
break
case 'duration':
coreDurationDeadline(w, t)
break
case 'freshness':
coreFreshnessCheck(w, t)
break
case 'schedule':
coreScheduleFired(w, t)
break
case 'hsmSet':
coreAdmit(w, [kind: 'attribute', device: LOCATION_DEVICE, attribute: t.attribute, value: t.value, ts: coreNow(w), source: 'engine', depth: (t.depth ?: 0) as int])
break
case 'modeSet':
coreAdmit(w, [kind: 'mode', value: t.value, ts: coreNow(w), source: 'engine', depth: (t.depth ?: 0) as int])
break
case 'variableChanged':
coreVariableChanged(w, t.variable as String, t.value, (t.depth ?: 0) as int, t.before, (t.attribute ?: CORE_VARIABLE_ATTRIBUTE) as String)
break
}
corePersist(w)
}
void coreSetRuleBoolean(Map w, String ruleId, boolean value, Object by, int depth) {
String vid = coreVarId(ruleId, PRIVATE_BOOLEAN_ID)
Object before = coreVariableBefore(w, vid, [initial: true])
Closure writeVariable = corePort(w, 'writeVariable')
writeVariable(vid, [value: value, writtenBy: by, at: coreNow(w)])
coreLog(w, 'variableWritten', [run: by, variable: vid, value: value])
coreSchedule(w, [at: coreNow(w), kind: 'variableChanged', variable: vid, value: value, before: before, depth: depth])
}
String coreExpandText(Map w, Map run, Map doc, String text) {
if (text == null || !text.contains('%')) return text
Map cause = (run?.waitEvent ?: run?.cause ?: [:])
Map inv = coreInv(w)
String dev = cause.device != null ? ((invDevice(inv, cause.device as String)?.name ?: cause.device) as String) : ''
ZonedDateTime at = coreLocal(w, coreNow(w))
String date = at.toLocalDate().toString()
String time = String.format('%02d:%02d', at.getHour(), at.getMinute())
Map vals = [device: dev, value: cause.value != null ? cause.value.toString() : '', date: date, time: time, now: "${date} ${time}".toString(),
elapsed: run?.elapsed != null ? run.elapsed.toString() : '']
if (cause.timeout == true) { vals.value = 'timeout'; vals.text = 'timeout' }
else vals.text = cause.kind == 'button' ? "${dev} button ${cause.value} was ${cause.action}".toString() :
cause.attribute != null ? "${dev} ${cause.attribute} is ${cause.value}".toString() :
cause.kind == 'mode' ? "the mode is ${cause.value}".toString() : ''
Closure readVariable = corePort(w, 'readVariable')
return text.replaceAll(/%([^%\s][^%]{0,63})%/) { List m ->
String key = m[1] as String
if (vals.containsKey(key)) return vals.get(key) as String
java.util.regex.Matcher f = key =~ /^(date|time|now):(.{1,40})$/
if (f.matches()) {
try { return java.time.format.DateTimeFormatter.ofPattern(f.group(2)).format(at) } catch (Exception e) { return m[0] as String }
}
Map ref = ((Map) (doc.refs ?: [:])).values().find { it instanceof Map && ((Map) it).kind == 'variable' && ((Map) it).name == key }
String vid = ref != null ? coreVarId(doc.id, ref.id as String) :
(((Map) (inv.variables ?: [:])).containsKey(HUB_VARIABLE_PREFIX + key) ? HUB_VARIABLE_PREFIX + key : null)
if (vid == null) return m[0] as String
Map cell = readVariable(vid)
Object v = cell != null ? cell.value : ref?.initial
return v == null ? '' : v.toString()
}
}
boolean coreIsEventDevice(Map doc, Object key) {
return ((Map) ((Map) (doc.refs ?: [:])).get(key))?.kind == 'eventDevice'
}
void coreFanOut(Map w, Map run, Map doc, Map a, String path) {
Map frame = ((List) run.frames)[-1]
if (!(run.fanOut instanceof List)) {
List keys = (a.ref != null ? [a.ref] : []) + (a.refs instanceof List ? a.refs : [])
Map refs = (doc.refs ?: [:])
List plan = []
keys.each { Object k ->
Object key = k
if (coreIsEventDevice(doc, k)) {
Object dev = ((Map) (run.cause ?: [:])).device
key = dev == null ? null : refs.find { kk, vv -> vv instanceof Map && ((Map) vv).kind == 'device' && ((Map) vv).id == dev }?.key
if (key == null) { coreLog(w, 'noEventDevice', [run: run.id, path: path]); return }
}
if (a.onlyIfOn == true && coreRead(w, coreRefId(doc, key), 'switch')?.value != 'on') { coreLog(w, 'skippedOff', [run: run.id, ref: key, path: path]); return }
Map one = new LinkedHashMap(a)
one.remove('refs'); one.remove('meterMs'); one.remove('onlyIfOn')
one.ref = key
one.n = plan.size()
plan << one
}
run.fanOut = plan
}
List queue = run.fanOut
if (queue.isEmpty()) { run.remove('fanOut'); run.remove('fanOutSent'); return }
if (((a.meterMs ?: 0) as int) > 0 && run.fanOutSent == true && run.fanOutPaused != true) {
run.fanOutPaused = true
frame.index = (frame.index as int) - 1
String token = coreNewToken(w)
run.status = 'waiting'
run.wait = [token: token, kind: 'delay', action: [type: 'wait', waitKind: 'delay'], path: path, since: coreNow(w)]
coreSchedule(w, [at: coreNow(w) + ((a.meterMs as long)), kind: 'resume', run: run.id, token: token, epoch: run.epoch])
return
}
run.remove('fanOutPaused')
Map next = queue.remove(0)
run.fanOutSent = true
frame.index = (frame.index as int) - 1
coreCommand(w, run, doc, next, "${path}.d${next.n}".toString())
}
List coreRestorePlan(Map w, Map doc, Map a) {
Map rec = w.rules.get(doc.id)
Map captured = (rec?.captured ?: [:])
Map inv = coreInv(w)
List out = []
((List) a.refs).each { Object k ->
String dev = coreRefId(doc, k)
Map c = captured.get(dev)
Map d = invDevice(inv, dev)
if (c == null || d == null) return
Set caps = (Set) (d.capabilities ?: [] as Set)
boolean levelled = false
boolean hasColour = c.hue != null && c.saturation != null && caps.contains('ColorControl')
boolean hasCt = c.colorTemperature != null && caps.contains('ColorTemperature')
String mode = c.colorMode == null ? null : c.colorMode.toString().toUpperCase()
boolean ctMode = mode == 'CT' || (mode == null && !hasColour)
if (hasColour && !ctMode) {
out << [type: 'command', ref: k, command: 'setColor', args: [c.hue, c.saturation] + (c.level != null ? [c.level] : [])]
levelled = c.level != null
} else if (hasCt) {
if (hasColour) out << [type: 'command', ref: k, command: 'setColor', args: [c.hue, c.saturation]]
out << [type: 'command', ref: k, command: 'setColorTemperature', args: [c.colorTemperature]]
}
if (!levelled && c.level != null && caps.contains('SwitchLevel')) out << [type: 'command', ref: k, command: 'setLevel', args: [c.level]]
if (c.switch in ['on', 'off'] && caps.contains('Switch')) out << [type: 'command', ref: k, command: c.switch]
if (c.valve in ['open', 'closed'] && caps.contains('Valve')) out << [type: 'command', ref: k, command: c.valve == 'open' ? 'openValve' : 'closeValve']
if (c.lock == 'locked' && caps.contains('Lock')) out << [type: 'command', ref: k, command: 'lock']
[position: 'setPosition', speed: 'setSpeed', thermostatMode: 'setThermostatMode', heatingSetpoint: 'setHeatingSetpoint',
coolingSetpoint: 'setCoolingSetpoint', thermostatFanMode: 'setThermostatFanMode'].each { String at, String cmd ->
Map op = schemaOperation(cmd)
if (op == null) return
List allowed = ((Map) ((List) op.args)[0]).values
Object v = c.get(at)
if (v == null || !caps.containsAll((List) op.requires) || (allowed != null && !allowed.contains(v))) return
out << [type: 'command', ref: k, command: cmd, args: [v]]
}
}
out.eachWithIndex { Object o, int i -> ((Map) o).n = i }
return out
}
String coreArgProblem(Map a, Map op, List args) {
if (!(a.args instanceof List)) return null
for (int i = 0; i < args.size(); i++) {
if (!(((List) a.args)[i] instanceof Map)) continue
Map s = ((List) (op.args ?: []))[i]
Object v = args[i]
if (v == null) return "argument ${i + 1} comes from a variable that is not set".toString()
if (s == null || s.type == 'string') {
args[i] = v.toString()
if (s?.values != null && !((List) s.values).contains(args[i])) return "argument ${i + 1} is '${v}', not one of ${((List) s.values).join(', ')}".toString()
continue
}
BigDecimal n = coreNumber(v)
if (n == null) return "argument ${i + 1} is '${v}', not a number".toString()
if ((s.min != null && n < (s.min as BigDecimal)) || (s.max != null && n > (s.max as BigDecimal))) return "argument ${i + 1} is ${n}, outside ${s.min} to ${s.max}".toString()
args[i] = s.type == 'integer' ? (Object) n.setScale(0, java.math.RoundingMode.HALF_UP).intValue() : (Object) n
}
return null
}
Object coreOperand(Map w, Map run, Map doc, Object o) {
if (o instanceof String) return coreExpandText(w, run, doc, (String) o)
if (!(o instanceof Map)) return o
Map m = o
String id = coreRefId(doc, m.ref)
Object v
if (m.attribute != null) v = coreRead(w, id, m.attribute as String)?.value
else {
Map ref = ((Map) (doc.refs ?: [:])).get(m.ref)
Closure readVariable = corePort(w, 'readVariable')
Map cell = readVariable(coreVarId(doc.id, id))
v = cell != null ? cell.value : ref?.initial
}
if (m.offset == null) return v
BigDecimal n = coreNumber(v)
return n == null ? null : n + coreNumber(m.offset)
}
BigDecimal coreNumber(Object v) {
if (v instanceof Number) return new BigDecimal(v.toString())
try { return v == null ? null : new BigDecimal(v.toString().trim()) } catch (Exception e) { return null }
}
Map coreSetVarOp(Map w, Map run, Map doc, Map a, String vid) {
List args = (a.args ?: [])
Closure arg = { int i -> i < args.size() ? coreOperand(w, run, doc, args[i]) : null }
ZonedDateTime at = coreLocal(w, coreNow(w))
Object out
switch (a.op) {
case 'set': out = arg(0); break
case 'add':
Closure readVariable = corePort(w, 'readVariable')
BigDecimal cur = coreNumber(((Map) readVariable(vid))?.value) ?: BigDecimal.ZERO
BigDecimal n = coreNumber(arg(0))
if (n == null) return [error: 'the number to add is not a number']
out = cur + n; break
case 'math':
BigDecimal x = coreNumber(arg(0))
String opn = args[1] as String
BigDecimal y = args.size() > 2 ? coreNumber(arg(2)) : null
if (x == null || (MATH_BINARY.contains(opn) && y == null)) return [error: 'a value is not a number']
double d = x.doubleValue()
switch (opn) {
case '+': out = x + y; break
case '-': out = x - y; break
case '*': out = x * y; break
case '/': if (y.signum() == 0) return [error: 'division by zero']; out = x.divide(y, 10, java.math.RoundingMode.HALF_UP).stripTrailingZeros(); break
case '%': if (y.signum() == 0) return [error: 'division by zero']; out = x.remainder(y); break
case 'negate': out = x.negate(); break
case 'absolute': out = x.abs(); break
case 'round': out = x.setScale(0, java.math.RoundingMode.HALF_UP); break
case 'random': out = new BigDecimal(Math.random() * d); break
case 'sqrt': if (d < 0) return [error: 'no square root of a negative number']; out = Math.sqrt(d); break
case 'sin': out = Math.sin(d); break
case 'cos': out = Math.cos(d); break
case 'tan': out = Math.tan(d); break
case 'asin': out = Math.asin(d); break
case 'acos': out = Math.acos(d); break
case 'atan': out = Math.atan(d); break
case 'log': if (d <= 0) return [error: 'no logarithm of zero or less']; out = Math.log(d); break
case 'toRadians': out = Math.toRadians(d); break
case 'toDegrees': out = Math.toDegrees(d); break
default: return [error: "unknown operator ${opn}".toString()]
}
break
case 'hour': out = at.getHour(); break
case 'minute': out = at.getMinute(); break
case 'epoch': out = coreNow(w); break
case 'now':
try { out = java.time.format.DateTimeFormatter.ofPattern((arg(0) ?: 'yyyy-MM-dd HH:mm').toString()).format(at) }
catch (Exception e) { return [error: "'${arg(0)}' is not a date and time format".toString()] }
break
case 'sunrise':
case 'sunset':
LocalTime sunAt = coreTimeOfDay(w, at.toLocalDate(), a.op as String, 0)
if (sunAt == null) return [error: "the hub gave no ${a.op} for today".toString()]
out = String.format('%02d:%02d', sunAt.getHour(), sunAt.getMinute())
break
case 'length': out = (arg(0) ?: '').toString().length(); break
case 'remove': out = (arg(0) ?: '').toString().replace((arg(1) ?: '').toString(), ''); break
case 'replace': out = (arg(0) ?: '').toString().replace((arg(1) ?: '').toString(), (arg(2) ?: '').toString()); break
case 'extract':
String regexProblem = coreRegexInputProblem(arg(0))
if (regexProblem != null) return [error: regexProblem]
out = coreRegexFind((arg(0) ?: '').toString(), (arg(1) ?: '').toString()); break
case 'token':
List parts = (arg(0) ?: '').toString().split(java.util.regex.Pattern.quote((arg(1) ?: ',').toString()), -1) as List
BigDecimal ti = coreNumber(arg(2))
int idx = ti == null ? -1 : ti.intValue()
out = idx >= 0 && idx < parts.size() ? parts[idx] : ''; break
case 'lowerCase': out = (arg(0) ?: '').toString().toLowerCase(); break
case 'urlEncode': out = URLEncoder.encode((arg(0) ?: '').toString(), 'UTF-8'); break
case 'urlDecode':
try { out = URLDecoder.decode((arg(0) ?: '').toString(), 'UTF-8') } catch (Exception e) { return [error: 'not URL-encoded text'] }
break
case 'compare':
if (args[1] == 'matches') {
String regexProblem = coreRegexInputProblem(arg(0))
if (regexProblem != null) return [error: regexProblem]
}
String cv = coreCompare(arg(0), args[1] as String, arg(2))
if (cv == LOGIC_UNKNOWN) return [error: 'the values cannot be compared']
out = cv == LOGIC_TRUE; break
case 'formatDate':
Map fd = coreDateValue(arg(0))
if (fd == null) return [error: 'the value is not a date or time']
try { out = java.time.format.DateTimeFormatter.ofPattern(arg(1).toString()).format(fd.at) }
catch (Exception e) { return [error: "'${arg(1)}' is not a format for this value".toString()] }
break
case 'addTime':
Map ad = coreDateValue(arg(0))
BigDecimal amt = coreNumber(arg(1))
Long unit = (Long) TIME_UNITS.get(args[2])
if (ad == null || amt == null || unit == null) return [error: 'addTime needs a date or time, a number and a unit']
long secs = (amt * unit).longValue()
if (ad.kind == 'time') out = ((LocalTime) ad.at).plusSeconds(secs).toString()
else {
LocalDateTime r = ((LocalDateTime) ad.at).plusSeconds(secs)
out = ad.kind == 'date' && secs % 86400L == 0 ? r.toLocalDate().toString() : r.toString()
}
break
case 'timeBetween':
Map fa = coreDateValue(arg(0)), fb = coreDateValue(arg(1))
Long per = (Long) TIME_UNITS.get(args[2])
if (fa == null || fb == null || per == null || (fa.kind == 'time') != (fb.kind == 'time')) return [error: 'timeBetween needs two dates or two times and a unit']
long diff = fa.kind == 'time' ? java.time.Duration.between((LocalTime) fa.at, (LocalTime) fb.at).getSeconds() :
java.time.Duration.between((LocalDateTime) fa.at, (LocalDateTime) fb.at).getSeconds()
out = (long) (diff / per)
break
case 'and': out = arg(0) == true && arg(1) == true; break
case 'or': out = arg(0) == true || arg(1) == true; break
case 'xor': out = (arg(0) == true) != (arg(1) == true); break
case 'not': out = arg(0) != true; break
case 'file':
String problem = schemaFileProblem(arg(0))
if (problem != null) return [error: problem]
if (!((Map) w.port).containsKey('readFile')) return [error: 'this runtime cannot read files']
try { Closure rf = corePort(w, 'readFile'); out = rf(arg(0) as String) }
catch (Exception e) { return [error: "the file ${arg(0)} could not be read (${e.message})".toString()] }
if (out == null) return [error: "there is no file ${arg(0)}".toString()]
if ((out as String).length() > KEEP_MAX_CHARS) return [error: "the file is ${(out as String).length()} characters; a variable keeps at most ${KEEP_MAX_CHARS}".toString()]
break
default: return [error: "unknown operation ${a.op}".toString()]
}
if (out instanceof Double) out = new BigDecimal(out.toString())
if (out instanceof BigDecimal) {
Map row = ((Map) (coreInv(w).variables ?: [:])).get(vid)
BigDecimal b = (BigDecimal) out
out = row?.integer ? (Object) b.setScale(0, java.math.RoundingMode.DOWN).longValue() : (Object) b.stripTrailingZeros()
if (out instanceof BigDecimal && ((BigDecimal) out).scale() <= 0) out = ((BigDecimal) out).longValue()
}
return [value: out]
}
Map coreDateValue(Object v) {
if (v == null) return null
String s = v.toString().trim()
java.util.regex.Matcher m = s =~ /^(\d{4}-\d{2}-\d{2})?(?:T?(\d{2}:\d{2})(:\d{2})?(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?)?$/
if (!m.matches() || (m.group(1) == null && m.group(2) == null)) return null
try {
LocalTime t = m.group(2) == null ? null : LocalTime.parse(m.group(2) + (m.group(3) ?: ':00'))
if (m.group(1) == null || m.group(1).startsWith('9999')) return t == null ? null : [kind: 'time', at: t]
LocalDate d = LocalDate.parse(m.group(1))
return [kind: t == null ? 'date' : 'datetime', at: LocalDateTime.of(d, t ?: LocalTime.MIDNIGHT)]
} catch (Exception e) { return null }
}
Object coreVariableBefore(Map w, String vid, Map ref) {
Closure readVariable = corePort(w, 'readVariable')
Map cell = readVariable(vid)
return cell != null ? cell.value : ref?.initial
}
void coreVariableChanged(Map w, String vid, Object value, int depth, Object before = null, String attr = CORE_VARIABLE_ATTRIBUTE) {
if (depth > CORE_MAX_CALL_DEPTH) { coreLog(w, 'callRefused', [variable: vid, why: 'depth']); return }
if (before != null && coreRead(w, vid, attr) == null) {
coreRecord(w, vid, attr, [value: before, ts: coreNow(w) - 1, seq: 0, source: 'engine', epoch: coreEpoch(w)])
}
coreAdmit(w, [kind: 'attribute', device: vid, attribute: attr, value: value, ts: coreNow(w),
source: 'engine', depth: depth])
}
void coreSend(Map w, Map t) {
if (coreHalted(w)) return
Map intent = w.ledger.get(t.intent)
if (intent == null) return
Map run = w.runs.get(t.run)
Map pending = run == null ? null : run.wait
boolean current = pending != null && run.status == 'waiting' && pending.kind == 'dispatch' && pending.token == t.token
if (!current) {
coreIntentRefused(w, intent, CONF_NOT_SENT, 'the run no longer waits for this send', null)
corePersist(w)
return
}
if (!coreRunLive(w, run)) {
coreIntentRefused(w, intent, CONF_NOT_SENT, run.superseded ? 'superseded' : 'notLive', null)
coreFinish(w, run, run.superseded ? 'superseded' : 'notLive')
corePersist(w)
return
}
run.wait = null
run.status = 'running'
Map refusal = null
if (intent.state != CONF_REQUESTED) {
refusal = [code: 'DISPATCH_INTERRUPTED', outcome: CONF_UNKNOWN, reason: (intent.unknownReason ?: 'not in the requested state')]
} else {
Map op = coreIntentOp(intent)
Map gone = coreDispatchCheck(w, coreDevice(w, intent.device as String), intent.device as String, op)
if (gone != null) refusal = [code: gone.code, outcome: CONF_NOT_SENT, reason: gone.message]
}
if (refusal == null) {
intent.dispatchedAt = coreNow(w)
Closure send = corePort(w, 'dispatch')
Object r = send([at: coreNow(w), intent: intent.id, stepKey: coreStepKey(intent), rule: intent.rule, run: run.id,
device: intent.device, command: intent.sendAs ?: intent.command, args: intent.args])
refusal = r instanceof Map ? r : null
}
if (refusal != null) {
coreSendRefused(w, run, intent, refusal)
coreStep(w, run)
corePersist(w)
return
}
intent.state = CONF_DISPATCHED
coreLog(w, 'dispatched', [intent: intent.id, device: intent.device, command: intent.command, path: intent.path, run: run.id])
coreScheduleConfirmation(w, intent, coreIntentOp(intent))
coreStep(w, run)
corePersist(w)
}
String coreStepKey(Map intent) {
return intent?.sk == null ? null : "${intent.run}#${intent.sk}".toString()
}
void coreSendRefused(Map w, Map run, Map intent, Map refusal) {
String code = refusal.code as String
coreIntentRefused(w, intent, refusal.outcome as String, refusal.reason as String, code)
if (code == 'DISPATCH_THREW') coreScheduleConfirmation(w, intent, coreIntentOp(intent))
String said = code == 'DISPATCH_THREW' ?
"the driver call for '${intent.command}' at ${intent.path} failed after it may have acted, so the result is unknown and it is not sent again" :
"'${intent.command}' at ${intent.path} was not sent: ${refusal.reason}"
coreFail(w, run, code, said.toString())
}
void coreAdoptFence(Map w, Map fenced, Object fencedAt) {
Map intent = new LinkedHashMap(fenced)
String id = intent.id as String
if (id != null && id ==~ /i\d+/ && (id.substring(1) as long) > (w.seq as long)) w.seq = id.substring(1) as long
if (id == null || w.ledger.containsKey(id)) { id = "i${coreNextSeq(w)}".toString(); intent.id = id }
String rid = intent.run as String
if (rid != null && rid ==~ /run\d+/ && (rid.substring(3) as long) > (w.nextRun as long)) w.nextRun = rid.substring(3) as long
intent.state = CONF_UNKNOWN
intent.unknownReason = 'sendAttempt'
intent.code = 'DISPATCH_INTERRUPTED'
intent.fenceAdopted = true
intent.fencedAt = fencedAt
w.ledger.put(id, intent)
coreLog(w, 'fenceAdopted', [intent: id, stepKey: coreStepKey(intent), run: rid, device: intent.device, command: intent.command])
Map run = rid == null ? null : w.runs.get(rid)
if (run != null && run.status in ['running', 'waiting']) {
if (!coreRunLive(w, run)) {
coreFinish(w, run, run.superseded ? 'superseded' : 'notLive')
} else {
run.wait = null
run.status = 'running'
coreFail(w, run, 'DISPATCH_INTERRUPTED',
"'${intent.command}' at ${intent.path} may have been sent by an execution whose state was lost, so the result is unknown and it is not sent again".toString())
if (run.status == 'running' && !run.inFailure) coreFinish(w, run, 'failed')
else coreStep(w, run)
}
}
corePersist(w)
}
void coreIntentRefused(Map w, Map intent, String outcome, String reason, String code) {
if (intent.state == CONF_REQUESTED) {
intent.state = outcome
intent.unknownReason = reason
intent.refusedAt = coreNow(w)
if (code != null) intent.code = code
}
coreLog(w, 'dispatchRefused', [intent: intent.id, run: intent.run, state: intent.state, code: code, reason: reason])
}
void coreTimerExceeded(Map w, Map t, long lateMs) {
if (coreHalted(w)) return
Map run = w.runs.get(t.run)
if (run == null || run.status != 'waiting' || run.wait == null || run.wait.token != (t.wait ?: t.token)) return
coreLog(w, 'timerRecoveryExceeded', [run: run.id, kind: t.kind, lateMs: lateMs])
if (!coreRunLive(w, run)) {
coreFinish(w, run, run.superseded ? 'superseded' : 'notLive')
corePersist(w)
return
}
run.wait = null
run.status = 'running'
coreFail(w, run, 'TIMER_RECOVERY_EXCEEDED', "a deadline was found ${lateMs} ms late, beyond the recovery objective".toString())
if (run.status == 'running' && !run.inFailure) coreFinish(w, run, 'failed')
else coreStep(w, run)
corePersist(w)
}
void coreDropDurations(Map w) {
Closure cancel = corePort(w, 'cancel')
w.rules.values().each { Object r ->
Map durations = ((Map) r).durations
durations.values().each { Object iv -> if (iv instanceof Map) cancel(((Map) iv).token) }
durations.clear()
}
corePersist(w)
}
@Field static final String RT_VERSION = 'HAI-1/m5b-0.2.0-dev'
@Field static final String RT_CHILD_BUILD = '72c0f8ecbaba5d86'
@Field static final long RT_GRACE_MS = 30000L
@Field static final long RT_CADENCE_MS = 60000L
@Field static final long RT_RECOVERY_MS = 120000L
@Field static final long RT_BOOT_TOLERANCE_MS = 2000L
@Field static final long RT_CLOCK_JUMP_MS = 5000L
@Field static final long RT_CONTINUE_MS = 1L
@Field static final long RT_COMMIT_DELAY_MS = 1L
@Field static final int RT_TRACE_RUNS = 16
@Field static final int RT_TRACE_HEAD = 10
@Field static final int RT_TRACE_TAIL = 10
@Field static final int RT_TRACE_MIN_RUNS = 3
@Field static final int RT_TRACE_ENTRY_BYTES = 256
@Field static final int RT_TRACE_TEXT_CHARS = 64
@Field static final int RT_REJECTIONS = 8
@Field static final int RT_GATE_TRACE = 8
@Field static final int RT_TERMINAL_INTENTS = 8
@Field static final int RT_LEDGER_FULL = 4
@Field static final int RT_LATE_SAMPLES = 32
@Field static final String RT_FRESH_SUFFIX = '.fresh'
@Field static final String RT_INVOKE_EVENT = 'haiRuleInvoke'
@Field static final String RT_CONTROL_EVENT = 'haiRuleControl'
@Field static final String RT_PAUSED_EVENT = 'haiRulePaused'
@Field static final String RT_SWITCHED_EVENT = 'haiRmSwitched'
@Field static final String RULE_INDEX_EVENT = 'haiRuleIndex'
@Field static final String RT_RETURN_EVENT = 'haiRuleReturn'
@Field static final String RT_COMMITTED_EVENT = 'haiRuleCommitted'
@Field static final List RT_BUTTON_EVENTS = ['pushed', 'held', 'doubleTapped', 'released']
@Field static final List RT_TERMINAL_RUN = ['done', 'failed', 'cancelled']
@Field static final String RT_FENCE_KEY = 'rtFence'
@Field static final int RT_MAX_ATTRIBUTES = 64
@Field static final String RT_LOCATION = 'location'
@Field static final long RT_OCCURRENCE_KEEP_MS = 172800000L
@Field static final List RT_ASLEEP_KEEP = ['haiRuleInvoke', 'haiRuleControl', 'haiRulePaused', 'haiRmSwitched', 'haiRuleReturn']
String rtCanon(Object v) {
StringBuilder sb = new StringBuilder()
rtCanonInto(v, sb)
return sb.toString()
}
void rtCanonInto(Object v, StringBuilder sb) {
if (v == null) { sb.append('null'); return }
if (v instanceof Map) {
List keys = ((Map) v).keySet().collect { it.toString() }.sort()
Map byText = [:]
((Map) v).each { Object k, Object x -> byText.put(k.toString(), x) }
sb.append('{')
keys.eachWithIndex { String k, int i ->
if (i > 0) sb.append(',')
rtCanonString(k, sb)
sb.append(':')
rtCanonInto(byText.get(k), sb)
}
sb.append('}')
return
}
if (v instanceof Set) {
List items = ((Set) v).collect { rtCanon(it) }.sort()
sb.append('[').append(items.join(',')).append(']')
return
}
if (v instanceof Collection) {
sb.append('[')
boolean first = true
((Collection) v).each { Object x ->
if (!first) sb.append(',')
first = false
rtCanonInto(x, sb)
}
sb.append(']')
return
}
if (v instanceof Boolean) { sb.append(v ? 'true' : 'false'); return }
if (v instanceof Number) {
sb.append(new BigDecimal(v.toString()).stripTrailingZeros().toPlainString())
return
}
rtCanonString(v.toString(), sb)
}
void rtCanonString(String s, StringBuilder sb) {
sb.append('"')
for (int i = 0; i < s.length(); i++) {
char c = s.charAt(i)
if (c == ('"' as char)) sb.append('\\"')
else if (c == ('\\' as char)) sb.append('\\\\')
else if (c < 0x20) sb.append(String.format('\\u%04x', (int) c))
else sb.append(c)
}
sb.append('"')
}
int rtBytes(Object v) { return rtCanon(v).getBytes('UTF-8').length }
String rtBareId(Object id) {
String s = id == null ? '' : id.toString()
return s.startsWith('dev:') ? s.substring(4) : s
}
boolean rtChildAllowed(Object pushed, Object devId) {
String bare = rtBareId(devId)
return ((pushed ?: []) as List).collect { it.toString() }.contains(bare)
}
Map rtDeviceRow(Object dev, String purpose) {
List unavailable = []
Set caps = [] as Set
try { dev.capabilities.each { c -> caps << c.name.toString() } } catch (Exception e) { unavailable << 'capabilities' }
Set cmds = null
Map commandArgs = [:]
try {
cmds = [] as Set
dev.supportedCommands.each { c ->
String n = c.name.toString()
cmds << n
try { commandArgs[n] = (c.arguments ?: []).collect { it.toString() } }
catch (Exception e) { commandArgs[n] = 'unknown' }
}
} catch (Exception e) {
cmds = null
unavailable << 'commands'
}
Set attrs = null
try {
attrs = [] as Set
(dev.supportedAttributes as List).take(RT_MAX_ATTRIBUTES).each { a -> attrs << a.name.toString() }
} catch (Exception e) {
attrs = null
unavailable << 'attributes'
}
Boolean disabled = null
try { disabled = dev.isDisabled() as Boolean } catch (Exception e) { unavailable << 'enabled state' }
String driver = null
try { driver = dev.typeName?.toString() } catch (Exception e) { driver = null }
Map row = [id: "dev:${dev.id}".toString(), name: dev.displayName.toString(), room: null, driver: driver,
capabilities: caps, commands: cmds, attributes: attrs, currentStates: [:],
metadata: [commandArgs: commandArgs, unavailable: unavailable]]
if (disabled != null) row.disabled = disabled
if (purpose != null) row.commissionedPurpose = purpose
if (caps.any { it in ['PushableButton', 'HoldableButton', 'DoubleTapableButton', 'ReleasableButton'] }) {
try {
Object n = dev.currentValue('numberOfButtons')
if (n != null) ((Map) row.currentStates).put('numberOfButtons', [value: n])
} catch (Exception e) { unavailable << 'numberOfButtons' }
}
return row
}
Map rtDeviceLite(Object dev) {
Set caps = [] as Set
try { dev.capabilities.each { c -> caps << c.name.toString() } } catch (Exception e) { }
Map row = [id: "dev:${dev.id}".toString(), capabilities: caps]
try { row.disabled = dev.isDisabled() as Boolean } catch (Exception e) { }
return row
}
Map rtWithRuleVariables(Map inv, List docs) {
docs.each { Object d ->
if (!(d instanceof Map)) return
((Map) (((Map) d).refs ?: [:])).each { Object k, Object r ->
if (!(r instanceof Map)) return
Map ref = r
if (ref.kind == 'variable' && ref.scope == 'rule' && ref.id instanceof String) {
((Map) inv.variables).put(ref.id, [id: ref.id, name: ref.name ?: ref.id, type: ref.type, scope: 'rule'])
}
}
}
return inv
}
List rtSlots(Map doc) {
Set out = [] as Set
Map refs = (doc.refs ?: [:])
Closure idOf = { Object key -> ((Map) refs.get(key))?.id as String }
((List) (doc.triggers ?: [])).each { Object t ->
Map trig = t
if (trig.disabled == true) return
String dev = idOf(trig.ref)
if (dev == null) return
if (trig.kind in ['deviceAttribute', 'threshold'] && trig.attribute) out << [dev, trig.attribute as String]
if (trig.kind == 'button') {
(trig.action ? [trig.action] : RT_BUTTON_EVENTS).each { out << [dev, it as String] }
}
}
((List) (doc.triggers ?: [])).each { Object t ->
if (!(t instanceof Map) || ((Map) t).disabled == true) return
Map trig = t
List keys = trig.kind == 'hubVariable' ? [trig.ref] : trig.kind == 'schedule' ? [trig.at, trig.offsetMinutes].findAll { it instanceof Map }.collect { ((Map) it).ref } : []
keys.each { Object k ->
String vid = idOf(k)
if (vid != null && vid.startsWith(HUB_VARIABLE_PREFIX)) out << [vid, CORE_VARIABLE_ATTRIBUTE]
}
}
if (doc.cancelOnFalse == true && doc.requiredExpression instanceof Map) rtLeafSlots((Map) doc.requiredExpression, idOf, out)
rtWalkConditions(doc) { Map n -> if (n.type == 'timeSince') rtLeafSlots(n, idOf, out) }
((List) (doc.triggers ?: [])).each { Object t ->
if (t instanceof Map && ((Map) t).kind == 'duration') rtLeafSlots((Map) ((Map) t).predicate, idOf, out)
}
if (((List) (doc.triggers ?: [])).any { it instanceof Map && ((Map) it).kind == 'mode' }) out << [RT_LOCATION, 'mode']
((List) (doc.triggers ?: [])).each { Object t ->
if (!(t instanceof Map) || ((Map) t).disabled == true) return
Map trig = t
if (trig.kind in ['hsmStatus', 'hsmAlert']) out << [RT_LOCATION, trig.kind as String]
if (trig.kind == 'locationEvent') ((List) (trig.events ?: [])).each { out << [RT_LOCATION, it as String] }
}
if (((List) (doc.triggers ?: [])).any { it instanceof Map && ((Map) it).kind == 'ruleInvocation' }) out << [RT_LOCATION, RT_INVOKE_EVENT]
out << [RT_LOCATION, RT_CONTROL_EVENT]
boolean asks = ((List) (doc.triggers ?: [])).any { it instanceof Map && ((Map) it).kind == 'rulePaused' && ((Map) it).disabled != true }
if (!asks) rtWalkConditions(doc) { Map n -> if (n.type == 'rulePaused') asks = true }
if (asks) out << [RT_LOCATION, RT_PAUSED_EVENT]
if (!rtActsOnRmRules(doc).isEmpty()) out << [RT_LOCATION, RT_SWITCHED_EVENT]
boolean waits = false
rtWalkActions((List) (doc.actions ?: [])) { Map a -> if (a.type == 'runRule' && a.wait == true) waits = true }
rtWalkActions((List) (doc.onFailure ?: [])) { Map a -> if (a.type == 'runRule' && a.wait == true) waits = true }
if (waits) out << [RT_LOCATION, RT_RETURN_EVENT]
rtWalkActions((List) (doc.actions ?: [])) { Map a -> rtWaitSlots(a, idOf, out) }
rtWalkActions((List) (doc.onFailure ?: [])) { Map a -> rtWaitSlots(a, idOf, out) }
return new ArrayList(out).sort { x, y -> ((List) x).join('|') <=> ((List) y).join('|') }
}
void rtWaitSlots(Map a, Closure idOf, Set out) {
if (a.type != 'wait') return
if (a.waitKind == 'forEvent') {
String dev = idOf(a.ref)
if (dev != null && a.attribute) out << [dev, a.attribute as String]
}
if (a.waitKind == 'until') rtLeafSlots((Map) a.predicate, idOf, out)
if (a.waitKind == 'forEvents') {
((List) (a.events ?: [])).each { Object eo ->
if (!(eo instanceof Map) || ((Map) eo).disabled == true) return
Map ev = eo
String dev = idOf(ev.ref)
if (ev.kind in ['deviceAttribute', 'threshold'] && dev != null && ev.attribute) out << [dev, ev.attribute as String]
if (ev.kind == 'button' && dev != null) (ev.action ? [ev.action] : RT_BUTTON_EVENTS).each { out << [dev, it as String] }
if (ev.kind == 'hubVariable' && dev != null && dev.startsWith(HUB_VARIABLE_PREFIX)) out << [dev, CORE_VARIABLE_ATTRIBUTE]
if (ev.kind == 'mode') out << [RT_LOCATION, 'mode']
if (ev.kind == 'duration') rtLeafSlots((Map) ev.predicate, idOf, out)
}
}
}
void rtLeafSlots(Map node, Closure idOf, Set out) {
if (node == null) return
if (node.of instanceof List) { ((List) node.of).each { rtLeafSlots((Map) it, idOf, out) }; return }
if (node.of instanceof Map) { rtLeafSlots((Map) node.of, idOf, out); return }
if (node.type == 'variable') {
String vid = idOf(node.ref)
if (vid != null && vid.startsWith(HUB_VARIABLE_PREFIX)) out << [vid, CORE_VARIABLE_ATTRIBUTE]
return
}
if (node.type == 'timeSince' && node.attribute && node.containsKey('value')) { String d = idOf(node.ref); if (d != null) out << [d, node.attribute as String]; return }
if (node.type == 'hsmStatus') { out << [RT_LOCATION, 'hsmStatus']; return }
if (node.type != 'deviceAttribute' || !node.attribute) return
List keys = []
if (node.ref) keys << node.ref
if (node.refs instanceof List) keys.addAll((List) node.refs)
keys.each { Object k -> String dev = idOf(k); if (dev != null) out << [dev, node.attribute as String] }
}
void rtWalkActions(List actions, Closure visit) {
(actions ?: []).each { Object o ->
if (!(o instanceof Map)) return
Map a = o
visit(a)
if (a.type == 'if') {
rtWalkActions((List) a.then, visit)
((List) (a.elseIf ?: [])).each { Object e -> if (e instanceof Map) rtWalkActions((List) ((Map) e).then, visit) }
rtWalkActions((List) a.otherwise, visit)
}
if (a.type == 'repeat') rtWalkActions((List) a.steps, visit)
if (a.type == 'later') rtWalkActions((List) a.actions, visit)
if (a.type == 'byMode') {
((List) (a.cases ?: [])).each { Object c -> if (c instanceof Map) rtWalkActions((List) ((Map) c).then, visit) }
rtWalkActions((List) a.otherwise, visit)
}
}
}
List rtEventsFrom(Object deviceId, String name, Object value, Object numberValue, Object dateMs, Object eventId, Object eventType = null) {
String dev = "dev:${deviceId}".toString()
Object v = value
if (numberValue != null && !RT_BUTTON_EVENTS.contains(name)) {
try { v = new BigDecimal(numberValue.toString()) } catch (Exception e) { v = value }
}
Map base = [kind: 'attribute', device: dev, attribute: name, value: v]
if (eventType in EVENT_TYPES) base.eventType = eventType
if (dateMs instanceof Number) base.ts = (dateMs as long)
if (eventId != null) base.sourceId = "${eventId}:a".toString()
List out = [base]
if (RT_BUTTON_EVENTS.contains(name)) {
Integer number = null
try { number = Integer.parseInt(value.toString().trim()) } catch (Exception e) { number = null }
if (number != null) {
base.value = number
Map b = [kind: 'button', device: dev, number: number, action: name]
if (base.ts != null) b.ts = base.ts
if (eventId != null) b.sourceId = "${eventId}:b".toString()
out << b
}
}
return out
}
Object rtOnInvoke(Map plat, String target, Map call) {
return rtExecute(plat) { Map ctx ->
Map rec = rtRule(ctx)
if (rec == null || rec.id != target) return null
coreInvoked((Map) ctx.w, target, [from: call?.from, caller: call?.caller, input: call?.input], ((call?.depth ?: 1) as int))
return null
}
}
void rtWalkConditions(Map doc, Closure visit) {
Closure walk
walk = { Object n ->
if (n instanceof Map) {
Map m = n
if (m.type instanceof String && CONDITION_TYPES.contains(m.type)) visit(m)
m.values().each { walk(it) }
} else if (n instanceof List) ((List) n).each { walk(it) }
}
walk(doc.conditions); walk(doc.actions); walk(doc.onFailure); walk(doc.triggers); walk(doc.requiredExpression)
}
Object rtOnReturn(Map plat, String ruleId, Map answer) {
return rtExecute(plat) { Map ctx ->
Map rec = rtRule(ctx)
if (rec == null || rec.id != ruleId) return null
coreReturned((Map) ctx.w, answer?.run as String, answer?.value, (answer?.how ?: 'completed') as String)
return null
}
}
Object rtOnRulePaused(Map plat, String ruleId, Object paused, Object eventId, Object dateMs = null) {
Object held = rtCall(plat, 'loadState', null)
if (!(held instanceof Map) || !rtNamesRule((Map) held, ruleId)) return null
Map e =[kind: 'attribute', device: RULE_STATE_PREFIX + ruleId, attribute: RULE_PAUSED_ATTRIBUTE, value: paused == true || paused == 'true']
if (dateMs instanceof Number) e.ts = (dateMs as long)
if (eventId != null) e.sourceId = "${eventId}:p".toString()
return rtOnEvents(plat, [e])
}
boolean rtNamesRule(Map rt, Object ruleId) {
if (ruleId == null) return false
String want = ruleId.toString()
List docs = new ArrayList(((Map) (rt.docs ?: [:])).values())
if (rt.staged instanceof Map) docs << ((Map) rt.staged).doc
return docs.any { Object d ->
d instanceof Map && ((Map) (((Map) d).refs ?: [:])).values().any { Object r -> r instanceof Map && ((Map) r).id?.toString() == want }
}
}
Object rtOnControl(Map plat, String target, Map call) {
return rtExecute(plat) { Map ctx ->
Map rec = rtRule(ctx)
if (rec == null || rec.id != target) return null
Map w = ctx.w
String from = (call?.from ?: 'another rule') as String
switch (call?.op) {
case 'setBoolean': coreSetRuleBoolean(w, target, call.value == true, from, ((call.depth ?: 1) as int)); break
case 'pause': corePause(w, target); break
case 'resume': coreResume(w, target); break
case 'cancelTimers': coreCancelRuns(w, target); break
case 'runActions': coreRunActions(w, target, from, ((call.depth ?: 1) as int)); break
case 'disable':
case 'enable':
rtNote(ctx, 'controlRefused', [from: from, op: call?.op,
why: 'an HAI rule is stopped and started from the editor or the end points, not by another rule'])
break
default: rtNote(ctx, 'controlRefused', [from: from, op: call?.op])
}
return null
}
}
Set rtActsOnRmRules(Map doc) {
Set out = [] as Set
Map refs = (doc?.refs ?: [:])
Closure look = { Map a ->
if (!(a.type in ['setRuleBoolean', 'controlRule']) || !(a.rules instanceof List)) return
((List) a.rules).each { Object k ->
Map ref = refs.get(k)
if (ref?.kind == 'rmRule' && ref.id != null) out << (ref.id as String)
}
}
rtWalkActions((List) (doc?.actions ?: [])) { Map a -> look(a) }
rtWalkActions((List) (doc?.onFailure ?: [])) { Map a -> look(a) }
return out
}
Map rtSwitchedOwn(Map switched) {
Map out = [:]
(switched ?: [:]).each { k, v -> if (k != null && v) out.put(k as String, v as String) }
return out
}
Object rtOnSwitched(Map plat, Map switched) {
return rtExecute(plat) { Map ctx ->
Map rec = rtRule(ctx)
if (rec == null) return null
Map rt = ctx.rt
Map before = new LinkedHashMap((Map) (rt.rmSwitched ?: [:]))
Set mine = rtActsOnRmRules((Map) rec.doc)
Map own = rtSwitchedOwn(switched).findAll { k, v -> mine.contains(k) }
if (own != before) {
if (own.isEmpty()) rt.remove('rmSwitched') else rt.rmSwitched = own
rtNote(ctx, 'rmSwitched', [now: own, was: before])
}
return null
}
}
Map rtVariableEvent(String name, Object value, String hubType, Object dateMs, Object eventId) {
Object v = schemaHubValue(hubType ?: 'string', value)
Map e = [kind: 'attribute', device: HUB_VARIABLE_PREFIX + name, attribute: CORE_VARIABLE_ATTRIBUTE, value: v == null ? value : v]
if (dateMs instanceof Number) e.ts = (dateMs as long)
if (eventId != null) e.sourceId = "${eventId}:v".toString()
return e
}
List rtHubVariableNames(Map doc) {
List out = []
((Map) (doc?.refs ?: [:])).each { k, v ->
if (v instanceof Map && ((Map) v).kind == 'variable' && (((Map) v).id as String)?.startsWith(HUB_VARIABLE_PREFIX)) {
out << (((Map) v).id as String).substring(HUB_VARIABLE_PREFIX.length())
}
}
return out.unique().sort()
}
Map rtVariableRead(Map ctx, String id) {
if (id == null) return null
if (!id.startsWith(HUB_VARIABLE_PREFIX)) return ((Map) ((Map) ctx.rt).vars).get(id)
List got = rtCall((Map) ctx.plat, 'variable', id.substring(HUB_VARIABLE_PREFIX.length()))
if (got == null) return null
Object v = schemaHubValue(got[1] as String, got[0])
return [value: v == null ? got[0] : v]
}
void rtVariableWrite(Map ctx, String id, Map cell) {
if (!id.startsWith(HUB_VARIABLE_PREFIX)) { ((Map) ((Map) ctx.rt).vars).put(id, cell); return }
String name = id.substring(HUB_VARIABLE_PREFIX.length())
Map row = ((Map) (rtInventory(ctx)?.variables ?: [:])).get(id)
if (row == null || row.writable == false) {
rtNote(ctx, 'variableRefused', [variable: id, why: row == null ? 'no such hub variable' : 'not one rules may change'])
throw new Exception("rules may not change the hub variable '${name}'")
}
Object v = schemaHubValue(row.hubType as String, cell.value)
if (v == null) throw new Exception("'${cell.value}' is not a value the hub variable '${name}' can hold")
rtCall((Map) ctx.plat, 'setVariable', [name, v])
}
Object rtRenameVariable(Map plat, String oldName, String newName) {
return rtExecute(plat) { Map ctx ->
Map rec = rtRule(ctx)
if (rec == null) return null
String from = HUB_VARIABLE_PREFIX + oldName
boolean changed = false
((Map) (((Map) rec.doc).refs ?: [:])).each { k, v ->
if (v instanceof Map && ((Map) v).kind == 'variable' && ((Map) v).id == from) {
((Map) v).id = HUB_VARIABLE_PREFIX + newName
((Map) v).name = newName
changed = true
}
}
if (changed) {
rtNote(ctx, 'variableRenamed', [from: oldName, to: newName])
rtSubscribe(ctx, rec.doc)
}
return changed
}
}
Map rtLocationEvent(String name, Object value, Object dateMs, Object eventId) {
Map e = [kind: 'attribute', device: LOCATION_DEVICE, attribute: name, value: value == null ? null : value.toString()]
if (dateMs instanceof Number) e.ts = (dateMs as long)
if (eventId != null) e.sourceId = "${eventId}:l".toString()
return e
}
Map rtModeEvent(Object value, Object dateMs, Object eventId) {
Map e = [kind: 'mode', value: value == null ? null : value.toString()]
if (dateMs instanceof Number) e.ts = (dateMs as long)
if (eventId != null) e.sourceId = "${eventId}:m".toString()
return e
}
Object rtCall(Map plat, String name, Object arg) {
Closure f = (Closure) plat.get(name)
return f == null ? null : f(arg)
}
void rtHook(Map ctx, String name) {
Closure h = (Closure) ((Map) ctx.plat).get('hook')
if (h != null) h(name)
}
Map rtNewState() {
return [v: 1, ruleId: null, bootAt: null, lastNow: null, lastUptimeMs: null, restartEpoch: 0,
world: null, docs: [:], evidence: [:], vars: [:], deadlines: [:], subs: [], allow: [],
staged: null, committed: null, firstCommittedAt: null, totalRuns: 0L, runsObservedSince: null,
discarded: null, activeHash: null, blocked: false, purposes: [:],
devWithhold: 0, ledgerSummary: [total: 0, byState: [:], lastId: null],
lastWatchdogAt: null, health: [:], lateSamples: [], bytes: 0,
trace: [runs: [], rejections: [], notes: [], counters: [:], droppedRuns: 0, unmatched: [:], coveredFrom: null]]
}
Map rtEncodeWorld(Map w) {
Map out = [:]
['seq', 'nextRun', 'nextToken', 'blocked', 'ledger', 'latestIntent', 'verifyMs', 'seenSources', 'holds', 'gaps'].each {
out.put(it, w.get(it))
}
out.occurrences = new ArrayList((Collection) w.occurrences)
Map rules = [:]
((Map) w.rules).each { Object id, Object ro ->
Map r = new LinkedHashMap((Map) ro)
r.remove('doc')
r.running = new ArrayList((Collection) r.running)
rules.put(id, r)
}
out.rules = rules
Map runs = [:]
((Map) w.runs).each { Object id, Object ro ->
Map run = ro
Map r = new LinkedHashMap(run)
r.frames = ((List) run.frames).collect { Object fo ->
Map f = fo
Map e = [lp: (f.path ?: 'actions'), index: f.index]
if (f.path != null) e.path = f.path
if (f.repeat instanceof Map) e.repeat = f.repeat
if (f.gradual instanceof Map) e.gradual = f.gradual
e
}
if (run.wait != null) {
Map wt = new LinkedHashMap((Map) run.wait)
wt.remove('action')
r.wait = wt
}
runs.put(id, r)
}
out.runs = runs
return out
}
Object rtResolve(Map doc, String path) {
Object at = doc
for (String part : path.tokenize('.')) {
if (at instanceof Map) at = ((Map) at).get(part)
else if (at instanceof List && part.isInteger() && (part as int) < ((List) at).size()) at = ((List) at)[part as int]
else return null
}
return at
}
Map rtDecodeWorld(Map rt, Map enc) {
Map w = coreNewWorld()
if (enc == null) return w
['seq', 'nextRun', 'nextToken', 'blocked', 'ledger', 'latestIntent', 'verifyMs', 'seenSources', 'holds', 'gaps'].each {
if (enc.get(it) != null) w.put(it, enc.get(it))
}
w.seq = (w.seq as long)
w.occurrences = new HashSet((Collection) (enc.occurrences ?: []))
Map docs = rt.docs
Map rules = [:]
((Map) (enc.rules ?: [:])).each { Object id, Object ro ->
Map r = new LinkedHashMap((Map) ro)
r.running = new LinkedHashSet((Collection) (r.running ?: []))
r.doc = docs.get(r.epoch.toString())
r.queue = new ArrayList((Collection) (r.queue ?: []))
rules.put(id as String, r)
}
w.rules = rules
Map runs = [:]
((Map) (enc.runs ?: [:])).each { Object id, Object ro ->
Map r = new LinkedHashMap((Map) ro)
Map doc = docs.get(r.epoch.toString())
r.frames = ((List) (r.frames ?: [])).collect { Object fo ->
Map f = fo
Object list = doc == null ? null : rtResolve(doc, f.lp as String)
Map frame = [list: (list instanceof List ? list : []), index: f.index as int]
if (f.path != null) frame.path = f.path
if (f.repeat instanceof Map) {
frame.repeat = new LinkedHashMap((Map) f.repeat)
Object a = doc == null ? null : rtResolve(doc, ((Map) f.repeat).path as String)
frame.action = a instanceof Map ? a : [:]
}
if (f.gradual instanceof Map) {
frame.gradual = new LinkedHashMap((Map) f.gradual)
Object a = doc == null ? null : rtResolve(doc, ((Map) f.gradual).path as String)
frame.action = a instanceof Map ? a : [:]
frame.list = doc == null ? [] : coreGradualSteps(doc, frame.action, frame.gradual)
}
frame
}
if (r.wait != null) {
Map wt = new LinkedHashMap((Map) r.wait)
Object a = doc == null ? null : rtResolve(doc, wt.path as String)
wt.action = a instanceof Map ? a : [:]
r.wait = wt
}
runs.put(id as String, r)
}
w.runs = runs
return w
}
Map rtEncode(Map ctx) {
Map out = new LinkedHashMap((Map) ctx.rt)
out.world = rtEncodeWorld((Map) ctx.w)
return out
}
Map rtBudgetOf(Map ctx) {
Map rt = ctx.rt
Map rest = rtEncode(ctx)
Object docsV = rest.remove('docs')
Object traceV = rest.remove('trace')
int docsBytes = rtDocsBytes(rt, docsV)
int traceBytes = rtBytes(traceV)
int total = rtBytes(rest) + 'docs'.length() + 4 + docsBytes + 'trace'.length() + 4 + traceBytes
int docs = docsBytes + rtBytes(rest.staged ?: [:]) + rtBytes(rest.discarded ?: [:])
return [total: total, docs: docs, working: total - docs, trace: traceBytes]
}
int rtDocsBytes(Map rt, Object docsV) {
if (!(docsV instanceof Map)) return rtBytes(docsV)
Map docs = docsV
Map had = rt.docBytes instanceof Map ? rt.docBytes : [:]
Map sizes = [:]
int n = 2
docs.each { Object k, Object d ->
Object b = had.get(k.toString())
int size = b instanceof Number ? (b as int) : rtBytes(d)
sizes.put(k.toString(), size)
n += rtBytes(k.toString()) + 1 + size
}
if (docs.size() > 1) n += docs.size() - 1
rt.docBytes = sizes
return n
}
int rtDropTrace(Map trace, int bytes, int limit, boolean loseCoverage) {
int left = bytes
[trace.runs, trace.notes, trace.rejections].eachWithIndex { Object lo, int which ->
List list = lo
int keep = which == 0 ? RT_TRACE_MIN_RUNS : 0
while (list.size() > keep && left > limit) {
Object gone = list.remove(0)
left -= rtBytes(gone) + (list.isEmpty() ? 0 : 1)
if (which == 0) trace.droppedRuns = (trace.droppedRuns as int) + 1
if (loseCoverage) rtLoseCoverage(trace, gone)
}
}
return left
}
int rtRunHeadroom() { return RT_MIN_RUN_BYTES + RT_LEDGER_RESERVE_BYTES }
void rtRebuildTimerJobs(Map ctx) {
Map rt = ctx.rt
Map plat = ctx.plat
Closure drop = (Closure) ((Map) plat).unscheduleHandler
if (drop == null) return
long now = rtNow(ctx)
List keep = ((Map) rt.deadlines).values().findAll { ((Map) it).firedAt == null && ((Map) it).kind != 'dispatch' }
drop('haiTimer')
int armed = 0
int overdue = 0
int withheld = 0
keep.sort { a, b -> (a.dueAt as long) <=> (b.dueAt as long) }.each { Object ro ->
Map row = ro
if ((row.dueAt as long) <= now) { overdue++; return }
if (row.withheld == true) { withheld++; return }
rtCall(plat, 'timerAt', [row.dueAt as long, 'haiTimer', [token: row.token]])
armed++
}
rtNote(ctx, 'timerJobsRebuilt', [armed: armed, overdue: overdue, withheld: withheld,
kinds: keep.collect { ((Map) it).kind }.unique().sort()])
}
void rtCancelSupersededSchedules(Map ctx, int epoch) {
Map rt = ctx.rt
Map deadlines = rt.deadlines
new ArrayList(deadlines.values()).each { Object ro ->
Map row = ro
if (row.kind != 'schedule' || row.firedAt != null) return
if ((row.epoch as int) == epoch) return
deadlines.remove(row.token)
rtNote(ctx, 'scheduleSuperseded', [token: row.token, epoch: row.epoch, now: epoch, dueAt: row.dueAt])
}
}
Map rtPort(Map ctx) {
Map rt = ctx.rt
Map plat = ctx.plat
return [
now : { Object unused -> rtCall(plat, 'now', null) as long },
restartEpoch : { Object unused -> rt.restartEpoch as int },
zone : { Object unused -> rtCall(plat, 'zone', null) as String },
inventory : { Object unused -> rtInventory(ctx) },
device : { Object dev ->
Closure f = (Closure) plat.get('device')
f == null || ctx.inv != null ? invDevice(rtInventory(ctx), dev as String) : f(dev)
},
validate : { Map doc -> rtHeldCheck(ctx, doc) },
sun : { String date -> rtCall(plat, 'sun', date) },
halted : { Object unused -> false },
read : { String dev, Object attr -> rtRead(ctx, dev, attr as String) },
record : { String dev, Object attr, Map cell -> rtRecord(ctx, dev, attr as String, cell) },
observed : { String dev, Object attr, Object v -> null },
readBack : { String dev, Object attr -> rtReadBack(ctx, dev, attr as String) },
readMode : { Object unused -> rtModeRead(ctx) },
recordMode : { Object v -> rt.modeSeen = v },
readVariable : { Object id -> rtVariableRead(ctx, id as String) },
writeVariable: { Object id, Map cell -> rtVariableWrite(ctx, id as String, cell) },
subscribe : { String ruleId, Map doc -> rtSubscribe(ctx, doc) },
unsubscribe : { String ruleId -> rtUnsubscribe(ctx) },
schedule : { Map timer -> rtArm(ctx, timer) },
cancel : { Object token ->
((Map) rt.deadlines).remove(token as String)
((Map) rt.deadlines).remove("${token}${RT_FRESH_SUFFIX}".toString())
},
dispatch : { Map effect -> rtSend(ctx, effect) },
dispatchNow : { Map effect -> rtSendNow(ctx, effect) },
invoke : { Map call -> rtCall(plat, 'announceInvoke', call) },
ruleBoolean : { Map call -> rtCall(plat, 'announceControl', [target: call.target, op: 'setBoolean', value: call.value, from: call.from, depth: call.depth]) },
controlRule : { Map call -> rtCall(plat, 'announceControl', call) },
rulePaused : { Map call -> rtCall(plat, 'announcePaused', call) },
log : { Map line -> rtCall(plat, 'log', line) },
setMode : { Object mode ->
if (((Map) ctx.rt).modeAllowed != true) throw new Exception('turn on "Allow rules to change the mode" on the HAI Engine page first')
rtCall(plat, 'setMode', mode)
},
returnTo : { Map call -> rtCall(plat, 'announceReturn', call) },
setHsm : { Object op -> rtCall(plat, 'setHsm', op) },
rmAction : { Map call -> rtCall(plat, 'rmAction', call) },
rmSwitched : { Object rmId -> ((Map) (rt.rmSwitched ?: [:])).get(rmId as String) },
appDisable : { Map call -> rtCall(plat, 'appDisable', call) },
net : { Map req ->
String why = rtNetRefusal(req)
if (why != null) { rtNote(ctx, 'netRefused', [kind: req.kind, why: why]); throw new Exception(why) }
rtCall(plat, 'net', req)
},
file : { Map req -> rtCall(plat, 'file', req) },
readFile : { Object name -> rtCall(plat, 'readFile', name) },
hubEchoesVariables: { Object u -> true },
defer : { Map cont -> rtDefer(ctx, cont) },
ackModel : { Map intent, Map op -> null },
persist : { Map world -> ctx.dirty = true },
trace : { Map entry -> rtTrace(ctx, entry) },
]
}
long rtNow(Map ctx) { return rtCall((Map) ctx.plat, 'now', null) as long }
boolean rtModeSubscribed(Map rt) {
return ((List) (rt.subs ?: [])).any { ((List) it)[0] == RT_LOCATION && ((List) it)[1] == 'mode' }
}
Object rtModeRead(Map ctx) {
Map rt = ctx.rt
if (rtModeSubscribed(rt) && rt.modeSeen != null) return rt.modeSeen
return rtCall((Map) ctx.plat, 'mode', null)
}
Map rtInventory(Map ctx) {
if (ctx.inv == null) {
Map rt = ctx.rt
List docs = new ArrayList(((Map) rt.docs).values())
if (rt.staged != null) docs << ((Map) rt.staged).doc
Map inv = rtWithRuleVariables((Map) rtCall((Map) ctx.plat, 'inventory', null), docs)
Map purposes = (rt.purposes ?: [:])
((Map) inv.devices).values().each { Object d ->
Map row = d
Object p = purposes.get(rtBareId(row.id))
if (p != null) row.commissionedPurpose = p else row.remove('commissionedPurpose')
}
if (rt.knownRules != null) ((Collection) inv.rules).addAll((List) rt.knownRules)
if (rt.writableVariables != null) {
List writable = rt.writableVariables
((Map) (inv.variables ?: [:])).values().each { Object v ->
Map row = v
if (row.scope == 'hub') row.writable = writable.contains(row.name)
}
}
ctx.inv = inv
}
return ctx.inv
}
Map rtBegin(Map plat) {
Object loaded = rtCall(plat, 'loadState', null)
Map rt = loaded instanceof Map ? loaded : rtNewState()
Map ctx = [plat: plat, rt: rt, loose: [], started: [], execId: rtCall(plat, 'execId', null)]
ctx.w = rtDecodeWorld(rt, rt.world)
((Map) ctx.w).noRetry = true
((Map) ctx.w).port = rtPort(ctx)
rtBoot(ctx)
rtSettleFences(ctx)
return ctx
}
void rtEnd(Map ctx) {
boolean asleep = rtSettleSleep(ctx)
rtFlushLoose(ctx)
rtCompact(ctx)
rtBudget(ctx)
rtFlushLoose(ctx)
rtSelfCheckWanted(ctx)
((Map) ctx.rt).lightMark = rtLight(ctx).mark
rtCall((Map) ctx.plat, 'saveState', rtEncode(ctx))
rtCall((Map) ctx.plat, 'idle', asleep)
}
void rtSelfCheckWanted(Map ctx) {
Map plat = ctx.plat
if (plat.get('selfCheck') == null) return
Map rt = ctx.rt
List due = ((Map) rt.deadlines).values().findAll { ((Map) it).firedAt == null }.collect { ((Map) it).dueAt as Long }.findAll { it != null }
if (!due.isEmpty()) {
long at = (due.min() as long) + RT_GRACE_MS + 5000L
if (rt.selfCheck != true || rt.selfCheckAt != at) { rtCall(plat, 'selfCheck', at); rt.selfCheck = true; rt.selfCheckAt = at }
} else if (ctx.selfPass == true && rt.selfCheck == true) {
rtCall(plat, 'selfCheck', false); rt.selfCheck = false; rt.remove('selfCheckAt')
}
}
boolean rtSettleSleep(Map ctx) {
Map rt = ctx.rt
boolean sleep = rtCanSleep(ctx)
if (sleep) rtSleep(ctx)
else if (rt.asleep == true) rtWake(ctx)
return sleep
}
boolean rtCanSleep(Map ctx) {
Map rec = rtRule(ctx)
if (rec == null || rec.doc == null || !(rec.paused == true || rec.disabled == true)) return false
if (!((List) (rec.queue ?: [])).isEmpty()) return false
if (((Map) ((Map) ctx.w).runs).values().any { Object r -> ((Map) r).rule == rec.id && !(((Map) r).status in RT_TERMINAL_RUN) }) return false
if (((Map) (rec.durations ?: [:])).values().any { it instanceof Map }) return false
return !((Map) ((Map) ctx.rt).deadlines).values().any { Object r -> ((Map) r).firedAt == null && ((Map) r).kind != 'schedule' }
}
void rtSleep(Map ctx) {
Map rt = ctx.rt
Map rec = rtRule(ctx)
boolean first = rt.asleep != true
rt.asleep = true
Map deadlines = rt.deadlines
List sched = deadlines.values().findAll { Object r -> ((Map) r).firedAt == null && ((Map) r).kind == 'schedule' }
if (!sched.isEmpty()) {
sched.each { Object r -> deadlines.remove(((Map) r).token) }
rtCall((Map) ctx.plat, 'unscheduleHandler', 'haiTimer')
}
if (rt.selfCheck == true && ((Map) ctx.plat).get('selfCheck') != null) {
rtCall((Map) ctx.plat, 'selfCheck', false); rt.selfCheck = false; rt.remove('selfCheckAt')
}
if (!rec.disabled && rtWantSlots(rt, rec.doc) != (rt.subs ?: [])) rtResubscribeKeepingWatch(ctx, rec.doc)
if (first) {
String why = rec.disabled ? 'stopped' : 'paused'
((Map) rt.trace).asleep = [t: rtNow(ctx), why: why]
rtNote(ctx, 'asleep', [why: why, schedules: sched.size()])
}
}
void rtResubscribeKeepingWatch(Map ctx, Map doc) {
Map rt = ctx.rt
boolean current = rt.watchKey != null && rt.watchKey == rtWatchKey(rt)
rtSubscribe(ctx, doc)
if (current) rt.watchKey = rtWatchKey(rt)
}
void rtWake(Map ctx) {
Map rt = ctx.rt
rt.remove('asleep')
((Map) rt.trace).remove('asleep')
Map rec = rtRule(ctx)
if (rec != null && !rec.disabled && rec.doc != null) {
rtResubscribeKeepingWatch(ctx, rec.doc)
rtEnsureSchedules(ctx)
}
rtNote(ctx, 'awake', [paused: rec?.paused == true, disabled: rec?.disabled == true])
}
List rtWantSlots(Map rt, Map doc) {
List all = rtSlots(doc)
if (rt?.asleep != true) return all
return all.findAll { Object so ->
List s = so
String dev = s[0] as String
dev == RT_LOCATION ? ((s[1] as String) in RT_ASLEEP_KEEP) : (dev.startsWith(RULE_STATE_PREFIX) || dev.startsWith(RULE_VARIABLE_PREFIX))
}
}
Object rtExecute(Map plat, Closure work) {
Map ctx = rtBegin(plat)
Object out = work(ctx)
rtEnd(ctx)
return out
}
Map rtRule(Map ctx) {
Map rt = ctx.rt
return rt.ruleId == null ? null : ((Map) ((Map) ctx.w).rules).get(rt.ruleId)
}
int rtEpoch(Map ctx) {
Map rec = rtRule(ctx)
return rec == null ? 0 : (rec.epoch as int)
}
void rtBoot(Map ctx) {
Map rt = ctx.rt
long now = rtNow(ctx)
long upMs = (rtCall((Map) ctx.plat, 'uptime', null) as long) * 1000L
if (rt.lastNow == null) {
rt.bootAt = now - upMs
} else {
long elapsed = now - (rt.lastNow as long)
if (upMs + RT_BOOT_TOLERANCE_MS < elapsed) {
rtOnRestart(ctx, now - upMs)
} else {
long expected = (rt.lastNow as long) + (upMs - ((rt.lastUptimeMs ?: 0L) as long))
long jump = now - expected
if (jump < -RT_CLOCK_JUMP_MS) rtNote(ctx, 'clockBackward', [jumpMs: jump])
if (jump > RT_CLOCK_JUMP_MS) rtNote(ctx, 'clockForward', [jumpMs: jump])
rt.bootAt = now - upMs
}
}
rt.lastUptimeMs = upMs
rt.lastNow = now
Map health = rt.health
boolean stale = rt.lastWatchdogAt != null && now - (rt.lastWatchdogAt as long) > 3L * RT_CADENCE_MS
if (stale) health.watchdogStale = now else health.remove('watchdogStale')
}
void rtOnRestart(Map ctx, long bootAt) {
Map rt = ctx.rt
Map w = ctx.w
rt.restartEpoch = (rt.restartEpoch as int) + 1
rt.bootAt = bootAt
rt.remove('selfCheckAt')
rtNote(ctx, 'restarted', [restartEpoch: rt.restartEpoch, bootAt: bootAt])
coreDropDurations(w)
Map rec = rtRule(ctx)
if (rec != null && !rec.disabled) rtSubscribe(ctx, rec.doc)
long now = rtNow(ctx)
List rows = ((Map) rt.deadlines).values().findAll { ((Map) it).firedAt == null }.sort { a, b -> (a.dueAt as long) <=> (b.dueAt as long) }
rows.each { Object ro ->
Map row = ro
if ((row.dueAt as long) > now) rtCall((Map) ctx.plat, 'timerAt', [row.dueAt as long, 'haiTimer', [token: row.token]])
}
rows.each { Object ro ->
Map row = ro
if ((row.dueAt as long) <= now && ((Map) rt.deadlines).get(row.token) != null && row.firedAt == null) {
rtFireRow(ctx, row, 'restart')
}
}
rtEnsureSchedules(ctx)
}
void rtEnsureSchedules(Map ctx) {
Map rec = rtRule(ctx)
if (rec == null || rec.disabled || rec.doc == null) return
Map rt = ctx.rt
if (rt.asleep == true) return
Map w = ctx.w
rtUnarmedSchedules((Map) rec.doc, rt.deadlines, rec.epoch as int).each { int ti ->
coreArmNextOccurrence(w, rec, ti, coreLocal(w, rtNow(ctx)).toLocalDateTime())
}
}
List rtUnarmedSchedules(Map doc, Map deadlines, int epoch) {
List out = []
((List) (doc.triggers ?: [])).eachWithIndex { Object t, int ti ->
if (!(t instanceof Map) || !(((Map) t).kind in ['schedule', 'periodic'])) return
boolean armed = ((Map) (deadlines ?: [:])).values().any { Object ro ->
Map row = ro
row.kind == 'schedule' && row.firedAt == null && (row.epoch as int) == epoch &&
(((Map) row.timer).trigger as int) == ti
}
if (!armed) out << ti
}
return out
}
Map rtRead(Map ctx, String dev, String attr) {
Map rt = ctx.rt
Map pre = ((Map) ctx.w)?.liveRead ? null : ((Map) ctx.preSend)?.get(dev)
if (pre != null && pre.containsKey(attr) && pre.get(attr) != null) return pre.get(attr)
boolean subscribed = ((List) (rt.subs ?: [])).any { ((List) it)[0] == dev && ((List) it)[1] == attr }
Map cell = ((Map) ((Map) rt.evidence).get(dev))?.get(attr)
Map hub = subscribed ? null : rtHubCell(ctx, dev, attr)
return hub != null && (cell == null || (hub.ts as long) >= (cell.ts as long)) ? hub : cell
}
Map rtHubCell(Map ctx, String dev, String attr) {
Map rt = ctx.rt
if (dev.startsWith(RULE_VARIABLE_PREFIX)) return null
if (dev.startsWith(RULE_STATE_PREFIX)) {
List paused = rt.pausedRules
if (paused == null) return null
return [value: paused.contains(dev.substring(RULE_STATE_PREFIX.length())), ts: (rt.pausedRulesAt ?: 0L) as long, seq: 0L,
source: 'hub', epoch: rt.restartEpoch as int]
}
if (dev.startsWith(HUB_VARIABLE_PREFIX)) {
Map cur = rtVariableRead(ctx, dev)
return cur == null ? null : [value: cur.value, ts: rtNow(ctx), seq: 0L, source: 'hub', epoch: rt.restartEpoch as int]
}
Closure f = (Closure) ((Map) ctx.plat).currentState
List cs = f == null ? null : f(dev, attr)
if (cs == null || cs[0] == null) return null
long ts = cs[1] instanceof Number ? (cs[1] as long) : rtNow(ctx)
int epoch = rt.restartEpoch as int
if (rt.bootAt != null && ts < (rt.bootAt as long)) epoch = epoch - 1
return [value: cs[0], ts: ts, seq: 0L, source: 'hub', epoch: epoch]
}
Map rtReadBack(Map ctx, String dev, String attr) {
if (!rtMayUse(ctx, dev)) return null
Closure f = (Closure) (((Map) ctx.plat).currentFresh ?: ((Map) ctx.plat).currentState)
List cs = f == null ? null : f(dev, attr)
return (cs == null || cs[0] == null) ? null : [value: cs[0]]
}
void rtRecord(Map ctx, String dev, String attr, Map cell) {
Map ev = ((Map) ctx.rt).evidence
Map d = ev.get(dev)
if (d == null) { d = [:]; ev.put(dev, d) }
d.put(attr, cell)
}
void rtSubscribe(Map ctx, Map doc) {
Map rt = ctx.rt
Map plat = ctx.plat
rtCall(plat, 'unlistenAll', null)
rtCall(plat, 'useVariables', rtHubVariableNames(doc))
List slots = rtWantSlots(rt, doc)
Closure sub = (Closure) plat.listen
slots.each { Object s -> sub(((List) s)[0], ((List) s)[1]) }
rt.subs = slots
rt.modeSeen = rtModeSubscribed(rt) ? rtCall(plat, 'mode', null) : null
slots.each { Object s ->
String dev = ((List) s)[0] as String
String attr = ((List) s)[1] as String
if ((dev == RT_LOCATION && attr != 'hsmStatus') || attr in RT_BUTTON_EVENTS) return
Map hub = rtHubCell(ctx, dev, attr)
if (hub == null) return
Map have = ((Map) ((Map) rt.evidence).get(dev))?.get(attr)
if (have == null || (hub.ts as long) > (have.ts as long)) rtRecord(ctx, dev, attr, hub)
}
}
void rtUnsubscribe(Map ctx) {
rtCall((Map) ctx.plat, 'unlistenAll', null)
rtCall((Map) ctx.plat, 'useVariables', [])
((Map) ctx.rt).subs = []
((Map) ctx.rt).modeSeen = null
}
void rtArm(Map ctx, Map timer) {
Map rt = ctx.rt
String token = (timer.kind == 'schedule' ? "s${timer.epoch}.${timer.trigger}.${timer.at}" : (timer.token ?: "v${timer.intent}")) as String
if (timer.kind == 'freshness') token = "${token}${RT_FRESH_SUFFIX}".toString()
long now = rtNow(ctx)
Map row = [token: token, kind: timer.kind, run: timer.run, epoch: timer.epoch, dueAt: timer.at as long,
armedAt: now, firedAt: null, restartEpoch: rt.restartEpoch, timer: timer]
((Map) rt.deadlines).put(token, row)
if (rtWithhold(ctx, row)) return
rtCall((Map) ctx.plat, 'timerAt', [timer.at as long, 'haiTimer', [token: token]])
}
boolean rtWithhold(Map ctx, Map row) {
Map rt = ctx.rt
if (((rt.devWithhold ?: 0) as int) <= 0) return false
rt.devWithhold = (rt.devWithhold as int) - 1
Map held = ((Map) rt.deadlines).get(row.token)
if (held != null) held.withheld = true
rtNote(ctx, 'timerWithheld', [token: row.token, kind: row.kind, run: row.run, dueAt: row.dueAt])
return true
}
void rtDefer(Map ctx, Map cont) {
Map rt = ctx.rt
long now = rtNow(ctx)
Map row = [token: cont.token, kind: 'dispatch', run: cont.run, epoch: cont.epoch, dueAt: cont.at as long,
armedAt: now, firedAt: null, restartEpoch: rt.restartEpoch, timer: cont]
((Map) rt.deadlines).put(cont.token as String, row)
if (!rtWithhold(ctx, row)) rtCall((Map) ctx.plat, 'timerIn', [RT_CONTINUE_MS, 'haiContinue', [token: cont.token]])
rtHook(ctx, 'afterRequest')
}
Object rtOnTimer(Map plat, String token, String via) {
return rtExecute(plat) { Map ctx ->
Map rt = ctx.rt
Map row = ((Map) rt.deadlines).get(token)
if (row == null) { rtNote(ctx, 'staleWake', [token: token, via: via]); return null }
if (row.firedAt != null) { rtNote(ctx, 'duplicateWake', [token: token, via: via, run: row.run]); return null }
long now = rtNow(ctx)
if (now < (row.dueAt as long)) {
rtNote(ctx, 'earlyWake', [token: token, dueAt: row.dueAt])
rtCall(plat, 'timerAt', [row.dueAt as long, 'haiTimer', [token: token]])
return null
}
rtFireRow(ctx, row, via)
return null
}
}
void rtFireRow(Map ctx, Map row, String via) {
Map rt = ctx.rt
Map w = ctx.w
long now = rtNow(ctx)
Map liveRec = rtRule(ctx)
if (row.kind == 'schedule' && liveRec != null && (row.epoch as int) != (liveRec.epoch as int)) {
((Map) rt.deadlines).remove(row.token)
rtNote(ctx, 'scheduleSuperseded', [token: row.token, epoch: row.epoch, now: liveRec.epoch, dueAt: row.dueAt])
return
}
long late = now - (row.dueAt as long)
row.firedAt = now
row.via = via
row.lateMs = late
Map detail = [token: row.token, kind: row.kind, dueAt: row.dueAt, firedAt: now, lateMs: late, via: via]
if (row.run != null) detail.run = row.run
rtNote(ctx, 'fired', detail)
List samples = rt.lateSamples
samples << [kind: row.kind, via: via, lateMs: late, at: now]
while (samples.size() > RT_LATE_SAMPLES) samples.remove(0)
Map timer = row.timer
if (row.kind == 'dispatch') { coreSend(w, timer); return }
boolean sameBoot = (row.restartEpoch as int) == (rt.restartEpoch as int)
if (row.kind == 'schedule' && late > RT_RECOVERY_MS) {
Map health = rt.health
String key = sameBoot ? 'timerRecoveryExceeded' : 'scheduleMissedByRestart'
health.put(key, ((health.get(key) ?: 0) as int) + 1)
health.put(sameBoot ? 'lastRecoveryExceeded' : 'lastScheduleMissed', [at: now, lateMs: late, kind: row.kind])
coreScheduleMissed(w, timer, late, via)
return
}
if (sameBoot && late > RT_RECOVERY_MS) {
Map health = rt.health
health.timerRecoveryExceeded = ((health.timerRecoveryExceeded ?: 0) as int) + 1
health.lastRecoveryExceeded = [at: now, lateMs: late, kind: row.kind]
if (row.kind in ['resume', 'waitTimeout', 'waitEvent']) { coreTimerExceeded(w, timer, late); return }
}
coreFire(w, timer)
}
Map rtOverdue(Map plat, long parentNow, List allow, Object lastWatchdogAt, boolean selfPass = false, Object allowHash = null) {
Map held = null
Map out = rtExecute(plat) { Map ctx ->
held = ctx
Map rt = ctx.rt
ctx.selfPass = selfPass
if (allow != null) {
Set mine = [] as Set
List docs = new ArrayList(((Map) (rt.docs ?: [:])).values())
if (rt.staged instanceof Map) docs << ((Map) rt.staged).doc
docs.each { Object d -> if (d instanceof Map) rtDocDevices((Map) d).each { mine << rtBareId(it) } }
List own = allow.collect { it.toString() }.findAll { mine.contains(it) }
if (own != rt.allow) rt.allow = own
}
rt.lastWatchdogAt = lastWatchdogAt ?: parentNow
((Map) rt.health).remove('watchdogStale')
Map live = rtRule(ctx)
if (live != null && !live.disabled) {
List want = rtWantSlots(rt, live.doc).collect { ((List) it).join('|') }
List have = ((List) (rt.subs ?: [])).collect { ((List) it).join('|') }
if (want != have) { rtSubscribe(ctx, live.doc); rtNote(ctx, 'resubscribed', [slots: want.size()]) }
}
if (!selfPass && allow != null && allowHash != null && live != null && !live.disabled) {
rt.allowHash = allowHash.toString()
rt.watchKey = rtWatchKey(rt)
}
long now = rtNow(ctx)
List fired = []
List due = ((Map) rt.deadlines).values().findAll { Object r ->
((Map) r).firedAt == null && ((((Map) r).dueAt as long) < now - RT_GRACE_MS)
}.sort { a, b -> (a.dueAt as long) <=> (b.dueAt as long) }
due.each { Object ro ->
Map row = ro
if (((Map) rt.deadlines).get(row.token) == null || row.firedAt != null) return
rtFireRow(ctx, row, 'watchdog')
fired << [token: row.token, kind: row.kind, dueAt: row.dueAt, firedAt: row.firedAt, lateMs: row.lateMs]
}
rtEnsureSchedules(ctx)
List samples = new ArrayList((List) rt.lateSamples)
rt.lateSamples = []
return [fired: fired, rows: ((Map) rt.deadlines).size(), blocked: rt.blocked, health: new LinkedHashMap((Map) rt.health),
epoch: rtEpoch(ctx), ruleHash: rt.activeHash, childNow: now, lateSamples: samples, light: null]
}
if (!selfPass) out.light = rtLight(held)
return out
}
Map rtWatchdogCall(Map plat, long parentNow, List allow, Object lastWatchdogAt, Object allowHash) {
Map quiet = rtOverdueQuiet(plat, allowHash)
return quiet != null ? quiet : rtOverdue(plat, parentNow, allow, lastWatchdogAt, false, allowHash)
}
Map rtOverdueQuiet(Map plat, Object allowHash) {
if (allowHash == null) return null
Object loaded = rtCall(plat, 'loadState', null)
if (!(loaded instanceof Map)) return null
Map rt = loaded
if (rt.ruleId == null || rt.lightMark == null || rt.blocked == true || rt.staged != null) return null
if (rt.allowHash != allowHash.toString() || rt.watchKey == null || rt.watchKey != rtWatchKey(rt)) return null
if (!((List) (rt.lateSamples ?: [])).isEmpty()) return null
Map rec = ((Map) (((Map) (rt.world ?: [:])).rules ?: [:])).get(rt.ruleId)
if (rec == null || rec.disabled == true || rec.epoch == null) return null
long now = rtCall(plat, 'now', null) as long
if (rt.lastNow == null) return null
long upMs = ((rtCall(plat, 'uptime', null) ?: 0L) as long) * 1000L
if (upMs + RT_BOOT_TOLERANCE_MS < now - (rt.lastNow as long)) return null
Map deadlines = (rt.deadlines ?: [:])
if (deadlines.values().any { Object r -> ((Map) r).firedAt == null && ((((Map) r).dueAt as long) < now - RT_GRACE_MS) }) return null
Map doc = ((Map) (rt.docs ?: [:])).get(rec.epoch.toString())
if (doc == null || (rt.asleep != true && !rtUnarmedSchedules(doc, deadlines, rec.epoch as int).isEmpty())) return null
int epoch = rec.epoch as int
return [fired: [], rows: deadlines.size(), blocked: rt.blocked, health: new LinkedHashMap((Map) (rt.health ?: [:])),
epoch: epoch, ruleHash: rt.activeHash, childNow: now, lateSamples: [], quiet: true,
light: [ruleId: rt.ruleId, epoch: epoch, ruleHash: rt.activeHash, paused: rec.paused ?: false,
disabled: rec.disabled ?: false, staged: false, mark: rt.lightMark]]
}
String rtWatchKey(Map rt) {
String subs = ((List) (rt.subs ?: [])).collect { ((List) it).join('|') }.join(',')
return "${RT_CHILD_BUILD}|${rt.activeHash}|${Integer.toHexString(subs.hashCode())}".toString()
}
Map rtLight(Map ctx) {
Map rt = ctx.rt
Map rec = rtRule(ctx)
Map w = ctx.w
Map t = (rt.trace ?: [:])
Closure last = { Object l -> l instanceof List && !((List) l).isEmpty() ? (((Map) ((List) l)[-1]).t ?: ((Map) ((List) l)[-1]).at) : null }
List runs = (t.runs ?: [])
Object lastRun = runs.isEmpty() ? null : [((Map) runs[-1]).id, ((List) (((Map) runs[-1]).tail ?: [])).size()]
String mark = [rtEpoch(ctx), rec?.paused ?: false, rec?.disabled ?: false, rt.staged == null ? null : ((Map) rt.staged).claimId,
w.seq, w.nextRun, w.nextToken, lastRun, last(t.rejections), last(t.notes), t.unmatched,
((Map) w.ledger ?: [:]).size(), rt.blocked].toString()
return [ruleId: rt.ruleId, epoch: rtEpoch(ctx), ruleHash: rt.activeHash, paused: rec?.paused ?: false,
disabled: rec?.disabled ?: false, staged: rt.staged != null, mark: mark]
}
Map rtFences(Map plat) {
Object f = rtCall(plat, 'atomicGet', RT_FENCE_KEY)
return f instanceof Map ? f : [:]
}
void rtSettleFences(Map ctx) {
Map plat = ctx.plat
Map fences = rtFences(plat)
ctx.fences = fences
if (fences.isEmpty()) return
Map w = ctx.w
Map ledger = w.ledger
Map byKey = [:]
ledger.values().each { Object io -> String k = coreStepKey((Map) io); if (k != null) byKey.put(k, io) }
Map next = new LinkedHashMap(fences)
boolean changed = false
List adopt = []
fences.each { Object key, Object mo ->
Map mark = mo
if (mark.exec != null && mark.exec == ctx.execId) return
Map intent = (byKey.get(key as String) ?: ledger.get(key))
if (intent != null && intent.state == CONF_REQUESTED) {
intent.state = CONF_UNKNOWN
intent.unknownReason = 'sendAttempt'
intent.fencedAt = mark.at
rtNote(ctx, 'fenceFound', [intent: intent.id, stepKey: key, run: intent.run])
return
}
if (intent == null && mark.intent instanceof Map) { adopt << [key: key, mark: mark]; return }
next.remove(key)
changed = true
}
if (changed) { rtCall(plat, 'atomicPut', [RT_FENCE_KEY, next]); ctx.fences = next }
adopt.each { Object ao ->
Map mark = ((Map) ao).mark
Map health = ((Map) ctx.rt).health
health.fenceAdopted = ((health.fenceAdopted ?: 0) as int) + 1
health.lastFenceAdopted = [at: rtNow(ctx), stepKey: ((Map) ao).key, exec: mark.exec]
rtNote(ctx, 'fenceAdopted', [stepKey: ((Map) ao).key, run: mark.run, exec: mark.exec, fencedAt: mark.at])
coreAdoptFence(w, mark.intent, mark.at)
}
}
Map rtFencesOf(Map ctx) {
if (!(ctx.fences instanceof Map)) ctx.fences = rtFences((Map) ctx.plat)
return ctx.fences
}
Object rtSendNow(Map ctx, Map effect) {
Map plat = ctx.plat
if (rtCall(plat, 'sendNow', null) != true) return 'defer'
if (plat.get('atomicPut') == null || plat.get('atomicGet') == null) return 'defer'
String key = effect.stepKey as String
boolean earlier = rtFencesOf(ctx).any { Object k, Object m ->
k != key && m instanceof Map && ((Map) m).run == effect.run && ((Map) m).exec != ctx.execId
}
if (earlier) { rtNote(ctx, 'sendDeferred', [run: effect.run, stepKey: key, why: 'earlierFence']); return 'defer' }
return rtSend(ctx, effect)
}
Map rtSend(Map ctx, Map effect) {
Map rt = ctx.rt
Map plat = ctx.plat
Map intent = ((Map) ((Map) ctx.w).ledger).get(effect.intent)
String dev = effect.device as String
long now = rtNow(ctx)
String key = (effect.stepKey ?: effect.intent) as String
if (rtFencesOf(ctx).containsKey(key)) {
return [code: 'DISPATCH_INTERRUPTED', outcome: CONF_UNKNOWN, reason: 'sendAttempt']
}
if ((intent.restartEpoch as int) != (rt.restartEpoch as int)) {
return [code: 'DISPATCH_INTERRUPTED', outcome: CONF_UNKNOWN, reason: 'the hub restarted before the send']
}
if (now - (intent.requestedAt as long) > RT_GRACE_MS) {
long late = now - (intent.requestedAt as long)
Map health = rt.health
health.lateSendRefused = ((health.lateSendRefused ?: 0) as int) + 1
health.lastLateSendRefused = [at: now, lateMs: late, run: effect.run, stepKey: key, device: dev, command: effect.command]
rtNote(ctx, 'lateSendRefused', [run: effect.run, intent: effect.intent, stepKey: key, device: dev, command: effect.command, lateMs: late])
return [code: 'DISPATCH_INTERRUPTED', outcome: CONF_UNKNOWN,
reason: "the continuation ran ${late} ms after the request".toString()]
}
if (!rtMayUse(ctx, dev)) {
rtNote(ctx, 'notAuthorised', [intent: effect.intent, run: effect.run, device: dev])
return [code: 'DISPATCH_NOT_AUTHORISED', outcome: CONF_NOT_SENT, reason: "${dev} is not on the runtime allow-list".toString()]
}
rtHook(ctx, 'beforeFence')
Map fences = new LinkedHashMap(rtFencesOf(ctx))
fences.put(key, [at: now, exec: ctx.execId, run: effect.run, intent: intent == null ? null : new LinkedHashMap(intent)])
rtCall(plat, 'atomicPut', [RT_FENCE_KEY, fences])
ctx.fences = fences
rtHook(ctx, 'afterFence')
Map pre = (ctx.preSend ?: (ctx.preSend = [:]))
((List) (intent?.targets ?: [])).each { Object to ->
String a = ((Map) to).attribute as String
Map d = (pre.get(dev) ?: [:])
if (a != null && !d.containsKey(a)) { d.put(a, rtHubCell(ctx, dev, a)); pre.put(dev, d) }
}
try {
rtCall(plat, 'command', [dev, effect.command, effect.args ?: []])
} catch (Exception e) {
return [code: 'DISPATCH_THREW', outcome: CONF_UNKNOWN, reason: "the driver call failed: ${e.message}".toString()]
}
rtHook(ctx, 'afterDriver')
return null
}
Map rtColourArg(List args, Object levelNow) {
List a = args ?: []
return [hue: a ? a[0] : null, saturation: a.size() > 1 ? a[1] : null,
level: (a.size() > 2 && a[2] != null) ? a[2] : levelNow]
}
void rtDriverCommand(Object d, String cmd, List args) {
List a = (args ?: []).findAll { it != null }
if (cmd == 'toggle') { cmd = d.currentValue('switch') == 'on' ? 'off' : 'on'; a = [] }
else if (cmd == 'alarmOff') cmd = 'off'
else if (cmd == 'setColor') { d.setColor(rtColourArg(a, d.currentValue('level'))); return }
if (!d.hasCommand(cmd)) throw new Exception("${cmd} is not a command offered by ${d.displayName}")
d."${cmd}"(*a)
}
Object rtOnEvents(Map plat, List events) {
return rtExecute(plat) { Map ctx ->
events.each { Object e -> rtAdmitOne(ctx, e) }
return null
}
}
void rtAdmitOne(Map ctx, Map event) {
Map rt = ctx.rt
Map rec = rtRule(ctx)
if (rec == null) { rtCount(ctx, 'noActiveRule'); return }
if (rt.blocked) { rtReject(ctx, [what: 'STATE_BUDGET', device: event.device]); return }
Map w = ctx.w
int live = ((Map) w.runs).values().count { !(((Map) it).status in RT_TERMINAL_RUN) && ((Map) it).later == null } as int
if (live + ((List) rec.queue).size() >= RT_NONTERMINAL_RUNS) {
rtReject(ctx, [what: 'nonTerminalLimit', limit: RT_NONTERMINAL_RUNS, device: event.device])
return
}
if (((rt.working ?: 0) as int) + RT_MIN_RUN_BYTES > RT_WORKING_BYTES &&
((Map) rtBudgetOf(ctx)).working + RT_MIN_RUN_BYTES > RT_WORKING_BYTES) {
rtTrimTrace(rt, RT_STATE_BYTES - RT_MIN_RUN_BYTES, ctx)
if (((Map) rtBudgetOf(ctx)).working + RT_MIN_RUN_BYTES > RT_WORKING_BYTES) {
rtReject(ctx, [what: 'STATE_BUDGET', device: event.device])
return
}
}
coreInject(w, event)
}
Map rtStage(Map plat, Map doc, String ruleHash, String challengeId, String claimId, int baseEpoch, long expiresAt,
Object startStopped = false) {
String arrive = startStopped instanceof String && startStopped in ['running', 'paused', 'stopped'] ? (String) startStopped : null
return rtExecute(plat) { Map ctx ->
Map rt = ctx.rt
String h = null
try { h = jsonHash(doc, SCHEMA_DEFAULTS) } catch (Exception e) { h = null }
if (h != ruleHash) return [ok: false, code: 'STAGE_HASH_MISMATCH']
if (rt.ruleId != null && rt.ruleId != doc.id) return [ok: false, code: 'STAGE_WRONG_RULE']
if (rtEpoch(ctx) != baseEpoch) return [ok: false, code: 'STAGE_EPOCH_MOVED', epoch: rtEpoch(ctx)]
Map old = rt.staged
if (old != null && old.claimId != claimId) rtDiscardStaged(ctx, 'replaced by a newer staging')
rt.ruleId = doc.id
rt.staged = [doc: doc, ruleHash: ruleHash, challengeId: challengeId, claimId: claimId,
baseEpoch: baseEpoch, expiresAt: expiresAt, stagedAt: rtNow(ctx), startStopped: startStopped == true, arrive: arrive,
commit: [handler: 'haiCommit', armedAt: rtNow(ctx), dueAt: rtNow(ctx) + RT_CONTINUE_MS,
delayMs: RT_COMMIT_DELAY_MS, observedAt: null, attempts: 0]]
rtNote(ctx, 'staged', [claim: claimId, baseEpoch: baseEpoch])
rtCall(plat, 'timerIn', [RT_COMMIT_DELAY_MS, 'haiCommit', [claimId: claimId]])
return [ok: true, epoch: baseEpoch]
}
}
void rtDiscardStaged(Map ctx, String reason) {
Map rt = ctx.rt
Map s = rt.staged
if (s == null) return
Map cm = s.commit
boolean ran = cm != null && cm.observedAt != null
rtNote(ctx, 'stagedDiscarded', [claim: s.claimId, reason: reason, commitObserved: ran])
rt.discarded = [claimId: s.claimId, challengeId: s.challengeId, reason: reason, at: rtNow(ctx),
commit: cm == null ? null : new LinkedHashMap(cm),
code: ran ? null : 'COMMIT_CALLBACK_NOT_OBSERVED']
rt.staged = null
Map rec = rtRule(ctx)
if (rec != null && !rec.disabled) rtSubscribe(ctx, rec.doc)
else if (rec == null) rtUnsubscribe(ctx)
}
Map rtCommitDirect(Map plat, String claimId) {
return rtCommitExecute(plat, claimId)
}
Map rtCommit(Map plat, String claimId) {
Map out = rtCommitExecute(plat, claimId)
Map notice = out.remove('notice')
if (notice != null) {
try { rtCall(plat, 'announceCommitted', notice) } catch (Exception e) { rtCall(plat, 'log', [level: 'warn', text: "HAI rule ${notice.ruleId}: the commit notice did not send (${e.message})".toString()]) }
}
return out
}
Object rtCommitExecute(Map plat, String claimId) {
return rtExecute(plat) { Map ctx ->
Map rt = ctx.rt
Map s = rt.staged
if (s instanceof Map && ((Map) s).commit instanceof Map) {
Map c = ((Map) s).commit
c.observedAt = rtNow(ctx)
c.attempts = ((c.attempts ?: 0) as int) + 1
c.lateBy = rtNow(ctx) - ((c.dueAt ?: rtNow(ctx)) as long)
}
if (s == null || s.claimId != claimId) { rtNote(ctx, 'commitStale', [claim: claimId]); return [ok: false, code: 'NOTHING_STAGED'] }
if ((s.baseEpoch as int) != rtEpoch(ctx)) { rtDiscardStaged(ctx, 'the epoch moved'); return [ok: false, code: 'EPOCH_MOVED'] }
if (rtNow(ctx) >= (s.expiresAt as long)) { rtDiscardStaged(ctx, 'the claim expired'); return [ok: false, code: 'CLAIM_EXPIRED'] }
Map doc = s.doc
String h = null
try { h = jsonHash(doc, SCHEMA_DEFAULTS) } catch (Exception e) { h = null }
if (h != s.ruleHash) { rtDiscardStaged(ctx, 'the staged document changed'); return [ok: false, code: 'STAGE_HASH_MISMATCH'] }
String outside = rtDocDevices(doc).find { !rtMayUse(ctx, it) }
if (outside != null) {
rtDiscardStaged(ctx, "device ${outside} left the allow-list".toString())
return [ok: false, code: 'RUNTIME_DEVICE_NOT_AUTHORISED']
}
rtHook(ctx, 'beforePublish')
Map w = ctx.w
rt.docs.put((rtEpoch(ctx) + 1).toString(), doc)
Map v = rtRule(ctx) == null ? coreLoad(w, doc) : coreActivate(w, doc)
if (!vActivatable(v)) {
rt.docs.remove((rtEpoch(ctx) + 1).toString())
rtDiscardStaged(ctx, "invalid at commit: ${vBlocking(v).collect { it.code }}".toString())
return [ok: false, code: 'INVALID_AT_COMMIT']
}
int epoch = rtEpoch(ctx)
rt.activeHash = s.ruleHash
rt.staged = null
if (epoch == 1 && rt.committed == null && rt.firstCommittedAt == null) rt.firstCommittedAt = rtNow(ctx)
rt.committed = [claimId: s.claimId, challengeId: s.challengeId, epoch: epoch, ruleHash: s.ruleHash,
at: rtNow(ctx)]
rtCancelSupersededSchedules(ctx, epoch)
rtRebuildTimerJobs(ctx)
if (s.startStopped == true && epoch == 1 && rtRule(ctx)?.disabled != true) {
coreDisable((Map) ctx.w, rt.ruleId as String)
rtNote(ctx, 'committedStopped', [claim: s.claimId])
}
if (s.arrive != null) rtArrive(ctx, s.arrive as String, s.claimId)
rtNote(ctx, 'committed', [claim: s.claimId, epoch: epoch])
rtHook(ctx, 'inCommit')
return [ok: true, epoch: epoch,
notice: [ruleId: rt.ruleId, claimId: s.claimId, challengeId: s.challengeId, epoch: epoch,
ruleHash: s.ruleHash, at: ((Map) rt.committed).at, disabled: rtRule(ctx)?.disabled == true]]
}
}
void rtArrive(Map ctx, String arrive, Object claimId) {
Map rec = rtRule(ctx)
if (rec == null) return
Map w = ctx.w
String id = rec.id as String
if (arrive == 'stopped') {
if (rec.disabled != true) { coreDisable(w, id); rtDropEndedJobs(ctx) }
} else if (arrive == 'paused') {
if (rec.paused != true) corePause(w, id)
if (rec.disabled == true) coreEnable(w, id)
} else if (arrive == 'running') {
if (rec.paused == true) coreResume(w, id)
if (rec.disabled == true) coreEnable(w, id)
}
rtNote(ctx, 'committedAs', [claim: claimId, lifecycle: arrive])
}
Map rtHeldCheck(Map ctx, Map doc) {
Map devs = (rtInventory(ctx)?.devices ?: [:])
String gone = rtDocDevices(doc).find { !devs.containsKey(it) }
List found = gone == null ? [] : [[code: 'REF_MISSING', severity: SEV_BLOCK, path: 'refs', message: "device ${gone} is no longer on the hub".toString()]]
return [findings: found, complete: true]
}
List rtDocDevices(Map doc) {
return ((Map) (doc.refs ?: [:])).values().findAll { it instanceof Map && ((Map) it).kind == 'device' }.collect { ((Map) it).id as String }
}
boolean rtMayUse(Map ctx, String dev) {
Map plat = ctx.plat
Closure custom = (Closure) plat.allowed
return custom != null ? (custom(dev) as boolean) : rtChildAllowed(((Map) ctx.rt).allow, dev)
}
Map rtResumeStaged(Map plat) {
return rtExecute(plat) { Map ctx ->
Map s = ((Map) ctx.rt).staged
if (s == null) return [ok: false, code: 'NOTHING_STAGED']
Map c = s.commit
if (c != null) { c.armedAt = rtNow(ctx); c.dueAt = rtNow(ctx) + RT_COMMIT_DELAY_MS }
rtCall(plat, 'timerIn', [RT_COMMIT_DELAY_MS, 'haiCommit', [claimId: s.claimId]])
return [ok: true, claimId: s.claimId]
}
}
Map rtDiscard(Map plat, String reason) {
return rtExecute(plat) { Map ctx ->
rtDiscardStaged(ctx, reason)
return [ok: true]
}
}
String rtRunNotSaid(String why) {
switch (why) {
case 'paused': return 'The rule is paused, so Run now did not run it. Resume it first.'
case 'disabled': return 'The rule is stopped, so Run now did not run it. Activate it first.'
case 'ignored': return 'The rule is already running and is set to ignore a new run while it does.'
case 'queueOverflow': return 'The rule is already running and its queue is full.'
case 'parallelLimit': return 'The rule is already running as many times at once as it allows.'
case 'noRule': return 'This rule app holds no rule.'
default: return "Run now did not run the rule: ${why}.".toString()
}
}
void rtDropEndedJobs(Map ctx) {
rtCompact(ctx)
long now = rtNow(ctx)
if (!((Map) ((Map) ctx.rt).deadlines).values().any { Map r -> r.firedAt == null && r.kind != 'dispatch' && (r.dueAt as long) <= now }) rtRebuildTimerJobs(ctx)
}
Map rtLifecycle(Map plat, String op, String arg) {
return rtExecute(plat) { Map ctx ->
Map rec = rtRule(ctx)
if (rec == null) return [ok: false, code: 'NO_ACTIVE_RULE']
Map w = ctx.w
String id = rec.id as String
switch (op) {
case 'pause': corePause(w, id); break
case 'resume': coreResume(w, id); break
case 'disable': coreDisable(w, id); rtDropEndedJobs(ctx); break
case 'enable': coreEnable(w, id); break
case 'stopRun': coreStopRun(w, arg); break
case 'runNow':
String why = coreRunNow(w, id, 'a person')
if (why != null) return [ok: false, code: 'NOT_AVAILABLE', message: rtRunNotSaid(why),
paused: rec.paused, disabled: rec.disabled]
break
case 'cancelRuns': coreCancelRuns(w, id); rtDropEndedJobs(ctx); break
case 'setBoolean':
if (!(arg in ['true', 'false'])) return [ok: false, code: 'NOT_A_BOOLEAN']
coreSetRuleBoolean(w, id, arg == 'true', 'an end point', 1); break
case 'endpoint': coreEndpoint(w, id, arg); break
default: return [ok: false, code: 'UNKNOWN_OPERATION']
}
return [ok: true, paused: rec.paused, disabled: rec.disabled]
}
}
boolean rtSetupSame(Map rt, List allow, Map purposes, List writableVariables, List knownRules,
List pausedRules, Boolean modeAllowed, Map switched = null) {
if (pausedRules != null && ((List) (rt.subs ?: [])).any { ((List) it)[0].toString().startsWith(RULE_STATE_PREFIX) }) return false
if (((allow ?: []).collect { it.toString() }) != (rt.allow ?: [])) return false
if (writableVariables != null && (writableVariables.collect { it.toString() }) != (rt.writableVariables ?: [])) return false
if (knownRules != null && (knownRules.collect { it.toString() }) != (rt.knownRules ?: [])) return false
if (modeAllowed != null && modeAllowed != rt.modeAllowed) return false
if (switched != null && rtSwitchedOwn(switched) != (rt.rmSwitched ?: [:])) return false
if (pausedRules != null && (pausedRules.collect { it.toString() }) != (rt.pausedRules ?: [])) return false
if (purposes != null) {
Map p = [:]
purposes.each { Object k, Object v -> p.put(rtBareId(k), v) }
if (p != (rt.purposes ?: [:])) return false
}
return true
}
Map rtSetAllow(Map plat, List allow, Map purposes, List writableVariables = null, List knownRules = null,
List pausedRules = null, Boolean modeAllowed = null, Map switched = null) {
Object held = rtCall(plat, 'loadState', null)
if (held instanceof Map && rtSetupSame((Map) held, allow, purposes, writableVariables, knownRules, pausedRules, modeAllowed, switched)) {
return [ok: true, unchanged: true]
}
return rtExecute(plat) { Map ctx ->
Map rt = ctx.rt
if (pausedRules != null) { rt.pausedRules = pausedRules.collect { it.toString() }; rt.pausedRulesAt = rtNow(ctx) }
rt.allow = (allow ?: []).collect { it.toString() }
rt.remove('allowHash')
if (writableVariables != null) rt.writableVariables = writableVariables.collect { it.toString() }
if (knownRules != null) rt.knownRules = knownRules.collect { it.toString() }
if (modeAllowed != null) rt.modeAllowed = modeAllowed
if (switched != null) { Map own = rtSwitchedOwn(switched); if (own.isEmpty()) rt.remove('rmSwitched') else rt.rmSwitched = own }
if (purposes != null) {
Map p = [:]
purposes.each { Object k, Object v -> p.put(rtBareId(k), v) }
rt.purposes = p
}
return [ok: true]
}
}
String rtNetRefusal(Map req) {
String host = req.kind == 'http' ? schemaUrlHost(req.url) : (req.host as String)?.toLowerCase()
if (host == null) return 'that is not an address'
if (req.kind == 'http' && schemaHubMaintenance(req.url) && req.approvedMaintenance != true) {
return "that is one of the hub's power or maintenance pages, and this step's own address does not name it, so no person approved it"
}
if (req.kind == 'http' && schemaHubSelfHost(host) && req.namedHubSelf != true) {
return "that address is this hub itself, and this step's own address does not name the hub, so no person wrote it"
}
return null
}
Object rtOnNetReply(Map plat, Object runId, Object token, Map reply) {
return rtExecute(plat) { Map ctx ->
if (rtRule(ctx) != null) coreNetReply((Map) ctx.w, runId as String, token as String, reply)
return null
}
}
Map rtDevWithhold(Map plat, int n) {
return rtExecute(plat) { Map ctx ->
((Map) ctx.rt).devWithhold = Math.max(0, Math.min(n, 10))
return [ok: true, withhold: ((Map) ctx.rt).devWithhold]
}
}
Map rtReconcile(Map plat) {
return rtExecute(plat) { Map ctx -> rtSummary(ctx) }
}
Map rtState(Map plat) {
return rtExecute(plat) { Map ctx -> rtSummary(ctx) }
}
Map rtRecentView(Map plat) {
Object loaded = rtCall(plat, 'loadState', null)
if (!(loaded instanceof Map)) return null
Map rt = loaded
if (rt.ruleId == null) return null
Map wenc = (rt.world ?: [:])
Map rec = ((Map) (wenc.rules ?: [:])).get(rt.ruleId)
Map docs = (rt.docs ?: [:])
Object epoch = rec?.epoch
return [ruleId: rt.ruleId, epoch: epoch, ruleHash: rt.activeHash, mark: rt.lightMark,
paused: rec?.paused ?: false, disabled: rec?.disabled ?: false,
doc: epoch == null ? null : docs.get(epoch.toString()), docs: docs,
trace: rt.trace, ledger: wenc.ledger]
}
Map rtSummary(Map ctx) {
Map rt = ctx.rt
Map rec = rtRule(ctx)
Map s = rt.staged
Map w = ctx.w
return [ruleId: rt.ruleId, epoch: rtEpoch(ctx), ruleHash: rt.activeHash, doc: rec?.doc,
paused: rec?.paused ?: false, disabled: rec?.disabled ?: false,
staged: s == null ? null : [claimId: s.claimId, challengeId: s.challengeId, baseEpoch: s.baseEpoch,
expiresAt: s.expiresAt, stagedAt: s.stagedAt, commit: s.commit],
committed: rt.committed, discarded: rt.discarded, blocked: rt.blocked, bytes: rt.bytes,
restartEpoch: rt.restartEpoch, health: new LinkedHashMap((Map) rt.health),
liveRuns: ((Map) w.runs).values().findAll { !(((Map) it).status in RT_TERMINAL_RUN) }.collect { [id: it.id, status: it.status] },
lateSamples: new ArrayList((List) rt.lateSamples), subs: rt.subs,
trace: rt.trace, ledger: w.ledger]
}
void rtNote(Map ctx, String what, Map detail) {
Map e = [at: rtNow(ctx), what: what]
e.putAll(detail)
rtTrace(ctx, e)
}
Map rtShrink(Map entry) {
Map out = [t: entry.at, w: entry.what]
entry.each { Object k, Object v ->
if (k in ['at', 'what', 'rule', 'run']) return
if (k == 'trace' && v instanceof List) {
out.c = ((List) v).collect { Object n ->
Map m = n
String code = [(LOGIC_TRUE): 'T', (LOGIC_FALSE): 'F', (LOGIC_UNKNOWN): 'U'].get(m.value) ?: 'N'
"${m.path}=${code}".toString()
}
return
}
out.put(k, v)
}
return out
}
Map rtRunTrace(Map rt, String runId) {
List runs = ((Map) rt.trace).runs
Map found = runs.find { ((Map) it).run == runId }
if (found != null) return found
Map t = [run: runId, head: [], tail: [], dropped: 0]
runs << t
while (runs.size() > RT_TRACE_RUNS) {
rtLoseCoverage((Map) rt.trace, runs.remove(0))
((Map) rt.trace).droppedRuns = (((Map) rt.trace).droppedRuns as int) + 1
}
return t
}
void rtAppend(Map t, Map entry) {
Map e = rtCapEntry(entry)
List head = t.head
List tail = t.tail
if (head.size() < RT_TRACE_HEAD) { head << e; return }
tail << e
if (tail.size() > RT_TRACE_TAIL) {
tail.remove(0)
t.dropped = (t.dropped as int) + 1
}
}
Map rtCapEntry(Map e) {
int size = rtBytes(e)
if (size <= RT_TRACE_ENTRY_BYTES) return e
Map out = [t: e.t, w: e.w, cut: size]
int used = rtBytes(out)
e.each { Object k, Object v ->
if (k in ['t', 'w', 'cut'] || v instanceof Map) return
int keyBytes = rtBytes(k as String) + 2
Object x = v
if (x instanceof String && ((String) x).length() > RT_TRACE_TEXT_CHARS) x = ((String) x).substring(0, RT_TRACE_TEXT_CHARS) + '...'
if (x instanceof Collection) {
if (used + keyBytes + 2 > RT_TRACE_ENTRY_BYTES) return
List kept = []
int listBytes = 2
for (Object item : (Collection) x) {
int b = rtBytes(item) + (kept.isEmpty() ? 0 : 1)
if (used + keyBytes + listBytes + b > RT_TRACE_ENTRY_BYTES) break
kept << item
listBytes += b
}
out.put(k, kept)
used += keyBytes + listBytes
return
}
int b = rtBytes(x)
if (used + keyBytes + b > RT_TRACE_ENTRY_BYTES) return
out.put(k, x)
used += keyBytes + b
}
return out
}
void rtLoseCoverage(Map trace, Object evicted) {
List stamps = []
if (evicted instanceof Map && ((Map) evicted).containsKey('head')) stamps.addAll(rtRunEntries((Map) evicted))
else stamps << evicted
long latest = 0L
stamps.each { Object o -> if (o instanceof Map && ((Map) o).t instanceof Number) latest = Math.max(latest, ((Map) o).t as long) }
if (latest > 0L && (trace.coveredFrom == null || latest > (trace.coveredFrom as long))) trace.coveredFrom = latest
}
void rtCoverageStart(Map ctx) {
Map trace = ((Map) ctx.rt).trace
if (trace.coveredFrom == null) trace.coveredFrom = rtNow(ctx)
if (trace.unmatched == null) trace.unmatched = [:]
}
List rtRunEntries(Map t) {
List out = new ArrayList((List) t.head)
if ((t.dropped as int) > 0) out << [w: 'truncated', dropped: t.dropped]
out.addAll((List) t.tail)
return out
}
@Field static final Map RT_LOG_OPTION = [accepted: 'logEvents', runStarted: 'logTriggers', runEnded: 'logTriggers', runCancelled: 'logTriggers',
dispatched: 'logActions', variableWritten: 'logActions', modeSet: 'logActions', invoked: 'logActions',
ruleControlled: 'logActions', ruleBooleanSet: 'logActions', rmRuleAction: 'logActions', rmRuleRetargeted: 'logActions',
netSent: 'logActions', fileWritten: 'logActions', waiting: 'logActions', exited: 'logActions',
returned: 'logActions', captured: 'logActions', restored: 'logActions', runError: 'logActions',
decisionUnknownSkipped: 'logActions', decisionElseOnUnknown: 'logActions']
void rtLogOption(Map ctx, Map e) {
String opt = RT_LOG_OPTION.get(e.what) as String
if (opt == null || e.kind == 'button') return
Map doc = rtRule(ctx)?.doc
if (doc == null || doc[opt] != true) return
Closure nm = { Object id ->
Map ref = ((Map) (doc.refs ?: [:])).values().find { it instanceof Map && ((Map) it).id == id }
(ref?.name ?: id) as String
}
String text
switch (e.what) {
case 'accepted': text = e.kind == 'mode' ? "event: mode ${e.value}" : "event: ${nm(e.device)} ${e.attribute} ${e.value}"; break
case 'runStarted': text = "triggered by ${e.cause}: ${e.run} started"; break
case 'runEnded': text = "${e.run} ended (${e.how})"; break
case 'runCancelled': text = "${e.run} cancelled (${e.why})"; break
case 'dispatched': text = "action: ${nm(e.device)} ${e.command}"; break
case 'decisionUnknownSkipped': text = "a reading in the IF at ${e.path} could not be read, so that branch was treated as not met"; break
case 'decisionElseOnUnknown': text = "a reading in the IF at ${e.path} could not be read, so the ELSE ran"; break
default:
List bits = ['variable', 'value', 'mode', 'target', 'rule', 'op', 'action', 'to', 'file', 'kind', 'code', 'message']
.findAll { e[it] != null }.collect { "${it} ${it == 'variable' ? nm(e[it]) : e[it]}" }
text = "action: ${e.what}${bits ? ' ' + bits.join(', ') : ''}"
}
rtCall((Map) ctx.plat, 'log', [level: 'info', text: "HAI ${doc.name ?: doc.id}: ${text}".toString()])
}
void rtTrace(Map ctx, Map entry) {
Closure sink = (Closure) ((Map) ctx.plat).traceSink
if (sink != null) sink(entry)
rtLogOption(ctx, entry)
rtCoverageStart(ctx)
Map rt = ctx.rt
if (entry.what == 'runStarted') {
if (rt.totalRuns == null) {
rt.totalRuns = 0L
rt.runsObservedSince = rtNow(ctx)
}
rt.totalRuns = ((rt.totalRuns as long) + 1L)
}
Map e = rtShrink(entry)
String run = entry.run as String
if (run == null && entry.intent != null) run = ((Map) ((Map) ((Map) ctx.w).ledger).get(entry.intent))?.run as String
if (run == null) { ((List) ctx.loose) << e; return }
Map t = rtRunTrace(rt, run)
if (entry.what == 'runStarted') {
((List) ctx.loose).each { rtAppend(t, it) }
((List) ctx.loose).clear()
}
rtAppend(t, e)
}
void rtFlushLoose(Map ctx) {
List loose = ctx.loose
if (loose.isEmpty()) return
rtCoverageStart(ctx)
List decisions = loose.findAll { ((Map) it).w in ['gate', 'ignored', 'queued', 'queueOverflow', 'parallelLimit', 'notAdmitted', 'duplicate'] }
decisions.each { Object o ->
Map d = o
if (d.w == 'gate' && d.value == LOGIC_TRUE) return
String what = d.w == 'gate' ? (d.value == LOGIC_UNKNOWN ? 'gateUnknown' : 'gateFalse') : (d.w as String)
Map rec = [what: what] + d.findAll { k, v -> !(k in ['w', 't', 'c']) }
if (d.c instanceof List) rec.c = ((List) d.c).take(RT_GATE_TRACE)
rtReject(ctx, rec)
}
List accepted = loose.findAll { ((Map) it).w == 'accepted' }
accepted.each { rtCount(ctx, 'accepted') }
if (decisions.isEmpty()) {
Map un = ((Map) ((Map) ctx.rt).trace).unmatched
accepted.each { Object o ->
Map a = o
String key = a.kind == 'mode' ? 'mode' : "${a.device}/${a.attribute}".toString()
Map row = un.get(key)
un.put(key, [value: a.value, t: a.t, n: ((row?.n ?: 0) as int) + 1, since: row?.since ?: a.t])
}
}
List notes = ((Map) ((Map) ctx.rt).trace).notes
loose.findAll { !(((Map) it).w in ['accepted', 'gate', 'ignored', 'queued', 'queueOverflow', 'parallelLimit', 'notAdmitted', 'duplicate']) }.each { Object o ->
notes << o
while (notes.size() > RT_REJECTIONS) rtLoseCoverage((Map) ((Map) ctx.rt).trace, notes.remove(0))
}
loose.clear()
}
void rtCount(Map ctx, String what) {
Map counters = ((Map) ((Map) ctx.rt).trace).counters
counters.put(what, ((counters.get(what) ?: 0) as int) + 1)
}
void rtReject(Map ctx, Map record) {
Map rt = ctx.rt
rtCount(ctx, record.what as String)
List ring = ((Map) rt.trace).rejections
ring << ([t: rtNow(ctx)] + record)
while (ring.size() > RT_REJECTIONS) rtLoseCoverage((Map) rt.trace, ring.remove(0))
}
void rtTrimTrace(Map rt, int limit, Map ctx) {
rtDropTrace((Map) rt.trace, (rtBudgetOf(ctx).total as int), limit, true)
}
void rtCompact(Map ctx) {
Map rt = ctx.rt
Map w = ctx.w
Map deadlines = rt.deadlines
Map runs = w.runs
new ArrayList(deadlines.values()).each { Object ro ->
Map row = ro
Map run = row.run == null ? null : runs.get(row.run)
boolean runEnded = row.run != null && (run == null || run.status in RT_TERMINAL_RUN)
if (row.firedAt == null && row.kind == 'dispatch' && runEnded) {
row.firedAt = rtNow(ctx)
coreSend(w, row.timer)
}
if (row.firedAt != null || (runEnded && row.kind != 'verify')) deadlines.remove(row.token)
}
new ArrayList(runs.keySet()).each { Object id -> if (((Map) runs.get(id)).status in RT_TERMINAL_RUN) runs.remove(id) }
rtCompactLedger(ctx)
Map ledger = w.ledger
Map latest = w.latestIntent
new ArrayList(latest.keySet()).each { Object k -> if (!ledger.containsKey(latest.get(k))) latest.remove(k) }
long now = rtNow(ctx)
Map seen = w.seenSources
new ArrayList(seen.keySet()).each { Object k ->
Object v = seen.get(k)
if (now - ((v instanceof List ? ((List) v)[1] : v) as long) > CORE_DEDUPE_WINDOW_MS) seen.remove(k)
}
List blocked = w.blocked
while (blocked.size() > RT_TERMINAL_INTENTS) blocked.remove(0)
List gaps = w.gaps
while (gaps.size() > RT_TERMINAL_INTENTS) gaps.remove(0)
((Set) w.occurrences).removeAll { Object o ->
String id = o.toString()
String tail = id.substring(id.lastIndexOf('|') + 1)
tail.isLong() && (tail as long) < now - RT_OCCURRENCE_KEEP_MS
}
Set epochs = [] as Set
Map rec = rtRule(ctx)
if (rec != null) {
epochs << rec.epoch.toString()
int live = rec.epoch as int
if (live > 1) epochs << (live - 1).toString()
}
runs.values().each { epochs << ((Map) it).epoch.toString() }
new ArrayList(((Map) rt.docs).keySet()).each { Object k -> if (!epochs.contains(k.toString())) ((Map) rt.docs).remove(k) }
Set readable = [] as Set
if (rec != null) readable << rec.epoch.toString()
runs.values().each { readable << ((Map) it).epoch.toString() }
Set declared = [] as Set
int readableDocs = 0
((Map) rt.docs).each { Object ep, Object dobj ->
if (!readable.contains(ep.toString())) return
readableDocs++
Object refs = ((Map) dobj)?.refs
if (refs instanceof Map) ((Map) refs).values().each { Object r ->
if (r instanceof Map && ((Map) r).id != null) declared << ((Map) r).id.toString()
}
}
declared << PRIVATE_BOOLEAN_ID
if (readableDocs > 0) {
[rt.evidence, rt.vars].each { Object mo ->
Map m = mo
if (m == null) return
new ArrayList(m.keySet()).each { Object k ->
String key = k.toString()
if (!key.startsWith('rv:')) return
String id = key.contains('@') ? key.substring(0, key.indexOf('@')) : key
if (!declared.contains(id)) m.remove(k)
}
}
}
}
void rtCompactLedger(Map ctx) {
Map rt = ctx.rt
Map w = ctx.w
Map fences = rtFencesOf(ctx)
Map ledger = w.ledger
Set pendingVerify = ((Map) rt.deadlines).values().findAll { ((Map) it).kind == 'verify' && ((Map) it).firedAt == null }
.collect { ((Map) it).timer.intent } as Set
Set waited = ((Map) w.runs).values().collect { ((Map) it).wait?.intent }.findAll { it != null } as Set
List eligible = ledger.values().findAll { Object io ->
Map i = io
!(i.state == CONF_REQUESTED || (i.sk != null && fences.containsKey(coreStepKey(i))) || fences.containsKey(i.id) || pendingVerify.contains(i.id) || waited.contains(i.id))
}.sort { a, b -> ((a.id as String).substring(1) as long) <=> ((b.id as String).substring(1) as long) }
if (eligible.size() <= RT_LEDGER_FULL) return
Map summary = (rt.ledgerSummary ?: [total: 0, byState: [:], lastId: null])
Map byState = (summary.byState ?: [:])
eligible.take(eligible.size() - RT_LEDGER_FULL).each { Object io ->
Map i = io
ledger.remove(i.id)
byState.put(i.state, ((byState.get(i.state) ?: 0) as int) + 1)
summary.total = ((summary.total ?: 0) as int) + 1
summary.lastId = i.id
rtNote(ctx, 'intentFolded', [intent: i.id, state: i.state, device: i.device, command: i.command, dispatchedAt: i.dispatchedAt])
}
summary.byState = byState
rt.ledgerSummary = summary
}
void rtBudget(Map ctx) {
Map rt = ctx.rt
Map w = ctx.w
Map trace = rt.trace
String key = rtBudgetKey(ctx)
int skipped = ((rt.budgetSkips ?: 0) as int)
if (key == rt.budgetKey && rt.blocked != true && skipped < RT_BUDGET_REMEASURE &&
((rt.working ?: RT_WORKING_BYTES) as int) + RT_BUDGET_NEAR_BYTES <= RT_WORKING_BYTES &&
((rt.bytes ?: RT_STATE_BYTES) as int) + RT_BUDGET_NEAR_BYTES <= RT_STATE_BYTES) {
rt.budgetSkips = skipped + 1
return
}
Map b = rtBudgetOf(ctx)
int traceBytes = b.trace as int
if (traceBytes > RT_TRACE_BYTES) {
int cut = traceBytes - rtDropTrace(trace, traceBytes, RT_TRACE_BYTES, false)
b = [total: (b.total as int) - cut, docs: b.docs, working: (b.working as int) - cut]
}
if (b.working > RT_WORKING_BYTES || b.total > RT_STATE_BYTES) {
rtTrimTrace(rt, RT_STATE_BYTES, ctx)
rtCompact(ctx)
b = rtBudgetOf(ctx)
}
if (b.working > RT_WORKING_BYTES || b.total > RT_STATE_BYTES) {
Map rec = rtRule(ctx)
if (rec != null) ((List) rec.queue).clear()
rt.blocked = true
((Map) rt.health).stateBudget = [at: rtNow(ctx), bytes: b.total, working: b.working, docs: b.docs]
rtTrimTrace(rt, 0, ctx)
b = rtBudgetOf(ctx)
if (b.working > RT_WORKING_BYTES || b.total > RT_STATE_BYTES) ((Map) rt.health).stateBudgetUnfit = b.total
else ((Map) rt.health).remove('stateBudgetUnfit')
} else if (rt.blocked && b.working + rtRunHeadroom() <= RT_WORKING_BYTES) {
rt.blocked = false
((Map) rt.health).remove('stateBudget')
((Map) rt.health).remove('stateBudgetUnfit')
}
rt.bytes = b.total
rt.working = b.working
rt.budgetKey = rtBudgetKey(ctx)
rt.budgetSkips = 0
}
@Field static final int RT_BUDGET_REMEASURE = 16
@Field static final int RT_BUDGET_NEAR_BYTES = 4096
String rtBudgetKey(Map ctx) {
Map rt = ctx.rt
Map w = ctx.w
Map t = (rt.trace ?: [:])
Closure n = { Object o -> o instanceof Map ? ((Map) o).size() : o instanceof Collection ? ((Collection) o).size() : 0 }
int entries = 0
((List) (t.runs ?: [])).each { Object r -> if (r instanceof Map) entries += (n(((Map) r).head) as int) + (n(((Map) r).tail) as int) }
List parts = [n(t.runs), entries, n(t.notes), n(t.rejections), t.droppedRuns, n(t.unmatched), n(t.counters),
n(w.ledger), n(w.runs), n(w.latestIntent), n(w.seenSources), n(w.occurrences), n(w.gaps), n(w.blocked), n(w.holds),
n(rt.deadlines), ((Map) (rt.docs ?: [:])).keySet().join(','), ((Map) rt.staged)?.claimId, ((Map) rt.discarded)?.claimId,
n(rt.evidence), n(rt.vars), n(rt.health), n(rt.subs), n(rt.allow), n(rt.lateSamples), rt.activeHash]
return Integer.toHexString(parts.join('|').hashCode())
}
