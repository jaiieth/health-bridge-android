# Health Bridge native design tokens

The palette authority is `app/src/main/res/values/colors.xml` and `values-night/colors.xml`. The dimensions authority is `values/dimens.xml`. Activity-selected styles provide light and dark platform themes without manifest changes.

Use system sans at 28sp for the title, 20sp for sections, 16sp for body and controls, and 14sp for field labels. Text containers wrap their content. Spacing is 4, 8, 16, 24, and 32dp. Controls have minimum 48dp bounds with 8dp separation. Cards have 16dp corners and padding. Phone gutters are 24dp; wide screens use 64dp.

The cyan palette is the clone's manually chosen fallback after unsuitable marketing design suggestions, not claimed generated skill output. Foreground colors require 4.5:1 against their actual surfaces. Boundaries require 3:1. `UiTokenTest` parses both actual XML palettes and emits every ratio. Disabled controls combine color and a visible reason. Status combines text and a decorative symbol.

Keep operational state before setup, read access, and empty tests. Never show connected, working delivery, fabricated chunk progress, or inferred success. Health delivery is contained. ON records intent and waits or pauses. An exact recorded empty-test acceptance is separate from health delivery. No token or health record belongs in the display model or renderer.

The rerunnable renderer is `verification/render-mockups.sh`. Its XML-derived PNGs are design renders, not device screenshots. See `docs/ui-design-rationale.md` for behavior, provenance, commands, and runtime limitations.
