package com.nothinglauncher

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.nothinglauncher.databinding.ActivityOnboardingBinding

/**
 * First-run experience. It explains the two gestures that matter, lets the user choose a
 * theme, and asks the system to make this app the HOME handler. Everything here is
 * optional: skipping goes straight to a fully usable launcher.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var prefs: LauncherPrefs
    private var fromSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = LauncherPrefs(this)
        applyStoredTheme()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fromSettings = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)

        val bg = ContextCompat.getColor(this, R.color.nothing_bg)
        val dot = ContextCompat.getColor(this, R.color.dot_off)
        binding.onboardingRoot.background = DotGridDrawable(
            resources.displayMetrics.density,
            backgroundColor = bg,
            dotColor = dot
        )
        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(binding.onboardingRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.onboardingColumn.updatePadding(
                top = bars.top + (20 * density).toInt(),
                bottom = bars.bottom + (28 * density).toInt()
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.onboardingRoot)

        val dark = isNight()
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }

        binding.onboardingDot.background.mutate().setTint(prefs.accentColor())
        binding.onboardingWordmark.accentColor = prefs.accentColor()
        binding.onboardingStatusDot.background.mutate().setTint(prefs.accentColor())
        binding.onboardingWordmarkAccent.accentIndices = setOf(0, 1, 2, 3)

        binding.onboardingSkip.setOnClickListener { finishToHome() }
        binding.onboardingContinue.setOnClickListener { finishToHome() }
        binding.onboardingDefaultAction.setOnClickListener {
            if (LauncherSystem.isDefaultHome(this)) {
                finishToHome()
            } else if (!LauncherSystem.requestDefaultHome(this)) {
                AppLauncher.toast(this, R.string.cannot_open)
            }
        }

        binding.onboardingThemeSystem.setOnClickListener { setThemeMode(LauncherPrefs.ThemeMode.SYSTEM) }
        binding.onboardingThemeDark.setOnClickListener { setThemeMode(LauncherPrefs.ThemeMode.DARK) }
        binding.onboardingThemeLight.setOnClickListener { setThemeMode(LauncherPrefs.ThemeMode.LIGHT) }

        // Back behaves like "Skip": the tour is optional and must never trap the user.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishToHome()
        })

        paintThemePills()
        refreshHomeState()
    }

    override fun onResume() {
        super.onResume()
        refreshHomeState()
    }

    @Deprecated("Handled through the Activity Result API; kept for role fallbacks on old OEMs.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LauncherSystem.REQUEST_HOME_ROLE) {
            refreshHomeState()
            // Some OEMs update the role asynchronously; check once more shortly after.
            if (::binding.isInitialized) binding.root.postDelayed({ if (!isDestroyed) refreshHomeState() }, 450)
        }
    }

    private fun applyStoredTheme() {
        val target = when (prefs.themeMode) {
            LauncherPrefs.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            LauncherPrefs.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            LauncherPrefs.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun setThemeMode(mode: LauncherPrefs.ThemeMode) {
        if (prefs.themeMode == mode) return
        prefs.themeMode = mode
        applyStoredTheme()
        paintThemePills()
        recreate()
    }

    private fun paintThemePills() {
        val active = prefs.themeMode
        fun paint(view: View, selected: Boolean) {
            view.setBackgroundResource(if (selected) R.drawable.bg_pill_white else 0)
            (view as android.widget.TextView).setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.pill_active_text else R.color.text_secondary)
            )
            view.isSelected = selected
        }
        paint(binding.onboardingThemeSystem, active == LauncherPrefs.ThemeMode.SYSTEM)
        paint(binding.onboardingThemeDark, active == LauncherPrefs.ThemeMode.DARK)
        paint(binding.onboardingThemeLight, active == LauncherPrefs.ThemeMode.LIGHT)
    }

    private fun refreshHomeState() {
        if (!::binding.isInitialized) return
        val isDefault = LauncherSystem.isDefaultHome(this)
        binding.onboardingStatus.setText(
            if (isDefault) R.string.onboarding_home_default else R.string.onboarding_home_not_default
        )
        binding.onboardingDefaultAction.setText(
            if (isDefault) R.string.onboarding_open_home else R.string.onboarding_set_default
        )
        binding.onboardingStatusDot.alpha = if (isDefault) 1f else 0.45f
        // Once we are the home app the primary button already says "Open home screen";
        // "Continue without changing home" would be redundant and confusing.
        binding.onboardingContinue.visibility = if (isDefault) View.GONE else View.VISIBLE
    }

    private fun finishToHome() {
        prefs.onboardingComplete = true
        if (fromSettings) {
            setResult(RESULT_OK)
            finish()
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    companion object {
        const val EXTRA_FROM_SETTINGS = "from_settings"
    }
}
