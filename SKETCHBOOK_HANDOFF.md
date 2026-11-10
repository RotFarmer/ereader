# Sketchbook handoff

Continue on `feature/sketchbooks-350-pages` using draft PR #2.

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
