package com.nothinglauncher

import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.nothinglauncher.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: LauncherViewModel by viewModels()

    private var homeFragment: HomeFragment? = null
    private var appDrawerFragment: AppDrawerFragment? = null
    private var isDrawerOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupStatusBar()
        setupFragments()
        setupBackHandler()
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isDrawerOpen) {
                    closeAppDrawer()
                } else {
                    homeFragment?.exitEditMode()
                }
            }
        })
    }

    private fun setupStatusBar() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
        try {
            val controller = window.insetsController
            controller?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                0
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupFragments() {
        homeFragment = HomeFragment()
        appDrawerFragment = AppDrawerFragment()

        supportFragmentManager.beginTransaction()
            .replace(R.id.homeContainer, homeFragment!!)
            .commit()
    }

    fun openAppDrawer() {
        if (isDrawerOpen) return
        isDrawerOpen = true

        val fragment = appDrawerFragment ?: AppDrawerFragment().also { appDrawerFragment = it }

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.slide_in_left,
                android.R.anim.slide_out_right
            )
            .replace(R.id.drawerContainer, fragment)
            .commit()

        binding.drawerContainer.visibility = View.VISIBLE
    }

    fun closeAppDrawer() {
        if (!isDrawerOpen) return
        isDrawerOpen = false

        appDrawerFragment?.let {
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    android.R.anim.slide_in_left,
                    android.R.anim.slide_out_right
                )
                .remove(it)
                .commit()
        }

        binding.drawerContainer.visibility = View.GONE
    }
}
