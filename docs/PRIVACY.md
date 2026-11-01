# Privacy

What NibTeXa sends off the device, and when. NibTeXa has no accounts or ads, and its developer receives none of your data. For questions, open an issue at
https://github.com/Iamthebarrow/nibtexa/issues.

## Your notes

Notes, ink, recognition results and exports stay on the device. They leave
it only when you run **Git sync** to a remote you configured, or make a backup, which goes where you choose. Automatic backups go to the folder you pick.

Importing reads only the file you pick in the system file
picker (and, for a project `.zip`, the `.tex` or `.md` files and pictures
inside it). Nothing in it is run or fetched: pictures a Markdown file links on
the web are not downloaded, and a PDF is read by Android's own PDF renderer.
Pictures you insert come from the system photo picker, which gives the app only
the picture you choose; the app keeps a copy in its private storage.

A backup holds your booklets, ink, results and pictures, and goes only
where you save it. It is not encrypted, so keep it somewhere you trust. Booklets you
delete stay on the device in the Trash until you delete them for good, or for 30 days
unless you turn that off.

The clipboard keeps what you copy on the device: the latest 50 entries,
plus any you pin, after the app closes. An entry stays even when the page or booklet
it came from is deleted, so deleting a booklet for good doesn't remove what you
copied from it. Delete an entry, or use **Clear all** in the clipboard panel, to
remove it. A backup of the whole library includes the clipboard; a backup of chosen
booklets doesn't. Confirmed equations and words you copy also go to Android's
clipboard as plain text, where other apps you open may read them. Turn off **Also
copy to other apps** in Settings to stop that. Ink, pictures and graphs never go
there.

If you turn on the sync setting that uses library folders, the names of
those folders become part of the path in your repository. It is off by default.

Links you add to your notes are only web or mail addresses. Tapping
one in the reading view opens it in your browser or mail app; nothing is
fetched otherwise.

## Reading handwritten words (Google ML Kit)

Text recognition uses Google ML Kit Digital Ink Recognition.

- **Your handwriting stays on the device.** Google: "The user's handwriting
  stays on the device", and recognition "is performed without any network
  connection".
- **The language pack** (English, about 20 MB) is downloaded from Google when the app opens without it, or when you choose *Download
  handwriting language…*. On a metered connection the app asks first. Once it
  is on the device, nothing more is downloaded.
- **ML Kit sends usage metrics to Google**, over HTTPS. The documented
  categories are:
  - device information (manufacturer, model, OS version and build);
  - app information (package name, app version);
  - performance metrics, API configuration, input and output sizes;
  - event types (including model downloads) and error codes;
  - per-installation identifiers;
  - for Digital Ink, the configured languages.

  ML Kit may also contact Google for bug fixes, updated models and hardware
  compatibility information. No way to turn this off is documented. **This is the one thing the app itself sends without
  your asking.**
- Sources, checked 2026-10-28:
  - [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
  - [ML Kit terms](https://developers.google.com/ml-kit/terms)
  - [Digital Ink overview](https://developers.google.com/ml-kit/vision/digital-ink-recognition)
- **Not verified by us:** the metrics traffic itself has not been captured or
  inspected. Recognition was checked to work in airplane mode on the AVD.

## Reading handwritten maths

The model isn't part of the app. If you choose **Download math recognition…**, the app fetches it from its authors at huggingface.co, which sees your connection as any website does. After that it runs on the device through ONNX Runtime and sends nothing.

## Permissions

`INTERNET`, for Git sync, these downloads and ML Kit's own traffic. `ACCESS_NETWORK_STATE`, to tell a metered connection from Wi-Fi. Libraries in the app, ML Kit among them, add a few more for their own work; none reads your files or location.

## Your rights over your notes

Everything is in the app's private storage on your device. Deleting a booklet for good, or uninstalling the app, removes it, and it can't then be recovered unless you have a backup or a
copy in your repository. What you pushed with Git sync stays on the server you chose, under that server's terms.
