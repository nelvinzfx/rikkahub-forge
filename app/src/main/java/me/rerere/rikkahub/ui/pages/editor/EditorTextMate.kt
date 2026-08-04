package me.rerere.rikkahub.ui.pages.editor

import android.content.Context
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * One-time TextMate wiring for the editor: asset resolver, two bundled themes
 * (quietlight/darcula mapped from app dark mode) and the grammar manifest in
 * assets/textmate/languages.json. Grammar load failures degrade to plain text.
 */
object EditorTextMate {

    private var initialized = false

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        initialized = true
        FileProviderRegistry.getInstance().addFileProvider(
            AssetsFileResolver(context.applicationContext.assets)
        )
        val themeRegistry = ThemeRegistry.getInstance()
        listOf("darcula", "quietlight").forEach { name ->
            val path = "textmate/$name.json"
            runCatching {
                themeRegistry.loadTheme(
                    ThemeModel(
                        IThemeSource.fromInputStream(
                            FileProviderRegistry.getInstance().tryGetInputStream(path), path, null
                        ), name
                    ).apply { isDark = name != "quietlight" }
                )
            }
        }
        runCatching {
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        }
    }

    private var lastDark: Boolean? = null

    fun applyTheme(editor: CodeEditor, dark: Boolean) {
        // creating a TextMateColorScheme forces a full editor repaint, so only
        // re-apply when the mode actually changed (this runs on every recompose)
        if (lastDark == dark) return
        lastDark = dark
        runCatching {
            ThemeRegistry.getInstance().setTheme(if (dark) "darcula" else "quietlight")
            editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
        }
    }

    fun languageFor(fileName: String): Language? {
        val scope = scopeForFile(fileName) ?: return null
        return runCatching { TextMateLanguage.create(scope, true) }.getOrNull()
    }

    private fun scopeForFile(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "java" -> "source.java"
            "kt", "kts" -> "source.kotlin"
            "py", "pyw" -> "source.python"
            "xml" -> "text.xml"
            "html", "htm" -> "text.html.basic"
            "js", "mjs", "cjs", "jsx" -> "source.js"
            "md", "markdown" -> "text.html.markdown"
            "lua" -> "source.lua"
            else -> null
        }
    }
}
