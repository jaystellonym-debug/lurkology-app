package com.example.lurkology

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredChannel(
    val channel: String,
    val emoteName: String,
    val time: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var spinner: ProgressBar
    private lateinit var editUsername: EditText
    private lateinit var editChannel: EditText
    private lateinit var btnChannelsDropdown: Button
    private lateinit var dropdownContainer: ScrollView
    private lateinit var layoutChannelsList: LinearLayout
    private var currentUser: String = ""

    // Cache: Jedes Emote wird nur genau einmal im Netzwerk nachgeschlagen
    private val emoteCache = ConcurrentHashMap<String, String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editUsername = findViewById(R.id.editUsername)
        editChannel = findViewById(R.id.editChannel)
        btnChannelsDropdown = findViewById(R.id.btnChannelsDropdown)
        dropdownContainer = findViewById(R.id.dropdownContainer)
        layoutChannelsList = findViewById(R.id.layoutChannelsList)

        val btnPaste = findViewById<Button>(R.id.btnPaste)
        val btnSearchLogs = findViewById<Button>(R.id.btnSearchLogs)
        webView = findViewById(R.id.inAppViewer)
        spinner = findViewById(R.id.loadingSpinner)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.addJavascriptInterface(EmoteBridge(), "AndroidEmoteBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                spinner.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                spinner.visibility = View.GONE
                injectEmoteEngine()
            }
        }

        webView.webChromeClient = WebChromeClient()

        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!item.isNullOrEmpty()) {
                editUsername.setText(clean(item))
            }
        }

        btnSearchLogs.setOnClickListener {
            currentUser = clean(editUsername.text.toString())
            var channel = clean(editChannel.text.toString())
            if (channel.isEmpty()) channel = currentUser

            if (currentUser.isEmpty()) {
                Toast.makeText(this, "Enter a Twitch username first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            loadLogsFor(channel, currentUser)
        }

        // Klick auf den Dropdown-Balken öffnet / schließt die Liste
        btnChannelsDropdown.setOnClickListener {
            if (dropdownContainer.visibility == View.VISIBLE) {
                dropdownContainer.visibility = View.GONE
                btnChannelsDropdown.text = btnChannelsDropdown.text.toString().replace("▲", "▼")
            } else {
                dropdownContainer.visibility = View.VISIBLE
                btnChannelsDropdown.text = btnChannelsDropdown.text.toString().replace("▼", "▲")
            }
        }
    }

    private fun clean(text: String): String {
        val raw = text.trim().removePrefix("@").lowercase()
        if (raw.isEmpty()) return ""

        val candidate = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            val uri = Uri.parse(raw)
            val host = uri.host.orEmpty()
            val path = uri.path.orEmpty().trim('/')

            when {
                host.endsWith("twitch.tv") -> path.substringBefore('/').ifBlank { "" }
                host.isNotEmpty() -> host.substringBefore('.').ifBlank { "" }
                else -> path.substringBefore('/').ifBlank { "" }
            }
        } else {
            raw
        }

        return candidate
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .removePrefix("/")
            .removeSuffix("/")
            .lowercase()
            .ifBlank { "" }
    }

    private fun loadLogsFor(channel: String, user: String) {
        runOnUiThread {
            currentUser = user
            editUsername.setText(user)
            editChannel.setText(channel)

            // Dropdown bei neuer Suche zurücksetzen
            btnChannelsDropdown.visibility = View.GONE
            dropdownContainer.visibility = View.GONE
            layoutChannelsList.removeAllViews()

            val targetUrl = "https://lurkology.com/logs?c=${Uri.encode(channel)}&u=${Uri.encode(user)}"
            webView.loadUrl(targetUrl)
        }
    }

    // Injektiert den Klick-Listener und den automatischen Emote-Scanner
    private fun injectEmoteEngine() {
        val js = """
            (function() {
                // 1. Klick auf ein beliebiges Emote im Chat
                function handleClick(e) {
                    var target = e.target.closest('img, [data-emote-id], .emote');
                    if (target) {
                        var emoteName = target.getAttribute('alt') || target.getAttribute('title') || target.getAttribute('data-name') || '';
                        var emoteSrc = target.getAttribute('src') || '';
                        if (!emoteName) {
                            var parent = target.closest('[title], [alt]');
                            if (parent) emoteName = parent.getAttribute('title') || parent.getAttribute('alt') || '';
                        }
                        if (emoteName || emoteSrc) {
                            window.AndroidEmoteBridge.onEmoteTapped(emoteName.trim(), emoteSrc.trim());
                        }
                    }
                }
                document.removeEventListener('click', handleClick, true);
                document.addEventListener('click', handleClick, true);

                // 2. Automatischer Scan nach allen Emotes in den geladenen Nachrichten
                setTimeout(function() {
                    var found = [];
                    var images = document.querySelectorAll('img');
                    images.forEach(function(img) {
                        var name = img.getAttribute('alt') || img.getAttribute('title') || '';
                        var src = img.getAttribute('src') || '';
                        if (!name && src.indexOf('7tv') === -1 && src.indexOf('emoticons') === -1 && src.indexOf('bttv') === -1) return;

                        var row = img.closest('tr, li, div, p');
                        var time = '';
                        if (row) {
                            var timeEl = row.querySelector('.timestamp, [class*="time"], [class*="date"], time');
                            if (timeEl) {
                                time = timeEl.innerText.trim();
                            } else {
                                var match = row.innerText.match(/(\d{4}-\d{2}-\d{2}|\d{1,2}:\d{2}(?::\d{2})?)/);
                                if (match) time = match[1];
                            }
                        }
                        found.push({ name: name, src: src, time: time });
                    });

                    if (found.length > 0) {
                        window.AndroidEmoteBridge.onEmotesScanned(JSON.stringify(found));
                    }
                }, 1300);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    inner class EmoteBridge {
        @JavascriptInterface
        fun onEmoteTapped(emoteName: String, emoteSrc: String) {
            val activeUser = if (currentUser.isNotEmpty()) {
                currentUser
            } else {
                val currentUrl = webView.url ?: ""
                Uri.parse(currentUrl).getQueryParameter("u") ?: clean(editUsername.text.toString())
            }

            if (activeUser.isEmpty()) return

            CoroutineScope(Dispatchers.IO).launch {
                val foundChannel = resolveEmoteChannel(emoteName, emoteSrc)
                withContext(Dispatchers.Main) {
                    if (!foundChannel.isNullOrEmpty()) {
                        Toast.makeText(this@MainActivity, "Switching to #$foundChannel...", Toast.LENGTH_SHORT).show()
                        loadLogsFor(foundChannel, activeUser)
                    }
                }
            }
        }

        @JavascriptInterface
        fun onEmotesScanned(jsonStr: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val jsonArray = JSONArray(jsonStr)
                    val channelMap = mutableMapOf<String, DiscoveredChannel>()
                    val currentChan = clean(editChannel.text.toString()).lowercase()

                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val name = obj.optString("name", "")
                        val src = obj.optString("src", "")
                        val time = obj.optString("time", "")

                        val resolved = resolveEmoteChannel(name, src)
                        // Nur Kanäle aufnehmen, die nicht der aktuell angezeigte Kanal sind
                        if (!resolved.isNullOrBlank() && resolved.lowercase() != currentChan) {
                            val key = resolved.lowercase()
                            if (!channelMap.containsKey(key) || (time.isNotEmpty() && channelMap[key]?.time.isNullOrEmpty())) {
                                channelMap[key] = DiscoveredChannel(key, name, time)
                            }
                        }
                    }

                    val list = channelMap.values.toList()
                    withContext(Dispatchers.Main) {
                        updateDropdownUI(list)
                    }
                } catch (e: Exception) {
                    // Ignoriert
                }
            }
        }
    }

    private fun updateDropdownUI(list: List<DiscoveredChannel>) {
        if (list.isEmpty()) {
            btnChannelsDropdown.visibility = View.GONE
            dropdownContainer.visibility = View.GONE
            return
        }

        btnChannelsDropdown.visibility = View.VISIBLE
        btnChannelsDropdown.text = "▼ ${list.size} Fremd-Kanäle aus Emotes erkannt"
        layoutChannelsList.removeAllViews()

        for (item in list) {
            val row = TextView(this).apply {
                val timeInfo = if (item.time.isNotEmpty()) " • ${item.time}" else ""
                val emoteInfo = if (item.emoteName.isNotEmpty()) " (${item.emoteName})" else ""
                text = "🔴 #${item.channel}$emoteInfo$timeInfo"
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(12), dp(10), dp(12), dp(10))

                setOnClickListener {
                    dropdownContainer.visibility = View.GONE
                    btnChannelsDropdown.text = "▼ #${item.channel} geladen"
                    loadLogsFor(item.channel, currentUser)
                }
            }

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                )
                setBackgroundColor(Color.parseColor("#2E2E35"))
            }

            layoutChannelsList.addView(row)
            layoutChannelsList.addView(divider)
        }
    }

    private fun resolveEmoteChannel(emoteName: String, emoteSrc: String): String? {
        val cacheKey = "$emoteName|$emoteSrc"
        emoteCache[cacheKey]?.let { return it }

        try {
            // 1. Prüfung über Name oder Link
            val nameMatch = Regex(
                """(?:^|[^a-z0-9_])(?:channel|c)[:=]?\s*([a-z0-9_]+)""",
                RegexOption.IGNORE_CASE
            ).find(emoteName)?.groupValues?.getOrNull(1)

            if (!nameMatch.isNullOrBlank()) {
                val chan = nameMatch.lowercase()
                emoteCache[cacheKey] = chan
                return chan
            }

            val srcMatch = Regex(
                """[?&](?:channel|c)=([^&]+)""",
                RegexOption.IGNORE_CASE
            ).find(emoteSrc)?.groupValues?.getOrNull(1)

            if (!srcMatch.isNullOrBlank()) {
                val chan = srcMatch.substringBefore('/').substringBefore('?').trim().lowercase()
                emoteCache[cacheKey] = chan
                return chan
            }

            // 2. 7TV Emote
            if (emoteSrc.contains("7tv")) {
                val id = emoteSrc.substringAfter("emote/").substringBefore("/").substringBefore(".")
                if (id.isNotEmpty()) {
                    val conn = URL("https://7tv.io/v3/emotes/$id").openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    if (conn.responseCode == 200) {
                        val res = conn.inputStream.bufferedReader().readText()
                        val owner = JSONObject(res).optJSONObject("owner")?.optString("username")
                        if (!owner.isNullOrEmpty()) {
                            val chan = owner.lowercase()
                            emoteCache[cacheKey] = chan
                            return chan
                        }
                    }
                }
            }

            // 3. Twitch Subscriber Emote über IVR API
            if (emoteName.isNotEmpty()) {
                val conn = URL("https://api.ivr.fi/v2/twitch/emotes/${Uri.encode(emoteName)}").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                if (conn.responseCode == 200) {
                    val res = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(res)
                    val chan = json.optString("channelLogin").ifEmpty { json.optString("channelName") }
                    if (chan.isNotEmpty()) {
                        val c = chan.lowercase()
                        emoteCache[cacheKey] = c
                        return c
                    }
                }
            }

            // 4. Präfix-Erkennung (z. B. bob14LUL -> bob14)
            if (emoteName.isNotEmpty()) {
                val prefixRegex = Regex("""^([a-zA-Z0-9_]{3,16})[A-Z]""")
                val match = prefixRegex.find(emoteName)
                if (match != null) {
                    val chan = match.groupValues[1].lowercase()
                    emoteCache[cacheKey] = chan
                    return chan
                }
            }
        } catch (e: Exception) {
            // Ignoriert
        }

        return null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}