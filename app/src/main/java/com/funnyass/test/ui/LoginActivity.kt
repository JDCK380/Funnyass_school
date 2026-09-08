package com.funnyass.test.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.funnyass.test.R
import com.funnyass.test.data.BaseResponse
import com.funnyass.test.data.UserInfo
import com.funnyass.test.net.Api
import com.funnyass.test.store.Session

class LoginActivity : AppCompatActivity() {
    private lateinit var phone: EditText
    private lateinit var code: EditText
    private lateinit var pwd: EditText
    private lateinit var sendBtn: Button
    private lateinit var codeLoginBtn: Button
    private lateinit var pwdLoginBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        phone = findViewById(R.id.phone)
        code = findViewById(R.id.code)
        pwd = findViewById(R.id.pwd)
        sendBtn = findViewById(R.id.send_btn)
        codeLoginBtn = findViewById(R.id.code_login_btn)
        pwdLoginBtn = findViewById(R.id.pwd_login_btn)

        Session.phone(this)?.takeIf { it.isNotBlank() }?.let {
            phone.setText(it)
            phone.setSelection(it.length)
        }
        intent.getStringExtra(EXTRA_REASON)?.takeIf { it.isNotBlank() }?.let { toast(it) }

        sendBtn.setOnClickListener { sendSms() }
        codeLoginBtn.setOnClickListener { codeLogin() }
        pwdLoginBtn.setOnClickListener { pwdLogin() }
    }

    private fun p(): String = phone.text.toString().trim()

    private fun sendSms() {
        if (p().length != 11) { toast("请输入11位手机号"); return }
        setLoading(true)
        Thread {
            try {
                val r: BaseResponse<Any> = Api.sendSms(p())
                runOnUiThread {
                    setLoading(false)
                    if (r.errorCode == 0) toast("验证码已发送") else toast(r.errorMessage ?: "发送失败(" + r.errorCode + ")")
                }
            } catch (e: Exception) {
                runOnUiThread { setLoading(false); toast("网络错误: " + e.message) }
            }
        }.start()
    }

    private fun codeLogin() {
        val c = code.text.toString().trim()
        if (p().length != 11 || c.isEmpty()) { toast("请输入手机号和验证码"); return }
        setLoading(true)
        Thread {
            try {
                val r: BaseResponse<UserInfo> = Api.loginByCode(p(), c)
                runOnUiThread { setLoading(false); handleLogin(r) }
            } catch (e: Exception) {
                runOnUiThread { setLoading(false); toast("网络错误: " + e.message) }
            }
        }.start()
    }

    private fun pwdLogin() {
        val pw = pwd.text.toString()
        if (p().length != 11 || pw.isEmpty()) { toast("请输入手机号和密码"); return }
        setLoading(true)
        Thread {
            try {
                val r: BaseResponse<UserInfo> = Api.loginByPassword(p(), pw)
                runOnUiThread { setLoading(false); handleLogin(r) }
            } catch (e: Exception) {
                runOnUiThread { setLoading(false); toast("网络错误: " + e.message) }
            }
        }.start()
    }

    private fun handleLogin(r: BaseResponse<UserInfo>) {
        if (r.errorCode == 0 && r.data != null) {
            Session.saveUser(this, r.data)
            toast("登录成功")
            startActivity(Intent(this, BathActivity::class.java))
            finish()
        } else {
            toast(r.errorMessage ?: "登录失败(" + r.errorCode + ")")
        }
    }

    private fun setLoading(b: Boolean) {
        codeLoginBtn.isEnabled = !b
        pwdLoginBtn.isEnabled = !b
        sendBtn.isEnabled = !b
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_REASON = "login_reason"
    }
}
