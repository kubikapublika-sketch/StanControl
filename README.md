# StanControl

Production and material-flow control system for a metal rolling plant.

## First release
- Daily production sheet with date and angle type.
- Configurable angle templates (50, 40, 30 and custom types).
- Row count is a working variable set during the day, not part of the angle template.
- New packs inherit the current row count. Changing it affects new packs only.
- Existing packs can be edited individually; each pack retains a snapshot of the calculation settings used.
- Pack weight, rod count and coefficient are calculated from the selected template and the pack's own row count.
- Defects are recorded separately by weight and destination/class. No coefficient is calculated for defects.
- Daily totals and A4 PDF export are planned for the first release.

## Calculation model
Templates store packing geometry and calculation rules (rods per full row, last-row adjustment, rod length and rounding). Daily row count is entered during operation and is not stored in the template. The actual parameters used are copied into each pack record.

Initial examples to validate with production:
- Angle 50: 9 rods per full row, adjustment -4.
- Angle 40: 13 rods per full row, adjustment -6.
- Angle 30: not configured until confirmed.

These examples remain editable and are not hardcoded assumptions.

## Data integrity
- Historical packs retain their original calculation snapshot.
- Template changes apply only to newly created packs.
- Daily row-count changes apply only to newly created packs; existing packs can be changed individually.
- Future warehouse movements will be recorded as linked transactions, not destructive balance edits.
