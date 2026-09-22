package app.workai.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AgentForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("workai_agent","Задачи WorkAI",NotificationManager.IMPORTANCE_LOW))
        startForeground(41,NotificationCompat.Builder(this,"workai_agent").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("WorkAI выполняет задачу").setContentText("Ответ продолжит генерироваться в фоне").setOngoing(true).build())
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_STICKY
    override fun onBind(intent:Intent?):IBinder?=null
}
