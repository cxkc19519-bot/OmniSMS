package com.omnisms.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
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
    private lateinit var statusTitle:TextView
    private lateinit var statusDetail:TextView
    private lateinit var statusDot:TextView
    private lateinit var endpoint:EditText
    private lateinit var deviceId:EditText
    private lateinit var secret:EditText
    private lateinit var enabled:Switch

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=BACKGROUND
        window.navigationBarColor=BACKGROUND
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if(SecureStorage.isEnabled(this))SmsForegroundService.ensureRunning(this)
        setContentView(buildUi())
        refresh()
        ensureSmsPermissions()
    }
    override fun onResume(){super.onResume();if(::statusTitle.isInitialized)refresh()}
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==SMS_PERMISSION_REQUEST){
            refresh()
            if(checkSelfPermission(Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED){InboxReconciler.ensureBaseline(this);SmsForegroundService.ensureRunning(this);SmsForegroundService.requestUpload(this)}
        }
    }

    private fun buildUi():ScrollView{
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(18),dp(20),dp(36));setBackgroundColor(BACKGROUND)}
        content.addView(heroCard(),fullParams(bottom=18))
        content.addView(statusCard(),fullParams(bottom=22))

        content.addView(sectionTitle("安全连接","仅首次设置或重新配对时需要"))
        val connection=card()
        connection.addView(fieldLabel("服务器地址"))
        endpoint=input("https://你的短信子域名")
        connection.addView(endpoint,fullParams(bottom=14))
        connection.addView(fieldLabel("设备编号"))
        deviceId=input("已配对的设备编号")
        connection.addView(deviceId,fullParams(bottom=14))
        connection.addView(fieldLabel("设备密钥"))
        secret=input("只在首次设置时输入").apply{inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD}
        connection.addView(secret,fullParams(bottom=16))
        connection.addView(primaryButton("保存安全连接"){saveConnection()})
        content.addView(connection,fullParams(bottom=24))

        content.addView(sectionTitle("运行控制","让短信在锁屏时也能可靠送达"))
        val controls=card()
        val switchRow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;orientation=LinearLayout.HORIZONTAL;setPadding(0,0,0,dp(16))}
        val switchText=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        switchText.addView(TextView(this).apply{text="开启短信转发";textSize=17f;setTextColor(INK);typeface=Typeface.DEFAULT_BOLD})
        switchText.addView(TextView(this).apply{text="保持前台服务运行，不在通知中显示短信内容";textSize=13f;setTextColor(MUTED);setPadding(0,dp(4),0,0)})
        enabled=Switch(this).apply{
            isChecked=SecureStorage.isEnabled(this@MainActivity)
            thumbTintList=ColorStateList.valueOf(TEAL)
            trackTintList=ColorStateList.valueOf(Color.rgb(207,230,221))
            setOnCheckedChangeListener{_,checked->toggle(checked)}
        }
        switchRow.addView(switchText,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        switchRow.addView(enabled)
        controls.addView(switchRow)
        controls.addView(primaryButton("发送虚构测试短信"){sendTest()})
        controls.addView(secondaryButton("授权5G消息通知读取"){startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))},fullParams(top=10))
        controls.addView(secondaryButton("检查短信权限与后台设置"){startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))},fullParams(top=10))
        content.addView(controls,fullParams(bottom=24))

        val privacy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=rounded(Color.rgb(232,244,239),16);setPadding(dp(18),dp(17),dp(18),dp(17))}
        privacy.addView(TextView(this).apply{text="隐私保护";textSize=15f;typeface=Typeface.DEFAULT_BOLD;setTextColor(TEAL)})
        privacy.addView(TextView(this).apply{text="普通短信通过系统短信接口处理；5G消息只读取 OPPO 系统短信 App 的通知。其他应用通知会被立即忽略，内容只进入加密队列和你的 Gmail。";textSize=14f;setTextColor(Color.rgb(57,91,79));setLineSpacing(dp(3).toFloat(),1f);setPadding(0,dp(6),0,0)})
        content.addView(privacy,fullParams())
        content.addView(TextView(this).apply{text="OmniSMS  ·  个人自用安全转发";gravity=Gravity.CENTER;textSize=12f;setTextColor(Color.rgb(131,151,143));setPadding(0,dp(24),0,0)},fullParams())
        return ScrollView(this).apply{isFillViewport=true;addView(content)}
    }

    private fun heroCard():LinearLayout=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL;background=rounded(TEAL_DARK,24);setPadding(dp(22),dp(22),dp(22),dp(22));elevation=dp(4).toFloat()
        addView(TextView(this@MainActivity).apply{text="OMNISMS";textSize=12f;letterSpacing=.16f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.rgb(190,232,215))})
        addView(TextView(this@MainActivity).apply{text="你的短信，安全抵达";textSize=29f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.WHITE);setPadding(0,dp(9),0,0)})
        addView(TextView(this@MainActivity).apply{text="普通短信与5G消息，锁屏也能转发到 Gmail";textSize=15f;setTextColor(Color.rgb(213,239,229));setPadding(0,dp(7),0,0)})
    }

    private fun statusCard():LinearLayout=card().apply{
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(18),dp(18),dp(18),dp(18))
        statusDot=TextView(this@MainActivity).apply{text="●";textSize=30f;setTextColor(SUCCESS);gravity=Gravity.CENTER}
        addView(statusDot,LinearLayout.LayoutParams(dp(32),ViewGroup.LayoutParams.WRAP_CONTENT))
        val texts=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,0,0)}
        statusTitle=TextView(this@MainActivity).apply{textSize=18f;typeface=Typeface.DEFAULT_BOLD;setTextColor(INK)}
        statusDetail=TextView(this@MainActivity).apply{textSize=13f;setTextColor(MUTED);setLineSpacing(dp(2).toFloat(),1f);setPadding(0,dp(4),0,0)}
        texts.addView(statusTitle);texts.addView(statusDetail)
        addView(texts,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
    }

    private fun saveConnection(){try{SecureStorage.saveConfig(this,endpoint.text.toString(),deviceId.text.toString(),secret.text.toString());secret.text.clear();toast("安全连接已保存");refresh()}catch(e:IllegalArgumentException){toast(e.message?:"连接信息格式不正确")}}
    private fun toggle(checked:Boolean){if(checked&&SecureStorage.loadConfig(this)==null){enabled.isChecked=false;toast("请先保存服务器连接");return};SecureStorage.setEnabled(this,checked);if(checked){SmsForegroundService.requestUpload(this);UploadWorker.enqueue(this)}else stopService(Intent(this,SmsForegroundService::class.java));refresh()}
    private fun sendTest(){if(SecureStorage.loadConfig(this)==null){toast("请先保存服务器连接");return};OutboxDatabase.get(this).insert("OmniSMS 测试","这是一条固定的虚构测试短信，不包含真实短信或验证码。",Instant.now().toEpochMilli(),null,"测试",false);SmsForegroundService.requestUpload(this);UploadWorker.enqueue(this);toast("测试短信已加入安全发送队列");refresh()}
    private fun ensureSmsPermissions(){
        val required=arrayOf(Manifest.permission.RECEIVE_SMS,Manifest.permission.READ_SMS)
        val missing=required.filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isNotEmpty()&&!SecureStorage.permissionsPrompted(this)){
            SecureStorage.markPermissionsPrompted(this)
            requestPermissions(missing.toTypedArray(),SMS_PERMISSION_REQUEST)
        }
    }
    private fun refresh(){
        val config=SecureStorage.loadConfig(this);if(config!=null){endpoint.setText(config.endpoint);deviceId.setText(config.deviceId)}
        val receivePermission=checkSelfPermission(Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED
        val readPermission=checkSelfPermission(Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED
        val notificationAccess=getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(this,MessageNotificationListenerService::class.java))
        val counts=runCatching{OutboxDatabase.get(this).counts()}.getOrDefault(Pair(0,0));val running=SecureStorage.isEnabled(this)
        when{
            !receivePermission->setStatus("需要接收短信权限","授予权限后才能监听并转发新短信。",WARNING)
            !readPermission->setStatus("需要读取短信权限","用于系统清理后的遗漏补发，不会上传历史短信。",WARNING)
            config==null->setStatus("等待安全连接","填写服务器地址、设备编号和密钥即可开始。",WARNING)
            !running->setStatus("短信转发已暂停","开启后，新短信会自动安全发送到 Gmail。",PAUSED)
            !notificationAccess->setStatus("普通短信转发已运行","授权通知使用权后，才能同时转发 ColorOS 5G消息。",WARNING)
            counts.second>0->setStatus("有短信需要处理","发现 ${counts.second} 条永久失败项，请检查连接后重新配对。",DANGER)
            counts.first>0->setStatus("正在安全发送","有 ${counts.first} 条短信等待网络或重试。",WARNING)
            else->setStatus("短信转发正在运行","已准备好接收双卡新短信并转发到 Gmail。",SUCCESS)
        }
    }
    private fun setStatus(title:String,detail:String,color:Int){statusTitle.text=title;statusDetail.text=detail;statusDot.setTextColor(color)}
    private fun input(hintText:String)=EditText(this).apply{hint=hintText;textSize=16f;setTextColor(INK);setHintTextColor(Color.rgb(143,159,152));setSingleLine(true);background=rounded(Color.rgb(247,250,248),13,Color.rgb(220,230,225));setPadding(dp(14),dp(13),dp(14),dp(13))}
    private fun fieldLabel(textValue:String)=TextView(this).apply{text=textValue;textSize=13f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.rgb(81,106,96));setPadding(0,0,0,dp(7))}
    private fun sectionTitle(title:String,subtitle:String)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(2),0,0,dp(10));addView(TextView(this@MainActivity).apply{text=title;textSize=20f;typeface=Typeface.DEFAULT_BOLD;setTextColor(INK)});addView(TextView(this@MainActivity).apply{text=subtitle;textSize=13f;setTextColor(MUTED);setPadding(0,dp(3),0,0)})}
    private fun card()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=rounded(Color.WHITE,20);setPadding(dp(18),dp(18),dp(18),dp(18));elevation=dp(2).toFloat()}
    private fun primaryButton(textValue:String,action:()->Unit)=button(textValue,Color.WHITE,TEAL,action)
    private fun secondaryButton(textValue:String,action:()->Unit)=button(textValue,TEAL,Color.rgb(232,243,238),action)
    private fun button(textValue:String,textColor:Int,backgroundColor:Int,action:()->Unit)=Button(this).apply{text=textValue;isAllCaps=false;textSize=16f;typeface=Typeface.DEFAULT_BOLD;setTextColor(textColor);background=rounded(backgroundColor,13);minHeight=dp(52);setPadding(dp(14),0,dp(14),0);setOnClickListener{action()}}
    private fun rounded(color:Int,radius:Int,stroke:Int?=null)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat();if(stroke!=null)setStroke(dp(1),stroke)}
    private fun fullParams(top:Int=0,bottom:Int=0)=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(top);bottomMargin=dp(bottom)}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()

    companion object{
        private const val SMS_PERMISSION_REQUEST=1201
        private val BACKGROUND=Color.rgb(245,248,246)
        private val TEAL_DARK=Color.rgb(15,76,61)
        private val TEAL=Color.rgb(17,111,84)
        private val INK=Color.rgb(27,50,43)
        private val MUTED=Color.rgb(107,128,119)
        private val SUCCESS=Color.rgb(31,150,102)
        private val WARNING=Color.rgb(220,139,30)
        private val DANGER=Color.rgb(203,70,70)
        private val PAUSED=Color.rgb(121,135,129)
    }
}
