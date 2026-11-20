package app.readingcapture.capture

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * 振動の使い分け。スマホは台に置いたままなので、手に持たなくても違いが分かるよう、
 * 回数と長さで分ける。
 *
 * - 撮れた: 短く 1 回
 * - 切れ・ぶれ・暗い: 長めに 2 回
 * - 消した・残した: ごく軽く 1 回
 *
 * 公式の手引きは、決まった型（createPredefined）を勧めている。未対応の端末でも代わりの型に落ちる。
 * 知らせの 2 回は、撮れたときとはっきり違う必要があるので、長さを指定した波形にしている
 * （手引きが「とても大事な知らせに限って」としている使い方）。
 * https://developer.android.com/develop/ui/views/haptics/haptics-apis （2026-11-20 に確認）
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
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK), attributes)
        return 120
    }

    /** 切れ・ぶれ・暗い。 */
    fun warning(): Long {
        val timings = longArrayOf(0, 110, 130, 110)
        val amplitudes = intArrayOf(0, 255, 0, 255)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1), attributes)
        return 450
    }

    /** 消した・残した・節を変えた。 */
    fun tick(): Long {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK), attributes)
        return 60
    }
}
