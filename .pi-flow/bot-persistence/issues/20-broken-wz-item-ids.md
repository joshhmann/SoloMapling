# 20: Bots never wear an item id that breaks the v83 client

**What to build:** Audit every item id a bot can be dressed in (hair, face, caps, overalls, gloves, weapons, NX
cosmetics — `BotDecoratorSystem/` pools: `BotCosmeticPool`, `BotDecorateEquips`, `QuickEquip`, `BeginnerEquip`,
`BotDecorateNX`, `NXItemPool`) against the client's `Character.wz`, and remove ids whose entries are missing, broken
UOLs, or info-only. Prior art: zmzeng/solomapling-plugin 0.3.1 (AGPL-3.0, same upstream) removed such ids after
they crashed or disconnected the client, and ships `scripts/validate_bot_config_ids.py`,
`scripts/check_client_wz.ps1` and `scripts/wzaudit/WzDeepAudit.java`. Compare their removed list with ours and
check each against our real `wz/` (sandbox copy). Credit the plugin in the ticket and any borrowed script.

**Blocked by:** none.

**Status:** done (2026-10-18) — the v83 client itself was not run; the check is the server's XML dump of Character.wz

- [x] A repeatable audit (script or test-scope main against a real `wz/`) lists every pool id and why it is bad.
- [x] Every bad id is removed from the pools; the audit then reports zero.
- [x] Ids the plugin removed are each confirmed bad (or recorded as fine) against our `wz/`.

## What was built (as landed)

Scope finding: bots do not only wear pool items. `BotDecorateEquips`/`getRandomEquip` picks any level-appropriate
equip in Character.wz, so the whole wearable catalogue (4876 equips) is in play and `EquipOmitList` is its only
filter - which the NX overlay (`BotDecorateNX` via `NXItemPool`) did not consult.

| Anchor | Change |
| --- | --- |
| `scripts/bot_item_audit.py` | stdlib-only audit of the server's XML Character.wz: missing imgs (hair base +0..7, face base +0/+100..800 as `BotCosmeticPool` rolls them; the explicit pools), info-only wearables (accessories exempt, as in the plugin), and UOLs whose relative target does not exist; `--write-omit` regenerates a marked block of `EquipOmitList.yaml`; exits 1 while any receivable id is bad |
| `soloMapling/itemPool/EquipOmitList.yaml` | generated block: 159 ids (152 broken-UOL, 7 info-only), each with the first broken link as its comment |
| `soloMapling/ArtificialPlayer/BotDecoratorSystem/NXItemPool.java` | `getRandom` skips omitted ids, so the one list covers base gear and the NX overlay |
| `soloMapling/itemPool/EquipOmitList.java` | javadoc: what the list now covers |
| `src/test/.../itemPool/EquipOmitListAuditTest.java` | the plugin's four and an info-only cap are omitted by the real parser; 8000 NX cap draws never return an omitted id |

## Findings

- Hair and face colour variants: all present (144 hair bases x 8, 11 face bases x 9).
- The plugin's four (`1082065`-`1082067` Diad Work Gloves, `1332037` Fan) are among our 152 broken-UOL equips; the
  plugin's other removals (hair/cap/overall/glove/weapon pools) are already absent from our pools - our
  `BotCosmeticPool`, `GenericEquipPool.yaml`, `NXItemPool.yaml`, `BeginnerEquip` match the plugin's byte for byte
  on ids, so upstream carries them.
- Broken links by part: `mailArm` 105, `weapon` 52, `default` 51, `defaultAc` 29 (several are Nexon typos like
  `defalutAc`), gloves 34; mostly in poses bots use (heal, shoot, prone, ladder, rope, sit, fly).
- Not every broken UOL is known to crash the client - the plugin only confirmed four. All 152 are omitted on the
  conservative side: it costs bots ~3% of the catalogue, and a bot simply rolls another item of the slot.
- NX pool ids reachable before this change: `1002186` (info-only), `1002695`, `1002839` (broken UOL).

## Verification

- `python3 -I scripts/bot_item_audit.py /tmp/solomapling-e2e/wz` -> `ACTIONABLE (a bot can receive it today): 0`
  after `--write-omit` (156 before); ~4 s.
- `EquipOmitListAuditTest` 2/2.

Credit: approach and the first four ids from zmzeng/solomapling-plugin 0.3.1 (AGPL-3.0, same upstream);
no code was copied.
