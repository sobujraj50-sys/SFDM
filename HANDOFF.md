# SFDM (SF Download Manager) — Handoff

## প্রজেক্ট
- IDM-এর সব ফিচার + আরও উন্নত ও ইউনিক ফিচার সহ আন্তর্জাতিক মানের Android ডাউনলোড ম্যানেজার।
- রিপো: github.com/sobujraj50-sys/sfdm (ব্রাঞ্চ main)। GitHub কানেক্টর লিংক করা আছে, রিপোতে push করা যায়।
- এখন নিজে ব্যবহার করব এবং APK হিসেবে বিতরণ করব; পরে Play Store-এ দেব।
- টেক: Kotlin 2.0.21, Jetpack Compose (Material 3, BOM 2024.12.01), Room 2.6.1 (KSP), OkHttp 4.12,
  AGP 8.7.3, Gradle 8.11.1, compileSdk/targetSdk 35, minSdk 26, package com.sfdm.app
- দুটো flavor: `full` (সব ফিচার, YouTube/টরেন্ট সহ) এবং `play` (BuildConfig.PLAY_EDITION=true, Play-নিষিদ্ধ ফিচার বন্ধ)।
- GitHub Actions (.github/workflows/build.yml) প্রতি push-এ fullDebug, playDebug, fullRelease APK বানায় → Artifacts।
- Claude-এর সেশনে Android SDK নেই, তাই বিল্ড যাচাই হয় CI-তে (public API দিয়ে run status ও job log দেখা যায়)।
- Room `exportSchema = false` রাখা হয়েছে: তিনটা variant একসাথে বিল্ড হলে schema ফাইল নিয়ে KSP ভেঙে যাচ্ছিল।

## Milestone 1 — সম্পন্ন (CI বিল্ড সফল)
- engine/DownloadEngine.kt: মাল্টি-কানেকশন (১–৩২), dynamic segmentation, Range/If-Range, resume, retry+backoff,
  segment progress Room-এ সেভ, Wi-Fi/মোবাইল বাইট আলাদা হিসাব।
- engine/DownloadManager.kt: কিউ, একসাথে সর্বোচ্চ N ডাউনলোড, pause/resume/delete, নেটওয়ার্ক বদলালে নেটের অপেক্ষায় থাকা ডাউনলোড আবার চালু।
- engine/Probe.kt (সাইজ, নাম, MIME, ETag; সার্ভার Range-এ 400/416 দিলে Range ছাড়া আবার চেষ্টা)।
- engine/Storage.kt (Android 10+ এ MediaStore → Download/SFDM, পুরোনো ফোনে সরাসরি ফাইল)।
- engine/LinkResolver.kt (শুধু full): Facebook share/reel/video পেজ থেকে সরাসরি ভিডিও লিংক বের করে (HD আগে, না পেলে SD)।
  প্রতিবার start/resume-এ নতুন করে বের করে, তাই মেয়াদ শেষ হওয়া লিংক নিজে থেকে ঠিক হয়। শুধু পাবলিক ভিডিও।
- ওয়েবপেজ (text/html) লিংক দিলে HTML না নামিয়ে পরিষ্কার বাংলা ত্রুটি দেখায়।
- network/NetworkProvider.kt: requestNetwork দিয়ে নির্দিষ্ট নেটওয়ার্কে বাঁধা OkHttp client।
- service/DownloadService.kt: foreground service (dataSync), wake/wifi lock, সামারি নোটিফিকেশন, "সব থামান"।
- ui/: Compose লিস্ট, ফিল্টার, Add ডায়ালগ (Advanced অংশে কানেকশন ও নেটওয়ার্ক), সেটিংস,
  Share-to-SFDM, ক্লিপবোর্ড থেকে লিংক, ফাইল খোলা। লেখা এখন বাংলায় hardcoded।

## ফোনে টেস্ট
- প্রথম APK-তে Facebook শেয়ার লিংক (facebook.com/share/r/...) "সার্ভার ত্রুটি (400)" দিয়েছিল → LinkResolver দিয়ে ঠিক করা হয়েছে।
- নতুন APK (run 45102445689) দিয়ে Facebook লিংক আবার টেস্ট করা বাকি। Claude-এর সেশন থেকে Facebook-এ পৌঁছানো যায় না,
  তাই extraction শুধু ফোনে যাচাই করা যায়।

## নেটওয়ার্ক মোড — ইউজারের চূড়ান্ত সিদ্ধান্ত (এভাবেই রাখতে হবে)
- ডিফল্ট Auto: যে নেটওয়ার্ক কানেক্ট আছে সেটাই, কোনো প্রশ্ন বা পপআপ নয়।
- শুধু Wi-Fi / শুধু মোবাইল ডেটা / Boost (Wi-Fi + মোবাইল একসাথে) — ঐচ্ছিক, শুধু সেটিংসে
  এবং ডাউনলোড ডায়ালগের "Advanced" অংশে। ইউজার নিজে না বাছলে কখনো সামনে আসবে না।
- প্রথমবার অ্যাপ খোলার প্রশ্ন, প্রতি ডাউনলোডে বাছাই, "মোবাইল ডেটায় চালাবেন?" নোটিফিকেশন — এগুলো রাখা যাবে না।
- ক্যাটাগরি রুল ও মোবাইল ডেটা লিমিট থাকলে ডিফল্টে বন্ধ থাকবে।

## বাকি মাইলস্টোন
2. কিউ, ক্যাটাগরি অনুযায়ী ফোল্ডার, প্রতিটা ডাউনলোডের আলাদা নোটিফিকেশন, হিস্ট্রি
3. লিংক ধরা আরও উন্নত করা + ব্যাচ লিংক জেনারেটর (file001–100)
4. বিল্ট-ইন ব্রাউজার + ভিডিও শনাক্ত করা (HLS/m3u8 থেকে MP4); লগইন লাগে এমন Facebook ভিডিও এখানে
5. শিডিউলার, বাংলাদেশি রাতের প্যাকেজের জন্য ডেটা স্মার্ট মোড, স্পিড লিমিটার
6. মাল্টি-মিরর ডাউনলোড (Boost মোড M1-এ হয়ে গেছে)
7. Site Grabber (Facebook লিংক অটো রিফ্রেশ হয়ে গেছে; অন্য সাইটের জন্য বাকি)
8. নামাতে নামাতে চালানোর প্লেয়ার + প্রাইভেট ভল্ট (ফিঙ্গারপ্রিন্ট)
9. টরেন্ট/Magnet (শুধু full flavor-এ)
10. ডাউনলোড শেষে অটো কাজ: ZIP/RAR খোলা, Google Drive-এ আপলোড, চেকসাম
11. পিসি থেকে ফোনে লিংক পাঠানো (এক্সটেনশন/QR), একাধিক ভাষা (strings.xml-এ সরানো), থিম, উইজেট
12. টেস্টিং, রিলিজ কীস্টোর, GitHub Releases থেকে ইন-অ্যাপ আপডেট চেকার

## জানা বিষয় / সীমাবদ্ধতা
- **সাইনিং:** প্রতিটা CI বিল্ড আলাদা ডিবাগ কী দিয়ে সাইন হয়, তাই নতুন APK দেওয়ার আগে পুরোনোটা আনইনস্টল করতে হয়।
  স্থায়ী কী দরকার। রিপো পাবলিক, তাই কীস্টোর রিপোতে commit করা যাবে না; GitHub Secrets-এ রাখতে হবে।
- Android 15-এ dataSync foreground service দিনে প্রায় ৬ ঘণ্টা চলতে পারে → পরে বিকল্প লাগবে।
- কিছু ফোন Wi-Fi চালু থাকলে মোবাইল ডেটা ঘুমিয়ে রাখে → মোবাইল-শুধু/Boost মোডে সেটা শনাক্ত করে ইউজারকে গাইড করতে হবে।
- নাম, আইকন, ডিজাইন SFDM-এর নিজস্ব; IDM-এর লোগো বা লুক কপি করা যাবে না।

## পরের কাজ
1. ইউজার নতুন APK দিয়ে Facebook লিংক টেস্ট করে ফলাফল জানাবে।
2. স্থায়ী সাইনিং কী সেট করা (GitHub Secrets + workflow)।
3. তারপর Milestone 2।
