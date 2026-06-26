package com.nsl.downloader

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.nsl.downloader.browser.BrowserFragment
import com.nsl.downloader.databinding.ActivityMainBinding
import com.nsl.downloader.library.LibraryFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val browserFragment = BrowserFragment()
    private val libraryFragment = LibraryFragment()

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotifPermission()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, libraryFragment, "library").hide(libraryFragment)
                .add(R.id.fragmentContainer, browserFragment, "browser")
                .commit()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_browser -> switchTo(browserFragment)
                R.id.nav_library -> switchTo(libraryFragment)
            }
            true
        }
    }

    /**
     * Deterministically show exactly [target] and hide every other fragment.
     * Avoids tracking a mutable "active" pointer that can desync from the
     * bottom-nav selection.
     */
    private fun switchTo(target: Fragment) {
        val tx = supportFragmentManager.beginTransaction()
        listOf(browserFragment, libraryFragment).forEach {
            if (it === target) tx.show(it) else tx.hide(it)
        }
        tx.commit()
    }

    private fun isBrowserVisible(): Boolean =
        browserFragment.isVisible

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isBrowserVisible() && browserFragment.onBackPressed()) {
            return
        }
        super.onBackPressed()
    }
}
