# UI design decisions

Arise O Sleeper is a native Android Views app using Material Components, the Material 3 DayNight theme, Inter, and the existing teal semantic palette. Keep this foundation. Use `UiStyle` for typography/buttons and `UiComponents` for page layout, grouped settings, dialogs and selection controls.

## Choose a control for the user's task

Evaluate selection versus action, label length, comparison needs, available space, accessibility, searchability, and when a change takes effect. Option count alone is insufficient.

| Task | Current choice | Reason and commitment |
| --- | --- | --- |
| Enable morning briefing or a filter | Material switch | An independent on/off setting that saves immediately. The briefing master controls collection and playback as well as the daily automatic run. |
| Include several briefing sources | Material checkboxes | WhatsApp, Gmail, Calendar and Tasks are independent members of a set. Changes save immediately. |
| Enable daily alarms | Material checkbox within the alarm form | Part of a draft, explicitly committed with Save along with times and session counts. Do not imply an immediate switch action. |
| Choose notification content | Independent Material checkboxes in a dialog | Count, sender names and previews can be combined freely. Require at least one part. Apply commits all parts, Cancel preserves the previous selection. |
| Choose notifications to summarize | Radio rows, separate from content checkboxes | Waiting cards and the collected briefing window are mutually exclusive sources. Keep this choice independent of the spoken parts and preserve existing users’ source selection. |
| Choose filter action, target field or match type | Visible radio rows | Alternatives need comparison while composing the rule. Save commits the entire rule. |
| Choose an app for a filter | Read-only exposed dropdown | Familiar short app names require little comparison and fit compactly within the longer form. |
| Choose Google Tasks lists | Searchable checkboxes in a dialog | Selections persist across searches by list ID. Apply saves the choices and Cancel discards them. |
| Filter tasks by recency | Radio choice plus an outlined exact days field in a dialog | All update times is the default. Only recently updated tasks enables a 1–365-day field. Explain modification time versus unavailable creation time. Apply commits both values and Cancel preserves the prior filter. |
| Choose an alarm time | Material time picker | Structured time selection respects the phone's 12/24-hour convention. Reject duplicate times. |
| Enter exact sessions, completion threshold or quiet seconds | Numeric entry with limits and validation | Exact values matter. A slider would obscure precision. Preserve drafts and show errors at the affected field. |
| Run, replay, stop, connect or add | Labeled button | These perform actions. Use a filled button for the primary next step, outlined buttons for supporting actions. |
| Delete data, a passage or a filter, or disconnect Tasks | Confirmation dialog | State the object and consequence before the user commits. Cancel is safe. |
| Browse saved passages | Search plus existing disclosure rows | Show progress summaries first, reveal details on demand. During a routine, search only references so hidden passage text stays hidden. |

Avoid segmented buttons when descriptions or enlarged labels need multiple lines. Do not replace exact entry with a slider solely because the value has bounds. Use menus for compact choices only when hiding alternatives does not impede comparison.

## Navigation and hierarchy

- The alarm screen remains the starting point. Morning briefing has a standard toolbar Back action.
- Group related settings into labeled Material surface cards. Put automatic briefing and playback first, followed by source sections, last briefing, and help/data.
- Put rarely needed setup instructions behind disclosures. Expand alarm setup help automatically when permissions or readiness steps are missing.
- Keep related buttons together. Stack action groups when their measured labels and padding cannot fit.
- Limit the main content column to 720dp, center it on wide screens, use 20dp outer gutters and 16dp card padding. Respect system bars, cutouts and the keyboard.
- Labels describe the result or setting. Explanations clarify tray notification counts versus actual unread messages, the preview window, and task date filtering.

## State, feedback and error prevention

- Explicitly state that briefing settings save automatically and alarm settings require Save.
- Preserve unfinished alarm time, enable, sessions and threshold edits across navigation and Activity recreation. Do not overwrite a newly saved backing configuration with a stale draft.
- Separate playback status from Tasks connection status. Use polite accessibility announcements for changing status and validation feedback.
- Show indeterminate progress during preparation/connection. Disable unavailable Replay/Stop actions and dependent source controls. Do not expose a disconnect action when already disconnected.
- Show a useful empty state for passages, filters, saved summaries and unsuccessful searches.
- Invalid exact values keep the form open, identify the affected field, and focus it. Filter patterns have a 120-character limit, helper text and inline errors.
- Changing briefing sources, filters or reading styles stops current speech and clears an outdated saved summary.
- Keep existing scheduling, lock-screen safeguards, private storage, notification filtering, authorization and speech behavior intact.

## Accessibility and appearance

- Use native Material controls and their checked/disabled/focus semantics rather than painted imitations.
- Provide at least 48dp interactive targets, 56dp selection rows where space allows, persistent labels or associated labels, and explanatory accessibility descriptions for repeated actions.
- Use scalable sp typography, wrapping labels, scrolling forms and adaptive button rows. Never fix a text container height merely to align neighboring controls.
- Mark section titles as accessibility headings. Disclosure headers expose expanded/collapsed state.
- Use semantic theme colors for both light and dark modes. Let Material own button ripple, focus and disabled states.
- Keep sensitive content protected by FLAG_SECURE. Test screenshots may clear it only inside an isolated emulator scenario populated with synthetic data.

## Verification for future changes

Build debug and instrumentation APKs and run unit tests. Run the alarm instrumentation suite only on an isolated emulator because it exercises real alarms/services. `UiReviewTest` uses synthetic data to verify alarm drafts, validation, radio Apply/Cancel, stable task-list selection, filter creation and screenshots.

Review portrait, landscape, dark mode, and enlarged font variants. Check long labels, keyboard visibility, small-width scrolling, empty/error states and touch targets. Screenshots are emitted to the emulator app's external files directory under `ui-review`; production screen protection remains enabled. On a personal phone, install with matching signer using an in-place update and verify existing settings are preserved. Do not clear app data or run alarm tests on it.

## Review record — 17 November 2026

- Publishing sources built successfully with 56 unit tests passing. All 25 unique instrumentation tests passed across permission-granted and permission-denied emulator configurations.
- Reviewed synthetic screenshots in light and dark portrait, landscape, and at 160% text scale on a 320dp-wide display.
- Verified radio Apply/Cancel, stable list IDs after searching, filter validation/save, confirmation Cancel/Remove, alarm draft recreation, and denied-permission Save behavior.
- Installed version 1.4 on the Vivo with the existing signing identity. Alarm and morning-briefing preference fingerprints matched before and after installation. Real-phone checks used normal navigation and left personal settings unchanged.

## References

Material 3 is the primary design reference. Android's official component guidance documents the behavior even where examples use Compose; implementation here remains Android Views.

- [Material 3 switches](https://m3.material.io/components/switch/guidelines), [Android switch guidance](https://developer.android.com/develop/ui/compose/components/switch)
- [Material 3 radio buttons](https://m3.material.io/components/radio-button/guidelines), [Android radio guidance](https://developer.android.com/develop/ui/compose/components/radio-button)
- [Material 3 checkboxes](https://m3.material.io/components/checkbox/guidelines), [Android checkbox guidance](https://developer.android.com/develop/ui/compose/components/checkbox)
- [Material segmented buttons](https://developer.android.com/develop/ui/compose/components/segmented-button)
- [Material Views text fields](https://github.com/material-components/material-components-android/blob/master/docs/components/TextField.md)
- [Material Views sliders](https://github.com/material-components/material-components-android/blob/master/docs/components/Slider.md)
- [UI UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill), supplementary native accessibility, hierarchy and responsive-layout guidance
