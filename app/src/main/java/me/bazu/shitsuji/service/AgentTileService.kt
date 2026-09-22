package me.bazu.shitsuji.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import me.bazu.shitsuji.ui.ChatActivity

/**
 * クイック設定タイル。通知シェードを下ろして1タップで呼べる。
 *
 * アシスタントアプリ設定（電源ボタン長押し）は端末メーカーによって
 * 差し替えを許さない機種があるので、常に使える経路としてこれを置いている。
 */
class AgentTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ChatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 以降はロック画面越しの起動に PendingIntent が要る。
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
