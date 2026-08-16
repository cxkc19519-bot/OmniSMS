package com.omnisms.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.Instant

class MainActivity:Activity(){
    private lateinit var status:TextView;private lateinit var endpoint:EditText;private lateinit var deviceId:EditText;private lateinit var secret:EditText;private lateinit var enabled:Switch
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);if(SecureStorage.isEnabled(this))SmsForegroundService.ensureRunning(this);setContentView(buildUi());refresh()}
    override fun onResume(){super.onResume();if(::status.isInitialized)refresh()}

    private fun buildUi():ScrollView{
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(40))}
        content.addView(TextView(this).apply{text="OmniSMS";textSize=30f;setTextColor(Color.rgb(23,107,82))})
        content.addView(TextView(this).apply{text="把本机收到的新短信安全转发到你的 Gmail";textSize=16f;setPadding(0,dp(6),0,dp(20))})
        status=TextView(this).apply{textSize=18f;setPadding(dp(16),dp(16),dp(16),dp(16));setBackgroundColor(Color.rgb(235,244,240))};content.addView(status,params())
        content.addView(label("服务器连接"));endpoint=input("https://你的短信子域名");content.addView(endpoint,params())
        deviceId=input("设备编号");content.addView(deviceId,params())
        secret=input("设备密钥（只在首次设置时输入）").apply{inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD};content.addView(secret,params())
        content.addView(button("保存安全连接"){saveConnection()})
        content.addView(label("运行设置"));content.addView(button("授予短信权限"){requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS),10)})
        content.addView(button("打开系统应用设置"){startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))})
        enabled=Switch(this).apply{text="开启短信转发";textSize=17f;isChecked=SecureStorage.isEnabled(this@MainActivity);setOnCheckedChangeListener{_,checked->toggle(checked)}};content.addView(enabled,params())
        content.addView(button("发送虚构测试短信"){sendTest()})
        content.addView(TextView(this).apply{text="请在 ColorOS 中允许完全后台行为、应用自启动和关联启动，并关闭耗电异常优化。\n\n短信正文只会进入加密队列和你的 Gmail；安卓常驻通知不会显示正文。";textSize=15f;setPadding(0,dp(24),0,0)})
        return ScrollView(this).apply{addView(content)}
    }

    private fun saveConnection(){try{SecureStorage.saveConfig(this,endpoint.text.toString(),deviceId.text.toString(),secret.text.toString());secret.text.clear();toast("安全连接已保存");refresh()}catch(e:IllegalArgumentException){toast(e.message?:"连接信息格式不正确")}}
    private fun toggle(checked:Boolean){if(checked&&SecureStorage.loadConfig(this)==null){enabled.isChecked=false;toast("请先保存服务器连接");return};SecureStorage.setEnabled(this,checked);if(checked){SmsForegroundService.requestUpload(this);UploadWorker.enqueue(this)}else stopService(Intent(this,SmsForegroundService::class.java));refresh()}
    private fun sendTest(){if(SecureStorage.loadConfig(this)==null){toast("请先保存服务器连接");return};OutboxDatabase.get(this).insert("OmniSMS 测试","这是一条固定的虚构测试短信，不包含真实短信或验证码。",Instant.now().toEpochMilli(),null,"测试",false);SmsForegroundService.requestUpload(this);UploadWorker.enqueue(this);toast("测试消息已加入发送队列");refresh()}
    private fun refresh(){val config=SecureStorage.loadConfig(this);if(config!=null){endpoint.setText(config.endpoint);deviceId.setText(config.deviceId)};val permission=checkSelfPermission(Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED;val counts=runCatching{OutboxDatabase.get(this).counts()}.getOrDefault(Pair(0,0));val running=SecureStorage.isEnabled(this)
        status.text=when{!permission->"未获得短信权限，无法监听新短信";config==null->"尚未连接服务器";!running->"短信转发已暂停";counts.second>0->"有 ${counts.second} 条短信需要处理连接问题";counts.first>0->"短信转发正在运行，${counts.first} 条等待发送";else->"短信转发正在运行"}}
    private fun input(hintText:String)=EditText(this).apply{hint=hintText;textSize=16f;setPadding(dp(12),dp(12),dp(12),dp(12))}
    private fun label(textValue:String)=TextView(this).apply{text=textValue;textSize=20f;setTextColor(Color.DKGRAY);setPadding(0,dp(24),0,dp(8))}
    private fun button(textValue:String,action:()->Unit)=Button(this).apply{text=textValue;setOnClickListener{action()}}
    private fun params()=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(10)}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()
}
