package com.sfdm.app.ui

import com.sfdm.app.data.DownloadStatus
import com.sfdm.app.data.NetworkMode

object Labels {
    fun mode(m: NetworkMode) = when (m) {
        NetworkMode.AUTO -> "Auto"
        NetworkMode.WIFI_ONLY -> "শুধু Wi-Fi"
        NetworkMode.MOBILE_ONLY -> "শুধু মোবাইল ডেটা"
        NetworkMode.BOOST -> "Boost (Wi-Fi + মোবাইল ডেটা)"
    }

    fun modeHint(m: NetworkMode) = when (m) {
        NetworkMode.AUTO -> "যে নেটওয়ার্ক কানেক্ট আছে সেটা দিয়েই নামাবে"
        NetworkMode.WIFI_ONLY -> "মোবাইল ডেটা খরচ হবে না; Wi-Fi না থাকলে থেমে থাকবে"
        NetworkMode.MOBILE_ONLY -> "Wi-Fi কানেক্ট থাকলেও সিম ডেটা দিয়ে নামাবে"
        NetworkMode.BOOST -> "দুটো নেটওয়ার্ক একসাথে — মোবাইল ডেটাও খরচ হবে"
    }

    fun status(s: DownloadStatus) = when (s) {
        DownloadStatus.QUEUED -> "সারিতে"
        DownloadStatus.CONNECTING -> "সংযোগ হচ্ছে…"
        DownloadStatus.RUNNING -> "চলছে"
        DownloadStatus.PAUSED -> "থামানো"
        DownloadStatus.WAITING_NETWORK -> "নেটওয়ার্কের অপেক্ষায়"
        DownloadStatus.COMPLETED -> "সম্পন্ন"
        DownloadStatus.FAILED -> "ব্যর্থ"
    }
}
