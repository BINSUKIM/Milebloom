package com.milebloom.app.speech

import android.content.Context
import android.util.Log

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener

/**
 * SpeechToText SDK 래퍼 — 음성 기록 버튼 1개(D-010)의 입구.
 * 오디오는 저장하지 않고 텍스트만 넘긴다 (스키마 13.5). Gleo AI가 아니다 (D-009-R).
 */
class VoiceIn(private val context: Context) {

    interface Callback {
        fun onListening() {}
        fun onPartial(text: String) {}
        fun onFinal(text: String)
        fun onEnded() {}
        fun onError(reason: String)
    }

    private var stt: SpeechToText? = null
    private var ready = false
    private var listening = false
    private var callback: Callback? = null
    private var lastText = ""

    private val results = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() { callback?.onListening() }
        override fun onUpdated(stt: String, completed: Boolean) {
            lastText = stt
            if (completed) callback?.onFinal(stt) else callback?.onPartial(stt)
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() {
            listening = false
            callback?.onEnded()
        }
        override fun onError(errorType: ErrorType) {
            listening = false
            Log.w(TAG, "STT 오류 $errorType")
            callback?.onError("$errorType")
        }
    }

    val isListening: Boolean get() = listening

    fun listen(cb: Callback) {
        if (listening) return
        callback = cb
        lastText = ""
        if (ready) {
            request()
            return
        }
        try {
            val s = stt ?: SpeechToText(context.applicationContext, Mode.ON_DEVICE).also { stt = it }
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    ready = true
                    try { s.addListener(results) } catch (e: Throwable) { Log.e(TAG, "addListener 실패: ${e.message}") }
                    request()
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.e(TAG, "STT 초기화 실패 $errorType")
                    cb.onError("init: $errorType")
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "STT 생성 실패: ${e.message}")
            cb.onError("create: ${e.javaClass.simpleName}")
        }
    }

    private fun request() {
        try {
            listening = true
            stt?.request()
        } catch (e: Throwable) {
            listening = false
            callback?.onError("request: ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        if (!listening) return
        try { stt?.stop() } catch (_: Throwable) {}
    }

    fun release() {
        val s = stt ?: return
        try {
            if (listening) s.stop()
            s.removeListener(results)
            s.release()
        } catch (_: Throwable) {}
        stt = null
        ready = false
        listening = false
    }

    private companion object {
        const val TAG = "MBSTT"
    }
}
