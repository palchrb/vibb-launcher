package com.kidslauncher.mdm.ui

import android.os.Bundle
import android.view.MenuItem
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.LegalInfoBinding

class LegalInfoActivity : UIObjectActivity() {
    private lateinit var binding: LegalInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialise layout
        binding = LegalInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setTitle(R.string.legal_info_title)
        setSupportActionBar(binding.legalInfoAppbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // The font's licence travels with it (OFL 1.1, condition 2).
        binding.legalInfoOfl.text = try {
            assets.open(OFL_ASSET).bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            OFL_ASSET
        }
        // So do the app icons' (Material Symbols, Apache-2.0 §4(a); design 14).
        binding.legalInfoApache.text = try {
            assets.open(APACHE_ASSET).bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            APACHE_ASSET
        }
    }

    companion object {
        const val OFL_ASSET = "licenses/OFL.txt"
        const val APACHE_ASSET = "licenses/Apache-2.0.txt"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }

            else -> {
                return super.onOptionsItemSelected(item)
            }
        }
    }
}