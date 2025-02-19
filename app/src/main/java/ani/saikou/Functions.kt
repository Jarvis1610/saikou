package ani.saikou

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.DatePickerDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources.getSystem
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.*
import android.text.InputFilter
import android.text.Spanned
import android.util.AttributeSet
import android.view.*
import android.view.animation.*
import android.widget.AutoCompleteTextView
import android.widget.DatePicker
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.MutableLiveData
import androidx.multidex.MultiDex
import androidx.multidex.MultiDexApplication
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.saikou.anilist.Anilist
import ani.saikou.anilist.Genre
import ani.saikou.anime.Episode
import ani.saikou.databinding.ItemCountDownBinding
import ani.saikou.media.Media
import ani.saikou.media.Source
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.exoplayer2.ui.DefaultTimeBar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nl.joery.animatedbottombar.AnimatedBottomBar
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.io.*
import java.text.DateFormatSymbols
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


const val STATE_RESUME_WINDOW = "resumeWindow"
const val STATE_RESUME_POSITION = "resumePosition"
const val STATE_PLAYER_FULLSCREEN = "playerFullscreen"
const val STATE_PLAYER_PLAYING = "playerOnPlay"
const val buildDebug = true

var statusBarHeight  = 0
var navBarHeight = 0
val Int.dp: Float get() = (this / getSystem().displayMetrics.density)
val Float.px: Int get() = (this * getSystem().displayMetrics.density).toInt()

lateinit var bottomBar: AnimatedBottomBar
var selectedOption = 1

object Refresh{
    fun all(){
        for (i in activity){
            activity[i.key]!!.postValue(true)
        }
    }
    val activity = mutableMapOf<Int,MutableLiveData<Boolean>>()
}

fun currActivity():Activity?{
    return App.currentActivity()
}

var loadMedia:Int?=null
var loadIsMAL=false

fun logger(e:Any?,print:Boolean=true){
    if(buildDebug && print)
        println(e)
}

fun saveData(fileName:String,data:Any,activity: Activity?=null){
    try{
        val a = activity?: currActivity()
        if (a!=null) {
            val fos: FileOutputStream = a.openFileOutput(fileName, Context.MODE_PRIVATE)
            val os = ObjectOutputStream(fos)
            os.writeObject(data)
            os.close()
            fos.close()
        }
    }catch (e:Exception){
        toastString(e.toString())
    }
}

@Suppress("UNCHECKED_CAST")
fun <T> loadData(fileName:String,activity: Activity?=null): T? {
    val a = activity?: currActivity()
    try{
    if (a?.fileList() != null)
        if (fileName in a.fileList()){
            val fileIS: FileInputStream = a.openFileInput(fileName)
            val objIS = ObjectInputStream(fileIS)
            val data = objIS.readObject() as T
            objIS.close()
            fileIS.close()
            return data
        }
    }catch (e:Exception){
        toastString("Error loading data $fileName")
    }
    return null
}

fun initActivity(a: Activity) {
    val window = a.window
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    if (statusBarHeight==0) {
        val windowInsets = ViewCompat.getRootWindowInsets(window.decorView.findViewById(android.R.id.content))
        if (windowInsets!=null) {
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarHeight = insets.top
            navBarHeight = insets.bottom
        }
    }
}

open class BottomSheetDialogFragment : BottomSheetDialogFragment() {
    override fun onStart() {
        super.onStart()
        if (this.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) {
            val behavior = BottomSheetBehavior.from(requireView().parent as View)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }
}

fun isOnline(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    try{
    val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    if (capabilities != null) {
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                logger("Device on Cellular")
                return true
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                logger("Device on Wifi")
                return true
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                logger("Device on Ethernet, TF man?")
                return true
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->{
                logger("Device on VPN")
                return true
            }
        }
    }
    }catch (e:Exception){
        toastString(e.toString())
    }
    return false
}

fun startMainActivity(activity: Activity){
    activity.finishAffinity()
    activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
}

data class FuzzyDate(
    val year: Int?=null,
    val month: Int?=null,
    val day: Int?=null,
): Serializable{
    override fun toString():String{
        val a = if (month!=null) DateFormatSymbols().months[month - 1] else ""
        return (if (day!=null) "$day " else "")+ a +(if (year!=null) ", $year" else "")
    }
    fun getToday():FuzzyDate{
        val cal = Calendar.getInstance()
        return FuzzyDate(cal.get(Calendar.YEAR),cal.get(Calendar.MONTH)+1,cal.get(Calendar.DAY_OF_MONTH))
    }
    fun getEpoch():Long{
        val cal = Calendar.getInstance()
        cal.set(year?:cal.get(Calendar.YEAR),month?:cal.get(Calendar.MONTH),day?:cal.get(Calendar.DAY_OF_MONTH))
        return cal.timeInMillis
    }
}

class DatePickerFragment(activity: Activity, var date: FuzzyDate=FuzzyDate().getToday()) : DialogFragment(), DatePickerDialog.OnDateSetListener {
    var dialog :DatePickerDialog
    init{
        val c = Calendar.getInstance()
        val year = date.year?:c.get(Calendar.YEAR)
        val month= if (date.month!=null) date.month!! -1 else c.get(Calendar.MONTH)
        val day = date.day?:c.get(Calendar.DAY_OF_MONTH)
        dialog = DatePickerDialog(activity, this, year, month, day)
    }

    override fun onDateSet(view: DatePicker, year: Int, month: Int, day: Int) {
        date = FuzzyDate(year,month+1,day)
    }
}

class InputFilterMinMax(private val min: Double, private val max: Double,private val status:AutoCompleteTextView?=null) : InputFilter {
    override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
        try {
            val input = (dest.toString() + source.toString()).toDouble()
            if (isInRange(min, max, input)) return null
        } catch (nfe: NumberFormatException) {
            nfe.printStackTrace()
        }
        return ""
    }

    @SuppressLint("SetTextI18n")
    private fun isInRange(a: Double, b: Double, c: Double): Boolean {
        if (c==b) {
            status?.setText("COMPLETED",false)
            status?.parent?.requestLayout()
        }
        return if (b > a) c in a..b else c in b..a
    }
}

fun getMalMedia(media:Media) : Media{
    try {
        if(media.anime!=null) {
            val res = Jsoup.connect("https://myanimelist.net/anime/${media.idMAL}").ignoreHttpErrors(true).get()
            val a = res.select(".title-english").text()
            media.nameMAL = if (a!="") a else res.select(".title-name").text()
            media.typeMAL = if(res.select("div.spaceit_pad > a").isNotEmpty()) res.select("div.spaceit_pad > a")[0].text() else null
            media.anime.op = arrayListOf()
            res.select(".opnening > table > tbody > tr").forEach {
                val text = it.text()
                if(!text.contains("Help improve our database"))
                    media.anime.op.add(it.text())
            }
            media.anime.ed = arrayListOf()
            res.select(".ending > table > tbody > tr").forEach {
                val text = it.text()
                if(!text.contains("Help improve our database"))
                    media.anime.ed.add(it.text())
            }

        }else{
            val res = Jsoup.connect("https://myanimelist.net/manga/${media.idMAL}").ignoreHttpErrors(true).get()
            val b = res.select(".title-english").text()
            val a = res.select(".h1-title").text().removeSuffix(b)
            media.nameMAL = a
            media.typeMAL = if(res.select("div.spaceit_pad > a").isNotEmpty()) res.select("div.spaceit_pad > a")[0].text() else null
        }
    } catch (e:Exception){
        toastString(e.message)
    }
    return media
}

class ZoomOutPageTransformer : ViewPager2.PageTransformer {
    override fun transformPage(view: View, position: Float) {
        if (position == 0.0f) {
            setAnimation(view.context,view,300, floatArrayOf(1.3f,1f,1.3f,1f))
            ObjectAnimator.ofFloat(view,"alpha",0f,1.0f).setDuration(200).start()
        }
    }
}

fun setAnimation(context: Context,viewToAnimate: View, duration:Long=150,list: FloatArray= floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f)) {
    val anim = ScaleAnimation(list[0], list[1], list[2], list[3], Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f)
    anim.duration = duration
    anim.setInterpolator(context,R.anim.over_shoot)
    viewToAnimate.startAnimation(anim)
}


class FadingEdgeRecyclerView : RecyclerView {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun isPaddingOffsetRequired(): Boolean {
        return !clipToPadding
    }

    override fun getLeftPaddingOffset(): Int {
        return if (clipToPadding) 0 else -paddingLeft
    }

    override fun getTopPaddingOffset(): Int {
        return if (clipToPadding) 0 else -paddingTop
    }

    override fun getRightPaddingOffset(): Int {
        return if (clipToPadding) 0 else paddingRight
    }

    override fun getBottomPaddingOffset(): Int {
        return if (clipToPadding) 0 else paddingBottom
    }
}

fun levenshtein(lhs : CharSequence, rhs : CharSequence) : Int {
    if(lhs == rhs) { return 0 }
    if(lhs.isEmpty()) { return rhs.length }
    if(rhs.isEmpty()) { return lhs.length }

    val lhsLength = lhs.length + 1
    val rhsLength = rhs.length + 1

    var cost = Array(lhsLength) { it }
    var newCost = Array(lhsLength) { 0 }

    for (i in 1 until rhsLength) {
        newCost[0] = i

        for (j in 1 until lhsLength) {
            val match = if(lhs[j - 1] == rhs[i - 1]) 0 else 1

            val costReplace = cost[j - 1] + match
            val costInsert = cost[j] + 1
            val costDelete = newCost[j - 1] + 1

            newCost[j] = min(min(costInsert, costDelete), costReplace)
        }

        val swap = cost
        cost = newCost
        newCost = swap
    }

    return cost[lhsLength - 1]
}

fun ArrayList<Source>.sortByTitle(string: String){
    val temp : MutableMap<Int,Int> = mutableMapOf()
    for (i in 0 until this.size){
        temp[i] = levenshtein(string.lowercase(),this[i].name.lowercase())
    }
    val c = temp.toList().sortedBy{ (_, value) -> value}.toMap()
    val a = ArrayList(c.keys.toList().subList(0,min(this.size,25)))
    val b = c.values.toList().subList(0,min(this.size,25))
    for( i in b.indices.reversed()){
        if(b[i]>18 && i<a.size) a.removeAt(i)
    }
    val temp2 = arrayListOf<Source>()
    temp2.addAll(this)
    this.clear()
    for (i in a.indices){
        this.add(temp2[a[i]])
    }
}

fun String.findBetween(a:String,b:String):String?{
    val start = this.indexOf(a)
    val end = if(start!=-1) this.indexOf(b,start) else return null
    return if(end!=-1) this.subSequence(start,end).removePrefix(a).removeSuffix(b).toString() else null
}

fun ImageView.loadImage(url:String?,size:Int=0,headers: MutableMap<String, String>?=null){
    if(!url.isNullOrEmpty()) {
        try{
            val glideUrl = GlideUrl(url){ headers?: mutableMapOf() }
            Glide.with(this).load(glideUrl).diskCacheStrategy(DiskCacheStrategy.ALL).transition(withCrossFade()).override(size).into(this)
        }catch (e:Exception){
            logger(e.localizedMessage)
        }
    }
}

class SafeClickListener(
    private var defaultInterval: Int = 1000,
    private val onSafeCLick: (View) -> Unit
) : View.OnClickListener {

    private var lastTimeClicked: Long = 0

    override fun onClick(v: View) {
        if (SystemClock.elapsedRealtime() - lastTimeClicked < defaultInterval) {
            return
        }
        lastTimeClicked = SystemClock.elapsedRealtime()
        onSafeCLick(v)
    }
}

fun View.setSafeOnClickListener(onSafeClick: (View) -> Unit) {
    val safeClickListener = SafeClickListener {
        onSafeClick(it)
    }
    setOnClickListener(safeClickListener)
}

fun getSize(url: String,headers:MutableMap<String,String>?=null):Double?{
    return try { Jsoup.connect(url)
        .ignoreContentType(true)
        .ignoreHttpErrors(true).timeout(1000)
        .followRedirects(true)
        .headers(headers?: mutableMapOf())
        .method(Connection.Method.HEAD)
        .execute().header("Content-Length")?.toDouble()?.div(1048576)
    } catch (e:Exception){
//        logger(e)
        null
    }
}


class App: MultiDexApplication() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    init { instance = this }

    val mFTActivityLifecycleCallbacks = FTActivityLifecycleCallbacks()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(mFTActivityLifecycleCallbacks)
    }

    companion object {
        private var instance: App? = null
        fun currentActivity(): Activity? {
            return instance!!.mFTActivityLifecycleCallbacks.currentActivity
        }
    }
}

class FTActivityLifecycleCallbacks: Application.ActivityLifecycleCallbacks {
    var currentActivity: Activity? = null
    override fun onActivityCreated(p0: Activity, p1: Bundle?) {}
    override fun onActivityStarted(p0: Activity) {}
    override fun onActivityResumed(p0: Activity) { currentActivity = p0 }
    override fun onActivityPaused(p0: Activity) {}
    override fun onActivityStopped(p0: Activity) {}
    override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {}
    override fun onActivityDestroyed(p0: Activity) {}
}

@SuppressLint("ViewConstructor")
class ExtendedTimeBar(
    context: Context,
    attrs: AttributeSet?
) : DefaultTimeBar(context, attrs) {
    private var enabled = false
    private var forceDisabled = false
    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        super.setEnabled(!forceDisabled && this.enabled)
    }

    fun setForceDisabled(forceDisabled: Boolean) {
        this.forceDisabled = forceDisabled
        isEnabled = enabled
    }
}

abstract class DoubleClickListener : GestureDetector.SimpleOnGestureListener() {
    private var timer: Timer? = null //at class level;
    private val delay:Long = 400

    override fun onSingleTapUp(e: MotionEvent?): Boolean {
        processSingleClickEvent(e)
        return super.onSingleTapUp(e)
    }

    override fun onLongPress(e: MotionEvent?) {
        processLongClickEvent(e)
        super.onLongPress(e)
    }

    override fun onDoubleTap(e: MotionEvent?): Boolean {
        processDoubleClickEvent(e)
        return super.onDoubleTap(e)
    }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent?, distanceX: Float, distanceY: Float): Boolean {
        onScrollYClick(distanceY)
        return super.onScroll(e1, e2, distanceX, distanceY)
    }

    private fun processSingleClickEvent(e:MotionEvent?) {
        val handler = Handler(Looper.getMainLooper())
        val mRunnable = Runnable {
            onSingleClick(e) //Do what ever u want on single click
        }
        val timerTask: TimerTask = object : TimerTask() {
            override fun run() {
                handler.post(mRunnable)
            }
        }
        timer = Timer()
        timer!!.schedule(timerTask, delay)
    }

    private fun processDoubleClickEvent(e: MotionEvent?) {
        if (timer != null) {
            timer!!.cancel() //Cancels Running Tasks or Waiting Tasks.
            timer!!.purge() //Frees Memory by erasing cancelled Tasks.
        }
        onDoubleClick(e) //Do what ever u want on Double Click
    }

    private fun processLongClickEvent(e: MotionEvent?) {
        if (timer != null) {
            timer!!.cancel() //Cancels Running Tasks or Waiting Tasks.
            timer!!.purge() //Frees Memory by erasing cancelled Tasks.
        }
        onLongClick(e) //Do what ever u want on Double Click
    }

    open fun onSingleClick(event: MotionEvent?) {}
    abstract fun onDoubleClick(event: MotionEvent?)
    open fun onScrollYClick(y:Float) {}
    open fun onLongClick(event:MotionEvent?) {}
}

fun View.circularReveal(x: Int, y: Int,time:Long) {
    ViewAnimationUtils.createCircularReveal(this, x, y, 0f, max(height, width).toFloat()).setDuration(time).start()
}

fun openLinkInBrowser(link:String?){
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        currActivity()?.startActivity(intent)
    }catch (e:Exception){
        toastString(e.toString())
    }
}

fun download(activity: Activity, episode:Episode, animeTitle:String){
    val manager = activity.getSystemService(AppCompatActivity.DOWNLOAD_SERVICE) as DownloadManager
    val stream = episode.streamLinks[episode.selectedStream]?:return
    val uri = Uri.parse(stream.quality[episode.selectedQuality].url)
    val regex = "[\\\\/:*?\"<>|]".toRegex()
    val aTitle = animeTitle.replace(regex, "")
    val request: DownloadManager.Request = DownloadManager.Request(uri)
    if(stream.headers!=null) {
        stream.headers.forEach{
            request.addRequestHeader(it.key,it.value)
        }
    }
    CoroutineScope(Dispatchers.IO).launch {
        try{
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val direct = File(Environment.DIRECTORY_DOWNLOADS + "/Saikou/${aTitle}/")
        if (!direct.exists()) direct.mkdirs()

        var title = "Episode ${episode.number} ${if (episode.title != null) " - ${episode.title}" else ""}"
        title = title.replace(regex,"")

        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            "/Saikou/${aTitle}/$title (${stream.quality[episode.selectedQuality].quality}).mp4"
        )
        request.setTitle("$title : $aTitle")
        manager.enqueue(request)
        toastString("Started Downloading\n$title : $aTitle")
        } catch (e:SecurityException){
            toastString("Please give permission to access Media from Settings, & Try again.")
        }
        catch (e:Exception){
            toastString(e.toString())
        }
    }
}

fun updateAnilistProgress(id:Int,number:String){
    if(Anilist.userid!=null) {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val a = number.toFloatOrNull()?.roundToInt()
            Anilist.mutation.editList(id, a, status = "CURRENT")
            toastString("Setting progress to $a")
            Refresh.all()
        }
    }else{
        toastString("Please Login into anilist account!")
    }
}

class MediaPageTransformer : ViewPager2.PageTransformer {
    private fun parallax(view:View, position: Float, speed:Float){
        if (position > -1 && position < 1) {
            val width = view.width.toFloat()
            view.translationX = -(position * width * speed)
        }
    }

    override fun transformPage(view: View, position: Float) {

        val bannerContainer = view.findViewById<View>(R.id.itemCompactBanner)
        parallax(bannerContainer,position,0.8f)
        val titleContainer = view.findViewById<View>(R.id.itemCompactTitleContainer)
        parallax(titleContainer,position,0.5f)
    }
}

class NoGestureSubsamplingImageView(context: Context?, attr: AttributeSet?) :
    SubsamplingScaleImageView(context, attr) {
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return false
    }
}

fun copyToClipboard(string: String,toast:Boolean=true){
    val activity = currActivity()?:return
    val clipboard = getSystemService(activity,ClipboardManager::class.java)
    val clip = ClipData.newPlainText("label", string)
    clipboard?.setPrimaryClip(clip)
    if(toast) toastString("Copied \"$string\"")
}

@SuppressLint("SetTextI18n")
fun countDown(media: Media, view: ViewGroup){
    if (media.anime?.nextAiringEpisode != null && media.anime.nextAiringEpisodeTime != null && (media.anime.nextAiringEpisodeTime!! - System.currentTimeMillis() / 1000) <= 86400 * 7.toLong()) {
        val v = ItemCountDownBinding.inflate(LayoutInflater.from(view.context), view, false)
        view.addView(v.root,0)
        v.mediaCountdownText.text = "Episode ${media.anime.nextAiringEpisode!!+1} will be released in"
        object : CountDownTimer((media.anime.nextAiringEpisodeTime!! + 10000) * 1000 - System.currentTimeMillis(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val a = millisUntilFinished / 1000
                v.mediaCountdown.text = "${a / 86400} days ${a % 86400 / 3600} hrs ${a % 86400 % 3600 / 60} mins ${a % 86400 % 3600 % 60} secs"
            }
            override fun onFinish() {
                v.mediaCountdownContainer.visibility = View.GONE
                toastString("Congrats Vro")
            }
        }.start()
    }
}

fun MutableMap<String, Genre>.checkId(id:Int):Boolean{
    this.forEach {
        if (it.value.id == id){
            return false
        }
    }
    return true
}

fun MutableMap<String, Genre>.checkTime(genre:String):Boolean{
    if(containsKey(genre))
        return (System.currentTimeMillis()-get(genre)!!.time) >= (1000*60*60*24*7)
    return true
}

val setSlideIn = AnimationSet(false).apply {
    var animation: Animation = AlphaAnimation(0.0f, 1.0f)
    animation.duration = 500
    animation.interpolator = AccelerateDecelerateInterpolator()
    addAnimation(animation)

    animation = TranslateAnimation(
        Animation.RELATIVE_TO_SELF, 1.0f,
        Animation.RELATIVE_TO_SELF, 0f,
        Animation.RELATIVE_TO_SELF, 0.0f,
        Animation.RELATIVE_TO_SELF, 0f
    )

    animation.duration = 750
    animation.interpolator = OvershootInterpolator(1.1f)
    addAnimation(animation)
}

val setSlideUp = AnimationSet(false).apply {
    var animation: Animation = AlphaAnimation(0.0f, 1.0f)
    animation.duration = 500
    animation.interpolator = AccelerateDecelerateInterpolator()
    addAnimation(animation)

    animation = TranslateAnimation(
        Animation.RELATIVE_TO_SELF, 0.0f,
        Animation.RELATIVE_TO_SELF, 0f,
        Animation.RELATIVE_TO_SELF, 1.0f,
        Animation.RELATIVE_TO_SELF, 0f
    )

    animation.duration = 750
    animation.interpolator = OvershootInterpolator(1.1f)
    addAnimation(animation)
}

class EmptyAdapter(private val count:Int):RecyclerView.Adapter<RecyclerView.ViewHolder>(){
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return EmptyViewHolder(View(parent.context))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}

    override fun getItemCount(): Int = count

    inner class EmptyViewHolder(view: View):RecyclerView.ViewHolder(view)
}

fun toastString(s: String?){
    if(s!=null) {
        currActivity()?.apply{
            runOnUiThread {
                val snackBar = Snackbar.make(window.decorView.findViewById(android.R.id.content), s, Snackbar.LENGTH_LONG)
                snackBar.view.translationY = -(navBarHeight.dp + 32f)
                snackBar.view.setOnClickListener {
                    snackBar.dismiss()
                }
                snackBar.view.setOnLongClickListener {
                    copyToClipboard(s,false)
                    true
                }
                snackBar.show()
            }
        }
        logger(s)
    }
}