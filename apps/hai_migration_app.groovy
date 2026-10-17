import groovy.transform.Field
import hubitat.helper.RMUtils
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.Instant

definition(
name: 'HAI Migration (Preview)',
namespace: 'gordonthelander',
author: 'HAI',
description: 'Copies Rule Machine 5.1 rules into HAI Engine, with switch over and switch back. Optional.',
category: 'Automations',
parent: 'gordonthelander:HAI Engine (Preview)',
iconUrl: '',
iconX2Url: '',
installOnOpen: true,
singleThreaded: true,
oauth: true
)
preferences {
page(name: 'migrationMainPage')
}
mappings {
path('/migration') { action: [GET: 'migrationPageGet'] }
path('/migration/api/state') { action: [GET: 'migrationStateGet'] }
path('/migration/api/apply') { action: [POST: 'migrationApplyPost'] }
path('/migration/api/assess') { action: [GET: 'migrationAssessGet', POST: 'migrationAssessPost'] }
path('/migration/api/plan') { action: [POST: 'migrationPlanPost'] }
path('/migration/api/dryrun') { action: [POST: 'migrationDryRunPost'] }
}
def installed() { migrationInit() }
def updated() { migrationInit() }
def haiRotateAccess() {
String before = state.accessToken as String
state.remove('accessToken')
try { createAccessToken() }
catch (Exception e) { if (before) state.accessToken = before; throw e }
return true
}
void migrationInit() {
if (!state.accessToken) {
try { createAccessToken() } catch (Exception e) { log.warn "HAI Migration: OAuth is not enabled for this app code (${e.message})" }
}
migrationAssessSubscribe(true)
}
@Field static final String ASSESS_MIN_AM = '2.4.36'
@Field static final String ASSESS_REQUEST_EVENT = 'haiAssessRequest'
@Field static final String ASSESS_DONE_EVENT = 'haiAssessDone'
@Field static final int ASSESS_SUBSCRIPTION = 1
@Field static final long ASSESS_WAIT_IDS_MS = 120000L
@Field static final long ASSESS_WAIT_ALL_MS = 300000L
void migrationAssessSubscribe(boolean force) {
if (!force && atomicState.assessSubscribed == ASSESS_SUBSCRIPTION) return
try {
unsubscribe()
subscribe(location, ASSESS_DONE_EVENT, 'migrationAssessDone')
atomicState.assessSubscribed = ASSESS_SUBSCRIPTION
} catch (Exception e) {
log.warn "HAI Migration: could not listen for Automation Map's answers (${e.message})"
}
}
String migrationAssessUnavailable(Map am) {
if (am?.uninstalled == true) return MIG_AM_UNINSTALLED
if (am?.appId == null) return 'Needs Automation Map 2.4.36 or later, with a decode HAI can read.'
return null
}
String migrationAssessOldHint(Map am) {
if (am?.version == null || migrationVersionCompare(am.version as String, ASSESS_MIN_AM) < 0) {
return 'Automation Map\'s last scan was by a version before 2.4.36. If it does not answer, update it to 2.4.36 or later.'
}
return null
}
Map migrationAssessOutstanding() {
Map cur = state.assess instanceof Map ? state.assess : null
if (cur == null || cur.done != null) return null
long wait = (cur.all == true || cur.progress != null) ? ASSESS_WAIT_ALL_MS : ASSESS_WAIT_IDS_MS
return (now() - ((cur.sentAt ?: 0) as long)) < wait ? cur : null
}
def migrationAssessGet() {
return migrationRespond([ok: true, assess: state.assess])
}
def migrationAssessPost() {
Map req = [:]
try { req = new groovy.json.JsonSlurper().parseText(request?.body ?: '{}') } catch (Exception e) { req = [:] }
Map am = state.assessAm instanceof Map ? state.assessAm : null
String why = migrationAssessUnavailable(am)
if (why != null) return migrationRespond([ok: false, code: 'MIGRATION_ASSESS_UNAVAILABLE', message: why])
Map busy = migrationAssessOutstanding()
if (busy != null) {
return migrationRespond([ok: false, code: 'MIGRATION_ASSESS_BUSY', assess: busy,
message: 'Automation Map is still assessing the last request.'])
}
boolean all = req.all == true
List ids = all ? [] : ((req.rmIds instanceof List ? req.rmIds : [req.rmId]).collect { "${it}".trim() }
.findAll { String s -> s.isLong() }.unique())
if (!all && ids.isEmpty()) return migrationRespond([ok: false, code: 'MIGRATION_BAD_REQUEST', message: 'That request named no rules.'])
int rules = (am.rules ?: 0) as int
if (!all && ids.size() > 12 && ids.size() > Math.ceil(rules * 0.4)) all = true
migrationAssessSubscribe(false)
String rid = "${now()}-${(int) (Math.random() * 99999)}".toString()
Map value = [requestId: rid, appId: am.appId] + (all ? [all: true] : [ids: ids])
sendLocationEvent(name: ASSESS_REQUEST_EVENT, value: groovy.json.JsonOutput.toJson(value), isStateChange: true,
descriptionText: ("HAI Migration asked Automation Map to assess " + (all ? 'all rules' : "${ids.size()} rule(s)")).toString())
state.assess = [requestId: rid, sentAt: now(), all: all, count: all ? rules : ids.size(),
waitMs: all ? ASSESS_WAIT_ALL_MS : ASSESS_WAIT_IDS_MS, progress: null, done: null]
return migrationRespond([ok: true, assess: state.assess])
}
def migrationAssessDone(evt) {
Object v = null
try { v = new groovy.json.JsonSlurper().parseText("${evt?.value ?: ''}") } catch (Exception e) { return }
Map cur = state.assess instanceof Map ? new LinkedHashMap((Map) state.assess) : null
if (!(v instanceof Map) || cur == null || ((Map) v).requestId != cur.requestId) return
Map ans = new LinkedHashMap((Map) v)
ans.at = now()
if (ans.pending == true) {
cur.progress = ans
cur.waitMs = ASSESS_WAIT_ALL_MS
} else {
cur.done = ans
}
state.assess = cur
}
def migrationPlanPost() {
Map req = [:]
try { req = new groovy.json.JsonSlurper().parseText(request?.body ?: '{}') } catch (Exception e) { req = [:] }
if (req.withdraw == true) {
state.remove('planApproved')
return migrationRespond([ok: true, planApproved: null, message: 'Approval withdrawn.'])
}
String fp = req.approve as String
if (!(fp ==~ /[0-9a-f]{6,64}/)) return migrationRespond([ok: false, code: 'MIGRATION_BAD_REQUEST', message: 'That request named no plan.'])
state.planApproved = [fingerprint: fp, at: now()]
return migrationRespond([ok: true, planApproved: state.planApproved, message: 'Plan approved.'])
}
def migrationDryRunPost() {
Map req = [:]
try { req = new groovy.json.JsonSlurper().parseText(request?.body ?: '{}') } catch (Exception e) { req = [:] }
String rmId = req.rmId as String
Map facts = parent.haiMigrationFacts(true)
Map refused = migrationContractRefusal(facts)
if (refused != null) return migrationRespond(refused)
Map pairs = migPairs([migPairs: facts.pairs])
Map pair = rmId == null ? null : pairs.get(rmId)
if (pair?.haiRuleId == null) return migrationRespond([ok: false, code: 'MIGRATION_NO_COPY', message: 'There is no HAI copy of this rule to try.'])
Map out
try { out = parent.haiMigrationCall('dryRun', [ruleId: pair.haiRuleId]) }
catch (Exception e) { out = [ok: false, message: "The dry run could not run (${e.message}).".toString()] }
if (out?.scenarios instanceof List) {
parent.haiMigrationRecord(migRecord(pairs, rmId, pair.rmName as String, [op: 'dryRun', ok: out.ok == true], now()))
}
return render(status: 200, contentType: 'application/json', data: groovy.json.JsonOutput.toJson((out ?: [:]) + [rmId: rmId]))
}
def migrationMainPage() {
migrationInit()
dynamicPage(name: 'migrationMainPage', title: 'HAI Migration (Preview)', install: true, uninstall: true) {
section('Rule Machine') {
if (state.accessToken) {
href name: 'haiMigrationHref', title: 'Rule Machine Migration Facility', required: false,
url: "${getFullLocalApiServerUrl()}/migration?access_token=${state.accessToken}&v=${MIGRATION_PAGE_SHA256.take(12)}".toString(),
description: 'Discover, select, assess and controlled migration of RM rules, with rollback option'
} else {
paragraph 'Enable OAuth for the HAI Migration app code, then press Done, to create the migration link.'
}
}
}
}
@Field static final int EXPECTED_ENGINE_CONTRACT = 2
Map migrationContractRefusal(Map facts) {
Object c = facts?.contract
if (c instanceof Number && ((Number) c).intValue() == EXPECTED_ENGINE_CONTRACT) return null
return [ok: false, code: 'MIGRATION_CONTRACT_MISMATCH',
message: ("This HAI Migration needs a HAI Engine that speaks migration contract ${EXPECTED_ENGINE_CONTRACT}; " +
"this Engine speaks ${c instanceof Number ? c : 'an older version'}. Update the one that is older.").toString()]
}
def migrationPageGet() {
render(status: 200, contentType: 'text/html', data: migrationPage({ Object n -> downloadHubFile(n as String) }))
}
def migrationStateGet() {
try {
return migrationRespond(migrationGatherState())
} catch (Throwable t) {
String why = ((t instanceof Error ? 'the hub stopped it' : 'it failed') +
(t.message ? ': ' + t.message : '')).toString()
log.warn "HAI Migration: the rule list failed at ${state.migStep}: ${why}"
return migrationRespond([ok: false, code: 'MIGRATION_STATE_FAILED',
message: ("The rule list could not be built. It got as far as " +
"${state.migStep ?: 'the start'} and then ${why}.").toString()])
}
}
def migrationApplyPost() {
Map req = [:]
try { req = new groovy.json.JsonSlurper().parseText(request?.body ?: '{}') } catch (Exception e) { req = [:] }
String op = req.op as String
List wanted = (req.rmIds instanceof List) ? ((List) req.rmIds).collect { it as String }
: (req.rmId == null ? [] : [req.rmId as String])
if (wanted.isEmpty() || op == null) {
return migrationRespond([ok: false, code: 'MIGRATION_BAD_REQUEST', message: 'That request did not name a rule and a step.'])
}
Map reads = [:]
Map gathered = migrationGatherState(wanted, reads)
if (gathered.ok == false) return migrationRespond(gathered)
if (wanted.size() > 1 || req.round != null) {
List known = gathered.rows
List rows = wanted.collect { String id -> known.find { it.rmId == id } }.findAll { it != null }
if (rows.isEmpty()) {
return migrationRespond([ok: false, code: 'MIGRATION_NO_SUCH_RULE', message: 'None of those rules are on this hub any more.'])
}
List skipped = []
if (req.round != null) {
Map picked = migRoundRows(rows, req.round, op)
rows = picked.rows
skipped = picked.skipped
if (rows.isEmpty()) {
return migrationRespond([ok: false, code: 'MIGRATION_ROUND_EMPTY', skipped: skipped,
message: "No rule in round ${req.round} can take that step now.".toString()])
}
}
Map many = migApplyMany(rows, op, migrationPlat(reads)) + [skipped: skipped]
Map pairs = migPairs([migPairs: ((Map) parent.haiMigrationFacts(true)).pairs])
((List) many.done).each { Object d ->
Map e = d
pairs = migRecord(pairs, e.rmId as String, e.rmName as String,
[ok: true, op: op, phase: e.phase, haiId: e.haiId, heldReason: e.heldReason, rmPrint: e.rmPrint,
testedAt: e.testedAt], now())
}
parent.haiMigrationRecord(pairs)
return migrationRespond(many + [state: migrationRowsAfter(wanted, reads)])
}
String rmId = wanted[0]
Map row = ((List) gathered.rows).find { it.rmId == rmId }
if (row == null) {
return migrationRespond([ok: false, code: 'MIGRATION_NO_SUCH_RULE', message: 'This hub has no Rule Machine rule with that id any more.'])
}
if (req.upgrades instanceof List) row = row + [upgrades: ((List) req.upgrades).collect { it as String }]
if (req.by != null) row = row + [testedBy: (req.by as String)]
Map out = migApply(row, op, migrationPlat(reads))
if (out?.ok) {
Map pairs = migPairs([migPairs: ((Map) parent.haiMigrationFacts(true)).pairs])
parent.haiMigrationRecord(migRecord(pairs, rmId, row.rmName as String, out, now()), rmId,
op == 'switchOver' || out.rmWasPaused == true ? Boolean.TRUE : (op == 'switchBack' && out.confirmed == true ? Boolean.FALSE : null))
}
return migrationRespond((out ?: [:]) + [state: migrationRowsAfter(wanted, reads)])
}
Map migrationRowsAfter(List wanted, Map reads) {
try {
Map st = migrationGatherState(wanted, reads)
if (st?.ok != true) return null
Map view = new LinkedHashMap(st)
view.remove('order')
view.remove('plan')
return view
} catch (Throwable t) {
log.warn "HAI Migration: the rows after that step could not be read (${t.message}); the page reads the whole list"
return null
}
}
Object migrationRespond(Map out) {
return render(status: (out?.ok ? 200 : 409), contentType: 'application/json', data: groovy.json.JsonOutput.toJson(out))
}
@Field static final java.util.concurrent.ConcurrentHashMap MIGRATION_DECODE_CACHE = new java.util.concurrent.ConcurrentHashMap()
@Field static final long MIGRATION_DECODE_TTL_MS = 120000L
void migrationDecodeCachePrune(long now) {
MIGRATION_DECODE_CACHE.keySet().toList().each { Object k ->
Map e = MIGRATION_DECODE_CACHE.get(k)
if (e == null || !(e.at instanceof Long) || now - (Long) e.at > MIGRATION_DECODE_TTL_MS) MIGRATION_DECODE_CACHE.remove(k)
}
}
@Field static final java.util.concurrent.ConcurrentHashMap MIGRATION_FM_LIST = new java.util.concurrent.ConcurrentHashMap()
@Field static final long MIGRATION_FM_LIST_TTL_MS = 5000L
Map migrationFileStamps(long at) {
Map memo = MIGRATION_FM_LIST.get('list')
if (memo != null && memo.at instanceof Long && at - (Long) memo.at >= 0 && at - (Long) memo.at < MIGRATION_FM_LIST_TTL_MS) return memo.stamps
Map stamps = null
try {
httpGet([uri: 'http://127.0.0.1:8080/hub/fileManager/json', timeout: 10]) { resp ->
if (resp?.status != 200) return
Object d = resp.data
List files = d instanceof List ? d : (d instanceof Map && ((Map) d).files instanceof List ? ((Map) d).files : null)
if (files == null) return
Map got = [:]
files.each { Object f ->
if (!(f instanceof Map) || ((Map) f).name == null) return
Object size = ((Map) f).size
Object date = ((Map) f).date
got.put(((Map) f).name as String, size == null || date == null ? null : "${size}|${date}".toString())
}
stamps = got
}
} catch (Exception e) { stamps = null }
if (stamps != null) MIGRATION_FM_LIST.put('list', [at: at, stamps: stamps])
return stamps
}
@Field static final java.util.concurrent.ConcurrentHashMap MIGRATION_DECODE_HEADS = new java.util.concurrent.ConcurrentHashMap()
Map migrationDecodeHead(String text, String channel, boolean detail) {
if (text == null || !text.trim().startsWith('{')) return [readable: false]
String t = text.trim()
int r = t.indexOf(',"rules":')
Map h = null
try { h = r > 0 ? new groovy.json.JsonSlurper().parseText(t.substring(0, r) + '}') : null } catch (Exception e) { h = null }
if (h == null) return [readable: true, appVersion: migrationAppVersion(t)]
Map inst = (h.instance instanceof Map ? h.instance : [:])
Map scan = (h.scan instanceof Map ? h.scan : [:])
return [readable: hamHeaderReadable(h, channel, detail), appVersion: inst.appVersion as String,
scanAt: scan.lastScanCompletedAt as String, writtenAt: h.generatedAt as String]
}
int migrationTimeCompare(String a, String b) {
Long x = null
Long y = null
try { x = a == null ? null : java.time.Instant.parse(a).toEpochMilli() } catch (Exception e) { x = null }
try { y = b == null ? null : java.time.Instant.parse(b).toEpochMilli() } catch (Exception e) { y = null }
if (x == null || y == null) return x == null ? (y == null ? 0 : -1) : 1
return x <=> y
}
String migrationDownload(String name) {
try {
byte[] b = downloadHubFile(name)
return b == null ? null : new String(b, 'UTF-8')
} catch (Exception e) { return null }
}
Map migrationDecode(boolean detail) {
long at = now()
migrationDecodeCachePrune(at)
Map stamps = migrationFileStamps(at)
Map texts = [:]
Map heads = [:]
['production', 'dev'].each { String c ->
String n = detail ? hamDetailFileFor(c) : hamFileFor(c)
if (stamps != null && !stamps.containsKey(n)) return
String stamp = stamps == null ? null : (String) stamps.get(n)
Map known = MIGRATION_DECODE_HEADS.get(n)
if (stamp != null && known != null && known.stamp == stamp) { heads.put(c, known.head); return }
String t = migrationDownload(n)
if (t == null || !t.trim().startsWith('{')) return
texts.put(c, t)
Map head = migrationDecodeHead(t, c, detail)
heads.put(c, head)
if (stamp != null) MIGRATION_DECODE_HEADS.put(n, [stamp: stamp, head: head])
}
List readable = ['production', 'dev'].findAll { heads.containsKey(it) && ((Map) heads[it]).readable == true }
List pool = readable ?: ['production', 'dev'].findAll { heads.containsKey(it) }
String ch = 'dev'
if (pool.size() == 1) ch = pool[0] as String
else if (pool.size() == 2) {
Map p = heads.production
Map d = heads.dev
int by = migrationTimeCompare(p.scanAt as String, d.scanAt as String)
if (by == 0) by = migrationTimeCompare(p.writtenAt as String, d.writtenAt as String)
if (by == 0) by = migrationVersionCompare(p.appVersion as String, d.appVersion as String)
ch = by >= 0 ? 'production' : 'dev'
}
String name = detail ? hamDetailFileFor(ch) : hamFileFor(ch)
String stamp = stamps == null ? null : (String) stamps.get(name)
String text = (String) texts.get(ch)
String key = stamp != null ? "${name}|fm|${stamp}".toString()
: "${name}|${text == null ? -1 : text.length()}|${text == null ? 0 : text.hashCode()}".toString()
Map hit = MIGRATION_DECODE_CACHE.get(name)
if (hit != null && hit.key == key) return hit.out
if (text == null && heads.containsKey(ch)) text = migrationDownload(name)
String parsed = text
Map files = [readLocalFile: { String n -> n == name ? parsed : null }]
Map out = detail ? hamDetail(files, ch) : hamDecode(files, ch)
Map made = hamSettled(out + [sourceFile: name, sourceVersion: migrationAppVersion(text), sourceChannel: ch])
MIGRATION_DECODE_CACHE.put(name, [key: key, out: made, at: at])
return made
}
String migrationAppVersion(String text) {
if (text == null) return null
java.util.regex.Matcher m = text.take(4096) =~ /"appVersion"\s*:\s*"([^"]*)"/
return m.find() ? m.group(1) : null
}
int migrationVersionCompare(String a, String b) {
List x = (a ?: '').tokenize('.')
List y = (b ?: '').tokenize('.')
for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
int p = i < x.size() && ((String) x[i]).isInteger() ? ((String) x[i]) as int : 0
int q = i < y.size() && ((String) y[i]).isInteger() ? ((String) y[i]) as int : 0
if (p != q) return p <=> q
}
return 0
}
Map migrationGatherState(Collection only = null, Map reads = null) {
boolean reuse = reads != null && reads.containsKey('decode')
state.migStep = 'the Rule Machine rule list'
List rmRows = []
if (reuse) rmRows = reads.rmRows
else {
try { rmRows = schemaRmRuleRows(RMUtils.getRuleList('5.0')) } catch (Exception e) { rmRows = [] }
if (reads != null) reads.rmRows = rmRows
}
state.migStep = 'HAI Engine'
Map facts = parent.haiMigrationFacts()
Map refused = migrationContractRefusal(facts)
if (refused != null) { state.migStep = null; return refused }
if (facts.appsComplete == true && facts.checked instanceof List) {
Set present = ((List) facts.checked).collect { it.toString() } as Set
rmRows = rmRows.findAll { Object r -> present.contains(((Map) r).id as String) }
}
Map pairs = migPairs([migPairs: facts.pairs])
List paused = (facts.paused ?: [])
List checked = facts.checked instanceof List ? facts.checked : paused
Map haiRules = (facts.haiRules ?: [:])
state.migStep = "Automation Map's summary decode"
Map decode = reuse ? reads.decode : migrationDecode(false)
state.migStep = "Automation Map's detailed decode"
Map detail = reuse ? reads.detail : null
String detailWhy = reuse ? (String) reads.detailWhy : null
if (!reuse) {
try {
detail = migrationDecode(true)
} catch (Throwable t) {
detailWhy = ((t instanceof Error ? 'the hub stopped the read' : 'the read failed') +
(t.message ? ' (' + t.message + ')' : '')).toString()
log.warn "HAI Migration: ${detailWhy}"
}
}
boolean amGone = false
if (!reuse && facts.appsComplete == true && facts.checked instanceof List) {
Map kept = migDecodeInstalled(decode, facts.checked)
amGone = decode != null && !kept.is(decode)
decode = kept
Map keptDetail = migDecodeInstalled(detail, facts.checked)
if (amGone || (detail != null && !keptDetail.is(detail))) {
detail = amGone ? decode : keptDetail
detailWhy = null
}
}
if (reuse) amGone = decode?.code == 'HAM_UNINSTALLED'
if (reads != null && !reuse) reads.putAll([decode: decode, detail: detail, detailWhy: detailWhy])
state.migStep = 'building the rows'
Set vocab = null
try { vocab = rmVocabulary() } catch (Throwable t) { vocab = null }
Map limits = new LinkedHashMap((Map) (facts.limits ?: [:]))
if (detail?.ok == true && detail.disabledDevices instanceof List) {
limits.disabled = (((List) limits.disabled ?: []) + ((List) detail.disabledDevices).collect { it.toString() }).unique()
}
Map st = migState(rmRows, paused, pairs, haiRules, decode, vocab, now(),
detail?.ok == true ? detail : null, facts.allowed, limits, checked,
facts.stopped instanceof List ? facts.stopped : [],
facts.disabled instanceof List ? facts.disabled : [], only)
st.amAppId = ((Map) (decode?.instance ?: [:])).appId
Map am = [appId: st.amAppId, version: decode?.sourceVersion, rules: rmRows.size(), uninstalled: amGone]
state.assessAm = am
String assessWhy = migrationAssessUnavailable(am)
st.assess = [available: assessWhy == null, why: assessWhy, hint: migrationAssessOldHint(am), amVersion: am.version,
decodeWrittenAt: decode?.writtenAt, request: migrationAssessOutstanding()]
state.migStep = null
st.links = [engine: parent?.id == null ? null : "/installedapp/configure/${parent.id}".toString(),
editor: facts.editorUrl, devices: '/device/list',
map: st.amAppId == null ? null : "/installedapp/configure/${st.amAppId}".toString()]
st.planApproved = state.planApproved
st.source = [summary: [file: decode?.sourceFile, appVersion: decode?.sourceVersion, channel: decode?.sourceChannel,
scanAt: decode?.generatedAt, writtenAt: decode?.writtenAt],
detail : [file: detail?.sourceFile, appVersion: detail?.sourceVersion, channel: detail?.sourceChannel,
scanAt: detail?.generatedAt, writtenAt: detail?.writtenAt]]
if (detail?.sourceFile != null) {
((List) st.notes).add(Math.min(1, ((List) st.notes).size()),
("Read from ${detail.sourceFile}, written by Automation Map ${detail.sourceVersion ?: '(version not stated)'}" +
(detail.sourceChannel == null ? '' : ", ${detail.sourceChannel == 'dev' ? 'Dev' : 'production'} channel, chosen as the latest scan") + '.').toString())
}
if (detailWhy != null) {
((List) st.notes).add(1, ("HAI could not read Automation Map's detailed decode, so there are no " +
"readings of what each rule does: ${detailWhy}").toString())
}
if (facts.pausedWhy) {
((List) st.notes) << ("Whether each Rule Machine rule is paused could not be read: ${facts.pausedWhy}. " +
'Rules without a reading say not checked.').toString()
}
return st
}
Map migrationPlat(Map reads = null) {
return [
rmAction : { Map c ->
try { RMUtils.sendAction((List) c.ids, c.action as String, app.label, '5.0'); return [ok: true] }
catch (Exception e) { return [ok: false, message: e.message as String] }
},
haiRuleOp: { Map c -> parent.haiMigrationCall('haiRuleOp', c) },
activateStopped: { Map c ->
Map out = parent.haiMigrationCall('activateStopped', c)
return out?.refused instanceof Map
? [ok: false, code: out.code, message: migRefusalWords((Map) out.refused, out.doc)]
: out
},
rmPausedNow: { Object rmId -> (Boolean) ((Map) parent.haiMigrationCall('rmPausedNow', [rmId: rmId]))?.paused },
now : { Object unused -> now() },
pauseFor : { Object ms -> pauseExecution(ms as long) },
rebuildRule: { Map c ->
Map detail = reads?.detail
try {
if (detail == null) detail = migrationDecode(true)
} catch (Throwable t) {
return [ok: false, code: 'MIGRATION_NO_DETAIL',
message: ('HAI could not read what Automation Map holds for this rule just now' +
(t.message ? ': ' + t.message : '.')).toString()]
}
if (detail?.ok != true) {
return [ok: false, code: (detail?.code as String) ?: 'MIGRATION_NO_DETAIL',
message: (detail?.message as String) ?: 'Automation Map holds no detailed decode to copy from.']
}
Map dRule = hamRule(detail, c.rmId)
if (dRule == null) {
return [ok: false, code: 'MIGRATION_RULE_GONE',
message: 'Automation Map no longer holds this rule. It may have been deleted in Rule Machine.']
}
Map locals = null
try {
httpGet([uri: "http://127.0.0.1:8080/installedapp/statusJson/${rmBareId(dRule.id)}".toString(),
contentType: 'application/json', timeout: 10]) { resp ->
if (resp?.status == 200) locals = rmLocalVariablesFrom(resp.data)
}
} catch (Exception e) { locals = null }
Map made = rmDecodeTooOld(detail) ?: rmRebuild(dRule, (detail.rules ?: []), detail.hubVariables,
(c.upgrades instanceof List) ? c.upgrades : null, locals)
return made + [decodeSource: [channel: detail.sourceChannel, file: detail.sourceFile,
scanAt: detail.generatedAt, writtenAt: detail.writtenAt]]
},
importRule : { Map c -> parent.haiMigrationCall('importRule', c) }
]
}
@Field static final int LIMIT_PARALLEL_RUNS = 4
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
@Field static final List BUTTON_ACTIONS = ['pushed', 'held', 'doubleTapped', 'released']
@Field static final List HSM_ARM_OPS = ['armAway', 'armHome', 'armNight', 'disarm', 'disarmAll', 'armAll', 'armRules', 'disarmRules', 'cancelAlerts']
@Field static final List LOCATION_EVENTS = ['systemStart', 'severeLoad', 'zigbeeOff', 'zigbeeOn', 'zwaveCrashed', 'sunrise', 'sunset', 'sunriseTime', 'sunsetTime', 'lowMemory']
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
boolean schemaHubMaintenance(Object url) {
return url instanceof String && (url =~ /(?i)^https?:\/\/[^\/]+\/hub\/[^?#]*(reboot|shutdown|restart|update|restore|reset|backup|delete)/).find()
}
@Field static final Map BUTTON_CAPABILITY = [pushed: 'PushableButton', held: 'HoldableButton',
doubleTapped: 'DoubleTapableButton', released: 'ReleasableButton']
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
String rtBareId(Object id) {
String s = id == null ? '' : id.toString()
return s.startsWith('dev:') ? s.substring(4) : s
}
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
@Field static final int MIG_CONFIRM_TRIES = 3
@Field static final long MIG_CONFIRM_GAP_MS = 250L
@Field static final String MIGRATION_PAGE_FILE = 'hai-migration.html'
@Field static final String MIGRATION_PAGE_SHA256 = '99497d81a640e4ff66cf058b72859c150bbca9dcb264af2a815e2184c5067be6'
@Field static final String MIG_PROV_OK = 'ok'
@Field static final String MIG_PROV_PARTIAL = 'partial'
@Field static final String MIG_PROV_ABSENT = 'absent'
@Field static final String MIG_PROV_INCOMPATIBLE = 'incompatible'
@Field static final String MIG_PROV_STALE = 'stale'
@Field static final String MIG_PROV_BROKEN = 'broken'
Map migProvider(Map decode, Long nowMs = null) {
if (decode == null) {
return [state: MIG_PROV_ABSENT, note: 'Automation Map has not been asked for a decode yet.']
}
if (decode.ok != true && decode.code == 'HAM_UNINSTALLED') {
return [state: MIG_PROV_ABSENT, code: 'HAM_UNINSTALLED', note: MIG_AM_UNINSTALLED]
}
if (decode.ok != true) {
String code = decode.code as String
String state = MIG_PROV_BROKEN
if (code == 'HAM_ABSENT' || code == 'HAM_NO_READER') state = MIG_PROV_ABSENT
if (code == 'HAM_CONTRACT' || code == 'HAM_SCHEMA' || code == 'HAM_VOCABULARY' || code == 'HAM_CHANNEL') {
state = MIG_PROV_INCOMPATIBLE
}
String can = 'The Rule Machine rules below still list, and you can still see which side is live, ' +
'because HAI reads those itself. There are no readings of what each rule does.'
return [state: state, code: code, note: ((decode.message ?: 'Automation Map did not answer.') + ' ' + can).toString()]
}
String readAt = migLater(decode.writtenAt as String, decode.generatedAt as String)
String age = migAgeWords(readAt, nowMs)
boolean stale = migStale(decode, nowMs)
boolean readLater = readAt != null && decode.generatedAt != null && readAt != (decode.generatedAt as String)
String note = (readLater
? "Automation Map last read rules ${age} (last full scan ${migAgeWords(decode.generatedAt as String, nowMs)})".toString()
: "Automation Map read this hub ${age}".toString()) +
(decode.partial == true ? ' and did not finish: some rules may be missing or thin.' : '.')
if (stale) {
note = note + (" Automation Map expects to re-read this hub at least every " +
"${migHours(decode.staleAfterSeconds as long)}, so this is out of date and nothing " +
'will be copied from it. Re-scan in Automation Map.').toString()
} else if (decode.staleAfterSeconds == null) {
note = note + (decode.autoScanEnabled == true
? ' It states no maximum age for a reading, so how current this is is yours to judge.'
: ' Automatic scanning is off in Automation Map, so it states no maximum age and nothing is' +
' watching how old this gets.')
}
String state = stale ? MIG_PROV_STALE : (decode.partial == true ? MIG_PROV_PARTIAL : MIG_PROV_OK)
return [state: state, count: decode.count, generatedAt: readAt, scannedAt: decode.generatedAt, partial: decode.partial == true,
stale: stale, staleAfterSeconds: decode.staleAfterSeconds, note: note]
}
@Field static final String MIG_AM_UNINSTALLED = 'Automation Map is not installed. Its last reading is ignored; install it and scan to copy rules.'
Map migDecodeInstalled(Map decode, Collection installed) {
if (decode == null || decode.ok != true || installed == null) return decode
Object id = ((Map) (decode.instance instanceof Map ? decode.instance : [:])).appId
if (id == null || installed.any { it?.toString() == id.toString() }) return decode
return [ok: false, code: 'HAM_UNINSTALLED', message: MIG_AM_UNINSTALLED, sourceFile: decode.sourceFile,
sourceVersion: decode.sourceVersion, sourceChannel: decode.sourceChannel]
}
boolean migStale(Map decode, Long nowMs) {
Object limit = decode?.staleAfterSeconds
if (!(limit instanceof Number) || nowMs == null || decode.generatedAt == null) return false
long then
try {
then = java.time.Instant.parse(decode.generatedAt as String).toEpochMilli()
} catch (Exception ignored) {
return false
}
return (nowMs - then) > ((limit as long) * 1000L)
}
String migHours(long secs) {
if (secs < 5400L) return "${Math.max(1L, secs.intdiv(60L))} minutes".toString()
if (secs < 172800L) return "${Math.round(secs / 3600.0d)} hours".toString()
return "${Math.round(secs / 86400.0d)} days".toString()
}
String migLater(String a, String b) {
if (a == null) return b
if (b == null) return a
try { return java.time.Instant.parse(a).isAfter(java.time.Instant.parse(b)) ? a : b } catch (Exception e) { return b }
}
String migAgeWords(String generatedAt, Long nowMs) {
if (generatedAt == null) return 'at an unrecorded time'
if (nowMs == null) return "at ${generatedAt}".toString()
long then
try {
then = java.time.Instant.parse(generatedAt).toEpochMilli()
} catch (Exception ignored) {
return "at ${generatedAt}".toString()
}
long secs = Math.max(0L, (nowMs - then).intdiv(1000L))
if (secs < 120L) return 'just now'
if (secs < 7200L) return "${secs.intdiv(60L)} minutes ago".toString()
if (secs < 172800L) return "${secs.intdiv(3600L)} hours ago".toString()
return "${secs.intdiv(86400L)} days ago".toString()
}
@Field static final String MIG_LIVE_RM = 'rm'
@Field static final String MIG_LIVE_HAI = 'hai'
@Field static final String MIG_LIVE_BOTH = 'both'
@Field static final String MIG_LIVE_NEITHER = 'neither'
@Field static final Map MIG_OPS = [
clone : 'Create a disabled HAI copy',
switchOver : 'Switch over: pause the RM rule and activate the HAI copy',
switchBack : 'Switch back: deactivate the HAI copy and resume the RM rule',
deleteClone : 'Delete the HAI copy',
deleteOriginal: 'Delete the Rule Machine rule',
markTested : 'Mark the HAI copy as tested'
]
Map migPairs(Object st) {
Object p = st instanceof Map ? ((Map) st).migPairs : null
return p instanceof Map ? p : [:]
}
String migRmRuleUrl(Object rmId) {
return "/installedapp/configure/${rmId}".toString()
}
List migRows(List rmRows, Collection pausedIds, Map pairs, Map haiRules,
Map decode = null, Collection known = null, Map detail = null, Collection allowed = null,
Map limits = null, Collection checkedIds = null, Collection stoppedIds = null,
Collection disabledIds = null, Collection onlyIds = null) {
Set only = onlyIds == null ? null : ((Collection) onlyIds).collect { it.toString() } as Set
Set disabled = ((disabledIds ?: []) as Collection).collect { it.toString() } as Set
Set paused = ((pausedIds ?: []) as Collection).collect { it.toString() } as Set
Set checked = checkedIds == null ? null : ((Collection) checkedIds).collect { it.toString() } as Set
Set stopped = ((stoppedIds ?: []) as Collection).collect { it.toString() } as Set
return ((rmRows ?: []) as List).collect { Object ro ->
Map r = ro
String rmId = r.id as String
Map pair = ((pairs ?: [:]).get(rmId) ?: [:])
String haiId = pair.haiRuleId as String
Map hai = haiId == null ? null : ((haiRules ?: [:]).get(haiId))
if (hai?.state == 'removed') hai = null
boolean rmDisabled = disabled.contains(rmId)
Boolean rmPaused = rmDisabled ? false : paused.contains(rmId) ? true : (checked == null || checked.contains(rmId) ? false : null)
boolean rmStopped = !rmDisabled && rmPaused == false && stopped.contains(rmId)
boolean haiActive = hai != null && hai.state == 'active'
Map row = [
rmId : rmId,
rmName : r.name as String,
rmPaused: rmPaused,
rmStopped: rmStopped,
rmDisabled: rmDisabled,
livePolled: haiId != null,
rmUrl : migRmRuleUrl(rmId),
haiId : haiId,
haiName : hai == null ? null : (hai.name as String),
haiState: hai == null ? null : (hai.state as String),
haiChildId: hai == null ? null : (hai.childId as String),
haiMissing: haiId != null && hai == null,
phase : (pair.phase ?: 'none') as String,
rmWasPaused: pair.rmWasPaused == true,
live : haiId == null || rmPaused == null ? null : migLive(rmPaused || rmStopped || rmDisabled, haiActive)
]
if (only == null || only.contains(rmId)) {
row.putAll(migAssess(decode, rmId, known, detail, allowed, limits))
Map selfRule = hamRule(detail, rmId)
Set fixes = rmRestartRepairTargets(selfRule)
boolean onlyRepairs = !fixes.isEmpty() && ((List) (selfRule?.steps ?: [])).every { Object so ->
Map s = so
return s.kind != 'action' || (rmSetsPbTrue(s) && !((List) (s.ruleTargets ?: [])).isEmpty())
}
if (onlyRepairs && fixes.every { Object t -> ((Map) ((pairs ?: [:]).get(t as String) ?: [:])).phase == 'switched' }) {
row.repairNote = 'Every rule this one repairs now runs in HAI, so it repairs nothing. It can stay paused.'
}
}
if (hai?.state == 'held' && pair.heldReason) {
if (!(row.warnings instanceof List)) row.warnings = []
((List) row.warnings) << [kind: 'refused', names: [], blocking: false,
message: "The copy could not go on the hub: ${pair.heldReason}. Once that is fixed, Switch over starts it, or Copy again.".toString()]
}
if (haiId != null && hai != null && pair.rmPrint && row.rmPrint && pair.rmPrint != row.rmPrint) {
if (!(row.warnings instanceof List)) row.warnings = []
((List) row.warnings) << [kind: 'drift', names: [], blocking: false,
message: 'The Rule Machine rule has changed since its HAI copy was made, so the copy still does what the rule used to. Delete the HAI copy and Copy again to bring it up to date.']
}
if (haiId != null && pair.testedAt != null) { row.testedAt = pair.testedAt; row.testedBy = pair.testedBy }
if (haiId != null && pair.dryRunAt != null) { row.dryRunAt = pair.dryRunAt; row.dryRunOk = pair.dryRunOk == true }
if (pair.phase != 'switched' && hai != null && hai.lastRunAt != null) row.copyLastRunAt = hai.lastRunAt
if (pair.phase == 'switched' && hai != null) {
row.haiLastRunAt = hai.lastRunAt
if (hai.lastRunProblem) {
if (!(row.warnings instanceof List)) row.warnings = []
((List) row.warnings) << [kind: 'watch', names: [], blocking: false,
message: "The HAI copy's last run went wrong: ${hai.lastRunProblem}. If it happens again, Switch back to Rule Machine.".toString()]
}
}
row.ops = migOps(row)
return row
}
}
Map migAssess(Map decode, Object rmId, Collection known, Map detail = null, Collection allowed = null,
Map limits = null) {
Map rule = hamRule(decode, rmId)
if (rule == null) {
return [verdict: null, verdictReasons: [], complexity: null,
decoded: decode != null && decode.ok == true ? false : null,
unread: decode != null && decode.ok == true,
amUninstalled: decode != null && decode.ok != true && decode.code == 'HAM_UNINSTALLED']
}
Map conds = null
Map dRule = hamRule(detail, rmId)
if (dRule != null) {
Map built = rmConditions(dRule)
int uses = ((List) (dRule.conditions ?: [])).size()
conds = built.ok == true
? [ok: true, count: uses, message: uses == 0
? 'This rule has no conditions.'
: "HAI can rebuild ${uses == 1 ? 'the condition' : "all ${uses} conditions"} in this rule.".toString(),
rendered: uses == 0 ? null : rmRendered(dRule, built.node)]
: [ok: false, count: uses, code: built.code, message: built.message]
}
Map coupling = dRule == null ? null : rmCoupling(dRule, (detail?.rules ?: []))
Map cov = hamCoverage(rule, known)
List reasons = new ArrayList((List) (cov.reasons ?: []))
if (conds != null) reasons << (conds.message as String)
List warnings = []
if (coupling != null && !((List) coupling.inbound).isEmpty()) {
List inb = coupling.inbound
List repair = (coupling.inboundRestartRepair ?: [])
List repairNoted = repair.containsAll(inb) ? [] : inb.findAll { repair.contains(it) }
inb = inb.findAll { !repairNoted.contains(it) }
warnings << [kind: 'rules', names: inb,
message: ("${inb.size()} other rule${inb.size() == 1 ? '' : 's'} reach into this one: " +
"${inb.take(4).join(', ')}${inb.size() > 4 ? ' and others' : ''}. Move this one " +
'alone and they will go on commanding the paused Rule Machine copy. Nothing ' +
'errors; the link stops working and the symptom shows up in those rules, not ' +
'this one.').toString()]
List pb = ((List) (coupling.inboundPb ?: [])).findAll { !repairNoted.contains(it) }
if (!pb.isEmpty()) {
Map w = warnings[-1]
w.pbNames = pb
w.pbOnlyNames = ((List) (coupling.inboundPbOnly ?: [])).findAll { !repairNoted.contains(it) }
w.message = ((w.message as String) + " ${pb.take(4).join(', ')}${pb.size() > 4 ? ' and others' : ''} " +
"set${pb.size() == 1 ? 's' : ''} this rule's private boolean, often to repair a cooldown " +
'after a restart. The HAI copy keeps its delays and its private boolean through a restart, ' +
'so it does not need that repair, but it will not receive those settings either, even from ' +
'a caller already copied to HAI. If a caller sets it for any other reason, change that step ' +
"to this rule's HAI copy once both are in HAI.").toString()
}
if (!repair.isEmpty() && repair.containsAll(inb)) {
Map w = warnings[-1]
w.restartRepair = true
w.message = ("${repair.take(4).join(', ')}${repair.size() > 4 ? ' and others' : ''} " +
"set${repair.size() == 1 ? 's' : ''} this rule's private boolean after a hub restart. The HAI " +
'copy does not need that: it keeps its delays and private boolean through a restart.').toString()
}
if (!repairNoted.isEmpty()) {
warnings << [kind: 'rules', restartRepair: true, names: repairNoted, pbNames: repairNoted, pbOnlyNames: repairNoted,
message: ("${repairNoted.take(4).join(', ')}${repairNoted.size() > 4 ? ' and others' : ''} " +
"set${repairNoted.size() == 1 ? 's' : ''} this rule's private boolean after a hub restart. The " +
'HAI copy does not need that: it keeps its delays and private boolean through a restart.').toString()]
}
}
if (dRule != null && allowed != null) {
List unapproved = rmUnapprovedDevices(dRule, allowed)
if (!unapproved.isEmpty()) {
warnings << [kind: 'devices', names: unapproved, blocking: true,
message: ("This rule uses ${unapproved.size()} device${unapproved.size() == 1 ? '' : 's'} HAI " +
"is not allowed to use: ${unapproved.take(4).join(', ')}" +
"${unapproved.size() > 4 ? ' and others' : ''}. Select ${unapproved.size() == 1 ? 'it' : 'them'} " +
"in HAI Engine's devices first. HAI will not copy this rule until then.").toString()]
}
}
if (dRule != null && limits?.disabled instanceof Collection) {
Set off = ((Collection) limits.disabled).collect { String a = it as String; a.startsWith('dev:') ? a.substring(4) : a } as Set
List names = rmDevicesNamed(dRule).findAll { Object k, Object v -> off.contains(k as String) }.values().toList().sort()
if (!names.isEmpty()) {
warnings << [kind: 'disabled', names: names, blocking: false,
message: ("This rule uses ${names.size() == 1 ? 'a device' : names.size() + ' devices'} disabled on this hub: " +
"${names.take(4).join(', ')}${names.size() > 4 ? ' and others' : ''}. HAI can copy it, but will not " +
"switch over to it until ${names.size() == 1 ? 'it is' : 'they are'} enabled in Devices.").toString()]
}
}
Map rebuild = null
boolean devicesBlocked = warnings.any { ((Map) it).kind == 'devices' }
if (dRule != null && conds?.ok == true && !devicesBlocked) {
Map made = rmDecodeTooOld(detail) ?: rmRebuild(dRule, (detail?.rules ?: []), detail?.hubVariables)
rebuild = made.ok == true
? [ok: true, triggers: ((List) made.doc.triggers).size(), steps: ((List) made.doc.actions).size(),
invoked: made.invoked == true, skipped: made.skipped, behaviour: made.behaviour]
: [ok: false, code: made.code, message: made.message]
if (made.ok == true && limits != null) warnings.addAll(migPermissionBlocks((Map) made.doc, limits))
if (made.ok == true && made.hubPower != null) {
Map line = ((List) (made.behaviour ?: [])).find { ((Map) it).key == 'hubPower' }
warnings << [kind: 'hubPower', names: ['the hub'], blocking: false,
message: (line?.text as String) ?: 'This rule reboots or shuts down your hub. Tick "Copy it anyway" on its row to copy it.']
}
}
String verdict = cov.verdict as String
if (verdict == 'ready' && dRule != null) {
String refusal = conds?.ok == false ? (conds.message as String)
: (warnings.find { ((Map) it).blocking == true } as Map)?.message as String
if (refusal == null && rebuild?.ok == false) refusal = (rebuild.message as String) ?: 'HAI cannot rebuild this rule.'
if (refusal == null && rebuild == null) refusal = 'HAI did not rebuild this rule, so it cannot say a copy would work.'
if (refusal != null) {
verdict = 'needs-decision'
reasons.remove(refusal)
reasons.add(0, refusal)
}
}
if (verdict == 'needs-decision' && dRule != null && conds?.ok == true && rebuild?.ok == true &&
!warnings.any { ((Map) it).blocking == true }) {
verdict = 'ready'
reasons.removeAll { (it as String)?.startsWith('HAI has no mapping for ') }
}
Map partial = dRule == null ? null : rmDecodeFailed(dRule)
if (partial != null) {
verdict = 'blocked'
reasons.remove(partial.message as String)
reasons.add(0, partial.message as String)
}
Map power = warnings.find { ((Map) it).kind == 'hubPower' }
if (power != null && verdict == 'ready') {
verdict = 'needs-decision'
reasons.remove(power.message as String)
reasons.add(0, power.message as String)
}
String emptyWhy = conds?.code == 'RM_RULE_EMPTY' ? (conds.message as String)
: rebuild?.code == 'RM_RULE_EMPTY' ? (rebuild.message as String) : null
if (emptyWhy != null) {
verdict = 'empty'
reasons = [emptyWhy]
}
return [verdict: verdict, verdictReasons: reasons, unmapped: cov.missing, rmPrint: migPrint(dRule),
conditions: conds, rebuild: rebuild, coupling: coupling, warnings: warnings,
complexity: hamComplexity(rule, known), decoded: rule.hasDecodedFlow == true,
steps: rule.stepCount, engine: rule.engine, rmNameSeen: rule.name]
}
String migPrint(Map dRule) {
if (dRule == null) return null
String text = groovy.json.JsonOutput.toJson([t: dRule.triggers, c: dRule.conditions, e: dRule.expressions, a: dRule.actions])
return java.security.MessageDigest.getInstance('SHA-256').digest(text.getBytes('UTF-8')).toList().take(6).collect { String.format('%02x', it) }.join('')
}
List migPermissionBlocks(Map doc, Map limits) {
List out = []
Map refs = (doc?.refs instanceof Map ? doc.refs : [:])
Set written = [] as Set
boolean setsMode = false
Closure walk
walk = { Object o ->
if (o instanceof List) { ((List) o).each { walk(it) }; return }
if (!(o instanceof Map)) return
Map m = o
if (m.type == 'setMode') setsMode = true
if (m.type == 'setVariable' && m.ref instanceof String) written << m.ref
if ((m.type == 'runRule' || m.type == 'http') && m.result instanceof String) written << m.result
if (m.type == 'ping' && m.into instanceof Map) ((Map) m.into).values().each { if (it instanceof String) written << it }
m.each { Object k, Object v -> if (v instanceof Map || v instanceof List) walk(v) }
}
walk(doc?.actions)
walk(doc?.onFailure)
if (limits.writable instanceof Collection) {
Set ok = ((Collection) limits.writable).collect { it.toString() } as Set
List names = written.collect { Object k -> refs.get(k) }
.findAll { Map r -> r != null && r.kind == 'variable' && (r.scope ?: 'hub') == 'hub' && !ok.contains((r.name ?: '') as String) }
.collect { Map r -> r.name as String }.unique().sort()
if (!names.isEmpty()) {
out << [kind: 'variables', names: names, blocking: true,
message: ("This rule changes ${names.size() == 1 ? 'a hub variable' : names.size() + ' hub variables'} HAI rules may not change: " +
"${names.take(4).join(', ')}${names.size() > 4 ? ' and others' : ''}. Add ${names.size() == 1 ? 'it' : 'them'} to " +
"\"Approved hub variables\" on the HAI Engine page first. HAI will not copy this rule until then.").toString()]
}
}
if (setsMode && limits.modeAllowed == false) {
out << [kind: 'mode', names: ['the mode'], blocking: true,
message: 'This rule sets the mode, and HAI rules may not. Turn on "Allow rules to change the mode" on the HAI Engine page first. HAI will not copy this rule until then.']
}
return out
}
String migLive(boolean rmPaused, boolean haiActive) {
if (!rmPaused && haiActive) return MIG_LIVE_BOTH
if (!rmPaused) return MIG_LIVE_RM
if (haiActive) return MIG_LIVE_HAI
return MIG_LIVE_NEITHER
}
Map migOps(Map row) {
Map out = [:]
boolean cloned = row.haiId != null && !row.haiMissing
Map rb = row.rebuild
boolean held = cloned && row.haiState == 'held'
List blocks = ((List) (row.warnings ?: [])).findAll { ((Map) it).blocking == true }
out.clone = (!cloned || held) && !blocks.isEmpty()
? migOp(false, blocks.collect { ((Map) it).message }.join(' '))
: held
? migOp(true, null)
: cloned
? migOp(false, 'There is already an HAI copy of this rule. Delete it in the editor before making another.')
: (rb == null
? migOp(false, ((Map) row.conditions)?.ok == false && ((Map) row.conditions).message
? (((Map) row.conditions).message as String)
: row.amUninstalled == true
? 'Automation Map is not installed. Install it and scan to copy rules.'
: row.unread == true
? 'Not read yet: Automation Map has not read this rule, so HAI cannot say whether it can be copied. Scan in Automation Map, then reload.'
: row.decoded == true && row.verdict in ['blocked', 'unassessed'] && ((List) (row.verdictReasons ?: []))
? (((List) row.verdictReasons)[0] as String)
: 'HAI has not read what this rule does yet, so it cannot say whether it can be copied.')
: (rb.ok == true
? migOp(true, null)
: migOp(false, (rb.message as String) ?: 'This rule cannot be rebuilt as an HAI rule.')))
Map off = ((List) (row.warnings ?: [])).find { ((Map) it).kind == 'disabled' }
out.switchOver = cloned && row.rmDisabled == true && row.live != MIG_LIVE_HAI
? migOp(false, 'The Rule Machine rule is disabled, so it is not running and Switch over cannot pause it. Enable it in Apps first, or start the HAI copy in the editor.')
: cloned && off != null && row.live != MIG_LIVE_HAI
? migOp(false, ("Enable ${((List) off.names).join(', ')} in Devices first. Switching over would pause the Rule Machine " +
'rule for a copy that cannot start.').toString())
: cloned && row.live == MIG_LIVE_HAI
? migOp(false, 'The HAI copy is already the one running.')
: cloned
? migOp(true, null)
: migOp(false, row.haiMissing == true
? 'The HAI copy this rule was paired with is gone, so there is no HAI copy to switch to. Copy it again first.'
: 'There is no HAI copy of this rule to switch to yet.')
out.switchBack = cloned && row.rmPaused == null
? migOp(false, 'Whether the Rule Machine rule is paused was not checked, so there may be nothing to switch back to. Reload the page.')
: cloned && row.rmDisabled == true
? migOp(false, 'The Rule Machine rule is disabled, so switching back would not start it. Enable it in Apps.')
: cloned && row.rmStopped == true
? migOp(false, 'The Rule Machine rule is stopped, not paused, so switching back would not start it. Start it in Rule Machine.')
: cloned
? migOp(row.rmPaused as boolean, (row.rmPaused as boolean) ? null : 'The Rule Machine rule is already running, so there is nothing to switch back to.')
: row.haiMissing == true && row.rmPaused == true
? migOp(false, 'The HAI copy this rule was paired with is gone and the Rule Machine rule is still paused, so neither is running. Resume it in Rule Machine.')
: row.haiMissing == true
? migOp(false, 'The HAI copy this rule was paired with is gone, so there is nothing to switch back from.')
: migOp(false, 'There is no HAI copy of this rule, so nothing was switched over.')
out.deleteClone = held
? migOp(false, 'This copy was never put on the hub, so there is nothing running to delete. Copy again replaces it.')
: cloned && (row.phase == 'switched' || row.live == MIG_LIVE_HAI)
? migOp(false, 'The HAI copy is the one doing this rule\'s job. Switch back first, then delete the copy.')
: cloned
? migOp(true, null)
: migOp(false, row.haiMissing == true ? 'The HAI copy this rule was paired with has already been deleted.' : 'There is no HAI copy of this rule.')
out.deleteOriginal = migOp(false, 'HAI never deletes a Rule Machine rule. Open the rule in Rule Machine and delete it there when you are ready.')
out.markTested = !cloned
? migOp(false, 'There is no HAI copy of this rule to test yet.')
: held
? migOp(false, 'This copy is held, not on the hub, so it cannot be tested there yet.')
: row.testedAt != null
? migOp(false, 'This copy is already marked as tested.')
: row.dryRunAt == null
? migOp(false, 'Run the dry run first. Mark it tested once the dry run passes and you have tried it for real.')
: row.dryRunOk != true
? migOp(false, 'The last dry run of this copy found a problem. Run it again and mark it tested once it passes.')
: migOp(true, null)
return out
}
Map migOp(boolean available, String why) {
return available ? [available: true] : [available: false, why: why]
}
Map migApply(Map row, String op, Map plat) {
if (!MIG_OPS.containsKey(op)) {
return [ok: false, code: 'MIGRATION_NO_SUCH_OP', message: 'There is no such migration step.']
}
Map can = ((Map) (row.ops ?: migOps(row))).get(op)
if (can?.available != true) {
return [ok: false, code: 'MIGRATION_UNAVAILABLE', message: can?.why as String, op: op]
}
if (op == 'switchOver') return migSwitch(row, plat, true)
if (op == 'switchBack') return migSwitch(row, plat, false)
if (op == 'clone') return migClone(row, plat)
if (op == 'deleteClone') return migDeleteClone(row, plat)
if (op == 'markTested') {
String by = ((row.testedBy ?: '') as String).trim().take(40)
return [ok: true, op: op, testedAt: migNow(plat), testedBy: by ?: 'you',
message: 'Recorded: tested in real life and it works. Switch over when you are ready.']
}
return [ok: false, code: 'MIGRATION_UNAVAILABLE', message: 'That step is not built yet.', op: op]
}
Map migClone(Map row, Map plat) {
Map made = migCall(plat, 'rebuildRule', [rmId: row.rmId, upgrades: (row.upgrades instanceof List) ? row.upgrades : []])
if (made?.ok != true) {
return [ok: false, op: 'clone', code: (made?.code as String) ?: 'MIGRATION_REBUILD',
message: (made?.message as String) ?: 'HAI could not rebuild this rule just now.',
phase: 'rebuild']
}
if (made.hubPower != null && made.hubPowerChosen != true) {
return [ok: false, op: 'clone', code: 'MIGRATION_HUB_POWER', phase: 'rebuild',
message: 'Not copied: this rule reboots or shuts down your hub. Open its row and tick "Copy it anyway" ' +
'if you want the HAI copy to do the same, then press Copy.']
}
Map kept = migCall(plat, 'importRule', [doc: made.doc])
if (kept?.ok != true) {
return [ok: false, op: 'clone', code: (kept?.code as String) ?: 'MIGRATION_IMPORT',
message: (kept?.message as String) ?: 'The copy was built but HAI could not keep it.',
phase: 'import']
}
List skipped = (made.skipped ?: [])
String note = skipped.isEmpty() ? '' :
(" ${skipped.size()} action${skipped.size() == 1 ? ' that is' : 's that are'} turned off in Rule " +
'Machine were left out, which is what Rule Machine does with them too.').toString()
Map stopped = plat?.containsKey('activateStopped') ? migCall(plat, 'activateStopped', [candidateId: kept.candidateId]) : null
boolean unconfirmed = stopped != null && stopped.ok != true && stopped.unconfirmed == true
boolean refused = stopped != null && stopped.ok != true && !unconfirmed
String held = refused ?
(" It is held for review rather than set up on the hub (${stopped.message ?: stopped.code ?: 'no reason given'}); " +
'Switch over starts it.').toString() : ''
String pending = unconfirmed ?
(' It is being set up on the hub, stopped; this page reads it again in a few seconds. If it does not ' +
'show as stopped then, Copy again.') : ''
Map src = made.decodeSource instanceof Map ? made.decodeSource : null
String readAt = src == null ? null : migLater(src.writtenAt as String, src.scanAt as String)
String from = src == null ? '' :
(" It reflects ${src.channel == 'dev' ? 'the Dev' : src.channel == 'production' ? 'the production' : 'the'} " +
"Automation Map's reading of this rule, made ${migAgeWords(readAt, migNow(plat))}; if the rule was edited " +
'in Rule Machine after that, scan again and copy again.').toString()
return [ok: true, op: 'clone', phase: 'cloned', candidateId: kept.candidateId, haiId: made.doc?.id, rmPrint: row.rmPrint,
skipped: skipped, stopped: stopped == null ? null : stopped.ok == true, unconfirmed: unconfirmed,
heldReason: refused ? ((stopped.message ?: stopped.code ?: 'no reason given') as String) : null,
decodeChannel: src?.channel, decodeReadAt: readAt,
message: ("A copy was made. It is not running: the Rule Machine rule is still the one in charge " +
'until you switch over.' + held + pending + note + from).toString()]
}
String migRefusalWords(Map out, Map doc) {
List found = out?.refusals instanceof List ? out.refusals : []
if (found.isEmpty()) return ((out?.message ?: out?.code) ?: 'no reason given') as String
Map names = [:]
((Map) (doc?.refs instanceof Map ? doc.refs : [:])).values().each { Object ro ->
if (ro instanceof Map && ((Map) ro).kind == 'device' && ((Map) ro).id != null) {
Map r = ro
names.put(rtBareId(r.id), (r.name ?: r.id) as String)
}
}
List notSelected = []
List other = []
found.each { Object fo ->
Map f = fo
String m = (f.message ?: '') as String
if (f.code == 'RUNTIME_DEVICE_NOT_AUTHORISED') {
String id = names.keySet().find { m.contains("device ${it} ".toString()) } as String
notSelected << (id == null ? 'a device' : names.get(id))
} else if (m) {
other << m
}
}
List parts = []
if (!notSelected.isEmpty()) {
parts << ("${notSelected.size() == 1 ? 'This device is' : 'These devices are'} not selected for HAI Engine: " +
"${notSelected.unique().join(', ')}. Select ${notSelected.size() == 1 ? 'it' : 'them'} in HAI Engine's devices, then copy again").toString()
}
parts.addAll(other.take(3))
if (other.size() > 3) parts << "and ${other.size() - 3} more".toString()
return parts.join('; ')
}
Map migSwitch(Map row, Map plat, boolean toHai) {
String op = toHai ? 'switchOver' : 'switchBack'
if (!toHai && row.rmWasPaused == true) {
Map off = migCall(plat, 'haiRuleOp', [ruleId: row.haiId, op: 'disable'])
if (off?.ok != true) return [ok: false, code: 'MIGRATION_HAI_REFUSED', op: op, message: off?.message ?: 'The HAI copy could not be stopped.']
return [ok: true, op: op, phase: 'reverted', confirmed: true, rmWasPaused: true,
message: 'Switched back: the HAI copy is stopped. The Rule Machine rule was already paused before Switch over, so it stays paused.']
}
Map first = toHai
? migCall(plat, 'rmAction', [ids: [row.rmId], action: 'pauseRule'])
: migCall(plat, 'haiRuleOp', [ruleId: row.haiId, op: 'disable'])
if (first?.ok != true) {
return [ok: false, code: toHai ? 'MIGRATION_RM_REFUSED' : 'MIGRATION_HAI_REFUSED',
op: op, message: first?.message ?: 'The active rule could not be stopped.']
}
if (toHai) {
Map seen = migConfirmPaused(plat, row.rmId)
if (seen.paused != true) {
return [ok: false, code: 'MIGRATION_RM_NOT_CONFIRMED', op: op, confirmed: false,
message: ("Rule Machine was asked to pause ${row.rmName ?: row.rmId} and it could not be " +
"confirmed as paused${seen.why ? ': ' + seen.why : ''}. HAI was NOT started, so the " +
'Rule Machine rule is the one still running. Check it and try again.').toString()]
}
}
Map second = toHai
? migCall(plat, 'haiRuleOp', [ruleId: row.haiId, op: 'enable'])
: migCall(plat, 'rmAction', [ids: [row.rmId], action: 'resumeRule'])
if (second?.ok != true) {
return [ok: false, code: 'MIGRATION_HALF_DONE', op: op,
message: "The active rule was stopped, but its replacement did not start: ${second?.message ?: 'unknown error'}. Both copies should now be inactive; check their actual states before retrying.".toString()]
}
if (toHai) {
return [ok: true, op: op, phase: 'switched', confirmed: true, rmWasPaused: row.rmPaused == true,
message: 'Switched over: the Rule Machine rule is paused and the HAI copy is running.']
}
Map back = migConfirmPaused(plat, row.rmId, MIG_CONFIRM_TRIES, false)
boolean running = back.paused == false
return [ok: true, op: op, phase: 'reverted', confirmed: running, pending: !running,
message: running ? 'Switched back: the HAI copy is stopped and the Rule Machine rule is running again.'
: ("Switched back: the HAI copy is stopped and Rule Machine was asked to resume " +
"${row.rmName ?: row.rmId}, but it is not yet confirmed as running" +
"${back.why ? ': ' + back.why : ''}. Read the row again in a moment.").toString()]
}
Map migDeleteClone(Map row, Map plat) {
if (row.haiState == 'active') {
Map off = migCall(plat, 'haiRuleOp', [ruleId: row.haiId, op: 'disable'])
if (off?.ok != true) {
return [ok: false, code: 'MIGRATION_HAI_REFUSED', op: 'deleteClone',
message: ('The HAI copy could not be stopped, so it was not deleted' + (off?.message ? ": ${off.message}" : '.')).toString()]
}
}
Map gone = migCall(plat, 'haiRuleOp', [ruleId: row.haiId, op: 'remove'])
if (gone?.ok != true) {
return [ok: false, code: 'MIGRATION_HAI_REFUSED', op: 'deleteClone',
message: ('The HAI copy was not deleted' + (gone?.message ? ": ${gone.message}" : '.')).toString()]
}
return [ok: true, op: 'deleteClone', phase: 'none', message: 'The HAI copy is deleted. The Rule Machine rule is unchanged.']
}
Map migConfirmPaused(Map plat, Object rmId, int tries = MIG_CONFIRM_TRIES, boolean want = true) {
Closure c = (Closure) (plat ?: [:]).get('rmPausedNow')
if (c == null) return [paused: null, why: 'this build cannot read Rule Machine\'s state']
String why = null
for (int i = 0; i < Math.max(1, tries); i++) {
try {
Object r = c(rmId)
if (r == want) return [paused: want, attempts: i + 1]
if (r == null) why = 'the hub did not say'
else why = want ? 'it still reports as running' : 'it still reports as paused'
} catch (Exception e) {
why = e.message as String
}
if (i + 1 < Math.max(1, tries)) migPause(plat, MIG_CONFIRM_GAP_MS)
}
return [paused: want ? false : null, why: why, attempts: Math.max(1, tries)]
}
void migPause(Map plat, long ms) {
Closure c = (Closure) (plat ?: [:]).get('pauseFor')
if (c != null) { try { c(ms) } catch (Exception ignored) { } }
}
long migNow(Map plat) {
Closure c = (Closure) (plat ?: [:]).get('now')
if (c != null) { try { return c(null) as long } catch (Exception ignored) { } }
return new Date().getTime()
}
Map migCall(Map plat, String name, Map arg) {
Closure c = (Closure) (plat ?: [:]).get(name)
if (c == null) return [ok: false, message: "this build has no ${name}".toString()]
try {
Object r = c(arg)
return r instanceof Map ? r : [ok: true]
} catch (Exception e) {
return [ok: false, message: e.message as String]
}
}
String migrationPage(Closure download) {
byte[] b = null
try { b = (byte[]) download(MIGRATION_PAGE_FILE) } catch (Exception e) { b = null }
return migrationPageChecked(b)
}
String migrationPageChecked(byte[] b) {
if (b == null) {
return migrationPageProblem("The migration page (${MIGRATION_PAGE_FILE}) is not in the hub's File Manager. Deploying HAI Engine puts it there.")
}
String h = java.security.MessageDigest.getInstance("SHA-256").digest(b).collect { String.format('%02x', it) }.join('')
if (h != MIGRATION_PAGE_SHA256) {
return migrationPageProblem("The migration page in the hub's File Manager is not the one this version of HAI Engine was built with. Deploy HAI Engine again.")
}
return new String(b, 'UTF-8')
}
String migrationPageProblem(String why) {
return ('<!doctype html><html lang="en"><head><meta charset="utf-8"><title>HAI migration</title></head>' +
'<body style="font-family:sans-serif;padding:24px"><h1>The migration page cannot open</h1><p>' + why + '</p></body></html>').toString()
}
Map migState(List rmRows, Collection pausedIds, Map pairs, Map haiRules,
Map decode = null, Collection known = null, Long nowMs = null, Map detail = null,
Collection allowed = null, Map limits = null, Collection checkedIds = null,
Collection stoppedIds = null, Collection disabledIds = null, Collection onlyIds = null) {
List rows = migRows(rmRows, pausedIds, pairs, haiRules, decode, known, detail, allowed, limits, checkedIds, stoppedIds,
disabledIds, onlyIds)
Map prov = migProvider(decode, nowMs)
Map order = detail?.rules == null ? null : migOrder((List) detail.rules)
List notes = []
notes << prov.note
if (rows.isEmpty()) {
notes << 'No Rule Machine rules were found on this hub. If Rule Machine is installed, HAI may not be allowed to ask it for the list.'
} else {
notes << 'Open a rule to copy it into HAI. The copy is set up stopped; Switch over pauses the Rule Machine rule and starts the copy, and Switch back reverses it.'
}
int stale = rows.count { it.haiMissing } as int
if (stale > 0) {
notes << "${stale} rule${stale == 1 ? '' : 's'} here point at an HAI copy that no longer exists. Nothing was lost on the Rule Machine side.".toString()
}
List both = rows.findAll { it.live == MIG_LIVE_BOTH }
if (both) {
notes << "Both copies are running for: ${both*.rmName.join(', ')}. Two rules doing one job will both act.".toString()
}
List none = rows.findAll { it.live == MIG_LIVE_NEITHER }
if (none) {
List midSwitch = none.findAll { it.phase == 'switched' }
notes << ((midSwitch.isEmpty()
? "Neither copy is running for: ${none*.rmName.join(', ')}. Whatever those rules used to do, nothing is doing now."
: "A switch did not finish for: ${midSwitch*.rmName.join(', ')}. Rule Machine is paused and the HAI copy is not running, so nothing is doing that rule's job. Switch back to Rule Machine, or start the HAI copy.").toString())
}
if (order != null) {
Map roundOf = [:]
((List) order.layers).eachWithIndex { Object lo, int i ->
((List) lo).each { Object e -> roundOf.put(((Map) e).rmId as String, i + 1) }
}
Set looped = ((List) order.blocked).findAll { ((Map) it).inLoop == true }.collect { ((Map) it).rmId as String } as Set
Set waiting = ((List) order.blocked).findAll { ((Map) it).inLoop != true }.collect { ((Map) it).rmId as String } as Set
rows.each { Object ro ->
Map row = ro
row.round = roundOf.get(row.rmId as String)
row.inLoop = looped.contains(row.rmId as String)
row.waitsOnLoop = waiting.contains(row.rmId as String)
}
if (((List) order.layers).size() > 1 || !((List) order.blocked).isEmpty()) notes << (order.message as String)
}
Map plan = onlyIds == null ? migPlan(rows, order) : null
if (onlyIds != null) {
Set only = ((Collection) onlyIds).collect { it.toString() } as Set
rows = rows.findAll { only.contains(((Map) it).rmId as String) }
}
return [ok: true, rows: rows, notes: notes, provider: prov, order: order, plan: plan]
}
@Field static final int MIG_BATCH_MAX = 6
Map migApplyMany(List rows, String op, Map plat, int max = MIG_BATCH_MAX) {
List all = (rows ?: []) as List
int take = Math.max(1, max)
List batch = all.size() > take ? all.subList(0, take) : all
List rest = all.size() > take ? all.subList(take, all.size()) : []
List done = []
List failed = []
batch.each { Object ro ->
Map row = ro
Map r
try {
r = migApply(row, op, plat)
} catch (Exception e) {
r = [ok: false, code: 'MIGRATION_THREW', message: e.message as String]
}
Map entry = [rmId: row.rmId, rmName: row.rmName, ok: r?.ok == true,
code: r?.code, message: r?.message, phase: r?.phase, haiId: r?.haiId, heldReason: r?.heldReason, rmPrint: r?.rmPrint,
testedAt: r?.testedAt, testedBy: r?.testedBy]
if (entry.ok) done << entry else failed << entry
}
List notAttempted = rest.collect { Object ro ->
Map row = ro
return [rmId: row.rmId, rmName: row.rmName, ok: false, code: 'MIGRATION_NOT_ATTEMPTED',
message: 'This rule was not reached in this batch. Run it again to continue.']
}
boolean everything = failed.isEmpty() && notAttempted.isEmpty()
return [ok: everything, op: op,
done: done, failed: failed, notAttempted: notAttempted,
remaining: rest,
message: migBatchWords(op, done, failed, notAttempted)]
}
String migBatchWords(String op, List done, List failed, List notAttempted) {
String what = op == 'switchOver' ? 'switched to HAI' : (op == 'switchBack' ? 'switched back to Rule Machine' :
(op == 'clone' ? 'copied to HAI, not running yet' : (op == 'markTested' ? 'marked as tested' : op)))
if (failed.isEmpty() && notAttempted.isEmpty()) {
return done.isEmpty() ? 'Nothing was selected.'
: "${done.size()} rule${done.size() == 1 ? '' : 's'} ${what}.".toString()
}
List parts = []
if (!done.isEmpty()) parts << "${done.size()} ${what}: ${done*.rmName.findAll { it }.join(', ')}".toString()
if (!failed.isEmpty()) {
parts << ("${failed.size()} did not: ${failed*.rmName.findAll { it }.join(', ')}. Check those on " +
'the hub before doing anything else with them.').toString()
}
if (!notAttempted.isEmpty()) {
parts << "${notAttempted.size()} not reached yet: ${notAttempted*.rmName.findAll { it }.join(', ')}. Run it again to continue.".toString()
}
return parts.join(' ')
}
Map migOrder(List detailRules) {
List rules = (detailRules ?: []) as List
Set ids = rules.collect { String id = ((Map) it).id as String; id?.startsWith('a') ? id.substring(1) : id } as Set
Map names = [:]
rules.each { Object ro ->
Map r = ro
String id = (r.id as String)
names.put(id?.startsWith('a') ? id.substring(1) : id, (r.name as String) ?: id)
}
Map commands = [:]
Map commandedBy = [:]
ids.each { commands.put(it, [] as Set); commandedBy.put(it, 0) }
rules.each { Object ro ->
Map r = ro
String id = (r.id as String)
String me = id?.startsWith('a') ? id.substring(1) : id
((List) (r.steps ?: [])).each { Object so ->
((List) (((Map) so).ruleTargets ?: [])).each { Object t ->
String target = t as String
if (target?.startsWith('a')) target = target.substring(1)
if (target == me || !ids.contains(target)) return
if (((Set) commands[me]).add(target)) commandedBy.put(target, ((commandedBy[target] ?: 0) as int) + 1)
}
}
}
List layers = []
Set placed = [] as Set
List ready = ids.findAll { ((commandedBy[it] ?: 0) as int) == 0 }.sort()
while (!ready.isEmpty()) {
layers << ready.collect { [rmId: it, rmName: names[it]] }
placed.addAll(ready)
Set next = [] as Set
ready.each { Object x ->
((Set) commands[x]).each { Object y ->
int left = ((commandedBy[y] ?: 0) as int) - 1
commandedBy.put(y, left)
if (left == 0) next << y
}
}
ready = next.toList().sort()
}
Set unplaced = ids.findAll { !placed.contains(it) } as Set
List blocked = unplaced.toList().sort().collect { String id ->
[rmId: id, rmName: names[id], inLoop: migReachesItself(id, commands, unplaced)]
}
boolean anyCommands = commands.values().any { !((Set) it).isEmpty() }
Map links = [:]
commands.each { Object caller, Object targets ->
((Set) targets).each { Object t ->
Map a = (links[caller] ?: (links[caller] = [commands: [], commandedBy: []]))
Map b = (links[t] ?: (links[t] = [commands: [], commandedBy: []]))
((List) a.commands) << t
((List) b.commandedBy) << caller
}
}
links.values().each { Object v -> ((Map) v).commands = ((List) ((Map) v).commands).sort(); ((Map) v).commandedBy = ((List) ((Map) v).commandedBy).sort() }
return [layers: layers, blocked: blocked, links: links,
message: migOrderWords(layers, blocked, anyCommands)]
}
boolean migReachesItself(String id, Map commands, Set unplaced) {
Set seen = [] as Set
List todo = ((Set) commands[id]).findAll { unplaced.contains(it) }.toList()
while (!todo.isEmpty()) {
String x = todo.remove(todo.size() - 1) as String
if (x == id) return true
if (!seen.add(x)) continue
todo.addAll(((Set) commands[x]).findAll { unplaced.contains(it) })
}
return false
}
String migOrderWords(List layers, List blocked, boolean anyCommands) {
if (layers.isEmpty() && blocked.isEmpty()) return 'There are no rules to order.'
List parts = []
if (!anyCommands) {
parts << 'No rule on this hub commands another, so they can be migrated in any order.'
} else if (layers.size() == 1) {
int n = ((List) layers[0]).size()
parts << ("${n} rule${n == 1 ? '' : 's'} can be migrated in any order.").toString()
} else if (layers.size() > 1) {
parts << ("Migrate in ${layers.size()} rounds: ${layers.collect { ((List) it).size() }.join(', then ')} rules. " +
'Each round only depends on the ones before it, so you can stop after any of them and nothing ' +
'is left half-migrated.').toString()
}
List looped = blocked.findAll { ((Map) it).inLoop == true }
List waiting = blocked.findAll { ((Map) it).inLoop != true }
if (!looped.isEmpty()) {
parts << ("${looped.size()} rules command each other in a loop " +
"(${looped.take(4)*.rmName.join(', ')}), so no order is safe for them one at a time: " +
'whichever moves first, the other goes on commanding a paused copy.').toString()
}
if (!waiting.isEmpty()) {
parts << ("${waiting.size()} more rule${waiting.size() == 1 ? ' is' : 's are'} commanded by a rule in that loop " +
"(${waiting.take(4)*.rmName.join(', ')}), so ${waiting.size() == 1 ? 'it waits' : 'they wait'} until the loop is resolved.").toString()
}
return parts.join(' ')
}
Map migRecord(Map pairs, String rmId, String rmName, Map result, long now) {
Map all = new LinkedHashMap((pairs ?: [:]))
if (result?.op == 'deleteClone' && result.ok == true) { all.remove(rmId); return all }
Map row = new LinkedHashMap((Map) (all.get(rmId) ?: [rmId: rmId]))
row.rmName = rmName
if (result?.phase != null) row.phase = result.phase
if (result?.haiId != null) row.haiRuleId = result.haiId as String
if (result?.op == 'clone') { if (result.heldReason) row.heldReason = result.heldReason as String else row.remove('heldReason') }
if (result?.op == 'clone') { if (result.rmPrint) row.rmPrint = result.rmPrint as String else row.remove('rmPrint') }
if (result?.op == 'clone') { row.remove('testedAt'); row.remove('testedBy'); row.remove('dryRunAt'); row.remove('dryRunOk') }
if (result?.op == 'markTested' && result.testedAt != null) { row.testedAt = result.testedAt; row.testedBy = result.testedBy }
if (result?.op == 'dryRun') { row.dryRunAt = now; row.dryRunOk = result.ok == true }
if (result?.op == 'switchOver' && result.ok == true) row.rmWasPaused = result.rmWasPaused == true
if (result?.op == 'switchBack' && result.ok == true) row.remove('rmWasPaused')
row.at = now
all.put(rmId, row)
return all
}
Map migPlan(List rows, Map order) {
List all = (rows ?: []) as List
Map byId = [:]
all.each { Object ro -> byId.put(((Map) ro).rmId as String, ro) }
List rounds = []
Set placed = [] as Set
((List) (order?.layers ?: [])).eachWithIndex { Object lo, int i ->
List ids = ((List) lo).collect { ((Map) it).rmId as String }.findAll { byId.containsKey(it) }
placed.addAll(ids)
List rs = ids.collect { byId[it] }
rounds << ([round: i + 1, rmIds: ids] + migStageCounts(rs) + [prereqs: migPrereqs(rs, null)])
}
List held = ((List) (order?.blocked ?: [])).collect { ((Map) it).rmId as String }.findAll { byId.containsKey(it) }
placed.addAll(held)
List outside = all.collect { ((Map) it).rmId as String }.findAll { !placed.contains(it) }
String print = java.security.MessageDigest.getInstance('SHA-256')
.digest(groovy.json.JsonOutput.toJson([rounds*.rmIds, held]).getBytes('UTF-8')).toList().take(6)
.collect { String.format('%02x', it) }.join('')
return [rounds: rounds, held: held, outside: outside, links: (order?.links ?: [:]),
fingerprint: print, total: all.size(), stages: migStageCounts(all), prereqs: migPrereqs(all, null)]
}
Map migStageCounts(List rows) {
int copied = 0, tested = 0, switched = 0, reverted = 0
((rows ?: []) as List).each { Object ro ->
Map r = ro
boolean has = r.haiId != null && r.haiMissing != true && r.haiState != 'held'
if (!has) return
if (r.phase == 'switched') switched++
else if (r.phase == 'reverted') reverted++
else { copied++; if (r.testedAt != null) tested++ }
}
int n = ((rows ?: []) as List).size()
return [count: n, copied: copied, tested: tested, switched: switched, reverted: reverted,
untouched: n - copied - switched - reverted]
}
Map migPrereqs(List rows, Collection ids) {
Set want = ids == null ? null : ids.collect { it.toString() } as Set
Map devices = [:], variables = [:]
List mode = []
((rows ?: []) as List).each { Object ro ->
Map r = ro
if (want != null && !want.contains(r.rmId as String)) return
((List) (r.warnings ?: [])).each { Object wo ->
Map w = wo
Map into = w.kind == 'devices' ? devices : w.kind == 'variables' ? variables : null
if (w.kind == 'mode') { if (!mode.contains(r.rmName)) mode << r.rmName; return }
if (into == null) return
((List) (w.names ?: [])).each { Object n ->
List who = (into[n as String] ?: (into[n as String] = []))
if (!who.contains(r.rmName)) who << r.rmName
}
}
}
Closure items = { Map m, String fix, String at -> m.keySet().sort().collect { String k -> [name: k, rules: m[k], fix: fix, at: at] } }
List out = items(devices, 'Select it in HAI Engine\'s devices', 'engine') +
items(variables, 'Add it to Approved hub variables in HAI Engine', 'engine') +
(mode ? [[name: 'Allow rules to change the mode', rules: mode, fix: 'Turn on Allow rules to change the mode in HAI Engine', at: 'engine']] : [])
return [items: out, devices: devices.size(), variables: variables.size(), settings: mode ? 1 : 0,
blocking: devices.size() + variables.size() + (mode ? 1 : 0)]
}
Map migRoundRows(List rows, Object round, String op) {
String want = round == null ? null : round.toString()
List take = [], skip = []
((rows ?: []) as List).each { Object ro ->
Map r = ro
if (want == null || (r.round as String) != want) {
skip << [rmId: r.rmId, rmName: r.rmName, why: "Not in round ${want}.".toString()]
return
}
Map can = ((Map) (r.ops ?: migOps(r))).get(op)
if (can?.available == true) take << r
else skip << [rmId: r.rmId, rmName: r.rmName, why: (can?.why ?: 'That step is not available on this rule.') as String]
}
return [rows: take, skipped: skip]
}
@Field static final String HAM_CONTRACT = 'ham.decode/1'
@Field static final String HAM_DETAIL_CONTRACT = 'ham.decode.detail/1'
@Field static final List HAM_SCHEMAS = [1, 2, 3, 4]
@Field static final int HAM_SCHEMA = 3
@Field static final int HAM_VOCABULARY = 2
String hamFileFor(String channel) {
return channel == 'production' ? 'ham-decode.json' : 'ham-decode-dev.json'
}
String hamDetailFileFor(String channel) {
return channel == 'production' ? 'ham-decode-detail.json' : 'ham-decode-detail-dev.json'
}
Map hamDecode(Map plat, String channel) {
return hamRead(plat, channel, HAM_CONTRACT, hamFileFor(channel))
}
Map hamDetail(Map plat, String channel) {
return hamRead(plat, channel, HAM_DETAIL_CONTRACT, hamDetailFileFor(channel))
}
boolean hamHeaderReadable(Map h, String channel, boolean detail) {
if (h == null) return false
Map inst = (h.instance instanceof Map ? h.instance : [:])
Map scan = (h.scan instanceof Map ? h.scan : [:])
return h.contract == (detail ? HAM_DETAIL_CONTRACT : HAM_CONTRACT) &&
HAM_SCHEMAS.contains(h.contractSchemaVersion as Integer) &&
((h.rmConstructVocabularyVersion as Integer) in [1, HAM_VOCABULARY]) &&
(inst.buildChannel == null || channel == null || inst.buildChannel == channel) &&
h.ok != false && scan.status != 'failed'
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
Object hamSettled(Object o) {
if (o instanceof Map) {
Map out = new LinkedHashMap(((Map) o).size())
((Map) o).each { Object k, Object v -> out.put(k, hamSettled(v)) }
return out
}
if (o instanceof List) {
List out = new ArrayList(((List) o).size())
((List) o).each { Object v -> out.add(hamSettled(v)) }
return out
}
return o
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
Map hamCoverage(Map rule, Collection known) {
if (rule == null) return [verdict: 'unassessed', reasons: ['Automation Map holds nothing for this rule.']]
if (known == null) {
return [verdict: 'unassessed', missing: [],
reasons: ['This build of HAI cannot yet translate Rule Machine rules, so no rule has been assessed.']]
}
Set have = known as Set
List reasons = []
String engine = rule.engine as String
if (engine != null && engine != 'Rule-5.1') {
return [verdict: 'unassessed', missing: [],
reasons: ["This rule is ${engine}. Only Rule Machine 5.1 can be read, so it has not been looked at.".toString()]]
}
if (rule.hasDecodedFlow != true) {
return [verdict: 'blocked', missing: [],
reasons: ['Automation Map read this rule but could not decode what it does.']]
}
List missing = ((List) (rule.constructs ?: [])).findAll { !have.contains(it) }.sort()
if (!missing.isEmpty()) {
reasons << ("HAI has no mapping for ${missing.join(', ')}. The step is left out rather than guessed, " +
'so the rule would be incomplete.').toString()
return [verdict: 'needs-decision', missing: missing, reasons: reasons]
}
if (rule.status == 'paused') reasons << 'This rule is paused in Rule Machine, so it is not running now.'
if (rule.status == 'inert') reasons << 'Rule Machine holds this rule but nothing can trigger it.'
reasons << 'Every construct in this rule has a mapping in HAI.'
return [verdict: 'ready', missing: [], reasons: reasons]
}
@Field static final Map HAM_HARD = [
waiting: ['action:getDelay', 'action:getWaitEvents', 'action:getWaitRule', 'structure:actionDelay'],
shared : ['action:getSetVariable', 'condition:Variable', 'trigger:Variable',
'action:getSetPrivateBoolean', 'condition:Private Boolean'],
reaches: ['action:getRuleActions', 'action:getPauseResumeRules', 'action:getStopActions'],
branch : ['action:getIfThen', 'action:getElseIf', 'action:getElse'],
capture: ['action:getCapture', 'action:getRestore'],
]
@Field static final Map HAM_WEIGHT = [waiting: 3, shared: 3, reaches: 2, branch: 1, capture: 2]
@Field static final Map HAM_DRIVER_WHY = [
waiting: 'waits or delays, which hold state between runs',
shared : 'shares variables or private booleans with other rules',
reaches: 'starts, stops or pauses other rules',
branch : 'branches, so there is more than one path through it',
capture: 'captures and restores device state',
]
Map hamComplexity(Map rule, Collection known = null) {
if (rule == null || rule.hasDecodedFlow != true) {
return [score: null, band: 'unknown', drivers: [],
reasons: ['There is no decoded flow for this rule, so its difficulty is not known.']]
}
Set cons = ((Collection) (rule.constructs ?: [])) as Set
int score = 0
List drivers = []
List reasons = []
HAM_HARD.each { String key, List tokens ->
if (tokens.any { cons.contains(it) }) {
score += (HAM_WEIGHT[key] as int)
drivers << key
reasons << ("This rule ${HAM_DRIVER_WHY[key]}.".toString())
}
}
int steps = (rule.stepCount ?: 0) as int
if (steps >= 20) {
score += 2
reasons << "It is ${steps} steps long.".toString()
} else if (steps >= 10) {
score += 1
}
if (known != null) {
Set have = known as Set
List unmapped = ((List) (rule.constructs ?: [])).findAll { !have.contains(it) }.sort()
if (!unmapped.isEmpty()) {
score += unmapped.size()
reasons << ("HAI has no mapping for ${unmapped.size()} of its constructs, so part of it cannot be rebuilt.".toString())
}
}
String band = drivers.size() >= 3 ? 'involved' : (drivers.isEmpty() ? 'simple' : 'moderate')
if (reasons.isEmpty()) reasons << 'Nothing in this rule holds state or branches; it is a direct rebuild.'
return [score: score, band: band, drivers: drivers, reasons: reasons]
}
Map hamNo(String code, String message, Map extra = [:]) {
Map m = [ok: false, code: code, message: message]
m.putAll(extra ?: [:])
return m
}
@Field static final String RM_EVAL_WALK = 'left-to-right-short-circuit'
@Field static final String RM_HAI_SCHEMA = 'hai.rule/1'
@Field static final String RM_HUB_VARIABLE_PREFIX = 'hv:'
@Field static final String RM_EVENT_DEVICE_KEY = 'eventDevice'
@Field static final Map RM_DAYS = [
monday: 'mon', tuesday: 'tue', wednesday: 'wed', thursday: 'thu',
friday: 'fri', saturday: 'sat', sunday: 'sun',
]
@Field static final Map RM_TRIG_VALUES = [
'HSM Status': ['armedAway', 'armingAway', 'armedHome', 'armingHome', 'armedNight', 'armingNight', 'disarmed', 'allDisarmed'],
'HSM Alert': ['intrusion', 'intrusion-delay', 'intrusion-home', 'intrusion-home-delay', 'intrusion-night',
'intrusion-night-delay', 'smoke', 'water', 'rule', 'arming', 'cancel'],
'Switch': ['on', 'off'], 'Contact': ['open', 'closed'], 'Motion': ['active', 'inactive'],
'Presence': ['present', 'not present'], 'Lock': ['locked', 'unlocked'], 'Water': ['wet', 'dry'],
'Smoke': ['clear', 'detected', 'tested'], 'Door': ['open', 'closed', 'opening', 'closing', 'unknown'],
'Garage door': ['open', 'closed', 'opening', 'closing', 'unknown'],
]
@Field static final Map RM_CAPABILITY_ATTR = [
'Contact': 'contact', 'Motion': 'motion', 'Door': 'door', 'Switch': 'switch', 'Lock': 'lock',
'Presence': 'presence', 'Water': 'water', 'Smoke': 'smoke', 'Illuminance': 'illuminance',
'Temperature': 'temperature', 'Humidity': 'humidity', 'Power': 'power', 'Battery': 'battery',
'Window Shade': 'windowShade', 'Dimmer': 'level', 'Fan Speed': 'speed',
'Thermostat mode': 'thermostatMode', 'Thermostat state': 'thermostatOperatingState',
'Thermostat heat setpoint': 'heatingSetpoint', 'Thermostat cool setpoint': 'coolingSetpoint',
'Thermostat fan mode': 'thermostatFanMode', 'Water sensor': 'water', 'Smoke detector': 'smoke',
'Carbon monoxide detector': 'carbonMonoxide', 'Carbon dioxide sensor': 'carbonDioxide',
'Acceleration': 'acceleration', 'Energy meter': 'energy', 'Power meter': 'power',
'Power source': 'powerSource', 'Tamper alert': 'tamper',
]
@Field static final Set RM_ACT_METHODS = ['getOnOffSwitch', 'getDelay', 'getSetPrivateBoolean',
'getMsg', 'getLogMsg', 'getDefinedAction',
'getCapture', 'getRestore', 'getSetColor', 'getSetVariable',
'getSetColorTemp', 'getSetVolume',
'getRuleActions', 'getStopActions', 'getPauseResumeRules',
'getSetDimmer', 'getAdjustDimmer', 'getFlashSwitch',
'getRefreshSwitch', 'getPollSwitch', 'getChime', 'getSetMode',
'getComment', 'getHTTPPost', 'getOCGarage',
'getToggleSwitch', 'getToggleDimmer', 'getStopDimmer',
'getShadePosition', 'getAdjustFan', 'getExitRule', 'getStopRepeat',
'getHTTPGet', 'getPingIP', 'getWriteLocalFile', 'getAppendLocalFile',
'getDeleteLocalFile',
'getRLDimmer', 'getRLShade', 'getStopShade', 'getLULock',
'getMuteUnmute', 'getFanSpeed', 'getPushButton', 'getFadeDimmer',
'getFadeCT', 'getToggleColor', 'getSetThermostat', 'getModeSwitch',
'getChooseSwitch', 'getDimmersPerMode', 'getColorPerMode',
'getDelayPerMode', 'getOCValve', 'getSetHSM',
'getPushButtonPerMode', 'getChooseButton', 'getToggleColorTemp',
'getCancelDelay', 'getColorTempPerMode', 'getSetMusicPlayer', 'getSiren',
'getStopFade', 'getStopCTFade', 'getDisable', 'getActivateScene', 'getActivateScenePerMode'] as Set
@Field static final Set RM_ASSEMBLED_METHODS = ['getWaitRule', 'getWaitEvents', 'getRepeat', 'getEndRepeat', 'getWhile', 'getUntil'] as Set
@Field static final Set RM_BRANCH_METHODS = ['getIfThen', 'getElseIf', 'getElse', 'getEndIf'] as Set
@Field static final Set RM_COND_SPECIAL = ['Between two times', 'Time of day', 'Between two dates',
'On a Day', 'Days of week', 'Days of Week', 'Mode', 'Variable', 'Private Boolean',
'Custom Attribute'] as Set
@Field static final Set RM_NO_BEHAVIOUR = ['option:logging', 'option:displayCurrentValues'] as Set
Set rmVocabulary() {
Set v = [] as Set
RM_ACT_METHODS.each { v << ('action:' + it).toString() }
RM_BRANCH_METHODS.each { v << ('action:' + it).toString() }
RM_ASSEMBLED_METHODS.each { v << ('action:' + it).toString() }
RM_COND_SPECIAL.each { v << ('condition:' + it).toString() }
RM_CAPABILITY_ATTR.keySet().each { v << ('condition:' + it).toString() }
rmTrigAttr().keySet().each { v << ('trigger:' + it).toString() }
RM_TRIG_OTHER.each { v << ('trigger:' + it).toString() }
v << 'trigger:Certain Time (and optional date)'
v << 'trigger:Certain Time'
LOCATION_EVENTS.each { v << ('trigger:Location Event:' + it).toString() }
v << 'structure:actionDelay'
v << 'structure:conditionalTrigger'
v.addAll(RM_NO_BEHAVIOUR)
return v
}
Map rmDecodeTooOld(Map detail) {
Integer schema = detail?.schema as Integer
if (schema == null || schema >= 3) return null
return rmNo('RM_DECODE_TOO_OLD',
("Automation Map's decode is contract ${schema}, which does not say whether a trigger only fires " +
'while a condition holds. Update Automation Map to 2.4.9 or later and rescan before copying a rule.').toString())
}
Map rmRebuild(Map detailRule, List allRules = null, Map hubVariableTypes = null, Collection upgrades = null,
Map localVariables = null) {
Map unread = rmUnreadable(detailRule)
if (unread != null) return unread
Map t = rmTriggers(detailRule, allRules)
if (t.ok != true) return t
Map c = rmConditions(detailRule)
if (c.ok != true) return c
Map a = rmActions(detailRule, allRules)
if (a.ok != true) return a
String rmId = rmBareId(detailRule.id)
Map doc = [schema : RM_HAI_SCHEMA,
id : ('rm:' + rmId).toString(),
name : rmRebuiltName(detailRule.name as String),
triggers: t.triggers,
actions : a.steps]
if (c.required != null) doc.requiredExpression = c.required
Map loc = rmLocaliseVariables(doc, hubVariableTypes, localVariables)
if (loc.ok != true) return loc
doc.refs = rmRefsFor(doc, detailRule, hubVariableTypes, loc.locals)
rmTypeVariableValues(doc)
List behaviour = rmFaithful(doc, upgrades)
if (allRules != null && !allRules.isEmpty()) {
Set live = (allRules.collect { rmBareId(((Map) it).id) } as Set)
List dead = ((Map) doc.refs).keySet().findAll { String k ->
k.startsWith('rm:') && !live.contains(k.substring(3))
}.collect { ((String) it).substring(3) }.sort()
if (!dead.isEmpty()) {
return rmNo('RM_RULE_TARGET_GONE',
("This rule commands ${dead.size() == 1 ? 'a rule' : 'rules'} that no longer " +
"exist${dead.size() == 1 ? 's' : ''} on this hub (${dead.join(', ')}). Rule " +
'Machine keeps the reference after a rule is deleted and quietly does nothing ' +
'for it. HAI will not rebuild a rule around a step that cannot work - remove it ' +
'in Rule Machine first.').toString())
}
}
rmSafeRefKeys(doc)
Map out = [ok: true, doc: doc, skipped: a.skipped, invoked: t.invoked == true, rmId: rmId, behaviour: behaviour]
if (doc.hubPower != null) {
out.hubPower = doc.remove('hubPower')
out.hubPowerChosen = ((upgrades ?: []) as List).collect { it as String }.contains('hubPower')
}
return out
}
void rmSafeRefKeys(Map doc) {
Map refs = (doc.refs ?: [:])
Map rename = [:]
refs.keySet().each { Object k ->
String key = k as String
if (!(key ==~ /[A-Za-z][A-Za-z0-9_]*/)) {
String safe = key.replaceAll(/[^A-Za-z0-9_]/, '_')
if (!(safe ==~ /[A-Za-z].*/)) safe = 'r_' + safe
rename[key] = safe
}
}
if (rename.isEmpty()) return
Map out = [:]
refs.each { k, v -> out[(rename[k as String] ?: k) as String] = v }
Closure walk
walk = { Object node ->
if (node instanceof Map) {
Map m = node
m.keySet().toList().each { Object f ->
Object v = m[f]
if (v instanceof String && rename.containsKey(v)) m[f] = rename[v]
else if (v instanceof List) m[f] = ((List) v).collect { Object r -> (r instanceof String && rename.containsKey(r)) ? rename[r] : r }
walk(m[f])
}
} else if (node instanceof List) {
((List) node).each { walk(it) }
}
}
doc.remove('refs')
walk(doc)
doc.refs = out
}
@Field static final String RM_LOCK_CODE_ATTR = 'lastCodeName'
@Field static final Set RM_UPGRADES = ['restart', 'changeOnly', 'stopOnUnknown', 'hubPower'] as Set
List rmFaithful(Map doc, Collection upgrades = null) {
Set chosen = ((upgrades ?: []) as List).collect { it as String }.findAll { RM_UPGRADES.contains(it) } as Set
List lines = []
boolean delays = false
boolean waits = false
boolean anyIf = false
rmEachStep((List) doc.actions) { Map st ->
if (st.type == 'wait' && st.waitKind == 'delay') delays = true
if (st.type == 'wait' && st.waitKind in ['forEvents', 'until']) waits = true
if (st.type == 'if') anyIf = true
}
List power = []
rmEachStep((List) doc.actions) { Map st ->
if (st.type == 'http' && schemaHubMaintenance(st.url)) power << (st.url as String)
}
if (!power.isEmpty()) {
boolean ok = chosen.contains('hubPower')
String low = power.join(' ').toLowerCase()
String does = low.contains('shutdown') ? 'shuts down' : (low.contains('reboot') || low.contains('restart')) ? 'reboots'
: 'runs a maintenance page on'
doc.hubPower = power
lines << [key: 'hubPower', differs: false, warn: !ok,
text: ok
? "You chose to copy it: like the Rule Machine rule, this copy ${does} your hub (${power[0]}).".toString()
: ("Warning: this rule ${does} your hub (it sends ${power[0]}). Tick \"Copy it anyway\" below " +
'if you want the HAI copy to do the same; without the tick, Copy leaves this rule alone.').toString(),
upgrade: [key: 'hubPower', chosen: ok,
label: "Copy it anyway, including the step that ${does} the hub".toString(),
tip: 'The HAI copy sends this request to your hub whenever the rule reaches that step, exactly as ' +
'the Rule Machine rule does. A restart or shutdown stops everything on the hub, so only tick ' +
'this if that is what you want.']]
}
if (delays && !waits) {
if (chosen.contains('restart')) {
doc.rerunPolicy = 'restart'
} else {
doc.rerunPolicy = 'parallel'
doc.parallelLimit = LIMIT_PARALLEL_RUNS
}
lines << [key: 'rerun', differs: chosen.contains('restart'),
text: chosen.contains('restart')
? 'Upgraded: a new trigger restarts the rule, so only the latest one counts. Rule Machine would run it again for each trigger.'
: ("As in Rule Machine, each trigger runs the rule again, its delays included, up to ${LIMIT_PARALLEL_RUNS} runs at once.").toString(),
upgrade: [key: 'restart', chosen: chosen.contains('restart'),
label: 'Restart on a new trigger, so only the latest one counts',
tip: 'Rule Machine starts the rule again on every trigger, so a sensor that triggers three times ' +
'during a delay runs the rest of the rule three times. With this upgrade a new trigger ' +
'cancels the run in progress and starts again, so the rule finishes once, from the latest ' +
'trigger. Usually what a motion or door rule means. Leave it off if each trigger must count.']]
} else if (waits) {
doc.rerunPolicy = 'restart'
lines << (delays
? [key: 'rerun', differs: true,
text: 'Differs from Rule Machine on a re-trigger: Rule Machine cancels a wait in progress but lets a delay finish; this copy cancels both and starts again.']
: [key: 'rerun', differs: false,
text: 'As in Rule Machine, a new trigger cancels a wait in progress and starts again.'])
}
List devTrigs = ((List) (doc.triggers ?: [])).findAll { ((Map) it).kind == 'deviceAttribute' }
if (!devTrigs.isEmpty()) {
boolean onChange = chosen.contains('changeOnly')
devTrigs.each { Object tr -> if (onChange) ((Map) tr).remove('discipline') else ((Map) tr).discipline = 'event' }
String what = devTrigs.size() == 1 ? 'its device trigger' : "its ${devTrigs.size()} device triggers"
lines << [key: 'reports', differs: onChange,
text: onChange
? "Upgraded: ${what} start the rule only when a reading changes, not when a device repeats it. Rule Machine starts on every report.".toString()
: "As in Rule Machine, ${what} start the rule on every report, including a device repeating the same reading.".toString(),
upgrade: [key: 'changeOnly', chosen: onChange,
label: 'Only on a real change, ignoring a device that repeats itself',
tip: 'Some devices report the same reading again: a motion sensor saying active while it is ' +
'already active, a thermometer every minute. Rule Machine starts the rule each time. With ' +
'this upgrade it starts only when the reading changes. Leave it off if the repeats matter, ' +
'for example a motion sensor that keeps a light on by re-reporting active.']]
}
if (anyIf) {
boolean stop = chosen.contains('stopOnUnknown')
doc.ifUnknown = stop ? 'stop' : 'otherwise'
if (stop) rmEachStep((List) doc.actions) { Map st ->
if (st.type != 'if') return
rmKnownOnly(st.condition)
((List) (st.elseIf ?: [])).each { Object b -> if (b instanceof Map) rmKnownOnly(((Map) b).condition) }
}
lines << [key: 'unknown', differs: stop,
text: stop
? 'Upgraded: if a reading in an IF cannot be read, this copy stops the run and records why. Rule Machine would treat it as not met and run the ELSE.'
: 'As in Rule Machine, if a reading in an IF cannot be read, its comparison counts as false: NOT of it is true, and otherwise the next ELSE-IF or the ELSE runs.',
upgrade: [key: 'stopOnUnknown', chosen: stop,
label: 'Stop instead of running the ELSE when a reading is missing',
tip: 'When a device in an IF cannot be read (offline, or never reported), Rule Machine treats ' +
'the condition as not met and runs the ELSE. That is right when the ELSE is a safety ' +
'catch, such as turning a heater off. It is wrong when the ELSE claims something is true, ' +
'such as announcing all clear, because nobody knows. With this upgrade the run stops ' +
'there instead and records why. Leave it off if the ELSE is a safety catch.']]
}
return lines
}
void rmKnownOnly(Object node) {
if (!(node instanceof Map)) return
Map n = node
n.remove('unknownAs')
if (n.of instanceof List) ((List) n.of).each { rmKnownOnly(it) } else rmKnownOnly(n.of)
}
void rmEachStep(List steps, Closure visit) {
(steps ?: []).each { Object o ->
if (!(o instanceof Map)) return
Map st = o
visit(st)
st.each { Object k, Object v ->
if (!(v instanceof List)) return
((List) v).each { Object x ->
if (!(x instanceof Map)) return
if (((Map) x).containsKey('type')) rmEachStep([x], visit)
else ((Map) x).values().findAll { it instanceof List }.each { rmEachStep((List) it, visit) }
}
}
}
}
String rmRebuiltName(String rmName) {
String base = (rmName ?: 'Rule').replaceAll(/\s*\((?:Rule-[\d.]+|child of Rule Machine|runs on a schedule[^)]*|could not be read)\)\s*$/, '').trim()
if (base.isEmpty()) base = 'Rule'
return (base + ' (from Rule Machine)').take(80)
}
Map rmRefsFor(Map doc, Map detailRule, Map hubVariableTypes = null, Map locals = null) {
Set keys = [] as Set
rmCollectRefs(doc, keys)
Map needs = rmCapabilitiesNeeded(doc)
Map types = rmVariableTypes(doc)
Map names = rmDevicesNamed(detailRule)
Map out = [:]
keys.sort().each { Object ko ->
String k = ko as String
if (k.startsWith('dev:')) {
String bare = k.substring(4)
String name = (names[bare] ?: names[k]) as String
Map entry = [kind: 'device', id: k, name: name ?: k]
Set caps = (Set) (needs[k] ?: ([] as Set))
if (!caps.isEmpty()) entry.capabilities = caps.sort()
out.put(k, entry)
} else if (k.startsWith(RM_HUB_VARIABLE_PREFIX)) {
Map e = [kind: 'variable', id: k, name: k.substring(RM_HUB_VARIABLE_PREFIX.length())]
String hubType = hubVariableTypes?.get(e.name) as String
e.type = (hubType != null ? HUB_VARIABLE_TYPES.get(hubType.toLowerCase()) : null) ?: (types[k] ?: 'string')
out.put(k, e)
} else if (k.startsWith('rm:')) {
out.put(k, [kind: 'rmRule', id: k.substring(3), name: k])
} else if (k == 'self') {
out.put(k, [kind: 'self'])
} else if (k == RM_EVENT_DEVICE_KEY) {
out.put(k, [kind: 'eventDevice'])
} else if (k == PRIVATE_BOOLEAN_ID) {
out.put(k, [kind: 'variable', id: k, name: k.substring(3), scope: 'rule', type: 'boolean', initial: true])
} else if (k.startsWith(RULE_VARIABLE_PREFIX)) {
Map lv = locals?.get(k)
Map e = [kind: 'variable', id: k, name: k.substring(RULE_VARIABLE_PREFIX.length()), scope: 'rule',
type: (lv?.type ?: types[k] ?: 'string')]
if (lv != null && lv.containsKey('initial')) e.initial = lv.initial
out.put(k, e)
}
}
return out
}
@Field static final Map RM_LOCAL_VARIABLE_TYPES = [integer: 'number', bigdecimal: 'number', decimal: 'number', number: 'number',
boolean: 'boolean', string: 'string', datetime: 'datetime',
date: 'date', time: 'time']
Map rmLocaliseVariables(Map doc, Map hubVariableTypes, Map localVariables) {
boolean hubKnown = hubVariableTypes != null && !hubVariableTypes.isEmpty()
if (localVariables == null && !hubKnown) return [ok: true, locals: [:]]
Set used = [] as Set
Closure isTriggerVariable = { Map m -> m.kind == 'hubVariable' }
Closure collect
collect = { Object node ->
if (node instanceof List) { ((List) node).each { collect(it) }; return }
if (!(node instanceof Map)) return
Map m = node
m.each { Object k, Object v ->
if (k == 'ref' && v instanceof String && ((String) v).startsWith(RM_HUB_VARIABLE_PREFIX)) {
if (!isTriggerVariable(m)) used << ((String) v).substring(RM_HUB_VARIABLE_PREFIX.length())
} else collect(v)
}
}
collect(doc)
Map locals = [:]
for (Object o : used.sort()) {
String name = o as String
boolean inHub = hubKnown && hubVariableTypes.containsKey(name)
boolean local
if (localVariables != null) {
local = localVariables.containsKey(name)
if (local && inHub) {
return rmNo('RM_LOCAL_VARIABLE',
("Rule Machine local variable ${name} has the same name as a hub variable, and Rule Machine " +
"stores both by name, so HAI cannot tell which one this rule means. Rename one of them in " +
'Rule Machine and copy the rule again.').toString())
}
} else local = !inHub
if (!local) continue
String id = (RULE_VARIABLE_PREFIX + name).toString()
if (id == PRIVATE_BOOLEAN_ID) {
return rmNo('RM_LOCAL_VARIABLE',
("Rule Machine local variable ${name} has the name HAI gives every rule's private boolean, so " +
'the copy would mix the two up. Rename it in Rule Machine and copy the rule again.').toString())
}
Map lv = [:]
Object meta = localVariables?.get(name)
if (meta instanceof Map) {
String rmType = ((((Map) meta).type ?: '') as String).toLowerCase()
String type = RM_LOCAL_VARIABLE_TYPES.get(rmType) as String
if (type != null) {
lv.type = type
Object initial = rmLocalInitial(type, ((Map) meta).value)
if (initial != null) lv.initial = initial
}
}
locals.put(id, lv)
}
if (locals.isEmpty()) return [ok: true, locals: locals]
Closure rename
rename = { Object node ->
if (node instanceof List) { ((List) node).each { rename(it) }; return }
if (!(node instanceof Map)) return
Map m = node
Object r = m.ref
if (r instanceof String && ((String) r).startsWith(RM_HUB_VARIABLE_PREFIX) && !isTriggerVariable(m)) {
String id = (RULE_VARIABLE_PREFIX + ((String) r).substring(RM_HUB_VARIABLE_PREFIX.length())).toString()
if (locals.containsKey(id)) m.ref = id
}
m.values().each { rename(it) }
}
rename(doc)
return [ok: true, locals: locals]
}
Map rmLocalVariablesFrom(Object status) {
Object rows = status instanceof Map ? ((Map) status).appState : null
if (!(rows instanceof List)) return null
Map row = ((List) rows).find { it instanceof Map && ((Map) it).name == 'allLocalVars' }
Object v = row?.value
if (v instanceof String) {
try { v = new groovy.json.JsonSlurper().parseText((String) v) } catch (Exception e) { return null }
}
if (v == null) return [:]
return v instanceof Map ? v : null
}
Object rmLocalInitial(String type, Object v) {
if (v == null) return null
switch (type) {
case 'number':
if (v instanceof Number) return v
String t = (v as String).trim()
if (!(t ==~ /-?\d+(\.\d+)?/)) return null
return t.contains('.') ? new BigDecimal(t) : (t as Long)
case 'boolean':
if (v instanceof Boolean) return v
return (v as String) in ['true', 'false'] ? ((v as String) == 'true') : null
default:
return (v instanceof String || v instanceof Number) ? v.toString() : null
}
}
void rmTypeVariableValues(Map doc) {
Map refs = (doc.refs ?: [:])
Closure walk
walk = { Object node ->
if (node instanceof Map) {
Map n = node
if (n.type == 'variable' && n.ref instanceof String && n.value instanceof String) {
String t = ((Map) refs.get(n.ref))?.type as String
String v = ((String) n.value).trim()
if (t == 'boolean' && v in ['true', 'false']) n.value = (v == 'true')
else if (t == 'number' && v ==~ /-?\d+(\.\d+)?/) n.value = (v.contains('.') ? new BigDecimal(v) : (v as Long))
}
n.values().each { walk(it) }
return
}
if (node instanceof List) ((List) node).each { walk(it) }
}
walk(doc.triggers)
walk(doc.requiredExpression)
walk(doc.actions)
}
Map rmCapabilitiesNeeded(Map doc) {
Map needs = [:]
((List) (doc.triggers ?: [])).each { Object to ->
Map t = to
if (t.kind != 'button' || !(t.ref instanceof String)) return
String cap = BUTTON_CAPABILITY.get((t.action ?: 'pushed') as String) as String
if (cap == null) return
Set set = (Set) needs.get(t.ref)
if (set == null) { set = [] as Set; needs.put(t.ref, set) }
set << cap
}
rmWalkSteps((List) (doc.actions ?: [])) { Map a ->
if (a.type != 'command' || !(a.command instanceof String)) return
Map op = schemaOperation(a.command as String)
if (op == null) return
List refs = ((a.ref != null ? [a.ref] : []) + (a.refs instanceof List ? a.refs : []))
refs.each { Object key ->
Set set = (Set) needs.get(key)
if (set == null) { set = [] as Set; needs.put(key, set) }
set.addAll((List) (op.requires ?: []))
}
}
return needs
}
void rmWalkSteps(List steps, Closure each) {
(steps ?: []).each { Object o ->
if (!(o instanceof Map)) return
Map a = o
each(a)
if (a.then instanceof List) rmWalkSteps((List) a.then, each)
if (a.otherwise instanceof List) rmWalkSteps((List) a.otherwise, each)
if (a.elseIf instanceof List) {
((List) a.elseIf).each { Object b ->
if (b instanceof Map && ((Map) b).then instanceof List) rmWalkSteps((List) ((Map) b).then, each)
}
}
}
}
Map rmVariableTypes(Map doc) {
Map out = [:]
Closure look
look = { Object node ->
if (node instanceof Map) {
Map n = node
if (n.type == 'variable' && n.ref instanceof String && n.containsKey('value')) {
Object v = n.value
String t = v instanceof Boolean ? 'boolean' : (v instanceof Number ? 'number' : 'string')
out.put(n.ref, t)
}
if (n.type == 'setVariable' && n.ref instanceof String && !out.containsKey(n.ref)) {
String t = n.containsKey('value')
? (n.value instanceof Boolean ? 'boolean' : (n.value instanceof Number ? 'number' : 'string'))
: (n.op in ['add', 'math'] ? 'number' : null)
if (t != null) out.put(n.ref, t)
}
n.each { Object k, Object v -> look(v) }
return
}
if (node instanceof List) ((List) node).each { look(it) }
}
look(doc)
return out
}
void rmCollectRefs(Object node, Set out) {
if (node instanceof Map) {
((Map) node).each { Object k, Object v ->
if ((k == 'refs' || k == 'rules') && v instanceof List) {
((List) v).each { if (it instanceof String) out << it }
} else if (k == 'ref' && v instanceof String) {
out << v
} else if (k != 'refs' && k != 'rules') {
rmCollectRefs(v, out)
}
}
return
}
if (node instanceof List) ((List) node).each { rmCollectRefs(it, out) }
}
Map rmTrigAttr() {
return RM_CAPABILITY_ATTR + ['Garage door': 'door', 'Valve': 'valve', 'Music player': 'status',
'Security keypad': 'securityKeypad',
'Physical Switch': 'switch', 'Digital Switch': 'switch',
'Physical Lock': 'lock', 'Physical dimmer level': 'level']
}
@Field static final Map RM_TRIG_EVENT_TYPE = ['Physical Switch': 'physical', 'Digital Switch': 'digital',
'Physical Lock': 'physical', 'Physical dimmer level': 'physical']
@Field static final Set RM_TRIG_OTHER = ['Button', 'Mode', 'Variable', 'Periodic Schedule',
'Location Event', 'Custom Attribute', 'HSM Status', 'HSM Alert',
'Local End Point', 'Cloud End Point', 'Rule paused'] as Set
@Field static final Map RM_TRIG_THRESHOLD = ['>': 'above', '<': 'below', '>=': 'atLeast', '<=': 'atMost',
'\u2265': 'atLeast', '\u2264': 'atMost']
Map rmTriggers(Map detailRule, List allRules = null) {
Map unread = rmUnreadable(detailRule)
if (unread != null) return unread
if (detailRule == null) return rmNo('RM_RULE_MISSING', 'Automation Map holds no detail for this rule.')
List trigs = (detailRule.triggers instanceof List) ? detailRule.triggers : []
if (trigs.isEmpty()) {
return [ok: true, triggers: [[kind: 'ruleInvocation']], count: 0, invoked: true,
calledByRule: rmIsCalledByAnother(detailRule, allRules)]
return rmNo('RM_TRIG_NONE',
((allRules == null
? 'Automation Map holds no triggers for this rule, and HAI was not given the other rules to check whether one of them runs it. '
: 'Automation Map holds no triggers for this rule and no other rule runs it. ') +
'A rule that nothing starts looks complete and never fires.').toString())
}
List out = []
for (Object o : trigs) {
Map r = rmTrigger((Map) o)
if (r.ok != true) return r
Map gate = rmTriggerCondition((Map) o, detailRule)
if (gate != null && gate.ok != true) return gate
List made = r.triggers
if (gate != null) made = made.collect { Object tr ->
Map t = tr
t + [condition: t.condition != null ? [type: 'all', of: [t.condition, gate.node]] : gate.node]
}
out.addAll(made)
}
if (out.isEmpty()) {
return [ok: true, triggers: [[kind: 'ruleInvocation']], count: 0, invoked: true,
calledByRule: rmIsCalledByAnother(detailRule, allRules)]
}
return [ok: true, triggers: out, count: trigs.size()]
}
Map rmTriggerCondition(Map trig, Map detailRule) {
Map ops = (trig.operands ?: [:])
if (ops.conditionMissing == true) {
return rmNo('RM_TRIG_CONDITION_MISSING',
'This trigger is marked conditional in Rule Machine, and Automation Map found no condition for it.',
trig.index)
}
if (ops.condition == null) return null
String idx = ops.condition.toString()
List conds = (detailRule.conditions instanceof List) ? detailRule.conditions : []
Map c = conds.find { Object x -> (((Map) x).index as Object)?.toString() == idx }
if (c == null) {
return rmNo('RM_TRIG_CONDITION_GONE',
"This trigger only fires while condition ${idx} holds, and the rule has no condition ${idx}.".toString(),
trig.index)
}
if ((c.capability as String) == 'Mode' && ((List) (((Map) (c.operands ?: [:])).modes ?: [])).isEmpty() &&
((List) (((Map) (c.operands ?: [:])).ids ?: [])).isEmpty()) {
return null
}
Map t = rmCondition(c)
if (t.ok != true) return t
return [ok: true, node: t.node]
}
Map rmUnreadable(Map detailRule) {
if (detailRule == null) return null
String status = detailRule.status as String
if (status == 'unreadable') {
return rmNo('RM_RULE_UNREADABLE',
('Automation Map could not fully read this rule, so part of it is missing and it does ' +
'not say which part. On this hub that is the trigger, and a rule rebuilt without one ' +
'runs at the wrong times or not at all.'))
}
Map partial = rmDecodeFailed(detailRule)
if (partial != null) return partial
if (detailRule.hasDecodedFlow == false) {
boolean empty = ((List) (detailRule.conditions ?: [])).isEmpty() &&
((List) (detailRule.actions ?: [])).isEmpty() &&
((Map) (detailRule.decodeFailures ?: [:])).isEmpty()
if (empty) {
return rmNo('RM_RULE_EMPTY',
('Nothing to copy: this rule has no actions in Rule Machine, so a copy would do nothing (it looks like a draft). ' +
'Finish it in Rule Machine and scan again, or delete it there if it is not needed.'))
}
return rmNo('RM_RULE_UNDECODED',
'Automation Map read this rule but could not work out what it does, so there is nothing to rebuild from.')
}
return null
}
@Field static final Map RM_DECODE_SECTIONS = [triggers: 'triggers', conditions: 'conditions',
expressions: 'condition logic (the IF and Required Expressions)',
actions: 'actions']
Map rmDecodeFailed(Map detailRule) {
Map fails = (detailRule?.decodeFailures instanceof Map) ? detailRule.decodeFailures : [:]
List failed = fails.findAll { Object k, Object v -> v != null && v != false && (v as String) != '' }
.keySet().collect { it as String }
if (failed.isEmpty()) return null
List ordered = RM_DECODE_SECTIONS.keySet().toList().findAll { failed.contains(it) } +
failed.findAll { !RM_DECODE_SECTIONS.containsKey(it) }.sort()
List words = ordered.collect { (RM_DECODE_SECTIONS[it] ?: it) as String }
String named = words.size() == 1 ? words[0] : (words[0..-2].join(', ') + ' and ' + words[-1])
return rmNo('RM_RULE_PARTIAL',
("Automation Map could not read this rule's ${named}, so that part is missing from its copy of the rule. " +
'A copy rebuilt without it would run at the wrong times or do the wrong thing, so HAI will not make one. ' +
'Scan again in Automation Map; if it still fails, the decoder needs fixing for this rule.').toString())
}
boolean rmIsCalledByAnother(Map detailRule, List allRules) {
if (allRules == null) return false
String me = rmBareId(detailRule.id)
if (me == null) return false
for (Object o : allRules) {
Map other = o
String them = rmBareId(other.id)
if (them == me) continue
for (Object so : ((List) (other.steps ?: []))) {
for (Object t : ((List) (((Map) so).ruleTargets ?: []))) {
if ((t as String) == me) return true
}
}
}
return false
}
String rmBareId(Object id) {
String s = (id as Object)?.toString()
return s == null ? null : (s.startsWith('a') ? s.substring(1) : s)
}
Map rmTrigger(Map trig) {
if (trig == null) return rmNo('RM_TRIG_MISSING', 'There is no trigger here to translate.')
String cap = trig.capability as String
Map ops = (trig.operands ?: [:])
boolean allOf = ops.allDevices == true
if (cap == 'Mode') {
List modes = (ops.modes instanceof List) ? ops.modes : []
if (modes.isEmpty()) return rmNo('RM_TRIG_MODE', 'This mode trigger names no mode.', trig.index)
return [ok: true, triggers: [[kind: 'mode', to: modes.collect { it as String }]]]
}
if (cap == 'HSM Status') {
String st = ops.value as String
if (!((List) RM_TRIG_VALUES['HSM Status']).contains(st)) {
return rmNo('RM_TRIG_VALUE_IMPOSSIBLE', "HAI has no HSM status '${st}'.".toString(), trig.index)
}
return [ok: true, triggers: [[kind: 'hsmStatus', to: [st]]]]
}
if (cap == 'HSM Alert') {
String al = ops.value as String
if (!((List) RM_TRIG_VALUES['HSM Alert']).contains(al)) {
return rmNo('RM_TRIG_VALUE_IMPOSSIBLE', "HAI has no HSM alert '${al}'.".toString(), trig.index)
}
return [ok: true, triggers: [[kind: 'hsmAlert', alerts: [al]]]]
}
if (cap == 'Local End Point' || cap == 'Cloud End Point') {
return [ok: true, triggers: [[kind: 'endpoint']]]
}
if (cap == 'Location Event') {
String ev = ops.value as String
if (!LOCATION_EVENTS.contains(ev)) {
return rmNo('RM_TRIG_LOCATION_EVENT',
"HAI has no location event called '${ev}'.".toString(), trig.index)
}
return [ok: true, triggers: [[kind: 'locationEvent', events: [ev]]]]
}
if (cap == 'Variable') {
String name = ops.name as String
if (name == null || name.trim().isEmpty()) {
return rmNo('RM_TRIG_VARIABLE', 'This variable trigger does not say which variable.', trig.index)
}
String comp = ops.comparator as String
if (comp != null && comp != '*changed*') {
String what = ops.value == null ? 'a value' : "'${ops.value}'".toString()
Map op = rmComparator(comp)
Map val = op.ok == true ? rmValue(ops.value) : null
if (op.ok != true || val?.ok != true) {
return rmNo('RM_TRIG_VARIABLE_COMPARE',
("This trigger fires on every change of ${name} while ${name} ${comp} ${what} holds " +
'(measured: not only when it crosses), and HAI does not recognise that comparison or ' +
'value. To build it in HAI, use a trigger on any change of the variable plus a ' +
'condition on it; otherwise keep the rule in Rule Machine.').toString(), trig.index)
}
String vref = (HUB_VARIABLE_PREFIX + name).toString()
return [ok: true, triggers: [[kind: 'hubVariable', ref: vref,
condition: [type: 'variable', ref: vref, op: op.op,
value: rmComparedValue(op.op as String, val.value)]]]]
}
return [ok: true, triggers: [[kind: 'hubVariable', ref: (HUB_VARIABLE_PREFIX + name).toString()]]]
}
if (cap == 'Periodic Schedule') {
return rmTrigPeriodic(ops, trig.index)
}
if (cap != null && cap.startsWith('Certain Time')) {
return rmTrigAtTime(ops, trig.index)
}
if (cap == 'Rule paused') {
return rmTrigRulePaused(ops, trig.index)
}
List devs = (ops.devices instanceof List) ? ops.devices : []
if (devs.isEmpty()) {
return rmNo('RM_TRIG_DEVICE', "This ${cap} trigger names no devices.".toString(), trig.index)
}
List refs = []
for (Object o : devs) {
String id = o instanceof Map ? (((Map) o).id as String) : (o as String)
if (id == null || !(id ==~ /(dev:)?\d+/)) {
return rmNo('RM_TRIG_DEVICE_ID',
("This ${cap} trigger identifies a device as " +
"'${o instanceof Map ? ((Map) o).name : o}' rather than by id.").toString(),
trig.index)
}
refs << (id.startsWith('dev:') ? id : 'dev:' + id)
}
if (cap == 'Button') {
Integer number = ops.button == null ? null : (ops.button as Integer)
if (number == null) return rmNo('RM_TRIG_BUTTON', 'This button trigger has no button number.', trig.index)
String action = ops.value as String
if (!BUTTON_ACTIONS.contains(action)) {
return rmNo('RM_TRIG_BUTTON_ACTION',
"HAI does not know the button action '${action}'.".toString(), trig.index)
}
return [ok: true, triggers: refs.collect { [kind: 'button', ref: it, number: number, action: action] }]
}
if (cap == 'Lock codes') {
String code = "${ops.value ?: ''}".trim()
if (!code || code.equalsIgnoreCase('unlocked')) return [ok: true, triggers: [], unfinished: true]
return [ok: true, triggers: refs.collect { [kind: 'deviceAttribute', ref: it, attribute: RM_LOCK_CODE_ATTR,
op: 'is', value: code] }]
}
String attr = cap == 'Custom Attribute' ? (ops.attribute as String) : (rmTrigAttr()[cap] as String)
if (attr == null) {
return rmNo('RM_TRIG_UNMAPPED',
("HAI has no mapping for the trigger '${cap}': what Rule Machine listens to for it has not " +
'been measured, so the rule is not copied. Keep the rule in Rule Machine, or build the ' +
'trigger in HAI on the device attribute you mean.').toString(), trig.index)
}
String comp = ops.comparator as String
boolean changed = comp == '*changed*' || (comp == null && ops.value == '*changed*')
if (!changed && ops.value != null && RM_TRIG_VALUES.containsKey(cap) && (comp == null || comp == '=' || comp == 'is')) {
List allowed = RM_TRIG_VALUES[cap]
if (!allowed.contains(ops.value as String)) {
return rmNo('RM_TRIG_VALUE_IMPOSSIBLE',
("This ${cap} trigger waits for '${ops.value}', which a ${cap.toLowerCase()} never " +
"reports (it reports ${allowed.join(' or ')}), so it could never fire. It is " +
'left for a person rather than copied.').toString(), trig.index)
}
}
List built = []
if (changed) {
built = refs.collect { [kind: 'deviceAttribute', ref: it, attribute: attr] }
} else if (comp != null && RM_TRIG_THRESHOLD.containsKey(comp)) {
Object val = rmTrigNumber(ops.value)
if (val == null) {
return rmNo('RM_TRIG_THRESHOLD_VALUE',
("This trigger compares the reading with '${ops.value}', which is not a number.").toString(), trig.index)
}
built = refs.collect { [kind: 'deviceAttribute', ref: it, attribute: attr,
op: RM_TRIG_THRESHOLD[comp], value: val] }
} else {
if (comp != null && comp != '=' && comp != 'is') {
Map c = rmComparator(comp)
if (c.ok != true) return c
}
Object val = ops.value
if (val == null) return rmNo('RM_TRIG_VALUE', "This ${cap} trigger has no value.".toString(), trig.index)
Object num = rmTrigNumber(val)
built = refs.collect { [kind: 'deviceAttribute', ref: it, attribute: attr, op: 'is',
value: num != null ? num : val] }
}
if (allOf && refs.size() > 1) {
Map every = [type: 'deviceAttribute', refs: refs, match: 'all', attribute: attr]
Map first = built[0]
if (first.op != null) { every.op = first.op; every.value = first.value }
built = built.collect { Object t -> ((Map) t) + [condition: every] }
}
Integer stays = ops.staysForSeconds == null ? null : (ops.staysForSeconds as Integer)
if (stays != null && stays > 0) {
built = built.collect { Object t ->
Map one = t
Map predicate = [type: 'deviceAttribute', ref: one.ref, attribute: one.attribute,
op: one.op ?: 'is', value: one.value]
Map dur = [kind: 'duration', seconds: stays, predicate: predicate]
if (one.condition != null) dur.condition = one.condition
return dur
}
}
String eventType = RM_TRIG_EVENT_TYPE[cap] as String
if (eventType != null) built.each { Object t -> if (((Map) t).kind == 'deviceAttribute') ((Map) t).eventType = eventType }
if (eventType != null && built.any { ((Map) it).kind != 'deviceAttribute' }) {
String shape = 'waits for a state to hold for a time'
return rmNo('RM_TRIG_UNMAPPED',
("This ${cap} trigger ${shape} and counts only ${eventType} events. HAI can limit a trigger " +
"to ${eventType} events only when it fires on the change itself, so this one is not copied. " +
"Build it in HAI without the ${eventType} limit if that is acceptable, or keep the rule in " +
'Rule Machine.').toString(), trig.index)
}
return [ok: true, triggers: built]
}
Map rmTrigAtTime(Map ops, Object index) {
Map at = (ops.at instanceof Map) ? ops.at : null
if (at == null) {
return rmNo('RM_TRIG_TIME', 'This time trigger does not say what time.', index)
}
String kind = at.kind as String
Map out = [kind: 'schedule']
if (kind == 'clock') {
String hhmm = rmWallClock(at.at)
if (hhmm == null) {
return rmNo('RM_TRIG_TIME_FORM',
("HAI does not recognise '${at.at}' as a time of day, so this time trigger is not " +
'copied. Set the time again in Rule Machine and scan, or build the trigger in HAI.').toString(), index)
}
out.at = hhmm
} else if (kind == 'sunrise' || kind == 'sunset') {
out.at = kind
Integer off = at.offsetMinutes == null ? null : (at.offsetMinutes as Integer)
if (off != null && off != 0) out.offsetMinutes = off
} else {
return rmNo('RM_TRIG_TIME_KIND',
("HAI does not know the time kind '${kind}'.").toString(), index)
}
if (ops.date != null) {
String date = ops.date as String
if (!(date ==~ /\d{4}-\d{2}-\d{2}/) && !(date ==~ /\d{2}-\d{2}/)) {
return rmNo('RM_TRIG_TIME_DATE',
"HAI does not recognise '${date}' as a date.".toString(), index)
}
out.date = date
}
return [ok: true, triggers: [out]]
}
Map rmTrigRulePaused(Map ops, Object index) {
List rules = (ops.rules instanceof List) ? ((List) ops.rules).findAll { (it as String)?.trim() } : []
if (rules.isEmpty() && "${ops.value ?: ''}".trim().isEmpty() && !(ops.rules instanceof List && !((List) ops.rules).isEmpty())) {
return [ok: true, triggers: [], unfinished: true]
}
if (rules.isEmpty()) {
return rmNo('RM_TRIG_DEVICE', 'This Rule paused trigger does not say which rule it watches. Pick the rule in Rule Machine and scan again.', index)
}
for (Object r : rules) {
if (!((r as String).trim() ==~ /\d+/)) {
return rmNo('RM_TRIG_DEVICE_ID',
"This Rule paused trigger names the rule '${r}' rather than by its number.".toString(), index)
}
}
String v = ops.value as String
if (v != 'true') {
return rmNo('RM_TRIG_VALUE',
("This Rule paused trigger stores '${v}' where HAI expects 'true' (fires when the rule is " +
"paused), so what it fires on is not known. Build the trigger in HAI with \"paused\" or " +
'"resumed" as you intend.').toString(), index)
}
return [ok: true, triggers: rules.collect { Object r ->
[kind: 'rulePaused', ref: ('rm:' + (r as String).trim()).toString(), value: true]
}]
}
Map rmTrigPeriodic(Map ops, Object index) {
if (ops.cron != null) {
String published = (ops.cron as String).trim()
List f = published.split(/\s+/) as List
String cron = (f.size() == 7 && f[6] == '*') ? f[0..5].join(' ') : f.join(' ')
Map parsed = timeCronParse(cron)
if (parsed.error) {
return rmNo('RM_TRIG_PERIODIC',
("Rule Machine schedules this with '${published}', which HAI cannot read (${parsed.error}), " +
'so it will not guess the times. Build the schedule in HAI.').toString(), index)
}
return [ok: true, triggers: [[kind: 'periodic', cron: cron]]]
}
String period = (ops.period as String)
Integer every = ops.every == null ? null : (ops.every as Integer)
if (period == null) return rmNo('RM_TRIG_PERIODIC', 'This periodic trigger has no period.', index)
switch (period) {
case 'Seconds':
if (every == null || every < 1 || every > 59) {
return rmNo('RM_TRIG_PERIODIC_EVERY',
"A trigger every ${every} seconds is not something HAI can schedule.".toString(), index)
}
return [ok: true, triggers: [[kind: 'periodic', cron: "*/${every} * * * * ?".toString()]]]
case 'Minutes':
if (every == null || every < 1 || every > 59) {
return rmNo('RM_TRIG_PERIODIC_EVERY',
"A trigger every ${every} minutes is not something HAI can schedule.".toString(), index)
}
return [ok: true, triggers: [[kind: 'periodic', cron: "0 */${every} * * * ?".toString()]]]
case 'Hourly':
if (ops.startingTime != null) {
String hhmm = ops.startingTime as String
if (!(hhmm ==~ /\d{1,2}:\d{2}/)) {
return rmNo('RM_TRIG_PERIODIC_STARTING_TIME',
"HAI does not recognise '${hhmm}' as the time an hourly schedule starts at.".toString(), index)
}
int min = (hhmm.split(':')[1] as int)
int hour = (hhmm.split(':')[0] as int)
if (every != null && every > 23) {
return rmNo('RM_TRIG_PERIODIC_EVERY', "A trigger every ${every} hours is not something HAI can schedule.".toString(), index)
}
int n = (every == null || every < 1) ? 1 : every
return [ok: true, triggers: [[kind: 'periodic', cron: "0 ${min} ${hour}/${n} * * ?".toString()]]]
}
String hours = (every == null || every <= 1) ? 'every hour' : "every ${every} hours".toString()
return rmNo('RM_TRIG_PERIODIC_STARTING_TIME',
("This runs ${hours} with no starting time, and Automation Map published no Rule Machine " +
'schedule for it (a paused rule has none). Rule Machine starts this schedule at the hour ' +
'after the rule was saved, and does not wrap past midnight, so it runs only from that hour ' +
'until the end of each day. HAI is not told that hour, so it will not guess it. Resume the ' +
'rule or set a starting time in Rule Machine and scan again, or build the schedule in HAI.').toString(), index)
case 'Daily':
if (ops.startingTime == null || !((ops.startingTime as String) ==~ /\d{1,2}:\d{2}/)) {
return rmNo('RM_TRIG_PERIODIC_STARTING_TIME', 'This daily schedule stores no time of day.', index)
}
if (every != null && (every < 0 || every > 31)) {
return rmNo('RM_TRIG_PERIODIC_EVERY', "A trigger every ${every} days is not something HAI can schedule.".toString(), index)
}
String[] hm = (ops.startingTime as String).split(':')
String dom = (every == null || every <= 1) ? '*' : "*/${every}".toString()
return [ok: true, triggers: [[kind: 'periodic', cron: "0 ${hm[1] as int} ${hm[0] as int} ${dom} * ?".toString()]]]
default:
return rmNo('RM_TRIG_PERIODIC_PERIOD',
("HAI has not been shown what Rule Machine stores for a '${period}' schedule, so " +
'it will not guess the times.').toString(), index)
}
}
Object rmTrigNumber(Object v) {
if (v instanceof Number) return v
String t = (v as String)?.trim()
if (t == null || !(t ==~ /-?\d+(\.\d+)?/)) return null
return t.contains('.') ? (t as BigDecimal) : (t as Integer)
}
@Field static final Map RM_COMPARATORS = [
'=' : 'is',
'*contains*' : 'contains',
'!=' : 'isNot',
'<>' : 'isNot',
'≠' : 'isNot',
'>' : 'above',
'<' : 'below',
'>=' : 'atLeast',
'<=' : 'atMost',
'is' : 'is',
'is not' : 'isNot',
'contains' : 'contains',
'between' : 'between',
'not between' : 'notBetween',
]
Map rmExprVerdict(Map expression) {
if (expression == null) return [ok: true, tokens: [], empty: true]
List tokens = (expression.tokens instanceof List) ? expression.tokens : null
if (tokens == null) {
return rmNo('RM_EXPR_SHAPE', 'Automation Map did not give this expression as a list of terms.')
}
if (tokens.isEmpty()) return [ok: true, tokens: [], empty: true]
String eval = expression.evaluation as String
if (eval != null && eval != RM_EVAL_WALK) {
return rmNo('RM_EXPR_EVALUATION',
("Automation Map says this rule's conditions are evaluated as '${eval}', and HAI only " +
"knows how to reproduce '${RM_EVAL_WALK}'.").toString())
}
List ops = tokens.findAll { rmIsOperator(it as String) }.collect { (it as String).toUpperCase() }
List unestablished = ops.findAll { it == 'NOT' }
if (!unestablished.isEmpty()) {
return rmNo('RM_EXPR_UNESTABLISHED',
("This rule combines its conditions with ${unestablished.unique().join(' and ')}, and " +
'how Rule Machine evaluates that has never been established. HAI will not guess at it.').toString())
}
Map levels = rmExprLevels(tokens)
if (levels.ok != true) return levels
return [ok: true, tokens: tokens, terms: levels.terms, empty: false]
}
Map rmExprLevels(List tokens) {
List outer = []
List stack = [outer]
for (Object t : tokens) {
String s = t as String
if (s == '(') {
List group = []
stack << group
continue
}
if (s == ')') {
if (stack.size() < 2 || ((List) stack[-1]).isEmpty()) {
return rmNo('RM_EXPR_SHAPE', "Automation Map gave this rule's conditions with brackets that do not pair up.")
}
List group = stack.remove(stack.size() - 1)
((List) stack[-1]) << group
continue
}
((List) stack[-1]) << s
}
if (stack.size() != 1) {
return rmNo('RM_EXPR_SHAPE', "Automation Map gave this rule's conditions with brackets that do not pair up.")
}
return [ok: true, terms: outer]
}
boolean rmIsOperator(String t) {
String u = (t ?: '').toUpperCase()
return u == 'AND' || u == 'OR' || u == 'NOT' || u == 'XOR'
}
Map rmExprNode(Map expression, Map byIndex) {
Map v = rmExprVerdict(expression)
if (v.ok != true) return v
List tokens = v.tokens
if (tokens.isEmpty()) return [ok: true, node: null]
return rmExprFold((List) v.terms, byIndex)
}
Map rmExprFold(List terms, Map byIndex) {
List operands = []
List operators = []
String pending = null
for (Object t : terms) {
Map leaf
if (t instanceof List) {
Map inner = rmExprFold((List) t, byIndex)
if (inner.ok != true) return inner
leaf = inner.node
} else {
String s = t as String
if (rmIsOperator(s)) { pending = s.toUpperCase(); continue }
leaf = byIndex.get(s) ?: byIndex.get(s?.toString())
if (leaf == null) {
return rmNo('RM_EXPR_MISSING_TERM',
"This rule's conditions refer to condition ${s}, which Automation Map did not give.".toString())
}
}
if (!operands.isEmpty()) operators << (pending ?: 'AND')
operands << leaf
}
if (operands.isEmpty()) return [ok: true, node: null]
List built = []
Map node = rmExprChain(operands, operators, 0, operands[0], built)
if ((operators as Set).size() > 1) node.rmGrouping = true
return [ok: true, node: node]
}
Map rmExprChain(List operands, List operators, int i, Map acc, List built) {
if (i >= operators.size()) return acc
String op = operators[i] as String
Map next = operands[i + 1]
if (op == 'XOR') {
Map x = (built.any { it.is(acc) } && acc.type == 'xor') ? [type: 'xor', of: ((List) acc.of) + [next]]
: [type: 'xor', of: [acc, next]]
built << x
return rmExprChain(operands, operators, i + 1, x, built)
}
String type = op == 'OR' ? 'any' : 'all'
Map rest = rmExprChain(operands, operators, i + 1, next, built)
Map node = (built.any { it.is(rest) } && rest.type == type) ? [type: type, of: [acc] + rest.of]
: [type: type, of: [acc, rest]]
built << node
return node
}
Map rmCondition(Map cond) {
if (cond == null) return rmNo('RM_COND_MISSING', 'There is no condition here to translate.')
String cap = cond.capability as String
Map ops = (cond.operands ?: [:])
String anyAll = rmCondAnyAll(cond)
if (anyAll != null && (ops.anyAll as String) != anyAll) ops = ops + [anyAll: anyAll]
Map out
switch (cap) {
case 'Between two times':
case 'Time of day':
out = rmTimeWindow(ops)
break
case 'Between two dates':
out = rmDateRange(ops)
break
case 'Days of week':
case 'Days of Week':
out = rmDayOfWeek(ops)
break
case 'On a Day':
Map onDay = rmDatePoint((Map) ops.date, 'day')
out = onDay.ok != true ? onDay : [ok: true, node: [type: 'dateRange', from: onDay.at, to: onDay.at]]
break
case 'Mode':
out = rmMode(ops)
break
case 'Variable':
out = rmVariable(ops)
break
case 'Private Boolean':
out = rmPrivateBoolean(ops)
break
case 'Custom Attribute':
out = rmCustomAttribute(ops)
break
default:
String attr = RM_CAPABILITY_ATTR[cap] as String
out = attr == null
? rmNo('RM_COND_UNMAPPED', "HAI has no mapping for the condition '${cap}'.".toString())
: rmDeviceAttribute(ops, attr)
}
if (out.ok != true) return out
Map node = out.node
if (cond.negated == true) node = [type: 'not', of: node]
return [ok: true, node: node, index: cond.index]
}
String rmCondAnyAll(Map cond) {
Map ops = (cond.operands ?: [:])
String said = (ops.anyAll as String)?.trim()?.toLowerCase()
if (said in ['all', 'true']) return 'all'
if (said in ['any', 'false']) return 'any'
Map raw = (cond.raw instanceof Map) ? cond.raw : [:]
String n = cond.index as String
if (n == null) return null
Object v = raw.containsKey('AllrDev_' + n) ? raw.get('AllrDev_' + n) : raw.get('AllrDev' + n)
if (v == null) return null
return (v as String).trim().toLowerCase() == 'true' ? 'all' : 'any'
}
Map rmTimeWindow(Map ops) {
String mode = ops.mode as String
if (mode == 'at') {
return rmNo('RM_TIME_INSTANT',
("This condition is true at one time (${((Map) (ops.from ?: [:])).at}) rather than " +
'between two. HAI has no condition for a single instant, so it is left out rather ' +
'than widened into a window Rule Machine did not ask for.').toString())
}
if (mode != null && mode != 'between') {
return rmNo('RM_TIME_MODE', "HAI does not know the time condition kind '${mode}'.".toString())
}
Map from = rmTimePoint((Map) ops.from, 'from')
if (from.ok != true) return from
Map to = rmTimePoint((Map) ops.to, 'to')
if (to.ok != true) return to
Map node = [type: 'timeWindow', from: from.at, to: to.at]
if (from.offset != null) node.fromOffsetMinutes = from.offset
if (to.offset != null) node.toOffsetMinutes = to.offset
return [ok: true, node: node]
}
String rmWallClock(Object raw) {
String at = (raw as String)?.trim()
if (at == null) return null
java.util.regex.Matcher iso = (at =~ /^\d{4}-\d{2}-\d{2}[T ](\d{1,2}:\d{2})/)
if (iso.find()) at = iso.group(1)
if (!(at ==~ /\d{1,2}:\d{2}/)) return null
List hm = at.tokenize(':')
int h = hm[0] as int, m = hm[1] as int
if (h > 23 || m > 59) return null
return String.format('%02d:%02d', h, m)
}
Map rmTimePoint(Map p, String which) {
if (p == null) return rmNo('RM_TIME_MISSING', "This time condition has no ${which} time.".toString())
String kind = p.kind as String
Integer offset = p.offsetMinutes == null ? null : (p.offsetMinutes as Integer)
if (kind == 'sunrise' || kind == 'sunset') return [ok: true, at: kind, offset: offset]
if (kind == 'clock') {
String at = rmWallClock(p.at)
if (at == null) {
return rmNo('RM_TIME_FORM',
"HAI does not recognise '${p.at}' as a time of day.".toString())
}
return [ok: true, at: at, offset: offset]
}
return rmNo('RM_TIME_KIND', "HAI does not know the time kind '${kind}'.".toString())
}
Map rmDateRange(Map ops) {
Map from = rmDatePoint((Map) ops.from, 'from')
if (from.ok != true) return from
Map to = rmDatePoint((Map) ops.to, 'to')
if (to.ok != true) return to
Map dates = [type: 'dateRange', from: from.at, to: to.at]
Object rawFrom = ((Map) (ops.from ?: [:])).at, rawTo = ((Map) (ops.to ?: [:])).at
boolean hasFrom = rawFrom != null && (rawFrom as String).trim(), hasTo = rawTo != null && (rawTo as String).trim()
if (!hasFrom && !hasTo) return [ok: true, node: dates]
String fromAt = hasFrom ? rmWallClock(rawFrom) : null, toAt = hasTo ? rmWallClock(rawTo) : null
if ((hasFrom && fromAt == null) || (hasTo && toAt == null)) {
return rmNo('RM_TIME_FORM', "HAI does not recognise '${fromAt == null ? rawFrom : rawTo}' as a time of day.".toString())
}
if (!hasFrom || !hasTo) {
return rmNo('RM_DATE_FORM', ('This date range gives a time at only one end. What Rule Machine does then has not ' +
'been measured, so it is left for a person.'))
}
if (from.at != to.at || from.at instanceof Map) {
return rmNo('RM_DATE_FORM', ("This date range runs from ${rmDateText(from.at)} at ${fromAt} to ${rmDateText(to.at)} " +
"at ${toAt}. Across several dates that can mean one continuous period or those " +
'hours on each day, and HAI has not been shown which, so it is left for a person.').toString())
}
if (fromAt >= toAt) {
return rmNo('RM_DATE_FORM', ("This date range ends (${toAt}) no later than it starts (${fromAt}) on the same date, " +
'so it is never true. Check the rule in Rule Machine.').toString())
}
return [ok: true, node: [type: 'all', of: [dates, [type: 'timeWindow', from: fromAt, to: toAt]]]]
}
String rmDateText(Object at) { at instanceof Map ? "the date in ${((Map) at).ref}".toString() : at as String }
Map rmDatePoint(Map p, String which) {
if (p == null) return rmNo('RM_DATE_MISSING', "This date condition has no ${which} date.".toString())
if (p.variable != null) return [ok: true, at: [ref: p.variable as String]]
String form = p.dateType as String
if (form != null && form != 'mm-dd') {
return rmNo('RM_DATE_FORM', "HAI does not know the ${which} date form '${form}'.".toString())
}
if (p.month == null || p.day == null) {
return rmNo('RM_DATE_FORM', "HAI does not recognise the ${which} date on this condition.".toString())
}
return [ok: true, at: String.format('%02d-%02d', (p.month as String) as int, (p.day as String) as int)]
}
Map rmDayOfWeek(Map ops) {
List days = (ops.days instanceof List) ? ops.days : null
if (days == null || days.isEmpty()) return rmNo('RM_DAYS_MISSING', 'This day condition names no days.')
List out = []
for (Object d : days) {
String k = RM_DAYS[(d as String)?.toLowerCase()] as String
if (k == null) return rmNo('RM_DAYS_NAME', "HAI does not recognise the day '${d}'.".toString())
out << k
}
return [ok: true, node: [type: 'dayOfWeek', days: out]]
}
Map rmMode(Map ops) {
List modes = (ops.modes instanceof List) ? ops.modes : null
if (modes == null || modes.isEmpty()) return rmNo('RM_MODE_MISSING', 'This mode condition names no modes.')
if ((ops.anyAll as String) == 'all' && modes.size() > 1) {
return rmNo('RM_MODE_ALL',
("This condition asks for the hub to be in ${modes.size()} modes at once, which cannot " +
'happen. Check the rule in Rule Machine.').toString())
}
List names = modes.collect { it as String }
if (names.size() == 1) return [ok: true, node: [type: 'mode', value: names[0]]]
return [ok: true, node: [type: 'any', of: names.collect { [type: 'mode', value: it] }]]
}
Map rmVariable(Map ops) {
String name = ops.name as String
if (name == null) return rmNo('RM_VAR_MISSING', 'This variable condition names no variable.')
Map op = rmComparator(ops.comparator as String)
if (op.ok != true) return op
Map value = rmValue(ops.value)
if (value.ok != true) return value
return [ok: true, node: [type: 'variable', ref: (RM_HUB_VARIABLE_PREFIX + name).toString(),
op: op.op, value: rmComparedValue(op.op as String, value.value), unknownAs: 'false']]
}
Map rmPrivateBoolean(Map ops) {
if (!(ops.value instanceof Boolean)) {
return rmNo('RM_PB_VALUE', 'This private boolean condition has no true or false to test against.')
}
String src = ops.source as String
if (src != null && src != 'private') {
return rmNo('RM_PB_SOURCE',
("This private boolean was read from '${src}', which mirrors the value rather than being " +
'it. HAI will not rebuild a rule from a mirror.').toString())
}
return [ok: true, node: [type: 'variable', ref: PRIVATE_BOOLEAN_ID, op: 'is', value: ops.value]]
}
Map rmCustomAttribute(Map ops) {
String attr = ops.attribute as String
if (attr == null) {
return rmNo('RM_ATTR_NAME', 'This condition names no attribute to test.')
}
return rmDeviceAttribute(ops, attr)
}
Map rmDeviceAttribute(Map ops, String attribute) {
Map got = rmDeviceRefs(ops, attribute)
if (got.ok != true) return got
List devices = got.refs
Map op = ops.comparator == null ? [ok: true, op: 'is'] : rmComparator(ops.comparator as String)
if (op.ok != true) return op
Map value = rmValue(ops.value)
if (value.ok != true) return value
Map node = [type: 'deviceAttribute', attribute: attribute, op: op.op,
value: rmComparedValue(op.op as String, value.value), unknownAs: 'false']
if (devices.size() == 1) {
node.ref = devices[0] as String
} else {
node.refs = devices.collect { it as String }
node.match = (ops.anyAll as String) == 'all' ? 'all' : 'any'
}
return [ok: true, node: node]
}
Map rmDeviceRefs(Map ops, String what) {
List ids = (ops.devices instanceof List && !((List) ops.devices).isEmpty() && ((List) ops.devices).every { it instanceof Map })
? ((List) ops.devices).collect { ((Map) it).id }.findAll { it != null }
: ((ops.deviceIds instanceof List) ? ops.deviceIds : null)
if (ids == null || ids.isEmpty()) {
List named = (ops.devices instanceof List) ? ops.devices : []
return named.isEmpty()
? rmNo('RM_ATTR_DEVICE', "This ${what} condition does not say which devices it tests.".toString())
: rmNo('RM_ATTR_DEVICE_NAME',
("This ${what} condition names its devices (${named.take(3).join(', ')}) without " +
'identifying them. HAI binds a device by id, because two devices can share a name and ' +
'renaming one would break the rule without saying so.').toString())
}
List bad = ids.findAll { !((it as String) ==~ /(dev:)?\d+/) }
if (!bad.isEmpty()) {
return rmNo('RM_ATTR_DEVICE_ID', "HAI does not recognise ${bad.take(3).join(', ')} as a device.".toString())
}
return [ok: true, refs: ids.collect { String d = it as String; d.startsWith('dev:') ? d : 'dev:' + d }]
}
Object rmComparedValue(String op, Object value) {
if (!(op in ['above', 'below', 'atLeast', 'atMost', 'between', 'notBetween'])) return value
if (value instanceof Number) return value
String t = (value as String)?.trim()
if (t == null || !(t ==~ /-?\d+(\.\d+)?/)) return value
return t.contains('.') ? (t as BigDecimal) : (t as Integer)
}
Map rmComparator(String raw) {
String key = (raw ?: '').trim()
String op = RM_COMPARATORS[key] ?: RM_COMPARATORS[key.toLowerCase()]
if (op == null) {
return rmNo('RM_COMPARATOR',
("HAI does not know the comparison '${raw}'. It is left alone rather than treated as " +
'an equality, which would change what the rule tests for without saying so.').toString())
}
return [ok: true, op: op]
}
Map rmValue(Object v) {
if (v == null) return rmNo('RM_VALUE_MISSING', 'This condition has nothing to compare against.')
Map m = v instanceof Map ? v : null
if (m == null) return [ok: true, value: v]
if (m.containsKey('literal')) return [ok: true, value: m.literal]
if (m.variable != null) return [ok: true, value: [ref: m.variable as String]]
if (m.device != null && m.attribute != null) {
return [ok: true, value: [ref: m.device as String, attribute: m.attribute as String]]
}
return rmNo('RM_VALUE_FORM', 'HAI does not recognise what this condition compares against.')
}
String rmActCondSlot(Object raw) {
String t = raw == null ? '' : (raw as Object).toString().trim()
if (!(t ==~ /\d+/)) return t ? t : null
return (t as BigInteger) > 0 ? (t as BigInteger).toString() : null
}
Map rmConditions(Map detailRule, Set actionConditions = null) {
if (detailRule == null) return rmNo('RM_RULE_MISSING', 'Automation Map holds no detail for this rule.')
Map unread = rmUnreadable(detailRule)
if (unread != null) return unread
List conds = (detailRule.conditions instanceof List) ? detailRule.conditions : []
Map exprs0 = (detailRule.expressions instanceof Map) ? detailRule.expressions : [:]
Set referenced = [] as Set
exprs0.each { Object k, Object v ->
((List) (((Map) v)?.tokens ?: [])).each { Object t ->
if (!rmIsOperator(t as String)) referenced << (t as String)
}
}
if (actionConditions != null) referenced.addAll(actionConditions)
Map byIndex = [:]
for (Object o : conds) {
Map c = o
String idx = (c.index as Object)?.toString()
if (!referenced.contains(idx)) continue
Map t = rmCondition(c)
if (t.ok != true) return t
byIndex.put(idx, t.node)
}
Map required = null
Map byAction = [:]
Map byActionRefused = [:]
for (Object k : exprs0.keySet()) {
String owner = k as String
Map r = rmExprNode((Map) exprs0.get(k), byIndex)
if (owner == '0') {
if (r.ok != true) return r
required = r.node
continue
}
if (r.ok != true) byActionRefused.put(owner, r) else byAction.put(owner, r.node)
}
return [ok: true, node: required, required: required, byAction: byAction, byActionRefused: byActionRefused,
byCondition: byIndex]
}
List rmUnapprovedDevices(Map detailRule, Collection allowed) {
if (allowed == null) return []
Set ok = allowed.collect { String a = it as String; a.startsWith('dev:') ? a.substring(4) : a } as Set
return rmDevicesNamed(detailRule).findAll { Object k, Object v ->
String id = k as String
return (id ==~ /\d+/) && !ok.contains(id)
}.values().toList().sort()
}
String rmRendered(Map detailRule, Map node) {
if (node == null) return 'always'
Map refs = [:]
rmDevicesNamed(detailRule).each { Object id, Object name ->
if ((id as String) ==~ /\d+/) refs.put('dev:' + (id as String), [kind: 'device', id: 'dev:' + (id as String), name: name as String])
}
return renderCondition([refs: refs], node)
}
Map rmCoupling(Map detailRule, List allRules) {
Map out = [outbound: [], inbound: []]
if (detailRule == null) return out
String me = (detailRule.id as String)
String meBare = me?.startsWith('a') ? me.substring(1) : me
Closure nameOf = { String id ->
String bare = id?.startsWith('a') ? id.substring(1) : id
Map hit = (allRules ?: []).find { Object o ->
String rid = ((Map) o).id as String
return rid == id || rid == 'a' + bare || rid == bare
}
return hit == null ? id : ((hit.name as String) ?: id)
}
Set outIds = [] as Set
((List) (detailRule.steps ?: [])).each { Object so ->
((List) (((Map) so).ruleTargets ?: [])).each { Object t -> outIds << (t as String) }
}
out.outbound = outIds.findAll { it != meBare && it != me }.collect { nameOf(it as String) }.sort()
Set inIds = [] as Set
Set pbIds = [] as Set
Set pbOnlyIds = [] as Set
Set repairIds = [] as Set
(allRules ?: []).each { Object ro ->
Map other = ro
if ((other.id as String) == me) return
List hits = ((List) (other.steps ?: [])).findAll { Object so ->
((List) (((Map) so).ruleTargets ?: [])).any { Object t -> (t as String) == meBare }
}
if (hits.isEmpty()) return
String name = (other.name as String) ?: (other.id as String)
inIds << name
Closure setsPb = { Object so -> ((List) (((Map) so).constructs ?: [])).contains('action:getSetPrivateBoolean') }
if (hits.any(setsPb)) pbIds << name
if (hits.every(setsPb)) pbOnlyIds << name
if (hits.every { Object so -> rmSetsPbTrue((Map) so) } && rmRestartOnly(other)) repairIds << name
}
out.inbound = inIds.toList().sort()
out.inboundPb = pbIds.toList().sort()
out.inboundPbOnly = pbOnlyIds.toList().sort()
out.inboundRestartRepair = repairIds.toList().sort()
return out
}
boolean rmSetsPbTrue(Map step) {
if (step == null) return false
List cons = (step.constructs ?: [])
return cons == ['action:getSetPrivateBoolean'] &&
((step.label ?: '') as String).trim().equalsIgnoreCase('Set Private Boolean True')
}
boolean rmRestartOnly(Map detailRule) {
List trig = (detailRule?.triggers ?: [])
if (trig.isEmpty()) return false
return trig.every { Object to ->
Map t = to
return (t.capability as String) == 'Location Event' && ((Map) (t.operands ?: [:])).value == 'systemStart'
}
}
Set rmRestartRepairTargets(Map detailRule) {
if (detailRule == null || !rmRestartOnly(detailRule)) return [] as Set
Set yes = [] as Set, no = [] as Set
((List) (detailRule.steps ?: [])).each { Object so ->
Map s = so
boolean fix = rmSetsPbTrue(s)
((List) (s.ruleTargets ?: [])).each { Object t -> (fix ? yes : no) << (t as String) }
}
yes.removeAll(no)
return yes
}
Map rmAction(Map act, Map ruleCtx = null) {
if (act == null) return rmNo('RM_ACT_MISSING', 'There is no action here to translate.')
if (act.disabled == true) {
return [ok: true, skipped: true, index: act.index,
why: ("This action is turned off in Rule Machine, so it is left out. It is kept in the " +
'rule there and shown as "Disabled:", which is easy to read as part of the rule.').toString()]
}
if (!RM_ACT_METHODS.contains(act.method as String)) {
return rmNo('RM_ACT_UNSUPPORTED',
"HAI has no mapping for the action '${act.method ?: 'unnamed'}'.".toString(), act.index)
}
if (act.supported != true) {
return rmNo('RM_ACT_UNSUPPORTED',
("Automation Map could not read how this '${act.method ?: 'unnamed'}' action is set up - it " +
'is a form of the action it has not learned yet - so HAI cannot copy it. Build this step ' +
'in HAI, or keep the rule in Rule Machine.').toString(), act.index)
}
Map ops = (act.operands ?: [:])
Map out
switch (act.method as String) {
case 'getOnOffSwitch': out = rmActSwitch(ops); break
case 'getDelay': out = rmActDelay(ops); break
case 'getSetPrivateBoolean': out = rmActPrivateBoolean(ops, ruleCtx); break
case 'getMsg': out = rmActMessage(ops); break
case 'getLogMsg': out = rmActLog(ops); break
case 'getDefinedAction': out = rmActDefined(ops); break
case 'getCapture': out = rmActCapture(ops); break
case 'getRestore': out = rmActRestore(ruleCtx); break
case 'getSetColor': out = rmActSetColor(ops); break
case 'getSetVariable': out = rmActSetVariable(ops); break
case 'getSetColorTemp': out = rmActColorTemp(ops); break
case 'getSetVolume': out = rmActVolume(ops); break
case 'getRuleActions':
case 'getStopActions':
case 'getPauseResumeRules': out = rmActRuleControl(act.type as String, ops, ruleCtx); break
case 'getSetDimmer': out = rmActSetLevel(ops); break
case 'getAdjustDimmer': out = rmActAdjustLevel(ops); break
case 'getFlashSwitch':
case 'getRefreshSwitch':
case 'getPollSwitch': out = rmActPlainCommand(act.method as String, ops); break
case 'getChime': out = rmActChime(ops); break
case 'getSetMode': out = rmActSetMode(ops); break
case 'getComment': out = rmActComment(ops); break
case 'getHTTPPost': out = rmActHttpPost(ops); break
case 'getOCGarage': out = rmActGarage(ops); break
case 'getToggleSwitch': out = rmActSwitch(ops); break
case 'getToggleDimmer': out = rmActToggleLevel(ops); break
case 'getStopDimmer': out = rmActDeviceCommand(ops, 'stopLevelChange', 'dimmer'); break
case 'getShadePosition': out = rmActShadePosition(ops); break
case 'getAdjustFan': out = rmActDeviceCommand(ops, 'cycleSpeed', 'fan'); break
case 'getExitRule': out = [ok: true, steps: [[type: 'exit']]]; break
case 'getStopRepeat': out = [ok: true, steps: [[type: 'stopRepeat']]]; break
case 'getHTTPGet': out = rmActHttpPost(ops + [method: 'get']); break
case 'getPingIP': out = rmActPing(ops); break
case 'getWriteLocalFile':
case 'getAppendLocalFile':
case 'getDeleteLocalFile': out = rmActFile(ops); break
case 'getRLDimmer': out = rmActDirected(ops, ['startLevelChange'], 'dimmer'); break
case 'getRLShade': out = rmActDirected(ops, ['openShade', 'closeShade'], 'shade'); break
case 'getOCValve': out = rmActDirected(ops, ['openValve', 'closeValve'], 'valve'); break
case 'getSetHSM': out = rmActHsm(ops); break
case 'getStopShade': out = rmActDirected(ops, ['stopPositionChange'], 'shade'); break
case 'getLULock': out = rmActDirected(ops, ['lock', 'unlock'], 'lock'); break
case 'getMuteUnmute': out = rmActDirected(ops, ['mute', 'unmute'], 'speaker'); break
case 'getFanSpeed': out = rmActFanSpeed(ops); break
case 'getPushButton': out = rmActPushButton(ops); break
case 'getFadeDimmer':
case 'getFadeCT': out = rmActFadeTo(ops); break
case 'getToggleColor': out = rmActToggleColor(ops); break
case 'getToggleColorTemp': out = rmActToggleColorTemp(ops); break
case 'getCancelDelay': out = [ok: true, steps: [[type: 'cancelDelays']]]; break
case 'getStopFade': out = [ok: true, steps: [[type: 'stopGradual', what: 'level']]]; break
case 'getStopCTFade': out = [ok: true, steps: [[type: 'stopGradual', what: 'colorTemperature']]]; break
case 'getDisable': out = rmActDeviceEnabled(ops); break
case 'getActivateScene':
Map sc = rmActDevices(ops.devices, 'scene')
out = sc.ok != true ? sc : [ok: true, steps: [[type: 'command', refs: sc.refs, command: 'on']]]
break
case 'getSetMusicPlayer': out = rmActMusicPlayer(ops); break
case 'getSiren': out = rmActSiren(ops); break
case 'getSetThermostat': out = rmActThermostat(ops); break
case 'getModeSwitch':
case 'getChooseSwitch':
case 'getDimmersPerMode':
case 'getColorPerMode':
case 'getDelayPerMode':
case 'getPushButtonPerMode':
case 'getChooseButton':
case 'getColorTempPerMode':
case 'getActivateScenePerMode': out = rmActPerMode(act.method as String, ops); break
default:
out = rmNo('RM_ACT_UNSUPPORTED', "HAI has no mapping for the action '${act.method}'.".toString(), act.index)
}
if (out.ok != true) return out
List steps = out.steps
Map d = act.delay
if (d != null && (d.seconds as Integer) != null && (d.seconds as int) > 0) {
steps = [[type: 'later', seconds: d.seconds as int, actions: steps] + (d.cancelable == true ? [cancelable: true] : [:])]
}
return [ok: true, index: act.index, steps: steps]
}
Map rmActDevices(Object list, String what) {
List devs = (list instanceof List) ? list : []
if (devs.isEmpty()) return rmNo('RM_ACT_DEVICE', "This ${what} action names no devices.".toString())
List refs = []
for (Object o : devs) {
String id = o instanceof Map ? (((Map) o).id as String) : (o as String)
if (id == null || !(id ==~ /(dev:)?\d+/)) {
return rmNo('RM_ACT_DEVICE_ID',
("This ${what} action identifies a device as '${o instanceof Map ? ((Map) o).name : o}' " +
'rather than by id, and HAI binds devices by id.').toString())
}
refs << (id.startsWith('dev:') ? id : 'dev:' + id)
}
return [ok: true, refs: refs]
}
Map rmActSwitch(Map ops) {
Map got = rmActDevices(ops.devices, 'switch')
if (got.ok != true) return got
String cmd = ops.command as String
if (!(cmd in ['on', 'off', 'toggle'])) {
return rmNo('RM_ACT_COMMAND', "HAI does not know the switch command '${cmd}'.".toString())
}
if (ops.trackEventSwitch == true && ops.onlyIfCurrently == null && ops.useLastDevice != true && cmd != 'toggle') {
return [ok: true, steps: [[type: 'track', refs: got.refs, attribute: 'switch']]]
}
String only = ops.onlyIfCurrently == null ? null : (ops.onlyIfCurrently as String)
if (only != null && only in ['on', 'off'] && cmd != 'toggle' && ops.trackEventSwitch != true && ops.useLastDevice != true) {
if (only == 'on') return [ok: true, steps: [[type: 'command', refs: got.refs, command: cmd, onlyIfOn: true]]]
return [ok: true, steps: ((List) got.refs).collect { Object ref ->
[type : 'if',
condition: [type: 'deviceAttribute', ref: ref, attribute: 'switch', op: 'is', value: 'off'],
then : [[type: 'command', refs: [ref], command: cmd]]]
}]
}
if (ops.useLastDevice == true) {
return rmNo('RM_ACT_SWITCH_OPTION',
("This ${cmd} is marked to act on the device that triggered the rule, but Rule Machine's switch " +
'action has no "Use last event device" option (only Run Custom Action has one), so HAI does not ' +
'know what it means and leaves it for a person.').toString())
}
if (ops.onlyIfCurrently != null || ops.trackEventSwitch == true) {
String what = ops.onlyIfCurrently != null ? "commands only switches that are ${ops.onlyIfCurrently}".toString()
: 'tracks the switch that triggered the rule'
return rmNo('RM_ACT_SWITCH_OPTION',
("This ${cmd} ${what}. HAI cannot do that yet, and copying it as a plain ${cmd} would send it to " +
'every device listed, so it is left for a person.').toString())
}
return [ok: true, steps: [[type: 'command', refs: got.refs, command: cmd]]]
}
Map rmActDelay(Map ops) {
if (ops.variable != null) {
String name = (ops.variable as String).trim()
if (name.isEmpty()) return rmNo('RM_ACT_DELAY_VARIABLE', 'This delay takes its time from a variable it does not name.')
Map vstep = [type: 'wait', waitKind: 'delay', seconds: [ref: (RM_HUB_VARIABLE_PREFIX + name).toString(), unit: 'seconds']]
if (ops.cancelable == true) vstep.cancelable = true
return [ok: true, steps: [vstep]]
}
Integer secs = ops.seconds == null ? null : (ops.seconds as Integer)
if (secs == null) return rmNo('RM_ACT_DELAY', 'This delay does not say how long for.')
if (secs <= 0) {
return rmNo('RM_ACT_DELAY_ZERO',
("This delay is ${secs} seconds: either it was never set in Rule Machine, or Automation Map " +
'could not read its time. HAI waits at least 1 second, so it is not guessed. Set the delay ' +
'in Rule Machine (or remove it) and scan again.').toString())
}
Map step = [type: 'wait', waitKind: 'delay', seconds: secs]
if (ops.cancelable == true) step.cancelable = true
return [ok: true, steps: [step]]
}
Map rmActPrivateBoolean(Map ops, Map ruleCtx = null) {
if (!(ops.value instanceof Boolean)) {
return rmNo('RM_ACT_PB_VALUE', 'This action does not say what to set the private boolean to.')
}
if (ops.self == true || ops.rules == null || ((List) ops.rules).isEmpty()) {
return [ok: true, steps: [[type: 'setRuleBoolean', rules: ['self'], value: ops.value]]]
}
Map gone = rmGoneRules((List) ops.rules, ruleCtx)
if (gone != null) return gone
List targets = ((List) ops.rules).collect { ('rm:' + (it as String)).toString() }
return [ok: true, steps: [[type: 'setRuleBoolean', rules: targets, value: ops.value]]]
}
Map rmActMessage(Map ops) {
String text = ops.text as String
if (text == null || text.trim().isEmpty()) return rmNo('RM_ACT_MSG_TEXT', 'This message has no text.')
List steps = []
if (ops.notify instanceof List && !((List) ops.notify).isEmpty()) {
Map got = rmActDevices(ops.notify, 'notification')
if (got.ok != true) return got
steps << [type: 'command', refs: got.refs, command: 'deviceNotification', args: [text]]
}
if (ops.speak instanceof List && !((List) ops.speak).isEmpty()) {
Map got = rmActDevices(ops.speak, 'speech')
if (got.ok != true) return got
Integer vol = (ops.volume == null || (ops.volume as int) <= 0) ? null : (ops.volume as int)
Map step = [type: 'command', refs: got.refs, command: 'speak', args: [text]]
if (vol != null) step.args = [text, vol]
steps << step
}
if (steps.isEmpty()) return rmNo('RM_ACT_MSG_DEVICE', 'This message names nothing to say it on.')
return [ok: true, steps: steps]
}
Map rmActDefined(Map ops) {
String cmd = ops.command as String
Map got
if (ops.useLastDevice == true) {
if (cmd == null || cmd.trim().isEmpty()) {
return rmNo('RM_ACT_DEFINED_COMMAND', 'This action does not say which command to run.')
}
Map risky = schemaOperation(cmd)
if (risky != null && risky.risk != null && risky.risk != 'none') {
return rmNo('RM_ACT_DEFINED_LAST_DEVICE',
("This runs ${cmd} on whichever device triggered the rule. HAI sends a command that can " +
'open, unlock or sound something only to a device the rule names, so it is not copied. ' +
'Name the device in Rule Machine and scan again, or keep the rule in Rule Machine.').toString())
}
got = [ok: true, refs: [RM_EVENT_DEVICE_KEY]]
} else {
got = rmActDevices(ops.devices, 'command')
if (got.ok != true) return got
}
if (cmd == null || cmd.trim().isEmpty()) {
return rmNo('RM_ACT_DEFINED_COMMAND', 'This action does not say which command to run.')
}
boolean modelled = schemaOperation(cmd) != null
Integer meterMs = null
Integer longGapSecs = null
if (ops.meterMillis != null) {
String gapText = (ops.meterMillis as String).trim()
Integer gap = (ops.meterMillis instanceof Number) ? ((Number) ops.meterMillis).intValue() :
(gapText.isInteger() ? gapText.toInteger() : null)
if (gap == null || gap < 0) {
return rmNo('RM_ACT_DEFINED_METER',
("This sends ${cmd} to its devices one at a time, and the gap between them (${ops.meterMillis}) " +
'is not a number of milliseconds HAI can read.').toString())
}
if (gap > 10000) longGapSecs = (int) Math.ceil(gap / 1000.0d)
else if (gap > 0) meterMs = gap
}
List args = []
List params = (ops.parameters instanceof List) ? ops.parameters : []
List sorted = new ArrayList(params).sort { Object a, Object b -> ((((Map) a).index ?: 0) as int) <=> ((((Map) b).index ?: 0) as int) }
for (Object o : sorted) {
Map prm = o
Map v = rmActParam(prm)
if (v.ok != true) return v
args << v.value
}
Map step = [type: 'command', refs: got.refs, command: cmd]
if (!args.isEmpty()) step.args = args
if (meterMs != null) {
step.meterMs = meterMs
step.refs = new ArrayList((List) got.refs).sort { Object a, Object b -> rmDevNum(a) <=> rmDevNum(b) }
}
if (!modelled) step.passthrough = true
if (longGapSecs != null) {
List order = new ArrayList((List) got.refs).sort { Object a, Object b -> rmDevNum(a) <=> rmDevNum(b) }
List steps = []
order.eachWithIndex { Object ref, int i ->
if (i > 0) steps << [type: 'wait', waitKind: 'delay', seconds: longGapSecs]
Map one = new LinkedHashMap(step)
one.remove('refs')
one.ref = ref
steps << one
}
return [ok: true, steps: steps]
}
return [ok: true, steps: [step]]
}
long rmDevNum(Object ref) {
String s = (ref as String) ?: ''
if (s.startsWith('dev:')) s = s.substring(4)
return s.isLong() ? s.toLong() : Long.MAX_VALUE
}
Map rmActParam(Map prm) {
String type = prm.type as String
Object raw = prm.value
if (raw == null) {
return rmNo('RM_ACT_PARAM_MISSING', "A parameter of this command has no value.")
}
if (type == 'string') return [ok: true, value: raw as String]
if (type == 'number' || type == 'decimal') {
String t = (raw as String)?.trim()
if (raw instanceof Number) return [ok: true, value: raw]
if (t == null || !(t ==~ /-?\d+(\.\d+)?/)) {
return rmNo('RM_ACT_PARAM_NUMBER',
("Rule Machine declares '${raw}' a ${type} and it is not one.").toString())
}
return [ok: true, value: type == 'decimal' || t.contains('.') ? (t as BigDecimal) : (t as Integer)]
}
return rmNo('RM_ACT_PARAM_TYPE',
("Rule Machine declares a parameter of this command to be '${type}', which HAI has not " +
'been shown.').toString())
}
Map rmActLog(Map ops) {
String text = ops.text as String
if (text == null) return rmNo('RM_ACT_LOG_TEXT', 'This log action has nothing to log.')
return [ok: true, steps: [[type: 'log', text: text]]]
}
Map rmActions(Map detailRule, List allRules = null) {
if (detailRule == null) return rmNo('RM_RULE_MISSING', 'Automation Map holds no detail for this rule.')
List acts = (detailRule.actions instanceof List) ? detailRule.actions : []
Map ruleCtx = [captured: rmCapturedRefs(acts)]
if (allRules != null) ruleCtx.knownRules = allRules.collect { rmBareId(((Map) it).id) } as Set
Set actionConds = acts.collect { rmActCondSlot(((Map) it).condition) }.findAll { it != null } as Set
Map conv = rmConditions(detailRule, actionConds)
if (conv.ok != true) return conv
Map byCondition = (conv.byCondition ?: [:])
Map byGroup = (conv.byAction ?: [:])
Map groupRefused = (conv.byActionRefused ?: [:])
List stack = []
Map frame = [kind: 'root', steps: []]
List skipped = []
for (Object o : acts) {
Map act = o
String branch = ((Map) (act.operands ?: [:])).branch as String
if (act.method == 'getWaitEvents' && act.disabled != true) {
Map w = rmActWaitEvents(act)
if (w.ok != true) return w
rmFrameAdd(frame, w.steps)
continue
}
if (act.method == 'getWaitRule' && act.disabled != true) {
Map w = rmActWaitRule(act, byGroup, groupRefused)
if (w.ok != true) return w
rmFrameAdd(frame, w.steps)
continue
}
if (act.method in ['getRepeat', 'getEndRepeat', 'getWhile', 'getUntil']) {
if (act.disabled == true) {
return rmNo('RM_ACT_BRANCH_DISABLED',
("The ${act.method == 'getRepeat' ? 'Repeat' : 'END-REP'} at step ${act.index} is turned off " +
'in Rule Machine. Leaving it out would change which steps repeat without the rule looking ' +
'any different.').toString(), act.index)
}
if (act.method == 'getRepeat' || act.method == 'getWhile') {
Map rp = act.method == 'getWhile' ? rmRepeatWhileOpen(act, acts, byGroup, groupRefused) : rmRepeatOpen(act, acts)
if (rp.ok != true) return rp
stack << frame
frame = [kind: 'repeat', step: rp.step, then: [], target: 'then']
continue
}
if (frame.kind != 'repeat') {
return rmNo('RM_ACT_BRANCH_UNBALANCED',
("Step ${act.index} is an ${act.method == 'getUntil' ? 'Until' : 'END-REP'} with no Repeat " +
'open before it. HAI will not guess which block it belongs to.').toString(), act.index)
}
Map rstep = frame.step
if (act.method == 'getUntil') {
if (rstep.repeatKind != 'every' || rstep.times != null) {
return rmNo('RM_ACT_BRANCH_UNBALANCED',
("The Until at step ${act.index} closes a Repeat that also has a count or is a " +
'Repeat While. HAI will not guess which of the two ends it.').toString(), act.index)
}
Map ucond = rmBranchCondition(act, byGroup, groupRefused)
if (ucond.ok != true) return ucond
rstep = [type: 'repeat', repeatKind: 'until', condition: ucond.node, everySeconds: rstep.everySeconds]
}
Map rbuilt = rstep + [steps: frame.then]
frame = stack.remove(stack.size() - 1)
rmFrameAdd(frame, [rbuilt])
continue
}
if (act.type != 'branch' || branch == null) {
Map r = rmAction(act, ruleCtx)
if (r.ok != true) return r
if (r.skipped == true) { skipped << [index: r.index, why: r.why]; continue }
String condSlot = rmActCondSlot(act.condition)
if (condSlot != null) {
Map gate = byCondition.get(condSlot)
if (gate == null) {
return rmNo('RM_ACT_BRANCH_EXPRESSION',
("The action at step ${act.index} only runs if condition ${act.condition} is true, and " +
'Automation Map does not hold that condition.').toString(), act.index)
}
rmFrameAdd(frame, [[type: 'if', condition: gate, then: r.steps]])
continue
}
rmFrameAdd(frame, r.steps)
continue
}
if (act.disabled == true) {
return rmNo('RM_ACT_BRANCH_DISABLED',
("The ${branch} at step ${act.index} is turned off in Rule Machine. HAI will not " +
'rebuild a rule around a disabled branch marker: leaving it out would change ' +
'which steps are conditional without the rule looking any different.').toString(),
act.index)
}
if (branch == 'if') {
Map got = rmBranchCondition(act, byGroup, groupRefused)
if (got.ok != true) return got
stack << frame
frame = [kind: 'if', condition: got.node, then: [], elseIf: [], otherwise: null, target: 'then']
continue
}
if (frame.kind != 'if') {
return rmNo('RM_ACT_BRANCH_UNBALANCED',
("Step ${act.index} is an ${branch} with no IF open before it. Rule Machine keeps " +
'markers that no longer pair up, and HAI will not guess which block this one ' +
'belongs to.').toString(), act.index)
}
if (branch == 'elseif') {
Map got = rmBranchCondition(act, byGroup, groupRefused)
if (got.ok != true) return got
((List) frame.elseIf) << [condition: got.node, then: []]
frame.target = 'elseIf'
continue
}
if (branch == 'else') {
if (frame.otherwise != null) {
return rmNo('RM_ACT_BRANCH_UNBALANCED',
"Step ${act.index} is a second ELSE in the same IF.".toString(), act.index)
}
frame.otherwise = []
frame.target = 'otherwise'
continue
}
Map built = [type: 'if', condition: frame.condition, then: frame.then]
if (!((List) frame.elseIf).isEmpty()) built.elseIf = frame.elseIf
if (frame.otherwise != null) built.otherwise = frame.otherwise
frame = stack.remove(stack.size() - 1)
rmFrameAdd(frame, [built])
}
while (frame.kind != 'root' && !stack.isEmpty()) {
Map built
if (frame.kind == 'repeat') {
built = ((Map) frame.step) + [steps: frame.then]
} else {
built = [type: 'if', condition: frame.condition, then: frame.then]
if (!((List) frame.elseIf).isEmpty()) built.elseIf = frame.elseIf
if (frame.otherwise != null) built.otherwise = frame.otherwise
}
frame = stack.remove(stack.size() - 1)
rmFrameAdd(frame, [built])
}
return [ok: true, steps: frame.steps, skipped: skipped, count: acts.size()]
}
Map rmRepeatOpen(Map act, List acts) {
Map ops = (act.operands ?: [:])
Object every = ops.everySeconds
if (!(every instanceof Number) || ((Number) every) < 1 || ((Number) every) > 86400) {
return rmNo('RM_ACT_PARAM_TYPE', "The Repeat at step ${act.index} does not store an interval from 1 second to a day.".toString(), act.index)
}
Map step = [type: 'repeat', repeatKind: 'every', everySeconds: ((Number) every).intValue()]
if (ops.times != null) {
if (!(ops.times instanceof Number) || ((Number) ops.times) < 1 || ((Number) ops.times) > 1000) {
return rmNo('RM_ACT_PARAM_TYPE', "The Repeat at step ${act.index} repeats '${ops.times}' times; HAI takes 1 to 1000.".toString(), act.index)
}
step.times = ((Number) ops.times).intValue()
}
if (ops.stoppable != true && acts.any { Map a = it; a.method == 'getStopRepeat' && a.disabled != true }) {
return rmNo('RM_ACT_PARAM_TYPE',
("The Repeat at step ${act.index} is not stoppable, but the rule has a Stop Repeating Actions. " +
'HAI stops every repeat of the rule at once, so it would stop this one too.').toString(), act.index)
}
return [ok: true, step: step]
}
Map rmRepeatWhileOpen(Map act, List acts, Map byGroup, Map groupRefused) {
Map cond = rmBranchCondition(act, byGroup, groupRefused)
if (cond.ok != true) return cond
Map ops = (act.operands ?: [:])
Map step = [type: 'repeat', repeatKind: 'while', condition: cond.node]
if (ops.everySeconds != null) {
if (!(ops.everySeconds instanceof Number) || ((Number) ops.everySeconds) < 1 || ((Number) ops.everySeconds) > 86400) {
return rmNo('RM_ACT_PARAM_TYPE', "The Repeat While at step ${act.index} does not store an interval from 1 second to a day.".toString(), act.index)
}
step.everySeconds = ((Number) ops.everySeconds).intValue()
}
if (acts.any { Map a = it; a.method == 'getStopRepeat' && a.disabled != true }) {
return rmNo('RM_ACT_PARAM_TYPE',
("The rule has a Stop Repeating Actions and a Repeat While at step ${act.index}. Whether Rule " +
'Machine stops a Repeat While has not been measured, and HAI stops every repeat.').toString(), act.index)
}
return [ok: true, step: step]
}
void rmFrameAdd(Map frame, List steps) {
if (frame.kind == 'root') { ((List) frame.steps).addAll(steps); return }
if (frame.target == 'otherwise') { ((List) frame.otherwise).addAll(steps); return }
if (frame.target == 'elseIf') {
List branches = frame.elseIf
((List) ((Map) branches[branches.size() - 1]).then).addAll(steps)
return
}
((List) frame.then).addAll(steps)
}
Map rmBranchCondition(Map act, Map byGroup, Map groupRefused) {
String group = (act.expressionGroup as Object)?.toString()
if (group == null) {
return rmNo('RM_ACT_BRANCH_NO_EXPRESSION',
"The branch at step ${act.index} does not say which condition it tests.".toString(),
act.index)
}
if (groupRefused.containsKey(group)) {
Map why = groupRefused.get(group)
return rmNo((why.code as String) ?: 'RM_ACT_BRANCH_EXPRESSION',
("The branch at step ${act.index} tests a condition HAI cannot translate: " +
"${why.message}").toString(), act.index)
}
Map node = byGroup.get(group)
if (node == null) {
return rmNo('RM_ACT_BRANCH_EXPRESSION',
("The branch at step ${act.index} tests expression ${group}, which Automation Map " +
'does not hold. The branch exists and its condition does not, so there is nothing ' +
'to rebuild it from.').toString(), act.index)
}
return [ok: true, node: node]
}
Map rmActCapture(Map ops) {
Map got = rmActDevices(ops.devices, 'capture')
if (got.ok != true) return got
return [ok: true, steps: [[type: 'capture', refs: got.refs]]]
}
Map rmActRestore(Map ruleCtx) {
List refs = (ruleCtx?.captured ?: [])
if (refs.isEmpty()) {
return rmNo('RM_ACT_RESTORE_NO_CAPTURE',
'This Restore has no Capture in the same rule to restore from, so there is nothing to put back.')
}
return [ok: true, steps: [[type: 'restore', refs: refs]]]
}
List rmCapturedRefs(List acts) {
LinkedHashSet refs = new LinkedHashSet()
(acts ?: []).each { Object o ->
Map a = o
if (a.method != 'getCapture' || a.disabled == true || a.supported != true) return
Map got = rmActDevices(((Map) (a.operands ?: [:])).devices, 'capture')
if (got.ok == true) refs.addAll((List) got.refs)
}
return refs.toList()
}
Map rmActSetColor(Map ops) {
Map got = rmActDevices(ops.devices, 'colour')
if (got.ok != true) return got
List fromVars = (ops.variableSourced instanceof List) ? ops.variableSourced : []
if (!fromVars.isEmpty()) {
return rmNo('RM_ACT_COLOR_VARIABLE',
("This colour takes its ${fromVars.join(' and ')} from a variable, and HAI cannot yet take a " +
'colour from a variable. Converting it as fixed values would change what the rule does.').toString())
}
if (!(ops.hue instanceof Number) || !(ops.saturation instanceof Number)) {
return rmNo('RM_ACT_COLOR_MODE',
("HAI has no conversion for the colour setting '${ops.colorMode ?: 'unknown'}' yet. A named colour " +
'and a custom HSB or RGB colour convert; other forms are left for a person rather than guessed.').toString())
}
List vals = [ops.hue, ops.saturation] + (ops.level instanceof Number ? [ops.level] : [])
if (vals.any { ((Number) it) < 0 || ((Number) it) > 100 }) {
return rmNo('RM_ACT_COLOR_RANGE', "HAI takes hue, saturation and level from 0 to 100, and this colour is ${vals}.".toString())
}
return [ok: true, steps: [[type: 'command', refs: got.refs, command: 'setColor', args: vals]]]
}
Map rmActSetVariable(Map ops) {
String name = ((ops.name ?: '') as String).trim().replaceAll(/\.$/, '')
if (!name) return rmNo('RM_ACT_VAR_NAME', 'This Set Variable does not say which variable it sets.')
String ref = (RM_HUB_VARIABLE_PREFIX + name).toString()
Map val = (ops.value instanceof Map) ? ops.value : null
if (val == null) return rmNo('RM_ACT_VAR_VALUE', "This Set Variable does not say what to set ${name} to.".toString())
switch (val.kind as String) {
case 'boolean':
if (!(val.value instanceof Boolean)) break
return [ok: true, steps: [[type: 'setVariable', ref: ref, value: val.value]]]
case 'number':
case 'addNumber':
Object num = rmTrigNumber(val.value)
if (num == null) {
return rmNo('RM_ACT_VAR_NUMBER', "Rule Machine stores '${val.value}' for ${name}, which is not a number.".toString())
}
return [ok: true, steps: [val.kind == 'number' ? [type: 'setVariable', ref: ref, value: num]
: [type: 'setVariable', ref: ref, op: 'add', args: [num]]]]
case 'math':
String operator = (val.operator ?: '') as String
if (!(operator in ['+', '-', '*', '/', '%'])) {
return rmNo('RM_ACT_VAR_MATH', "HAI does not know the variable operation '${operator}'.".toString())
}
String left = ((val.left ?: '') as String).trim().replaceAll(/\.$/, '')
Map right = (val.right instanceof Map) ? val.right : [:]
Object b = right.containsKey('constant') ? rmTrigNumber(right.constant)
: (right.variable ? [ref: (RM_HUB_VARIABLE_PREFIX + ((right.variable as String).trim().replaceAll(/\.$/, ''))).toString()] : null)
if (!left || b == null) {
return rmNo('RM_ACT_VAR_MATH', "This variable sum for ${name} is missing an operand.".toString())
}
return [ok: true, steps: [[type: 'setVariable', ref: ref, op: 'math',
args: [[ref: (RM_HUB_VARIABLE_PREFIX + left).toString()], operator, b]]]]
case 'variable':
String src = ((val.variable ?: '') as String).trim().replaceAll(/\.$/, '')
if (!src) return rmNo('RM_ACT_VAR_VALUE', "This copies a variable into ${name} without naming which.".toString())
Map srcRef = [ref: (RM_HUB_VARIABLE_PREFIX + src).toString()]
Object off = val.offset == null ? 0 : rmTrigNumber(val.offset)
if (off == null) return rmNo('RM_ACT_VAR_NUMBER', "The offset '${val.offset}' for ${name} is not a number.".toString())
if (((Number) off).doubleValue() == 0d) return [ok: true, steps: [[type: 'setVariable', ref: ref, value: srcRef]]]
return [ok: true, steps: [[type: 'setVariable', ref: ref, op: 'math', args: [srcRef, '+', off]]]]
case 'string':
if (val.value == null) break
return [ok: true, steps: [[type: 'setVariable', ref: ref, op: 'set', args: [val.value as String]]]]
case 'deviceAttribute':
Map got = rmActDevices(val.devices, 'variable source')
if (got.ok != true) return got
if (((List) got.refs).size() != 1 || !val.attribute) {
return rmNo('RM_ACT_VAR_DEVICE', "This sets ${name} from a device reading HAI cannot pin to one device and attribute.".toString())
}
return [ok: true, steps: [[type: 'setVariable', ref: ref, op: 'set',
args: [[ref: ((List) got.refs)[0], attribute: val.attribute as String]]]]]
}
return rmNo('RM_ACT_VAR_VALUE', "HAI has no conversion for how this rule sets ${name} ('${val.kind}').".toString())
}
Map rmActColorTemp(Map ops) {
Map got = rmActDevices(ops.devices, 'colour temperature')
if (got.ok != true) return got
if (!(ops.kelvin instanceof Number) || ((Number) ops.kelvin) < 1000 || ((Number) ops.kelvin) > 30000) {
return rmNo('RM_ACT_CT_KELVIN', "HAI takes a colour temperature from 1000 to 30000 K, and this action stores '${ops.kelvin}'.".toString())
}
List args = [ops.kelvin]
if (ops.level instanceof Number) {
if (((Number) ops.level) < 0 || ((Number) ops.level) > 100) {
return rmNo('RM_ACT_LEVEL_RANGE', "HAI takes a level from 0 to 100, and this action stores ${ops.level}.".toString())
}
args << ops.level
}
return [ok: true, steps: [[type: 'command', refs: got.refs, command: 'setColorTemperature', args: args]]]
}
Map rmActVolume(Map ops) {
Map got = rmActDevices(ops.devices, 'volume')
if (got.ok != true) return got
if (!(ops.level instanceof Number) || ((Number) ops.level) < 0 || ((Number) ops.level) > 100) {
return rmNo('RM_ACT_LEVEL_RANGE', "HAI takes a volume from 0 to 100, and this action stores '${ops.level}'.".toString())
}
return [ok: true, steps: [[type: 'command', refs: got.refs, command: 'setVolume', args: [ops.level]]]]
}
Map rmActSetLevel(Map ops) {
Map got = rmActDevices(ops.devices, 'dimmer')
if (got.ok != true) return got
Object level = ops.variable != null ? [ref: (RM_HUB_VARIABLE_PREFIX + (ops.variable as String).trim()).toString()] : ops.level
if (ops.variable == null && (!(ops.level instanceof Number) || ((Number) ops.level) < 0 || ((Number) ops.level) > 100)) {
return rmNo('RM_ACT_LEVEL_RANGE', "HAI takes a level from 0 to 100, and this action stores '${ops.level}'.".toString())
}
List args = [level]
if (ops.fadeSeconds != null) {
if (!(ops.fadeSeconds instanceof Number) || ((Number) ops.fadeSeconds) < 0 || ((Number) ops.fadeSeconds) > 3600) {
return rmNo('RM_ACT_FADE_RANGE', "HAI takes a fade from 0 to 3600 seconds, and this action stores '${ops.fadeSeconds}'.".toString())
}
args << ops.fadeSeconds
}
return [ok: true, steps: [[type: 'command', refs: got.refs, command: 'setLevel', args: args]]]
}
Map rmActAdjustLevel(Map ops) {
Map got = rmActDevices(ops.devices, 'dimmer')
if (got.ok != true) return got
if (!(ops.amount instanceof Number) || ((Number) ops.amount) < -100 || ((Number) ops.amount) > 100) {
return rmNo('RM_ACT_LEVEL_RANGE', "HAI adjusts a level by -100 to 100, and this action stores '${ops.amount}'.".toString())
}
int by = ((Number) ops.amount).intValue()
if (ops.down == true || (ops.down as String) == 'true') {
return rmNo('RM_ACT_ADJUST_FADE',
("This action adjusts the level by ${by} with fade set to true, and Rule Machine itself fails " +
"on it: it reads 'true' as a fade value, throws an error and leaves the level unchanged, so " +
'the rule does nothing there either. Turn fade off for this action in Rule Machine and scan ' +
'again.').toString())
}
return [ok: true, steps: [[type: 'command', refs: got.refs, command: 'adjustLevel', args: [by]]]]
}
Map rmActPlainCommand(String method, Map ops) {
String cmd = ['getFlashSwitch': 'flash', 'getRefreshSwitch': 'refresh', 'getPollSwitch': 'poll'][method] as String
if ((ops.command as String) != cmd) {
return rmNo('RM_ACT_COMMAND', "Automation Map reports '${ops.command}' for ${method}, where HAI expects '${cmd}'.".toString())
}
Map got = rmActDevices(ops.devices, cmd)
if (got.ok != true) return got
return [ok: true, steps: [[type: 'command', refs: got.refs, command: cmd]]]
}
Map rmActChime(Map ops) {
Map got = rmActDevices(ops.devices, 'chime')
if (got.ok != true) return got
if ((ops.op as String) != 'play') {
return rmNo('RM_ACT_CHIME_STOP', "This chime action stops the sound, and HAI has no chime stop to send.")
}
if (!(ops.sound instanceof Number) || ((Number) ops.sound) < 1 || ((Number) ops.sound) > 100) {
return rmNo('RM_ACT_CHIME_SOUND', "This chime action does not store a sound from 1 to 100 ('${ops.sound}').".toString())
}
return [ok: true, steps: [[type: 'command', refs: got.refs, command: 'playSound', args: [((Number) ops.sound).intValue()]]]]
}
Map rmActSetMode(Map ops) {
String mode = ops.mode as String
if (mode == null || mode.trim().isEmpty()) {
return rmNo('RM_ACT_MODE', ops.modeId != null ?
"This action sets mode id ${ops.modeId}, which is not one of this hub's modes.".toString() :
'This action does not say which mode to set.')
}
return [ok: true, steps: [[type: 'setMode', value: mode]]]
}
Map rmActGarage(Map ops) {
Map got = rmActDevices(ops.devices, 'garage door')
if (got.ok != true) return got
if (ops.directionConflict == true) {
return rmNo('RM_ACT_GARAGE_DIRECTION',
'Rule Machine stores two directions for this garage door that disagree, so it is not converted.')
}
String cmd = ops.command as String
if (!(cmd in ['open', 'close'])) {
return rmNo('RM_ACT_GARAGE_DIRECTION', 'This garage door action does not say whether it opens or closes.')
}
return [ok: true, steps: [[type: 'command', refs: got.refs, command: cmd]]]
}
Map rmActDeviceCommand(Map ops, String cmd, String what) {
if ((ops.command as String) != cmd) {
return rmNo('RM_ACT_COMMAND', "Automation Map reports '${ops.command}' where HAI expects '${cmd}'.".toString())
}
Map got = rmActDevices(ops.devices, what)
if (got.ok != true) return got
return [ok: true, steps: [[type: 'command', refs: got.refs, command: cmd]]]
}
Map rmActToggleLevel(Map ops) {
Map got = rmActDevices(ops.devices, 'dimmer')
if (got.ok != true) return got
if (!(ops.level instanceof Number) || ((Number) ops.level) < 1 || ((Number) ops.level) > 100) {
return rmNo('RM_ACT_LEVEL', "This toggle does not store a level from 1 to 100 ('${ops.level}').".toString())
}
return [ok: true, steps: [[type: 'command', refs: got.refs, command: 'toggleLevel', args: [((Number) ops.level).intValue()]]]]
}
Map rmActShadePosition(Map ops) {
Map got = rmActDevices(ops.devices, 'shade')
if (got.ok != true) return got
if (!(ops.position instanceof Number) || ((Number) ops.position) < 0 || ((Number) ops.position) > 100) {
return rmNo('RM_ACT_POSITION', "This shade action does not store a position from 0 to 100 ('${ops.position}').".toString())
}
return [ok: true, steps: [[type: 'command', refs: got.refs, command: 'setPosition', args: [((Number) ops.position).intValue()]]]]
}
Map rmActPing(Map ops) {
String host = ops.host as String
if (host == null || host.trim().isEmpty()) return rmNo('RM_ACT_PING', 'This ping has no address.')
return [ok: true, steps: [[type: 'ping', host: host.trim()]]]
}
Map rmActFile(Map ops) {
String op = ops.op as String
String name = ops.name as String
if (!(op in ['write', 'append', 'delete'])) return rmNo('RM_ACT_FILE', "HAI does not know the file operation '${op}'.".toString())
if (name == null || name.trim().isEmpty()) return rmNo('RM_ACT_FILE', 'This file action does not name a file.')
Map step = [type: 'file', op: op, name: name.trim()]
if (op != 'delete') step.text = (ops.text ?: '') as String
return [ok: true, steps: [step]]
}
Map rmActDirected(Map ops, List allowed, String what) {
String cmd = ops.command as String
if (!(cmd in allowed)) return rmNo('RM_ACT_COMMAND', "Automation Map reports '${cmd}' for this ${what} action.".toString())
Map got = rmActDevices(ops.devices, what)
if (got.ok != true) return got
Map step = [type: 'command', refs: got.refs, command: cmd]
if (cmd == 'startLevelChange') {
if (!(ops.direction in ['up', 'down'])) return rmNo('RM_ACT_DIRECTION', 'This raise/lower action does not say which way.')
step.args = [ops.direction as String]
}
return [ok: true, steps: [step]]
}
Map rmActFanSpeed(Map ops) {
Map got = rmActDevices(ops.devices, 'fan')
if (got.ok != true) return got
String speed = ops.speed as String
if (!(speed in ['low', 'medium-low', 'medium', 'medium-high', 'high', 'on', 'off', 'auto'])) {
return rmNo('RM_ACT_FAN_SPEED', "HAI has no fan speed '${speed}'.".toString())
}
return [ok: true, steps: [[type: 'command', refs: got.refs, command: 'setSpeed', args: [speed]]]]
}
Map rmActPushButton(Map ops) {
Map got = rmActDevices(ops.devices, 'button')
if (got.ok != true) return got
String event = (ops.event ?: 'push') as String
if (!(event in ['push', 'hold', 'doubleTap', 'release'])) {
return rmNo('RM_ACT_BUTTON_EVENT', "This action sends '${event}' to a button, which HAI does not know.".toString())
}
if (!(ops.button instanceof Number) || ((Number) ops.button) < 1 || ((Number) ops.button) > 100) {
return rmNo('RM_ACT_BUTTON_NUMBER', "This action does not store a button number from 1 to 100 ('${ops.button}').".toString())
}
return [ok: true, steps: [[type: 'command', refs: got.refs, command: event, args: [((Number) ops.button).intValue()]]]]
}
Map rmActFadeTo(Map ops) {
Map got = rmActDevices(ops.devices, 'light')
if (got.ok != true) return got
String at = ops.attribute as String
if (!(at in ['level', 'colorTemperature'])) return rmNo('RM_ACT_FADE', "HAI cannot fade '${at}'.".toString())
if (!(ops.target instanceof Number)) return rmNo('RM_ACT_FADE', 'This fade does not say what to fade to.')
if (!(ops.seconds instanceof Number) || ((Number) ops.seconds) < 1) return rmNo('RM_ACT_FADE', 'This fade does not say how long it takes.')
return [ok: true, steps: [[type: 'fade', refs: got.refs, attribute: at, to: ((Number) ops.target).intValue(),
seconds: ((Number) ops.seconds).intValue()]]]
}
Map rmActToggleColor(Map ops) {
Map got = rmActDevices(ops.devices, 'colour bulb')
if (got.ok != true) return got
if (!(ops.hue instanceof Number) || !(ops.saturation instanceof Number)) {
return rmNo('RM_ACT_COLOUR', "HAI does not know the colour '${ops.color}'.".toString())
}
List args = [ops.hue, ops.saturation]
if (ops.level instanceof Number) args << ops.level
return [ok: true, steps: [[type: 'command', refs: got.refs, command: 'toggleColor', args: args]]]
}
Map rmActToggleColorTemp(Map ops) {
Map got = rmActDevices(ops.devices, 'colour temperature bulb')
if (got.ok != true) return got
if (!(ops.kelvin instanceof Number) || ((Number) ops.kelvin) < 1000 || ((Number) ops.kelvin) > 30000) {
return rmNo('RM_ACT_COLOUR', "This toggle does not store a colour temperature from 1000 K to 30000 K ('${ops.kelvin}').".toString())
}
List args = [((Number) ops.kelvin).intValue()]
if (ops.level instanceof Number) args << ops.level
return [ok: true, steps: [[type: 'command', refs: got.refs, command: 'toggleColorTemperature', args: args]]]
}
Map rmActMusicPlayer(Map ops) {
Map got = rmActDevices(ops.devices, 'music player')
if (got.ok != true) return got
String word = (ops.action ?: '') as String
Map plain = ['play': 'play', 'pause': 'pause', 'stop': 'stop', 'mute': 'mute', 'unmute': 'unmute',
'next track': 'nextTrack', 'previous track': 'previousTrack']
plain.putAll(['toggle play/pause': 'togglePlay', 'toggle mute': 'toggleMute', 'raise volume': 'volumeUp', 'lower volume': 'volumeDown'])
if (plain.containsKey(word)) return [ok: true, steps: [[type: 'command', refs: got.refs, command: plain[word]]]]
if (word == 'play text') {
if (!(ops.text instanceof String) || ((String) ops.text).trim().isEmpty()) return rmNo('RM_ACT_COMMAND', 'This music player action says no text.')
return [ok: true, steps: [[type: 'command', refs: got.refs, command: 'playText', args: [ops.text]]]]
}
if (word in ['restore track', 'resume track']) {
if (!(ops.track instanceof String) || ((String) ops.track).trim().isEmpty()) return rmNo('RM_ACT_COMMAND', "This music player action '${word}' names no track.".toString())
return [ok: true, steps: [[type: 'command', refs: got.refs, command: word == 'restore track' ? 'restoreTrack' : 'resumeTrack', args: [ops.track]]]]
}
if (word in ['play track', 'set track']) {
if (!(ops.track instanceof String) || ((String) ops.track).trim().isEmpty()) {
return rmNo('RM_ACT_COMMAND', "This music player action '${word}' names no track.".toString())
}
return [ok: true, steps: [[type: 'command', refs: got.refs, command: word == 'play track' ? 'playTrack' : 'setTrack', args: [ops.track]]]]
}
if (word == 'set volume') {
Object vol = ops.variable != null ? [ref: (RM_HUB_VARIABLE_PREFIX + (ops.variable as String)).toString()] : ops.level
if (!(vol instanceof Map) && !(vol instanceof Number && ((Number) vol) >= 0 && ((Number) vol) <= 100)) {
return rmNo('RM_ACT_COMMAND', "This music player sets the volume to '${ops.level}'; HAI takes 0 to 100.".toString())
}
return [ok: true, steps: [[type: 'command', refs: got.refs, command: 'setVolume', args: [vol]]]]
}
return rmNo('RM_ACT_COMMAND', "HAI has no music player command for '${word}'.".toString())
}
Map rmActDeviceEnabled(Map ops) {
Map got = rmActDevices(ops.devices, 'device')
if (got.ok != true) return got
if (!(ops.enabled instanceof Boolean)) return rmNo('RM_ACT_COMMAND', 'This action does not say whether to disable or enable.')
return [ok: true, steps: [[type: 'deviceEnabled', refs: got.refs, enabled: ops.enabled]]]
}
Map rmActSiren(Map ops) {
Map got = rmActDevices(ops.devices, 'siren')
if (got.ok != true) return got
String cmd = ['siren': 'siren', 'strobe': 'strobe', 'both': 'both', 'off': 'alarmOff'][((ops.action ?: '') as String).toLowerCase()]
if (cmd == null) return rmNo('RM_ACT_COMMAND', "HAI has no siren command '${ops.action}'.".toString())
return [ok: true, steps: [[type: 'command', refs: got.refs, command: cmd]]]
}
Map rmActThermostat(Map ops) {
Map got = rmActDevices(ops.devices, 'thermostat')
if (got.ok != true) return got
Map cmds = [mode: 'setThermostatMode', fanMode: 'setThermostatFanMode', heatingSetpoint: 'setHeatingSetpoint',
coolingSetpoint: 'setCoolingSetpoint', adjustHeating: 'adjustHeatingSetpoint', adjustCooling: 'adjustCoolingSetpoint']
List present = cmds.keySet().findAll { ops[it] != null }.toList()
if (present.size() != 1) return rmNo('RM_ACT_THERMOSTAT', 'This thermostat action does not store exactly one setting.')
String k = present[0] as String
return [ok: true, steps: [[type: 'command', refs: got.refs, command: cmds[k], args: [ops[k]]]]]
}
Map rmActPerMode(String method, Map ops) {
List modes = (ops.modes instanceof List) ? ops.modes : []
if (modes.isEmpty()) return rmNo('RM_ACT_PER_MODE', 'This per-mode action lists no modes.')
Map devs = null
if (method in ['getModeSwitch', 'getDimmersPerMode', 'getColorPerMode', 'getPushButtonPerMode', 'getColorTempPerMode']) {
devs = rmActDevices(ops.devices, method == 'getModeSwitch' ? 'switch' : method == 'getPushButtonPerMode' ? 'button' : 'light')
if (devs.ok != true) return devs
}
List cases = []
for (Object mo : modes) {
Map m = mo
if (m.unreadable == true || !(m.mode instanceof String)) {
return rmNo('RM_ACT_PER_MODE', "Part of this per-mode action, for mode ${m.mode ?: m.modeId}, could not be read.".toString())
}
List then = []
switch (method) {
case 'getModeSwitch':
then << [type: 'command', refs: devs.refs, command: m.command as String]; break
case 'getChooseSwitch':
Map on = ((List) (m.on ?: [])).isEmpty() ? null : rmActDevices(m.on, 'switch')
Map off = ((List) (m.off ?: [])).isEmpty() ? null : rmActDevices(m.off, 'switch')
if (on != null && on.ok != true) return on
if (off != null && off.ok != true) return off
if (on != null) then << [type: 'command', refs: on.refs, command: 'on']
if (off != null) then << [type: 'command', refs: off.refs, command: 'off']
break
case 'getDimmersPerMode':
then << [type: 'command', refs: devs.refs, command: 'setLevel', args: [m.level]]; break
case 'getColorPerMode':
then << [type: 'command', refs: devs.refs, command: 'setColor',
args: [m.hue, m.saturation] + (m.level != null ? [m.level] : [])]; break
case 'getPushButtonPerMode':
case 'getChooseButton':
Map bd = method == 'getChooseButton' ? rmActDevices(m.devices, 'button') : devs
if (bd.ok != true) return bd
if (!(m.command in ['push', 'hold', 'doubleTap', 'release']) || !(m.button instanceof Number) ||
((Number) m.button) < 1 || ((Number) m.button) > 100) {
return rmNo('RM_ACT_BUTTON_NUMBER', "The button for mode ${m.mode} is not a number from 1 to 100 with push, hold, double tap or release.".toString())
}
then << [type: 'command', refs: bd.refs, command: m.command as String, args: [((Number) m.button).intValue()]]
break
case 'getActivateScenePerMode':
Map scd = rmActDevices(m.devices, 'scene')
if (scd.ok != true) return scd
then << [type: 'command', refs: scd.refs, command: 'on']
break
case 'getColorTempPerMode':
if (!(m.kelvin instanceof Number) || ((Number) m.kelvin) < 1000 || ((Number) m.kelvin) > 30000) {
return rmNo('RM_ACT_COLOUR', "The colour temperature for mode ${m.mode} is not from 1000 K to 30000 K.".toString())
}
then << [type: 'command', refs: devs.refs, command: 'setColorTemperature',
args: [((Number) m.kelvin).intValue()] + (m.level instanceof Number ? [m.level] : [])]
break
case 'getDelayPerMode':
if (((m.seconds ?: 0) as int) > 0) {
then << ([type: 'wait', waitKind: 'delay', seconds: m.seconds as int] + (ops.cancelable == true ? [cancelable: true] : [:]))
}
break
}
if (!then.isEmpty()) cases << [modes: [m.mode as String], then: then]
}
if (cases.isEmpty()) return rmNo('RM_ACT_PER_MODE', 'This per-mode action does nothing in any mode.')
return [ok: true, steps: [[type: 'byMode', cases: cases]]]
}
Map rmActHsm(Map ops) {
String op = ops.op as String
if (!(op in HSM_ARM_OPS)) return rmNo('RM_ACT_HSM', "HAI has no HSM command '${op}'.".toString())
return [ok: true, steps: [[type: 'hsm', op: op]]]
}
Map rmActComment(Map ops) {
return [ok: true, steps: [[type: 'comment', text: (ops.text ?: '') as String]]]
}
Map rmActHttpPost(Map ops) {
String url = ops.url as String
if (url == null || url.trim().isEmpty()) return rmNo('RM_ACT_HTTP_URL', 'This HTTP request has no address.')
Map step = [type: 'http', method: ((ops.method ?: 'post') as String), url: url]
if (ops.body != null) step.body = ops.body as String
if (ops.contentType != null) step.contentType = ops.contentType as String
return [ok: true, steps: [step]]
}
Map rmActRuleControl(String kind, Map ops, Map ruleCtx = null) {
String op = ['runRuleActions': 'runActions', 'cancelRuleTimers': 'cancelTimers',
'pauseRules': 'pause', 'resumeRules': 'resume'][kind] as String
if (op == null) return rmNo('RM_ACT_RULE_OP', 'HAI does not know which rule operation this action performs.')
if (ops.self == true) {
return rmNo('RM_ACT_RULE_SELF', 'This action acts on its own rule, which HAI has no step for yet. It is left for a person.')
}
List ids = (ops.rules instanceof List) ? ops.rules : []
if (ids.isEmpty()) return rmNo('RM_ACT_RULE_TARGET', 'This action names no rule to act on.')
String engine = (ops.engine ?: '') as String
if (engine && engine != 'Rule Machine') {
return rmNo('RM_ACT_RULE_ENGINE', ("This acts on a ${engine} rule. HAI reaches Rule Machine rules only, so it would " +
'silently do nothing.').toString())
}
Map gone = rmGoneRules(ids, ruleCtx)
if (gone != null) return gone
return [ok: true, steps: [[type: 'controlRule', rules: ids.collect { ('rm:' + (it as String)).toString() }, op: op]]]
}
Map rmActWaitEvents(Map act) {
Map ops = (act.operands ?: [:])
List events = (ops.events instanceof List) ? ops.events : []
if (events.isEmpty()) {
return rmNo('RM_ACT_WAIT_EVENTS_NONE', 'This wait does not say what it is waiting for.', act.index)
}
if (ops.useDuration == true && ops.waitSeconds != null) {
return rmNo('RM_ACT_WAIT_EVENTS_DURATION',
('This wait for events also carries a duration, which HAI has not been shown the ' +
'meaning of on a wait for events.'), act.index)
}
List built = []
for (Object o : events) {
Map e = o
Map r = rmTrigger([index: e.index, capability: e.capability, operands: e])
if (r.ok != true) return r
((List) r.triggers).each { Object ev ->
if (((Map) ev).kind == 'deviceAttribute') ((Map) ev).discipline = 'event'
}
built.addAll((List) r.triggers)
}
Map step = [type: 'wait', waitKind: 'forEvents', events: built]
Integer secs = ops.waitSeconds == null ? null : (ops.waitSeconds as Integer)
if (secs != null && secs > 0) {
step.timeoutSeconds = secs
step.onTimeout = 'continue'
}
if (events.size() > 1 || built.size() > 1) {
step.match = 'any'
}
return [ok: true, steps: [step]]
}
Map rmActWaitRule(Map act, Map byGroup, Map groupRefused) {
Map got = rmBranchCondition(act, byGroup, groupRefused)
if (got.ok != true) return got
Map ops = (act.operands ?: [:])
Object rawTime = ops.waitRaw != null ? ops.waitRaw : ops.timeoutRaw
if (rawTime != null) {
return rmNo('RM_ACT_WAIT_TIMEOUT', "HAI does not recognise '${rawTime}' as a wait time.".toString(), act.index)
}
Integer secs = ops.waitSeconds == null ? null : (ops.waitSeconds as Integer)
if (secs == null && ops.timeoutSeconds != null) {
return rmNo('RM_ACT_WAIT_TIME',
("This wait stores a time (${ops.timeoutSeconds} s) under the old name and does not say " +
'whether the condition must hold for it or whether it is a timeout. They do opposite ' +
'things, so it is left for a person.').toString(), act.index)
}
Map step = [type: 'wait', waitKind: 'until', predicate: got.node]
if (secs != null && secs > 0) {
if (ops.useDuration == null) {
return rmNo('RM_ACT_WAIT_TIME', ("This wait stores a time (${secs} s) without saying whether the condition " +
'must hold for it or whether it is a timeout. They do opposite things, so ' +
'it is left for a person.').toString(), act.index)
}
if (ops.useDuration != true) {
step.timeoutSeconds = secs
step.onTimeout = 'continue'
return [ok: true, steps: [step]]
}
step.staysSeconds = secs
}
return [ok: true, steps: [step]]
}
Map rmGoneRules(List ids, Map ruleCtx) {
Set known = (Set) ruleCtx?.knownRules
List gone = known == null ? [] : ids.findAll { !known.contains(it as String) }
if (gone.isEmpty()) return null
return rmNo('RM_ACT_RULE_GONE', ("This acts on rule ${gone.join(', ')}, which is no longer on the hub. Rule Machine " +
'kept the reference after the rule was deleted; remove it there, or here once copied.').toString())
}
Map rmNo(String code, String message, Object index = null) {
Map m = [ok: false, code: code, message: message]
if (index != null) m.index = index
return m
}
