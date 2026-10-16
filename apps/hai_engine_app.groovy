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

@Field static final String HAI_CHANNEL = 'dev'
definition(
name: 'HAI Engine (Preview)',
namespace: 'gordonthelander',
author: 'HAI',
description: 'Writes, checks, simulates and runs automation rules. One app; each rule gets its own child.',
category: 'Automations',
menu: 'Automations',
iconUrl: '',
iconX2Url: '',
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
path('/endpoint/rules/:id/:op') { action: [GET: 'endpointRuleGet', POST: 'endpointRuleGet'] }
path('/endpoint/rules/:id/:op/:value') { action: [GET: 'endpointRuleGet', POST: 'endpointRuleGet'] }
path('/endpoint/variables/:name/:value') { action: [GET: 'endpointVariableGet'] }
}
@Field static final String HAI_ENVIRONMENT = 'PREVIEW'
@Field static final String HAI_COMMUNITY_URL = ''
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
def updated() { engineKeySweep(); haiWeatherApprove(); haiInit(); haiEverydayFollow(); if (state.haiApproveAll instanceof Map && state.haiApproveAll.seen != true) { Map m = new LinkedHashMap((Map) state.haiApproveAll); m.seen = true; state.haiApproveAll = m } }
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
if (ids) log.info "HAI Engine: new install, so all ${ids.size()} devices on the hub are approved; untick any under Approved devices"
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
log.info "HAI Engine: Everyday words: ${wx.displayName} is the weather device, so it is now an approved device"
}
def haiDeviceJoined(evt) {
if (settings.haiAutoApproveNew == false) return
String id = null
try { id = evt?.deviceId != null ? evt.deviceId.toString() : null } catch (Exception e) { id = null }
if (!(id ==~ /[0-9]{1,12}/)) id = ((evt?.value ?: '') as String).trim()
if (!(id ==~ /[0-9]{1,12}/)) { log.info "HAI Engine: a device joined the hub, but its id could not be read, so it was not approved (${evt?.value})"; return }
List have = ((settings.runtimeAllowDevices ?: []) as List).findAll { it != null }.collect { it.id.toString() }
if (have.contains(id)) return
app.updateSetting('runtimeAllowDevices', [type: 'capability', value: have + [id]])
log.info "HAI Engine: device ${id} joined the hub and is now an approved device (Approve new devices automatically is on)"
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
Map f = doc != null ? everydayFollow(doc, everydayValues(haiEverydayLevels())) : null
if (f == null) { res.failed = (res.failed as int) + 1; log.warn "HAI Engine: Everyday words: ${id} could not be read; left as it was" }
else if ((f.changed as int) == 0) {
res.unchanged = (res.unchanged as int) + 1
if (e.index == null || !((Map) e.index).containsKey('everyday')) rtRegistryTransition(pp, id, [e.state as String], e.state as String, [doc: doc])
} else {
Map kept = runtimeKeepText(pp, groovy.json.JsonOutput.toJson(f.doc))
if (kept.kept > 0) { res.updated = (res.updated as int) + 1; log.info "HAI Engine: Everyday words: ${id} updated to the new levels" }
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
runtimePermissionDefaults()
haiAudiencesInit()
engineInit()
runtimeInit()
}
void runtimePermissionDefaults() {
if (state.permissionDefaultsApplied) return
state.permissionDefaultsApplied = true
if (settings.runtimeAllowSetMode == null) app.updateSetting('runtimeAllowSetMode', [type: 'bool', value: true])
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
if (btn == 'engineClearSpendStop') { engineClearSpendStop(); return }
if (btn == 'engineTestApiKey') { engineTestApiKey(); return }
if (btn == 'haiDiagnostics') { haiDiagnosticsOn(); return }
if (btn == 'haiRotateAccess') { haiRotateAccess(); return }
if (btn == 'haiMigrationAdd') {
try { addChildApp('gordonthelander', MIGRATION_CHILD_NAME, 'HAI Migration'); state.remove('haiNotice') }
catch (Exception e) { state.haiNotice = 'HAI Migration is not on this hub. Install it with Hubitat Package Manager (Modify, HAI Engine) or add its app code under Apps Code, then press Turn on HAI Migration again.' }
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
def haiMainPage() {
boolean haiPending = false
try { haiPending = app.getInstallationState() != 'COMPLETE' } catch (Exception e) { haiPending = false }
try { if (state.haiApproveAll == null && haiPending) haiApproveAllOnce() } catch (Exception e) { log.warn "HAI Engine: could not approve devices on install: ${e.message}" }
List haiOthers = haiPending ? haiOtherEngines() : []
dynamicPage(name: 'haiMainPage', title: "HAI Engine ${haiVersion()} (${HAI_ENVIRONMENT})", install: !haiOthers, uninstall: true) {
if (haiOthers) {
section() {
paragraph "<b style='color:#b00020'>HAI Engine is already on this hub (app ${haiOthers.join(', ')}).</b> Do not press Done here: a second HAI Engine would run every rule twice. Remove this one, and open the existing HAI Engine from Apps."
}
}
Map haiAi = haiAiStatus()
if (haiAi.available != true && haiAi.code != 'nokey') {
section() {
paragraph "<b style='color:#b00020'>AI Rule Assistant is off: ${esc(haiAi.why as String)}.</b> ${esc(haiAi.act as String)} under AI token and cost management."
}
}
boolean haiNotYet = false
try { haiNotYet = app.getInstallationState() != 'COMPLETE' } catch (Exception e) { haiNotYet = false }
if (!state.accessToken) { try { createAccessToken() } catch (Exception e) { /* OAuth off: said below */ } }
section('The rule editor') {
if (state.accessToken) {
href name: 'haiEditorHref', title: 'Open the rule editor', required: false,
url: "${getFullLocalApiServerUrl()}/editor?access_token=${state.accessToken}&aud=${haiAudienceSecret('editor')}&v=${EDITOR_PAGE_SHA256.take(12)}".toString(),
description: 'Write, check, simulate and run your rules.'
if (haiNotYet) paragraph '<small>Press Done at the bottom to finish installing. Until then the editor can write and check rules, but not put them on the hub.</small>'
} else {
paragraph 'Enable OAuth for this app code (Apps Code, HAI Engine, OAuth), then open this page again to create the editor link.'
}
if (state.haiNotice) paragraph "<b>${esc(state.haiNotice as String)}</b>"
paragraph haiHelpLine()
}
section('Rule Machine') {
def mig = haiMigrationChild()
if (mig != null) {
href name: 'haiMigrationHref', title: 'Rule Machine Migration Facility', required: false,
url: "/installedapp/configure/${mig.id}".toString(),
description: 'Discover, select, assess and controlled migration of RM rules, with rollback option'
} else if (haiNotYet) {
paragraph 'HAI Migration copies Rule Machine rules into HAI, one at a time. Press Done to finish installing, then turn it on here.'
} else {
paragraph 'HAI Migration copies Rule Machine rules into HAI, one at a time. It needs Automation Map, with a scan done.'
input 'haiMigrationAdd', 'button', title: 'Turn on HAI Migration'
}
}
int haiDeviceCount = ((List) (settings.runtimeAllowDevices ?: [])).size()
Map haiAll = state.haiApproveAll instanceof Map ? state.haiApproveAll : null
if (haiDeviceCount == 0 && haiAll?.count) haiDeviceCount = haiAll.count as int
boolean haiAllNew = haiAll != null && haiAll.seen != true && !haiAll.skipped
if (haiAllNew && haiAll.count) section() { paragraph "<b>HAI can use all ${haiAll.count} devices on this hub.</b> Open Approved devices below to untick any you want to keep it away from, then press Done." }
section(haiPermissionTitle('Approved devices:', "(${haiDeviceCount})"),
hideable: true, hidden: haiDeviceCount > 0) {
if (haiAllNew && haiAll.error) paragraph "<b>HAI could not read this hub's device list (${haiAll.error}), so no device is approved yet.</b> Select the devices your rules may use."
input 'runtimeAllowDevices', 'capability.*', title: 'Devices', multiple: true, required: false, submitOnChange: true
input 'haiAutoApproveNew', 'bool', title: 'Approve new devices automatically', defaultValue: true, submitOnChange: true
paragraph '<small>When this is on, a Z-Wave or Zigbee device you pair with the hub is added to Approved devices as soon as the hub reports it joined. ' +
'A virtual device, or one a cloud or LAN integration creates, is not reported that way, so add it here by hand.</small>'
}
Map haiVarOptions = runtimeHubVariableOptions()
List haiVarsChosen = settings.runtimeWritableVariables == null ? (haiVarOptions.keySet() as List) : settings.runtimeWritableVariables
boolean haiModeOn = settings.runtimeAllowSetMode != false
section(haiPermissionTitle('Mode', "[${haiModeOn ? 'ON' : 'OFF'}]", 'and approved hub variables', "(${haiVarsChosen.size()})"),
hideable: true, hidden: true) {
List haiModeNames = location.modes.collect { it.name.toString() }
input 'runtimeAllowSetMode', 'bool', defaultValue: true, submitOnChange: true,
title: "Allow rules to change the mode${haiModeNames ? " (${haiModeNames.join(', ')})" : ''}".toString()
input 'runtimeWritableVariables', 'enum', title: 'Approved hub variables', submitOnChange: true,
options: haiVarOptions, multiple: true, required: false, defaultValue: haiVarOptions.keySet() as List
}
section('Stop a rule', hideable: true, hidden: true) {
paragraph '<small>Here as well as in the editor, so a rule can always be stopped from the hub even when the editor cannot be served. Deactivate stops the rule and anything it is part way through: its waits, delays and repeats do not carry on.</small>'
input 'haiDeactivateTarget', 'enum', title: 'Rule', options: runtimeRuleOptions(), required: false, submitOnChange: true
if (settings.haiDeactivateTarget) input 'haiDeactivate', 'button', title: 'Deactivate'
}
Long aiTestAt = (state.engineKeyTest instanceof Map ? ((Map) state.engineKeyTest).at : null) as Long
boolean aiOpen = (haiAi.available != true && haiAi.code != 'nokey') || (aiTestAt != null && now() - aiTestAt < 120000L)
section('AI token and cost management', hideable: true, hidden: !aiOpen) {
if (!engineHasKey()) paragraph "<small>Optional. HAI works fully without it. Add your own Claude API key here if you want the AI Rule Assistant to draft rules from plain words.</small>"
input 'engineApiKey', 'password', required: false,
title: state.engineKeyTail ? "Claude API key - one ending ...${state.engineKeyTail} is stored. Type a new one to replace it.".toString()
: 'Claude API key (stored on this hub only)'
paragraph "<small>Your Claude API keys, and new ones, are at <a href='${CLAUDE_KEYS_URL}' target='_blank'>${CLAUDE_KEYS_URL.replace('https://', '')}</a>.</small>"
input 'engineSpendLimitUsd', 'decimal', title: 'Total spending allowed for drafting (US$)', required: false
paragraph "<small>${esc(engineDraftAverageText())}</small>"
if (state.engineSpendStop) {
paragraph "<b>Drafting is stopped.</b> ${esc((state.engineSpendStop.reason ?: 'the spending limit was reached') as String)}. Type the limit again to clear it; what has already been spent still counts."
input 'engineSpendLimitConfirmUsd', 'decimal', title: 'Type the spending limit again to confirm', required: false
input 'engineClearSpendStop', 'button', title: 'Clear spending stop'
}
input 'engineEffort', 'enum', title: 'Effort', options: ['low', 'medium', 'high', 'xhigh'], defaultValue: 'high'
input 'engineTestApiKey', 'button', title: 'Test the API key'
if (state.engineKeyTest) paragraph engineKeyTestLine()
}
section('Rule end points', hideable: true, hidden: true) {
paragraph 'Let other things run your HAI rules: a dashboard button, a phone shortcut, another hub, or another app on this hub such as Rule Machine. ' +
"They can start, stop, pause or resume a rule. They can't create, change or delete one."
input 'runtimeAppApi', 'bool', title: 'Let other apps on this hub run HAI rules', defaultValue: true
paragraph '<small><b>Technical:</b> other apps send a request as a hub location event, and HAI answers with the rule list or the result. Off means HAI refuses every request.</small>'
if (state.haiApiToken) {
paragraph haiApiTokenHtml(state.haiApiToken as String)
paragraph '<small>Each request from another app must carry this token, or HAI ignores it and says so once in the log. ' +
'Paste it into the app that sends the requests. With the HAI Rule API library, that is its <b>HAI API token</b> setting.</small>'
}
input 'haiEndpointOn', 'bool', title: 'Let a web address run HAI rules (dashboards, shortcuts, other hubs)', defaultValue: false, submitOnChange: true
if (settings.haiEndpointOn != true) {
paragraph '<small>Off: the web address does nothing. Turn it on to show it.</small>'
} else if (state.accessToken) {
paragraph "<b>Your private web address</b><br><code>${getFullLocalApiServerUrl()}/endpoint/rules?access_token=${state.accessToken}&aud=${haiAudienceSecret('endpoint')}</code>"
paragraph "Anyone who has this address can run your rules, so treat it like a password. Don't post it in the community or in screenshots."
paragraph '<small><b>Technical:</b> open the address as it is to list your rules and their ids. To act on one, put the rule id and an action after <code>/endpoint/rules</code> and before the <code>?</code>: ' +
'<code>/endpoint/rules/&lt;rule id&gt;/run</code>. The other actions are <code>/stop</code>, <code>/pause</code>, <code>/resume</code>, <code>/trigger</code>, ' +
'and <code>/boolean/true</code> or <code>/boolean/false</code> for the rule\'s Private Boolean. <code>/endpoint/variables/&lt;name&gt;/&lt;value&gt;</code> sets a hub variable, ' +
'but only one ticked under Approved hub variables. Works on your home network only.</small>'
}
}
section('Everyday words', hideable: true, hidden: true) {
String scale = haiEverydayScale()
String deg = "\u00b0${scale}".toString()
Closure dv = { String level -> everydayNumber(haiEverydayDefault(level, scale)) }
paragraph 'The levels behind the words the rule editor offers for conditions: how dark is dark, how hot is hot. ' +
'The values below are generic starting points: change them to suit your own climate and preferences. ' +
'Rules follow these settings: after a change, every rule that uses a changed word is updated to it, one at a time, each as a new version. A running rule stays running and a stopped one stays stopped.'
String evDone = haiEverydayReport()
if (evDone) paragraph "<small>Last change: ${evDone}</small>".toString()
input 'everydayDarkBelow', 'decimal', title: 'Dark: under this many lux', required: false, defaultValue: dv('darkBelow')
input 'everydayOvercastFrom', 'decimal', title: 'Dusk or dawn up to, and overcast from, this many lux', required: false, defaultValue: dv('overcastFrom')
input 'everydayBrightFrom', 'decimal', title: 'Bright day: this many lux or more', required: false, defaultValue: dv('brightFrom')
input 'everydayHotFrom', 'decimal', title: "Hot: this many ${deg} or more".toString(), required: false, defaultValue: dv('hotFrom')
input 'everydayColdTo', 'decimal', title: "Cold: this many ${deg} or less".toString(), required: false, defaultValue: dv('coldTo')
input 'everydayWindyFrom', 'decimal', title: 'Windy: wind speed this or more, in the unit your weather device reports', required: false, defaultValue: dv('windyFrom')
paragraph '<small>Light is read from a light sensor (illuminance), so artificial light and dull weather count.</small>'
paragraph 'Weather words read an OpenWeatherMap device. Hubitat has a built-in OpenWeatherMap driver: create a Virtual Device with that driver, ' +
'enter your OpenWeatherMap API key in it, then choose it here.'
input 'everydayWeatherDevice', 'capability.temperatureMeasurement', title: 'Weather device', required: false, multiple: false
paragraph '<small>Choosing it here also approves it for HAI, under Approved devices. With none chosen, the editor uses an approved device that reports temperature, wind speed and the weather.</small>'
}
section('MCP endpoint', hideable: true, hidden: true) {
paragraph 'Connect an AI assistant, such as Claude Desktop, so you can describe a rule in a conversation and have it written for you. ' +
'The assistant can see your approved devices and your rules, and send HAI a rule to check. HAI checks it, reads it back in plain English, ' +
"and keeps it as a draft in the editor for you to review. The assistant can't turn a rule on or run one; only you can, in the editor."
input 'haiMcpOn', 'bool', title: 'Let an AI assistant connect (MCP)', defaultValue: false, submitOnChange: true
if (settings.haiMcpOn != true) {
paragraph '<small>Off: nothing can connect. Turn it on to show the address to give your AI assistant.</small>'
} else if (!state.accessToken) {
paragraph '<small>Press Done to create the address.</small>'
} else {
paragraph "<code>${getFullLocalApiServerUrl()}/mcp?access_token=${state.accessToken}&aud=${haiAudienceSecret('mcp')}</code>"
paragraph '<small><b>Technical:</b> add this address as an MCP server (Streamable HTTP) in your AI client. It offers <code>hai_describe</code> (the rule format and your devices), ' +
'<code>hai_propose_rule</code> (check, read back and simulate a draft), <code>hai_get_rule</code> and <code>hai_render_rule</code>. ' +
"Works on your home network only. It uses your AI client's own account, not the API key above.</small>"
}
}
section('Change access addresses', hideable: true, hidden: true) {
paragraph 'Use this if you think someone has a link to HAI they should not have, for example from a screenshot, a shared message or an old phone. ' +
'Pressing the button changes every HAI link at once, so the old ones stop working. Your rules keep running as before.'
paragraph 'This does not change the AI API key under AI token and cost management. It changes only the links and token used by apps and pages on your home network.'
paragraph '<b style="color:#c62828">What stops working until you update it:</b> bookmarks and saved links to the HAI editor, editor pages you already have open, ' +
'and any dashboard, shortcut, AI assistant or other app that controls HAI.'
paragraph '<b style="color:#1565c0">What you need to do after changing it:</b> open the editor from this page again, ' +
'and copy the new links and token shown on this page to wherever you use them.'
paragraph '<b>Technical</b>'
paragraph '<small>The editor, end point and MCP addresses, the API token for other apps and the Rule Machine Migration address all change at once. ' +
'After you press it, editor tabs that are open, saved links and bookmarks, dashboard buttons and shortcuts that use the end point address, ' +
'your AI assistant\'s MCP address, and other apps\' API token stop working. Copy the new addresses and token from this page to where you use them. ' +
'Rule apps receive their new editor link automatically. The Claude API key is a separate credential and is not rotated.</small>'
input 'haiRotateAccess', 'button', title: "Change HAI's access addresses"
}
section('Health and diagnostics', hideable: true, hidden: true) {
paragraph haiHealthLine()
haiDiagnosticLines().each { String line -> paragraph "<small>${esc(line)}</small>".toString() }
if (haiDiagnosticsActive()) {
paragraph '<b>Diagnostics are on.</b> They turn themselves off an hour after being switched on.'
} else {
paragraph '<small>Diagnostics record far more detail and turn themselves off an hour later, so they cannot be left on by forgetting.</small>'
input 'haiDiagnostics', 'button', title: 'Turn diagnostics on for an hour'
}
}
}
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
Map ev = haiEverydayLevels().collectEntries { k, v -> [(k): k == 'scale' ? v : everydayNumber(v)] }
String deg = "\u00b0${ev.scale}".toString()
out << "Everyday words in use: dark under ${ev.darkBelow} lux, dusk or dawn to ${ev.overcastFrom}, bright from ${ev.brightFrom} lux, hot from ${ev.hotFrom}${deg}, cold to ${ev.coldTo}${deg}, windy from ${ev.windyFrom}.".toString()
def wx = settings.everydayWeatherDevice
if (wx == null) {
out << 'Weather device: none chosen; the editor uses an approved device that reports like one, if there is one.'
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
out << "Migration watch: ${n} Rule Machine rule${n == 1 ? '' : 's'} switched over to ${n == 1 ? 'its' : 'their'} HAI cop${n == 1 ? 'y' : 'ies'}; ${ran} ha${ran == 1 ? 's' : 've'} run, ${wrong} last run${wrong == 1 ? '' : 's'} went wrong${wrong ? ' (see HAI Migration)' : ''}.".toString()
} else {
out << 'Migration watch: no Rule Machine rule is switched over to an HAI copy.'
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
@Field static final String ENGINE_APP_VERSION = '0.5.89-dev'
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
try { createAccessToken() } catch (Exception e) { log.warn "HAI Engine: OAuth is not enabled for this app code (${e.message})" }
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
state.engineKeyMasked = engineKeyMask(entered.trim())
state.engineKeyTail = entered.trim().length() > 4 ? entered.trim()[-4..-1] : null
if (state.engineKeyTest?.fault == 'key') state.engineKeyTest = null
}
if (settings.containsKey('engineApiKey')) app.removeSetting('engineApiKey')
}
String engineKey() {
String entered = settings.engineApiKey as String
if (entered != null && !entered.trim().isEmpty()) return entered.trim()
return engineKeyUnmask(state.engineKeyMasked as String)
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
Map session = authorStart((settings.engineRequest ?: '') as String, inv, engineOpts(authorOptions))
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
log.info "HAI Engine: a scheduled resend was dropped because ${why}"
return
}
engineSend()
}
void engineSend() {
Map session = filed('engineSession')
if (session == null || session.status != 'awaitingModel' || session.nextRequest == null) return
Map prep = authorPrepareSend(session, now())
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
log.info "HAI Engine: a provider reply was ignored because ${why}"
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
session = authorOnResponse(session, inv, status, body as String, headers, now())
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
state.engineDraftResult = engineRemember(proposal, editorRuleFromHistory(history, proposal.ruleHash as String),
session.outcome.status == 'failed')
}
log.info "HAI Engine: drafting finished, ${session.outcome.status}, ${session.outcome.attempts} attempt(s)"
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
log.info "HAI Engine: spending stop cleared (${state.engineSpendStop.reason})"
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
filedPut('engineSession', authorBoundSession(session))
}
void engineStoreOutcome(Map outcome) {
filedPut('engineOutcome', authorBoundOutcome(outcome))
}
Map engineRemember(Map proposal, Map rule = null, boolean needsFixing = false) {
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
render(status: 405, contentType: 'text/plain', data: 'This server does not offer a server-to-client stream.')
}
def mcpPost() {
if (!haiAudienceOk('/mcp')) return haiRefuse('/mcp')
if (settings.haiMcpOn != true) return mcpOff()
String raw = null
try { raw = request.body as String } catch (Exception e) { raw = null }
if (raw == null) return mcpRender(null, null, mcpError(-32700, 'Parse error', 'the request had no body'))
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
out = [guide: authorSystem(), commands: CATALOGUE.keySet().sort(),
context: authorContext(inv, args.request instanceof String ? (String) args.request : '')]
break
case 'hai_propose_rule':
case 'hai_render_rule':
if (!(args.rule instanceof Map)) {
out = [error: 'rule must be a hai.rule/1 object']
break
}
if (name == 'hai_render_rule') {
Map v = validateRule((Map) args.rule, inv)
out = [rendering: renderRule((Map) args.rule), findings: v.findings, activatable: vActivatable(v)]
} else {
if (!(args.request instanceof String) || ((String) args.request).trim().isEmpty()) {
out = [error: "request must be the person's original words"]
break
}
Map p = proposeEvaluate((Map) args.rule, args.requirements, inv, null,
engineOpts([request: args.request, source: 'mcp']))
engineRemember(p, args.rule)
out = p
}
break
default:
return [content: [[type: 'text', text: "Unknown tool: ${name.take(64)}".toString()]], isError: true]
}
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
if (!(state.haiApiToken instanceof String) || !((String) state.haiApiToken ==~ /[a-f0-9]{32}/)) state.haiApiToken = rtNewApiToken()
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
boolean migration = true
try { def m = haiMigrationChild(); if (m != null) m.haiRotateAccess() }
catch (Exception e) { migration = false; log.warn "HAI Engine: HAI Migration kept its old address: ${e.message}" }
runtimeRefreshEditorLinks()
rtLog(runtimePlat(), [what: 'accessRotated'])
log.info 'HAI Engine: the access addresses and the API token were changed. Old links and the old API token no longer work.'
state.haiNotice = "HAI's addresses changed. Open the editor from this page again, and give other apps the new API token. " +
(migration ? '' : 'HAI Migration kept its old address; press the button again to retry. ')
}
boolean haiAudienceOk(String route) {
String want = HAI_ROUTE_AUDIENCE.get(route) as String
if (want == null) return false
if (want == 'feed') return true
String given = null
try { given = params?.aud as String } catch (Exception e) { given = null }
return jsonSameSecret(haiAudienceSecret(want), given)
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
String page = editorPage()
boolean current = (params?.v as String) == EDITOR_PAGE_SHA256.take(12) && EDITOR_FILE_CACHE.containsKey('page:' + EDITOR_PAGE_SHA256)
return render(status: 200, contentType: 'text/html', data: page,
headers: ['Cache-Control': current ? 'private, max-age=31536000, immutable' : 'no-store, must-revalidate'])
}
@Field static final String HAI_REPORT_URL = 'https://github.com/GordonThelander/HAI-Engine/issues/new/choose'
@Field static final String MIGRATION_CHILD_NAME = 'HAI Migration (Preview)'
@Field static final int MIGRATION_CONTRACT = 2
def haiMigrationChild() {
return getChildApps()?.find { it.name == MIGRATION_CHILD_NAME }
}
Map haiMigrationFacts(Boolean pairsOnly = false) {
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
return [contract: MIGRATION_CONTRACT, pairs: haiMigrationPairs(), paused: paused, checked: checked, stopped: stopped,
disabled: disabled, appsComplete: listed.ok == true,
pausedWhy: listed.ok == true ? null : listed.why, haiRules: haiRules, allowed: allowed,
limits: [writable: runtimeIdList(settings.runtimeWritableVariables), modeAllowed: settings.runtimeAllowSetMode == true,
disabled: disabledIds],
editorUrl: state.accessToken ? "/apps/api/${app.id}/editor?access_token=${state.accessToken}&aud=${haiAudienceSecret('editor')}&v=${EDITOR_PAGE_SHA256.take(12)}".toString() : null]
}
Map haiAppsPaused() {
Object data = null
try {
httpGet([uri: 'http://127.0.0.1:8080/hub2/appsList', contentType: 'application/json', timeout: 20]) { resp ->
if (resp?.status == 200) data = resp.data
}
} catch (Exception e) {
return [ok: false, why: "the hub's app list could not be read (${e.message})".toString()]
}
return haiAppsPausedFrom(data)
}
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
void haiMigrationRecord(Map pairs, String rmId = null, Boolean rmPaused = null) {
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
data: groovy.json.JsonOutput.toJson([pairs: now]),
descriptionText: "HAI: ${now.size()} Rule Machine rule${now.size() == 1 ? '' : 's'} switched over".toString())
}
Map haiMigrationCall(String name, Map arg) {
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
return simDryRun(doc, now(), (location.timeZone ?: TimeZone.getTimeZone('UTC')).ID, inv)
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
return [ok: false, code: out.code, refused: out, doc: cand?.doc]
},
rmPausedNow: { Object rmId ->
Boolean paused = null
httpGet([uri: "http://127.0.0.1:8080/installedapp/statusJson/${rmId}".toString(),
contentType: 'application/json', timeout: 10]) { resp ->
if (resp?.status == 200) paused = rtRmPausedFrom(resp.data)
}
return paused
},
importRule : { Map c ->
Map kept = rtImportDoc(runtimePlat(), c.doc)
return kept.ok == true
? [ok: true, candidateId: ((Map) kept.candidate)?.id, message: kept.message]
: [ok: false, code: (kept.code as String) ?: 'MIGRATION_IMPORT', message: kept.message as String]
}
]
Closure f = (Closure) hooks.get(name)
if (f == null) return [ok: false, message: "this build of HAI Engine has no ${name}".toString()]
if (name == 'rmPausedNow') return [paused: f(arg?.rmId)]
Object r = f(arg)
return r instanceof Map ? r : [ok: true]
}
def editorStateGet() {
if (!haiAudienceOk('/editor/api/state')) return haiRefuse('/editor/api/state')
editorRespond('state') }
def editorFixedGet() {
if (!haiAudienceOk('/editor/api/fixed')) return haiRefuse('/editor/api/fixed')
String text
try { text = editorFixedText() } catch (Exception e) {
return render(status: 500, contentType: 'application/json',
data: groovy.json.JsonOutput.toJson([ok: false, error: 'NO_TABLES', message: e.message]))
}
boolean current = (params?.v as String) == editorFixedVersion()
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
String raw = null
try { raw = request.body as String } catch (Exception e) { raw = null }
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
} else if (step.type == 'hsm') {
sendLocationEvent(name: 'hsmSetArm', value: step.op as String)
sent << "HSM ${step.op}".toString()
} else if (step.type == 'log') {
if (step.level == 'warn') log.warn(step.text as String) else log.info(step.text as String)
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
String stamp = rtBackupStamp(live, paused, [drafts: haiBackupDraftsMark(), pairs: pairs])
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
log.info "HAI Engine: rule backup ${name}: ${rules.size()} rule${rules.size() == 1 ? '' : 's'}, ${drafts.size()} draft${drafts.size() == 1 ? '' : 's'}, " +
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
log.info "HAI Engine: ${add.size()} migration pair${add.size() == 1 ? '' : 's'} restored from ${b.name}" +
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
if (idx.size() > have.size()) log.info "HAI Engine: found ${idx.size() - have.size()} earlier rule backup${idx.size() - have.size() == 1 ? '' : 's'} in File Manager"
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
String raw = null
try { raw = request.body as String } catch (Exception e) { raw = null }
out = editorSubmission(raw, haiSigningKey(), now(),
state.accessToken ? "/apps/api/${app.id}/editor?access_token=${state.accessToken}&aud=${haiAudienceSecret('editor')}&v=${EDITOR_PAGE_SHA256.take(12)}".toString() : null)
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
log.info "HAI editor run timings (ms): submit=${sendTiming.submitMs ?: 0}, keep=${sendTiming.keepMs ?: 0}, dispatch=${sendTiming.dispatchMs ?: 0}, serialize=${sendTiming.serializeMs}, total=${sendTiming.totalMs}"
}
return editorJson(out.status as int, response)
}
def endpointRulesGet() {
if (!haiAudienceOk('/endpoint/rules')) return haiRefuse('/endpoint/rules')
if (settings.haiEndpointOn != true) return endpointOff()
endpointRespond([kind: 'rules']) }
def endpointRuleGet() {
if (!haiAudienceOk('/endpoint/rules/:id/:op')) return haiRefuse('/endpoint/rules/:id/:op')
if (settings.haiEndpointOn != true) return endpointOff()
endpointRespond([kind: 'rule', id: endpointParam(params?.id), op: params?.op, value: endpointParam(params?.value)]) }
def endpointVariableGet() {
if (!haiAudienceOk('/endpoint/variables/:name/:value')) return haiRefuse('/endpoint/variables/:name/:value')
if (settings.haiEndpointOn != true) return endpointOff()
endpointRespond([kind: 'variable', id: endpointParam(params?.name), value: endpointParam(params?.value)]) }
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
log.info "HAI Engine: end point ${((Map) out.payload).op} ${((Map) out.payload).ruleId}: ${receipt?.ok ? 'done' : (receipt?.notice ?: receipt?.code)}"
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
render(status: out.status as int, contentType: 'application/json', data: groovy.json.JsonOutput.toJson(out.body))
}
def editorSavePost() {
if (!haiAudienceOk('/editor/api/save')) return haiRefuse('/editor/api/save')
editorRespond('save') }
def editorDeletePost() {
if (!haiAudienceOk('/editor/api/delete')) return haiRefuse('/editor/api/delete')
editorRespond('delete') }
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
String raw = null
try { raw = request.body as String } catch (Exception e) { raw = null }
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
try { raw = request.body as String } catch (Exception e) { raw = null }
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
if (timeCheck) timing.optionsMs = now() - optionsStarted
if (timeCheck) {
options.editorTiming = timing
timing.profileCanonical = state.haiDigestProfiledUntil != state.haiDiagnosticsUntil
}
Map recent = route == 'runtime' && params?.rule ? runtimeRecentLine(params.rule) : null
out = editorApi(route, [body: checkBody, params: [id: params?.id]], store, inv, options,
[spending: spending, version: haiVersion(), environment: HAI_ENVIRONMENT,
runtimeUrl: "/installedapp/configure/${app.id}".toString(),
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
log.info "HAI editor check timings (ms): inventory=${timing.inventoryMs ?: 0}, options=${timing.optionsMs ?: 0}, validate=${timing.validateMs ?: 0}, render=${timing.renderMs ?: 0}, sentences=${timing.sentencesMs ?: 0}, hash=${timing.hashMs ?: 0}, proposal=${timing.proposalMs ?: 0}, proposalValidate=${timing.proposeValidateMs ?: 0}, proposalChecklist=${timing.proposeChecklistMs ?: 0}, proposalRenderDiff=${timing.proposeRenderDiffMs ?: 0}, proposalScenarios=${timing.proposeScenariosMs ?: 0}, proposalEvidence=${timing.proposeEvidenceMs ?: 0}, proposalInventoryDigest=${timing.proposeInventoryDigestMs ?: 0}, digestTyped=${timing.digestTypedMs ?: 0}, digestFastJson=${timing.digestFastJsonMs ?: 0}, digestCanonicalJson=${timing.digestCanonicalJsonMs ?: 0}, digestHash=${timing.digestHashMs ?: 0}, digestFallback=${timing.digestFallback ?: false}, proposalPacketHash=${timing.proposePacketHashMs ?: 0}, receipt=${timing.receiptMs ?: 0}, serialize=${timing.serializeMs}, total=${timing.totalMs}"
}
return editorJson(out.status as int, response)
}
Object editorJson(int status, String data) {
return render(status: status, contentType: 'application/json', data: data, headers: ['Cache-Control': 'no-store'])
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
runIn(8, 'haiWriteAmStateNow')
subscribe(location, 'systemStart', 'haiSystemStart')
haiSigningKey()
subscribe(location, 'haiRuleSubmit', 'haiRuleSubmit')
subscribe(location, RT_API_EVENT, 'haiRuleApi')
subscribe(location, RT_COMMITTED_EVENT, 'haiRuleCommitted')
unschedule('haiBackupDaily')
schedule(HAI_BACKUP_CRON, 'haiBackupDaily')
if (settings.haiAutoApproveNew != false) subscribe(location, 'deviceJoin', 'haiDeviceJoined')
if (!state.accessToken) {
try { createAccessToken() } catch (Exception e) { log.warn "HAI Runtime: OAuth is not enabled for this app code (${e.message})" }
}
runtimeSyncVariableRegistration()
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
if (op == null) log.info "HAI Runtime: end point ${chk.op} ${chk.ruleId}: ${r?.ok ? 'done' : (r?.code ?: 'refused')}"
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
def haiRuleApi(evt) {
Map req = [:]
try { req = new groovy.json.JsonSlurper().parseText(evt.data as String) } catch (Exception e) { req = [:] }
String refused = rtAppApiTokenRefusal(req, state.haiApiToken as String)
if (refused != null) {
String from = ((req.from ?: 'an app') as String).take(80).replaceAll(/[<>]/, '')
Map seen = HAI_API_REFUSED.get(app.id as String)
if (seen == null) { seen = new java.util.concurrent.ConcurrentHashMap(); HAI_API_REFUSED.put(app.id as String, seen) }
if (rtAppApiFirstRefusal(seen, from, 50)) {
log.warn "HAI Engine: ignored a request from ${from}: ${refused == 'NO_TOKEN' ? 'it carries no API token' : 'its API token is wrong'}. " +
"Copy the API token from the HAI Engine page, under Rule end points, into that app. Said once for each app until the hub restarts."
}
return
}
if (!req.op) req.op = evt.value
Map pp = runtimePlat()
Map out = rtAppApi(pp, req, settings.runtimeAppApi != false, (state.runtimePaused ?: []))
log.info "HAI Runtime: ${out.from} asked to ${out.op}${out.rule ? ' ' + out.rule : ''}: ${out.ok ? 'done' : out.code}"
sendLocationEvent(name: RT_API_REPLY, value: (req.id ?: out.op ?: 'reply') as String, data: groovy.json.JsonOutput.toJson(out),
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
log.info "HAI Engine: activated ${cand?.ruleId} (${out.code ?: (out.ok ? 'staged' : 'refused')})"
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
try {
httpGet([uri: "http://127.0.0.1:8080/device/fullJson/${rtBareId(deviceId as String)}".toString(),
timeout: 8, textParser: false]) { resp ->
((List) (resp?.data?.appsUsing ?: [])).each { Object a ->
String label = (((Map) a).label ?: ((Map) a).name ?: '').toString().replaceAll('<[^>]+>', '').trim()
String id = ((Map) a).id?.toString()
if (!label || id == app.id?.toString() || label.startsWith('[HAI]') || label.startsWith('HAI ')) return
out << label
}
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
log.info "HAI Runtime: ${ruleId} reported its commit (version ${n.epoch})"
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
void haiWatchdogTick() {
Map pp = runtimePlat([hold: true, budgetMs: RT_WATCHDOG_BUDGET_MS])
Map wd = rtWatchdog(pp)
Map lights = wd.lights
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
Map read = rtRmListReading((List) (data?.ids ?: []), got, (state.runtimeRmPaused ?: [:]), (state.runtimeRmGone ?: []))
List events = read.events
if ((List) read.gone != (state.runtimeRmGone ?: [])) state.runtimeRmGone = read.gone
if ((Map) read.seen != (state.runtimeRmPaused ?: [:])) state.runtimeRmPaused = read.seen
events.each { Map ev ->
sendLocationEvent(name: RT_PAUSED_EVENT, value: ev.id, isStateChange: true, displayed: false, data: groovy.json.JsonOutput.toJson([paused: ev.paused]),
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
if (was != null) sendLocationEvent(name: RT_PAUSED_EVENT, value: id, isStateChange: true, displayed: false, data: groovy.json.JsonOutput.toJson([paused: paused]),
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
if (now() - t0 > 1000L) log.info "HAI Runtime: summary took ${now() - t0} ms, ${reads} of ${rows.size()} rules read"
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
Map pp = runtimePlat()
((Map) rtRegistry(pp).rules).values().each { e ->
if (e.state == 'removed' || runtimeChild(e.childId) == null) return
try { runtimeChildCall(e.childId, 'haiReconcile', []) } catch (Exception ex) { log.warn "HAI Runtime: reconcile of ${e.ruleId} failed: ${ex.message}" }
}
rtReconcileAll(pp)
unschedule('haiWatchdog')
schedule(RT_WATCHDOG_CRON, 'haiWatchdog')
}
@Field static final long RT_CHILD_SLOW_MS = 10000L
@Field static final long RT_WATCHDOG_BUDGET_MS = 30000L
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
if (!hold) return atomicState[key]
if (!held.containsKey(key)) held[key] = runtimeCopy(atomicState[key])
return runtimeCopy(held[key])
},
atomicPut : { List a ->
atomicState[a[0] as String] = a[1]
if (hold) held[a[0] as String] = runtimeCopy(a[1])
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
rmSwitched : { Object u -> haiSwitchedPairs() },
liveUsers : { Object dv -> runtimeLiveUsers(dv) },
hubVariables: { Object u -> getAllGlobalVars() },
setHubVariable: { List a -> setGlobalVar(a[0] as String, a[1]) },
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
case 'haiResumeStaged': return c.haiResumeStaged()
case 'haiCommitNow': return c.haiCommitNow(args[0] as String)
case 'haiDiscardStaged': return c.haiDiscardStaged(args[0] as String)
case 'haiReconcile': return c.haiReconcile()
case 'haiDevWithhold': return c.haiDevWithhold(args[0])
case 'haiEditorLink': return c.haiEditorLink(args[0] as String)
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
@Field static final int LIMIT_REFERENCES_PER_RULE = 128
@Field static final int LIMIT_CONDITION_NODES = 512
@Field static final int LIMIT_ACTION_NODES = 512
@Field static final int LIMIT_TRIGGERS_PER_RULE = 64
@Field static final int LIMIT_WAIT_EVENTS = 16
@Field static final int LIMIT_PARSER_WORK = 1000000
@Field static final int LIMIT_VALIDATION_WORK = 200000
@Field static final int LIMIT_QUEUE_DEPTH = 4
@Field static final int LIMIT_PARALLEL_RUNS = 4
@Field static final int LIMIT_REPEAT_PASSES = 1000
@Field static final long LIMIT_REPEAT_MIN_MS = 1000L
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
@Field static final List RERUN_POLICIES = ['restart', 'ignore', 'queue', 'parallel']
@Field static final List ERROR_POLICIES = ['continue', 'stopRun', 'failurePath']
@Field static final List UNKNOWN_POLICIES = ['block', 'proceed']
@Field static final List IF_UNKNOWN_POLICIES = ['stop', 'otherwise']
@Field static final List UNKNOWN_AS = ['false']
@Field static final List CONDITION_TIMING = ['atTrigger', 'atRunStart']
@Field static final List PAUSE_MODES = ['finish', 'hold']
@Field static final List LOG_LEVELS = ['info', 'warn']
@Field static final List CAPTURE_ATTRIBUTES = ['switch', 'level', 'colorTemperature', 'hue', 'saturation', 'colorMode', 'valve', 'lock',
'position', 'speed', 'thermostatMode', 'heatingSetpoint', 'coolingSetpoint', 'thermostatFanMode']
@Field static final Map SETVAR_OPS = [
set : [result: 'any', args: ['source']],
extract : [result: 'string', args: ['source', 'pattern']],
add : [result: 'number', args: ['number']],
math : [result: 'number', args: ['a', 'operator', 'b?']],
hour : [result: 'number', args: []],
minute : [result: 'number', args: []],
epoch : [result: 'number', args: []],
now : [result: 'string', args: ['format?']],
sunrise : [result: 'string', args: []],
sunset : [result: 'string', args: []],
length : [result: 'number', args: ['source']],
remove : [result: 'string', args: ['source', 'text']],
replace : [result: 'string', args: ['source', 'find', 'with']],
token : [result: 'string', args: ['source', 'delimiter', 'index']],
lowerCase: [result: 'string', args: ['source']],
urlEncode: [result: 'string', args: ['source']],
urlDecode: [result: 'string', args: ['source']],
compare : [result: 'boolean', args: ['a', 'comparison', 'b']],
and : [result: 'boolean', args: ['a', 'b']],
or : [result: 'boolean', args: ['a', 'b']],
xor : [result: 'boolean', args: ['a', 'b']],
not : [result: 'boolean', args: ['a']],
formatDate : [result: 'string', args: ['source', 'format']],
addTime : [result: 'string', args: ['source', 'amount', 'unit']],
timeBetween: [result: 'number', args: ['from', 'to', 'unit']],
file : [result: 'string', args: ['name']],
]
@Field static final Map TIME_UNITS = [seconds: 1L, minutes: 60L, hours: 3600L, days: 86400L]
@Field static final List MATH_BINARY = ['+', '-', '*', '/', '%']
@Field static final List MATH_UNARY = ['negate', 'absolute', 'round', 'random', 'sqrt', 'sin', 'cos', 'tan', 'asin', 'acos', 'atan', 'log', 'toRadians', 'toDegrees']
@Field static final List REF_KINDS = ['device', 'variable', 'mode', 'room', 'rule', 'self', 'rmRule', 'eventDevice']
@Field static final List TRIGGER_KINDS = [
'deviceAttribute', 'threshold', 'duration', 'button', 'schedule', 'mode', 'hubVariable', 'ruleInvocation',
'rulePaused', 'periodic', 'endpoint', 'hsmStatus', 'hsmAlert', 'locationEvent',
]
@Field static final List DISCIPLINES = ['transition', 'event']
@Field static final List DISCRETE_TRIGGER_KINDS = ['button', 'schedule', 'ruleInvocation', 'periodic', 'endpoint']
@Field static final List CONDITION_TYPES = ['all', 'any', 'not', 'deviceAttribute', 'variable', 'mode', 'timeWindow', 'triggeredBy', 'rulePaused', 'waitOutcome',
'xor', 'count', 'dayOfWeek', 'dateRange', 'timeSince', 'hsmStatus']
@Field static final List MATCH_MODES = ['any', 'all']
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
Map everydayFollow(Map doc, Map values) {
Map copy = new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(doc))
int n = 0
Closure walk
walk = { Object v ->
if (v instanceof Map) {
Map m = v
if (m.everyday instanceof String && values.containsKey(m.everyday) && values.get(m.everyday) != null && !everydaySame(m.value, values.get(m.everyday))) {
Object want = values.get(m.everyday)
m.value = want instanceof List ? new ArrayList((List) want) : want
n++
}
m.values().each { walk(it) }
} else if (v instanceof List) ((List) v).each { walk(it) }
}
walk(copy)
return [doc: copy, changed: n]
}
@Field static final List COMPARATORS = ['is', 'isNot', 'above', 'below', 'atLeast', 'atMost', 'contains', 'matches', 'isEmpty', 'between', 'notBetween']
@Field static final int REGEX_MAX_CHARS = 200
@Field static final int REGEX_MAX_SUBJECT = 4000
@Field static final String REGEX_NESTED_QUANTIFIER = '\\([^()]*[*+][^()]*\\)[*+]'
@Field static final List THRESHOLD_OPS = ['above', 'below', 'atLeast', 'atMost']
@Field static final Set NON_READING_ATTRIBUTES = (['switch', 'contact', 'motion', 'lock', 'presence', 'water',
'smoke', 'carbonMonoxide', 'door', 'valve', 'acceleration', 'tamper', 'mute', 'shock', 'sound', 'sleeping',
'windowShade', 'thermostatMode', 'thermostatFanMode', 'thermostatOperatingState', 'status', 'mode',
'alarm', 'hsmStatus', 'lastCodeName', 'colorName', 'colorMode', 'effectName', 'naturalGas', 'consumableStatus',
'filterStatus', 'healthStatus', 'networkStatus', 'transportStatus', 'trackDescription', 'trackData',
'threeAxis', 'color', 'RGB'] +
['pushed', 'held', 'doubleTapped', 'released']) as Set
@Field static final List BUTTON_ACTIONS = ['pushed', 'held', 'doubleTapped', 'released']
@Field static final List SUN_EVENTS = ['sunrise', 'sunset']
@Field static final List EVENT_TYPES = ['physical', 'digital']
@Field static final String LOCATION_DEVICE = 'location'
@Field static final List HSM_STATUSES = ['armedAway', 'armingAway', 'armedHome', 'armingHome', 'armedNight', 'armingNight', 'disarmed', 'allDisarmed']
@Field static final List HSM_ALERTS = ['intrusion', 'intrusion-delay', 'intrusion-home', 'intrusion-home-delay', 'intrusion-night',
'intrusion-night-delay', 'smoke', 'water', 'rule', 'arming', 'cancel']
@Field static final List HSM_ARM_OPS = ['armAway', 'armHome', 'armNight', 'disarm', 'disarmAll', 'armAll', 'armRules', 'disarmRules', 'cancelAlerts']
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
@Field static final Map ALLOWED_FIELDS = [
'rule' : ['schema', 'id', 'name', 'description', 'refs', 'triggers', 'conditions', 'actions',
'rerunPolicy', 'queueLimit', 'parallelLimit', 'onUnknown', 'ifUnknown', 'errorPolicy', 'onFailure', 'onCancel',
'enabled', 'initialEvaluation', 'conditionTiming', 'pauseMode', 'requiredExpression', 'cancelOnFalse',
'logEvents', 'logTriggers', 'logActions'],
'ref:device' : ['kind', 'id', 'name', 'room', 'driver', 'capabilities', 'required'],
'ref:variable' : ['kind', 'id', 'name', 'type', 'scope', 'required', 'initial'],
'ref:mode' : ['kind', 'name', 'required'],
'ref:room' : ['kind', 'name'],
'ref:rule' : ['kind', 'id', 'name'],
'ref:rmRule' : ['kind', 'id', 'name'],
'ref:self' : ['kind'],
'ref:eventDevice' : ['kind'],
'trigger:deviceAttribute' : ['kind', 'ref', 'attribute', 'op', 'value', 'discipline', 'eventType', 'condition', 'disabled'],
'trigger:threshold' : ['kind', 'ref', 'attribute', 'op', 'value', 'condition', 'disabled'],
'trigger:duration' : ['kind', 'seconds', 'predicate', 'condition', 'disabled'],
'trigger:button' : ['kind', 'ref', 'number', 'action', 'condition', 'disabled'],
'trigger:schedule' : ['kind', 'at', 'offsetMinutes', 'days', 'date', 'condition', 'disabled'],
'trigger:periodic' : ['kind', 'cron', 'condition', 'disabled'],
'trigger:mode' : ['kind', 'to', 'condition', 'disabled'],
'trigger:rulePaused' : ['kind', 'ref', 'value', 'condition', 'disabled'],
'trigger:hubVariable' : ['kind', 'ref', 'condition', 'disabled'],
'trigger:ruleInvocation' : ['kind', 'condition', 'disabled'],
'trigger:endpoint' : ['kind', 'condition', 'disabled'],
'trigger:hsmStatus' : ['kind', 'to', 'condition', 'disabled'],
'trigger:hsmAlert' : ['kind', 'alerts', 'condition', 'disabled'],
'trigger:locationEvent' : ['kind', 'events', 'condition', 'disabled'],
'condition:all' : ['type', 'of', 'rmGrouping'],
'condition:any' : ['type', 'of', 'rmGrouping'],
'condition:not' : ['type', 'of'],
'condition:deviceAttribute': ['type', 'ref', 'refs', 'match', 'attribute', 'everyday', 'op', 'value', 'freshSeconds', 'unknownAs'],
'condition:variable' : ['type', 'ref', 'op', 'value', 'unknownAs'],
'condition:waitOutcome' : ['type', 'value'],
'condition:mode' : ['type', 'refs', 'ref', 'value'],
'condition:rulePaused' : ['type', 'ref', 'value'],
'condition:timeWindow' : ['type', 'from', 'to', 'fromOffsetMinutes', 'toOffsetMinutes'],
'condition:xor' : ['type', 'of'],
'condition:count' : ['type', 'of', 'min', 'max'],
'condition:dayOfWeek' : ['type', 'days'],
'condition:dateRange' : ['type', 'from', 'to'],
'condition:timeSince' : ['type', 'ref', 'attribute', 'value', 'op', 'seconds'],
'condition:triggeredBy' : ['type', 'triggers'],
'condition:hsmStatus' : ['type', 'value'],
'action:command' : ['type', 'ref', 'refs', 'command', 'args', 'approval', 'meterMs', 'onlyIfOn', 'passthrough', 'disabled'],
'action:setVariable' : ['type', 'ref', 'value', 'valueType', 'op', 'args', 'disabled'],
'action:wait:delay' : ['type', 'waitKind', 'seconds', 'modeSeconds', 'randomSeconds', 'cancelable', 'onRestart', 'disabled'],
'action:wait:forEvent' : ['type', 'waitKind', 'ref', 'attribute', 'value', 'timeoutSeconds', 'onTimeout', 'onRestart', 'disabled'],
'action:wait:forEvents' : ['type', 'waitKind', 'events', 'match', 'timeoutSeconds', 'onTimeout', 'onRestart', 'disabled'],
'action:wait:until' : ['type', 'waitKind', 'predicate', 'staysSeconds', 'timeoutSeconds', 'onTimeout', 'onRestart', 'disabled'],
'action:later' : ['type', 'seconds', 'modeSeconds', 'randomSeconds', 'cancelable', 'actions', 'onRestart', 'disabled'],
'action:cancelDelays' : ['type', 'disabled'],
'action:if' : ['type', 'condition', 'then', 'elseIf', 'otherwise', 'disabled'],
'action:repeat:every' : ['type', 'repeatKind', 'everySeconds', 'times', 'steps', 'onRestart', 'disabled'],
'action:repeat:while' : ['type', 'repeatKind', 'condition', 'everySeconds', 'steps', 'onRestart', 'disabled'],
'action:repeat:until' : ['type', 'repeatKind', 'condition', 'everySeconds', 'steps', 'onRestart', 'disabled'],
'action:stopRepeat' : ['type', 'disabled'],
'action:elseIf' : ['condition', 'then'],
'action:runRule' : ['type', 'ref', 'input', 'wait', 'result', 'timeoutSeconds', 'onTimeout', 'disabled'],
'action:return' : ['type', 'value', 'ref', 'disabled'],
'action:log' : ['type', 'text', 'level', 'disabled'],
'action:setMode' : ['type', 'ref', 'value', 'disabled'],
'action:customCommand' : ['type', 'ref', 'command', 'args', 'disabled'],
'action:capture' : ['type', 'refs', 'disabled'],
'action:restore' : ['type', 'refs', 'disabled'],
'action:exit' : ['type', 'disabled'],
'action:comment' : ['type', 'text', 'disabled'],
'action:setRuleBoolean' : ['type', 'rules', 'value', 'disabled'],
'action:controlRule' : ['type', 'rules', 'op', 'disabled'],
'action:http' : ['type', 'method', 'url', 'headers', 'body', 'contentType', 'result', 'field', 'saveFile', 'select', 'rssItems', 'timeoutSeconds', 'disabled'],
'action:ping' : ['type', 'host', 'into', 'timeoutSeconds', 'disabled'],
'action:file' : ['type', 'op', 'name', 'text', 'disabled'],
'action:hsm' : ['type', 'op', 'disabled'],
'action:byMode' : ['type', 'cases', 'otherwise', 'disabled'],
'action:modeCase' : ['modes', 'then'],
'action:flashSwitches' : ['type', 'refs', 'seconds', 'times', 'disabled'],
'action:deviceEnabled' : ['type', 'refs', 'enabled', 'disabled'],
'action:fade' : ['type', 'refs', 'attribute', 'to', 'seconds', 'stepSeconds', 'disabled'],
'action:stopGradual' : ['type', 'what', 'disabled'],
'action:track' : ['type', 'refs', 'attribute', 'disabled'],
]
@Field static final List ACTION_TYPES = ['command', 'setVariable', 'wait', 'if', 'runRule', 'exit', 'comment', 'setRuleBoolean', 'controlRule', 'return', 'log', 'setMode', 'customCommand', 'capture', 'restore', 'repeat', 'stopRepeat', 'http', 'ping', 'file', 'later', 'cancelDelays', 'hsm',
'byMode', 'flashSwitches', 'fade', 'stopGradual', 'track', 'deviceEnabled']
@Field static final List RULE_CONTROL_OPS = ['pause', 'resume', 'cancelTimers', 'runActions', 'disable', 'enable']
@Field static final List WAIT_KINDS = ['delay', 'forEvent', 'forEvents', 'until']
@Field static final List WAIT_EVENT_KINDS = ['deviceAttribute', 'threshold', 'button', 'mode', 'hubVariable', 'schedule', 'duration']
@Field static final List REPEAT_KINDS = ['every', 'while', 'until']
@Field static final List FADE_ATTRIBUTES = ['level', 'colorTemperature']
@Field static final List GRADUAL_WHAT = ['level', 'colorTemperature', 'flash']
@Field static final int FADE_MAX_STEPS = 100
@Field static final List TRACK_ATTRIBUTES = ['switch', 'level']
@Field static final List HTTP_METHODS = ['get', 'post']
@Field static final List HTTP_CONTENT_TYPES = ['application/json', 'application/x-www-form-urlencoded', 'text/plain', 'text/xml']
@Field static final List PING_RESULTS = ['transmitted', 'received', 'loss', 'rttMin', 'rttAvg', 'rttMax']
@Field static final List FILE_OPS = ['write', 'append', 'delete']
@Field static final int NET_DEFAULT_TIMEOUT_SECONDS = 10
@Field static final int KEEP_MAX_CHARS = 1024
@Field static final int NET_MAX_BODY_CHARS = 65536
@Field static final List TIMEOUT_BEHAVIOURS = ['continue', 'stopRun', 'failurePath']
@Field static final List RESTART_POLICIES = ['catchUp', 'skip', 'reevaluate']
@Field static final List VARIABLE_TYPES = ['string', 'number', 'boolean', 'date', 'time', 'datetime']
@Field static final List VARIABLE_SCOPES = ['hub', 'rule', 'run']
@Field static final List LOSSLESS_COERCIONS = [
'number>string', 'boolean>string', 'date>string', 'time>string', 'datetime>string',
'date>datetime', 'time>datetime',
]
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
boolean schemaIsDiscreteTrigger(String kind) { return DISCRETE_TRIGGER_KINDS.contains(kind) }
Map schemaOperation(String command) { return CATALOGUE.get(command) }
@Field static final String HUB_VARIABLE_PREFIX = 'hv:'
@Field static final String RULE_VARIABLE_PREFIX = 'rv:'
@Field static final String PRIVATE_BOOLEAN_ID = 'rv:privateBoolean'
@Field static final String RULE_INPUT_ID = 'rv:input'
@Field static final String RULE_STATE_PREFIX = 'rule:'
@Field static final String RULE_PAUSED_ATTRIBUTE = 'paused'
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
List schemaStepCommands(Map a) {
if (a.type == 'flashSwitches' || (a.type == 'track' && a.attribute == 'switch')) return ['on', 'off']
if (a.type == 'track' && a.attribute == 'level') return ['setLevel']
if (a.type == 'fade') return [a.attribute == 'colorTemperature' ? 'setColorTemperature' : 'setLevel']
return []
}
boolean schemaDeviceOffers(String op, Object offered) {
if (offered == null) return schemaOperation(op)?.mustOffer != true
Collection have = (Collection) offered
if (have.contains(op)) return true
List basis = COMMAND_BASIS.get(op)
return basis != null && have.containsAll(basis)
}
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
boolean schemaCoercionIsLossless(String from, String to) {
if (from == to) return true
return LOSSLESS_COERCIONS.contains(from + '>' + to)
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
@Field static final int RT_STAGE_FAILURE_NOTES = 8
@Field static final List EDITOR_SUBMIT_OPS = ['run', 'pause', 'resume', 'stop', 'start', 'remove', 'runNow', 'cancel', 'setBoolean', 'trigger', 'setVariable']
@Field static final List RT_ARRIVE_STATES = ['running', 'paused', 'stopped']
@Field static final Map USE_KIND = [
command: 'device', forEvent: 'device', deviceAttribute: 'device', threshold: 'device', button: 'device',
setVariable: 'variable', variable: 'variable', hubVariable: 'variable',
mode: 'mode', runRule: 'rule', rulePaused: 'rule', setMode: 'mode',
]
Map vResult() {
return [findings: [], complete: true, incompleteReason: null, workUsed: 0, exhausted: false,
conditionNodes: 0, actionNodes: 0, triggerCount: null]
}
@Field static final Map V_AUDIENCE = [
SCHEMA_ID: 'document', SCHEMA_MISSING: 'document', SCHEMA_TYPE: 'document', SCHEMA_UNKNOWN_FIELD: 'document',
SCHEMA_ENUM: 'document', SCHEMA_ARGS: 'document', ARGS_TYPE: 'document', TRIGGER_DISCIPLINE_ON_DISCRETE: 'document',
TRIGGER_INDEX_DUPLICATE: 'document', TRIGGER_INDEX_RANGE: 'document', CONDITION_MATCH_UNDECLARED: 'document',
RULE_CALL_NOT_STATIC: 'document', REF_UNDECLARED: 'document', VALUE_TYPE_MISMATCH: 'document',
APPROVAL_IGNORED: 'internal', REF_UNUSED: 'internal', CATALOGUE_PASSTHROUGH: 'internal', CUSTOM_ARG_ABOVE_RM_LIMIT: 'internal',
]
void vAdd(Map r, String code, String severity, String path, String message, Map detail = null) {
if (((List) r.findings).any { Map g -> g.code == code && g.severity == severity && g.path == path && g.message == message }) return
Map f = [code: code, severity: severity, path: path, message: message, audience: V_AUDIENCE.get(code) ?: 'person']
if (detail) f.detail = detail
r.findings << f
}
void vBlock(Map r, String code, String path, String msg, Map detail = null) {
vAdd(r, code, SEV_BLOCK, path, msg, detail)
}
void vWarn(Map r, String code, String path, String msg, Map detail = null) {
vAdd(r, code, SEV_WARN, path, msg, detail)
}
boolean vTick(Map r) {
r.workUsed = r.workUsed + 1
if (r.workUsed > LIMIT_VALIDATION_WORK) {
if (!r.exhausted) {
r.exhausted = true
r.complete = false
r.incompleteReason = "validation work limit of ${LIMIT_VALIDATION_WORK} exceeded".toString()
}
return false
}
return true
}
Map vMap(Map r, Object x, String path, String what) {
if (x instanceof Map) return x
vBlock(r, 'SCHEMA_TYPE', path, "${what} must be an object")
return null
}
List vList(Map r, Object x, String path, String what) {
if (x instanceof List) return x
vBlock(r, 'SCHEMA_TYPE', path, "${what} must be a list")
return null
}
boolean vIsInteger(Object x) {
if (x instanceof Integer || x instanceof Long) return true
if (x instanceof BigDecimal) return ((BigDecimal) x).stripTrailingZeros().scale() <= 0
return false
}
boolean vIsNumber(Object x) { return x instanceof Number }
boolean vIntField(Map r, Object x, String path, String what, String rangeKey) {
List range = FIELD_RANGES.get(rangeKey)
return vIntRange(r, x, path, what, range[0] as long, range[1] as long)
}
boolean vIntRange(Map r, Object x, String path, String what, long lo, long hi) {
if (!vIsInteger(x)) {
vBlock(r, 'SCHEMA_TYPE', path, "${what} must be a whole number, not ${x}")
return false
}
long n = (x as BigDecimal).longValue()
if (n < lo || n > hi) {
vBlock(r, 'LIMIT_RANGE', path, "${what} must be between ${lo} and ${hi}, not ${n}")
return false
}
return true
}
void vFields(Map r, Map node, String kind, String path) {
List allowed = ALLOWED_FIELDS.get(kind)
if (allowed == null) return
node.keySet().each { Object k ->
if (!allowed.contains(k)) {
vBlock(r, 'SCHEMA_UNKNOWN_FIELD', "${path}.${k}",
"'${k}' is not a field this engine understands here, so it would be silently ignored")
}
}
}
boolean vTimeSpec(Object x) {
if (x instanceof Map) return ((Map) x).ref instanceof String && ((Map) x).size() == 1
if (!(x instanceof String)) return false
String s = (String) x
if (SUN_EVENTS.contains(s)) return true
if (!(s ==~ /\d{2}:\d{2}/)) return false
int h = Integer.parseInt(s.substring(0, 2))
int m = Integer.parseInt(s.substring(3, 5))
return h < 24 && m < 60
}
String vLiteralType(Object v) {
if (v instanceof Boolean) return 'boolean'
if (v instanceof Number) return 'number'
if (v instanceof String) return 'string'
return null
}
Map validateRule(Object docIn, Map inv) {
Map r = vResult()
try {
Map doc = vMap(r, docIn, '', 'the rule')
if (doc == null) return r
vCheckSchema(r, doc)
if (vBlocking(r).isEmpty() && !r.exhausted) {
vCheckReferences(r, doc, inv)
vCheckUses(r, doc, inv)
vCheckStructure(r, doc)
vCheckBehaviour(r, doc)
vCheckSafety(r, doc, inv)
}
} catch (Exception e) {
vBlock(r, 'VALIDATOR_INPUT', '', "the rule could not be checked: ${e.message}")
r.complete = false
r.incompleteReason = 'validator input error'
}
return r
}
void vCheckSchema(Map r, Map doc) {
if (!vTick(r)) return
if (doc.schema != SCHEMA_ID) {
vBlock(r, 'SCHEMA_ID', 'schema',
"this document declares schema '${doc.schema}', and this engine reads '${SCHEMA_ID}'")
return
}
vFields(r, doc, 'rule', '')
if (!(doc.id instanceof String) || ((String) doc.id).isEmpty()) vBlock(r, 'SCHEMA_MISSING', 'id', 'every rule needs an id')
if (!(doc.name instanceof String) || ((String) doc.name).isEmpty()) vBlock(r, 'SCHEMA_MISSING', 'name', 'every rule needs a name')
vEnum(r, doc, 'rerunPolicy', RERUN_POLICIES)
vEnum(r, doc, 'errorPolicy', ERROR_POLICIES)
vEnum(r, doc, 'onUnknown', UNKNOWN_POLICIES)
vEnum(r, doc, 'ifUnknown', IF_UNKNOWN_POLICIES)
vEnum(r, doc, 'conditionTiming', CONDITION_TIMING)
vEnum(r, doc, 'pauseMode', PAUSE_MODES)
['enabled', 'initialEvaluation'].each { String k ->
if (doc.containsKey(k) && !(doc[k] instanceof Boolean)) vBlock(r, 'SCHEMA_TYPE', k, "${k} must be true or false")
}
String policy = (doc.rerunPolicy ?: SCHEMA_DEFAULTS.rerunPolicy) as String
if (policy == 'queue') vLimit(r, doc, 'queueLimit', LIMIT_QUEUE_DEPTH, policy)
if (policy == 'parallel') vLimit(r, doc, 'parallelLimit', LIMIT_PARALLEL_RUNS, policy)
if (policy != 'queue' && doc.containsKey('queueLimit')) {
vWarn(r, 'SCHEMA_UNUSED', 'queueLimit', "the number of runs that may wait applies only when a new trigger waits its turn, so it does nothing here")
}
if (policy != 'parallel' && doc.containsKey('parallelLimit')) {
vWarn(r, 'SCHEMA_UNUSED', 'parallelLimit', "the number of runs at once applies only when runs may overlap, so it does nothing here")
}
if (doc.refs != null) vMap(r, doc.refs, 'refs', 'refs')
if (doc.triggers == null) {
vBlock(r, 'SCHEMA_MISSING', 'triggers', "a rule needs a trigger. If it should run only when another rule calls it, give it the " +
"'Another rule runs this one' trigger")
} else {
List triggers = vList(r, doc.triggers, 'triggers', 'triggers')
if (triggers != null && triggers.isEmpty()) {
vBlock(r, 'SCHEMA_MISSING', 'triggers', "a rule needs a trigger. If it should run only when another rule calls it, give it the " +
"'Another rule runs this one' trigger")
} else if (triggers != null) {
if (triggers.size() > LIMIT_TRIGGERS_PER_RULE) {
vBlock(r, 'LIMIT_TRIGGERS', 'triggers', "${triggers.size()} triggers, limit is ${LIMIT_TRIGGERS_PER_RULE}")
}
r.triggerCount = triggers.size()
triggers.eachWithIndex { t, i -> vCheckTrigger(r, t, "triggers.${i}".toString()) }
}
}
if (doc.conditions != null) vCheckCondition(r, doc.conditions, 'conditions')
if (doc.requiredExpression != null) vCheckCondition(r, doc.requiredExpression, 'requiredExpression')
if (doc.cancelOnFalse != null && !(doc.cancelOnFalse instanceof Boolean)) vBlock(r, 'SCHEMA_TYPE', 'cancelOnFalse', 'cancelOnFalse is true or false')
['logEvents', 'logTriggers', 'logActions'].each { String k ->
if (doc.containsKey(k) && !(doc[k] instanceof Boolean)) vBlock(r, 'SCHEMA_TYPE', k, "${k} must be true or false")
}
if (doc.cancelOnFalse == true && doc.requiredExpression == null) vWarn(r, 'SCHEMA_UNUSED', 'cancelOnFalse', 'cancelOnFalse needs a required expression to act on')
if (doc.actions != null) vCheckActions(r, doc.actions, 'actions')
else vBlock(r, 'SCHEMA_MISSING', 'actions', 'a rule with no actions does nothing')
String errPolicy = (doc.errorPolicy ?: 'stopRun') as String
if (doc.onFailure != null) {
if (errPolicy != 'failurePath') {
vWarn(r, 'SCHEMA_UNUSED', 'onFailure', "onFailure only runs when errorPolicy is failurePath")
}
vCheckActions(r, doc.onFailure, 'onFailure')
} else if (errPolicy == 'failurePath') {
vBlock(r, 'SCHEMA_MISSING', 'onFailure', 'a failurePath error policy needs onFailure actions')
}
if (doc.onCancel != null) {
vCheckActions(r, doc.onCancel, 'onCancel')
if (doc.requiredExpression == null && doc.cancelOnFalse != true) {
vWarn(r, 'SCHEMA_UNUSED', 'onCancel',
'nothing in this rule cancels a run: onCancel runs only when a run is cancelled, which needs a required expression with cancelOnFalse, or another rule cancelling this one')
}
}
vCheckReentry(r, doc)
if (r.conditionNodes > LIMIT_CONDITION_NODES) {
vBlock(r, 'LIMIT_CONDITIONS', 'conditions',
"${r.conditionNodes} condition nodes across the whole rule, limit is ${LIMIT_CONDITION_NODES}")
}
if (r.actionNodes > LIMIT_ACTION_NODES) {
vBlock(r, 'LIMIT_ACTIONS', 'actions',
"${r.actionNodes} actions across the whole rule, limit is ${LIMIT_ACTION_NODES}")
}
}
void vEnum(Map r, Map doc, String key, List allowed) {
if (!vTick(r)) return
if (!doc.containsKey(key)) return
if (!allowed.contains(doc[key])) {
vBlock(r, 'SCHEMA_ENUM', key, "'${doc[key]}' is not a ${key}; allowed values are ${allowed.join(', ')}")
}
}
void vLimit(Map r, Map doc, String key, int max, String policy) {
if (!vTick(r)) return
Object v = doc[key]
if (v == null) {
vBlock(r, 'SCHEMA_MISSING', key, "a '${policy}' re-run policy must declare ${key}")
return
}
vIntRange(r, v, key, key, 1L, max as long)
}
void vCheckTrigger(Map r, Object tIn, String path) {
if (!vTick(r)) return
Map t = vMap(r, tIn, path, 'a trigger')
if (t == null) return
String kind = t.kind as String
if (!TRIGGER_KINDS.contains(kind)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.kind",
"'${kind}' is not a trigger kind; allowed are ${TRIGGER_KINDS.join(', ')}")
return
}
vFields(r, t, "trigger:${kind}".toString(), path)
if (t.condition != null) vCheckCondition(r, t.condition, "${path}.condition".toString())
if (schemaIsDiscreteTrigger(kind) && t.containsKey('discipline')) {
vBlock(r, 'TRIGGER_DISCIPLINE_ON_DISCRETE', "${path}.discipline",
"a ${kind} trigger is a discrete occurrence and cannot declare event or transition discipline")
}
if (t.containsKey('discipline') && !DISCIPLINES.contains(t.discipline)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.discipline",
"'${t.discipline}' is not a discipline; allowed are ${DISCIPLINES.join(', ')}")
}
if (kind == 'deviceAttribute' || kind == 'threshold') {
if (!(t.ref instanceof String)) vBlock(r, 'SCHEMA_MISSING', "${path}.ref", "a ${kind} trigger needs a device reference")
if (!(t.attribute instanceof String)) vBlock(r, 'SCHEMA_MISSING', "${path}.attribute", "a ${kind} trigger needs an attribute")
}
if (kind == 'deviceAttribute' && t.op != null) {
if (t.op in ['contains', 'isEmpty']) vBlock(r, 'SCHEMA_ENUM', "${path}.op", "a device trigger compares with is, isNot, above, below, atLeast, atMost, between or notBetween, not '${t.op}'")
else vCheckComparison(r, t, path)
}
if (kind == 'threshold') {
if (!THRESHOLD_OPS.contains(t.op)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.op", "a threshold needs op ${THRESHOLD_OPS.join(', ')}")
}
if (!vIsNumber(t.value)) vBlock(r, 'SCHEMA_TYPE', "${path}.value", 'a threshold value must be a number')
if (t.attribute instanceof String && NON_READING_ATTRIBUTES.contains(t.attribute)) {
vBlock(r, 'THRESHOLD_NOT_A_READING', "${path}.attribute",
"'${t.attribute}' reports a state or an event, not a reading, so it cannot rise above or fall below a number")
}
}
if (kind == 'duration') {
if (t.seconds == null) vBlock(r, 'SCHEMA_MISSING', "${path}.seconds", 'a duration trigger needs a period')
else vIntField(r, t.seconds, "${path}.seconds".toString(), 'a duration', 'durationSeconds')
if (t.predicate == null) vBlock(r, 'SCHEMA_MISSING', "${path}.predicate", 'a duration trigger needs a predicate to hold true')
else vCheckCondition(r, t.predicate, "${path}.predicate".toString())
}
if (kind == 'button') {
if (!(t.ref instanceof String)) vBlock(r, 'SCHEMA_MISSING', "${path}.ref", 'a button trigger needs a device reference')
if (t.number == null) vBlock(r, 'SCHEMA_MISSING', "${path}.number", 'a button trigger needs a button number')
else vIntField(r, t.number, "${path}.number".toString(), 'a button number', 'buttonNumber')
if (t.action != null && !BUTTON_ACTIONS.contains(t.action)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.action", "'${t.action}' is not a button action; allowed are ${BUTTON_ACTIONS.join(', ')}")
}
}
if (kind == 'schedule') {
if (!vTimeSpec(t.at)) vBlock(r, 'SCHEMA_TYPE', "${path}.at", "a schedule needs at as HH:mm, sunrise or sunset, not '${t.at}'")
if (t.offsetMinutes != null) {
if (!(t.offsetMinutes instanceof Map && vTimeSpec(t.offsetMinutes))) vIntField(r, t.offsetMinutes, "${path}.offsetMinutes".toString(), 'an offset', 'offsetMinutes')
if (t.at instanceof String && !SUN_EVENTS.contains(t.at)) vWarn(r, 'SCHEMA_UNUSED', "${path}.offsetMinutes", 'an offset only applies to sunrise, sunset or a time from a variable')
}
if (t.days != null && (!(t.days instanceof List) || ((List) t.days).isEmpty() || !((List) t.days).every { CORE_DAY_NAMES.contains(it) })) {
vBlock(r, 'SCHEMA_TYPE', "${path}.days", "days is a list of ${CORE_DAY_NAMES.join(', ')}")
}
if (t.date != null) {
if (vDateForm(t.date) == null) vBlock(r, 'SCHEMA_TYPE', "${path}.date", "date is yyyy-MM-dd for one day, or MM-dd for every year, not '${t.date}'")
else if (t.days != null) vBlock(r, 'SCHEMA_FIELD', "${path}.days", 'give a date or days of the week, not both')
}
}
if (kind == 'periodic') {
Map c = timeCronParse(t.cron)
if (c.error) vBlock(r, 'CRON_INVALID', "${path}.cron", c.error as String)
else if (((List) c.sec).size() > 1) vWarn(r, 'PERIODIC_FREQUENT', "${path}.cron", 'this runs more than once a minute, which keeps the hub busy; use it only if you need it')
}
if (kind == 'mode') {
if (t.to != null) {
List to = vList(r, t.to, "${path}.to".toString(), 'to')
if (to != null && !to.every { it instanceof String }) vBlock(r, 'SCHEMA_TYPE', "${path}.to", 'mode names must be text')
}
}
if (kind == 'hubVariable' && !(t.ref instanceof String)) {
vBlock(r, 'SCHEMA_MISSING', "${path}.ref", 'a hub variable trigger needs a variable reference')
}
if (kind == 'deviceAttribute' && t.eventType != null && !EVENT_TYPES.contains(t.eventType)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.eventType", "'${t.eventType}' is not an event type; use physical or digital")
}
if (kind == 'hsmStatus') vNames(r, t.to, "${path}.to".toString(), HSM_STATUSES, 'an HSM status', false)
if (kind == 'hsmAlert') vNames(r, t.alerts, "${path}.alerts".toString(), HSM_ALERTS, 'an HSM alert', false)
if (kind == 'locationEvent') vNames(r, t.events, "${path}.events".toString(), LOCATION_EVENTS, 'a location event', true)
}
void vNames(Map r, Object v, String path, List allowed, String what, boolean required) {
if (v == null && !required) return
if (!(v instanceof List) || ((List) v).isEmpty()) { vBlock(r, 'SCHEMA_MISSING', path, "choose at least one: ${allowed.join(', ')}"); return }
((List) v).findAll { !allowed.contains(it) }.each { vBlock(r, 'SCHEMA_ENUM', path, "'${it}' is not ${what}; allowed are ${allowed.join(', ')}") }
}
void vCheckCondition(Map r, Object nodeIn, String path) {
if (!vTick(r)) return
Map node = vMap(r, nodeIn, path, 'a condition')
if (node == null) return
r.conditionNodes = (r.conditionNodes as int) + 1
String type = node.type as String
if (!CONDITION_TYPES.contains(type)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.type",
"'${type}' is not a condition type; allowed are ${CONDITION_TYPES.join(', ')}")
return
}
vFields(r, node, "condition:${type}".toString(), path)
if (type == 'all' || type == 'any' || type == 'xor') {
List of = vList(r, node.of, "${path}.of".toString(), "an ${type} condition's members")
if (type == 'xor' && of != null && of.size() < 2) vBlock(r, 'SCHEMA_MISSING', "${path}.of", 'an xor compares at least two conditions')
if (of != null) of.eachWithIndex { c, i -> vCheckCondition(r, c, "${path}.of.${i}".toString()) }
return
}
if (type == 'count') {
List of = vList(r, node.of, "${path}.of".toString(), "a count condition's members")
int n = of == null ? 0 : of.size()
if (of != null && n < 2) vBlock(r, 'SCHEMA_MISSING', "${path}.of", "'at least' or 'exactly one' needs two or more conditions to count")
Integer min = vIsInteger(node.min) ? (node.min as BigDecimal).intValue() : null
if (node.min == null) vBlock(r, 'SCHEMA_MISSING', "${path}.min", "a count condition needs 'min', how many of its conditions must be true")
else if (min == null) vBlock(r, 'SCHEMA_TYPE', "${path}.min", "'min' must be a whole number")
else if (min < 1 || (n >= 2 && min > n)) vBlock(r, 'LIMIT_RANGE', "${path}.min", "'min' must be from 1 to ${Math.max(n, 1)}, the number of conditions it counts".toString())
if (node.max != null) {
Integer max = vIsInteger(node.max) ? (node.max as BigDecimal).intValue() : null
if (max == null) vBlock(r, 'SCHEMA_TYPE', "${path}.max", "'max' must be a whole number")
else if ((min != null && max < min) || (n >= 2 && max > n) || max < 1) {
vBlock(r, 'LIMIT_RANGE', "${path}.max", "'max' must be from ${min ?: 1} to ${Math.max(n, 1)}: no fewer than 'min', no more than the conditions it counts".toString())
}
}
if (of != null) of.eachWithIndex { c, i -> vCheckCondition(r, c, "${path}.of.${i}".toString()) }
return
}
if (type == 'not') {
if (node.of == null) vBlock(r, 'SCHEMA_MISSING', "${path}.of", 'a not condition needs one member')
else vCheckCondition(r, node.of, "${path}.of".toString())
return
}
if (type == 'deviceAttribute') {
List keys = vConditionKeys(r, node, path)
if (keys.isEmpty()) vBlock(r, 'SCHEMA_MISSING', "${path}.refs", 'a device condition needs at least one device reference')
if (keys.size() > 1 && node.match == null) {
vBlock(r, 'CONDITION_MATCH_UNDECLARED', "${path}.match",
'a condition over several devices must say whether it means any of them or all of them')
}
if (node.match != null && !MATCH_MODES.contains(node.match)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.match", "'${node.match}' is not a match mode; use any or all")
}
if (!(node.attribute instanceof String)) vBlock(r, 'SCHEMA_MISSING', "${path}.attribute", 'a device condition needs an attribute')
vCheckComparison(r, node, path)
if (node.freshSeconds != null) vIntField(r, node.freshSeconds, "${path}.freshSeconds".toString(), 'freshness', 'freshSeconds')
if (node.everyday != null) vCheckEveryday(r, node, path)
}
if (type == 'variable') {
if (!(node.ref instanceof String)) vBlock(r, 'SCHEMA_MISSING', "${path}.ref", 'a variable condition needs a variable reference')
vCheckComparison(r, node, path)
}
if ((type == 'deviceAttribute' || type == 'variable') && node.unknownAs != null && !UNKNOWN_AS.contains(node.unknownAs)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.unknownAs", "'${node.unknownAs}' is not allowed; use ${UNKNOWN_AS.join(' or ')}")
}
if (type == 'mode') {
if (node.refs != null && !(node.refs instanceof List)) {
vBlock(r, 'SCHEMA_TYPE', "${path}.refs", 'the modes must be a list of mode references')
} else if (node.refs instanceof List && !((List) node.refs).every { it instanceof String }) {
vBlock(r, 'SCHEMA_TYPE', "${path}.refs", 'each mode must be a reference key')
} else if (!(node.ref instanceof String) && !(node.value instanceof String) &&
!(node.refs instanceof List && !((List) node.refs).isEmpty())) {
vBlock(r, 'SCHEMA_MISSING', "${path}.ref", 'a mode condition needs at least one mode')
}
}
if (type == 'timeWindow') {
if (!vTimeSpec(node.from) || !vTimeSpec(node.to)) {
vBlock(r, 'SCHEMA_TYPE', path, 'a time window needs from and to as HH:mm, sunrise or sunset')
} else if (node.from == node.to && node.fromOffsetMinutes == node.toOffsetMinutes) {
vWarn(r, 'WINDOW_EMPTY', path, 'this window starts and ends at the same moment, so it is never open')
}
['fromOffsetMinutes', 'toOffsetMinutes'].each { String k ->
if (node[k] != null && !(node[k] instanceof Map && vTimeSpec(node[k]))) vIntField(r, node[k], "${path}.${k}".toString(), 'an offset', 'offsetMinutes')
}
}
if (type == 'triggeredBy') vCheckTriggeredBy(r, node, path)
if (type == 'hsmStatus') vNames(r, node.value, "${path}.value".toString(), HSM_STATUSES, 'an HSM status', true)
if (type == 'timeSince') {
if (!(node.ref instanceof String)) vBlock(r, 'SCHEMA_MISSING', "${path}.ref", 'time since needs a device')
if (node.seconds == null) vBlock(r, 'SCHEMA_MISSING', "${path}.seconds", 'time since needs a length of time')
else vIntField(r, node.seconds, "${path}.seconds".toString(), 'a time', 'delaySeconds')
if (node.op != null && !(node.op in ['atLeast', 'below'])) vBlock(r, 'SCHEMA_ENUM', "${path}.op", "time since is atLeast (the default) or below, not '${node.op}'")
if (node.containsKey('value') && node.attribute == null) vBlock(r, 'SCHEMA_FIELD', "${path}.value", 'a value needs its attribute')
if (path.contains('predicate')) vBlock(r, 'TIME_SINCE_PLACEMENT', path, 'time since is checked when something happens, so it cannot be waited on; use it in Only if or an If')
}
if (type == 'dayOfWeek' && (!(node.days instanceof List) || ((List) node.days).isEmpty() || !((List) node.days).every { CORE_DAY_NAMES.contains(it) })) {
vBlock(r, 'SCHEMA_TYPE', "${path}.days", "days is a list of ${CORE_DAY_NAMES.join(', ')}")
}
if (type == 'dateRange') {
if (node.from == null) vBlock(r, 'SCHEMA_MISSING', "${path}.from", 'a date condition needs a date')
List forms = []
['from', 'to'].each { String k ->
Object x = node[k]
if (x == null || (x instanceof Map && ((Map) x).ref instanceof String && ((Map) x).size() == 1)) return
String f = vDateForm(x)
if (f == null) vBlock(r, 'SCHEMA_TYPE', "${path}.${k}", "a date is yyyy-MM-dd, MM-dd for every year, or {\"ref\": a variable}, not '${x}'")
else forms << f
}
if (forms.size() == 2 && forms[0] != forms[1]) vBlock(r, 'SCHEMA_TYPE', path, 'give both dates with a year, or both without')
}
}
String vDateForm(Object x) {
String d = x as String
try {
if (d ==~ /\d{4}-\d{2}-\d{2}/) { java.time.LocalDate.parse(d); return 'full' }
if (d ==~ /\d{2}-\d{2}/) { java.time.LocalDate.parse("2024-${d}".toString()); return 'yearly' }
} catch (Exception e) { }
return null
}
void vCheckTriggeredBy(Map r, Map node, String path) {
if (path.contains('.predicate')) {
vBlock(r, 'TRIGGERED_BY_PLACEMENT', path,
'which trigger started a run is fixed for that run, so it cannot be a duration or wait predicate')
}
String tp = "${path}.triggers".toString()
if (node.triggers == null) {
vBlock(r, 'SCHEMA_MISSING', tp, 'a triggeredBy condition needs the list of trigger numbers it accepts')
return
}
List indexes = vList(r, node.triggers, tp, 'the trigger numbers')
if (indexes == null) return
if (indexes.isEmpty()) {
vBlock(r, 'SCHEMA_MISSING', tp, 'a triggeredBy condition with no trigger numbers can never be true')
return
}
Set seen = [] as Set
indexes.eachWithIndex { Object k, int i ->
String ip = "${tp}.${i}".toString()
if (!vIsInteger(k)) {
vBlock(r, 'SCHEMA_TYPE', ip, "a trigger number must be a whole number, not ${k}")
return
}
long n = (k as BigDecimal).longValue()
if (r.triggerCount != null && (n < 0 || n >= (r.triggerCount as long))) {
vBlock(r, 'TRIGGER_INDEX_RANGE', ip,
"this rule has ${r.triggerCount} trigger${r.triggerCount == 1 ? '' : 's'}, numbered from 0, so there is no trigger ${n}")
return
}
if (!seen.add(n)) vBlock(r, 'TRIGGER_INDEX_DUPLICATE', ip, "trigger ${n} is listed more than once")
}
}
List vConditionKeys(Map r, Map node, String path) {
List keys = []
if (node.ref != null) {
if (node.ref instanceof String) keys << node.ref
else vBlock(r, 'SCHEMA_TYPE', "${path}.ref", 'a reference must be a key name')
}
if (node.refs != null) {
List refs = vList(r, node.refs, "${path}.refs".toString(), 'refs')
if (refs != null) {
refs.each { k ->
if (k instanceof String) keys << k
else vBlock(r, 'SCHEMA_TYPE', "${path}.refs", 'every reference must be a key name')
}
}
}
return keys
}
void vCheckComparison(Map r, Map node, String path) {
String op = (node.op ?: 'is') as String
if (!COMPARATORS.contains(op)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.op", "'${op}' is not a comparator; allowed are ${COMPARATORS.join(', ')}")
return
}
if (op == 'isEmpty') {
if (node.containsKey('value')) vBlock(r, 'SCHEMA_FIELD', "${path}.value", "'isEmpty' takes no value")
return
}
if (!node.containsKey('value')) {
vBlock(r, 'SCHEMA_MISSING', "${path}.value", 'a comparison needs a value')
return
}
if (node.value instanceof Map) {
Map m = node.value
if (!(m.ref instanceof String) || !m.keySet().every { it in ['ref', 'attribute', 'offset'] } || (m.offset != null && !vIsNumber(m.offset))) {
vBlock(r, 'SCHEMA_TYPE', "${path}.value", 'compare with a value, {"ref": a variable key} or {"ref": a device key, "attribute": name}, with an optional numeric "offset"')
}
return
}
if (op == 'between' || op == 'notBetween') {
if (!(node.value instanceof List) || ((List) node.value).size() != 2 || !((List) node.value).every { vIsNumber(it) }) {
vBlock(r, 'SCHEMA_TYPE', "${path}.value", "'${op}' takes two numbers, [low, high]")
}
return
}
if (op in ['above', 'below', 'atLeast', 'atMost'] && !vIsNumber(node.value) &&
!(node.value instanceof String && node.value ==~ /(\d{4}-\d{2}-\d{2})?T?(\d{2}:\d{2}.*)?/ && node.value != '')) {
vBlock(r, 'SCHEMA_TYPE', "${path}.value", "'${op}' compares numbers, dates or times, so the value must be one")
}
if (!(node.value instanceof String || node.value instanceof Number || node.value instanceof Boolean)) {
vBlock(r, 'SCHEMA_TYPE', "${path}.value", 'a comparison value must be text, a number or true or false')
}
}
void vCheckDelay(Map r, Map a, String path) {
if (a.seconds == null && a.modeSeconds == null && a.randomSeconds == null) {
vBlock(r, 'SCHEMA_MISSING', "${path}.seconds", 'a delay needs a duration')
} else if (a.seconds instanceof Map) {
Map m = a.seconds
if (!(m.ref instanceof String) || !m.keySet().every { it in ['ref', 'unit'] } || !(m.unit in [null, 'seconds', 'minutes', 'hours'])) {
vBlock(r, 'SCHEMA_TYPE', "${path}.seconds", 'a delay from a variable is {"ref": a variable key, "unit": seconds, minutes or hours}')
}
} else if (a.seconds != null) vIntField(r, a.seconds, "${path}.seconds".toString(), 'a delay', 'delaySeconds')
if (a.modeSeconds != null) {
List ms = vList(r, a.modeSeconds, "${path}.modeSeconds".toString(), 'the lengths per mode')
ms?.eachWithIndex { Object o, int i ->
String mp = "${path}.modeSeconds.${i}".toString()
if (!(o instanceof Map) || !(((Map) o).ref instanceof String) || !((Map) o).keySet().every { it in ['ref', 'seconds'] }) {
vBlock(r, 'SCHEMA_TYPE', mp, 'a length per mode is {"ref": a mode key, "seconds": a number}')
} else vIntRange(r, ((Map) o).seconds, "${mp}.seconds".toString(), 'a delay in a mode', 0L, ((List) FIELD_RANGES.delaySeconds)[1] as long)
}
}
if (a.randomSeconds != null) vIntField(r, a.randomSeconds, "${path}.randomSeconds".toString(), 'the random part', 'delaySeconds')
if (a.cancelable != null && !(a.cancelable instanceof Boolean)) vBlock(r, 'SCHEMA_TYPE', "${path}.cancelable", 'cancelable is true or false')
}
void vCheckEveryday(Map r, Map node, String path) {
Map w = node.everyday instanceof String ? EVERYDAY_WORDS.get(node.everyday) : null
if (w == null) {
vBlock(r, 'SCHEMA_ENUM', "${path}.everyday", "'${node.everyday}' is not an everyday word; allowed are ${EVERYDAY_WORDS.keySet().join(', ')}")
return
}
if (!((List) w.attributes).contains(node.attribute) || (node.op ?: 'is') != w.op) {
vBlock(r, 'EVERYDAY_MISMATCH', path, "'${node.everyday}' compares ${((List) w.attributes).join(' or ')} with ${w.op}, not ${node.attribute} with ${node.op ?: 'is'}")
}
}
String vRegexProblem(Object pattern) {
if (pattern == null) { return 'a pattern is needed' }
if (!(pattern instanceof String)) { return 'a pattern is text' }
String pat = (String) pattern
if (pat.isEmpty()) { return 'a pattern is needed' }
if (pat.length() > REGEX_MAX_CHARS) {
return "a pattern may be at most ${REGEX_MAX_CHARS} characters; this one is ${pat.length()}".toString()
}
if (pat =~ REGEX_NESTED_QUANTIFIER) {
return 'this pattern repeats a group that already repeats, such as (a+)+. On some text that takes so long it stops the rule engine, so it is refused. Repeat the group or its contents, not both.'
}
try {
java.util.regex.Pattern.compile(pat)
} catch (Exception e) {
return "this is not a valid pattern: ${e.message}".toString()
}
return null
}
void vCheckReentry(Map r, Map doc) {
List trigs = (doc.triggers ?: [])
vWalkActions(r, doc) { Map a, String p ->
if (a.type != 'wait' || a.waitKind != 'forEvent' || a.ref == null) { return }
String attr = a.attribute as String
if (!(attr in BUTTON_ACTIONS)) { return }
trigs.eachWithIndex { Object to, int i ->
if (!(to instanceof Map)) { return }
Map t = to
if (t.disabled == true || t.kind != 'button') { return }
if (t.ref != a.ref || (t.action ?: 'pushed') != attr) { return }
if (a.value != null && t.number != null && a.value.toString() != t.number.toString()) { return }
vWarn(r, 'WAIT_REENTERS_TRIGGER', p,
("this press also starts this rule (trigger ${i + 1}). Acknowledging the wait may start " +
'another run once this one ends, and that run can undo what this one just did').toString())
}
}
}
void vCheckActions(Map r, Object listIn, String path) {
if (!vTick(r)) return
List actions = vList(r, listIn, path, 'actions')
if (actions == null) return
actions.eachWithIndex { a, i -> vCheckAction(r, a, "${path}.${i}".toString()) }
}
void vCheckAction(Map r, Object aIn, String path) {
if (!vTick(r)) return
Map a = vMap(r, aIn, path, 'an action')
if (a == null) return
r.actionNodes = (r.actionNodes as int) + 1
String type = a.type as String
if (!ACTION_TYPES.contains(type)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.type",
"'${type}' is not an action type; allowed are ${ACTION_TYPES.join(', ')}")
return
}
if (type == 'command') {
vFields(r, a, 'action:command', path)
if (a.containsKey('refs') && (!(a.refs instanceof List) || ((List) a.refs).isEmpty() || ((List) a.refs).size() > 64 || ((List) a.refs).any { !(it instanceof String) })) {
vBlock(r, 'SCHEMA_MISSING', "${path}.refs", 'choose from 1 to 64 devices')
} else if (!(a.ref instanceof String) && !a.containsKey('refs')) vBlock(r, 'SCHEMA_MISSING', "${path}.ref", 'a command needs a device reference')
if (a.meterMs != null) vIntRange(r, a.meterMs, "${path}.meterMs".toString(), 'the pause between devices', 0L, 10000L)
if (a.onlyIfOn != null && !(a.onlyIfOn instanceof Boolean)) vBlock(r, 'SCHEMA_TYPE', "${path}.onlyIfOn", 'onlyIfOn is true or false')
if (!(a.command instanceof String)) {
vBlock(r, 'SCHEMA_MISSING', "${path}.command", 'a command needs a command name')
return
}
Map op = schemaOperation(a.command as String)
if (op == null) {
if (a.passthrough == true) {
vWarn(r, 'CATALOGUE_PASSTHROUGH', "${path}.command",
("'${a.command}' is not in this engine's catalogue, so HAI cannot say what it does, " +
'whether it is safe to repeat, or what it affects. It is sent to the device as written ' +
'and never retried.').toString())
return
}
vBlock(r, 'CATALOGUE_UNKNOWN', "${path}.command",
"HAI does not know the command '${a.command}'. To send a command only this device offers, use \"Use a non-standard device command\"")
return
}
vCheckArgs(r, a, op, path)
if (a.approval != null) {
vWarn(r, 'APPROVAL_IGNORED', "${path}.approval",
'approvals were removed in HAI 1.0.0; this command runs without asking anyone, as it does in Rule Machine')
}
return
}
if (type == 'wait') {
String wk = a.waitKind as String
if (!WAIT_KINDS.contains(wk)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.waitKind", "'${wk}' is not a wait kind; allowed are ${WAIT_KINDS.join(', ')}")
return
}
vFields(r, a, "action:wait:${wk}".toString(), path)
if (a.onRestart != null && !RESTART_POLICIES.contains(a.onRestart)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.onRestart",
"'${a.onRestart}' is not a restart policy; allowed are ${RESTART_POLICIES.join(', ')}")
}
if (wk == 'delay') {
vCheckDelay(r, a, path)
return
}
if (a.timeoutSeconds == null) {
vWarn(r, 'WAIT_NO_TIMEOUT', "${path}.timeoutSeconds",
'this wait has no time limit, so the run stays suspended until the thing happens or the rule is cancelled')
if (a.onTimeout != null) {
vWarn(r, 'SCHEMA_UNUSED', "${path}.onTimeout",
'a wait with no timeout can never time out, so this has no effect')
}
} else {
vIntField(r, a.timeoutSeconds, "${path}.timeoutSeconds".toString(), 'a timeout', 'timeoutSeconds')
}
if (a.onTimeout != null && !TIMEOUT_BEHAVIOURS.contains(a.onTimeout)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.onTimeout", "'${a.onTimeout}' is not a timeout behaviour")
}
if (wk == 'until') {
if (a.predicate == null) vBlock(r, 'SCHEMA_MISSING', "${path}.predicate", 'a wait until needs a predicate')
else vCheckCondition(r, a.predicate, "${path}.predicate".toString())
if (a.staysSeconds != null) vIntField(r, a.staysSeconds, "${path}.staysSeconds".toString(), 'how long it must stay true', 'durationSeconds')
}
if (wk == 'forEvents') {
List events = vList(r, a.events, "${path}.events".toString(), 'the events')
if (events != null && (events.isEmpty() || events.size() > LIMIT_WAIT_EVENTS)) {
vBlock(r, 'SCHEMA_MISSING', "${path}.events", "a wait for events needs from 1 to ${LIMIT_WAIT_EVENTS} events".toString())
}
events?.eachWithIndex { Object e, int i ->
String ep = "${path}.events.${i}".toString()
if (e instanceof Map && !WAIT_EVENT_KINDS.contains(((Map) e).kind)) {
vBlock(r, 'SCHEMA_ENUM', "${ep}.kind", "a wait can wait for ${WAIT_EVENT_KINDS.join(', ')}, not '${((Map) e).kind}'".toString())
} else vCheckTrigger(r, e, ep)
}
if (a.match != null && !MATCH_MODES.contains(a.match)) vBlock(r, 'SCHEMA_ENUM', "${path}.match", "'${a.match}' is not a match mode; use any or all")
}
if (wk == 'forEvent') {
if (!(a.ref instanceof String)) vBlock(r, 'SCHEMA_MISSING', "${path}.ref", 'a wait for an event needs a device reference')
if (!(a.attribute instanceof String)) vBlock(r, 'SCHEMA_MISSING', "${path}.attribute", 'a wait for an event needs an attribute')
}
return
}
if (type == 'later') {
vFields(r, a, 'action:later', path)
vCheckDelay(r, a, path)
if (a.onRestart != null && !RESTART_POLICIES.contains(a.onRestart)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.onRestart", "'${a.onRestart}' is not a restart policy; allowed are ${RESTART_POLICIES.join(', ')}")
}
if (!(a.actions instanceof List)) vBlock(r, 'SCHEMA_MISSING', "${path}.actions", 'steps to do later need a list of steps')
else if (((List) a.actions).isEmpty()) vWarn(r, 'SCHEMA_UNUSED', "${path}.actions", 'there are no steps to do later yet')
else vCheckActions(r, a.actions, "${path}.actions".toString())
return
}
if (type == 'if') {
vFields(r, a, 'action:if', path)
if (a.condition == null) vBlock(r, 'SCHEMA_MISSING', "${path}.condition", 'an if needs a condition')
else vCheckCondition(r, a.condition, "${path}.condition".toString())
if (a.then == null) vBlock(r, 'SCHEMA_MISSING', "${path}.then", 'an if needs a then branch')
else vCheckActions(r, a.then, "${path}.then".toString())
if (a.elseIf != null) {
List branches = vList(r, a.elseIf, "${path}.elseIf".toString(), 'elseIf')
branches?.eachWithIndex { e, i ->
String bp = "${path}.elseIf.${i}".toString()
Map branch = vMap(r, e, bp, 'an else if branch')
if (branch == null) return
vFields(r, branch, 'action:elseIf', bp)
if (branch.condition == null) vBlock(r, 'SCHEMA_MISSING', "${bp}.condition", 'an else if needs a condition')
else vCheckCondition(r, branch.condition, "${bp}.condition".toString())
if (branch.then == null) vBlock(r, 'SCHEMA_MISSING', "${bp}.then", 'an else if needs a then branch')
else vCheckActions(r, branch.then, "${bp}.then".toString())
}
}
if (a.otherwise != null) vCheckActions(r, a.otherwise, "${path}.otherwise".toString())
return
}
if (type == 'repeat') {
String rk = a.repeatKind as String
if (!REPEAT_KINDS.contains(rk)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.repeatKind", "'${rk}' is not a repeat kind; allowed are ${REPEAT_KINDS.join(', ')}")
return
}
vFields(r, a, "action:repeat:${rk}".toString(), path)
if (a.onRestart != null && !RESTART_POLICIES.contains(a.onRestart)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.onRestart", "'${a.onRestart}' is not a restart policy; allowed are ${RESTART_POLICIES.join(', ')}")
}
if (a.everySeconds != null) vIntField(r, a.everySeconds, "${path}.everySeconds".toString(), 'a repeat interval', 'repeatSeconds')
else if (rk == 'every') vBlock(r, 'SCHEMA_MISSING', "${path}.everySeconds", 'a repeat every needs its interval')
if (a.times != null) vIntField(r, a.times, "${path}.times".toString(), 'the number of times', 'repeatTimes')
if (rk != 'every') {
if (a.condition == null) vBlock(r, 'SCHEMA_MISSING', "${path}.condition", "a repeat ${rk} needs a condition")
else vCheckCondition(r, a.condition, "${path}.condition".toString())
}
if (!(a.steps instanceof List) || ((List) a.steps).isEmpty()) vBlock(r, 'SCHEMA_MISSING', "${path}.steps", 'a repeat needs at least one step to repeat')
else vCheckActions(r, a.steps, "${path}.steps".toString())
if (a.everySeconds == null && rk != 'every' && a.steps instanceof List &&
!((List) a.steps).any { it instanceof Map && ((Map) it).type == 'wait' && ((Map) it).disabled != true }) {
vBlock(r, 'REPEAT_UNPACED', path, "a repeat ${rk} with no interval must wait in its own steps, or it would go round as fast as the hub can; set Every, or add a wait step")
}
if (vIsInteger(a.everySeconds) && (a.everySeconds as long) < 60 && a.times == null) {
vWarn(r, 'REPEAT_FREQUENT', "${path}.everySeconds", 'this repeats more than once a minute with no set number of times, which keeps the hub busy; use it only if you need it')
}
return
}
if (type == 'stopRepeat') { vFields(r, a, 'action:stopRepeat', path); return }
if (type == 'byMode') {
vFields(r, a, 'action:byMode', path)
List cases = vList(r, a.cases, "${path}.cases".toString(), 'cases')
if (cases != null && cases.isEmpty()) vBlock(r, 'SCHEMA_MISSING', "${path}.cases", 'a per mode step needs at least one case')
Set seen = [] as Set
cases?.eachWithIndex { Object co, int i ->
String cp = "${path}.cases.${i}".toString()
Map c = vMap(r, co, cp, 'a mode case')
if (c == null) return
vFields(r, c, 'action:modeCase', cp)
if (!(c.modes instanceof List) || ((List) c.modes).isEmpty() || ((List) c.modes).any { !(it instanceof String) }) {
vBlock(r, 'SCHEMA_MISSING', "${cp}.modes", 'a case names one or more modes')
} else ((List) c.modes).each { Object m ->
if (!seen.add(m)) vWarn(r, 'MODE_CASE_REPEATED', "${cp}.modes", "the mode '${m}' is in an earlier case too, so this case never runs for it")
}
if (c.then == null) vBlock(r, 'SCHEMA_MISSING', "${cp}.then", 'a case needs its steps')
else vCheckActions(r, c.then, "${cp}.then".toString())
}
if (a.otherwise != null) vCheckActions(r, a.otherwise, "${path}.otherwise".toString())
return
}
if (type == 'deviceEnabled') {
vFields(r, a, 'action:deviceEnabled', path)
if (!(a.refs instanceof List) || ((List) a.refs).isEmpty() || ((List) a.refs).size() > 32 || ((List) a.refs).any { !(it instanceof String) }) {
vBlock(r, 'SCHEMA_MISSING', "${path}.refs", 'choose from 1 to 32 devices')
}
if (!(a.enabled instanceof Boolean)) vBlock(r, 'SCHEMA_MISSING', "${path}.enabled", 'say whether the devices are disabled or enabled')
return
}
if (type == 'flashSwitches' || type == 'fade' || type == 'track') {
vFields(r, a, "action:${type}".toString(), path)
if (!(a.refs instanceof List) || ((List) a.refs).isEmpty() || ((List) a.refs).size() > 32 || ((List) a.refs).any { !(it instanceof String) }) {
vBlock(r, 'SCHEMA_MISSING', "${path}.refs", 'choose from 1 to 32 devices')
}
if (type == 'flashSwitches') {
if (a.seconds == null) vBlock(r, 'SCHEMA_MISSING', "${path}.seconds", 'a flash needs its rate: how long each switch stays on, then off')
else vIntField(r, a.seconds, "${path}.seconds".toString(), 'the flash rate', 'flashSeconds')
if (a.times != null) vIntField(r, a.times, "${path}.times".toString(), 'the number of flashes', 'repeatTimes')
}
if (type == 'fade') {
String at = (a.attribute ?: 'level') as String
if (!FADE_ATTRIBUTES.contains(at)) vBlock(r, 'SCHEMA_ENUM', "${path}.attribute", "a fade changes ${FADE_ATTRIBUTES.join(' or ')}")
List range = at == 'colorTemperature' ? [1000, 30000] : [0, 100]
if (!vIsInteger(a.to) || (a.to as long) < (range[0] as long) || (a.to as long) > (range[1] as long)) {
vBlock(r, 'LIMIT_RANGE', "${path}.to", "a fade of the ${at == 'level' ? 'level goes to a whole number from 0 to 100' : 'colour temperature goes to a whole number of kelvin from 1000 to 30000'}")
}
if (a.seconds == null) vBlock(r, 'SCHEMA_MISSING', "${path}.seconds", 'a fade needs the time it takes')
else vIntField(r, a.seconds, "${path}.seconds".toString(), 'the fade time', 'fadeSeconds')
if (a.stepSeconds != null) {
vIntField(r, a.stepSeconds, "${path}.stepSeconds".toString(), 'the time between steps', 'fadeSeconds')
if (vIsInteger(a.stepSeconds) && vIsInteger(a.seconds) && (a.seconds as long) > (a.stepSeconds as long) * LIMIT_REPEAT_PASSES) {
vBlock(r, 'LIMIT_RANGE', "${path}.stepSeconds", "a fade takes at most ${LIMIT_REPEAT_PASSES} steps; make the steps longer")
}
}
}
if (type == 'track' && !TRACK_ATTRIBUTES.contains(a.attribute)) vBlock(r, 'SCHEMA_ENUM', "${path}.attribute", "a track step follows ${TRACK_ATTRIBUTES.join(' or ')}")
return
}
if (type == 'stopGradual') {
vFields(r, a, 'action:stopGradual', path)
if (a.what != null && !GRADUAL_WHAT.contains(a.what)) vBlock(r, 'SCHEMA_ENUM', "${path}.what", "'${a.what}' is not one of ${GRADUAL_WHAT.join(', ')}")
return
}
if (type == 'setVariable' && a.op != null) {
vFields(r, a, 'action:setVariable', path)
if (!(a.ref instanceof String)) vBlock(r, 'SCHEMA_MISSING', "${path}.ref", 'setVariable needs a variable reference')
Map spec = SETVAR_OPS.get(a.op)
if (spec == null) { vBlock(r, 'SCHEMA_ENUM', "${path}.op", "'${a.op}' is not a Set Variable operation"); return }
if (a.containsKey('value')) vBlock(r, 'SCHEMA_FIELD', "${path}.value", 'an operation takes its values in args, not value')
List want = spec.args
List args = a.args instanceof List ? a.args : (a.args == null ? [] : null)
int need = want.count { !((String) it).endsWith('?') } as int
if (args == null || args.size() < need || args.size() > want.size()) {
vBlock(r, 'SCHEMA_ARGS', "${path}.args", "${a.op} takes ${want.join(', ')}"); return
}
args.eachWithIndex { Object o, int i ->
String role = (want[i] as String).replace('?', '')
String ap = "${path}.args.${i}".toString()
if (role == 'operator') {
boolean unary = MATH_UNARY.contains(o)
if (!(MATH_BINARY.contains(o) || unary)) vBlock(r, 'SCHEMA_ENUM', ap, "'${o}' is not a math operator")
else if (!unary && args.size() < 3) vBlock(r, 'SCHEMA_ARGS', "${path}.args", "'${o}' needs a second value")
} else if (role == 'comparison') {
if (!COMPARATORS.contains(o)) vBlock(r, 'SCHEMA_ENUM', ap, "'${o}' is not a comparison")
} else if (role == 'unit') {
if (!TIME_UNITS.containsKey(o)) vBlock(r, 'SCHEMA_ENUM', ap, "'${o}' is not a unit; use ${TIME_UNITS.keySet().join(', ')}")
} else if (role == 'name' && o instanceof String && !((String) o).contains('%') && schemaFileProblem(o) != null) {
vBlock(r, 'FILE_NAME', ap, schemaFileProblem(o))
} else if (o instanceof Map) {
Map m = o
if (!(m.ref instanceof String) || !m.keySet().every { it in ['ref', 'attribute'] }) vBlock(r, 'SCHEMA_TYPE', ap, 'an argument is a value, {"ref": key} or {"ref": key, "attribute": name}')
} else if (vLiteralType(o) == null) {
vBlock(r, 'SCHEMA_TYPE', ap, 'an argument is text, a number, true or false, or a reference')
}
}
return
}
if (type == 'setVariable') {
vFields(r, a, 'action:setVariable', path)
if (!(a.ref instanceof String)) vBlock(r, 'SCHEMA_MISSING', "${path}.ref", 'setVariable needs a variable reference')
if (!a.containsKey('value') || vLiteralType(a.value) == null) {
vBlock(r, 'SCHEMA_TYPE', "${path}.value", 'setVariable needs a value that is text, a number or true or false')
}
if (a.valueType != null && !VARIABLE_TYPES.contains(a.valueType)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.valueType", "'${a.valueType}' is not a variable type")
}
return
}
if (type == 'runRule') {
vFields(r, a, 'action:runRule', path)
if (!(a.ref instanceof String)) {
vBlock(r, 'RULE_CALL_NOT_STATIC', "${path}.ref",
'a rule call names the rule directly. Choosing which rule to call while running - from a ' +
'variable, say - is not supported, so the call cannot be checked before it runs')
}
if (a.containsKey('wait') && !(a.wait instanceof Boolean)) vBlock(r, 'SCHEMA_TYPE', "${path}.wait", 'wait is true or false')
if (a.wait == true) {
if (a.timeoutSeconds == null) {
vBlock(r, 'WAIT_UNBOUNDED', "${path}.timeoutSeconds", 'waiting for another rule needs a time limit, because it may never finish')
} else {
vIntField(r, a.timeoutSeconds, "${path}.timeoutSeconds".toString(), 'a timeout', 'timeoutSeconds')
}
if (a.onTimeout != null && !TIMEOUT_BEHAVIOURS.contains(a.onTimeout)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.onTimeout", "'${a.onTimeout}' is not a timeout behaviour")
}
} else {
['result', 'timeoutSeconds', 'onTimeout'].each { String f ->
if (a.containsKey(f)) vBlock(r, 'SCHEMA_FIELD', "${path}.${f}", "${f} applies only when the step waits for the rule (wait: true)")
}
}
if (a.containsKey('result') && !(a.result instanceof String)) vBlock(r, 'SCHEMA_TYPE', "${path}.result", 'result is a variable reference')
if (a.containsKey('input') && vLiteralType(a.input) == null) vBlock(r, 'SCHEMA_TYPE', "${path}.input", 'input is text, a number or true or false')
return
}
if (type == 'capture' || type == 'restore') {
vFields(r, a, "action:${type}".toString(), path)
if (!(a.refs instanceof List) || ((List) a.refs).isEmpty() || ((List) a.refs).size() > 32 || ((List) a.refs).any { !(it instanceof String) }) {
vBlock(r, 'SCHEMA_MISSING', "${path}.refs", 'choose from 1 to 32 devices')
}
return
}
if (type == 'customCommand') {
vFields(r, a, 'action:customCommand', path)
if (!(a.ref instanceof String)) vBlock(r, 'SCHEMA_MISSING', "${path}.ref", 'a custom command needs a device')
if (!(a.command instanceof String) || !(((String) a.command) ==~ /[A-Za-z][A-Za-z0-9_]{0,63}/)) {
vBlock(r, 'SCHEMA_TYPE', "${path}.command", 'a custom command is named by letters and digits')
}
if (a.args != null && !(a.args instanceof List && ((List) a.args).size() <= 8 && ((List) a.args).every { vLiteralType(it) != null })) {
vBlock(r, 'SCHEMA_TYPE', "${path}.args", 'up to 8 values, each text, a number, or true or false')
} else if (a.args instanceof List) {
((List) a.args).eachWithIndex { Object v, int ix ->
if (v instanceof Number && !(v instanceof Double) && !(v instanceof Float) &&
(v as BigDecimal).compareTo(new BigDecimal('2147483647')) > 0) {
vWarn(r, 'CUSTOM_ARG_ABOVE_RM_LIMIT', "${path}.args[${ix}]".toString(),
"Rule Machine would silently drop this value and shift the later parameters left. " +
"This rule passes it as written, which is what was asked for, so it will behave " +
"differently from the original")
}
}
}
return
}
if (type == 'log') {
vFields(r, a, 'action:log', path)
if (!(a.text instanceof String) || ((String) a.text).isEmpty()) vBlock(r, 'SCHEMA_MISSING', "${path}.text", 'a log step needs some text')
else if (((String) a.text).length() > 1024) vBlock(r, 'LIMIT_RANGE', "${path}.text", 'a log line is at most 1024 characters')
if (a.level != null && !LOG_LEVELS.contains(a.level)) vBlock(r, 'SCHEMA_ENUM', "${path}.level", "'${a.level}' is not a log level")
return
}
if (type == 'setMode') {
vFields(r, a, 'action:setMode', path)
if (a.containsKey('ref') == a.containsKey('value')) vBlock(r, 'SCHEMA_MISSING', path, 'choose the mode, by reference or by name, one of the two')
if (a.containsKey('value') && !(a.value instanceof String)) vBlock(r, 'SCHEMA_TYPE', "${path}.value", 'the mode is named by text')
return
}
if (type == 'return') {
vFields(r, a, 'action:return', path)
boolean hasValue = a.containsKey('value'), hasRef = a.containsKey('ref')
if (hasValue == hasRef) vBlock(r, 'SCHEMA_MISSING', path, 'return gives either a value or a variable, one of the two')
if (hasValue && vLiteralType(a.value) == null) vBlock(r, 'SCHEMA_TYPE', "${path}.value", 'return a value that is text, a number or true or false')
if (hasRef && !(a.ref instanceof String)) vBlock(r, 'SCHEMA_TYPE', "${path}.ref", 'ref is a variable reference')
return
}
if (type == 'exit' || type == 'cancelDelays') { vFields(r, a, "action:${type}".toString(), path); return }
if (type == 'hsm') {
vFields(r, a, 'action:hsm', path)
if (!HSM_ARM_OPS.contains(a.op)) vBlock(r, 'SCHEMA_ENUM', "${path}.op", "'${a.op}' is not an HSM operation; allowed are ${HSM_ARM_OPS.join(', ')}")
return
}
if (type == 'http') {
vFields(r, a, 'action:http', path)
if (a.method != null && !HTTP_METHODS.contains(a.method)) vBlock(r, 'SCHEMA_ENUM', "${path}.method", "'${a.method}' is not a method; use get or post")
if (!(a.url instanceof String) || ((String) a.url).isEmpty()) vBlock(r, 'SCHEMA_MISSING', "${path}.url", 'an HTTP step needs an address')
else if (((String) a.url).length() > 2048) vBlock(r, 'LIMIT_RANGE', "${path}.url", 'an address is at most 2048 characters')
else if (!((String) a.url).contains('%') && schemaUrlHost(a.url) == null) vBlock(r, 'SCHEMA_TYPE', "${path}.url", "'${a.url}' is not an http or https address")
if (a.headers != null && !(a.headers instanceof Map && ((Map) a.headers).size() <= 16 &&
((Map) a.headers).every { k, v -> (k as String) ==~ /[A-Za-z0-9-]{1,64}/ && v instanceof String && ((String) v).length() <= 1024 })) {
vBlock(r, 'SCHEMA_TYPE', "${path}.headers", 'headers are up to 16 names (letters, digits and dashes), each with text')
}
boolean post = a.method == 'post'
['body', 'contentType'].each { String f -> if (!post && a.containsKey(f)) vBlock(r, 'SCHEMA_FIELD', "${path}.${f}", "${f} applies only to a POST") }
if (a.body != null && !(a.body instanceof String && ((String) a.body).length() <= LIMIT_STRING_CHARS)) vBlock(r, 'SCHEMA_TYPE', "${path}.body", "the body is text, at most ${LIMIT_STRING_CHARS} characters")
if (a.contentType != null && !HTTP_CONTENT_TYPES.contains(a.contentType)) vBlock(r, 'SCHEMA_ENUM', "${path}.contentType", "'${a.contentType}' is not a content type; use ${HTTP_CONTENT_TYPES.join(', ')}")
if (a.containsKey('result') && !(a.result instanceof String)) vBlock(r, 'SCHEMA_TYPE', "${path}.result", 'result is a variable reference')
if (a.containsKey('field')) {
if (!a.containsKey('result')) vBlock(r, 'SCHEMA_FIELD', "${path}.field", 'field picks part of the answer to keep, so it needs a result')
else if (!(a.field instanceof String && ((String) a.field) ==~ /[A-Za-z0-9_-]{1,64}(\.[A-Za-z0-9_-]{1,64}){0,7}/)) vBlock(r, 'SCHEMA_TYPE', "${path}.field", 'field is a path into a JSON answer, like data.temperature or items.0.name')
}
if (a.containsKey('saveFile')) {
if (!(a.saveFile instanceof String) || ((String) a.saveFile).isEmpty()) vBlock(r, 'SCHEMA_TYPE', "${path}.saveFile", 'a file name is required')
else if (!((String) a.saveFile).contains('%') && schemaFileProblem(a.saveFile) != null) vBlock(r, 'FILE_NAME', "${path}.saveFile", schemaFileProblem(a.saveFile))
if (a.containsKey('result') || a.containsKey('field')) vBlock(r, 'SCHEMA_FIELD', path, 'saveFile writes the answer directly; do not also keep it in a variable')
}
if (a.containsKey('select')) {
if (!a.containsKey('saveFile')) vBlock(r, 'SCHEMA_FIELD', "${path}.select", 'select writes JSON values to a file, so it needs saveFile')
else if (!(a.select instanceof List) || ((List) a.select).isEmpty() || !((List) a.select).every { it instanceof String && it ==~ /[A-Za-z0-9_-]{1,64}(\.[A-Za-z0-9_-]{1,64}){0,7}/ })
vBlock(r, 'SCHEMA_TYPE', "${path}.select", 'select is a list of JSON paths such as top_news.0.news.0')
}
if (a.containsKey('rssItems')) {
if (!a.containsKey('saveFile') || a.containsKey('select')) vBlock(r, 'SCHEMA_FIELD', "${path}.rssItems", 'RSS item selection needs saveFile and cannot be combined with JSON select')
vIntField(r, a.rssItems, "${path}.rssItems".toString(), 'the RSS item count', 'rssItems')
}
if (a.timeoutSeconds != null) vIntField(r, a.timeoutSeconds, "${path}.timeoutSeconds".toString(), 'a timeout', 'netTimeoutSeconds')
return
}
if (type == 'ping') {
vFields(r, a, 'action:ping', path)
if (!(a.host instanceof String) || !(((String) a.host).contains('%') ? ((String) a.host).length() <= 253 : ((String) a.host) ==~ /[A-Za-z0-9.:-]{1,253}/)) {
vBlock(r, 'SCHEMA_TYPE', "${path}.host", 'a ping needs a host name or IP address')
}
if (a.into != null && !(a.into instanceof Map && ((Map) a.into).every { k, v -> PING_RESULTS.contains(k) && v instanceof String })) {
vBlock(r, 'SCHEMA_TYPE', "${path}.into", "into keeps any of ${PING_RESULTS.join(', ')}, each in a variable")
}
if (a.timeoutSeconds != null) vIntField(r, a.timeoutSeconds, "${path}.timeoutSeconds".toString(), 'a timeout', 'netTimeoutSeconds')
return
}
if (type == 'file') {
vFields(r, a, 'action:file', path)
if (!FILE_OPS.contains(a.op)) vBlock(r, 'SCHEMA_ENUM', "${path}.op", "'${a.op}' is not a file operation; use ${FILE_OPS.join(', ')}")
if (!(a.name instanceof String) || ((String) a.name).isEmpty()) vBlock(r, 'SCHEMA_MISSING', "${path}.name", 'a file step needs a file name')
else if (!((String) a.name).contains('%') && schemaFileProblem(a.name) != null) vBlock(r, 'FILE_NAME', "${path}.name", schemaFileProblem(a.name))
if (a.op == 'delete' && a.containsKey('text')) vBlock(r, 'SCHEMA_FIELD', "${path}.text", 'deleting a file takes no text')
if (a.text != null && !(a.text instanceof String && ((String) a.text).length() <= LIMIT_STRING_CHARS)) vBlock(r, 'SCHEMA_TYPE', "${path}.text", "the text is at most ${LIMIT_STRING_CHARS} characters")
return
}
if (type == 'setRuleBoolean' || type == 'controlRule') {
vFields(r, a, "action:${type}".toString(), path)
if (!(a.rules instanceof List) || ((List) a.rules).isEmpty() || ((List) a.rules).any { !(it instanceof String) }) {
vBlock(r, 'SCHEMA_MISSING', "${path}.rules", 'choose at least one rule')
}
if (type == 'setRuleBoolean' && !(a.value instanceof Boolean)) {
vBlock(r, 'SCHEMA_TYPE', "${path}.value", 'set it to true or false')
}
if (type == 'controlRule' && !RULE_CONTROL_OPS.contains(a.op)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.op", "'${a.op}' is not something a rule can do to another")
}
return
}
if (type == 'comment') {
vFields(r, a, 'action:comment', path)
if (!(a.text instanceof String) || ((String) a.text).trim().isEmpty()) {
vBlock(r, 'SCHEMA_MISSING', "${path}.text", 'a comment needs some text')
} else if (((String) a.text).length() > 500) {
vBlock(r, 'LIMIT_RANGE', "${path}.text", 'a comment is at most 500 characters')
}
}
}
void vCheckArgs(Map r, Map a, Map op, String path) {
List spec = (op.args ?: [])
List args = []
if (a.args != null) {
List given = vList(r, a.args, "${path}.args".toString(), 'args')
if (given == null) return
args = given
}
int required = spec.count { it.required }
if (args.size() < required || args.size() > spec.size()) {
String want = required == spec.size() ? "${required}" : "${required} to ${spec.size()}"
vBlock(r, 'ARGS_ARITY', "${path}.args", a.command == 'setColor'
? "'setColor' takes hue, saturation and, if wanted, level, as separate numbers from 0 to 100, not ${args.size()} value${args.size() == 1 ? '' : 's'}".toString()
: "'${a.command}' takes ${want} argument${spec.size() == 1 ? '' : 's'}, not ${args.size()}".toString())
return
}
args.eachWithIndex { Object v, int i ->
Map s = spec[i]
String ap = "${path}.args.${i}".toString()
if (v instanceof Map) {
if (!(((Map) v).ref instanceof String) || ((Map) v).size() != 1) vBlock(r, 'ARGS_TYPE', ap, 'a value from a variable is {"ref": "a variable key"}')
return
}
if (s.type == 'string') {
if (!(v instanceof String)) { vBlock(r, 'ARGS_TYPE', ap, "argument ${i + 1} of '${a.command}' must be text"); return }
if (s.values != null && !((List) s.values).contains(v)) {
vBlock(r, 'ARGS_RANGE', ap, "argument ${i + 1} of '${a.command}' is one of ${((List) s.values).join(', ')}, not '${v}'")
}
if (s.maxChars != null && ((String) v).size() > (s.maxChars as int)) {
vBlock(r, 'ARGS_RANGE', ap, "argument ${i + 1} of '${a.command}' is longer than ${s.maxChars} characters")
}
return
}
boolean ok = s.type == 'integer' ? vIsInteger(v) : vIsNumber(v)
if (!ok) {
vBlock(r, 'ARGS_TYPE', ap, "argument ${i + 1} of '${a.command}' must be ${s.type == 'integer' ? 'a whole number' : 'a number'}")
return
}
BigDecimal n = new BigDecimal(v.toString())
if ((s.min != null && n < (s.min as BigDecimal)) || (s.max != null && n > (s.max as BigDecimal))) {
vBlock(r, 'ARGS_RANGE', ap, "argument ${i + 1} of '${a.command}' must be between ${s.min} and ${s.max}, not ${v}")
}
}
}
void vCheckReferences(Map r, Map doc, Map inv) {
if (!vTick(r)) return
Map refs = (doc.refs ?: [:])
if (refs.size() > LIMIT_REFERENCES_PER_RULE) {
vBlock(r, 'LIMIT_REFS', 'refs', "${refs.size()} references, limit is ${LIMIT_REFERENCES_PER_RULE}")
return
}
Set outsideEnable = [] as Set
vCollectRefKeys(r, doc, outsideEnable, true)
refs.each { k, v ->
if (!vTick(r)) return
String path = "refs.${k}".toString()
Map ref = vMap(r, v, path, 'a reference')
if (ref == null) return
if (!REF_KINDS.contains(ref.kind)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.kind", "'${ref.kind}' is not a reference kind")
return
}
vFields(r, ref, "ref:${ref.kind}".toString(), path)
if (ref.kind == 'device') vResolveDevice(r, ref, path, inv, !outsideEnable.contains(k))
if (ref.kind == 'variable') vResolveVariable(r, ref, path, inv)
if (ref.kind == 'mode' && !((Set) inv.modes).contains(ref.name)) {
vBlock(r, 'REF_MISSING', path, "there is no mode called '${ref.name}' on this hub")
}
if (ref.kind == 'rule' && !((Set) inv.rules).contains(ref.id)) {
vBlock(r, 'REF_MISSING', path, "there is no rule '${ref.id}' to call" + (vLooksRm(ref.id) ? ". ${RM_REF_SHAPE}" : ''))
}
if (ref.kind == 'rmRule' && !((Collection) (inv.rmRules ?: [])).collect { it?.toString() }.contains(ref.id?.toString())) {
vBlock(r, 'REF_MISSING', path, "there is no Rule Machine rule '${ref.name ?: ref.id}' on this hub")
}
}
Set used = [] as Set
vCollectRefKeys(r, doc, used)
used.each { String key ->
if (!refs.containsKey(key)) vBlock(r, 'REF_UNDECLARED', "refs.${key}".toString(), "this rule uses '${key}' but never declares it" + (vLooksRm(key) ? ". ${RM_REF_SHAPE}" : ''))
}
refs.keySet().each { Object key ->
if (!used.contains(key)) vWarn(r, 'REF_UNUSED', "refs.${key}", "'${key}' is declared but never used")
}
}
void vResolveDevice(Map r, Map ref, String path, Map inv, boolean onlyEnabled = false) {
if (!vTick(r)) return
if (!(ref.id instanceof String)) { vBlock(r, 'SCHEMA_MISSING', "${path}.id", 'a device reference needs an id'); return }
if (ref.capabilities != null && !(ref.capabilities instanceof List)) {
vBlock(r, 'SCHEMA_TYPE', "${path}.capabilities", 'capabilities must be a list'); return
}
Map d = invDevice(inv, (String) ref.id)
if (d == null) {
vBlock(r, 'REF_MISSING', path,
"no device matches '${ref.name ?: ref.id}' among the devices this engine may use; import would report this as unresolved rather than guessing")
return
}
if (d.disabled == true) vWarn(r, 'REF_DISABLED', path, onlyEnabled ? "the device '${d.name}' is disabled on this hub; this rule only disables or enables it" : "${d.name} is disabled, so this step does nothing until it is enabled")
if (d.disabled == null) vWarn(r, 'REF_AVAILABILITY_UNKNOWN', path, "the hub did not say whether '${d.name}' is enabled")
if (d.variableName) vWarn(r, 'REF_CONNECTOR', path, "'${d.name}' is the connector for the hub variable '${d.variableName}'; a rule can use the variable itself")
((ref.capabilities ?: []) as List).each { cap ->
if (!((d.capabilities ?: []) as Set).contains(cap)) {
vBlock(r, 'REF_MISSING_CAPABILITY', path, "'${d.name}' does not have the ${cap} capability this rule needs")
}
}
}
void vResolveVariable(Map r, Map ref, String path, Map inv) {
if (!vTick(r)) return
if (ref.scope == 'rule') {
if (!(ref.id instanceof String) || !((String) ref.id).startsWith(RULE_VARIABLE_PREFIX)) {
vBlock(r, 'SCHEMA_TYPE', "${path}.id", "a rule's own variable has an id starting ${RULE_VARIABLE_PREFIX}")
}
if (!VARIABLE_TYPES.contains(ref.type)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.type", "'${ref.type}' is not a variable type")
} else if (ref.containsKey('initial')) {
String lit = vLiteralType(ref.initial)
boolean fits = lit == ref.type || (lit == 'string' && ref.type in ['string', 'date', 'time', 'datetime'])
if (!fits) vBlock(r, 'VALUE_TYPE_MISMATCH', "${path}.initial", "the starting value is not a ${ref.type}")
}
return
}
if (ref.containsKey('initial')) vBlock(r, 'SCHEMA_FIELD', "${path}.initial", 'only a rule\'s own variable has a starting value')
Map v = invVariable(inv, ref.id as String)
if (v == null) { vBlock(r, 'REF_MISSING', path, "no variable matches '${ref.name ?: ref.id}'"); return }
if (ref.type != null && ref.type != v.type) {
vBlock(r, 'REF_TYPE', path, "this rule expects '${v.name}' to be a ${ref.type}, and on this hub it is a ${v.type}")
}
if (ref.scope != null && !VARIABLE_SCOPES.contains(ref.scope)) {
vBlock(r, 'SCHEMA_ENUM', "${path}.scope", "'${ref.scope}' is not a variable scope")
}
}
@Field static final Map BUTTON_CAPABILITY = [pushed: 'PushableButton', held: 'HoldableButton',
doubleTapped: 'DoubleTapableButton', released: 'ReleasableButton']
void vCheckButton(Map r, Map t, String p, Map d) {
if (d == null) return
String action = (t.action ?: 'pushed') as String
String cap = BUTTON_CAPABILITY.get(action) as String
if (cap == null) return
Set caps = (Set) (d.capabilities ?: [] as Set)
if (!caps.contains(cap)) {
vBlock(r, 'BUTTON_UNSUPPORTED', "${p}.action",
"'${d.name}' does not report ${cap}, so it cannot send a ${action} event")
return
}
if (d.attributes != null && !((Set) d.attributes).contains(action)) {
vBlock(r, 'BUTTON_UNSUPPORTED', "${p}.action", "'${d.name}' does not report a ${action} event")
}
Object count = ((Map) ((Map) (d.currentStates ?: [:])).get('numberOfButtons'))?.value
BigDecimal n = null
try { n = count == null ? null : new BigDecimal(count.toString()) } catch (Exception e) { n = null }
if (n == null) {
vWarn(r, 'BUTTON_NUMBER_UNVERIFIED', "${p}.number",
"the hub did not say how many buttons '${d.name}' has, so button ${t.number} could not be checked; settle this before activation")
} else if (t.number instanceof Number && new BigDecimal(t.number.toString()) > n) {
vBlock(r, 'BUTTON_NUMBER_RANGE', "${p}.number",
"'${d.name}' has ${n.toPlainString()} buttons, so there is no button ${t.number}")
}
}
void vCheckDriverArgs(Map r, Map a, String p, Map d) {
Object declared = ((Map) (((Map) (d.metadata ?: [:])).get('commandArgs') ?: [:])).get(a.command)
List args = a.args instanceof List ? a.args : []
if (!(declared instanceof List)) {
vWarn(r, 'ARGS_DRIVER_UNVERIFIED', "${p}.args",
"HAI could not find out from the hub what values '${d.name}' takes for this command, so they were not checked")
return
}
List kinds = declared
if (a.command == 'setColor' && kinds.size() == 1 && kinds[0].toString().toUpperCase() == 'COLOR_MAP') {
if (args.size() > 3) {
vBlock(r, 'ARGS_DRIVER_ARITY', "${p}.args", "setColor takes hue, saturation and level, and ${args.size()} were given")
}
return
}
if (args.size() > kinds.size()) {
vBlock(r, 'ARGS_DRIVER_ARITY', "${p}.args",
"'${d.name}' accepts ${kinds.size()} argument(s) for ${a.command}, and ${args.size()} were given")
return
}
args.eachWithIndex { Object v, int i ->
if (v instanceof Map) return
String k = kinds[i].toString().toUpperCase()
if (k in ['NUMBER', 'INTEGER', 'DECIMAL'] && !(v instanceof Number)) {
vBlock(r, 'ARGS_DRIVER_TYPE', "${p}.args.${i}", "'${d.name}' expects a number here for ${a.command}")
} else if (k == 'STRING' && !(v instanceof String)) {
vBlock(r, 'ARGS_DRIVER_TYPE', "${p}.args.${i}", "'${d.name}' expects text here for ${a.command}")
} else if (k == 'INTEGER' && v instanceof Number && new BigDecimal(v.toString()).stripTrailingZeros().scale() > 0) {
vBlock(r, 'ARGS_DRIVER_TYPE', "${p}.args.${i}", "'${d.name}' expects a whole number here for ${a.command}")
}
}
}
void vCheckUses(Map r, Map doc, Map inv) {
if (!vTick(r)) return
Map refs = (doc.refs ?: [:])
Closure kindOf = { Object key -> ((Map) refs.get(key))?.kind }
Closure deviceOf = { Object key -> Map ref = refs.get(key); ref?.kind == 'device' ? invDevice(inv, ref.id as String) : null }
Closure checkKind = { Object key, String use, String path ->
String want = USE_KIND.get(use) as String
String got = kindOf(key) as String
if (key != null && got != null && want != null && got != want && !(use == 'rulePaused' && got == 'rmRule')) {
vBlock(r, 'REF_WRONG_KIND', path, "'${key}' is a ${got} reference, and this needs a ${want}")
return false
}
return true
}
Closure checkAttribute = { Object key, Object attr, String path ->
Map d = deviceOf(key)
if (d == null || !(attr instanceof String)) return
if (d.attributes == null) {
vWarn(r, 'ATTRIBUTE_UNVERIFIED', path, "HAI could not find out from the hub what '${d.name}' reports, so '${attr}' was not checked")
} else if (!((Set) d.attributes).contains(attr)) {
vBlock(r, 'ATTRIBUTE_UNSUPPORTED', path, "'${d.name}' does not report an attribute called '${attr}'")
}
}
Closure checkTrigger = { Map t, String p ->
if (t.ref != null && checkKind(t.ref, t.kind as String, "${p}.ref".toString()) && t.attribute != null) {
checkAttribute(t.ref, t.attribute, "${p}.attribute".toString())
if (t.kind == 'threshold' && !NON_READING_ATTRIBUTES.contains(t.attribute)) {
Map d = deviceOf(t.ref)
Object now = (d?.currentStates instanceof Map) ? ((Map) ((Map) d.currentStates).get(t.attribute))?.value : null
if (now != null && !(now instanceof Number) && !((now as String).trim() ==~ /-?\d+(\.\d+)?/)) {
vBlock(r, 'THRESHOLD_NOT_A_READING', "${p}.attribute".toString(),
"'${d.name}' reports ${t.attribute} as '${now}', which is not a number, so it cannot be crossed")
}
}
}
if (t.kind == 'button' && t.ref != null) vCheckButton(r, t, p, deviceOf(t.ref))
if (t.kind == 'schedule') ['at', 'offsetMinutes'].each { String k -> if (t[k] instanceof Map) checkKind(((Map) t[k]).ref, 'variable', "${p}.${k}".toString()) }
}
((List) (doc.triggers ?: [])).eachWithIndex { Object to, int i ->
if (to instanceof Map) checkTrigger((Map) to, "triggers.${i}".toString())
}
vWalkAllConditions(r, doc) { Map n, String p ->
if (n.type == 'deviceAttribute') {
vConditionKeys(r, n, p).each { k ->
if (checkKind(k, 'deviceAttribute', "${p}.refs".toString())) checkAttribute(k, n.attribute, "${p}.attribute".toString())
}
}
if ((n.type == 'variable' || n.type == 'mode' || n.type == 'rulePaused') && n.ref != null) checkKind(n.ref, n.type as String, "${p}.ref".toString())
if (n.op == 'matches') {
String bad = vRegexProblem(n.value)
if (bad != null) vBlock(r, 'REGEX_UNSAFE', "${p}.value".toString(), bad)
}
if (n.type == 'waitOutcome') {
if (n.value != null && !(n.value in ['event', 'timeout'])) {
vBlock(r, 'SCHEMA_ENUM', "${p}.value".toString(), "a wait ends on 'event' or 'timeout', not '${n.value}'".toString())
}
if (p.startsWith('triggers.') || p.startsWith('requiredExpression') || p.startsWith('conditions')) {
vWarn(r, 'SCHEMA_UNUSED', p, 'only a step can ask how a wait ended; here there is no wait to ask, so this is unknown')
}
}
if (n.type == 'timeSince' && n.ref != null && checkKind(n.ref, 'deviceAttribute', "${p}.ref".toString()) && n.attribute != null) checkAttribute(n.ref, n.attribute, "${p}.attribute".toString())
if (n.type == 'dateRange' || n.type == 'timeWindow') ['from', 'to', 'fromOffsetMinutes', 'toOffsetMinutes'].each { String k -> if (n[k] instanceof Map) checkKind(((Map) n[k]).ref, 'variable', "${p}.${k}".toString()) }
if ((n.type == 'variable' || n.type == 'deviceAttribute') && n.value instanceof Map && ((Map) n.value).ref instanceof String) {
Map m = n.value
if (m.attribute != null) { if (checkKind(m.ref, 'deviceAttribute', "${p}.value".toString())) checkAttribute(m.ref, m.attribute, "${p}.value.attribute".toString()) }
else checkKind(m.ref, 'variable', "${p}.value".toString())
}
}
vWalkActions(r, doc) { Map a, String p ->
List cmdKeys = a.type == 'command' ? ((a.ref != null ? [a.ref] : []) + (a.refs instanceof List ? a.refs : [])) : []
cmdKeys.eachWithIndex { Object key, int ki ->
String kp = a.ref != null && ki == 0 ? "${p}.ref".toString() : "${p}.refs.${a.ref != null ? ki - 1 : ki}".toString()
if (kindOf(key) == 'eventDevice') {
if (!((List) (doc.triggers ?: [])).any { it instanceof Map && ((Map) it).kind in ['deviceAttribute', 'threshold', 'button'] }) {
vBlock(r, 'EVENT_DEVICE_NONE', kp, 'no device trigger starts this rule, so there is never a device that fired it')
}
return
}
if (!checkKind(key, 'command', kp)) return
Map d = deviceOf(key)
if (d != null && a.command instanceof String) {
if (d.commands == null && !schemaDeviceOffers(a.command as String, null)) {
vBlock(r, 'COMMAND_UNSUPPORTED', "${p}.command",
"HAI could not find out from the hub which commands '${d.name}' takes, and sends this one only to a device that offers it")
} else if (d.commands == null) {
vWarn(r, 'COMMAND_UNVERIFIED', "${p}.command", "HAI could not find out from the hub which commands '${d.name}' takes, so this one was not checked")
} else if (!schemaDeviceOffers(a.command as String, d.commands)) {
vBlock(r, 'COMMAND_UNSUPPORTED', "${p}.command", "'${d.name}' does not offer the command '${a.command}'")
}
if (d.commands == null || ((Set) d.commands).contains(a.command)) vCheckDriverArgs(r, a, p, d)
}
}
if (a.type == 'command' && a.args instanceof List) {
((List) a.args).eachWithIndex { Object v, int i ->
if (!(v instanceof Map) || !(((Map) v).ref instanceof String)) return
String ap = "${p}.args.${i}".toString()
if (!checkKind(((Map) v).ref, 'variable', ap)) return
Map spec = ((List) (schemaOperation(a.command as String)?.args ?: []))[i]
Map vref = refs.get(((Map) v).ref)
String vt = ((vref?.scope == 'hub' ? invVariable(inv, vref.id as String)?.type : null) ?: vref?.type) as String
if (spec != null && spec.type != 'string' && vt != null && vt != 'number') {
vBlock(r, 'VALUE_TYPE_MISMATCH', ap, "argument ${i + 1} of '${a.command}' is a number, and ${vref.name} holds ${vt}")
}
}
}
if (a.type in ['wait', 'later'] && a.seconds instanceof Map) checkKind(((Map) a.seconds).ref, 'variable', "${p}.seconds".toString())
if (a.type in ['wait', 'later'] && a.modeSeconds instanceof List) {
((List) a.modeSeconds).eachWithIndex { Object m, int i -> if (m instanceof Map) checkKind(((Map) m).ref, 'mode', "${p}.modeSeconds.${i}.ref".toString()) }
}
if (a.type == 'wait' && a.waitKind == 'forEvents' && a.events instanceof List) {
((List) a.events).eachWithIndex { Object e, int i -> if (e instanceof Map) checkTrigger((Map) e, "${p}.events.${i}".toString()) }
}
if (a.type == 'wait' && a.waitKind == 'forEvent' && a.ref != null && checkKind(a.ref, 'forEvent', "${p}.ref".toString())) {
checkAttribute(a.ref, a.attribute, "${p}.attribute".toString())
}
if (a.type == 'setVariable' && a.ref != null && a.op == null && checkKind(a.ref, 'setVariable', "${p}.ref".toString())) {
vCheckCoercion(r, a, p, refs.get(a.ref), inv)
}
if (a.type == 'setVariable' && a.ref != null && a.op != null && checkKind(a.ref, 'setVariable', "${p}.ref".toString())) {
Map vref = refs.get(a.ref)
String result = ((Map) SETVAR_OPS.get(a.op))?.result
Map row = vref?.scope == 'hub' ? invVariable(inv, vref.id as String) : null
String vtype = (row?.type ?: vref?.type) as String
if (result != null && result != 'any' && vtype != null && vtype != result) {
vBlock(r, 'VALUE_TYPE_MISMATCH', "${p}.op", "${a.op} gives ${result == 'number' ? 'a number' : result == 'string' ? 'text' : 'true or false'}, and ${vref.name} holds ${vtype}")
}
if (row != null && row.writable == false) vBlock(r, 'VARIABLE_NOT_WRITABLE', "${p}.ref", "rules may not change the hub variable '${vref.name}'")
((List) (a.args instanceof List ? a.args : [])).eachWithIndex { Object o, int i ->
if (!(o instanceof Map)) return
Map m = o
String got = kindOf(m.ref) as String
String ap = "${p}.args.${i}".toString()
if (m.attribute != null) { if (checkKind(m.ref, 'deviceAttribute', ap)) checkAttribute(m.ref, m.attribute, "${ap}.attribute".toString()) }
else if (got != null && got != 'variable') vBlock(r, 'REF_WRONG_KIND', ap, "'${m.ref}' is a ${got} reference; a device reading also names its attribute")
}
}
if (a.type == 'runRule' && a.ref != null) checkKind(a.ref, 'runRule', "${p}.ref".toString())
if (a.type == 'runRule' && a.result instanceof String && checkKind(a.result, 'setVariable', "${p}.result".toString())) {
Map vref = refs.get(a.result)
if (vref?.scope == 'hub') {
Map row = invVariable(inv, vref.id as String)
if (row != null && row.writable == false) vBlock(r, 'VARIABLE_NOT_WRITABLE', "${p}.result", "rules may not change the hub variable '${vref.name}'")
}
}
List kept = a.type == 'http' && a.result instanceof String ? [[a.result, "${p}.result"]] :
a.type == 'ping' && a.into instanceof Map ? ((Map) a.into).collect { k, v -> [v, "${p}.into.${k}"] } : []
kept.each { List kp ->
if (!checkKind(kp[0], 'setVariable', kp[1] as String)) return
Map vref = refs.get(kp[0])
Map row = vref?.scope == 'hub' ? invVariable(inv, vref.id as String) : null
if (row != null && row.writable == false) vBlock(r, 'VARIABLE_NOT_WRITABLE', kp[1] as String, "rules may not change the hub variable '${vref.name}'")
}
if (a.type == 'return' && a.ref != null) checkKind(a.ref, 'variable', "${p}.ref".toString())
if (a.type == 'setMode' && a.ref != null) checkKind(a.ref, 'setMode', "${p}.ref".toString())
if ((a.type == 'capture' || a.type == 'restore' || a.type == 'deviceEnabled') && a.refs instanceof List) {
((List) a.refs).eachWithIndex { Object k, int i -> checkKind(k, 'command', "${p}.refs.${i}".toString()) }
}
List sends = schemaStepCommands(a)
if (sends && a.refs instanceof List) {
((List) a.refs).eachWithIndex { Object k, int i ->
String kp = "${p}.refs.${i}".toString()
if (!checkKind(k, 'command', kp)) return
Map d = deviceOf(k)
if (d == null) return
if (d.commands == null) {
List needOffer = sends.findAll { !schemaDeviceOffers(it as String, null) }
if (needOffer) vBlock(r, 'COMMAND_UNSUPPORTED', kp,
"HAI could not find out from the hub which commands '${d.name}' takes, and sends ${needOffer.join(', ')} only to a device that offers them")
else vWarn(r, 'COMMAND_UNVERIFIED', kp, "HAI could not find out from the hub which commands '${d.name}' takes, so this one was not checked")
}
else sends.each { Object c -> if (!schemaDeviceOffers(c as String, d.commands)) vBlock(r, 'COMMAND_UNSUPPORTED', kp, "'${d.name}' does not offer the command '${c}'") }
}
}
if (a.type == 'track' && !((List) (doc.triggers ?: [])).any { it instanceof Map && ((Map) it).kind in ['deviceAttribute', 'threshold', 'button'] }) {
vBlock(r, 'EVENT_DEVICE_NONE', p, 'no device trigger starts this rule, so there is never a device that fired it to follow')
}
if (a.type == 'byMode' && a.cases instanceof List) {
((List) a.cases).eachWithIndex { Object co, int i ->
if (!(co instanceof Map) || !(((Map) co).modes instanceof List)) return
((List) ((Map) co).modes).each { Object m ->
if (m instanceof String && !((Set) inv.modes).contains(m)) vBlock(r, 'REF_MISSING', "${p}.cases.${i}.modes".toString(), "there is no mode called '${m}' on this hub")
}
}
}
if (a.type == 'customCommand' && a.ref != null && checkKind(a.ref, 'command', "${p}.ref".toString())) {
Map d = deviceOf(a.ref)
if (d != null) {
if (d.commands == null) vWarn(r, 'COMMAND_UNVERIFIED', "${p}.command", "HAI could not find out from the hub which commands '${d.name}' takes, so this one was not checked")
else if (!((Set) d.commands).contains(a.command)) vBlock(r, 'COMMAND_UNSUPPORTED', "${p}.command", "'${d.name}' does not offer the command '${a.command}'")
}
}
if (a.type == 'setMode' && a.value instanceof String && !((Set) inv.modes).contains(a.value)) {
vBlock(r, 'REF_MISSING', "${p}.value", "there is no mode called '${a.value}' on this hub")
}
if ((a.type == 'setRuleBoolean' || a.type == 'controlRule') && a.rules instanceof List) {
((List) a.rules).eachWithIndex { Object k, int i ->
String got = kindOf(k) as String
String rp = "${p}.rules.${i}".toString()
if (got != null && !(got in ['self', 'rule', 'rmRule'])) {
vBlock(r, 'REF_WRONG_KIND', rp, "'${k}' is a ${got} reference, and this needs a rule")
} else if (a.type == 'controlRule' && a.op == 'runActions' && got in ['self', 'rule']) {
vBlock(r, 'REF_WRONG_KIND', rp, 'to run an HAI rule, use the Run another rule step')
} else if (a.type == 'controlRule' && a.op in ['disable', 'enable'] && got in ['self', 'rule']) {
vBlock(r, 'REF_WRONG_KIND', rp,
"only a Rule Machine rule can be switched off this way. ${RM_REF_SHAPE}, even when it is switched over to an HAI copy. Stop an HAI rule from the editor, or use pause")
}
}
}
}
}
@Field static final String RM_REF_SHAPE = 'A Rule Machine rule is declared under refs as {"kind": "rmRule", "id": "<its app id>"}, and that key goes in rules'
boolean vLooksRm(Object id) {
return id != null && (id.toString() ==~ /(?i)(rm|rmRule|rule)?:?\d+/)
}
void vCheckCoercion(Map r, Map a, String path, Map ref, Map inv) {
String literal = vLiteralType(a.value)
if (literal == null) return
if (a.valueType != null && a.valueType != literal && !(literal == 'string' && a.valueType in ['date', 'time', 'datetime'])) {
vBlock(r, 'VALUE_TYPE_MISMATCH', "${path}.valueType",
"the value is ${literal}, but valueType says ${a.valueType}")
return
}
Map v = ref ? invVariable(inv, ref.id as String) : null
String target = (v?.type ?: ref?.type) as String
if (target == null) {
vWarn(r, 'COERCION_UNVERIFIED', path, 'the type of this variable is not known, so the write could not be checked')
return
}
if (v?.writable == false) {
vBlock(r, 'VARIABLE_NOT_WRITABLE', "${path}.ref",
"rules may not change the hub variable '${v.name}'. Choose it under the variables rules may change in HAI Runtime, or use a different one.")
return
}
if (v?.integer == true && literal == 'number' && !(new BigDecimal(a.value.toString()).stripTrailingZeros().scale() <= 0)) {
vBlock(r, 'COERCION_LOSSY', path, "'${v.name}' holds whole numbers only, so ${a.value} would lose its fraction",
[from: 'number', to: 'integer'])
return
}
String from = literal
if (literal == 'string' && a.valueType in ['date', 'time', 'datetime']) {
String s = a.value as String
boolean shaped = a.valueType == 'time' ? (s ==~ /\d{2}:\d{2}(:\d{2})?/) :
a.valueType == 'date' ? (s ==~ /\d{4}-\d{2}-\d{2}/) :
(s ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,3})?)?([+-]\d{2}:?\d{2}|Z)?/)
if (!shaped) {
vBlock(r, 'VALUE_TYPE_MISMATCH', "${path}.value", "'${s}' is not a ${a.valueType}")
return
}
from = a.valueType as String
}
if (!schemaCoercionIsLossless(from, target)) {
vBlock(r, 'COERCION_LOSSY', path,
("writing a ${from} into '${ref?.name ?: a.ref}', which is a ${target}, would lose or invent " +
'information. Convert it explicitly, or change the variable type.').toString(),
[from: from, to: target])
}
}
Map vRequiredCapabilities(Map doc) {
Map r = vResult()
Map required = [:]
vWalkActions(r, doc) { Map a, String path ->
List ops = a.type == 'command' && a.command instanceof String ? [a.command] : schemaStepCommands(a)
ops.each { Object c ->
Map op = schemaOperation(c as String)
if (op == null) return
((a.ref != null ? [a.ref] : []) + (a.refs instanceof List ? a.refs : [])).each { Object key ->
Set set = (Set) required.get(key)
if (set == null) { set = [] as Set; required.put(key, set) }
set.addAll((List) op.requires)
}
}
}
return required.collectEntries { k, v -> [(k): ((Set) v).sort()] }
}
void vCheckStructure(Map r, Map doc) {
if (!vTick(r)) return
vWalkAllConditions(r, doc) { Map n, String path ->
if ((n.type == 'all' || n.type == 'any') && n.of instanceof List && ((List) n.of).isEmpty()) {
vWarn(r, 'CONDITION_EMPTY', path, n.type == 'all'
? 'an empty all condition is always true, so it checks nothing'
: 'an empty any condition is always false, so what it guards never runs')
}
}
}
void vCheckBehaviour(Map r, Map doc) {
if (!vTick(r)) return
Set triggerOn = [] as Set
((List) (doc.triggers ?: [])).each { Object t ->
if (!(t instanceof Map)) return
Map trig = t
if (trig.ref && trig.attribute) triggerOn << "${trig.ref}:${trig.attribute}".toString()
}
vWalkActions(r, doc) { Map a, String path ->
if (a.type != 'command' || !(a.command instanceof String)) return
Map op = schemaOperation(a.command as String)
if (op == null) return
((List) op.writes).each { String attr ->
if (triggerOn.contains("${a.ref}:${attr}".toString())) {
vWarn(r, 'BEHAVIOUR_SELF_RETRIGGER', path,
"this action writes ${attr} on a device this rule also triggers on, so it can retrigger itself")
}
}
}
boolean repeats = false, stops = false
vWalkActions(r, doc) { Map a, String path -> if (a.type == 'repeat') repeats = true; if (a.type == 'stopRepeat') stops = true }
boolean waitsOut = (doc.rerunPolicy ?: 'restart') in ['ignore', 'queue']
vWalkActions(r, doc) { Map a, String path ->
if (a.type == 'stopRepeat' && !repeats) vWarn(r, 'REPEAT_NOTHING_TO_STOP', path, 'this rule has no repeat for this step to stop')
if (a.type == 'repeat' && a.repeatKind == 'every' && a.times == null && (!stops || waitsOut)) {
vWarn(r, 'REPEAT_ENDLESS', path, (waitsOut ? 'a later run of this rule cannot start while this one repeats, so ' : '') +
'nothing in this rule stops this repeat; it goes on until the rule is stopped or its timers are cancelled. Set a number of times, or add a Stop repeating step')
}
}
Set gradual = [] as Set
boolean stopsAll = false
vWalkActions(r, doc) { Map a, String path ->
if (a.type == 'flashSwitches') gradual << 'flash'
if (a.type == 'fade') gradual << (a.attribute ?: 'level')
if (a.type == 'stopGradual' && a.what in [null, 'flash']) stopsAll = true
}
vWalkActions(r, doc) { Map a, String path ->
if (a.type == 'stopGradual' && (a.what == null ? gradual.isEmpty() : !gradual.contains(a.what))) {
vWarn(r, 'GRADUAL_NOTHING_TO_STOP', path, 'this rule starts no fade or flash of that kind for this step to stop')
}
if (a.type == 'flashSwitches' && a.times == null && !stopsAll) {
vWarn(r, 'FLASH_ENDLESS', path, 'nothing in this rule stops this flashing; it goes on until the rule is stopped or its timers are cancelled. Set a number of times, or add a Stop fading or flashing step')
}
}
if (doc.actions instanceof List) vOverlaps(r, doc.actions, 'actions', [:])
if (doc.onFailure instanceof List) vOverlaps(r, doc.onFailure, 'onFailure', [:])
if (doc.onCancel instanceof List) vOverlaps(r, doc.onCancel, 'onCancel', [:])
}
Map vOverlaps(Map r, List actions, String base, Map before) {
Map writers = new LinkedHashMap(before)
boolean ended = false
actions.eachWithIndex { Object ao, int i ->
if (ended || !(ao instanceof Map)) return
Map a = ao
String path = "${base}.${i}".toString()
if ((a.type == 'exit' || a.type == 'return') && a.disabled != true) { ended = true; writers = [:]; return }
if (a.type == 'wait') { writers.clear(); return }
if (a.type == 'repeat') {
if (a.steps instanceof List) vOverlaps(r, a.steps, "${path}.steps".toString(), [:])
writers.clear()
return
}
if (a.type == 'later') { if (a.actions instanceof List) vOverlaps(r, a.actions, "${path}.actions".toString(), [:]); return }
if (a.type == 'byMode') {
List outs = []
((List) (a.cases instanceof List ? a.cases : [])).eachWithIndex { Object c, int j ->
if (c instanceof Map && ((Map) c).then instanceof List) outs << vOverlaps(r, ((Map) c).then, "${path}.cases.${j}.then".toString(), writers)
}
outs << (a.otherwise instanceof List ? vOverlaps(r, a.otherwise, "${path}.otherwise".toString(), writers) : writers)
Map merged = [:]
outs.each { Map o -> o.each { k, v -> if (!merged.containsKey(k)) merged.put(k, v) } }
writers = merged
return
}
if (a.type == 'if') {
List outs = []
if (a.then instanceof List) outs << vOverlaps(r, a.then, "${path}.then".toString(), writers)
if (a.elseIf instanceof List) {
((List) a.elseIf).eachWithIndex { Object e, int j ->
if (e instanceof Map && ((Map) e).then instanceof List) {
outs << vOverlaps(r, ((Map) e).then, "${path}.elseIf.${j}.then".toString(), writers)
}
}
}
outs << (a.otherwise instanceof List ? vOverlaps(r, a.otherwise, "${path}.otherwise".toString(), writers) : writers)
Map merged = [:]
outs.each { Map o -> o.each { k, v -> if (!merged.containsKey(k)) merged.put(k, v) } }
writers = merged
return
}
if (a.type != 'command' || !(a.command instanceof String)) return
Map op = schemaOperation(a.command as String)
if (op == null) return
((List) op.writes).each { String attr ->
String slot = "${a.ref}:${attr}".toString()
if (writers.containsKey(slot)) {
vWarn(r, 'BEHAVIOUR_OVERLAPPING_WRITE', path,
"an earlier step in this rule already sets ${attr} on this device; this later one wins")
} else {
writers.put(slot, path)
}
}
}
return writers
}
void vCheckSafety(Map r, Map doc, Map inv) {
if (!vTick(r)) return
Map refs = (doc.refs ?: [:])
boolean risky = false
vWalkActions(r, doc) { Map a, String path ->
if (a.type == 'hsm') {
risky = true
return
}
if (a.type == 'deviceEnabled') {
risky = true
return
}
if (a.type != 'command' || !(a.command instanceof String)) return
Map op = schemaOperation(a.command as String)
if (op == null) return
String risk = op.risk as String
if (risk == 'none') return
risky = true
Map riskClass = RISK_CLASSES.get(risk)
List keys = (a.ref != null ? [a.ref] : []) + (a.refs instanceof List ? a.refs : [])
if (keys.any { ((Map) refs.get(it))?.kind == 'eventDevice' }) {
vBlock(r, 'SAFETY_EVENT_DEVICE', path, "this step can open, unlock or sound something, so it must name its device; it cannot go to \"the device that started the rule\"")
return
}
if (keys.size() > 1) {
keys.each { Object k ->
Map dk = ((Map) refs.get(k))?.id ? invDevice(inv, ((Map) refs.get(k)).id as String) : null
}
}
Map ref = refs.get(keys ? keys[0] : null)
Map d = ref ? invDevice(inv, ref.id as String) : null
}
if (doc.onUnknown == 'proceed' && risky) {
vWarn(r, 'SAFETY_PROCEED_ON_UNKNOWN', 'onUnknown',
'this rule controls a lock, door, siren or valve, and is set to carry on when a condition cannot be checked')
}
}
void vCollectRefKeys(Map r, Object node, Set out, boolean skipDeviceEnabled = false) {
if (!vTick(r)) return
if (node instanceof Map) {
boolean skipRefs = skipDeviceEnabled && ((Map) node).type == 'deviceEnabled'
((Map) node).each { k, v ->
if (k == 'refs' && skipRefs) return
if ((k == 'refs' || k == 'rules') && v instanceof List) ((List) v).each { if (it instanceof String) out << it }
else if ((k == 'ref' || k == 'result') && v instanceof String) out << v
else if (k == 'into' && v instanceof Map) ((Map) v).values().each { if (it instanceof String) out << it }
else if (k != 'refs' && k != 'rules') vCollectRefKeys(r, v, out, skipDeviceEnabled)
}
return
}
if (node instanceof List) ((List) node).each { vCollectRefKeys(r, it, out, skipDeviceEnabled) }
}
void vWalkActions(Map r, Map doc, Closure visit) {
vWalkActionList(r, doc.actions, 'actions', visit)
vWalkActionList(r, doc.onFailure, 'onFailure', visit)
vWalkActionList(r, doc.onCancel, 'onCancel', visit)
}
void vWalkActionList(Map r, Object actions, String path, Closure visit) {
if (!(actions instanceof List)) return
((List) actions).eachWithIndex { Object a, int i ->
if (!vTick(r)) return
if (!(a instanceof Map)) return
Map m = a
String p = "${path}.${i}".toString()
visit(m, p)
vWalkActionList(r, m.then, "${p}.then".toString(), visit)
vWalkActionList(r, m.otherwise, "${p}.otherwise".toString(), visit)
vWalkActionList(r, m.steps, "${p}.steps".toString(), visit)
if (m.type == 'later') vWalkActionList(r, m.actions, "${p}.actions".toString(), visit)
if (m.type == 'byMode' && m.cases instanceof List) {
((List) m.cases).eachWithIndex { Object c, int j ->
if (c instanceof Map) vWalkActionList(r, ((Map) c).then, "${p}.cases.${j}.then".toString(), visit)
}
}
if (m.elseIf instanceof List) {
((List) m.elseIf).eachWithIndex { Object e, int j ->
if (e instanceof Map) vWalkActionList(r, ((Map) e).then, "${p}.elseIf.${j}.then".toString(), visit)
}
}
}
}
void vWalkAllConditions(Map r, Map doc, Closure visit) {
vWalkConditions(r, doc.conditions, 'conditions', visit)
vWalkConditions(r, doc.requiredExpression, 'requiredExpression', visit)
((List) (doc.triggers instanceof List ? doc.triggers : [])).eachWithIndex { Object t, int i ->
if (t instanceof Map && ((Map) t).kind == 'duration') vWalkConditions(r, ((Map) t).predicate, "triggers.${i}.predicate".toString(), visit)
if (t instanceof Map) vWalkConditions(r, ((Map) t).condition, "triggers.${i}.condition".toString(), visit)
}
vWalkActions(r, doc) { Map a, String p ->
if (a.type == 'if') {
vWalkConditions(r, a.condition, "${p}.condition".toString(), visit)
if (a.elseIf instanceof List) {
((List) a.elseIf).eachWithIndex { Object e, int j ->
if (e instanceof Map) vWalkConditions(r, ((Map) e).condition, "${p}.elseIf.${j}.condition".toString(), visit)
}
}
}
if (a.type == 'wait' && a.waitKind == 'until') vWalkConditions(r, a.predicate, "${p}.predicate".toString(), visit)
if (a.type == 'repeat') vWalkConditions(r, a.condition, "${p}.condition".toString(), visit)
if (a.type == 'wait' && a.waitKind == 'forEvents' && a.events instanceof List) {
((List) a.events).eachWithIndex { Object e, int i ->
if (e instanceof Map && ((Map) e).kind == 'duration') vWalkConditions(r, ((Map) e).predicate, "${p}.events.${i}.predicate".toString(), visit)
}
}
}
}
void vWalkConditions(Map r, Object nodeIn, String path, Closure visit) {
if (!(nodeIn instanceof Map)) return
if (!vTick(r)) return
Map node = nodeIn
visit(node, path)
if (node.of instanceof List) {
((List) node.of).eachWithIndex { Object c, int i -> vWalkConditions(r, c, "${path}.of.${i}".toString(), visit) }
} else if (node.of instanceof Map) {
vWalkConditions(r, node.of, "${path}.of".toString(), visit)
}
}
@Field static final String BIND_RESOLVED = 'resolved'
@Field static final String BIND_CANDIDATE = 'candidate'
@Field static final String BIND_UNRESOLVED = 'unresolved'
@Field static final List LOCAL_ID_KINDS = ['device', 'variable']
Map bundleExport(List docs, Map inv, Map provenance = [:], Map scenarios = [:]) {
List portableRules = []
Map bindings = [:]
docs.each { Object d ->
Map doc = d
Map copy = bundleDeepCopy(doc)
Map refs = (copy.refs ?: [:])
Map ruleBindings = [:]
Map needs = vRequiredCapabilities(copy)
refs.each { Object k, Object v -> if (needs.containsKey(k) && ((Map) v).kind == 'device') ((Map) v).capabilities = needs.get(k) }
refs.each { Object k, Object v ->
Map ref = v
if (ref.containsKey('id') && LOCAL_ID_KINDS.contains(ref.kind)) {
ruleBindings[k] = ref.remove('id')
}
}
bindings[copy.id] = ruleBindings
portableRules << copy
}
Map portable = [
schema : SCHEMA_ID,
compilerVersion : COMPILER_VERSION,
catalogueVersion: CATALOGUE_VERSION,
rules : portableRules,
]
Map attached = bundleScenariosPortable(scenarios)
if (attached) portable.scenarios = attached
Map manifest = [
bindings : bindings,
provenance: provenance,
]
return [portable: portable, manifest: manifest]
}
Map bundleScenariosPortable(Map scenarios) {
Map out = [:]
((Map) (scenarios ?: [:])).each { Object ruleId, Object list ->
List copied = []
((List) (list ?: [])).each { Object sc ->
Map scenario = bundleDeepCopy((Map) sc)
List offenders = []
Closure walk
walk = { Object n ->
if (n instanceof List) { n.each { walk(it) } }
else if (n instanceof Map) {
Map m = n
if (m.containsKey('device') || m.containsKey('id')) offenders << (m.device ?: m.id)
m.values().each { walk(it) }
}
}
walk(scenario)
if (!offenders.isEmpty()) {
throw new IllegalArgumentException(
"scenario '${scenario.name}' names a local id ${offenders.first()}; scenarios travel by reference key".toString())
}
copied << scenario
}
if (!copied.isEmpty()) out[ruleId] = copied
}
return out
}
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
Map bundleImport(Map bundle, Map inv, boolean sameHubRestore = false) {
Map portable = bundle.portable
List resolution = []
if (portable.schema != SCHEMA_ID) {
return [rules: [], resolution: [], enabled: false, approved: false, blocked: true,
refusal: [code: 'BUNDLE_SCHEMA', message:
"this bundle declares schema '${portable.schema}', and this engine reads '${SCHEMA_ID}'".toString()]]
}
if (portable.catalogueVersion != CATALOGUE_VERSION) {
return [rules: [], resolution: [], enabled: false, approved: false, blocked: true,
refusal: [code: 'BUNDLE_CATALOGUE_VERSION', message:
("this bundle was written against catalogue ${portable.catalogueVersion} and this engine has " +
"${CATALOGUE_VERSION}; an operation may have changed meaning").toString()]]
}
Map bindings = ((bundle.manifest ?: [:]).bindings ?: [:])
List rules = []
((List) portable.rules).each { Object r ->
Map rule = bundleDeepCopy(r)
Map refs = (rule.refs ?: [:])
Map ruleBindings = (bindings.get(rule.id) ?: [:])
refs.each { Object k, Object v ->
Map ref = v
Map found = bundleResolveRef(ref, inv, sameHubRestore ? ruleBindings.get(k) : null)
Map row = [rule: rule.id, refKey: k, kind: ref.kind, status: found.status,
chosen: found.chosen, alternatives: found.alternatives, why: found.why]
if (found.nearMisses) row.nearMisses = found.nearMisses
resolution << row
if (found.chosen != null && ((List) ALLOWED_FIELDS.get("ref:${ref.kind}".toString()))?.contains('id')) ref.id = found.chosen
}
rules << rule
}
Map scenarios = (portable.scenarios ?: [:])
List scenarioReview = bundleScenarioReview(scenarios, inv)
return [rules: rules, resolution: resolution, enabled: false, approved: false,
scenarios: scenarios, scenarioReview: scenarioReview,
blocked: bundleBlocked(resolution), refusal: null]
}
List bundleScenarioReview(Map scenarios, Map inv) {
List out = []
String here = (inv?.zone) as String
((Map) (scenarios ?: [:])).each { Object ruleId, Object list ->
((List) (list ?: [])).each { Object sc ->
Map scenario = sc
String there = (((Map) (scenario.environment ?: [:])).zone) as String
if (there == null) return
if (here == null) {
out << [rule: ruleId, scenario: scenario.name, code: 'SCENARIO_ZONE_UNKNOWN',
scenarioZone: there, destinationZone: null,
why: 'this hub declares no time zone, so the zone of the scenario cannot be confirmed']
} else if (here != there) {
out << [rule: ruleId, scenario: scenario.name, code: 'SCENARIO_ZONE_CHANGED',
scenarioZone: there, destinationZone: here,
why: "the scenario was recorded in ${there} and this hub is ${here}; sun times and wall-clock behaviour differ".toString()]
}
}
}
return out
}
boolean bundleBlocked(List resolution) {
return resolution.any { it.status != BIND_RESOLVED }
}
Map bundleResolveRef(Map ref, Map inv, Object recordedLocalId) {
String kind = ref.kind as String
if (kind == 'mode') {
boolean ok = inv.modes.contains(ref.name)
return [status: ok ? BIND_RESOLVED : BIND_UNRESOLVED, chosen: ok ? ref.name : null,
alternatives: [], why: ok ? 'mode exists' : 'no mode of that name']
}
if (kind == 'rule') {
boolean ok = inv.rules.contains(ref.id)
return [status: ok ? BIND_RESOLVED : BIND_UNRESOLVED, chosen: ok ? ref.id : null,
alternatives: [], why: ok ? 'rule exists' : 'no rule of that logical id']
}
if (kind == 'variable') {
if (recordedLocalId != null) {
Map rec = invVariable(inv, recordedLocalId as String)
if (rec != null && rec.name == ref.name && (!ref.type || rec.type == ref.type)) {
return [status: BIND_RESOLVED, chosen: rec.id, alternatives: [],
why: 'same-hub restore, recorded variable still has that name and type']
}
}
List hits = inv.variables.values().findAll { it.name == ref.name && (!ref.type || it.type == ref.type) }.collect { it.id }
if (hits) {
return [status: BIND_CANDIDATE, chosen: null, alternatives: hits,
why: hits.size() == 1 ? 'a variable with that name and type exists; confirm it is the same one'
: 'several variables share that name and type']
}
List sameName = inv.variables.values().findAll { it.name == ref.name }.collect { it.id }
if (sameName) {
return [status: BIND_UNRESOLVED, chosen: null, alternatives: [], nearMisses: sameName,
why: 'a variable of that name exists but holds a different type, so it cannot be bound']
}
return [status: BIND_UNRESOLVED, chosen: null, alternatives: [], why: 'no variable of that name']
}
if (kind != 'device') {
return [status: BIND_UNRESOLVED, chosen: null, alternatives: [], why: "unsupported reference kind '${kind}'".toString()]
}
Set needed = (((ref.capabilities ?: []) as List) as Set)
if (recordedLocalId != null) {
Map d = invDevice(inv, (String) recordedLocalId)
if (d != null && d.disabled != true && needed.every { ((Set) (d.capabilities as Set)).contains(it) }) {
return [status: BIND_RESOLVED, chosen: d.id, alternatives: [],
why: 'same-hub restore, recorded id still has the capabilities this rule uses']
}
}
List capable = inv.devices.values().findAll { Map d ->
d.disabled != true && needed.every { ((Set) (d.capabilities as Set)).contains(it) }
}
if (capable.isEmpty()) {
return [status: BIND_UNRESOLVED, chosen: null, alternatives: [],
why: "no device here has ${needed.join(', ') ?: 'the required capabilities'}".toString()]
}
List exact = capable.findAll { it.name == ref.name }
if (exact.size() == 1) {
return [status: BIND_CANDIDATE, chosen: null, alternatives: [exact[0].id],
why: 'one device of that name has the capabilities this rule uses; confirm it is the same device']
}
if (exact.size() > 1) {
return [status: BIND_CANDIDATE, chosen: null, alternatives: exact.collect { it.id },
why: 'several devices share that name']
}
List sameRoom = capable.findAll { ref.room && it.room == ref.room }
if (sameRoom) {
return [status: BIND_CANDIDATE, chosen: null, alternatives: sameRoom.collect { it.id },
why: 'same room and the right capabilities, but a different name']
}
return [status: BIND_CANDIDATE, chosen: null, alternatives: capable.collect { it.id },
why: 'capable devices exist, none matching by name or room']
}
Map bundleResolutionSummary(Map imported, Map inv = null) {
List res = imported.resolution
boolean complete = !imported.blocked
List invalid = null
if (complete && inv != null) {
invalid = ((List) imported.rules).findAll { !vActivatable(validateRule((Map) it, inv)) }.collect { it.id }
}
return [
resolved : res.count { it.status == BIND_RESOLVED },
candidates : res.count { it.status == BIND_CANDIDATE },
unresolved : res.count { it.status == BIND_UNRESOLVED },
bindingComplete: complete,
validated : invalid != null,
invalidRules : invalid ?: [],
activatable : complete && invalid != null && invalid.isEmpty(),
enabled : imported.enabled,
approved : imported.approved,
]
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
@Field static final String SEV_WARN = 'warn'
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
void coreIntentRefused(Map w, Map intent, String outcome, String reason, String code) {
if (intent.state == CONF_REQUESTED) {
intent.state = outcome
intent.unknownReason = reason
intent.refusedAt = coreNow(w)
if (code != null) intent.code = code
}
coreLog(w, 'dispatchRefused', [intent: intent.id, run: intent.run, state: intent.state, code: code, reason: reason])
}
@Field static final long SIM_DEFAULT_LATENCY_MS = 500L
Map simNew(Map inv, long startAt, String zone) {
Map sim = coreNewWorld()
sim.putAll([
inv : inv,
clock : startAt,
zone : zone,
restartEpoch: 0,
state : [:],
variables : [:],
mode : null,
timers : [],
effects : [],
ackScript : [:],
latency : SIM_DEFAULT_LATENCY_MS,
trace : [],
physical : [:],
readable : [] as Set,
sun : [:],
requests : [],
replies : [:],
files : [:],
])
sim.port = simPort(sim)
return sim
}
Map simPort(Map sim) {
return [
now : { Object unused -> sim.clock as long },
restartEpoch : { Object unused -> sim.restartEpoch as int },
zone : { Object unused -> sim.zone as String },
inventory : { Object unused -> sim.inv },
validate : { Map doc -> validateRule(doc, sim.inv) },
rmSwitched : { Object rmId -> ((Map) (sim.rmSwitched ?: [:])).get(rmId as String) },
sun : { String date -> sim.sun.get(date) },
halted : { Object unused -> sim.halted ? true : false },
read : { String dev, Object attr ->
Map d = sim.state.get(dev)
return d == null ? null : d.get(attr)
},
record : { String dev, Object attr, Map cell ->
Map d = sim.state.get(dev)
if (d == null) { d = [:]; sim.state.put(dev, d) }
d.put(attr, cell)
},
observed : { String dev, Object attr, Object value -> simSetPhysical(sim, dev, attr as String, value) },
readBack : { String dev, Object attr ->
if (!((Set) sim.readable).contains(dev)) return null
Map phys = sim.physical.get(dev)
return (phys != null && phys.containsKey(attr)) ? [value: phys.get(attr)] : null
},
readMode : { Object unused -> sim.mode },
recordMode : { Object v -> sim.mode = v },
readVariable : { Object id -> sim.variables.get(id) },
writeVariable: { Object id, Map cell -> sim.variables.put(id, cell) },
subscribe : { String ruleId, Map doc -> },
unsubscribe : { String ruleId -> },
schedule : { Map timer -> simArm(sim, timer) },
cancel : { Object token -> ((List) sim.timers).removeAll { it.token == token } },
dispatch : { Map effect -> simEmit(sim, effect) },
ackModel : { Map intent, Map op -> simAckModel(sim, intent, op) },
persist : { Map w -> },
trace : { Map entry -> ((List) sim.trace) << entry },
net : { Map req -> simNet(sim, req) },
file : { Map req -> simFile(sim, req) },
readFile : { Object name -> (String) sim.files.get(name as String) },
]
}
void simSetReply(Map sim, String host, Map reply) { sim.replies.put(host.toLowerCase(), reply) }
void simNet(Map sim, Map req) {
((List) sim.requests) << new LinkedHashMap(req)
String host = ((req.kind == 'http' ? schemaUrlHost(req.url) : req.host) ?: '') as String
Map reply = sim.replies.get(host.toLowerCase())
if (reply == null && req.kind == 'http' && req.url == CORE_DEVICE_DISABLE_URL) {
Map b = new groovy.json.JsonSlurper().parseText(req.body as String)
if (!(sim.disabledDevices instanceof Set)) sim.disabledDevices = [] as Set
if (sim.hubIgnoresDisable != true) { if (b.disable == true) ((Set) sim.disabledDevices) << (b.id as String) else ((Set) sim.disabledDevices).remove(b.id as String) }
reply = [status: 200, body: '{"success":true}']
} else if (reply == null && req.kind == 'http' && (req.url as String).startsWith(CORE_DEVICE_JSON_URL)) {
String id = (req.url as String).substring(CORE_DEVICE_JSON_URL.length())
reply = [status: 200, body: groovy.json.JsonOutput.toJson([device: [id: id as long, disabled: ((sim.disabledDevices ?: []) as Set).contains(id)]])]
}
if (reply != null) {
simAddTimer(sim, [at: (sim.clock as long) + (sim.latency as long), kind: 'netReply', run: req.run, token: req.token, reply: reply])
return
}
String gap = "no answer from ${host} was scripted; the simulator contacts no server".toString()
if (!((List) sim.gaps).contains(gap)) ((List) sim.gaps) << gap
}
void simFile(Map sim, Map req) {
String name = req.name as String
if (req.op == 'delete') { sim.files.remove(name); return }
String text = (req.op == 'append' ? (sim.files.get(name) ?: '') : '') + (req.text ?: '')
if (text.length() > NET_MAX_BODY_CHARS) throw new Exception("the file would be ${text.length()} characters, over ${NET_MAX_BODY_CHARS}")
sim.files.put(name, text)
}
void simSetSun(Map sim, String date, String sunrise, String sunset) {
sim.sun.put(date, [sunrise: sunrise, sunset: sunset])
}
void simSeed(Map sim, String deviceId, String attribute, Object value, long ts) {
Map dev = sim.state.get(deviceId)
if (dev == null) { dev = [:]; sim.state.put(deviceId, dev) }
dev.put(attribute, [value: value, ts: ts, seq: coreNextSeq(sim), source: 'seed', epoch: sim.restartEpoch])
simSetPhysical(sim, deviceId, attribute, value)
}
void simSetPhysical(Map sim, String deviceId, String attribute, Object value) {
Map dev = sim.physical.get(deviceId)
if (dev == null) { dev = [:]; sim.physical.put(deviceId, dev) }
dev.put(attribute, value)
}
Map simLoad(Map sim, Map doc) { return coreLoad(sim, doc) }
Map simInject(Map sim, Map event) { return coreInject(sim, event) }
String simAckBehaviour(Map sim, Map intent) {
return (sim.ackScript.get("${intent.device}|${intent.command}".toString()) ?: 'ack') as String
}
void simAckModel(Map sim, Map intent, Map op) {
String behaviour = simAckBehaviour(sim, intent)
if (behaviour != 'fail') {
((List) intent.targets).each { Object to ->
Map t = to
if (t.value != null) simSetPhysical(sim, intent.device as String, t.attribute as String, t.value)
}
}
if (op.ack == 'none') return
if (behaviour == 'ack' || behaviour == 'observed') {
((List) intent.targets).each { Object to ->
Map t = to
if (t.value == null) return
simAddTimer(sim, [at: (sim.clock as long) + (sim.latency as long), kind: 'echo',
event: [kind: 'attribute', device: intent.device, attribute: t.attribute,
value: t.value, source: 'engine', intent: intent.id,
observed: behaviour == 'observed']])
}
}
}
void simArm(Map sim, Map timer) {
if (timer.kind == 'verify') timer.behaviour = simAckBehaviour(sim, sim.ledger.get(timer.intent))
simAddTimer(sim, timer)
}
void simAddTimer(Map sim, Map timer) {
timer.seq = coreNextSeq(sim)
((List) sim.timers) << timer
}
void simAdvance(Map sim, long ms) {
long until = (sim.clock as long) + ms
int guard = 0
while (true) {
if (sim.halted) return
List due = ((List) sim.timers).findAll { (it.at as long) <= until }
if (due.isEmpty()) break
if (++guard > 100000) throw new Exception('timer loop did not settle')
Map next = due.min { a, b -> (a.at as long) <=> (b.at as long) ?: (a.seq as long) <=> (b.seq as long) }
((List) sim.timers).remove(next)
if ((next.at as long) > (sim.clock as long)) sim.clock = next.at
simFireTimer(sim, next)
}
sim.clock = until
}
void simFireTimer(Map sim, Map t) {
if (sim.halted) return
if (t.kind == 'echo') {
Map e = new LinkedHashMap((Map) t.event)
e.ts = sim.clock
simInject(sim, e)
return
}
if (t.kind == 'netReply') { coreNetReply(sim, t.run as String, t.token as String, t.reply); return }
coreFire(sim, t)
}
Map simReport(Map sim) {
List runs = sim.runs.values().collect { Map r ->
[id: r.id, rule: r.rule, epoch: r.epoch, status: r.status, how: r.endedHow ?: r.cancelReason,
errors: r.errors]
}
return [
effects : sim.effects,
blocked : sim.blocked,
intents : sim.ledger.values().collect { [id: it.id, command: it.command, device: it.device,
state: it.state, retries: it.retries,
possiblyInFlight: it.possiblyInFlight ?: false] },
runs : runs,
coverage : [complete: ((List) sim.gaps).isEmpty(), gaps: sim.gaps,
restarts: sim.restartEpoch,
partial: ['confirmation uses a scripted acknowledgement model, not independent physical evidence',
'command targets come from a fixed table, not a reviewed device-aware model',
'rule documents carry no write priority; undeclared writes are priority 0',
'persistence, watchdog and missed-callback recovery are not simulated']],
assumptions: ['the command model describes how these devices acknowledge',
'events arrive in the order they were injected',
'sun times are exactly as supplied',
'no controller other than the ones injected acts on these devices'],
simulatedTo: sim.clock,
]
}
void simEmit(Map sim, Map effect) {
((List) sim.effects) << effect
Closure check = (Closure) sim.onEffect
if (check != null && !sim.halted) {
Map d = check(effect)
if (d != null) {
sim.halted = d
coreLog(sim, 'halted', [why: d.why, index: d.index])
}
}
}
@Field static final Map DRY_RUN_READS = [
toggle: 'switch', toggleLevel: 'switch', toggleColor: 'switch', toggleColorTemperature: 'switch',
togglePlay: 'status', toggleMute: 'mute', adjustLevel: 'level',
adjustHeatingSetpoint: 'heatingSetpoint', adjustCoolingSetpoint: 'coolingSetpoint',
]
@Field static final Set DRY_RUN_OUTPUTS = ['variableWritten', 'ruleBooleanSet', 'ruleControlled', 'rmRuleAction', 'fileWritten'] as Set
@Field static final Map DRY_RUN_DEFAULTS = [
level: 50, switch: 'off', status: 'stopped', mute: 'unmuted', heatingSetpoint: 20, coolingSetpoint: 24,
]
Map simDryRun(Map doc, long at, String zone, Map hubInv = null) {
Map refs = (doc?.refs ?: [:])
List leaves = [], leafNeg = [], ifs = []
long delay = 0L
Map cmds = [:], attrs = [:], actionReads = [:]
Closure walk
walk = { Object n, boolean neg = false ->
if (n instanceof List) { ((List) n).each { walk(it, neg) }; return }
if (!(n instanceof Map)) return
Map m = n
List keys = m.ref instanceof String ? [m.ref] : []
if (m.refs instanceof List) keys.addAll(((List) m.refs).findAll { it instanceof String })
keys.each { Object k ->
if (m.command) ((Set) (cmds[k] ?: (cmds[k] = [] as Set))) << m.command
if (m.attribute) ((Set) (attrs[k] ?: (attrs[k] = [] as Set))) << m.attribute
if (m.kind == 'button') ((Set) (attrs[k] ?: (attrs[k] = [] as Set))) << ((m.action ?: 'pushed') as String)
String reads = m.command ? (String) DRY_RUN_READS.get(m.command as String) : null
if (reads) ((Set) (actionReads[k] ?: (actionReads[k] = [] as Set))) << reads
if (m.command && m.onlyIfOn == true) ((Set) (actionReads[k] ?: (actionReads[k] = [] as Set))) << 'switch'
}
if (m.type == 'deviceAttribute' && (m.ref instanceof String || m.refs instanceof List)) { leaves << m; leafNeg << neg }
if (m.type == 'if' && m.condition instanceof Map) ifs << m
if (m.type == 'wait') delay += ((m.seconds ?: m.timeoutSeconds ?: m.staysSeconds ?: 0) as long)
boolean under = m.type == 'not' ? !neg : neg
m.values().each { walk(it, under) }
}
walk(doc?.triggers); walk(doc?.conditions); walk(doc?.requiredExpression); walk(doc?.actions)
boolean gated = doc?.conditions != null || doc?.requiredExpression != null
boolean real = hubInv != null
Map inv = real ? hubInv : invNew()
refs.each { Object k, Object v ->
Map r = v
if (r.kind == 'device') {
if (!real || !((Map) inv.devices).containsKey(r.id as String))
inv.devices[r.id as String] = [id: r.id, name: r.name, room: 'Dry run', capabilities: ((r.capabilities ?: []) as Set),
commands: (cmds[k] ?: []) as Set, attributes: (attrs[k] ?: []) as Set]
}
else if (r.kind == 'variable') {
if (!real || (r.scope == 'rule' && !((Map) inv.variables).containsKey(r.id as String)))
inv.variables[r.id as String] = [id: r.id, name: r.name, type: r.type ?: 'string', scope: r.scope ?: 'hub']
}
else if (real) return
else if (r.kind == 'mode') inv.modes << (r.name ?: r.id)
else if (r.kind == 'rule') ((Set) inv.rules) << r.id
else if (r.kind == 'rmRule') ((Set) inv.rmRules) << r.id
}
Closure dev = { Object key -> ((Map) (refs[key] ?: [:])).id as String }
Closure name = { String id -> (refs.values().find { ((Map) it).id == id } as Map)?.name ?: id }
List starts = []
actionReads.each { Object k, Object set ->
String d = dev(k)
if (d == null || ((Map) (refs[k] ?: [:])).kind != 'device') return
((Set) set).each { Object a ->
Map row = real ? ((Map) (((Map) (((Map) inv.devices).get(d) ?: [:])).currentStates ?: [:])).get(a) : null
boolean fromHub = row != null && row.value != null
starts << [device: d, attribute: a as String, value: fromHub ? row.value : DRY_RUN_DEFAULTS.get(a), fromHub: fromHub]
}
}
Closure sat = { Map c, boolean yes ->
Object v = c.value
if (v instanceof List && ((List) v).size() == 2) return yes ? ((List) v)[0] : (new BigDecimal(((List) v)[1].toString()) + 1)
BigDecimal x = null
try { x = new BigDecimal(v.toString()) } catch (Exception e) { }
switch (c.op ?: 'is') {
case 'isNot': return yes ? "not ${v}".toString() : v
case 'above': return x == null ? v : (yes ? x + 1 : x - 1)
case 'below': return x == null ? v : (yes ? x - 1 : x + 1)
case 'atLeast': return x == null ? v : (yes ? x : x - 1)
case 'atMost': return x == null ? v : (yes ? x : x + 1)
default: return yes ? v : "not ${v}".toString()
}
}
List trigs = ((List) (doc?.triggers ?: [])).findAll { it instanceof Map && ((Map) it).disabled != true }
Closure run = { int t, boolean condsTrue, Set unread, boolean again ->
Map sim = simNew(inv, at, zone)
starts.each { Map x -> simSeed(sim, x.device as String, x.attribute as String, x.value, at - 60000L) }
leaves.eachWithIndex { Map c, int i ->
if (unread.contains(c)) return
Object v = sat(c, condsTrue != (leafNeg[i] == true))
((c.refs instanceof List) ? c.refs : [c.ref]).each { Object k -> String d = dev(k); if (d) simSeed(sim, d, c.attribute as String, v, at - 60000L) }
}
Map trig = trigs[t]
boolean onDevice = trig.kind in ['deviceAttribute', 'threshold'] && dev(trig.ref) != null && trig.attribute
if (onDevice) simSeed(sim, dev(trig.ref), trig.attribute as String, sat(trig, false), at - 60000L)
Map v = simLoad(sim, doc)
if (!vActivatable(v)) {
List why = vBlocking(v).collect { Map f -> [code: f.code, message: f.message] }
if (why.isEmpty()) why << [code: 'INCOMPLETE', message: (v.incompleteReason ?: 'the check of the copy did not finish') as String]
return [refused: why]
}
String id = doc.id as String
Closure fire = {
if (onDevice) simInject(sim, [kind: 'attribute', device: dev(trig.ref), attribute: trig.attribute as String, value: sat(trig, true), ts: sim.clock])
else coreRunNow(sim, id, 'dry run')
}
fire()
if (again) { simAdvance(sim, Math.max(1000L, delay * 500L)); if (onDevice) simSeed(sim, dev(trig.ref), trig.attribute as String, sat(trig, false), sim.clock as long); fire() }
simAdvance(sim, Math.min(86400000L, delay * 1000L + 60000L))
List errors = ((List) sim.trace).findAll { Map x -> x.what == 'runError' && !(unread && x.code == 'DECISION_UNKNOWN') }
boolean acted = !((List) sim.effects).isEmpty() || !((List) sim.requests).isEmpty() || sim.mode != null ||
((List) sim.trace).any { Map x -> x.what in DRY_RUN_OUTPUTS }
return [effects: sim.effects, acted: acted, bad: !errors.isEmpty(), errors: errors.collect { Map x -> [code: x.code, message: x.message] }]
}
Closure words = { Map out ->
if (out.refused) return 'The dry run could not load the copy: ' +
((List) out.refused).collect { Map f -> "${(f.message ?: '').toString().replaceAll('\\.+\$', '')} (${f.code})".toString() }.join('; ') + '.'
List e = out.effects
if (e.isEmpty()) return 'Nothing happens: it sends no command.'
long last = at
return e.withIndex().collect { Object eo, int i ->
Map x = eo
String verb = [on: 'turns on', off: 'turns off', lock: 'locks', unlock: 'unlocks', open: 'opens', close: 'closes'][x.command] ?:
(x.command == 'setLevel' && x.args ? "is set to ${((List) x.args)[0]}%" : "gets ${x.command}${x.args ? ' ' + ((List) x.args).join(', ') : ''}")
long gap = Math.max(0L, ((x.at ?: at) as long) - last)
last = (x.at ?: at) as long
String when = gap < 1000L ? '' : gap % 60000L == 0 ? " ${gap.intdiv(60000L)} minute${gap == 60000L ? '' : 's'} later" : " ${Math.round(gap / 1000.0)} seconds later"
return (i == 0 ? '' : 'then ') + "${name(x.device as String)} ${verb}${when}"
}.join(', ').capitalize() + '.'
}
Closure says = { Map t ->
t.kind in ['deviceAttribute', 'threshold'] && dev(t.ref)
? "${name(dev(t.ref))} reports ${t.attribute} ${sat(t, true)}".toString()
: "its ${t.kind ?: 'trigger'} trigger fires".toString()
}
List out = [], check = []
boolean ok = true, anyActed = false
Closure add = { String what, Map r, String note ->
if (r.refused || r.bad) ok = false
String said = words(r)
if (r.bad) said += ' Then it stopped with a problem: ' +
((List) (r.errors ?: [])).collect { Map f -> "${(f.message ?: '').toString().replaceAll('\\.+\$', '')} (${f.code})".toString() }.join('; ') + '.'
Map row = [scenario: what, result: said, ok: !(r.refused || r.bad), note: note]
if (r.refused) row.refused = ((List) r.refused).collect { ((Map) it).code }
out << row
}
trigs.take(3).eachWithIndex { Object t, int i ->
Map r = run(i, true, [] as Set, false)
if (r.acted == true) anyActed = true
add("When ${says((Map) t)}${leaves ? ', with its conditions true' : ''}".toString(), r, null)
check << [doIt: ((Map) t).kind in ['deviceAttribute', 'threshold'] ? "Make ${says((Map) t).replace(' reports ', ' report ')}.".toString() : "Wait until ${says((Map) t)}.".toString(),
expect: words(r)]
}
if (!trigs.isEmpty()) {
Map first = trigs[0]
if (gated) add("When ${says(first)}, with its conditions false".toString(), run(0, false, [] as Set, false), null)
if (delay > 0 || ((List) (doc?.actions ?: [])).any { ((Map) it).type == 'wait' })
add("When ${says(first)} again during its delay or wait".toString(), run(0, true, [] as Set, true),
doc?.rerunPolicy == 'parallel' ? 'Same as Rule Machine: each trigger runs the steps once.'
: 'Rule Machine runs the steps once per trigger, so the steps after the delay would happen twice there; this copy starts again and does them once. This is where the copy behaves differently from Rule Machine, on purpose.')
if (ifs) {
Set blind = leaves.findAll { Map c -> ifs[0].toString().contains(c.toString()) } as Set
add("When ${says(first)} and an IF cannot read its device".toString(), run(0, true, blind, false),
((Map) ifs[0]).ifUnknown == 'otherwise' || doc?.ifUnknown == 'otherwise' ? 'Same as Rule Machine, which runs the ELSE.'
: 'Rule Machine runs the ELSE here; this copy stops instead. This is where the copy behaves differently from Rule Machine, on purpose.')
}
}
Closure said = { Map x -> "${name(x.device as String)} ${x.attribute} ${x.value}".toString() }
List hubSaid = starts.findAll { ((Map) it).fromHub }.collect { said((Map) it) }
List assumedSaid = starts.findAll { !((Map) it).fromHub }.collect { said((Map) it) }
String startText = starts.isEmpty() ? '' : ' Starting values: ' +
([hubSaid ? "from the hub, ${hubSaid.join(', ')}".toString() : null,
assumedSaid ? "assumed because the hub has no reading, ${assumedSaid.join(', ')}".toString() : null].findAll { it }.join('; ')) + '.'
boolean idle = ok && !out.isEmpty() && !anyActed
String msg = out.isEmpty() ? 'This copy has no trigger to try.'
: idle ? "No scenario ran the actions: the copy's conditions were never true in the dry run. No device was touched."
: ok ? 'Dry run passed. No device was touched.' : 'The dry run found a problem. No device was touched.'
Map result = [ok: ok && !out.isEmpty() && !idle, scenarios: out, checklist: check,
seeded: starts.collect { Map x -> [device: x.device, attribute: x.attribute, value: x.value, from: x.fromHub ? 'hub' : 'assumed'] },
message: msg + startText]
if (idle) result.reason = 'nothingRan'
return result
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
@Field static final List CHECK_STATUSES = ['satisfied', 'unsupported', 'unresolved']
@Field static final long PROPOSE_MAX_SIM_MS = 26L * 3600000L
@Field static final int PROPOSE_MAX_REQUIREMENTS = 64
@Field static final int PROPOSE_MAX_TEXT_CHARS = 1024
@Field static final int PROPOSE_MAX_WHERE = 16
@Field static final int PROPOSE_MAX_PATH_CHARS = 128
@Field static final int PROPOSE_MAX_PATH_SEGMENTS = 16
@Field static final int PROPOSE_MAX_REQUEST_CHARS = 4096
@Field static final long PROPOSE_UNBOUNDED_WAIT_MS = 3600000L
@Field static final List PROPOSE_RULE_SETTINGS = ['rerunPolicy', 'queueLimit', 'parallelLimit', 'onUnknown', 'ifUnknown',
'errorPolicy', 'initialEvaluation', 'conditionTiming', 'enabled']
Object proposeAt(Object node, String path) {
if (path == null || path.isEmpty() || path.length() > PROPOSE_MAX_PATH_CHARS) return null
String[] parts = path.split('\\.', -1)
if (parts.length > PROPOSE_MAX_PATH_SEGMENTS) return null
Object cur = node
for (String part : parts) {
if (cur instanceof Map) {
if (!((Map) cur).containsKey(part)) return null
cur = ((Map) cur).get(part)
} else if (cur instanceof List) {
if (!(part ==~ /(0|[1-9][0-9]{0,5})/)) return null
int i = Integer.parseInt(part)
if (i >= ((List) cur).size()) return null
cur = ((List) cur).get(i)
} else {
return null
}
}
return cur
}
List proposeClaimablePaths(Map doc) {
List out = []
((List) (doc.triggers instanceof List ? doc.triggers : [])).eachWithIndex { t, i -> out << "triggers.${i}".toString() }
if (doc.conditions != null) out << 'conditions'
PROPOSE_RULE_SETTINGS.each { String k ->
if (doc.containsKey(k) && doc.get(k) != SCHEMA_DEFAULTS.get(k)) out << k
}
pActionPaths(doc.actions instanceof List ? doc.actions : [], 'actions', out)
pActionPaths(doc.onFailure instanceof List ? doc.onFailure : [], 'onFailure', out)
return out
}
void pActionPaths(List actions, String path, List out) {
actions.eachWithIndex { Object ao, int i ->
String p = "${path}.${i}".toString()
if (!(ao instanceof Map)) { out << p; return }
Map a = ao
if (a.type == 'repeat') {
out << p
if (a.condition instanceof Map) out << "${p}.condition".toString()
if (a.steps instanceof List) pActionPaths((List) a.steps, "${p}.steps".toString(), out)
return
}
if (a.type == 'byMode') {
out << p
((List) (a.cases instanceof List ? a.cases : [])).eachWithIndex { Object c, int j ->
if (c instanceof Map && ((Map) c).then instanceof List) pActionPaths((List) ((Map) c).then, "${p}.cases.${j}.then".toString(), out)
}
if (a.otherwise instanceof List) pActionPaths((List) a.otherwise, "${p}.otherwise".toString(), out)
return
}
if (a.type != 'if') { out << p; return }
out << "${p}.condition".toString()
if (a.then instanceof List) pActionPaths((List) a.then, "${p}.then".toString(), out)
if (a.elseIf instanceof List) {
((List) a.elseIf).eachWithIndex { Object e, int j ->
if (!(e instanceof Map)) return
out << "${p}.elseIf.${j}.condition".toString()
if (((Map) e).then instanceof List) pActionPaths((List) ((Map) e).then, "${p}.elseIf.${j}.then".toString(), out)
}
}
if (a.otherwise instanceof List) pActionPaths((List) a.otherwise, "${p}.otherwise".toString(), out)
}
}
String pText(Object v, int max) {
if (v == null) return null
String s = v.toString()
return s.length() > max ? s.substring(0, max) + '...' : s
}
Map proposeCheckList(Map doc, Object requirementsIn) {
List items = []
List findings = []
Set cited = [] as Set
List claimable = proposeClaimablePaths(doc)
Set claimableSet = claimable as Set
Set citable = new HashSet(claimableSet)
PROPOSE_RULE_SETTINGS.each { if (doc.containsKey(it)) citable << it }
List requirements = []
if (requirementsIn instanceof List) {
requirements = requirementsIn
} else if (requirementsIn != null) {
findings << [code: 'REQUIREMENTS_SHAPE', severity: SEV_BLOCK, path: 'requirements',
message: 'the requirement list is not a list']
}
if (requirements.size() > PROPOSE_MAX_REQUIREMENTS) {
findings << [code: 'REQUIREMENTS_LIMIT', severity: SEV_BLOCK, path: 'requirements',
message: "more than ${PROPOSE_MAX_REQUIREMENTS} requirements; only the first ${PROPOSE_MAX_REQUIREMENTS} were read".toString()]
requirements = requirements.take(PROPOSE_MAX_REQUIREMENTS)
}
requirements.eachWithIndex { Object ro, int idx ->
String fallbackId = "r${idx + 1}".toString()
if (!(ro instanceof Map)) {
items << [id: fallbackId, text: '', status: 'unresolved', where: [], note: 'this requirement is not an object']
return
}
Map r = ro
String status = r.status instanceof String ? (String) r.status : 'unresolved'
String text = pText(r.text, PROPOSE_MAX_TEXT_CHARS) ?: ''
String note = pText(r.note, PROPOSE_MAX_TEXT_CHARS)
List where = []
String problem = null
if (r.where != null && !(r.where instanceof List)) {
problem = '`where` is not a list of paths'
} else {
List raw = (r.where ?: [])
if (raw.size() > PROPOSE_MAX_WHERE) problem = "cites more than ${PROPOSE_MAX_WHERE} paths".toString()
where = raw.take(PROPOSE_MAX_WHERE).collect { pText(it, PROPOSE_MAX_PATH_CHARS) ?: '' }
}
if (!CHECK_STATUSES.contains(status)) {
problem = "status '${pText(status, 64)}' is not one of ${CHECK_STATUSES.join(', ')}".toString()
}
if (text.trim().isEmpty()) problem = problem ?: 'the requirement has no text'
if (problem == null && status == 'satisfied') {
List missing = where.findAll { proposeAt(doc, it as String) == null }
List containers = where.findAll { !missing.contains(it) && !citable.contains(it) }
if (where.isEmpty()) {
problem = 'marked satisfied but does not say which part of the rule satisfies it'
} else if (missing) {
problem = "marked satisfied but cites ${missing.join(', ')}, which the rule does not contain".toString()
} else if (containers) {
problem = "marked satisfied but cites ${containers.join(', ')}, which is not a single rule element; cite the exact trigger, condition, setting or action".toString()
}
}
if (problem != null) {
status = 'unresolved'
note = problem
} else if (status == 'satisfied') {
cited.addAll(where)
}
Map item = [id: pText(r.id, 64) ?: fallbackId, text: text, status: status, where: where]
if (note != null) item.note = note
items << item
}
List unclaimed = claimable.findAll { !cited.contains(it) }
unclaimed.each { String p ->
findings << [code: 'NOT_REQUESTED', severity: SEV_WARN, path: p,
message: "the rule does this (${p}), but no requirement cites it".toString()]
}
items.findAll { it.status == 'unsupported' }.each { Map it ->
findings << [code: 'REQUIREMENT_UNSUPPORTED', severity: SEV_BLOCK, path: 'requirements',
message: "not expressible: ${it.text}${it.note ? ' (' + it.note + ')' : ''}".toString()]
}
items.findAll { it.status == 'unresolved' }.each { Map it ->
findings << [code: 'REQUIREMENT_UNRESOLVED', severity: SEV_BLOCK, path: 'requirements',
message: "not settled: ${it.text}${it.note ? ' (' + it.note + ')' : ''}".toString()]
}
boolean complete = !items.isEmpty() && items.every { it.status == 'satisfied' } && unclaimed.isEmpty() &&
!findings.any { it.code in ['REQUIREMENTS_SHAPE', 'REQUIREMENTS_LIMIT'] }
return [items: items, unclaimed: unclaimed, complete: complete, findings: findings,
basis: 'author-supplied; citations are checked exactly, their meaning is for the person to judge']
}
long proposeActionSpanMs(List actions) {
long total = 0L
((List) (actions ?: [])).each { Object ao ->
if (!(ao instanceof Map)) return
Map a = ao
if (a.type == 'wait' || a.type == 'later') {
Object secs = a.type == 'later' || a.waitKind == 'delay' ? pLongestDelay(a) : a.timeoutSeconds
total += secs == null || secs instanceof Map ? PROPOSE_UNBOUNDED_WAIT_MS : ((secs as long) * 1000L)
}
if (a.type == 'later') total += proposeActionSpanMs((List) a.actions)
if (a.type == 'byMode') {
List spans = [proposeActionSpanMs((List) a.otherwise)]
((List) (a.cases instanceof List ? a.cases : [])).each { Object c -> if (c instanceof Map) spans << proposeActionSpanMs((List) ((Map) c).then) }
total += (long) spans.max()
}
if (a.type == 'fade' && a.seconds instanceof Number) total += (a.seconds as long) * 1000L
if (a.type == 'if') {
List spans = [proposeActionSpanMs((List) a.then), proposeActionSpanMs((List) a.otherwise)]
((List) (a.elseIf instanceof List ? a.elseIf : [])).each { Object e ->
if (e instanceof Map) spans << proposeActionSpanMs((List) ((Map) e).then)
}
total += (long) spans.max()
}
if (a.type == 'repeat') {
long pass = Math.max(proposeActionSpanMs((List) a.steps), a.everySeconds instanceof Number ? (a.everySeconds as long) * 1000L : 0L)
total += a.repeatKind == 'every' && a.times instanceof Number ? pass * (a.times as long) : PROPOSE_UNBOUNDED_WAIT_MS
}
}
return total
}
List pComparisonPair(String op, Object v) {
if (v instanceof Map) return null
if (op == 'is') return [pOppositeValue(v), v]
if (op == 'isNot') return [v, pOppositeValue(v)]
if (op in ['between', 'notBetween']) {
BigDecimal lo = new BigDecimal(((List) v)[0].toString()), hi = new BigDecimal(((List) v)[1].toString())
return op == 'between' ? [lo - 1, lo] : [lo, hi + 1]
}
BigDecimal n = new BigDecimal(v.toString())
return op in ['above', 'atLeast'] ? [n - 1, n + 1] : [n + 1, n - 1]
}
Object pOppositeValue(Object v) {
Map opposite = [on: 'off', off: 'on', active: 'inactive', inactive: 'active', open: 'closed',
closed: 'open', locked: 'unlocked', unlocked: 'locked', present: 'not present',
'not present': 'present', wet: 'dry', dry: 'wet', detected: 'clear', clear: 'detected']
return opposite.containsKey(v) ? opposite.get(v) : "not ${v}".toString()
}
List pSeedFromInventory(Map sim, Map inv) {
List gaps = []
if (inv.currentMode) sim.mode = inv.currentMode as String
((Map) inv.devices).values().each { Object d ->
Map dev = d
((Map) (dev.currentStates ?: [:])).each { k, v ->
Map st = v
if (st?.value == null) return
if (st.ts == null) {
gaps << "${dev.name} ${k} has no reading time, so it was treated as unknown".toString()
return
}
simSeed(sim, dev.id as String, k as String, st.value, st.ts as long)
}
}
return gaps
}
List pRefDevices(Map doc, Map node) {
List keys = []
if (node.ref) keys << node.ref
if (node.refs instanceof List) keys.addAll((List) node.refs)
return keys.collect { ((Map) ((Map) (doc.refs ?: [:])).get(it))?.id as String }
}
String pUnforceable(Map node) {
if (node == null) return 'there is no predicate'
if (node.type == 'all') return ((List) (node.of ?: [])).collect { pUnforceable((Map) it) }.find { it != null }
if (node.type == 'deviceAttribute') {
if ((node.op ?: 'is') != 'is') return "the ${node.attribute} comparison ${node.op} cannot be forced automatically".toString()
if (node.freshSeconds != null) return 'it needs repeated fresh reports, which are not synthesised'
return null
}
return "a ${node.type} predicate cannot be forced automatically".toString()
}
List proposeScenarios(Map doc, Map inv, long now, String zone, Map sun = [:]) {
List out = []
((List) (doc.triggers ?: [])).eachWithIndex { Object to, int ti ->
Map t = to
String description = renderTrigger(doc, t)
Map sim = simNew(inv, now, zone)
((Map) (sun ?: [:])).each { k, v -> simSetSun(sim, k as String, ((Map) v).sunrise as String, ((Map) v).sunset as String) }
List seedGaps = pSeedFromInventory(sim, inv)
Map loaded = simLoad(sim, doc)
if (!vActivatable(loaded)) {
out << [trigger: ti, description: description, outcome: 'skipped', admitted: false,
why: 'the rule does not validate, so nothing was simulated']
return
}
String dev = t.ref ? (((Map) ((Map) doc.refs).get(t.ref))?.id as String) : null
List assumptions = []
long actions = proposeActionSpanMs((List) doc.actions)
if (proposeHasUnboundedWait((List) doc.actions)) {
assumptions << 'a wait with no timeout was followed for one hour only'
}
long span = actions + 60000L
switch (t.kind) {
case 'deviceAttribute':
Object want = t.containsKey('value') ? t.value : 'changed'
Object start = pOppositeValue(want)
if (t.op != null) {
List pair = pComparisonPair(t.op as String, t.value)
if (pair == null) {
out << [trigger: ti, description: description, outcome: 'skipped', admitted: false,
why: 'a comparison with another reading is not simulated automatically']
return
}
start = pair[0]; want = pair[1]
simSeed(sim, dev, t.attribute as String, start, now - 1000L)
assumptions << "${t.attribute} was assumed to go from ${start} to ${want}".toString()
} else if (!((Map) (sim.state.get(dev) ?: [:])).containsKey(t.attribute)) {
simSeed(sim, dev, t.attribute as String, start, now - 1000L)
assumptions << "${t.attribute} was assumed to start as ${start}".toString()
}
simInject(sim, [kind: 'attribute', device: dev, attribute: t.attribute, value: want, ts: now])
break
case 'threshold':
BigDecimal v = new BigDecimal(t.value.toString())
boolean up = !(t.op == 'below' || t.op == 'atMost')
simSeed(sim, dev, t.attribute as String, up ? v - 1 : v + 1, now - 1000L)
simInject(sim, [kind: 'attribute', device: dev, attribute: t.attribute, value: up ? v + 1 : v - 1, ts: now])
assumptions << 'the value was assumed to start on the other side of the threshold'
break
case 'button':
simInject(sim, [kind: 'button', device: dev, number: t.number, action: t.action ?: 'pushed', ts: now])
break
case 'mode':
String target = t.to ? ((List) t.to)[0] as String : 'changed'
sim.mode = 'before'
simInject(sim, [kind: 'mode', value: target, ts: now])
assumptions << "the mode was assumed to change to ${target}".toString()
break
case 'hsmStatus':
case 'hsmAlert':
case 'locationEvent':
List vals = (t.kind == 'hsmStatus' ? t.to : t.kind == 'hsmAlert' ? t.alerts : t.events)
String attr = t.kind == 'locationEvent' ? (vals ? vals[0] : 'systemStart') as String : t.kind as String
Object lv = t.kind == 'locationEvent' ? 'true' : (vals ? vals[0] : t.kind == 'hsmStatus' ? 'armedAway' : 'intrusion')
simInject(sim, [kind: 'attribute', device: LOCATION_DEVICE, attribute: attr, value: lv, ts: now])
assumptions << "the hub was assumed to report ${attr} ${lv}".toString()
break
case 'duration':
String why = pUnforceable((Map) t.predicate)
if (why != null) {
out << [trigger: ti, description: description, outcome: 'skipped', admitted: false,
why: "the held condition was not simulated: ${why}".toString()]
return
}
assumptions << 'the held condition was assumed to become true now and stay true'
pForcePredicate(sim, doc, t.predicate, now)
span += ((t.seconds as long) * 1000L)
break
case 'schedule':
case 'periodic':
Map next = ((List) sim.timers).findAll { it.kind == 'schedule' && (it.trigger as int) == ti }.min { it.at as long }
if (next == null && !((List) sim.gaps).isEmpty()) {
span = 25L * 3600000L + actions
break
}
if (next == null || (next.at as long) - now > PROPOSE_MAX_SIM_MS - actions) {
out << [trigger: ti, description: description, outcome: 'skipped', admitted: false,
why: next == null ? 'this schedule has no time coming up' : "its next time is ${coreLocal(sim, next.at as long).toLocalDateTime()}, too far ahead to simulate".toString()]
return
}
span = (next.at as long) - now + actions
assumptions << 'the clock was run forward to its next time'
break
default:
out << [trigger: ti, description: description, outcome: 'skipped', admitted: false,
why: "a ${t.kind} trigger is not simulated automatically".toString()]
return
}
simAdvance(sim, Math.min(span, PROPOSE_MAX_SIM_MS))
Map report = simReport(sim)
List gateValues = sim.trace.findAll { it.what == 'gate' }.collect { it.value }
boolean admitted = !sim.runs.isEmpty()
String gateNote = null
if (gateValues.contains('unknown')) {
gateNote = 'the conditions could not be checked from what the hub reports, so the rule would do nothing'
} else if (gateValues.contains('false')) {
gateNote = 'the conditions are not met right now, so the rule would do nothing at this moment'
}
List gaps = seedGaps + report.coverage.gaps
String outcome = gaps.isEmpty() ? 'simulated' : 'incomplete'
Map s = [
trigger : ti,
description: description,
outcome : outcome,
admitted : admitted,
commands : sim.effects.collect { Map c ->
Map row = [at: (c.at as long) - now, device: c.device, command: c.command]
if (c.args) row.args = c.args
row
},
blocked : sim.blocked.collect { Map c ->
Map row = [at: (c.at as long) - now, device: c.device, command: c.command,
status: c.status, code: c.code, approval: c.approval]
if (c.args) row.args = c.args
row
},
gate : gateValues,
errors : sim.runs.values().collectMany { it.errors }.collect { it.code },
assumptions: assumptions + report.assumptions,
gaps : gaps,
partial : report.coverage.partial,
]
if (gateNote) s.gateNote = gateNote
if (!admitted && !gateNote) s.why = 'the trigger did not start a run under these inputs'
out << s
}
return out
}
Object pLongestDelay(Map a) {
if (a.seconds instanceof Map) return a.seconds
long s = ([(a.seconds ?: 0) as long] + ((List) (a.modeSeconds instanceof List ? a.modeSeconds : [])).collect { ((Map) it).seconds as long }).max() as long
return s + ((a.randomSeconds ?: 0) as long)
}
boolean proposeHasUnboundedWait(List actions) {
return ((List) (actions ?: [])).any { Object ao ->
if (!(ao instanceof Map)) return false
Map a = ao
if (a.type == 'wait' && a.waitKind != 'delay' && a.timeoutSeconds == null) return true
if (a.type == 'later') return proposeHasUnboundedWait((List) a.actions)
if (a.type == 'if') {
return proposeHasUnboundedWait((List) a.then) || proposeHasUnboundedWait((List) a.otherwise) ||
((List) (a.elseIf ?: [])).any { proposeHasUnboundedWait((List) ((Map) it).then) }
}
if (a.type == 'repeat') return !(a.repeatKind == 'every' && a.times instanceof Number) || proposeHasUnboundedWait((List) a.steps)
if (a.type == 'byMode') {
return proposeHasUnboundedWait((List) a.otherwise) ||
((List) (a.cases ?: [])).any { it instanceof Map && proposeHasUnboundedWait((List) ((Map) it).then) }
}
return false
}
}
void pForcePredicate(Map sim, Map doc, Map node, long now) {
if (node == null) return
if (node.of instanceof List) { ((List) node.of).each { pForcePredicate(sim, doc, it, now) }; return }
if (node.type == 'deviceAttribute' && (node.op ?: 'is') == 'is') {
pRefDevices(doc, node).each { String dev ->
simSeed(sim, dev, node.attribute as String, pOppositeValue(node.value), now - 2000L)
simInject(sim, [kind: 'attribute', device: dev, attribute: node.attribute, value: node.value, ts: now])
}
}
}
@Field static final String EVIDENCE_FORMAT = 'hai.evidence/1'
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
Map proposeEvaluate(Map candidate, Object requirements, Map inv, Map active, Map opts) {
long now = (opts.now ?: 0L) as long
String zone = (opts.zone ?: 'UTC') as String
String request = pText(opts.request, PROPOSE_MAX_REQUEST_CHARS)
Map timing = opts?.editorTiming instanceof Map ? opts.editorTiming : null
long phaseStarted = timing == null ? 0L : this.now()
boolean fromEditor = opts.source == 'editor'
Map v = opts.validation instanceof Map ? opts.validation : validateRule(candidate, inv)
if (timing != null) { timing.proposeValidateMs = this.now() - phaseStarted; phaseStarted = this.now() }
Map checks = proposeCheckList(candidate, requirements)
if (timing != null) { timing.proposeChecklistMs = this.now() - phaseStarted; phaseStarted = this.now() }
List rendering = []
Map diff = null
List scenarios = []
String ruleHash = null
String problem = null
try {
rendering = opts.rendering instanceof List ? opts.rendering : renderRule(candidate)
if (!fromEditor) {
diff = diffRules(active, candidate)
diff.kind = active == null ? 'newRulePreview' : 'againstBaseline'
if (active == null) diff.note = 'no active version was supplied, so this shows the whole rule as new'
}
ruleHash = opts.ruleHash instanceof String ? (String) opts.ruleHash : jsonHash(candidate, SCHEMA_DEFAULTS)
} catch (Exception e) {
problem = "the rule could not be rendered or hashed: ${e.message}".toString()
}
if (timing != null) { timing.proposeRenderDiffMs = this.now() - phaseStarted; phaseStarted = this.now() }
if (vActivatable(v) && problem == null) {
try {
scenarios = proposeScenarios(candidate, inv, now, zone, (opts.sun ?: [:]))
} catch (Exception e) {
problem = "simulation failed: ${e.message}".toString()
}
}
if (timing != null) { timing.proposeScenariosMs = this.now() - phaseStarted; phaseStarted = this.now() }
List findings = v.findings + checks.findings
if (request == null) {
findings << [code: 'REQUEST_MISSING', severity: SEV_WARN, path: '',
message: 'the original request was not supplied, so the checklist cannot be read against it']
}
if (problem) findings << [code: 'PROPOSAL_INTERNAL', severity: SEV_BLOCK, path: '', message: problem]
boolean blocked = findings.any { it.severity == SEV_BLOCK }
List simGaps = scenarios.collectMany { (it.gaps ?: []) }.unique()
List activationBlockers = proposeActivationBlockers(findings)
long digestStarted = timing == null ? 0L : this.now()
Map digestTiming = null
digestTiming = timing
String inventoryDigest = fromEditor ? null : proposeInventoryDigest(inv, digestTiming)
if (timing != null) timing.proposeInventoryDigestMs = this.now() - digestStarted
Map result = [
kind       : 'hai.proposal/1',
request : request,
ruleHash : ruleHash,
ready : !blocked && checks.complete,
effects : 'no automation is activated and no device command is sent',
validation : [activatable: vActivatable(v), complete: v.complete, workUsed: v.workUsed],
findings : findings,
rendering : rendering,
diff : diff,
checklist : checks.items,
checklistBasis: checks.basis,
activationBlockers: activationBlockers,
requiredApprovals: proposeRequiredApprovals(findings),
unclaimed : checks.unclaimed,
simulation : scenarios,
coverage : [
checklistComplete: checks.complete,
scenariosSimulated: scenarios.count { it.outcome == 'simulated' },
scenariosIncomplete: scenarios.count { it.outcome == 'incomplete' },
scenariosSkipped : scenarios.count { it.outcome == 'skipped' },
gaps : simGaps,
note : 'simulation shows what these inputs produce under these assumptions; it is not proof the rule is right',
],
inputs : [now: now, zone: zone, inventory: inventoryDigest,
baseline: active == null ? 'none' : jsonHash(active, SCHEMA_DEFAULTS),
baselineRevision: opts.baselineRevision, sun: opts.sun, source: opts.source],
engine : [schema: SCHEMA_ID, compiler: COMPILER_VERSION, catalogue: CATALOGUE_VERSION],
]
long packetStarted = timing == null ? 0L : this.now()
if (!fromEditor) {
result.evidenceFormat = EVIDENCE_FORMAT
result.evidenceId = 'ev_' + proposeEvidenceHash(result).take(32)
result.evidenceIdMeaning = 'identifies the complete evidence packet under hai.evidence/1; it is not an approval token'
}
if (timing != null) {
timing.proposePacketHashMs = this.now() - packetStarted
timing.proposeEvidenceMs = this.now() - phaseStarted
}
return result
}
@Field static final String AUTHOR_MODEL = 'claude-opus-5'
@Field static final String AUTHOR_API_URL = 'https://api.anthropic.com/v1/messages'
@Field static final String AUTHOR_API_VERSION = '2023-06-01'
@Field static final String AUTHOR_BETA = 'server-side-fallback-2026-07-01'
@Field static final int AUTHOR_MAX_TOKENS = 16000
@Field static final Map AUTHOR_MAX_TOKENS_BY_EFFORT = [low: 4000, medium: 8000, high: 16000]
@Field static final int AUTHOR_TIMEOUT_SECONDS = 240
@Field static final int AUTHOR_MAX_REPAIRS = 2
@Field static final int AUTHOR_MAX_TRANSPORT_RETRIES = 2
@Field static final int AUTHOR_MAX_WEB_SEARCHES = 2
@Field static final double AUTHOR_WEB_SEARCH_USD = 0.01d
@Field static final int AUTHOR_MAX_OTHER_COMMANDS = 24
@Field static final int AUTHOR_MAX_REPAIR_FINDINGS = 8
@Field static final int AUTHOR_MAX_REQUEST_CHARS = 2000
@Field static final int AUTHOR_MAX_RESPONSE_CHARS = 262144
@Field static final int AUTHOR_MAX_HISTORY_BYTES = 600000
@Field static final int AUTHOR_MAX_STORED_OUTCOME_BYTES = 200000
@Field static final int AUTHOR_MAX_STORED_SESSION_BYTES = 700000
@Field static final int AUTHOR_MIN_SEND_SECONDS = 15
@Field static final int AUTHOR_MIN_STORED_OUTCOME_BYTES = 512
@Field static final long AUTHOR_MAX_TOTAL_TOKENS = 200000L
@Field static final long AUTHOR_MAX_ELAPSED_MS = 15L * 60000L
@Field static final int AUTHOR_MAX_RETRY_AFTER_SECONDS = 120
@Field static final String AUTHOR_TOOL_NAME = 'submit_rule'
@Field static final String AUTHOR_GUIDE_FILE = 'hai-author-guide.txt'
@Field static final String AUTHOR_GUIDE_SHA256 = '980db1ff83d8c96128a9229d343fc0574e11b3612e01f0c48d48f8ef8b7e8cf1'
String authorSystem() {
String kept = (String) AUTHOR_GUIDE_CACHE.get(AUTHOR_GUIDE_SHA256)
if (kept != null) return kept
byte[] b = null
try { b = downloadHubFile(AUTHOR_GUIDE_FILE) } catch (Exception e) { b = null }
if (b == null) throw new Exception("the AI guide (${AUTHOR_GUIDE_FILE}) is not in the hub's File Manager; deploy HAI Engine again".toString())
String h = MessageDigest.getInstance('SHA-256').digest(b).collect { String.format('%02x', it) }.join('')
if (h != AUTHOR_GUIDE_SHA256) throw new Exception("the AI guide in the File Manager is not the one this version of HAI Engine was built with; deploy HAI Engine again")
String text = new String(b, 'UTF-8')
AUTHOR_GUIDE_CACHE.put(AUTHOR_GUIDE_SHA256, text)
return text
}
@Field static final java.util.concurrent.ConcurrentHashMap AUTHOR_GUIDE_CACHE = new java.util.concurrent.ConcurrentHashMap()
List aWords(String s) {
return (s ?: '').toLowerCase().split(/[^a-z0-9]+/).findAll { it.size() > 2 } as List
}
boolean authorNeedsResearch(String request) {
return (request ?: '') =~ /(?i)\b(api|https?|url|web service|rss|news|fetch|forecast|weather|air quality|currency|exchange rate|stock price|share price|timetable|transit|traffic)\b/
}
Map authorContext(Map inv, String request) {
List words = aWords(request)
List scored = ((Map) inv.devices).values().collect { Object d ->
Map dev = d
List hay = aWords("${dev.name} ${dev.room ?: ''} ${((Set) dev.capabilities).join(' ')}".toString())
int score = words.count { hay.contains(it) }
[dev: dev, score: score]
}
List chosen = scored.findAll { it.dev.disabled != true }
.sort { a, b -> (b.score <=> a.score) ?: (a.dev.name <=> b.dev.name) }
.collect { Map it ->
Map dev = it.dev
Set caps = (Set) dev.capabilities
Set offered = dev.commands instanceof Collection ? (((Collection) dev.commands).collect { it.toString() } as Set) : null
List cmds = CATALOGUE.findAll { k, op -> caps.containsAll((List) op.requires) && schemaDeviceOffers(k as String, offered) }.keySet() as List
Map row = [id: dev.id, name: dev.name, capabilities: caps.sort(), engineCommands: cmds.sort()]
if (offered == null) {
row.deviceCommands = 'the hub did not list this device\'s own commands'
} else {
List other = (offered - cmds).sort()
row.otherDeviceCommands = other.take(AUTHOR_MAX_OTHER_COMMANDS).collect { aCut(it, 64) }
if (other.size() > AUTHOR_MAX_OTHER_COMMANDS) row.otherDeviceCommandsNotShown = other.size() - AUTHOR_MAX_OTHER_COMMANDS
}
if (dev.room) row.room = dev.room
if (dev.variable) row.variable = dev.variable
Map now = [:]
((Map) (dev.currentStates ?: [:])).each { k, v -> if (((Map) v)?.value != null) now.put(k, ((Map) v).value) }
if (now) row.now = now
row
}
return [
devices : chosen,
devicesShown: chosen.size(),
devicesTotal: ((Map) inv.devices).size(),
modes : ((Set) inv.modes).sort(),
variables : ((Map) inv.variables).values().collect { Map v ->
Map row = [id: v.id, name: v.name, type: v.type, scope: v.scope ?: 'hub']
if (v.integer) row.wholeNumbersOnly = true
if (v.scope == 'hub') row.writable = v.writable != false
row
},
rules : ((Collection) (inv.rules ?: [])).collect { String id -> [id: id, name: ((Map) (inv.ruleNames ?: [:])).get(id) ?: id] }.sort { it.name },
rmRules : ((Collection) (inv.rmRules ?: [])).collect { Object id -> [id: id.toString(), name: ((Map) (inv.rmRuleNames ?: [:])).get(id) ?: id.toString()] }.sort { it.name },
]
}
Map aTool() {
return [
name : AUTHOR_TOOL_NAME,
description : 'Submit the drafted hai.rule/1 rule and the full list of requirements taken from the request. The engine validates, renders and simulates it and returns findings.',
input_schema: [
type : 'object',
properties : [
rule         : [type: 'object', description: 'The hai.rule/1 document.'],
requirements : [type: 'array', items: [
type : 'object',
properties: [
id : [type: 'string'],
text : [type: 'string', description: 'The requirement in the person\'s words.'],
status: [type: 'string', enum: ['satisfied', 'unsupported', 'unresolved']],
where : [type: 'array', items: [type: 'string']],
note : [type: 'string'],
],
required : ['id', 'text', 'status'],
]],
cannotExpress: [type: 'string', description: 'Why no rule can be written, when that is the case.'],
],
required : ['requirements'],
],
]
}
Map authorStart(String request, Map inv, Map opts = [:]) {
Map session = [
request : request,
opts : opts,
messages : [],
attempts : 0,
repairs : 0,
repairReasons: [],
transportRetries: 0,
webSearchRemaining: authorNeedsResearch(request) ? AUTHOR_MAX_WEB_SEARCHES : 0,
searchPauses: 0,
status : 'awaitingModel',
outcome : null,
log : [],
usage : [input: 0, output: 0, cacheCreation: 0, cacheRead: 0, webSearches: 0],
firstAttemptParsed: null,
firstAttemptSchemaValid: null,
models : [],
startedAt : (opts.now ?: 0L) as long,
contextIds : [],
generation : (opts.generation ?: 1) as String,
pendingRequestId: null,
scopeKey : authorScopeKey(inv),
budget : opts.budgetUsd == null ? null : authorBudgetNew(opts.budgetUsd as double),
inFlightId : null,
]
if (request == null || request.trim().isEmpty()) {
return aFinish(session, 'failed', 'there is no request to work from')
}
if (request.size() > AUTHOR_MAX_REQUEST_CHARS) {
return aFinish(session, 'failed', "the request is longer than ${AUTHOR_MAX_REQUEST_CHARS} characters".toString())
}
Map ctx = authorContext(inv, request)
session.contextIds = ctx.devices.collect { it.id }
String inventory = """Devices you may use - all ${ctx.devicesTotal} selected for this engine, one per line: id | name | room | capabilities. These are the devices available to this engine, not the hub's full inventory; the hub may have other devices you cannot see or use. A device listed here is a device a person selected, and that is the whole permission. A device marked "variable" stands in for that hub variable.
${authorDeviceLines(inv)}

${authorCommandTable()}

Modes: ${JsonOutput.toJson(ctx.modes)}
Variables: ${JsonOutput.toJson(ctx.variables.sort { a, b -> (a.name <=> b.name) ?: (a.id <=> b.id) })}

${authorRuleLines(inv)}"""
String amendment = opts.activeRule instanceof Map ? """Amend the existing rule below. Return the complete hai.rule/1 document, keep its id exactly, and retain everything the household did not ask to change. The editor will show the differences before a person saves it. Do not activate the rule.
Existing rule: ${JsonOutput.toJson(opts.activeRule)}
""" : ''
String user = """${amendment}
The household asked:
${request}"""
session.messages << [role: 'user', content: [[type: 'text', text: inventory],
[type: 'text', text: user]]]
session.nextRequest = authorRequestBody(session)
return session
}
@Field static final List AUTHOR_QUIET_CAPABILITIES = ['Actuator', 'Sensor']
String aLineText(Object s) { return ((s ?: '') as String).replaceAll(/[|\r\n]+/, '/').trim() }
String authorDeviceLines(Map inv) {
List rows = ((Map) (inv?.devices ?: [:])).values().findAll { Object o -> ((Map) o).disabled != true }
.collect { it }.sort { Map a, Map b -> (aLineText(a.name) <=> aLineText(b.name)) ?: ((a.id as String) <=> (b.id as String)) }
return rows.collect { Map d ->
List caps = ((Collection) (d.capabilities ?: [])).collect { it.toString() }.findAll { !AUTHOR_QUIET_CAPABILITIES.contains(it) }.sort()
String line = "${d.id} | ${aLineText(d.name)} | ${aLineText(d.room) ?: '-'} | ${caps.join(' ')}".toString()
if (d.variable) line += " | variable ${d.variable}"
line
}.join('\n')
}
void aColourArgs(Object node) {
if (node instanceof Map) {
Map m = node
List args = m.args instanceof List ? m.args : null
if (m.command == 'setColor' && args?.size() == 1 && args[0] instanceof Map && ((Map) args[0]).containsKey('hue')) {
Map c = args[0]
m.args = c.level != null ? [c.hue, c.saturation, c.level] : [c.hue, c.saturation]
}
m.values().each { aColourArgs(it) }
} else if (node instanceof List) {
((List) node).each { aColourArgs(it) }
}
}
String authorCommandTable() {
Map byNeed = new TreeMap()
CATALOGUE.each { Object k, Object v ->
Map op = v
String need = ((List) (op.requires ?: [])).collect { it.toString() }.sort().join(' + ') ?: 'any device'
List args = (op.args ?: [])
int required = args.count { ((Map) it).required != false } as int
String arity = args.isEmpty() ? '' : (required == args.size() ? "(${args.size()})" : "(${required}-${args.size()})").toString()
if (!byNeed.containsKey(need)) byNeed.put(need, [])
((List) byNeed.get(need)) << ("${k}${arity}".toString())
}
return 'Commands this engine can send, by the capabilities a device needs for them; the number is how many values the command takes:\n' +
byNeed.collect { k, v -> "${k}: ${((List) v).sort().join(', ')}".toString() }.join('\n') +
'\nsetColor takes hue, saturation and, if wanted, level as separate numbers from 0 to 100: green at half brightness is [33, 100, 50], never one {hue, saturation, level} value.' +
'\nA device\'s own driver may offer commands outside this table, which are not listed here. Use a customCommand step only when the household names such a command; HAI refuses it if the driver does not offer it.'
}
String authorRuleLines(Map inv) {
List index = inv?.ruleIndex instanceof List ? inv.ruleIndex : null
List lines = []
if (index != null) {
index.each { Object o ->
Map r = o
String line = "${r.kind == 'rmRule' ? 'rmRule' : 'rule'} ${r.id} | ${aLineText(r.name)}".toString()
List on = (r.on ?: []), uses = (r.uses ?: [])
if (on) line += " | starts on: ${on.join(' ')}"
if (uses) line += " | uses: ${uses.join(' ')}"
lines << line
}
} else {
((Collection) (inv?.rules ?: [])).each { Object id -> lines << "rule ${id} | ${aLineText(((Map) (inv.ruleNames ?: [:])).get(id) ?: id)}".toString() }
((Collection) (inv?.rmRules ?: [])).each { Object id -> lines << "rmRule ${id} | ${aLineText(((Map) (inv.rmRuleNames ?: [:])).get(id) ?: id)}".toString() }
}
if (lines.isEmpty()) return 'Rules already on the hub: none listed.'
return "Rules already on the hub, one per line: kind id | name | the devices that start it | the devices it uses. " +
"A rule of kind \"rule\" is an HAI rule and one of kind \"rmRule\" is a Rule Machine rule; use these ids for runRule, " +
"setRuleBoolean and controlRule. When the request touches a device another rule already starts on or uses, say so in a " +
"requirement's note rather than silently duplicating or contradicting that rule.\n" + lines.join('\n')
}
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
Map authorRequestBody(Map session) {
List tools = [aTool()]
if (((session.webSearchRemaining ?: 0) as int) > 0 || session.searchPaused == true) {
tools << [type: 'web_search_20250305', name: 'web_search',
max_uses: Math.max(1, (session.webSearchRemaining ?: 0) as int)]
}
Map body = [
model : AUTHOR_MODEL,
max_tokens : (AUTHOR_MAX_TOKENS_BY_EFFORT.get(session.opts?.effort ?: 'high') ?: AUTHOR_MAX_TOKENS) as int,
thinking : [type: 'adaptive'],
output_config: [effort: (session.opts?.effort ?: 'high')],
system : [[type: 'text', text: authorSystem(), cache_control: [type: 'ephemeral']]],
tools : tools,
tool_choice : [type: 'auto'],
messages : session.messages,
]
if (session.budget == null) body.fallbacks = 'default'
return body
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
Map authorOnResponse(Map session, Map inv, int httpStatus, String body, Map headers = [:], long at = 0L) {
if (session.status != 'awaitingModel') return session
session.attempts = (session.attempts as int) + 1
session.retryAfterSeconds = null
aSettle(session, httpStatus, body)
if (at > 0L && (session.startedAt as long) > 0L && at - (session.startedAt as long) > AUTHOR_MAX_ELAPSED_MS) {
return aFinish(session, 'failed', "drafting took longer than ${AUTHOR_MAX_ELAPSED_MS / 60000L} minutes".toString())
}
if (body != null && body.length() > AUTHOR_MAX_RESPONSE_CHARS) {
return aFinish(session, 'failed', "the provider reply was larger than ${AUTHOR_MAX_RESPONSE_CHARS} characters".toString())
}
if (httpStatus != 200) {
String detail = aErrorText(body)
boolean retryable = httpStatus == 408 || httpStatus == 429 || httpStatus >= 500
if (retryable && (session.transportRetries as int) < Math.min(AUTHOR_MAX_TRANSPORT_RETRIES, authorFollowUpLimit(session))) {
session.transportRetries = (session.transportRetries as int) + 1
session.attempts = (session.attempts as int) - 1
Integer hinted = aRetryAfter(headers)
session.retryAfterSeconds = hinted != null ? hinted : (httpStatus == 429 ? 20 : 5)
session.log << [what: 'transportRetry', httpStatus: httpStatus, detail: detail,
retryAfterSeconds: session.retryAfterSeconds, hinted: hinted != null]
return session
}
String why = httpStatus == 401 ? 'the API key was not accepted'
: httpStatus == 403 ? 'the API key is not allowed to use this model'
: "the provider returned HTTP ${httpStatus}: ${detail}".toString()
return aFinish(session, 'failed', why)
}
if (authorScopeKey(inv) != session.scopeKey) {
return aFinish(session, 'failed', 'the devices or modes available to the engine changed while drafting; draft again')
}
Map resp
try { resp = new JsonSlurper().parseText(body) }
catch (Exception e) { return aFinish(session, 'failed', 'the provider reply was not JSON') }
if (resp.model != null) ((List) session.models) << (resp.model as String)
Map usage = (resp.usage instanceof Map ? resp.usage : [:])
long created = (usage.cache_creation_input_tokens ?: 0) as long
long read = (usage.cache_read_input_tokens ?: 0) as long
session.usage.input = (session.usage.input as long) + ((usage.input_tokens ?: 0) as long) + created + read
session.usage.output = (session.usage.output as long) + ((usage.output_tokens ?: 0) as long)
session.usage.cacheCreation = (session.usage.cacheCreation as long) + created
session.usage.cacheRead = (session.usage.cacheRead as long) + read
int searches = ((usage.server_tool_use instanceof Map ? usage.server_tool_use.web_search_requests : 0) ?: 0) as int
session.webSearchRemaining = Math.max(0, ((session.webSearchRemaining ?: 0) as int) - searches)
session.usage.webSearches = ((session.usage.webSearches ?: 0) as int) + searches
session.searchPaused = false
String stop = resp.stop_reason as String
if (stop == 'refusal') {
return aFinish(session, 'failed', 'the model declined this request')
}
List content = (resp.content instanceof List ? resp.content : [])
if (stop == 'max_tokens') {
return aFinish(session, 'failed', 'the model ran out of room before finishing the rule')
}
if (stop == 'pause_turn') {
if (authorFollowUpLimit(session) == 0) return aFinish(session, 'failed', 'the documentation lookup did not finish; no second AI request was sent')
session.searchPauses = ((session.searchPauses ?: 0) as int) + 1
if ((session.searchPauses as int) > 2) return aFinish(session, 'failed', 'the documentation lookup did not finish')
session.searchPaused = true
session.messages << [role: 'assistant', content: content]
return aNext(session)
}
List calls = content.findAll { it instanceof Map && it.type == 'tool_use' }
session.messages << [role: 'assistant', content: content]
if (calls.isEmpty()) {
aFirstAttempt(session, false, false)
if ((session.repairs as int) < Math.min(AUTHOR_MAX_REPAIRS, authorRepairLimit(session))) {
session.repairs = (session.repairs as int) + 1
aRecordRepair(session, [[code: 'NO_TOOL_CALL', message: "the reply did not call ${AUTHOR_TOOL_NAME}".toString()]])
session.messages << [role: 'user', content: "Answer by calling the ${AUTHOR_TOOL_NAME} tool exactly once.".toString()]
session.log << [what: 'noToolCall']
return aNext(session)
}
return aFinish(session, 'failed', 'the model never submitted a rule')
}
if (calls.size() != 1 || calls[0].name != AUTHOR_TOOL_NAME) {
aFirstAttempt(session, false, false)
String wrongCall = "Call ${AUTHOR_TOOL_NAME} exactly once and no other tool; this reply made ${calls.size()} tool call(s).".toString()
return aRepairOrFinish(session, calls, wrongCall, null, [[code: 'TOOL_CALL', message: wrongCall]])
}
Map input = (calls[0].input instanceof Map ? calls[0].input : [:])
String shape = aInputShapeProblem(input)
if (shape != null) {
aFirstAttempt(session, true, false)
return aRepairOrFinish(session, calls, "The ${AUTHOR_TOOL_NAME} input is malformed: ${shape}".toString(), null,
[[code: 'TOOL_INPUT_SHAPE', message: shape]])
}
List requirements = input.requirements
if (input.rule == null) {
aFirstAttempt(session, true, true)
Map checks = proposeCheckList([triggers: [], actions: []], requirements)
String why = (input.cannotExpress ?: 'the model could not express this as a rule') as String
return aFinish(session, 'cannotExpress', why.take(1024), [checklist: checks.items, request: session.request])
}
Map strict = jsonParse(JsonOutput.toJson(input.rule))
if (!strict.ok) {
aFirstAttempt(session, false, false)
return aRepairOrFinish(session, calls, "The rule is not valid hai.rule/1 JSON: ${strict.code}, ${strict.message}".toString(), null,
[[code: strict.code, message: strict.message]])
}
Map rule = strict.value
aColourArgs(rule)
Map opts = session.opts
if (opts.activeRule instanceof Map && rule.id != ((Map) opts.activeRule).id) {
return aFinish(session, 'failed', 'the AI changed the rule identity; the amendment was not kept')
}
if (opts.activeRule instanceof Map && jsonCanonicalise(rule, SCHEMA_DEFAULTS).getBytes('UTF-8').length > HAI_DOC_BYTES) {
return aFinish(session, 'failed', "the amended rule exceeds the ${HAI_DOC_BYTES}-byte runnable limit".toString())
}
Map proposal = proposeEvaluate(rule, requirements, inv, opts.activeRule,
[now: opts.now ?: 0L, zone: opts.zone ?: 'UTC', request: session.request,
sun: opts.sun, source: 'panel'])
aFirstAttempt(session, true, !proposal.findings.any { it.severity == SEV_BLOCK && (it.code as String)?.startsWith('SCHEMA_') })
aOutsideContext(session, rule, proposal)
List repairable = proposal.findings.findAll {
it.severity == SEV_BLOCK && !(it.code in ['REQUIREMENT_UNSUPPORTED', 'REQUIREMENT_UNRESOLVED'])
}
List selfClaim = proposal.checklist.findAll { it.status == 'unresolved' && it.note?.startsWith('marked satisfied') }
if (repairable || selfClaim) {
List rejected = repairable + selfClaim.collect { [code: 'CHECKLIST', path: 'requirements.' + it.id, message: it.note] }
String feedback = rejected.collect { "- ${it.code} at ${it.path}: ${it.message}" }.join('\n')
return aRepairOrFinish(session, calls, "The engine rejected this rule:\n${feedback}".toString(), proposal, rejected)
}
String status = proposal.ready ? 'proposed' : 'proposedWithIssues'
return aFinish(session, status, proposal.ready
? 'a rule was drafted and passed every check; it has not been activated and no device was commanded'
: 'a rule was drafted, but some requirements are unsupported, unsettled or not cited',
[proposal: proposal])
}
void aFirstAttempt(Map session, boolean parsed, boolean schemaValid) {
if (session.firstAttemptParsed != null) return
session.firstAttemptParsed = parsed
session.firstAttemptSchemaValid = schemaValid
}
int authorFollowUpLimit(Map session) {
int requested = (((Map) (session.opts ?: [:])).followUpLimit ?: 0) as int
return Math.max(0, Math.min(2, requested))
}
int authorRepairLimit(Map session) {
Object requested = ((Map) (session.opts ?: [:])).followUpLimit
return requested == null ? 1 : Math.max(0, Math.min(2, requested as int))
}
Map aNext(Map session) {
if ((session.attempts as int) >= 1 + Math.max(authorFollowUpLimit(session), authorRepairLimit(session))) {
return aFinish(session, 'failed', 'the draft did not finish within its allowed AI requests')
}
long tokens = (session.usage.input as long) + (session.usage.output as long)
if (tokens > AUTHOR_MAX_TOTAL_TOKENS) {
return aFinish(session, 'failed', "drafting used more than ${AUTHOR_MAX_TOTAL_TOKENS} tokens".toString())
}
if (authorBytes(session.messages) > AUTHOR_MAX_HISTORY_BYTES) {
return aFinish(session, 'failed', 'the conversation grew past its stored size limit')
}
session.nextRequest = authorRequestBody(session)
return session
}
Integer aRetryAfter(Map headers) {
Object raw = null
((Map) (headers ?: [:])).each { k, v -> if (k.toString().equalsIgnoreCase('retry-after')) raw = v }
if (raw instanceof List) raw = ((List) raw) ? ((List) raw)[0] : null
if (raw == null || !(raw.toString().trim() ==~ /\d{1,6}/)) return null
return Math.min(Integer.parseInt(raw.toString().trim()), AUTHOR_MAX_RETRY_AFTER_SECONDS)
}
String aInputShapeProblem(Map input) {
if (input.rule != null && !(input.rule instanceof Map)) return '`rule` must be an object'
if (!(input.requirements instanceof List)) return '`requirements` must be a list'
if (((List) input.requirements).size() > PROPOSE_MAX_REQUIREMENTS) return "more than ${PROPOSE_MAX_REQUIREMENTS} requirements".toString()
if (input.cannotExpress != null && !(input.cannotExpress instanceof String)) return '`cannotExpress` must be text'
return null
}
void aOutsideContext(Map session, Map rule, Map proposal) {
Set shown = ((List) (session.contextIds ?: [])) as Set
((Map) (rule.refs instanceof Map ? rule.refs : [:])).each { k, r ->
if (!(r instanceof Map) || ((Map) r).kind != 'device') return
String id = ((Map) r).id as String
if (id != null && !shown.contains(id)) {
proposal.findings << [code: 'REF_OUTSIDE_CONTEXT', severity: SEV_WARN, path: "refs.${k}".toString(),
message: "the rule uses ${id}, which the model was not shown".toString()]
}
}
}
void aRecordRepair(Map session, List findings) {
List all = (findings ?: [])
Map rec = [attempt: session.attempts as int, repair: session.repairs as int, findingsTotal: all.size(),
findings: all.take(AUTHOR_MAX_REPAIR_FINDINGS).collect { Object f ->
Map row = [code: aCut(((Map) f).code, 48), message: aCut(((Map) f).message, 160)]
if (((Map) f).path != null) row.path = aCut(((Map) f).path, 80)
row
}]
if (session.repairReasons == null) session.repairReasons = []
List kept = session.repairReasons
kept << rec
while (kept.size() > AUTHOR_MAX_REPAIRS + 1) kept.remove(0)
}
Map aRepairOrFinish(Map session, List calls, String feedback, Map proposal, List findings) {
if ((session.repairs as int) >= Math.min(AUTHOR_MAX_REPAIRS, authorRepairLimit(session))) {
session.log << [what: 'repairLimit', feedback: feedback]
String message = authorRepairLimit(session) == 0
? 'the first draft did not pass validation; no second AI request was sent'
: "the rule still did not pass after ${session.repairs} corrections".toString()
return aFinish(session, 'failed', message,
[lastFindings: feedback, proposal: proposal])
}
session.repairs = (session.repairs as int) + 1
aRecordRepair(session, findings)
List results = calls.collect { Object c ->
[type: 'tool_result', tool_use_id: ((Map) c).id, is_error: true, content: feedback]
}
session.messages << [role: 'user', content: results]
session.log << [what: 'repair', feedback: feedback]
return aNext(session)
}
String aErrorText(String body) {
try {
Map m = new JsonSlurper().parseText(body ?: '{}')
return ((m.error ?: [:]).message ?: body ?: '') as String
} catch (Exception e) {
return (body ?: '').take(200)
}
}
String authorScopeKey(Map inv) {
Map basis = [
devices : ((Map) inv.devices).values().collect { Map d ->
[id: d.id, name: d.name, room: d.room, driver: d.driver, disabled: d.disabled,
capabilities: d.capabilities, commands: d.commands, attributes: d.attributes,
commandArgs: ((Map) (d.metadata ?: [:])).get('commandArgs')]
}.sort { it.id.toString() },
modes : inv.modes,
variables: ((Map) inv.variables).values().collect { [id: it.id, name: it.name, type: it.type, scope: it.scope] }
.sort { it.id.toString() },
rules : inv.rules,
]
return proposeEvidenceHash(basis)
}
Map authorMarkSent(Map session) {
session.sent = ((session.sent ?: 0) as int) + 1
session.pendingRequestId = "${session.generation}.${session.sent}".toString()
session.inFlightId = session.pendingRequestId
return [generation: session.generation as String, requestId: session.pendingRequestId]
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
@Field static final int AUTHOR_INPUT_OVERHEAD_TOKENS = 2000
@Field static final int AUTHOR_MAX_BUDGET_ENTRIES = 16
@Field static final List AUTHOR_UNPROCESSED_STATUSES = [400, 401, 403, 404, 413, 429]
Map authorBudgetNew(double limitUsd) {
return [limitUsd: limitUsd, spentUsd: 0.0d, outstanding: [:], entries: [], stopped: null]
}
double authorCommitted(Map budget) {
if (budget == null) return 0.0d
double open = ((Map) (budget.outstanding ?: [:])).values().sum(0.0d) { it as double } as double
return (budget.spentUsd as double) + open
}
double authorReservationUsd(Map body) {
Map price = AUTHOR_PRICES.get(body.model)
if (price == null) return -1.0d
long inputBound = (long) Math.ceil(JsonOutput.toJson(body).getBytes('UTF-8').length / 2.0d) + AUTHOR_INPUT_OVERHEAD_TOKENS
long outputBound = (body.max_tokens ?: AUTHOR_MAX_TOKENS) as long
int searches = ((List) (body.tools ?: [])).find { it.name == 'web_search' }?.max_uses ?: 0
long searchInputAllowance = 5000L * searches
return (inputBound + searchInputAllowance) * (price.input as double) / 1e6d +
outputBound * (price.output as double) / 1e6d + searches * AUTHOR_WEB_SEARCH_USD
}
String aReserve(Map session, String requestId, Map body) {
Map budget = session.budget
if (budget == null) return null
if (budget.stopped) return budget.stopped as String
double reserve = authorReservationUsd(body)
if (reserve < 0) return "the model ${body?.model} has no agreed price".toString()
if (authorCommitted(budget) + reserve > (budget.limitUsd as double) + 1e-12d) {
return "the next request is estimated to cost up to US\$${String.format('%.4f', reserve)}, which would pass the spending limit".toString()
}
((Map) budget.outstanding).put(requestId, reserve)
return null
}
void aSettle(Map session, int httpStatus, String body) {
Map budget = session.budget
String id = session.inFlightId as String
session.inFlightId = null
if (budget == null || id == null) return
Object held = ((Map) budget.outstanding).remove(id)
double reserve = held == null ? 0.0d : (held as double)
Map parsed = null
try { parsed = new JsonSlurper().parseText(body ?: '') } catch (Exception e) { parsed = null }
Map usage = parsed?.usage instanceof Map ? parsed.usage : null
String model = parsed?.model instanceof String ? (String) parsed.model : null
boolean complete = usage != null && usage.input_tokens instanceof Number && usage.output_tokens instanceof Number
double cost
String basis
if (httpStatus == 200 && complete && AUTHOR_PRICES.containsKey(model)) {
Map price = AUTHOR_PRICES.get(model)
double inRate = (price.input as double) / 1e6d
cost = (usage.input_tokens as long) * inRate +
((usage.cache_creation_input_tokens ?: 0) as long) * inRate * 1.25d +
((usage.cache_read_input_tokens ?: 0) as long) * inRate * 0.1d +
(usage.output_tokens as long) * (price.output as double) / 1e6d +
(((usage.server_tool_use instanceof Map ? usage.server_tool_use.web_search_requests : 0) ?: 0) as int) * AUTHOR_WEB_SEARCH_USD
basis = 'reported usage'
if (cost > reserve + 1e-12d) budget.stopped = 'a reply cost more than its reservation, so no further request is sent'
} else if (httpStatus in AUTHOR_UNPROCESSED_STATUSES && parsed?.error instanceof Map) {
cost = 0.0d
basis = 'refused before processing'
} else {
cost = reserve
basis = 'unresolved; the full reservation is kept'
if (httpStatus == 200 && complete) budget.stopped = "a reply came from ${model}, which has no agreed price".toString()
}
budget.spentUsd = (budget.spentUsd as double) + cost
List entries = budget.entries
entries << [request: id, status: httpStatus, model: model, costUsd: cost, reservedUsd: reserve, basis: basis]
while (entries.size() > AUTHOR_MAX_BUDGET_ENTRIES) entries.remove(0)
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
int authorBytes(Object v) {
return JsonOutput.toJson(v).getBytes('UTF-8').length
}
String aCut(Object v, int max) {
if (v == null) return null
String s = v.toString()
return s.length() > max ? s.substring(0, max) + '...' : s
}
Map authorBoundOutcome(Map outcome, int maxBytes = AUTHOR_MAX_STORED_OUTCOME_BYTES) {
if (outcome == null) return null
int cap = Math.max(maxBytes, AUTHOR_MIN_STORED_OUTCOME_BYTES)
Map full = new LinkedHashMap(outcome)
full.retained = 'full'
if (authorBytes(full) <= cap) return full
maxBytes = cap
Map p = outcome.proposal
Map summary = [status: outcome.status, message: aCut(outcome.message, 512), attempts: outcome.attempts,
repairs: outcome.repairs, transportRetries: outcome.transportRetries, usage: outcome.usage,
models: ((List) (outcome.models ?: [])).take(8), fallbackUsed: outcome.fallbackUsed,
retained: 'summary',
retainedNote: 'the full evidence packet was too large to keep; its id names it, but it cannot be rebuilt from here']
if (outcome.lastFindings) summary.lastFindings = aCut(outcome.lastFindings, 2000)
List reasons = (outcome.repairReasons ?: [])
if (reasons) {
summary.repairReasons = reasons.take(AUTHOR_MAX_REPAIRS + 1).collect { Object o ->
Map r = o
[attempt: r.attempt, repair: r.repair, findingsTotal: r.findingsTotal,
findings: ((List) (r.findings ?: [])).take(AUTHOR_MAX_REPAIR_FINDINGS).collect { Object f ->
[code: aCut(((Map) f).code, 48), message: aCut(((Map) f).message, 80)]
}]
}
}
if (outcome.repairReasonsNote) summary.repairReasonsNote = aCut(outcome.repairReasonsNote, 120)
List checklistSource = (p?.checklist ?: outcome.checklist ?: [])
summary.checklist = checklistSource.take(32).collect { Map c ->
[id: aCut(c.id, 64), text: aCut(c.text, 200), status: c.status, where: ((List) (c.where ?: [])).take(8), note: aCut(c.note, 200)]
}
if (p != null) {
summary.proposal = [
evidenceId: p.evidenceId, evidenceFormat: p.evidenceFormat, ruleHash: p.ruleHash, ready: p.ready,
request: aCut(p.request, 1000), effects: p.effects,
rendering: ((List) (p.rendering ?: [])).take(60).collect { aCut(it, 200) },
checklist: summary.checklist,
findings: ((List) (p.findings ?: [])).take(24).collect { [code: it.code, severity: it.severity, message: aCut(it.message, 200)] },
simulation: ((List) (p.simulation ?: [])).take(8).collect { Map s ->
[description: aCut(s.description, 200), outcome: s.outcome, admitted: s.admitted,
commands: ((List) (s.commands ?: [])).take(12), why: aCut(s.why, 200), gateNote: aCut(s.gateNote, 200)]
},
counts: [findings: ((List) (p.findings ?: [])).size(), checklist: checklistSource.size(),
simulation: ((List) (p.simulation ?: [])).size()],
coverage: [note: p.coverage?.note],
diff: [kind: p.diff?.kind],
]
}
if (authorBytes(summary) <= maxBytes) return summary
Map minimal = [status: aCut(outcome.status, 32), message: aCut(outcome.message, 64), retained: 'minimal',
retainedNote: 'only a minimal record could be kept; draft again to see the evidence']
if (p != null) minimal.proposal = [evidenceId: aCut(p.evidenceId, 40), ready: p.ready == true]
if (reasons) {
Map withCodes = new LinkedHashMap(minimal)
withCodes.repairCodes = reasons.take(AUTHOR_MAX_REPAIRS + 1).collect { Object o ->
[attempt: ((Map) o).attempt,
codes: ((List) (((Map) o).findings ?: [])).take(AUTHOR_MAX_REPAIR_FINDINGS).collect { aCut(((Map) it).code, 48) }]
}
if (authorBytes(withCodes) <= maxBytes) return withCodes
}
if (authorBytes(minimal) <= maxBytes) return minimal
return [status: aCut(outcome.status, 32), retained: 'none']
}
Map authorBoundSession(Map session, int maxBytes = AUTHOR_MAX_STORED_SESSION_BYTES) {
if (session == null) return null
Map copy = new LinkedHashMap(session)
if (copy.nextRequest instanceof Map) copy.nextRequest = true
copy.log = new ArrayList((List) (session.log ?: []))
if (copy.outcome != null) copy.outcome = authorBoundOutcome((Map) copy.outcome, maxBytes.intdiv(4))
if (authorBytes(copy) <= maxBytes) return copy
if (copy.status == 'awaitingModel') {
aFinish(copy, 'failed', 'the drafting session grew past its stored size limit')
copy.outcome = authorBoundOutcome((Map) copy.outcome, maxBytes.intdiv(4))
}
if (authorBytes(copy) <= maxBytes) return copy
return [status: 'done', generation: session.generation, request: aCut(session.request, 256), budget: session.budget,
repairReasons: session.repairReasons,
outcome: [status: 'failed', message: 'the drafting session could not be stored', retained: 'minimal']]
}
Map authorPrepareSend(Map session, long at, int maxBytes = AUTHOR_MAX_STORED_SESSION_BYTES) {
if (session.status != 'awaitingModel' || session.nextRequest == null) {
return [send: false, session: authorBoundSession(session, maxBytes)]
}
if (((session.sent ?: 0) as int) >= 1 + Math.max(authorFollowUpLimit(session), authorRepairLimit(session))) {
aFinish(session, 'failed', 'the draft has used its allowed AI requests')
return [send: false, session: authorBoundSession(session, maxBytes)]
}
Map allowance = authorSendAllowance(session, at)
if (!allowance.ok) return [send: false, session: authorBoundSession(session, maxBytes)]
Map body = session.nextRequest instanceof Map ? session.nextRequest : authorRequestBody(session)
String nextId = "${session.generation}.${((session.sent ?: 0) as int) + 1}".toString()
String refused = aReserve(session, nextId, body)
if (refused != null) {
aFinish(session, 'failed', refused)
return [send: false, session: authorBoundSession(session, maxBytes)]
}
Map tag = authorMarkSent(session)
Map stored = authorBoundSession(session, maxBytes)
boolean live = stored.status == 'awaitingModel' && stored.nextRequest != null &&
(stored.pendingRequestId as String) == (tag.requestId as String)
if (!live) return [send: false, session: stored]
return [send: true, session: stored, tag: tag, timeoutSeconds: allowance.timeoutSeconds, body: body]
}
Map authorSendAllowance(Map session, long at) {
long started = (session.startedAt ?: 0L) as long
if (started <= 0L) return [ok: true, timeoutSeconds: AUTHOR_TIMEOUT_SECONDS]
long remaining = (started + AUTHOR_MAX_ELAPSED_MS - at).intdiv(1000L)
if (remaining < AUTHOR_MIN_SEND_SECONDS) {
aFinish(session, 'failed', "drafting ran out of its ${AUTHOR_MAX_ELAPSED_MS.intdiv(60000L)}-minute allowance".toString())
return [ok: false]
}
return [ok: true, timeoutSeconds: (int) Math.min((long) AUTHOR_TIMEOUT_SECONDS, remaining),
note: 'the hub may not end a request exactly at its timeout']
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
@Field static final int EDITOR_MAX_SENTENCES = 2048
@Field static final int EDITOR_MAX_REQUIREMENT_PATHS = 16
@Field static final String EDITOR_EFFECTS = 'Save draft keeps this rule here, in the editor, and changes nothing on the hub. Put on hub is the one that puts it live, and HAI checks it before it runs. Run now runs it there and then.'
@Field static final String EDITOR_CHECK_LABEL = 'Checked by the hub in the editor. This is not an AI proposal, and nothing was stored.'
@Field static final String EDITOR_PAGE_FILE = 'hai-editor.html'
@Field static final String EDITOR_PAGE_SHA256 = '3eb5d11221ce454dbb365fa166634f6330345a0371e70a3c2f9caa77934ace15'
@Field static final String EDITOR_SPECS_FILE = 'hai-editor-specs.json'
@Field static final String EDITOR_SPECS_SHA256 = 'bf549b1316d42380482af8aa1a2030936728d6058f8b2a017266f3f552106ba8'
@Field static final List EDITOR_DEVICE_CLASSES = [
[key: 'acceleration', label: 'Acceleration', capability: 'AccelerationSensor', attribute: 'acceleration', values: ['active', 'inactive']],
[key: 'battery', label: 'Battery', capability: 'Battery', attribute: 'battery', numeric: true],
[key: 'carbonDioxide', label: 'Carbon dioxide', capability: 'CarbonDioxideMeasurement', attribute: 'carbonDioxide', numeric: true],
[key: 'carbonMonoxide', label: 'Carbon monoxide', capability: 'CarbonMonoxideDetector', attribute: 'carbonMonoxide', values: ['clear', 'detected', 'tested']],
[key: 'contact', label: 'Contact', capability: 'ContactSensor', attribute: 'contact', values: ['open', 'closed']],
[key: 'level', label: 'Dimmer level', capability: 'SwitchLevel', attribute: 'level', numeric: true],
[key: 'door', label: 'Door', capability: 'DoorControl', attribute: 'door', values: ['open', 'closed', 'opening', 'closing', 'unknown']],
[key: 'energy', label: 'Energy meter', capability: 'EnergyMeter', attribute: 'energy', numeric: true],
[key: 'fanSpeed', label: 'Fan speed', capability: 'FanControl', attribute: 'speed', values: ['low', 'medium-low', 'medium', 'medium-high', 'high', 'on', 'off', 'auto']],
[key: 'garageDoor', label: 'Garage door', capability: 'GarageDoorControl', attribute: 'door', values: ['open', 'closed', 'opening', 'closing', 'unknown']],
[key: 'gas', label: 'Gas detector', capability: 'GasDetector', attribute: 'naturalGas', values: ['clear', 'detected', 'tested']],
[key: 'humidity', label: 'Humidity', capability: 'RelativeHumidityMeasurement', attribute: 'humidity', numeric: true],
[key: 'illuminance', label: 'Illuminance', capability: 'IlluminanceMeasurement', attribute: 'illuminance', numeric: true],
[key: 'motion', label: 'Motion', capability: 'MotionSensor', attribute: 'motion', values: ['active', 'inactive']],
[key: 'musicPlayer', label: 'Music player', capability: 'MusicPlayer', attribute: 'status', values: ['playing', 'paused', 'stopped']],
[key: 'power', label: 'Power meter', capability: 'PowerMeter', attribute: 'power', numeric: true],
[key: 'powerSource', label: 'Power source', capability: 'PowerSource', attribute: 'powerSource', values: ['battery', 'dc', 'mains', 'unknown']],
[key: 'presence', label: 'Presence', capability: 'PresenceSensor', attribute: 'presence', values: ['present', 'not present']],
[key: 'shock', label: 'Shock', capability: 'ShockSensor', attribute: 'shock', values: ['clear', 'detected']],
[key: 'smoke', label: 'Smoke', capability: 'SmokeDetector', attribute: 'smoke', values: ['clear', 'detected', 'tested']],
[key: 'sound', label: 'Sound', capability: 'SoundSensor', attribute: 'sound', values: ['detected', 'not detected']],
[key: 'switch', label: 'Switch', capability: 'Switch', attribute: 'switch', values: ['on', 'off']],
[key: 'tamper', label: 'Tamper', capability: 'TamperAlert', attribute: 'tamper', values: ['clear', 'detected']],
[key: 'temperature', label: 'Temperature', capability: 'TemperatureMeasurement', attribute: 'temperature', numeric: true],
[key: 'heatingSetpoint', label: 'Thermostat heating setpoint', capability: 'Thermostat', attribute: 'heatingSetpoint', numeric: true],
[key: 'coolingSetpoint', label: 'Thermostat cooling setpoint', capability: 'Thermostat', attribute: 'coolingSetpoint', numeric: true],
[key: 'thermostatFanMode', label: 'Thermostat fan mode', capability: 'Thermostat', attribute: 'thermostatFanMode', values: ['auto', 'circulate', 'on']],
[key: 'thermostatMode', label: 'Thermostat mode', capability: 'Thermostat', attribute: 'thermostatMode', values: ['auto', 'off', 'heat', 'emergency heat', 'cool']],
[key: 'thermostatState', label: 'Thermostat state', capability: 'Thermostat', attribute: 'thermostatOperatingState', values: ['heating', 'pending cool', 'pending heat', 'vent economizer', 'idle', 'cooling', 'fan only']],
[key: 'valve', label: 'Valve', capability: 'Valve', attribute: 'valve', values: ['open', 'closed']],
[key: 'water', label: 'Water', capability: 'WaterSensor', attribute: 'water', values: ['wet', 'dry']],
[key: 'windowShade', label: 'Window shade', capability: 'WindowShade', attribute: 'windowShade', values: ['opening', 'partially open', 'closed', 'open', 'closing', 'unknown']],
[key: 'lock', label: 'Lock', capability: 'Lock', attribute: 'lock', values: ['locked', 'unlocked', 'unlocked with timeout', 'unknown']],
[key: 'securityKeypad', label: 'Security keypad', capability: 'SecurityKeypad', attribute: 'securityKeypad', values: ['disarmed', 'armed home', 'armed away', 'armed night', 'unknown']],
]
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
Set declared = unread ? (((dev.attributes ?: []) as List).collect { it.toString() } as Set) : null
row.classes = EDITOR_DEVICE_CLASSES.findAll { Object o ->
caps.contains(((Map) o).capability) && (unread ? declared.contains(((Map) o).attribute) : values.containsKey(((Map) o).attribute))
}.collect { Object o -> ((Map) o).key }
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
Map editorValues(Map inv) {
return [devices: editorDevices(inv), variables: edVariableRows(inv), currentMode: inv?.currentMode]
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
String edSay(Closure c) {
try {
Object s = c()
return s == null ? null : s.toString()
} catch (Exception e) {
return 'This block is not complete, so the hub cannot read it back yet.'
}
}
void edPut(Map out, String path, String sentence) {
if (out.size() < EDITOR_MAX_SENTENCES) out.put(path, sentence)
}
void edConditionSentences(Map doc, Object node, String path, Map out, int depth) {
if (depth > LIMIT_DEPTH || out.size() >= EDITOR_MAX_SENTENCES) return
if (!(node instanceof Map)) { edPut(out, path, 'This condition is not an object.'); return }
Map c = node
edPut(out, path, edSay { renderCondition(doc, c) })
if (c.of instanceof List) {
((List) c.of).eachWithIndex { Object n, int i -> edConditionSentences(doc, n, "${path}.of.${i}".toString(), out, depth + 1) }
} else if (c.of instanceof Map) {
edConditionSentences(doc, c.of, "${path}.of".toString(), out, depth + 1)
}
}
void edActionSentences(Map doc, Object list, String path, Map out, int depth) {
if (!(list instanceof List) || depth > LIMIT_DEPTH) return
((List) list).eachWithIndex { Object ao, int i ->
if (out.size() >= EDITOR_MAX_SENTENCES) return
String p = "${path}.${i}".toString()
if (!(ao instanceof Map)) { edPut(out, p, 'This step is not an object.'); return }
Map a = ao
if (a.type == 'if') {
edPut(out, p, edSay { "If ${renderCondition(doc, (Map) a.condition)}, then:".toString() })
if (a.condition != null) edConditionSentences(doc, a.condition, "${p}.condition".toString(), out, depth + 1)
edActionSentences(doc, a.then, "${p}.then".toString(), out, depth + 1)
if (a.elseIf instanceof List) {
((List) a.elseIf).eachWithIndex { Object eo, int j ->
String ep = "${p}.elseIf.${j}".toString()
if (!(eo instanceof Map)) { edPut(out, ep, 'This branch is not an object.'); return }
Map e = eo
edPut(out, ep, edSay { "Otherwise, if ${renderCondition(doc, (Map) e.condition)}, then:".toString() })
if (e.condition != null) edConditionSentences(doc, e.condition, "${ep}.condition".toString(), out, depth + 1)
edActionSentences(doc, e.then, "${ep}.then".toString(), out, depth + 1)
}
}
if (a.otherwise instanceof List) {
edPut(out, "${p}.otherwise".toString(), 'Otherwise:')
edActionSentences(doc, a.otherwise, "${p}.otherwise".toString(), out, depth + 1)
}
return
}
if (a.type == 'byMode') {
edPut(out, p, 'Depending on the mode:')
((List) (a.cases instanceof List ? a.cases : [])).eachWithIndex { Object co, int j ->
String cp = "${p}.cases.${j}".toString()
if (!(co instanceof Map)) { edPut(out, cp, 'This case is not an object.'); return }
edPut(out, cp, edSay { renderModeCase(((Map) co).modes) + ':' })
edActionSentences(doc, ((Map) co).then, "${cp}.then".toString(), out, depth + 1)
}
if (a.otherwise instanceof List) {
edPut(out, "${p}.otherwise".toString(), 'In any other mode:')
edActionSentences(doc, a.otherwise, "${p}.otherwise".toString(), out, depth + 1)
}
return
}
if (a.type == 'repeat') {
edPut(out, p, edSay { renderRepeatHead(doc, a) + ':' })
if (a.condition != null) edConditionSentences(doc, a.condition, "${p}.condition".toString(), out, depth + 1)
edActionSentences(doc, a.steps, "${p}.steps".toString(), out, depth + 1)
return
}
edPut(out, p, edSay { ((String) renderActions(doc, [a])[0]).replaceFirst(/^\s*1\. /, '') })
if (a.type == 'wait' && a.predicate != null) edConditionSentences(doc, a.predicate, "${p}.predicate".toString(), out, depth + 1)
if (a.type == 'later') edActionSentences(doc, a.actions, "${p}.actions".toString(), out, depth + 1)
if (a.type == 'wait' && a.events instanceof List) {
((List) a.events).eachWithIndex { Object e, int j ->
String ep = "${p}.events.${j}".toString()
if (!(e instanceof Map)) { edPut(out, ep, 'This event is not an object.'); return }
edPut(out, ep, edSay { renderTrigger(doc, e) })
if (((Map) e).predicate != null) edConditionSentences(doc, ((Map) e).predicate, "${ep}.predicate".toString(), out, depth + 1)
}
}
}
}
Map editorSentences(Map doc) {
Map out = [:]
if (doc == null) return out
if (doc.triggers instanceof List) {
((List) doc.triggers).eachWithIndex { Object t, int i ->
String p = "triggers.${i}".toString()
if (!(t instanceof Map)) { edPut(out, p, 'This trigger is not an object.'); return }
edPut(out, p, edSay { 'Runs when ' + renderTrigger(doc, t) + '.' })
if (((Map) t).predicate != null) edConditionSentences(doc, ((Map) t).predicate, "${p}.predicate".toString(), out, 1)
}
}
if (doc.conditions != null) edConditionSentences(doc, doc.conditions, 'conditions', out, 0)
edActionSentences(doc, doc.actions, 'actions', out, 0)
edActionSentences(doc, doc.onFailure, 'onFailure', out, 0)
edActionSentences(doc, doc.onCancel, 'onCancel', out, 0)
return out
}
List editorRequirements(Map doc) {
List paths = proposeClaimablePaths(doc)
List out = []
paths.collate(EDITOR_MAX_REQUIREMENT_PATHS).eachWithIndex { List group, int i ->
out << [id: "editor${i + 1}".toString(), text: 'editor draft', status: 'satisfied', where: group]
}
return out
}
Map edError(int status, String code, String message) {
return [status: status, body: [ok: false, error: code, message: message]]
}
Map editorReadBody(Object raw) {
if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) return [error: edError(400, 'EMPTY_BODY', 'The request had no body.')]
String text = (String) raw
if (text.length() > EDITOR_MAX_BODY_CHARS) {
return [error: edError(413, 'BODY_TOO_LARGE', "The request is larger than ${EDITOR_MAX_BODY_CHARS} characters.".toString())]
}
Map parsed = jsonParse(text)
if (!parsed.ok) return [error: edError(400, 'INVALID_JSON', "The request is not valid JSON for this hub: ${parsed.message}".toString())]
if (!(parsed.value instanceof Map)) return [error: edError(400, 'INVALID_SHAPE', 'The request must be a JSON object.')]
return [value: parsed.value]
}
Map editorCheck(Object raw, Map inv, Map opts) {
Map read = raw instanceof Map ? [value: raw] : editorReadBody(raw)
if (read.error) return read.error
Object ruleIn = ((Map) read.value).rule
if (!(ruleIn instanceof Map)) return edError(400, 'RULE_MISSING', 'Send the rule as {"rule": {...}}.')
Map rule = ruleIn
Map timing = opts?.editorTiming instanceof Map ? opts.editorTiming : null
long phaseStarted = timing == null ? 0L : now()
Map v = validateRule(rule, inv)
if (timing != null) { timing.validateMs = now() - phaseStarted; phaseStarted = now() }
List rendering = []
String renderProblem = null
try { rendering = renderRule(rule) }
catch (Exception e) { renderProblem = 'The hub could not read the whole rule back yet; finish the highlighted blocks.' }
if (timing != null) { timing.renderMs = now() - phaseStarted; phaseStarted = now() }
Map sentences = editorSentences(rule)
if (timing != null) { timing.sentencesMs = now() - phaseStarted; phaseStarted = now() }
String ruleHash = null
try { ruleHash = jsonHash(rule, SCHEMA_DEFAULTS) } catch (Exception e) { ruleHash = null }
if (timing != null) { timing.hashMs = now() - phaseStarted; phaseStarted = now() }
List findings = ((List) v.findings).findAll { it.audience != 'internal' }
List simulation = []
Map coverage = null
List blockers = []
String simulationNote
if (vActivatable(v) && renderProblem == null) {
Map p = proposeEvaluate(rule, editorRequirements(rule), inv, null,
new LinkedHashMap(opts ?: [:]) + [request: 'Checked in the editor', source: 'editor',
validation: v, rendering: rendering, ruleHash: ruleHash])
if (timing != null) timing.proposalMs = now() - phaseStarted
findings = ((List) p.findings).findAll { !(it.code in ['NOT_REQUESTED', 'REQUIREMENT_UNSUPPORTED', 'REQUIREMENT_UNRESOLVED', 'REQUEST_MISSING']) && it.audience != 'internal' }
simulation = p.simulation
coverage = p.coverage
blockers = p.activationBlockers
simulationNote = 'Synthetic scenarios, one per trigger, from what the hub reports now. Nothing was sent to any device.'
} else {
blockers = findings.findAll { (it.code as String)?.endsWith('_UNVERIFIED') || it.code == 'REF_AVAILABILITY_UNKNOWN' }
.collect { [code: it.code, path: it.path, message: it.message] }
simulationNote = 'Not simulated, because the rule does not pass validation yet.'
}
boolean blocked = findings.any { it.severity == SEV_BLOCK } || !v.complete || renderProblem != null
List approvals = []
String status = blocked ? 'blocked' : ((findings.any { it.severity == SEV_WARN } || blockers || approvals) ? 'attention' : 'valid')
Map body = [
ok : true,
checkedBy : 'editor',
label : EDITOR_CHECK_LABEL,
effects : EDITOR_EFFECTS,
status : status,
validation : [activatable: vActivatable(v), complete: v.complete, incompleteReason: v.incompleteReason],
findings : findings.collect { Map f -> [code: f.code, severity: f.severity, path: f.path, message: f.message, audience: f.audience ?: 'person'] },
rendering : rendering,
renderProblem : renderProblem,
sentences : sentences,
simulation : simulation,
simulationNote : simulationNote,
coverage : coverage,
requiredApprovals: approvals,
activationBlockers: blockers,
ruleHash : ruleHash,
]
try { body.truth = editorTruth(rule, inv, opts) } catch (Exception e) { body.truth = null }
try { body.values = editorValues(inv) } catch (Exception e) { body.values = null }
return [status: 200, body: body, rule: rule, receiptable: status != 'blocked' && blockers.isEmpty() && vActivatable(v)]
}
@Field static final String EDITOR_RECEIPT_FORMAT = 'hai-receipt/1'
String editorHmac(String key, String text) {
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
Map editorTruth(Map rule, Map inv, Map opts) {
Map sim = simNew(inv ?: invNew(), (opts?.now ?: 0L) as long, (opts?.zone ?: 'UTC') as String)
((Map) (opts?.sun ?: [:])).each { k, v -> simSetSun(sim, k as String, ((Map) v).sunrise as String, ((Map) v).sunset as String) }
pSeedFromInventory(sim, inv ?: invNew())
((Map) (inv?.variables ?: [:])).each { k, v ->
if (v instanceof Map && ((Map) v).value != null) ((Map) sim.variables).put(k as String, [value: ((Map) v).value])
}
Map refs = (rule.refs ?: [:])
Closure leaf = { Map node ->
String t = node.type as String
if (t == 'boolean') return LOGIC_UNKNOWN
if (t == 'variable' && ((Map) refs.get(node.ref))?.scope != 'hub') return LOGIC_UNKNOWN
if (t in ['triggeredBy', 'waitOutcome']) return LOGIC_UNKNOWN
try { return ((List) coreLeaf(sim, rule, node, false, null))[0] as String }
catch (Exception e) { return LOGIC_UNKNOWN }
}
Map out = [:]
Closure eval
eval = { Object n, String path ->
if (!(n instanceof Map)) return LOGIC_UNKNOWN
Map node = n
String t = node.type as String
String v
if (t in ['all', 'any', 'xor', 'count']) {
List vals = []
((List) (node.of ?: [])).eachWithIndex { Object c, int i -> vals << eval(c, "${path}.of.${i}".toString()) }
v = t == 'all' ? logicAnd(vals) : t == 'any' ? logicOr(vals) : t == 'count' ? logicCountNode(node, vals) : logicXor(vals)
} else if (t == 'not') {
v = logicNot(eval(node.of, "${path}.of".toString()) as String)
} else {
v = leaf(node) as String
}
out.put(path, v)
return v
}
if (rule.requiredExpression instanceof Map) eval(rule.requiredExpression, 'requiredExpression')
if (rule.conditions instanceof Map) eval(rule.conditions, 'conditions')
((List) (rule.triggers ?: [])).eachWithIndex { Object t, int i ->
if (t instanceof Map && ((Map) t).condition instanceof Map) eval(((Map) t).condition, "triggers.${i}.condition".toString())
}
Closure steps
steps = { Object list, String path ->
((List) (list instanceof List ? list : [])).eachWithIndex { Object a, int i ->
if (!(a instanceof Map)) return
Map s = a
String p = "${path}.${i}".toString()
if (s.condition instanceof Map) eval(s.condition, "${p}.condition".toString())
if (s.predicate instanceof Map) eval(s.predicate, "${p}.predicate".toString())
['then', 'otherwise', 'steps', 'actions'].each { String k -> if (s.get(k) instanceof List) steps(s.get(k), "${p}.${k}".toString()) }
((List) (s.elseIf instanceof List ? s.elseIf : [])).eachWithIndex { Object b, int j ->
if (!(b instanceof Map)) return
if (((Map) b).condition instanceof Map) eval(((Map) b).condition, "${p}.elseIf.${j}.condition".toString())
steps(((Map) b).then, "${p}.elseIf.${j}.then".toString())
}
((List) (s.cases instanceof List ? s.cases : [])).eachWithIndex { Object c, int j ->
if (c instanceof Map) steps(((Map) c).then, "${p}.cases.${j}.then".toString())
}
}
}
['actions', 'onFailure', 'onCancel'].each { String k -> steps(rule.get(k), k) }
return out
}
@Field static final List EDITOR_RUN_STEP_TYPES = ['command', 'customCommand', 'log', 'setMode', 'hsm']
Map editorRunStepDoc(Map body) {
Map step = body?.step instanceof Map ? body.step : null
if (step == null) return edError(400, 'NO_STEP', 'There was no step to run.')
if (step.disabled == true) return edError(409, 'STEP_SKIPPED', 'This step is skipped. Enable it first.')
if (!(step.type in EDITOR_RUN_STEP_TYPES)) {
return edError(409, 'NOT_ONE_ACTION', 'Run now runs a device command, a mode, HSM or a log line on its own. Run the whole rule for this step.')
}
if (step.type == 'hsm' && !HSM_ARM_OPS.contains(step.op)) {
return edError(400, 'SCHEMA_ENUM', "'${step.op}' is not an HSM operation; allowed are ${HSM_ARM_OPS.join(', ')}".toString())
}
if (step.type == 'setMode' && step.ref == null && !(step.value instanceof String && ((String) step.value).trim())) {
return edError(400, 'SCHEMA_TYPE', 'A mode step needs the mode to change to.')
}
if (step.type in ['command', 'customCommand'] && !(step.command instanceof String && ((String) step.command) ==~ /[A-Za-z][A-Za-z0-9_]{0,63}/)) {
return edError(400, 'SCHEMA_TYPE', 'A command step needs the name of a command.')
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
Map doc = [schema: SCHEMA_ID, id: 'hai-run-step', name: 'Run now', refs: refs, triggers: [[kind: 'ruleInvocation']], actions: [step]]
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
if (req.needsFixing == true) draft.needsFixing = true
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
Object id = ((Map) read.value).id
if (!(id instanceof String)) return edError(400, 'INVALID_ID', 'Say which draft to delete.')
if (((String) id).startsWith('ev_')) return edError(400, 'NOT_A_DRAFT', 'Only drafts can be deleted here. Proposals are kept as evidence.')
Map next = edCopyStore(store)
List drafts = next.drafts
int at = drafts.findIndexOf { it.id == id }
if (at < 0) return edError(404, 'DRAFT_NOT_FOUND', 'That draft does not exist.')
drafts.remove(at)
return [status: 200, store: next, body: [ok: true, drafts: drafts.collect { edDraftSummary((Map) it) }, message: 'Draft deleted.']]
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
Map editorRule(Object idIn, Map store) {
if (!(idIn instanceof String) || ((String) idIn).isEmpty() || ((String) idIn).length() > EDITOR_MAX_ID_CHARS) {
return edError(400, 'INVALID_ID', 'Say which rule to open.')
}
String id = (String) idIn
Map d = ((List) (store?.drafts ?: [])).find { it.id == id }
if (d != null) {
return [status: 200, body: [ok: true, kind: 'draft', id: d.id, name: d.name, savedAt: d.savedAt, ruleHash: d.ruleHash, rule: d.rule,
assessment: d.assessment, request: d.request]]
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
Map editorExport(Object raw, Map inv, long now) {
Map body = editorReadBody(raw)
if (body.error) return body.error
Object rule = ((Map) body.value).rule
if (!(rule instanceof Map)) return edError(400, 'NO_RULE', 'There is no rule to export.')
Map out = bundleExport([rule], inv, [exportedAt: now, from: 'HAI'])
return [status: 200, body: [ok: true, bundle: [portable: out.portable], name: ((Map) rule).name ?: 'rule']]
}
Map editorImport(Object raw, Map inv) {
Map body = editorReadBody(raw)
if (body.error) return body.error
Object nested = ((Map) body.value).bundle
Map v
if (nested instanceof Map) {
v = nested
} else {
Object text = ((Map) body.value).text
Map parsed = text instanceof String ? jsonParse((String) text) : [ok: false]
if (!parsed.ok && text instanceof String) return edError(400, 'NOT_A_BUNDLE', "That file could not be read: ${parsed.message ?: 'it is not valid JSON'}.".toString())
if (!(parsed.value instanceof Map)) return edError(400, 'NOT_A_BUNDLE', 'That file is not a HAI rule or bundle.')
v = parsed.value
}
Map bundle = v.portable instanceof Map ? v : (v.schema instanceof String && v.rules instanceof List ? [portable: v] : null)
if (bundle == null) {
if (v.schema instanceof String && v.triggers instanceof List) {
return [status: 200, body: [ok: true, rules: [v], summary: null, message: 'A plain rule, opened as it is.']]
}
return edError(400, 'NOT_A_BUNDLE', 'That file is not a HAI rule or bundle.')
}
Map imported = bundleImport(bundle, inv, false)
if (imported.refusal != null) return edError(409, ((Map) imported.refusal).code as String, ((Map) imported.refusal).message as String)
Map summary = bundleResolutionSummary(imported, inv)
List toConfirm = []
((List) imported.resolution).each { Map row ->
if (row.status != BIND_CANDIDATE || ((List) (row.alternatives ?: [])).size() != 1) return
Map rule = ((List) imported.rules).find { ((Map) it).id == row.rule }
Map ref = ((Map) (rule?.refs ?: [:])).get(row.refKey)
if (ref == null) return
String id = ((List) row.alternatives)[0] as String
ref.id = id
toConfirm << [ref: row.refKey, device: invDevice(inv, id)?.name ?: id]
}
int toChoose = ((List) imported.resolution).count { Map row -> row.status != BIND_RESOLVED && !toConfirm.any { it.ref == row.refKey } } as int
return [status: 200, body: [ok: true, rules: imported.rules, resolution: imported.resolution, summary: summary,
toConfirm: toConfirm, toChoose: toChoose]]
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
message: ("This copy is held for review, not running on the hub: ${reason}. Fix it here and press Put on hub, " +
'or save it as a draft.').toString()]]
}
Map editorApi(String route, Map req, Map store, Map inv, Map opts, Map extra = [:]) {
try {
switch (route) {
case 'state':
return [status: 200, body: editorState(inv, store, extra)]
case 'check':
return editorCheck(req?.body, inv, opts)
case 'rule':
Map found = editorRule(((Map) (req?.params ?: [:])).id, store)
if (found.status == 404 && extra?.held instanceof Map) found = editorHeldView(((Map) (req?.params ?: [:])).id, extra.held)
Map opened = editorFreshNames(found, inv)
Object openedRule = ((Map) (opened.body ?: [:])).rule
if (opts?.withCheck == true && opened.status == 200 && openedRule instanceof Map) {
Map c = editorCheck([rule: openedRule], inv, opts)
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
return editorExport(req?.body, inv, (opts?.now ?: 0L) as long)
case 'import':
return editorImport(req?.body, inv)
}
return edError(404, 'NO_SUCH_ROUTE', 'The editor has no such request.')
} catch (Exception e) {
return edError(500, 'INTERNAL', 'The hub could not complete this request. Your edits in the page are kept.')
}
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
@Field static final String RT_CHILD_BUILD = '72c0f8ecbaba5d86'
@Field static final long RT_CADENCE_MS = 60000L
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
List rtScopeFindings(Map doc, boolean modeAllowed = false) {
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
Map v = fast ? [findings: [], complete: true] : validateRule(doc, inv)
rtpMark(pp, 'validate')
List rendering = []
Map diff = null
List simulation = []
String problem = null
try {
if (presentation) {
rendering = renderRule(doc)
if (hasActive && activeDoc == null) activeDoc = rtChildDoc(pp, ruleId, entry.epoch)
diff = diffRules(activeDoc, doc)
if (vActivatable(v)) simulation = proposeScenarios(doc, inv, rtpNow(pp), (rtpCall(pp, 'zone', null) ?: 'UTC') as String,
rtDocUsesSun(doc) ? (rtpCall(pp, 'sunDays', null) ?: [:]) : [:])
}
} catch (Exception e) {
problem = "the rule could not be read back or simulated: ${e.message}".toString()
}
List refusals = []
vBlocking(v).each { refusals << rtFinding(it.code as String, it.path as String, it.message as String) }
if (!v.complete) refusals << rtFinding('VALIDATION_INCOMPLETE', '', (v.incompleteReason ?: 'validation did not finish') as String)
refusals.addAll(rtScopeFindings(doc, rtpCall(pp, 'modeAllowed', null) == true))
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
if (!((List) review.refusals).isEmpty()) {
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
try {
rtpCall(pp, 'child', [childId, 'haiSetup', [devices, review.allow, rtpCall(pp, 'purposes', null) ?: [:], rtSetupOptions(pp, cand.doc)]])
rtpMark(pp, 'setup')
rtpHook(pp, 'beforeStage')
res = rtpCall(pp, 'child', [childId, 'haiStage', [cand.doc, binding.ruleHash, challengeId, claimId, baseEpoch, expiresAt, rtStageIntent(ch)]])
rtpMark(pp, 'stage')
} catch (Exception e) {
rtLog(pp, [what: 'stageCallFailed', ruleId: ruleId, message: e.message])
return [ok: false, code: 'STAGE_UNCONFIRMED', challengeId: challengeId, claimId: claimId,
message: 'Staging was not confirmed. The runtime reconciles it on the next pass.', chainWarnings: review.chainWarnings]
}
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
Map res = rtpCall(pp, 'child', [childId, 'haiLifecycle', [op, runId]])
if (res?.ok && op == 'disable' && entry.state == 'active') rtRegistryTransition(pp, ruleId, ['active'], 'disabled', [:])
if (res?.ok && op == 'enable' && entry.state == 'disabled') rtRegistryTransition(pp, ruleId, ['disabled'], 'active', [:])
rtLog(pp, [what: op, ruleId: ruleId, ok: res?.ok ?: false])
return res ?: [ok: false]
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
try { rtpCall(pp, 'child', [e.childId, 'haiSetup', [devices, allow, rtpCall(pp, 'purposes', null) ?: [:], rtSetupOptions(pp)]]) }
catch (Exception ex) { rtLog(pp, [what: 'allowPushFailed', ruleId: e.ruleId, message: ex.message]) }
}
}
Map rtSetupOptions(Map pp, Map doc = null) {
Map out = [writableVariables: rtpCall(pp, 'writableVariables', null) ?: [], modeAllowed: rtpCall(pp, 'modeAllowed', null) == true]
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
String rtAppApiTokenRefusal(Map req, String token) {
Object given = req?.token
if (!(given instanceof String) || ((String) given).isEmpty()) return 'NO_TOKEN'
return jsonSameSecret(token, given) ? null : 'WRONG_TOKEN'
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
if (gap > 5L * RT_CADENCE_MS) flags << [what: 'clockForward', gapMs: gap]
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
entries = entries.findAll { waited.contains(((Map) it).ruleId as String) } + entries.findAll { !waited.contains(((Map) it).ruleId as String) }
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
String suffix = ruleState == 'disabled' ? ' (Stopped)' : ruleState == 'active' && paused ? ' (Paused)' : ''
return '[HAI] ' + n.take(80 - 6 - suffix.length()) + suffix
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
@Field static final String AM_CAPS_SHA256 = 'da3a0db15b46e2becd9d44f524423cd2fe8ed431bfc8d8fc6bea6a09e5ad2822'
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
String hamDetailFileFor(String channel) {
return channel == 'production' ? 'ham-decode-detail.json' : 'ham-decode-detail-dev.json'
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
if (theirs != null && channel != null && theirs != channel) {
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
