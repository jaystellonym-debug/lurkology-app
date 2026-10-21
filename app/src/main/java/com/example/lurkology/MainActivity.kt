package com.example.lurkology

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
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
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var spinner: ProgressBar
    private lateinit var editUsername: EditText
    private lateinit var editChannel: EditText
    private var currentUser: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editUsername = findViewById(R.id.editUsername)
        editChannel = findViewById(R.id.editChannel)
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
                injectEmoteClickListener()
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
            val targetUrl = "https://lurkology.com/logs?c=${Uri.encode(channel)}&u=${Uri.encode(user)}"
            webView.loadUrl(targetUrl)
        }
    }

    private fun injectEmoteClickListener() {
        val js = """
            (function() {
                function handleClick(e) {
                    var target = e.target.closest('img, [data-emote-id], .emote');
                    if (target) {
                        var emoteName = target.getAttribute('alt') || target.getAttribute('title') || target.getAttribute('data-name') || '';
                        var emoteSrc = target.getAttribute('src') || '';
                        
                        if (!emoteName) {
                            var parent = target.closest('[title], [alt]');
                            if (parent) {
                                emoteName = parent.getAttribute('title') || parent.getAttribute('alt') || '';
                            }
                        }

                        if (emoteName || emoteSrc) {
                            window.AndroidEmoteBridge.onEmoteTapped(emoteName.trim(), emoteSrc.trim());
                        }
                    }
                }
                document.removeEventListener('click', handleClick, true);
                document.addEventListener('click', handleClick, true);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    inner class EmoteBridge {
        @JavascriptInterface
        fun onEmoteTapped(emoteName: String, emoteSrc: String) {
            // Behalte aktuellen Chatter (aus Speicher oder URL)
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