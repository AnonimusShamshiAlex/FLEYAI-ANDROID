package ai.fley.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Действия на телефоне. Ничего опасного: звонок и SMS только открываются, отправляет сам пользователь. */
class PhoneActions(private val act: Activity) {

    private val alias = mapOf(
        "ютуб" to "youtube", "ютьюб" to "youtube", "телеграм" to "telegram", "телега" to "telegram", "ватсап" to "whatsapp",
        "вотсап" to "whatsapp", "инстаграм" to "instagram", "инста" to "instagram", "хром" to "chrome", "браузер" to "chrome",
        "камера" to "camera", "камеру" to "camera", "галерея" to "gallery", "галерею" to "gallery", "фото" to "photos",
        "калькулятор" to "calculator", "настройки" to "settings", "часы" to "clock", "будильник" to "clock",
        "карты" to "maps", "почта" to "gmail", "джимейл" to "gmail", "тикток" to "tiktok", "спотифай" to "spotify",
        "музыка" to "music", "контакты" to "contacts", "телефон" to "phone", "сообщения" to "messages", "плей маркет" to "play store",
        "гугл" to "google", "вк" to "vk", "вконтакте" to "vk", "яндекс" to "yandex", "заметки" to "notes"
    )

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е').trim()

    private fun launch(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        act.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    fun openApp(name: String): String {
        val q = norm(alias[norm(name)] ?: name)
        val pm = act.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        var best: String? = null
        var bestScore = 0
        for (ri in list) {
            val label = norm(ri.loadLabel(pm).toString())
            val pkg = ri.activityInfo.packageName
            val score = when {
                label == q -> 100
                label.startsWith(q) -> 80
                label.contains(q) -> 60
                pkg.lowercase(Locale.ROOT).contains(q.replace(" ", "")) -> 40
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                best = pkg
            }
        }
        val pkg = best ?: return "Не нашёл приложение «$name»."
        val i = pm.getLaunchIntentForPackage(pkg) ?: return "Не удалось открыть «$name»."
        return if (launch(i)) "" else "Не удалось открыть «$name»."
    }

    fun run(a: JSONObject): String {
        return try {
            when (a.optString("type")) {
                "open_app" -> openApp(a.optString("name"))
                "open_url" -> if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url"))))) "" else "Не удалось открыть ссылку."
                "web_search" -> if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(a.optString("query")))))) "" else "Не удалось открыть поиск."
                "youtube" -> if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(a.optString("query")))))) "" else "Не удалось открыть YouTube."
                "maps" -> if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.optString("query")))))) "" else "Не удалось открыть карты."
                "call" -> if (launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.optString("number")))))) "Открыл набор номера, нажмите вызов." else "Не удалось открыть набор номера."
                "sms" -> {
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(a.optString("number"))))
                    i.putExtra("sms_body", a.optString("text"))
                    if (launch(i)) "Сообщение готово, нажмите отправить." else "Не удалось открыть сообщения."
                }
                "alarm" -> {
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                        .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "FLEY"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (launch(i)) "" else "Не удалось поставить будильник."
                }
                "timer" -> {
                    val i = Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "FLEY"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (launch(i)) "" else "Не удалось поставить таймер."
                }
                "volume" -> volume(a.optString("direction", "up"))
                "flashlight" -> flashlight(a.optBoolean("on", true))
                "settings" -> {
                    val act2 = when (a.optString("page")) {
                        "wifi" -> Settings.ACTION_WIFI_SETTINGS
                        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                        "display" -> Settings.ACTION_DISPLAY_SETTINGS
                        "sound" -> Settings.ACTION_SOUND_SETTINGS
                        else -> Settings.ACTION_SETTINGS
                    }
                    if (launch(Intent(act2))) "" else "Не удалось открыть настройки."
                }
                "status" -> status()
                else -> ""
            }
        } catch (e: Exception) {
            "Ошибка действия: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun volume(dir: String): String {
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = maxOf(1, max / 7)
        val v = when (dir) {
            "down" -> cur - step
            "max" -> max
            "mute" -> 0
            else -> cur + step
        }.coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)
        return ""
    }

    fun flashlight(on: Boolean): String {
        return try {
            val cm = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull() ?: return "На этом телефоне нет фонарика."
            cm.setTorchMode(id, on)
            ""
        } catch (e: Exception) {
            "Не удалось включить фонарик."
        }
    }

    fun status(): String {
        val bm = act.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return "Сейчас $time, заряд батареи $lvl процентов."
    }
}
