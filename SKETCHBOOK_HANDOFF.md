# Sketchbook handoff

The sketchbook work now lives on the main branch (`ccr-c9250789-tpqdf1`).

The Library grid combines regular books and sketchbooks. The editor has dark
paper, explicit page navigation, a thumbnail browser, pressure input, pan and
zoom, selection and shapes, and page-level undo. References can sit below ink
or beside the page. Excerpts carry a link back to their book. Exports default to
light paper, and editable ZIP backups restore as independent sketchbooks.

`SketchStore` v2 keeps stable page identities and preserves the original slots
used by ink, references and paper. The migration retains a v1 recovery copy.
Completed gestures are written through `SketchWriter` and recorded as deltas.
Import uses a staging directory and commits the selected pages in one
transaction. Keep the source files, reference transforms and ink independent.

Run `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`
on Windows, or the corresponding `./gradlew` command elsewhere. The workspace
has JDK 17 and Android SDK 35 under `E:/ai-workers/tools/android-build`. Local
build outputs are under `E:/ai-workers/work/ereader-build` when using the
build script at `E:/ai-workers/tmp/build-ereader.ps1`.

Remaining validation and polish:

- Test pressure, hover, the barrel button, palm rejection and sustained drawing
  on a physical Galaxy Tab S9 FE+. Verify dense pages and large documents with a
  memory trace.
- Exercise DOCX images and formatting, PDF page range cancellation, linked
  excerpt round trips, and reader annotations on-device.
- The export menu offers the current page, used pages, or all pages for PDF,
  and the current page for PNG. Add arbitrary page-range export and an export
  preview. Reference crops use sliders. DOCX pagination is reflowed.
- Review the editor and reference panel in portrait, landscape, and both
  themes. Import selection can be canceled during the commit stage. Add
  progress and cancellation to the initial document preparation stage.

## Changes after review (merged into the main branch)

- `SketchStore.savePage` and undo apply only what a change touched (strokes,
  references by id, paper only if it changed). An editor holding an older copy
  of the page can no longer delete a reference imported by another window.
- `SketchActivity` reloads the page in `onRestart`, keeps one canvas for its
  lifetime (rotation keeps the tool, selection and arranged reference), queues
  storage work instead of dropping it, and saves its state for recreation.
- One shared `SketchStore.of(context)` per process; `SketchWriter` retries
  failed saves with backoff and no longer blocks Back.
- The library survives sketchbook database errors; the 30-day purge runs in the
  background once a day and only searches the journal for orphaned files.
- Reference decodes are sized to fit the canvas cache (no decode loop when
  zoomed); library and page-browser previews update one card at a time.
- Backups are buffered; PDF export draws references at about 150 dpi.
- Large journal entries are read in pieces (no 2 MB cursor-window limit).
- EPUB and MOBI import as references, with progress and cancel while preparing.
- Page-range PDF export, swipe page turning at normal zoom, rotation that needs
  a deliberate twist and snaps to 90°, a scrollable pen toolbar, a 10,000-page
  limit, and temporary PDF-region images kept in the cache.
- `SketchImporter` (unused) was removed.

