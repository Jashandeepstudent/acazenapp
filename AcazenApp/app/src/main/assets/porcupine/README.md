# Hey Acazen — Porcupine keyword file

Drop the trained Android keyword file here, named exactly:

    hey_acazen_android.ppn

## How to get it

1. Sign in to the Picovoice Console: https://console.picovoice.ai/
2. Go to **Porcupine** → **Create Wake Word**.
3. Phrase: `Hey Acazen`
4. Platform: **Android**
5. Train and download the `.ppn` file.
6. Rename it to `hey_acazen_android.ppn` and place it in this folder
   (`app/src/main/assets/porcupine/`), replacing this README reference.

## AccessKey

Also grab your **AccessKey** from the Picovoice Console dashboard (top of the page) and set it
as the `PORCUPINE_KEY` environment variable wherever the Android app is built — it's read at
build time in `app/build.gradle` and compiled into `BuildConfig.PORCUPINE_ACCESS_KEY`. It is
never committed to source.

Without both the `.ppn` file here and `PORCUPINE_KEY` set, the app still builds and runs fine —
the "Hey Acazen" toggle will just report itself as unsupported (`AcazenAssistant.isSupported()`
returns `false`), and the in-app sheet explains that instead of offering a dead switch.
