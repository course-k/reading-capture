package app.readingcapture.capture

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import app.readingcapture.data.Settings

/** 選べる振動の 1 つ。 */
data class HapticChoice(
    /** 保存に使う名前 */
    val id: String,
    /** 画面に出す名前 */
    val label: String,
    /** 鳴り終わるまでの時間の目安（このあいだの映像の揺れは、動きとして数えない） */
    val durationMs: Long,
)

/**
 * 振動の使い分け。画面を見なくても分かるよう、回数と長さで分ける。
 *
 * - 撮れた: 短いバイブ 1 回（0.04 秒）。設定の「撮れたときの振動」で選び直せる
 * - 切れ・ぶれ・暗い: 長めに 2 回（0.3 秒ずつ）
 *
 * 「消す」「残す」を押したときは振動しない（持ち主: 「別にいらないです」）。
 *
 * 最初は、キーを押したときと同じ種類の軽い振動にしていたが、持ち主が思っていた「バイブ」ではなかった。
 * 次に 0.2 秒のバイブにしたが、長かった（実際には 0.29 秒鳴っていた）。
 * 触って選べるようにし、持ち主が 0.04 秒を選んだ（2026-11-21）。これを既定にしてある。
 */
class Haptics(context: Context, private val settings: Settings) {

    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

    /**
     * 振動の種類の届け出。
     *
     * 何も届けないと、Android は「画面に触れたときの振動」として扱い、スマホの設定で
     * 「タッチ操作の振動」を切っていると鳴らない（実機で、撮れても振動しなかった原因。
     * Android の記録に ignored_for_settings と残っていた）。
     * 「通知」として届けると、マナーモードを消音にしたときに鳴らない。
     * 撮れたかどうかは、画面を見ずに分かることがこのアプリの要なので、
     * 消音でも鳴る「アラーム」として届ける（設定の「アラームのバイブレーション」に従う）。
     */
    private val attributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)

    /** 長さを指定するバイブ。 */
    val lengths: List<HapticChoice> = listOf(40, 60, 80, 120, 200).map { HapticChoice("len:$it", "%.2f 秒".format(it / 1000f), it + 90L) }

    /** あらかじめ用意されている振動（Android の決まった型）。 */
    val presets: List<HapticChoice> = listOf(
        HapticChoice("preset:CLICK", "クリック", 120),
        HapticChoice("preset:HEAVY_CLICK", "強いクリック", 140),
        HapticChoice("preset:DOUBLE_CLICK", "ダブルクリック", 300),
        HapticChoice("preset:TICK", "チッ（軽い）", 100),
    )

    /** 組み合わせ用の振動の部品。この端末が対応しているものだけ。 */
    val primitives: List<HapticChoice> = listOf(
        Triple(VibrationEffect.Composition.PRIMITIVE_THUD, "prim:THUD", "ドン"),
        Triple(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, "prim:QUICK_FALL", "すっと弱まる"),
        Triple(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, "prim:QUICK_RISE", "すっと強まる"),
        Triple(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, "prim:SLOW_RISE", "ゆっくり強まる"),
        Triple(VibrationEffect.Composition.PRIMITIVE_SPIN, "prim:SPIN", "回る感じ"),
        Triple(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, "prim:LOW_TICK", "低いチッ"),
    ).filter { vibrator.areAllPrimitivesSupported(it.first) }.map { HapticChoice(it.second, it.third, 400) }

    private val all: List<HapticChoice> get() = lengths + presets + primitives

    /** 撮れたときの振動として、いま選ばれているもの。 */
    val selected: HapticChoice get() = all.firstOrNull { it.id == settings.capturedHaptic } ?: lengths.first { it.id == DEFAULT }

    /** 選んだ振動を鳴らす。@return 鳴り終わるまでの時間の目安 */
    fun play(choice: HapticChoice): Long {
        val kind = choice.id.substringBefore(':')
        val name = choice.id.substringAfter(':')
        val effect = when (kind) {
            "len" -> VibrationEffect.createOneShot(name.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
            "preset" -> VibrationEffect.createPredefined(
                when (name) {
                    "CLICK" -> VibrationEffect.EFFECT_CLICK
                    "HEAVY_CLICK" -> VibrationEffect.EFFECT_HEAVY_CLICK
                    "DOUBLE_CLICK" -> VibrationEffect.EFFECT_DOUBLE_CLICK
                    else -> VibrationEffect.EFFECT_TICK
                },
            )
            else -> VibrationEffect.startComposition().addPrimitive(
                when (name) {
                    "THUD" -> VibrationEffect.Composition.PRIMITIVE_THUD
                    "QUICK_FALL" -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
                    "QUICK_RISE" -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
                    "SLOW_RISE" -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
                    "SPIN" -> VibrationEffect.Composition.PRIMITIVE_SPIN
                    else -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                },
            ).compose()
        }
        vibrator.vibrate(effect, attributes)
        return choice.durationMs
    }

    /** 撮れた。@return 振動が続く時間の目安（このあいだの映像の揺れは、めくった動きとして数えない） */
    fun captured(): Long = play(selected)

    /** 切れ・ぶれ・暗い。 */
    fun warning(): Long {
        val timings = longArrayOf(0, 300, 180, 300)
        val amplitudes = intArrayOf(0, 255, 0, 255)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1), attributes)
        return 780
    }

    companion object {
        const val DEFAULT = "len:40"
    }
}
