package com.hopon.car.voice

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import com.hopon.car.ui.Design
import com.hopon.car.ui.Palette

/** Foreground voice dialog, entered by the voice button or an opt-in wake phrase. Dismissing the dialog destroys both speech clients. */
class VoiceDialog(private val activity: Activity, private val dictateMemo: Boolean,
                  context: () -> VoiceCommands.Context, private val memoReady: (String) -> Unit,
                  inputFactory: () -> VoiceInput = { PleosVoiceInput(activity) },
                  outputFactory: () -> VoiceOutput = { vehicleVoiceOutput(activity) },
                  private val dismissed: () -> Unit = {},
                  audio: VoiceAudio = AndroidVoiceAudio(activity),
                  private val stopReady: (String) -> Unit = {}) {
    private val design = Design(activity)
    private val main = Handler(Looper.getMainLooper())
    private val muted = Color.rgb(82, 96, 113)
    private val ink = Color.rgb(24, 39, 57)
    private val root = design.column(Color.WHITE, 28)
    private val body = design.column()
    private val status = design.text("연결 중", 18, Palette.forest, true)
    private val prompt = design.text("마이크를 준비하고 있어요", 30, ink, true)
    private val hint = design.text(if(dictateMemo) "예: 지하 3층 기둥 12" else
        "“여정 상태 알려줘” 또는 “주차 메모 지하 3층”", 20, muted)
    private val answerCard = design.column(Color.rgb(239, 244, 250), 22)
    private val answerLabel = design.text("답변", 17, muted, true)
    private val reply = design.text("", 32, Palette.forest, true).apply { setLineSpacing(design.dp(5).toFloat(), 1.08f) }
    private val transcriptBlock = design.column()
    private val transcript = design.text("", 21, ink).apply { setLineSpacing(design.dp(3).toFloat(), 1.05f) }
    private val errorCard = design.column(Color.rgb(255, 245, 233), 18)
    private val error = design.text("", 20, Color.rgb(123, 62, 17))
    private var draft: String? = null
    private var stopQuery: String? = null
    private val closeButton = design.button("닫기") { dismiss() }
    private val retry = design.button("취소", primary = true) { retryOrCancel() }
    private val confirm = design.button("메모 확인", primary = true) {
        val query = stopQuery
        if (query != null) { dismiss(); stopReady(query) }
        else { val memo = draft ?: return@button; dismiss(); memoReady(memo) }
    }
    private val dialog: AlertDialog
    private val session = VoiceSession(
        inputFactory, outputFactory,
        VoiceTimer { delay, action ->
            val task = Runnable { action() }; main.postDelayed(task, delay)
            val cancel: () -> Unit = { main.removeCallbacks(task) }; cancel
        }, { VoiceCommands.answer(it, context(), dictateMemo) }, { update(it) }, audio)
    private fun retryOrCancel() { if(session.state.active) session.cancel() else session.listen() }
    private fun block(view: View, top: Int = 18) {
        body.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = design.dp(top) })
    }
    init {
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(design.text("MOVO 음성", 22, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        status.apply {
            background = design.background(Color.rgb(232, 240, 249), 12)
            setPadding(design.dp(14), design.dp(6), design.dp(14), design.dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        header.addView(status)
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = design.dp(20) })
        body.addView(prompt)
        block(hint, 8)
        answerCard.addView(answerLabel)
        answerCard.addView(reply, LinearLayout.LayoutParams(-1, -2).apply { topMargin = design.dp(8) })
        block(answerCard, 0)
        transcriptBlock.addView(design.text("이렇게 들었어요", 17, muted, true))
        transcriptBlock.addView(transcript, LinearLayout.LayoutParams(-1, -2).apply { topMargin = design.dp(6) })
        block(transcriptBlock, 20)
        errorCard.addView(error)
        block(errorCard)
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val windowHeight = activity.window.decorView.height.takeIf { it > 0 }
                    ?: resources.displayMetrics.heightPixels
                val limit = (windowHeight - design.dp(230)).coerceAtLeast(design.dp(120))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport = false; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        for(button in listOf(closeButton, retry, confirm)) {
            actions.addView(button, LinearLayout.LayoutParams(0, design.dp(68), 1f).apply {
                if(button != closeButton) marginStart = design.dp(12)
            })
        }
        root.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = design.dp(24) })
        dialog = AlertDialog.Builder(activity).create()
        dialog.setView(root, 0, 0, 0, 0)
        dialog.setOnDismissListener { session.close(); main.removeCallbacksAndMessages(null); draft = null; stopQuery = null; dismissed() }
        update(VoiceState())
    }
    fun show() {
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.58f)
            val available = activity.window.decorView.width.takeIf { it > 0 }
                ?: activity.resources.displayMetrics.widthPixels
            setLayout(design.dp(760).coerceAtMost(available - design.dp(40)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        session.listen()
    }
    private fun update(state: VoiceState) {
        status.text = when(state.stage) {
            VoiceStage.IDLE -> "대기"
            VoiceStage.CONNECTING -> "연결 중"
            VoiceStage.LISTENING -> "듣는 중"
            VoiceStage.FINISHING -> "인식 완료"
            VoiceStage.SPEAKING -> "읽는 중"
            VoiceStage.DONE -> "완료"
            VoiceStage.ERROR -> "확인 필요"
        }
        draft = state.reply?.memo
        stopQuery = state.reply?.stopQuery
        val hasReply = state.reply != null
        prompt.visibility = if(hasReply) View.GONE else View.VISIBLE
        prompt.text = when(state.stage) {
            VoiceStage.IDLE -> "버튼을 누르고 말해 주세요"
            VoiceStage.CONNECTING -> "시작 알림음이 끝나면 말해 주세요"
            VoiceStage.LISTENING -> if(dictateMemo) "주차 구역을 말해 주세요" else "말씀해 주세요"
            VoiceStage.ERROR -> "음성을 다시 확인해 주세요"
            else -> "답변을 준비하고 있어요"
        }
        // Examples are for listening, not a competing paragraph above the final answer.
        hint.visibility = if(hasReply || state.stage == VoiceStage.ERROR) View.GONE else View.VISIBLE
        answerCard.visibility = if(hasReply) View.VISIBLE else View.GONE
        answerLabel.text = if(draft != null) "주차 메모 · 저장 전" else if(stopQuery != null) "경유지 제안 · 승인 전" else "답변"
        reply.text = draft ?: state.reply?.text ?: ""
        transcriptBlock.visibility = if(state.transcript.isNotBlank()) View.VISIBLE else View.GONE
        transcript.text = if(draft != null) "내용을 확인한 뒤 저장해 주세요." else state.transcript
        (transcriptBlock.getChildAt(0) as android.widget.TextView).text = if(draft != null) "다음 단계" else "이렇게 들었어요"
        errorCard.visibility = if(state.error.isBlank()) View.GONE else View.VISIBLE
        error.text = state.error
        confirm.text = if(stopQuery != null) "장소 후보 보기" else "메모 확인"
        confirm.visibility = if(draft != null || stopQuery != null) View.VISIBLE else View.GONE
        retry.text = if(state.active) "취소" else "다시 말하기"
        closeButton.background = design.background(Color.rgb(238, 240, 243), 18)
        retry.background = design.background(if(draft == null && stopQuery == null) Palette.forest else Color.rgb(238, 240, 243), 18)
        retry.setTextColor(if(draft == null && stopQuery == null) Color.WHITE else Palette.forest)
    }
    fun dismiss() { session.close(); dialog.dismiss() }
}
