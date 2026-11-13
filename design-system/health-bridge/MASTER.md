# Health Bridge design tokens

## Source of truth

| Rule | Contract |
| --- | --- |
| Authority | `app/src/main/res/values/colors.xml`, `values-night/colors.xml`, and `values/dimens.xml` are authoritative. This file must match them. `DocTokenParityTest.resourceTablesMatchXml` checks exact token sets and values. |
| Scope | Resource and code reference at b387fea. Ratios use WCAG sRGB relative luminance, rounded to three decimals. L/D means light/dark. |

## Colours

| Token | Light | Dark | Use in MainActivity | Pair and required minimum | Measured L/D |
| --- | --- | --- | --- | --- | --- |
| `hb_background` | #F3F8FA | #0D191F | Page fill | hb_text: 4.5:1 | hb_text: 12.385:1/15.487:1 |
| `hb_surface` | #FFFFFF | #172A33 | Cards and enabled inputs | hb_text: 4.5:1 | hb_text: 13.258:1/12.867:1 |
| `hb_text` | #163341 | #E4F1F6 | Body, headings, enabled input text | hb_surface: 4.5:1; hb_background: 4.5:1 | hb_surface: 13.258:1/12.867:1; hb_background: 12.385:1/15.487:1 |
| `hb_muted` | #455E6B | #B3C8D2 | Input hint text | hb_surface: 4.5:1; hb_background: 4.5:1 | hb_surface: 6.848:1/8.555:1; hb_background: 6.398:1/10.297:1 |
| `hb_primary` | #00657B | #8CD5E8 | Enabled buttons, switch thumb, focused input outline | hb_surface: 4.5:1; hb_background: 4.5:1; hb_disabled: 4.5:1 | hb_surface: 6.672:1/9.042:1; hb_background: 6.233:1/10.883:1; hb_disabled: 5.288:1/6.599:1 |
| `hb_on_primary` | #FFFFFF | #003542 | Enabled button text | hb_primary: 4.5:1 | hb_primary: 6.672:1/8.060:1 |
| `hb_off` | #455E6B | #B3C8D2 | OFF status and icon | hb_surface: 4.5:1; hb_background: 4.5:1 | hb_surface: 6.848:1/8.555:1; hb_background: 6.398:1/10.297:1 |
| `hb_blocked` | #A12029 | #FFB4B8 | Blocked status, icon, validation errors | hb_surface: 4.5:1; hb_background: 4.5:1 | hb_surface: 7.625:1/8.812:1; hb_background: 7.123:1/10.606:1 |
| `hb_paused` | #785300 | #F4CE80 | Paused status and icon | hb_surface: 4.5:1; hb_background: 4.5:1 | hb_surface: 6.917:1/9.882:1; hb_background: 6.462:1/11.894:1 |
| `hb_waiting` | #00657B | #8CD5E8 | Waiting status and icon | hb_surface: 4.5:1; hb_background: 4.5:1 | hb_surface: 6.672:1/9.042:1; hb_background: 6.233:1/10.883:1 |
| `hb_unknown` | #615174 | #D4BFEA | Unknown status and icon | hb_surface: 4.5:1; hb_background: 4.5:1 | hb_surface: 7.149:1/8.789:1; hb_background: 6.679:1/10.579:1 |
| `hb_outline` | #607784 | #90AAB7 | Card and input boundaries | hb_surface: 3:1; hb_background: 3:1; hb_disabled: 3:1 | hb_surface: 4.699:1/6.090:1; hb_background: 4.390:1/7.330:1; hb_disabled: 3.724:1/4.445:1 |
| `hb_disabled` | #DFE6EA | #2C404A | Disabled buttons, inputs, switch track | hb_on_disabled: 4.5:1; hb_primary: 4.5:1 | hb_on_disabled: 5.428:1/6.244:1; hb_primary: 5.288:1/6.599:1 |
| `hb_on_disabled` | #455E6B | #B3C8D2 | Disabled button and input text | hb_surface: 4.5:1; hb_background: 4.5:1; hb_disabled: 4.5:1 | hb_surface: 6.848:1/8.555:1; hb_background: 6.398:1/10.297:1; hb_disabled: 5.428:1/6.244:1 |

| Contrast policy | Meaning |
| --- | --- |
| Text | 4.5:1, including large and disabled text as a project requirement. |
| Boundaries | 3:1 for outline against adjacent fills. Fill colours have no independent text minimum; their rows report the paired foreground requirement. |
| Verification | `app/src/test/java/dev/healthbridge/UiTokenTest.kt`, `allForegroundsPassBothThemes`, checks foreground pairs in both themes. Resource parity does not validate prose or measured ratios. |

## Dimensions

| Token | Value | Use |
| --- | --- | --- |
| `hb_space_xs` | 4dp | Small spacing and focus stroke |
| `hb_space_sm` | 8dp | Control separation and padding |
| `hb_space_md` | 16dp | Card and button padding |
| `hb_space_lg` | 24dp | Phone gutter, card top margin, icon size |
| `hb_space_xl` | 32dp | Bottom gutter; twice this value for wide gutter |
| `hb_touch_min` | 48dp | Minimum control bounds |
| `hb_corner` | 16dp | Card corners |
| `hb_control_corner` | 8dp | Input corners |
| `hb_elevation` | 2dp | Card elevation |
| `hb_type_title` | 28sp | Title |
| `hb_type_section` | 20sp | Section and switch text |
| `hb_type_body` | 16sp | Body and controls |
| `hb_type_label` | 14sp | Field labels |

## State colours

| State | Colour | Text and symbol contract |
| --- | --- | --- |
| OFF | `hb_off` | OFF and stop icon |
| WAITING | `hb_waiting` | ON, waiting / paused and clock icon |
| PAUSED | `hb_paused` | Paused and pause icon |
| BLOCKED | `hb_blocked` | Blocked and warning icon |
| UNKNOWN | `hb_unknown` | Unknown and help icon |
| acknowledged-test | `hb_text` | Separate test text, no success colour or health success inference |
| disabled | `hb_on_disabled` on `hb_disabled` | Disabled controls have a visible reason |

| Implementation | Symbol |
| --- | --- |
| State selection and labels | `app/src/main/java/dev/healthbridge/SyncStatusDisplay.kt`: `format`, `State`, `testResult` |
| Colour binding and disabled controls | `app/src/main/java/dev/healthbridge/MainActivity.kt`: `refresh`, `button`, `input`, `text` |

## Provenance

| Item | Evidence or limitation |
| --- | --- |
| Skill repository | Local `/home/ubuntu/tools/ui-ux-pro-max-skill`, commit `6ae86f61f520d6ca229d2f40e070410638c908c5` |
| CLI | CLI package `ui-ux-pro-max-cli 2.5.0` from `cli/package.json`; repository commit `6ae86f61f520d6ca229d2f40e070410638c908c5` dated 2026-11-10; no other version string is declared, and an earlier 2.15.0 claim is withdrawn as unverified. |
| Scoped searches | Six offline searches run for this documentation pass through `.claude/skills/ui-ux-pro-max/scripts/search.py`, each with `-n 2`; queries below are exact. They explain applicable rules, not historical generation of this UI. |
| Not skill output | Layout composition, exact palette, and section order are manual choices. Exact dimension values and native system sans are repository choices. No generated font or layout recommendation is claimed as implemented. |

| Query | Domain | Returned rule | Current decision and implementation |
| --- | --- | --- | --- |
| contrast text | ux | Color Contrast; Contrast Readability | Text minimum 4.5:1; `UiTokenTest.allForegroundsPassBothThemes` |
| touch target size | ux | Touch Target Size | Android 48dp; `MainActivity.button`, `input`, `onCreate` switch |
| form labels error feedback | ux | Form Labels; Submit Feedback | `MainActivity.input` uses labelFor; save listener shows saving and result feedback |
| visible focus keyboard | ux | Focus States | Focus outline in `MainActivity.input`; platform button focus retained. Runtime focus coverage unverified. |
| status color icons | ux | Color Only; Submit Feedback | `MainActivity.refresh` binds equivalent status text and decorative icon |
| font size readability | typography | SaaS Mobile Boutique; Neumorphism Mobile | Font recommendations rejected. Existing system sans and sp scale retained; no exact scale attributed to these rules. |

| Manual decision | Basis |
| --- | --- |
| 4/8/16/24/32dp spacing, 16/8dp corners, 2dp elevation | Existing XML and `MainActivity.params`, `card`, `input`; no scoped rule supplies these exact values |
| OFF first, setup, access, test section order | Existing `MainActivity.onCreate`; operational containment semantics |
| Cyan palette and dark variants | Existing XML; contrast measured above rather than inferred from a skill palette |
| No delivery progress or success inference | `SyncStatusDisplay.progress`, `format`, `testResult`; `Containment.run` |
