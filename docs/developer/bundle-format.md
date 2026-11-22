# Portable bundle format

A bundle moves rules between hubs, or keeps them as a backup. It is built by `bundleExport` and read by
`bundleImport` in `engine/lib/hai_bundle.groovy`, and the editor's "Export for another hub" and "Import a
rule" use them.

## Three separate things

| Part | Travels | Holds |
|---|---|---|
| Portable definition | Yes | The rules, with every local id removed. Each device is described by name, room, driver and the capabilities the rule uses. |
| Binding manifest | No, it stays on the exporting hub | Which local id each reference had. Used only to restore a backup on the same hub. |
| Run state | No | Pending runs, deadlines and approvals are never exported. |

## Shape

`bundleExport` returns both parts:

```json
{
  "portable": {
    "schema": "hai.rule/1",
    "compilerVersion": "<the compiler's version>",
    "catalogueVersion": "<the operation catalogue's version>",
    "rules": [ { "schema": "hai.rule/1", "id": "...", "name": "...", "refs": { ... }, "triggers": [ ... ], "actions": [ ... ] } ]
  },
  "manifest": {
    "bindings": { "<ref key>": { ... what local id each reference had ... } },
    "provenance": { "exportedAt": 0, "from": "HAI" }
  }
}
```

**`provenance` is in the manifest, not in the portable definition, and this matters.** A bundle's content
hash is the hash of `portable` and nothing else (`bundleHash`). Put `provenance` inside `portable` and the
same rules hash differently. That quietly breaks the property `canonicalisation.md` exists to guarantee:
the same rules produce the same hash everywhere. Anything building a bundle by hand must match the shape
above exactly, `compilerVersion` included.

**Through the editor API the shape is nested one deeper.** `POST /editor/api/export` answers
`{ok: true, bundle: {portable: ...}, name: "<the rule's name>"}` — the bundle is under `bundle`, and the
manifest is not returned, because it is of no use on another hub.

## Import

**Checks:**
- A bundle with another schema, or another catalogue version, is refused with a named reason. Nothing is
  partly imported.

**Matching:**
- Each device reference is matched against this hub's devices by what it describes. It is never matched by
  the other hub's id, because device 42 on one hub has nothing to do with device 42 on another.
- Each reference comes out as one of:
  - **resolved:** matched, for a same-hub restore only;
  - **candidate:** one or more likely devices;
  - **unresolved:** none.

**In the editor:**
- A reference with exactly one likely device is filled in, and the person is asked to confirm it.
- Anything else is left for them to choose.

**The result:**
- An imported rule is an unsaved draft. It is never enabled, approved or activated by the import.
