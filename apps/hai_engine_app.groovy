import groovy.transform.Field
import hubitat.helper.RMUtils
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.Instant
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

@Field static final String HAI_CHANNEL = 'preview'
definition(
name: 'HAI Engine (Preview)',
namespace: 'gordonthelander',
author: 'HAI',
description: 'Writes, checks, simulates and runs automation rules. One app; each rule gets its own child.',
category: 'Automations',
menu: 'Automations',
iconUrl: '',
iconX2Url: '',
installOnOpen: true,
singleInstance: true,
singleThreaded: true,
oauth: true
)
preferences {
page(name: 'haiMainPage')
}
mappings {
path('/automation-map') { action: [GET: 'automationMapGet'] }
path('/mcp') { action: [POST: 'mcpPost', GET: 'mcpGet'] }
path('/editor') { action: [GET: 'editorPageGet'] }
path('/editor/api/state') { action: [GET: 'editorStateGet'] }
path('/editor/api/fixed') { action: [GET: 'editorFixedGet'] }
path('/editor/api/device') { action: [GET: 'editorDeviceGet'] }
path('/editor/api/check') { action: [POST: 'editorCheckPost'] }
path('/editor/api/rule') { action: [GET: 'editorRuleGet'] }
path('/editor/api/save') { action: [POST: 'editorSavePost'] }
path('/editor/api/delete') { action: [POST: 'editorDeletePost'] }
path('/editor/api/delete-many') { action: [POST: 'editorDeleteManyPost', GET: 'editorDeleteManyGet'] }
path('/editor/api/delete-many/stop') { action: [POST: 'editorDeleteManyStop'] }
path('/editor/api/runtime') { action: [GET: 'editorRuntimeGet'] }
path('/editor/api/active') { action: [GET: 'editorActiveGet'] }
path('/editor/api/uses') { action: [GET: 'editorUsesGet'] }
path('/editor/api/backups') { action: [GET: 'editorBackupsGet'] }
path('/editor/api/backup') { action: [GET: 'editorBackupGet', POST: 'editorBackupPost'] }
path('/editor/api/send') { action: [POST: 'editorSendPost'] }
path('/editor/api/run-step') { action: [POST: 'editorRunStepPost'] }
path('/editor/api/export') { action: [POST: 'editorExportPost'] }
path('/editor/api/import') { action: [POST: 'editorImportPost'] }
path('/editor/api/draft') { action: [POST: 'editorDraftPost', GET: 'editorDraftGet'] }
path('/endpoint/rules') { action: [GET: 'endpointRulesGet'] }
path('/endpoint/rules/:id/:op') { action: [GET: 'endpointRuleGet', POST: 'endpointRulePost'] }
path('/endpoint/rules/:id/:op/:value') { action: [GET: 'endpointRuleGet', POST: 'endpointRulePost'] }
path('/endpoint/variables/:name/:value') { action: [GET: 'endpointVariableGet', POST: 'endpointVariablePost'] }
}
@Field static final String HAI_ENVIRONMENT = 'PREVIEW'
@Field static final String HAI_COMMUNITY_URL = ''
@Field static final String AM_REPO_URL = 'https://github.com/GordonThelander/hubitat-automation-map'
@Field static final String HAI_DOCS_URL = 'https://github.com/GordonThelander/HAI-Engine/blob/preview/docs'
@Field static final String HAI_ISSUES_URL = 'https://github.com/GordonThelander/HAI-Engine/issues/new/choose'
@Field static final String CLAUDE_KEYS_URL = 'https://platform.claude.com/settings/keys'
@Field static final Map HAI_ROUTE_AUDIENCE = [
'/automation-map'                  : 'feed',
'/mcp'                             : 'mcp',
'/editor'                          : 'editor',
'/editor/api/state'                : 'editor',
'/editor/api/fixed'                : 'editor',
'/editor/api/device'               : 'editor',
'/editor/api/check'                : 'editor',
'/editor/api/rule'                 : 'editor',
'/editor/api/save'                 : 'editor',
'/editor/api/delete'               : 'editor',
'/editor/api/delete-many'          : 'editor',
'/editor/api/delete-many/stop'     : 'editor',
'/editor/api/runtime'              : 'editor',
'/editor/api/active'               : 'editor',
'/editor/api/uses'                 : 'editor',
'/editor/api/backups'              : 'editor',
'/editor/api/backup'               : 'editor',
'/editor/api/send'                 : 'editor',
'/editor/api/run-step'             : 'editor',
'/editor/api/export'               : 'editor',
'/editor/api/import'               : 'editor',
'/editor/api/draft'                : 'editor',
'/endpoint/rules'                  : 'endpoint',
'/endpoint/rules/:id/:op'          : 'endpoint',
'/endpoint/rules/:id/:op/:value'   : 'endpoint',
'/endpoint/variables/:name/:value' : 'endpoint'
]
def installed() { haiApproveAllOnce(); haiInit() }
def updated() { engineKeySweep(); haiWeatherApprove(); haiNoteDeclined(); haiLogsSchedule(); haiInit(); haiEverydayFollow(); if (state.haiApproveAll instanceof Map && state.haiApproveAll.seen != true) { Map m = new LinkedHashMap((Map) state.haiApproveAll); m.seen = true; state.haiApproveAll = m } }
void haiApproveAllOnce() {
if (state.haiApproveAll != null) return
if (((List) (settings.runtimeAllowDevices ?: [])).size() > 0) { state.haiApproveAll = [skipped: true, at: now()]; return }
List ids = []
String err = null
try {
httpGet([uri: 'http://127.0.0.1:8080/hub2/devicesList', timeout: 30]) { resp -> ids = haiDeviceListIds(resp.data) }
} catch (Exception e) { err = (e.message ?: 'no answer') as String }
if (!ids && err == null) err = 'the hub listed no devices'
if (ids) app.updateSetting('runtimeAllowDevices', [type: 'capability', value: ids])
state.haiApproveAll = [count: ids.size(), error: err, at: now()]
if (ids) haiInfo "HAI Engine: new install, so all ${ids.size()} devices on the hub are approved; untick any under Approved devices"
else log.warn "HAI Engine: new install, but the hub's device list could not be read (${err}), so no device is approved yet"
}
List haiOtherEngines() {
List out = []
try {
httpGet([uri: 'http://127.0.0.1:8080/hub2/appsList', timeout: 15]) { resp ->
List pending = new ArrayList(resp.data instanceof Map && ((Map) resp.data).apps instanceof List ? ((Map) resp.data).apps : [])
while (pending) {
Object node = pending.remove(0)
if (!(node instanceof Map)) continue
Map d = ((Map) node).data instanceof Map ? ((Map) node).data : null
String nm = "${d?.name ?: ''} ${d?.label ?: ''}".toString()
if (d?.id != null && "${d.id}".isLong() && ("${d.id}" as long) < (app.id as long) && nm.contains('HAI Engine')) out << "${d.id}".toString()
if (((Map) node).children instanceof List) pending.addAll((List) ((Map) node).children)
}
}
} catch (Exception e) { log.warn "HAI Engine: the hub's app list could not be read (${e.message})" }
return out
}
List haiDeviceListIds(Object data) {
List pending = new ArrayList((data instanceof Map && ((Map) data).devices instanceof List) ? ((Map) data).devices : [])
LinkedHashSet ids = new LinkedHashSet()
while (pending) {
Object node = pending.remove(0)
if (!(node instanceof Map)) continue
Map d = ((Map) node).data instanceof Map ? ((Map) node).data : null
if (d?.id != null && "${d.id}" ==~ /[0-9]{1,12}/) ids << "${d.id}".toString()
if (((Map) node).children instanceof List) pending.addAll((List) ((Map) node).children)
}
return new ArrayList(ids)
}
void haiWeatherApprove() {
def wx = settings.everydayWeatherDevice
if (wx == null) return
List have = ((settings.runtimeAllowDevices ?: []) as List).findAll { it != null }
if (have.any { it.id.toString() == wx.id.toString() }) return
app.updateSetting('runtimeAllowDevices', [type: 'capability', value: have.collect { it.id.toString() } + [wx.id.toString()]])
haiInfo "HAI Engine: Everyday words: ${wx.displayName} is the weather device, so it is now an approved device"
}
def haiDeviceJoined(evt) {
if (settings.haiAutoApproveNew == false) return
String id = null
try { id = evt?.deviceId != null ? evt.deviceId.toString() : (evt?.value as String)?.trim() } catch (Exception e) { id = null }
if (!(id ==~ /[0-9]{1,12}/)) { log.warn "HAI Engine: a device-joined event named no device id, so nothing was approved"; return }
List have = ((settings.runtimeAllowDevices ?: []) as List).findAll { it != null }.collect { it.id.toString() }
if (have.contains(id)) return
if (((List) (state.haiDeclined ?: [])).contains(id)) {
haiInfo "HAI Engine: device ${id} joined, but it was taken off Approved devices before, so it was not approved again"
return
}
String why = haiJoinRefusal(id)
if (why != null) { log.warn "HAI Engine: a device-joined event named device ${id}, but ${why}, so it was not approved"; return }
app.updateSetting('runtimeAllowDevices', [type: 'capability', value: have + [id]])
haiInfo "HAI Engine: device ${id} joined the hub and is now an approved device (Approve new devices automatically is on)"
}
@Field static final long HAI_JOIN_NEW_MS = 900000L
String haiJoinRefusal(String id) {
Object data = null
try {
httpGet([uri: "http://127.0.0.1:8080/device/fullJson/${id}".toString(), contentType: 'application/json', timeout: 8]) { resp ->
if (resp?.status == 200) data = resp.data
}
} catch (Exception e) { return "the hub's record of it could not be read (${e.message})".toString() }
Map d = data instanceof Map ? (((Map) data).device instanceof Map ? ((Map) data).device : data) : null
if (d == null || d.id == null || d.id.toString() != id) return 'the hub has no such device'
Long made = null
for (String k : ['createTime', 'createdDate', 'dateCreated', 'created']) {
Object v = d.get(k)
if (v instanceof Number) { made = v as long; break }
if (v instanceof String && v ==~ /\d{10,13}/) { made = v as long; break }
if (v instanceof String) { try { made = Date.parse("yyyy-MM-dd'T'HH:mm:ssZ", (v as String).trim().replaceFirst(/Z$/, '+0000')).time; break } catch (Exception e) { made = null } }
}
if (made != null && made < 100000000000L) made = made * 1000L
if (made == null) return "the hub's record of it gives no time it was added"
if (now() - made > HAI_JOIN_NEW_MS) return 'the hub says it was added earlier, not just now'
return null
}
void haiNoteDeclined() {
List chosen = ((settings.runtimeAllowDevices ?: []) as List).findAll { it != null }.collect { it.id.toString() }
List before = state.haiAllowSeen instanceof List ? state.haiAllowSeen : null
List declined = new ArrayList((List) (state.haiDeclined ?: []))
boolean moved = false
if (before != null) {
before.findAll { !chosen.contains(it) }.each { if (!declined.contains(it)) { declined << it; moved = true } }
}
if (declined.removeAll { chosen.contains(it) }) moved = true
if (moved) state.haiDeclined = declined.size() > 500 ? declined.subList(declined.size() - 500, declined.size()) : declined
if (before == null || before.sort(false) != chosen.sort(false)) state.haiAllowSeen = chosen
}
void haiEverydayFollow() {
Map vals = everydayValues(haiEverydayLevels())
List changed = everydayChanged((Map) state.everydayApplied, vals)
state.everydayApplied = vals
if (!changed) return
List queue = []
try {
((Map) rtRegistry(runtimePlat()).rules).each { Object id, Object eo ->
Map e = eo
Map ix = e.index
boolean uses = ix == null || !ix.containsKey('everyday') || ((List) ix.everyday).any { it in changed }
if (uses && e.state in ['active', 'disabled']) queue << (id as String)
}
} catch (Exception ex) { log.warn "HAI Engine: Everyday words: the rule list could not be read: ${ex.message}" }
state.everydayQueue = queue
state.everydayResult = [updated: 0, failed: 0, unchanged: 0, pending: queue.size(), at: now()]
if (queue) runIn(2, 'haiEverydayStep', [overwrite: true])
}
def haiEverydayStep() {
List queue = new ArrayList((List) (state.everydayQueue ?: []))
if (!queue) return
String id = queue.remove(0) as String
state.everydayQueue = queue
Map res = new LinkedHashMap((Map) (state.everydayResult ?: [updated: 0, failed: 0, unchanged: 0]))
try {
Map pp = runtimePlat([hold: true])
Map e = rtEntry(pp, id)
Map child = e?.childId != null && runtimeChild(e.childId) != null ? runtimeChildCall(e.childId, 'haiRuntimeState', []) : null
Map doc = child?.doc instanceof Map ? child.doc : null
Map f = null
if (doc != null) { Map got = checkerCall([op: 'everyday', doc: doc, values: everydayValues(haiEverydayLevels())]); f = got.checkerError ? null : got.follow }
if (f == null) { res.failed = (res.failed as int) + 1; log.warn "HAI Engine: Everyday words: ${id} could not be read; left as it was" }
else if ((f.changed as int) == 0) {
res.unchanged = (res.unchanged as int) + 1
if (e.index == null || !((Map) e.index).containsKey('everyday')) rtRegistryTransition(pp, id, [e.state as String], e.state as String, [doc: doc])
} else {
Map kept = runtimeKeepText(pp, groovy.json.JsonOutput.toJson(f.doc))
if (kept.kept > 0) { res.updated = (res.updated as int) + 1; haiInfo "HAI Engine: Everyday words: ${id} updated to the new levels" }
else { res.failed = (res.failed as int) + 1; log.warn "HAI Engine: Everyday words: ${id} not updated: ${kept.notice ?: 'refused'}" }
}
} catch (Exception ex) {
res.failed = (res.failed as int) + 1
log.warn "HAI Engine: Everyday words: ${id} not updated: ${ex.message}"
}
res.pending = queue.size()
res.at = now()
state.everydayResult = res
if (queue) runIn(3, 'haiEverydayStep', [overwrite: true])
}
String haiEverydayReport() {
Map r = state.everydayResult
if (r == null) return null
List parts = []
int u = (r.updated ?: 0) as int, f = (r.failed ?: 0) as int, p = (r.pending ?: 0) as int
parts << "${u} rule${u == 1 ? '' : 's'} updated to the new levels".toString()
if (p) parts << "${p} still to update".toString()
if (f) parts << "${f} could not be updated (see the log)".toString()
return parts.join('; ') + '.'
}
void haiInit() {
state.haiCodeVersion = ENGINE_APP_VERSION
runtimePermissionDefaults()
haiAudiencesInit()
engineInit()
runtimeInit()
try { checkerChild() } catch (Exception e) { log.warn "HAI Engine: HAI Checker could not be added yet (${e.message})" }
try { runtimeRelabelAll() } catch (Exception e) { log.warn "HAI Engine: the rules' app names could not be refreshed (${e.message})" }
}
void runtimeRelabelAll() {
Map pp = runtimePlat()
List paused = (state.runtimePaused ?: [])
((Map) rtRegistry(pp).rules).values().each { Object eo ->
Map e = eo
if (e.state == 'removed') return
def child = runtimeChild(e.childId)
if (child == null) return
String label = rtChildLabel(runtimeNameWithValues(runtimeRuleName(e)), e.ruleId, e.state, paused.contains(e.ruleId))
if (child.label != label) {
try { child.updateLabel(label) } catch (Exception ex) { log.warn "HAI Runtime: could not relabel ${e.ruleId}: ${ex.message}" }
}
}
}
def haiCodeCheckSoon() { haiCodeCheck() }
void haiCodeCheck() {
if (state.haiCodeVersion == ENGINE_APP_VERSION) return
String was = state.haiCodeVersion as String
try {
haiInit()
haiInfo "HAI Engine: updated from ${was ?: 'an earlier build'} to ${ENGINE_APP_VERSION}; its subscriptions and schedules are set up again."
} catch (Exception e) {
state.haiCodeVersion = ENGINE_APP_VERSION
log.warn "HAI Engine: setting up ${ENGINE_APP_VERSION} after a code update failed (${e.message}); press Done on the HAI Engine page."
}
}
void runtimePermissionDefaults() {
if (state.permissionDefaultsApplied) return
state.permissionDefaultsApplied = true
if (settings.runtimeAllowSetMode == null) app.updateSetting('runtimeAllowSetMode', [type: 'bool', value: true])
if (settings.runtimeAllowHsm == null) app.updateSetting('runtimeAllowHsm', [type: 'bool', value: true])
if (settings.runtimeWritableVariables == null) {
List all = runtimeHubVariableOptions().keySet() as List
if (all) app.updateSetting('runtimeWritableVariables', [type: 'enum', value: all])
}
}
String haiPermissionTitle(String... parts) {
List out = []
parts.eachWithIndex { String part, int i ->
if (!part) return
out << (i % 2 == 0 ? "<span style='color:#1a77c9'>${esc(part)}</span>" : "<span style='color:#000'>${esc(part)}</span>")
}
return out.join(' ')
}
Map haiEverydayLevels() {
String scale = haiEverydayScale()
Map out = [:]
EVERYDAY_SETTINGS.each { String level, String name ->
out.put(level, settings[name] != null ? (settings[name] as BigDecimal) : (haiEverydayDefault(level, scale) as BigDecimal))
}
out.scale = scale
return out
}
@Field static final Map EVERYDAY_SETTINGS = [darkBelow: 'everydayDarkBelow', overcastFrom: 'everydayOvercastFrom', brightFrom: 'everydayBrightFrom',
hotFrom: 'everydayHotFrom', coldTo: 'everydayColdTo', windyFrom: 'everydayWindyFrom']
String haiEverydayScale() { return location?.temperatureScale == 'F' ? 'F' : 'C' }
Object haiEverydayDefault(String level, String scale) {
return EVERYDAY_DEFAULTS.containsKey(level) ? EVERYDAY_DEFAULTS.get(level) : EVERYDAY_DEFAULTS.get(level + scale)
}
String haiVersion() { return ENGINE_APP_VERSION.replace('-dev', '') }
String haiHelpLine() {
String report = "<a href='${HAI_ISSUES_URL}' target='_blank'>report it or give feedback on GitHub</a>"
String thread = HAI_COMMUNITY_URL ? ", or ask in the <a href='${HAI_COMMUNITY_URL}' target='_blank'>HAI community thread</a>" : ''
return "<small>Need help or found a problem? ${report}${thread}.</small>".toString()
}
def appButtonHandler(String btn) {
if (btn == null) return
if (HAI_PAGE_BUTTONS.containsKey(btn)) state.haiPageOpen = HAI_PAGE_BUTTONS.get(btn)
if (btn == 'engineClearSpendStop') { engineClearSpendStop(); return }
if (btn == 'engineTestApiKey') { engineTestApiKey(); return }
if (btn == 'haiDiagnostics') { haiDiagnosticsOn(); return }
if (btn == 'haiRotateAccess') { haiRotateAccess(); return }
if (btn == 'haiMigrationAdd') {
try { addChildApp('gordonthelander', MIGRATION_CHILD_NAME, 'HAI Migration'); state.remove('haiNotice') }
catch (Exception e) {
boolean notYet = false
try { notYet = app.getInstallationState() != 'COMPLETE' } catch (Exception x) { notYet = false }
state.haiNotice = notYet ? 'help:migration-not-yet' : 'help:migration-missing'
}
return
}
if (btn == 'haiDeactivate') {
String rule = settings.haiDeactivateTarget as String
if (rule == null) return
Map out = rtRuleOp(runtimePlat(), rule, 'disable')
state.haiNotice = out?.ok ? "Deactivated ${rule}. Its running runs were cancelled.".toString()
: ((out?.message ?: out?.code ?: 'That did not work.') as String)
}
}
String haiApiTokenHtml(String token) {
String t = (token ?: '').replaceAll(/[^a-f0-9]/, '')
String copy = "var c=document.getElementById('haiApiTokenText'),r=document.createRange();r.selectNodeContents(c);" +
"var s=window.getSelection();s.removeAllRanges();s.addRange(r);" +
"var ok=false;try{ok=document.execCommand('copy')}catch(e){}this.textContent=ok?'Copied':'Selected: press Ctrl+C'"
return "<b>API token for other apps</b><br><code id=\"haiApiTokenText\">${t}</code> " +
"<button type=\"button\" onclick=\"${copy}\">Copy</button>"
}
Map runtimeHubVariableOptions() {
try {
return ((Map) (getAllGlobalVars() ?: [:])).keySet().sort().collectEntries { name ->
[(name.toString()): name.toString()]
}
} catch (Exception e) {
log.warn "HAI Engine: hub variables unavailable: ${e.message}"
return [:]
}
}
Map runtimeRuleOptions() {
Map rules = (rtRegistry(runtimePlat()).rules ?: [:])
return rules.values().findAll { it.state == 'active' }.sort { a, b ->
runtimeRuleName(a) <=> runtimeRuleName(b)
}.collectEntries { e ->
[(e.ruleId.toString()): runtimeRuleName(e)]
}
}
@Field static final Map HAI_PAGE_SECTIONS = [
devices: ['runtimeAllowDevices', 'haiAutoApproveNew'],
powers: ['runtimeAllowSetMode', 'runtimeAllowHsm', 'runtimeAllowHubPower', 'runtimeWritableVariables'],
stop: ['haiDeactivateTarget'],
endpoints: ['haiEndpointOn', 'haiEndpointGet', 'haiEndpointCloud'],
mcp: ['haiMcpOn', 'haiMcpCloud']]
@Field static final Map HAI_PAGE_BUTTONS = [haiDeactivate: 'stop', engineClearSpendStop: 'ai', engineTestApiKey: 'ai',
haiRotateAccess: 'access', haiDiagnostics: 'health']
String haiPageOpenSection() {
String pressed = state.remove('haiPageOpen') as String
Map seen = state.haiPageSeen instanceof Map ? state.haiPageSeen : null
Map marks = [:]
HAI_PAGE_SECTIONS.each { String k, List keys ->
marks.put(k, keys.collect { String n ->
Object v = settings[n]
v instanceof List ? ((List) v).collect { x -> x instanceof String || x instanceof Number ? x : x?.id }.join(',') : "${v}"
}.join('|').hashCode())
}
boolean recent = seen != null && now() - ((seen.at ?: 0L) as long) < 1800000L
String changed = recent ? (String) marks.keySet().find { String k -> seen.get(k) != null && seen.get(k) != marks.get(k) } : null
state.haiPageSeen = marks + [at: now()]
return pressed ?: changed
}
def haiMainPage() {
boolean haiPending = false
try { haiPending = app.getInstallationState() != 'COMPLETE' } catch (Exception e) { haiPending = false }
try { if (state.haiApproveAll == null && haiPending) haiApproveAllOnce() } catch (Exception e) { log.warn "HAI Engine: could not approve devices on install: ${e.message}" }
List haiOthers = haiPending ? haiOtherEngines() : []
String haiHelpText = editorHelpPage()
Map haiHelpFill = [docs: HAI_DOCS_URL, issues: HAI_ISSUES_URL, amRepo: AM_REPO_URL, amRepoShort: AM_REPO_URL.replace('https://', ''),
keys: CLAUDE_KEYS_URL, keysShort: CLAUDE_KEYS_URL.replace('https://', '')]
Closure pageHelp = { String part -> editorHelpParts(haiHelpText, part, haiHelpFill) }
String haiOpen = haiPageOpenSection()
dynamicPage(name: 'haiMainPage', title: "HAI Engine ${haiVersion()} (${HAI_ENVIRONMENT})", install: !haiOthers, uninstall: true) {
if (haiHelpText == null) {
section() {
paragraph "<b style='color:#b00020'>Some of this page's explanations are missing.</b> ${HELP_PAGE_FILE} in the File Manager is missing or is not the copy this version of HAI Engine was built with. Update HAI again to put it back."
}
}
if (haiOthers) {
section() {
paragraph "<b style='color:#b00020'>HAI Engine is already on this hub (app ${haiOthers.join(', ')}).</b> Do not press Done here: a second HAI Engine would run every rule twice. Remove this one, and open the existing HAI Engine from Apps."
}
}
String haiNewer = haiNewerVersion()
if (haiNewer) {
section() {
editorHelpParts(haiHelpText, 'newer', haiHelpFill + [newer: esc(haiNewer)]).each { paragraph it }
}
}
Map haiAi = haiAiStatus()
if (haiAi.available != true && haiAi.code != 'nokey') {
section() {
editorHelpParts(haiHelpText, 'ai-off', haiHelpFill + [why: esc(haiAi.why as String), act: esc(haiAi.act as String)]).each { paragraph it }
}
}
boolean haiNotYet = false
try { haiNotYet = app.getInstallationState() != 'COMPLETE' } catch (Exception e) { haiNotYet = false }
if (!state.accessToken) { try { createAccessToken() } catch (Exception e) { /* OAuth off: said below */ } }
section('The rule editor') {
if (state.accessToken) {
href name: 'haiEditorHref', title: 'Open the rule editor', required: false,
url: haiEditorAddress(),
description: 'Write, check, simulate and run your rules.'
if (haiNotYet) pageHelp('editor-not-yet').each { paragraph it }
} else {
pageHelp('editor-no-oauth').each { paragraph it }
}
if (state.haiNotice) ("${state.haiNotice}".startsWith('help:') ? pageHelp("${state.haiNotice}".substring(5)) : ["<b>${esc(state.haiNotice as String)}</b>"]).each { paragraph it }
paragraph haiHelpLine()
}
section('HAI Overview', hideable: true, hidden: true) {
pageHelp('overview').each { paragraph it }
}
section('Rule Machine') {
def mig = haiMigrationChild()
String amNeed = pageHelp('am-need').join(' ')
if (state.haiAmSeen != true) {
boolean found = ['ham-decode-dev.json', 'ham-decode.json'].any { String f ->
try { byte[] b = downloadHubFile(f); return b != null && b.length > 2 && b[0] == (byte) 123 } catch (Exception e) { return false }
}
if (found) state.haiAmSeen = true
}
if (mig != null) {
href name: 'haiMigrationHref', title: 'Rule Machine Migration Facility', required: false,
url: "/installedapp/configure/${mig.id}".toString(),
description: 'Discover, select, assess and controlled migration of RM rules, with rollback option'
if (state.haiAmSeen != true && amNeed) paragraph amNeed
} else {
pageHelp('migration').each { paragraph it }
if (amNeed) paragraph amNeed
input 'haiMigrationAdd', 'button', title: 'Turn on HAI Migration'
}
}
int haiDeviceCount = ((List) (settings.runtimeAllowDevices ?: [])).size()
Map haiAll = state.haiApproveAll instanceof Map ? state.haiApproveAll : null
if (haiDeviceCount == 0 && haiAll?.count) haiDeviceCount = haiAll.count as int
boolean haiAllNew = haiAll != null && haiAll.seen != true && !haiAll.skipped
if (haiAllNew && haiAll.count) section() { editorHelpParts(haiHelpText, 'all-devices', haiHelpFill + [count: haiAll.count]).each { paragraph it } }
section(haiPermissionTitle('Approved devices:', "(${haiDeviceCount})"),
hideable: true, hidden: haiDeviceCount > 0 && haiOpen != 'devices') {
if (haiAllNew && haiAll.error) editorHelpParts(haiHelpText, 'all-devices-unread', haiHelpFill + [error: haiAll.error]).each { paragraph it }
input 'runtimeAllowDevices', 'capability.*', title: 'Devices', multiple: true, required: false, submitOnChange: true
input 'haiAutoApproveNew', 'bool', title: 'Approve new devices automatically', defaultValue: true, submitOnChange: true
pageHelp('devices-new').each { paragraph it }
}
Map haiVarOptions = runtimeHubVariableOptions()
List haiVarsChosen = settings.runtimeWritableVariables == null ? (haiVarOptions.keySet() as List) : settings.runtimeWritableVariables
boolean haiModeOn = settings.runtimeAllowSetMode != false
boolean haiHsmOn = settings.runtimeAllowHsm != false
boolean haiPowerOn = settings.runtimeAllowHubPower != false
section(haiPermissionTitle('Mode', "[${haiModeOn ? 'ON' : 'OFF'}],", 'HSM', "[${haiHsmOn ? 'ON' : 'OFF'}],",
'Reboot', "[${haiPowerOn ? 'ON' : 'OFF'}]",
'and approved hub variables', "(${haiVarsChosen.size()})"),
hideable: true, hidden: haiOpen != 'powers') {
List haiModeNames = location.modes.collect { it.name.toString() }
input 'runtimeAllowSetMode', 'bool', defaultValue: true, submitOnChange: true,
title: "Allow rules to change the mode${haiModeNames ? " (${haiModeNames.join(', ')})" : ''}".toString()
input 'runtimeAllowHsm', 'bool', defaultValue: true, submitOnChange: true,
title: 'Allow rules to arm and disarm Hubitat Safety Monitor'
input 'runtimeAllowHubPower', 'bool', defaultValue: true, submitOnChange: true,
title: 'Allow rules to reboot or shut down the hub'
input 'runtimeWritableVariables', 'enum', title: 'Approved hub variables', submitOnChange: true,
options: haiVarOptions, multiple: true, required: false, defaultValue: haiVarOptions.keySet() as List
}
section('Stop a rule', hideable: true, hidden: haiOpen != 'stop') {
pageHelp('stop-rule').each { paragraph it }
input 'haiDeactivateTarget', 'enum', title: 'Rule', options: runtimeRuleOptions(), required: false, submitOnChange: true
if (settings.haiDeactivateTarget) input 'haiDeactivate', 'button', title: 'Deactivate'
}
String typed = (settings.engineApiKey as String)?.trim()
if (typed) {
String mark = "${typed.length()}:${typed.length() > 4 ? typed[-4..-1] : ''}".toString()
if (state.engineKeyTestedMark != mark) { state.engineKeyTestedMark = mark; engineTestApiKey() }
}
Long aiTestAt = (state.engineKeyTest instanceof Map ? ((Map) state.engineKeyTest).at : null) as Long
boolean aiOpen = (haiAi.available != true && haiAi.code != 'nokey') || (aiTestAt != null && now() - aiTestAt < 120000L)
section('AI token and cost management', hideable: true, hidden: !aiOpen && haiOpen != 'ai') {
if (!engineHasKey()) pageHelp('ai-optional').each { paragraph it }
input 'engineApiKey', 'password', required: false, submitOnChange: true,
title: state.engineKeyTail ? "Claude API key - one ending ...${state.engineKeyTail} is stored. Type a new one to replace it.".toString()
: 'Claude API key (stored on this hub only)'
pageHelp('ai-keys').each { paragraph it }
if (engineHasKey() && settings.engineSpendLimitUsd == null) pageHelp('ai-no-limit').each { paragraph it }
input 'engineSpendLimitUsd', 'decimal', title: 'Total spending allowed for drafting (US$)', required: false
paragraph "<small>${esc(engineDraftAverageText())}</small>"
if (state.engineSpendStop) {
editorHelpParts(haiHelpText, 'ai-stopped', haiHelpFill + [reason: esc((state.engineSpendStop.reason ?: 'the spending limit was reached') as String)]).each { paragraph it }
input 'engineSpendLimitConfirmUsd', 'decimal', title: 'Type the spending limit again to confirm', required: false
input 'engineClearSpendStop', 'button', title: 'Clear spending stop'
}
input 'engineEffort', 'enum', title: 'Effort', options: ['low', 'medium', 'high', 'xhigh'], defaultValue: 'high'
input 'engineTestApiKey', 'button', title: 'Test the API key'
if (state.engineKeyTest) paragraph engineKeyTestLine()
}
section('Rule end points', hideable: true, hidden: haiOpen != 'endpoints') {
pageHelp('endpoints').each { paragraph it }
input 'runtimeAppApi', 'bool', title: 'Let other apps on this hub run HAI rules', defaultValue: true
pageHelp('endpoints-apps').each { paragraph it }
if (state.haiApiToken) {
paragraph haiApiTokenHtml(state.haiApiToken as String)
pageHelp('endpoints-token').each { paragraph it }
}
input 'haiEndpointOn', 'bool', title: 'Let a web address run HAI rules (dashboards, shortcuts, other hubs)', defaultValue: false, submitOnChange: true
if (settings.haiEndpointOn != true) {
pageHelp('endpoints-off').each { paragraph it }
} else if (state.accessToken) {
paragraph "<b>Your private web address</b><br><code>${getFullLocalApiServerUrl()}/endpoint/rules?access_token=${state.accessToken}&aud=${haiAudienceSecret('endpoint')}</code>"
pageHelp('endpoints-on').each { paragraph it }
input 'haiEndpointGet', 'bool', title: 'Allow GET links (Rule Machine-style)', defaultValue: false, submitOnChange: true
pageHelp(settings.haiEndpointGet == true ? 'endpoints-get-on' : 'endpoints-get-off').each { paragraph it }
input 'haiEndpointCloud', 'bool', title: "Allow through Hubitat's cloud", defaultValue: false, submitOnChange: true
if (settings.haiEndpointCloud == true) {
paragraph "<b>Your private cloud address</b><br><code>${getFullApiServerUrl()}/endpoint/rules?access_token=${state.accessToken}&aud=${haiAudienceSecret('endpoint')}</code>"
pageHelp('cloud-on').each { paragraph it }
} else {
pageHelp('cloud-off').each { paragraph it }
}
}
}
section('Everyday words', hideable: true, hidden: true) {
String scale = haiEverydayScale()
String deg = "\u00b0${scale}".toString()
Closure dv = { String level -> everydayNumber(haiEverydayDefault(level, scale)) }
pageHelp('everyday').each { paragraph it }
String evDone = haiEverydayReport()
if (evDone) paragraph "<small>Last change: ${evDone}</small>".toString()
input 'everydayDarkBelow', 'decimal', title: 'Dark: under this many lux', required: false, defaultValue: dv('darkBelow')
input 'everydayOvercastFrom', 'decimal', title: 'Dusk or dawn up to, and overcast from, this many lux', required: false, defaultValue: dv('overcastFrom')
input 'everydayBrightFrom', 'decimal', title: 'Bright day: this many lux or more', required: false, defaultValue: dv('brightFrom')
input 'everydayHotFrom', 'decimal', title: "Hot: this many ${deg} or more".toString(), required: false, defaultValue: dv('hotFrom')
input 'everydayColdTo', 'decimal', title: "Cold: this many ${deg} or less".toString(), required: false, defaultValue: dv('coldTo')
input 'everydayWindyFrom', 'decimal', title: 'Windy: wind speed this or more, in the unit your weather device reports', required: false, defaultValue: dv('windyFrom')
pageHelp('everyday-light').each { paragraph it }
input 'everydayWeatherDevice', 'capability.temperatureMeasurement', title: 'Weather device', required: false, multiple: false
pageHelp('everyday-weather').each { paragraph it }
}
section('MCP endpoint', hideable: true, hidden: haiOpen != 'mcp') {
pageHelp('mcp').each { paragraph it }
input 'haiMcpOn', 'bool', title: 'Let an AI assistant connect (MCP)', defaultValue: false, submitOnChange: true
if (settings.haiMcpOn != true) {
pageHelp('mcp-off').each { paragraph it }
} else if (!state.accessToken) {
pageHelp('mcp-done').each { paragraph it }
} else {
paragraph "<code>${getFullLocalApiServerUrl()}/mcp?access_token=${state.accessToken}&aud=${haiAudienceSecret('mcp')}</code>"
pageHelp('mcp-on').each { paragraph it }
input 'haiMcpCloud', 'bool', title: "Allow through Hubitat's cloud", defaultValue: false, submitOnChange: true
if (settings.haiMcpCloud == true) {
paragraph "<code>${getFullApiServerUrl()}/mcp?access_token=${state.accessToken}&aud=${haiAudienceSecret('mcp')}</code>"
pageHelp('cloud-on').each { paragraph it }
} else {
pageHelp('cloud-off').each { paragraph it }
}
}
}
section('Change access addresses', hideable: true, hidden: haiOpen != 'access') {
pageHelp('access').each { paragraph it }
input 'haiRotateAccess', 'button', title: "Change HAI's access addresses"
}
section('Health and diagnostics', hideable: true, hidden: haiOpen != 'health') {
paragraph haiHealthLine()
haiDiagnosticLines().each { String line -> paragraph "<small>${esc(line)}</small>".toString() }
if (haiDiagnosticsActive()) {
pageHelp('diagnostics-on').each { paragraph it }
} else {
pageHelp('diagnostics-off').each { paragraph it }
input 'haiDiagnostics', 'button', title: 'Turn diagnostics on for an hour'
}
input 'txtEnable', 'bool', title: 'Log what HAI Engine does (descriptive text)', defaultValue: true
input 'debugEnable', 'bool', title: 'Log every request and activation as well (debug; turns itself off after 30 minutes)', defaultValue: false
}
}
}
void haiInfo(Object text) { if (settings.txtEnable != false) log.info(text as String) }
void haiDebug(Object text) { if (settings.debugEnable == true) log.debug(text as String) }
def logsOff() {
app.updateSetting('debugEnable', [value: 'false', type: 'bool'])
log.info 'HAI Engine: debug logging is off again'
}
void haiLogsSchedule() {
unschedule('logsOff')
if (settings.debugEnable == true) runIn(1800, 'logsOff')
}
void haiDiagnosticsOn() {
state.haiDiagnosticsUntil = now() + 3600000L
runIn(3600, 'haiDiagnosticsExpire')
}
String haiSigningKey() {
if (((state.runtimePairing ?: '') as String).length() < 32) state.runtimePairing = UUID.randomUUID().toString().replace('-', '')
return state.runtimePairing as String
}
String haiReceiptKey() {
if (!state.haiReceiptKey) state.haiReceiptKey = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace('-', '')
return state.haiReceiptKey as String
}
def haiDiagnosticsExpire() {
state.remove('haiDiagnosticsUntil')
}
boolean haiDiagnosticsActive() {
Object until = state.haiDiagnosticsUntil
return until != null && (until as long) > now()
}
List haiDiagnosticLines() {
List out = []
String hp = editorHelpPage()
Map ev = haiEverydayLevels().collectEntries { k, v -> [(k): k == 'scale' ? v : everydayNumber(v)] }
out.addAll(editorHelpParts(hp, 'diag-everyday', ev + [deg: "\u00b0${ev.scale}"]))
def wx = settings.everydayWeatherDevice
if (wx == null) {
out.addAll(editorHelpParts(hp, 'diag-no-weather'))
} else {
Long at = null
try { at = wx.getLastActivity()?.getTime() } catch (Exception e) { at = null }
String seen = at == null ? 'has not reported' :
(now() - at > 6L * 3600000L ? "has not reported since ${runtimeClock().call(at)}, check its API key and polling".toString()
: "reporting, last at ${runtimeClock().call(at)}".toString())
out << "Weather device: ${wx.displayName}, ${seen}.".toString()
}
Map pairs = (state.migPairs ?: [:])
List switched = pairs.values().findAll { it instanceof Map && ((Map) it).phase == 'switched' && ((Map) it).haiRuleId != null }
if (switched) {
Map runs = [:]
((List) (((Map) (state.runtimeSummary ?: [:])).rules ?: [])).each { Object o -> if (o instanceof Map) runs.put(((Map) o).id as String, o) }
int wrong = switched.count { Object p -> ((Map) (runs.get(((Map) p).haiRuleId as String) ?: [:])).lastRunProblem } as int
int ran = switched.count { Object p -> ((Map) (runs.get(((Map) p).haiRuleId as String) ?: [:])).lastRunAt != null } as int
int n = switched.size()
out << "Migration watch: Rule Machine rules switched over to HAI ${n}, run ${ran}, last run went wrong ${wrong}${wrong ? ' (see HAI Migration)' : ''}.".toString()
} else {
out.addAll(editorHelpParts(hp, 'diag-no-switch'))
}
return out
}
String haiHealthLine() {
Map reg = (rtRegistry(runtimePlat()).rules ?: [:])
int live = reg.values().count { it instanceof Map && ((Map) it).index != null } as int
Object last = state.runtimeAnnouncedAt
String when = last == null ? 'not yet' : runtimeClock().call(last as long)
return "HAI Engine ${haiVersion()} (${HAI_ENVIRONMENT}). ${live} rule${live == 1 ? '' : 's'} on this hub. Last summary: ${when}."
}
@Field static final String ENGINE_APP_VERSION = '0.5.158-dev'
@Field static final int ENGINE_MAX_PROPOSALS = 20
@Field static final java.util.concurrent.ConcurrentHashMap ENGINE_DRAFT_INV = new java.util.concurrent.ConcurrentHashMap()
@Field static final int ENGINE_MAX_ATTRIBUTES = 64
@Field static final int ENGINE_MAX_MCP_BODY_CHARS = 262144
@Field static final int ENGINE_SUN_DAYS = 3
@Field static final List MCP_PROTOCOL_VERSIONS = ['2025-06-18', '2025-03-26', '2024-11-05']
@Field static final String ENGINE_EFFECTS = 'No automation is activated and no device command is sent.'
void engineInit() {
unsubscribe()
['engineDispatchNow', 'runtimeRuleIndex', 'runtimeAnnounced'].each { state.remove(it) }
if (!state.accessToken) {
try { createAccessToken() } catch (Exception e) { haiOAuthSelf('HAI Engine') }
}
}
boolean haiOAuthSelf(String who) {
if (state.oauthSelfAt != null && now() - (state.oauthSelfAt as long) < 60000L) return false
state.oauthSelfAt = now()
try {
Object typeId = null
httpGet([uri: "http://127.0.0.1:8080/installedapp/json/${app.id}".toString(), contentType: 'application/json', timeout: 10]) { resp ->
Map d = resp?.data instanceof Map ? resp.data : [:]
typeId = (d.app instanceof Map ? ((Map) d.app).id : null) ?: (d.appType instanceof Map ? ((Map) d.appType).id : null) ?:
(d.installedApp instanceof Map ? (((Map) d.installedApp).appTypeId ?: ((Map) d.installedApp).appId) : null) ?: d.appTypeId
}
if (!("${typeId}" ==~ /\d{1,12}/)) throw new IllegalStateException("this app's code id was not found")
Object version = null
httpGet([uri: "http://127.0.0.1:8080/app/ajax/code?id=${typeId}".toString(), contentType: 'application/json', timeout: 10]) { resp ->
version = resp?.data instanceof Map ? ((Map) resp.data).version : null
}
if (version == null) throw new IllegalStateException("the code's version was not read")
httpPost([uri: 'http://127.0.0.1:8080/app/edit/update', requestContentType: 'application/x-www-form-urlencoded', timeout: 20,
body: [id: typeId as String, version: version as String, oauthEnabled: 'true', webServerRedirectUri: '', displayLink: '',
_action_update: 'Update']]) { resp -> }
createAccessToken()
log.info "${who}: OAuth was off for this app's code, so HAI turned it on"
return true
} catch (Exception e) {
log.warn "${who}: OAuth is not enabled for this app code, and could not be turned on (${e.message}); turn it on in Apps Code"
return false
}
}
Map engineDispatchSubmission(Map payload, Map inProcess = null) {
Map receipt
try {
receipt = inProcess != null ? runtimeSubmitBody(payload, inProcess) : runtimeSubmitBody(groovy.json.JsonOutput.toJson(payload))
} catch (Exception e) {
receipt = [ok: false, code: 'DISPATCH_FAILED', message: e.message as String]
}
engineRecordDispatch(payload, receipt)
return receipt
}
void engineRecordDispatch(Map payload, Map receipt) {
state.engineLastDispatch = [ruleId: payload?.ruleId, sentAt: payload?.sentAt, sig: payload?.sig,
at: now(), ok: receipt?.ok == true, code: receipt?.code,
message: receipt?.message, notice: receipt?.notice]
if (receipt?.ok != true) log.warn "HAI Engine: submission ${payload?.ruleId} not accepted: ${receipt?.code} ${receipt?.message ?: ''}"
}
Map engineInventory(Collection<String> onlyIds = null, Map forDoc = null, boolean withValues = true) {
Map inv = invNew()
Map byDriver = [:]
List selected = (settings.runtimeAllowDevices ?: []) as List
if (onlyIds != null) selected = selected.findAll { it != null && onlyIds.contains(it.id.toString()) }
selected.each { dev ->
List unavailable = []
String driverKey = (dev.typeName ?: "device ${dev.id}").toString()
Map drv = byDriver[driverKey]
if (drv == null) {
drv = [unavailable: [], numeric: [] as Set]
Set dc = [] as Set
dev.capabilities.each { c -> dc << c.name.toString() }
drv.caps = dc
Map commandArgs = [:]
try {
Set cmds = [] as Set
dev.supportedCommands.each { c ->
String n = c.name.toString()
cmds << n
try { commandArgs[n] = (c.arguments ?: []).collect { it.toString() } }
catch (Exception e) { commandArgs[n] = 'unknown' }
}
drv.cmds = cmds
} catch (Exception e) {
drv.cmds = null
((List) drv.unavailable) << 'commands'
}
drv.commandArgs = commandArgs
try {
List declared = (dev.supportedAttributes as List)
drv.attrNames = declared.collect { a -> a.name.toString() }
Map dv = [:]
Set numeric = [] as Set
declared.each { a ->
try {
if (a.dataType?.toString() == 'NUMBER') numeric << a.name.toString()
if (a.dataType?.toString() == 'ENUM') {
List vs = (a.values ?: []) as List
if (vs) dv[a.name.toString()] = vs.take(40).collect { it.toString() }
}
} catch (Exception ignored) { }
}
drv.declaredValues = dv
drv.numeric = numeric
} catch (Exception e) {
drv.attrNames = null
((List) drv.unavailable) << 'attributes'
}
byDriver[driverKey] = drv
}
unavailable.addAll((List) drv.unavailable)
Set caps = new LinkedHashSet((Set) drv.caps)
Set cmds = drv.cmds == null ? null : new LinkedHashSet((Set) drv.cmds)
Map commandArgs = new LinkedHashMap((Map) drv.commandArgs)
Set attrs = null
Map states = [:]
if (drv.attrNames != null) {
List all = drv.attrNames
attrs = new LinkedHashSet(all)
if (withValues) try {
all.take(ENGINE_MAX_ATTRIBUTES).each { String n ->
def st = dev.currentState(n)
Object v = st?.value
if (v == null) return
if (((Set) drv.numeric).contains(n)) { try { if (st.numberValue != null) v = st.numberValue } catch (Exception e) { } }
if (!(v instanceof String || v instanceof Number || v instanceof Boolean)) v = v.toString()
Map row = [value: v]
try { if (st.date != null) row.ts = st.date.getTime() } catch (Exception e) { }
states[n] = row
}
if (all.size() > ENGINE_MAX_ATTRIBUTES) unavailable << "readings beyond the first ${ENGINE_MAX_ATTRIBUTES} attributes".toString()
} catch (Exception e) {
unavailable << 'attributes'
}
}
Boolean disabled = null
try { disabled = dev.isDisabled() as Boolean }
catch (Exception e) { unavailable << 'enabled state' }
String room = null
try { room = dev.roomName } catch (Exception e) { room = null }
String id = "dev:${dev.id}".toString()
Map row = [id: id, name: dev.displayName.toString(), room: room, driver: dev.typeName?.toString(),
capabilities: caps, commands: cmds, attributes: attrs, currentStates: states,
metadata: [commandArgs: commandArgs, unavailable: unavailable]]
if (disabled != null) row.disabled = disabled
if (!withValues) row.valuesRead = false
if (drv.declaredValues) row.declaredValues = drv.declaredValues
inv.devices[id] = row
}
inv.modeIds = [:]
location.modes.each { m -> inv.modes << m.name.toString(); inv.modeIds[m.id.toString()] = m.name.toString() }
inv.currentMode = location.mode?.toString()
Object writable = ((Map) (state.runtimeSummary ?: [:])).writableVariables
inv.ruleNames = [:]
((List) (((Map) (state.runtimeSummary ?: [:])).rules ?: [])).each { Object r ->
if (r instanceof Map && ((Map) r).id) {
((Collection) inv.rules) << (((Map) r).id as String)
((Map) inv.ruleNames).put(((Map) r).id as String, ((Map) r).name ?: ((Map) r).id)
}
}
inv.rmRuleNames = [:]
if (forDoc == null || rtDocUsesRmRules(forDoc)) { try {
schemaRmRuleRows(RMUtils.getRuleList('5.0')).each { Map r -> ((Collection) inv.rmRules) << r.id; ((Map) inv.rmRuleNames).put(r.id, r.name) }
Map sw = haiSwitchedPairs()
if (!sw.isEmpty()) inv.rmSwitched = sw
} catch (Exception e) { log.warn "HAI Engine: Rule Machine's rules could not be listed (${e.message})" } }
try { ((Map) inv.variables).putAll(schemaHubVariableRows(getAllGlobalVars(), writable instanceof List ? writable : null)) }
catch (Exception e) { log.warn "HAI Engine: hub variables could not be read (${e.message})" }
schemaMarkConnectors(inv)
return inv
}
Map engineSun() {
Map out = [:]
TimeZone tz = location.timeZone
for (int i = 0; i < ENGINE_SUN_DAYS; i++) {
try {
Date d = new Date(now() + i * 86400000L)
Map s = getSunriseAndSunset(date: d)
if (s?.sunrise && s?.sunset) {
out[d.format('yyyy-MM-dd', tz)] = [sunrise: s.sunrise.format('HH:mm', tz), sunset: s.sunset.format('HH:mm', tz)]
}
} catch (Exception e) { }
}
return out
}
Map engineOpts(Map extra = [:]) {
Map o = [now: now(), zone: location.timeZone.ID, effort: settings.engineEffort ?: 'high', activeRule: null,
sun: engineSun()]
o.putAll(extra)
return o
}
String engineKeyMask(String plain) {
if (plain == null || plain.isEmpty()) return null
byte[] b = plain.getBytes('UTF-8')
byte[] out = new byte[b.length]
for (int i = 0; i < b.length; i++) { out[i] = (byte) (b[i] ^ ENGINE_KEY_MASK[i % ENGINE_KEY_MASK.length]) }
return out.encodeBase64().toString()
}
List engineRuleIndex(Map inv) {
List hai = []
Map pp = runtimePlat()
((Map) (rtRegistry(pp).rules ?: [:])).each { Object k, Object v ->
Map e = v
if (!(e.state in ['active', 'disabled'])) return
Map ix = rtIndex(pp, e)
hai << [id: k as String, name: (e.name ?: ix?.name ?: k) as String, listens: ix?.listens ?: [], deviceIds: ix?.deviceIds ?: []]
}
Map detail = null
try {
detail = hamDetail([readLocalFile: { String n ->
byte[] b = downloadHubFile(n)
return b == null ? null : new String(b, 'UTF-8')
}], HAI_CHANNEL)
} catch (Throwable t) { detail = null }
return authorRuleIndex(hai, detail?.ok == true ? detail : null, (Collection) (inv.rmRules ?: []), (inv.rmRuleNames ?: [:]))
}
String engineKeyUnmask(String stored) {
if (stored == null || stored.isEmpty()) return null
try {
byte[] b = stored.decodeBase64()
byte[] out = new byte[b.length]
for (int i = 0; i < b.length; i++) { out[i] = (byte) (b[i] ^ ENGINE_KEY_MASK[i % ENGINE_KEY_MASK.length]) }
return new String(out, 'UTF-8')
} catch (Exception ignored) { return null }
}
void engineKeySweep() {
String entered = settings.engineApiKey as String
if (entered != null && !entered.trim().isEmpty()) {
engineKeyStore(entered.trim())
state.engineKeyTail = entered.trim().length() > 4 ? entered.trim()[-4..-1] : null
if (state.engineKeyTest?.fault == 'key') state.engineKeyTest = null
}
if (settings.containsKey('engineApiKey')) app.removeSetting('engineApiKey')
}
void engineKeyStore(String plain) {
String enc = null
try { enc = encrypt(plain) } catch (Throwable t) { enc = null }
if (enc) { state.engineKeyEnc = enc; state.remove('engineKeyMasked') }
else { state.engineKeyMasked = engineKeyMask(plain); state.remove('engineKeyEnc') }
}
String engineKey() {
String entered = settings.engineApiKey as String
if (entered != null && !entered.trim().isEmpty()) return entered.trim()
if (state.engineKeyEnc) { try { return decrypt(state.engineKeyEnc as String) as String } catch (Throwable t) { return null } }
String old = engineKeyUnmask(state.engineKeyMasked as String)
if (old) engineKeyStore(old)
return old
}
boolean engineHasKey() { return engineKey() != null }
Map haiAiStatus() {
return editorAiStatus(engineHasKey(), state.engineKeyTest, state.engineSpendStop, settings.engineSpendLimitUsd)
}
void engineDraft(Map base = null) {
state.engineDraftResult = null
filedPut('engineOutcome', null)
filedPut('engineAmendment', null)
if (!engineHasKey()) {
state.engineStatus = 'Add a Claude API key under Settings first.'
return
}
Map inv = engineInventory()
if (inv.devices.isEmpty()) {
state.engineStatus = 'Pick the devices the engine may use first.'
return
}
if (settings.engineSpendLimitUsd == null) {
state.engineStatus = 'Set a spending limit under Settings first.'
return
}
engineFoldSpend()
if (state.engineSpendStop) {
state.engineStatus = "Drafting is stopped: ${state.engineSpendStop.reason}. Check your usage, then clear the stop under Settings.".toString()
return
}
double remaining = (settings.engineSpendLimitUsd as double) - ((state.engineSpentUsd ?: 0.0d) as double)
long generation = ((state.engineGeneration ?: 0) as long) + 1L
state.engineGeneration = generation
if (base != null) {
filedPut('engineAmendment', [generation: generation, basedOn: [ruleHash: jsonHash(base, SCHEMA_DEFAULTS)], rule: null])
}
unschedule('engineResend')
Map authorOptions = [generation: generation, budgetUsd: Math.max(0.0d, remaining)]
if (base != null) authorOptions.activeRule = base
try { inv.ruleIndex = engineRuleIndex(inv) } catch (Throwable t) { log.warn "HAI Engine: the rule list for drafting was read without devices (${t.message})" }
ENGINE_DRAFT_INV.put(app.id.toString(), [generation: generation.toString(), inv: inv])
Map session = checkerCall([op: 'draftStart', request: (settings.engineRequest ?: '') as String, inv: inv, opts: engineOpts(authorOptions)])
if (session.checkerError) {
ENGINE_DRAFT_INV.remove(app.id.toString())
state.engineStatus = session.message as String
return
}
engineStoreSession(session)
if (session.status != 'done') engineCountDraft()
if (session.status == 'done') {
engineStoreOutcome(session.outcome)
state.engineStatus = null
return
}
state.engineStatus = 'Drafting. This can take a minute or two; refresh the page to see the result.'
engineSend()
}
void engineResend(Map data) {
String why = authorStaleReason(filed('engineSession'), data, true)
if (why != null) {
haiDebug "HAI Engine: a scheduled resend was dropped because ${why}"
return
}
engineSend()
}
void engineSend() {
Map session = filed('engineSession')
if (session == null || session.status != 'awaitingModel' || session.nextRequest == null) return
Map prep = checkerCall([op: 'draftSend', session: session, now: now()])
if (prep.checkerError) {
engineStoreSession(aFinish(session, 'failed', prep.message as String))
engineStoreOutcome(session.outcome)
state.engineStatus = null
return
}
filedPut('engineSession', prep.session)
if (!prep.send) {
engineStoreOutcome(((Map) prep.session).outcome)
state.engineStatus = null
return
}
Map params = [
uri : AUTHOR_API_URL,
headers : authorHeaders(engineKey(), ((Map) prep.session).budget == null),
contentType       : 'application/json',
requestContentType: 'application/json',
body : groovy.json.JsonOutput.toJson(prep.body),
timeout : prep.timeoutSeconds,
]
asynchttpPost('engineReply', params, prep.tag)
}
void engineReply(resp, data) {
Map session = filed('engineSession')
String why = authorStaleReason(session, data)
if (why != null) {
haiDebug "HAI Engine: a provider reply was ignored because ${why}"
return
}
authorMarkReceived(session)
List history = (session?.messages ?: [])
int status = 0
String body = ''
Map headers = [:]
try {
status = resp.status as int
body = resp.hasError() ? (resp.errorData ?: '') : (resp.data ?: '')
headers = (resp.headers ?: [:])
} catch (Exception e) {
status = 0
body = e.message ?: 'no response'
}
if (status == 0) status = 504
Map drafted = ENGINE_DRAFT_INV.get(app.id.toString())
Map inv = drafted != null && drafted.generation == session?.generation?.toString() ? drafted.inv : engineInventory()
Map read = checkerCall([op: 'draftReply', session: session, inv: inv, status: status, body: body as String, headers: headers, now: now()])
session = read.checkerError ? aFinish(session, 'failed', read.message as String) : read
if (session.status == 'done') ENGINE_DRAFT_INV.remove(app.id.toString())
if (session.status == 'done') {
engineStoreSession(session)
engineStoreOutcome(session.outcome)
state.engineStatus = null
Map amendment = filed('engineAmendment')
if (session.outcome?.proposal && amendment?.generation == state.engineGeneration) {
Map proposal = session.outcome.proposal
Map rule = editorRuleFromHistory(history, proposal.ruleHash as String)
if (rule != null && rule.id == ((Map) session.opts.activeRule)?.id) {
Map rawDiff = (proposal.diff ?: [:])
Map changePaths = [added: ((List) (rawDiff.added ?: [])).collect { ((Map) it).path },
removed: ((List) (rawDiff.removed ?: [])).collect { ((Map) it).path },
changed: ((List) (rawDiff.changed ?: [])).collect { ((Map) it).path }]
filedPut('engineAmendment', [generation: amendment.generation, basedOn: amendment.basedOn,
rule: rule, ruleHash: proposal.ruleHash, ready: proposal.ready,
findings: ((List) (proposal.findings ?: [])).take(12),
changePaths: changePaths])
}
} else if (session.outcome?.proposal) {
Map proposal = session.outcome.proposal
Object spent = ((Map) session.budget)?.spentUsd
state.engineDraftResult = engineRemember(proposal, editorRuleFromHistory(history, proposal.ruleHash as String),
session.outcome.status == 'failed', spent instanceof Number ? (spent as double) : null)
}
haiInfo "HAI Engine: drafting finished, ${session.outcome.status}, ${session.outcome.attempts} attempt(s)"
return
}
state.engineStatus = "Drafting, attempt ${(session.attempts as int) + 1}. Refresh to see the result."
if (session.retryAfterSeconds) {
int wait = session.retryAfterSeconds as int
session.retryAfterSeconds = null
engineStoreSession(session)
runIn(wait, 'engineResend', [data: [generation: session.generation]])
} else {
engineStoreSession(session)
engineSend()
}
}
void engineFoldSpend() {
Map s = filed('engineSession')
if (s?.budget == null || s.budgetFolded) return
engineMonthSpendMeter()
Map folded = authorFoldBudget([spentUsd: state.engineSpentUsd, stop: state.engineSpendStop], s.budget, now())
state.engineSpentUsd = folded.spentUsd
state.engineSpendStop = folded.stop
s.budgetFolded = true
filedPut('engineSession', s)
}
void engineTestApiKey() {
String key = engineKey()
if (key == null || key.trim().isEmpty()) {
state.engineKeyTest = [ok: false, at: now(), fault: 'key', message: 'No Claude API key is set. Put one in the box above and press Done first.']
return
}
if (state.engineSpendStop) {
state.engineKeyTest = [ok: false, at: now(), fault: 'spending',
message: 'Drafting is stopped on spending, so nothing is sent. Clear the stop first.']
return
}
Map body = [model: AUTHOR_MODEL, max_tokens: 1, messages: [[role: 'user', content: 'hi']]]
int code = -1
Map parsed = [:]
try {
httpPost([
uri : AUTHOR_API_URL,
headers : authorHeaders(key, false),
contentType       : 'application/json',
requestContentType: 'application/json',
body : groovy.json.JsonOutput.toJson(body),
timeout : 20,
]) { resp ->
code = resp.status as int
parsed = engineKeyTestBody(resp.data)
}
} catch (Exception e) {
boolean answered = false
try {
if (e.response != null) {
code = e.response.status as int
parsed = engineKeyTestBody(e.response.data)
answered = true
}
} catch (Exception ignored) { answered = false }
if (!answered) {
state.engineKeyTest = [ok: false, at: now(), fault: 'network',
message: "The request could not be sent: ${e.message}".toString()]
return
}
}
state.engineKeyTest = engineKeyTestVerdict(code, parsed)
}
Map engineKeyTestBody(Object data) {
if (data instanceof Map) return data
try { return new groovy.json.JsonSlurper().parseText(data as String ?: '{}') }
catch (Exception ignored) { return [:] }
}
Map engineKeyTestVerdict(int code, Map parsed) {
Map out = [at: now()]
if (code == 200) {
Map usage = (parsed.usage ?: [:])
double cost = 0.0d
Map price = AUTHOR_PRICES.get(parsed.model)
if (price != null && usage.input_tokens instanceof Number && usage.output_tokens instanceof Number) {
cost = (usage.input_tokens as long) * (price.input as double) / 1e6d +
(usage.output_tokens as long) * (price.output as double) / 1e6d
}
Map meter = engineMonthSpendMeter()
meter.testUsd = ((meter.testUsd ?: 0.0d) as double) + cost
state.engineMonthSpendMeter = meter
state.engineSpentUsd = ((state.engineSpentUsd ?: 0.0d) as double) + cost
out.putAll([ok: true, model: parsed.model as String, costUsd: cost])
} else if (code == 401 || code == 403) {
out.putAll([ok: false, fault: 'key', message: 'The provider refused the key. Check the Claude API key above; the rest of the settings are fine.'])
} else if (code == 404 || (code == 400 && ((parsed.error as Map)?.message as String)?.toLowerCase()?.contains('model'))) {
out.putAll([ok: false, fault: 'model', message: "The key works, but this build asks for ${AUTHOR_MODEL}, which this account cannot use.".toString()])
} else if (code == 429) {
out.putAll([ok: false, fault: 'provider', message: 'The key works. The provider is rate limiting right now, so try again in a moment.'])
} else {
String why = ((parsed.error as Map)?.message as String) ?: "HTTP ${code}".toString()
out.putAll([ok: false, fault: 'provider', message: "The provider answered ${code}: ${why}".toString()])
}
return out
}
String engineKeyTestLine() {
Map t = state.engineKeyTest
if (t == null) return ''
if (t.pending) return '<small>Testing the key...  Press Done and reopen this page to see the answer.</small>'
String when = runtimeClock().call(t.at as long)
if (t.ok) {
return "<b>The key works.</b> ${esc(t.model as String)} answered ${esc(when)}, and this test cost US\$${String.format('%.5f', (t.costUsd ?: 0.0d) as double)}, counted against your limit like any other call.".toString()
}
return "<b>Not working.</b> ${esc(t.message as String)} <small>(${esc(when)})</small>".toString()
}
void engineClearSpendStop() {
String refused = authorSpendStopClearRefusal((Map) state.engineSpendStop, settings.engineSpendLimitUsd,
settings.engineSpendLimitConfirmUsd)
app.removeSetting('engineSpendLimitConfirmUsd')
if (refused != null) {
state.engineStatus = "The spending stop was not cleared: ${refused}.".toString()
return
}
haiInfo "HAI Engine: spending stop cleared (${state.engineSpendStop.reason})"
state.engineSpendStop = null
state.engineStatus = 'The spending stop was cleared. The amount already counted still applies.'
}
double engineSpendNow() {
double current = (((Map) (engineSessionMark() ?: [:])).committedUsd ?: 0.0d) as double
return ((state.engineSpentUsd ?: 0.0d) as double) + current
}
@Field static final List ENGINE_FILED = ['editorDrafts', 'proposals', 'proposalRules', 'engineSession', 'engineOutcome', 'engineAmendment']
@Field static final java.util.concurrent.ConcurrentHashMap ENGINE_FILED_TEXT = new java.util.concurrent.ConcurrentHashMap()
String engineFiledFile(String name) { return "hai-engine-${app.id}-${name}.json".toString() }
String filedText(String name) {
String key = "${app.id}:${name}".toString()
String text = (String) ENGINE_FILED_TEXT.get(key)
if (text == null) {
byte[] b = null
try { b = downloadHubFile(engineFiledFile(name)) } catch (Exception e) { b = null }
text = b == null || b.length == 0 ? '' : new String(b, 'UTF-8')
ENGINE_FILED_TEXT.put(key, text)
}
return text.isEmpty() ? null : text
}
Object filed(String name) {
String text = filedText(name)
if (text == null) {
Object old = state[name]
if (old != null) runIn(2, 'haiFiledMove', [overwrite: true])
return old
}
if (state[name] != null) runIn(2, 'haiFiledMove', [overwrite: true])
if (text == 'null') return null
try { return new groovy.json.JsonSlurper().parseText(text) }
catch (Exception e) { log.warn "HAI Engine: ${engineFiledFile(name)} could not be read (${e.message}); treated as empty"; return null }
}
void filedMark(Object value) {
state.engineSessionMark = engineMarkOf(value)
}
Map engineMarkOf(Object value) {
Map v = value instanceof Map ? value : null
boolean open = v?.budget != null && !v.budgetFolded
return v == null ? null : [generation: v.generation, status: v.status,
committedUsd: open ? authorCommitted((Map) v.budget) : 0.0d,
stopped: open ? ((Map) v.budget).stopped : null]
}
Map engineSessionMark() {
Map m = state.engineSessionMark
return m != null ? m : engineMarkOf(filed('engineSession'))
}
void filedPut(String name, Object value) {
String key = "${app.id}:${name}".toString()
String text = groovy.json.JsonOutput.toJson(value)
if (name == 'engineSession') filedMark(value)
if (text == ENGINE_FILED_TEXT.get(key)) return
try {
uploadHubFile(engineFiledFile(name), text.getBytes('UTF-8'))
ENGINE_FILED_TEXT.put(key, text)
} catch (Exception e) {
log.warn "HAI Engine: ${engineFiledFile(name)} not written (${e.message})"
ENGINE_FILED_TEXT.put(key, text)
}
if (state[name] != null) runIn(2, 'haiFiledMove', [overwrite: true])
}
def haiFiledMove() {
ENGINE_FILED.each { String name ->
Object old = state[name]
if (old == null) return
if (filedText(name) == null) {
try {
String text = groovy.json.JsonOutput.toJson(old)
uploadHubFile(engineFiledFile(name), text.getBytes('UTF-8'))
ENGINE_FILED_TEXT.put("${app.id}:${name}".toString(), text)
} catch (Exception e) {
log.warn "HAI Engine: ${engineFiledFile(name)} not written (${e.message}); left in state for the next try"
return
}
}
state.remove(name)
if (name == 'engineSession' && state.engineSessionMark == null) filedMark(filed('engineSession'))
}
}
void engineStoreSession(Map session) {
Map b = checkerCall([op: 'bound', session: session])
filedPut('engineSession', b.checkerError ? session : b.session)
}
void engineStoreOutcome(Map outcome) {
Map b = checkerCall([op: 'bound', outcome: outcome])
filedPut('engineOutcome', b.checkerError ? outcome : b.outcome)
}
Map engineRemember(Map proposal, Map rule = null, boolean needsFixing = false, Double costUsd = null) {
List kept = (filed('proposals') ?: [])
kept.removeAll { it.evidenceId == proposal.evidenceId }
kept.add(0, [evidenceId: proposal.evidenceId, ruleHash: proposal.ruleHash, ready: proposal.ready, at: now(),
name: (proposal.rendering ?: [''])[0], source: proposal.inputs?.source])
List proposals = kept.take(ENGINE_MAX_PROPOSALS)
filedPut('proposals', proposals)
Set listed = proposals.collect { it.evidenceId } as Set
List rules = editorKeepProposalRule((List) (filed('proposalRules') ?: []), proposal, rule, now())
List keptRules = rules.findAll { listed.contains(it.evidenceId) }
filedPut('proposalRules', keptRules)
if (!keptRules.any { it.evidenceId == proposal.evidenceId }) {
return [error: 'The AI returned a rule, but HAI could not keep an exact copy. No draft was saved.']
}
Map store = [drafts: filed('editorDrafts') ?: [], draftSeq: state.editorDraftSeq ?: 0,
proposals: proposals, proposalRules: keptRules, outcome: filed('engineOutcome')]
Map saved
try {
Map body = [name: rule.name ?: 'AI draft', proposalId: proposal.evidenceId, rule: rule]
if (needsFixing) body.needsFixing = true
if (costUsd != null) body.aiDraftUsd = Math.round(costUsd * 10000d) / 10000d
saved = editorSave(groovy.json.JsonOutput.toJson(body), store, now())
} catch (Exception ex) {
log.warn "HAI Engine: AI draft could not be saved: ${ex.message}"
return [error: 'HAI could not save the AI draft. No draft was saved.']
}
if (saved.status != 200) return [error: ((Map) saved.body).message ?: 'HAI could not save the AI draft.']
filedPut('editorDrafts', ((Map) saved.store).drafts)
state.editorDraftSeq = ((Map) saved.store).draftSeq
return [draftId: ((Map) ((Map) saved.body).draft).id, draftAt: now()]
}
Map engineMonthSpendMeter() {
long at = now()
String month = new Date(at).format('yyyy-MM', location.timeZone ?: TimeZone.getTimeZone('UTC'))
Map meter = state.engineMonthSpendMeter
if (meter?.month != month) {
meter = [month: month, baselineUsd: ((state.engineSpentUsd ?: 0.0d) as double),
startedAt: at, partial: meter == null]
} else if (meter.drafts != null) {
return meter
}
meter.drafts = 0
meter.draftBaselineUsd = engineSpendNow()
meter.draftsSince = at
meter.draftsPartial = meter.startedAt != at || meter.partial
meter.testUsd = 0.0d
state.engineMonthSpendMeter = meter
return meter
}
void engineCountDraft() {
Map meter = engineMonthSpendMeter()
meter.drafts = ((meter.drafts ?: 0) as int) + 1
state.engineMonthSpendMeter = meter
}
String engineDraftAverageText() {
Map meter = engineMonthSpendMeter()
int drafts = (meter.drafts ?: 0) as int
TimeZone tz = location.timeZone ?: TimeZone.getTimeZone('UTC')
String period = meter.draftsPartial ? "Since ${new Date(meter.draftsSince as long).format('d MMM', tz)}".toString() : 'This month'
if (drafts == 0) return "${period}: no AI drafts yet.".toString()
double usd = Math.max(0.0d, engineSpendNow() - (meter.draftBaselineUsd as double) - ((meter.testUsd ?: 0.0d) as double))
return "${period}: ${drafts} AI draft${drafts == 1 ? '' : 's'}, US\$${String.format('%.2f', usd)} in all, an average of US\$${String.format('%.2f', usd / drafts)} per draft.".toString()
}
String engineMonthSpendText() {
Map meter = engineMonthSpendMeter()
double monthUsd = Math.max(0.0d, engineSpendNow() - (meter.baselineUsd as double))
String period = meter.partial ? "Tracked this month since ${new Date(meter.startedAt as long).format('d MMM yyyy', location.timeZone ?: TimeZone.getTimeZone('UTC'))}".toString()
: 'Month to date'
String limit = settings.engineSpendLimitUsd == null ? 'no overall limit set' : "overall limit US\$${String.format('%.2f', settings.engineSpendLimitUsd as double)}".toString()
return "${period}: US\$${String.format('%.2f', monthUsd)} (reservations included). Lifetime counted US\$${String.format('%.2f', engineSpendNow())}; ${limit}.".toString()
}
String esc(Object s) {
return (s == null ? '' : s.toString()).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}
Object mcpOff() {
return render(status: 403, contentType: 'application/json', headers: ['Cache-Control': 'no-store'],
data: groovy.json.JsonOutput.toJson([ok: false, error: 'MCP_OFF', message: 'The MCP endpoint is turned off on the HAI Engine page.']))
}
def mcpGet() {
if (!haiAudienceOk('/mcp')) return haiRefuse('/mcp')
if (settings.haiMcpOn != true) return mcpOff()
if (haiViaCloud() && settings.haiMcpCloud != true) return haiCloudOff('MCP')
render(status: 405, contentType: 'text/plain', data: 'This server does not offer a server-to-client stream.')
}
def mcpPost() {
if (!haiAudienceOk('/mcp')) return haiRefuse('/mcp')
if (settings.haiMcpOn != true) return mcpOff()
if (haiViaCloud() && settings.haiMcpCloud != true) return haiCloudOff('MCP')
String raw = haiBody()
if (raw.length() > ENGINE_MAX_MCP_BODY_CHARS) {
return mcpRender(null, null, mcpError(-32600, 'Invalid Request', "the body is larger than ${ENGINE_MAX_MCP_BODY_CHARS} characters".toString()))
}
Object value
try {
value = new groovy.json.JsonSlurper().parseText(raw)
} catch (Exception e) {
return mcpRender(null, null, mcpError(-32700, 'Parse error', 'the body is not JSON'))
}
if (!(value instanceof Map)) {
return mcpRender(null, null, mcpError(-32600, 'Invalid Request', 'batches are not supported; send one JSON-RPC object'))
}
Map req = value
Object id = req.id
String method = req.method instanceof String ? (String) req.method : null
boolean idOk = id == null || id instanceof String || id instanceof Integer || id instanceof Long ||
id instanceof BigInteger || (id instanceof BigDecimal && ((BigDecimal) id).scale() <= 0)
if (req.jsonrpc != '2.0' || method == null || !idOk || (req.params != null && !(req.params instanceof Map))) {
return mcpRender(idOk ? id : null, null, mcpError(-32600, 'Invalid Request', 'expected a JSON-RPC 2.0 object with a method, an id and object params'))
}
if (id == null) {
return render(status: 202, contentType: 'application/json', data: '')
}
Map params = (req.params ?: [:])
try {
switch (method) {
case 'initialize':
String asked = params.protocolVersion instanceof String ? (String) params.protocolVersion : null
String agreed = MCP_PROTOCOL_VERSIONS.contains(asked) ? asked : MCP_PROTOCOL_VERSIONS[0]
return mcpRender(id, [
protocolVersion: agreed,
capabilities : [tools: [listChanged: false]],
serverInfo : [name: 'hai-engine', version: ENGINE_APP_VERSION],
instructions   : "Draft only. Use hai_describe to learn the rule format and the selected devices, draft a hai.rule/1 rule with a full requirement list, then call hai_propose_rule with the person's original request. The hub validates the rule, reads it back in plain English and simulates it. ${ENGINE_EFFECTS} Tool annotations describe behaviour; they are not access control.".toString(),
], null)
case 'ping':
return mcpRender(id, [:], null)
case 'tools/list':
return mcpRender(id, [tools: mcpTools()], null)
case 'tools/call':
if (!(params.name instanceof String)) return mcpRender(id, null, mcpError(-32602, 'Invalid params', 'name must be a string'))
if (params.arguments != null && !(params.arguments instanceof Map)) {
return mcpRender(id, null, mcpError(-32602, 'Invalid params', 'arguments must be an object'))
}
return mcpRender(id, mcpCall((String) params.name, (params.arguments ?: [:])), null)
default:
return mcpRender(id, null, mcpError(-32601, 'Method not found', method))
}
} catch (Exception e) {
log.warn "HAI Engine MCP: ${method} failed: ${e.message}"
return mcpRender(id, null, mcpError(-32603, 'Internal error', null))
}
}
Map mcpError(int code, String message, String detail) {
Map e = [code: code, message: message]
if (detail) e.data = [detail: detail.take(512)]
return e
}
def mcpRender(Object id, Object result, Map error) {
Map body = [jsonrpc: '2.0', id: id]
if (error) body.error = error else body.result = result
render(status: 200, contentType: 'application/json', data: groovy.json.JsonOutput.toJson(body))
}
Map mcpReadOnly() {
return [readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false]
}
List mcpTools() {
Map ruleArg = [type: 'object', description: 'A hai.rule/1 document.']
Map reqArg = [type: 'array', description: 'Every requirement taken from the request, each with id, text, status (satisfied, unsupported or unresolved), where (exact document paths such as triggers.0 or actions.2) and note.',
items: [type: 'object']]
Map requestArg = [type: 'string', description: "The person's original request, word for word."]
return [
[name: 'hai_describe', title: 'Describe the rule format and devices',
description: 'The hai.rule/1 authoring guide, the supported commands, and the selected devices, modes, hub variables and other rules on the hub.',
inputSchema: [type: 'object', properties: [request: [type: 'string', description: 'Optional request text, used to put the most relevant devices first.']]],
annotations: mcpReadOnly()],
[name: 'hai_propose_rule', title: 'Check a drafted rule',
description: "Validates a drafted rule against this hub, checks that the requirement citations are exact and cover every rule element, renders the hub's own plain-English read-back and simulates it. Returns evidence with an evidence id, which is not an approval. ${ENGINE_EFFECTS} A proposal summary is kept on the hub.".toString(),
inputSchema: [type: 'object', properties: [request: requestArg, rule: ruleArg, requirements: reqArg],
required: ['request', 'rule', 'requirements']],
annotations: [readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false]],
[name: 'hai_get_rule', title: 'Read a rule on this hub',
description: "A rule's definition (hai.rule/1), read-back and state on the hub, and whether it is the version the hub runs. To change it, propose a new version with the same id.",
inputSchema: [type: 'object', properties: [ruleId: [type: 'string', description: 'The id of the rule, as listed by hai_describe.']], required: ['ruleId']],
annotations: mcpReadOnly()],
[name: 'hai_render_rule', title: 'Read a rule back in plain English',
description: "The hub's plain-English read-back of a rule, with its findings.",
inputSchema: [type: 'object', properties: [rule: ruleArg], required: ['rule']],
annotations: mcpReadOnly()],
]
}
Map mcpCall(String name, Map args) {
if (args.rule != null) {
Map strict = jsonParse(groovy.json.JsonOutput.toJson(args.rule))
if (!strict.ok) {
return [content: [[type: 'text', text: "rule: ${strict.code}: ${strict.message}".toString()]], isError: true]
}
args = args + [rule: strict.value]
}
Map inv = name == 'hai_describe' ? engineInventory()
: (name in ['hai_render_rule', 'hai_propose_rule'] && args.rule instanceof Map) ? engineInventory(editorDocDeviceIds(args.rule), args.rule)
: null
Map out
switch (name) {
case 'hai_get_rule':
out = editorGetRule(args.ruleId, (filed('editorDrafts') ?: []), state.runtimeSummary)
break
case 'hai_describe':
out = checkerCall([op: 'mcp', name: name, args: args, inv: inv])
break
case 'hai_propose_rule':
case 'hai_render_rule':
if (!(args.rule instanceof Map)) {
out = [error: 'rule must be a hai.rule/1 object']
break
}
if (name == 'hai_propose_rule' && (!(args.request instanceof String) || ((String) args.request).trim().isEmpty())) {
out = [error: "request must be the person's original words"]
break
}
out = checkerCall([op: 'mcp', name: name, args: args, inv: inv,
opts: name == 'hai_propose_rule' ? engineOpts([request: args.request, source: 'mcp']) : null])
if (name == 'hai_propose_rule' && !out.checkerError) engineRemember(out, args.rule)
break
default:
return [content: [[type: 'text', text: "Unknown tool: ${name.take(64)}".toString()]], isError: true]
}
if (out.checkerError) out = [error: out.message as String]
String text = groovy.json.JsonOutput.toJson(out)
return [content: [[type: 'text', text: text]], structuredContent: out, isError: out.containsKey('error')]
}
String haiAudienceSecret(String audience) {
Map keys = (state.haiAudienceKeys ?: [:])
if (!keys.containsKey(audience)) {
keys.put(audience, UUID.randomUUID().toString().replace('-', ''))
state.haiAudienceKeys = keys
}
return keys.get(audience) as String
}
void haiAudiencesInit() {
HAI_ROUTE_AUDIENCE.values().toList().unique().each { Object a -> haiAudienceSecret(a as String) }
if (!(state.haiApiToken instanceof String) || !((String) state.haiApiToken ==~ /[a-f0-9]{32}/) || state.haiApiSigned != true) {
state.haiApiToken = rtNewApiToken()
state.haiApiSigned = true
}
}
void haiRotateAccess() {
String before = state.accessToken as String
try {
state.remove('accessToken')
createAccessToken()
} catch (Exception e) {
if (before) state.accessToken = before
state.haiNotice = "HAI's addresses were not changed: ${e.message}".toString()
return
}
state.haiAudienceKeys = [:]
state.remove('haiApiToken')
haiAudiencesInit()
HAI_API_REFUSED.remove(app.id as String)
state.haiMigrationEpoch = ((state.haiMigrationEpoch ?: 0) as int) + 1
state.remove('haiMigrationLink')
boolean migration = haiMigrationChild() != null
if (migration) sendLocationEvent(name: MIGRATION_ROTATE_EVENT, value: 'rotate', isStateChange: true, displayed: false)
haiRuleKeyNew()
rtPushAllow(runtimePlat())
runtimeRefreshEditorLinks()
rtLog(runtimePlat(), [what: 'accessRotated'])
haiInfo 'HAI Engine: the access addresses and the API token were changed. Old links and the old API token stop working within a minute.'
state.haiNotice = "HAI's addresses changed. Open the editor from this page again, and give other apps the new API token. " +
(migration ? 'HAI Migration changes its own address within a few seconds. ' : '')
}
boolean haiAudienceOk(String route) {
String want = HAI_ROUTE_AUDIENCE.get(route) as String
if (want == null) return false
if (want == 'feed') return true
String given = null
try { given = params?.aud as String } catch (Exception e) { given = null }
if (given == null) given = haiRequestHeader('X-HAI-Aud')
return jsonSameSecret(haiAudienceSecret(want), given)
}
String haiRequestHeader(String name) {
Object hs = null
try { hs = request?.headers } catch (Exception e) { hs = null }
if (!(hs instanceof Map)) return null
Object v = ((Map) hs).find { k, x -> k != null && k.toString().equalsIgnoreCase(name) }?.value
if (v instanceof List) v = ((List) v).isEmpty() ? null : ((List) v)[0]
return v == null ? null : v.toString()
}
String haiBody() {
Object b = null
try { b = request?.body } catch (Exception e) { b = null }
String t = b instanceof byte[] ? new String((byte[]) b, 'UTF-8') : b?.toString()
return t == null || t.trim().isEmpty() ? '{}' : t
}
Map haiAudienceRefusal(String route) {
return [status: 403, body: [ok: false, error: 'WRONG_AUDIENCE',
message: "This address is for the '${HAI_ROUTE_AUDIENCE.get(route)}' credential.".toString()]]
}
Object haiRefuse(String route) {
Map r = haiAudienceRefusal(route)
return render(status: r.status as int, contentType: 'application/json',
data: groovy.json.JsonOutput.toJson(r.body), headers: ['Cache-Control': 'no-store'])
}
def editorPageGet() {
if (!haiAudienceOk('/editor')) return haiRefuse('/editor')
haiCodeCheck()
String page = editorPage()
if (haiTooBigForCloud(page)) return haiCloudTooBig(page, true)
boolean current = (params?.v as String) == EDITOR_PAGE_SHA256.take(12) && EDITOR_FILE_CACHE.containsKey('page:' + EDITOR_PAGE_SHA256)
String tag = "\"${EDITOR_PAGE_SHA256.take(16)}\"".toString()
if (current && haiRequestHeader('If-None-Match') == tag) {
return render(status: 304, contentType: 'text/html', data: '', headers: ['Cache-Control': 'private, no-cache', 'ETag': tag])
}
return render(status: 200, contentType: 'text/html', data: page,
headers: current ? ['Cache-Control': 'private, no-cache', 'ETag': tag] : ['Cache-Control': 'no-store, must-revalidate'])
}
@Field static final String HAI_REPORT_URL = 'https://github.com/GordonThelander/HAI-Engine/issues/new/choose'
@Field static final String MIGRATION_CHILD_NAME = 'HAI Migration (Preview)'
@Field static final int MIGRATION_CONTRACT = 2
String haiMigrationPageUrl() {
def m = haiMigrationChild()
if (m == null) return null
String page = state.haiMigrationLink instanceof String ? (String) state.haiMigrationLink : null
if (page == null || !page.startsWith("/apps/api/${m.id}/".toString())) return "/installedapp/configure/${m.id}".toString()
String editor = haiEditorAddress()
return editor == null ? page : "${page}#editor=${URLEncoder.encode(editor, 'UTF-8')}".toString()
}
Map haiMigrationAccess(String link) {
if (link instanceof String && link != state.haiMigrationLink) state.haiMigrationLink = link
return [epoch: (state.haiMigrationEpoch ?: 0) as int, key: haiRuleKey()]
}
@Field static final String MIGRATION_ROTATE_EVENT = 'haiMigrationRotate'
Map haiShadowLights() {
try {
def m = haiMigrationChild()
if (m == null) return null
Map rules = (rtRegistry(runtimePlat()).rules ?: [:])
Map live = [:]
List gone = []
Map migrated = haiMigratedIds()
((Map) (m.haiShadowLights() ?: [:])).each { k, v ->
Map e = rules.get(k as String)
if (e != null && e.state != 'removed' && migrated.get(k as String) != 'switched') live.put(k, v) else gone << (k as String)
}
if (gone) m.haiShadowForget(gone)
return live
} catch (Exception e) { return null }
}
def haiShadowRelight() { runIn(15, 'haiShadowRelightNow', [overwrite: true]) }
def haiShadowRelightNow() {
Map rules = (rtRegistry(runtimePlat()).rules ?: [:])
haiMigratedIds().keySet().each { Object rid ->
Map e = rules.get(rid as String)
if (e == null || e.state == 'removed') return
try { runtimeChildCall(e.childId, 'haiShadowTell', []) }
catch (Exception ex) { log.debug "HAI Engine: ${rid} did not say its light again (${ex.message})" }
}
}
Map haiMigratedIds() {
Map out = [:]
((Map) (state.migPairs ?: [:])).values().each { Object v ->
if (!(v instanceof Map) || ((Map) v).haiRuleId == null) return
out.put(((Map) v).haiRuleId as String, ((Map) v).phase == 'switched' ? 'switched' : 'cloned')
}
return out
}
def haiMigrationChild() {
return getChildApps()?.find { it.name == MIGRATION_CHILD_NAME }
}
@Field static final String CHECKER_CHILD_NAME = 'HAI Checker (Preview)'
def checkerChild(boolean fresh = false) {
def c = null
if (!fresh && state.haiCheckerId != null) {
try { c = getChildAppById(state.haiCheckerId as Long) } catch (Exception e) { c = null }
if (c != null && c.name != CHECKER_CHILD_NAME) c = null
}
if (c == null) {
c = getChildApps()?.find { it.name == CHECKER_CHILD_NAME }
if (c == null) c = addChildApp('gordonthelander', CHECKER_CHILD_NAME, 'HAI Checker')
state.haiCheckerId = c.id
}
return c
}
Map checkerCall(Map req) {
Map ask = new LinkedHashMap(req)
ask.build = CHECKER_BUILD
Map out = null
String failed = null
for (int attempt = 0; attempt < 2 && out == null; attempt++) {
def kid
try { kid = checkerChild(attempt > 0) }
catch (Exception e) {
boolean notYet = false
try { notYet = app.getInstallationState() != 'COMPLETE' } catch (Exception x) { notYet = false }
String why = notYet ? 'Press Done at the bottom of the HAI Engine page to finish installing, then try again.'
: 'HAI Checker is not on this hub. Update HAI with Hubitat Package Manager, or add the HAI Checker app code under Apps Code, then try again.'
log.warn "HAI Engine: HAI Checker could not be added (${e.message})"
return [checkerError: 'CHECKER_MISSING', message: why]
}
try { out = kid.checkerRun(ask) }
catch (Exception e) { failed = e.message; out = null }
}
if (out == null) {
log.warn "HAI Engine: HAI Checker did not answer ${req.op} (${failed ?: 'no answer'})"
return [checkerError: 'CHECKER_FAILED', message: 'HAI Checker, the part of HAI Engine that checks rules, could not answer. Update HAI so HAI Engine and HAI Checker match, then try again.']
}
if (out.checkerError == 'CHECKER_MISMATCH') {
log.warn "HAI Engine: HAI Checker is build ${out.build ?: 'unknown'} and this HAI Engine expects ${CHECKER_BUILD}. Deploy HAI Checker and HAI Engine from the same commit (checker first)."
return [checkerError: 'CHECKER_MISMATCH',
message: 'HAI Engine and HAI Checker are from different versions. Update HAI so both are the same version, then try again.']
}
return out
}
String haiRuleKey() {
if (!(state.haiRuleKey instanceof String)) haiRuleKeyNew()
return state.haiRuleKey as String
}
void haiRuleKeyNew() {
state.haiRuleKey = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace('-', '')
checkerCall([op: 'listen', key: state.haiRuleKey])
}
String haiSignedData(String name, Object value, Map payload) {
return rtSignedData(haiRuleKey(), name, value, payload, now(), UUID.randomUUID().toString())
}
Map haiMigrationFacts(Boolean pairsOnly = false) {
if (!pairsOnly && state.haiCodeVersion != ENGINE_APP_VERSION) runIn(1, 'haiCodeCheckSoon', [overwrite: true])
if (pairsOnly) return [contract: MIGRATION_CONTRACT, pairs: haiMigrationPairs()]
Map reg = (rtRegistry(runtimePlat()).rules ?: [:])
Map lastRuns = [:]
((List) (((Map) (state.runtimeSummary ?: [:])).rules ?: [])).each { Object o ->
Map r = o
if (r.lastRunAt != null) lastRuns.put(r.id as String, [lastRunAt: r.lastRunAt, lastRunProblem: r.lastRunProblem])
}
Map haiRules = [:]
reg.each { Object k, Object v ->
Map e = v
haiRules.put(k as String, [name: e.name as String, state: e.state as String, childId: e.childId as String] +
((Map) (lastRuns.get(k as String) ?: [:])))
}
rtCandidates(runtimePlat()).each { Object co ->
Map c = co
Map have = haiRules.get(c.ruleId as String)
if (have == null || have.state == 'removed') haiRules.put(c.ruleId as String, [name: c.name as String, state: 'held'])
}
List allowed = null
try { allowed = runtimeAllowList() } catch (Throwable t) { allowed = null }
List disabledIds = []
try {
disabledIds = ((settings.runtimeAllowDevices ?: []) as List).findAll { d -> d != null && d.isDisabled() == true }.collect { it.id.toString() }
} catch (Throwable t) { disabledIds = null }
Map cache = (state.runtimeRmPaused ?: [:])
Map listed = haiAppsPaused()
try { haiMigrationPairsRestore(listed) } catch (Exception e) { log.warn "HAI Engine: migration pairs not restored from a backup: ${e.message}" }
List paused = listed.ok == true ? listed.paused
: cache.findAll { k, v -> v == true }.keySet().collect { it.toString() }
List checked = listed.ok == true ? listed.checked : cache.keySet().collect { it.toString() }
List stopped = listed.ok == true ? listed.stopped : []
List disabled = listed.ok == true ? listed.disabled : []
List drafted = []
Map draftWhy = [:]
try {
((List) (filed('editorDrafts') ?: [])).each { Object o ->
Map d = o
String id = d.rule instanceof Map ? (((Map) d.rule).id as String) : null
if (id == null || !id.startsWith('rm:')) return
String k = id.substring(3)
if (!drafted.contains(k)) drafted << k
if (d.fixReason instanceof String) draftWhy.put(k, d.fixReason)
}
} catch (Throwable t) { drafted = []; draftWhy = [:] }
return [contract: MIGRATION_CONTRACT, pairs: haiMigrationPairs(), paused: paused, checked: checked, stopped: stopped, drafted: drafted, draftWhy: draftWhy, environment: HAI_ENVIRONMENT,
disabled: disabled, appsComplete: listed.ok == true,
pausedWhy: listed.ok == true ? null : listed.why, haiRules: haiRules, allowed: allowed,
limits: [writable: runtimeIdList(settings.runtimeWritableVariables), modeAllowed: settings.runtimeAllowSetMode == true,
hubPowerAllowed: settings.runtimeAllowHubPower != false, hubAddresses: haiHubAddresses(), disabled: disabledIds]]
}
List haiHubAddresses() {
String ip = null
try { ip = location.hub?.localIP as String } catch (Exception e) { ip = null }
return ip ? [ip] : []
}
String haiEditorAddress() {
return state.accessToken ? "/apps/api/${app.id}/editor?access_token=${state.accessToken}&aud=${haiAudienceSecret('editor')}&v=${EDITOR_PAGE_SHA256.take(12)}".toString() : null
}
String haiEditorPageLink() { return haiEditorAddress() }
Map haiAppsPaused() {
Map had = HAI_APPS_SEEN.get(app.id as String)
if (had != null && now() - (had.at as long) < HAI_APPS_FRESH_MS) return had.read
Object data = null
try {
httpGet([uri: 'http://127.0.0.1:8080/hub2/appsList', contentType: 'application/json', timeout: 20]) { resp ->
if (resp?.status == 200) data = resp.data
}
} catch (Exception e) {
return [ok: false, why: "the hub's app list could not be read (${e.message})".toString()]
}
Map read = haiAppsPausedFrom(data)
if (read.ok == true) HAI_APPS_SEEN.put(app.id as String, [at: now(), read: read])
return read
}
@Field static final java.util.concurrent.ConcurrentHashMap HAI_APPS_SEEN = new java.util.concurrent.ConcurrentHashMap()
@Field static final long HAI_APPS_FRESH_MS = 30000L
Map haiAppsPausedFrom(Object data) {
if (!(data instanceof Map) || !(((Map) data).apps instanceof List)) return [ok: false, why: "the hub's app list came back empty or in an unexpected shape"]
List paused = []
List stopped = []
List disabled = []
List checked = []
List pending = new ArrayList((List) ((Map) data).apps)
while (!pending.isEmpty()) {
Object n = pending.remove(pending.size() - 1)
if (!(n instanceof Map)) continue
Map node = n
Map d = node.data instanceof Map ? node.data : [:]
Object id = d.id ?: node.id
if (id != null) {
String sid = id.toString()
checked << sid
String shown = ((d.name ?: '') as String) + ' ' + ((d.label ?: '') as String)
if (d.disabled == true) disabled << sid
else if (shown =~ />\s*\(Paused\)\s*<\/span>/) paused << sid
else if (shown =~ />\s*\(Stopped\)\s*<\/span>/) stopped << sid
}
if (node.children instanceof List) pending.addAll((List) node.children)
}
if (!checked.contains(app.id.toString())) return [ok: false, why: "the hub's app list did not include HAI itself, so it was incomplete"]
return [ok: true, paused: paused, stopped: stopped, disabled: disabled, checked: checked]
}
Map haiMigrationPairs() {
Map pairs = (state.migPairs ?: [:])
List gone = (state.runtimeRmGone ?: [])
if (gone.isEmpty() || pairs.isEmpty()) return pairs
Map pp = runtimePlat()
Map keep = pairs.findAll { k, v ->
Map p = v
if (!gone.contains((p.rmId ?: k) as String)) return true
Map e = p.haiRuleId == null ? null : rtEntry(pp, p.haiRuleId as String)
return e != null && e.state != 'removed'
}
if (keep.size() != pairs.size()) { state.migPairs = keep; haiAnnounceSwitched() }
return keep
}
void haiMigrationForget(String ruleId) {
Map pairs = (state.migPairs ?: [:])
Map keep = pairs.findAll { k, v -> ((Map) v).haiRuleId != ruleId || ((Map) v).phase == 'switched' }
if (keep.size() != pairs.size()) { state.migPairs = keep; haiAnnounceSwitched() }
}
void haiAutomationMapSeen(Object seen) { state.haiAmSeen = seen == true }
void haiMigrationRecord(Map pairs, String rmId = null, Boolean rmPaused = null) {
HAI_APPS_SEEN.remove(app.id as String)
if (pairs != null) { state.migPairs = pairs; haiAnnounceSwitched() }
if (pairs != null && state.migPairsRestored != true) state.migPairsRestored = true
if (rmId != null && rmPaused != null) {
Map seen = new LinkedHashMap((Map) (state.runtimeRmPaused ?: [:]))
seen.put(rmId, rmPaused)
state.runtimeRmPaused = seen
}
}
Map haiSwitchedPairs() {
Map out = [:]
((Map) (state.migPairs ?: [:])).each { Object k, Object v ->
Map p = v
if (p?.phase == 'switched' && p.haiRuleId != null) out.put(((p.rmId ?: k) as String), p.haiRuleId as String)
}
return out
}
void haiAnnounceSwitched() {
Map now = haiSwitchedPairs()
Map sent = state.migSwitchedSent
if (sent?.build == ENGINE_APP_VERSION && sent?.pairs == now) return
state.migSwitchedSent = [build: ENGINE_APP_VERSION, pairs: now]
if (now.isEmpty() && sent == null) return
sendLocationEvent(name: RT_SWITCHED_EVENT, value: 'switched', isStateChange: true, displayed: false,
data: haiSignedData(RT_SWITCHED_EVENT, 'switched', [pairs: now]),
descriptionText: "HAI: ${now.size()} Rule Machine rule${now.size() == 1 ? '' : 's'} switched over".toString())
}
List haiMigrationRenames(Map doc) {
List out = []
Map refs = (doc?.refs ?: [:])
Set seen = [] as Set
List cmds = []
haiCollectCommands(doc?.actions, cmds)
cmds.each { Map a ->
((a.ref != null ? [a.ref] : []) + (a.refs instanceof List ? a.refs : [])).each { Object key ->
Map r = refs.get(key)
if (r?.kind != 'device' || !(a.command instanceof String)) return
String k = "${r.id}:${a.command}".toString()
if (!seen.add(k)) return
def d = ((settings.runtimeAllowDevices ?: []) as List).find { it?.id?.toString() == rtBareId(r.id) }
if (d == null || d.hasCommand(a.command as String)) return
List names = (d.supportedCommands ?: []).collect { it.name as String }
String from = a.command as String
List near = names.findAll { String n -> n.length() >= 4 && (from.startsWith(n) || n.startsWith(from)) }
if (near.size() == 1) out << [from: from, to: near[0], device: (r.name ?: d.displayName) as String]
}
}
return out
}
void haiCollectCommands(Object v, List out) {
if (v instanceof List) { ((List) v).each { haiCollectCommands(it, out) }; return }
if (!(v instanceof Map)) return
Map m = v
if (m.type == 'command') out << m
m.values().each { Object x -> if (x instanceof List || x instanceof Map) haiCollectCommands(x, out) }
}
Map haiShadowChildCall(String ruleId, String method) {
Map e = rtEntry(runtimePlat(), ruleId)
if (e == null || e.state == 'removed' || runtimeChild(e.childId) == null) return [ok: false, message: 'The HAI clone is no longer on the hub.']
try { return runtimeChildCall(e.childId, method, []) }
catch (Exception ex) { return [ok: false, message: "The clone could not be asked (${ex.message}). Press Done on the HAI Engine page and try again.".toString()] }
}
Map haiMigrationCall(String name, Map arg) {
HAI_APPS_SEEN.remove(app.id as String)
Map hooks = [
haiRuleOp: { Map c ->
Map pp = runtimePlat()
Map e = rtEntry(pp, c.ruleId as String)
if (c.op == 'enable' && (e == null || e.state == 'removed')) {
Map cand = rtCandidates(pp).reverse().find { ((Map) it).ruleId == c.ruleId }
if (cand == null) return [ok: false, message: 'The HAI copy is no longer on the hub.']
Map out = rtActivateAuto(pp, cand.id)
if (out.ok) runIn(1, 'haiReconcileSoon', [overwrite: false, data: [ids: [c.ruleId as String]]])
else if (out.code != 'STAGE_UNCONFIRMED') {
try { rtCandidateHeld(pp, cand.id, out) } catch (Exception ex) { log.warn "HAI Runtime: hold reason not kept: ${ex.message}" }
}
return out.ok || out.code == 'STAGE_UNCONFIRMED' ? [ok: true] : [ok: false, message: out.message as String]
}
Map r = rtRuleOp(pp, c.ruleId as String, c.op as String)
if (c.op == 'remove' && r?.ok) {
try {
List drafts = (filed('editorDrafts') ?: [])
List kept = drafts.findAll { Object d -> !(((Map) d).needsFixing == true && ((Map) d).rule instanceof Map && ((Map) ((Map) d).rule).id == c.ruleId) }
if (kept.size() != drafts.size()) filedPut('editorDrafts', kept)
} catch (Exception ex) { log.warn "HAI Engine: the copy's draft was not removed: ${ex.message}" }
}
if (r?.ok) {
try { runtimeAnnounce(pp, true, [c.ruleId as String], false) } catch (Exception ex) { log.warn "HAI Runtime: summary not announced: ${ex.message}" }
}
return r
},
dryRun: { Map c ->
Map pp = runtimePlat()
Map e = rtEntry(pp, c.ruleId as String)
Map doc = null
if (e != null && e.state != 'removed' && runtimeChild(e.childId) != null) {
try { doc = ((Map) runtimeChildCall(e.childId, 'haiRuntimeState', []))?.doc } catch (Exception ex) { doc = null }
}
if (doc == null) doc = ((Map) rtCandidates(pp).reverse().find { ((Map) it).ruleId == c.ruleId })?.doc
if (doc == null) return [ok: false, message: 'HAI could not find the copy to try.']
Map inv = null
try { inv = engineInventory(rtDeviceIds(doc), doc) } catch (Exception ex) { log.warn "HAI Engine: dry run uses made-up devices (${ex.message})" }
Map tried = checkerCall([op: 'dryRun', doc: doc, now: now(), zone: (location.timeZone ?: TimeZone.getTimeZone('UTC')).ID, inv: inv])
return tried.checkerError ? [ok: false, message: tried.message as String] : tried
},
activateStopped: { Map c ->
Map pp = runtimePlat()
Map cand = rtCandidate(pp, c.candidateId)
String rid = cand?.ruleId as String
Map out = rtActivateAuto(pp, c.candidateId, true)
boolean unconfirmed = out.code == 'STAGE_UNCONFIRMED' || (out.ok && out.committed != true)
if ((out.ok || unconfirmed) && rid) runIn(1, 'haiReconcileSoon', [overwrite: false, data: [ids: [rid]]])
if (out.ok && !unconfirmed) return [ok: true]
if (unconfirmed) return [ok: false, unconfirmed: true, code: (out.code ?: 'COMMIT_PENDING') as String]
try { rtCandidateHeld(pp, c.candidateId, out) } catch (Exception ex) { log.warn "HAI Runtime: hold reason not kept: ${ex.message}" }
List renames = []
try { renames = haiMigrationRenames((Map) cand?.doc) } catch (Exception ex) { renames = [] }
return [ok: false, code: out.code, refused: out, doc: cand?.doc, renames: renames]
},
rmPausedNow: { Object rmId ->
Boolean paused = null
httpGet([uri: "http://127.0.0.1:8080/installedapp/statusJson/${rmId}".toString(),
contentType: 'application/json', timeout: 10]) { resp ->
if (resp?.status == 200) paused = rtRmPausedFrom(resp.data)
}
return paused
},
keepDraft: { Map c ->
Map doc = c.doc
List drafts = (filed('editorDrafts') ?: [])
Map prior = drafts.find { ((Map) it).rule instanceof Map && ((Map) ((Map) it).rule).id == doc?.id }
Map store = [drafts: drafts, draftSeq: state.editorDraftSeq ?: 0, proposals: filed('proposals') ?: [],
proposalRules: filed('proposalRules') ?: [], outcome: filed('engineOutcome')]
Map body = [name: ((doc?.name ?: 'Rule Machine copy') as String).take(80), rule: doc, needsFixing: true]
if (c.why instanceof String) body.fixReason = c.why
if (prior?.id) body.id = prior.id
Map saved = editorSave(groovy.json.JsonOutput.toJson(body), store, now())
if (saved.status != 200) return [ok: false, message: (((Map) saved.body).message ?: 'The draft could not be saved.') as String]
filedPut('editorDrafts', ((Map) saved.store).drafts)
state.editorDraftSeq = ((Map) saved.store).draftSeq
return [ok: true, draftId: ((Map) ((Map) saved.body).draft).id]
},
importRule : { Map c ->
Map kept
try { kept = rtImportDoc(runtimePlat(), c.doc) }
catch (Exception ex) { return [ok: false, code: 'MIGRATION_IMPORT', message: "HAI could not store this copy (${ex.message})".toString()] }
return kept.ok == true
? [ok: true, candidateId: ((Map) kept.candidate)?.id, message: kept.message]
: [ok: false, code: (kept.code as String) ?: 'MIGRATION_IMPORT', message: kept.message as String]
},
setBoolean : { Map c -> rtRuleOp(runtimePlat(), c.ruleId as String, 'setBoolean', c.value == true ? 'true' : 'false') },
shadowRuns : { Map c -> haiShadowChildCall(c.ruleId as String, 'haiShadowRuns') },
shadowTest : { Map c -> haiShadowChildCall(c.ruleId as String, 'haiShadowTest') }
]
Closure f = (Closure) hooks.get(name)
if (f == null) return [ok: false, message: "this build of HAI Engine has no ${name}".toString()]
if (name == 'rmPausedNow') return [paused: f(arg?.rmId)]
Object r = f(arg)
return r instanceof Map ? r : [ok: true]
}
def editorStateGet() {
if (!haiAudienceOk('/editor/api/state')) return haiRefuse('/editor/api/state')
haiCodeCheck()
editorRespond('state') }
def editorFixedGet() {
if (!haiAudienceOk('/editor/api/fixed')) return haiRefuse('/editor/api/fixed')
String text
try { text = editorFixedText() } catch (Exception e) {
return render(status: 500, contentType: 'application/json',
data: groovy.json.JsonOutput.toJson([ok: false, error: 'NO_TABLES', message: e.message]))
}
if (haiTooBigForCloud(text)) return haiCloudTooBig(text)
boolean current = (params?.v as String) == editorFixedVersion() && params?.access_token == null
return render(status: 200, contentType: 'application/json', data: text,
headers: ['Cache-Control': current ? 'private, max-age=31536000, immutable' : 'no-store, must-revalidate'])
}
def editorDeviceGet() {
if (!haiAudienceOk('/editor/api/device')) return haiRefuse('/editor/api/device')
Map body
int status = 200
try {
List ids = ((params?.ids ?: '') as String).split(',').collect { it.trim().replaceFirst('^dev:', '') }
.findAll { it ==~ /[0-9]{1,12}/ }.unique().take(20)
Map inv = ids ? engineInventory(ids, [:]) : invNew()
body = [ok: true, devices: editorDevices(inv)]
} catch (Exception e) {
log.warn "HAI Engine editor: device failed: ${e.message}"
status = 500
body = [ok: false, error: 'INTERNAL', message: 'The hub could not read that device.']
}
return editorJson(status, groovy.json.JsonOutput.toJson(body))
}
def editorRunStepPost() {
if (!haiAudienceOk('/editor/api/run-step')) return haiRefuse('/editor/api/run-step')
Map out
int status = 200
try {
String raw = haiBody()
Map read = editorReadBody(raw)
Map made = read.error ? read.error : editorRunStepDoc((Map) read.value)
if (made.status != null) { status = made.status as int; out = made.body }
else out = editorRunStep((Map) made.doc, made.deviceIds)
} catch (Exception e) {
log.warn "HAI Engine editor: run-step failed: ${e.message}"
status = 500
out = [ok: false, error: 'INTERNAL', message: "The hub could not run the step: ${e.message}".toString()]
}
return editorJson(status, groovy.json.JsonOutput.toJson(out))
}
Map editorRunStepNo(String code, String message) { return [ok: false, error: code, message: message] }
Map editorRunStep(Map doc, List ids) {
Map held = [:]
((settings.runtimeAllowDevices ?: []) as List).each { d -> if (d != null && ids.contains("dev:${d.id}".toString())) held.put("dev:${d.id}".toString(), d) }
Map refs = doc.refs
String bad = ids.find { !held.containsKey(it) }
if (bad != null) return editorRunStepNo('DEVICE_NOT_SELECTED', "Device ${bad} is not selected for HAI.".toString())
def off = held.values().find { d -> try { return d.isDisabled() == true } catch (Exception e) { return false } }
if (off != null) return editorRunStepNo('DEVICE_DISABLED', "${off.displayName} is disabled on the hub. Enable it first.".toString())
Map step = ((List) doc.actions)[0]
List sent = [], errors = []
if (step.type == 'setMode') {
if (settings.runtimeAllowSetMode != true) return editorRunStepNo('MODE_NOT_ALLOWED', 'Turn on "Allow rules to change the mode" on the HAI Engine page first.')
String mode = step.ref != null ? ((Map) refs.get(step.ref))?.name : step.value
location.setMode(mode)
sent << "mode ${mode}".toString()
} else if (step.type == 'setVariable') {
String name = (((Map) refs.get(step.ref)).id as String).substring(HUB_VARIABLE_PREFIX.length())
List approved = settings.runtimeWritableVariables == null ? null : runtimeIdList(settings.runtimeWritableVariables)
if (approved != null && !approved.contains(name)) return editorRunStepNo('VARIABLE_NOT_WRITABLE', "Rules may not change the hub variable '${name}'. Approve it on the HAI Engine page first.".toString())
def gv = getGlobalVar(name)
if (gv == null) return editorRunStepNo('VARIABLE_NOT_FOUND', "There is no hub variable named '${name}'.".toString())
Object v = schemaHubValue(gv.type as String, step.value)
if (v == null) return editorRunStepNo('VARIABLE_VALUE', "'${step.value}' is not a value the hub variable '${name}' can hold.".toString())
if (setGlobalVar(name, v) == false) return editorRunStepNo('VARIABLE_REFUSED', "The hub refused to set the hub variable '${name}'.".toString())
sent << "${name} set to ${v}".toString()
} else if (step.type == 'log') {
if (step.level == 'warn') log.warn(step.text as String) else haiInfo(step.text as String)
sent << 'logged'
} else {
String cmd = step.command as String
List args = step.args instanceof List ? step.args : []
List keys = ([step.ref] + (step.refs instanceof List ? step.refs : [])).findAll { it != null }.unique()
if (keys.any { !(refs?.get(it) instanceof Map) }) {
return editorRunStepNo('REF_MISSING', "This step names a device the rule doesn't list; save or re-pick the devices.")
}
if ((COMMAND_BASIS.containsKey(cmd) && !(cmd in ['toggle', 'alarmOff'])) || args.any { it instanceof Map || it.toString().contains('%') } ||
keys.any { ((Map) refs.get(it))?.kind != 'device' }) {
return editorRunStepNo('NEEDS_THE_RULE', 'This step works out a value while the rule runs. Run the whole rule instead.')
}
List devs = []
for (Object k : keys) {
for (String id : edRunStepRefIds((Map) refs.get(k))) {
def d = held.get(id)
if (d == null) return editorRunStepNo('DEVICE_NOT_SELECTED', "Device ${id} is not selected for HAI.".toString())
if (!devs.contains(d)) devs << d
}
}
if (!devs) return editorRunStepNo('REF_MISSING', "This step names a device the rule doesn't list; save or re-pick the devices.")
devs.each { d ->
if (step.onlyIfOn == true && d.currentValue('switch') != 'on') return
try { rtDriverCommand(d, cmd, args); sent << "${d.displayName}: ${cmd}".toString() }
catch (Exception e) { errors << "${d.displayName}: ${e.message}".toString() }
}
}
return editorRunStepAnswer(sent, errors)
}
def editorCheckPost() {
if (!haiAudienceOk('/editor/api/check')) return haiRefuse('/editor/api/check')
editorRespond('check') }
def editorRuleGet() {
if (!haiAudienceOk('/editor/api/rule')) return haiRefuse('/editor/api/rule')
editorRespond('rule') }
def editorRuntimeGet() {
if (!haiAudienceOk('/editor/api/runtime')) return haiRefuse('/editor/api/runtime')
editorRespond('runtime') }
def editorActiveGet() {
if (!haiAudienceOk('/editor/api/active')) return haiRefuse('/editor/api/active')
Map out
try {
String id = endpointParam(params?.id)
Map pp = runtimePlat()
Map e = id == null ? null : rtEntry(pp, id)
Map held = id != null && (e == null || e.state == 'removed') ? rtHeldCandidate(pp, id) : null
if (held != null) {
out = editorFreshNames(editorHeldView(id, held), engineInventory(editorDocDeviceIds((Map) held.doc)))
} else {
Map child = null
if (e != null && runtimeChild(e.childId) != null) {
try { child = runtimeChildCall(e.childId, 'haiRuntimeState', []) } catch (Exception ex) { child = null }
}
out = editorActiveView(id, e, child, engineInventory(editorDocDeviceIds(child?.doc)))
}
} catch (Exception ex) {
log.warn "HAI Engine editor: active failed: ${ex.message}"
out = [status: 500, body: [ok: false, error: 'INTERNAL', message: 'The hub could not read the running version.']]
}
return editorJson(out.status as int, groovy.json.JsonOutput.toJson(out.body))
}
def editorUsesGet() {
if (!haiAudienceOk('/editor/api/uses')) return haiRefuse('/editor/api/uses')
Map out
int status = 200
try {
String want = rtBareId(endpointParam(params?.device) ?: '')
if (!(want ==~ /[0-9]{1,12}/)) {
status = 400
out = [ok: false, error: 'INVALID_ID', message: 'Choose a device first.']
} else {
Map pp = runtimePlat()
List rules = []
((Map) rtRegistry(pp).rules).values().each { Object eo ->
Map e = eo
if (e.state == 'removed') return
Map ix = rtIndex(pp, e)
if (!(ix?.deviceIds instanceof List) || !((List) ix.deviceIds).contains(want)) return
rules << [id: e.ruleId, name: runtimeRuleName(e), state: e.state, paused: ((List) (state.runtimePaused ?: [])).contains(e.ruleId),
epoch: e.epoch, ruleHash: e.ruleHash, rule: runtimeRuleDoc(pp, e)]
}
List drafts = ((List) (filed('editorDrafts') ?: [])).findAll { Object o ->
Map d = o
Map refs = d.rule instanceof Map && ((Map) d.rule).refs instanceof Map ? ((Map) d.rule).refs : [:]
refs.values().any { it instanceof Map && ((Map) it).kind == 'device' && rtBareId(((Map) it).id) == want }
}.collect { Object o ->
Map d = o
[id: d.id, name: d.name, ruleId: ((Map) d.rule).id, rule: d.rule]
}
out = [ok: true, device: "dev:${want}".toString(), rules: rules, drafts: drafts]
}
} catch (Exception ex) {
log.warn "HAI Engine editor: uses failed: ${ex.message}"
status = 500
out = [ok: false, error: 'INTERNAL', message: 'The hub could not list the rules that use that device.']
}
return editorJson(status, groovy.json.JsonOutput.toJson(out))
}
@Field static final String HAI_BACKUP_CRON = '43 41 3 * * ?'
@Field static final String HAI_UPDATE_CRON = '17 23 4 * * ?'
@Field static final String HAI_VERSION_URL = 'https://raw.githubusercontent.com/GordonThelander/HAI-Engine/preview/VERSION'
def haiUpdateCheck() {
if (HAI_ENVIRONMENT != 'PREVIEW') return
try { asynchttpGet('haiUpdateAnswer', [uri: HAI_VERSION_URL, contentType: 'text/plain', timeout: 20]) }
catch (Exception e) { log.warn "HAI Engine: update check not sent: ${e.message}" }
}
def haiUpdateAnswer(resp, Map data) {
try {
if (resp.status != 200) return
String v = ((resp.data ?: '') as String).trim().replace('-dev', '')
if (v ==~ /\d+(\.\d+){1,3}/) state.haiLatest = [version: v, at: now()]
} catch (Exception e) { log.warn "HAI Engine: update check answer not read: ${e.message}" }
}
String haiNewerVersion() {
if (HAI_ENVIRONMENT != 'PREVIEW') return null
String v = (state.haiLatest instanceof Map ? ((Map) state.haiLatest).version : null) as String
if (!v) return null
List a = v.tokenize('.').collect { it.isInteger() ? it as int : 0 }
List b = haiVersion().tokenize('.').collect { it.isInteger() ? it as int : 0 }
for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
int x = i < a.size() ? (a[i] as int) : 0, y = i < b.size() ? (b[i] as int) : 0
if (x != y) return x > y ? v : null
}
return null
}
@Field static final int HAI_BACKUP_KEEP = 10
def haiBackupDaily() {
try { haiBackupMake(false) } catch (Exception e) { log.warn "HAI Engine: rule backup failed: ${e.message}" }
}
String haiBackupName(long at) {
return "hai-backup-${new Date(at).format('yyyyMMdd', location.timeZone)}.json".toString()
}
Map haiBackupMake(boolean force) {
Map pp = runtimePlat()
Map reg = (rtRegistry(pp).rules ?: [:])
List live = reg.values().findAll { it instanceof Map && ((Map) it).state in ['active', 'disabled'] }.sort { ((Map) it).ruleId as String }
List paused = (state.runtimePaused ?: [])
Map pairs = (state.migPairs ?: [:])
String stamp = rtBackupStamp(live, paused, [drafts: haiBackupDraftsMark() ?: '', pairs: pairs])
if (!force && stamp == state.haiBackupPrint) return [ok: true, written: false, message: 'No rule or draft on the hub changed since the last backup.']
List rules = []
int unread = 0
live.each { Map e ->
Map doc = runtimeRuleDoc(pp, e)
if (doc == null) { unread++; return }
rules << [id: e.ruleId, name: runtimeRuleName(e), state: e.state, paused: paused.contains(e.ruleId), epoch: e.epoch, ruleHash: e.ruleHash, rule: doc]
}
List drafts = ((List) (filed('editorDrafts') ?: [])).findAll { it instanceof Map && ((Map) it).rule instanceof Map }.collect { Object o ->
Map d = o
Map out = [draft: true, id: d.id, name: d.name, savedAt: d.savedAt, ruleHash: d.ruleHash, rule: d.rule]
if (d.needsFixing == true) out.needsFixing = true
out
}
long at = now()
String name = haiBackupName(at)
String text = groovy.json.JsonOutput.toJson([format: 'hai.backup/1', engine: ENGINE_APP_VERSION, at: at, rules: rules,
drafts: drafts, migPairs: pairs])
byte[] bytes = text.getBytes('UTF-8')
uploadHubFile(name, bytes)
List idx = ((List) (state.haiBackups ?: [])).findAll { it instanceof Map && ((Map) it).name != name }
idx << [name: name, at: at, bytes: bytes.length, rules: rules.size(), drafts: drafts.size(), pairs: pairs.size()]
idx = idx.sort { ((Map) it).name as String }
while (idx.size() > HAI_BACKUP_KEEP) {
Map old = idx.remove(0)
try { deleteHubFile(old.name as String) } catch (Exception e) { log.warn "HAI Engine: old backup ${old.name} not deleted: ${e.message}" }
}
state.haiBackups = idx
if (unread == 0) state.haiBackupPrint = stamp
haiInfo "HAI Engine: rule backup ${name}: ${rules.size()} rule${rules.size() == 1 ? '' : 's'}, ${drafts.size()} draft${drafts.size() == 1 ? '' : 's'}, " +
"${pairs.size()} migration pair${pairs.size() == 1 ? '' : 's'}, ${bytes.length} bytes${unread ? ", ${unread} could not be read" : ''}"
return [ok: true, written: true, name: name, bytes: bytes.length, rules: rules.size(), drafts: drafts.size(), pairs: pairs.size(), unread: unread]
}
String haiBackupDraftsMark() {
String t = filedText('editorDrafts')
if (t == null && state.editorDrafts != null) t = groovy.json.JsonOutput.toJson(state.editorDrafts)
return t == null ? null : "${t.length()}:${t.hashCode()}".toString()
}
void haiMigrationPairsRestore(Map listed) {
if (state.migPairsRestored == true) return
Map have = (state.migPairs ?: [:])
String from = state.migPairsRestoreFrom as String
if (!have.isEmpty() && from == null) { state.migPairsRestored = true; return }
if (listed?.ok != true) return
Map pp = runtimePlat()
Set hai = ((Map) (rtRegistry(pp).rules ?: [:])).values().findAll { it instanceof Map && ((Map) it).state != 'removed' }
.collect { ((Map) it).ruleId as String } as Set
rtCandidates(pp).each { Object c -> if (c instanceof Map && ((Map) c).ruleId != null) hai << (((Map) c).ruleId as String) }
if (state.migPairsRestoreSeen == hai.size()) return
boolean unreadable = false
List backups = from == null ? haiBackupList() : haiBackupList().findAll { ((Map) it).name == from }
for (Object bo : backups) {
Map b = bo
if (b.pairs == 0) continue
Object file = null
try {
byte[] raw = downloadHubFile(b.name as String)
file = raw == null ? null : new groovy.json.JsonSlurper().parseText(new String(raw, 'UTF-8'))
} catch (Exception e) { file = null }
if (!(file instanceof Map)) { unreadable = true; continue }
Map got = rtBackupPairsResolving(((Map) file).migPairs, listed.checked, hai)
Map add = ((Map) got.pairs).findAll { k, v -> !have.containsKey(k) }
if (add.isEmpty() && from == null) continue
if (!add.isEmpty()) {
state.migPairs = new LinkedHashMap(have) + add
haiAnnounceSwitched()
haiInfo "HAI Engine: ${add.size()} migration pair${add.size() == 1 ? '' : 's'} restored from ${b.name}" +
"${got.dropped ? "; ${got.dropped} wait for a rule of the pair to be on the hub" : ''}"
}
state.migPairsRestoreFrom = b.name
state.migPairsRestoreSeen = hai.size()
if (got.dropped == 0) state.migPairsRestored = true
return
}
if (from != null && backups.isEmpty()) { state.migPairsRestored = true; return }
if (!unreadable) state.migPairsRestoreSeen = hai.size()
}
List haiBackupList() {
if (state.haiBackupsScanned != true) haiBackupIndexRebuild()
return ((List) (state.haiBackups ?: [])).findAll { it instanceof Map }.collect { new LinkedHashMap((Map) it) }.reverse()
}
void haiBackupIndexRebuild() {
List idx = ((List) (state.haiBackups ?: [])).findAll { it instanceof Map }
Set have = idx.collect { ((Map) it).name as String } as Set
boolean read = false
try {
httpGet([uri: 'http://127.0.0.1:8080/hub/fileManager/json', timeout: 15]) { resp ->
read = true
Object d = resp.data
List files = d instanceof List ? d : (d instanceof Map && ((Map) d).files instanceof List ? ((Map) d).files : [])
files.each { Object f ->
String n = (f instanceof Map ? ((Map) f).name : f) as String
if (!(n ==~ /hai-backup-[0-9]{8}\.json/) || have.contains(n)) return
long at = 0L
try { at = Date.parse('yyyyMMdd', n.substring(11, 19)).time } catch (Exception e) { at = 0L }
Object size = f instanceof Map ? ((Map) f).size : null
idx << [name: n, at: at, bytes: size instanceof Number ? size : null, rules: null]
}
}
} catch (Exception e) { log.warn "HAI Engine: File Manager could not be listed to find earlier backups (${e.message})" }
state.haiBackups = idx.sort { ((Map) it).name as String }
if (read) state.haiBackupsScanned = true
if (idx.size() > have.size()) haiInfo "HAI Engine: found ${idx.size() - have.size()} earlier rule backup${idx.size() - have.size() == 1 ? '' : 's'} in File Manager"
}
def editorBackupsGet() {
if (!haiAudienceOk('/editor/api/backups')) return haiRefuse('/editor/api/backups')
Map out = [ok: true, backups: haiBackupList(), keep: HAI_BACKUP_KEEP, daily: '03:41']
return editorJson(200, groovy.json.JsonOutput.toJson(out))
}
def editorBackupGet() {
if (!haiAudienceOk('/editor/api/backup')) return haiRefuse('/editor/api/backup')
String name = endpointParam(params?.name)
if (!(name ==~ /hai-backup-[0-9]{8}\.json/) || !haiBackupList().any { it.name == name }) {
return editorJson(404, groovy.json.JsonOutput.toJson([ok: false, error: 'NO_SUCH_BACKUP', message: 'There is no backup with that name.']))
}
byte[] b = null
try { b = downloadHubFile(name) } catch (Exception e) { b = null }
if (b == null) return editorJson(404, groovy.json.JsonOutput.toJson([ok: false, error: 'BACKUP_UNREADABLE', message: "The backup ${name} could not be read from File Manager.".toString()]))
return editorJson(200, '{"ok":true,"name":' + groovy.json.JsonOutput.toJson(name) + ',"backup":' + new String(b, 'UTF-8') + '}')
}
def editorBackupPost() {
if (!haiAudienceOk('/editor/api/backup')) return haiRefuse('/editor/api/backup')
Map out
int status = 200
try {
out = haiBackupMake(true)
out.backups = haiBackupList()
} catch (Exception e) {
log.warn "HAI Engine editor: backup failed: ${e.message}"
status = 500
out = [ok: false, error: 'BACKUP_FAILED', message: "The backup was not written: ${e.message}".toString()]
}
return editorJson(status, groovy.json.JsonOutput.toJson(out))
}
def editorSendPost() {
if (!haiAudienceOk('/editor/api/send')) return haiRefuse('/editor/api/send')
boolean notYet = false
try { notYet = app.getInstallationState() != 'COMPLETE' } catch (Exception e) { notYet = false }
if (notYet) return editorJson(409, groovy.json.JsonOutput.toJson([ok: false, error: 'NOT_INSTALLED',
message: 'HAI Engine is not finished installing. Press Done on the HAI Engine page, then put the rule on the hub.']))
boolean timeRun = HAI_ENVIRONMENT == 'DEV' && haiDiagnosticsActive()
long sendStarted = timeRun ? now() : 0L
long phaseStarted = sendStarted
Map sendTiming = timeRun ? [:] : null
boolean wasRun = false
Map out
try {
String raw = haiBody()
out = editorSubmission(raw, haiSigningKey(), now(),
haiEditorAddress())
if (timeRun) {
sendTiming.submitMs = now() - phaseStarted
phaseStarted = now()
wasRun = out?.payload instanceof Map && ((Map) out.payload).op == 'run'
}
boolean isRun = out.payload != null && ((Map) out.payload).op == 'run'
if (isRun) {
Map kept = editorKeepSent(out.text as String, [drafts: filed('editorDrafts') ?: [], draftSeq: state.editorDraftSeq ?: 0], now(),
((Map) out.payload).rule instanceof Map ? ((Map) out.payload).rule : null)
if (kept != null) { filedPut('editorDrafts', kept.drafts); state.editorDraftSeq = kept.draftSeq }
}
if (timeRun && wasRun) { sendTiming.keepMs = now() - phaseStarted; phaseStarted = now() }
if (out.payload != null) {
Map inProcess = isRun ? [canonical: out.text as String,
checked: editorReceiptClaim(out.receipt, haiReceiptKey(), ENGINE_APP_VERSION)] : null
Map receipt = engineDispatchSubmission((Map) out.payload, inProcess)
if (((Map) out.payload).op != 'run' && receipt?.ok != true) {
out = [status: 409, body: [ok: false, error: (receipt?.code ?: 'REFUSED') as String, op: ((Map) out.payload).op,
ruleId: ((Map) out.payload).ruleId, sentAt: ((Map) out.payload).sentAt,
message: (receipt?.notice ?: receipt?.message ?: 'The hub refused this.') as String]]
}
}
if (timeRun && wasRun) { sendTiming.dispatchMs = now() - phaseStarted; phaseStarted = now() }
} catch (Exception e) {
if (timeRun && wasRun && sendTiming.dispatchMs == null) {
sendTiming.dispatchMs = now() - phaseStarted
phaseStarted = now()
}
log.warn "HAI Engine editor: send failed: ${e.message}"
out = [status: 500, body: [ok: false, error: 'INTERNAL', message: 'The hub could not send this. Your edits in the page are kept.']]
}
String response = groovy.json.JsonOutput.toJson(out.body)
if (timeRun && wasRun) {
sendTiming.serializeMs = now() - phaseStarted
sendTiming.totalMs = now() - sendStarted
log.info "HAI editor run timings (ms): ${'submitMs keepMs dispatchMs'.split(' ').collect { "${it[0..-3]}=${sendTiming[it] ?: 0}" }.join(', ')}, serialize=${sendTiming.serializeMs}, total=${sendTiming.totalMs}"
}
return editorJson(out.status as int, response)
}
def endpointRulesGet() {
if (!haiAudienceOk('/endpoint/rules')) return haiRefuse('/endpoint/rules')
if (settings.haiEndpointOn != true) return endpointOff()
if (haiViaCloud() && settings.haiEndpointCloud != true) return haiCloudOff('end point')
endpointRespond([kind: 'rules']) }
def endpointRuleGet() { if (settings.haiEndpointGet != true) return endpointGetOff(); return endpointRulePost() }
def endpointRulePost() {
if (!haiAudienceOk('/endpoint/rules/:id/:op')) return haiRefuse('/endpoint/rules/:id/:op')
if (settings.haiEndpointOn != true) return endpointOff()
if (haiViaCloud() && settings.haiEndpointCloud != true) return haiCloudOff('end point')
endpointRespond([kind: 'rule', id: endpointParam(params?.id), op: params?.op, value: endpointParam(params?.value)]) }
def endpointVariableGet() { if (settings.haiEndpointGet != true) return endpointGetOff(); return endpointVariablePost() }
def endpointVariablePost() {
if (!haiAudienceOk('/endpoint/variables/:name/:value')) return haiRefuse('/endpoint/variables/:name/:value')
if (settings.haiEndpointOn != true) return endpointOff()
if (haiViaCloud() && settings.haiEndpointCloud != true) return haiCloudOff('end point')
endpointRespond([kind: 'variable', id: endpointParam(params?.name), value: endpointParam(params?.value)]) }
Object endpointGetOff() {
return render(status: 405, contentType: 'application/json', headers: ['Cache-Control': 'no-store', 'Allow': 'POST'],
data: groovy.json.JsonOutput.toJson([ok: false, error: 'POST_ONLY',
message: 'This end point changes something, so it takes POST. To use it as a plain link (GET), as Rule Machine does, turn on Allow GET links (Rule Machine-style) on the HAI Engine page.']))
}
boolean haiViaCloud() {
String src = null
try { src = request?.requestSource as String } catch (Exception e) { src = null }
return src != null && src.toLowerCase() == 'cloud'
}
Object haiCloudOff(String what) {
return render(status: 403, contentType: 'application/json', headers: ['Cache-Control': 'no-store'],
data: groovy.json.JsonOutput.toJson([ok: false, error: 'CLOUD_OFF',
message: "This ${what} address works on the home network only. To use it through Hubitat's cloud, turn on Allow through Hubitat's cloud for it on the HAI Engine page.".toString()]))
}
Object endpointOff() {
return render(status: 403, contentType: 'application/json', headers: ['Cache-Control': 'no-store'],
data: groovy.json.JsonOutput.toJson([ok: false, error: 'ENDPOINT_OFF', message: 'The rule web address is turned off on the HAI Engine page.']))
}
String endpointParam(Object v) {
if (v == null) return null
String s = v.toString()
try { return s.contains('%') ? URLDecoder.decode(s, 'UTF-8') : s } catch (Exception e) { return s }
}
def endpointRespond(Map p) {
Map out
try {
out = editorEndpoint(p, haiSigningKey(), now(), state.runtimeSummary)
if (out.payload != null) {
Map receipt = engineDispatchSubmission((Map) out.payload)
haiDebug "HAI Engine: end point ${((Map) out.payload).op} ${((Map) out.payload).ruleId}: ${receipt?.ok ? 'done' : (receipt?.notice ?: receipt?.code)}"
if (receipt?.ok != true) {
out = [status: 409, body: [ok: false, error: (receipt?.code ?: 'REFUSED') as String, op: ((Map) out.payload).op,
ruleId: ((Map) out.payload).ruleId,
message: (receipt?.notice ?: receipt?.message ?: 'The hub refused this.') as String]]
}
}
} catch (Exception e) {
log.warn "HAI Engine: end point failed: ${e.message}"
out = [status: 500, body: [ok: false, error: 'INTERNAL', message: 'The hub could not complete this request.']]
}
render(status: out.status as int, contentType: 'application/json', data: groovy.json.JsonOutput.toJson(out.body), headers: ['Cache-Control': 'no-store'])
}
def editorSavePost() {
if (!haiAudienceOk('/editor/api/save')) return haiRefuse('/editor/api/save')
editorRespond('save') }
def editorDeletePost() {
if (!haiAudienceOk('/editor/api/delete')) return haiRefuse('/editor/api/delete')
editorRespond('delete') }
@Field static final int ED_DEL_STEP = 4
@Field static final int ED_DEL_MAX = 500
def editorDeleteManyPost() {
if (!haiAudienceOk('/editor/api/delete-many')) return haiRefuse('/editor/api/delete-many')
Map body = null
try { body = new groovy.json.JsonSlurper().parseText(haiBody()) } catch (Exception e) { body = null }
List rules = body?.rules instanceof List ? ((List) body.rules).findAll { it instanceof String }.unique() : []
List drafts = body?.drafts instanceof List ? ((List) body.drafts).findAll { it instanceof String }.unique() : []
if (rules.size() + drafts.size() == 0 || rules.size() + drafts.size() > ED_DEL_MAX) {
return editorJson(400, groovy.json.JsonOutput.toJson([ok: false, error: 'INVALID_IDS', message: 'Say which rules and drafts to delete.']))
}
Map job = state.edDelJob
if (job?.state == 'running' && now() - ((job.lastStepAt ?: 0L) as long) < 180000L) {
return editorJson(409, groovy.json.JsonOutput.toJson([ok: false, error: 'JOB_RUNNING', message: 'A delete is already running on the hub.', job: editorDeleteJobView(job)]))
}
Map pp = runtimePlat()
Map names = [:]
List known = rules.findAll { String id -> Map e = rtEntry(pp, id); if (e != null && e.state != 'removed') { names.put(id, runtimeRuleName(e)); return true }; false }
List draftRows = ((List) (filed('editorDrafts') ?: []))
drafts.each { String id -> Map d = draftRows.find { ((Map) it).id == id }; if (d) names.put(id, d.name) }
state.remove('edDelStop')
state.edDelJob = [state: 'running', rules: known, drafts: drafts, unknown: rules.size() - known.size(), names: names,
total: known.size() + drafts.size(), handled: 0, results: [], startedAt: now(), lastStepAt: now()]
runIn(1, 'editorDeleteManyStep', [overwrite: true])
return editorJson(200, groovy.json.JsonOutput.toJson([ok: true, job: editorDeleteJobView((Map) state.edDelJob)]))
}
def editorDeleteManyGet() {
if (!haiAudienceOk('/editor/api/delete-many')) return haiRefuse('/editor/api/delete-many')
Map job = state.edDelJob
if (job?.state == 'running' && now() - ((job.lastStepAt ?: 0L) as long) > 180000L) runIn(1, 'editorDeleteManyStep', [overwrite: true])
return editorJson(200, groovy.json.JsonOutput.toJson([ok: true, job: job == null ? null : editorDeleteJobView(job)]))
}
def editorDeleteManyStop() {
if (!haiAudienceOk('/editor/api/delete-many/stop')) return haiRefuse('/editor/api/delete-many/stop')
if (((Map) state.edDelJob)?.state == 'running') state.edDelStop = true
return editorJson(200, groovy.json.JsonOutput.toJson([ok: true]))
}
Map editorDeleteJobView(Map job) {
List rules = (job.rules ?: [])
int rulesDone = ((List) (job.results ?: [])).count { ((Map) it).kind == 'rule' } as int
String nowId = job.state == 'running' ? (rulesDone < rules.size() ? (String) rules[rulesDone] : null) : null
return [state: job.state, total: job.total, ruleCount: rules.size(), handled: job.handled, startedAt: job.startedAt, endedAt: job.endedAt, now: now(),
current: nowId != null ? ((Map) job.names)?.get(nowId) : (job.state == 'running' && job.drafts ? "${((List) job.drafts).size()} drafts".toString() : null),
results: job.results, unknown: job.unknown, draftsMessage: job.draftsMessage]
}
def editorDeleteManyStep() {
Map job = state.edDelJob
if (job?.state != 'running') return
if (state.edDelStop == true) {
state.remove('edDelStop')
job.state = 'stopped'; job.endedAt = now(); state.edDelJob = job
return
}
job.lastStepAt = now()
state.edDelJob = job
Map pp = runtimePlat()
List rules = job.rules
List results = (job.results ?: [])
int from = results.count { ((Map) it).kind == 'rule' } as int
if (from < rules.size()) {
List these = rules.subList(from, Math.min(rules.size(), from + ED_DEL_STEP)).collect { it as String }
List gone = []
these.each { String id ->
Map r
try { r = rtRemoveStoppingFirst(pp, id) } catch (Exception e) { r = [ok: false, message: e.message] }
results << [kind: 'rule', id: id, name: ((Map) job.names)?.get(id), ok: r?.ok == true, message: r?.ok == true ? null : ((r?.message ?: r?.code ?: 'refused') as String)]
if (r?.ok == true) gone << id
}
if (gone) {
try {
List ds = (filed('editorDrafts') ?: [])
List kept = ds.findAll { Object d -> !(((Map) d).needsFixing == true && ((Map) d).rule instanceof Map && gone.contains(((Map) ((Map) d).rule).id)) }
if (kept.size() != ds.size()) filedPut('editorDrafts', kept)
} catch (Exception e) { log.warn "HAI Engine: copies' drafts not removed: ${e.message}" }
try { runtimeAnnounce(pp, true, gone, false) } catch (Exception e) { log.warn "HAI Runtime: summary not announced: ${e.message}" }
}
job.results = results
job.handled = results.size()
state.edDelJob = job
if (from + these.size() < rules.size() || job.drafts) { runIn(1, 'editorDeleteManyStep', [overwrite: true]); return }
}
List drafts = (job.drafts ?: [])
if (drafts) {
Map store = [drafts: filed('editorDrafts') ?: [], draftSeq: state.editorDraftSeq ?: 0, proposals: [], proposalRules: [], outcome: null]
Map res = editorDelete(groovy.json.JsonOutput.toJson([ids: drafts]), store)
if (res.status == 200) {
filedPut('editorDrafts', ((Map) res.store).drafts)
job.draftsMessage = ((Map) res.body).message
} else job.draftsMessage = 'Drafts not deleted: ' + (((Map) res.body)?.message ?: 'the hub refused')
job.handled = (job.handled as int) + drafts.size()
}
job.state = 'done'
job.endedAt = now()
state.edDelJob = job
}
def editorExportPost() {
if (!haiAudienceOk('/editor/api/export')) return haiRefuse('/editor/api/export')
editorRespond('export') }
def editorImportPost() {
if (!haiAudienceOk('/editor/api/import')) return haiRefuse('/editor/api/import')
editorRespond('import') }
def editorDraftPost() {
if (!haiAudienceOk('/editor/api/draft')) return haiRefuse('/editor/api/draft')
Map out
try {
String raw = haiBody()
Map parsed = jsonParse(raw ?: '{}')
Map body = parsed.ok && parsed.value instanceof Map ? parsed.value : [:]
String text = (body.request ?: '') as String
text = text.trim()
if (text.isEmpty()) {
out = [status: 400, body: [ok: false, error: 'EMPTY', message: 'Say what the rule should do.']]
} else if (text.length() > AUTHOR_MAX_REQUEST_CHARS) {
out = [status: 400, body: [ok: false, error: 'TOO_LONG', message: "That request is longer than the engine accepts (${AUTHOR_MAX_REQUEST_CHARS} characters).".toString()]]
} else if (body.containsKey('base') && !(body.base instanceof Map)) {
out = [status: 400, body: [ok: false, error: 'BASE_INVALID', message: 'Send the existing rule as a complete document.']]
} else if (body.base instanceof Map && (((Map) body.base).schema != SCHEMA_ID || !(((Map) body.base).id instanceof String) || !((String) ((Map) body.base).id).trim())) {
out = [status: 400, body: [ok: false, error: 'BASE_INVALID', message: 'The existing rule needs its schema and id.']]
} else if (body.base instanceof Map && jsonCanonicalise((Map) body.base, SCHEMA_DEFAULTS).getBytes('UTF-8').length > HAI_DOC_BYTES) {
out = [status: 413, body: [ok: false, error: 'BASE_TOO_LARGE', message: "The existing rule exceeds the ${HAI_DOC_BYTES}-byte runnable limit.".toString()]]
} else {
app.updateSetting('engineRequest', [type: 'text', value: text])
state.engineStatus = null
long before = (state.engineGeneration ?: 0L) as long
engineDraft(body.base instanceof Map ? body.base : null)
boolean started = ((state.engineGeneration ?: 0L) as long) > before &&
engineSessionMark() != null &&
engineSessionMark().generation?.toString() == state.engineGeneration?.toString()
out = [status: 200, body: [ok: started, working: engineDrafting(),
status: state.engineStatus, message: state.engineStatus ?: 'Drafting.',
basedOn: ((Map) filed('engineAmendment'))?.basedOn]]
}
} catch (Exception e) {
log.warn "HAI Engine editor: draft failed: ${e.message}"
out = [status: 500, body: [ok: false, error: 'INTERNAL', message: 'The hub could not start the draft.']]
}
return editorJson(out.status as int, groovy.json.JsonOutput.toJson(out.body))
}
def editorDraftGet() {
if (!haiAudienceOk('/editor/api/draft')) return haiRefuse('/editor/api/draft')
Map saved = state.engineDraftResult
boolean working = engineDrafting()
Map stored = working ? null : filed('engineOutcome')
Map outcome = stored == null ? null :
[ready: stored.ready, headline: stored.headline, message: stored.message, status: stored.status]
if (saved?.error) outcome = [status: 'failed', message: saved.error]
else if (outcome?.status == 'failed' && saved?.draftId) {
outcome.message = ('The AI\'s rule did not pass HAI\'s checks, even after one correction. It is saved as a draft ' +
'marked Needs fixing, with the steps to fix marked in red.').toString()
outcome.needsFixing = true
}
Map amendment = filed('engineAmendment')
Map body = [ok: true, working: working, status: state.engineStatus,
outcome: outcome, draftId: saved?.draftId, draftAt: saved?.draftAt,
basedOn: amendment?.basedOn,
amendment: amendment?.rule == null ? null :
[rule: amendment.rule, ruleHash: amendment.ruleHash, ready: amendment.ready,
findings: amendment.findings, changePaths: amendment.changePaths]]
if (!working) {
body.drafts = ((List) (filed('editorDrafts') ?: [])).collect { edDraftSummary((Map) it) }
body.spending = engineMonthSpendText()
Object spent = ((Map) filed('engineSession'))?.budget?.spentUsd
if (spent instanceof Number) body.costUsd = Math.round((spent as double) * 10000d) / 10000d
}
return editorJson(200, groovy.json.JsonOutput.toJson(body))
}
boolean engineDrafting() {
Map s = engineSessionMark()
if (s == null) return false
if (s.generation?.toString() != state.engineGeneration?.toString()) return false
return s.status != 'done' && s.status != 'failed'
}
List editorDocDeviceIds(Object doc) {
return doc instanceof Map ? rtDeviceIds((Map) doc) : []
}
def editorRespond(String route) {
Map out
boolean timeCheck = route == 'check' && HAI_ENVIRONMENT == 'DEV' && haiDiagnosticsActive()
long checkStarted = timeCheck ? now() : 0L
Map timing = timeCheck ? [:] : null
try {
String raw = null
if (route in ['check', 'save', 'delete', 'export', 'import']) {
raw = haiBody()
}
Map store = route == 'state' ?
[drafts: filed('editorDrafts') ?: [], draftSeq: state.editorDraftSeq ?: 0, proposals: [], proposalRules: [], outcome: null] :
route in ['rule', 'save', 'delete'] ?
[drafts: filed('editorDrafts') ?: [], draftSeq: state.editorDraftSeq ?: 0,
proposals: filed('proposals') ?: [], proposalRules: filed('proposalRules') ?: [], outcome: filed('engineOutcome')] :
[drafts: [], draftSeq: state.editorDraftSeq ?: 0, proposals: [], proposalRules: [], outcome: null]
String spending = route == 'state' ? engineMonthSpendText() : null
long inventoryStarted = timeCheck ? now() : 0L
Object checkBody = raw
Map ruleDoc = null
Map held = null
if (route == 'check') {
Map read = editorReadBody(raw)
if (read.value instanceof Map) checkBody = read.value
if (((Map) (read.value ?: [:])).rule instanceof Map) ruleDoc = ((Map) read.value).rule
} else if (route == 'rule') {
Map found = editorRule(params?.id, store)
Object r = ((Map) (found.body ?: [:])).rule
if (r instanceof Map) ruleDoc = r
else if (found.status == 404) {
try { held = rtHeldCandidate(runtimePlat(), endpointParam(params?.id)) } catch (Exception ex) { held = null }
if (held?.doc instanceof Map) ruleDoc = held.doc
}
}
Collection<String> only = route in ['check', 'rule'] ? editorDocDeviceIds(ruleDoc) : null
Map inv = route == 'state' ? engineInventory(null, null, false) :
route in ['check', 'rule', 'export', 'import'] ? engineInventory(only, ruleDoc) : invNew()
if (timeCheck) timing.inventoryMs = now() - inventoryStarted
long optionsStarted = timeCheck ? now() : 0L
Map options = route == 'runtime' ? [:] : engineOpts(route == 'rule' ? [withCheck: true] : [:])
if (route in ['check', 'rule', 'export', 'import']) options.checker = { Map r -> checkerCall(r) }
if (timeCheck) timing.optionsMs = now() - optionsStarted
if (timeCheck) {
options.editorTiming = timing
timing.profileCanonical = state.haiDigestProfiledUntil != state.haiDiagnosticsUntil
}
Map recent = route == 'runtime' && params?.rule ? runtimeRecentLine(params.rule) : null
out = editorApi(route, [body: checkBody, params: [id: params?.id]], store, inv, options,
[spending: spending, version: haiVersion(), environment: HAI_ENVIRONMENT,
runtimeUrl: "/installedapp/configure/${app.id}".toString(),
migrationUrl: route == 'state' ? haiMigrationPageUrl() : null,
shadowLights: route == 'state' ? haiShadowLights() : null,
migrated: route == 'state' ? haiMigratedIds() : null,
reportUrl: HAI_REPORT_URL,
endpointAud: haiAudienceSecret('endpoint'),
everyday: route == 'state' ? haiEverydayLevels() + (settings.everydayWeatherDevice != null ? [weatherDevice: "dev:${settings.everydayWeatherDevice.id}".toString()] : [:]) : null,
runtime: state.runtimeSummary, runtimeAt: state.runtimeSummaryAt,
runtimeCheckedAt: state.runtimeCheckedAt, now: now(),
line: recent?.line, lineNote: recent?.note, held: held, since: recent != null ? params?.since : null,
canSend: true,
ai: route == 'state' ? haiAiStatus() : null])
if (out.store != null) {
filedPut('editorDrafts', ((Map) out.store).drafts)
state.editorDraftSeq = ((Map) out.store).draftSeq
}
Map checked = route == 'check' ? out.body : (route == 'rule' && out.body instanceof Map ? ((Map) out.body).check : null)
if (checked != null && out.receiptable == true && out.rule instanceof Map) {
long receiptStarted = timeCheck ? now() : 0L
checked.receipt = editorCheckReceipt((Map) out.rule, checked.ruleHash as String)
if (timeCheck) timing.receiptMs = now() - receiptStarted
}
} catch (Exception e) {
log.warn "HAI Engine editor: ${route} failed: ${e.message}"
out = [status: 500, body: [ok: false, error: 'INTERNAL', message: 'The hub could not complete this request. Your edits in the page are kept.']]
}
long serializeStarted = timeCheck ? now() : 0L
String response = groovy.json.JsonOutput.toJson(out.body)
if (timeCheck) {
if (timing.digestCanonicalJsonMs != null) state.haiDigestProfiledUntil = state.haiDiagnosticsUntil
timing.serializeMs = now() - serializeStarted
timing.totalMs = now() - checkStarted
List timed = 'inventoryMs optionsMs validateMs renderMs sentencesMs hashMs proposalMs proposeValidateMs proposeChecklistMs proposeRenderDiffMs proposeScenariosMs proposeEvidenceMs proposeInventoryDigestMs digestTypedMs digestFastJsonMs digestCanonicalJsonMs digestHashMs'.split(' ').collect { String k -> "${k[0..-3].replace('propose', 'proposal')}=${timing[k] ?: 0}" }
timed += ["digestFallback=${timing.digestFallback ?: false}", "proposalPacketHash=${timing.proposePacketHashMs ?: 0}", "receipt=${timing.receiptMs ?: 0}", "serialize=${timing.serializeMs}", "total=${timing.totalMs}"]
log.info "HAI editor check timings (ms): ${timed.join(', ')}"
}
return editorJson(out.status as int, response)
}
Object editorJson(int status, String data) {
if (haiTooBigForCloud(data)) return haiCloudTooBig(data)
return render(status: status, contentType: 'application/json', data: data, headers: ['Cache-Control': 'no-store'])
}
@Field static final int HAI_CLOUD_MAX_BYTES = 120000
boolean haiTooBigForCloud(String data) {
if (data == null || data.length() * 3 <= HAI_CLOUD_MAX_BYTES || !haiViaCloud()) return false
return data.length() > HAI_CLOUD_MAX_BYTES || data.getBytes('UTF-8').length > HAI_CLOUD_MAX_BYTES
}
Object haiCloudTooBig(String data, boolean html = false) {
int kb = (int) Math.ceil(data.getBytes('UTF-8').length / 1024.0d)
String msg = "This answer is ${kb} KB, more than Hubitat's cloud relay carries (about 128 KB), so it would fail there. Open this page on your home network.".toString()
if (html) return render(status: 413, contentType: 'text/html', headers: ['Cache-Control': 'no-store'],
data: "<!doctype html><meta charset=\"utf-8\"><title>HAI</title><p>${msg}</p>".toString())
return render(status: 413, contentType: 'application/json', headers: ['Cache-Control': 'no-store'],
data: groovy.json.JsonOutput.toJson([ok: false, error: 'TOO_LARGE_FOR_CLOUD', message: msg]))
}
Map editorCheckReceipt(Map doc, String ruleHash = null) {
try {
Map pp = runtimePlat()
Map inv = rtWithRuleVariables(runtimeCheckedInventory(pp, doc), [doc])
return editorReceipt(haiReceiptKey(), ruleHash ?: jsonHash(doc, SCHEMA_DEFAULTS), rtInventoryVersion(inv), ENGINE_APP_VERSION)
} catch (Exception e) {
log.warn "HAI Engine editor: no check receipt (${e.message})"
return null
}
}
@Field static final String RUNTIME_CHILD_NAME = 'HAI Rule Container (Preview)'
def uninstalled() {
unschedule()
try {
List ids = ((Map) (state.migPairs ?: [:])).findAll { k, v -> v instanceof Map && (((Map) v).phase == 'switched') && ((Map) v).rmWasPaused != true }
.collect { k, v -> k as String }
if (ids) { RMUtils.sendAction(ids, 'resumeRule', app.label, '5.0'); haiInfo "HAI Engine: resumed ${ids.size()} Rule Machine rule(s) whose HAI copy is being removed" }
} catch (Exception e) { log.warn "HAI Engine: Rule Machine rules switched over to HAI could not be resumed (${e.message}); resume them in Rule Machine" }
ENGINE_FILED.each { String n -> try { deleteHubFile(engineFiledFile(n)) } catch (Exception ignored) { } }
getChildApps()?.each { deleteChildApp(it.id) }
}
void runtimeSyncVariableRegistration() {
List want = runtimeIdList(settings.runtimeWritableVariables).unique()
List had = runtimeIdList(state.runtimeRegisteredVars).unique()
Map delta = rtRegistrationDelta(had, want)
List confirmed = new ArrayList(had)
if (delta.drop) {
try { removeInUseGlobalVar((List) delta.drop); confirmed.removeAll((List) delta.drop) }
catch (Exception e) { log.warn "HAI Runtime: could not release ${delta.drop}: ${e.message}" }
}
if (delta.add) {
try { addInUseGlobalVar((List) delta.add); confirmed.addAll((List) delta.add) }
catch (Exception e) { log.warn "HAI Runtime: could not register ${delta.add}: ${e.message}" }
}
state.runtimeRegisteredVars = confirmed.unique()
}
void renameVariable(String oldName, String newName) {
List next = rtRenamedWritable(runtimeIdList(settings.runtimeWritableVariables), oldName, newName)
if (next == null) return
app.updateSetting('runtimeWritableVariables', [type: 'enum', value: next])
List confirmed = runtimeIdList(state.runtimeRegisteredVars)
state.runtimeRegisteredVars = confirmed.findAll { it != oldName }
rtLog(runtimePlat(), [what: 'writableVariableRenamed', from: oldName, to: newName])
rtPushAllow(runtimePlat())
}
void runtimeInit() {
unschedule('haiWatchdog')
unsubscribe()
schedule(RT_WATCHDOG_CRON, 'haiWatchdog')
unschedule('haiReconcileSoon')
unschedule('haiAutoActivateQueued')
unschedule('haiWatchdogMore')
try {
List staging = ((Map) rtRegistry(runtimePlat()).rules).values().findAll { ((Map) it).state == 'staging' }.collect { ((Map) it).ruleId as String }
if (staging) runIn(5, 'haiReconcileSoon', [overwrite: true, data: [ids: staging]])
} catch (Exception e) { log.warn "HAI Runtime: pending reconciles not re-armed (${e.message})" }
if (state.runtimeAutoQueue) runIn(5, 'haiAutoActivateQueued', [overwrite: true])
runIn(8, 'haiWriteAmStateNow')
subscribe(location, 'systemStart', 'haiSystemStart')
haiSigningKey()
subscribe(location, 'haiRuleSubmit', 'haiRuleSubmit')
subscribe(location, RT_API_EVENT, 'haiRuleApi')
subscribe(location, RT_COMMITTED_EVENT, 'haiRuleCommitted')
unschedule('haiBackupDaily')
schedule(HAI_BACKUP_CRON, 'haiBackupDaily')
unschedule('haiUpdateCheck')
if (HAI_ENVIRONMENT == 'PREVIEW') { schedule(HAI_UPDATE_CRON, 'haiUpdateCheck'); if (state.haiLatest == null) runIn(30, 'haiUpdateCheck') }
if (settings.haiAutoApproveNew != false) subscribe(location, 'deviceJoin', 'haiDeviceJoined')
if (!state.accessToken) {
try { createAccessToken() } catch (Exception e) { haiOAuthSelf('HAI Engine') }
}
runtimeSyncVariableRegistration()
haiRuleKeyNew()
rtPushAllow(runtimePlat())
runtimeRefreshEditorLinks()
}
Map runtimeKeepText(Map pp, String text, Map identity = null, Map doc = null, String arrive = null) {
boolean timeKeep = HAI_ENVIRONMENT == 'DEV' && haiDiagnosticsActive()
long keepStarted = timeKeep ? now() : 0L
long phaseStarted = keepStarted
Map keepTiming = timeKeep ? [:] : null
Map out = doc != null ? rtImportParsed(pp, doc, text) : rtImportPaste(pp, text)
rtpMark(pp, 'import')
if (timeKeep) { keepTiming.importMs = now() - phaseStarted; phaseStarted = now() }
List kept = ((List) out.results).findAll { it.ok }
List notes = [out.message]
List auto = []
state.runtimeKeptJustNow = kept.collect { ((Map) it.candidate).id as String }
if (!kept.isEmpty()) {
List queue = []
state.runtimeAutoQueue = []
kept.each { Object ko ->
Map k = ko
String cid = ((Map) k.candidate).id as String
String rid = ((Map) k.candidate).ruleId as String
Map done = null
try {
done = runtimeAutoActivate(pp, cid, arrive)
} catch (Exception e) {
String phase = rtInlinePhase(pp, cid, rid)
log.warn "HAI Runtime: inline activation of ${rid} failed (${e.message}); ${phase == 'queue' ? 'queued once' : 'leaving it to the reconciliation of the same claim'}"
if (phase == 'queue') { queue << cid }
else {
runtimeAwaitClaim(rid, identity)
runIn(1, 'haiReconcileSoon', [overwrite: false, data: [ids: [rid]]])
}
auto << [ok: false, code: 'INLINE_FAILED', note: "${rid}: the hub refused the work inline (${e.message}); ${phase == 'queue' ? 'it will be activated in a moment' : 'its activation is being reconciled'}.".toString()]
return
}
auto << done
}
if (!queue.isEmpty()) {
state.runtimeAutoQueue = queue.collect { Object q ->
(q instanceof Map) ? q : [candidateId: q as String, sig: identity?.sig, sentAt: identity?.sentAt, arrive: arrive] }
runInMillis(1, 'haiAutoActivateQueued', [overwrite: false])
notes << "Activation will continue in a moment."
}
}
if (timeKeep) { keepTiming.activateMs = now() - phaseStarted; phaseStarted = now() }
if (kept.size() == 1 && !(identity != null && !auto.isEmpty() && auto.every { it.ok })) {
app.updateSetting('runtimeCandidate', [type: 'enum', value: kept[0].candidate.id])
}
auto.each { Map a -> notes << a.note }
state.runtimeNotice = notes.join(' ')
if (timeKeep) {
keepTiming.finishMs = now() - phaseStarted
keepTiming.totalMs = now() - keepStarted
log.info "HAI runtime keep timings (ms): import=${keepTiming.importMs}, activate=${keepTiming.activateMs}, finish=${keepTiming.finishMs}, total=${keepTiming.totalMs}"
}
return [kept: kept.size(), activated: auto.any { it.ok }, already: auto.any { it.code == 'ALREADY_ACTIVE' },
pending: !((List) (state.runtimeAutoQueue ?: [])).isEmpty() || state.runtimeAwaiting != null,
notice: state.runtimeNotice]
}
def haiAutoActivateQueued() {
List queue = new ArrayList((List) (state.runtimeAutoQueue ?: []))
if (queue.isEmpty()) return
Object head = queue.remove(0)
Map item = (head instanceof Map) ? head : [candidateId: head as String]
String id = item.candidateId as String
state.runtimeAutoQueue = queue
String ruleId = rtCandidate(runtimePlat(), id)?.ruleId as String
Map out
try {
out = runtimeAutoActivate(runtimePlat(), id, item.arrive as String)
} catch (Exception e) {
out = [ok: false, code: 'AUTO_ACTIVATE_FAILED', note: "auto-activation failed: ${e.message}".toString()]
log.error "HAI Runtime: auto-activation of candidate ${id} failed: ${e.message}"
}
state.runtimeNotice = out.note ?: state.runtimeNotice
Map last = (state.runtimeLastSubmit ?: [:])
boolean mayStillCommit = false
try { mayStillCommit = rtInlinePhase(runtimePlat(), id, ruleId) == 'reconcile' } catch (Exception ignored) { }
Map outcome = out.ok == true
? [ok: true, activated: true, pending: false, code: out.code, notice: out.note ?: last.notice, at: now()]
: (mayStillCommit
? [ok: null, activated: false, pending: true, code: out.code, notice: out.note ?: last.notice, at: now()]
: [ok: false, activated: false, pending: false, code: out.code ?: 'AUTO_ACTIVATE_REFUSED',
notice: out.note ?: last.notice, at: now()])
state.runtimeLastSubmit = rtSubmitRecord(last, ruleId, outcome,
item.sig == null ? null : [sig: item.sig, sentAt: item.sentAt])
if (ruleId) { try { runtimeAnnounce(runtimePlat(), true, [ruleId]) } catch (Exception e) { log.warn "HAI Runtime: summary not announced: ${e.message}" } }
if (!queue.isEmpty()) runInMillis(1, 'haiAutoActivateQueued', [overwrite: false])
}
Map runtimeSubmitAbandoned(Exception e) {
boolean busy = (e.message ?: '').contains('excessive hub load')
log.error "HAI Runtime: submission abandoned: ${e.message}"
Map pp = runtimePlat()
int rolled = 0
List claimed = []
((List) (state.runtimeKeptJustNow ?: [])).each { Object id ->
Map cand = rtCandidate(pp, id as String)
if (cand == null) return
if (rtInlinePhase(pp, id, cand.ruleId) == 'reconcile') { claimed << (cand.ruleId as String); return }
if (rtEntry(pp, cand.ruleId as String) == null && rtRemoveCandidate(pp, id).ok) rolled++
}
state.remove('runtimeKeptJustNow')
state.remove('runtimeAutoQueue')
Map current = state.runtimeSubmitNow
state.remove('runtimeSubmitNow')
Map reply = rtAbandonReply(current, claimed, rolled, busy, e.message as String)
if (reply.code == 'INLINE_AWAITING') {
String rid = reply.ruleId as String
runtimeAwaitClaim(rid, [sig: current?.sig, sentAt: current?.sentAt])
claimed.each { Object r -> runIn(1, 'haiReconcileSoon', [overwrite: false, data: [ids: [r as String]]]) }
}
state.runtimeNotice = reply.notice
state.runtimeLastSubmit = reply + [at: now()]
try { runtimeAnnounce(pp, true, [], true) } catch (Exception ignored) { }
return [ok: false, code: reply.code ?: 'ABANDONED', ruleId: reply.ruleId,
sentAt: current?.sentAt, accepted: true, notice: reply.notice]
}
def haiRuleSubmit(evt) {
haiCodeCheck()
state.remove('runtimeKeptJustNow')
try {
runtimeSubmit(evt)
} catch (Exception e) {
runtimeSubmitAbandoned(e)
}
}
void runtimeSubmit(evt) { runtimeSubmitBody(evt?.data as String) }
Map runtimeSubmitBody(Object raw, Map inProcess = null) {
Map timing = null
if (HAI_ENVIRONMENT == 'DEV' && haiDiagnosticsActive()) timing = [_from: now(), _last: now()]
Map pp = runtimePlat([hold: true, checked: inProcess?.checked, timing: timing])
List seen = (state.runtimeSubmitSeen ?: [])
Map chk = rtCheckSubmission(raw, state.runtimePairing, seen, now(), inProcess?.canonical as String)
rtpMark(pp, 'verify')
if (!chk.ok) {
log.warn "HAI Runtime: ignored a request from the editor (${chk.code})"
state.runtimeLastSubmit = [ok: false, code: chk.code, sig: chk.sig, ruleId: chk.ruleId,
sentAt: chk.sentAt, at: now()]
runtimeAnnounce(pp, true, [], true)
return [ok: false, code: chk.code, ruleId: chk.ruleId, sentAt: chk.sentAt, accepted: false]
}
if (chk.linkRejected) log.warn 'HAI Runtime: signed editor link rejected because its local path or query shape is not recognised'
if (chk.link) state.runtimeEditorUrl = chk.link
List next = new ArrayList(seen)
next << chk.sig
while (next.size() > RT_SUBMIT_SEEN) next.remove(0)
state.runtimeSubmitSeen = next
Map result
state.runtimeSubmitNow = [op: chk.op, ruleId: chk.ruleId, sentAt: chk.sentAt, sig: chk.sig]
if (chk.op == 'run') {
String arrive = chk.lifecycle as String
Map kept = runtimeKeepText(pp, chk.rule as String, [sig: chk.sig, sentAt: chk.sentAt], chk.doc instanceof Map ? chk.doc : null, arrive)
if (arrive != null && kept.already == true) runtimeArriveLive(pp, chk.ruleId as String, arrive)
Map e = rtEntry(pp, chk.ruleId)
result = [ok: kept.kept > 0, activated: kept.activated, already: kept.already, pending: kept.pending,
stopped: e?.state == 'disabled', notice: kept.notice]
} else {
String op = [pause: 'pause', resume: 'resume', stop: 'disable', start: 'enable', remove: 'remove', runNow: 'runNow'].get(chk.op)
Map r = op == 'remove' && chk.rule == 'stopFirst' ? rtRemoveStoppingFirst(pp, chk.ruleId as String)
: op != null ? rtRuleOp(pp, chk.ruleId as String, op) : rtEndpointOp(pp, chk.op as String, chk.ruleId as String, chk.rule as String)
if (op == null) haiDebug "HAI Runtime: end point ${chk.op} ${chk.ruleId}: ${r?.ok ? 'done' : (r?.code ?: 'refused')}"
result = [ok: r?.ok == true, notice: r?.ok ? 'done' : (r?.message ?: r?.code ?: 'refused')]
}
state.runtimeLastSubmit = rtSubmitRecord(null, chk.ruleId as String,
result + [op: chk.op, at: now()], [sig: chk.sig, sentAt: chk.sentAt])
if (chk.op == 'run') runtimeSummaryPatch(pp, chk.ruleId as String)
else runtimeAnnounce(pp, true, [chk.ruleId as String], false)
state.remove('runtimeSubmitNow')
if (timing != null && chk.op == 'run') {
rtpMark(pp, 'announce')
Map t = new LinkedHashMap(timing)
long total = now() - (t.remove('_from') as long)
t.remove('_last')
log.info "HAI runtime put-on-hub timings (ms): ${t.collect { k, v -> "${k}=${v}" }.join(', ')}, total=${total}, receipt=${inProcess?.checked != null ? 'verified' : 'none'}"
}
return [ok: result.ok == true, code: result.ok == true ? 'ACCEPTED' : 'REFUSED', ruleId: chk.ruleId,
sentAt: chk.sentAt, op: chk.op, accepted: true, notice: result.notice]
}
@Field static final java.util.concurrent.ConcurrentHashMap HAI_API_REFUSED = new java.util.concurrent.ConcurrentHashMap()
@Field static final java.util.concurrent.ConcurrentHashMap HAI_API_SEEN = new java.util.concurrent.ConcurrentHashMap()
def haiRuleApi(evt) {
haiCodeCheck()
String token = state.haiApiToken as String
Map seen = HAI_API_SEEN.get(app.id as String)
if (seen == null) { seen = new LinkedHashMap(); HAI_API_SEEN.put(app.id as String, seen) }
Map checked = rtAppApiVerify(token, evt.value, evt.data, now(), seen)
String refused = checked.refused as String
if (refused != null) {
String from = ((checked.from ?: 'an app') as String).take(80).replaceAll(/[<>]/, '')
Map once = HAI_API_REFUSED.get(app.id as String)
if (once == null) { once = new java.util.concurrent.ConcurrentHashMap(); HAI_API_REFUSED.put(app.id as String, once) }
if (rtAppApiFirstRefusal(once, from, 50)) {
log.warn "HAI Engine: ignored a request from ${from}: ${refused == 'TOKEN_IN_CLEAR' ? 'it sends the API token in clear; update its hai_rule_api.groovy' : refused == 'NO_SIGNATURE' ? 'it is not signed with the API token' : 'its signature is wrong, old or repeated'}. " +
"Copy the API token from the HAI Engine page (Rule end points) into that app. Said once per app until the hub restarts."
}
return
}
Map req = checked.req
if (!req.op) req.op = evt.value
Map pp = runtimePlat()
Map out = rtAppApi(pp, req, settings.runtimeAppApi != false, (state.runtimePaused ?: []))
haiDebug "HAI Runtime: ${out.from} asked to ${out.op}${out.rule ? ' ' + out.rule : ''}: ${out.ok ? 'done' : out.code}"
String value = (req.id ?: out.op ?: 'reply') as String
sendLocationEvent(name: RT_API_REPLY, value: value, data: rtAppApiReplyData(token, value, out, now(), UUID.randomUUID().toString()),
isStateChange: true, displayed: false, descriptionText: "HAI Runtime answered ${out.from}".toString())
if (out.ok && out.op != 'list') runIn(2, 'haiReconcileSoon', out.rule ? [overwrite: false, data: [ids: [out.rule as String]]] : [overwrite: false])
}
void runtimeArriveLive(Map pp, String ruleId, String arrive) {
Map e = rtEntry(pp, ruleId)
if (e == null || !(e.state in ['active', 'disabled'])) return
boolean stopped = e.state == 'disabled'
if (arrive == 'stopped') { if (!stopped) rtRuleOp(pp, ruleId, 'disable'); return }
rtRuleOp(pp, ruleId, arrive == 'paused' ? 'pause' : 'resume')
if (stopped) rtRuleOp(pp, ruleId, 'enable')
}
Map runtimeAutoActivate(Map pp, String candidateId, String arrive = null) {
Map cand0 = rtCandidate(pp, candidateId)
Map out = rtActivateAuto(pp, candidateId, false, arrive)
if (!out.ok && out.code in ['CANDIDATE_MISSING', 'ALREADY_ACTIVE', 'CHALLENGE_LIMIT']) {
return [ok: false, code: out.code, note: "${rtCandidate(pp, candidateId)?.ruleId}: not activated (${out.message ?: out.code}).".toString()]
}
((List) (out.chainWarnings ?: [])).each { Object co ->
Map c = co
if (c.kind == 'alsoUsed') return
log.warn "HAI Runtime: ${cand0?.ruleId ?: candidateId} - ${c.message}"
}
Map cand = rtCandidate(pp, candidateId)
state.runtimeLastActivation = [ruleId: cand?.ruleId, ok: out.ok || out.code == 'STAGE_UNCONFIRMED', code: out.code,
message: out.message, claimId: out.claimId, challengeId: out.challengeId,
activatedAt: now()]
haiDebug "HAI Engine: activated ${cand?.ruleId} (${out.code ?: (out.ok ? 'staged' : 'refused')})"
Map soon = cand?.ruleId ? [overwrite: false, data: [ids: [cand.ruleId as String]]] : [overwrite: false]
runIn(1, 'haiReconcileSoon', soon)
if (out.code == 'STAGE_UNCONFIRMED') runIn(15, 'haiReconcileSoon', soon)
boolean ok = out.ok || out.code == 'STAGE_UNCONFIRMED'
return [ok: ok, code: out.code, note: ok ?
"${cand?.ruleId}: activated; its rule app commits it.".toString() :
"${cand?.ruleId}: activation refused (${((List) (out.refusals ?: [])).isEmpty() ? (out.message ?: out.code) : ((List) out.refusals).collect { ((Map) it).message }.join('; ')}).".toString()]
}
List runtimeLiveUsers(Object deviceId) {
List out = []
String devId = rtBareId(deviceId as String)
Closure take = { Object a ->
String label = null
String id = null
try {
label = ((a instanceof Map ? (((Map) a).label ?: ((Map) a).name) : (a?.label ?: a?.name)) ?: '').toString().replaceAll('<[^>]+>', '').trim()
id = (a instanceof Map ? ((Map) a).id : a?.id)?.toString()
} catch (Exception e) { label = null }
if (!label || id == app.id?.toString() || label.startsWith('[HAI]') || label.startsWith('HAI ')) return
out << label
}
List inProc = null
try { inProc = getAppsUsingDevice(devId as Long) } catch (Exception e) { inProc = null }
if (inProc != null) {
inProc.each { take(it) }
return out.unique()
}
try {
httpGet([uri: "http://127.0.0.1:8080/device/fullJson/${devId}".toString(),
timeout: 8, textParser: false]) { resp ->
((List) (resp?.data?.appsUsing ?: [])).each { take(it) }
}
} catch (Exception e) {
log.warn "HAI Runtime: could not read who else uses ${deviceId} (${e.message})"
}
return out.unique()
}
def haiRuleCommitted(evt) {
Map n = [:]
try { n = new groovy.json.JsonSlurper().parseText(evt?.data as String) } catch (Exception e) { n = [:] }
String ruleId = (n.ruleId ?: evt?.value) as String
if (ruleId == null) return
Map pp = runtimePlat()
Map e = rtEntry(pp, ruleId)
if (e == null || e.state != 'staging' || e.claimId != n.claimId || e.challengeId != n.challengeId) return
Map r = rtRecordCommit(pp, [challengeId: e.challengeId, claimId: e.claimId, epoch: n.epoch,
ruleHash: n.ruleHash, at: n.at, disabled: n.disabled == true])
if (r.ok && !r.already) {
runtimeSettleAwait(pp)
haiDebug "HAI Runtime: ${ruleId} reported its commit (version ${n.epoch})"
try { runtimeAnnounce(pp, true, [ruleId]) } catch (Exception ex) { log.warn "HAI Runtime: summary not announced: ${ex.message}" }
}
}
void runtimeAwaitClaim(String ruleId, Map identity = null) {
state.runtimeAwaiting = [ruleId: ruleId, at: now(), sig: identity?.sig, sentAt: identity?.sentAt]
runIn(15, 'haiReconcileSoon', [overwrite: false, data: [ids: [ruleId]]])
runIn(((RT_INLINE_AWAIT_MS / 1000L) as int) + 2, 'haiSettleAwait', [overwrite: true])
}
def haiSettleAwait() {
Map pp = runtimePlat()
runtimeSettleAwait(pp)
try { runtimeAnnounce(pp, true, [], true) } catch (Exception e) { log.warn "HAI Runtime: summary not announced: ${e.message}" }
}
void runtimeSettleAwait(Map pp) {
Map aw = state.runtimeAwaiting
if (aw == null) return
String rid = aw.ruleId as String
Map e = rtEntry(pp, rid)
boolean expired = now() - ((aw.at ?: 0L) as long) > RT_INLINE_AWAIT_MS
Map out = rtAwaitOutcome(e?.state, !((List) (state.runtimeAutoQueue ?: [])).isEmpty(), expired)
if (!out.settled) return
state.remove('runtimeAwaiting')
Map last = (state.runtimeLastSubmit ?: [:])
if (last.ruleId != rid) return
String note = out.activated ?
"${rid}: activated after the hub refused the work in the same execution.".toString() :
(e?.state == 'staging' ?
"${rid}: activation was not confirmed within 60 seconds; check the runtime page. The claim stands and is being reconciled.".toString() :
"${rid}: it was not activated (${e?.state ?: 'the claim did not commit'}). Activate it on the runtime page.".toString())
state.runtimeNotice = note
state.runtimeLastSubmit = rtSubmitRecord(last, rid,
[ok: out.activated ? true : (e?.state == 'staging' ? null : false),
pending: false, activated: out.activated, notice: note, at: now()],
aw?.sig == null ? null : [sig: aw.sig, sentAt: aw.sentAt])
}
def haiReconcileSoon(Map data = null) {
Map pp = runtimePlat([hold: true])
List ids = data?.ids instanceof List ? ((List) data.ids).collect { it as String } : null
rtReconcileAll(pp, null, ids)
runtimeSettleAwait(pp)
List all = ((Map) rtRegistry(pp).rules).keySet().collect { it as String }
try { runtimeAnnounce(pp, false, ids ?: all) } catch (Exception e) { log.warn "HAI Runtime: summary not announced: ${e.message}" }
}
def haiWatchdog() {
haiCodeCheck()
if (state.runtimeWatchdogBusy == true) {
state.runtimeWatchdogSkipped = ((state.runtimeWatchdogSkipped ?: 0) as int) + 1
return
}
state.runtimeWatchdogBusy = true
try {
haiWatchdogTick()
} finally {
state.runtimeWatchdogBusy = false
}
}
def haiWatchdogMore(Map data) {
if (state.runtimeWatchdogBusy == true) return
state.runtimeWatchdogBusy = true
try {
int round = ((data?.round ?: 1) as int)
Map pp = runtimePlat([hold: true, budgetMs: RT_WATCHDOG_BUDGET_MS])
pp.deferredOnly = { Object u -> true }
Map wd = rtWatchdog(pp)
Map lights = wd.lights
rtReconcileAll(pp, lights, lights.keySet().collect { it as String })
Map cache = (state.rtSumRows ?: [:])
List moved = lights.findAll { k, v -> ((Map) cache.get(k as String))?.mark != ((Map) v).mark }.keySet().collect { it as String }
if (moved) { try { runtimeAnnounce(pp, false, moved, false, lights) } catch (Exception e) { log.warn "HAI Runtime: summary not announced: ${e.message}" } }
haiWatchdogContinue(wd, round)
} finally {
state.runtimeWatchdogBusy = false
}
}
void haiWatchdogContinue(Map wd, int round) {
if (((List) (wd?.deferred ?: [])).isEmpty() || round >= RT_WATCHDOG_MORE_MAX) return
runIn(3, 'haiWatchdogMore', [overwrite: true, data: [round: round + 1]])
}
void haiWatchdogTick() {
Map pp = runtimePlat([hold: true, budgetMs: RT_WATCHDOG_BUDGET_MS])
Map wd = rtWatchdog(pp)
Map lights = wd.lights
haiWatchdogContinue(wd, 0)
rtReconcileAll(pp, lights)
runtimeSettleAwait(pp)
runtimeRmPausedPoll(pp)
try { haiAnnounceSwitched() } catch (Exception e) { log.warn "HAI Runtime: switch-overs not announced: ${e.message}" }
runtimeWriteAmState(pp)
if (rtpCall(pp, 'overBudget', null) != true) runtimeRefreshEditorLinks()
String mark = rtAnnounceMark(pp, lights, state.runtimeLastSubmit)
boolean fresh = now() - ((state.runtimeAnnouncedAt ?: 0L) as long) < 900000L
if (mark == state.runtimeAnnounceMark && fresh && rtpCall(pp, 'overBudget', null) != true &&
((List) (rtpCall(pp, 'slowChildren', null) ?: [])).isEmpty()) state.runtimeCheckedAt = now()
if (mark == state.runtimeAnnounceMark && fresh) return
if (rtpCall(pp, 'overBudget', null) == true) return
state.runtimeAnnounceMark = mark
state.runtimeAnnouncedAt = now()
Map cache = (state.rtSumRows ?: [:])
List moved = lights.findAll { k, v -> ((Map) cache.get(k as String))?.mark != ((Map) v).mark }.keySet().collect { it as String }
try { runtimeAnnounce(pp, false, moved, false, lights) } catch (Exception e) { log.warn "HAI Runtime: summary not announced: ${e.message}" }
if (rtpCall(pp, 'overBudget', null) == true || ((List) (rtpCall(pp, 'slowChildren', null) ?: [])).size() > 0) state.remove('runtimeAnnounceMark')
}
void runtimeRmPausedPoll(Map pp) {
List ids = rtRmPausedIds(pp)
((Map) (state.migPairs ?: [:])).values().each { Object po ->
String id = ((Map) po).rmId as String
if (id != null && !ids.contains(id)) ids << id
}
Map seen = (state.runtimeRmPaused ?: [:])
if (seen.keySet().any { !ids.contains(it) }) state.runtimeRmPaused = seen.findAll { k, v -> ids.contains(k) }
List gone = (state.runtimeRmGone ?: [])
if (gone.any { !ids.contains(it) }) state.runtimeRmGone = gone.findAll { ids.contains(it) }
if (ids.isEmpty()) return
try { asynchttpGet('haiRmAppsList', [uri: 'http://127.0.0.1:8080/hub2/appsList', contentType: 'application/json', timeout: 20], [ids: ids]) }
catch (Exception e) { log.warn "HAI Runtime: could not read the hub's app list for Rule Machine pause: ${e.message}" }
}
def haiRmAppsList(resp, Map data) {
Object body = null
try { if (resp.status == 200) body = resp.json } catch (Exception e) { body = null }
Map got = body == null ? [ok: false] : haiAppsPausedFrom(body)
if (got.ok != true || !((List) got.checked).contains(app.id.toString())) return
HAI_APPS_SEEN.put(app.id as String, [at: now(), read: got])
Map read = rtRmListReading((List) (data?.ids ?: []), got, (state.runtimeRmPaused ?: [:]), (state.runtimeRmGone ?: []))
List events = read.events
if ((List) read.gone != (state.runtimeRmGone ?: [])) state.runtimeRmGone = read.gone
if ((Map) read.seen != (state.runtimeRmPaused ?: [:])) state.runtimeRmPaused = read.seen
events.each { Map ev ->
sendLocationEvent(name: RT_PAUSED_EVENT, value: ev.id, isStateChange: true, displayed: false, data: haiSignedData(RT_PAUSED_EVENT, ev.id, [paused: ev.paused]),
descriptionText: "Rule Machine rule ${ev.id} ${ev.paused ? 'paused' : 'resumed'}".toString())
}
}
def haiRmStatus(resp, Map data) {
Boolean paused = null
boolean answered = false
try { answered = resp.status in [200, 404]; if (resp.status == 200) paused = rtRmPausedFrom(resp.json) } catch (Exception e) { paused = null }
String id = data?.id as String
if (id != null && answered) {
List gone = (state.runtimeRmGone ?: [])
boolean isGone = paused == null
if (isGone != gone.contains(id)) state.runtimeRmGone = isGone ? gone + [id] : gone - [id]
}
Map seen = new LinkedHashMap((Map) (state.runtimeRmPaused ?: [:]))
if (paused == null || id == null || seen.get(id) == paused) return
Object was = seen.put(id, paused)
state.runtimeRmPaused = seen
if (was != null) sendLocationEvent(name: RT_PAUSED_EVENT, value: id, isStateChange: true, displayed: false, data: haiSignedData(RT_PAUSED_EVENT, id, [paused: paused]),
descriptionText: "Rule Machine rule ${id} ${paused ? 'paused' : 'resumed'}".toString())
}
Map runtimeRecentLine(Object ruleId) {
String id = endpointParam(ruleId)
if (!id) return [line: null, note: null]
String note
Map line = null
try {
Map pp = runtimePlat()
Map e = rtEntry(pp, id)
if (e == null || e.state == 'removed') note = 'not-in-runtime'
else if (runtimeChild(e.childId) == null) note = 'no-rule-app'
else {
Map view = null
try { view = runtimeChildCall(e.childId, 'haiRecent', []); note = tlRecentWhyNot(e, view) }
catch (Exception ex) { note = 'rule-app-failed: ' + ((ex.message ?: ex.toString()) as String).take(80) }
if (note == null) line = tlRecentLine(e, view, runtimeClock(), runtimeRuleName(e), now())
}
} catch (Exception ex) {
note = 'failed: ' + ((ex.message ?: ex.toString()) as String).take(80)
}
if (HAI_ENVIRONMENT == 'DEV' && haiDiagnosticsActive()) log.info "HAI editor haiRecent ${id}: ${line != null ? 'fresh' : note}"
return [line: line, note: line != null ? null : (note ?: 'no-line')]
}
void runtimeSummaryPatch(Map pp, String ruleId) {
Map e = rtEntry(pp, ruleId)
Map sum = new LinkedHashMap((Map) (state.runtimeSummary ?: [:]))
List rules = new ArrayList((List) (sum.rules ?: []))
if (e != null) {
Map patch = [id: ruleId, state: e.state, version: e.epoch, ruleHash: ((e.ruleHash ?: '') as String).take(12), changedAt: e.updatedAt]
int i = rules.findIndexOf { ((Map) it).id == ruleId }
if (i >= 0) rules[i] = ((Map) rules[i]) + patch
else rules.add(0, patch + [name: runtimeRuleName(e)])
}
sum.rules = rules
sum.lastSubmit = state.runtimeLastSubmit
state.runtimeSummary = sum
state.runtimeSummaryAt = now()
}
void runtimeAnnounce(Map pp, boolean force = false, Collection refresh = [], boolean cacheOnly = false, Map lights = null) {
long t0 = now()
List entries = ((Map) rtRegistry(pp).rules).values().findAll { it.state != 'removed' }
boolean readFailed = false
Closure read = { Map e, boolean detailed ->
if (rtpCall(pp, 'skipChild', e.childId) == true) return null
Map s = null
def child = runtimeChild(e.childId)
if (child != null) {
try { s = rtpCall(pp, 'child', [e.childId, 'haiRuntimeState', []]) } catch (Exception ex) { s = null; readFailed = true }
String label = rtChildLabel(runtimeNameWithValues(runtimeRuleName(e)), e.ruleId, e.state, (s?.paused ?: false) as boolean)
if (child.label != label) {
try { child.updateLabel(label) } catch (Exception ex) { log.warn "HAI Runtime: could not relabel ${e.ruleId}: ${ex.message}" }
}
runtimeOfferEditorLink(e)
}
Map names = runtimeDeviceNames(s?.doc instanceof Map ? rtDeviceIds((Map) s.doc) : [])
if (s?.doc instanceof Map) { s = new LinkedHashMap(s); s.doc = tlFreshNames((Map) s.doc, names) }
return [state: s, older: detailed ? rtOlderDocs(pp, e.ruleId as String, names) : [:]]
}
Map joined = tlSumCacheJoin((Map) state.rtSumRows, ((Map) (state.runtimeSummary ?: [:])).rules)
Map got = tlSummaryRefresh(entries, joined, refresh, cacheOnly, lights, read, runtimeClock(), { Map e -> runtimeRuleName(e) })
Map cache = got.cache
List rows = got.rows
int reads = got.reads as int
Map links = (state.runtimeChildLinks ?: [:])
Map keptLinks = tlPruneChildLinks(links, entries.collect { it.childId })
if (keptLinks.size() != links.size()) state.runtimeChildLinks = keptLinks
state.runtimePaused = rows.findAll { it.paused == true && it.state == 'active' }.collect { it.id as String }.sort()
runtimeQueueAmState(pp)
Map sum = tlSummaryCompose(rows, now())
if (now() - t0 > 1000L) haiDebug "HAI Runtime: summary took ${now() - t0} ms, ${reads} of ${rows.size()} rules read"
sum.lastSubmit = state.runtimeLastSubmit
sum.stageFailure = rtStageFailure(pp)
if (readFailed) {
String asked = rtAnyChild(pp)
Map built = asked == null ? null : rtChildBuild(pp, asked)
if (built != null && !built.ok) {
sum.childBuild = [message: RT_CHILD_MISMATCH_WORDS, engineChildBuild: RT_CHILD_BUILD, childBuild: built.build]
log.warn "HAI Runtime: ${RT_CHILD_MISMATCH_WORDS} HAI Engine expects ${RT_CHILD_BUILD}; app ${asked} answered ${built.build ?: 'nothing'} (${built.why})."
}
}
sum.writableVariables = runtimeIdList(settings.runtimeWritableVariables)
state.runtimeSummary = sum
state.rtSumRows = tlSumCacheMarks(cache)
state.runtimeSummaryAt = now()
if (!cacheOnly) state.runtimeAnnouncedAt = now()
}
boolean runtimeOfferEditorLink(Map e) {
if (!state.accessToken || !e?.childId || !e?.ruleId) return false
String base = "/apps/api/${app.id}/editor?access_token=${state.accessToken}&aud=${haiAudienceSecret('editor')}&v=${EDITOR_PAGE_SHA256.take(12)}".toString()
String link = "${base}#rule=${URLEncoder.encode(e.ruleId as String, 'UTF-8')}".toString()
Map linked = (state.runtimeChildLinks ?: [:])
String mark = jsonHash(link).take(12)
if (linked[e.childId as String] == mark) return false
try {
if (runtimeChildCall(e.childId, 'haiEditorLink', [link]) != true) throw new Exception('child did not accept the link')
linked[e.childId as String] = mark
state.runtimeChildLinks = linked
return true
} catch (Exception ex) { log.warn "HAI Runtime: could not link ${e.ruleId} to the editor: ${ex.message}" }
return false
}
void runtimeRefreshEditorLinks() {
if (!state.accessToken) return
int sent = 0
for (Map e : ((Map) rtRegistry(runtimePlat()).rules).values()) {
if (e.state != 'removed' && runtimeOfferEditorLink(e)) sent++
if (sent >= 4) {
runIn(15, 'haiRefreshEditorLinksNow', [overwrite: true])
break
}
}
}
def haiRefreshEditorLinksNow() { runtimeRefreshEditorLinks() }
def haiWriteAmStateNow() {
try { runtimeWriteAmState(runtimePlat([budgetMs: RT_WATCHDOG_BUDGET_MS])) }
catch (Exception e) { log.warn "HAI Runtime: ${AM_STATE_FILE} not written at start: ${e.message}" }
}
@Field static final byte[] ENGINE_KEY_MASK = 'hai.engine.key.mask/1'.getBytes('UTF-8')
@Field static final String AM_STATE_FILE = 'hai-am-state.json'
@Field static final java.util.concurrent.ConcurrentHashMap AM_STATE_DOCS = new java.util.concurrent.ConcurrentHashMap()
Object amScrubNulls(Object v) {
if (v instanceof Map) {
Map out = [:]
((Map) v).each { Object k, Object x -> if (x != null) out.put(k as String, amScrubNulls(x)) }
return out
}
if (v instanceof List) return ((List) v).findAll { it != null }.collect { amScrubNulls(it) }
return v
}
String runtimeAmStateKey(Map pp) {
Map reg = rtRegistry(pp)
List rows = ((Map) reg.rules).values().findAll { it.state != 'removed' }
.collect { Object o -> Map e = o; [e.ruleId, e.state, e.epoch, e.ruleHash].collect { it == null ? '' : it.toString() } }
.sort { it[0] }
return jsonHash(amScrubNulls([revision: reg.revision, rules: rows, capabilities: AM_CAPS_SHA256,
paused: ((List) (state.runtimePaused ?: [])).sort()]))
}
Map runtimeAmStateDoc(Map pp) {
List paused = (state.runtimePaused ?: [])
Map seen = AM_STATE_DOCS.get(app.id.toString())
Map docs = [:]
List rows = ((Map) rtRegistry(pp).rules).values().findAll { it.state != 'removed' }.collect { e ->
String key = "${e.childId}:${e.epoch}:${e.ruleHash}".toString()
Object doc = seen != null && seen.containsKey(key) ? seen.get(key) : runtimeRuleDoc(pp, e)
if (doc != null) docs.put(key, doc)
[ruleId: e.ruleId, appId: e.childId, state: e.state, paused: paused.contains(e.ruleId),
name: runtimeNameWithValues(runtimeRuleName(e)), doc: doc]
}
AM_STATE_DOCS.put(app.id.toString(), docs)
Map connectors = [:]
try { connectors = schemaMarkConnectors([variables: schemaHubVariableRows(getAllGlobalVars()), devices: [:]]) } catch (Exception ignored) { }
Map feed = amFeed(rows, runtimeDeviceNames(), null, now(), connectors)
['capabilities', 'capabilityIdsHash', 'capabilitiesError'].each { feed.remove(it) }
feed.capabilitiesHash = AM_CAPS_SHA256
Object at = feed.remove('generatedAt')
String hash = jsonHash(amScrubNulls(feed))
feed.contentHash = hash
feed.generatedAt = at
return feed
}
void runtimeWriteAmState(Map pp) {
try {
String key = runtimeAmStateKey(pp)
if (key == state.runtimeAmStateKey) return
if (rtpCall(pp, 'overBudget', null) == true || ((List) (rtpCall(pp, 'slowChildren', null) ?: [])).size() > 0) return
Map doc = runtimeAmStateDoc(pp)
if (((List) (rtpCall(pp, 'slowChildren', null) ?: [])).size() > 0) return
uploadHubFile(AM_STATE_FILE, groovy.json.JsonOutput.toJson(doc).getBytes('UTF-8'))
state.runtimeAmStateKey = key
state.runtimeAmStateHash = doc.contentHash
state.remove('runtimeAmStateError')
} catch (Throwable e) {
state.runtimeAmStateError = e.toString()
log.warn "HAI Runtime: ${AM_STATE_FILE} not written: ${e}"
}
}
void runtimeQueueAmState(Map pp) {
try {
if (runtimeAmStateKey(pp) != state.runtimeAmStateKey) runIn(2, 'haiWriteAmStateNow', [overwrite: true])
} catch (Throwable e) {
state.runtimeAmStateError = e.toString()
log.warn "HAI Runtime: ${AM_STATE_FILE} not queued: ${e}"
}
}
def automationMapGet() {
try {
Map pp = runtimePlat()
List paused = (state.runtimePaused ?: [])
List rows = ((Map) rtRegistry(pp).rules).values().findAll { it.state != 'removed' }.collect { e ->
[ruleId: e.ruleId, appId: e.childId, state: e.state, paused: paused.contains(e.ruleId),
name: runtimeNameWithValues(runtimeRuleName(e)), doc: runtimeRuleDoc(pp, e)]
}
Map connectors = [:]
try { connectors = schemaMarkConnectors([variables: schemaHubVariableRows(getAllGlobalVars()), devices: [:]]) } catch (Exception e) { }
return render(contentType: 'application/json', data: groovy.json.JsonOutput.toJson(amFeed(rows, runtimeDeviceNames(), amCapabilities(), now(), connectors)))
} catch (Throwable t) {
log.error "HAI Runtime: the Automation Map feed failed: ${t}"
return render(status: 500, contentType: 'application/json',
data: groovy.json.JsonOutput.toJson([contract: AM_CONTRACT, engine: RT_ENGINE, haiVersion: RT_VERSION,
error: 'FEED_FAILED', message: "the feed could not be built: ${t.message ?: t}".toString()]))
}
}
def haiSystemStart(evt) {
haiCodeCheck()
Map pp = runtimePlat()
((Map) rtRegistry(pp).rules).values().each { e ->
if (e.state == 'removed' || runtimeChild(e.childId) == null) return
try { runtimeChildCall(e.childId, 'haiReconcile', []) } catch (Exception ex) { log.warn "HAI Runtime: reconcile of ${e.ruleId} failed: ${ex.message}" }
}
rtReconcileAll(pp)
unschedule('haiWatchdog')
schedule(RT_WATCHDOG_CRON, 'haiWatchdog')
if (state.everydayQueue) runIn(5, 'haiEverydayStep', [overwrite: true])
if (state.runtimeAutoQueue) runIn(5, 'haiAutoActivateQueued', [overwrite: true])
}
@Field static final List RT_STATE_KEYS = ['rtLog', 'rtWatchdog', 'rtShown', 'rtCandidates']
Object runtimeStoreGet(String key) {
if (!RT_STATE_KEYS.contains(key)) return atomicState[key]
Object v = state[key]
if (v == null && !(((List) (state.rtMovedKeys ?: [])).contains(key))) {
v = atomicState[key]
if (v != null) state[key] = v
state.rtMovedKeys = ((List) (state.rtMovedKeys ?: [])) + [key]
}
return v
}
void runtimeStorePut(String key, Object value) {
if (!RT_STATE_KEYS.contains(key)) { atomicState[key] = value; return }
state[key] = value
if (!(((List) (state.rtMovedKeys ?: [])).contains(key))) state.rtMovedKeys = ((List) (state.rtMovedKeys ?: [])) + [key]
}
@Field static final long RT_CHILD_SLOW_MS = 10000L
@Field static final long RT_WATCHDOG_BUDGET_MS = 5000L
@Field static final int RT_WATCHDOG_MORE_MAX = 20
Map runtimePlat(Map opts = null) {
Map memo = [:]
boolean hold = opts?.hold == true
Map checked = opts?.checked instanceof Map ? opts.checked : null
Map timing = opts?.timing instanceof Map ? opts.timing : null
Map held = [:]
long passFrom = now()
long budget = (opts?.budgetMs ?: 0L) as long
Set slow = [] as Set
Map plat = [
now : { Object u -> now() },
atomicGet : { Object k ->
String key = k as String
if (!hold) return runtimeStoreGet(key)
if (!held.containsKey(key)) held[key] = runtimeCopy(runtimeStoreGet(key))
return runtimeCopy(held[key])
},
atomicPut : { List a ->
String key = a[0] as String
if (hold && held.containsKey(key) && held[key] == a[1]) return
runtimeStorePut(key, a[1])
if (hold) held[key] = runtimeCopy(a[1])
},
inventory : { Object d ->
String k = "inv:${d == null || rtDocUsesRmRules((Map) d)}".toString()
if (!memo.containsKey(k)) memo[k] = runtimeInventory((Map) d)
return runtimeInvHandout((Map) memo[k])
},
allowList : { Object u ->
if (!memo.containsKey('allow')) memo.allow = runtimeAllowList()
return new ArrayList((List) memo.allow)
},
checked : { Object u -> checked },
purposes : { Object u ->
if (!memo.containsKey('purposes')) memo.purposes = runtimePurposes()
return new LinkedHashMap((Map) memo.purposes)
},
writableVariables: { Object u -> runtimeIdList(settings.runtimeWritableVariables) },
pausedRules : { Object u -> (state.runtimePaused ?: []) + ((Map) (state.runtimeRmPaused ?: [:])).findAll { k, v -> v == true }.keySet().toList() },
modeAllowed: { Object u -> settings.runtimeAllowSetMode == true },
hsmAllowed : { Object u -> settings.runtimeAllowHsm != false },
hubPowerAllowed: { Object u -> settings.runtimeAllowHubPower != false },
ruleKey : { Object u -> haiRuleKey() },
rmSwitched : { Object u -> haiSwitchedPairs() },
liveUsers : { Object dv -> runtimeLiveUsers(dv) },
hubVariables: { Object u -> getAllGlobalVars() },
setHubVariable: { List a -> if (setGlobalVar(a[0] as String, a[1]) == false) throw new Exception("the hub refused to set the variable ${a[0]}".toString()) },
zone : { Object u -> location.timeZone?.ID },
sunDays : { Object u -> runtimeSun() },
newId : { Object p -> "${p}${UUID.randomUUID().toString().replace('-', '')}".toString() },
addChild : { Object ruleId -> runtimeAddChild(ruleId as String) },
childExists: { Object id -> runtimeChild(id) != null },
childIds : { Object u -> getChildApps()?.collect { it.id.toString() } },
warn : { Object m -> log.warn(m as String) },
child : { List a ->
if (budget <= 0L) return runtimeChildCall(a[0], a[1] as String, a[2])
long t = now()
try {
return runtimeChildCall(a[0], a[1] as String, a[2])
} catch (Exception e) {
slow << (a[0] as String)
throw e
} finally {
if (now() - t > RT_CHILD_SLOW_MS && !slow.contains(a[0] as String)) {
slow << (a[0] as String)
log.warn "HAI Runtime: rule app ${a[0]} took ${now() - t} ms to answer ${a[1]}; it is left for the next pass"
}
}
},
removeChild: { Object id -> deleteChildApp(id as Long) },
ruleRemoved: { Object id -> haiMigrationForget(id as String) },
checker : { Object r -> checkerCall((Map) r) },
]
plat.checkedInventory = { Object d -> runtimeCheckedInventory(plat, d) }
Closure everyDevice = (Closure) plat.inventory
plat.inventory = { Object d -> d instanceof Map ? runtimeCheckedInventory(plat, d) : everyDevice(d) }
if (timing != null) {
plat.mark = { Object phase ->
long t = now()
String k = phase as String
timing[k] = ((timing[k] ?: 0L) as long) + (t - (timing._last as long))
timing._last = t
}
}
if (budget > 0L) {
plat.skipChild = { Object childId -> now() - passFrom > budget || slow.contains(childId as String) }
plat.overBudget = { Object u -> now() - passFrom > budget }
plat.slowChildren = { Object u -> new ArrayList(slow) }
}
return plat
}
Object runtimeCopy(Object v) {
if (v instanceof Map) {
Map out = new LinkedHashMap()
((Map) v).each { Object k, Object x -> out.put(k == null ? null : k.toString(), runtimeCopy(x)) }
return out
}
if (v instanceof Collection) {
List out = new ArrayList(((Collection) v).size())
((Collection) v).each { Object x -> out.add(runtimeCopy(x)) }
return out
}
if (v instanceof CharSequence && !(v instanceof String)) return v.toString()
return v
}
List runtimeSelectedRows() {
return ((settings.runtimeAllowDevices ?: []) as List).findAll { it != null }.collect { d -> [id: d.id.toString(), driver: d.typeName?.toString(), name: d.displayName?.toString()] }
}
List runtimeAllowList() { return rtEffectiveAllowList(runtimeSelectedRows()) }
List runtimeIdList(Object v) {
if (v == null) return []
if (v instanceof Collection) return ((Collection) v).collect { it.toString() }
String t = v.toString().trim()
if (t.startsWith('[')) {
try { return ((List) new groovy.json.JsonSlurper().parseText(t)).collect { it.toString() } } catch (Exception e) { }
}
return t.split(',').collect { it.trim() }.findAll { it }
}
Map runtimePurposes() {
List allow = runtimeAllowList()
Map out = [:]
allow.each { String id ->
List found = []
if (found.size() == 1) out[id] = found[0]
}
return out
}
Map runtimeInvHandout(Map inv) {
Map out = new LinkedHashMap(inv)
out.devices = new LinkedHashMap((Map) inv.devices)
out.variables = new LinkedHashMap((Map) (inv.variables ?: [:]))
out.modes = new LinkedHashSet((Collection) (inv.modes ?: []))
out.rules = new LinkedHashSet((Collection) (inv.rules ?: []))
out.rmRules = new LinkedHashSet((Collection) (inv.rmRules ?: []))
return out
}
Map runtimeCheckedInventory(Map pp, Map doc) {
Map inv = invNew()
List allow = ((List) (rtpCall(pp, 'allowList', null) ?: [])).collect { it.toString() }
Set wanted = (rtDeviceIds(doc) as Set).findAll { allow.contains(it) } as Set
Map purposes = wanted.isEmpty() ? [:] : (rtpCall(pp, 'purposes', null) ?: [:])
((settings.runtimeAllowDevices ?: []) as List).findAll { it != null && wanted.contains(it.id.toString()) }.each { d ->
Map row = rtDeviceRow(d, (String) purposes[d.id.toString()])
inv.devices[row.id] = row
}
location.modes.each { m -> inv.modes << m.name.toString() }
inv.currentMode = location.mode?.toString()
try { ((Map) inv.variables).putAll(schemaHubVariableRows(getAllGlobalVars(), runtimeIdList(settings.runtimeWritableVariables))) } catch (Exception e) { }
schemaMarkConnectors(inv)
((Collection) inv.rules).addAll(rtKnownRuleIds(pp))
if (rtDocUsesRmRules(doc)) {
try { ((Collection) inv.rmRules).addAll(schemaRmRuleRows(RMUtils.getRuleList('5.0'))*.id) } catch (Exception e) { }
}
return inv
}
Map runtimeInventory(Map doc = null) {
Map inv = invNew()
List allow = runtimeAllowList()
Map purposes = runtimePurposes()
((settings.runtimeAllowDevices ?: []) as List).findAll { it != null && allow.contains(it.id.toString()) }.each { d ->
Map row = rtDeviceRow(d, (String) purposes[d.id.toString()])
inv.devices[row.id] = row
}
location.modes.each { m -> inv.modes << m.name.toString() }
inv.currentMode = location.mode?.toString()
try { ((Map) inv.variables).putAll(schemaHubVariableRows(getAllGlobalVars(), runtimeIdList(settings.runtimeWritableVariables))) } catch (Exception e) { }
schemaMarkConnectors(inv)
((Collection) inv.rules).addAll(rtKnownRuleIds(runtimePlat()))
if (doc == null || rtDocUsesRmRules(doc)) {
try { ((Collection) inv.rmRules).addAll(schemaRmRuleRows(RMUtils.getRuleList('5.0'))*.id) } catch (Exception e) { }
}
return inv
}
Map runtimeSun() {
Map out = [:]
TimeZone tz = location.timeZone
for (int i = 0; i < 3; i++) {
try {
Date d = new Date(now() + i * 86400000L)
Map s = getSunriseAndSunset(date: d)
if (s?.sunrise && s?.sunset) out[d.format('yyyy-MM-dd', tz)] = [sunrise: s.sunrise.format('HH:mm', tz), sunset: s.sunset.format('HH:mm', tz)]
} catch (Exception e) { }
}
return out
}
String runtimeAddChild(String ruleId) {
def c = addChildApp('gordonthelander', RUNTIME_CHILD_NAME, "HAI rule ${ruleId}".toString().take(60))
return c.id.toString()
}
def runtimeChild(Object childId) {
if (childId == null) return null
def c = getChildAppById(childId.toString() as Long)
return c ?: getChildApps()?.find { it.id.toString() == childId.toString() }
}
Object runtimeChildCall(Object childId, String method, List args) {
def c = runtimeChild(childId)
if (c == null) throw new Exception("no rule child ${childId}")
switch (method) {
case 'haiBuild': return c.haiBuild()
case 'haiSetup': return c.haiSetup((List) args[0], args[1], args[2], args.size() > 3 ? args[3] : [:])
case 'haiStage': return c.haiStage((Map) args[0], args[1] as String, args[2] as String, args[3] as String, args[4], args[5], args.size() > 6 ? args[6] : false)
case 'haiLifecycle': return c.haiLifecycle(args[0] as String, args[1] as String)
case 'haiOverdue':
if (args.size() > 3) {
try { return c.haiOverdue(args[0], args[1], args[2], args[3]) }
catch (Exception e) { if (!(e.message ?: '').contains('haiOverdue')) throw e }
}
return c.haiOverdue(args[0], args[1], args[2])
case 'haiRuntimeState': return c.haiRuntimeState()
case 'haiRecent': return c.haiRecent()
case 'haiShadowTell': return c.haiShadowTell()
case 'haiResumeStaged': return c.haiResumeStaged()
case 'haiCommitNow': return c.haiCommitNow(args[0] as String)
case 'haiDiscardStaged': return c.haiDiscardStaged(args[0] as String)
case 'haiReconcile': return c.haiReconcile()
case 'haiDevWithhold': return c.haiDevWithhold(args[0])
case 'haiEditorLink': return c.haiEditorLink(args[0] as String)
case 'haiShadowRuns': return c.haiShadowRuns()
case 'haiShadowTest': return c.haiShadowTest()
case 'ruleRetainedDoc': return c.ruleRetainedDoc(args[0] as String)
case 'ruleAdoptRetainedDoc': return c.ruleAdoptRetainedDoc(args[0] as String, args[1])
}
throw new Exception("unknown child method ${method}")
}
Map runtimeDeviceNames(Collection onlyIds = null) {
Map out = [:]
Set want = onlyIds == null ? null : (onlyIds.collect { it.toString() } as Set)
if (want != null && want.isEmpty()) return out
((settings.runtimeAllowDevices ?: []) as List).each { d ->
if (d != null && (want == null || want.contains(d.id.toString()))) out.put("dev:${d.id}".toString(), d.displayName.toString())
}
return out
}
String runtimeNameWithValues(String name) {
if (name == null || !name.contains('%')) return name
return name.replaceAll(/%([^%]{1,64})%/) { List m ->
def g = null
try { g = getGlobalVar(m[1] as String) } catch (Exception e) { g = null }
g != null ? "${g.value}".toString() : (m[0] as String)
}
}
Map runtimeRuleDoc(Map pp, Map e) {
if (e?.childId == null || e.epoch == null) return e?.doc instanceof Map ? e.doc : null
try {
Object got = rtpCall(pp, 'child', [e.childId, 'ruleRetainedDoc', [e.epoch.toString()]])
if (got instanceof Map) return got
} catch (Exception ignored) { }
return e.doc instanceof Map ? e.doc : null
}
String runtimeRuleName(Map e) {
if (e == null) return ''
Object n = (e.index instanceof Map) ? ((Map) e.index).name : ((e.doc instanceof Map) ? ((Map) e.doc).name : null)
if (n == null) n = rtCandidates(runtimePlat()).find { it.ruleId == e.ruleId }?.name
return (n ?: e.ruleId) as String
}
Closure runtimeClock() {
return { long t -> new Date(t).format('HH:mm:ss', location.timeZone) }
}
@Field static final int LIMIT_DOCUMENT_BYTES = 262144
@Field static final int LIMIT_STRING_CHARS = 65536
@Field static final int LIMIT_KEY_CHARS = 64
@Field static final int LIMIT_DEPTH = 32
@Field static final int LIMIT_ARRAY_ELEMENTS = 1024
@Field static final int LIMIT_OBJECT_MEMBERS = 256
@Field static final int LIMIT_PARSER_WORK = 1000000
@Field static final int HAI_DOC_BYTES = 16384
@Field static final int HAI_RETAINED_DOCS = 2
@Field static final int RT_BASE_BYTES = 1536
@Field static final int RT_TRACE_BYTES = 6144
@Field static final int RT_TRACE_FLOOR_BYTES = 15635
@Field static final int RT_MIN_RUN_BYTES = 1024
@Field static final int RT_LEDGER_RESERVE_BYTES = 3072
@Field static final int RT_DEADLINE_BYTES = 1536
@Field static final int RT_DOC_BYTES = 32768
@Field static final int RT_STATE_BYTES = 62739
@Field static final int LIMIT_SIGNIFICANT_DIGITS = 15
@Field static final int LIMIT_DECIMAL_PLACES = 6
Map jsonOk(Object value) { return [ok: true, value: value] }
Map jsonFail(String code, String message, int offset) {
return [ok: false, code: code, message: "${message} (at offset ${offset})".toString(), offset: offset]
}
Map jsonParse(String text) {
if (text == null) return jsonFail('JSON_EMPTY', 'no input', 0)
int bytes = text.getBytes('UTF-8').length
if (bytes > LIMIT_DOCUMENT_BYTES) {
return jsonFail('LIMIT_SIZE', "document is ${bytes} bytes, limit is ${LIMIT_DOCUMENT_BYTES}", 0)
}
Map st = [src: text, pos: 0, work: 0, depth: 0, error: null]
jsonSkipWs(st)
Object value = jsonReadValue(st)
if (st.error) return st.error
jsonSkipWs(st)
if (st.pos < text.length()) {
return jsonFail('JSON_TRAILING', 'unexpected content after the top-level value', st.pos)
}
return jsonOk(value)
}
boolean jsonTick(Map st) {
st.work = st.work + 1
if (st.work > LIMIT_PARSER_WORK) {
if (!st.error) st.error = jsonFail('LIMIT_WORK', "parser work limit of ${LIMIT_PARSER_WORK} exceeded", st.pos)
return false
}
return true
}
void jsonSkipWs(Map st) {
String s = st.src
while (st.pos < s.length()) {
String c = s.substring(st.pos, st.pos + 1)
if (c == ' ' || c == '\t' || c == '\n' || c == '\r') { st.pos = st.pos + 1 } else { break }
}
}
String jsonPeek(Map st) {
if (st.pos >= st.src.length()) {
if (!st.error) st.error = jsonFail('JSON_TRUNCATED', 'input ended unexpectedly', st.pos)
return null
}
return st.src.substring(st.pos, st.pos + 1)
}
Object jsonReadValue(Map st) {
if (st.error) return null
if (!jsonTick(st)) return null
String c = jsonPeek(st)
if (c == null) return null
if (c == '{') return jsonReadObject(st)
if (c == '[') return jsonReadArray(st)
if (c == '"') return jsonReadString(st)
if (c == 't') { return jsonExpect(st, 'true') ? Boolean.TRUE : null }
if (c == 'f') { return jsonExpect(st, 'false') ? Boolean.FALSE : null }
if (c == 'n') {
if (jsonExpect(st, 'null')) {
st.error = jsonFail('JSON_NULL', 'null is not permitted; omit the member instead', st.pos - 4)
}
return null
}
return jsonReadNumber(st)
}
boolean jsonExpect(Map st, String literal) {
int end = st.pos + literal.length()
if (end > st.src.length() || st.src.substring(st.pos, end) != literal) {
st.error = jsonFail('JSON_TOKEN', "expected ${literal}", st.pos)
return false
}
st.pos = end
return true
}
Map jsonReadObject(Map st) {
st.depth = st.depth + 1
if (st.depth > LIMIT_DEPTH) {
st.error = jsonFail('LIMIT_DEPTH', "nesting deeper than ${LIMIT_DEPTH}", st.pos)
return null
}
st.pos = st.pos + 1
Map result = [:]
jsonSkipWs(st)
if (jsonPeek(st) == '}') { st.pos = st.pos + 1; st.depth = st.depth - 1; return result }
while (true) {
if (st.error || !jsonTick(st)) return null
jsonSkipWs(st)
if (jsonPeek(st) != '"') { st.error = jsonFail('JSON_TOKEN', 'expected a key', st.pos); return null }
int keyAt = st.pos
String key = jsonReadString(st)
if (st.error) return null
if (key.length() > LIMIT_KEY_CHARS) {
st.error = jsonFail('LIMIT_STRING', "key longer than ${LIMIT_KEY_CHARS} characters", keyAt); return null
}
if (!(key ==~ /[A-Za-z][A-Za-z0-9_]*/)) {
st.error = jsonFail('JSON_KEY_CHARSET', "key '${key}' is outside [A-Za-z][A-Za-z0-9_]*", keyAt); return null
}
if (result.containsKey(key)) {
st.error = jsonFail('JSON_DUPLICATE_KEY', "duplicate key '${key}'", keyAt); return null
}
jsonSkipWs(st)
if (jsonPeek(st) != ':') { st.error = jsonFail('JSON_TOKEN', 'expected :', st.pos); return null }
st.pos = st.pos + 1
jsonSkipWs(st)
result.put(key, jsonReadValue(st))
if (st.error) return null
if (result.size() > LIMIT_OBJECT_MEMBERS) {
st.error = jsonFail('LIMIT_WIDTH', "more than ${LIMIT_OBJECT_MEMBERS} members in one object", st.pos); return null
}
jsonSkipWs(st)
String c = jsonPeek(st)
if (c == ',') { st.pos = st.pos + 1; continue }
if (c == '}') { st.pos = st.pos + 1; st.depth = st.depth - 1; return result }
st.error = jsonFail('JSON_TOKEN', 'expected , or }', st.pos)
return null
}
}
List jsonReadArray(Map st) {
st.depth = st.depth + 1
if (st.depth > LIMIT_DEPTH) {
st.error = jsonFail('LIMIT_DEPTH', "nesting deeper than ${LIMIT_DEPTH}", st.pos)
return null
}
st.pos = st.pos + 1
List result = []
jsonSkipWs(st)
if (jsonPeek(st) == ']') { st.pos = st.pos + 1; st.depth = st.depth - 1; return result }
while (true) {
if (st.error || !jsonTick(st)) return null
jsonSkipWs(st)
result.add(jsonReadValue(st))
if (st.error) return null
if (result.size() > LIMIT_ARRAY_ELEMENTS) {
st.error = jsonFail('LIMIT_WIDTH', "more than ${LIMIT_ARRAY_ELEMENTS} elements in one array", st.pos); return null
}
jsonSkipWs(st)
String c = jsonPeek(st)
if (c == ',') { st.pos = st.pos + 1; continue }
if (c == ']') { st.pos = st.pos + 1; st.depth = st.depth - 1; return result }
st.error = jsonFail('JSON_TOKEN', 'expected , or ]', st.pos)
return null
}
}
String jsonReadString(Map st) {
int startedAt = st.pos
st.pos = st.pos + 1
StringBuilder out = new StringBuilder()
while (true) {
if (!jsonTick(st)) return null
if (st.pos >= st.src.length()) {
st.error = jsonFail('JSON_TRUNCATED', 'unterminated string', startedAt); return null
}
String c = st.src.substring(st.pos, st.pos + 1)
if (c == '"') { st.pos = st.pos + 1; break }
if (c == '\\') {
st.pos = st.pos + 1
String e = jsonPeek(st)
if (e == null) return null
st.pos = st.pos + 1
if (e == '"') { out.append('"') }
else if (e == '\\') { out.append('\\') }
else if (e == '/') { out.append('/') }
else if (e == 'b') { out.append('\b') }
else if (e == 'f') { out.append('\f') }
else if (e == 'n') { out.append('\n') }
else if (e == 'r') { out.append('\r') }
else if (e == 't') { out.append('\t') }
else if (e == 'u') {
if (st.pos + 4 > st.src.length()) {
st.error = jsonFail('JSON_TRUNCATED', 'truncated \\u escape', st.pos); return null
}
String hex = st.src.substring(st.pos, st.pos + 4)
if (!(hex ==~ /[0-9a-fA-F]{4}/)) {
st.error = jsonFail('JSON_TOKEN', "bad \\u escape '${hex}'", st.pos); return null
}
out.append((char) Integer.parseInt(hex, 16))
st.pos = st.pos + 4
} else {
st.error = jsonFail('JSON_TOKEN', "unknown escape \\${e}", st.pos - 1); return null
}
continue
}
if (c.charAt(0) < 0x20) {
st.error = jsonFail('JSON_TOKEN', 'unescaped control character in string', st.pos); return null
}
out.append(c)
st.pos = st.pos + 1
}
String value = out.toString()
if (value.length() > LIMIT_STRING_CHARS) {
st.error = jsonFail('LIMIT_STRING', "string longer than ${LIMIT_STRING_CHARS} characters", startedAt); return null
}
String surrogate = jsonSurrogateProblem(value)
if (surrogate) { st.error = jsonFail('JSON_STRING_SURROGATE', surrogate, startedAt); return null }
return value
}
String jsonSurrogateProblem(String value) {
for (int i = 0; i < value.length(); i++) {
char c = value.charAt(i)
if (Character.isHighSurrogate(c)) {
if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) return 'unpaired high surrogate'
i++
} else if (Character.isLowSurrogate(c)) {
return 'unpaired low surrogate'
}
}
return null
}
BigDecimal jsonReadNumber(Map st) {
int startedAt = st.pos
int end = st.pos
while (end < st.src.length() && '-+.eE0123456789'.indexOf(st.src.substring(end, end + 1)) >= 0) { end++ }
String raw = st.src.substring(startedAt, end)
if (raw.isEmpty()) { st.error = jsonFail('JSON_TOKEN', 'expected a value', startedAt); return null }
st.pos = end
if (!(raw ==~ /-?(0|[1-9][0-9]*)(\.[0-9]*[1-9])?/)) {
st.error = jsonFail('JSON_NUMBER_FORM',
"number '${raw}' must have no exponent, no leading plus, no leading zero and no trailing fractional zero",
startedAt)
return null
}
if (raw == '-0') { st.error = jsonFail('JSON_NUMBER_FORM', 'negative zero is not permitted', startedAt); return null }
BigDecimal value = new BigDecimal(raw)
if (value.precision() > LIMIT_SIGNIFICANT_DIGITS) {
st.error = jsonFail('JSON_NUMBER_RANGE',
"number '${raw}' has more than ${LIMIT_SIGNIFICANT_DIGITS} significant digits", startedAt)
return null
}
if (value.scale() > LIMIT_DECIMAL_PLACES) {
st.error = jsonFail('JSON_NUMBER_RANGE',
"number '${raw}' has more than ${LIMIT_DECIMAL_PLACES} decimal places", startedAt)
return null
}
return value
}
String jsonCanonicalise(Object value, Map defaults = [:]) {
StringBuilder out = new StringBuilder()
jsonWrite(value, out, defaults, '')
return out.toString()
}
String jsonHash(Object value, Map defaults = [:]) {
byte[] bytes = jsonCanonicalise(value, defaults).getBytes('UTF-8')
return MessageDigest.getInstance('SHA-256').digest(bytes).collect { String.format('%02x', it) }.join('')
}
boolean jsonSameSecret(Object a, Object b) {
if (!(a instanceof String) || !(b instanceof String)) return false
String x = (String) a, y = (String) b
if (x.isEmpty() || x.length() != y.length()) return false
int diff = 0
for (int i = 0; i < x.length(); i++) diff |= ((int) x.charAt(i)) ^ ((int) y.charAt(i))
return diff == 0
}
String jsonHmac(String key, String text) {
byte[] k = (key ?: '').getBytes('UTF-8')
if (k.length > 64) k = MessageDigest.getInstance('SHA-256').digest(k)
byte[] ipad = new byte[64]
byte[] opad = new byte[64]
for (int i = 0; i < 64; i++) {
int b = i < k.length ? (int) k[i] : 0
ipad[i] = (byte) (b ^ 0x36)
opad[i] = (byte) (b ^ 0x5c)
}
MessageDigest inner = MessageDigest.getInstance('SHA-256')
inner.update(ipad)
inner.update((text ?: '').getBytes('UTF-8'))
byte[] ih = inner.digest()
MessageDigest outer = MessageDigest.getInstance('SHA-256')
outer.update(opad)
outer.update(ih)
return outer.digest().collect { String.format('%02x', it) }.join('')
}
@Field static final long RT_SIG_WINDOW_MS = 120000L
@Field static final int RT_SIG_SEEN_CAP = 256
@Field static final String RT_SIG_FIELD = ',"sig":"'
String rtSignedData(String key, String name, Object value, Map payload, long at, String nonce) {
if (!(key instanceof String) || key.isEmpty()) return null
Map body = new LinkedHashMap(payload ?: [:])
body.remove('sig')
body.remove('key')
body.ts = at
body.n = nonce
String text = groovy.json.JsonOutput.toJson(body)
String sig = jsonHmac(key, "${name}|${value}|${text}".toString())
return text.substring(0, text.length() - 1) + RT_SIG_FIELD + sig + '"}'
}
Map rtSignedPayload(List keys, String name, Object value, Object data, long at, Map seen) {
if (!(data instanceof String)) return null
String d = (String) data
int cut = d.lastIndexOf(RT_SIG_FIELD)
if (cut < 1 || !d.endsWith('"}')) return null
String sig = d.substring(cut + RT_SIG_FIELD.length(), d.length() - 2)
String text = d.substring(0, cut) + '}'
String signed = "${name}|${value}|${text}".toString()
boolean good = false
for (Object k in (keys ?: [])) if (k instanceof String && !((String) k).isEmpty() && jsonSameSecret(jsonHmac((String) k, signed), sig)) good = true
if (!good) return null
Map body
try { body = new groovy.json.JsonSlurper().parseText(text) } catch (Exception e) { return null }
Object ts = body.ts
Object n = body.n
if (!(ts instanceof Number) || !(n instanceof String) || Math.abs(at - (ts as long)) > RT_SIG_WINDOW_MS) return null
if (seen != null) {
if (seen.containsKey(n)) return null
List old = seen.findAll { k, v -> !(v instanceof Number) || at - (v as long) > RT_SIG_WINDOW_MS }.collect { it.key }
old.each { seen.remove(it) }
while (seen.size() >= RT_SIG_SEEN_CAP) seen.remove(seen.keySet().iterator().next())
seen.put(n, ts as long)
}
return body
}
String jsonSign(String key, String text) {
byte[] bytes = "${key}\n${text}".toString().getBytes('UTF-8')
return MessageDigest.getInstance('SHA-256').digest(bytes).collect { String.format('%02x', it) }.join('')
}
String jsonSeal(String key, String nonce, String plain) {
byte[] b = plain.getBytes('UTF-8')
byte[] k = jsonKeystream(key, nonce, b.length)
StringBuilder out = new StringBuilder()
for (int i = 0; i < b.length; i++) out.append(String.format('%02x', (b[i] ^ k[i]) & 0xff))
return out.toString()
}
String jsonUnseal(String key, String nonce, String hex) {
if (hex == null || !(hex ==~ /(?:[0-9a-f]{2})*/)) return null
int n = hex.length().intdiv(2)
byte[] k = jsonKeystream(key, nonce, n)
byte[] b = new byte[n]
for (int i = 0; i < n; i++) b[i] = (byte) ((Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16) ^ k[i]) & 0xff)
return new String(b, 'UTF-8')
}
byte[] jsonKeystream(String key, String nonce, int length) {
byte[] out = new byte[length]
int at = 0
int block = 0
while (at < length) {
byte[] d = MessageDigest.getInstance('SHA-256').digest("${key}\nseal\n${nonce}\n${block}".toString().getBytes('UTF-8'))
for (int i = 0; i < d.length && at < length; i++) out[at++] = d[i]
block++
}
return out
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
@Field static final String SCHEMA_ID = 'hai.rule/1'
@Field static final String COMPILER_VERSION = '1.0.0'
@Field static final String CATALOGUE_VERSION = '1.0.0'
@Field static final Map SCHEMA_DEFAULTS = [
'rerunPolicy' : 'restart',
'onUnknown' : 'block',
'ifUnknown' : 'stop',
'errorPolicy' : 'stopRun',
'enabled' : true,
'initialEvaluation': false,
'conditionTiming' : 'atTrigger',
]
@Field static final List CONDITION_TYPES = ['all', 'any', 'not', 'deviceAttribute', 'variable', 'mode', 'timeWindow', 'triggeredBy', 'rulePaused', 'waitOutcome',
'xor', 'count', 'dayOfWeek', 'dateRange', 'timeSince', 'hsmStatus']
@Field static final Map EVERYDAY_WORDS = [
dark : [say: 'dark', group: 'light', attributes: ['illuminance'], op: 'below'],
dusk : [say: 'dusk or dawn', group: 'light', attributes: ['illuminance'], op: 'between'],
overcast: [say: 'overcast', group: 'light', attributes: ['illuminance'], op: 'between'],
bright : [say: 'bright daylight', group: 'light', attributes: ['illuminance'], op: 'atLeast'],
hot : [say: 'hot', group: 'weather', attributes: ['temperature'], op: 'atLeast'],
cold : [say: 'cold', group: 'weather', attributes: ['temperature'], op: 'atMost'],
windy : [say: 'windy', group: 'weather', attributes: ['windSpeed'], op: 'atLeast'],
raining : [say: 'raining', group: 'weather', attributes: ['condition_code', 'weather', 'condition_text'], op: 'matches',
pattern: '(?i)rain|drizzle|shower|thunder|tstorm'],
snowing : [say: 'snowing', group: 'weather', attributes: ['condition_code', 'weather', 'condition_text'], op: 'matches',
pattern: '(?i)snow|sleet|flurr'],
]
@Field static final Map EVERYDAY_DEFAULTS = [darkBelow: 10, overcastFrom: 200, brightFrom: 2000,
hotFromF: 80, hotFromC: 27, coldToF: 40, coldToC: 4, windyFromF: 20, windyFromC: 30]
Map everydayValues(Map levels) {
Map L = (levels ?: [:]).collectEntries { k, v -> [(k): k == 'scale' ? v : everydayNumber(v)] }
return [dark: L.darkBelow, dusk: [L.darkBelow, L.overcastFrom], overcast: [L.overcastFrom, L.brightFrom], bright: L.brightFrom,
hot: L.hotFrom, cold: L.coldTo, windy: L.windyFrom]
}
Object everydayNumber(Object v) {
if (v == null) return null
BigDecimal b
try { b = new BigDecimal(v.toString()).stripTrailingZeros() } catch (Exception e) { return v }
if (b.scale() <= 0) {
BigInteger i = b.toBigInteger()
return (i.bitLength() < 32) ? (Object) i.intValue() : (Object) i.longValue()
}
return new BigDecimal(b.toPlainString())
}
boolean everydaySame(Object a, Object b) {
if (a instanceof List || b instanceof List) {
if (!(a instanceof List && b instanceof List) || ((List) a).size() != ((List) b).size()) return false
return (0..<((List) a).size()).every { int i -> everydaySame(((List) a)[i], ((List) b)[i]) }
}
if (a == null || b == null) return a == b
try { return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0 } catch (Exception e) { return a.toString() == b.toString() }
}
List everydayChanged(Map before, Map after) {
return ((Map) after).keySet().findAll { Object k -> before == null || !everydaySame(before.get(k), after.get(k)) }.collect { it as String }.sort()
}
List everydayWordsIn(Object doc) {
Set out = [] as Set
Closure walk
walk = { Object v ->
if (v instanceof Map) {
if (((Map) v).everyday instanceof String) out << (((Map) v).everyday as String)
((Map) v).values().each { walk(it) }
} else if (v instanceof List) ((List) v).each { walk(it) }
}
walk(doc)
return new ArrayList(out).sort()
}
@Field static final List HUB_POWER_TYPES = ['hubReboot', 'hubShutdown']
@Field static final int NET_DEFAULT_TIMEOUT_SECONDS = 10
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
@Field static final Map RISK_CLASSES = [
'access': [note: 'locks and entry control'],
'motion': [note: 'garage doors, gates, anything that moves and can trap'],
'alarm' : [note: 'sirens and alarm state; stopping one can suppress a real alarm'],
'security': [note: 'Hubitat Safety Monitor: arming, disarming and cancelling alerts for the whole house'],
]
Map schemaOperation(String command) { return CATALOGUE.get(command) }
@Field static final String HUB_VARIABLE_PREFIX = 'hv:'
List schemaRmRuleRows(Object list) {
List out = []
if (!(list instanceof List)) return out
((List) list).each { Object e ->
if (e instanceof Map) ((Map) e).each { Object k, Object v -> if (k != null) out << [id: k.toString(), name: (v ?: k).toString()] }
}
return out.sort { a, b -> (a.name as String) <=> (b.name as String) }
}
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
Map schemaMarkConnectors(Map inv) {
Map out = [:]
((Map) (inv?.variables ?: [:])).values().each { Object o ->
Map v = o
if (!(v.connector instanceof String)) return
out.put(v.connector, v.id)
Map dev = ((Map) (inv.devices ?: [:])).get(v.connector)
if (dev != null) { dev.variable = v.id; dev.variableName = v.name }
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
boolean schemaDeviceOffers(String op, Object offered) {
if (offered == null) return schemaOperation(op)?.mustOffer != true
Collection have = (Collection) offered
if (have.contains(op)) return true
List basis = COMMAND_BASIS.get(op)
return basis != null && have.containsAll(basis)
}
@Field static final int RT_STAGE_FAILURE_NOTES = 8
@Field static final List EDITOR_SUBMIT_OPS = ['run', 'pause', 'resume', 'stop', 'start', 'remove', 'runNow', 'cancel', 'setBoolean', 'trigger', 'setVariable']
@Field static final List RT_ARRIVE_STATES = ['running', 'paused', 'stopped']
Object bundleDeepCopy(Object node) {
if (node instanceof Map) {
Map out = [:]
((Map) node).each { k, v -> out[k] = bundleDeepCopy(v) }
return out
}
if (node instanceof List) {
return node.collect { bundleDeepCopy(it) }
}
return node
}
@Field static final String CORE_VARIABLE_ATTRIBUTE = 'value'
@Field static final String SEV_BLOCK = 'block'
Map invNew() { return [devices: [:], variables: [:], modes: [] as Set, rules: [] as Set, rmRules: [] as Set, currentMode: null] }
List vBlocking(Map r) { return r.findings.findAll { it.severity == SEV_BLOCK } }
String coreRefId(Map doc, Object refKey) {
Map ref = ((Map) (doc.refs ?: [:])).get(refKey)
return ref ? (ref.id as String) : null
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
@Field static final List RENDER_COLOURS = [['red', 0], ['orange', 8], ['yellow', 17], ['green', 33],
['cyan', 50], ['blue', 67], ['purple', 75], ['pink', 92]]
String rColourWords(List args) {
if (!args) { return '?' }
if (args[0] instanceof Map) { Map c = args[0]; args = [c.hue, c.saturation, c.level].findAll { it != null } }
BigDecimal hue = (args[0] instanceof Number) ? ((Number) args[0]) as BigDecimal : 0G
BigDecimal sat = (args.size() > 1 && args[1] instanceof Number) ? ((Number) args[1]) as BigDecimal : 100G
BigDecimal lvl = (args.size() > 2 && args[2] instanceof Number) ? ((Number) args[2]) as BigDecimal : 100G
String name
if (lvl <= 0G) {
name = 'off'
} else if (sat <= 10G) {
name = 'white'
} else {
String best = 'colour'
BigDecimal bestD = 1000G
RENDER_COLOURS.each { List p ->
BigDecimal gap = (hue - (((Number) p[1]) as BigDecimal)).abs()
BigDecimal d = gap < (100G - gap) ? gap : (100G - gap)
if (d < bestD) { bestD = d; best = (String) p[0] }
}
name = sat < 45G ? ('pale ' + best) : best
}
if (lvl > 0G && lvl < 100G) { name = name + ', ' + Math.round(lvl as double) + '% bright' }
return name
}
@Field static final Map RENDER_COMMANDS = [
on: 'turn on %s', off: 'turn off %s', toggle: 'toggle %s',
setLevel: 'set %s to %s%%', setColorTemperature: 'set %s to %sK',
lock: 'lock %s', unlock: 'unlock %s', open: 'open %s', close: 'close %s',
siren: 'sound %s', strobe: 'strobe %s', both: 'sound and strobe %s', alarmOff: 'silence %s', push: 'push %s',
deviceNotification: 'send "%s" to %s', refresh: 'refresh %s', speak: 'say "%s" on %s',
setVolume: 'set the volume of %s to %s%%', mute: 'mute %s', unmute: 'unmute %s', beep: 'sound a tone on %s',
playSound: 'play chime %s on %s', setColor: 'set the colour of %s to %s',
toggleColor: 'turn off %s if it is on, otherwise set its colour to %s',
toggleColorTemperature: 'turn off %s if it is on, otherwise set its colour temperature to %sK',
flash: 'flash %s', adjustLevel: 'change the level of %s by %s%%', toggleLevel: 'turn off %s if it is on, otherwise set it to %s%%',
startLevelChange: 'start changing the level of %s %s', stopLevelChange: 'stop changing the level of %s',
openShade: 'open %s', closeShade: 'close %s', setPosition: 'set %s to position %s%%', stopPositionChange: 'stop %s moving',
openValve: 'open %s', closeValve: 'close %s', setSpeed: 'set the fan speed of %s to %s', cycleSpeed: 'change %s to its next fan speed',
setHeatingSetpoint: 'set the heating setpoint of %s to %s', setCoolingSetpoint: 'set the cooling setpoint of %s to %s',
adjustHeatingSetpoint: 'change the heating setpoint of %s by %s', adjustCoolingSetpoint: 'change the cooling setpoint of %s by %s',
setThermostatMode: 'set %s to %s mode', setThermostatFanMode: 'set the fan of %s to %s',
play: 'play %s', pause: 'pause %s', stop: 'stop %s', nextTrack: 'skip to the next track on %s', previousTrack: 'go back a track on %s',
playTrack: 'play on %s: %s', setTrack: 'set the track of %s to %s', poll: 'poll %s',
restoreTrack: 'play on %s, then go back to what was playing: %s', resumeTrack: 'play on %s, then resume what was playing: %s',
playText: 'say on %s: %s', volumeUp: 'turn up the volume of %s', volumeDown: 'turn down the volume of %s',
togglePlay: 'pause %s if it is playing, otherwise play it', toggleMute: 'unmute %s if it is muted, otherwise mute it',
]
@Field static final Map RENDER_OPS = [
is: 'is', isNot: 'is not', above: 'is above', below: 'is below',
atLeast: 'is at least', atMost: 'is at most', contains: 'contains', isEmpty: 'is empty', between: 'is between', notBetween: 'is not between',
]
String rCompareValue(Map doc, Map c) {
if (c.op == 'isEmpty') return ''
Object v = c.value
if (v instanceof Map) {
Map m = v
String off = m.offset == null ? '' : " ${(m.offset as BigDecimal) < 0 ? 'minus' : 'plus'} ${(m.offset as BigDecimal).abs()}".toString()
return ' ' + (m.attribute ? "the ${m.attribute} of ${rName(doc, m.ref)}".toString() : rName(doc, m.ref)) + off
}
if (v instanceof List && ((List) v).size() == 2) return " ${((List) v)[0]} and ${((List) v)[1]}".toString()
return " ${v}".toString()
}
String rName(Map doc, Object key) {
Map ref = ((Map) (doc.refs ?: [:])).get(key)
if (key == null) return '(no device chosen)'
if (ref == null) return "[unknown ${key}]".toString()
if (ref.kind == 'eventDevice') return 'the device that fired the trigger'
return (ref.name ?: key) as String
}
String rRestart(Map a) {
switch (a.onRestart ?: 'reevaluate') {
case 'skip': return 'If the hub restarts meanwhile, this run stops there.'
case 'catchUp': return 'If the hub restarts meanwhile, it carries on, using only readings taken since the restart.'
default: return 'If the hub restarts meanwhile, it checks the conditions again with fresh readings before carrying on.'
}
}
String rSetVarOp(Map doc, Map a) {
List g = ((List) (a.args ?: [])).collect { Object o ->
if (!(o instanceof Map)) return o instanceof String ? "\"${o}\"".toString() : "${o}".toString()
Map m = o
return m.attribute ? "the ${m.attribute} of ${rName(doc, m.ref)}".toString() : rName(doc, m.ref)
}
Closure at = { int i -> i < g.size() ? g[i] : '?' }
switch (a.op) {
case 'set': return at(0)
case 'add': return "itself plus ${at(0)}".toString()
case 'math':
Object mop = ((List) (a.args ?: [])).size() > 1 ? ((List) a.args)[1] : '?'
return g.size() > 2 ? "${at(0)} ${mop} ${at(2)}".toString() : "${mop} of ${at(0)}".toString()
case 'hour': return 'the current hour'
case 'minute': return 'the current minute'
case 'epoch': return 'the current time in milliseconds'
case 'now': return g ? "the current date and time as ${at(0)}".toString() : 'the current date and time'
case 'sunrise': return "today's sunrise time"
case 'sunset': return "today's sunset time"
case 'length': return "the length of ${at(0)}".toString()
case 'remove': return "${at(0)} without ${at(1)}".toString()
case 'replace': return "${at(0)} with ${at(1)} replaced by ${at(2)}".toString()
case 'token': return "part ${at(2)} of ${at(0)}, split at ${at(1)}".toString()
case 'lowerCase': return "${at(0)} in lower case".toString()
case 'urlEncode': return "${at(0)}, URL-encoded".toString()
case 'urlDecode': return "${at(0)}, URL-decoded".toString()
case 'compare': return "whether ${at(0)} ${RENDER_OPS[a.args?.getAt(1)] ?: at(1)} ${at(2)}".toString()
case 'and': return "${at(0)} and ${at(1)}".toString()
case 'or': return "${at(0)} or ${at(1)}".toString()
case 'xor': return "${at(0)} or ${at(1)}, but not both".toString()
case 'not': return "not ${at(0)}".toString()
case 'formatDate': return "${at(0)} written as ${at(1)}".toString()
case 'addTime': return "${at(0)} plus ${at(1)} ${a.args?.getAt(2)}".toString()
case 'timeBetween': return "the ${a.args?.getAt(2)} from ${at(0)} to ${at(1)}".toString()
case 'file': return "the text of the file ${at(0)}".toString()
}
return "${a.op} of ${g.join(', ')}".toString()
}
String rDevices(Map doc, Object keys) {
List names = ((List) (keys instanceof List ? keys : [])).collect { rName(doc, it) }
return names.isEmpty() ? '(no device chosen)' : names.size() == 1 ? names[0] : names[0..-2].join(', ') + ' and ' + names[-1]
}
String rDelay(Map doc, Map a) {
Object s = a.seconds
if (s == null && a.modeSeconds == null && a.randomSeconds == null) throw new Exception('a delay with no length')
String len = s instanceof Map ? "the number of ${((Map) s).unit ?: 'seconds'} in ${rName(doc, ((Map) s).ref)}".toString() : s != null ? rSeconds(s) : null
if (a.modeSeconds instanceof List) {
String per = ((List) a.modeSeconds).collect { Object m -> "${(((Map) m).seconds as long) == 0L ? 'no time' : rSeconds(((Map) m).seconds)} in ${rName(doc, ((Map) m).ref)}" }.join(', ')
len = len == null ? "${per}, and no time in any other mode".toString() : "${per}, and ${len} in any other mode".toString()
}
if (a.randomSeconds != null) len = len == null ? "a random time of up to ${rSeconds(a.randomSeconds)}".toString() : "${len}, plus up to ${rSeconds(a.randomSeconds)} at random".toString()
return len
}
String rCancelable(Map a) { return a.cancelable == true ? ' A Cancel delayed actions step can cancel it.' : '' }
String rInput(Map a) { return a.containsKey('input') ? " with the input ${a.input}".toString() : '' }
String rRules(Map doc, Object keys) {
List names = ((List) (keys instanceof List ? keys : [])).collect { Object k ->
Map ref = ((Map) (doc.refs ?: [:])).get(k)
ref?.kind == 'self' ? 'this rule' : ref?.kind == 'rmRule' ? "Rule Machine rule ${ref.name ?: ref.id}".toString() : rName(doc, k)
}
return names.isEmpty() ? '(no rule chosen)' : names.size() == 1 ? names[0] : names[0..-2].join(', ') + ' and ' + names[-1]
}
String renderModeCase(Object modes) {
List m = (modes instanceof List ? modes : [])
return m.isEmpty() ? 'When the mode is (no mode chosen)' : "When the mode is ${m.size() == 1 ? m[0] : m[0..-2].join(', ') + ' or ' + m[-1]}".toString()
}
String renderGradual(Map doc, Map a) {
String who = rDevices(doc, a.refs)
if (a.type == 'flashSwitches') {
String rate = a.seconds != null ? rSeconds(a.seconds) : '(no rate chosen)'
return "Flash ${who}: on for ${rate}, then off for ${rate}, ${a.times != null ? a.times + ' times, ending off' : 'until stopped'}; the steps after this carry on meanwhile".toString()
}
if (a.type == 'fade') {
boolean ct = a.attribute == 'colorTemperature'
String step = a.stepSeconds != null ? ", a step every ${rSeconds(a.stepSeconds)}" : ''
return "Fade the ${ct ? 'colour temperature' : 'level'} of ${who} to ${a.to}${ct ? 'K' : '%'} over ${a.seconds != null ? rSeconds(a.seconds) : '(no time chosen)'}${step}, each from where it is${ct ? '' : ' (0% if off)'}; the steps after this carry on meanwhile".toString()
}
if (a.type == 'stopGradual') {
String what = [level: 'fades of the level', colorTemperature: 'changes of colour temperature', flash: 'flashing'].get(a.what) ?: 'fades and flashing'
return "Stop the ${what} this rule started".toString()
}
return "Set ${who} to follow the device that started the run: ${a.attribute == 'level' ? 'its level' : 'on or off, as its switch is'}; nothing is sent if there is none".toString()
}
String renderRepeatHead(Map doc, Map a, boolean rm = false) {
String every = a.everySeconds != null ? "every ${rSeconds(a.everySeconds)}" : ''
if (a.repeatKind == 'every') return "${rm ? 'REPEAT' : 'Repeat'} ${every}, ${a.times != null ? a.times + ' times' : 'until stopped'}".toString()
String cond = renderCondition(doc, a.condition)
if (rm) {
String head = a.repeatKind == 'while' ? "REPEAT WHILE (${cond}) (checked before each pass)" : "REPEAT UNTIL (${cond}) (checked before each pass after the first)"
return "${head}${every ? ', ' + every : ''}; if it cannot be checked, the rule's error handling applies".toString()
}
String when = a.repeatKind == 'while' ? "while ${cond} (checked before each pass)" : "until ${cond} (checked before each pass after the first)"
return "Repeat ${every ? every + ' ' : ''}${when}; if it cannot be checked, the rule's error handling applies".toString()
}
String rSeconds(Object s) {
long n = (s as long)
if (n % 3600 == 0 && n >= 3600) return "${n / 3600} hour${n == 3600 ? '' : 's'}".toString()
if (n % 60 == 0 && n >= 60) return "${n / 60} minute${n == 60 ? '' : 's'}".toString()
return "${n} second${n == 1 ? '' : 's'}".toString()
}
String rTimeSpec(Object spec, Object offset, Map doc = null) {
String s = spec instanceof Map ? "the time in ${rName(doc, ((Map) spec).ref)}".toString() : spec as String
if (offset instanceof Map) return "${s}, moved by ${rName(doc, ((Map) offset).ref)} minutes".toString()
if (s != 'sunrise' && s != 'sunset' && !(spec instanceof Map)) return s
long off = offset == null ? 0L : (offset as long)
if (off == 0L) return s
return "${Math.abs(off)} minutes ${off < 0 ? 'before' : 'after'} ${s}".toString()
}
@Field static final Map RENDER_DAYS = [mon: 'Monday', tue: 'Tuesday', wed: 'Wednesday', thu: 'Thursday', fri: 'Friday', sat: 'Saturday', sun: 'Sunday']
String rCron(Object cron) {
Map c = timeCronParse(cron)
if (c.error) return 'not a valid schedule'
List f = ((String) cron).trim().toUpperCase().split(/\s+/) as List
Closure step = { String x, String one, String many ->
if (x == '*') return "every ${one}".toString()
java.util.regex.Matcher m = x =~ /^(\*|0)\/(\d+)$/
return m.matches() ? "every ${m.group(2)} ${many}".toString() : null
}
String when
if (f[0] ==~ /\d+/ && f[1] ==~ /\d+/ && f[2] ==~ /\d+/) {
when = 'at ' + String.format('%02d:%02d', f[2] as int, f[1] as int) + (f[0] == '0' ? '' : String.format(':%02d', f[0] as int))
} else {
List p = []
if (f[0] != '0') p << (step(f[0], 'second', 'seconds') ?: "at second ${f[0]}")
if (f[1] != '*' || f[0] == '0') p << (step(f[1], 'minute', 'minutes') ?: "at minute ${f[1]}")
if (f[2] != '*') p << (step(f[2], 'hour', 'hours') ?: "in hour ${f[2]}")
when = p.join(', ')
}
List ord = ['', 'first', 'second', 'third', 'fourth', 'fifth']
String day = ''
Map dom = c.dom, dow = c.dow
if (dom?.days) day = " on day ${f[3]} of the month"
else if (dom?.last != null) day = dom.last == 0 ? ' on the last day of the month' : " ${dom.last} days before the end of the month"
else if (dom?.lastWeekday) day = ' on the last weekday of the month'
else if (dom?.nearWeekday) day = " on the weekday nearest day ${dom.nearWeekday}"
else if (dow?.days) day = ' on ' + ((List) dow.days).collect { TIME_CRON_DAYS[(it as int) - 1] }.join(', ')
else if (dow?.nth) day = " on the ${ord[dow.nth as int]} ${TIME_CRON_DAYS[(dow.day as int) - 1]} of the month"
else if (dow?.lastOf) day = " on the last ${TIME_CRON_DAYS[(dow.lastOf as int) - 1]} of the month"
String mon = f[4] in ['*', '?'] ? '' : " in ${((List) c.month).collect { TIME_CRON_MONTHS[(it as int) - 1] }.join(', ')}"
String yr = f.size() > 6 && f[6] != '*' ? " in ${f[6]}" : ''
return "${when}${day}${mon}${yr}".toString()
}
String renderTrigger(Map doc, Map t) {
if (t.condition != null) {
Map bare = new LinkedHashMap(t); bare.remove('condition')
return "${renderTrigger(doc, bare)}, if ${renderCondition(doc, (Map) t.condition)}".toString()
}
switch (t.kind) {
case 'deviceAttribute':
String who = rName(doc, t.ref)
String disc = (t.discipline == 'event') ? ' (every report, even a repeat)' : ''
if (t.eventType != null) disc += " (${t.eventType} events only)"
if (t.op != null) return "${who} ${t.attribute} changes to a value that ${RENDER_OPS[t.op] ?: t.op}${rCompareValue(doc, t)}${disc}".toString()
if (t.containsKey('value')) return "${who} ${t.attribute} becomes ${t.value}${disc}".toString()
return "${who} ${t.attribute} changes${disc}".toString()
case 'threshold':
String dir = (t.op == 'below' || t.op == 'atMost') ? 'falls below' : 'rises above'
return "${rName(doc, t.ref)} ${t.attribute} ${dir} ${t.value}".toString()
case 'duration':
return "${renderCondition(doc, (Map) t.predicate)} for ${rSeconds(t.seconds)} without a break".toString()
case 'button':
String verb = [pushed: 'pushed', held: 'held', doubleTapped: 'double-tapped', released: 'released']
.get(t.action ?: 'pushed') ?: (t.action as String)
return "button ${t.number} on ${rName(doc, t.ref)} is ${verb}".toString()
case 'schedule':
String on = t.date ? ((t.date as String).size() == 5 ? " every year on ${t.date}" : " on ${t.date}") :
(t.days ? " on ${((List) t.days).collect { RENDER_DAYS[it] ?: it }.join(', ')}" : '')
return "it is ${rTimeSpec(t.at, t.offsetMinutes, doc)}${on}".toString()
case 'periodic':
return "the schedule ${t.cron} comes round (${rCron(t.cron)})".toString()
case 'mode':
return t.to ? "the mode changes to ${((List) t.to).join(' or ')}".toString() : 'the mode changes'
case 'hubVariable':
return "${rName(doc, t.ref)} changes".toString()
case 'ruleInvocation':
return 'another rule runs this one'
case 'endpoint':
return "its end point is called"
case 'rulePaused':
return "${rName(doc, t.ref)} is ${t.value == false ? 'resumed' : 'paused'}".toString()
case 'hsmStatus':
return t.to ? "HSM's status changes to ${((List) t.to).join(' or ')}".toString() : "HSM's status changes"
case 'hsmAlert':
return t.alerts ? "HSM raises a ${((List) t.alerts).join(' or ')} alert".toString() : 'HSM raises any alert'
case 'locationEvent':
return "the hub reports ${((List) (t.events ?: [])).join(' or ')}".toString()
}
return "[unrecognised trigger ${t.kind}]".toString()
}
String rBracketed(String s) {
if (s.startsWith('(') && s.endsWith(')')) {
int depth = 0
boolean whole = true
for (int i = 0; i < s.length(); i++) {
char ch = s.charAt(i)
if (ch == (char) '(') depth++
else if (ch == (char) ')') depth--
if (depth == 0 && i < s.length() - 1) { whole = false; break }
}
if (whole) return s
}
return "(${s})".toString()
}
String rCountLead(Map c) {
Object min = c.min
Object max = c.max
if (max == null) return "at least ${min} of these ${min == 1 ? 'is' : 'are'} true".toString()
if (min == 1 && max == 1) return 'exactly one of these is true'
if (min == max) return "exactly ${min} of these are true".toString()
return "from ${min} to ${max} of these are true".toString()
}
String renderCondition(Map doc, Map c) {
if (c == null) return 'always'
switch (c.type) {
case 'all':
List parts = ((List) (c.of ?: [])).collect { renderCondition(doc, it) }
if (parts.isEmpty()) return 'always'
return parts.size() == 1 ? parts[0] : "(${parts.join(' and ')})".toString()
case 'any':
List parts = ((List) (c.of ?: [])).collect { renderCondition(doc, it) }
if (parts.isEmpty()) return 'never'
return parts.size() == 1 ? parts[0] : "(${parts.join(' or ')})".toString()
case 'not':
return "not ${renderCondition(doc, (Map) c.of)}".toString()
case 'xor':
List xs = ((List) (c.of ?: [])).collect { renderCondition(doc, it) }
return xs.size() == 2 ? "(either ${xs[0]} or ${xs[1]}, not both)".toString() : "(an odd number of: ${xs.join('; ')})".toString()
case 'count':
List cs = ((List) (c.of ?: [])).collect { renderCondition(doc, it) }
return "(${rCountLead(c)}: ${cs.join('; ')})".toString()
case 'timeSince':
String what = c.attribute == null ? 'anything' : (c.containsKey('value') ? "${c.attribute} ${c.value}".toString() : c.attribute as String)
return "${c.op == 'below' ? 'less than' : 'at least'} ${rSeconds(c.seconds)} have passed since ${rName(doc, c.ref)} last reported ${what}".toString()
case 'dayOfWeek':
return "it is ${((List) (c.days ?: [])).collect { RENDER_DAYS[it] ?: it }.join(' or ')}".toString()
case 'dateRange':
Closure day = { Object x -> x instanceof Map ? rName(doc, ((Map) x).ref) : ((x as String).size() == 5 ? "${x} (every year)".toString() : x as String) }
return c.to == null ? "the date is ${day(c.from)}".toString() : "the date is from ${day(c.from)} to ${day(c.to)}".toString()
case 'deviceAttribute':
List keys = []
if (c.ref) keys << c.ref
if (c.refs instanceof List) keys.addAll((List) c.refs)
String who = keys.collect { rName(doc, it) }.join(', ')
String lead = keys.size() > 1 ? "${c.match == 'all' ? 'all of' : 'any of'} ${who}" : who
String fresh = c.freshSeconds != null ? " (reading no older than ${rSeconds(c.freshSeconds)})" : ''
String buttonVerb = [pushed: 'pushed', held: 'held', doubleTapped: 'double-tapped', released: 'released'].get(c.attribute)
if (buttonVerb != null && keys.size() == 1 && (c.op ?: 'is') == 'is') {
return "the last button ${buttonVerb} on ${who} was number ${c.value}${fresh}".toString()
}
Map word = c.everyday instanceof String ? EVERYDAY_WORDS.get(c.everyday) : null
if (word != null) {
String how = c.op == 'matches' ? "by what ${lead} reports".toString()
: "${lead} ${c.attribute} ${RENDER_OPS[c.op ?: 'is'] ?: c.op}${rCompareValue(doc, c)}${c.attribute == 'illuminance' ? ' lux' : ''}".toString()
return "it is ${word.say} (${how})${fresh}".toString()
}
return "${lead} ${c.attribute} ${RENDER_OPS[c.op ?: 'is'] ?: c.op}${rCompareValue(doc, c)}${fresh}".toString()
case 'variable':
return "${rName(doc, c.ref)} ${RENDER_OPS[c.op ?: 'is'] ?: c.op}${rCompareValue(doc, c)}".toString()
case 'mode':
List names = coreModeNames(doc, c)
if (names.isEmpty()) {
boolean chose = c.refs instanceof List && !((List) c.refs).isEmpty() || c.ref != null
return chose ? 'the mode is (a mode this rule does not declare)' : 'the mode is (no mode chosen)'
}
String last = names.last() as String
return "the mode is ${names.size() == 1 ? last : names[0..-2].join(', ') + ' or ' + last}".toString()
case 'timeWindow':
return "it is between ${rTimeSpec(c.from, c.fromOffsetMinutes, doc)} and ${rTimeSpec(c.to, c.toOffsetMinutes, doc)}".toString()
case 'rulePaused':
return "${rName(doc, c.ref)} is ${c.value == false ? 'not paused' : 'paused'}".toString()
case 'waitOutcome':
return (c.value == 'timeout' ? 'the wait before this step gave up'
: 'the wait before this step ended on what it was waiting for')
case 'hsmStatus':
return "HSM's status is ${((List) (c.value ?: [])).join(' or ')}".toString()
case 'triggeredBy':
List started = ((List) (c.triggers ?: [])).collect { rStartedBy(doc, it) }
return "it was started by ${started.join(' or ')}".toString()
}
return "[unrecognised condition ${c.type}]".toString()
}
String rStartedBy(Map doc, Object index) {
List triggers = (doc.triggers ?: [])
int n = (index instanceof Number) ? (index as int) : -1
if (n < 0 || n >= triggers.size() || !(triggers[n] instanceof Map)) return "[unknown trigger ${index}]".toString()
Map t = triggers[n]
String said = renderTrigger(doc, t)
if (t.kind == 'button') return said.replaceFirst(/ is (\S+)$/, ' being $1')
return "the trigger \"${said}\"".toString()
}
List renderActions(Map doc, List actions, String indent = '', boolean rm = false) {
List out = []
actions.eachWithIndex { Object ao, int i ->
Map a = ao
String n = "${indent}${i + 1}. ".toString()
int before = out.size()
switch (a.type) {
case 'command':
String tmpl = a.command == null ? '(no command chosen) on %s' : (RENDER_COMMANDS[a.command as String] ?: "run ${a.command} on %s")
List args = ((List) (a.args ?: [])).collect { it instanceof Map && ((Map) it).ref ? "the value of ${rName(doc, ((Map) it).ref)}".toString() : it }
String who = a.refs instanceof List ? rDevices(doc, (a.ref != null ? [a.ref] : []) + a.refs) : rName(doc, a.ref)
String line
int wanted = (tmpl =~ /%s/).count
if (a.command == 'deviceNotification' || a.command == 'speak') line = String.format(tmpl, args ? args[0] : '?', who)
else if (a.command in ['push', 'hold', 'doubleTap', 'release']) {
String verb = [push: 'push', hold: 'hold', doubleTap: 'double tap', release: 'release'][a.command]
line = "${verb} button ${args ? args[0] : '?'} on ${who}".toString()
}
else if (a.command == 'playSound') line = String.format(tmpl, args ? args[0] : '?', who)
else if (a.command == 'setColor' || a.command == 'toggleColor') line = String.format(tmpl, who, rColourWords(args))
else line = String.format(tmpl, ([who] + (wanted > 1 ? [args ? args[0] : '?'] : [])) as Object[])
if (a.command in ['setColorTemperature', 'toggleColorTemperature'] && args.size() > 1 && args[1] != null) line += " at ${args[1]}%"
if (a.command == 'flash' && args) line += ", every ${args[0]} ms"
if (a.command == 'speak' && args.size() > 1 && args[1] != null && args[1] != '') line += " at volume ${args[1]}"
if (a.command == 'speak' && args.size() > 2 && args[2] != null && args[2] != '') line += " in the voice ${args[2]}"
Object fade = a.command in ['setLevel', 'adjustLevel'] ? (args.size() > 1 ? args[1] : null) :
a.command == 'setColorTemperature' ? (args.size() > 2 ? args[2] : null) : null
if (fade != null) line += " over ${rSeconds(fade)}"
if (a.onlyIfOn == true) line += ', only those that are on'
if (a.refs instanceof List && ((a.meterMs ?: 0) as int) > 0) line += ", one at a time, ${a.meterMs} ms apart"
out << (n + line.capitalize())
break
case 'setVariable':
out << "${n}Set ${rName(doc, a.ref)} to ${a.op == null ? a.value : rSetVarOp(doc, a)}".toString()
break
case 'wait':
if (a.waitKind == 'delay') {
out << "${n}Wait ${rDelay(doc, a)}.${rCancelable(a)} ${rRestart(a)}".toString()
} else {
String what = a.waitKind == 'until'
? "until ${renderCondition(doc, (Map) a.predicate)}${a.staysSeconds != null ? ' and it has stayed so for ' + rSeconds(a.staysSeconds) + ' without a break' : ''}"
: a.waitKind == 'forEvents'
? "${a.match == 'all' ? 'until all of these have happened, each at least once' : 'for any of these'}: ${((List) (a.events ?: [])).findAll { !(it instanceof Map && ((Map) it).disabled == true) }.collect { renderTrigger(doc, (Map) it) }.join('; ')}"
: "for ${rName(doc, a.ref)}${a.attribute ? ' ' + a.attribute : ''}${a.containsKey('value') ? ' to become ' + a.value : ' to change'}"
String timeout = {
switch (a.onTimeout ?: 'stopRun') {
case 'continue': return 'then carry on anyway'
case 'failurePath': return 'then treat it as a failure'
default: return 'then stop'
}
}()
out << (a.timeoutSeconds == null
? "${n}Wait ${what}, with no time limit. ${rRestart(a)}".toString()
: "${n}Wait ${what}, giving up after ${rSeconds(a.timeoutSeconds)}, ${timeout}. ${rRestart(a)}".toString())
}
break
case 'if':
out << (rm ? "${n}IF ${rBracketed(renderCondition(doc, (Map) a.condition))} THEN" : "${n}If ${renderCondition(doc, (Map) a.condition)}:").toString()
out.addAll(renderActions(doc, (a.then ?: []), indent + '    ', rm))
((List) (a.elseIf ?: [])).each { Object eo ->
Map e = eo
out << (rm ? "${indent}   ELSE-IF ${rBracketed(renderCondition(doc, (Map) e.condition))} THEN" : "${indent}   Otherwise, if ${renderCondition(doc, (Map) e.condition)}:").toString()
out.addAll(renderActions(doc, (e.then ?: []), indent + '    ', rm))
}
if (a.otherwise) {
out << "${indent}   ${rm ? 'ELSE' : 'Otherwise:'}".toString()
out.addAll(renderActions(doc, a.otherwise, indent + '    ', rm))
}
String unread = (doc.ifUnknown ?: 'stop') != 'otherwise'
? 'If that condition cannot be checked, neither branch runs.'
: ((a.elseIf || a.otherwise)
? 'If a condition here cannot be checked, it counts as not met, so the next branch runs.'
: 'If that condition cannot be checked, it counts as not met, so these steps are skipped.')
out << "${indent}   ${rm ? 'END-IF. ' : ''}${unread}".toString()
break
case 'runRule':
if (a.wait == true) {
String keep = a.result ? ", keeping what it returns in ${rName(doc, a.result)}" : ''
String then = a.onTimeout == 'continue' ? 'carry on anyway' : a.onTimeout == 'failurePath' ? 'treat it as a failure' : 'stop'
out << "${n}Run the rule ${rName(doc, a.ref)}${rInput(a)} and wait for it to finish${keep}. If it has not finished after ${rSeconds(a.timeoutSeconds)}, ${then}.".toString()
} else {
out << "${n}Run the rule ${rName(doc, a.ref)}${rInput(a)}".toString()
}
break
case 'return':
out << "${n}Stop here and return ${a.containsKey('ref') ? 'the value of ' + rName(doc, a.ref) : a.value} to the rule that ran this one".toString()
break
case 'capture':
out << "${n}Remember how ${rDevices(doc, a.refs)} ${((List) (a.refs ?: [])).size() == 1 ? 'is' : 'are'} set".toString()
break
case 'restore':
out << "${n}Put ${rDevices(doc, a.refs)} back as last remembered".toString()
break
case 'customCommand':
List cargs = (a.args ?: [])
out << "${n}Send the command ${a.command ?: '(none chosen)'} to ${rName(doc, a.ref)}${cargs ? ' with ' + cargs.collect { it instanceof String ? '"' + it + '"' : it }.join(', ') : ''}".toString()
break
case 'log':
out << "${n}Write to the hub's log${a.level == 'warn' ? ' as a warning' : ''}: \"${a.text ?: '(no text yet)'}\"".toString()
break
case 'deviceEnabled':
List dnames = ((List) (a.refs ?: [])).collect { rName(doc, it) }
out << "${n}${a.enabled == true ? 'Enable' : 'Disable'} ${dnames ? dnames.join(', ') : '(no devices chosen)'}${a.enabled == true ? '' : ': the hub ignores them, for every app, until they are enabled again'}".toString()
break
case 'hubReboot':
out << "${n}Reboot the hub".toString()
break
case 'hubShutdown':
out << "${n}Shut down the hub".toString()
break
case 'hsm':
out << "${n}Tell Hubitat Safety Monitor to ${a.op ?: '(nothing chosen)'}".toString()
break
case 'setMode':
Map mref = ((Map) (doc.refs ?: [:])).get(a.ref)
out << "${n}Change the hub's mode to ${mref ? mref.name : (a.value ?: '(no mode chosen)')}".toString()
break
case 'setRuleBoolean':
out << "${n}Set the Private Boolean of ${rRules(doc, a.rules)} to ${a.value}".toString()
break
case 'http':
String keep = a.result ? ", keeping ${a.field ? a.field + ' from ' : ''}the answer in ${rName(doc, a.result)}; if it fails or none comes within ${rSeconds(a.timeoutSeconds ?: NET_DEFAULT_TIMEOUT_SECONDS)}, the step fails" : ''
if (a.saveFile) keep = ", writing ${a.select ? 'the selected JSON values' : a.rssItems ? 'the first ' + a.rssItems + ' RSS items' : 'the answer'} to ${a.saveFile}; if it fails or none comes within ${rSeconds(a.timeoutSeconds ?: NET_DEFAULT_TIMEOUT_SECONDS)}, the step fails".toString()
String sent = a.method == 'post' ? " with ${a.contentType ?: 'application/json'} \"${a.body ?: ''}\"" : ''
out << "${n}Send an HTTP ${a.method == 'post' ? 'POST' : 'GET'} to ${a.url ?: '(no address yet)'}${sent}${keep}".toString()
break
case 'ping':
List into = ((Map) (a.into ?: [:])).collect { k, v -> "${k} in ${rName(doc, v)}" }
out << "${n}Ping ${a.host ?: '(no host yet)'}${into ? ', keeping ' + into.join(', ') : ''}".toString()
break
case 'file':
String fname = a.name ?: '(no file yet)'
out << (a.op == 'delete' ? "${n}Delete the file ${fname}" : "${n}${a.op == 'append' ? 'Add' : 'Write'} \"${a.text ?: ''}\" ${a.op == 'append' ? 'to the end of' : 'to'} the file ${fname}").toString()
break
case 'controlRule':
String verb = [pause: 'Pause', resume: 'Resume', cancelTimers: 'Cancel the waits and delays of', runActions: 'Run the actions of'].get(a.op) ?: "${a.op}"
out << "${n}${verb} ${rRules(doc, a.rules)}".toString()
break
case 'repeat':
out << "${n}${renderRepeatHead(doc, a, rm)}${rm ? '' : ':'}".toString()
out.addAll(renderActions(doc, (a.steps ?: []), indent + '    ', rm))
out << "${indent}   ${rm ? 'END-REP. The' : 'End of the repeat; the'} steps after it run once it ends.${a.everySeconds != null ? ' ' + rRestart(a) : ''}".toString()
break
case 'stopRepeat':
out << "${n}${rm ? 'STOP REPEATING' : 'Stop repeating'}: every repeat of this rule ends before its next pass".toString()
break
case 'later':
out << "${n}After ${rDelay(doc, a)}, while the rest carries on, do these steps.${rCancelable(a)} ${rRestart(a)}".toString()
out.addAll(renderActions(doc, (a.actions ?: []), indent + '    ', rm))
break
case 'cancelDelays':
out << "${n}Cancel this rule's cancelable delays, and the steps they hold back".toString()
break
case 'byMode':
out << "${n}${rm ? 'DEPENDING ON THE MODE' : 'Depending on the mode:'}".toString()
((List) (a.cases ?: [])).each { Object co ->
Map c = co
out << "${indent}   ${rm ? renderModeCase(c.modes).replaceFirst('^When the mode is', 'MODE IS') : renderModeCase(c.modes)}:".toString()
out.addAll(renderActions(doc, (c.then ?: []), indent + '    ', rm))
}
out << "${indent}   ${rm ? (a.otherwise ? 'ANY OTHER MODE:' : 'ANY OTHER MODE: nothing.') : (a.otherwise ? 'In any other mode:' : 'In any other mode, nothing.')}".toString()
if (a.otherwise) out.addAll(renderActions(doc, a.otherwise, indent + '    ', rm))
out << "${indent}   ${rm ? 'END-MODE. ' : ''}If the mode cannot be read, no case runs.".toString()
break
case 'flashSwitches':
case 'fade':
case 'stopGradual':
case 'track':
out << "${n}${renderGradual(doc, a)}".toString()
break
case 'exit':
out << "${n}${rm ? 'EXIT RULE' : 'Stop here'}: the rest of the steps do not run".toString()
break
case 'comment':
out << "${n}Note: ${a.text ?: '(no text yet)'}".toString()
break
default:
out << "${n}[unrecognised action ${a.type}]".toString()
}
if (a.disabled == true && out.size() > before) out[before] = n + '(Disabled, skipped) ' + (out[before] as String).substring(n.length())
}
return out
}
List renderRule(Map doc) {
List out = []
out << "Rule: ${doc.name}".toString()
List all = ((List) (doc.triggers ?: []))
List triggers = all.findAll { !(it instanceof Map && ((Map) it).disabled == true) }.collect { renderTrigger(doc, it) }
List off = all.findAll { it instanceof Map && ((Map) it).disabled == true }.collect { renderTrigger(doc, it) }
out << (triggers ? "Runs when ${triggers.join(', or when ')}.".toString() :
(off ? 'Runs when: every trigger is disabled, so nothing starts it.' : 'Runs when: no trigger yet.'))
if (off) out << "Disabled, so it does not start the rule: ${off.join('; ')}.".toString()
if (doc.requiredExpression != null) {
Map prerequisite = doc.requiredExpression
if (prerequisite.of instanceof List && ((List) prerequisite.of).isEmpty()) {
out << 'Prerequisite is on, but no condition has been added yet.'
} else {
out << "Triggers count only while ${renderCondition(doc, prerequisite)}${doc.cancelOnFalse == true ? '; when that stops being true, runs under way are cancelled' : ''}.".toString()
}
}
if (doc.conditions != null) {
out << "Only if ${renderCondition(doc, (Map) doc.conditions)}.".toString()
out << ((doc.onUnknown ?: 'block') == 'proceed'
? 'If that cannot be checked, it runs anyway.'
: 'If that cannot be checked, it does nothing.')
}
out << 'Then:'
out.addAll(renderActions(doc, (doc.actions ?: []), '  ', true))
switch (doc.rerunPolicy ?: 'restart') {
case 'restart': out << 'If it is triggered again while still running, it starts over.'; break
case 'ignore': out << 'If it is triggered again while still running, the new trigger is ignored.'; break
case 'queue': out << "If it is triggered again while still running, up to ${doc.queueLimit} runs wait their turn.".toString(); break
case 'parallel': out << "Up to ${doc.parallelLimit} runs can happen at once.".toString(); break
}
switch (doc.errorPolicy ?: 'stopRun') {
case 'continue': out << 'If a step fails, the remaining steps still run.'; break
case 'failurePath':
out << 'If a step fails:'
out.addAll(renderActions(doc, (doc.onFailure ?: []), '  ', true))
break
default: out << 'If a step fails, it stops there.'
}
if (doc.onCancel instanceof List && !((List) doc.onCancel).isEmpty()) {
out << 'If a run is cancelled:'
out.addAll(renderActions(doc, doc.onCancel, '  ', true))
} else if (doc.cancelOnFalse == true) {
out << 'A cancelled run stops where it stands: no further steps run, and anything it has already set stays as it is.'
}
if (doc.pauseMode == 'hold') out << 'If it is paused, runs already going wait where they are until it is resumed.'
List logs = [logEvents: 'events', logTriggers: 'triggers', logActions: 'actions'].findAll { k, v -> doc[k] == true }.collect { it.value }
if (logs) out << "It logs its ${logs.join(', ')} to the hub's Logs page.".toString()
return out
}
void dFlatten(Object node, String path, Map out) {
if (node instanceof Map) {
if (((Map) node).isEmpty()) { out.put(path, '{}'); return }
((Map) node).keySet().sort().each { k -> dFlatten(((Map) node).get(k), path ? "${path}.${k}".toString() : (k as String), out) }
return
}
if (node instanceof List) {
if (((List) node).isEmpty()) { out.put(path, '[]'); return }
((List) node).eachWithIndex { Object v, int i -> dFlatten(v, "${path}.${i}".toString(), out) }
return
}
out.put(path, node == null ? null : node.toString())
}
Map diffRules(Map before, Map after) {
Map a = [:]
Map b = [:]
if (before != null) dFlatten(before, '', a)
dFlatten(after, '', b)
List added = [], removed = [], changed = []
b.each { k, v ->
if (!a.containsKey(k)) added << [path: k, value: v]
else if (a[k] != v) changed << [path: k, from: a[k], to: v]
}
a.each { k, v -> if (!b.containsKey(k)) removed << [path: k, value: v] }
List ra = before == null ? [] : renderRule(before)
List rb = renderRule(after)
return [
newRule : before == null,
added : added,
removed : removed,
changed : changed,
renderedAdded : rb.findAll { !ra.contains(it) },
renderedRemoved: ra.findAll { !rb.contains(it) },
identical : added.isEmpty() && removed.isEmpty() && changed.isEmpty(),
]
}
Object proposeCanon(Object v) {
if (v == null) return [t: 'null']
if (v instanceof Map) {
List pairs = ((Map) v).collect { k, x -> [k.toString(), proposeCanon(x)] }.sort { a, b -> a[0] <=> b[0] }
return [t: 'map', v: pairs]
}
if (v instanceof Set) {
List keyed = ((Set) v).collect { Object item ->
Object encoded = proposeCanon(item)
[key: jsonCanonicalise(encoded), value: encoded]
}
keyed.sort { a, b -> a.key <=> b.key }
return [t: 'set', v: keyed.collect { it.value }]
}
if (v instanceof List) return [t: 'list', v: ((List) v).collect { proposeCanon(it) }]
if (v instanceof Boolean) return [t: 'bool', v: v]
if (v instanceof CharSequence) return [t: 'str', v: v.toString()]
if (v instanceof Number) return [t: 'num', v: new BigDecimal(v.toString()).stripTrailingZeros().toPlainString()]
return [t: 'other', v: v.toString()]
}
String proposeEvidenceHash(Object v, Map timing = null) {
long phaseStarted = timing == null ? 0L : this.now()
Object typed = proposeCanon(v)
if (timing != null) { timing.digestTypedMs = this.now() - phaseStarted; phaseStarted = this.now() }
String encoded = groovy.json.JsonOutput.toJson(typed)
if (timing != null) { timing.digestFastJsonMs = this.now() - phaseStarted; phaseStarted = this.now() }
boolean fallback = encoded.contains('\\u')
String canonical = null
if (timing?.profileCanonical == true) {
canonical = jsonCanonicalise(typed)
timing.digestCanonicalJsonMs = this.now() - phaseStarted
phaseStarted = this.now()
}
if (fallback) encoded = canonical == null ? jsonCanonicalise(typed) : canonical
if (canonical != null && encoded != canonical) throw new Exception('evidence encoding changed in the Dev profile')
if (timing != null) { timing.digestFallback = fallback; phaseStarted = this.now() }
byte[] digest = MessageDigest.getInstance('SHA-256').digest(encoded.getBytes('UTF-8'))
String hash = digest.collect { String.format('%02x', it) }.join('')
if (timing != null) timing.digestHashMs = this.now() - phaseStarted
return hash
}
String proposeInventoryDigest(Map inv, Map timing = null) {
Map basis = [
devices : ((Map) inv.devices).values().collect { Map d ->
[id: d.id, name: d.name, room: d.room, driver: d.driver, disabled: d.disabled,
capabilities: d.capabilities, commands: d.commands, attributes: d.attributes,
currentStates: d.currentStates, metadata: d.metadata]
}.sort { it.id.toString() },
modes : inv.modes,
currentMode: inv.currentMode,
variables : inv.variables,
rules : inv.rules,
]
return proposeEvidenceHash(basis, timing)
}
List proposeActivationBlockers(List findings) {
return findings.findAll { (it.code as String)?.endsWith('_UNVERIFIED') || it.code == 'REF_AVAILABILITY_UNKNOWN' }
.collect { [code: it.code, path: it.path, message: it.message] }
}
List proposeRequiredApprovals(List findings) {
return []
}
@Field static final String AUTHOR_MODEL = 'claude-opus-5'
@Field static final String AUTHOR_API_URL = 'https://api.anthropic.com/v1/messages'
@Field static final String AUTHOR_API_VERSION = '2023-06-01'
@Field static final String AUTHOR_BETA = 'server-side-fallback-2026-07-01'
@Field static final int AUTHOR_MAX_REQUEST_CHARS = 2000
@Field static final String AUTHOR_TOOL_NAME = 'submit_rule'
List authorRuleIndex(List haiRules, Map detail, Collection rmIds, Map rmNames) {
Closure dev = { Object id -> String s = id as String; s.startsWith('dev:') ? s : "dev:${s}".toString() }
List out = []
((List) (haiRules ?: [])).each { Object o ->
Map r = o
List on = ((Collection) (r.listens ?: [])).collect { dev(it) }.unique().sort()
List uses = (((Collection) (r.deviceIds ?: [])).collect { dev(it) } - on).unique().sort()
out << [kind: 'rule', id: r.id as String, name: (r.name ?: r.id) as String, on: on, uses: uses]
}
((Collection) (rmIds ?: [])).each { Object id ->
Map rule = detail == null ? null : hamRule(detail, id)
Map row = [kind: 'rmRule', id: id as String, name: ((rmNames ?: [:]).get(id) ?: rule?.name ?: id) as String]
if (rule != null) {
Set on = [] as Set
((List) (rule.triggers ?: [])).each { Object t ->
Object ds = ((Map) (((Map) t).operands ?: [:])).devices
if (ds instanceof List) ((List) ds).each { Object d -> if (d instanceof Map && ((Map) d).id != null) on << dev(((Map) d).id) }
}
row.on = new ArrayList(on).sort()
row.uses = (rmDevicesNamed(rule).keySet().collect { dev(it) } - on).unique().sort()
}
out << row
}
return out
}
Map authorHeaders(String apiKey, boolean withFallback = true, String workspaceId = null) {
Map h = ['x-api-key': apiKey, 'anthropic-version': AUTHOR_API_VERSION, 'content-type': 'application/json']
if (withFallback) h['anthropic-beta'] = AUTHOR_BETA
String ws = workspaceId?.trim()
if (ws) h['anthropic-workspace-id'] = ws
return h
}
Map aFinish(Map session, String status, String message, Map extra = [:]) {
session.status = 'done'
session.nextRequest = null
session.messages = []
session.remove('lastProposal')
Map outcome = [status: status, message: message, attempts: session.attempts, repairs: session.repairs,
transportRetries: session.transportRetries, usage: session.usage,
firstAttemptParsed: session.firstAttemptParsed,
firstAttemptSchemaValid: session.firstAttemptSchemaValid,
models: session.models, fallbackUsed: ((List) session.models).any { it != AUTHOR_MODEL },
repairReasons: (session.repairReasons ?: [])]
int unexplained = (session.repairs as int) - ((List) outcome.repairReasons).size()
if (unexplained > 0) outcome.repairReasonsNote = "no reason was recorded for ${unexplained} correction(s)".toString()
if (session.budget != null) {
Map b = session.budget
outcome.spending = [limitUsd: b.limitUsd, spentUsd: b.spentUsd, committedUsd: authorCommitted(b),
outstanding: ((Map) b.outstanding).size(), stopped: b.stopped, entries: b.entries]
}
outcome.putAll(extra)
session.outcome = outcome
session.log << [what: 'finished', status: status, message: message]
return session
}
String authorStaleReason(Map session, Map tag, boolean isResend = false) {
if (session == null) return 'there is no drafting session'
if (session.status != 'awaitingModel') return 'the session has already finished'
if (tag == null || (tag.generation as String) != (session.generation as String)) return 'it belongs to an earlier request'
if (isResend) return session.pendingRequestId == null ? null : 'a request is already outstanding'
if (tag.requestId == null || (tag.requestId as String) != (session.pendingRequestId as String)) {
return 'it is a duplicate or arrived out of order'
}
return null
}
void authorMarkReceived(Map session) {
session.pendingRequestId = null
}
@Field static final Map AUTHOR_PRICES = ['claude-opus-5': [input: 5.0d, output: 25.0d]]
double authorCommitted(Map budget) {
if (budget == null) return 0.0d
double open = ((Map) (budget.outstanding ?: [:])).values().sum(0.0d) { it as double } as double
return (budget.spentUsd as double) + open
}
Map authorFoldBudget(Map appSpend, Map sessionBudget, long at) {
double spent = ((appSpend?.spentUsd ?: 0.0d) as double)
Map stop = appSpend?.stop
if (sessionBudget == null) return [spentUsd: spent, stop: stop]
spent += authorCommitted(sessionBudget)
if (stop == null && sessionBudget.stopped) {
stop = [reason: aCut(sessionBudget.stopped, 256), at: at]
}
return [spentUsd: spent, stop: stop]
}
String authorSpendStopClearRefusal(Map stop, Object limitUsd, Object confirmUsd) {
if (stop == null) return 'there is no spending stop to clear'
if (limitUsd == null) return 'set a spending limit first'
if (confirmUsd == null) return 'enter the spending limit again to confirm'
double limit
double confirm
try {
limit = limitUsd as double
confirm = confirmUsd as double
} catch (Exception e) {
return 'the confirmation is not a number'
}
if (Math.abs(limit - confirm) > 1e-9d) return 'the confirmation does not match the saved spending limit'
return null
}
String aCut(Object v, int max) {
if (v == null) return null
String s = v.toString()
return s.length() > max ? s.substring(0, max) + '...' : s
}
@Field static final String EDITOR_VERSION = '0.1.0'
@Field static final int TL_SUMMARY_ITEMS_EDITOR = 5
@Field static final int EDITOR_MAX_BODY_CHARS = 262144
@Field static final int EDITOR_MAX_DRAFT_BYTES = 65536
@Field static final int EDITOR_MAX_DRAFTS_BYTES = 393216
@Field static final int EDITOR_MAX_KEPT_RULES = 10
@Field static final int EDITOR_MAX_KEPT_RULE_BYTES = 65536
@Field static final int EDITOR_MAX_NAME_CHARS = 80
@Field static final int EDITOR_MAX_ID_CHARS = 64
@Field static final String EDITOR_EFFECTS = "Save draft keeps this rule here, in the editor, and changes nothing on the hub. Activate on hub (Update on hub for a rule already there) is the one that puts it live, and HAI checks it before it runs. Run rule runs the hub's version once, now."
@Field static final String EDITOR_PAGE_FILE = 'hai-editor.html'
@Field static final String EDITOR_PAGE_SHA256 = 'bb425899e9dc1a615d759ea90ec52a87cd3f5929d7fb929ef2b10b33a0f7ca8b'
@Field static final String EDITOR_SPECS_FILE = 'hai-editor-specs.json'
@Field static final String EDITOR_SPECS_SHA256 = 'd24031d13143e19736db036db89e38ff3bf9c86a628f578a0666d89ad5f12f23'
@Field static final int EDITOR_MAX_OTHER_COMMANDS = 200
@Field static final int EDITOR_MAX_DECLARED_VALUES = 40
@Field static final List EDITOR_ICON_RULES = [
[key: 'locks', caps: ['Lock', 'LockCodes']],
[key: 'presence', caps: ['PresenceSensor']],
[key: 'doors', caps: ['ContactSensor', 'GarageDoorControl', 'DoorControl']],
[key: 'water', caps: ['WaterSensor', 'Valve']],
[key: 'motion', caps: ['MotionSensor']],
[key: 'safety', caps: ['SmokeDetector', 'CarbonMonoxideDetector']],
[key: 'buttons', caps: ['PushableButton', 'HoldableButton', 'DoubleTapableButton', 'ReleasableButton']],
[key: 'cameras', caps: ['ImageCapture']],
[key: 'shades', caps: ['WindowShade']],
[key: 'broker', caps: ['Notification']],
[key: 'climate', caps: ['Thermostat', 'ThermostatMode', 'ThermostatSetpoint', 'ThermostatCoolingSetpoint',
'ThermostatHeatingSetpoint', 'ThermostatOperatingState', 'ThermostatFanMode', 'FanControl']],
[key: 'lighting', caps: ['Light', 'ColorControl', 'ColorTemperature', 'ColorMode', 'SwitchLevel', 'LightEffects']],
[key: 'security', caps: ['Alarm', 'Chime', 'Tone']],
[key: 'media', caps: ['AudioVolume', 'SpeechSynthesis', 'MediaTransport', 'MusicPlayer']],
[key: 'switches', caps: ['Switch', 'Outlet']],
[key: 'energy', caps: ['PowerMeter', 'EnergyMeter', 'VoltageMeasurement']],
[key: 'environmental', caps: ['TemperatureMeasurement', 'IlluminanceMeasurement', 'RelativeHumidityMeasurement',
'PressureMeasurement', 'CarbonDioxideMeasurement', 'UltravioletIndex']],
[key: 'sensor', caps: ['Sensor']],
]
@Field static final List EDITOR_ICON_NAME_HINTS = [
[key: 'buttons', words: ['button', 'remote']],
[key: 'appliance', words: ['kettle', 'oven', 'fridge', 'refrigerator', 'dishwasher', 'washer', 'dryer', 'microwave', 'toaster']],
[key: 'network', words: ['internet', 'wifi', 'router', 'modem']],
[key: 'hub', words: ['bridge']],
[key: 'display', words: ['display', 'monitor', 'tablet', 'nest']],
[key: 'climate', words: ['heater', 'dehumidifier', 'dehumidifyer', 'humidifier', 'aircon']],
[key: 'environmental', words: ['temperature', 'humidity', 'illuminance', 'lux']],
[key: 'lighting', words: ['light', 'lights', 'lamp', 'bulb']],
]
@Field static final List EDITOR_ICON_KEYS = ['locks', 'presence', 'doors', 'water', 'motion', 'safety', 'buttons',
'cameras', 'shades', 'broker', 'climate', 'lighting', 'security', 'media', 'switches', 'energy', 'environmental',
'sensor', 'hub', 'ai', 'appliance', 'network', 'display', 'scene', 'connector', 'unknown']
String editorIconKey(String name, Collection capabilities, String driver = null) {
String drv = (driver ?: '').toLowerCase()
if (drv.contains('scene')) return 'scene'
if (drv.contains('connector')) return 'connector'
List words = ((name ?: '').toLowerCase().split('[^a-z0-9]+') as List)
for (Object o in EDITOR_ICON_NAME_HINTS) {
Map hint = o
if (((List) hint.words).any { words.contains(it) }) return (String) hint.key
}
Set caps = ((capabilities ?: []) as List).collect { it.toString() } as Set
for (Object o in EDITOR_ICON_RULES) {
Map rule = o
if (((List) rule.caps).any { caps.contains(it) }) return (String) rule.key
}
return 'unknown'
}
String editorFixedVersion() { return EDITOR_SPECS_SHA256.take(12) }
String editorFixedText() {
String text = (String) EDITOR_FILE_CACHE.get('specs:' + EDITOR_SPECS_SHA256)
if (text == null) {
byte[] b = null
try { b = downloadHubFile(EDITOR_SPECS_FILE) } catch (Exception e) { b = null }
String h = b == null ? null : MessageDigest.getInstance('SHA-256').digest(b).collect { String.format('%02x', it) }.join('')
if (h != EDITOR_SPECS_SHA256) throw new Exception("the editor's tables (${EDITOR_SPECS_FILE}) in the File Manager are missing or not the ones this version of HAI Engine was built with; deploy HAI Engine again".toString())
text = new String(b, 'UTF-8')
EDITOR_FILE_CACHE.put('specs:' + EDITOR_SPECS_SHA256, text)
}
return text
}
@Field static final java.util.concurrent.ConcurrentHashMap EDITOR_FILE_CACHE = new java.util.concurrent.ConcurrentHashMap()
List edSorted(Object c) {
return c instanceof Collection ? ((Collection) c).collect { it.toString() }.sort() : null
}
List editorDevices(Map inv) {
return ((Map) (inv?.devices ?: [:])).values().collect { Object d ->
Map dev = d
Set caps = ((dev.capabilities ?: []) as List).collect { it.toString() } as Set
Set offered = dev.commands instanceof Collection ? (((Collection) dev.commands).collect { it.toString() } as Set) : null
List engine = CATALOGUE.findAll { k, op -> caps.containsAll((List) op.requires) && schemaDeviceOffers(k as String, offered) }
.keySet().sort()
Map values = [:]
((Map) (dev.currentStates ?: [:])).each { k, v -> if (((Map) v)?.value != null) values.put(k.toString(), ((Map) v).value) }
Map row = [id: dev.id, name: dev.name, room: dev.room, capabilities: caps.sort(),
engineCommands: engine, attributes: edSorted(dev.attributes)]
boolean unread = dev.valuesRead == false
if (!unread) row.values = values
Set reported = values.keySet().collect { it.toString() } as Set
if (!unread && dev.declaredValues instanceof Map) {
Map dv = [:]
((Map) dev.declaredValues).each { k, vs ->
if (vs instanceof Collection && reported.contains(k.toString())) {
dv.put(k.toString(), ((Collection) vs).collect { it.toString() }.take(EDITOR_MAX_DECLARED_VALUES))
}
}
if (dv) row.declaredValues = dv
}
if (offered != null) {
List other = (offered - engine).sort()
row.otherDeviceCommands = other.take(EDITOR_MAX_OTHER_COMMANDS)
if (other.size() > EDITOR_MAX_OTHER_COMMANDS) row.otherDeviceCommandsNotShown = other.size() - EDITOR_MAX_OTHER_COMMANDS
} else {
row.otherDeviceCommands = null
}
String ham = dev.hamIconKey instanceof String ? (String) dev.hamIconKey : null
if (ham && EDITOR_ICON_KEYS.contains(ham)) { row.iconKey = ham; row.iconFrom = 'ham' }
else row.iconKey = editorIconKey(dev.name as String, caps, dev.driver as String)
if (dev.disabled != null) row.disabled = dev.disabled
if (dev.variable) { row.variable = dev.variable; row.variableName = dev.variableName }
Object buttons = values.get('numberOfButtons')
if (buttons != null) {
try { row.buttons = new BigDecimal(buttons.toString()).intValue() } catch (Exception e) { }
}
row
}.sort { a, b -> (a.name <=> b.name) ?: (a.id <=> b.id) }
}
List edVariableRows(Map inv) {
return ((Map) (inv?.variables ?: [:])).values().findAll { ((Map) it).scope == 'hub' }.collect { Object o ->
Map v = o
Map row = [id: v.id, name: v.name, type: v.type, writable: v.writable != false, value: v.value]
if (v.integer) row.integer = true
if (v.connector) row.connector = v.connector
row
}.sort { a, b -> (a.name as String).toLowerCase() <=> (b.name as String).toLowerCase() }
}
Map editorAiStatus(boolean hasKey, Object keyTest, Object spendStop, Object spendLimit) {
if (!hasKey) return [available: false, code: 'nokey', why: 'no Claude API key', act: 'Add one']
if (keyTest instanceof Map && ((Map) keyTest).ok == false && ((Map) keyTest).fault == 'key') {
return [available: false, code: 'rejected', why: 'the Claude API key was not accepted', act: 'Replace it']
}
if (spendStop) return [available: false, code: 'spend', why: 'the AI spending limit has been reached', act: 'Clear the stop']
if (spendLimit == null) return [available: false, code: 'nolimit', why: 'no AI spending limit is set', act: 'Set one']
return [available: true]
}
Map edDraftSummary(Map d) {
Map out = [id: d.id, name: d.name, savedAt: d.savedAt, ruleHash: d.ruleHash, bytes: d.bytes,
ruleId: d.rule instanceof Map ? ((Map) d.rule).id : null]
if (d.needsFixing == true) out.needsFixing = true
if (d.needsFixing == true && d.fixReason instanceof String) out.fixReason = d.fixReason
return out
}
Map editorState(Map inv, Map store, Map extra = [:]) {
return [
ok : true,
effects : EDITOR_EFFECTS,
editor : EDITOR_VERSION,
app : extra.version,
environment : extra.environment,
spending : extra.spending,
runtimeUrl : extra.runtimeUrl,
migrationUrl: extra.migrationUrl,
shadowLights: extra.shadowLights,
migrated : extra.migrated,
reportUrl : extra.reportUrl,
endpointAud : extra.endpointAud,
everyday : extra.everyday,
runtime : editorRuntimeView(extra),
canSend : extra.canSend == true,
ai : extra.ai,
devices : editorDevices(inv),
modes : ((inv?.modes ?: []) as List).collect { it.toString() }.sort(),
variables : edVariableRows(inv),
currentMode : inv?.currentMode,
rmRules : ((Collection) (inv?.rmRules ?: [])).collect { Object id ->
Map row = [id: id.toString(), name: (((Map) (inv?.rmRuleNames ?: [:])).get(id) ?: id).toString()]
Object copy = ((Map) (inv?.rmSwitched ?: [:])).get(id.toString())
if (copy != null) row.switchedTo = copy.toString()
row
}.sort { a, b -> (a.name as String).toLowerCase() <=> (b.name as String).toLowerCase() },
fixed : editorFixedVersion(),
drafts : ((List) (store?.drafts ?: [])).collect { edDraftSummary((Map) it) },
limits : [draftBytes: EDITOR_MAX_DRAFT_BYTES, draftsBytes: EDITOR_MAX_DRAFTS_BYTES,
bodyChars: EDITOR_MAX_BODY_CHARS, nameChars: EDITOR_MAX_NAME_CHARS],
]
}
Map edError(int status, String code, String message) {
return [status: status, body: [ok: false, error: code, message: message]]
}
Map editorReadBody(Object raw) {
if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) return [error: edError(400, 'EMPTY_BODY', 'The request had no body.')]
String text = (String) raw
int kb = (int) Math.ceil(text.length() / 1024.0d)
if (text.length() > EDITOR_MAX_BODY_CHARS) {
return [error: edError(413, 'BODY_TOO_LARGE', "The request is ${kb} KB (${text.length()} characters), larger than the ${EDITOR_MAX_BODY_CHARS} characters the hub takes.".toString())]
}
Map parsed = jsonParse(text)
if (!parsed.ok && (parsed.code in ['LIMIT_SIZE', 'LIMIT_WORK'] || (text.length() >= 32768 && (parsed.code == 'JSON_TRUNCATED' || (parsed.offset as int) >= text.trim().length() - 1)))) {
return [error: edError(413, 'BODY_TOO_LARGE', "The request is ${kb} KB (${text.length()} characters), too large for the hub to read in one piece (${parsed.message}).".toString())]
}
if (!parsed.ok) return [error: edError(400, 'INVALID_JSON', "The request is not valid JSON for this hub: ${parsed.message}".toString())]
if (!(parsed.value instanceof Map)) return [error: edError(400, 'INVALID_SHAPE', 'The request must be a JSON object.')]
return [value: parsed.value]
}
@Field static final String EDITOR_RECEIPT_FORMAT = 'hai-receipt/1'
String editorHmac(String key, String text) {
return jsonHmac(key, text)
}
String edReceiptText(Object ruleHash, Object inventory, Object engine) {
return "${EDITOR_RECEIPT_FORMAT}\n${ruleHash}\n${inventory}\n${engine}".toString()
}
Map editorReceipt(String key, String ruleHash, String inventory, String engine) {
if (!key || !ruleHash || !inventory || !engine) return null
return [v: 1, ruleHash: ruleHash, inventory: inventory, engine: engine,
mac: editorHmac(key, edReceiptText(ruleHash, inventory, engine))]
}
Map editorReceiptClaim(Object receipt, String key, String engine) {
if (!(receipt instanceof Map) || !key) return null
Map r = receipt
if (!(r.ruleHash instanceof String) || !(r.inventory instanceof String) || !(r.mac instanceof String)) return null
if (r.engine != engine) return null
String want = editorHmac(key, edReceiptText(r.ruleHash, r.inventory, engine))
String got = (String) r.mac
if (want.length() != got.length()) return null
int diff = 0
for (int i = 0; i < want.length(); i++) diff |= ((int) want.charAt(i)) ^ ((int) got.charAt(i))
return diff == 0 ? [ruleHash: r.ruleHash, inventory: r.inventory] : null
}
@Field static final List EDITOR_RUN_STEP_TYPES = ['command', 'customCommand', 'log', 'setMode', 'setVariable']
Map editorRunStepDoc(Map body) {
Map step = body?.step instanceof Map ? body.step : null
if (step == null) return edError(400, 'NO_STEP', 'There was no step to run.')
if (step.disabled == true) return edError(409, 'STEP_SKIPPED', 'This step is skipped. Enable it first.')
boolean risky = step.type in ['hsm', 'deviceEnabled'] || HUB_POWER_TYPES.contains(step.type) ||
(step.type in ['command', 'customCommand'] && step.command instanceof String && (schemaOperation(step.command as String)?.risk ?: 'none') != 'none')
if (risky) return edError(409, 'RISKY_STEP', HUB_POWER_TYPES.contains(step.type)
? 'This step is not run on its own because it reboots or shuts down the hub. Run the whole rule for it.'
: 'This step is not run on its own because it can open, unlock, sound or disarm something. Run the whole rule for it.')
if (!(step.type in EDITOR_RUN_STEP_TYPES)) {
return edError(409, 'NOT_ONE_ACTION', 'Run this step runs a device command, a notification, a mode, a hub variable or a log line on its own. Run the whole rule for this step.')
}
if (step.type == 'setMode' && step.ref == null && !(step.value instanceof String && ((String) step.value).trim())) {
return edError(400, 'SCHEMA_TYPE', 'A mode step needs the mode to change to.')
}
if (step.type in ['command', 'customCommand'] && !(step.command instanceof String && ((String) step.command) ==~ /[A-Za-z][A-Za-z0-9_]{0,63}/)) {
return edError(400, 'SCHEMA_TYPE', 'A command step needs the name of a command.')
}
if (step.type in ['command', 'customCommand'] && schemaOperation(step.command as String) == null) {
return edError(409, 'UNKNOWN_COMMAND', "Run this step sends only commands HAI knows, and '${step.command}' is the driver's own. Run the whole rule for it.".toString())
}
if (step.type == 'setVariable') {
Map vr = body.refs instanceof Map && ((Map) body.refs).get(step.ref) instanceof Map ? ((Map) body.refs).get(step.ref) : null
if (vr?.kind != 'variable' || !((vr.id ?: '') as String).startsWith(HUB_VARIABLE_PREFIX)) {
return edError(409, 'NEEDS_THE_RULE', 'A local variable lives only inside a run of the rule. Run the whole rule instead.')
}
if (step.op != null || step.value == null || step.value instanceof Map || step.value instanceof List || step.value.toString().contains('%')) {
return edError(409, 'NEEDS_THE_RULE', 'This step works out a value while the rule runs. Run the whole rule instead.')
}
}
Map given = body.refs instanceof Map ? body.refs : [:]
Map refs = [:]
Closure walk
walk = { Object v ->
if (v instanceof Map) ((Map) v).values().each { walk(it) }
else if (v instanceof List) ((List) v).each { walk(it) }
else if (given.get(v) instanceof Map) refs.put(v, given.get(v))
}
walk(step)
List named = ([step.ref] + (step.refs instanceof List ? step.refs : [])).findAll { it instanceof String && it }
if (named.any { !(given.get(it) instanceof Map) }) {
return edError(409, 'REF_MISSING', "This step names a device the rule doesn't list; save or re-pick the devices.")
}
List ids = []
for (Object r : refs.values()) {
if (((Map) r).kind != 'device') continue
List one = edRunStepRefIds((Map) r)
if (!one) return edError(409, 'REF_MISSING', "This step names a device the rule doesn't list; save or re-pick the devices.")
ids.addAll(one)
}
Map doc = [schema: SCHEMA_ID, id: 'hai-run-step', name: 'Run this step', refs: refs, triggers: [[kind: 'ruleInvocation']], actions: [step]]
return [doc: doc, deviceIds: ids.unique()]
}
List edRunStepRefIds(Map r) {
Object v = r?.id != null ? r.id : r?.ids
List raw = v instanceof List ? v : (v != null ? [v] : [])
return raw.findAll { it != null && it.toString().trim() }.collect { Object x ->
String t = x.toString().trim()
t.startsWith('dev:') ? t : 'dev:' + t
}.unique()
}
Map editorRunStepAnswer(List sent, List errors) {
if (errors) {
return [ok: false, error: 'FAILED', sent: sent,
message: ('Failed: ' + errors.join('; ') + (sent ? ". Done before that: ${sent.join('; ')}." : '.')) as String]
}
return [ok: true, done: true, sent: sent, message: (sent ? "Done. ${sent.join('; ')}." : 'Done. Nothing needed sending.') as String]
}
int edBytes(Object v) {
return JsonOutput.toJson(v).getBytes('UTF-8').length
}
String edCleanName(Object n) {
if (!(n instanceof String)) return null
String s = ((String) n).replaceAll(/\s+/, ' ').trim()
return s.isEmpty() ? null : s
}
Map edCopyStore(Map store) {
return [drafts: new ArrayList((List) (store?.drafts ?: [])), draftSeq: (store?.draftSeq ?: 0) as long,
proposals: store?.proposals, proposalRules: store?.proposalRules, outcome: store?.outcome]
}
@Field static final List ED_AI_NOTE_CODES = ['REQUIREMENT_UNSUPPORTED', 'REQUIREMENT_UNRESOLVED', 'REQUIREMENTS_SHAPE',
'REQUIREMENTS_LIMIT', 'NOT_REQUESTED', 'REF_OUTSIDE_CONTEXT']
Map edProposalAssessment(Map proposal) {
if (proposal == null) return null
List findings = ((List) (proposal.findings instanceof List ? proposal.findings : [])).findAll {
it instanceof Map && it.severity in ['block', 'warn'] && (it.code == null || it.code in ED_AI_NOTE_CODES)
}.take(6).collect { Map f ->
Map kept = [severity: f.severity, message: (f.message ?: '').toString().take(240)]
if (f.code != null) kept.code = f.code.toString()
kept
}
return [ready: proposal.ready == true, checklistComplete: proposal.coverage?.checklistComplete == true,
findings: findings]
}
Map editorSave(Object raw, Map store, long now) {
Map read = editorReadBody(raw)
if (read.error) return read.error
Map req = read.value
if (!(req.rule instanceof Map)) return edError(400, 'RULE_MISSING', 'Send the draft as {"name": ..., "rule": {...}}.')
String name = edCleanName(req.name)
if (name == null) return edError(400, 'NAME_MISSING', 'Give the draft a name.')
if (name.length() > EDITOR_MAX_NAME_CHARS) return edError(400, 'NAME_TOO_LONG', "Keep the name to ${EDITOR_MAX_NAME_CHARS} characters.".toString())
if (req.id != null && !(req.id instanceof String)) return edError(400, 'INVALID_ID', 'The draft id must be text.')
Map rule = req.rule
Map prior = ((List) (store?.drafts ?: [])).find { it.id == req.id }
Map origin = req.proposalId instanceof String ? ((List) (store?.proposalRules ?: [])).find { it.evidenceId == req.proposalId } : null
Map originSummary = origin == null ? null : ((List) (store?.proposals ?: [])).find { it.evidenceId == req.proposalId }
Map liveProposal = origin == null ? null : ((Map) (store?.outcome ?: [:])).proposal
Map assessment = prior?.assessment instanceof Map ? prior.assessment :
origin?.assessment instanceof Map ? origin.assessment :
liveProposal?.evidenceId == req.proposalId ? edProposalAssessment(liveProposal) :
originSummary?.ready == false ? [ready: false, checklistComplete: null, findings: []] : null
String sourceRequest = prior?.request instanceof String ? (String) prior.request :
origin?.request instanceof String ? (String) origin.request :
req.proposalId != null && liveProposal?.evidenceId == req.proposalId && liveProposal.request instanceof String ? (String) liveProposal.request : null
int bytes = edBytes(rule) + (assessment == null ? 0 : edBytes(assessment)) + (sourceRequest == null ? 0 : edBytes(sourceRequest))
if (bytes > EDITOR_MAX_DRAFT_BYTES) {
return edError(413, 'DRAFT_TOO_LARGE', "This rule is ${bytes} bytes; a draft may be at most ${EDITOR_MAX_DRAFT_BYTES}. Nothing was saved.".toString())
}
Map next = edCopyStore(store)
List drafts = next.drafts
String id = req.id as String
int at = id == null ? -1 : drafts.findIndexOf { it.id == id }
if (id != null && at < 0) {
return edError(404, 'DRAFT_NOT_FOUND', 'That draft no longer exists. Save it as a new draft instead.')
}
int others = 0
drafts.eachWithIndex { Object d, int i -> if (i != at) others += ((((Map) d).bytes ?: 0) as int) }
if (others + bytes > EDITOR_MAX_DRAFTS_BYTES) {
return edError(409, 'DRAFTS_FULL', "All drafts together may take ${EDITOR_MAX_DRAFTS_BYTES} bytes, and this one would pass that. Delete a draft first. Nothing was saved.".toString())
}
String ruleHash = null
try { ruleHash = jsonHash(rule, SCHEMA_DEFAULTS) } catch (Exception e) { ruleHash = null }
if (at < 0) {
next.draftSeq = (next.draftSeq as long) + 1L
id = "d_${Long.toString(now, 36)}${Long.toString(next.draftSeq as long, 36)}".toString()
}
Map draft = [id: id, name: name, savedAt: now, ruleHash: ruleHash, bytes: bytes, rule: rule]
if (assessment != null) draft.assessment = assessment
if (sourceRequest != null) draft.request = sourceRequest
Map aiCost = prior?.aiCost instanceof Map ? new LinkedHashMap((Map) prior.aiCost) : [:]
if (edCostOk(req.aiDraftUsd)) aiCost.drafted = (req.aiDraftUsd as double)
if (edCostOk(req.aiAmendUsd)) aiCost.amended = (((aiCost.amended ?: 0d) as double) + (req.aiAmendUsd as double))
if (!aiCost.isEmpty()) draft.aiCost = aiCost
if (req.needsFixing == true) draft.needsFixing = true
if (req.needsFixing == true && req.fixReason instanceof String && req.fixReason) draft.fixReason = ((String) req.fixReason).take(300)
if (at >= 0) drafts.remove(at)
drafts.add(0, draft)
return [status: 200, store: next, body: [ok: true, draft: edDraftSummary(draft), drafts: drafts.collect { edDraftSummary((Map) it) },
message: 'Draft saved on this hub. It is not active.']]
}
Map editorKeepSent(String ruleText, Map store, long now, Map doc = null) {
Map rule = doc
if (rule == null) {
Map parsed = jsonParse(ruleText ?: '')
if (!parsed.ok || !(parsed.value instanceof Map)) return null
rule = parsed.value
}
if (((List) (store?.drafts ?: [])).any { it instanceof Map && ((Map) it).rule instanceof Map && ((Map) ((Map) it).rule).id == rule.id }) return null
Map res = editorSave(JsonOutput.toJson([name: (rule.name ?: rule.id) as String, rule: rule]), store, now)
return res.status == 200 ? res.store : null
}
Map editorGetRule(Object ruleId, List drafts, Map summary) {
if (!(ruleId instanceof String) || ((String) ruleId).isEmpty()) return [error: 'ruleId must be the id of a rule']
Map hub = ((List) (summary?.rules ?: [])).find { it instanceof Map && ((Map) it).id == ruleId }
Map draft = ((List) (drafts ?: [])).find { it instanceof Map && ((Map) it).rule instanceof Map && ((Map) ((Map) it).rule).id == ruleId }
if (draft == null && hub == null) return [error: "there is no rule '${ruleId}' on the hub or in the editor".toString()]
if (draft == null) return [error: "the hub runs '${ruleId}', but the editor keeps no copy of its definition (it was activated from the runtime page); its runtime page shows what it does".toString(),
onHub: [state: hub.state, version: hub.version, paused: hub.paused]]
Map out = [rule: draft.rule, rendering: renderRule((Map) draft.rule), draftName: draft.name, savedAt: draft.savedAt,
howToChange: 'Propose a new version with the same id through hai_propose_rule. A person runs it on the hub from the editor.']
if (hub != null) {
boolean same = hub.ruleHash && draft.ruleHash && ((String) draft.ruleHash).startsWith(hub.ruleHash as String)
out.onHub = [state: hub.state, version: hub.version, paused: hub.paused, sameAsThisCopy: same]
if (!same) out.note = "The hub runs a different version from this copy: the copy was edited after it was sent, or sent from elsewhere."
} else {
out.onHub = null
out.note = 'This rule is not on the hub; it is a draft in the editor.'
}
return out
}
Map editorDelete(Object raw, Map store) {
Map read = editorReadBody(raw)
if (read.error) return read.error
Map body = read.value
boolean many = body.ids instanceof List
List ids = many ? ((List) body.ids) : [body.id]
if (ids.isEmpty() || ids.size() > 500 || ids.any { !(it instanceof String) }) return edError(400, 'INVALID_ID', 'Say which draft to delete.')
if (ids.any { ((String) it).startsWith('ev_') }) return edError(400, 'NOT_A_DRAFT', 'Only drafts can be deleted here. Proposals are kept as evidence.')
Map next = edCopyStore(store)
List drafts = next.drafts
List gone = []
ids.each { Object id ->
int at = drafts.findIndexOf { it.id == id }
if (at < 0) gone << id
else drafts.remove(at)
}
if (!many && gone) return edError(404, 'DRAFT_NOT_FOUND', 'That draft does not exist.')
int done = ids.size() - gone.size()
String msg = !many ? 'Draft deleted.' : (done == 1 ? '1 draft deleted.' : "${done} drafts deleted.".toString()) +
(gone ? " ${gone.size()} had already gone.".toString() : '')
Map out = [ok: true, drafts: drafts.collect { edDraftSummary((Map) it) }, message: msg]
if (many) { out.deleted = done; out.missing = gone }
return [status: 200, store: next, body: out]
}
Map editorFreshNames(Map res, Map inv) {
Map body = res?.body
if (!(body?.rule instanceof Map)) return res
Map rule = bundleDeepCopy(body.rule)
List renamed = []
((Map) (rule.refs instanceof Map ? rule.refs : [:])).each { k, v ->
if (!(v instanceof Map) || ((Map) v).kind != 'device') return
Map dev = ((Map) (inv?.devices ?: [:])).get(((Map) v).id)
if (dev?.name != null && dev.name != ((Map) v).name) {
renamed << [ref: k, from: ((Map) v).name, to: dev.name]
((Map) v).name = dev.name
}
}
if (!renamed) return res
Map out = new LinkedHashMap(body)
out.rule = rule
out.renamed = renamed
return [status: res.status, body: out]
}
boolean edCostOk(Object v) {
return v instanceof Number && (v as double) >= 0d && (v as double) <= 1000d
}
Map editorRule(Object idIn, Map store) {
if (!(idIn instanceof String) || ((String) idIn).isEmpty() || ((String) idIn).length() > EDITOR_MAX_ID_CHARS) {
return edError(400, 'INVALID_ID', 'Say which rule to open.')
}
String id = (String) idIn
Map d = ((List) (store?.drafts ?: [])).find { it.id == id }
if (d != null) {
return [status: 200, body: [ok: true, kind: 'draft', id: d.id, name: d.name, savedAt: d.savedAt, ruleHash: d.ruleHash, rule: d.rule,
assessment: d.assessment, request: d.request, aiCost: d.aiCost]]
}
Map k = ((List) (store?.proposalRules ?: [])).find { it.evidenceId == id }
if (k != null) {
Map summary = ((List) (store?.proposals ?: [])).find { it.evidenceId == id }
String message = 'This is a copy of a proposal. Saving it makes a new draft; the proposal is unchanged.'
if (summary?.ready == false) message += ' The AI marked this proposal with unresolved requirements. A Valid check confirms the rule document, not that it does everything you asked. Compare it with your request before running it.'
Map assessment = k.assessment instanceof Map ? k.assessment :
((Map) (store?.outcome ?: [:])).proposal?.evidenceId == id ? edProposalAssessment((Map) ((Map) store.outcome).proposal) :
summary?.ready == false ? [ready: false, checklistComplete: null, findings: []] : null
return [status: 200, body: [ok: true, kind: 'proposal', id: k.evidenceId, name: k.name, at: k.at, source: k.source,
ruleHash: k.ruleHash, rule: k.rule, assessment: assessment, message: message]]
}
if (((List) (store?.proposals ?: [])).any { it.evidenceId == id }) {
return edError(404, 'RULE_NOT_KEPT', 'Only the summary of this proposal was kept, so its rule cannot be opened.')
}
return edError(404, 'NOT_FOUND', 'There is no draft or kept proposal with that id.')
}
List editorKeepProposalRule(List kept, Map proposal, Object rule, long now) {
List out = new ArrayList((List) (kept ?: []))
if (proposal?.evidenceId == null || !(rule instanceof Map)) return out
String hash = null
try { hash = jsonHash(rule, SCHEMA_DEFAULTS) } catch (Exception e) { hash = null }
if (hash == null || hash != proposal.ruleHash) return out
if (edBytes(rule) > EDITOR_MAX_KEPT_RULE_BYTES) return out
out.removeAll { it.evidenceId == proposal.evidenceId }
Map assessment = edProposalAssessment(proposal)
out.add(0, [evidenceId: proposal.evidenceId, ruleHash: hash, at: now, source: proposal.inputs?.source, request: proposal.request,
name: ((Map) rule).name instanceof String ? ((Map) rule).name : 'Unnamed proposal', rule: rule, assessment: assessment])
return out.take(EDITOR_MAX_KEPT_RULES)
}
Map editorRuleFromHistory(List messages, String ruleHash) {
if (!(messages instanceof List) || ruleHash == null) return null
for (int i = messages.size() - 1; i >= 0; i--) {
Object m = messages[i]
if (!(m instanceof Map) || ((Map) m).role != 'assistant' || !(((Map) m).content instanceof List)) continue
Map call = ((List) ((Map) m).content).reverse().find {
it instanceof Map && it.type == 'tool_use' && it.name == AUTHOR_TOOL_NAME && it.input instanceof Map
}
if (call == null) continue
Object rule = ((Map) call.input).rule
if (!(rule instanceof Map)) return null
Map strict = jsonParse(JsonOutput.toJson(rule))
if (!strict.ok || !(strict.value instanceof Map)) return null
String hash = null
try { hash = jsonHash(strict.value, SCHEMA_DEFAULTS) } catch (Exception e) { hash = null }
return hash == ruleHash ? strict.value : null
}
return null
}
Map editorRuntimeView(Map extra) {
Object sum = extra?.runtime
if (!(sum instanceof Map)) return null
Map fresh = extra?.line instanceof Map ? extra.line : null
List rules = ((((Map) sum).rules ?: []) as List).findAll { it instanceof Map }.collect { Object o ->
Map r = fresh != null && ((Map) o).id == fresh.id ? ((Map) o) + fresh : o
[id: r.id as String, name: r.name as String, state: r.state as String, version: r.version, paused: r.paused == true, ruleHash: r.ruleHash as String,
readable: r.readable == null ? null : r.readable == true, invocable: r.invocable == null ? null : r.invocable == true, recent: ((r.recent ?: []) as List).collect { it as String }.take(TL_SUMMARY_ITEMS_EDITOR),
readAt: r.readAt]
}
Map ls = ((Map) sum).lastSubmit instanceof Map ? ((Map) sum).lastSubmit : null
Map sf = ((Map) sum).stageFailure instanceof Map ? ((Map) sum).stageFailure : null
Object at = ((Map) sum).at
Object checked = extra?.runtimeCheckedAt
Object asOf = at instanceof Number && checked instanceof Number ? Math.max(at as long, checked as long) : (at ?: checked)
boolean partial = fresh != null && extra?.since != null && at != null && "${extra.since}" == "${at}"
if (partial) rules = rules.findAll { "${((Map) it).id}" == "${fresh.id}" }
Map out = [rules: rules, reportedAt: at, asOf: asOf, now: extra?.now, receivedAt: extra?.runtimeAt, lastSubmit: ls,
stageFailure: sf]
if (partial) out.partial = true
if (fresh == null && extra?.lineNote != null) out.lineNote = extra.lineNote as String
return out
}
Map editorActiveView(Object ruleId, Map entry, Map child, Map inv) {
String id = ruleId == null ? null : ruleId.toString()
if (!id) return edError(400, 'NO_RULE', 'Which rule to read was not given.')
if (entry == null) return edError(404, 'NOT_IN_RUNTIME', 'That rule is not in the runtime, so nothing is running for it yet.')
if (child == null) return edError(409, 'NOT_READABLE', 'The rule app for that rule cannot be read just now. Try again in a moment.')
if (!(child.doc instanceof Map)) return edError(409, 'NO_ACTIVE_DOC', 'That rule is in the runtime but has no active version yet.')
String eh = ((entry.ruleHash ?: '') as String)
String ch = ((child.ruleHash ?: '') as String)
boolean sameEpoch = entry.epoch != null && child.epoch != null && entry.epoch.toString() == child.epoch.toString()
boolean sameHash = !eh || !ch || eh == ch
if (!sameEpoch || !sameHash) {
return [status: 200, body: [ok: true, id: id, stale: true,
message: 'active version changed, refresh comparison']]
}
Map res = editorFreshNames([status: 200, body: [ok: true, rule: child.doc]], inv)
return [status: 200, body: [ok: true, id: id, stale: false, state: entry.state as String,
epoch: child.epoch, ruleHash: ch.take(12),
paused: child.paused == true, rule: ((Map) res.body).rule]]
}
Map editorHeldView(Object ruleId, Map cand) {
if (!(cand?.doc instanceof Map)) return edError(404, 'NOT_FOUND', 'There is no draft or kept proposal with that id.')
Map h = cand.held instanceof Map ? cand.held : [:]
List rs = ((List) (h.reasons ?: [])).collect { it as String }.findAll { it }
String reason = rs ? rs.join('; ') + (h.more ? "; and ${h.more} more".toString() : '') :
'it was refused when it was put on the hub, before HAI kept the reason; Check shows what is wrong'
return [status: 200, body: [ok: true, kind: 'held', id: (cand.ruleId ?: ruleId) as String, name: (cand.name ?: ruleId) as String,
state: 'held', held: true, heldReason: reason, heldCode: h.code, heldAt: h.at ?: cand.importedAt,
stale: false, ruleHash: ((cand.ruleHash ?: '') as String).take(12), rule: cand.doc,
message: ("This copy is held for review, not running on the hub: ${reason}. Fix it here and press Activate on hub, " +
'or save it as a draft.').toString()]]
}
Map editorApi(String route, Map req, Map store, Map inv, Map opts, Map extra = [:]) {
try {
switch (route) {
case 'state':
return [status: 200, body: editorState(inv, store, extra)]
case 'check':
return edChecked(opts, [op: 'check', body: req?.body, inv: inv])
case 'rule':
Map found = editorRule(((Map) (req?.params ?: [:])).id, store)
if (found.status == 404 && extra?.held instanceof Map) found = editorHeldView(((Map) (req?.params ?: [:])).id, extra.held)
Map opened = editorFreshNames(found, inv)
Object openedRule = ((Map) (opened.body ?: [:])).rule
if (opts?.withCheck == true && opened.status == 200 && openedRule instanceof Map) {
Map c = edChecked(opts, [op: 'check', body: [rule: openedRule], inv: inv])
if (c.status == 200) {
return [status: 200, body: new LinkedHashMap((Map) opened.body) + [check: c.body],
rule: c.rule, receiptable: c.receiptable]
}
}
return opened
case 'save':
return editorSave(req?.body, store, (opts?.now ?: 0L) as long)
case 'delete':
return editorDelete(req?.body, store)
case 'runtime':
return [status: 200, body: [ok: true, runtime: editorRuntimeView(extra)]]
case 'export':
return edChecked(opts, [op: 'export', body: req?.body, inv: inv, now: (opts?.now ?: 0L) as long])
case 'import':
return edChecked(opts, [op: 'import', body: req?.body, inv: inv])
}
return edError(404, 'NO_SUCH_ROUTE', 'The editor has no such request.')
} catch (Exception e) {
return edError(500, 'INTERNAL', 'The hub could not complete this request. Your edits in the page are kept.')
}
}
Map edChecked(Map opts, Map req) {
Closure checker = opts?.checker instanceof Closure ? (Closure) opts.checker : null
if (checker == null) return edError(503, 'CHECKER_MISSING', 'HAI Engine could not reach HAI Checker, the part that checks rules. Open HAI Engine and press Done, then try again.')
Map sent = new LinkedHashMap((Map) (opts ?: [:]))
sent.remove('checker')
Map out = checker(new LinkedHashMap(req) + [opts: sent])
if (out == null) return edError(503, 'CHECKER_MISSING', 'HAI Checker did not answer.')
if (out.checkerError) return edError(503, out.checkerError as String, (out.message ?: 'HAI Checker refused the request.') as String)
if (out.timing instanceof Map) {
if (opts.editorTiming instanceof Map && !opts.editorTiming.is(out.timing)) ((Map) opts.editorTiming).putAll((Map) out.timing)
out = new LinkedHashMap(out)
out.remove('timing')
}
return out
}
@Field static final String HELP_PAGE_FILE = 'hai-help.html'
@Field static final String HELP_PAGE_SHA256 = 'e6b3770829ab815d57336bf635e25d5f30d1647d6c766adb507956a5eab4f3e2'
String editorHelpPage() {
String key = 'help:' + HELP_PAGE_SHA256
String kept = (String) EDITOR_FILE_CACHE.get(key)
if (kept != null) return kept
byte[] b = null
try { b = downloadHubFile(HELP_PAGE_FILE) } catch (Exception e) { b = null }
String h = b == null ? null : MessageDigest.getInstance('SHA-256').digest(b).collect { String.format('%02x', it) }.join('')
if (h != HELP_PAGE_SHA256) return null
String page = new String(b, 'UTF-8')
EDITOR_FILE_CACHE.put(key, page)
return page
}
List editorHelpParts(String page, String key, Map fill = [:]) {
if (page == null) return []
String open = "<!--hai:${key}-->".toString()
String close = "<!--/hai:${key}-->".toString()
int a = page.indexOf(open)
int z = page.indexOf(close)
if (a < 0 || z < a) return []
String text = page.substring(a + open.length(), z)
fill.each { Object k, Object v -> text = text.replace('{{' + k + '}}', v as String) }
return text.split('<!--p-->').collect { it.trim() }.findAll { it }
}
String editorPage() {
String key = 'page:' + EDITOR_PAGE_SHA256
String kept = (String) EDITOR_FILE_CACHE.get(key)
if (kept != null) return kept
byte[] b = null
try { b = downloadHubFile(EDITOR_PAGE_FILE) } catch (Exception e) { b = null }
String h = b == null ? null : MessageDigest.getInstance('SHA-256').digest(b).collect { String.format('%02x', it) }.join('')
if (h != EDITOR_PAGE_SHA256) return editorPageChecked(b)
String page = new String(b, 'UTF-8')
EDITOR_FILE_CACHE.put(key, page)
return page
}
String editorPageChecked(byte[] b) {
if (b == null) return editorPageProblem("The editor page (${EDITOR_PAGE_FILE}) is not in the hub's File Manager. Deploying HAI Engine puts it there.")
String h = MessageDigest.getInstance('SHA-256').digest(b).collect { String.format('%02x', it) }.join('')
if (h != EDITOR_PAGE_SHA256) return editorPageProblem("The editor page in the hub's File Manager is not the one this version of HAI Engine was built with. Deploy HAI Engine again.")
return new String(b, 'UTF-8')
}
String editorPageProblem(String why) {
return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>HAI editor</title></head><body style=\"font-family:sans-serif;padding:24px\"><h1>The editor cannot open</h1><p>${why}</p></body></html>".toString()
}
@Field static final int EDITOR_SUBMIT_MAX_CHARS = 16000
@Field static final Map EDITOR_ENDPOINT_OPS = [run: 'runNow', stop: 'cancel', pause: 'pause', resume: 'resume', boolean: 'setBoolean', trigger: 'trigger']
@Field static final int EDITOR_ENDPOINT_VALUE_CHARS = 1024
Map editorSubmission(Object raw, Object pairing, long now, String editorLink = null) {
if (!(pairing instanceof String) || ((String) pairing).trim().length() < 8) {
return edError(409, 'NOT_PAIRED', 'HAI is not ready to take rules from the editor yet. Open the HAI Engine app page once and press Done, then try again.')
}
Map body = editorReadBody(raw)
if (body.error) return body.error
Map req = body.value
String op = (req.op ?: 'run') as String
if (!(op in EDITOR_SUBMIT_OPS)) return edError(400, 'NO_SUCH_OPERATION', 'The hub cannot do that with a rule.')
String text = ''
String ruleId
String lifecycle = op == 'run' && req.lifecycle != null ? req.lifecycle as String : null
if (lifecycle != null && !(lifecycle in RT_ARRIVE_STATES)) return edError(400, 'NO_SUCH_LIFECYCLE', 'The hub cannot put a rule on in that state.')
if (op == 'run') {
Object rule = req.rule
if (!(rule instanceof Map) || !(((Map) rule).id instanceof String)) return edError(400, 'NO_RULE', 'There is no rule to send.')
text = jsonCanonicalise(rule, SCHEMA_DEFAULTS)
if (text.length() > EDITOR_SUBMIT_MAX_CHARS) return edError(413, 'TOO_LARGE', "The rule is too large to send (${text.length()} characters; at most ${EDITOR_SUBMIT_MAX_CHARS}).".toString())
ruleId = ((Map) rule).id
} else {
if (!(req.ruleId instanceof String) || ((String) req.ruleId).isEmpty()) return edError(400, 'NO_RULE', 'Which rule?')
ruleId = req.ruleId
if (req.value != null) text = req.value.toString()
}
String key = ((String) pairing).trim()
String sealed = editorLink ? jsonSeal(key, "${now}\n${op}\n${ruleId}".toString(), editorLink) : ''
Map payload = [v: 1, op: op, ruleId: ruleId, sentAt: now, rule: (op == 'run' ? req.rule : text), link: sealed,
sig: jsonSign(key, "${now}\n${op}\n${ruleId}\n${text}\n${sealed}${lifecycle != null ? '\n' + lifecycle : ''}".toString())]
if (lifecycle != null) payload.lifecycle = lifecycle
return [status: 200, body: [ok: true, op: op, ruleId: ruleId, sentAt: now], payload: payload, text: text,
receipt: op == 'run' ? req.receipt : null]
}
Map editorEndpoint(Map p, Object pairing, long now, Map summary) {
if (p.kind == 'rules') {
if (!(summary?.rules instanceof List)) return edError(503, 'NO_SUMMARY', 'HAI has not said which rules it runs yet. Try again in a minute.')
return [status: 200, body: [ok: true, at: summary.at, rules: ((List) summary.rules).collect { Map r -> [id: r.id, name: r.name, state: r.state, paused: r.paused == true] }]]
}
String value = p.value == null ? null : p.value.toString()
if (value != null && value.length() > EDITOR_ENDPOINT_VALUE_CHARS) return edError(413, 'TOO_LARGE', "A value is at most ${EDITOR_ENDPOINT_VALUE_CHARS} characters.".toString())
String op = p.kind == 'variable' ? 'setVariable' : (String) EDITOR_ENDPOINT_OPS.get(p.op)
if (op == null) return edError(404, 'NO_SUCH_OPERATION', "An end point can run, stop, pause, resume, trigger or set the boolean of a rule; '${p.op}' is none of these.".toString())
if (op == 'setBoolean' && !(value in ['true', 'false'])) return edError(400, 'NOT_A_BOOLEAN', 'Set the Private Boolean to true or false.')
if (op == 'setVariable' && value == null) return edError(400, 'NO_VALUE', 'Which value?')
if (op == 'setVariable') {
Object wl = summary == null ? null : summary.writableVariables
if (wl instanceof List) {
List names = ((List) wl).findAll { it != null }.collect { it.toString() }
if (!names.contains(p.id == null ? null : p.id.toString())) {
return edError(404, 'NOT_WRITABLE', ("HAI does not change the hub variable '${p.id}'. As of ${summary.at}, the ones rules may change are: " +
(names.isEmpty() ? 'none' : names.sort().join(', ')) + '. Choose one on the HAI Engine page to change that.').toString())
}
}
}
Map req = [op: op, ruleId: p.id]
if (value != null && op in ['setBoolean', 'trigger', 'setVariable']) req.value = value
Map out = editorSubmission(JsonOutput.toJson(req), pairing, now)
if (out.status != 200 || !(out.body instanceof Map)) return out
Map body = new LinkedHashMap((Map) out.body)
body.remove('ok')
body.accepted = true
body.applied = 'pending'
body.outcome = 'lastSubmit at /editor/api/runtime, matched on sentAt'
return [status: 202, body: body, payload: out.payload]
}
@Field static final String RT_ENGINE = 'HAI-1'
@Field static final String RT_VERSION = 'HAI-1/m5b-0.2.0-dev'
@Field static final String RT_CHILD_BUILD = '8dd877237d65ee3c'
@Field static final long RT_WATCHDOG_EVERY_MS = 900000L
@Field static final long RT_RECOVERY_MS = 120000L
@Field static final long RT_CLAIM_MS = 600000L
@Field static final String RT_INVOKE_EVENT = 'haiRuleInvoke'
@Field static final String RT_CONTROL_EVENT = 'haiRuleControl'
@Field static final String RT_PAUSED_EVENT = 'haiRulePaused'
@Field static final String RT_SWITCHED_EVENT = 'haiRmSwitched'
@Field static final String RT_RETURN_EVENT = 'haiRuleReturn'
@Field static final String RT_COMMITTED_EVENT = 'haiRuleCommitted'
@Field static final List RT_BUTTON_EVENTS = ['pushed', 'held', 'doubleTapped', 'released']
@Field static final int RT_MAX_ATTRIBUTES = 64
@Field static final String RT_LOCATION = 'location'
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
String rtHash(Object v) {
byte[] b = rtCanon(v).getBytes('UTF-8')
return MessageDigest.getInstance('SHA-256').digest(b).collect { String.format('%02x', it) }.join('')
}
String rtBareId(Object id) {
String s = id == null ? '' : id.toString()
return s.startsWith('dev:') ? s.substring(4) : s
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
if (a.any { it instanceof Number }) {
List types = []
try { types = ((d.supportedCommands?.find { it.name == cmd })?.parameters?.collect { (it?.type ?: '').toString().toUpperCase() } ?: []) } catch (Exception ignored) { types = [] }
List out = []
a.eachWithIndex { Object v, int i -> out << (v instanceof Number && i < types.size() && types[i] == 'STRING' ? new BigDecimal(v.toString()).stripTrailingZeros().toPlainString() : v) }
a = out
}
d."${cmd}"(*a)
}
List rtRunEntries(Map t) {
List out = new ArrayList((List) t.head)
if ((t.dropped as int) > 0) out << [w: 'truncated', dropped: t.dropped]
out.addAll((List) t.tail)
return out
}
@Field static final int RT_MAX_EVALS_PER_EVENT = 1024
@Field static final int RT_QUEUE_CAP = 4
@Field static final int RT_PARALLEL_CAP = 4
@Field static final int RT_MAX_CHALLENGES = 4
@Field static final int RT_MAX_CANDIDATES = 12
@Field static final long RT_CANDIDATE_MAX_AGE_MS = 86400000L
@Field static final int RT_MAX_TOMBSTONES = 20
@Field static final int RT_LOG_ENTRIES = 16
@Field static final String RT_CANDIDATES_KEY = 'rtCandidates'
@Field static final String RT_CHALLENGES_KEY = 'rtChallenges'
@Field static final String RT_REGISTRY_KEY = 'rtRegistry'
@Field static final String RT_WATCHDOG_KEY = 'rtWatchdog'
@Field static final String RT_LOG_KEY = 'rtLog'
@Field static final String RT_VERSIONS_KEY = 'rtVersions'
@Field static final int RT_VERSIONS_PER_RULE = 2
@Field static final String RT_WATCHDOG_CRON = '17 7/15 * * * ?'
Object rtpCall(Map pp, String name, Object arg) {
Closure f = (Closure) pp.get(name)
return f == null ? null : f(arg)
}
void rtpHook(Map pp, String name) {
Closure h = (Closure) pp.get('hook')
if (h != null) h(name)
}
Map rtpGet(Map pp, String key) {
Object v = rtpCall(pp, 'atomicGet', key)
return v instanceof Map ? new LinkedHashMap((Map) v) : [:]
}
void rtpPut(Map pp, String key, Object value) { rtpCall(pp, 'atomicPut', [key, value]) }
long rtpNow(Map pp) { return rtpCall(pp, 'now', null) as long }
void rtpMark(Map pp, String phase) { rtpCall(pp, 'mark', phase) }
Map rtFinding(String code, String path, String message) { return [code: code, path: path, message: message] }
int rtNodeCount(Map doc) {
int n = 0
((List) (doc.triggers instanceof List ? doc.triggers : [])).each { Object t ->
n += 1
if (t instanceof Map) n += rtConditionNodes(((Map) t).predicate)
}
n += rtConditionNodes(doc.conditions)
n += rtActionNodes(doc.actions) + rtActionNodes(doc.onFailure)
return n
}
int rtConditionNodes(Object c) {
if (!(c instanceof Map)) return 0
int n = 1
Object of = ((Map) c).of
if (of instanceof List) ((List) of).each { n += rtConditionNodes(it) }
else if (of instanceof Map) n += rtConditionNodes(of)
return n
}
int rtActionNodes(Object list) {
int n = 0
((List) (list instanceof List ? list : [])).each { Object o ->
if (!(o instanceof Map)) return
Map a = o
n += 1
if (a.type == 'wait') n += rtConditionNodes(a.predicate) + ((List) (a.events instanceof List ? a.events : [])).sum(0) { 1 + rtConditionNodes(it instanceof Map ? ((Map) it).predicate : null) }
if (a.type == 'later') n += rtActionNodes(a.actions)
if (a.type == 'if') {
n += rtConditionNodes(a.condition) + rtActionNodes(a.then) + rtActionNodes(a.otherwise)
((List) (a.elseIf instanceof List ? a.elseIf : [])).each { Object e ->
if (e instanceof Map) n += 1 + rtConditionNodes(((Map) e).condition) + rtActionNodes(((Map) e).then)
}
}
if (a.type == 'repeat') n += rtConditionNodes(a.condition) + rtActionNodes(a.steps)
if (a.type == 'byMode') {
n += rtActionNodes(a.otherwise)
((List) (a.cases instanceof List ? a.cases : [])).each { Object c -> if (c instanceof Map) n += 1 + rtActionNodes(((Map) c).then) }
}
}
return n
}
List rtKnownRuleIds(Map pp) {
return ((Map) rtRegistry(pp).rules).values().findAll { it.state in ['active', 'disabled', 'staging'] }.collect { it.ruleId as String }.sort()
}
List rtPausedRuleIds(Map pp) {
return ((List) (rtpCall(pp, 'pausedRules', null) ?: [])).collect { it.toString() }.sort()
}
List rtRmPausedIds(Map pp) {
Set out = [] as Set
((Map) rtRegistry(pp).rules).values().findAll { it.state == 'active' }.each { Object eo ->
Map idx = rtIndex(pp, eo)
((List) (idx?.rmPausedIds ?: [])).each { Object id -> out << (id as String) }
}
return new ArrayList(out).sort()
}
Boolean rtRmPausedFrom(Object status) {
Object rows = status instanceof Map ? ((Map) status).appState : null
if (!(rows instanceof List)) return null
Map row = ((List) rows).find { it instanceof Map && ((Map) it).name == 'paused' }
return row == null ? false : (row.value == true || row.value == 'true')
}
Map rtRmListReading(List ids, Map got, Map seenIn, List goneIn) {
Set present = ((List) (got.checked ?: [])).collect { it.toString() } as Set
Set paused = ((List) (got.paused ?: [])).collect { it.toString() } as Set
Set disabled = ((List) (got.disabled ?: [])).collect { it.toString() } as Set
List gone = new ArrayList((List) (goneIn ?: []))
Map seen = new LinkedHashMap((Map) (seenIn ?: [:]))
List events = []
((List) (ids ?: [])).each { Object o ->
String id = o as String
boolean isGone = !present.contains(id)
if (isGone != gone.contains(id)) { if (isGone) gone << id else gone.remove(id) }
if (isGone || disabled.contains(id)) return
boolean p = paused.contains(id)
Object was = seen.get(id)
if (was == p) return
seen.put(id, p)
if (was != null) events << [id: id, paused: p]
}
return [seen: seen, gone: gone, events: events]
}
List rtScopeFindings(Map doc, boolean modeAllowed = false, boolean hsmAllowed = true, boolean hubPowerAllowed = true) {
List out = []
((List) (doc.triggers instanceof List ? doc.triggers : [])).eachWithIndex { Object t, int i ->
String kind = t instanceof Map ? ((Map) t).kind as String : null
String p = "triggers.${i}".toString()
}
((Map) (doc.refs instanceof Map ? doc.refs : [:])).each { Object k, Object r ->
if (!(r instanceof Map)) return
Map ref = r
}
if (doc.enabled == false) {
out << rtFinding('RUNTIME_SCOPE_DISABLED_DOCUMENT', 'enabled', 'activate the rule enabled; use Deactivate on the runtime page to stop it')
}
List paths = []
rtEachAction(doc, 'actions', doc.actions, paths)
rtEachAction(doc, 'onFailure', doc.onFailure, paths)
paths.each { Object po ->
Map a = ((List) po)[1]
String p = ((List) po)[0] as String
if (!modeAllowed && a.type == 'setMode') {
out << rtFinding('RUNTIME_MODE_NOT_ALLOWED', p, 'turn on "Allow rules to change the mode" on the HAI Engine page first')
}
if (!hsmAllowed && a.type == 'hsm') {
out << rtFinding('RUNTIME_HSM_NOT_ALLOWED', p, 'turn on "Allow rules to arm and disarm Hubitat Safety Monitor" on the HAI Engine page first')
}
if (!hubPowerAllowed && HUB_POWER_TYPES.contains(a.type)) {
out << rtFinding('RUNTIME_HUB_POWER_NOT_ALLOWED', p, 'turn on "Allow rules to reboot or shut down the hub" on the HAI Engine page first')
}
if (a.type == 'wait' && a.waitKind == 'forEvent' && !a.attribute) {
out << rtFinding('RUNTIME_SCOPE_WAIT_ATTRIBUTE', p, 'an event wait must name its attribute, so the child can subscribe to it')
}
}
if ((doc.rerunPolicy ?: 'restart') == 'queue' && ((doc.queueLimit ?: 1) as int) > RT_QUEUE_CAP) {
out << rtFinding('RUNTIME_LIMIT_QUEUE', 'queueLimit', "queueLimit ${doc.queueLimit} is over the cap of ${RT_QUEUE_CAP} for this release".toString())
}
if (doc.rerunPolicy == 'parallel' && ((doc.parallelLimit ?: 1) as int) > RT_PARALLEL_CAP) {
out << rtFinding('RUNTIME_LIMIT_PARALLEL', 'parallelLimit', "parallelLimit ${doc.parallelLimit} is over the cap of ${RT_PARALLEL_CAP} for this release".toString())
}
return out
}
void rtEachAction(Map doc, String path, List actions, List out) {
((List) (actions ?: [])).eachWithIndex { Object o, int i ->
if (!(o instanceof Map)) return
Map a = o
String p = "${path}.${i}".toString()
out << [p, a]
if (a.type == 'if') {
rtEachAction(doc, "${p}.then".toString(), a.then, out)
((List) (a.elseIf ?: [])).eachWithIndex { Object e, int j ->
if (e instanceof Map) rtEachAction(doc, "${p}.elseIf.${j}.then".toString(), ((Map) e).then, out)
}
rtEachAction(doc, "${p}.otherwise".toString(), a.otherwise, out)
}
if (a.type == 'repeat') rtEachAction(doc, "${p}.steps".toString(), a.steps, out)
if (a.type == 'later') rtEachAction(doc, "${p}.actions".toString(), a.actions, out)
if (a.type == 'byMode') {
((List) (a.cases ?: [])).eachWithIndex { Object c, int j ->
if (c instanceof Map) rtEachAction(doc, "${p}.cases.${j}.then".toString(), ((Map) c).then, out)
}
rtEachAction(doc, "${p}.otherwise".toString(), a.otherwise, out)
}
}
}
List rtDeviceIds(Map doc) {
return ((Map) (doc.refs instanceof Map ? doc.refs : [:])).values().findAll { it instanceof Map && ((Map) it).kind == 'device' }
.collect { rtBareId(((Map) it).id) }.unique().sort()
}
List rtEffectiveAllowList(List rows) {
return ((List) (rows ?: [])).collect { rtBareId(((Map) it).id) }.unique().sort()
}
List rtDeviceRefusals(Map doc, List allow) {
List out = []
rtDeviceIds(doc).each { String id ->
if (!allow.contains(id)) {
out << rtFinding('RUNTIME_DEVICE_NOT_AUTHORISED', 'refs', "device ${id} is not on the runtime allow-list (select the device in the rule picker)".toString())
}
}
return out
}
Set rtReadSlots(Map doc) {
Set out = [] as Set
rtSlots(doc).each { out << ((List) it).join('|') }
Map refs = (doc.refs ?: [:])
Closure leaves
leaves = { Object c ->
if (!(c instanceof Map)) return
Map m = c
if (m.of instanceof List) ((List) m.of).each { leaves(it) }
else if (m.of instanceof Map) leaves(m.of)
else if (m.type == 'deviceAttribute') {
List keys = []
if (m.ref) keys << m.ref
if (m.refs instanceof List) keys.addAll((List) m.refs)
keys.each { Object k -> Map r = refs.get(k); if (r != null) out << "${r.id}|${m.attribute}".toString() }
}
}
leaves(doc.conditions)
List paths = []
rtEachAction(doc, 'actions', doc.actions, paths)
rtEachAction(doc, 'onFailure', doc.onFailure, paths)
paths.each { Object po -> Map a = ((List) po)[1]; leaves(a.condition); leaves(a.predicate)
((List) (a.elseIf ?: [])).each { Object e -> if (e instanceof Map) leaves(((Map) e).condition) } }
return out
}
int rtEvalLoadOf(List pairs) {
Map perSlot = [:]
pairs.each { Object po ->
if (!(po instanceof Map)) return
Map pair = po
int n = (pair.nodes ?: 0) as int
((List) (pair.slots ?: [])).each { Object s -> perSlot.put(s, ((perSlot.get(s) ?: 0) as int) + n) }
}
return perSlot.isEmpty() ? 0 : (perSlot.values().max() as int)
}
int rtReservationFor(Map doc, int activeBytes) {
Map b = rtReservationParts(doc, activeBytes)
return (b.docs as int) + (b.working as int)
}
Map rtReservationParts(Map doc, int activeBytes) {
int docs = rtBytes(doc) + activeBytes
int working = RT_BASE_BYTES + Math.max(RT_TRACE_BYTES, RT_TRACE_FLOOR_BYTES) + RT_MIN_RUN_BYTES + RT_LEDGER_RESERVE_BYTES + RT_DEADLINE_BYTES
return [docs: docs, working: working]
}
List rtCandidates(Map pp) {
Object v = rtpCall(pp, 'atomicGet', RT_CANDIDATES_KEY)
return v instanceof List ? new ArrayList((List) v) : []
}
Map rtCandidate(Map pp, Object id) { return rtCandidates(pp).find { ((Map) it).id == id } }
Map rtImportCandidate(Map pp, Object text) {
if (!(text instanceof String) || ((String) text).trim().isEmpty()) return [ok: false, code: 'CANDIDATE_EMPTY', message: 'Nothing was pasted.']
Map parsed = jsonParse((String) text)
if (!parsed.ok) return [ok: false, code: 'CANDIDATE_JSON', message: "Not valid JSON for this hub: ${parsed.message}".toString()]
if (!(parsed.value instanceof Map)) return [ok: false, code: 'CANDIDATE_SHAPE', message: 'Paste one rule document, a JSON object, not a list or bundle.']
return rtImportDoc(pp, parsed.value)
}
Map rtImportPaste(Map pp, Object text) {
if (!(text instanceof String) || ((String) text).trim().isEmpty()) return [ok: false, code: 'CANDIDATE_EMPTY', message: 'Nothing was pasted.', results: []]
Map parsed = jsonParse((String) text)
if (!parsed.ok || !(parsed.value instanceof List)) {
Map one = rtImportCandidate(pp, text)
return [ok: one.ok, code: one.code, message: one.message, results: [one]]
}
List docs = parsed.value
if (docs.isEmpty() || docs.size() > RT_MAX_CANDIDATES) {
return [ok: false, code: 'CANDIDATE_SHAPE', message: "Paste one rule, or a list of 1 to ${RT_MAX_CANDIDATES}.".toString(), results: []]
}
List results = docs.collect { Object d ->
d instanceof Map ? rtImportDoc(pp, d) : [ok: false, code: 'CANDIDATE_SHAPE', message: 'Each list item must be a rule document.']
}
int kept = results.count { ((Map) it).ok } as int
return [ok: kept > 0, results: results,
message: "Kept ${kept} of ${docs.size()}.${kept < docs.size() ? ' Refused: ' + results.findAll { !((Map) it).ok }.collect { ((Map) it).message }.join(' ') : ''}".toString()]
}
List rtPruneSpent(Map pp, List all) {
Map rules = rtRegistry(pp).rules
return all.findAll { Object co ->
Map c = co
Map e = rules.get(c.ruleId)
!(e != null && (e.state == 'removed' || (e.state in ['active', 'disabled'] && e.ruleHash == c.ruleHash)))
}
}
List rtPruneAged(Map pp, List all) {
long now = rtpNow(pp)
Set busy = ((Map) rtChallenges(pp)).values().findAll { ((Map) it).state in ['open', 'claimed'] }
.collect { ((Map) it).candidateId as String } as Set
List kept = all.findAll { Object co ->
Map c = co
busy.contains(c.id as String) || now - ((c.importedAt ?: now) as long) < RT_CANDIDATE_MAX_AGE_MS
}
if (kept.size() != all.size()) rtLog(pp, [what: 'candidatesAged', count: all.size() - kept.size()])
return kept
}
Map rtImportParsed(Map pp, Map doc, String canonical = null) {
Map one = rtImportDoc(pp, doc, canonical)
return [ok: one.ok, code: one.code, message: one.message, results: [one]]
}
Map rtImportDoc(Map pp, Map doc, String canonical = null) {
if (doc.schema != SCHEMA_ID || !(doc.id instanceof String) || ((String) doc.id).isEmpty()) {
return [ok: false, code: 'CANDIDATE_SHAPE', message: "The document must declare schema ${SCHEMA_ID} and an id.".toString()]
}
int bytes = rtBytes(doc)
if (bytes > HAI_DOC_BYTES) {
return [ok: false, code: 'CANDIDATE_TOO_LARGE', message: "The rule is ${bytes} bytes; HAI keeps at most ${HAI_DOC_BYTES} bytes for one rule in this release.".toString()]
}
String ruleHash = canonical != null
? MessageDigest.getInstance('SHA-256').digest(canonical.getBytes('UTF-8')).collect { String.format('%02x', it) }.join('')
: jsonHash(doc, SCHEMA_DEFAULTS)
List all = rtCandidates(pp)
Map same = all.find { ((Map) it).ruleHash == ruleHash }
if (same != null) return [ok: true, candidate: same, message: 'This exact rule is already a candidate.']
int before = all.size()
all = rtPruneSpent(pp, all.findAll { ((Map) it).ruleId != doc.id })
boolean freed = all.size() != before
if (all.size() >= RT_MAX_CANDIDATES) {
all = rtPruneSpent(pp, all)
freed = true
}
if (all.size() >= RT_MAX_CANDIDATES) {
int had = all.size()
all = rtPruneAged(pp, all)
freed = freed || all.size() != had
}
if (all.size() >= RT_MAX_CANDIDATES) {
Set busy = ((Map) rtChallenges(pp)).values().findAll { ((Map) it).state in ['open', 'claimed'] }
.collect { ((Map) it).candidateId as String } as Set
Object oldest = all.findAll { ((Map) it).held instanceof Map && !busy.contains(((Map) it).id as String) }
.min { (((Map) it).importedAt ?: 0) as long }
if (oldest != null) {
all = all.findAll { !it.is(oldest) }
freed = true
rtLog(pp, [what: 'heldCopyGaveWay', ruleId: ((Map) oldest).ruleId])
}
}
if (all.size() >= RT_MAX_CANDIDATES) {
if (freed) rtpPut(pp, RT_CANDIDATES_KEY, all)
return [ok: false, code: 'CANDIDATE_LIMIT',
message: ("HAI already holds ${RT_MAX_CANDIDATES} rules waiting to go on the hub, all from the last day. " +
'Each one clears itself a day after it arrived; try again then, or put one of them on the hub.').toString()]
}
Map c = [id: rtpCall(pp, 'newId', 'c') as String, ruleId: doc.id, name: (doc.name ?: doc.id).toString().take(80),
ruleHash: ruleHash, bytes: bytes, importedAt: rtpNow(pp), doc: doc]
all << c
rtpPut(pp, RT_CANDIDATES_KEY, all)
return [ok: true, candidate: c, message: 'Kept as a candidate. It is not active.']
}
void rtCandidateHeld(Map pp, Object candidateId, Map refused) {
if (candidateId == null || !(refused instanceof Map) || refused.ok == true) return
List all = rtCandidates(pp)
int i = all.findIndexOf { ((Map) it).id == candidateId }
if (i < 0) return
List found = refused.refusals instanceof List ? refused.refusals : []
List reasons = found.take(4).collect { Object fo ->
String m = ((((Map) fo).message ?: ((Map) fo).code) ?: '') as String
m.size() > 160 ? m.substring(0, 157) + '...' : m
}.findAll { it }
if (reasons.isEmpty() && (refused.message ?: refused.code)) reasons = [((refused.message ?: refused.code) as String).take(160)]
Map held = [code: (refused.code ?: 'REFUSED') as String, reasons: reasons, at: rtpNow(pp)]
if (found.size() > 4) held.more = found.size() - 4
all[i] = new LinkedHashMap((Map) all[i]) + [held: held]
rtpPut(pp, RT_CANDIDATES_KEY, all)
}
Map rtHeldCandidate(Map pp, Object ruleId) {
if (ruleId == null) return null
Map e = rtEntry(pp, ruleId)
if (e != null && e.state != 'removed') return null
return rtCandidates(pp).reverse().find { ((Map) it).ruleId == ruleId && ((Map) it).doc instanceof Map }
}
Map rtRemoveCandidate(Map pp, Object id) {
List all = rtCandidates(pp)
boolean gone = all.removeAll { ((Map) it).id == id }
rtpPut(pp, RT_CANDIDATES_KEY, all)
return [ok: gone]
}
int rtDropCandidatesFor(Map pp, String ruleId) {
Set busy = ((Map) rtChallenges(pp)).values().findAll { ((Map) it).state in ['open', 'claimed'] }
.collect { ((Map) it).candidateId as String } as Set
List all = rtCandidates(pp)
List kept = all.findAll { Object co -> ((Map) co).ruleId != ruleId || busy.contains(((Map) co).id as String) }
int gone = all.size() - kept.size()
if (gone > 0) rtpPut(pp, RT_CANDIDATES_KEY, kept)
return gone
}
Map rtRegistry(Map pp) {
Map r = rtpGet(pp, RT_REGISTRY_KEY)
if (r.rules == null) r = [revision: 0, rules: [:]]
return r
}
Map rtEntry(Map pp, Object ruleId) { return ((Map) rtRegistry(pp).rules).get(ruleId) }
Map rtRegistryTransition(Map pp, String ruleId, List from, String to, Map fields) {
Map reg = rtRegistry(pp)
Map rules = new LinkedHashMap((Map) reg.rules)
Map cur = rules.get(ruleId)
String state = cur == null ? null : cur.state as String
if (!from.contains(state)) return [ok: false, code: 'REGISTRY_STATE', state: state]
if (fields.expectClaim != null && cur?.claimId != fields.expectClaim) return [ok: false, code: 'REGISTRY_CLAIM', state: state]
Map next = cur == null ? [ruleId: ruleId] : new LinkedHashMap(cur)
fields.each { Object k, Object v -> if (k != 'expectClaim') next.put(k, v) }
if (fields.containsKey('doc')) {
next.index = rtIndexOf((Map) fields.doc)
next.remove('doc')
}
if (to == 'removed') {
next.remove('doc')
next.index = null
}
next.state = to
next.updatedAt = rtpNow(pp)
rules.put(ruleId, next)
int revision = (reg.revision as int) + 1
rtpPut(pp, RT_REGISTRY_KEY, [revision: revision, rules: rules])
if ((to == 'active' || (state == 'staging' && to == 'disabled')) && next.index != null) rtRecordVersion(pp, next)
if (to == 'removed') rtForgetVersions(pp, ruleId)
if (to == 'removed') rtPruneTombstones(pp)
return [ok: true, entry: next, revision: revision]
}
void rtRecordVersion(Map pp, Map entry) {
Map all = rtpGet(pp, RT_VERSIONS_KEY)
List list = new ArrayList((List) (all.get(entry.ruleId) ?: []))
if (list.any { ((Map) it).epoch == entry.epoch && ((Map) it).ruleHash == entry.ruleHash }) return
list << [epoch: entry.epoch, ruleHash: entry.ruleHash, at: rtpNow(pp), name: ((Map) (entry.index ?: [:])).name]
while (list.size() > RT_VERSIONS_PER_RULE) list.remove(0)
all.put(entry.ruleId, list)
rtpPut(pp, RT_VERSIONS_KEY, all)
}
Map rtChildDoc(Map pp, String ruleId, Object epoch) {
if (epoch == null) return null
String childId = rtEntry(pp, ruleId)?.childId as String
if (childId == null) return null
try {
Object got = rtpCall(pp, 'child', [childId, 'ruleRetainedDoc', [epoch.toString()]])
return got instanceof Map ? got : null
} catch (Exception ignored) {
return null
}
}
void rtForgetVersions(Map pp, String ruleId) {
Map all = rtpGet(pp, RT_VERSIONS_KEY)
if (all.remove(ruleId) != null) rtpPut(pp, RT_VERSIONS_KEY, all)
}
Map rtVersionDoc(Map pp, String ruleId, Map v) {
Map fromChild = rtChildDoc(pp, ruleId, v?.epoch)
if (fromChild != null) return fromChild
return v?.doc instanceof Map ? v.doc : null
}
Map rtOlderDocs(Map pp, String ruleId, Map names) {
Map out = [:]
((List) (rtpGet(pp, RT_VERSIONS_KEY).get(ruleId) ?: [])).each { Object o ->
Map v = o
Map doc = rtVersionDoc(pp, ruleId, v)
if (doc != null) out.put(v.epoch as String, tlFreshNames(doc, names))
}
return out
}
List rtVersions(Map pp, String ruleId) {
return new ArrayList((List) (rtpGet(pp, RT_VERSIONS_KEY).get(ruleId) ?: [])).reverse()
}
Map rtEvalPairFor(Map pp, Map e) {
Map idx = rtIndex(pp, e)
if (idx?.evalSlots instanceof List && idx?.evalNodes != null) {
return [nodes: idx.evalNodes as int, slots: new ArrayList((List) idx.evalSlots)]
}
Map doc = e?.doc instanceof Map ? e.doc : null
if (doc == null) {
Object cand = rtCandidates(pp).find { ((Map) it).ruleId == e?.ruleId }
if (cand instanceof Map && ((Map) cand).doc instanceof Map) doc = ((Map) cand).doc
}
if (doc == null && e?.childId != null && e?.epoch != null) {
try {
Object got = rtpCall(pp, 'child', [e.childId, 'ruleRetainedDoc', [e.epoch.toString()]])
if (got instanceof Map) doc = got
} catch (Exception ignored) { }
}
if (doc == null) return null
return [nodes: rtNodeCount(doc), slots: new ArrayList(rtReadSlots(doc))]
}
List rtCounted(Map pp, String exceptRule) {
return ((Map) rtRegistry(pp).rules).values().findAll { Object e ->
((Map) e).state in ['active', 'staging', 'disabled'] && ((Map) e).ruleId != exceptRule
}
}
boolean rtRiskyCommand(String command) {
if (!command) return true
Map op = schemaOperation(command)
return op == null ? true : RISK_CLASSES.containsKey(op.risk as String)
}
Map rtIndex(Map pp, Map entry) {
if (entry == null) return null
if (entry.index instanceof Map) return entry.index
return entry.doc instanceof Map ? rtIndexOf((Map) entry.doc) : null
}
Map rtIndexOf(Map doc) {
if (!(doc instanceof Map)) return null
Map touches = rtTouches(doc)
List risky = []
rtWalkActions((List) (doc.actions ?: [])) { Map a ->
if (a.type == 'command' && rtRiskyCommand(a.command as String)) {
risky << [device: rtBareId(coreRefId(doc, a.ref) ?: ''), command: a.command]
}
}
Set rmPaused = [] as Set
Closure add = { Object k ->
Map ref = ((Map) (doc.refs ?: [:])).get(k)
if (ref?.kind == 'rmRule' && ref.id != null) rmPaused << (ref.id as String)
}
((List) (doc.triggers ?: [])).each { Object t ->
if (t instanceof Map && ((Map) t).kind == 'rulePaused' && ((Map) t).disabled != true) add(((Map) t).ref)
}
rtWalkConditions(doc) { Map n -> if (n.type == 'rulePaused') add(n.ref) }
return [name : doc.name,
bytes : rtBytes(doc),
evalNodes : rtNodeCount(doc),
evalSlots : new ArrayList(rtReadSlots(doc)).sort(),
deviceIds : rtDeviceIds(doc),
listens : new ArrayList((Set) touches.listens).sort(),
commands : new ArrayList((Set) touches.commands).sort(),
risky : risky,
rmPausedIds: new ArrayList(rmPaused).sort(),
everyday : everydayWordsIn(doc)]
}
Map rtTouches(Map doc) {
Set commands = [] as Set
Set listens = [] as Set
rtWalkActions((List) (doc?.actions ?: [])) { Map a ->
if (a.type in ['command', 'customCommand']) {
String id = coreRefId(doc, a.ref); if (id) commands << rtBareId(id)
((List) (a.refs ?: [])).each { Object r -> String i = coreRefId(doc, r); if (i) commands << rtBareId(i) }
}
}
((List) (doc?.triggers ?: [])).each { Object t ->
String id = coreRefId(doc, ((Map) t).ref); if (id) listens << rtBareId(id)
}
return [commands: commands, listens: listens]
}
List rtChainWarnings(Map pp, Map doc, Closure liveUsers = null) {
List out = []
Map mine = rtTouches(doc)
String meId = doc?.id as String
rtWalkActions((List) (doc?.actions ?: [])) { Map a ->
if (a.type == 'command' && rtRiskyCommand(a.command as String)) {
out << [kind: 'commandsPhysical', device: rtBareId(coreRefId(doc, a.ref) ?: ''), command: a.command,
message: "this rule sends '${a.command}' itself".toString()]
}
}
((Map) rtRegistry(pp).rules).values().each { Object eo ->
Map e = eo
if (e.state != 'active' || e.ruleId == meId) return
Map idx = rtIndex(pp, e)
if (idx == null) return
Set shared = ((Set) mine.commands).intersect(((List) (idx.listens ?: [])) as Set)
if (shared.isEmpty()) return
((List) (idx.risky ?: [])).each { Object ro ->
Map r = ro
out << [kind: 'chain', via: shared.sort().join(', '), rule: e.ruleId,
device: r.device, command: r.command,
message: ("this rule commands ${shared.sort().join(', ')}, which starts '${e.ruleId}', " +
"which sends '${r.command}'").toString()]
}
}
if (liveUsers != null) {
((Set) mine.commands).sort().each { Object dev ->
List others = liveUsers(dev)
if (others) out << [kind: 'alsoUsed', device: dev, others: others,
message: ("${others.size()} other app(s) also use ${dev}; whether any of them " +
'commands it is not visible from here').toString()]
}
}
return out
}
Map rtChallenges(Map pp) { return rtpGet(pp, RT_CHALLENGES_KEY) }
Map rtChallengeTransition(Map pp, String id, List from, String to, Map fields, String claimId = null) {
Map all = rtChallenges(pp)
Map out = rtChallengeApply(all, id, from, to, fields, claimId)
if (!out.ok) return out
rtpPut(pp, RT_CHALLENGES_KEY, all)
return out
}
Map rtChallengeApply(Map all, String id, List from, String to, Map fields, String claimId = null) {
Map ch = all.get(id)
if (ch == null) return [ok: false, code: 'CHALLENGE_MISSING']
if (!from.contains(ch.state)) {
String code = ch.state in ['claimed', 'consumed'] ? 'CHALLENGE_ALREADY_CLAIMED' :
(ch.state == 'expired' ? 'CHALLENGE_EXPIRED' : 'CHALLENGE_STATE')
return [ok: false, code: code, state: ch.state]
}
if (claimId != null && ch.claimId != claimId) return [ok: false, code: 'CLAIM_NOT_HELD', state: ch.state]
if (to == null) {
all.remove(id)
} else {
Map next = new LinkedHashMap(ch)
next.putAll(fields)
next.state = to
all.put(id, next)
ch = next
}
return [ok: true, challenge: ch]
}
void rtVoidClaim(Map pp, Map ch, String claimId, String reason) {
rtChallengeTransition(pp, ch.id as String, ['claimed'], 'expired', [expiredReason: reason, expiredAt: rtpNow(pp)], claimId)
}
Map rtInventoryForDigest(Map inv) {
Map copy = invNew()
((Map) inv.devices).each { Object k, Object d ->
Map row = new LinkedHashMap((Map) d)
row.remove('currentStates')
((Map) copy.devices).put(k, row)
}
copy.modes = inv.modes
copy.variables = inv.variables
copy.rules = inv.rules
return copy
}
Map rtReview(Map pp, Map cand, String forClaim = null, boolean withChains = true, boolean presentation = true,
boolean withLiveUsers = true) {
Map doc = cand.doc
String ruleId = doc.id as String
boolean fast = !presentation && rtCheckedStill(pp, cand)
rtpMark(pp, fast ? 'receiptOk' : 'receiptNone')
Map inv = fast ? null : rtWithRuleVariables((Map) ((presentation ? null : rtpCall(pp, 'checkedInventory', doc)) ?: rtpCall(pp, 'inventory', doc)), [doc])
List allow = ((List) (rtpCall(pp, 'allowList', null) ?: [])).collect { it.toString() }
rtpMark(pp, 'inventory')
Map reg = rtRegistry(pp)
Map entry = ((Map) reg.rules).get(ruleId)
boolean own = entry != null && entry.state == 'staging' && forClaim != null && entry.claimId == forClaim
boolean hasActive = entry != null && entry.state in ['active', 'disabled', 'staging']
int activeBytes = hasActive ? ((rtIndex(pp, entry)?.bytes ?: 0) as int) : 0
Map activeDoc = null
int baseEpoch = entry != null && entry.state != 'removed' ? ((entry.epoch ?: 0) as int) : 0
String baseRuleHash = entry != null && entry.state != 'removed' ? ((entry.ruleHash ?: 'none') as String) : 'none'
int revision = own ? (entry.baseRevision as int) : (reg.revision as int)
Map v = fast ? [findings: [], complete: true] : null
List simulation = []
String problem = null
if (v == null) {
Map ask = [op: 'review', doc: doc, inv: inv, simulate: presentation]
if (presentation) {
ask.now = rtpNow(pp)
ask.zone = (rtpCall(pp, 'zone', null) ?: 'UTC') as String
ask.sun = rtDocUsesSun(doc) ? (rtpCall(pp, 'sunDays', null) ?: [:]) : [:]
}
Map checked = rtpCall(pp, 'checker', ask)
if (checked == null || checked.checkerError || !(checked.validation instanceof Map)) {
String why = (checked?.message ?: 'HAI Checker, the part of HAI Engine that checks rules, did not answer') as String
v = [findings: [], complete: false, incompleteReason: why]
} else {
v = checked.validation
simulation = (checked.simulation ?: [])
problem = checked.problem as String
}
}
rtpMark(pp, 'validate')
List rendering = []
Map diff = null
try {
if (presentation) {
rendering = renderRule(doc)
if (hasActive && activeDoc == null) activeDoc = rtChildDoc(pp, ruleId, entry.epoch)
diff = diffRules(activeDoc, doc)
}
} catch (Exception e) {
problem = "the rule could not be read back or simulated: ${e.message}".toString()
simulation = []
}
List refusals = []
vBlocking(v).each { refusals << rtFinding(it.code as String, it.path as String, it.message as String) }
if (!v.complete) refusals << rtFinding('VALIDATION_INCOMPLETE', '', (v.incompleteReason ?: 'validation did not finish') as String)
refusals.addAll(rtScopeFindings(doc, rtpCall(pp, 'modeAllowed', null) == true, rtpCall(pp, 'hsmAllowed', null) != false,
rtpCall(pp, 'hubPowerAllowed', null) != false))
refusals.addAll(rtDeviceRefusals(doc, allow))
proposeActivationBlockers((List) v.findings).each { refusals << rtFinding(it.code as String, it.path as String, it.message as String) }
if (problem != null) refusals << rtFinding('REVIEW_FAILED', '', problem)
List others = rtCounted(pp, ruleId)
List pairs = []
List unreadable = []
others.each { Object eo ->
Map pair = rtEvalPairFor(pp, eo)
if (pair == null) unreadable << (((Map) eo).ruleId as String) else pairs << pair
}
pairs << [nodes: rtNodeCount(doc), slots: new ArrayList(rtReadSlots(doc))]
int load = rtEvalLoadOf(pairs)
if (!unreadable.isEmpty()) {
refusals << rtFinding('RUNTIME_EVAL_UNREADABLE', '', ("the combined work of one event cannot be checked because ${unreadable.size() == 1 ? 'rule' : 'rules'} " +
"${unreadable.sort().join(', ')} ${unreadable.size() == 1 ? 'does' : 'do'} not report what ${unreadable.size() == 1 ? 'it reads' : 'they read'}; activate ${unreadable.size() == 1 ? 'it' : 'them'} again, or remove ${unreadable.size() == 1 ? 'it' : 'them'}, so this rule can be admitted safely").toString())
}
if (load > RT_MAX_EVALS_PER_EVENT) {
refusals << rtFinding('RUNTIME_EVAL_LIMIT', '', "one event could make the active rules evaluate ${load} steps and conditions together, over the ${RT_MAX_EVALS_PER_EVENT} this release allows; remove or simplify a rule to make room".toString())
}
Map parts = rtReservationParts(doc, activeBytes)
int reservation = rtReservationFor(doc, activeBytes)
if ((parts.docs as int) > RT_DOC_BYTES) {
refusals << rtFinding('STATE_BUDGET', '', ("this rule and the version it would replace come to ${parts.docs} bytes of stored rule, over the ${RT_DOC_BYTES} bytes HAI keeps per rule " +
"in this release (${HAI_DOC_BYTES} for each of ${HAI_RETAINED_DOCS} kept versions); shorten the rule").toString())
} else if (reservation > RT_STATE_BYTES) {
refusals << rtFinding('STATE_BUDGET', '', "the rule needs ${reservation} bytes of stored state - ${parts.docs} for the rule itself and ${parts.working} to run it - over the ${RT_STATE_BYTES} bytes HAI allows per rule in this release".toString())
}
Map core = [ruleId: ruleId, candidateId: cand.id, ruleHash: fast ? (cand.ruleHash as String) : jsonHash(doc, SCHEMA_DEFAULTS),
renderingHash: presentation ? rtHash(rendering) : '', diffHash: presentation ? rtHash(diff) : '',
inventoryDigest: presentation ? rtHash([inventory: proposeInventoryDigest(rtInventoryForDigest(inv)), allow: allow]) : '',
baseEpoch: baseEpoch, baseRuleHash: baseRuleHash, ruleSetRevision: revision,
engineVersion: "${RT_VERSION}/${COMPILER_VERSION}/${CATALOGUE_VERSION}".toString()]
List chains = []
if (withChains) {
try { chains = rtChainWarnings(pp, doc, (!withLiveUsers || pp.get('liveUsers') == null) ? null : { Object dv -> rtpCall(pp, 'liveUsers', dv) }) }
catch (Exception e) { chains = [[kind: 'unavailable', message: "the chain check did not run (${e.message})".toString()]] }
}
rtpMark(pp, 'limits')
return [core: core, refusals: refusals, chainWarnings: chains, findings: v.findings, rendering: rendering, diff: diff, fast: fast,
simulation: simulation, requiredApprovals: proposeRequiredApprovals((List) v.findings),
allow: allow, nodes: rtNodeCount(doc), reservation: reservation, activeDoc: activeDoc,
retryNote: 'Automatic retry is not available in this release: verification still runs, and an unknown result is never sent again.',
entry: entry]
}
String rtInventoryVersion(Map inv) {
Map vars = [:]
((Map) (inv?.variables ?: [:])).each { Object k, Object v ->
Map row = v instanceof Map ? new LinkedHashMap((Map) v) : [:]
row.remove('value')
vars.put(k, row)
}
Map slice = new LinkedHashMap(inv ?: [:])
slice.variables = vars
return rtHash(slice)
}
boolean rtCheckedStill(Map pp, Map cand) {
Object c = rtpCall(pp, 'checked', null)
if (!(c instanceof Map) || ((Map) c).ruleHash == null || ((Map) c).ruleHash != cand?.ruleHash) return false
try {
Map now = rtWithRuleVariables((Map) rtpCall(pp, 'checkedInventory', cand.doc), [cand.doc])
return now != null && rtInventoryVersion(now) == ((Map) c).inventory
} catch (Exception e) {
return false
}
}
String rtDigest(Map core, String nonce, long expiresAt) {
Map pre = new LinkedHashMap(core)
pre.nonce = nonce
pre.expiresAt = expiresAt
return rtHash(pre)
}
void rtLog(Map pp, Map entry) {
Object v = rtpCall(pp, 'atomicGet', RT_LOG_KEY)
List log = v instanceof List ? new ArrayList((List) v) : []
log << ([at: rtpNow(pp)] + entry)
while (log.size() > RT_LOG_ENTRIES) log.remove(0)
rtpPut(pp, RT_LOG_KEY, log)
}
Map rtRefuse(Map pp, String code, String message, Map extra = [:]) {
Map line = [what: 'activationRefused', code: code, message: message]
List found = extra?.refusals instanceof List ? extra.refusals : []
if (!found.isEmpty()) {
line.reasons = found.take(4).collect { Object fo ->
Map f = fo
String m = (f.message ?: '') as String
[code: f.code, message: m.size() > 160 ? m.substring(0, 157) + '...' : m]
}
if (found.size() > 4) line.moreReasons = found.size() - 4
}
rtLog(pp, line)
return [ok: false, code: code, message: message] + extra
}
@Field static final String RT_CHILD_MISMATCH_WORDS = 'The HAI Rule Container on this hub is a different build from HAI Engine. Deploy both from the same version, then try again.'
Map rtChildBuild(Map pp, String childId) {
Object got
try {
got = rtpCall(pp, 'child', [childId, 'haiBuild', []])
} catch (Exception e) {
String m = (e.message ?: 'no answer') as String
return [ok: false, build: null,
why: 'it cannot say which build it is: ' + (m.size() > 160 ? m.substring(0, 157) + '...' : m)]
}
String build = got instanceof Map ? (((Map) got).build as String) : null
if (build == null) return [ok: false, build: null, why: 'it did not say which build it is']
if (build != RT_CHILD_BUILD) return [ok: false, build: build, why: 'it is a different build']
return [ok: true, build: build]
}
String rtAnyChild(Map pp) {
Object found = ((Map) rtRegistry(pp).rules).values().find { Object eo ->
Map e = eo
e.childId != null && (rtpCall(pp, 'childExists', e.childId as String) as boolean)
}
return found == null ? null : (((Map) found).childId as String)
}
Map rtStageChild(Map pp, String ruleId, String childId) {
String id = childId
if (id != null && !(rtpCall(pp, 'childExists', id) as boolean)) id = null
String probe = id ?: rtAnyChild(pp)
if (probe != null) {
Map b = rtChildBuild(pp, probe)
if (!b.ok) return rtChildMismatch(pp, ruleId, b, probe, null)
}
if (id == null) {
id = rtpCall(pp, 'addChild', ruleId) as String
if (probe == null) {
Map b = rtChildBuild(pp, id)
if (!b.ok) {
String removed = 'removed'
try { rtpCall(pp, 'removeChild', id) } catch (Exception e) { removed = "not removed: ${e.message}".toString() }
return rtChildMismatch(pp, ruleId, b, id, removed)
}
}
}
return [ok: true, childId: id]
}
Map rtChildMismatch(Map pp, String ruleId, Map b, String askedId, String newChild) {
Map line = [what: 'activationRefused', code: 'STAGE_CHILD_MISMATCH', message: RT_CHILD_MISMATCH_WORDS,
ruleId: ruleId, engineChildBuild: RT_CHILD_BUILD, childBuild: b.build, why: b.why, asked: askedId]
if (newChild != null) line.newChild = newChild
rtLog(pp, line)
return [ok: false, code: 'STAGE_CHILD_MISMATCH', message: RT_CHILD_MISMATCH_WORDS,
engineChildBuild: RT_CHILD_BUILD, childBuild: b.build]
}
Map rtAutoCore(String ruleId, String candidateId, String ruleHash, int baseEpoch, String baseRuleHash, int revision) {
return [ruleId: ruleId, candidateId: candidateId, ruleHash: ruleHash, baseEpoch: baseEpoch,
baseRuleHash: baseRuleHash, ruleSetRevision: revision,
engineVersion: "${RT_VERSION}/${COMPILER_VERSION}/${CATALOGUE_VERSION}".toString()]
}
Object rtStageIntent(Map ch) {
return ch.arrive in RT_ARRIVE_STATES ? (String) ch.arrive : (ch.startStopped == true)
}
String rtBackupStamp(List live, List paused, Object extra = null) {
List p = (paused ?: [])
List rows = live.collect { Object o -> Map e = o; [e.ruleId, e.epoch, e.ruleHash, e.state, p.contains(e.ruleId)] }
return jsonHash(extra == null ? rows : [rows, extra])
}
Map rtBackupPairsResolving(Object pairs, Collection rmIds, Collection haiIds) {
Set rm = (rmIds ?: []).collect { it.toString() } as Set
Set hai = (haiIds ?: []).collect { it.toString() } as Set
Map keep = [:]
int dropped = 0
if (pairs instanceof Map) ((Map) pairs).each { Object k, Object v ->
Map p = v instanceof Map ? v : null
String rmId = p == null ? null : (p.rmId ?: k) as String
String haiId = p?.haiRuleId == null ? null : p.haiRuleId as String
if (haiId != null && rm.contains(rmId) && hai.contains(haiId)) keep.put(k as String, new LinkedHashMap(p))
else dropped++
}
return [pairs: keep, dropped: dropped]
}
Map rtActivateAuto(Map pp, Object candidateId, boolean startStopped = false, String arrive = null) {
Map cand = rtCandidate(pp, candidateId)
if (cand == null) return [ok: false, code: 'CANDIDATE_MISSING', message: 'That candidate is no longer kept.']
Map live = rtEntry(pp, cand.ruleId)
if (live != null && live.state in ['active', 'disabled'] && live.ruleHash == cand.ruleHash) {
return [ok: false, code: 'ALREADY_ACTIVE', entry: live,
message: "This exact rule is already ${live.state == 'active' ? 'active' : 'activated (now deactivated)'}, epoch ${live.epoch}. There is nothing to activate.".toString()]
}
Map review = rtReview(pp, cand, null, true, false, false)
boolean firstStopped = startStopped && arrive == null && ((((Map) review.core).baseEpoch ?: 0) as int) == 0
List refusals = firstStopped ? ((List) review.refusals).findAll { ((Map) it).code != 'VARIABLE_NOT_WRITABLE' } : review.refusals
if (!refusals.isEmpty()) {
return rtRefuse(pp, 'PREVALIDATION', 'The rule cannot be activated now.',
[refusals: review.refusals, chainWarnings: review.chainWarnings])
}
String ruleId = cand.ruleId as String
Map entry = review.entry
if (entry?.state == 'staging') return rtRefuse(pp, 'STAGING_IN_PROGRESS', 'Another activation of this rule is still being committed.')
long now = rtpNow(pp)
Map all = rtChallenges(pp)
Set staging = ((Map) rtRegistry(pp).rules).values().findAll { ((Map) it).state == 'staging' }.collect { ((Map) it).claimId } as Set
all.values().each { Object co ->
Map ch = co
if (now >= (ch.expiresAt as long) && (ch.state == 'open' || (ch.state == 'claimed' && !staging.contains(ch.claimId)))) {
rtChallengeApply(all, ch.id as String, [ch.state as String], 'expired', [expiredReason: 'timeout', expiredAt: now])
}
}
if (all.size() >= RT_MAX_CHALLENGES) {
List gone = new ArrayList(all.values()).findAll { ((Map) it).state in ['expired', 'consumed'] }
gone.sort { a, b -> (a.createdAt as long) <=> (b.createdAt as long) }.each { Object co ->
if (all.size() >= RT_MAX_CHALLENGES) rtChallengeApply(all, ((Map) co).id as String, [((Map) co).state as String], null, [:])
}
}
if (all.size() >= RT_MAX_CHALLENGES) return rtRefuse(pp, 'CHALLENGE_LIMIT', "${RT_MAX_CHALLENGES} challenges are open or in use. Wait for one to expire.".toString())
int baseEpoch = ((Map) review.core).baseEpoch as int
Map child = rtStageChild(pp, ruleId, entry?.childId as String)
rtpMark(pp, 'childBuild')
if (!child.ok) return child + [chainWarnings: review.chainWarnings]
String childId = child.childId as String
String claimId = rtpCall(pp, 'newId', 'k') as String
String challengeId = rtpCall(pp, 'newId', 'h') as String
long expiresAt = now + RT_CLAIM_MS
Map binding = [ruleId: ruleId, baseEpoch: baseEpoch, childId: childId, ruleHash: ((Map) review.core).ruleHash]
Map core = rtAutoCore(ruleId, cand.id as String, binding.ruleHash as String, baseEpoch,
((Map) review.core).baseRuleHash as String, ((Map) review.core).ruleSetRevision as int)
String nonce = rtpCall(pp, 'newId', 'n') as String
Map ch = [id: challengeId, candidateId: cand.id, ruleId: ruleId, state: 'claimed', claimId: claimId,
createdAt: now, claimedAt: now, expiresAt: expiresAt, nonce: nonce, core: core, auto: true,
startStopped: startStopped, arrive: arrive in RT_ARRIVE_STATES ? arrive : null, digest: rtDigest(core, nonce, expiresAt), binding: binding]
all.put(challengeId, ch)
rtpPut(pp, RT_CHALLENGES_KEY, all)
rtpHook(pp, 'afterClaim')
Map prior = entry == null || entry.state == 'removed' ? null :
[state: entry.state, epoch: entry.epoch, ruleHash: entry.ruleHash, index: rtIndex(pp, entry), claimId: entry.claimId]
Map staged = rtRegistryTransition(pp, ruleId, [null, 'active', 'disabled', 'removed'], 'staging',
[childId: childId, claimId: claimId, challengeId: challengeId, prior: prior, baseRevision: ((Map) review.core).ruleSetRevision,
epoch: baseEpoch, ruleHash: prior?.ruleHash, index: prior?.index])
if (!staged.ok) {
rtVoidClaim(pp, ch, claimId, 'registry moved')
return rtRefuse(pp, 'REGISTRY_STATE', 'The rule registry changed. Review it again.')
}
List devices = rtDeviceIds((Map) cand.doc).findAll { ((List) review.allow).contains(it) }
rtpMark(pp, 'claim')
Map res = null
Object set = null
try {
set = rtpCall(pp, 'child', [childId, 'haiSetup', [devices, review.allow, rtpCall(pp, 'purposes', null) ?: [:], rtSetupOptions(pp, cand.doc)]])
rtpMark(pp, 'setup')
if (rtSetupConfirmed(set)) {
rtpHook(pp, 'beforeStage')
res = rtpCall(pp, 'child', [childId, 'haiStage', [cand.doc, binding.ruleHash, challengeId, claimId, baseEpoch, expiresAt, rtStageIntent(ch)]])
rtpMark(pp, 'stage')
}
} catch (Exception e) {
rtLog(pp, [what: 'stageCallFailed', ruleId: ruleId, message: e.message])
return [ok: false, code: 'STAGE_UNCONFIRMED', challengeId: challengeId, claimId: claimId,
message: 'Staging was not confirmed. The runtime reconciles it on the next pass.', chainWarnings: review.chainWarnings]
}
if (!rtSetupConfirmed(set)) return rtSetupRefused(pp, ch, ruleId, claimId, [chainWarnings: review.chainWarnings])
rtpHook(pp, 'afterStage')
if (res == null || !res.ok) {
rtVoidClaim(pp, ch, claimId, "stage refused: ${res?.code}".toString())
rtRegistryBack(pp, ruleId, claimId)
return rtRefuse(pp, 'STAGE_REFUSED', "The rule child refused the staged document (${res?.code}).".toString(),
[chainWarnings: review.chainWarnings])
}
Map done = rtDriveCommit(pp, ruleId, childId, claimId, challengeId)
return [ok: true, claimId: claimId, childId: childId, challengeId: challengeId, baseEpoch: baseEpoch, fast: review.fast == true,
chainWarnings: review.chainWarnings, committed: done.ok == true, epoch: done.epoch,
message: done.ok == true ? 'Activated.' : 'Staged. The rule child commits it in its own execution.']
}
Map rtDriveCommit(Map pp, String ruleId, String childId, String claimId, String challengeId) {
Map res = null
try {
res = rtpCall(pp, 'child', [childId, 'haiCommitNow', [claimId]])
} catch (Exception e) {
rtLog(pp, [what: 'commitCallFailed', ruleId: ruleId, message: e.message])
return [ok: false, code: 'COMMIT_CALL_FAILED']
}
rtpMark(pp, 'commit')
Map n = res?.ok == true && res.notice instanceof Map ? res.notice : null
if (n != null && n.claimId == claimId && n.challengeId == challengeId && n.epoch != null && n.ruleHash != null && n.at != null) {
Map r = rtRecordCommit(pp, [challengeId: challengeId, claimId: claimId, epoch: n.epoch, ruleHash: n.ruleHash,
at: n.at, disabled: n.disabled == true, driven: true])
rtpMark(pp, 'record')
if (r.ok) return [ok: true, epoch: n.epoch]
}
Map s = null
try { s = rtpCall(pp, 'child', [childId, 'haiRuntimeState', []]) } catch (Exception e) { s = null }
if (s == null) return [ok: false, code: 'CHILD_NOT_READ_AFTER_COMMIT']
Map c = s.committed
Map ch = rtChallenges(pp).get(challengeId)
Map b = ch?.binding
if (c == null || c.claimId != claimId || b == null) {
return [ok: false, code: (res?.code ?: 'NOT_COMMITTED') as String]
}
Map r = rtRecordCommit(pp, [challengeId: challengeId, claimId: claimId, epoch: s.epoch,
ruleHash: s.ruleHash, at: c.at, doc: s.doc, disabled: s.disabled == true, driven: true])
if (!r.ok) {
rtRegistryRepair(pp, ruleId, s)
return [ok: false, code: (r.code ?: 'COMMIT_NOT_RECORDED') as String]
}
return [ok: true, epoch: s.epoch]
}
void rtRegistryBack(Map pp, String ruleId, String claimId) {
Map entry = rtEntry(pp, ruleId)
if (entry == null || entry.state != 'staging' || entry.claimId != claimId) return
Map prior = entry.prior
if (prior == null) {
rtRegistryTransition(pp, ruleId, ['staging'], 'removed', [expectClaim: claimId, epoch: 0, ruleHash: null, index: null, prior: null])
} else {
rtRegistryTransition(pp, ruleId, ['staging'], prior.state as String,
[expectClaim: claimId, epoch: prior.epoch, ruleHash: prior.ruleHash, index: prior.index, claimId: prior.claimId, prior: null])
}
}
Map rtRecordCommit(Map pp, Map report) {
Map ch = rtChallenges(pp).get(report?.challengeId)
if (ch == null || ch.claimId != report.claimId) return [ok: false, code: 'CLAIM_NOT_HELD']
if (ch.state == 'consumed') return [ok: true, already: true]
Map b = ch.binding
boolean matches = ch.state == 'claimed' && b != null && (report.epoch as int) == (b.baseEpoch as int) + 1 &&
report.ruleHash == b.ruleHash && report.at != null && (report.at as long) < (ch.expiresAt as long)
if (!matches) {
if (ch.state == 'claimed') rtVoidClaim(pp, ch, ch.claimId as String, 'commit did not match the claim')
return [ok: false, code: 'COMMIT_MISMATCH']
}
Map cand = rtCandidate(pp, ch.candidateId)
rtChallengeTransition(pp, ch.id as String, ['claimed'], 'consumed', [consumedAt: rtpNow(pp), epoch: report.epoch], ch.claimId as String)
rtRegistryTransition(pp, b.ruleId as String, ['staging'], report.disabled == true ? 'disabled' : 'active',
[expectClaim: ch.claimId, epoch: report.epoch, ruleHash: report.ruleHash, doc: report.doc ?: cand?.doc, prior: null, childId: b.childId])
Map line = [what: 'activated', ruleId: b.ruleId, epoch: report.epoch, claim: ch.claimId]
if (report.driven == true) line.driven = true
rtLog(pp, line)
return [ok: true]
}
@Field static final String RT_STAGE_FAILURE_KEY = 'rtStageFailure'
Map rtStageFailure(Map pp) { return rtpGet(pp, RT_STAGE_FAILURE_KEY) }
Map rtRecordStageFailure(Map pp, Map entry, Map s, String how) {
if (entry == null) return null
Map ch = rtChallenges(pp).get(entry.challengeId)
Map staged = s?.staged
Map disc = s?.discarded
Map commit = (staged?.commit ?: disc?.commit)
boolean ran = commit != null && commit.observedAt != null
String code
String message
if (s == null) {
code = 'CHILD_NOT_READ'
message = 'The rule app could not be read while this activation was being given up, so why it did not finish is not known.'
} else if (disc?.code != null && disc.claimId == entry.claimId) {
code = disc.code as String
message = 'The rule app was asked to commit and the commit never ran. Nothing refused the rule; the step that finishes an activation did not happen.'
} else if (ran) {
code = 'COMMIT_REJECTED'
message = ("The rule app ran the commit and did not keep it" +
(disc?.reason ? ": ${disc.reason}." : '.')).toString()
} else if (commit != null) {
code = 'COMMIT_CALLBACK_NOT_OBSERVED'
message = 'The rule app armed the step that finishes an activation and that step never ran.'
} else {
code = 'STAGE_NOT_RECORDED'
message = 'The rule app holds no record of this activation, so it did not reach the point of committing.'
}
Map rec = [ruleId: entry.ruleId as String, code: code, message: message, how: how, at: rtpNow(pp),
childId: entry.childId as String, claimId: entry.claimId as String,
challengeId: entry.challengeId as String,
stagedAt: staged?.stagedAt ?: disc?.at, expiresAt: ch?.expiresAt,
callback: commit == null ? null : [handler: commit.handler, armedAt: commit.armedAt,
dueAt: commit.dueAt, delayMs: commit.delayMs,
observedAt: commit.observedAt, attempts: commit.attempts],
childNotes: (((Map) s?.trace)?.notes ?: []).takeRight(RT_STAGE_FAILURE_NOTES)]
rtpPut(pp, RT_STAGE_FAILURE_KEY, rec)
rtLog(pp, [what: 'stageFailed', ruleId: entry.ruleId, code: code])
return rec
}
boolean rtReleaseExpiredStaging(Map pp, String ruleId) {
Map e = rtEntry(pp, ruleId)
if (e == null || e.state != 'staging') return false
Map ch = rtChallenges(pp).get(e.challengeId)
if (ch != null && rtpNow(pp) < (ch.expiresAt as long)) return false
Map s = null
if (e.childId != null && (rtpCall(pp, 'childExists', e.childId) as boolean)) {
try { s = rtpCall(pp, 'child', [e.childId, 'haiRuntimeState', []]) } catch (Exception ignored) { s = null }
try { rtpCall(pp, 'child', [e.childId, 'haiDiscardStaged', ['the claim expired']]) } catch (Exception ignored) { }
}
rtRecordStageFailure(pp, e, s, 'released on request')
if (ch?.state == 'claimed') rtVoidClaim(pp, ch, e.claimId as String, 'not committed')
rtRegistryBack(pp, ruleId, e.claimId as String)
rtLog(pp, [what: 'stagingReleased', ruleId: ruleId])
return true
}
String rtStagingWait(Map pp, Map entry) {
Map ch = rtChallenges(pp).get(entry?.challengeId)
if (ch == null) return 'This rule is being activated.'
long left = (ch.expiresAt as long) - rtpNow(pp)
if (left <= 0) return 'This rule is being activated and its claim has expired.'
long s = Math.round(left / 1000.0d)
return ("This rule is still being activated. If it does not finish, it releases itself in " +
(s < 60 ? "${s} seconds" : "${Math.round(s / 60.0d)} minutes") + ', and it can be stopped then.').toString()
}
Map rtRuleOp(Map pp, String ruleId, String op, String runId = null) {
Map entry = rtEntry(pp, ruleId)
if (entry != null && entry.state == 'staging' && rtReleaseExpiredStaging(pp, ruleId)) {
entry = rtEntry(pp, ruleId)
}
if (entry != null && entry.state == 'staging') {
return [ok: false, code: 'STILL_ACTIVATING', message: rtStagingWait(pp, entry)]
}
if (op == 'remove' && entry == null) {
int held = rtDropCandidatesFor(pp, ruleId)
if (held == 0) return [ok: false, code: 'NOT_AVAILABLE', state: 'absent', message: 'HAI has no rule with this id and holds no copy of it, so nothing was removed.']
rtLog(pp, [what: 'heldCopyRemoved', ruleId: ruleId, count: held])
return [ok: true, message: 'The copy was never on the hub; the held copy has been discarded.']
}
if (entry == null || (entry.state == 'removed' && op != 'remove')) {
return [ok: false, code: 'NOT_AVAILABLE', state: entry == null ? 'absent' : 'removed',
message: (entry == null ? 'HAI has no rule with this id on the hub' : 'This rule was already removed from the hub') + ', so nothing was done.']
}
String childId = entry.childId as String
if (op == 'remove') {
if (entry.state == 'removed') {
int held = rtDropCandidatesFor(pp, ruleId)
if (childId != null && (rtpCall(pp, 'childExists', childId) as boolean)) {
rtpCall(pp, 'removeChild', childId)
rtRegistryTransition(pp, ruleId, ['removed'], 'removed', [childId: null, index: null])
rtLog(pp, [what: 'orphanChildRemoved', ruleId: ruleId])
return [ok: true, message: 'The rule was already gone; its leftover app has been removed.']
}
if (held > 0) {
rtLog(pp, [what: 'heldCopyRemoved', ruleId: ruleId, count: held])
return [ok: true, message: 'The rule was already gone; its held copy has been discarded.']
}
return [ok: false, code: 'NOT_AVAILABLE', state: 'removed', message: 'This rule was already removed, and nothing of it is left on the hub to remove.']
}
if (entry.state != 'disabled') return [ok: false, code: 'DEACTIVATE_FIRST', message: 'Stop the rule before removing it. A running rule is not removed.']
rtpCall(pp, 'removeChild', childId)
rtRegistryTransition(pp, ruleId, ['disabled'], 'removed', [index: null])
rtDropCandidatesFor(pp, ruleId)
rtLog(pp, [what: 'removed', ruleId: ruleId])
rtpCall(pp, 'ruleRemoved', ruleId)
return [ok: true]
}
if (op == 'enable') {
Collection writable = (Collection) rtpCall(pp, 'writableVariables', null)
List names = writable == null ? [] : rtUnapprovedHubWrites(rtChildDoc(pp, ruleId, entry.epoch), writable)
if (!names.isEmpty()) {
rtLog(pp, [what: op, ruleId: ruleId, ok: false, code: 'VARIABLE_NOT_WRITABLE'])
return [ok: false, code: 'VARIABLE_NOT_WRITABLE', names: names, message: rtUnapprovedHubWritesWords(names)]
}
}
Map res = rtpCall(pp, 'child', [childId, 'haiLifecycle', [op, runId]])
if (res?.ok && op == 'disable' && entry.state == 'active') rtRegistryTransition(pp, ruleId, ['active'], 'disabled', [:])
if (res?.ok && op == 'enable' && entry.state == 'disabled') rtRegistryTransition(pp, ruleId, ['disabled'], 'active', [:])
rtLog(pp, [what: op, ruleId: ruleId, ok: res?.ok ?: false])
return res ?: [ok: false]
}
List rtUnapprovedHubWrites(Map doc, Collection writable) {
if (!(doc instanceof Map) || writable == null) return []
Map refs = (doc.refs instanceof Map ? doc.refs : [:])
Set ok = writable.collect { it.toString() } as Set
Set keys = [] as Set
Closure walk
walk = { Object o ->
if (o instanceof List) { ((List) o).each { walk(it) }; return }
if (!(o instanceof Map)) return
Map m = o
if (m.type == 'setVariable' && m.ref instanceof String) keys << m.ref
if ((m.type == 'runRule' || m.type == 'http') && m.result instanceof String) keys << m.result
if (m.type == 'ping' && m.into instanceof Map) ((Map) m.into).values().each { if (it instanceof String) keys << it }
m.each { Object k, Object v -> if (v instanceof Map || v instanceof List) walk(v) }
}
walk(doc.actions)
walk(doc.onFailure)
return keys.collect { Object k -> refs.get(k) }
.findAll { Map r -> r != null && r.kind == 'variable' && (r.scope ?: 'hub') == 'hub' && !ok.contains((r.name ?: '') as String) }
.collect { Map r -> (r.name ?: '') as String }.unique().sort()
}
String rtUnapprovedHubWritesWords(List names) {
boolean one = names.size() == 1
return ("This rule changes the hub variable${one ? '' : 's'} ${names.take(4).join(', ')}${names.size() > 4 ? ' and others' : ''}, " +
"which HAI rules may not change yet, so it cannot be started or promoted until ${one ? 'it is' : 'they are'} added to " +
'Approved hub variables on the HAI Engine page.').toString()
}
void rtPruneTombstones(Map pp) {
Map reg = rtRegistry(pp)
Map rules = reg.rules
Set busy = ((Map) rtChallenges(pp)).values()
.findAll { ((Map) it).state in ['open', 'claimed'] }
.collect { ((Map) it).ruleId as String } as Set
List dead = rules.values().findAll { ((Map) it).state == 'removed' && !busy.contains(((Map) it).ruleId as String) }
if (dead.size() <= RT_MAX_TOMBSTONES) return
dead.sort { a, b -> ((b.updatedAt ?: 0) as long) <=> ((a.updatedAt ?: 0) as long) }
List drop = dead.subList(RT_MAX_TOMBSTONES, dead.size()).collect { ((Map) it).ruleId as String }
Map kept = new LinkedHashMap()
rules.each { Object k, Object v -> if (!drop.contains(k as String)) kept.put(k, v) }
rtpPut(pp, RT_REGISTRY_KEY, [revision: (reg.revision as int) + 1, rules: kept])
List cands = rtCandidates(pp).findAll { !drop.contains(((Map) it).ruleId as String) }
rtpPut(pp, RT_CANDIDATES_KEY, cands)
rtLog(pp, [what: 'tombstonesForgotten', count: drop.size()])
}
List rtRenamedWritable(List names, String oldName, String newName) {
List have = (names ?: []).collect { it.toString() }
if (!have.contains(oldName)) return null
return have.collect { it == oldName ? newName : it }.unique()
}
Map rtRegistrationDelta(List had, List want) {
List have = (had ?: []).collect { it.toString() }.unique()
List wanted = (want ?: []).collect { it.toString() }.unique()
return [drop: have.findAll { !wanted.contains(it) }, add: wanted.findAll { !have.contains(it) }]
}
void rtPushAllow(Map pp) {
List allow = rtpCall(pp, 'allowList', null)
((Map) rtRegistry(pp).rules).values().each { Object eo ->
Map e = eo
if (e.state == 'removed' || !(rtpCall(pp, 'childExists', e.childId) as boolean)) return
List devices = ((List) (rtIndex(pp, e)?.deviceIds ?: [])).findAll { allow.contains(it) }
try {
Object set = rtpCall(pp, 'child', [e.childId, 'haiSetup', [devices, allow, rtpCall(pp, 'purposes', null) ?: [:], rtSetupOptions(pp)]])
if (!rtSetupConfirmed(set)) rtLog(pp, [what: 'allowPushFailed', ruleId: e.ruleId, message: 'the rule app did not confirm its setup'])
}
catch (Exception ex) { rtLog(pp, [what: 'allowPushFailed', ruleId: e.ruleId, message: ex.message]) }
}
}
boolean rtSetupConfirmed(Object set) {
return set instanceof Map && ((Map) set).ok == true
}
Map rtSetupRefused(Map pp, Map ch, String ruleId, String claimId, Map extra = [:]) {
rtLog(pp, [what: 'setupUnconfirmed', ruleId: ruleId])
rtVoidClaim(pp, ch, claimId, 'setup not confirmed')
rtRegistryBack(pp, ruleId, claimId)
return rtRefuse(pp, 'SETUP_UNCONFIRMED', 'The rule app did not confirm its devices and permissions, so nothing was staged. Try again.', extra)
}
Map rtSetupOptions(Map pp, Map doc = null) {
Map out = [writableVariables: rtpCall(pp, 'writableVariables', null) ?: [], modeAllowed: rtpCall(pp, 'modeAllowed', null) == true,
hubPowerAllowed: rtpCall(pp, 'hubPowerAllowed', null) != false]
Object key = rtpCall(pp, 'ruleKey', null)
if (key instanceof String) out.ruleKey = key
if (doc != null) {
Set named = ((Map) (doc.refs ?: [:])).values().findAll { it instanceof Map && ((Map) it).id != null }.collect { ((Map) it).id.toString() } as Set
out.rules = rtKnownRuleIds(pp).findAll { named.contains(it) }
out.paused = rtPausedRuleIds(pp).findAll { named.contains(it) }
Set acts = rtActsOnRmRules(doc)
Map sw = acts.isEmpty() ? [:] : (rtpCall(pp, 'rmSwitched', null) ?: [:])
out.switched = sw.findAll { k, v -> acts.contains(k as String) }
}
return out
}
Map rtEndpointOp(Map pp, String op, String target, String value) {
switch (op) {
case 'cancel': return rtRuleOp(pp, target, 'cancelRuns')
case 'setBoolean': return rtRuleOp(pp, target, 'setBoolean', value)
case 'trigger': return rtRuleOp(pp, target, 'endpoint', value)
case 'setVariable':
Map row = ((Map) (rtpCall(pp, 'hubVariables', null) ?: [:])).get(target)
Object v = row == null ? null : schemaHubValue(((row.type ?: 'string') as String).toLowerCase(), value)
Map out = !((List) (rtpCall(pp, 'writableVariables', null) ?: [])).contains(target) || row == null ? [ok: false, code: 'VARIABLE_NOT_WRITABLE'] :
v == null ? [ok: false, code: 'VALUE_TYPE'] : [ok: true]
if (out.ok) rtpCall(pp, 'setHubVariable', [target, v])
rtLog(pp, [what: 'setVariable', ruleId: target, ok: out.ok, code: out.code])
return out
}
return [ok: false, code: 'UNKNOWN_OPERATION']
}
@Field static final String RT_API_EVENT = 'haiRuleApi'
@Field static final String RT_API_REPLY = 'haiRuleApiReply'
@Field static final Map RT_API_OPS = [run: 'runNow', stop: 'cancelRuns', pause: 'pause', resume: 'resume', setBoolean: 'setBoolean', trigger: 'endpoint']
Map rtAppApi(Map pp, Map req, boolean on, List paused) {
String op = req?.op as String
String rule = req?.rule == null ? null : req.rule.toString()
String value = req?.value == null ? null : req.value.toString()
Map out
if (!on) out = [ok: false, code: 'API_OFF']
else if (op == 'list') {
out = [ok: true, rules: ((Map) rtRegistry(pp).rules).values().findAll { ((Map) it).state in ['active', 'disabled'] }.collect { Object o ->
Map e = o
[id: e.ruleId, name: rtIndex(pp, e)?.name ?: e.ruleId, state: e.state, paused: (paused ?: []).contains(e.ruleId)]
}.sort { a, b -> (a.name as String).toLowerCase() <=> (b.name as String).toLowerCase() }]
} else if (!RT_API_OPS.containsKey(op)) out = [ok: false, code: 'UNKNOWN_OPERATION']
else if (!rule) out = [ok: false, code: 'NO_RULE']
else if (op == 'setBoolean' && !(value in ['true', 'false'])) out = [ok: false, code: 'NOT_A_BOOLEAN']
else if (value != null && value.length() > 1024) out = [ok: false, code: 'TOO_LARGE']
else out = rtRuleOp(pp, rule, RT_API_OPS.get(op) as String, value)
String from = ((req?.from ?: 'an app') as String).take(80)
if (op != 'list') rtLog(pp, [what: 'appApi', op: op, ruleId: rule, from: from, ok: out.ok == true, code: out.code])
return [ok: out.ok == true, op: op, rule: rule, id: req?.id, from: from] + out.findAll { k, v -> k in ['code', 'message', 'rules'] }
}
Map rtAppApiVerify(String token, Object value, Object data, long at, Map seen) {
Map loose = [:]
try { loose = data instanceof String ? new groovy.json.JsonSlurper().parseText((String) data) : [:] } catch (Exception e) { loose = [:] }
if (loose?.containsKey('token')) return [refused: 'TOKEN_IN_CLEAR', from: loose.from]
if (!(loose?.sig instanceof String)) return [refused: 'NO_SIGNATURE', from: loose?.from]
if (!(token instanceof String) || token.isEmpty()) return [refused: 'BAD_SIGNATURE', from: loose.from]
Map req = rtSignedPayload([token], RT_API_EVENT, value, data, at, seen)
return req == null ? [refused: 'BAD_SIGNATURE', from: loose.from] : [req: req]
}
String rtAppApiReplyData(String token, Object value, Map out, long at, String nonce) {
Map body = [ok: out.ok == true, op: out.op, rule: out.rule, id: out.id]
if (out.code != null) body.code = out.code
if (out.message != null) body.message = out.message
if (out.rules instanceof List) body.sealed = rtSeal(token, nonce, groovy.json.JsonOutput.toJson(out.rules))
return rtSignedData(token, RT_API_REPLY, value, body, at, nonce)
}
String rtSeal(String key, String nonce, String text) {
byte[] b = (text ?: '').getBytes('UTF-8')
byte[] out = new byte[b.length]
for (int i = 0; i < b.length; i += 32) {
String ks = jsonHmac(key, "hai-seal/1|${nonce}|${i.intdiv(32)}".toString())
for (int j = 0; j < 32 && i + j < b.length; j++) out[i + j] = (byte) (((int) b[i + j]) ^ Integer.parseInt(ks.substring(2 * j, 2 * j + 2), 16))
}
return out.encodeBase64().toString()
}
boolean rtAppApiFirstRefusal(Map seen, String from, int cap) {
String key = ((from ?: 'an app') as String).take(80)
if (seen.containsKey(key) || seen.size() >= cap) return false
seen.put(key, true)
return true
}
String rtNewApiToken() {
return UUID.randomUUID().toString().replace('-', '')
}
Map rtReconcileAll(Map pp, Map lights = null, Collection only = null) {
List done = []
((Map) rtRegistry(pp).rules).values().each { Object eo ->
Map e = eo
if (e.state == 'removed') return
if (only != null && !only.contains(e.ruleId)) return
String ruleId = e.ruleId as String
Map light = lights?.get(ruleId)
if (lights != null && light == null && e.state == 'disabled') return
if (light != null && e.state != 'staging' && !light.staged && (light.epoch as int) == ((e.epoch ?: 0) as int) &&
light.ruleHash == e.ruleHash && (light.disabled ? 'disabled' : 'active') == e.state) return
if (rtpCall(pp, 'skipChild', e.childId) == true) return
if (e.childId == null || !(rtpCall(pp, 'childExists', e.childId) as boolean)) {
if (e.state == 'staging') {
Map ch = rtChallenges(pp).get(e.challengeId)
if (ch?.state == 'claimed') rtVoidClaim(pp, ch, e.claimId as String, 'child lost')
}
rtRegistryTransition(pp, ruleId, [e.state], 'removed', [lostChild: true, index: null])
rtLog(pp, [what: 'childLost', ruleId: ruleId])
done << [ruleId: ruleId, action: 'childLost']
return
}
Map s = null
try { s = rtpCall(pp, 'child', [e.childId, 'haiRuntimeState', []]) }
catch (Exception ex) { done << [ruleId: ruleId, action: 'unreadable']; return }
if (s == null) {
List ids = null
try { ids = rtpCall(pp, 'childIds', null) } catch (Exception ex2) { ids = null }
if (ids == null || ids.collect { it as String }.contains(e.childId as String)) { done << [ruleId: ruleId, action: 'unreadable']; return }
rtpCall(pp, 'warn', "HAI rule ${ruleId}: its app ${e.childId} is gone, so it is marked removed".toString())
rtRegistryTransition(pp, ruleId, [e.state], 'removed', [lostChild: true, index: null, removedNote: 'its app was removed outside HAI'])
rtLog(pp, [what: 'childLost', ruleId: ruleId])
rtpCall(pp, 'ruleRemoved', ruleId)
done << [ruleId: ruleId, action: 'childLost']
return
}
if (e.state == 'staging') {
Map ch = rtChallenges(pp).get(e.challengeId)
Map b = ch?.binding
Map c = s.committed
if (c != null && c.claimId == e.claimId && b != null && (s.epoch as int) == (c.epoch as int) &&
s.ruleHash == b.ruleHash && (s.epoch as int) == (b.baseEpoch as int) + 1) {
Map r = rtRecordCommit(pp, [challengeId: e.challengeId, claimId: e.claimId, epoch: s.epoch, ruleHash: s.ruleHash, at: c.at, doc: s.doc, disabled: s.disabled == true])
done << [ruleId: ruleId, action: r.ok ? 'commitRecorded' : 'commitRejected']
if (!r.ok) rtRegistryRepair(pp, ruleId, s)
return
}
Map st = s.staged
if (st != null && st.claimId == e.claimId && ch?.state == 'claimed' && rtpNow(pp) < (ch.expiresAt as long)) {
rtpCall(pp, 'child', [e.childId, 'haiResumeStaged', []])
rtDriveCommit(pp, ruleId, e.childId as String, e.claimId as String, e.challengeId as String)
done << [ruleId: ruleId, action: 'commitResumed']
return
}
rtRecordStageFailure(pp, e, s, 'reconciled')
if (st != null && st.claimId == e.claimId) rtpCall(pp, 'child', [e.childId, 'haiDiscardStaged', ['the claim expired']])
if (ch?.state == 'claimed') rtVoidClaim(pp, ch, e.claimId as String, 'not committed')
rtRegistryBack(pp, ruleId, e.claimId as String)
rtRegistryRepair(pp, ruleId, s)
done << [ruleId: ruleId, action: 'stagingAbandoned']
return
}
if (rtRegistryRepair(pp, ruleId, s)) done << [ruleId: ruleId, action: 'repaired']
}
return [reconciled: done]
}
boolean rtRegistryRepair(Map pp, String ruleId, Map s) {
if (s == null) return false
Map e = rtEntry(pp, ruleId)
if (e == null || !(e.state in ['active', 'disabled', 'removed'])) return false
int epoch = (s.epoch ?: 0) as int
if (epoch == 0) return false
String want = s.disabled ? 'disabled' : 'active'
if (e.state == want && (e.epoch as int) == epoch && e.ruleHash == s.ruleHash) return false
rtRegistryTransition(pp, ruleId, [e.state], want, [epoch: epoch, ruleHash: s.ruleHash, doc: s.doc, repairedAt: rtpNow(pp)])
rtLog(pp, [what: 'registryRepaired', ruleId: ruleId, epoch: epoch])
return true
}
Map rtWatchdog(Map pp) {
long now = rtpNow(pp)
rtPruneTombstones(pp)
try { rtPruneGhosts(pp) } catch (Exception ex) { rtLog(pp, [what: 'pruneFailed', error: ex.toString()]) }
Map prev = rtpGet(pp, RT_WATCHDOG_KEY)
List flags = []
if (prev.lastWatchdogAt != null) {
long gap = now - (prev.lastWatchdogAt as long)
if (gap < 0) flags << [what: 'clockBackward', gapMs: gap]
if (gap > 2L * RT_WATCHDOG_EVERY_MS) flags << [what: 'clockForward', gapMs: gap]
}
List allow = rtpCall(pp, 'allowList', null)
String allowHash = jsonHash(((allow ?: []) as List).collect { it.toString() }.sort()).take(16)
Map children = [:]
Map lights = [:]
List samples = []
List health = []
List deferred = []
int quiet = 0
Set waited = ((List) (prev.deferred ?: [])).collect { it as String } as Set
List entries = ((Map) rtRegistry(pp).rules).values().toList()
boolean more = rtpCall(pp, 'deferredOnly', null) == true
entries = entries.findAll { waited.contains(((Map) it).ruleId as String) } + (more ? [] : entries.findAll { !waited.contains(((Map) it).ruleId as String) })
entries.each { Object eo ->
Map e = eo
if (!(e.state in ['active', 'staging'])) return
if (rtpCall(pp, 'skipChild', e.childId) == true) { deferred << (e.ruleId as String); return }
if (!(rtpCall(pp, 'childExists', e.childId) as boolean)) { health << [ruleId: e.ruleId, what: 'childLost']; return }
try {
Map r = rtpCall(pp, 'child', [e.childId, 'haiOverdue', [now, allow, now, allowHash]])
children.put(e.ruleId, [fired: ((List) r.fired).size(), rows: r.rows, blocked: r.blocked, epoch: r.epoch])
if (r.quiet == true) quiet++
if (r.light instanceof Map) lights.put(e.ruleId, r.light)
((List) (r.lateSamples ?: [])).each { Object f -> samples << ([ruleId: e.ruleId] + f) }
Map h = r.health
if (h?.timerRecoveryExceeded) health << [ruleId: e.ruleId, what: 'TIMER_RECOVERY_EXCEEDED', detail: h.lastRecoveryExceeded]
if (h?.lateSendRefused) health << [ruleId: e.ruleId, what: 'LATE_SEND_REFUSED', detail: h.lastLateSendRefused]
if (h?.fenceAdopted) health << [ruleId: e.ruleId, what: 'FENCE_ADOPTED', detail: h.lastFenceAdopted]
if (r.blocked || h?.stateBudget) health << [ruleId: e.ruleId, what: 'STATE_BUDGET', detail: h?.stateBudget]
((List) r.fired).findAll { (((Map) it).lateMs as long) > RT_RECOVERY_MS }.each {
health << [ruleId: e.ruleId, what: 'RECOVERY_OBJECTIVE_EXCEEDED', lateMs: ((Map) it).lateMs]
}
} catch (Exception ex) {
health << [ruleId: e.ruleId, what: 'childError', message: ex.message]
}
}
List keep = ((List) (prev.samples ?: [])) + samples
if (more) {
children = ((Map) (prev.children ?: [:])) + children
health = ((List) (prev.health ?: [])) + health
flags = ((List) (prev.flags ?: [])) + flags
quiet += ((prev.quiet ?: 0) as int)
}
Map rec = [lastWatchdogAt: now, passMs: rtpNow(pp) - now, children: children, flags: flags,
samples: keep.size() > 64 ? keep.subList(keep.size() - 64, keep.size()) : keep,
health: health, healthy: health.isEmpty(), quiet: quiet]
if (!deferred.isEmpty()) rec.deferred = deferred
rtpPut(pp, RT_WATCHDOG_KEY, rec)
return rec + [lights: lights]
}
void rtPruneGhosts(Map pp) {
List ids = null
try { ids = rtpCall(pp, 'childIds', null) } catch (Exception e) { ids = null }
if (ids == null || ids.isEmpty()) return
Set have = ids.collect { it as String } as Set
((Map) rtRegistry(pp).rules).values().toList().each { Object eo ->
Map e = eo
if (!(e.state in ['active', 'disabled']) || e.childId == null || have.contains(e.childId as String)) return
Map t = rtRegistryTransition(pp, e.ruleId as String, [e.state as String], 'removed',
[childId: null, index: null, removedNote: 'its app was removed outside HAI'])
if (t?.ok == false) return
rtLog(pp, [what: 'removedOutside', ruleId: e.ruleId, note: 'its app was removed outside HAI'])
rtpCall(pp, 'warn', "HAI rule ${e.ruleId}: its app ${e.childId} was removed outside HAI, so it is marked removed".toString())
rtpCall(pp, 'ruleRemoved', e.ruleId)
}
}
Map rtRemoveStoppingFirst(Map pp, String ruleId) {
Map e = rtEntry(pp, ruleId)
if (e?.state == 'active') {
Map stopped = rtRuleOp(pp, ruleId, 'disable')
if (stopped?.ok != true) {
return [ok: false, code: (stopped?.code ?: 'NOT_STOPPED') as String,
message: ('The rule could not be stopped, so it was not removed' + (stopped?.message ? ": ${stopped.message}" : '.')).toString()]
}
}
return rtRuleOp(pp, ruleId, 'remove')
}
String rtAnnounceMark(Map pp, Map lights, Object lastSubmit) {
List reg = ((Map) rtRegistry(pp).rules).values().collect { Object o -> Map e = o; [e.ruleId, e.state, e.epoch, e.ruleHash] }
return jsonHash([reg: reg.collect { it.collect { v -> v == null ? '' : v.toString() } },
lights: (lights ?: [:]).collect { k, v -> [k as String, (((Map) v).mark ?: '') as String] }.sort { it[0] },
at: lastSubmit?.at == null ? '' : lastSubmit.at.toString()]).take(16)
}
String rtInlinePhase(Map pp, Object candidateId, Object ruleId) {
boolean claimed = ((Map) rtChallenges(pp)).values().any { Object o ->
Map ch = o
ch.candidateId == candidateId && (ch.state in ['claimed', 'consumed'])
}
Map e = ruleId == null ? null : rtEntry(pp, ruleId as String)
return claimed || e?.state == 'staging' ? 'reconcile' : 'queue'
}
@Field static final long RT_INLINE_AWAIT_MS = 60000L
Map rtAwaitOutcome(Object entryState, boolean queued, boolean expired) {
if (queued && !expired) return [pending: true, activated: false, settled: false]
if (entryState == 'staging' && !expired) return [pending: true, activated: false, settled: false]
boolean live = entryState in ['active', 'disabled']
return [pending: false, activated: live, settled: true]
}
Map rtAbandonReply(Map current, List claimedRuleIds, int rolled, boolean busy, String why) {
if (current?.sentAt != null && claimedRuleIds) {
String rid = claimedRuleIds[0] as String
return [ok: true, code: 'INLINE_AWAITING', op: current.op, ruleId: rid, sentAt: current.sentAt,
pending: true, activated: false,
notice: ("The hub refused this app more work part way through, after ${claimedRuleIds.join(', ')} " +
'had been claimed. The claim stands and is being reconciled; nothing was retried.').toString()]
}
String notice = ('The last request from the editor was abandoned: ' +
(busy ? 'the hub refused this app more work (excessive hub load). Nothing was activated' : "it failed (${why})") +
(rolled > 0 ? '. The half-kept rule was removed, so nothing is left half-done' : '') + '.').toString()
Map out = [ok: false, code: 'SUBMIT_ABANDONED', pending: false, activated: false, notice: notice, rolledBack: rolled]
return current?.sentAt == null ? out : out + [op: current.op, ruleId: current.ruleId, sentAt: current.sentAt]
}
boolean rtDocUsesSun(Map doc) {
return jsonCanonicalise(doc) ==~ /(?s).*(sunrise|sunset).*/
}
boolean rtDocUsesRmRules(Map doc) {
return ((Map) (doc?.refs ?: [:])).values().any { ((Map) it).kind == 'rmRule' }
}
@Field static final long RT_SUBMIT_MAX_AGE_MS = 120000L
@Field static final String RT_EDITOR_LINK = '/apps/api/[0-9]{1,9}/editor[?]access_token=[A-Za-z0-9-]{8,64}(&aud=[a-f0-9]{32}&v=[a-f0-9]{12})?'
@Field static final int RT_SUBMIT_SEEN = 16
String rtChildLabel(Object name, Object ruleId, Object ruleState, boolean paused) {
String n = ((name ?: ruleId ?: 'rule') as String).replaceAll(/[<>\r\n\t]+/, ' ').replaceAll(/\s+/, ' ').trim()
String word = ruleState == 'disabled' ? '(Stopped)' : ruleState == 'active' && paused ? '(Paused)' : ''
return '[HAI] ' + n.take(80 - 6 - (word ? word.length() + 1 : 0)) + (word ? " <span style='color:red'>${word}</span>".toString() : '')
}
Map rtCheckSubmission(Object data, Object pairing, List seen, long now, String canonical = null) {
if (!(pairing instanceof String) || ((String) pairing).isEmpty()) return [ok: false, code: 'NOT_PAIRED']
Map parsed = data instanceof String ? jsonParse((String) data) : (data instanceof Map ? [ok: true, value: data] : [ok: false])
if (!parsed.ok || !(parsed.value instanceof Map)) return [ok: false, code: 'SUBMIT_SHAPE']
Map p = parsed.value
boolean ruleOk = (p.rule instanceof String) || (p.rule instanceof Map)
if (!ruleOk || !(p.sig instanceof String) || !(p.sentAt instanceof Number) || !(p.ruleId instanceof String)) return [ok: false, code: 'SUBMIT_SHAPE']
String op = (p.op ?: 'run') as String
if (!(op in EDITOR_SUBMIT_OPS)) return [ok: false, code: 'SUBMIT_SHAPE']
long sentAt = p.sentAt as long
String sealed = p.link instanceof String ? (String) p.link : ''
String text = p.rule instanceof Map ? (canonical != null && data instanceof Map ? canonical : jsonCanonicalise((Map) p.rule, SCHEMA_DEFAULTS)) : (p.rule as String)
if (p.lifecycle != null && !(op == 'run' && p.lifecycle instanceof String && p.lifecycle in RT_ARRIVE_STATES)) return [ok: false, code: 'SUBMIT_SHAPE']
String lifecycle = p.lifecycle as String
String expected = jsonSign((String) pairing, "${sentAt}\n${op}\n${p.ruleId}\n${text}\n${sealed}${lifecycle != null ? '\n' + lifecycle : ''}".toString())
if (!jsonSameSecret(expected, p.sig)) return [ok: false, code: 'SUBMIT_SIGNATURE']
if (Math.abs(now - sentAt) > RT_SUBMIT_MAX_AGE_MS) return [ok: false, code: 'SUBMIT_STALE', sig: p.sig, ruleId: p.ruleId, sentAt: sentAt]
if (((List) (seen ?: [])).contains(p.sig)) return [ok: false, code: 'SUBMIT_REPLAY', sig: p.sig, ruleId: p.ruleId, sentAt: sentAt]
String link = sealed ? jsonUnseal((String) pairing, "${sentAt}\n${op}\n${p.ruleId}".toString(), sealed) : null
boolean linkRejected = link != null && !(link ==~ RT_EDITOR_LINK)
if (linkRejected) link = null
return [ok: true, op: op, rule: text, sig: p.sig, ruleId: p.ruleId, sentAt: sentAt, link: link,
linkRejected: linkRejected, doc: p.rule instanceof Map ? p.rule : null, lifecycle: lifecycle]
}
Map rtSubmitRecord(Map last, String ruleId, Map outcome, Map identity = null) {
Map prev = (last ?: [:])
boolean sameRule = ruleId != null && prev.ruleId == ruleId
Map next = new LinkedHashMap(sameRule ? prev : [:])
next.putAll(outcome ?: [:])
if (ruleId != null) next.ruleId = ruleId
next.remove('sig')
next.remove('sentAt')
if (identity?.sig != null) {
next.sig = identity.sig
next.sentAt = identity.sentAt
}
return next
}
@Field static final Map TL_OUTCOMES = [
reportedStateMatch: 'confirmed', observedStateMatch: 'confirmed', requested: 'requested, not yet sent',
dispatched: 'sent, not yet confirmed', unknown: 'result unknown', failed: 'failed', notSent: 'not sent',
]
@Field static final Map TL_REFUSALS = [
gateFalse : 'its conditions were false',
gateUnknown : 'a condition could not be read, and the rule blocks when unsure',
notAdmitted : 'the rule was not accepting new runs',
ignored : 'a run was already in progress, and the rule ignores new triggers meanwhile',
queued : 'it was queued behind a run in progress',
queueOverflow: 'the queue of waiting runs was full',
parallelLimit: 'too many runs were already in progress',
duplicate : 'the same event had already been handled',
]
String tlDevice(Map doc, Object dev) {
Map hit = ((Map) (doc?.refs ?: [:])).values().find { it instanceof Map && ((Map) it).id == dev }
return (hit?.name ?: dev) as String
}
Object tlNode(Map doc, String path) {
Object cur = doc
for (String part : (path ?: '').split('\\.')) {
if (cur instanceof Map) cur = ((Map) cur).get(part)
else if (cur instanceof List && part.isInteger() && (part as int) < ((List) cur).size()) cur = ((List) cur)[part as int]
else return null
}
return cur
}
String tlCommand(Map doc, Map r, Map intent) {
String key = ((Map) (doc?.refs ?: [:])).find { k, v -> v instanceof Map && ((Map) v).id == r.device }?.key as String
if (key == null) return "${r.command} ${tlDevice(doc, r.device)}".toString()
List lines = renderActions(doc, [[type: 'command', ref: key, command: r.command, args: intent?.args ?: []]])
return lines ? (lines[0] as String).replaceFirst(/^\s*1\. /, '') : "${r.command} ${tlDevice(doc, r.device)}".toString()
}
List tlFailedConditions(Map doc, List c) {
List out = []
((List) (c ?: [])).each { Object o ->
List kv = (o as String).split('=') as List
if (kv.size() != 2 || !(kv[1] in ['F', 'U'])) return
Object node = tlNode(doc, kv[0] as String)
if (!(node instanceof Map) || ((Map) node).type in ['all', 'any', 'not', 'xor', 'count']) return
out << "${renderCondition(doc, (Map) node)} ${kv[1] == 'F' ? 'was false' : 'could not be read'}".toString()
}
return out
}
String tlCause(Map doc, List entries) {
Map started = entries.find { ((Map) it).w == 'runStarted' }
if (started?.cause == 'manual') return 'Run now was pressed'
if (started?.cause == 'ruleInvocation') return 'another rule ran it'
if (started?.cause == 'endpoint') return 'its end point was called'
if (started?.cause == 'later') return 'an earlier run set these steps aside for later'
Map a = entries.find { ((Map) it).w in ['accepted', 'scheduleFired'] }
if (a == null) return 'a cause that is no longer in the trace'
if (a.w == 'scheduleFired') {
Map t = ((List) (doc?.triggers ?: []))[(a.trigger ?: 0) as int]
return t == null ? 'its schedule' : renderTrigger(doc, t)
}
if (a.kind == 'mode') return "the mode changed to ${a.value}".toString()
if (a.kind == 'button') return "button ${a.number} on ${tlDevice(doc, a.device)} was ${a.action}".toString()
return "${tlDevice(doc, a.device)} ${a.attribute} became ${a.value}".toString()
}
Map tlRun(Map doc, Map runTrace, Map ledger, Closure fmt) {
List entries = rtRunEntries(runTrace)
Map start = entries.find { ((Map) it).w == 'runStarted' }
Map end = entries.find { ((Map) it).w in ['runEnded', 'runCancelled', 'runError'] }
List steps = []
entries.findAll { ((Map) it).w == 'requested' }.each { Object o ->
Map r = o
Map i = ledger?.get(r.intent)
String outcome = i == null ? 'outcome no longer kept' : (TL_OUTCOMES[i.state] ?: i.state)
if (i?.confirmedBy == 'read') outcome = 'confirmed by reading the device back'
steps << "${fmt(r.t as long)}: ${tlCommand(doc, r, i)}: ${outcome}".toString()
}
if (entries.any { ((Map) it).w == 'truncated' }) steps << 'Some steps in the middle of this run are no longer kept.'
String how = end == null ? 'still running' :
end.w == 'runEnded' ? (end.how == 'completed' ? 'finished' : "ended (${end.how})".toString()) :
end.w == 'runCancelled' ? 'was cancelled' : "stopped on an error: ${end.message ?: end.code}".toString()
long at = (start?.t ?: entries.find { ((Map) it).t != null }?.t ?: 0L) as long
return [run: runTrace.run, at: at, cause: tlCause(doc, entries), steps: steps, how: how,
text: "${fmt(at)}: ran because ${tlCause(doc, entries)}; ${how}.".toString()]
}
Map tlFreshNames(Map doc, Map names) {
if (doc == null || !names) return doc
Map refs = (doc.refs instanceof Map ? doc.refs : [:])
if (!refs.any { k, v -> v instanceof Map && ((Map) v).kind == 'device' && names.containsKey(((Map) v).id) && names.get(((Map) v).id) != ((Map) v).name }) return doc
Map out = new LinkedHashMap(doc)
Map fresh = [:]
refs.each { k, v ->
if (v instanceof Map && ((Map) v).kind == 'device' && names.containsKey(((Map) v).id)) {
Map r = new LinkedHashMap((Map) v)
r.name = names.get(r.id)
fresh.put(k, r)
} else fresh.put(k, v)
}
out.refs = fresh
return out
}
String tlNotAdmitted(String why) {
switch (why) {
case 'requiredExpression': return 'its required expression was false'
case 'triggerCondition': return "the trigger's own condition was false"
default: return "the rule was ${why}".toString()
}
}
List tlTimeline(Map doc, Map trace, Map ledger, Closure fmt, Map older = null) {
List items = []
((List) (trace?.runs ?: [])).each { Object o ->
Object ep = older ? ((Map) rtRunEntries((Map) o).find { ((Map) it).w == 'runStarted' })?.epoch : null
Map r = tlRun((Map) (ep != null ? (older.get(ep as String) ?: doc) : doc), o, ledger, fmt)
items << [t: r.at, kind: 'run', text: r.text, steps: r.steps]
}
((List) (trace?.rejections ?: [])).each { Object o ->
Map j = o
String why = TL_REFUSALS[j.what] ?: (j.what as String)
if (j.what == 'notAdmitted' && j.why) why = tlNotAdmitted(j.why as String)
List failed = tlFailedConditions(doc, j.c)
String by = j.cause ? " (${j.cause} trigger)" : ''
items << [t: j.t, kind: 'notRun', text: "${fmt(j.t as long)}: triggered${by} but did not run: ${why}${failed ? ': ' + failed.join('; ') : ''}.".toString()]
}
((List) (trace?.notes ?: [])).each { Object o ->
Map n = o
if (!(n.w in ['committed', 'paused', 'resumed', 'disabled', 'enabled', 'timerRecoveryExceeded'])) return
String what = [committed: "version ${n.epoch} of the rule became active", paused: 'paused', resumed: 'resumed',
disabled: 'deactivated', enabled: 'enabled again',
timerRecoveryExceeded: 'a timer was found too late to act on'].get(n.w)
items << [t: n.t, kind: 'note', text: "${fmt(n.t as long)}: ${what}.".toString()]
}
return items.findAll { it.t != null }.sort { a, b -> (b.t as long) <=> (a.t as long) }
}
@Field static final int TL_SUMMARY_RULES = 8
@Field static final int TL_SUMMARY_ITEMS = 5
@Field static final int TL_SUMMARY_CHARS = 180
boolean tlInvocable(Map doc) {
if (doc == null) { return false }
return ((List) (doc.triggers ?: [])).any { Object t ->
t instanceof Map && ((Map) t).kind == 'ruleInvocation' && ((Map) t).disabled != true
}
}
String tlHubPower(Map doc) {
if (doc == null) return null
String found = null
Closure walk
walk = { Object x ->
if (x instanceof List) { ((List) x).each { walk(it) }; return }
if (!(x instanceof Map) || ((Map) x).disabled == true) return
Map m = x
if (m.type == 'hubShutdown') found = 'shutdown'
else if (m.type == 'hubReboot' && found == null) found = 'reboot'
m.each { k, v -> if (k != 'refs' && k != 'condition' && (v instanceof Map || v instanceof List)) walk(v) }
}
walk([doc.actions, doc.onFailure, doc.onCancel])
return found
}
List tlNewestFirst(Collection entries) {
return new ArrayList(entries).sort { Object o -> -((((Map) o).updatedAt ?: 0L) as long) }
}
Map tlSummaryRow(Map row, Closure fmt, boolean detailed) {
Map e = row.entry
Map s = row.state
List recent = s == null || !detailed ? [] : tlTimeline((Map) (s.doc ?: e.doc), s.trace, s.ledger, fmt, row.older)
.take(TL_SUMMARY_ITEMS).collect { Object it -> ((it as Map).text as String).take(TL_SUMMARY_CHARS) }
Map out = [id: e.ruleId, name: row.name, state: e.state, version: e.epoch, ruleHash: ((e.ruleHash ?: '') as String).take(12),
paused: s?.paused ?: false, readable: s != null, invocable: tlInvocable((Map) (s?.doc ?: e.doc)), recent: recent]
Map last = tlLastRun(s)
if (last != null) out.putAll(last)
String power = tlHubPower((Map) (s?.doc ?: e.doc))
if (power != null) out.hubPower = power
return out
}
Map tlLastRun(Map s) {
List runs = (((Map) (s?.trace ?: [:])).runs ?: [])
if (runs.isEmpty()) return null
List entries = rtRunEntries((Map) runs[-1])
Map start = entries.find { ((Map) it).w == 'runStarted' }
Map err = entries.find { ((Map) it).w == 'runError' }
Long at = (start?.t ?: entries.find { ((Map) it).t != null }?.t) as Long
String problem = err != null ? "it stopped on an error: ${err.message ?: err.code}".toString() : null
if (problem == null) {
Map ledger = (s.ledger ?: [:])
int failed = entries.count { Object o -> ((Map) o).w == 'requested' && ((Map) (ledger.get(((Map) o).intent) ?: [:])).state == 'failed' } as int
if (failed > 0) problem = "${failed == 1 ? 'a command' : failed + ' commands'} it sent failed".toString()
}
Map out = [:]
if (at != null) out.lastRunAt = at
if (problem != null) out.lastRunProblem = problem.take(TL_SUMMARY_CHARS)
return out
}
Map tlSummaryRefresh(Collection entries, Map cacheIn, Collection refresh, boolean cacheOnly, Map lights,
Closure read, Closure fmt, Closure nameOf) {
Map cache = new LinkedHashMap(cacheIn ?: [:])
Set live = entries.collect { ((Map) it).ruleId as String } as Set
new ArrayList(cache.keySet()).each { if (!live.contains(it)) cache.remove(it) }
List newest = tlNewestFirst(entries)
Set detailed = newest.take(TL_SUMMARY_RULES).collect { ((Map) it).ruleId as String } as Set
int reads = 0
List rows = []
newest.each { Object eo ->
Map e = eo
String id = e.ruleId as String
Map c = cache.get(id)
boolean moved = c == null || c.epoch != e.epoch || c.state != e.state || c.ruleHash != e.ruleHash
boolean d = detailed.contains(id)
boolean joined = d && c != null && c.detailed != true
if (!cacheOnly && (moved || joined || refresh?.contains(id))) {
Map got = read.call(e, d)
if (got != null) {
Map row = tlSummaryRow([entry: e, state: got.state, name: nameOf.call(e), older: d ? (got.older ?: [:]) : [:]], fmt, d)
c = [epoch: e.epoch, state: e.state, ruleHash: e.ruleHash, mark: ((Map) lights?.get(id))?.mark ?: c?.mark, detailed: d, row: row]
cache.put(id, c)
reads++
}
} else if (c != null && !d && c.detailed != false) {
c = new LinkedHashMap(c); c.row = ((Map) c.row) + [recent: []]; c.detailed = false; cache.put(id, c)
}
Map base = c == null ? tlSummaryRow([entry: e, state: null, name: nameOf.call(e)], fmt, false) : c.row
rows << (base + [name: nameOf.call(e), state: e.state, version: e.epoch, ruleHash: ((e.ruleHash ?: '') as String).take(12),
changedAt: e.updatedAt])
}
return [cache: cache, rows: rows, reads: reads]
}
String tlRecentWhyNot(Map entry, Map view) {
if (entry == null) return 'not-in-runtime'
if (view == null) return 'no-answer'
if ((view.ruleId as String) != (entry.ruleId as String)) return 'other-rule'
if ((view.epoch as String) != (entry.epoch as String)) return 'version-moving'
String vh = (view.ruleHash ?: '') as String, eh = (entry.ruleHash ?: '') as String
if (vh && eh && vh != eh) return 'version-moving'
return null
}
Map tlRecentLine(Map entry, Map view, Closure fmt, String name, long now) {
if (tlRecentWhyNot(entry, view) != null) return null
Map older = [:]
((Map) (view.docs ?: [:])).each { k, v -> if ((k as String) != (view.epoch as String) && v instanceof Map) older.put(k as String, v) }
Map row = tlSummaryRow([entry: entry, state: [doc: view.doc, trace: view.trace, ledger: view.ledger, paused: view.paused],
name: name, older: older], fmt, true)
return row + [changedAt: entry.updatedAt, readAt: now]
}
Map tlSumCacheJoin(Map marks, List rows) {
Map byId = [:]
((List) (rows ?: [])).each { Object o -> if (o instanceof Map && ((Map) o).id != null) byId.put(((Map) o).id as String, o) }
Map out = new LinkedHashMap()
((Map) (marks ?: [:])).each { Object k, Object v ->
if (!(v instanceof Map)) return
Map c = v
Object row = byId.get(k as String) ?: c.row
if (row instanceof Map) out.put(k as String, new LinkedHashMap(c) + [row: row])
}
return out
}
Map tlSumCacheMarks(Map cache) {
Map out = new LinkedHashMap()
((Map) (cache ?: [:])).each { Object k, Object v -> Map c = new LinkedHashMap((Map) v); c.remove('row'); out.put(k, c) }
return out
}
Map tlPruneChildLinks(Map links, Collection liveChildIds) {
Set live = liveChildIds.findAll { it != null }.collect { it as String } as Set
return (links ?: [:]).findAll { k, v -> live.contains(k as String) }
}
Map tlSummaryCompose(List rules, long now) {
return [v: 1, at: now, rules: rules, digest: jsonHash([rules: rules]).take(16)]
}
@Field static final String AM_CONTRACT = 'hai.am/1'
@Field static final String AM_CAPS_FILE = 'hai-am-capabilities.json'
@Field static final Map AM_EVIDENCE_MEANINGS = [
hub : 'HAI executed on the hub and the expected result was observed on the date given. Virtual and physical hub devices count equally. This establishes the observed mechanism, not every parameter variant or device model.',
simulated: 'Checked using modelled state or command effects, without observed HAI runtime execution for this row. Actual execution against a virtual hub device is hub-tested, not simulation.',
]
@Field static final Map AM_STATUS_MEANINGS = [
Runs : 'Implementation status, not a claim that this row has been watched on a hub: built, checked, read back and simulated, and not refused by the runtime. The evidence field says whether anyone has seen it run. Rows share mechanisms - several features run through one command, subscription or evaluation path - so the number of hub-observed rows is not a completeness score.',
Format : 'Can be written, will not act. A rule can use it and HAI checks and simulates it, but the runtime deliberately refuses to carry it out yet; the note says why.',
Partial: 'Some of it works. The note says what is missing.',
Missing: 'Not built. HAI has no equivalent; the note says why.',
Scoped : 'Not built, and the route is known. The parts already exist and the note says what it would be assembled from. Nothing here works yet.',
]
@Field static final String AM_CAPS_SHA256 = '6e03a4942ac9e395e5242f0d4182e2c1904a17b80fdf31b5215c7344cfd8f1e3'
@Field static final Map AM_FIELDS = [
'trigger:deviceAttribute' : [ref: 'read', value: 'value', condition: 'cond'],
'trigger:threshold' : [ref: 'read', value: 'value', condition: 'cond'],
'trigger:duration' : [predicate: 'cond', condition: 'cond'],
'trigger:button' : [ref: 'read', condition: 'cond'],
'trigger:schedule' : [at: 'value', offsetMinutes: 'value', condition: 'cond'],
'trigger:periodic' : [condition: 'cond'],
'trigger:mode' : [condition: 'cond'],
'trigger:hubVariable' : [ref: 'read', condition: 'cond'],
'trigger:ruleInvocation' : [condition: 'cond'],
'trigger:rulePaused' : [condition: 'cond'],
'trigger:endpoint' : [condition: 'cond'],
'trigger:hsmStatus' : [condition: 'cond'],
'trigger:hsmAlert' : [condition: 'cond'],
'trigger:locationEvent' : [condition: 'cond'],
'condition:hsmStatus' : [:],
'action:hsm' : [:],
'condition:all' : [of: 'cond'],
'condition:any' : [of: 'cond'],
'condition:not' : [of: 'cond'],
'condition:xor' : [of: 'cond'],
'condition:count' : [of: 'cond', min: 'value', max: 'value'],
'condition:deviceAttribute': [ref: 'read', refs: 'read', value: 'value'],
'condition:variable' : [ref: 'read', value: 'value'],
'condition:mode' : [:],
'condition:timeWindow' : [from: 'value', to: 'value', fromOffsetMinutes: 'value', toOffsetMinutes: 'value'],
'condition:triggeredBy' : [:],
'condition:rulePaused' : [:],
'condition:waitOutcome' : [:],
'condition:dayOfWeek' : [:],
'condition:dateRange' : [from: 'value', to: 'value'],
'condition:timeSince' : [ref: 'read'],
'action:command' : [ref: 'command', refs: 'command', args: 'value'],
'action:setVariable' : [ref: 'write', args: 'value'],
'action:wait' : [ref: 'read', predicate: 'cond', seconds: 'value', randomSeconds: 'value', events: 'events'],
'action:if' : [condition: 'cond', then: 'steps', elseIf: 'steps', otherwise: 'steps'],
'action:runRule' : [ref: 'rules', result: 'write'],
'action:exit' : [:],
'action:comment' : [:],
'action:setRuleBoolean' : [rules: 'rules'],
'action:controlRule' : [rules: 'rules'],
'action:return' : [ref: 'monitor'],
'action:log' : [:],
'action:setMode' : [:],
'action:customCommand' : [ref: 'command', args: 'value'],
'action:capture' : [refs: 'monitor'],
'action:restore' : [refs: 'command'],
'action:repeat' : [condition: 'cond', everySeconds: 'value', times: 'value', steps: 'steps'],
'action:stopRepeat' : [:],
'action:later' : [seconds: 'value', randomSeconds: 'value', actions: 'steps'],
'action:cancelDelays' : [:],
'action:http' : [url: 'text', body: 'text', result: 'write'],
'action:ping' : [host: 'text', into: 'write'],
'action:file' : [name: 'text', text: 'text'],
'action:byMode' : [cases: 'steps', otherwise: 'steps'],
'action:modeCase' : [then: 'steps'],
'action:flashSwitches' : [refs: 'command'],
'action:fade' : [refs: 'command'],
'action:stopGradual' : [:],
'action:track' : [refs: 'command'],
'action:deviceEnabled' : [refs: 'command'],
'action:hubReboot' : [:],
'action:hubShutdown' : [:],
]
@Field static final Map AM_RULE_LINKS = [runRule: 'runs', setRuleBoolean: 'setspb', pause: 'pauseResume', resume: 'pauseResume',
cancelTimers: 'cancelTimedActions', runActions: 'runs']
Map amFeed(List rows, Map names, List caps, long now, Map connectors = null) {
Map apps = [:]
rows.each { Map r -> apps.put(r.ruleId, r.appId) }
Map st = [nodes: new LinkedHashMap(), edges: new LinkedHashMap(), apps: apps, names: names ?: [:], connectors: connectors ?: [:]]
Map flows = new LinkedHashMap()
rows.each { Map r ->
String app = "a${r.appId}".toString()
String status = r.state == 'disabled' ? 'stopped' : (r.doc instanceof Map && ((Map) r.doc).enabled == false) ? 'disabled' : r.paused ? 'paused' : 'active'
st.nodes.put(app, [id: app, ruleId: r.ruleId, label: r.name ?: r.ruleId, group: 'app', engine: 'HAI', status: status,
disabled: status in ['stopped', 'disabled'], url: "/installedapp/configure/${r.appId}".toString()])
if (r.doc instanceof Map) flows.put(app, amFlow(st, app, tlFreshNames((Map) r.doc, st.names)))
}
List edges = ((Map) st.edges).values().collect { Map e -> e.unused == false ? e.findAll { k, v -> k != 'unused' } : e }
Map out = [contract: AM_CONTRACT, engine: RT_ENGINE, haiVersion: RT_VERSION,
capabilitiesHash: caps == null ? null : AM_CAPS_SHA256,
capabilityIdsHash: caps == null ? null : jsonHash((caps.collect { ((Map) it).id as String }).sort()),
generatedAt: now, nodes: ((Map) st.nodes).values() as List,
edges: edges, flows: flows, capabilities: caps ?: [], statusMeanings: AM_STATUS_MEANINGS, evidenceMeanings: AM_EVIDENCE_MEANINGS]
if (caps == null) out.capabilitiesError = "${AM_CAPS_FILE} in the File Manager is missing or not the one this HAI Runtime was built with; upload it again".toString()
return out
}
List amFlow(Map st, String app, Map doc) {
List flow = []
((List) (doc.triggers ?: [])).each { Object t ->
if (!(t instanceof Map)) return
Map c = [st: st, doc: doc, app: app, where: 'trigger', off: ((Map) t).disabled == true, devs: []]
amWalk(c, "trigger:${((Map) t).kind}".toString(), t)
flow << amStep('trigger', (c.off ? '(Disabled) ' : '') + renderTrigger(doc, t), c.devs)
}
[[doc.conditions, 'Only if '], [doc.requiredExpression, 'Required expression: ']].each { List g ->
if (!(g[0] instanceof Map)) return
Map c = [st: st, doc: doc, app: app, where: 'required-expression', off: false, devs: []]
amCond(c, g[0])
flow << amStep('required', g[1] + renderCondition(doc, g[0]), c.devs)
}
amSteps(st, doc, app, (doc.actions ?: []), false, flow)
if (doc.errorPolicy == 'failurePath' && doc.onFailure instanceof List) {
flow << amStep('action', 'a step fails', [], 'if', 'a step fails')
amSteps(st, doc, app, doc.onFailure, false, flow)
flow << amStep('action', 'endif', [], 'endif')
}
return flow
}
Map amStep(String kind, String label, List devs, String ctrl = null, String cond = '', List targets = [], String varField = null) {
return [kind: kind, label: label, devices: devs.unique(), ctrl: ctrl, cond: cond, ruleTargets: targets, variableField: varField]
}
void amSteps(Map st, Map doc, String app, List actions, boolean off, List flow) {
actions.each { Object ao ->
if (!(ao instanceof Map)) return
Map a = ao
boolean aOff = off || a.disabled == true
Closure ctx = { [st: st, doc: doc, app: app, where: 'condition', off: aOff, devs: [], targets: [], vars: []] }
if (a.type == 'if') {
String skip = aOff ? '(Disabled, skipped) ' : ''
Map c = ctx()
amCond(c, a.condition)
String cond = renderCondition(doc, a.condition)
flow << amStep('action', skip + cond, c.devs, 'if', cond)
amSteps(st, doc, app, (a.then ?: []), aOff, flow)
((List) (a.elseIf ?: [])).each { Object eo ->
Map e = eo
Map ce = ctx()
amCond(ce, e.condition)
String econd = renderCondition(doc, e.condition)
flow << amStep('action', skip + econd, ce.devs, 'elseif', econd)
amSteps(st, doc, app, (e.then ?: []), aOff, flow)
}
if (a.otherwise) {
flow << amStep('action', 'else', [], 'else')
amSteps(st, doc, app, a.otherwise, aOff, flow)
}
flow << amStep('action', 'endif', [], 'endif')
return
}
if (a.type == 'byMode') {
String skip = aOff ? '(Disabled, skipped) ' : ''
((List) (a.cases ?: [])).eachWithIndex { Object co, int j ->
Map mc = co
String cond = renderModeCase(mc.modes).replaceFirst('When the mode', 'the mode')
flow << amStep('action', skip + cond, [], j == 0 ? 'if' : 'elseif', cond)
amSteps(st, doc, app, (mc.then ?: []), aOff, flow)
}
if (a.otherwise) {
flow << amStep('action', 'else', [], 'else')
amSteps(st, doc, app, a.otherwise, aOff, flow)
}
flow << amStep('action', 'endif', [], 'endif')
return
}
Map c = ctx()
if (a.type != 'repeat') c.where = 'action'
String key = "action:${a.type}".toString()
List nested = a.type == 'repeat' ? [a.steps ?: []] : a.type == 'later' ? [a.actions ?: []] : AM_FIELDS.containsKey(key) ? [] : a.values().findAll { v -> v instanceof List && ((List) v).any { it instanceof Map && ((Map) it).type } }
amWalk(c, key, a)
if (nested) {
String cond = a.condition instanceof Map ? renderCondition(doc, a.condition) : ''
String head = a.type == 'later' ? (renderActions(doc, [a])[0] as String).substring(3) : "${a.type} (loop)".toString()
flow << amStep('action', head, c.devs, null, cond, c.targets)
nested.each { amSteps(st, doc, app, it, aOff, flow) }
flow << amStep('action', "end ${a.type}".toString(), [])
return
}
String line = (renderActions(doc, [a])[0] as String).substring(3)
if (off && a.disabled != true) line = '(Disabled, skipped) ' + line
flow << amStep('action', line, c.devs, null, '', c.targets, a.type == 'setVariable' && c.vars ? c.vars[0] as String : null)
}
}
void amCond(Map c, Object cond) {
if (cond instanceof List) { ((List) cond).each { amCond(c, it) }; return }
if (cond instanceof Map) amWalk(c, "condition:${((Map) cond).type}".toString(), cond)
}
void amWalk(Map c, String key, Map node) {
((Map) (AM_FIELDS.get(key) ?: [:])).each { String field, String role ->
Object v = node.get(field)
if (v == null) return
switch (role) {
case 'cond': amCond(c, v); break
case 'value': amValue(c, v); break
case 'rules':
String kind = node.type == 'controlRule' ? AM_RULE_LINKS.get(node.op) : AM_RULE_LINKS.get(node.type)
(v instanceof List ? v : [v]).each { Object k ->
String to = amRuleNode(c, k)
if (to == null || kind == null) return
c.targets << to
amEdge(c, to, kind)
}
break
case 'steps': break
case 'events': if (v instanceof List) ((List) v).each { Object e -> if (e instanceof Map) amWalk(c, "trigger:${((Map) e).kind}".toString(), e) }; break
case 'text':
if (!(v instanceof String)) break
java.util.regex.Matcher m = ((String) v) =~ /%([^%\s][^%]{0,63})%/
while (m.find()) {
String name = m.group(1)
Object k = ((Map) (c.doc.refs ?: [:])).find { rk, rv -> rv instanceof Map && ((Map) rv).kind == 'variable' && ((Map) rv).name == name }?.key
if (k != null) amRef(c, k, 'value', node)
}
break
default: (v instanceof List ? v : v instanceof Map ? ((Map) v).values() : [v]).each { Object k -> amRef(c, k, role, node) }
}
}
}
void amValue(Map c, Object v) {
if (v instanceof List) ((List) v).each { amValue(c, it) }
else if (v instanceof Map && ((Map) v).ref != null) amRef(c, ((Map) v).ref, 'value', null)
}
void amRef(Map c, Object key, String role, Map node) {
Map ref = ((Map) (c.doc.refs ?: [:])).get(key)
if (ref == null) return
Object hv = ref.kind == 'device' ? ((Map) c.st.connectors).get(ref.id) : null
if (hv != null) {
c.devs << (c.st.names.get(ref.id) ?: ref.name ?: ref.id)
ref = [kind: 'variable', id: hv]
if (role == 'command') role = 'write'
}
if (ref.kind == 'device') {
String id = ref.id as String
String to = 'd' + id.replaceFirst(/^dev:/, '')
c.devs << (c.st.names.get(id) ?: ref.name ?: id)
amNode(c, to, c.st.names.get(id) ?: ref.name ?: id, 'device')
if (role == 'command') {
String cmd = node.type == 'restore' ? 'restore' : (node.command ?: node.type) as String
Map op = CATALOGUE.get(cmd)
boolean lasting = node.type in ['restore', 'fade', 'flashSwitches', 'track'] || (node.type == 'command' && op != null && op.shape != 'none')
amEdge(c, to, 'action', [stateful: lasting, commands: [cmd]])
} else if (role == 'read') amEdge(c, to, c.where == 'trigger' ? 'trigger' : 'constraint')
else amEdge(c, to, 'monitor')
} else if (ref.kind == 'variable') {
String id = ref.id as String
boolean hub = id.startsWith(HUB_VARIABLE_PREFIX)
String name = hub ? id.substring(HUB_VARIABLE_PREFIX.length()) : id.replaceFirst(/^rv:/, '')
String to = hub ? "v${name}".toString() : "${c.app}:${name}".toString()
amNode(c, to, hub ? name : (ref.name ?: name), hub ? 'hubVariable' : 'localVariable')
if (role == 'write') { c.vars << to; amEdge(c, to, 'write'); return }
String use = role in ['value', 'monitor'] ? 'value-source' : c.where == 'trigger' ? 'trigger' : c.where == 'required-expression' ? 'required-expression' : 'condition'
amEdge(c, to, 'read', [usageRole: use])
}
}
void amNode(Map c, String id, Object label, String group) {
if (!((Map) c.st.nodes).containsKey(id)) c.st.nodes.put(id, [id: id, label: label as String, group: group])
}
String amRuleNode(Map c, Object key) {
Map ref = ((Map) (c.doc.refs ?: [:])).get(key)
if (ref?.kind == 'self') return c.app
if (ref?.kind == 'rmRule' && ref.id != null) {
String to = "a${ref.id}".toString()
amNode(c, to, ref.name ?: ref.id, 'app')
return to
}
Object appId = ref?.kind == 'rule' ? c.st.apps.get(ref.id) : null
return appId == null ? null : "a${appId}".toString()
}
void amEdge(Map c, String to, String kind, Map extra = [:]) {
if (c.off && kind != 'constraint') return
String k = "${c.app}|${to}|${kind}".toString()
Map e = c.st.edges.get(k)
if (e == null) {
e = [from: c.app, to: to, kind: kind]
if (kind == 'constraint') e.unused = c.off
e.putAll(extra)
c.st.edges.put(k, e)
return
}
if (kind == 'constraint' && !c.off) e.unused = false
if (kind == 'action') {
e.stateful = e.stateful || extra.stateful
e.commands = ((List) e.commands + extra.commands).unique()
}
}
List amCapabilities() {
byte[] b = null
try { b = downloadHubFile(AM_CAPS_FILE) } catch (Exception e) { b = null }
String h = b == null ? null : MessageDigest.getInstance('SHA-256').digest(b).collect { String.format('%02x', it) }.join('')
if (h != AM_CAPS_SHA256) return null
return new groovy.json.JsonSlurper().parseText(new String(b, 'UTF-8'))
}
@Field static final String HAM_CONTRACT = 'ham.decode/1'
@Field static final String HAM_DETAIL_CONTRACT = 'ham.decode.detail/1'
@Field static final List HAM_SCHEMAS = [1, 2, 3, 4]
@Field static final int HAM_SCHEMA = 3
@Field static final int HAM_VOCABULARY = 2
String hamAmChannel(String channel) {
if (channel == null) return null
return channel in ['production', 'preview'] ? 'production' : 'dev'
}
String hamDetailFileFor(String channel) {
return hamAmChannel(channel) == 'production' ? 'ham-decode-detail.json' : 'ham-decode-detail-dev.json'
}
Map hamDetail(Map plat, String channel) {
return hamRead(plat, channel, HAM_DETAIL_CONTRACT, hamDetailFileFor(channel))
}
Map hamRead(Map plat, String channel, String wantContract, String name) {
Closure read = (Closure) (plat ?: [:]).get('readLocalFile')
if (read == null) return hamNo('HAM_NO_READER', 'This build cannot read the hub\'s File Manager.')
Object raw = null
try {
raw = read(name)
} catch (Exception e) {
return hamNo('HAM_ABSENT', "Automation Map's decode (${name}) could not be read: ${e.message}".toString())
}
String text = raw == null ? null : raw.toString().trim()
if (text == null || text.isEmpty()) {
return hamNo('HAM_ABSENT', "Automation Map has not written ${name} on this hub.".toString())
}
if (!text.startsWith('{')) {
return hamNo('HAM_ABSENT', "Automation Map has not written ${name} on this hub; the hub answered with a page instead.".toString())
}
if (!text.endsWith('}')) {
return hamNo('HAM_UNREADABLE',
("Automation Map's ${name} stops part way through: it starts as JSON and never " +
'finishes. The write was cut short rather than the file being out of date, so what ' +
'is in it cannot be trusted as a whole.').toString())
}
Map doc = null
try {
doc = new groovy.json.JsonSlurper().parseText(text)
} catch (Exception e) {
return hamNo('HAM_UNREADABLE', "Automation Map's decode could not be read as JSON: ${e.message}".toString())
}
if (!(doc instanceof Map)) return hamNo('HAM_UNREADABLE', 'Automation Map\'s decode is not an object.')
if (doc.contract != wantContract) {
return hamNo('HAM_CONTRACT', ("This is ${doc.contract ?: 'an unnamed contract'}; HAI reads ${wantContract}. " +
'Automation Map and HAI need to be the same generation.').toString())
}
if (!HAM_SCHEMAS.contains(doc.contractSchemaVersion as Integer)) {
return hamNo('HAM_SCHEMA', ("Automation Map writes contract schema ${doc.contractSchemaVersion}; HAI reads ${HAM_SCHEMAS.join(' or ')}. " +
'Reading the parts that look familiar is how a field changes meaning underneath a consumer, ' +
'so none of it is read.').toString())
}
if (!((doc.rmConstructVocabularyVersion as Integer) in [1, HAM_VOCABULARY])) {
return hamNo('HAM_VOCABULARY', ("Automation Map describes Rule Machine with vocabulary ${doc.rmConstructVocabularyVersion}; " +
"HAI understands ${HAM_VOCABULARY}. The same token can mean something else between versions.").toString())
}
Map inst = (doc.instance ?: [:])
String theirs = inst.buildChannel as String
if (theirs != null && channel != null && theirs != hamAmChannel(channel)) {
return hamNo('HAM_CHANNEL', ("This decode came from the ${theirs} Automation Map and HAI is ${channel}. " +
'Both are installed on this hub and they hold different estates.').toString(),
[instance: inst])
}
if (doc.ok == false) {
String issue = doc.issue as String
return hamNo(issue == 'no-scan' ? 'HAM_NO_SCAN' : 'HAM_REFUSED',
((doc.message as String) ?: "Automation Map answered without a decode (${issue ?: 'no reason given'}).").toString(),
[instance: inst, issue: issue])
}
Map scan = (doc.scan ?: [:])
String status = scan.status as String
if (status == 'failed') {
return hamNo('HAM_SCAN_FAILED', 'Automation Map\'s last scan failed, so what it holds is not a reading of this hub.',
[instance: inst, scan: scan])
}
List rules = (doc.rules instanceof List) ? doc.rules : null
if (rules == null) return hamNo('HAM_UNREADABLE', 'Automation Map\'s decode carries no rule list.', [instance: inst, scan: scan])
if ((doc.contractSchemaVersion as Integer) >= 2 &&
(doc.complete != true || !(doc.ruleCount instanceof Number) || (doc.ruleCount as int) != rules.size())) {
return hamNo('HAM_UNREADABLE',
("Automation Map's ${name} does not end as a whole document does: " +
(doc.complete == true ? "it says ${doc.ruleCount} rules and holds ${rules.size()}." : 'its closing marker is missing.') +
' The write was cut short, so what it holds cannot be trusted as a whole.').toString(),
[instance: inst, scan: scan])
}
Object stale = scan.staleAfterSeconds
Map out = [ok: true, partial: status == 'complete-with-gaps', scanStatus: status,
generatedAt: scan.lastScanCompletedAt, instance: inst, scan: scan,
writtenAt: doc.generatedAt as String,
staleAfterSeconds: (stale instanceof Number) ? (stale as long) : null,
autoScanEnabled: scan.autoScanEnabled == true,
schema: doc.contractSchemaVersion as Integer,
hubVariables: (doc.hubVariables instanceof Map) ? doc.hubVariables : null,
rules: rules, count: rules.size()]
if ((doc.contractSchemaVersion as Integer) >= 4 && doc.disabledDevices instanceof List) {
out.disabledDevices = ((List) doc.disabledDevices).collect { it as String }
}
return out
}
Map rmDevicesNamed(Map detailRule) {
Map out = [:]
if (detailRule == null) return out
Closure take = { Object o ->
Map ops = (((Map) o).operands ?: [:])
List devs = (ops.devices instanceof List) ? ops.devices : []
if (!devs.isEmpty() && devs.every { it instanceof Map }) {
devs.each { Object d -> if (((Map) d).id != null) out.put(((Map) d).id as String, (((Map) d).name as String) ?: (((Map) d).id as String)) }
return
}
List ids = (ops.deviceIds instanceof List) ? ops.deviceIds : []
List names = devs
ids.eachWithIndex { Object id, int i ->
out.put(id as String, (i < names.size() ? names[i] as String : null) ?: (id as String))
}
}
((List) (detailRule.conditions ?: [])).each(take)
((List) (detailRule.actions ?: [])).each { Object o ->
Map ops = (((Map) o).operands ?: [:])
['devices', 'notify', 'speak'].each { String key ->
if (!(ops[key] instanceof List)) return
((List) ops[key]).each { Object d ->
if (!(d instanceof Map)) return
String id = ((Map) d).id as String
if (id != null) out.put(id, (((Map) d).name as String) ?: id)
}
}
}
((List) (detailRule.triggers ?: [])).each { Object o ->
Map ops = (((Map) o).operands ?: [:])
if (!(ops.devices instanceof List)) return
((List) ops.devices).each { Object d ->
if (!(d instanceof Map)) return
String id = ((Map) d).id as String
if (id != null) out.put(id, (((Map) d).name as String) ?: id)
}
}
((List) (detailRule.steps ?: [])).each { Object so ->
((List) (((Map) so).devices ?: [])).each { Object n -> out.putIfAbsent(n as String, n as String) }
}
return out
}
Map hamRule(Map decode, Object ruleId) {
if (decode == null || decode.ok != true) return null
String want = ruleId == null ? null : ruleId.toString().trim()
if (want == null || want.isEmpty()) return null
String bare = want.startsWith('a') ? want.substring(1) : want
String prefixed = 'a' + bare
return ((List) decode.rules).find { Object o ->
String id = ((Map) o).id as String
return id == want || id == prefixed || id == bare
}
}
Map hamNo(String code, String message, Map extra = [:]) {
Map m = [ok: false, code: code, message: message]
m.putAll(extra ?: [:])
return m
}
@Field static final String CHECKER_BUILD = '2d66394aabfc6f43'
