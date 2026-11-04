package com.milebloom.app.speech

import android.content.Context
import android.util.Log
import java.util.Locale

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener

/**
 * TextToSpeech SDK 래퍼. 첫 [speak] 때 초기화하고, 초기화 중 들어온 문장은 하나만 기억했다가 말한다.
 * 실제 재생은 Pleos 음성 서비스 프로세스가 맡는다 (13번 14-9). 음악 덕킹 여부는 미확인.
 */
class VoiceOut(private val context: Context) {

    enum class State { IDLE, INITIALIZING, READY, SPEAKING, FAILED }

    var onState: (State) -> Unit = {}

    private var tts: TextToSpeech? = null
    private var state = State.IDLE
    private var pending: String? = null

    private val events = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = set(State.SPEAKING)
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = set(State.READY)
        override fun onStop() = set(State.READY)
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "TTS 오류 $errorType")
            set(State.READY)
        }
    }

    fun speak(text: String) {
        when (state) {
            State.READY, State.SPEAKING -> doSpeak(text)
            State.INITIALIZING -> pending = text
            State.IDLE, State.FAILED -> {
                pending = text
                init()
            }
        }
    }

    fun stop() {
        try { tts?.stop() } catch (_: Throwable) {}
    }

    private fun init() {
        set(State.INITIALIZING)
        try {
            val t = TextToSpeech(context.applicationContext, Mode.ON_DEVICE).also { tts = it }
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    try {
                        t.addEventListener(events)
                        t.setLocale(Locale.KOREA)
                        set(State.READY)
                        pending?.let { pending = null; doSpeak(it) }
                    } catch (e: Throwable) {
                        Log.e(TAG, "TTS 설정 실패: ${e.message}")
                        set(State.FAILED)
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.e(TAG, "TTS 초기화 실패 $errorType")
                    set(State.FAILED)
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "TTS 생성 실패: ${e.message}")
            set(State.FAILED)
        }
    }

    private fun doSpeak(text: String) {
        try {
            tts?.speak(text)
            Log.i(TAG, "speak \"$text\"")
        } catch (e: Throwable) {
            Log.e(TAG, "speak 실패: ${e.message}")
        }
    }

    private fun set(s: State) {
        state = s
        onState(s)
    }

    fun release() {
        val t = tts ?: return
        try {
            t.stop()
            t.removeEventListener(events)
            t.release()
        } catch (_: Throwable) {}
        tts = null
        state = State.IDLE
    }

    private companion object {
        const val TAG = "MBTTS"
    }
}
