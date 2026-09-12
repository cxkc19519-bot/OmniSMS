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
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
    private lateinit var pageContainer:FrameLayout
    private lateinit var homePageView:View
    private lateinit var settingsPageView:View
    private lateinit var aboutPageView:View
    private lateinit var homeTab:TextView
    private lateinit var settingsTab:TextView
    private lateinit var aboutTab:TextView

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

    private fun buildUi():View{
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BACKGROUND)}
        pageContainer=FrameLayout(this)
        root.addView(pageContainer,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        val home=homePage()
        val settings=settingsPage()
        val about=aboutPage()
        root.addView(bottomNavigation(),LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(76)))
        homePageView=home
        settingsPageView=settings
        aboutPageView=about
        showPage(PAGE_HOME)
        return root
    }

    private fun homePage():ScrollView{
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(16),dp(18),dp(28));setBackgroundColor(BACKGROUND)}
        content.addView(heroCard(),fullParams(bottom=18))
        content.addView(statusCard(),fullParams(bottom=24))
        content.addView(sectionTitle("运行控制","日常使用只需要关注这里"))
        val controls=card()
        val switchRow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;orientation=LinearLayout.HORIZONTAL;setPadding(0,0,0,dp(16))}
        val switchText=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        switchText.addView(TextView(this).apply{text="开启短信转发";textSize=17f;setTextColor(INK);typeface=Typeface.DEFAULT_BOLD})
        switchText.addView(TextView(this).apply{text="保持后台服务运行，不在通知中显示短信内容";textSize=13f;setTextColor(MUTED);setPadding(0,dp(4),0,0)})
        enabled=Switch(this).apply{
            isChecked=SecureStorage.isEnabled(this@MainActivity)
            thumbTintList=ColorStateList.valueOf(PRIMARY)
            trackTintList=ColorStateList.valueOf(Color.rgb(190,222,249))
            setOnCheckedChangeListener{_,checked->toggle(checked)}
        }
        switchRow.addView(switchText,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        switchRow.addView(enabled)
        controls.addView(switchRow)
        controls.addView(primaryButton("发送虚构测试短信"){sendTest()})
        content.addView(controls,fullParams(bottom=18))
        val guide=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=rounded(BLUE_TINT,18,BLUE_BORDER);setPadding(dp(18),dp(16),dp(18),dp(16))}
        guide.addView(TextView(this).apply{text="日常无需保持页面打开";textSize=15f;typeface=Typeface.DEFAULT_BOLD;setTextColor(BLUE_DARK)})
        guide.addView(TextView(this).apply{text="开启转发后，可以返回桌面或锁屏。连接和权限选项都已收纳到“设置”页。";textSize=14f;setTextColor(BLUE_TEXT);setLineSpacing(dp(3).toFloat(),1f);setPadding(0,dp(6),0,0)})
        content.addView(guide)
        return ScrollView(this).apply{isFillViewport=true;addView(content)}
    }

    private fun settingsPage():ScrollView{
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(20),dp(18),dp(28));setBackgroundColor(BACKGROUND)}
        content.addView(pageHeader("设置","管理安全连接、系统权限与隐私"),fullParams(bottom=24))
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
        content.addView(sectionTitle("权限与后台","遇到锁屏延迟时可在这里检查"))
        val permissions=card()
        permissions.addView(secondaryButton("授权5G消息通知读取"){startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))})
        permissions.addView(secondaryButton("允许锁屏后台运行"){requestBatteryExemption()},fullParams(top=10))
        permissions.addView(secondaryButton("检查短信权限与后台设置"){startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))},fullParams(top=10))
        content.addView(permissions,fullParams(bottom=20))
        return ScrollView(this).apply{isFillViewport=true;addView(content)}
    }

    private fun aboutPage():ScrollView{
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(20),dp(18),dp(28));setBackgroundColor(BACKGROUND)}
        content.addView(pageHeader("关于 OmniSMS","一款为个人设计的短信转发助手"),fullParams(bottom=20))
        val intro=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;background=gradient(intArrayOf(Color.rgb(196,227,253),Color.rgb(239,249,255)),24);setPadding(dp(22),dp(24),dp(22),dp(24))}
        intro.addView(brandMark(72),LinearLayout.LayoutParams(dp(72),dp(72)))
        intro.addView(TextView(this).apply{text="OmniSMS";textSize=24f;typeface=Typeface.DEFAULT_BOLD;setTextColor(INK);gravity=Gravity.CENTER;setPadding(0,dp(12),0,0)})
        intro.addView(TextView(this).apply{text="让另一台设备也能及时收到你的重要短信";textSize=14f;setTextColor(BLUE_TEXT);gravity=Gravity.CENTER;setPadding(0,dp(5),0,0)})
        intro.addView(TextView(this).apply{text="版本 "+BuildConfig.VERSION_NAME;textSize=12f;setTextColor(MUTED);gravity=Gravity.CENTER;setPadding(0,dp(10),0,0)})
        content.addView(intro,fullParams(bottom=24))
        content.addView(sectionTitle("它能做什么","简单、可靠，只服务于你的个人设备"))
        val features=card()
        features.addView(featureRow("双卡短信监听","自动接收手机中的普通短信和验证码"))
        features.addView(divider())
        features.addView(featureRow("安全转发","通过你的私人服务器送达指定 Gmail"))
        features.addView(divider())
        features.addView(featureRow("断网自动补发","网络恢复后继续处理安全队列中的消息"))
        content.addView(features,fullParams(bottom=24))
        content.addView(sectionTitle("消息如何抵达","三个步骤完成一次转发"))
        val flow=card()
        flow.addView(flowRow("1","手机收到短信","系统将新短信交给 OmniSMS"))
        flow.addView(flowRow("2","加密上传","消息发送到你的私人服务器"))
        flow.addView(flowRow("3","邮件提醒","服务器将完整内容投递到 Gmail"))
        content.addView(flow,fullParams(bottom=20))
        val privacy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=rounded(BLUE_TINT,18,BLUE_BORDER);setPadding(dp(18),dp(17),dp(18),dp(17))}
        privacy.addView(TextView(this).apply{text="为隐私而设计";textSize=15f;typeface=Typeface.DEFAULT_BOLD;setTextColor(BLUE_DARK)})
        privacy.addView(TextView(this).apply{text="OmniSMS 不提供公共账号或共享平台。短信仅在你的手机、私人服务器和接收邮箱之间流转，通知与诊断日志不会显示短信正文或验证码。";textSize=14f;setTextColor(BLUE_TEXT);setLineSpacing(dp(3).toFloat(),1f);setPadding(0,dp(6),0,0)})
        content.addView(privacy)
        content.addView(TextView(this).apply{text="个人自用 · 请妥善保护短信和验证码";gravity=Gravity.CENTER;textSize=12f;setTextColor(MUTED);setPadding(0,dp(24),0,0)},fullParams())
        return ScrollView(this).apply{isFillViewport=true;addView(content)}
    }

    private fun featureRow(title:String,detail:String)=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL;setPadding(dp(2),dp(10),dp(2),dp(10))
        addView(TextView(this@MainActivity).apply{text=title;textSize=16f;typeface=Typeface.DEFAULT_BOLD;setTextColor(INK)})
        addView(TextView(this@MainActivity).apply{text=detail;textSize=13f;setTextColor(MUTED);setPadding(0,dp(4),0,0)})
    }

    private fun flowRow(number:String,title:String,detail:String)=LinearLayout(this).apply{
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(2),dp(9),dp(2),dp(9))
        addView(TextView(this@MainActivity).apply{text=number;gravity=Gravity.CENTER;textSize=14f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.WHITE);background=rounded(PRIMARY,12)},LinearLayout.LayoutParams(dp(36),dp(36)))
        val textGroup=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,0,0)}
        textGroup.addView(TextView(this@MainActivity).apply{text=title;textSize=15f;typeface=Typeface.DEFAULT_BOLD;setTextColor(INK)})
        textGroup.addView(TextView(this@MainActivity).apply{text=detail;textSize=13f;setTextColor(MUTED);setPadding(0,dp(3),0,0)})
        addView(textGroup,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
    }

    private fun divider()=View(this).apply{setBackgroundColor(Color.rgb(231,238,245))}.also{it.layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(1))}

    private fun pageHeader(title:String,subtitle:String)=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply{text=title;textSize=30f;typeface=Typeface.DEFAULT_BOLD;setTextColor(INK)})
        addView(TextView(this@MainActivity).apply{text=subtitle;textSize=15f;setTextColor(MUTED);setPadding(0,dp(5),0,0)})
    }

    private fun bottomNavigation():LinearLayout=LinearLayout(this).apply{
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;background=rounded(Color.WHITE,0,BLUE_BORDER);setPadding(dp(12),dp(10),dp(12),dp(10));elevation=dp(8).toFloat()
        homeTab=navItem("首页"){showPage(PAGE_HOME)}
        settingsTab=navItem("设置"){showPage(PAGE_SETTINGS)}
        aboutTab=navItem("关于"){showPage(PAGE_ABOUT)}
        addView(homeTab,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f).apply{rightMargin=dp(4)})
        addView(settingsTab,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f).apply{leftMargin=dp(4);rightMargin=dp(4)})
        addView(aboutTab,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f).apply{leftMargin=dp(4)})
    }

    private fun navItem(label:String,action:()->Unit)=TextView(this).apply{
        text=label;gravity=Gravity.CENTER;textSize=15f;typeface=Typeface.DEFAULT_BOLD;setOnClickListener{action()}
    }

    private fun showPage(page:Int){
        val target=when(page){PAGE_SETTINGS->settingsPageView;PAGE_ABOUT->aboutPageView;else->homePageView}
        (target.parent as? ViewGroup)?.removeView(target)
        pageContainer.removeAllViews()
        pageContainer.addView(target,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
        styleTab(homeTab,page==PAGE_HOME)
        styleTab(settingsTab,page==PAGE_SETTINGS)
        styleTab(aboutTab,page==PAGE_ABOUT)
        if(::statusTitle.isInitialized)refresh()
    }

    private fun styleTab(tab:TextView,selected:Boolean){
        tab.setTextColor(if(selected)BLUE_DARK else MUTED)
        tab.background=rounded(if(selected)BLUE_TINT else Color.TRANSPARENT,16)
    }

    private fun brandMark(size:Int)=ImageView(this).apply{
        setImageResource(R.drawable.ic_brand_mark)
        contentDescription="OmniSMS 标志"
        scaleType=ImageView.ScaleType.FIT_CENTER
        minimumWidth=dp(size);minimumHeight=dp(size)
    }

    private fun heroCard():LinearLayout=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL;background=gradient(intArrayOf(Color.rgb(196,227,253),Color.rgb(235,247,255)),26);setPadding(dp(22),dp(21),dp(22),dp(22));elevation=dp(2).toFloat()
        val brandRow=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        brandRow.addView(brandMark(42),LinearLayout.LayoutParams(dp(42),dp(42)))
        brandRow.addView(TextView(this@MainActivity).apply{text="OMNISMS";textSize=13f;letterSpacing=.14f;typeface=Typeface.DEFAULT_BOLD;setTextColor(BLUE_DARK);setPadding(dp(11),0,0,0)})
        addView(brandRow)
        addView(TextView(this@MainActivity).apply{text="你的短信，安心抵达";textSize=28f;typeface=Typeface.DEFAULT_BOLD;setTextColor(INK);setPadding(0,dp(18),0,0)})
        addView(TextView(this@MainActivity).apply{text="连接手机与邮箱，让重要消息及时送达";textSize=15f;setTextColor(BLUE_TEXT);setPadding(0,dp(7),0,0)})
    }

    private fun statusCard():LinearLayout=card().apply{
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(18),dp(18),dp(18),dp(18))
        statusDot=TextView(this@MainActivity).apply{text="●";textSize=22f;setTextColor(SUCCESS);gravity=Gravity.CENTER;background=rounded(Color.rgb(239,249,255),14)}
        addView(statusDot,LinearLayout.LayoutParams(dp(42),dp(42)))
        val texts=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,0,0)}
        statusTitle=TextView(this@MainActivity).apply{textSize=18f;typeface=Typeface.DEFAULT_BOLD;setTextColor(INK)}
        statusDetail=TextView(this@MainActivity).apply{textSize=13f;setTextColor(MUTED);setLineSpacing(dp(2).toFloat(),1f);setPadding(0,dp(4),0,0)}
        texts.addView(statusTitle);texts.addView(statusDetail)
        addView(texts,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
    }

    private fun saveConnection(){try{SecureStorage.saveConfig(this,endpoint.text.toString(),deviceId.text.toString(),secret.text.toString());secret.text.clear();toast("安全连接已保存");refresh()}catch(e:IllegalArgumentException){toast(e.message?:"连接信息格式不正确")}}
    private fun toggle(checked:Boolean){if(checked&&SecureStorage.loadConfig(this)==null){enabled.isChecked=false;toast("请先保存服务器连接");return};SecureStorage.setEnabled(this,checked);if(checked){SmsForegroundService.requestUpload(this);UploadWorker.enqueue(this)}else stopService(Intent(this,SmsForegroundService::class.java));refresh()}
    private fun sendTest(){if(SecureStorage.loadConfig(this)==null){toast("请先保存服务器连接");return};OutboxDatabase.get(this).insert("OmniSMS 测试","这是一条固定的虚构测试短信，不包含真实短信或验证码。",Instant.now().toEpochMilli(),null,"测试",false);SmsForegroundService.requestUpload(this);UploadWorker.enqueue(this);toast("测试短信已加入安全发送队列");refresh()}
    private fun requestBatteryExemption(){
        val intent=Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:$packageName"))
        runCatching{startActivity(intent)}.onFailure{startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}
    }
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
        val batteryExempt=getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val notificationAccess=getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(this,MessageNotificationListenerService::class.java))
        val counts=runCatching{OutboxDatabase.get(this).counts()}.getOrDefault(Pair(0,0));val running=SecureStorage.isEnabled(this)
        when{
            !receivePermission->setStatus("需要接收短信权限","授予权限后才能监听并转发新短信。",WARNING)
            !readPermission->setStatus("需要读取短信权限","用于系统清理后的遗漏补发，不会上传历史短信。",WARNING)
            config==null->setStatus("等待安全连接","填写服务器地址、设备编号和密钥即可开始。",WARNING)
            !running->setStatus("短信转发已暂停","开启后，新短信会自动安全发送到 Gmail。",PAUSED)
            !batteryExempt->setStatus("需要允许锁屏后台运行","关闭 OmniSMS 的电池优化，并在 ColorOS 中允许自启动和后台运行。",WARNING)
            !notificationAccess->setStatus("普通短信转发已运行","授权通知使用权后，才能同时转发 ColorOS 5G消息。",WARNING)
            counts.second>0->setStatus("有短信需要处理","发现 ${counts.second} 条永久失败项，请检查连接后重新配对。",DANGER)
            counts.first>0->setStatus("正在安全发送","有 ${counts.first} 条短信等待网络或重试。",WARNING)
            else->setStatus("短信转发正在运行","已准备好接收双卡新短信并转发到 Gmail。",SUCCESS)
        }
    }
    private fun setStatus(title:String,detail:String,color:Int){statusTitle.text=title;statusDetail.text=detail;statusDot.setTextColor(color)}
    private fun input(hintText:String)=EditText(this).apply{hint=hintText;textSize=16f;setTextColor(INK);setHintTextColor(Color.rgb(137,153,171));setSingleLine(true);background=rounded(INPUT_BACKGROUND,14,BLUE_BORDER);setPadding(dp(15),dp(13),dp(15),dp(13))}
    private fun fieldLabel(textValue:String)=TextView(this).apply{text=textValue;textSize=13f;typeface=Typeface.DEFAULT_BOLD;setTextColor(BLUE_TEXT);setPadding(0,0,0,dp(7))}
    private fun sectionTitle(title:String,subtitle:String)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(2),0,0,dp(10));addView(TextView(this@MainActivity).apply{text=title;textSize=20f;typeface=Typeface.DEFAULT_BOLD;setTextColor(INK)});addView(TextView(this@MainActivity).apply{text=subtitle;textSize=13f;setTextColor(MUTED);setPadding(0,dp(3),0,0)})}
    private fun card()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=rounded(Color.WHITE,20,Color.rgb(227,235,244));setPadding(dp(18),dp(18),dp(18),dp(18));elevation=dp(1).toFloat()}
    private fun primaryButton(textValue:String,action:()->Unit)=button(textValue,Color.WHITE,PRIMARY,action)
    private fun secondaryButton(textValue:String,action:()->Unit)=button(textValue,BLUE_DARK,BLUE_TINT,action)
    private fun button(textValue:String,textColor:Int,backgroundColor:Int,action:()->Unit)=Button(this).apply{text=textValue;isAllCaps=false;textSize=16f;typeface=Typeface.DEFAULT_BOLD;setTextColor(textColor);background=rounded(backgroundColor,13);minHeight=dp(52);setPadding(dp(14),0,dp(14),0);setOnClickListener{action()}}
    private fun rounded(color:Int,radius:Int,stroke:Int?=null)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat();if(stroke!=null)setStroke(dp(1),stroke)}
    private fun gradient(colors:IntArray,radius:Int)=GradientDrawable(GradientDrawable.Orientation.TL_BR,colors).apply{cornerRadius=dp(radius).toFloat()}
    private fun fullParams(top:Int=0,bottom:Int=0)=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(top);bottomMargin=dp(bottom)}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()

    companion object{
        private const val SMS_PERMISSION_REQUEST=1201
        private const val PAGE_HOME=0
        private const val PAGE_SETTINGS=1
        private const val PAGE_ABOUT=2
        private val BACKGROUND=Color.rgb(244,249,253)
        private val PRIMARY=Color.rgb(40,120,185)
        private val BLUE_DARK=Color.rgb(35,105,164)
        private val BLUE_TEXT=Color.rgb(70,101,130)
        private val BLUE_TINT=Color.rgb(235,246,255)
        private val BLUE_BORDER=Color.rgb(207,228,246)
        private val INPUT_BACKGROUND=Color.rgb(248,252,255)
        private val INK=Color.rgb(30,53,76)
        private val MUTED=Color.rgb(105,126,147)
        private val SUCCESS=Color.rgb(47,166,122)
        private val WARNING=Color.rgb(220,139,30)
        private val DANGER=Color.rgb(203,70,70)
        private val PAUSED=Color.rgb(121,135,129)
    }
}
