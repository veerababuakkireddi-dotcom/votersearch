# Voter Search Android starter project

This is source code, not an installable APK. It is tailored to the supplied Telugu electoral-roll sample.

- First import copies the PDF into app-private storage.
- The app saves OCR text and card images locally; relaunching does not ask for the PDF again.
- Importing a new PDF asks before replacing the current list.
- Every card is stored separately, so duplicate names are separate search results.
- Tesseract English and Telugu language files are downloaded during first import.

Important: this is an early starter, not a tested finished app. It uses a fixed 3-column x 9-row crop based on the supplied sample. It currently displays raw OCR text rather than reliably splitting fields into name, age, gender and EPIC number. Telugu OCR accuracy and crop alignment must be tested against the complete 100-page PDF. The project has not been compiled or tested on a physical phone.

Open in Android Studio, sync Gradle, then Build > Build APK(s). Debug APK output: app/build/outputs/apk/debug/.


## GitHub Actions build

The workflow at `.github/workflows/android.yml` supports either:
1. The Android project files committed directly into the repository, or
2. The supplied source ZIP committed into the repository (the workflow extracts it before building).

Recommended: extract this ZIP and commit `settings.gradle.kts`, `build.gradle.kts`,
`gradle.properties`, `app/`, and `.github/` directly to the repository root.
Do not commit only the ZIP if you can avoid it.

The workflow uses JDK 17 and Gradle 8.9, then uploads the debug APK as the
`VoterSearch-debug-apk` artifact.
