package app.readingcapture.capture

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * 振動の使い分け。スマホは台に置いたままなので、手に持たなくても違いが分かるよう、
 * 回数と長さで分ける。
 *
 * - 撮れた: ブルッと 1 回（0.2 秒）
 * - 切れ・ぶれ・暗い: ブルッ、ブルッと長めに 2 回（0.3 秒ずつ）
 * - 消した・残した: ごく短く 1 回
 *
 * 最初は、キーを押したときと同じ種類の軽い振動（決まった型）にしていたが、
 * 持ち主が思っていた「バイブ」ではなかった（2026-11-21）。長さを指定した振動にしている。
 */
class Haptics(context: Context) {

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

    /** 撮れた。@return 振動が続く時間の目安（このあいだの映像の揺れは、めくった動きとして数えない） */
    fun captured(): Long {
        vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE), attributes)
        return 200
    }

    /** 切れ・ぶれ・暗い。 */
    fun warning(): Long {
        val timings = longArrayOf(0, 300, 180, 300)
        val amplitudes = intArrayOf(0, 255, 0, 255)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1), attributes)
        return 780
    }

    /** 消した・残した・節を変えた。 */
    fun tick(): Long {
        vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE), attributes)
        return 40
    }
}
