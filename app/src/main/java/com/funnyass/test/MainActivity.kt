package com.funnyass.test

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.funnyass.test.store.Session
import com.funnyass.test.ui.BathActivity
import com.funnyass.test.ui.LoginActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = Session.loadUser(this)
        val target = if (user != null) BathActivity::class.java else LoginActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}
