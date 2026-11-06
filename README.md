# StanControl

Android application for production and material-flow control at a metal rolling plant.

The project is being refactored incrementally. The priority is to improve separation of concerns and maintainability without changing established production workflows or the data format used by existing installations.

## Application areas

- Production shifts, packs, defects and pallets.
- Shift history, archive and backup/restore.
- PDF production forms and handover documents.
- ProControl production calendar, historical records and reporting.
- Interactive production charts.
- Home-screen widget for quick production controls.

## Current code layout

The app currently uses a screen-oriented Android architecture. Responsibilities are being moved out of the activities in small, verifiable steps.

| File | Responsibility |
| --- | --- |
| `MainActivity.kt` | Main production screen and existing shift workflows. Legacy PDF, archive and backup flows remain here pending dedicated extraction. |
| `ProControlActivity.kt` | ProControl screen orchestration, dialogs, archive workflows and reporting. |
| `ProductionModels.kt` | Production domain records: packs, defects, pallets and ProControl entries. |
| `ProductionFormatters.kt` | Shared number and date formatting helpers. |
| `ProductionControlRepository.kt` | Persistence of current ProControl production entries. |
| `ProductionChartView.kt` | Interactive chart rendering, zoom and touch handling. |
| `PackWidgetProvider.kt` | Android home-screen widget. |

### Separation principles

- Activities coordinate UI and user actions; reusable domain models do not depend on Android views.
- Persistence for current ProControl entries is isolated behind `ProductionControlRepository`.
- Interactive chart drawing and gesture handling live in a dedicated custom view.
- Formatting helpers are shared instead of duplicated between screens.
- New refactoring should be incremental and build-verified.

## Data compatibility rules

Existing user data is operational data. Treat its format as a compatibility contract.

- Keep the existing SharedPreferences file names and keys unless an explicit migration is implemented.
- Keep existing JSON property names and value units stable.
- Do not clear, recreate or silently overwrite production archives during refactoring.
- Backup import, restore and archive behavior must be reviewed separately from ordinary UI changes.
- A schema change requires a migration path and tests against representative old data.
- Do not replace synchronous persistence in critical flows without first checking the failure and lifecycle behavior.

The current ProControl repository continues to read and write the legacy `procontrol/entries` JSON representation. Successfully parsed records are retained if a later record is malformed, matching the previous recovery behavior.

## Production calculation model

Templates store packing geometry and calculation rules (rods per full row, last-row adjustment, rod length and rounding). Daily row count is entered during operation and is not stored in the template. The actual parameters used are copied into each pack record.

Initial examples to validate with production:

- Angle 50: 9 rods per full row, adjustment -4.
- Angle 40: 13 rods per full row, adjustment -6.
- Angle 30: not configured until confirmed.

These examples remain editable and are not hardcoded assumptions.

## Build

Build the debug APK with:

```bash
gradle testDebugUnitTest assembleDebug
```

Build environment used by CI:

- JDK 17
- Android SDK 35
- Gradle 8.9

The GitHub Actions workflow builds the APK and packages the source project on changes to the Android app and Gradle configuration.

## Review checklist for changes

1. Does the change preserve existing shift and archive data?
2. Are backup and restore formats unchanged, or is there a tested migration?
3. Do PDF page layout, totals and typography remain correct?
4. Does the widget still show the correct current shift state?
5. Does the debug APK compile successfully?
6. Are failures visible and diagnosable rather than silently hidden?
