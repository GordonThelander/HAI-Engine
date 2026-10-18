import groovy.transform.Field
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.Instant
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

definition(
name: 'HAI Checker (Preview)',
namespace: 'gordonthelander',
author: 'HAI',
description: 'Part of HAI Engine: checks and simulates rules for it. HAI Engine installs and updates it; there is nothing to set.',
category: 'Automations',
parent: 'gordonthelander:HAI Engine (Preview)',
iconUrl: '',
iconX2Url: ''
)
preferences {
page(name: 'checkerPage')
}
def checkerPage() {
dynamicPage(name: 'checkerPage', title: 'HAI Checker', install: true, uninstall: false) {
section {
paragraph 'This is part of HAI Engine, which installs and updates it. There is nothing to set here.'
}
}
}
def installed() { }
def updated() { }
Map checkerRun(Map req) {
if (req?.build != CHECKER_BUILD) {
return [checkerError: 'CHECKER_MISMATCH', build: CHECKER_BUILD,
message: 'HAI Engine and HAI Checker are from different versions.']
}
return checkerDispatch(req)
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
@Field static final List HAI_OWN_FILES = [
'hai-(editor|migration|help|toolkit)[.]html', 'hai-editor-specs[.]json', 'hai-author-guide[.]txt', 'hai-am-(capabilities|state)[.]json',
'hai-engine-[0-9]+-[a-z0-9_]+[.]json', 'hai-backup-[0-9]{8}[.]json', 'ham-decode(-detail)?(-dev)?[.]json',
]
String schemaFileProblem(Object name) {
String n = name == null ? '' : name.toString()
if (!(n ==~ /[A-Za-z0-9][A-Za-z0-9._-]{0,63}/)) return "'${n}' is not a file name: letters, digits, dots, dashes and underscores, up to 64".toString()
String low = n.toLowerCase()
if (HAI_OWN_FILES.any { String p -> low ==~ p }) return "'${n}' is one of HAI's own files".toString()
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
if (ref.kind == 'device') vResolveDevice(r, ref, path, inv, !outsideEnable.contains(k), vCommandsSentTo(doc, k as String))
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
Set vCommandsSentTo(Map doc, String key) {
Set out = [] as Set
Closure walk
walk = { Object v ->
if (v instanceof List) { ((List) v).each { walk(it) }; return }
if (!(v instanceof Map)) return
Map m = v
if (m.type == 'command' && m.command instanceof String && (m.ref == key || (m.refs instanceof List && ((List) m.refs).contains(key)))) out << m.command
m.values().each { Object x -> if (x instanceof List || x instanceof Map) walk(x) }
}
walk([doc.actions, doc.onFailure, doc.onCancel])
return out
}
void vResolveDevice(Map r, Map ref, String path, Map inv, boolean onlyEnabled = false, Set sent = null) {
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
Set listed = d.commands instanceof Collection ? ((Collection) d.commands).collect { it as String } as Set : null
((ref.capabilities ?: []) as List).each { cap ->
List needing = ((sent ?: []) as List).findAll { Object c -> ((List) (schemaOperation(c as String)?.requires ?: [])).contains(cap) }
if (listed != null && !needing.isEmpty() && listed.containsAll(needing)) return
if (!((d.capabilities ?: []) as Set).contains(cap)) {
vBlock(r, 'REF_MISSING_CAPABILITY', path, "'${d.name}' does not have the ${cap} capability this rule needs")
}
}
}
String vNearVariable(Map inv, String wanted) {
if (!wanted) return null
String w = wanted.startsWith('hv:') ? wanted.substring(3) : wanted
Closure<String> norm = { String x -> x.toLowerCase().replaceAll(/^[\s\p{Punct}]+|[\s\p{Punct}]+$/, '') }
List names = ((Map) (inv?.variables ?: [:])).values().findAll { it instanceof Map && ((Map) it).name instanceof String }
.collect { ((Map) it).name as String }.findAll { it != w }
List same = names.findAll { norm(it) == norm(w) }
if (same.size() == 1) return same[0]
if (!same.isEmpty()) return null
List one = names.findAll { vOneEdit(it, w) }
return one.size() == 1 ? (String) one[0] : null
}
boolean vOneEdit(String a, String b) {
if (a == null || b == null || a == b || Math.abs(a.length() - b.length()) > 1 || Math.min(a.length(), b.length()) < 4) return false
int i = 0
while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++
if (a.length() == b.length()) return a.substring(i + 1) == b.substring(i + 1)
return a.length() > b.length() ? a.substring(i + 1) == b.substring(i) : a.substring(i) == b.substring(i + 1)
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
if (v == null) {
String near = vNearVariable(inv, (ref.name ?: ref.id) as String)
vBlock(r, 'REF_MISSING', path, "no variable matches '${ref.name ?: ref.id}'" + (near ? ". Did you mean '${near}'?" : ''))
return
}
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
} else if (k == 'STRING' && !(v instanceof String) && !(v instanceof Number)) {
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
String vSwitchEffect(Map a) {
String c = a.command as String
if (c == 'off') return 'off'
if (c == 'on' || c == 'setColor' || c == 'setColorTemperature' || c == 'setHue' || c == 'setSaturation') return 'on'
if (c == 'setLevel') {
Object v = a.args instanceof List && !((List) a.args).isEmpty() ? ((List) a.args)[0] : null
return v instanceof Number && ((Number) v).doubleValue() <= 0 ? 'off' : (v instanceof Number ? 'on' : null)
}
return null
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
String effect = attr == 'switch' ? vSwitchEffect(a) : null
if (writers.containsKey(slot)) {
if (effect == null || writers.get("${slot}=".toString()) != effect) {
vWarn(r, 'BEHAVIOUR_OVERLAPPING_WRITE', path,
"an earlier step in this rule already sets ${attr} on this device; this later one wins")
}
} else {
writers.put(slot, path)
if (effect != null) writers.put("${slot}=".toString(), effect)
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
if (rec.disabled && cause.once != true) return 'disabled'
if (!coreAdmissible(rec) && cause.once != true) {
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
if (rec == null || (rec.disabled && !coreOnce(run))) return false
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
Map cause = [kind: 'manual', by: by]
if (!coreAdmissible(rec)) cause.once = true
return coreConsider(w, rec, cause, 0)
}
boolean coreOnce(Map run) { return ((Map) run?.cause)?.once == true }
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
if (run.later == null && !rec.queue.isEmpty() && (coreAdmissible(rec) || ((Map) rec.queue[0]).once == true)) {
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
if (rec == null || !rec.paused || ((Map) rec.doc).pauseMode != 'hold' || coreOnce(run)) return false
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
else if (!first && rec.paused && doc.pauseMode != 'hold' && !coreOnce(run)) why = 'paused'
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
(g.tick as int) > 0 && rec.paused && doc.pauseMode != 'hold' && !coreOnce(run) ? 'paused' : null
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
if (rec == null || (rec.disabled && !coreOnce(run))) return [allowed: false, reason: 'rule no longer enabled']
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
if (doc.requiredExpression != null) out << 'requiredExpression'
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
@Field static final int EDITOR_MAX_BODY_CHARS = 262144
@Field static final int EDITOR_MAX_SENTENCES = 2048
@Field static final int EDITOR_MAX_REQUIREMENT_PATHS = 16
@Field static final String EDITOR_EFFECTS = "Save draft keeps this rule here, in the editor, and changes nothing on the hub. Activate on hub (Update on hub for a rule already there) is the one that puts it live, and HAI checks it before it runs. Run rule runs the hub's version once, now."
@Field static final String EDITOR_CHECK_LABEL = 'Checked by the hub in the editor. This is not an AI proposal, and nothing was stored.'
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
@Field static final String CHECKER_BUILD = '75da22ab46fc617d'
Map checkerDispatch(Map req) {
String op = req?.op as String
switch (op) {
case 'check': return checkerEdCheck(req)
case 'export': return editorExport(req.body, req.inv, (req.now ?: 0L) as long)
case 'import': return editorImport(req.body, req.inv)
case 'review': return checkerReviewRule(req)
case 'mcp': return checkerMcpTool(req)
case 'dryRun': return simDryRun((Map) req.doc, (req.now ?: 0L) as long, (req.zone ?: 'UTC') as String, req.inv)
case 'draftStart': return authorStart((req.request ?: '') as String, req.inv, (req.opts ?: [:]))
case 'draftSend': return authorPrepareSend((Map) req.session, (req.now ?: 0L) as long)
case 'draftReply':
return authorOnResponse((Map) req.session, req.inv, (req.status ?: 0) as int, req.body as String,
(req.headers ?: [:]), (req.now ?: 0L) as long)
}
return [checkerError: 'CHECKER_NO_SUCH_OP', message: "HAI Checker has no operation called ${op}.".toString()]
}
Map checkerEdCheck(Map req) {
Map opts = new LinkedHashMap((Map) (req.opts ?: [:]))
Map out = editorCheck(req.body, req.inv, opts)
if (opts.editorTiming instanceof Map) out = new LinkedHashMap(out) + [timing: opts.editorTiming]
return out
}
Map checkerReviewRule(Map req) {
Map doc = req.doc
Map inv = req.inv
Map v = validateRule(doc, inv)
List simulation = []
String problem = null
if (req.simulate == true && vActivatable(v)) {
try {
simulation = proposeScenarios(doc, inv, (req.now ?: 0L) as long, (req.zone ?: 'UTC') as String, (req.sun ?: [:]))
} catch (Exception e) {
problem = "the rule could not be read back or simulated: ${e.message}".toString()
}
}
return [validation: v, simulation: simulation, problem: problem]
}
Map checkerMcpTool(Map req) {
Map args = (req.args ?: [:])
Map inv = req.inv
switch (req.name as String) {
case 'hai_describe':
return [guide: authorSystem(), commands: CATALOGUE.keySet().sort(),
context: authorContext(inv, args.request instanceof String ? (String) args.request : '')]
case 'hai_render_rule':
Map v = validateRule((Map) args.rule, inv)
return [rendering: renderRule((Map) args.rule), findings: v.findings, activatable: vActivatable(v)]
case 'hai_propose_rule':
return proposeEvaluate((Map) args.rule, args.requirements, inv, null, (req.opts ?: [:]))
}
return [checkerError: 'CHECKER_NO_SUCH_OP', message: "HAI Checker has no MCP tool called ${req.name}.".toString()]
}
